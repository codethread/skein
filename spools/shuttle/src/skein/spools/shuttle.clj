(ns skein.spools.shuttle
  "Userland spool that spawns coding agents in user-chosen harnesses.

  An agent run is a strand carrying `shuttle/*` attributes; creating the strand
  is the API. The shuttle listens for graph mutations, spawns each pending run
  the moment its strand becomes ready (`depends-on` readiness is the only
  scheduler), captures the harness process output back onto the run strand, and
  closes the strand so dependent runs unblock. Everything is asynchronous by
  default; `await` is the opt-in blocking convenience.

  Runs survive weaver crashes because the strands are durable: `reconcile!`
  respawns still-active running strands on install, bounded by
  `shuttle/max-attempts`. Run memory is append-only note strands linked by
  `notes` annotation edges plus `shuttle/note-for` attributes.

  The whole spool composes public surfaces (`skein.weaver.api` inside the
  weaver JVM) and owns no privileged runtime state. Low-trust CLI agents drive
  it through `strand op agent ...`; `strand op agent about` is the complete,
  harness-agnostic manual."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.weaver.api :as api]
            [skein.weaver.runtime :as runtime])
  (:import [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.time Instant]))

(defonce ^:private harness-registry (atom {}))

;; run-id -> {:phase :claimed|:running, :process Process} — weaver-lifetime
;; ownership of live child processes; survives config reload via defonce.
(defonce ^:private in-flight (atom {}))

(def ^:private default-max-attempts 3)

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- rt []
  (or @runtime/current-runtime
      (fail! "Shuttle requires an in-process weaver runtime" {})))

(defn- sattr
  "Read the `shuttle/<k>` attribute from a normalized strand."
  [strand k]
  (get-in strand [:attributes (keyword "shuttle" k)]))

(defn- attr
  "Read a normalized strand attribute by exact keyword."
  [strand k]
  (get-in strand [:attributes k]))

(defn- now [] (str (Instant/now)))

;; ---------------------------------------------------------------------------
;; Harness registry

(defn- harness-key [name]
  (cond
    (keyword? name) name
    (symbol? name) (keyword name)
    (and (string? name) (not (str/blank? name))) (keyword name)
    :else (fail! "Harness name must be a keyword, symbol, or non-blank string" {:name name})))

