(ns skein.spools.agent-run
  "Userland spool that spawns coding agents in user-chosen harnesses.

  An agent run is a strand carrying `agent-run/*` attributes; creating the strand
  is the API. The engine listens for graph mutations, spawns each pending run
  the moment its strand becomes ready (`depends-on` readiness is the only
  scheduler), captures the harness process output back onto the run strand, and
  closes the strand so dependent runs unblock. Everything is asynchronous by
  default; `await` is the opt-in blocking convenience.

  Runs survive weaver crashes because the strands are durable: `reconcile!`
  respawns still-active running strands on install, bounded by
  `agent-run/max-attempts`. Run memory is append-only note strands linked by
  `notes` annotation edges — the edge is the sole linkage.

  Delegation semantics ride a `serves` edge (run → target): a serving run is a
  delegation of that target's own work. This is distinct from the `parent-of`
  edge, which only places a run in the graph — a serving run carries both
  (placement plus semantics), a read-only helper (recon spawn, reviewer, panel
  seat) carries `parent-of` alone. `spawn-run!` writes the `serves` edge; the
  read side (`run-summary` `:for`, `runs`, delegation guards) keys serving off
  it rather than inferring it from placement.

  A run may continue a predecessor's harness session: `spawn-run!` accepts
  `:resume <predecessor-run-id>`, and a harness def declares a `:resume` argv
  splice (keyword placeholders resolve from the predecessor's captured
  attributes) that the engine splices in before the prompt. Continuation is
  recorded on the graph (`agent-run/resumes` plus a `resumes` annotation edge);
  a lost session fails loudly, classed `agent-run/error-class \"resume\"`, so
  recovery deliberately branches to a fresh spawn rather than silently starting
  cold.

  A dead run is succeeded — never mutated in place — through the one primitive
  `supersede-and-respawn!`: it mints a fresh successor preserving the
  predecessor's `serves` target, `depends-on` edges, `spawned-by` provenance,
  and execution shape, closes the predecessor `agent-run/phase \"superseded\"`,
  and records lineage as a `supersedes` edge (successor → predecessor) plus an
  `agent-run/supersedes` attr. `runs-serving` resolves the current run for a
  target as the serving run with no incoming `supersedes` edge. Crash-respawn
  (`reconcile!`) and session-carrying resume are the same family read two ways:
  reconcile resets a strand in place so the run id stays stable, and
  `:continuity :resume` layers the resume link onto a supersession — `resumes`
  and `supersedes` stay distinct edges and the resolution rule keys on
  `supersedes` alone.

  Interactive runs are the second execution mode: instead of exec-and-wait, the
  engine launches the harness into a user-registered multiplexer backend
  (tmux by default) and supervises it through the graph — the run completes
  when its interactive completion target (`agent-run/for`) closes (claims
  model), not when a process exits. That interactive `agent-run/for` target is
  the interactive completion signal, separate from the headless `serves`
  delegation edge above and never folded into it.
  Backends are data-first argv definitions (`defbackend!`) whose `:start` op
  returns a durable handle stored as `agent-run/handle.*` attributes, so
  sessions survive weaver restarts and are adopted, never respawned.

  Harnesses (tools) and aliases (seats) live in two independent runtime
  registries: `defharness!` writes one entry per tool (claude, pi, codex, sh),
  `defalias!` writes named seats over them. Resolution is alias-first — an
  unvisited alias shadows a same-named harness, so a seat may carry a tool's own
  name and still terminate at the tool. Re-registration replaces within a
  registry (reload idempotency); across registries names are independent.

  The whole spool composes public surfaces (`skein.api.weaver.alpha` inside the
  weaver JVM) and owns no privileged runtime state. Higher-level spools, such as
  `skein.spools.delegation`, register CLI operations over this engine."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.graph.alpha :as graph]
            [skein.api.notes.alpha :as notes-alpha]
            [skein.api.weaver.alpha :as api]
            [skein.api.events.alpha :as events]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.vocab.alpha :as vocab]
            [skein.spools.format :as fmt]
            [skein.spools.util :refer [fail! attr-get]])
  (:import [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]
           [java.time Instant]
           [java.util.concurrent Executors ScheduledThreadPoolExecutor ThreadFactory TimeUnit]))



(def ^:private default-max-attempts 3)

(def ^:dynamic *runtime*
  "Runtime captured for asynchronous engine worker threads."
  nil)

(defn- rt []
  (or *runtime* (current/runtime)))

(defn- ^ThreadFactory daemon-thread-factory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (swap! counter inc)))
          (.setDaemon true))))))

(defn- warn!
  "Emit a loud-but-non-fatal engine warning to the weaver's stderr log.

  Used where failing dead would be worse than continuing (a reload replacing a
  set-once registration): the world stays operable and the divergence is still
  visible in the weaver log."
  [message data]
  (binding [*out* *err*]
    (println (str "[agent-run] WARN " message " " (pr-str data)))))

