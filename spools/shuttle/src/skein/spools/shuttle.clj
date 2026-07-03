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

  The whole spool composes public surfaces (`skein.api.weaver.alpha` inside the
  weaver JVM) and owns no privileged runtime state. Higher-level spools, such as
  `skein.spools.agents`, register CLI operations over this engine."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.api.weaver.alpha :as api]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime])
  (:import [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.time Instant]))



(def ^:private default-max-attempts 3)

(defn- fail! [message data]
  (throw (ex-info message data)))

(def ^:dynamic *runtime*
  "Runtime captured for asynchronous shuttle worker threads."
  nil)

(defn- rt []
  (or *runtime* (current/runtime)))

(defn- state []
  (runtime/spool-state (rt) ::state
                       #(hash-map :harness-registry (atom {})
                                  ;; run-id -> {:phase :claimed|:running, :process Process}
                                  :in-flight (atom {})
                                  :preamble-extension (atom nil)
                                  :default-review-contract (atom nil))))

(defn- harness-registry [] (:harness-registry (state)))
(defn- in-flight [] (:in-flight (state)))
(defn- preamble-extension [] (:preamble-extension (state)))
(defn- default-review-contract
  "Workspace review-contract override atom; nil means the generic contract applies."
  []
  (:default-review-contract (state)))

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
    (swap! (harness-registry) assoc key def)
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
    (swap! (harness-registry) assoc key def)
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
    (let [def (or (get @(harness-registry) key)
                  (fail! "Harness not found" {:harness key :available (sort (keys @(harness-registry)))}))]
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
        (sort-by key @(harness-registry))))

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
      (when-not (contains? @(harness-registry) key)
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

(defn pinned-strand-command
  "Return the fully pinned strand invocation prefix for spawned agents.

  Harness shells may re-source user dotfiles and override ambient env, so the
  state root that selects the mill/weaver must ride inside the command text,
  not the inherited environment."
  []
  (str "env XDG_STATE_HOME=" (state-root) " strand --workspace " (workspace-dir)))



(defn set-preamble-extension!
  "Register additional preamble text appended after shuttle's engine contract.

  A second registration with different text fails loudly so composed spools do
  not silently replace each other's worker contract. Re-registering the same
  text is idempotent for config reloads."
  [text]
  (when-not (and (string? text) (not (str/blank? text)))
    (fail! "Preamble extension must be non-blank text" {:text text}))
  (let [[old _] (swap-vals! (preamble-extension) #(or % text))]
    (when (and old (not= old text))
      (fail! "Preamble extension already registered" {}))
    {:preamble-extension true}))

(defn- preamble [run-id prompt-prefix]
  (let [cmd (pinned-strand-command)]
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
         (when-let [extension @(preamble-extension)]
           (str extension "\n"))
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
        stdout (read-file-safe out-file)
        current (api/show (rt) id)]
    (swap! (in-flight) dissoc id)
    (when-not (= "failed" (sattr current "phase"))
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
          (mark-failed! id (str "harness exited " exit ": " (tail detail 2000))))))))

(defn- process-start-instant
  "Return the OS start instant string for a live process, or nil when unknown."
  [^Process process]
  (some-> (.info process) (.startInstant) (.orElse nil) str))

(defn- launch-run! [runtime run]
  (binding [*runtime* runtime]
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
          (swap! (in-flight) assoc id {:phase :running :process process})
          (update-run! id (cond-> {"shuttle/pid" (.pid ^Process process)}
                            (process-start-instant process)
                            (assoc "shuttle/pid-started-at" (process-start-instant process)))
                       {})
          (finish-run! id process harness out-file err-file)))
      (catch Throwable t
        (some-> ^Process @process-ref (.destroy))
        (swap! (in-flight) dissoc id)
        (try
          (mark-failed! id (str (ex-message t) (some->> (ex-data t) (str " "))))
          (catch Throwable _
            nil)))))))

(defn- claim! [id]
  (let [[old _new] (swap-vals! (in-flight) (fn [m]
                                           (if (contains? m id)
                                             m
                                             (assoc m id {:phase :claimed}))))]
    (not (contains? old id))))

(defn scan!
  "Spawn every ready pending run not already claimed. Returns claimed run ids."
  []
  (let [runtime (rt)
        ready-runs (api/ready runtime pending-query {})
        claimed (filterv (comp claim! :id) ready-runs)]
    (doseq [run claimed]
      (doto (Thread. #(launch-run! runtime run) (str "shuttle-run-" (:id run)))
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
  (let [orphans (remove #(contains? @(in-flight) (:id %))
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

(def ^:private terminal-phases #{"done" "failed" "exhausted" "superseded"})

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
        process (:process (get @(in-flight) id))
        process-handle (when-not process (run-process-handle run))]
    (when-not (or process process-handle)
      (fail! "Run has no live process" {:id id}))
    ;; Mark failed before destroying: the run's waiter thread is blocked in
    ;; waitFor until the destroy, and must see the failed phase when it wakes
    ;; so it does not overwrite this error with its own exit stamp.
    (mark-failed! id "killed by request")
    (if process
      (.destroy ^Process process)
      (.destroy ^ProcessHandle process-handle))
    (swap! (in-flight) dissoc id)
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
;; Review contract state

(def generic-review-contract
  "Default contract text for independent shuttle reviews."
  "Review the target read-only. Report prioritized findings with file:line references when applicable. Do not modify files or close strands. Append your findings as a note on the target strand, then end with the same findings as your final result.")



(defn set-default-review-contract!
  "Set the workspace default review contract text; nil restores the generic one."
  [text]
  (when (and (some? text) (or (not (string? text)) (str/blank? text)))
    (fail! "Default review contract must be a non-blank string or nil" {:text text}))
  (reset! (default-review-contract) text)
  {:default-review-contract (boolean text)})

(defn default-review-contract-text
  "Return the effective workspace review contract text."
  []
  (or @(default-review-contract) generic-review-contract))

;; ---------------------------------------------------------------------------
;; Install

(defn install!
  "Install the shuttle into the active weaver: default harnesses, the graph
  event listener, crash reconciliation, and a first scan."
  []
  (let [runtime (rt)]
    (register-default-harnesses!)
    (api/register-event-handler! runtime :shuttle/engine
                                 #{:strand/added :strand/updated :batch/applied
                                   :strand/burned :strand/superseded}
                                 'skein.spools.shuttle/on-event
                                 {:spool "shuttle"})
    (let [recovered (reconcile!)]
      {:installed true
       :namespace 'skein.spools.shuttle
       :harnesses (mapv :name (harnesses))
       :recovered recovered})))
