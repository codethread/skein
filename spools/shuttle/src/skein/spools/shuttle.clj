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

  A run may continue a predecessor's harness session: `spawn-run!` accepts
  `:resume <predecessor-run-id>`, and a harness def declares a `:resume` argv
  splice (keyword placeholders resolve from the predecessor's captured
  attributes) that the engine splices in before the prompt. Continuation is
  recorded on the graph (`shuttle/resumes` plus a `resumes` annotation edge);
  a lost session fails loudly, classed `shuttle/error-class \"resume\"`, so
  recovery deliberately branches to a fresh spawn rather than silently starting
  cold.

  Interactive runs are the second execution mode: instead of exec-and-wait, the
  engine launches the harness into a user-registered multiplexer backend
  (tmux by default) and supervises it through the graph — the run completes
  when the strand it serves closes (claims model), not when a process exits.
  Backends are data-first argv definitions (`defbackend!`) whose `:start` op
  returns a durable handle stored as `shuttle/handle.*` attributes, so
  sessions survive weaver restarts and are adopted, never respawned.

  The whole spool composes public surfaces (`skein.api.weaver.alpha` inside the
  weaver JVM) and owns no privileged runtime state. Higher-level spools, such as
  `skein.spools.agents`, register CLI operations over this engine."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.weaver.alpha :as api]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime])
  (:import [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]
           [java.time Instant]
           [java.util.concurrent TimeUnit]))



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
                                  :backend-registry (atom {})
                                  ;; run-id -> {:phase :claimed|:running, :process Process}
                                  :in-flight (atom {})
                                  :preamble-extension (atom nil)
                                  :default-review-contract (atom nil))))

(defn- harness-registry [] (:harness-registry (state)))
(defn- backend-registry [] (:backend-registry (state)))
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