(def ^:private state-version
  "Shape version for the engine's runtime spool-state map.

  Bump this whenever `new-state`'s key set changes. Spool state survives
  `reload!`, so a post-upgrade reload would otherwise reuse a preserved map that
  predates a newly added key (`:worker-executor`/`:recovery-scheduler` were the
  incident: scan!'s `(.execute nil ..)` then parked every new run silently). A
  changed version makes the runtime reinit through `migrate-state` deliberately
  instead. The `state-shape-matches-declared-version` test fails loudly if
  `new-state` and this version drift apart.

  v3 split the single mixed harness registry into `:harness-registry` (tools)
  and `:alias-registry` (seats); `migrate-state` splits a preserved v2 registry
  by entry shape."
  3)

(defn- new-state []
  (let [scheduler (ScheduledThreadPoolExecutor. 1 (daemon-thread-factory "agent-run-recovery"))
        workers (Executors/newCachedThreadPool (daemon-thread-factory "agent-run-exec"))]
    (.setRemoveOnCancelPolicy scheduler true)
    {:harness-registry (atom {})
     :alias-registry (atom {})
     :backend-registry (atom {})
     ;; run-id -> {:phase :claimed|:running|:deferred-recovery, :process Process}
     :in-flight (atom {})
     :recovery-scheduler scheduler
     :worker-executor workers
     :preamble-extension (atom nil)
     ;; durable record of genuine set-preamble-extension! conflicts (a second
     ;; distinct registrant replacing an existing value), so the clash survives
     ;; as queryable state rather than a stderr line lost on a long-lived daemon.
     :preamble-conflicts (atom [])
     :default-review-contract (atom nil)
     :close-fn (fn []
                 (.shutdownNow scheduler)
                 (.shutdownNow workers)
                 (when-not (.awaitTermination scheduler 1000 TimeUnit/MILLISECONDS)
                   (fail! "Engine recovery scheduler did not stop" {}))
                 (when-not (.awaitTermination workers 1000 TimeUnit/MILLISECONDS)
                   (fail! "Engine worker executor did not stop" {})))}))

(defn- classify-registry-entry
  "Classify a preserved registry entry as `:harness` or `:alias` by exact shape
  match against `::harness-def`/`::alias-def`.

  Fails loudly with the offending `[key def]` in `ex-data` when an entry matches
  neither or both shapes, so the v2->v3 split never silently drops a corrupt
  record or misfiles a record that could pass as either kind."
  [key def]
  (let [harness? (s/valid? ::harness-def def)
        alias? (s/valid? ::alias-def def)]
    (cond
      (and harness? (not alias?)) :harness
      (and alias? (not harness?)) :alias
      :else (fail! "Preserved registry entry matches neither or both registry shapes"
                   {:entry [key def]
                    :matches-harness? harness?
                    :matches-alias? alias?}))))

(defn- migrate-state
  "Reinit a preserved engine state whose shape predates `state-version`.

  The v2 mixed `:harness-registry` is split by entry shape into the v3
  `:harness-registry` (tools) and `:alias-registry` (seats); backend registry,
  in-flight tracking, and the set-once override atoms carry over; the executors
  and close hook are rebuilt fresh so scan!/scheduling never runs against a
  stale or missing executor. The old recovery scheduler is stopped (best-effort,
  accepting no new ticks) while the old worker pool is left to drain any
  in-flight run monitors as daemon threads rather than being interrupted
  mid-run."
  [old]
  (when-let [scheduler (:recovery-scheduler old)]
    (try (.shutdown ^ScheduledThreadPoolExecutor scheduler)
         (catch Throwable t
           (warn! "Old recovery scheduler shutdown failed during migrate; it may leak"
                  {:exception/message (ex-message t)}))))
  (let [grouped (group-by (fn [[k v]] (classify-registry-entry k v))
                          @(:harness-registry old))]
    (merge (new-state)
           (select-keys old [:backend-registry :in-flight
                             :preamble-extension :preamble-conflicts
                             :default-review-contract])
           {:harness-registry (atom (into {} (:harness grouped)))
            :alias-registry (atom (into {} (:alias grouped)))})))

(defn- state []
  (runtime/spool-state (rt) ::state
                       {:version state-version :migrate-fn migrate-state}
                       new-state))

(defn- require-state-entry
  "Return spool-state entry `k`, failing loudly when it is missing.

  A missing executor or scheduler means a shape-mismatched state slipped through
  without a reinit; parking runs on a `(.execute nil ..)` is exactly the silent
  fallback TEN-003 forbids, so surface it as a loud error at the launch seam
  instead."
  [k]
  (let [s (state)]
    (or (get s k)
        (fail! "Required engine spool-state entry is missing"
               {:missing k :present (vec (sort (keys s)))}))))

(defn- harness-registry [] (:harness-registry (state)))
(defn- alias-registry [] (:alias-registry (state)))
(defn- backend-registry [] (:backend-registry (state)))
(defn- in-flight [] (:in-flight (state)))
(defn- preamble-extension [] (:preamble-extension (state)))
(defn- preamble-conflicts-atom [] (:preamble-conflicts (state)))
(defn- ^ScheduledThreadPoolExecutor recovery-scheduler [] (require-state-entry :recovery-scheduler))
(defn- ^java.util.concurrent.ExecutorService worker-executor [] (require-state-entry :worker-executor))

(defn in-flight-run-ids
  "Return the set of run ids the engine is currently tracking in-flight
  (claimed, running, or awaiting recovery).

  Attention detectors use this to tell a genuinely parked ready run — one that
  scan! should have launched but did not — from one already in flight."
  []
  (set (keys @(in-flight))))
(defn- default-review-contract
  "Workspace review-contract override atom; nil means the generic contract applies."
  []
  (:default-review-contract (state)))

(defn- sattr
  "Read the `agent-run/<k>` attribute from a normalized strand."
  [strand k]
  (attr-get strand (keyword "agent-run" k)))

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
(def ^:private prompt-via-strategies #{:arg :stdin})

;; A harness :resume splice is literal argv strings interleaved with placeholder
;; keywords, each resolving from the predecessor run's captured attribute of the
;; same name at launch. The placeholder set is closed so a typo diagnoses loudly
;; instead of resolving to nil.
(def ^:private resume-placeholder-inputs #{:agent-run/session-id})

(s/def :skein.spools.agent-run.harness/resume-token
  (s/or :literal string? :placeholder resume-placeholder-inputs))
(s/def :skein.spools.agent-run.harness/resume
  (s/coll-of :skein.spools.agent-run.harness/resume-token :kind vector? :min-count 1))

;; Registry entry shapes. `::harness-def` and `::alias-def` are the source of
;; truth for the "is this a tool or a seat?" distinction: they are consulted by
;; `defharness!`/`defalias!` and by the migrate split's exactly-one-shape
;; classification. Mutual exclusivity rides on the required keys — a harness
;; needs `:argv`, an alias needs `:alias-of`, and the closed key sets forbid a
;; valid registration carrying both. `:capture`/`:resume` are intentionally left
;; open here; their splice semantics need the dedicated validators a spec cannot
;; express.
(s/def :skein.spools.agent-run.harness/argv (s/coll-of string? :kind vector? :min-count 1))
(s/def :skein.spools.agent-run.harness/parse parse-strategies)
(s/def :skein.spools.agent-run.harness/prompt-via prompt-via-strategies)
(s/def :skein.spools.agent-run.harness/preamble? boolean?)
(s/def :skein.spools.agent-run.harness/env map?)
(s/def :skein.spools.agent-run.harness/cwd string?)
(s/def :skein.spools.agent-run.harness/doc string?)
(s/def ::harness-def
  (s/keys :req-un [:skein.spools.agent-run.harness/argv]
          :opt-un [:skein.spools.agent-run.harness/parse
                   :skein.spools.agent-run.harness/prompt-via
                   :skein.spools.agent-run.harness/preamble?
                   :skein.spools.agent-run.harness/env
                   :skein.spools.agent-run.harness/cwd
                   :skein.spools.agent-run.harness/doc]))

(s/def :skein.spools.agent-run.alias/alias-of
  (s/or :keyword keyword? :symbol symbol? :string (s/and string? (complement str/blank?))))
(s/def :skein.spools.agent-run.alias/extra-args (s/coll-of string? :kind vector?))
(s/def :skein.spools.agent-run.alias/prompt-prefix string?)
(s/def :skein.spools.agent-run.alias/doc string?)
(s/def ::alias-def
  (s/keys :req-un [:skein.spools.agent-run.alias/alias-of]
          :opt-un [:skein.spools.agent-run.alias/extra-args
                   :skein.spools.agent-run.alias/prompt-prefix
                   :skein.spools.agent-run.alias/doc]))

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
  (when-not (s/valid? :skein.spools.agent-run.harness/resume argv)
    (fail! "Harness :resume does not conform to spec"
           {:harness owner :spec :skein.spools.agent-run.harness/resume
            :explain (s/explain-str :skein.spools.agent-run.harness/resume argv)}))
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
  (:arg or :stdin), `:preamble?` (default true; when false the run
  preamble is not injected), `:env` map, `:cwd`, `:doc`, `:resume` — an argv
  splice of literal strings and placeholder keywords (from the closed
  `resume-placeholder-inputs` set) that continues a predecessor's session, each
  placeholder resolving from that predecessor run's captured attributes at
  launch — and `:capture` — a
  spliced argv (interactive runs only) that prints this harness's best
  transcript text to stdout, overriding the backend's scrollback capture.
  Harness capture is the seam for harness-aware transcripts (session logs,
  user hook-written dialogue logs) without the engine knowing any harness's
  log format; correlate via the SKEIN_RUN_ID env var every session exports.

  Writes only the harness (tool) registry; a same-named seat may coexist in the
  alias registry and shadows this tool at resolution time. The def shape is the
  `::harness-def` spec; `:capture`/`:resume` splice semantics keep their
  dedicated validators."
  [name def]
  (let [key (harness-key name)]
    (when-let [unknown (and (map? def) (seq (remove harness-def-keys (keys def))))]
      (fail! "Harness def contains unknown keys" {:harness key :keys (vec unknown)}))
    (when-not (s/valid? ::harness-def def)
      (fail! (str "Harness def does not conform to ::harness-def: "
                  (s/explain-str ::harness-def def))
             {:harness key :def def :explain (s/explain-data ::harness-def def)}))
    (when (contains? def :capture)
      (validate-op-argv! key :capture (:capture def)))
    (when (contains? def :resume)
      (validate-resume-argv! key (:resume def)))
    (swap! (harness-registry) assoc key def)
    {:harness key :def def}))

(defn defalias!
  "Register `name` as an alias (seat) layered over another harness or alias.

  Alias defs take `:alias-of` (required), `:extra-args` appended to the base
  argv before the prompt, `:prompt-prefix` prepended to the run prompt, and
  `:doc`. Aliases are how userland exposes its own named agent seats.

  Writes only the alias (seat) registry, independent of the harness registry, so
  a seat may intentionally carry a tool's name — `defalias! :pi {:alias-of :pi}`
  is a lawful shadow that resolves through the seat and terminates at the tool.
  The def shape is the `::alias-def` spec."
  [name def]
  (let [key (harness-key name)]
    (when-let [unknown (and (map? def) (seq (remove alias-def-keys (keys def))))]
      (fail! "Alias def contains unknown keys" {:alias key :keys (vec unknown)}))
    (when-not (s/valid? ::alias-def def)
      (fail! (str "Alias def does not conform to ::alias-def: "
                  (s/explain-str ::alias-def def))
             {:alias key :def def :explain (s/explain-data ::alias-def def)}))
    (swap! (alias-registry) assoc key def)
    {:alias key :def def}))

(defn resolve-harness
  "Return the effective harness definition for `name`, flattening alias layers.

  Resolution is alias-first: at each hop an unvisited alias shadows a same-named
  harness, so `defalias! :pi {:alias-of :pi}` resolves through the seat and
  terminates at the `pi` tool; otherwise the harness registry answers, otherwise
  the name is missing. A missing name fails `:error-class \"harness-not-found\"`
  and lists both registries' available names (recovery deferral keys off that
  class). A genuine alias cycle fails with a distinct `:error-class
  \"alias-cycle\"` so a real configuration bug never masquerades as the
  transient not-found reload race."
  [name]
  (let [alias-defs @(alias-registry)
        harness-defs @(harness-registry)]
    (loop [key (harness-key name)
           seen #{}
           extra-args []
           prompt-prefix ""]
      (cond
        (and (not (contains? seen key)) (contains? alias-defs key))
        (let [def (get alias-defs key)]
          (recur (harness-key (:alias-of def))
                 (conj seen key)
                 (into (vec (:extra-args def)) extra-args)
                 (str (:prompt-prefix def) prompt-prefix)))

        (contains? harness-defs key)
        (assoc (get harness-defs key)
               :name key
               :extra-args extra-args
               :prompt-prefix prompt-prefix)

        (contains? alias-defs key)
        (fail! "Harness alias chain contains a cycle"
               {:error-class "alias-cycle" :harness (harness-key name) :cycle (conj seen key)})

        :else
        (fail! "Harness not found"
               {:error-class "harness-not-found"
                :harness key
                :available-harnesses (sort (keys harness-defs))
                :available-aliases (sort (keys alias-defs))})))))

(defn- root-harness
  "Return `[root-key root-def]` for the alias chain at `key` under the same
  alias-first rule `resolve-harness` uses, or nil when the chain is dangling or
  cyclic. The listing stays best-effort here; `resolve-harness` is the loud path
  a broken chain fails on."
  [key alias-defs harness-defs]
  (loop [key key
         seen #{}]
    (cond
      (and (not (contains? seen key)) (contains? alias-defs key))
      (recur (harness-key (:alias-of (get alias-defs key))) (conj seen key))
      (contains? harness-defs key)
      [key (get harness-defs key)]
      :else nil)))

(defn harnesses
  "Return registered harness and alias metadata ordered by name.

  The result is the concatenation of both registries' entries sorted by name,
  never merged by name — a same-named tool and seat both appear, distinguished
  by `:kind`. Alias entries carry `:harness` (the resolved root harness name)
  and that root's doc as `:harness-doc` beside their own `:doc`, so one listing
  shows tool-level capabilities together with seat-level capabilities without
  callers re-walking alias chains. Root resolution is best-effort: a broken
  chain omits the `:harness`/`:harness-doc` keys rather than failing the
  listing."
  []
  (let [alias-defs @(alias-registry)
        harness-defs @(harness-registry)
        harness-entries
        (map (fn [[key def]]
               (cond-> {:name (name key) :kind "harness"}
                 (:argv def) (assoc :argv (:argv def))
                 (:doc def) (assoc :doc (:doc def))))
             harness-defs)
        alias-entries
        (map (fn [[key def]]
               (let [[root-key root-def] (root-harness key alias-defs harness-defs)]
                 (cond-> {:name (name key) :kind "alias"
                          :alias-of (name (harness-key (:alias-of def)))}
                   root-key (assoc :harness (name root-key))
                   (:doc root-def) (assoc :harness-doc (:doc root-def))
                   (:doc def) (assoc :doc (:doc def)))))
             alias-defs)]
    (vec (sort-by :name (concat harness-entries alias-entries)))))

(defn register-default-harnesses!
  "Register the shipped harness definitions, keeping any existing entries."
  []
  (let [defaults
        {:claude {:argv ["claude" "-p" "--output-format" "json" "--dangerously-skip-permissions"]
                  :parse :claude-json
                  :prompt-via :stdin
                  :resume ["--resume" :agent-run/session-id]
                  :doc (fmt/reflow "
                        |Claude Code headless: full agentic coding toolset (file edits, shell,
                        |subagents) plus web search/fetch, from a code-focused model family.
                        |The worker prompt rides on stdin (`claude -p` reads
                        |it) so it never lands in the process argv, keeping prompts out of `ps` and
                        |out of the blast radius of any `pkill -f` pattern kill. Skips permission
                        |prompts so the run can drive the strand CLI; redefine with your own argv
                        |to tighten. :claude-json captures agent-run/session-id and :resume continues
                        |that session with `--resume <session-id>`.")}
         :pi {:argv ["pi" "-p" "--mode" "json"]
              :parse :pi-json
              :prompt-via :stdin
              :resume ["--session" :agent-run/session-id]
              :doc (fmt/reflow "
                    |pi headless in JSON event mode: agentic coding toolset that is
                    |provider/model-agnostic — aliases pick the model via --provider/--model,
                    |so model capability notes belong on the aliases.
                    |The worker prompt rides on stdin (`pi -p`
                    |reads it) so it stays out of the process argv, `ps`, and any `pkill -f`
                    |blast radius. :pi-json captures agent-run/session-id and :resume continues
                    |that specific session with `--session <session-id>` (an existing session,
                    |not the create-if-missing --session-id).")}
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
  "Query form selecting all agent run strands."
  [:= [:attr "agent-run/run"] "true"])

(def ^:private pending-query
  [:and [:= :state "active"] run-query [:= [:attr "agent-run/phase"] "pending"]])

(def ^:private running-query
  [:and [:= :state "active"] run-query [:= [:attr "agent-run/phase"] "running"]])

;; no :state filter: a manually closed interactive run still needs its
;; session reaped, so supervision must see closed strands in phase running
(def ^:private interactive-running-query
  [:and run-query
   [:= [:attr "agent-run/mode"] "interactive"]
   [:= [:attr "agent-run/phase"] "running"]])

(def ^:private control-attrs
  #{"agent-run/run" "agent-run/harness" "agent-run/prompt" "agent-run/phase"
    "agent-run/cwd" "agent-run/spawned-by" "agent-run/max-attempts" "agent-run/mode"
    "agent-run/backend" "agent-run/completion" "agent-run/for" "agent-run/reap"
    "agent-run/session" "agent-run/session-id" "agent-run/resumes" "agent-run/error-class"
    "agent-run/recovered-at" "agent-run/recovery-deferred-until"})

(def ^:private usage-attrs
  "Usage attributes written onto a run at completion by finish-run! — distinct
  from control-attrs, which spawn reserves. Declared through the same F4 vocab
  entry but never part of the spawn-time reservation set."
  #{"agent-run/cost-usd" "agent-run/tokens-total" "agent-run/tokens"
    "agent-run/usage-source"})

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
  "Register additional preamble text appended after the engine's worker contract.

  Reload-tolerant, but it distinguishes the two cases the previous fail-loud path
  could not tell apart:

  - Replay (same spool re-running its own registration on `reload!`): identical
    text is a silent no-op (`:replaced false`).
  - Conflict (a second, distinct registrant clashing on the worker contract):
    different text replaces the value AND is recorded durably in the engine's
    `:preamble-conflicts` state (see `preamble-extension-conflicts`), plus a
    stderr warning. It deliberately does not fail: a hard error here would abort a
    `reload!` mid-startup and leave the world with zero ops (the original
    incident), which is worse than a recorded clash. The durable record is the
    fail-loud substitute — an operator/detector can see the conflict long after
    the stderr line has scrolled away, unlike the prior stderr-only signal."
  [text]
  (when-not (and (string? text) (not (str/blank? text)))
    (fail! "Preamble extension must be non-blank text" {:text text}))
  (let [[old _] (reset-vals! (preamble-extension) text)
        replaced? (boolean (and old (not= old text)))]
    (when replaced?
      (swap! (preamble-conflicts-atom) conj
             {:at (now) :previous old :replacement text})
      (warn! "Preamble extension replaced on conflicting re-registration"
             {:previous old :replacement text}))
    {:preamble-extension true :replaced replaced?}))

(defn preamble-extension-conflicts
  "Return the durable record of genuine set-preamble-extension! conflicts.

  Each entry is `{:at <iso-instant> :previous <text> :replacement <text>}` for a
  re-registration that replaced an existing, non-identical preamble extension —
  a cross-spool worker-contract clash. Identical replays are not recorded. The
  record survives for the weaver lifetime (and across `reload!`, carried through
  `migrate-state`) so a conflict stays visible to operators and attention
  detectors after the stderr warning has scrolled off."
  []
  @(preamble-conflicts-atom))

(defn- preamble [run-id prompt-prefix]
  (let [cmd (pinned-strand-command)]
    (str "[agent-run context]\n"
         "You are a headless subagent run managed by the Skein agent-run spool.\n"
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
    (str "[agent-run interactive session context]\n"
         "You are an interactive agent session managed by the Skein agent-run spool.\n"
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

(defn- usage-figure
  "Keep a usage dimension only when it is a present, non-zero measure. A missing
  figure is an absent key and a reported zero is dropped rather than stored, so
  every recorded dimension is one the harness actually spent — a reader never
  mistakes a silent zero for a captured figure (PROP-Ru-001.A3, G3, R3)."
  [n]
  (when (and (number? n) (not (zero? n))) n))

(defn- normalize-usage
  "Fold a raw per-format usage tally into the run-level C1 `:usage` shape
  `{:cost-usd, :tokens-total, :tokens {…}, :usage-source}`, dropping nil/zero
  dimensions so the map carries only what the harness reported. `:tokens-total`
  is the harness's own total; a `:reasoning` breakdown figure rides in `:tokens`
  but is never folded into it, since pi already counts reasoning inside
  `totalTokens` (the double-count trap, PROP-Ru-001.C1, C2)."
  [{:keys [usage-source cost-usd tokens-total tokens]}]
  (let [tokens (reduce-kv (fn [m k v] (if-let [v (usage-figure v)] (assoc m k v) m)) {} tokens)]
    (cond-> {:usage-source usage-source}
      (usage-figure cost-usd) (assoc :cost-usd (usage-figure cost-usd))
      (usage-figure tokens-total) (assoc :tokens-total (usage-figure tokens-total))
      (seq tokens) (assoc :tokens tokens))))

(defn- parse-claude-json [stdout]
  (let [data (json/read-str (str/trim stdout))]
    (when-not (map? data)
      (fail! "claude JSON output was not an object" {:output (subs stdout 0 (min 400 (count stdout)))}))
    (let [usage (get data "usage")
          ;; the four token counts claude reports; a version that omits one
          ;; drops out of the sum rather than being read as zero.
          counts (keep #(get usage %)
                       ["input_tokens" "output_tokens"
                        "cache_creation_input_tokens" "cache_read_input_tokens"])]
      {:result (or (get data "result") "")
       :session-id (get data "session_id")
       :usage (normalize-usage
               {:usage-source "claude-json"
                :cost-usd (get data "total_cost_usd")
                :tokens-total (when (seq counts) (reduce + counts))
                :tokens {:input (get usage "input_tokens")
                         :output (get usage "output_tokens")
                         :cache-write (get usage "cache_creation_input_tokens")
                         :cache-read (get usage "cache_read_input_tokens")}})})))

(defn- pi-usage
  "Fold pi's per-turn usage deltas across an event stream into one run-level
  `:usage` tally. Only `message_end` assistant events carry a turn's finalized
  `usage`, so summing those is the run total; `message_start`/`message_update`
  (cumulative *within* a turn) and `turn_end` (a duplicate of the turn's
  `message_end`) are skipped by design — folding them in would double-count.
  These are deltas summed across turns, never a cumulative take-last
  (PROP-Ru-001.C2, R1). `reasoning` is folded into the breakdown only, never
  `totalTokens` (PROP-Ru-001.C1)."
  [events]
  (let [usages (->> events
                    (filter #(= "message_end" (get % "type")))
                    (keep #(get % "message"))
                    (filter #(= "assistant" (get % "role")))
                    (keep #(get % "usage")))
        sum (fn [f] (reduce (fn [acc u] (if-let [v (f u)] (+ (or acc 0) v) acc)) nil usages))]
    (normalize-usage
     {:usage-source "pi-json"
      :cost-usd (sum #(get-in % ["cost" "total"]))
      :tokens-total (sum #(get % "totalTokens"))
      :tokens {:input (sum #(get % "input"))
               :output (sum #(get % "output"))
               :cache-read (sum #(get % "cacheRead"))
               :cache-write (sum #(get % "cacheWrite"))
               :reasoning (sum #(get % "reasoning"))}})))

(defn- parse-pi-json
  "Parse pi --mode json output: a stream of JSON event lines; the run result is
  the last assistant message carrying text. `:result` is always a string:
  tool_execution_end events also carry a top-level \"result\" holding a tool
  output object, so the bare result-key fallback accepts strings only.

  A terminal provider failure (usage limit, auth, transport) surfaces as
  `stopReason \"error\"` plus `errorMessage` on the last assistant message while
  pi itself still exits 0; return it as `:error` so the caller can fail the run
  instead of closing a hollow done (live incident: runs m5i8k/mdxnn,
  2026-07-10). For an event stream with no assistant text `:result` is blank —
  never the raw stream — so the exit-0 blank-result guard stays reachable."
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
        assistant-messages (->> events
                                (keep #(get % "message"))
                                (filter #(= "assistant" (get % "role"))))
        assistant-text (->> assistant-messages
                            (keep #(not-empty (text-of %)))
                            last)
        terminal-error (let [message (last assistant-messages)
                             em (get message "errorMessage")
                             ;; errorMessage is not contractually a string; a
                             ;; non-string value must still fail the run, not
                             ;; throw into the parse-error path (which would
                             ;; recreate the hollow done this guards against).
                             em-str (cond (string? em) em (some? em) (pr-str em))]
                         (when (or (= "error" (get message "stopReason"))
                                   (not (str/blank? em-str)))
                           (if (str/blank? em-str)
                             "pi turn ended with stopReason error"
                             em-str)))]
    (if (seq events)
      (cond-> {:result (or assistant-text
                           (some #(let [r (get % "result")] (when (string? r) r)) (reverse events))
                           "")
               :session-id (some #(or (when (= "session" (get % "type")) (get % "id"))
                                      (get % "session_id")
                                      (get % "sessionId"))
                                 events)
               :usage (pi-usage events)}
        terminal-error (assoc :error terminal-error))
      {:result (str/trim stdout)
       :usage (pi-usage events)})))

(defn- parse-output [strategy stdout]
  (case (or strategy :raw)
    :raw {:result (str/trim stdout)}
    :claude-json (parse-claude-json stdout)
    :pi-json (parse-pi-json stdout)))

;; ---------------------------------------------------------------------------
;; Spawn engine

(defn- log-dir []
  ;; on-disk log dir keeps the legacy "shuttle" name: existing run records point
  ;; at logs under it, and renaming the layout is a behavior change beyond this rename
  (doto (io/file (get-in (rt) [:metadata :state-dir]) "shuttle")
    (.mkdirs)))

(defn- update-run! [id attributes patch]
  (api/update (rt) id (merge patch {:attributes attributes})))

(defn- mark-failed!
  "Mark a run failed with `error`. `extra` merges additional terminal attributes
  (e.g. `agent-run/error-class`) so recovery can branch on the failure kind."
  ([id error] (mark-failed! id error nil))
  ([id error extra]
   (update-run! id (merge {"agent-run/phase" "failed"
                           "agent-run/error" error
                           "agent-run/finished-at" (now)}
                          extra)
                {})))

(def ^:private ^:dynamic *recovery-harness-deferral-ms*
  "Milliseconds a recovered run may wait for harness aliases to register."
  30000)

(def ^:private recovery-harness-retry-ms 250)

(defn- harness-not-found? [t]
  (= "harness-not-found" (:error-class (ex-data t))))

(defn- recovered-run? [run]
  (some? (sattr run "recovered-at")))

(defn- recovery-deferral-expired? [run]
  (let [recovered-at (java.time.Instant/parse (sattr run "recovered-at"))]
    (not (.isBefore (java.time.Instant/now)
                    (.plusMillis recovered-at *recovery-harness-deferral-ms*)))))

(defn- recovery-deferred? [run]
  (when-let [deferred-until (sattr run "recovery-deferred-until")]
    (.isAfter (Instant/parse deferred-until) (Instant/now))))

(declare claim! launch-run! note! scan!)

(defn- schedule-deferred-recovery!
  "Schedule a recovered run retry on the runtime-owned recovery executor."
  [runtime id]
  (.schedule (recovery-scheduler)
             ^Runnable (fn []
                         (binding [*runtime* runtime]
                           (swap! (in-flight) dissoc id)
                           (when-let [run (first (api/ready runtime [:and pending-query [:= :id id]] {}))]
                             (when (claim! id)
                               (launch-run! runtime run)))))
             (long recovery-harness-retry-ms)
             TimeUnit/MILLISECONDS))

(defn- defer-recovered-missing-harness!
  "Return a recovered run to pending while its alias registration may still be loading."
  [id run t]
  (if (recovery-deferral-expired? run)
    (mark-failed! id (str (ex-message t) (some->> (ex-data t) (str " "))))
    (do
      (swap! (in-flight) assoc id {:phase :deferred-recovery})
      (update-run! id {"agent-run/phase" "pending"
                       "agent-run/error" (str (ex-message t) (some->> (ex-data t) (str " ")))
                       "agent-run/recovery-deferred-until" (str (.plusMillis (Instant/now)
                                                                            recovery-harness-retry-ms))}
                   {})
      (schedule-deferred-recovery! (rt) id))))

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
                             (when (and (= "agent-run" (namespace k))
                                        (str/starts-with? (name k) "handle."))
                               [(subs (name k) (count "handle.")) (str v)]))
                           (:attributes run)))]
    (merge (when-let [session (sattr run "session")] {"session" session})
           stored)))

(defn- sh-quote [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn- ^java.io.File launcher-script-file [id]
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
  can stamp `agent-run/error-class` and recovery can branch to a fresh spawn
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
  handmade `agent-run/resumes` run must not bypass the checks that otherwise live
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
                                           [:= [:attr "agent-run/resumes"] predecessor-id]]
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

(defn- usage->attrs
  "Render the parse layer's keyword-keyed C1 `:usage` map into the string-keyed
  `agent-run/*` completion attributes, carrying only the dimensions the harness
  actually reported. normalize-usage has already dropped every nil/zero figure,
  so an absent key here is spend the run never made — never a stored 0
  (PROP-Ru-001.R3). `:tokens` rides as the nested breakdown map. A run with no
  parsed usage (a `:raw` run) yields no attributes."
  [{:keys [usage-source cost-usd tokens-total tokens]}]
  (cond-> {}
    usage-source (assoc "agent-run/usage-source" usage-source)
    cost-usd (assoc "agent-run/cost-usd" cost-usd)
    tokens-total (assoc "agent-run/tokens-total" tokens-total)
    (seq tokens) (assoc "agent-run/tokens" tokens)))

(defn- finish-run! [id process harness out-file err-file]
  (let [exit (.waitFor ^Process process)
        stdout (read-file-safe out-file)
        current (api/show (rt) id)]
    (swap! (in-flight) dissoc id)
    (when-not (= "failed" (sattr current "phase"))
      (if (zero? exit)
      (let [{:keys [result session-id parse-error error usage]}
            (try
              (parse-output (:parse harness) stdout)
              (catch Exception e
                {:result (str/trim stdout)
                 :parse-error (str (ex-message e))}))
            usage-attrs (usage->attrs usage)]
        (cond
          ;; exit 0 but the harness's own event stream reports a terminal
          ;; provider failure (usage limit, auth, transport): the process
          ;; exiting cleanly does not make the turn a success. Fail loudly and
          ;; retryably instead of closing a done run with no real report.
          error
          (let [stderr (str/trim (read-file-safe err-file))]
            (mark-failed! id
                          (str "harness exited 0 but the final turn errored: " error
                               (when-not (str/blank? stderr) (str "; stderr: " (tail stderr 2000))))
                          (merge (cond-> {"agent-run/exit-code" exit}
                                   session-id (assoc "agent-run/session-id" session-id))
                                 usage-attrs)))

          (str/blank? result)
          ;; exit 0 but no result text: the harness died silently (a transport
          ;; drop mid-turn writes nothing yet still exits 0). A run's result is
          ;; the worker's report, so an empty one is not success — record it
          ;; failed (loud, retryable via agent-failures/retry) rather than
          ;; closing a hollow `done` that dodges every recovery path (TEN-003).
          ;; session-id is preserved for forensics; the failure is deliberately
          ;; not resume-classed, so a plain retry respawns fresh.
          (let [stderr (str/trim (read-file-safe err-file))]
            (mark-failed! id
                          (str "harness exited 0 with an empty result"
                               (when parse-error (str " (parse error: " parse-error ")"))
                               (when-not (str/blank? stderr) (str "; stderr: " (tail stderr 2000))))
                          (cond-> {"agent-run/exit-code" exit}
                            session-id (assoc "agent-run/session-id" session-id))))

          :else
          (update-run! id (merge (cond-> {"agent-run/phase" "done"
                                          "agent-run/exit-code" exit
                                          "agent-run/result" result
                                          "agent-run/finished-at" (now)}
                                   session-id (assoc "agent-run/session-id" session-id)
                                   parse-error (assoc "agent-run/parse-error" parse-error))
                                 usage-attrs)
                       {:state "closed"})))
        (let [stderr (str/trim (read-file-safe err-file))
              detail (if (str/blank? stderr) (str/trim stdout) stderr)]
          (mark-failed! id (str "harness exited " exit ": " (tail detail 2000))
                        {"agent-run/exit-code" exit}))))))

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
  under the run log dir. Returns the file path or fails loudly."
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
            (update-run! id (cond-> {"agent-run/phase" "done"
                                     "agent-run/result" reason
                                     "agent-run/finished-at" (now)}
                              capture-path (assoc "agent-run/log" capture-path)
                              stop-error (assoc "agent-run/teardown-error" stop-error))
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
      (update-run! id {"agent-run/phase" "running"
                       "agent-run/attempt" attempt
                       "agent-run/session" session
                       "agent-run/started-at" (now)}
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
              (update-run! id (into {} (map (fn [[k v]] [(str "agent-run/handle." k) v])) handle) {}))
            (swap! (in-flight) assoc id {:phase :running}))
          (catch Throwable t
            ;; claim the teardown before stopping: once :stop kills the session,
            ;; a concurrent supervise! probe sees it dead and would race its
            ;; generic dead-session error over this launch failure. A lost claim
            ;; means another teardown path (e.g. a reap after the served target
            ;; closed) already owns the run's terminal state: stopping or
            ;; failing it here would stamp over that owner's outcome.
            (if (claim-teardown! id)
              (do (try
                    (let [handle (merge {"session" session}
                                        (try (parse-handle backend-name out) (catch Exception _ {})))
                          stop-argv (splice-op-argv backend-name :stop (:stop backend)
                                                    {:run-id id} handle)]
                      (run-backend-op! backend-name :stop stop-argv cwd))
                    (catch Throwable _ nil))
                  (.delete (launcher-script-file id))
                  (throw t))
              (do (warn! "interactive launch failure lost the teardown claim; deferring to the owning path"
                         {:run id :error (ex-message t)})
                  ;; the owner's terminal outcome stands, but the launch failure
                  ;; must stay strand-visible, not only in weaver stderr
                  (note! id (str "interactive launch failed after another teardown path claimed the run: "
                                 (ex-message t)))))))))))

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
        (update-run! id {"agent-run/phase" "running"
                         "agent-run/attempt" attempt
                         "agent-run/log" (.getPath out-file)
                         "agent-run/started-at" (now)}
                     {})
        (let [process (start-process! argv {:cwd (or (sattr run "cwd") (:cwd harness) (workspace-dir))
                                            :env (:env harness)
                                            :out-file out-file
                                            :err-file err-file
                                            :stdin (when (= :stdin (:prompt-via harness)) prompt)})]
          (reset! process-ref process)
          (swap! (in-flight) assoc id {:phase :running :process process})
          (update-run! id (cond-> {"agent-run/pid" (.pid ^Process process)}
                            (process-start-instant process)
                            (assoc "agent-run/pid-started-at" (process-start-instant process)))
                       {})
          (finish-run! id process harness out-file err-file)))
      (catch Throwable t
        (some-> ^Process @process-ref (.destroy))
        (swap! (in-flight) dissoc id)
        (try
          (if (and (recovered-run? run) (harness-not-found? t))
            (defer-recovered-missing-harness! id run t)
            (mark-failed! id (str (ex-message t) (some->> (ex-data t) (str " ")))
                          (when (= "resume" (:error-class (ex-data t)))
                            {"agent-run/error-class" "resume"})))
          (catch Throwable _
            nil))))))

(defn- launch-run! [runtime run]
  (binding [*runtime* runtime]
    (let [id (:id run)]
      (if (interactive? run)
        (try
          (launch-interactive! id run)
          (catch Throwable t
            ;; persist the terminal error before releasing the in-flight entry:
            ;; released first, a concurrent supervise! probe could claim the
            ;; teardown and overwrite this specific failure with its generic
            ;; dead-session error
            (try
              (mark-failed! id (str (ex-message t) (some->> (ex-data t) (str " ")))
                            (when (= "resume" (:error-class (ex-data t)))
                              {"agent-run/error-class" "resume"}))
              (catch Throwable _
                nil)
              (finally (swap! (in-flight) dissoc id)))))
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
        workers (worker-executor)
        ready-runs (remove recovery-deferred? (api/ready runtime pending-query {}))
        claimed (filterv (comp claim! :id) ready-runs)]
    (doseq [run claimed]
      (.execute workers ^Runnable (fn [] (launch-run! runtime run))))
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
  ^ProcessHandle [run]
  (when-let [pid (sattr run "pid")]
    (when-let [handle ^ProcessHandle (.orElse (ProcessHandle/of (long pid)) nil)]
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
  active so dependents stay blocked) when `agent-run/max-attempts` is spent.

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
                       (do (update-run! id {"agent-run/phase" "exhausted"
                                            "agent-run/error" (str "run exhausted " attempt " of " max-attempts
                                                                 " attempts after weaver crash")}
                                        {})
                           (update acc :exhausted conj id))
                       (do (update-run! id {"agent-run/phase" "pending"
                                            "agent-run/recovered-at" (now)} {})
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
  names), it must carry a captured `agent-run/session-id`, the spawning harness
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
      (fail! "Resume predecessor has no captured agent-run/session-id"
             {:resume predecessor-id :harness harness-name}))
    (when-not (:resume harness-def)
      (fail! "Resume requires a harness that declares a :resume splice"
             {:resume predecessor-id :harness harness-name}))
    (when-let [live (seq (mapv :id (api/list (rt)
                                             [:and [:= :state "active"] run-query
                                              [:= [:attr "agent-run/resumes"] predecessor-id]]
                                             {})))]
      (fail! "Another active run already continues this session"
             {:resume predecessor-id :active live}))))

(defn spawn-run!
  "Create one agent-run strand; the engine spawns it when it becomes ready.

  Opts: `:harness` and `:prompt` required; optional `:title`, `:depends-on`
  (vector of strand ids), `:parent` and `:spawned-by` (each gets a parent-of
  edge placing the run in the graph), `:serves <target-id>` (writes one `serves`
  edge run → target, marking the run a delegation of that target's own work),
  `:cwd`, `:max-attempts`, and extra `:attrs`. Placement and serving are
  orthogonal: a serving run carries both `parent-of` and `serves`, a helper
  carries `parent-of` alone. Interactive sessions pass `:mode :interactive` with
  a required `:backend`, and optionally `:reap` (`auto` tears the session down on
  completion, `manual` leaves it to the human; default auto). An interactive run
  with a `:parent` completes when that strand closes (claim) — its interactive
  completion target `agent-run/for`, distinct from the headless `serves` edge;
  without one it completes when its own run strand is closed (manual-close).

  `:resume <predecessor-run-id>` continues the predecessor's harness session:
  the run is stamped `agent-run/resumes` with a `resumes` annotation edge, and at
  launch the harness `:resume` splice resolves from the predecessor's captured
  attributes ahead of the prompt (see `validate-resume!` for the loud rules).
  Asynchronous: returns the created run strand immediately."
  [{:keys [harness prompt title depends-on parent spawned-by cwd max-attempts attrs
           mode backend reap resume serves]}]
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
        reserved (merge {"agent-run/run" "true"
                         "agent-run/harness" (name (harness-key harness))
                         "agent-run/prompt" prompt
                         "agent-run/phase" "pending"}
                        (when cwd {"agent-run/cwd" cwd})
                        (when spawned-by {"agent-run/spawned-by" spawned-by})
                        (when max-attempts {"agent-run/max-attempts" max-attempts})
                        (when resume {"agent-run/resumes" resume})
                        (when mode
                          (merge {"agent-run/mode" "interactive"
                                  "agent-run/backend" (name (harness-key backend))
                                  "agent-run/completion" (if parent "claim" "manual-close")}
                                 (when parent {"agent-run/for" parent})
                                 (when reap {"agent-run/reap" (name (keyword reap))}))))]
    (when-let [collisions (seq (filter #(or (contains? control-attrs %)
                                            (str/starts-with? (str %) "agent-run/handle."))
                                       (keys (or attrs {}))))]
      (fail! "Run :attrs must not override agent-run control attributes" {:keys (vec collisions)}))
    ;; validate provenance targets before the run exists so a bad parent id
    ;; cannot leave a spawned run behind a thrown edge update
    (doseq [parent-id parent-ids]
      (when-not (api/show (rt) parent-id)
        (fail! "Run parent strand not found" {:id parent-id})))
    (when (and serves (not (api/show (rt) serves)))
      (fail! "Run :serves target not found" {:id serves}))
    (let [run (api/add (rt) {:title (or title (truncate prompt 72))
                             :attributes (merge attrs reserved)
                             :edges (cond-> (mapv (fn [dep] {:type "depends-on" :to dep}) (distinct (or depends-on [])))
                                      resume (conj {:type "resumes" :to resume})
                                      serves (conj {:type "serves" :to serves}))})]
      (doseq [parent-id parent-ids]
        (api/update (rt) parent-id {:edges [{:type "parent-of" :to (:id run)}]}))
      run)))

(defn- parent-of-sources
  "Return strand ids with a parent-of edge to `run-id`, via one indexed fetch."
  [run-id]
  (->> (graph/incoming-edges (rt) [run-id] "parent-of")
       (map :from_strand_id)
       distinct
       vec))

(defn- parents-by-run
  "Map run-id -> its parent-of source ids from one bulk incoming-edge fetch,
  so summarising many runs costs a single query instead of one per run."
  ([run-ids]
   (parents-by-run run-ids graph/incoming-edges))
  ([run-ids incoming-edges-fn]
   (reduce (fn [m {:keys [from_strand_id to_strand_id]}]
             (update m to_strand_id (fnil conj []) from_strand_id))
           {}
           (incoming-edges-fn (rt) (vec run-ids) "parent-of"))))

(defn- serving-targets-by-run
  "Map run-id -> its outgoing `serves` target from one bulk outgoing-edge fetch,
  so summarising many runs resolves serving with a single query instead of one
  per run. A run serves at most one target."
  [run-ids]
  (reduce (fn [m {:keys [from_strand_id to_strand_id]}]
            (assoc m from_strand_id to_strand_id))
          {}
          (graph/outgoing-edges (rt) (vec run-ids) "serves")))

(defn- run-for-target
  "Return the run's serving target: the target of its one outgoing `serves`
  edge, else nil. Serving is the delegation relation — a read-only helper
  carries `parent-of` placement only, no `serves` edge, so this is nil for it."
  [run]
  (first (map :to_strand_id (graph/outgoing-edges (rt) [(:id run)] "serves"))))

(defn runs-serving
  "Runs currently serving strand `target-id`: those with a `serves` edge to it
  that have not been superseded. This is the C5 resolution rule — its unique
  element is the current run serving the target. A run is superseded when it
  carries an incoming `supersedes` edge, equivalently `agent-run/phase
  \"superseded\"`; `supersede-and-respawn!` writes edge and phase together so the
  two criteria stay in lockstep. Read-only helpers carry `parent-of` placement
  with no `serves` edge, so they never appear here."
  [target-id]
  (let [run-ids (mapv :from_strand_id (graph/incoming-edges (rt) [target-id] "serves"))
        superseded-ids (set (map :to_strand_id (graph/incoming-edges (rt) run-ids "supersedes")))]
    (->> run-ids
         (remove superseded-ids)
         (map #(api/show (rt) %))
         (remove #(= "superseded" (sattr % "phase"))))))

(defn- helper-parent
  "Return a helper run's structural target: its `parent-of` parent (from
  `parents`, the run's parent-of source ids), excluding `spawned-by` provenance.
  Helpers carry no `serves` edge, so `spawn --for X` still reads \"for X\" off
  placement."
  [run parents]
  (let [spawned-by (sattr run "spawned-by")]
    (first (remove #(= spawned-by %) parents))))

(defn supersede-and-respawn!
  "Succeed a dead run `old-run-id` with a fresh successor — the sole succession
  path in the engine (PROP-Aep-001.C4). Preserved from the predecessor, engine
  owned: its `serves` target (the successor now serves it), its structural
  `parent-of` placement (the served target for serving runs, the `--for`
  placement for helpers — a serving run carries both edges, C1), its
  `depends-on` edges, its `spawned-by` provenance (the parent-of placement plus
  the `agent-run/spawned-by` attr), and its execution shape (`agent-run/mode`/
  `backend`/`reap` and the interactive completion target for interactive runs,
  `agent-run/max-attempts` always). `:prompt` and `:harness` come from the
  caller, `:cwd` too (else the predecessor's). `:carry-attrs` layers spool-owned
  structural attrs on top — the primitive stays ignorant of the delegation
  vocabulary.

  The successor is fresh: a new run id and strand, `agent-run/phase \"pending\"`,
  no execution residue. `:continuity` (default `:fresh`) severs any session;
  `:resume` continues the predecessor's session by stamping `:resume` on the
  spawn (a `resumes` edge plus `agent-run/resumes`, validated by the resume
  machinery at launch), so `resumes` and `supersedes` stay distinct edges.

  Lineage is recorded in the same call: the predecessor is closed
  `agent-run/phase \"superseded\"`, and the successor gains a `supersedes` edge
  to it (successor --supersedes--> predecessor, the catalog direction) plus an
  `agent-run/supersedes` attr naming it. `runs-serving` keys on the `serves`
  edge and the absence of an incoming `supersedes` edge, which the `superseded`
  phase mirrors because this call writes edge and phase together. Returns the
  successor run strand."
  [old-run-id {:keys [prompt harness cwd carry-attrs continuity]
               :or {continuity :fresh}}]
  (when-not (contains? #{:fresh :resume} continuity)
    (fail! "supersede-and-respawn! :continuity must be :fresh or :resume"
           {:run old-run-id :continuity continuity}))
  (let [predecessor (or (api/show (rt) old-run-id)
                        (fail! "Supersede predecessor not found" {:run old-run-id}))
        interactive? (interactive? predecessor)
        served-target (run-for-target predecessor)
        placement (or served-target
                      (helper-parent predecessor (parent-of-sources old-run-id)))
        deps (mapv :to_strand_id (graph/outgoing-edges (rt) [old-run-id] "depends-on"))
        successor (spawn-run!
                   (cond-> {:harness harness
                            :prompt prompt
                            :title (:title predecessor)
                            :cwd (or cwd (sattr predecessor "cwd"))
                            :depends-on deps}
                     placement (assoc :parent placement)
                     served-target (assoc :serves served-target)
                     (sattr predecessor "spawned-by") (assoc :spawned-by (sattr predecessor "spawned-by"))
                     (sattr predecessor "max-attempts") (assoc :max-attempts (sattr predecessor "max-attempts"))
                     interactive? (assoc :mode :interactive :backend (sattr predecessor "backend"))
                     (and interactive? (sattr predecessor "reap")) (assoc :reap (sattr predecessor "reap"))
                     (and interactive? (sattr predecessor "for")) (assoc :parent (sattr predecessor "for"))
                     (= :resume continuity) (assoc :resume old-run-id)
                     (seq carry-attrs) (assoc :attrs carry-attrs)))]
    ;; Close the predecessor and record lineage only once the successor exists,
    ;; so a spawn that throws (resume validation, missing target) leaves the
    ;; predecessor untouched rather than half-succeeded.
    (api/update (rt) old-run-id {:state "closed" :attributes {"agent-run/phase" "superseded"}})
    (api/update (rt) (:id successor)
                {:attributes {"agent-run/supersedes" old-run-id}
                 :edges [{:type "supersedes" :to old-run-id}]})
    successor))

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
  "Project a run strand into the compact summary shape the op surface returns.

  `:for` resolves serving first: a serving run reads its `serves`-edge target; a
  helper (`parent-of` only) falls back to its structural parent, so `spawn --for
  X` still shows the helper \"for X\". Pass `parents` (the run's parent-of source
  ids) and `served-target` (its `serves` target) to reuse bulk fetches; when
  omitted single indexed lookups resolve them."
  ([run] (run-summary run (parent-of-sources (:id run)) (run-for-target run)))
  ([run parents] (run-summary run parents (run-for-target run)))
  ([run parents served-target]
   (let [target (or served-target (helper-parent run parents))
         base (cond-> {:id (:id run)
                       :title (:title run)
                       :state (:state run)
                       :phase (sattr run "phase")
                       :harness (sattr run "harness")}
                target (assoc :for target)
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
       base))))

(defn- runs*
  [opts incoming-edges-fn]
  (let [{:keys [active for]} opts]
    (try (supervise!) (catch Exception _ nil))
   (let [run-strands (api/list (rt)
                               (if active [:and [:= :state "active"] run-query] run-query)
                               {})
         ;; A run satisfies a --for filter two ways: a serving run has an
         ;; incoming `serves` edge from the target, and a helper has a structural
         ;; `parent-of` edge from it. Narrow to the union up front (two indexed
         ;; edge lookups) rather than summarising every run.
         run-strands (if for
                       (let [children (set (map :to_strand_id
                                                (graph/outgoing-edges (rt) [for] "parent-of")))
                             servers (set (map :from_strand_id
                                               (graph/incoming-edges (rt) [for] "serves")))
                             relevant (into children servers)]
                         (filterv #(relevant (:id %)) run-strands))
                       run-strands)
         run-ids (mapv :id run-strands)
         parents (parents-by-run run-ids incoming-edges-fn)
         serving (serving-targets-by-run run-ids)
         summaries (mapv #(run-summary % (get parents (:id %) []) (get serving (:id %))) run-strands)]
      (if for
        (filterv #(= for (:for %)) summaries)
        summaries))))

(defn runs
  "Return summaries of agent-run runs; opts may filter to `:active` or `:for`.
  Listing doubles as an interactive liveness checkpoint (there is no
  background poller): dead sessions are failed here, best-effort."
  ([] (runs {}))
  ([{:keys [active for]}]
   (runs* {:active active :for for} graph/incoming-edges)))

;; ---------------------------------------------------------------------------
;; Spend aggregation

(defn- run-duration-ms
  "Wall-time in milliseconds between a run's `started-at` and `finished-at`
  timestamps, or nil when either is absent. Duration is derived from the engine's
  own timestamps for every format including `:raw` (PROP-Ru-001.C4), so a run that
  recorded no cost or tokens still reports how long it ran; a run with no
  `finished-at` (never completed) derives no duration."
  [run]
  (let [started (sattr run "started-at")
        finished (sattr run "finished-at")]
    (when (and started finished)
      (- (.toEpochMilli (Instant/parse finished))
         (.toEpochMilli (Instant/parse started))))))

(defn- spend-row
  "Project a run strand into the C7 per-run spend row: identity and phase, the
  recorded usage figures (an unreported dimension stays nil, never 0), the nested
  token breakdown, and the timestamp-derived wall-time."
  [run]
  {:id (:id run)
   :harness (sattr run "harness")
   :phase (sattr run "phase")
   :cost-usd (sattr run "cost-usd")
   :tokens-total (sattr run "tokens-total")
   :tokens (sattr run "tokens")
   :duration-ms (run-duration-ms run)
   :started-at (sattr run "started-at")
   :finished-at (sattr run "finished-at")})

(defn- sum-present
  "Sum the non-nil values, or nil when none is present. A wholly-unreported
  dimension stays null rather than collapsing to 0, so spend is never inflated
  (PROP-Ru-001.R3)."
  [xs]
  (let [present (remove nil? xs)]
    (when (seq present) (reduce + present))))

(defn- spend-totals
  "Aggregate spend rows into `{:runs :cost-usd :tokens-total :duration-ms}`. The
  run count is every row; each figure sums only its non-nil contributors, so a
  raw/pre-feature run adds to the count and (when timed) the duration without
  faking a cost or token figure it never reported."
  [rows]
  {:runs (count rows)
   :cost-usd (sum-present (map :cost-usd rows))
   :tokens-total (sum-present (map :tokens-total rows))
   :duration-ms (sum-present (map :duration-ms rows))})

(defn- spend-group-key
  "The grouping bucket for a row: its harness, or its started-at calendar day
  (the ISO date prefix) when grouping by day."
  [group-dim row]
  (case group-dim
    :harness (:harness row)
    :day (some-> (:started-at row) (subs 0 10))))

(defn- in-window?
  "True when `started` (an ISO instant string) falls in the optional
  `[since until]` window. ISO-8601 UTC instants order lexicographically, so a
  string compare is the window test; a row with no started-at is outside any
  bounded window."
  [since until started]
  (and (or (nil? since) (and started (<= 0 (compare started since))))
       (or (nil? until) (and started (>= 0 (compare started until))))))

(defn- spend*
  [opts list-fn]
  (let [{:keys [harness since until] gb :group-by} opts
        group-dim (keyword (or gb :harness))
        _ (when-not (contains? #{:harness :day} group-dim)
            (fail! "spend :group-by must be :harness or :day" {:group-by gb}))
        rows (->> (list-fn (rt) run-query {})
                  (map spend-row)
                  (filter (fn [row]
                            (and (or (nil? harness) (= harness (:harness row)))
                                 (in-window? since until (:started-at row)))))
                  (sort-by (juxt (comp str :started-at) :id))
                  vec)
        groups (->> rows
                    (group-by #(spend-group-key group-dim %))
                    (map (fn [[k grp]] (assoc (spend-totals grp) :key k)))
                    (sort-by (comp str :key))
                    vec)]
    {:operation "agent-spend"
     :filters (cond-> {:group-by group-dim}
                harness (assoc :harness harness)
                since (assoc :since since)
                until (assoc :until until))
     :totals (spend-totals rows)
     :groups groups
     :runs rows}))

(defn spend
  "Aggregate recorded agent-run spend into the C7 read shape (PROP-Ru-001.C7):
  `{:operation \"agent-spend\", :filters, :totals, :groups, :runs}`. Optional
  opts filter and shape the report:

    :harness   restrict to one harness/alias name
    :since     lower ISO-instant bound on the run's started-at (inclusive)
    :until     upper ISO-instant bound on the run's started-at (inclusive)
    :group-by  :harness (default) or :day (buckets by the started-at date)

  Every run contributes its count and its timestamp-derived duration for every
  format including `:raw`; a run that recorded no cost/tokens contributes null for
  those, and every sum skips nils so a missing figure is never inflated to 0. The
  read costs one bulk query for many runs, never one per run (PROP-Ru-001.R4)."
  ([] (spend {}))
  ([opts] (spend* opts api/list)))

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
       (let [strands (graph/strands-by-ids (rt) (vec ids))]
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
  `agent-run/log`, and return {:id :path :text}. Works on live runs (a
  coordinator peek without attaching) and, when the harness capture source
  outlives the session (hook-written logs), on finished runs too. Fails
  loudly when the run is not interactive or no capture op is configured."
  [id]
  (let [run (or (api/show (rt) id) (fail! "Run not found" {:id id}))]
    (when-not (interactive? run)
      (fail! "Capture applies to interactive runs; headless runs already write agent-run/log" {:id id}))
    (let [backend (resolve-backend (harness-key (sattr run "backend")))
          path (run-capture-op! id run backend)]
      (update-run! id {"agent-run/log" path} {})
      {:id id :path path :text (slurp path)})))

(defn note!
  "Append an immutable note strand to `target-id`'s memory via the blessed
  `skein.api.notes.alpha/note!`, threading this spool's runtime.

  The note is born closed (memory, not work), linked to the target by a `notes`
  edge alone — no `note/for` attribute — and carries optional `note/by`/`note/round`."
  ([target-id text] (note! target-id text {}))
  ([target-id text opts] (notes-alpha/note! (rt) target-id text opts)))

(defn notes
  "Return `target-id`'s notes in `note/at` order, optionally one `:round`, via
  the blessed `skein.api.notes.alpha/notes` threading this spool's runtime.

  Walks the incoming `notes` edges to the target, so it reads every writer's
  notes regardless of decorating attrs."
  ([target-id] (notes target-id {}))
  ([target-id opts] (notes-alpha/notes (rt) target-id opts)))

;; ---------------------------------------------------------------------------
;; Review contract state

(def generic-review-contract
  "Default contract text for independent agent-run reviews."
  (fmt/reflow "
   |Review the target read-only. Report prioritized findings with file:line
   |references when applicable. Do not modify files or close strands. Append
   |your findings as a note on the target strand, then end with the same
   |findings as your final result."))



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
  "Install the agent-run engine into the active weaver: default harnesses, the graph
  event listener, crash reconciliation, and a first scan, and declare the
  `agent-run/*` attribute-namespace vocabulary this spool owns."
  []
  (let [runtime (rt)]
    (register-default-harnesses!)
    (register-default-backends!)
    (vocab/declare! runtime
                    {:kind :attr-namespace
                     :name "agent-run"
                     :owner :skein/spools-shuttle
                     :keys (vec (sort (into control-attrs usage-attrs)))
                     :doc "Agent-run engine control attributes on run strands, reserved by spawn-run! and the spawn/supervision engine, plus the usage attributes finish-run! records at completion."})
    (events/register! runtime :agent-run/engine
                      #{:strand/added :strand/updated :batch/applied
                        :strand/burned :strand/superseded}
                      'skein.spools.agent-run/on-event
                      {:spool "agent-run"})
    (let [recovered (reconcile!)]
      {:installed true
       :namespace 'skein.spools.agent-run
       :harnesses (mapv :name (harnesses))
       :backends (mapv :name (backends))
       :recovered recovered})))