(def ^:private harness-def-keys #{:argv :parse :prompt-via :preamble? :env :cwd :doc})
(def ^:private alias-def-keys #{:alias-of :extra-args :prompt-prefix :doc})
(def ^:private parse-strategies #{:raw :claude-json :pi-json})

(defn defharness!
  "Register a harness definition under `name`.

  A harness def is plain data: required `:argv` (vector of strings; the run
  prompt is appended per `:prompt-via`, default `:arg`), optional `:parse`
  strategy (:raw, :claude-json, :pi-json — default :raw), `:prompt-via`
  (:arg or :stdin), `:preamble?` (default true; when false the shuttle run
  preamble is not injected), `:env` map, `:cwd`, and `:doc`."
  [name def]
  (let [key (harness-key name)]
    (when-not (map? def)
      (fail! "Harness def must be a map" {:harness key :def def}))
    (when-let [unknown (seq (remove harness-def-keys (keys def)))]
      (fail! "Harness def contains unknown keys" {:harness key :keys (vec unknown)}))
    (when-not (and (vector? (:argv def)) (seq (:argv def)) (every? string? (:argv def)))
      (fail! "Harness :argv must be a non-empty vector of strings" {:harness key :argv (:argv def)}))
    (when-let [parse (:parse def)]
      (when-not (parse-strategies parse)
        (fail! "Unknown harness :parse strategy" {:harness key :parse parse :supported parse-strategies})))
    (swap! harness-registry assoc key def)
    {:harness key :def def}))

(defn defalias!
  "Register `name` as an alias layered over another harness or alias.

  Alias defs take `:alias-of` (required), `:extra-args` appended to the base
  argv before the prompt, `:prompt-prefix` prepended to the run prompt, and
  `:doc`. Aliases are how userland exposes its own named agent types."
  [name def]
  (let [key (harness-key name)]
    (when-not (map? def)
      (fail! "Alias def must be a map" {:alias key :def def}))
    (when-let [unknown (seq (remove alias-def-keys (keys def)))]
      (fail! "Alias def contains unknown keys" {:alias key :keys (vec unknown)}))
    (when-not (:alias-of def)
      (fail! "Alias def requires :alias-of" {:alias key :def def}))
    (swap! harness-registry assoc key def)
    {:alias key :def def}))

(defn resolve-harness
  "Return the effective harness definition for `name`, flattening alias layers."
  [name]
  (loop [key (harness-key name)
         seen #{}
         extra-args []
         prompt-prefix ""]
    (when (contains? seen key)
      (fail! "Harness alias chain contains a cycle" {:harness (harness-key name) :cycle (conj seen key)}))
    (let [def (or (get @harness-registry key)
                  (fail! "Harness not found" {:harness key :available (sort (keys @harness-registry))}))]
      (if-let [base (:alias-of def)]
        (recur (harness-key base)
               (conj seen key)
               (into (vec (:extra-args def)) extra-args)
               (str (:prompt-prefix def) prompt-prefix))
        (assoc def
               :name key
               :extra-args extra-args
               :prompt-prefix prompt-prefix)))))

(defn harnesses
  "Return registered harness and alias metadata ordered by name."
  []
  (mapv (fn [[key def]]
          (cond-> {:name (name key)
                   :kind (if (:alias-of def) "alias" "harness")}
            (:alias-of def) (assoc :alias-of (name (harness-key (:alias-of def))))
            (:argv def) (assoc :argv (:argv def))
            (:doc def) (assoc :doc (:doc def))))
        (sort-by key @harness-registry)))

(defn register-default-harnesses!
  "Register the shipped harness definitions, keeping any existing entries."
  []
  (let [defaults
        {:claude {:argv ["claude" "-p" "--output-format" "json" "--dangerously-skip-permissions"]
                  :parse :claude-json
                  :doc "Claude Code headless. Skips permission prompts so the run can drive the strand CLI; redefine with your own argv to tighten."}
         :pi {:argv ["pi" "-p"]
              :parse :raw
              :doc "pi headless; stdout carries only the final assistant message, so :raw parses cleanly. Redefine with --mode json and :parse :pi-json to also capture shuttle/session-id."}
         :sh {:argv ["sh" "-c"]
              :parse :raw
              :preamble? false
              :doc "Shell harness: the prompt is the script, stdout is the result. Intended for tests and plumbing."}}]
    (doseq [[key def] defaults]
      (when-not (contains? @harness-registry key)
        (defharness! key def)))
    (harnesses)))

;; ---------------------------------------------------------------------------
;; Run vocabulary and preamble

(def run-query
  "Query form selecting all shuttle run strands."
  [:= [:attr "shuttle/run"] "true"])

(def ^:private pending-query
  [:and [:= :state "active"] run-query [:= [:attr "shuttle/phase"] "pending"]])

(def ^:private running-query
  [:and [:= :state "active"] run-query [:= [:attr "shuttle/phase"] "running"]])

(defn- workspace-dir []
  (get-in (rt) [:metadata :config-dir]))

(defn- state-root
  "Return the XDG state root above this weaver's state dir."
  []
  (-> (io/file (get-in (rt) [:metadata :state-dir]))
      .getParentFile .getParentFile .getParentFile
      .getCanonicalPath))

(defn- pinned-strand
  "Return the fully pinned strand invocation prefix for spawned agents.

  Harness shells may re-source user dotfiles and override ambient env, so the
  state root that selects the mill/weaver must ride inside the command text,
  not the inherited environment."
  []
  (str "env XDG_STATE_HOME=" (state-root) " strand --workspace " (workspace-dir)))

(defn- preamble [run-id prompt-prefix]
  (let [cmd (pinned-strand)]
    (str "[shuttle run context]\n"
         "You are a headless subagent run managed by the Skein shuttle spool.\n"
         "run-id: " run-id "\n"
         "- Every strand command MUST be invoked exactly as: " cmd " <command...>\n"
         "  (the env prefix and --workspace flag are both required; ambient env is unreliable)\n"
         "- Complete manual for spawning/awaiting/notes: " cmd " op agent about\n"
         "- Leave durable notes for peers or successors: " cmd " op agent note <strand-id> \"<text>\" --by " run-id "\n"
         "- Spawn subagents (async, returns a run id): " cmd " op agent spawn --harness <name> --prompt \"...\" --spawned-by " run-id "\n"
         "- Wait for children: " cmd " op agent await <child-run-id>\n"
         "- Never start or stop mills or weavers, and never create or edit workspace config"
         " (init.clj, spools.edn, config.json, libs.edn): if strand commands fail, report the"
         " exact error as your result instead of repairing the environment.\n"
         "- Your final message is captured automatically as this run's result; end with a clear, self-contained report for your caller.\n"
         "[task]\n"
         prompt-prefix)))

;; ---------------------------------------------------------------------------
;; Output parsing

(defn- parse-claude-json [stdout]
  (let [data (json/read-str (str/trim stdout))]
    (when-not (map? data)
      (fail! "claude JSON output was not an object" {:output (subs stdout 0 (min 400 (count stdout)))}))
    {:result (or (get data "result") "")
     :session-id (get data "session_id")}))

(defn- parse-pi-json
  "Parse pi --mode json output: a stream of JSON event lines; the assistant
  text is accumulated from message events, and the last line may be a summary."
  [stdout]
  (let [lines (->> (str/split-lines stdout)
                   (map str/trim)
                   (remove str/blank?))
        events (keep (fn [line]
                       (try
                         (let [v (json/read-str line)]
                           (when (map? v) v))
                         (catch Exception _ nil)))
                     lines)
        text-of (fn [message]
                  (->> (get message "content")
                       (filter #(= "text" (get % "type")))
                       (map #(get % "text"))
                       (str/join "\n")))
        assistant-messages (filter #(= "assistant" (get-in % ["message" "role"])) events)]
    (if (seq events)
      {:result (or (some-> (last assistant-messages) (get "message") text-of not-empty)
                   (some #(get % "result") (reverse events))
                   (str/trim stdout))
       :session-id (some #(or (when (= "session" (get % "type")) (get % "id"))
                              (get % "session_id")
                              (get % "sessionId"))
                         events)}
      {:result (str/trim stdout)})))

(defn- parse-output [strategy stdout]
  (case (or strategy :raw)
    :raw {:result (str/trim stdout)}
    :claude-json (parse-claude-json stdout)
    :pi-json (parse-pi-json stdout)))

;; ---------------------------------------------------------------------------
;; Spawn engine

(defn- log-dir []
  (doto (io/file (get-in (rt) [:metadata :state-dir]) "shuttle")
    (.mkdirs)))

(defn- update-run! [id attributes patch]
  (api/update (rt) id (merge patch {:attributes attributes})))

(defn- mark-failed! [id error]
  (update-run! id {"shuttle/phase" "failed"
                   "shuttle/error" error
                   "shuttle/finished-at" (now)}
               {}))

(defn- effective-prompt [harness run]
  (let [prompt (str (:prompt-prefix harness) (sattr run "prompt"))]
    (if (false? (:preamble? harness))
      prompt
      (str (preamble (:id run) "") prompt))))

(defn- build-argv [harness prompt]
  (let [argv (into (:argv harness) (:extra-args harness))]
    (if (= :stdin (:prompt-via harness))
      argv
      (conj argv prompt))))

(defn- start-process! [argv {:keys [cwd env out-file err-file stdin]}]
  (let [pb (ProcessBuilder. ^java.util.List argv)]
    (.directory pb (io/file cwd))
    (.redirectOutput pb (ProcessBuilder$Redirect/to out-file))
    (.redirectError pb (ProcessBuilder$Redirect/to err-file))
    (let [environment (.environment pb)]
      (doseq [[k v] env]
        (.put environment (str k) (str v))))
    (let [process (.start pb)]
      (with-open [in (.getOutputStream process)]
        (when stdin
          (.write in (.getBytes ^String stdin "UTF-8"))))
      process)))

(defn- read-file-safe [file]
  (if (.exists ^java.io.File file) (slurp file) ""))

(defn- tail [s n]
  (if (> (count s) n) (subs s (- (count s) n)) s))

(declare scan!)

(defn- finish-run! [id process harness out-file err-file]
  (let [exit (.waitFor ^Process process)
        stdout (read-file-safe out-file)]
    (swap! in-flight dissoc id)
    (if (zero? exit)
      (let [{:keys [result session-id parse-error]}
            (try
              (parse-output (:parse harness) stdout)
              (catch Exception e
                {:result (str/trim stdout)
                 :parse-error (str (ex-message e))}))]
        (update-run! id (cond-> {"shuttle/phase" "done"
                                 "shuttle/exit-code" exit
                                 "shuttle/result" (or result "")
                                 "shuttle/finished-at" (now)}
                          session-id (assoc "shuttle/session-id" session-id)
                          parse-error (assoc "shuttle/parse-error" parse-error))
                     {:state "closed"}))
      (let [stderr (str/trim (read-file-safe err-file))
            detail (if (str/blank? stderr) (str/trim stdout) stderr)]
        (mark-failed! id (str "harness exited " exit ": " (tail detail 2000)))))))

(defn- process-start-instant
  "Return the OS start instant string for a live process, or nil when unknown."
  [^Process process]
  (some-> (.info process) (.startInstant) (.orElse nil) str))

(defn- launch-run! [run]
  (let [id (:id run)
        process-ref (atom nil)]
    (try
      (let [harness (resolve-harness (sattr run "harness"))
            prompt (effective-prompt harness run)
            argv (build-argv harness prompt)
            dir (log-dir)
            out-file (io/file dir (str id ".out"))
            err-file (io/file dir (str id ".err"))
            attempt (inc (or (sattr run "attempt") 0))]
        ;; durably mark the run running before the process exists: a crash in
        ;; the gap respawns a strand whose process never started, instead of
        ;; leaving a pending strand with a live orphan process.
        (update-run! id {"shuttle/phase" "running"
                         "shuttle/attempt" attempt
                         "shuttle/log" (.getPath out-file)
                         "shuttle/started-at" (now)}
                     {})
        (let [process (start-process! argv {:cwd (or (sattr run "cwd") (:cwd harness) (workspace-dir))
                                            :env (:env harness)
                                            :out-file out-file
                                            :err-file err-file
                                            :stdin (when (= :stdin (:prompt-via harness)) prompt)})]
          (reset! process-ref process)
          (swap! in-flight assoc id {:phase :running :process process})
          (update-run! id (cond-> {"shuttle/pid" (.pid ^Process process)}
                            (process-start-instant process)
                            (assoc "shuttle/pid-started-at" (process-start-instant process)))
                       {})
          (finish-run! id process harness out-file err-file)))
      (catch Throwable t
        (some-> ^Process @process-ref (.destroy))
        (swap! in-flight dissoc id)
        (mark-failed! id (str (ex-message t) (some->> (ex-data t) (str " "))))))))

(defn- claim! [id]
  (let [[old _new] (swap-vals! in-flight (fn [m]
                                           (if (contains? m id)
                                             m
                                             (assoc m id {:phase :claimed}))))]
    (not (contains? old id))))

(defn scan!
  "Spawn every ready pending run not already claimed. Returns claimed run ids."
  []
  (let [ready-runs (api/ready (rt) pending-query {})
        claimed (filterv (comp claim! :id) ready-runs)]
    (doseq [run claimed]
      (doto (Thread. #(launch-run! run) (str "shuttle-run-" (:id run)))
        (.setDaemon true)
        (.start)))
    (mapv :id claimed)))

(defn on-event
  "Weaver event handler: any graph mutation may unblock a pending run."
  [_event]
  (scan!))

;; ---------------------------------------------------------------------------
;; Crash reconciliation

(defn- run-process-handle
  "Return a verified live ProcessHandle for run's recorded pid, or nil.

  PIDs are recycled, so the handle is returned only when the OS start instant
  matches the one recorded at launch; an unverified pid must never be
  signalled — it could belong to an unrelated process."
  [run]
  (when-let [pid (sattr run "pid")]
    (when-let [handle (.orElse (ProcessHandle/of (long pid)) nil)]
      (when (.isAlive handle)
        (let [recorded (sattr run "pid-started-at")
              actual (some-> (.info handle) (.startInstant) (.orElse nil) str)]
          (when (and recorded actual (= recorded actual))
            handle))))))

(defn reconcile!
  "Recover running runs whose owning weaver died.

  Any active `running` run this weaver has no in-flight handle for was owned by
  a dead predecessor: its stale process is killed when its identity can be
  verified (pid plus recorded start instant), then the run is either reset to
  `pending` for respawn or marked `exhausted` (loudly, still active so
  dependents stay blocked) when `shuttle/max-attempts` is spent. Returns a
  summary of respawned and exhausted run ids."
  []
  (let [orphans (remove #(contains? @in-flight (:id %))
                        (api/list (rt) running-query {}))
        summary (reduce
                 (fn [acc run]
                   (let [id (:id run)
                         attempt (or (sattr run "attempt") 0)
                         max-attempts (or (sattr run "max-attempts") default-max-attempts)]
                     (some-> (run-process-handle run) (.destroy))
                     (if (>= attempt max-attempts)
                       (do (update-run! id {"shuttle/phase" "exhausted"
                                            "shuttle/error" (str "run exhausted " attempt " of " max-attempts
                                                                 " attempts after weaver crash")}
                                        {})
                           (update acc :exhausted conj id))
                       (do (update-run! id {"shuttle/phase" "pending"} {})
                           (update acc :respawned conj id)))))
                 {:respawned [] :exhausted []}
                 orphans)]
    (scan!)
    summary))

;; ---------------------------------------------------------------------------
;; Run creation, inspection, notes

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 (- n 1)) "…") s))

(defn spawn-run!
  "Create one agent-run strand; the engine spawns it when it becomes ready.

  Opts: `:harness` and `:prompt` required; optional `:title`, `:depends-on`
  (vector of strand ids), `:parent` and `:spawned-by` (each gets a parent-of
  edge to the run), `:cwd`, `:max-attempts`, and extra `:attrs`. Asynchronous:
  returns the created run strand immediately."
  [{:keys [harness prompt title depends-on parent spawned-by cwd max-attempts attrs]}]
  (when (str/blank? prompt)
    (fail! "Run :prompt must be non-blank" {}))
  (resolve-harness (or harness (fail! "Run :harness is required" {})))
  (let [parent-ids (distinct (remove nil? [parent spawned-by]))
        reserved (merge {"shuttle/run" "true"
                         "shuttle/harness" (name (harness-key harness))
                         "shuttle/prompt" prompt
                         "shuttle/phase" "pending"}
                        (when cwd {"shuttle/cwd" cwd})
                        (when spawned-by {"shuttle/spawned-by" spawned-by})
                        (when max-attempts {"shuttle/max-attempts" max-attempts}))]
    (when-let [collisions (seq (filter (set (keys reserved)) (keys (or attrs {}))))]
      (fail! "Run :attrs must not override shuttle control attributes" {:keys (vec collisions)}))
    ;; validate provenance targets before the run exists so a bad parent id
    ;; cannot leave a spawned run behind a thrown edge update
    (doseq [parent-id parent-ids]
      (when-not (api/show (rt) parent-id)
        (fail! "Run parent strand not found" {:id parent-id})))
    (let [run (api/add (rt) {:title (or title (truncate prompt 72))
                             :attributes (merge attrs reserved)
                             :edges (mapv (fn [dep] {:type "depends-on" :to dep}) (distinct (or depends-on [])))})]
      (doseq [parent-id parent-ids]
        (api/update (rt) parent-id {:edges [{:type "parent-of" :to (:id run)}]}))
      run)))

(defn- parent-of-sources
  "Return strand ids with a parent-of edge to `run-id`."
  [run-id]
  (->> (api/list (rt))
       (mapcat (fn [strand]
                 (for [edge (:edges (api/subgraph (rt) [(:id strand)]))
                       :when (and (= "parent-of" (:edge_type edge))
                                  (= run-id (:to_strand_id edge)))]
                   (:from_strand_id edge))))
       distinct
       vec))

(defn- run-for-target
  "Return the delegated target for `run`, excluding spawned-by provenance."
  [run]
  (or (attr run :treadle/gate)
      (let [spawned-by (sattr run "spawned-by")]
        (first (remove #(= spawned-by %) (parent-of-sources (:id run)))))))

(defn run-summary
  "Project a run strand into the compact summary shape the op surface returns."
  [run]
  (cond-> {:id (:id run)
           :title (:title run)
           :state (:state run)
           :phase (sattr run "phase")
           :harness (sattr run "harness")}
    (run-for-target run) (assoc :for (run-for-target run))
    (sattr run "result") (assoc :result (sattr run "result"))
    (sattr run "error") (assoc :error (sattr run "error"))
    (sattr run "session-id") (assoc :session-id (sattr run "session-id"))
    (sattr run "spawned-by") (assoc :spawned-by (sattr run "spawned-by"))
    (sattr run "attempt") (assoc :attempt (sattr run "attempt"))))

(defn runs
  "Return summaries of shuttle runs; opts may filter to `:active` or `:for`."
  ([] (runs {}))
  ([{:keys [active for]}]
   (let [summaries (mapv run-summary
                         (api/list (rt)
                                   (if active [:and [:= :state "active"] run-query] run-query)
                                   {}))]
     (if for
       (filterv #(= for (:for %)) summaries)
       summaries))))

(def ^:private terminal-phases #{"done" "failed" "exhausted"})

(defn- terminal? [run]
  (or (not= "active" (:state run))
      (contains? terminal-phases (sattr run "phase"))))

(defn await-runs
  "Block until every id is terminal (closed, failed, or exhausted) or
  `timeout-secs` (default 300) elapses. Returns run summaries plus :timed-out."
  ([ids] (await-runs ids {}))
  ([ids {:keys [timeout-secs] :or {timeout-secs 300}}]
   (let [deadline (+ (System/currentTimeMillis) (* 1000 (long timeout-secs)))]
     (loop []
       (let [strands (api/strands-by-ids (rt) (vec ids))]
         (cond
           (every? terminal? strands) {:timed-out false :runs (mapv run-summary strands)}
           (>= (System/currentTimeMillis) deadline) {:timed-out true :runs (mapv run-summary strands)}
           :else (do (Thread/sleep 250) (recur))))))))

(defn kill!
  "Kill a run's harness process and mark the run failed."
  [id]
  (let [run (or (api/show (rt) id) (fail! "Run not found" {:id id}))
        handle (get @in-flight id)]
    (if-let [process (:process handle)]
      (.destroy ^Process process)
      (some-> (run-process-handle run) (.destroy)))
    (swap! in-flight dissoc id)
    (mark-failed! id "killed by request")
    {:killed id}))

(defn note!
  "Append an immutable note strand to `target-id`'s memory.

  The note is born closed (it is memory, not work), carries
  `shuttle/note-for`, optional `shuttle/note-by` and `shuttle/round`
  attributes, and a `notes` annotation edge to the target."
  ([target-id text] (note! target-id text {}))
  ([target-id text {:keys [by round]}]
   (when (str/blank? text)
     (fail! "Note text must be non-blank" {}))
   (when-not (api/show (rt) target-id)
     (fail! "Note target strand not found" {:id target-id}))
   (let [note (api/add (rt) {:title (truncate text 72)
                             :state "closed"
                             ;; shuttle/at carries sub-second precision; the
                             ;; core created_at column only has seconds, which
                             ;; cannot order a burst of notes.
                             :attributes (cond-> {"shuttle/note-for" target-id
                                                  "shuttle/note" text
                                                  "shuttle/at" (now)}
                                           by (assoc "shuttle/note-by" by)
                                           round (assoc "shuttle/round" round))
                             :edges [{:type "notes" :to target-id}]})]
     {:id (:id note) :note-for target-id})))

(defn notes
  "Return `target-id`'s notes in creation order, optionally one `:round`."
  ([target-id] (notes target-id {}))
  ([target-id {:keys [round]}]
   (->> (api/list (rt)
                  (cond-> [:and [:= [:attr "shuttle/note-for"] target-id]]
                    round (conj [:= [:attr "shuttle/round"] round]))
                  {})
        (sort-by (juxt #(sattr % "at") :created_at :id))
        (mapv (fn [note]
                (cond-> {:id (:id note)
                         :note (sattr note "note")
                         :at (or (sattr note "at") (:created_at note))}
                  (sattr note "note-by") (assoc :by (sattr note "note-by"))
                  (sattr note "round") (assoc :round (sattr note "round"))))))))

;; ---------------------------------------------------------------------------
;; Council

(defn- council-member-prompt [{:keys [council-id topic member member-count rounds]}]
  (let [cmd (pinned-strand)
        notes-cmd (str cmd " op agent notes " council-id)]
    (str "You are council member " member " of " member-count
         " deliberating this topic over " rounds " rounds:\n"
         topic "\n\n"
         "Shared memory is the council strand " council-id ". Protocol for each round r (1.." rounds "):\n"
         "1. Do your own genuine exploration/thinking for round r (use your tools; investigate, do not just opine).\n"
         "2. Post your position: " cmd " op agent note " council-id
         " \"<your round-r analysis>\" --by <your run-id> --round r\n"
         "3. Wait for peers: poll `" notes-cmd " --round r` (sleep a few seconds between polls, up to ~2 minutes)"
         " until it lists " member-count " notes, then read them all with `" notes-cmd "`.\n"
         "4. Let their arguments genuinely update your round r+1 thinking; agree, rebut, or refine.\n\n"
         "After the final round, end with your definitive position as your last message —"
         " it is captured automatically as your result.")))

(defn- council-synthesis-prompt [{:keys [council-id topic member-count rounds]}]
  (let [cmd (pinned-strand)]
    (str "You are the synthesizer for a " member-count "-member, " rounds "-round council on:\n"
         topic "\n\n"
         "Read the full deliberation: " cmd " op agent notes " council-id "\n"
         "Weigh the arguments, note consensus and unresolved disagreements, and produce one decisive synthesis.\n"
         "Record it on the council strand: " cmd " update " council-id
         " --attr shuttle/result=\"<one-paragraph verdict>\"\n"
         "Then end with the full synthesis as your last message.")))

(defn council!
  "Convene a council: `members` agent runs deliberating `topic` over `rounds`
  rounds on one shared parent strand, then a synthesizer run that depends on
  every member and writes the verdict onto the council strand.

  Opts: `:harness` (default :claude), `:members` (default 2), `:rounds`
  (default 2), `:spawned-by`. Asynchronous; await the synthesizer id."
  [topic {:keys [harness members rounds spawned-by]
          :or {harness :claude members 2 rounds 2}}]
  (when (str/blank? topic)
    (fail! "Council topic must be non-blank" {}))
  (when-not (and (pos-int? members) (pos-int? rounds))
    (fail! "Council :members and :rounds must be positive integers" {:members members :rounds rounds}))
  (let [council (api/add (rt) {:title (truncate (str "Council: " topic) 72)
                               :attributes (cond-> {"shuttle/role" "council"
                                                    "shuttle/topic" topic
                                                    "shuttle/members" members
                                                    "shuttle/rounds" rounds}
                                             spawned-by (assoc "shuttle/spawned-by" spawned-by))})
        council-id (:id council)
        member-runs (mapv (fn [member]
                            (spawn-run! {:harness harness
                                         :title (truncate (str "Council member " member ": " topic) 72)
                                         :prompt (council-member-prompt {:council-id council-id
                                                                         :topic topic
                                                                         :member member
                                                                         :member-count members
                                                                         :rounds rounds})
                                         :parent council-id
                                         :attrs {"shuttle/council" council-id}}))
                          (range 1 (inc members)))
        synthesizer (spawn-run! {:harness harness
                                 :title (truncate (str "Council synthesis: " topic) 72)
                                 :prompt (council-synthesis-prompt {:council-id council-id
                                                                    :topic topic
                                                                    :member-count members
                                                                    :rounds rounds})
                                 :parent council-id
                                 :depends-on (mapv :id member-runs)
                                 :attrs {"shuttle/council" council-id}})]
    {:council council-id
     :members (mapv :id member-runs)
     :synthesizer (:id synthesizer)}))

;; ---------------------------------------------------------------------------
;; Op surface

(def ^:private about-doc
  {:overview
   ["Shuttle spawns coding agents (subagents) in configurable harnesses."
    "An agent run is a strand with shuttle/* attributes; `spawn` creates it and returns immediately."
    "The run starts as soon as its strand is ready: no depends-on edges means now; edges mean it waits for those strands to close."
    "On success the run strand closes with the agent's final message in shuttle/result; on failure it stays active with shuttle/phase failed and shuttle/error."
    "Everything is async. Poll with `ps`/`strand show <id>`, or block with `await`."
    "Fan-out: spawn several runs with no edges between them — they execute concurrently."
    "Fan-in: spawn a collector run with --depends-on <each child> — it starts only when all children closed; or just `await` the ids yourself and synthesize."
    "Runs can themselves spawn runs (any harness, e.g. claude spawning pi), so delegation nests; pass --spawned-by so the tree is recorded."]
   :subcommands
   {:about "strand op agent about — this manual."
    :spawn (str "strand op agent spawn --harness <name> --prompt \"...\" "
                "[--title t] [--depends-on <id>]... [--parent <id>] [--spawned-by <run-id>] "
                "[--cwd <dir>] [--max-attempts n] — create a run; prints the run id. Async.")
    :ps "strand op agent ps [--active] — list runs with phase/result summaries."
    :await "strand op agent await <id>... [--timeout-secs n] — block until the ids finish (default 300s); returns their summaries."
    :kill "strand op agent kill <id> — kill a running harness process; the run is marked failed."
    :note "strand op agent note <strand-id> \"<text>\" [--by <run-id>] [--round n] — append an immutable note to any strand's memory."
    :notes "strand op agent notes <strand-id> [--round n] — read a strand's notes in order."
    :harnesses "strand op agent harnesses — list available harnesses and aliases."
    :council (str "strand op agent council --topic \"...\" [--members n] [--rounds n] [--harness name] [--spawned-by id] "
                  "— spawn n agents that deliberate on one shared strand for n rounds plus a synthesizer; "
                  "await the returned synthesizer id for the verdict.")}
   :recipes
   {:review "spawn --harness claude --prompt \"Review the diff on branch X for correctness...\" then await the id."
    :explore-fan-out "spawn 3 runs with different exploration prompts, then await all three ids and read their results."
    :cross-harness "a claude run may spawn --harness pi (or any registered alias) when the job fits it better."
    :memory "before finishing risky work, note key findings to your own run id so a respawned successor can resume."}
   :vocabulary
   {:shuttle/phase "pending -> running -> done | failed | exhausted"
    :shuttle/result "final agent message (set on success; strand also closes)"
    :shuttle/error "failure detail (run stays active and blocks dependents, loudly)"
    :shuttle/session-id "harness session for manual resumption"
    :crash-recovery "if the weaver dies mid-run, on restart the run respawns (bounded by shuttle/max-attempts); design prompts to be resumable."}})

(defn- parse-argv
  "Parse raw op argv into positionals and flag values.

  `flag-spec` maps flag string to :single or :multi; unknown flags fail loudly."
  [argv flag-spec]
  (loop [remaining argv
         positional []
         flags {}]
    (if-let [arg (first remaining)]
      (if (str/starts-with? arg "--")
        (let [kind (or (get flag-spec arg)
                       (fail! "Unknown flag" {:flag arg :allowed (sort (keys flag-spec))}))
              value (or (second remaining)
                        (fail! "Flag requires a value" {:flag arg}))]
          (recur (drop 2 remaining)
                 positional
                 (if (= :multi kind)
                   (update flags arg (fnil conj []) value)
                   (assoc flags arg value))))
        (recur (rest remaining) (conj positional arg) flags))
      {:positional positional :flags flags})))

(defn- parse-int! [flag value]
  (try
    (Long/parseLong value)
    (catch NumberFormatException _
      (fail! "Flag requires an integer value" {:flag flag :value value}))))

(defn- op-spawn [argv]
  (let [{:keys [positional flags]}
        (parse-argv argv {"--harness" :single "--prompt" :single "--title" :single
                          "--depends-on" :multi "--parent" :single "--spawned-by" :single
                          "--cwd" :single "--max-attempts" :single})]
    (when (seq positional)
      (fail! "spawn takes only flags" {:unexpected positional}))
    (run-summary
     (spawn-run! {:harness (or (get flags "--harness") (fail! "spawn requires --harness" {}))
                  :prompt (or (get flags "--prompt") (fail! "spawn requires --prompt" {}))
                  :title (get flags "--title")
                  :depends-on (get flags "--depends-on")
                  :parent (get flags "--parent")
                  :spawned-by (get flags "--spawned-by")
                  :cwd (get flags "--cwd")
                  :max-attempts (some->> (get flags "--max-attempts") (parse-int! "--max-attempts"))}))))

(defn- op-await [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--timeout-secs" :single})]
    (when (empty? positional)
      (fail! "await requires at least one run id" {}))
    (await-runs positional
                (if-let [timeout (get flags "--timeout-secs")]
                  {:timeout-secs (parse-int! "--timeout-secs" timeout)}
                  {}))))

(defn- op-note [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--by" :single "--round" :single})]
    (when-not (= 2 (count positional))
      (fail! "note requires <strand-id> <text>" {:got positional}))
    (note! (first positional) (second positional)
           (cond-> {}
             (get flags "--by") (assoc :by (get flags "--by"))
             (get flags "--round") (assoc :round (parse-int! "--round" (get flags "--round")))))))

(defn- op-notes [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--round" :single})]
    (when-not (= 1 (count positional))
      (fail! "notes requires <strand-id>" {:got positional}))
    (notes (first positional)
           (if-let [round (get flags "--round")]
             {:round (parse-int! "--round" round)}
             {}))))

(defn- op-logs [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--tail" :single})
        [run-id] positional]
    (when-not (= 1 (count positional))
      (fail! "logs requires <run-id>" {:got positional}))
    (let [run (or (api/show (rt) run-id) (fail! "Run not found" {:id run-id}))
          log-path (or (sattr run "log") (fail! "Run has no shuttle/log" {:id run-id}))
          tail (some->> (get flags "--tail") (parse-int! "--tail"))
          read-file (fn [path]
                      (let [f (java.io.File. path)]
                        (when-not (.exists f)
                          (fail! "Run log file missing" {:id run-id :path path}))
                        (let [lines (str/split-lines (slurp f))]
                          (if tail (str/join "\n" (take-last tail lines)) (str/join "\n" lines)))))]
      {:id run-id
       :out {:path log-path :text (read-file log-path)}
       :err {:path (str/replace log-path #"\.out$" ".err")
             :text (read-file (str/replace log-path #"\.out$" ".err"))}})))

(defn- op-ps [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--for" :single})
        active? (boolean (some #{"--active"} positional))
        unexpected (vec (remove #{"--active"} positional))]
    (when (seq unexpected)
      (fail! "ps takes only --active and --for" {:unexpected unexpected}))
    (runs (cond-> {:active active?}
            (get flags "--for") (assoc :for (get flags "--for"))))))

(defn- op-council [argv]
  (let [{:keys [positional flags]}
        (parse-argv argv {"--topic" :single "--members" :single "--rounds" :single
                          "--harness" :single "--spawned-by" :single})]
    (when (seq positional)
      (fail! "council takes only flags" {:unexpected positional}))
    (council! (or (get flags "--topic") (fail! "council requires --topic" {}))
              (cond-> {}
                (get flags "--members") (assoc :members (parse-int! "--members" (get flags "--members")))
                (get flags "--rounds") (assoc :rounds (parse-int! "--rounds" (get flags "--rounds")))
                (get flags "--harness") (assoc :harness (get flags "--harness"))
                (get flags "--spawned-by") (assoc :spawned-by (get flags "--spawned-by"))))))

(defn agent-op
  "`strand op agent` entrypoint: dispatch argv to a shuttle subcommand."
  [{:keys [op/argv]}]
  (let [[sub & args] argv]
    (case sub
      nil about-doc
      "about" about-doc
      "spawn" (op-spawn args)
      "ps" (op-ps args)
      "await" (op-await args)
      "kill" (do (when-not (= 1 (count args))
                   (fail! "kill requires <run-id>" {:got (vec args)}))
                 (kill! (first args)))
      "note" (op-note args)
      "notes" (op-notes args)
      "logs" (op-logs args)
      "harnesses" (harnesses)
      "council" (op-council args)
      (fail! "Unknown agent subcommand"
             {:subcommand sub
              :available ["about" "spawn" "ps" "await" "kill" "note" "notes" "logs" "harnesses" "council"]}))))

;; ---------------------------------------------------------------------------
;; Install

(defn install!
  "Install the shuttle into the active weaver: default harnesses, the graph
  event listener, the `agent` op, then crash reconciliation and a first scan."
  []
  (let [runtime (rt)]
    (register-default-harnesses!)
    (api/register-event-handler! runtime :shuttle/engine
                                 #{:strand/added :strand/updated :batch/applied
                                   :strand/burned :strand/superseded}
                                 'skein.spools.shuttle/on-event
                                 {:spool "shuttle"})
    (api/register-op! runtime 'agent
                      "Spawn and manage coding-agent runs; `strand op agent about` is the manual"
                      'skein.spools.shuttle/agent-op)
    (api/register-query! 'agent-failures
                         [:and [:= :state "active"]
                          [:= [:attr "shuttle/run"] "true"]
                          [:in [:attr "shuttle/phase"] ["failed" "exhausted"]]])
    (let [recovered (reconcile!)]
      {:installed true
       :namespace 'skein.spools.shuttle
       :op 'agent
       :harnesses (mapv :name (harnesses))
       :recovered recovered})))