(def ^:private harness-def-keys #{:argv :parse :prompt-via :preamble? :env :cwd :doc :capture :resume})
(def ^:private alias-def-keys #{:alias-of :extra-args :prompt-prefix :doc})
(def ^:private parse-strategies #{:raw :claude-json :pi-json})

;; A harness :resume splice is literal argv strings interleaved with placeholder
;; keywords, each resolving from the predecessor run's captured attribute of the
;; same name at launch. The placeholder set is closed so a typo diagnoses loudly
;; instead of resolving to nil.
(def ^:private resume-placeholder-inputs #{:shuttle/session-id})

(s/def :skein.spools.shuttle.harness/resume-token
  (s/or :literal string? :placeholder resume-placeholder-inputs))
(s/def :skein.spools.shuttle.harness/resume
  (s/coll-of :skein.spools.shuttle.harness/resume-token :kind vector? :min-count 1))

(defn- validate-resume-argv!
  "Validate a harness :resume splice: a non-empty vector of literal strings and
  placeholder keywords drawn from the closed `resume-placeholder-inputs` set.
  The closed-placeholder check runs before spec conform so an unknown
  placeholder diagnoses as such rather than as an opaque spec failure."
  [owner argv]
  (when-not (and (vector? argv) (seq argv)
                 (every? #(or (string? %) (keyword? %)) argv))
    (fail! "Harness :resume must be a non-empty vector of strings and keywords"
           {:harness owner :resume argv}))
  (doseq [token argv :when (keyword? token)]
    (when-not (resume-placeholder-inputs token)
      (fail! "Harness :resume placeholder is not an available input"
             {:harness owner :token token :allowed resume-placeholder-inputs})))
  (when-not (s/valid? :skein.spools.shuttle.harness/resume argv)
    (fail! "Harness :resume does not conform to spec"
           {:harness owner :spec :skein.spools.shuttle.harness/resume
            :explain (s/explain-str :skein.spools.shuttle.harness/resume argv)}))
  argv)

;; Spliced-op plumbing shared by the backend registry and harness :capture:
;; argv tokens are literal strings, bare keywords naming engine inputs, or
;; :handle/<key> lookups into a run's stored backend handle.
(def ^:private backend-start-inputs #{:session :cwd :command :run-id})
(def ^:private backend-handle-op-inputs #{:run-id})
(def ^:private capture-op-inputs #{:run-id :cwd :session})
(def ^:private handle-key-pattern #"[A-Za-z0-9_-]+")

(defn- op-allowed-inputs [op]
  (case op
    :start backend-start-inputs
    :capture capture-op-inputs
    backend-handle-op-inputs))

(defn- validate-op-argv!
  "Validate one spliced op's argv. `:start` may not reference handle keys —
  the handle is its output, not its input. `owner` is the registering backend
  or harness name, used in failure data."
  [owner op argv]
  (when-not (and (vector? argv) (seq argv)
                 (every? #(or (string? %) (keyword? %)) argv))
    (fail! "Op argv must be a non-empty vector of strings and keywords"
           {:owner owner :op op :argv argv}))
  (doseq [token argv :when (keyword? token)]
    (cond
      (= "handle" (namespace token))
      (do (when (= :start op)
            (fail! "Backend :start cannot reference handle keys"
                   {:owner owner :token token}))
          (when-not (re-matches handle-key-pattern (name token))
            (fail! "Handle key must match [A-Za-z0-9_-]+"
                   {:owner owner :op op :token token})))

      (some? (namespace token))
      (fail! "Op argv keyword has an unknown namespace"
             {:owner owner :op op :token token})

      :else
      (let [allowed (op-allowed-inputs op)]
        (when-not (allowed token)
          (fail! "Op argv keyword is not an available input"
                 {:owner owner :op op :token token :allowed allowed}))))))

(defn defharness!
  "Register a harness definition under `name`.

  A harness def is plain data: required `:argv` (vector of strings; the run
  prompt is appended per `:prompt-via`, default `:arg`), optional `:parse`
  strategy (:raw, :claude-json, :pi-json — default :raw), `:prompt-via`
  (:arg or :stdin), `:preamble?` (default true; when false the shuttle run
  preamble is not injected), `:env` map, `:cwd`, `:doc`, `:resume` — an argv
  splice of literal strings and placeholder keywords (from the closed
  `resume-placeholder-inputs` set) that continues a predecessor's session, each
  placeholder resolving from that predecessor run's captured attributes at
  launch — and `:capture` — a
  spliced argv (interactive runs only) that prints this harness's best
  transcript text to stdout, overriding the backend's scrollback capture.
  Harness capture is the seam for harness-aware transcripts (session logs,
  user hook-written dialogue logs) without the engine knowing any harness's
  log format; correlate via the SKEIN_RUN_ID env var every session exports."
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
    (when (contains? def :capture)
      (validate-op-argv! key :capture (:capture def)))
    (when (contains? def :resume)
      (validate-resume-argv! key (:resume def)))
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
                  :resume ["--resume" :shuttle/session-id]
                  :doc "Claude Code headless. Skips permission prompts so the run can drive the strand CLI; redefine with your own argv to tighten. :claude-json captures shuttle/session-id and :resume continues that session with `--resume <session-id>`."}
         :pi {:argv ["pi" "-p" "--mode" "json"]
              :parse :pi-json
              :resume ["--session" :shuttle/session-id]
              :doc "pi headless in JSON event mode: :pi-json captures shuttle/session-id and :resume continues that specific session with `--session <session-id>` (an existing session, not the create-if-missing --session-id)."}
         :sh {:argv ["sh" "-c"]
              :parse :raw
              :preamble? false
              :doc "Shell harness: the prompt is the script, stdout is the result. Intended for tests and plumbing."}}]
    (doseq [[key def] defaults]
      (when-not (contains? @(harness-registry) key)
        (defharness! key def)))
    (harnesses)))

;; ---------------------------------------------------------------------------
;; Backend registry (interactive session multiplexers)

(def ^:private backend-def-keys #{:start :alive :stop :capture :attach :doc})
(def ^:private backend-required-ops [:start :alive :stop])

(defn defbackend!
  "Register an interactive session backend under `name`.

  A backend def is plain data: required `:start`, `:alive`, and `:stop` argv
  vectors, optional `:capture` (scrollback forensics before teardown),
  `:attach` (display-only hint rendered for humans, never executed), and
  `:doc`. Argv tokens are literal strings, engine-input keywords (`:session`,
  `:cwd`, `:command`, `:run-id` — `:start` only, except `:run-id`), or
  `:handle/<key>` lookups into the handle `:start` returned. `:start` must
  print one flat JSON object of strings as its last stdout line (empty output
  means `{}`); that handle is stored durably on the run strand."
  [name def]
  (let [key (harness-key name)]
    (when-not (map? def)
      (fail! "Backend def must be a map" {:backend key :def def}))
    (when-let [unknown (seq (remove backend-def-keys (keys def)))]
      (fail! "Backend def contains unknown keys" {:backend key :keys (vec unknown)}))
    (doseq [op backend-required-ops]
      (when-not (contains? def op)
        (fail! "Backend def missing required op" {:backend key :op op})))
    (doseq [op [:start :alive :stop :capture :attach]
            :when (contains? def op)]
      (validate-op-argv! key op (get def op)))
    (swap! (backend-registry) assoc key def)
    {:backend key :def def}))

(defn resolve-backend
  "Return the backend definition registered under `name`; fails loudly."
  [name]
  (let [key (harness-key name)]
    (or (get @(backend-registry) key)
        (fail! "Backend not found" {:backend key :available (sort (keys @(backend-registry)))}))))

(defn backends
  "Return registered backend metadata ordered by name."
  []
  (mapv (fn [[key def]]
          (cond-> {:name (name key)
                   :ops (->> [:start :alive :stop :capture :attach]
                             (filter #(contains? def %))
                             (mapv clojure.core/name))}
            (:doc def) (assoc :doc (:doc def))))
        (sort-by key @(backend-registry))))

(defn register-default-backends!
  "Register the shipped tmux backend, keeping any existing entries."
  []
  (let [defaults
        {:tmux {:start ["tmux" "new-session" "-d" "-s" :session "-c" :cwd
                        "-P" "-F" "{\"session\":\"#{session_name}\",\"pane\":\"#{pane_id}\"}"
                        :command]
                :alive ["tmux" "has-session" "-t" :handle/session]
                :stop ["tmux" "kill-session" "-t" :handle/session]
                :capture ["tmux" "capture-pane" "-p" "-t" :handle/pane]
                :attach ["tmux" "attach" "-t" :handle/session]
                :doc "tmux: one detached session per run; honors the suggested session name."}}]
    (doseq [[key def] defaults]
      (when-not (contains? @(backend-registry) key)
        (defbackend! key def)))
    (backends)))

(defn- splice-op-argv
  "Resolve a backend op's argv against engine `inputs` and the run `handle`.
  Unresolvable keywords fail loudly — a backend referencing a handle key its
  own `:start` never returned is a config error, not a default."
  [backend op argv inputs handle]
  (mapv (fn [token]
          (if (string? token)
            token
            (let [value (if (= "handle" (namespace token))
                          (get handle (name token))
                          (get inputs token))]
              (when (nil? value)
                (fail! "Backend op references an unavailable value"
                       {:backend backend :op op :token token
                        :inputs (vec (keys inputs)) :handle-keys (vec (keys handle))}))
              (str value))))
        argv))

(def ^:private backend-op-timeout-ms 10000)

(defn- run-backend-op!
  "Run one spliced backend op synchronously; returns {:exit :out :err}.
  Ops are expected to be near-instant multiplexer control commands; a hung
  backend is destroyed and fails loudly rather than wedging supervision."
  [backend op argv cwd]
  (let [pb (ProcessBuilder. ^java.util.List argv)]
    (.directory pb (io/file cwd))
    (let [process (.start pb)
          out (future (slurp (.getInputStream process)))
          err (future (slurp (.getErrorStream process)))]
      (if (.waitFor process backend-op-timeout-ms TimeUnit/MILLISECONDS)
        {:exit (.exitValue process) :out @out :err @err}
        (do (.destroyForcibly process)
            (fail! "Backend op timed out" {:backend backend :op op :argv argv}))))))

(defn- parse-handle
  "Parse backend `:start` stdout into the run handle: the last non-blank line
  must be one flat JSON object of string values; empty output means {}."
  [backend stdout]
  (let [line (->> (str/split-lines (or stdout ""))
                  (map str/trim)
                  (remove str/blank?)
                  last)]
    (if (nil? line)
      {}
      (let [value (try
                    (json/read-str line)
                    (catch Exception e
                      (fail! "Backend start output is not a JSON handle"
                             {:backend backend :line line :error (ex-message e)})))]
        (when-not (map? value)
          (fail! "Backend handle must be a JSON object" {:backend backend :line line}))
        (doseq [[k v] value]
          (when-not (and (string? k) (re-matches handle-key-pattern k))
            (fail! "Backend handle key must match [A-Za-z0-9_-]+" {:backend backend :key k}))
          (when-not (string? v)
            (fail! "Backend handle values must be strings" {:backend backend :key k :value v})))
        value))))

;; ---------------------------------------------------------------------------
;; Run vocabulary and preamble

(def run-query
  "Query form selecting all shuttle run strands."
  [:= [:attr "shuttle/run"] "true"])

(def ^:private pending-query
  [:and [:= :state "active"] run-query [:= [:attr "shuttle/phase"] "pending"]])

(def ^:private running-query
  [:and [:= :state "active"] run-query [:= [:attr "shuttle/phase"] "running"]])

;; no :state filter: a manually closed interactive run still needs its
;; session reaped, so supervision must see closed strands in phase running
(def ^:private interactive-running-query
  [:and run-query
   [:= [:attr "shuttle/mode"] "interactive"]
   [:= [:attr "shuttle/phase"] "running"]])

(def ^:private control-attrs
  #{"shuttle/run" "shuttle/harness" "shuttle/prompt" "shuttle/phase"
    "shuttle/cwd" "shuttle/spawned-by" "shuttle/max-attempts" "shuttle/mode"
    "shuttle/backend" "shuttle/completion" "shuttle/for" "shuttle/reap"
    "shuttle/session" "shuttle/session-id" "shuttle/resumes" "shuttle/error-class"})

(defn- interactive? [run]
  (= "interactive" (sattr run "mode")))

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
         "- Complete manual for spawning/awaiting/notes: " cmd " agent about\n"
         "- Leave durable notes for peers or successors: " cmd " agent note <strand-id> \"<text>\" --by " run-id "\n"
         "- Spawn subagents (async, returns a run id): " cmd " agent spawn --harness <name> --prompt \"...\" --spawned-by " run-id "\n"
         "- Wait for children: " cmd " agent await <child-run-id>\n"
         "- Never start or stop mills or weavers, and never create or edit workspace config"
         " (init.clj, spools.edn, config.json, libs.edn): if strand commands fail, report the"
         " exact error as your result instead of repairing the environment.\n"
         "- Your final message is captured automatically as this run's result; end with a clear, self-contained report for your caller.\n"
         (when-let [extension @(preamble-extension)]
           (str extension "\n"))
         "[task]\n"
         prompt-prefix)))

(defn- interactive-preamble
  "Preamble for interactive sessions. Deliberately excludes the headless
  preamble extension: that seam carries the delegated-worker contract (never
  close your strand), which is the opposite of the interactive completion
  contract (closing the served strand is how the session ends)."
  [run]
  (let [cmd (pinned-strand-command)
        id (:id run)
        for-id (sattr run "for")]
    (str "[shuttle interactive session context]\n"
         "You are an interactive agent session managed by the Skein shuttle spool.\n"
         "run-id: " id "\n"
         "- Every strand command MUST be invoked exactly as: " cmd " <command...>\n"
         "  (the env prefix and --workspace flag are both required; ambient env is unreliable)\n"
         "- Complete manual for spawning/awaiting/notes: " cmd " agent about\n"
         (if for-id
           (str "- You serve strand " for-id ". Completion contract, in this exact order:\n"
                "  1) leave a durable summary note: " cmd " agent note " for-id " \"<summary>\" --by " id "\n"
                "  2) say goodbye to the user in this session\n"
                "  3) as your LITERAL LAST action, close it: " cmd " update " for-id " --state closed\n"
                "  Closing that strand tears this session down; nothing you do after it will run.\n")
           (str "- This session ends when its own run strand is closed. When the work is done:"
                " leave notes as needed, say goodbye, then as your LITERAL LAST action run: "
                cmd " update " id " --state closed\n"))
         "- Never start or stop mills or weavers, and never create or edit workspace config"
         " (init.clj, spools.edn, config.json, libs.edn): if strand commands fail, report the"
         " exact error to the user instead of repairing the environment.\n"
         "[task]\n")))

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

(defn- mark-failed!
  "Mark a run failed with `error`. `extra` merges additional terminal attributes
  (e.g. `shuttle/error-class`) so recovery can branch on the failure kind."
  ([id error] (mark-failed! id error nil))
  ([id error extra]
   (update-run! id (merge {"shuttle/phase" "failed"
                           "shuttle/error" error
                           "shuttle/finished-at" (now)}
                          extra)
                {})))

(defn- suggested-session
  "Deterministic session name suggested to backends. Workspace-namespaced:
  multiplexer session names live in a server-global namespace, so two
  workspaces sharing one server must not collide or cross-adopt."
  [id]
  (format "skein-%08x-%s" (bit-and (hash (workspace-dir)) 0xffffffff) id))

(defn- run-handle
  "Return the run's stored backend handle. The suggested session name is the
  fallback for the `session` key only: a crash between backend :start and the
  handle write leaves no stored handle, and the suggestion is the one
  identity that still lets session-name backends probe and stop."
  [run]
  (let [stored (into {}
                     (keep (fn [[k v]]
                             (when (and (= "shuttle" (namespace k))
                                        (str/starts-with? (name k) "handle."))
                               [(subs (name k) (count "handle.")) (str v)]))
                           (:attributes run)))]
    (merge (when-let [session (sattr run "session")] {"session" session})
           stored)))

(defn- sh-quote [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn- launcher-script-file [id]
  (io/file (log-dir) (str id ".launch.sh")))

(defn- write-launcher-script!
  "Write the run's launch script: harness env, pinned engine env, cwd, exec.
  The prompt rides inside this 0700 file rather than the backend argv so it
  stays out of multiplexer argv and process listings; engine pins come after
  harness env so they always win."
  [id argv env cwd]
  (let [file (launcher-script-file id)]
    (spit file (str "#!/bin/sh\n"
                    (apply str (for [[k v] env]
                                 (str "export " (str k) "=" (sh-quote v) "\n")))
                    "export SKEIN_RUN_ID=" (sh-quote id) "\n"
                    "export XDG_STATE_HOME=" (sh-quote (state-root)) "\n"
                    "cd " (sh-quote cwd) " || exit 1\n"
                    "exec " (str/join " " (map sh-quote argv)) "\n"))
    (Files/setPosixFilePermissions (.toPath file)
                                   (PosixFilePermissions/fromString "rwx------"))
    (.getPath file)))

(defn- effective-prompt [harness run]
  (let [prompt (str (:prompt-prefix harness) (sattr run "prompt"))]
    (cond
      (false? (:preamble? harness)) prompt
      (interactive? run) (str (interactive-preamble run) prompt)
      :else (str (preamble (:id run) "") prompt))))

(defn- resume-args
  "Resolve a resuming run's harness `:resume` splice against its predecessor's
  captured attributes, or nil when the run does not resume. Failures are
  resume-classed via `:error-class \"resume\"` in the ex-data so the launch path
  can stamp `shuttle/error-class` and recovery can branch to a fresh spawn
  rather than silently retrying against a lost session."
  [harness run]
  (when-let [predecessor-id (sattr run "resumes")]
    (let [splice (or (:resume harness)
                     (fail! "Resuming run's harness declares no :resume splice"
                            {:run (:id run) :harness (:name harness) :error-class "resume"}))
          predecessor (or (api/show (rt) predecessor-id)
                          (fail! "Resume predecessor not found"
                                 {:run (:id run) :predecessor predecessor-id :error-class "resume"}))]
      (mapv (fn [token]
              (if (string? token)
                token
                (let [value (get-in predecessor [:attributes token])]
                  (when (nil? value)
                    (fail! "Resume predecessor is missing a required attribute"
                           {:run (:id run) :predecessor predecessor-id
                            :token token :error-class "resume"}))
                  (str value))))
            splice))))

(defn- validate-resume-at-launch!
  "Re-enforce the A1 resume invariants at the launch seam. Creating a pending
  run strand directly (via `api/add`, not `spawn-run!`) is a supported API, so a
  handmade `shuttle/resumes` run must not bypass the checks that otherwise live
  only in `validate-resume!`: interactive runs cannot resume (the live session is
  their continuity), a resuming run must share the predecessor's exact
  harness/alias name, and only one active continuation may exist per session.
  Splice and captured-session-id presence are rechecked in `resume-args`.
  Failures are resume-classed so recovery deliberately branches to a fresh spawn
  rather than retrying against a lost or wrong session."
  [run]
  (when-let [predecessor-id (sattr run "resumes")]
    (when (interactive? run)
      (fail! "Interactive runs cannot resume; the live session is its own continuity"
             {:run (:id run) :resumes predecessor-id :error-class "resume"}))
    (let [harness-name (sattr run "harness")
          predecessor (or (api/show (rt) predecessor-id)
                          (fail! "Resume predecessor not found"
                                 {:run (:id run) :predecessor predecessor-id :error-class "resume"}))
          predecessor-harness (sattr predecessor "harness")]
      (when-not (= harness-name predecessor-harness)
        (fail! "Resume requires the exact same harness as the predecessor"
               {:run (:id run) :harness harness-name
                :predecessor-harness predecessor-harness :error-class "resume"}))
      (when-let [live (seq (->> (api/list (rt)
                                          [:and [:= :state "active"] run-query
                                           [:= [:attr "shuttle/resumes"] predecessor-id]]
                                          {})
                                (map :id)
                                (remove #(= (:id run) %))))]
        (fail! "Another active run already continues this session"
               {:run (:id run) :predecessor predecessor-id :active (vec live)
                :error-class "resume"})))))

(defn- build-argv
  "Assemble the launch argv: base argv, alias extra-args, the resolved `:resume`
  splice (before the prompt so the session flag precedes the turn text), then
  the prompt unless it rides on stdin."
  [harness resume prompt]
  (let [argv (into (into (:argv harness) (:extra-args harness)) resume)]
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

;; ---------------------------------------------------------------------------
;; Interactive supervision

(defn- claim-teardown!
  "Atomically claim the right to tear a run down; teardown paths can race
  (event reap vs await probe vs kill), and the session must be stopped once."
  [id]
  (let [[old _new] (swap-vals! (in-flight) (fn [m]
                                             (if (= :tearing-down (:phase (get m id)))
                                               m
                                               (assoc m id {:phase :tearing-down}))))]
    (not= :tearing-down (:phase (get old id)))))

(defn- for-target-closed?
  "True when the run's served strand is no longer active work. A burned
  target counts as closed: the session would otherwise outlive the graph."
  [run]
  (when-let [for-id (sattr run "for")]
    (let [target (api/show (rt) for-id)]
      (or (nil? target) (not= "active" (:state target))))))

(defn- session-alive? [id run backend-name backend]
  (let [argv (splice-op-argv backend-name :alive (:alive backend)
                             {:run-id id} (run-handle run))]
    (zero? (:exit (run-backend-op! backend-name :alive argv (workspace-dir))))))

(defn- stop-session!
  "Run the backend :stop op; returns nil on success or the failure detail."
  [id run backend-name backend]
  (try
    (let [argv (splice-op-argv backend-name :stop (:stop backend)
                               {:run-id id} (run-handle run))
          {:keys [exit out err]} (run-backend-op! backend-name :stop argv (workspace-dir))]
      (when-not (zero? exit)
        (str "backend stop exited " exit ": " (tail (str err out) 500))))
    (catch Exception e
      (ex-message e))))

(defn- run-capture-op!
  "Resolve and run a run's capture op — the harness `:capture` (harness-aware
  transcripts: session logs, user hook-written dialogue logs) wins over the
  backend's scrollback capture — then persist its stdout as `<id>.capture`
  under the shuttle log dir. Returns the file path or fails loudly."
  [id run backend]
  (let [harness (try (resolve-harness (sattr run "harness")) (catch Exception _ nil))
        [owner op] (or (cond
                         (:capture harness) [(:name harness) (:capture harness)]
                         (:capture backend) [(harness-key (sattr run "backend")) (:capture backend)])
                       (fail! "Run has no capture op (no harness or backend :capture)"
                              {:id id}))
        ;; the effective launch cwd, including the harness default — a
        ;; capture source keyed by project directory must see the same value
        ;; the session was launched with
        cwd (or (sattr run "cwd") (:cwd harness) (workspace-dir))
        argv (splice-op-argv owner :capture op
                             {:run-id id :cwd cwd :session (sattr run "session")}
                             (run-handle run))
        {:keys [exit out err]} (run-backend-op! owner :capture argv cwd)]
    (when-not (zero? exit)
      (fail! "Capture op failed" {:id id :owner owner :exit exit
                                  :detail (tail (str err out) 500)}))
    (let [file (io/file (log-dir) (str id ".capture"))]
      (spit file out)
      (.getPath file))))

(defn- capture-session!
  "Best-effort transcript capture before teardown; returns the capture file
  path, or nil when no capture op is configured or capture fails (capture is
  forensics, never a teardown blocker)."
  [id run backend]
  (try
    (run-capture-op! id run backend)
    (catch Exception _ nil)))

(defn- finish-interactive!
  "Tear down a completed interactive run: capture scrollback, stop the
  session (unless reap policy is manual), record terminal attrs, close the
  run strand. Teardown failure is recorded loudly on the run but never blocks
  completion — a task that closed must not be held hostage by cleanup."
  [id reason]
  (when (claim-teardown! id)
    (try
      (let [current (api/show (rt) id)]
        ;; a racing path may have finished the run between selection and claim
        (when (= "running" (sattr current "phase"))
          (let [backend-name (harness-key (sattr current "backend"))
                backend (resolve-backend backend-name)
                capture-path (capture-session! id current backend)
                reap (or (sattr current "reap") "auto")
                stop-error (when (= "auto" reap)
                             (stop-session! id current backend-name backend))]
            (.delete (launcher-script-file id))
            (update-run! id (cond-> {"shuttle/phase" "done"
                                     "shuttle/result" reason
                                     "shuttle/finished-at" (now)}
                              capture-path (assoc "shuttle/log" capture-path)
                              stop-error (assoc "shuttle/teardown-error" stop-error))
                         (if (= "active" (:state current)) {:state "closed"} {})))))
      (finally
        (swap! (in-flight) dissoc id)))))

(defn- fail-dead-session!
  "Mark a run whose session died before its work completed. No auto-respawn:
  restarting an interactive session silently discards a human conversation,
  so recovery is a deliberate retry."
  [id]
  (when (claim-teardown! id)
    (try
      (let [current (api/show (rt) id)]
        (when (= "running" (sattr current "phase"))
          (.delete (launcher-script-file id))
          (mark-failed! id "session ended before its work completed")))
      (finally
        (swap! (in-flight) dissoc id)))))

(defn supervise!
  "Advance every interactive run in phase running: reap completed ones, fail
  dead sessions. Completion wins races — graph completion is checked before
  and after the liveness probe, so an agent that closes its target and exits
  in the same instant is reaped as done, not failed. This runs on graph
  events and inspection calls; the weaver deliberately has no timers, so
  there is no background poller. Returns {:reaped [..] :failed [..]}."
  []
  (let [outcome
        (reduce
         (fn [acc run]
           (let [id (:id run)]
             (try
               (cond
                 (not= "active" (:state run))
                 (do (finish-interactive! id "run strand closed")
                     (update acc :reaped conj id))

                 (for-target-closed? run)
                 (do (finish-interactive! id (str "served strand " (sattr run "for") " closed"))
                     (update acc :reaped conj id))

                 :else
                 (let [backend-name (harness-key (sattr run "backend"))
                       backend (resolve-backend backend-name)]
                   (if (session-alive? id run backend-name backend)
                     acc
                     ;; recheck completion after the probe: the agent may have
                     ;; closed its target and exited between selection and probe
                     (let [current (api/show (rt) id)]
                       (if (or (not= "active" (:state current))
                               (for-target-closed? current))
                         (do (finish-interactive! id "completed at session end")
                             (update acc :reaped conj id))
                         (do (fail-dead-session! id)
                             (update acc :failed conj id)))))))
               (catch Exception e
                 (update acc :errors conj {:id id :error (ex-message e)})))))
         {:reaped [] :failed [] :errors []}
         (api/list (rt) interactive-running-query {}))]
    (when (seq (:errors outcome))
      (fail! "Interactive supervision hit errors" outcome))
    (dissoc outcome :errors)))

(defn- launch-interactive!
  "Launch one interactive run into its backend. Crash-gap ordering mirrors
  the headless path: durably mark running (with the suggested session name as
  the probe/cleanup anchor) before the session exists, then start, then store
  the returned handle."
  [id run]
  (validate-resume-at-launch! run)
  (let [harness (resolve-harness (sattr run "harness"))
        backend-name (harness-key (sattr run "backend"))
        backend (resolve-backend backend-name)]
    (when (= :stdin (:prompt-via harness))
      (fail! "Interactive runs require an :arg prompt harness; stdin belongs to the session"
             {:run id :harness (:name harness)}))
    (let [prompt (effective-prompt harness run)
          argv (build-argv harness nil prompt)
          cwd (or (sattr run "cwd") (:cwd harness) (workspace-dir))
          session (suggested-session id)
          attempt (inc (or (sattr run "attempt") 0))
          script (write-launcher-script! id argv (:env harness) cwd)]
      (update-run! id {"shuttle/phase" "running"
                       "shuttle/attempt" attempt
                       "shuttle/session" session
                       "shuttle/started-at" (now)}
                   {})
      (let [inputs {:session session :cwd cwd :command script :run-id id}
            start-argv (splice-op-argv backend-name :start (:start backend) inputs {})
            {:keys [exit out err]} (run-backend-op! backend-name :start start-argv cwd)]
        (when-not (zero? exit)
          (.delete (launcher-script-file id))
          (fail! "Backend start failed"
                 {:run id :backend backend-name :exit exit
                  :detail (tail (str err out) 2000)}))
        ;; the session is live from here: a failure in handle parsing or
        ;; persistence must stop it before failing the run, or a real terminal
        ;; leaks unsupervised behind a failed strand
        (try
          (let [handle (parse-handle backend-name out)]
            (when (seq handle)
              (update-run! id (into {} (map (fn [[k v]] [(str "shuttle/handle." k) v])) handle) {}))
            (swap! (in-flight) assoc id {:phase :running}))
          (catch Throwable t
            (try
              (let [handle (merge {"session" session}
                                  (try (parse-handle backend-name out) (catch Exception _ {})))
                    stop-argv (splice-op-argv backend-name :stop (:stop backend)
                                              {:run-id id} handle)]
                (run-backend-op! backend-name :stop stop-argv cwd))
              (catch Throwable _ nil))
            (.delete (launcher-script-file id))
            (throw t)))))))

(defn- launch-headless! [id run]
  (let [process-ref (atom nil)]
    (try
      (validate-resume-at-launch! run)
      (let [harness (resolve-harness (sattr run "harness"))
            resume (resume-args harness run)
            prompt (effective-prompt harness run)
            argv (build-argv harness resume prompt)
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
          (mark-failed! id (str (ex-message t) (some->> (ex-data t) (str " ")))
                        (when (= "resume" (:error-class (ex-data t)))
                          {"shuttle/error-class" "resume"}))
          (catch Throwable _
            nil))))))

(defn- launch-run! [runtime run]
  (binding [*runtime* runtime]
    (let [id (:id run)]
      (if (interactive? run)
        (try
          (launch-interactive! id run)
          (catch Throwable t
            (swap! (in-flight) dissoc id)
            (try
              (mark-failed! id (str (ex-message t) (some->> (ex-data t) (str " ")))
                            (when (= "resume" (:error-class (ex-data t)))
                              {"shuttle/error-class" "resume"}))
              (catch Throwable _
                nil))))
        (launch-headless! id run)))))

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
  "Weaver event handler: any graph mutation may unblock a pending run or
  complete the strand an interactive session serves."
  [_event]
  (scan!)
  (supervise!))

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

  Headless: any active `running` run this weaver has no in-flight handle for
  was owned by a dead predecessor: its stale process is killed when its
  identity can be verified (pid plus recorded start instant), then the run is
  either reset to `pending` for respawn or marked `exhausted` (loudly, still
  active so dependents stay blocked) when `shuttle/max-attempts` is spent.

  Interactive: sessions survive the weaver by design, so orphans are adopted,
  never respawned — a live session keeps its run `running` from durable
  handle attributes; a dead one is reaped as done when its target already
  closed (completion wins), otherwise failed loudly regardless of attempts
  (auto-respawn would silently discard a human conversation).

  Returns a summary of respawned/exhausted/adopted/reaped/failed run ids."
  []
  (let [orphans (remove #(contains? @(in-flight) (:id %))
                        (api/list (rt) running-query {}))
        {interactive-orphans true headless-orphans false}
        (group-by (comp boolean interactive?) orphans)
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
                 {:respawned [] :exhausted [] :adopted [] :reaped [] :failed []}
                 headless-orphans)
        ;; adoption is bookkeeping only: live sessions enter in-flight so a
        ;; second reconcile skips them. Reap/fail decisions are deliberately
        ;; left to supervise! below so completion-first ordering (including
        ;; closed-state manual-close leftovers running-query cannot see) lives
        ;; in exactly one place.
        summary (reduce
                 (fn [acc run]
                   (let [id (:id run)]
                     (try
                       (let [backend-name (harness-key (sattr run "backend"))
                             backend (resolve-backend backend-name)]
                         (if (session-alive? id run backend-name backend)
                           (do (swap! (in-flight) assoc id {:phase :running})
                               (update acc :adopted conj id))
                           acc))
                       (catch Exception e
                         (try
                           (mark-failed! id (str "reconcile failed: " (ex-message e)
                                                 (some->> (ex-data e) (str " "))))
                           (catch Throwable _ nil))
                         (update acc :failed conj id)))))
                 summary
                 interactive-orphans)
        supervised (try
                     (supervise!)
                     (catch Exception e
                       {:reaped [] :failed [] :supervise-error (ex-message e)}))]
    (scan!)
    (cond-> (-> summary
                (update :reaped into (:reaped supervised))
                (update :failed into (:failed supervised)))
      (:supervise-error supervised) (assoc :supervise-error (:supervise-error supervised)))))

;; ---------------------------------------------------------------------------
;; Run creation, inspection, notes

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 (- n 1)) "…") s))

(defn- validate-resume!
  "Validate a `:resume <predecessor-id>` spawn against its predecessor, failing
  loudly (TEN-003) so a lost or mismatched session never resumes silently:
  interactive runs cannot resume (the live session is their continuity), the
  predecessor must exist and share the exact harness/alias name (aliases swap
  model/provider, so a base-root match is too weak — the error carries both
  names), it must carry a captured `shuttle/session-id`, the spawning harness
  must declare a `:resume` splice, and only one active continuation may exist
  per predecessor session. `harness-name` is the resolved spawn name string;
  `harness-def` its flattened def."
  [harness-name harness-def mode predecessor-id]
  (when mode
    (fail! "Interactive runs cannot :resume; the live session is its own continuity"
           {:resume predecessor-id :mode mode}))
  (let [predecessor (or (api/show (rt) predecessor-id)
                        (fail! "Resume predecessor not found" {:resume predecessor-id}))
        predecessor-harness (sattr predecessor "harness")]
    (when-not (= harness-name predecessor-harness)
      (fail! "Resume requires the exact same harness as the predecessor"
             {:resume predecessor-id :harness harness-name
              :predecessor-harness predecessor-harness}))
    (when-not (sattr predecessor "session-id")
      (fail! "Resume predecessor has no captured shuttle/session-id"
             {:resume predecessor-id :harness harness-name}))
    (when-not (:resume harness-def)
      (fail! "Resume requires a harness that declares a :resume splice"
             {:resume predecessor-id :harness harness-name}))
    (when-let [live (seq (mapv :id (api/list (rt)
                                             [:and [:= :state "active"] run-query
                                              [:= [:attr "shuttle/resumes"] predecessor-id]]
                                             {})))]
      (fail! "Another active run already continues this session"
             {:resume predecessor-id :active live}))))

(defn spawn-run!
  "Create one agent-run strand; the engine spawns it when it becomes ready.

  Opts: `:harness` and `:prompt` required; optional `:title`, `:depends-on`
  (vector of strand ids), `:parent` and `:spawned-by` (each gets a parent-of
  edge to the run), `:cwd`, `:max-attempts`, and extra `:attrs`. Interactive
  sessions pass `:mode :interactive` with a required `:backend`, and
  optionally `:reap` (`auto` tears the session down on completion, `manual`
  leaves it to the human; default auto). An interactive run with a `:parent`
  completes when that strand closes (claim); without one it completes when
  its own run strand is closed (manual-close).

  `:resume <predecessor-run-id>` continues the predecessor's harness session:
  the run is stamped `shuttle/resumes` with a `resumes` annotation edge, and at
  launch the harness `:resume` splice resolves from the predecessor's captured
  attributes ahead of the prompt (see `validate-resume!` for the loud rules).
  Asynchronous: returns the created run strand immediately."
  [{:keys [harness prompt title depends-on parent spawned-by cwd max-attempts attrs
           mode backend reap resume]}]
  (when (str/blank? prompt)
    (fail! "Run :prompt must be non-blank" {}))
  (let [resolved (resolve-harness (or harness (fail! "Run :harness is required" {})))]
    (when (and mode (not (contains? #{:interactive "interactive"} mode)))
      (fail! "Run :mode must be :interactive when provided" {:mode mode}))
    (when (and (not mode) (or backend reap))
      (fail! "Run :backend and :reap apply only to interactive runs"
             {:backend backend :reap reap}))
    (when mode
      (resolve-backend (or backend (fail! "Interactive runs require :backend" {})))
      (when (and reap (not (contains? #{:auto :manual "auto" "manual"} reap)))
        (fail! "Run :reap must be auto or manual" {:reap reap})))
    (when resume
      (validate-resume! (name (harness-key harness)) resolved mode resume)))
  (let [parent-ids (distinct (remove nil? [parent spawned-by]))
        reserved (merge {"shuttle/run" "true"
                         "shuttle/harness" (name (harness-key harness))
                         "shuttle/prompt" prompt
                         "shuttle/phase" "pending"}
                        (when cwd {"shuttle/cwd" cwd})
                        (when spawned-by {"shuttle/spawned-by" spawned-by})
                        (when max-attempts {"shuttle/max-attempts" max-attempts})
                        (when resume {"shuttle/resumes" resume})
                        (when mode
                          (merge {"shuttle/mode" "interactive"
                                  "shuttle/backend" (name (harness-key backend))
                                  "shuttle/completion" (if parent "claim" "manual-close")}
                                 (when parent {"shuttle/for" parent})
                                 (when reap {"shuttle/reap" (name (keyword reap))}))))]
    (when-let [collisions (seq (filter #(or (contains? control-attrs %)
                                            (str/starts-with? (str %) "shuttle/handle."))
                                       (keys (or attrs {}))))]
      (fail! "Run :attrs must not override shuttle control attributes" {:keys (vec collisions)}))
    ;; validate provenance targets before the run exists so a bad parent id
    ;; cannot leave a spawned run behind a thrown edge update
    (doseq [parent-id parent-ids]
      (when-not (api/show (rt) parent-id)
        (fail! "Run parent strand not found" {:id parent-id})))
    (let [run (api/add (rt) {:title (or title (truncate prompt 72))
                             :attributes (merge attrs reserved)
                             :edges (cond-> (mapv (fn [dep] {:type "depends-on" :to dep}) (distinct (or depends-on [])))
                                      resume (conj {:type "resumes" :to resume}))})]
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

(defn- attach-hint
  "Render the backend :attach op over the run's stored handle as the human
  attach command. Display-only and best-effort: a summary must not throw
  because a backend def or handle is currently unresolvable."
  [run]
  (try
    (let [backend (resolve-backend (sattr run "backend"))]
      (when-let [op (:attach backend)]
        (->> (splice-op-argv (harness-key (sattr run "backend")) :attach op
                             {:run-id (:id run)} (run-handle run))
             (map (fn [token]
                    (if (re-find #"[^A-Za-z0-9_@%+=:,./-]" token) (sh-quote token) token)))
             (str/join " "))))
    (catch Exception _ nil)))

(defn run-summary
  "Project a run strand into the compact summary shape the op surface returns."
  [run]
  (let [base (cond-> {:id (:id run)
                      :title (:title run)
                      :state (:state run)
                      :phase (sattr run "phase")
                      :harness (sattr run "harness")}
               (run-for-target run) (assoc :for (run-for-target run))
               (sattr run "result") (assoc :result (sattr run "result"))
               (sattr run "error") (assoc :error (sattr run "error"))
               (sattr run "session-id") (assoc :session-id (sattr run "session-id"))
               (sattr run "spawned-by") (assoc :spawned-by (sattr run "spawned-by"))
               (sattr run "resumes") (assoc :resumes (sattr run "resumes"))
               (sattr run "error-class") (assoc :error-class (sattr run "error-class"))
               (sattr run "attempt") (assoc :attempt (sattr run "attempt")))]
    (if (interactive? run)
      (let [attach (attach-hint run)]
        (cond-> (assoc base
                       :mode "interactive"
                       :backend (sattr run "backend")
                       :completion (sattr run "completion"))
          (sattr run "reap") (assoc :reap (sattr run "reap"))
          (get (run-handle run) "session") (assoc :session (get (run-handle run) "session"))
          attach (assoc :attach attach)))
      base)))

(defn runs
  "Return summaries of shuttle runs; opts may filter to `:active` or `:for`.
  Listing doubles as an interactive liveness checkpoint (there is no
  background poller): dead sessions are failed here, best-effort."
  ([] (runs {}))
  ([{:keys [active for]}]
   (try (supervise!) (catch Exception _ nil))
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
     (loop [i 1]
       (let [strands (api/strands-by-ids (rt) (vec ids))]
         (cond
           (every? terminal? strands) {:timed-out false :runs (mapv run-summary strands)}
           (>= (System/currentTimeMillis) deadline) {:timed-out true :runs (mapv run-summary strands)}
           :else (do
                   ;; awaiting an interactive run must notice a dead session
                   ;; rather than sit out the full timeout; probe every ~2s
                   (when (and (zero? (mod i 8)) (some interactive? strands))
                     (try (supervise!) (catch Exception _ nil)))
                   (Thread/sleep 250)
                   (recur (inc i)))))))))

(defn kill!
  "Kill a run's harness process (or interactive session) and mark it failed."
  [id]
  (let [run (or (api/show (rt) id) (fail! "Run not found" {:id id}))]
    (if (interactive? run)
      (let [backend-name (harness-key (sattr run "backend"))
            backend (resolve-backend backend-name)]
        (when-not (session-alive? id run backend-name backend)
          (fail! "Run has no live session" {:id id}))
        (when (claim-teardown! id)
          (try
            (when-let [error (stop-session! id run backend-name backend)]
              (fail! "Backend stop failed" {:id id :error error}))
            (.delete (launcher-script-file id))
            (mark-failed! id "killed by request")
            (finally
              (swap! (in-flight) dissoc id))))
        {:killed id})
      (let [process (:process (get @(in-flight) id))
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
        {:killed id}))))

(defn capture!
  "Capture an interactive run's transcript right now, persist it as the run's
  `shuttle/log`, and return {:id :path :text}. Works on live runs (a
  coordinator peek without attaching) and, when the harness capture source
  outlives the session (hook-written logs), on finished runs too. Fails
  loudly when the run is not interactive or no capture op is configured."
  [id]
  (let [run (or (api/show (rt) id) (fail! "Run not found" {:id id}))]
    (when-not (interactive? run)
      (fail! "Capture applies to interactive runs; headless runs already write shuttle/log" {:id id}))
    (let [backend (resolve-backend (harness-key (sattr run "backend")))
          path (run-capture-op! id run backend)]
      (update-run! id {"shuttle/log" path} {})
      {:id id :path path :text (slurp path)})))

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
    (register-default-backends!)
    (api/register-event-handler! runtime :shuttle/engine
                                 #{:strand/added :strand/updated :batch/applied
                                   :strand/burned :strand/superseded}
                                 'skein.spools.shuttle/on-event
                                 {:spool "shuttle"})
    (let [recovered (reconcile!)]
      {:installed true
       :namespace 'skein.spools.shuttle
       :harnesses (mapv :name (harnesses))
       :backends (mapv :name (backends))
       :recovered recovered})))
