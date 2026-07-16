(ns skein.spools.bench
  "Trusted userland spool for deterministic, containerized benchmarking of
  coding-agent harnesses.

  A bench run is a strand graph: a run root, one entry strand per matrix cell,
  and a judge strand that depends on every entry. Each entry executes its agent
  inside a fresh container against a pristine checkout of a pinned repo+sha;
  when the container exits the engine deterministically extracts metrics and
  stamps them on the entry strand, then closes it. Closing every entry unblocks
  the judge — a decoupled fulfilment seam (an agent run by default, but
  fulfillable by any mechanism) that writes a comparative verdict.

  Setup and measurement are code (this namespace plus `skein.spools.bench.exec`);
  only judgment is a model. Two registries — harness definitions and suites — are
  weaver-lifetime trusted config validated loudly at registration. All public
  functions take `runtime` explicitly and keep state runtime-owned via
  `skein.api.runtime.alpha/spool-state` (shared-spool rules); the versioned
  state carries the bounded executor, the registries, and in-flight container
  tracking, and its `:close-fn` kills live containers on runtime stop."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.bench.exec :as exec]
            [skein.spools.bench.metrics :as metrics]
            [skein.api.format.alpha :as fmt]
            [skein.spools.agent-run :as agent-run]
            [skein.api.spool.alpha :refer [fail! reject-unknown-keys! require-valid! attr-get]])
  (:import [java.io File]
           [java.util.concurrent ExecutorService Executors Semaphore ThreadFactory TimeUnit]))

;; ---------------------------------------------------------------------------
;; Runtime-owned state

(def ^:private state-version
  "Shape version for bench's runtime spool-state map. Bump whenever `new-state`'s
  key set changes: spool-state survives `reload!`, so a post-upgrade reload would
  otherwise reuse a preserved map missing a new key. The
  `state-shape-matches-declared-version` test fails loudly if `new-state` and
  this version drift apart."
  3)

(defn- ^ThreadFactory daemon-thread-factory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable (str prefix "-" (swap! counter inc)))
          (.setDaemon true))))))

(defn- new-state []
  (let [workers (Executors/newCachedThreadPool (daemon-thread-factory "bench"))
        engine (atom nil)
        in-flight (atom {})]
    {:harnesses (atom {})
     :suites (atom {})
     :extractors (atom {})
     :engine engine
     :executor workers
     ;; run-id -> Semaphore: one shared parallelism gate per run so retries
     ;; contend for the same run's :parallel budget as its original entries.
     :semaphores (atom {})
     ;; entry-strand-id -> {:container name :process Process :run-id id}
     :in-flight in-flight
     :close-fn (fn []
                 (doseq [[_ {:keys [container process]}] @in-flight]
                   (when container (exec/kill-container! @engine container))
                   (when process (.destroyForcibly ^Process process)))
                 (.shutdownNow workers)
                 (when-not (.awaitTermination workers 1000 TimeUnit/MILLISECONDS)
                   (fail! "Bench executor did not stop" {})))}))

(defn- state [runtime]
  (runtime/spool-state runtime ::state {:version state-version} new-state))

(defn- harnesses-atom [runtime] (:harnesses (state runtime)))
(defn- suites-atom [runtime] (:suites (state runtime)))
(defn- extractors-atom [runtime] (:extractors (state runtime)))
(defn- engine-atom [runtime] (:engine (state runtime)))
(defn- semaphores-atom [runtime] (:semaphores (state runtime)))
(defn- in-flight-atom [runtime] (:in-flight (state runtime)))
(defn- ^ExecutorService executor [runtime] (:executor (state runtime)))

(defn- ^Semaphore run-semaphore!
  "Return the shared parallelism semaphore for `run-id`, creating it once at
  `parallel` permits and reusing it thereafter so a retry contends for the same
  run's :parallel budget as its still-in-flight original entries."
  [runtime run-id parallel]
  (let [a (semaphores-atom runtime)]
    (or (get @a run-id)
        (get (swap! a (fn [m] (if (contains? m run-id) m (assoc m run-id (Semaphore. (int parallel))))))
             run-id))))

(defn- drop-semaphore! [runtime run-id]
  (swap! (semaphores-atom runtime) dissoc run-id))

;; ---------------------------------------------------------------------------
;; Specs (closed key sets enforced separately with reject-unknown-keys!)

(s/def ::non-blank (s/and string? (complement str/blank?)))
(s/def ::string-vec (s/coll-of string? :kind vector?))
(s/def ::sha (s/and string? #(re-matches #"[0-9a-f]{40}" %)))

(s/def :bench.harness/image ::non-blank)
(s/def :bench.harness/argv (s/and ::string-vec seq))
;; `:prompt-via` is agent-run's key and enum verbatim, but bench's default
;; DIVERGES: bench defaults :stdin where `agent-run/register-harness!` defaults
;; :arg. Container entries are non-interactive and prompts routinely exceed a
;; safe argv, so :stdin is the safe default here; an entry that needs :arg says
;; so explicitly. Read `(or (:prompt-via harness-def) :stdin)` in `run-entry!`.
(s/def :bench.harness/prompt-via #{:stdin :arg})
(s/def :bench.harness/model-flag string?)
(s/def :bench.harness/thinking-flag string?)
(s/def :bench.harness/env (s/map-of string? string?))
(s/def :bench.harness.auth/env (s/coll-of string? :kind vector?))
(s/def :bench.harness.auth.mount/host string?)
(s/def :bench.harness.auth.mount/container string?)
(s/def :bench.harness.auth/mount
  (s/keys :req-un [:bench.harness.auth.mount/host :bench.harness.auth.mount/container]))
(s/def :bench.harness.auth/mounts (s/coll-of :bench.harness.auth/mount :kind vector?))
(s/def :bench.harness/auth (s/keys :opt-un [:bench.harness.auth/env :bench.harness.auth/mounts]))
(s/def :bench.harness/extractor keyword?)
(s/def :bench.harness/doc string?)
(def ^:private harness-def-keys
  #{:image :argv :prompt-via :model-flag :thinking-flag :env :auth :extractor :doc})
(s/def ::harness-def
  (s/keys :req-un [:bench.harness/image :bench.harness/argv]
          :opt-un [:bench.harness/prompt-via :bench.harness/model-flag :bench.harness/thinking-flag
                   :bench.harness/env :bench.harness/auth :bench.harness/extractor :bench.harness/doc]))

(s/def :bench.cell/harness keyword?)
(s/def :bench.cell/model string?)
(s/def :bench.cell/thinking string?)
(s/def :bench.cell/prompt keyword?)
(s/def :bench.cell/slug ::non-blank)
(s/def :bench.cell/env (s/map-of string? string?))
(def ^:private cell-keys #{:harness :model :thinking :prompt :slug :env})
(s/def ::cell
  (s/keys :req-un [:bench.cell/harness]
          :opt-un [:bench.cell/model :bench.cell/thinking :bench.cell/prompt
                   :bench.cell/slug :bench.cell/env]))

(s/def :bench.prompt/path string?)
(s/def ::prompt-value (s/or :text string? :ref (s/keys :req-un [:bench.prompt/path])))
(s/def :bench.file/content string?)
(s/def :bench.file/path string?)
(s/def :bench.file/dir string?)
(s/def ::file-value
  (s/or :content (s/keys :req-un [:bench.file/content])
        :path (s/keys :req-un [:bench.file/path])
        :dir (s/keys :req-un [:bench.file/dir])))

(s/def :bench.suite/repo ::non-blank)
(s/def :bench.suite/sha ::sha)
(s/def :bench.suite/rev ::non-blank)
(s/def :bench.suite/prompts (s/map-of keyword? ::prompt-value))
(s/def :bench.suite/prompt string?)
(s/def :bench.suite/setup ::string-vec)
(s/def :bench.suite/validation ::string-vec)
(s/def :bench.suite/files (s/map-of string? ::file-value))
(s/def :bench.suite/remove ::string-vec)
(s/def :bench.suite/entries (s/and (s/coll-of ::cell :kind vector?) seq))
(s/def :bench.suite/parallel pos-int?)
(s/def :bench.suite/timeout-secs pos-int?)
(s/def :bench.judge/harness (s/or :kw keyword? :str ::non-blank))
(s/def :bench.judge/external #{true})
(s/def :bench.judge/contract string?)
(s/def ::judge-value
  (s/or :none #{:none}
        ;; exactly-one-of :harness/:external is enforced by validate-judge!,
        ;; which reject-unknown-keys! also holds closed
        :spec (s/keys :opt-un [:bench.judge/harness :bench.judge/external :bench.judge/contract])))
(s/def :bench.suite/judge ::judge-value)
(def ^:private suite-def-keys
  #{:repo :sha :rev :prompts :prompt :setup :validation :files :remove
    :entries :parallel :timeout-secs :judge})
(s/def ::suite-def
  (s/keys :req-un [:bench.suite/repo :bench.suite/entries :bench.suite/judge]
          :opt-un [:bench.suite/sha :bench.suite/rev :bench.suite/prompts :bench.suite/prompt
                   :bench.suite/setup :bench.suite/validation :bench.suite/files
                   :bench.suite/remove :bench.suite/parallel :bench.suite/timeout-secs]))

;; ---------------------------------------------------------------------------
;; Agent + suite + extractor registries

(defn- reg-key [k]
  (cond
    (keyword? k) k
    (and (string? k) (not (str/blank? k))) (keyword k)
    :else (fail! "bench registry key must be a keyword or non-blank string" {:key k})))

(defn register-harness!
  "Register a bench harness definition under `k` for this `runtime`.

  Says how one tool runs inside a container: `:image` and `:argv` are required;
  `:prompt-via`, `:model-flag`, `:thinking-flag`, `:env`, `:auth`, `:extractor`,
  and `:doc` are optional. Fails loudly on unknown keys (TEN-003) or a shape the
  spec rejects. Returns the stored definition.

  `:prompt-via` is `agent-run/register-harness!`'s key and `#{:stdin :arg}` enum
  verbatim, but its DEFAULT diverges: bench defaults `:stdin`, agent-run
  defaults `:arg`. Container entries are non-interactive and prompts routinely
  exceed a safe argv, so a definition that needs `:arg` must say so explicitly.

  A bench harness is NOT a `skein.spools.agent-run` harness, and the two
  registries are not interchangeable. A bench harness is a *container*
  definition (`:image`/`:auth`/`:model-flag`/`:thinking-flag`/`:extractor`)
  resolved by bench's own registry to run one entry cell; an agent-run harness
  is a *host process* definition (`:parse`/`:resume`/`:preamble?`) resolved by
  `agent-run/resolve-harness`. A bench harness cannot be passed to
  `agent-run/spawn-run!`, and an agent-run harness cannot run an entry. Both
  words appear in one suite map: an entry cell's `:harness` resolves here, while
  the suite's `:judge :harness` resolves agent-run's registry."
  [runtime k definition]
  (reject-unknown-keys! "bench/register-harness!" harness-def-keys definition)
  (require-valid! ::harness-def definition "bench harness definition is invalid")
  (let [key (reg-key k)]
    (swap! (harnesses-atom runtime) assoc key (assoc definition :name key))
    (get @(harnesses-atom runtime) key)))

(def ^:private judge-spec-keys #{:harness :external :contract})

(defn- validate-judge!
  "Validate a suite's `:judge` value beyond the spec: a spec-map judge is closed
  to `#{:harness :external :contract}` and declares exactly one of
  `:harness`/`:external` (the two fulfilment modes). `:none` passes untouched.
  Fails loudly (TEN-003)."
  [judge]
  (when-not (= :none judge)
    (reject-unknown-keys! "bench suite :judge" judge-spec-keys judge)
    (when-not (= 1 (count (filter judge [:harness :external])))
      (fail! "bench suite :judge must declare exactly one of :harness or :external" {:judge judge}))))

(defn- validate-slugs!
  "Compute each cell's slug and fail loudly on a collision (TEN-003)."
  [entries single-prompt?]
  (let [with-slugs (mapv (fn [cell]
                           (assoc cell :slug
                                  (or (:slug cell)
                                      (->> [(name (:harness cell)) (:model cell) (:thinking cell)
                                            (when-not single-prompt? (some-> (:prompt cell) name))]
                                           (remove nil?)
                                           (str/join "-")))))
                         entries)
        slugs (mapv :slug with-slugs)
        dups (->> (frequencies slugs) (filter (fn [[_ n]] (> n 1))) (map key) vec)]
    (when (seq dups)
      (fail! "bench suite entry slugs collide; set :slug explicitly" {:duplicates dups}))
    with-slugs))

(defn- normalize-suite
  "Validate `definition` and return its internal normalized form.

  Enforces the closed key set, the spec, exactly-one-of `:sha`/`:rev`,
  exactly-one-of `:prompts`/`:prompt`, that every multi-prompt entry names a
  known prompt, and unique entry slugs. The single-prompt sugar becomes a
  `:prompts {::single ...}` map with `:single-prompt? true`."
  [definition]
  ;; A registered suite is stored with an internal :name; strip it before
  ;; validation so re-normalizing a stored suite (run!/retry!) does not trip the
  ;; closed key set.
  (let [definition (dissoc definition :name)]
    (reject-unknown-keys! "bench/register-suite!" suite-def-keys definition)
    (require-valid! ::suite-def definition "bench suite definition is invalid")
    (when-not (= 1 (count (filter definition [:sha :rev])))
      (fail! "bench suite must declare exactly one of :sha or :rev" {:sha (:sha definition) :rev (:rev definition)}))
    (when-not (= 1 (count (filter definition [:prompts :prompt])))
      (fail! "bench suite must declare exactly one of :prompts or :prompt" {}))
    (validate-judge! (:judge definition))
    (let [single-prompt? (contains? definition :prompt)
          prompts (if single-prompt? {::single (:prompt definition)} (:prompts definition))
          entries (:entries definition)]
      (doseq [cell entries]
        (if single-prompt?
          (when (:prompt cell)
            (fail! "bench single-prompt suite entries must not name a :prompt" {:cell cell}))
          (let [p (or (:prompt cell) (fail! "bench multi-prompt suite entry must name a :prompt" {:cell cell}))]
            (when-not (contains? prompts p)
              (fail! "bench entry names an unknown prompt" {:prompt p :available (vec (keys prompts))})))))
      {:repo (:repo definition)
       :sha (:sha definition)
       :rev (:rev definition)
       :setup (:setup definition)
       :validation (:validation definition)
       :files (:files definition)
       :remove (:remove definition)
       :parallel (or (:parallel definition) 2)
       :timeout-secs (or (:timeout-secs definition) 3600)
       :judge (:judge definition)
       :single-prompt? single-prompt?
       :prompts prompts
       :entries (validate-slugs! entries single-prompt?)})))

(defn register-suite!
  "Register a benchmark suite under `k` for this `runtime`.

  A suite is the matrix plus its deterministic starting state. Validated loudly
  at registration (closed key set, spec, one-of `:sha`/`:rev` and
  `:prompts`/`:prompt`, unique slugs). Stores the raw definition; `run!`
  normalizes it. Returns the stored definition."
  [runtime k definition]
  (normalize-suite definition)
  (let [key (reg-key k)]
    (swap! (suites-atom runtime) assoc key (assoc definition :name key))
    (get @(suites-atom runtime) key)))

(defn register-extractor!
  "Register a metrics extractor `f` under `k` for this `runtime`.

  `f` is `(fn [ctx] -> partial metrics map)`; `ctx` carries the entry dir paths
  and parsed stdout. Its return is validated against the closed §7 metrics schema
  before merging — nonconforming keys/values are dropped and recorded under
  `:extraction-warnings` rather than laundered onto `bench/*` attrs (the entry
  still completes). The shipped `:claude`/`:pi`/`:codex`/`:generic` extractors
  register through this registry; userland extends it. Fails loudly when `f` is
  not a function."
  [runtime k f]
  (when-not (ifn? f)
    (fail! "bench extractor must be a function" {:key k}))
  (let [key (reg-key k)]
    (swap! (extractors-atom runtime) assoc key f)
    key))

(defn set-engine!
  "Override the detected container engine with `argv` (a prefix vector speaking
  the docker/podman `run`/`inspect`/`kill` dialect), e.g. `[\"podman\"]`.

  Trusted config pins the engine; tests inject a fake-engine script this way."
  [runtime argv]
  (when-not (and (vector? argv) (seq argv) (every? string? argv))
    (fail! "bench engine must be a non-empty vector of strings" {:argv argv}))
  (reset! (engine-atom runtime) argv)
  argv)

(defn engine
  "Return the resolved container engine argv prefix, or nil when none is set."
  [runtime]
  @(engine-atom runtime))

(defn harnesses
  "Return registered bench harness definitions for `runtime`, sorted by key."
  [runtime]
  (->> @(harnesses-atom runtime) vals (sort-by (comp str :name)) vec))

(defn suites
  "Return registered suite definitions for `runtime`, sorted by key."
  [runtime]
  (->> @(suites-atom runtime) vals (sort-by (comp str :name)) vec))

(defn extractors
  "Return the registered extractor keys for `runtime`, sorted."
  [runtime]
  (->> @(extractors-atom runtime) keys (sort-by str) vec))

(defn cross
  "Return the cross-product of axis maps as an explicit vector of entry cells.

  `(cross {:harness [:claude :codex]} {:prompt [:baseline :strict]})` expands to
  the four `{:harness .. :prompt ..}` cells. A convenience for authoring suites;
  the persisted suite always holds explicit entries."
  [& axes]
  (reduce (fn [cells axis]
            (for [cell cells
                  [k vs] axis
                  v vs]
              (assoc cell k v)))
          [{}]
          axes))

;; ---------------------------------------------------------------------------
;; Extractors: :generic ships inline; :claude/:pi/:codex live in bench.metrics

(defn- generic-extractor
  "The `:generic` metrics extractor: exit, duration, diff, and validation only.

  Those already ride in the engine's post-exit collection, so this contributes
  no extra keys; it exists so every harness def resolves an extractor by key."
  [_ctx]
  {})

(defn- register-default-extractors!
  "Register the shipped `:claude`/`:pi`/`:codex` extractors as install-time
  defaults, leaving any extractor a trusted config already registered under the
  same key untouched (user registration wins). Idempotent across reloads."
  [runtime]
  (let [reg (extractors-atom runtime)]
    (doseq [[k f] metrics/shipped]
      (when-not (contains? @reg k)
        (swap! reg assoc k f)))))

;; ---------------------------------------------------------------------------
;; Paths

(defn- ^File data-root [runtime]
  (io/file (get-in runtime [:metadata :state-dir]) "bench"))

(defn- ^File run-dir [runtime run-id]
  (io/file (data-root runtime) run-id))

(defn- workspace-root
  "Return the host root against which suite `:files` relative paths resolve.

  Matches the repo-root/.skein layout: the config-dir's parent when it is named
  `.skein`, else the config-dir itself."
  [runtime]
  (let [cfg (io/file (get-in runtime [:metadata :config-dir]))]
    (if (= ".skein" (.getName cfg)) (.getParent cfg) (.getPath cfg))))

;; ---------------------------------------------------------------------------
;; Prompt / harness-command resolution

(defn- resolve-prompt-text [runtime prompts slug]
  (let [v (get prompts slug)]
    (cond
      (string? v) v
      (:path v) (slurp (let [f (io/file (:path v))]
                         (if (.isAbsolute f) f (io/file (workspace-root runtime) (:path v)))))
      :else (fail! "bench prompt is neither text nor {:path}" {:slug slug :value v}))))

(defn- harness-command
  "Return the in-container agent argv with the cell's `:model`/`:thinking`
  splices applied per the harness def's flags."
  [harness-def cell]
  (cond-> (vec (:argv harness-def))
    (and (:model cell) (:model-flag harness-def))
    (into [(:model-flag harness-def) (:model cell)])
    (and (:thinking cell) (:thinking-flag harness-def))
    (into [(:thinking-flag harness-def) (:thinking cell)])))

(defn- validate-cell-axes!
  "Fail loudly (TEN-003) when `cell` names a `:model`/`:thinking` axis its
  `harness-def` cannot splice for lack of the matching flag.

  Without this the axis would silently no-op and benchmark the agent's default
  config under an entry stamped with the requested — but never applied — axis,
  destroying the comparison guarantee."
  [harness-def cell]
  (when (and (:model cell) (not (:model-flag harness-def)))
    (fail! "bench entry sets :model but its harness def declares no :model-flag"
           {:harness (:harness cell) :model (:model cell)}))
  (when (and (:thinking cell) (not (:thinking-flag harness-def)))
    (fail! "bench entry sets :thinking but its harness def declares no :thinking-flag"
           {:harness (:harness cell) :thinking (:thinking cell)})))

;; ---------------------------------------------------------------------------
;; Extracted-metrics validation (README §7 schema; closed at the top level)

(s/def :bench.metrics/exit int?)
(s/def :bench.metrics/duration-ms nat-int?)
(s/def :bench.metrics/cost-usd (s/and number? (complement neg?)))
(s/def :bench.metrics.tokens/input nat-int?)
(s/def :bench.metrics.tokens/output nat-int?)
(s/def :bench.metrics.tokens/cache-read nat-int?)
(s/def :bench.metrics.tokens/cache-write nat-int?)
(s/def :bench.metrics/tokens
  (s/keys :opt-un [:bench.metrics.tokens/input :bench.metrics.tokens/output
                   :bench.metrics.tokens/cache-read :bench.metrics.tokens/cache-write]))
(s/def :bench.metrics/turns nat-int?)
(s/def :bench.metrics.tools/file-reads nat-int?)
(s/def :bench.metrics.tools/file-writes nat-int?)
(s/def :bench.metrics.tools/file-edits nat-int?)
(s/def :bench.metrics.tools/bash nat-int?)
(s/def :bench.metrics.tools/other nat-int?)
(s/def :bench.metrics.tools/total nat-int?)
(s/def :bench.metrics/tools
  (s/keys :opt-un [:bench.metrics.tools/file-reads :bench.metrics.tools/file-writes
                   :bench.metrics.tools/file-edits :bench.metrics.tools/bash
                   :bench.metrics.tools/other :bench.metrics.tools/total]))
(s/def :bench.metrics/tool-errors nat-int?)
(s/def :bench.metrics.diff/files nat-int?)
(s/def :bench.metrics.diff/insertions nat-int?)
(s/def :bench.metrics.diff/deletions nat-int?)
(s/def :bench.metrics/diff
  (s/keys :opt-un [:bench.metrics.diff/files :bench.metrics.diff/insertions
                   :bench.metrics.diff/deletions]))
(s/def :bench.metrics.validation/exit int?)
(s/def :bench.metrics.validation/cmd string?)
(s/def :bench.metrics/validation
  (s/keys :opt-un [:bench.metrics.validation/exit :bench.metrics.validation/cmd]))
(s/def :bench.metrics/extraction-warnings (s/coll-of string? :kind sequential?))

(def ^:private metrics-key-specs
  "The closed §7 metrics schema: each allowed key mapped to its value spec. An
  extractor's return is validated key-by-key against this; anything not here is
  an unknown key."
  {:exit :bench.metrics/exit
   :duration-ms :bench.metrics/duration-ms
   :cost-usd :bench.metrics/cost-usd
   :tokens :bench.metrics/tokens
   :turns :bench.metrics/turns
   :tools :bench.metrics/tools
   :tool-errors :bench.metrics/tool-errors
   :diff :bench.metrics/diff
   :validation :bench.metrics/validation
   :extraction-warnings :bench.metrics/extraction-warnings})

(defn- sanitize-extracted
  "Validate an extractor's return against the closed §7 metrics schema.

  Keeps only conforming keys and folds every dropped key/value into
  `:extraction-warnings` so a buggy or third-party extractor cannot launder junk
  onto `bench/*` attrs — but never fails the entry (README §7). A non-map return
  is discarded wholesale with a warning."
  [extracted]
  (if-not (map? extracted)
    {:extraction-warnings [(str "extractor returned a non-map value; ignored: " (pr-str extracted))]}
    (reduce-kv
     (fn [acc k v]
       (cond
         (= k :extraction-warnings)
         (if (s/valid? :bench.metrics/extraction-warnings v)
           (update acc :extraction-warnings (fnil into []) v)
           (update acc :extraction-warnings (fnil conj [])
                   "extractor :extraction-warnings was not a list of strings; dropped"))
         (not (contains? metrics-key-specs k))
         (update acc :extraction-warnings (fnil conj [])
                 (str "extractor returned unknown metric key " k "; dropped"))
         (s/valid? (get metrics-key-specs k) v)
         (assoc acc k v)
         :else
         (update acc :extraction-warnings (fnil conj [])
                 (str "extractor metric " k " failed validation; dropped"))))
     {}
     extracted)))

;; ---------------------------------------------------------------------------
;; Metrics flattening

(defn- metric-attrs
  "Flatten a normalized metrics map onto `bench/*` strand attributes (§7),
  omitting keys the metrics map does not carry."
  [metrics]
  (let [{:keys [cost-usd tokens turns duration-ms tools tool-errors diff validation exit]} metrics]
    (cond-> {}
      cost-usd (assoc "bench/cost-usd" cost-usd)
      (:input tokens) (assoc "bench/tokens-in" (:input tokens))
      (:output tokens) (assoc "bench/tokens-out" (:output tokens))
      (:cache-read tokens) (assoc "bench/tokens-cache-read" (:cache-read tokens))
      turns (assoc "bench/turns" turns)
      duration-ms (assoc "bench/duration-ms" duration-ms)
      (:file-reads tools) (assoc "bench/tools-file-reads" (:file-reads tools))
      (:file-writes tools) (assoc "bench/tools-file-writes" (:file-writes tools))
      (:file-edits tools) (assoc "bench/tools-file-edits" (:file-edits tools))
      (:bash tools) (assoc "bench/tools-bash" (:bash tools))
      tool-errors (assoc "bench/tool-errors" tool-errors)
      (:files diff) (assoc "bench/diff-files" (:files diff))
      (:insertions diff) (assoc "bench/diff-insertions" (:insertions diff))
      (:deletions diff) (assoc "bench/diff-deletions" (:deletions diff))
      (:exit validation) (assoc "bench/validation-exit" (:exit validation))
      (some? exit) (assoc "bench/exit-code" exit))))

;; ---------------------------------------------------------------------------
;; Entry execution (on the spool executor, bounded by the run semaphore)

(defn- set-phase! [runtime entry-id phase]
  (weaver/update runtime entry-id {:attributes {"bench/phase" phase}}))

(defn- aborted?
  "True when `entry-id` has already been marked aborted. A worker finalizing an
  entry re-reads it first and skips its own done/failed stamp when abort! has won
  the race, so an aborted entry is never resurrected to done/closed."
  [runtime entry-id]
  (let [e (weaver/show runtime entry-id)]
    (and (= "failed" (attr-get e "bench/phase"))
         (= "aborted" (attr-get e "bench/error")))))

(defn- run-entry!
  "Prepare, run, measure, and close one entry strand.

  Runs on the bench executor after acquiring the run's parallelism permit.
  Preparing → running → done closes the strand with metrics stamped; any throw
  leaves it active with `bench/phase failed` and `bench/error` (TEN-003), plus
  `bench/error-detail` with the full stderr when the failure carried one
  (e.g. a git command failure from `skein.spools.bench.exec`)."
  [runtime ctx entry]
  (let [{:keys [run-id engine suite semaphore]} ctx
        {:keys [entry-id slug cell prompt-text]} entry
        harness-def (get @(harnesses-atom runtime) (:harness cell))
        entry-dir (io/file (run-dir runtime run-id) slug)
        container (exec/container-name run-id slug)]
    (.acquire ^Semaphore semaphore)
    (try
      (set-phase! runtime entry-id "preparing")
      (let [{:keys [workspace overlay]}
            (exec/prepare-workspace! {:data-dir (data-root runtime)
                                      :repo (:repo suite)
                                      :sha (:sha ctx)
                                      :entry-dir entry-dir
                                      :files (:files suite)
                                      :remove-paths (:remove suite)
                                      :workspace-root (workspace-root runtime)})]
        (set-phase! runtime entry-id "running")
        (let [prompt-via (or (:prompt-via harness-def) :stdin)
              on-start (fn [proc]
                         (swap! (in-flight-atom runtime) assoc entry-id
                                {:container container :process proc :run-id run-id}))
              result (exec/execute-entry!
                      {:engine engine
                       :run-id run-id
                       :slug slug
                       :entry-dir entry-dir
                       :image (:image harness-def)
                       :env (:env harness-def)
                       :auth (:auth harness-def)
                       :agent-cmd (harness-command harness-def cell)
                       :agent-prompt prompt-text
                       :prompt-via prompt-via
                       :setup (:setup suite)
                       :validation (:validation suite)
                       :timeout-ms (* 1000 (:timeout-secs suite))
                       :overlay overlay
                       :sha (:sha ctx)
                       :on-start on-start})
              extractor (get @(extractors-atom runtime) (or (:extractor harness-def) :generic) generic-extractor)
              extracted (sanitize-extracted (extractor {:entry-dir entry-dir
                                                        :home (io/file entry-dir "home")
                                                        :workspace workspace
                                                        :stdout (:stdout result)
                                                        :result result}))
              metrics (merge extracted
                             (cond-> {:exit (:exit result)
                                      :duration-ms (:duration-ms result)
                                      :diff (:diff result)}
                               (:validation result) (assoc :validation (:validation result))))]
          (spit (io/file entry-dir "metrics.json") (json/write-str metrics))
          ;; abort! wins: if the run was aborted while this entry was in flight,
          ;; do not resurrect it to done/closed.
          (if (aborted? runtime entry-id)
            {:slug slug :phase "aborted"}
            (do
              (weaver/update runtime entry-id
                          {:state "closed"
                           :attributes (merge {"bench/phase" "done"
                                               "bench/image-digest" (:image-digest result)}
                                              (metric-attrs metrics))})
              {:slug slug :phase "done"}))))
      (catch Throwable t
        (if (aborted? runtime entry-id)
          {:slug slug :phase "aborted"}
          (let [detail (:err (ex-data t))]
            (weaver/update runtime entry-id
                        {:attributes (cond-> {"bench/phase" "failed"
                                              "bench/error" (ex-message t)}
                                       (not (str/blank? detail)) (assoc "bench/error-detail" detail))})
            {:slug slug :phase "failed" :error (ex-message t)})))
      (finally
        (swap! (in-flight-atom runtime) dissoc entry-id)
        (.release ^Semaphore semaphore)))))

(defn- queue-entry! [runtime ctx entry]
  (.execute (executor runtime) ^Runnable (fn [] (run-entry! runtime ctx entry))))

;; ---------------------------------------------------------------------------
;; Suite resolution (shared by judge-spec and run!)

(defn- resolve-suite [runtime suite-name-or-inline]
  (if (map? suite-name-or-inline)
    {:name "<inline>" :definition suite-name-or-inline}
    (let [key (reg-key suite-name-or-inline)
          def (or (get @(suites-atom runtime) key)
                  (fail! "bench suite not registered" {:suite key :available (mapv :name (suites runtime))}))]
      {:name (name key) :definition def})))

;; ---------------------------------------------------------------------------
;; Judge — a fulfilment seam (README §8)
;;
;; The judge strand IS the seam. `judge-spec` builds, as plain data, the full
;; judge prompt plus the durable attributes a fulfiller needs — so bench pouring
;; an agent run, a workflow `:subagent` gate, or a human all read one source of
;; truth. `bench/judge-prompt` carries the full built prompt on the strand; the
;; strand `body` carries a short mechanism-agnostic fulfilment contract; the
;; final verdict lands in `bench/verdict` regardless of who fulfils it.

(defn- judge-body
  "The judge strand's `body`: a short, mechanism-agnostic fulfilment contract
  any fulfiller (an agent run, a workflow gate, a human) follows. The full
  judging protocol lives in the strand's `bench/judge-prompt` attribute."
  []
  (str/join
   "\n"
   ["Bench judge — fulfilment seam. To fulfil this strand:"
    "1. Read this strand's bench/judge-prompt attribute: the full judging protocol, the entry strand ids, and each entry's data-dir."
    "2. Judge the entries per that protocol (metrics are ground truth — judge quality, not arithmetic)."
    "3. Append one note per entry to its entry strand with scores and findings."
    "4. Stamp your final verdict on THIS strand as the bench/verdict attribute (a comparison table plus a winner / pass-fail) — this is the canonical, durable verdict."
    "5. Close THIS strand. Closing it completes the bench run."]))

(defn- judge-prompt
  "Build the full judge prompt (README §8): the suite prompts, per-entry
  data-dir paths and strand ids, and the judging protocol, layering the suite's
  `:judge :contract`. The ground-truth invariant is baked in here and is not
  overridable by `:contract`.

  `prompts` must already be resolved text (via `resolve-prompt-text`) — a
  `{:path ...}` prompt value is never rendered raw here."
  [{:keys [suite-name sha prompts single-prompt? entries contract]}]
  (let [prompt-lines (map (fn [[slug text]]
                            (str "  - " (if single-prompt? "prompt" (name slug)) ": "
                                 (str/replace text #"\s+" " ")))
                          prompts)
        entry-lines (mapcat (fn [{:keys [slug id data-dir harness model thinking prompt-slug]}]
                              [(str "- " slug " [harness=" (name harness)
                                    (when model (str " model=" model))
                                    (when thinking (str " thinking=" thinking))
                                    (when prompt-slug (str " prompt=" (name prompt-slug)))
                                    "]")
                               (str "    strand: " id)
                               (str "    data-dir: " data-dir)])
                            entries)]
    (str/join
     "\n"
     (concat
      [(str "You are the judge for bench suite " suite-name ".")]
      (when sha [(str "Repo pinned at sha " sha ".")])
      [""
       "Prompt(s) under test:"]
      prompt-lines
      [""
       "Entries under comparison:"]
      entry-lines
      [""
       "Judging protocol:"
       "- Read each entry's metrics.json, diff.patch, and captured stdout under its data-dir; inspect the workspace when needed."
       "- Metrics are ground truth — never re-derive or dispute them; judge quality, not arithmetic."
       "- Frame per the varying axis (model-vs-model, prompt-vs-prompt, or interaction effects); never flatten a two-axis run into one ranking."
       "- Append one note per entry to its strand id (use your strand note command) with scores and findings."
       (fmt/reflow
        "|- Stamp your final verdict on this judge strand (the strand you are fulfilling / your own run strand)
         |as the bench/verdict attribute; this is the canonical, durable location bench report reads.")
       "- Finish with the same verdict as your result: a comparison table plus a winner / pass-fail."]
      (when contract ["" "Suite contract:" contract])))))

(defn judge-spec
  "Return a bench run's judge fulfilment seam as plain data — the one prompt
  source every fulfilment mode shares:

      {:prompt <full judge prompt>
       :attrs  {\"bench/judge\" .. \"bench/run-id\" .. \"bench/judge-prompt\" .. \"body\" ..}
       :entry-ids [<entry strand id> ..]}

  `run-context` is `{:run-id <root id> :entries [{:id :slug :data-dir :harness?
  :model? :thinking? :prompt-slug?} ..] :sha?}`. `suite-name-or-inline` is a
  registered suite name or an inline suite value (validated identically); its
  `:judge :contract` is layered onto the built-in protocol, but the ground-truth
  invariant is baked into the builder and is not overridable.

  Suite prompts are resolved via the same `resolve-prompt-text` entries
  themselves go through (a `:path` prompt is slurped relative to the workspace
  root), so the judge is always shown the same text an entry received — never
  a raw `{:path ...}` value. The built prompt lists only the suite prompts
  `entries` actually reference via `:prompt-slug` (a subset `--entries` run
  omits unused prompts); single-prompt suites are unaffected, since there is
  only ever the one.

  This is the seam `run!` and workflow authors both consume. `run!` pours the
  judge strand and (in `:harness` mode) its serving agent run straight from
  this output — the strand's `bench/judge-prompt` and an agent run's
  `agent-run/prompt` come from this one builder, so they never drift. A workflow
  author calls `judge-spec` at pour time and maps it onto a `:subagent` gate
  exactly as roster review specs do (`skein.spools.delegation/roster-review-specs`):
  `:prompt` becomes the gate's `agent-run/prompt`, the author picks the gate's
  `agent-run/harness`, `:attrs` merge into the gate, and the gate depends on
  `:entry-ids`. Bench thus never requires or references the workflow spool.

  Fails loudly when the suite declares `:judge :none` (there is no judge to
  spec). A read over the suite registry, the workspace's suite-prompt files,
  and the passed run context."
  [runtime suite-name-or-inline {:keys [run-id entries sha]}]
  (let [{:keys [name definition]} (resolve-suite runtime suite-name-or-inline)
        normalized (normalize-suite definition)
        judge (:judge normalized)]
    (when (= :none judge)
      (fail! "bench judge-spec: suite declares :judge :none (no judge to spec)" {:suite name}))
    (let [used-slugs (if (:single-prompt? normalized)
                       #{::single}
                       (set (keep :prompt-slug entries)))
          resolved-prompts (into {}
                                 (keep (fn [[slug _]]
                                         (when (contains? used-slugs slug)
                                           [slug (resolve-prompt-text runtime (:prompts normalized) slug)])))
                                 (:prompts normalized))
          prompt (judge-prompt {:suite-name name
                                :sha sha
                                :prompts resolved-prompts
                                :single-prompt? (:single-prompt? normalized)
                                :contract (:contract judge)
                                :entries entries})]
      {:prompt prompt
       :entry-ids (mapv :id entries)
       :attrs {"bench/judge" "true"
               "bench/run-id" run-id
               "bench/judge-prompt" prompt
               "body" (judge-body)}})))

(defn- pour-judge!
  "Pour the judge strand from a `judge-spec` output per the suite's `:judge`
  fulfilment mode, returning its id. `:harness` spawns a serving agent run
  (the run strand IS the judge strand); `:external` pours the strand and stops —
  any external mechanism fulfils it per its body contract. Both carry the same
  `:attrs` and `depends-on` every entry."
  [runtime {:keys [judge suite-name root-id spec]}]
  (let [{:keys [prompt attrs entry-ids]} spec
        title (str "Bench judge: " suite-name)]
    (cond
      (:harness judge)
      (binding [agent-run/*runtime* runtime]
        (:id (agent-run/spawn-run! {:harness (:harness judge)
                                  :prompt prompt
                                  :title title
                                  :depends-on (vec entry-ids)
                                  :parent root-id
                                  :attrs attrs})))
      :else
      (let [judge-strand (weaver/add runtime
                                  {:title title
                                   :attributes attrs
                                   :edges (mapv (fn [e] {:type "depends-on" :to e}) entry-ids)})]
        (weaver/update runtime root-id {:edges [{:type "parent-of" :to (:id judge-strand)}]})
        (:id judge-strand)))))

;; ---------------------------------------------------------------------------
;; run!

(defn- select-entries [normalized only-slugs]
  (if (seq only-slugs)
    (let [wanted (set (map str only-slugs))
          selected (filterv #(contains? wanted (:slug %)) (:entries normalized))
          found (set (map :slug selected))
          missing (remove found wanted)]
      (when (seq missing)
        (fail! "bench run --entries names unknown slugs" {:missing (vec missing)
                                                          :available (mapv :slug (:entries normalized))}))
      selected)
    (:entries normalized)))

(defn run!
  "Pour and start a bench run for `suite-name-or-inline` on `runtime`.

  `opts` may carry `:entries` (a subset of slugs to run) and `:for` (a parent
  strand id the run root hangs beneath). Validates everything — suite conforms,
  harnesses registered, judge harness (in `:harness` mode) and engine resolvable —
  and resolves a `:rev` to a concrete sha before creating any strand (TEN-003).
  Pours the run root, one entry per matrix cell, and (unless `:judge :none`) the
  judge strand depending on every entry — a serving agent run in `:harness`
  mode, a bare fulfilment-seam strand in `:external` mode — queues entries on the
  bounded executor, and returns `{:run root-id :entries {slug id} :judge
  judge-id}` immediately; execution is async."
  [runtime suite-name-or-inline opts]
  (let [{:keys [name definition]} (resolve-suite runtime suite-name-or-inline)
        normalized (normalize-suite definition)
        eng (or (engine runtime) (fail! "bench has no container engine; set-engine! or install docker/podman" {}))
        judge (:judge normalized)
        selected (select-entries normalized (:entries opts))]
    (doseq [cell selected]
      (let [harness-def (or (get @(harnesses-atom runtime) (:harness cell))
                            (fail! "bench entry references an unregistered harness"
                                   {:harness (:harness cell) :available (mapv :name (harnesses runtime))}))]
        (validate-cell-axes! harness-def cell)))
    (when (:harness judge)
      (binding [agent-run/*runtime* runtime]
        (agent-run/resolve-harness (:harness judge))))
    (let [sha (or (:sha normalized)
                  (exec/resolve-rev! (data-root runtime) (:repo normalized) (:rev normalized)))
          root (weaver/add runtime {:title (str "Bench: " name)
                                 :attributes {"bench/run" "true"
                                              "bench/suite" name
                                              "bench/repo" (:repo normalized)
                                              "bench/sha" sha}})
          run-id (:id root)
          data-dir (.getCanonicalPath (run-dir runtime run-id))]
      (when-let [parent (:for opts)]
        (weaver/update runtime parent {:edges [{:type "parent-of" :to run-id}]}))
      (weaver/update runtime run-id {:attributes {"bench/data-dir" data-dir}})
      (let [poured (mapv (fn [cell]
                           (let [prompt-slug (if (:single-prompt? normalized) ::single (:prompt cell))
                                 prompt-text (resolve-prompt-text runtime (:prompts normalized) prompt-slug)
                                 entry (weaver/add runtime
                                                {:title (str "Bench entry: " (:slug cell))
                                                 :attributes (cond-> {"bench/entry" "true"
                                                                      "bench/slug" (:slug cell)
                                                                      "bench/harness" (clojure.core/name (:harness cell))
                                                                      "bench/phase" "pending"
                                                                      "bench/attempt" 1}
                                                               (:model cell) (assoc "bench/model" (:model cell))
                                                               (:thinking cell) (assoc "bench/thinking" (:thinking cell))
                                                               (and (not (:single-prompt? normalized)) (:prompt cell))
                                                               (assoc "bench/prompt-slug" (clojure.core/name (:prompt cell))))})]
                             (weaver/update runtime run-id {:edges [{:type "parent-of" :to (:id entry)}]})
                             {:slug (:slug cell) :entry-id (:id entry) :cell cell
                              :prompt-text prompt-text :prompt-slug prompt-slug
                              :entry-dir (str (io/file data-dir (:slug cell)))}))
                         selected)
            judge-entries (mapv (fn [p]
                                  {:id (:entry-id p)
                                   :slug (:slug p)
                                   :data-dir (:entry-dir p)
                                   :harness (get-in p [:cell :harness])
                                   :model (get-in p [:cell :model])
                                   :thinking (get-in p [:cell :thinking])
                                   :prompt-slug (when-not (:single-prompt? normalized)
                                                  (get-in p [:cell :prompt]))})
                                poured)
            judge-id (when-not (= :none judge)
                       (pour-judge! runtime
                                    {:judge judge
                                     :suite-name name
                                     :root-id run-id
                                     :spec (judge-spec runtime suite-name-or-inline
                                                       {:run-id run-id :entries judge-entries :sha sha})}))
            ctx {:run-id run-id :engine eng :suite normalized :sha sha
                 :semaphore (run-semaphore! runtime run-id (:parallel normalized))}]
        (doseq [entry poured]
          (queue-entry! runtime ctx entry))
        {:run run-id
         :entries (into {} (map (juxt :slug :entry-id) poured))
         :judge judge-id}))))

;; ---------------------------------------------------------------------------
;; retry! / abort! / gc!

(defn- root-of [runtime entry-id]
  (some-> (graph/incoming-edges runtime [entry-id] "parent-of") first :from_strand_id))

(defn- rebuild-ctx
  "Reconstruct a run context for `root` from its registered suite (suites are
  re-registered by trusted config across restarts)."
  [runtime root]
  (let [suite-name (attr-get root "bench/suite")
        definition (or (get @(suites-atom runtime) (keyword suite-name))
                       (fail! "bench retry needs the suite registered" {:suite suite-name}))
        normalized (normalize-suite definition)]
    {:run-id (:id root)
     :engine (or (engine runtime) (fail! "bench has no container engine" {}))
     :suite normalized
     :sha (attr-get root "bench/sha")
     :semaphore (run-semaphore! runtime (:id root) (:parallel normalized))}))

(defn retry!
  "Re-run one failed entry on a fresh workspace, incrementing `bench/attempt`.

  Only a `bench/phase failed` entry is retryable (TEN-003). Resets it to
  `pending`, clears `bench/error`, and re-queues it on the executor."
  [runtime entry-id]
  (let [entry (or (weaver/show runtime entry-id) (fail! "bench retry: no such entry" {:id entry-id}))]
    (when-not (= "failed" (attr-get entry "bench/phase"))
      (fail! "bench retry only applies to failed entries" {:id entry-id :phase (attr-get entry "bench/phase")}))
    (let [root (weaver/show runtime (root-of runtime entry-id))
          ctx (rebuild-ctx runtime root)
          slug (attr-get entry "bench/slug")
          cell (or (first (filter #(= slug (:slug %)) (:entries (:suite ctx))))
                   (fail! "bench retry: entry slug not in suite" {:slug slug}))
          harness-def (get @(harnesses-atom runtime) (:harness cell))
          _ (validate-cell-axes! harness-def cell)
          prompt-slug (if (:single-prompt? (:suite ctx)) ::single (:prompt cell))
          prompt-text (resolve-prompt-text runtime (:prompts (:suite ctx)) prompt-slug)
          attempt (or (attr-get entry "bench/attempt") 1)]
      (weaver/update runtime entry-id
                  {:attributes {"bench/phase" "pending"
                                "bench/error" nil
                                "bench/attempt" (inc attempt)}})
      (queue-entry! runtime ctx {:slug slug :entry-id entry-id :cell cell :prompt-text prompt-text})
      {:retried entry-id :attempt (inc attempt)})))

(defn- run-entries [runtime run-id]
  (->> (graph/outgoing-edges runtime [run-id] "parent-of")
       (map :to_strand_id)
       (graph/strands-by-ids runtime)
       (filter #(= "true" (str (attr-get % "bench/entry"))))))

(defn abort!
  "Abort a bench run: kill live containers, fail outstanding entries, and close
  the judge strand cleanly.

  Outstanding entries (phase pending/preparing/running) become `failed` with
  `bench/error \"aborted\"`; done entries are left closed. Best-effort kills
  every entry's container by name. The judge strand is closed with
  `bench/error \"aborted\"` (the same marking as an aborted entry, whether the
  judge is an agent run or an external seam); an agent-run judge additionally
  gets `agent-run/phase \"superseded\"` so the run engine treats it as retired."
  [runtime run-id]
  (let [root (or (weaver/show runtime run-id) (fail! "bench abort: no such run" {:id run-id}))
        eng (engine runtime)
        entries (run-entries runtime run-id)
        outstanding #{"pending" "preparing" "running"}
        failed (reduce (fn [acc entry]
                         (let [slug (attr-get entry "bench/slug")
                               marked? (contains? outstanding (attr-get entry "bench/phase"))]
                           ;; Mark BEFORE killing so a kill-triggered worker error
                           ;; can't race the aborted marking (abort wins).
                           (when marked?
                             (weaver/update runtime (:id entry)
                                         {:attributes {"bench/phase" "failed" "bench/error" "aborted"}}))
                           (when eng (exec/kill-container! eng (exec/container-name run-id slug)))
                           (when-let [{:keys [process]} (get @(in-flight-atom runtime) (:id entry))]
                             (when process (.destroyForcibly ^Process process)))
                           (if marked? (conj acc slug) acc)))
                       []
                       entries)
        judge (->> (graph/outgoing-edges runtime [run-id] "parent-of")
                   (map :to_strand_id)
                   (graph/strands-by-ids runtime)
                   (filter #(= "true" (str (attr-get % "bench/judge"))))
                   first)]
    (when judge
      (weaver/update runtime (:id judge)
                  {:state "closed"
                   :attributes (cond-> {"bench/error" "aborted"}
                                 (attr-get judge "agent-run/run") (assoc "agent-run/phase" "superseded"))}))
    (drop-semaphore! runtime run-id)
    {:aborted run-id :failed failed :judge (:id judge)}))

(defn gc!
  "Delete bench artifact directories, keeping strand-side metrics and verdicts.

  With `:run <id>` removes that run's dir; otherwise removes every run dir under
  the bench data root (the mirror cache is preserved). Returns the removed ids."
  [runtime {:keys [run]}]
  (letfn [(rm! [^File f]
            (when (.exists f)
              (when (.isDirectory f)
                (doseq [^File c (.listFiles f)] (rm! c)))
              (.delete f)))]
    (if run
      (do (rm! (run-dir runtime run))
          (drop-semaphore! runtime run)
          {:removed [run]})
      (let [root (data-root runtime)
            run-dirs (when (.exists root)
                       (->> (.listFiles root)
                            (filter #(and (.isDirectory ^File %) (not= "mirrors" (.getName ^File %))))))]
        (doseq [^File d run-dirs]
          (rm! d)
          (drop-semaphore! runtime (.getName ^File d)))
        {:removed (mapv #(.getName ^File %) run-dirs)}))))

;; ---------------------------------------------------------------------------
;; Read projections: runs / status / report (pure reads over the strand graph
;; and the on-disk artifacts)

(defn- run-children
  "Return the strands the run root `run-id` parents (entries and judge)."
  [runtime run-id]
  (->> (graph/outgoing-edges runtime [run-id] "parent-of")
       (map :to_strand_id)
       (graph/strands-by-ids runtime)))

(defn- entry-strands [children]
  (filter #(= "true" (str (attr-get % "bench/entry"))) children))

(defn- judge-strand [children]
  (first (filter #(= "true" (str (attr-get % "bench/judge"))) children)))

(defn- judge-verdict
  "Resolve a judge strand's verdict and its source (§8): the strand's
  `bench/verdict` attribute wins (the canonical, mode-agnostic location); else a
  serving agent run's `agent-run/result`; else none. Returns
  `{:verdict <text>? :verdict-source \"attr\"|\"run\"|\"none\"}`."
  [judge]
  (if-let [v (attr-get judge "bench/verdict")]
    {:verdict v :verdict-source "attr"}
    (if-let [r (attr-get judge "agent-run/result")]
      {:verdict r :verdict-source "run"}
      {:verdict-source "none"})))

(defn- run-root!
  "Return the bench run root for `run-id`, failing loudly when it is missing or
  not a `bench/run` strand."
  [runtime run-id verb]
  (let [root (or (weaver/show runtime run-id)
                 (fail! (str "bench " verb ": no such run") {:id run-id}))]
    (when-not (= "true" (str (attr-get root "bench/run")))
      (fail! (str "bench " verb ": not a bench run root") {:id run-id}))
    root))

(defn- entry-descriptor
  "The cell identity and phase of one entry strand, JSON-shaped."
  [entry]
  (cond-> {:id (:id entry)
           :slug (attr-get entry "bench/slug")
           :harness (attr-get entry "bench/harness")
           :phase (attr-get entry "bench/phase")}
    (attr-get entry "bench/model") (assoc :model (attr-get entry "bench/model"))
    (attr-get entry "bench/thinking") (assoc :thinking (attr-get entry "bench/thinking"))
    (attr-get entry "bench/prompt-slug") (assoc :prompt-slug (attr-get entry "bench/prompt-slug"))
    (attr-get entry "bench/attempt") (assoc :attempt (attr-get entry "bench/attempt"))
    (attr-get entry "bench/image-digest") (assoc :image-digest (attr-get entry "bench/image-digest"))
    (attr-get entry "bench/error") (assoc :error (attr-get entry "bench/error"))))

(defn- entry-headline
  "Headline metrics for one entry strand from its `bench/*` attrs (§7): cost,
  tokens, duration, and diff."
  [entry]
  (let [a #(attr-get entry %)
        diff (cond-> {}
               (a "bench/diff-files") (assoc :files (a "bench/diff-files"))
               (a "bench/diff-insertions") (assoc :insertions (a "bench/diff-insertions"))
               (a "bench/diff-deletions") (assoc :deletions (a "bench/diff-deletions")))]
    (cond-> {}
      (a "bench/cost-usd") (assoc :cost-usd (a "bench/cost-usd"))
      (a "bench/tokens-in") (assoc :tokens-in (a "bench/tokens-in"))
      (a "bench/tokens-out") (assoc :tokens-out (a "bench/tokens-out"))
      (a "bench/duration-ms") (assoc :duration-ms (a "bench/duration-ms"))
      (some? (a "bench/exit-code")) (assoc :exit-code (a "bench/exit-code"))
      (seq diff) (assoc :diff diff))))

(defn runs
  "Return bench run roots with per-run entry phase counts.

  `opts` may carry `:suite` to scope the listing to one suite. A pure read."
  [runtime {:keys [suite]}]
  (let [query (if suite
                [:and [:= [:attr "bench/run"] "true"] [:= [:attr "bench/suite"] suite]]
                [:= [:attr "bench/run"] "true"])]
    (mapv (fn [root]
            (let [entries (entry-strands (run-children runtime (:id root)))]
              {:run (:id root)
               :suite (attr-get root "bench/suite")
               :sha (attr-get root "bench/sha")
               :state (:state root)
               :entries (count entries)
               :phases (frequencies (map #(attr-get % "bench/phase") entries))}))
          (weaver/list runtime query {}))))

(defn status
  "Return a bench run's entries with phase and headline metrics, judge run
  state, and the slugs of blocking (failed) entries. A pure read (§10)."
  [runtime run-id]
  (let [root (run-root! runtime run-id "status")
        children (run-children runtime run-id)
        entries (entry-strands children)
        judge (judge-strand children)]
    {:run run-id
     :suite (attr-get root "bench/suite")
     :repo (attr-get root "bench/repo")
     :sha (attr-get root "bench/sha")
     :entries (mapv (fn [e] (assoc (entry-descriptor e) :metrics (entry-headline e))) entries)
     :judge (when judge
              (merge {:id (:id judge)
                      :state (:state judge)
                      :phase (attr-get judge "agent-run/phase")}
                     (judge-verdict judge)))
     :blocking-failures (->> entries
                             (filter #(= "failed" (attr-get % "bench/phase")))
                             (mapv #(attr-get % "bench/slug")))}))

(defn- read-metrics-json
  "Read an entry's `metrics.json`, distinguishing absence from corruption.

  Returns `nil` when the file is absent, `{:ok metrics}` when it parses, or
  `{:unreadable msg}` when present but unparseable — so `report` can surface a
  corrupt/truncated file as an explicit warning instead of hiding it as missing."
  [runtime run-id slug]
  (let [^File f (io/file (run-dir runtime run-id) slug "metrics.json")]
    (when (.exists f)
      (try {:ok (json/read-str (slurp f) :key-fn keyword)}
           (catch Exception e {:unreadable (ex-message e)})))))

(defn- entry-artifacts
  "Map of the artifact files present under an entry's data dir to their paths."
  [runtime run-id slug]
  (let [^File dir (io/file (run-dir runtime run-id) slug)]
    (reduce (fn [acc [k nm]]
              (let [^File f (io/file dir nm)]
                (cond-> acc (.exists f) (assoc k (.getCanonicalPath f)))))
            {:dir (.getCanonicalPath dir)}
            {:metrics "metrics.json" :diff "diff.patch" :stdout "stdout" :stderr "stderr"
             :manifest "manifest.json" :setup-log "setup.log" :validation-log "validation.log"})))

(defn report
  "Return the full comparison document for a bench run (§10): per-entry
  normalized metrics, extraction warnings, artifact paths, and per-entry judge
  notes, plus the judge verdict resolved per §8 (the judge strand's
  `bench/verdict` attr, else a serving run's `agent-run/result`) with its
  `:verdict-source` (attr|run|none). A pure read."
  [runtime run-id]
  (let [root (run-root! runtime run-id "report")
        children (run-children runtime run-id)
        entries (entry-strands children)
        judge (judge-strand children)]
    (binding [agent-run/*runtime* runtime]
      {:run run-id
       :suite (attr-get root "bench/suite")
       :repo (attr-get root "bench/repo")
       :sha (attr-get root "bench/sha")
       :data-dir (attr-get root "bench/data-dir")
       :entries (mapv (fn [e]
                        (let [slug (attr-get e "bench/slug")
                              parsed (read-metrics-json runtime run-id slug)
                              metrics (:ok parsed)
                              warnings (cond-> (vec (:extraction-warnings metrics))
                                         (:unreadable parsed)
                                         (conj (str "metrics.json unreadable: " (:unreadable parsed))))]
                          (cond-> (assoc (entry-descriptor e)
                                         :artifacts (entry-artifacts runtime run-id slug)
                                         :judge-notes (agent-run/notes (:id e)))
                            metrics (assoc :metrics (dissoc metrics :extraction-warnings))
                            (seq warnings) (assoc :extraction-warnings warnings))))
                      entries)
       :judge (when judge
                (merge {:id (:id judge)
                        :phase (attr-get judge "agent-run/phase")}
                       (judge-verdict judge)))})))

;; ---------------------------------------------------------------------------
;; about — authored manual (concepts only; arg shapes are help's job)

(defn about
  "Return the authored bench manual: purpose, determinism model, run lifecycle,
  attribute vocabulary, judge protocol summary, and artifact layout.

  Deliberately carries no argument shapes — `strand help bench` projects those
  from the declared `:subcommands`."
  []
  {:operation "bench about"
   :summary (fmt/reflow
             "|Deterministic, containerized benchmarking of coding-agent harnesses: given a
              |pinned repo+sha, a pinned memory-file/skill surface, and a prompt, compare N
              |agent configurations reproducibly.")
   :purpose (fmt/reflow
             "|A bench run is a strand graph — a run root, one entry per matrix cell, and a
              |judge strand that depends on every entry. Each entry runs its agent in a fresh
              |container against a pristine checkout; the engine extracts metrics
              |deterministically and closes the entry. Closing every entry unblocks the judge
              |— a decoupled fulfilment seam that writes a comparative verdict, shipped as a
              |agent run but fulfillable by anything (workflow gate, human, custom bridge).
              |Setup and measurement are code; only judgment is a model.")
   :determinism-model {:pinned (fmt/reflow
                                "|repo sha, memory files/skills (overlay manifest), prompt text,
                                 |agent argv, container image (digest recorded; pin by digest for
                                 |strictness), setup/validation commands, extractor code version")
                       :not-pinned "model behavior (what is being measured), network-fetched deps during setup (pin via lockfiles in the repo sha), API-side model versions"
                       :note "Re-running a suite yields a comparable run, not a bit-identical one; the run manifest makes any drift diagnosable."}
   :run-lifecycle {:validate (fmt/reflow
                              "|Suite conforms, harnesses registered, judge harness (in :harness mode)
                               |and engine resolvable, and any :rev resolved to a sha — all before
                               |any strand is created.")
                   :pour (fmt/reflow
                          "|Create the run root (optionally under a --for parent), one entry per
                           |cell, and (unless :judge :none) the judge strand depending on every
                           |entry; write the manifest.")
                   :execute "Queue entries on the bounded executor (:parallel); the judge stays pending until every entry closes."
                   :entry-phases (fmt/reflow
                                  "|pending -> preparing -> running -> done | failed. A done entry
                                   |has metrics stamped and is closed. A failed entry stays active
                                   |with bench/phase failed and bench/error, keeping the judge
                                   |blocked until retry or abort.")}
   :attributes {:run {"bench/run" "true on the run root"
                      "bench/suite" "suite name"
                      "bench/repo" "repo URL or path"
                      "bench/sha" "resolved 40-hex sha"
                      "bench/data-dir" "host artifact root for the run"}
                :entry {"bench/entry" "true on entry strands"
                        "bench/slug" "cell slug"
                        "bench/harness" "bench harness def key"
                        "bench/model" "optional model axis"
                        "bench/thinking" "optional thinking axis"
                        "bench/prompt-slug" "optional prompt axis"
                        "bench/phase" "pending|preparing|running|done|failed"
                        "bench/attempt" "1-based execution attempt"
                        "bench/image-digest" "container image digest at launch"
                        "bench/error" "failure reason when phase is failed"
                        "bench/error-detail" "full stderr when the failure carried one (e.g. a git command failure)"
                        "bench/*" "flattened §7 metrics: cost-usd, tokens-in/out, tokens-cache-read, turns, duration-ms, tools-*, tool-errors, diff-*, validation-exit, exit-code"}
                :judge {"bench/judge" "true on the judge strand — the fulfilment seam, depending on every entry"
                        "bench/run-id" "the run root id"
                        "bench/judge-prompt" "the complete built judge prompt (protocol + entry ids + data-dir paths); the one prompt source both modes share"
                        "body" "a short mechanism-agnostic fulfilment contract: read bench/judge-prompt, judge, note each entry, stamp bench/verdict, close"
                        "bench/verdict" "the canonical, durable verdict stamped by whoever fulfils the strand"}}
   :judge-protocol {:seam (fmt/reflow
                           "|The judge strand IS a fulfilment seam. run! pours it depending on
                            |every entry and stamps bench/judge-prompt + a body contract; anything
                            |may fulfil it. bench never requires the workflow spool.")
                    :modes (fmt/reflow
                            "|:judge {:harness h} spawns a serving agent run (shipped default);
                             |:judge {:external true} pours the strand and stops for any external
                             |mechanism (workflow gate, human, custom bridge) to fulfil; :judge
                             |:none runs a judgeless, metrics-only suite. Exactly one of
                             |:harness/:external.")
                    :composition (fmt/reflow
                                  "|skein.spools.bench/judge-spec (trusted Clojure) returns {:prompt
                                   |:attrs :entry-ids} — the same source run! pours from. A workflow
                                   |author maps it onto a :subagent gate (agent-run/prompt from
                                   |:prompt, gate depends on :entry-ids), exactly like agents'
                                   |roster-review-specs.")
                    :seat "In :harness mode the suite's :judge :harness — any agent-run harness/alias — chooses the approving model."
                    :ground-truth (fmt/reflow
                                   "|Metrics are ground truth — the judge never re-derives or
                                    |disputes them; it judges quality, not arithmetic (baked into
                                    |the builder, not overridable by :contract).")
                    :verdict (fmt/reflow
                              "|The verdict lands in bench/verdict on the judge strand (canonical);
                               |an agent run also leaves it as agent-run/result. report/status
                               |resolve bench/verdict first, else agent-run/result, and report the
                               |verdict-source (attr|run|none).")
                    :output "One note appended per entry strand with scores and findings, the verdict stamped on the judge strand, then the strand closed."
                    :recovery "A failed agent-run judge recovers with the ordinary strand agent retry."}
   :artifact-layout {:root "<weaver state dir>/bench/<run-id>/<slug>/"
                     :files {"workspace/" "clone at the pinned sha with the overlay applied"
                             "home/" "the container HOME; harness session artifacts land here for extraction"
                             "stdout / stderr" "captured agent streams"
                             "setup.log / validation.log" "setup and validation container output"
                             "metrics.json" "normalized §7 metrics plus any extraction warnings"
                             "diff.patch" "everything the agent changed, including untracked files"
                             "manifest.json" "resolved sha, image digest, compiled argv (auth env redacted), overlay listing, prompt text"}
                     :gc "Artifacts are kept until `bench gc [--run <id>]`; strand-side metrics and verdicts survive gc."}
   :discovery {:help "strand help bench"
               :query "strand list --query bench-runs"}})

;; ---------------------------------------------------------------------------
;; CLI op surface (§10): declared :subcommands buy generated `strand help bench`;
;; verbs are thin JSON wrappers over the engine fns (TEN-006)

(def ^:private bench-arg-spec
  "Declared command surface for the `bench` op (README §10)."
  {:op "bench"
   :doc "Deterministic containerized benchmarking of coding-agent harnesses. Run `strand bench about` for the manual."
   :subcommands
   {"run" {:doc "Pour and start a bench run for a registered suite."
           :flags {:entries {:doc "Comma-separated subset of entry slugs to run."}
                   :for {:doc "Parent strand id to root the run beneath."}}
           :positionals [{:name :suite :required? true :doc "Registered suite name."}]}
    "list" {:doc "List bench run roots with per-run entry phase counts."
            :flags {:suite {:doc "Only runs of this suite."}}}
    "status" {:doc "Entries with phase and headline metrics, judge run state, and blocking failures."
              :positionals [{:name :run-id :required? true :doc "Bench run root id."}]}
    "report" {:doc "Full comparison document: per-entry metrics, extraction warnings, judge verdict and notes, artifact paths."
              :positionals [{:name :run-id :required? true :doc "Bench run root id."}]}
    "retry" {:doc "Rerun one failed entry on a fresh workspace."
             :positionals [{:name :entry-id :required? true :doc "Failed entry strand id."}]}
    "abort" {:doc "Kill live containers, fail outstanding entries, and supersede the judge."
             :positionals [{:name :run-id :required? true :doc "Bench run root id."}]}
    "suites" {:doc "List registered benchmark suites."}
    "harnesses" {:doc "List registered bench harness definitions (container definitions; not agent-run harnesses)."}
    "gc" {:doc "Delete bench artifact directories (strand-side metrics and verdicts survive)."
          :flags {:run {:doc "Only this run's artifact directory."}}}
    "about" {:doc "Return the authored bench manual."}}})

(def ^:private bench-returns
  {:subcommands
   {"run" {:type :map :required {:operation :string :run :string
                                  :entries {:type :map :extra :string}
                                  :judge :json}}
    "list" {:type :collection
            :items {:type :map
                    :required {:run :string :suite :string :sha :string :state :string
                               :entries :integer :phases {:type :map :extra :integer}}}}
    "status" {:type :map
              :required {:operation :string :run :string :suite :string :repo :string :sha :string
                         :entries {:type :collection :items {:type :map :extra :json}}
                         :judge :json
                         :blocking-failures {:type :collection :items :string}}}
    "report" {:type :map
              :required {:operation :string :run :string :suite :string :repo :string :sha :string
                         :data-dir :string
                         :entries {:type :collection :items {:type :map :extra :json}}
                         :judge :json}}
    "retry" {:type :map :required {:operation :string :retried :string :attempt :integer}}
    "abort" {:type :map
             :required {:operation :string :aborted :string
                        :failed {:type :collection :items :string}
                        :judge :json}}
    "suites" {:type :collection :items {:type :map :required {:name :string} :extra :json}}
    "harnesses" {:type :collection :items {:type :map :required {:name :string} :extra :json}}
    "gc" {:type :map
          :required {:operation :string :removed {:type :collection :items :string}}}
    "about" {:type :map :required {:operation :string :summary :string} :extra :json}}})

(defn- split-csv
  "Split a comma-separated flag value into trimmed, non-blank tokens."
  [s]
  (into [] (comp (map str/trim) (remove str/blank?)) (str/split s #",")))

(defn bench-op
  "Dispatch parsed `strand bench ...` subcommands to the engine functions.

  Each verb is a thin JSON wrapper: the parser routes on `:subcommand` and
  supplies flags and positionals; rich data stays in trusted Clojure. A bare
  `strand bench` or an unknown verb fails during parser routing (the declared
  `:subcommands` machinery), never here."
  [{:op/keys [args runtime]}]
  (case (:subcommand args)
    "run" (run! runtime (:suite args)
                (cond-> {}
                  (:entries args) (assoc :entries (split-csv (:entries args)))
                  (:for args) (assoc :for (:for args))))
    "list" (runs runtime (select-keys args [:suite]))
    "status" (status runtime (:run-id args))
    "report" (report runtime (:run-id args))
    "retry" (retry! runtime (:entry-id args))
    "abort" (abort! runtime (:run-id args))
    "suites" (suites runtime)
    "harnesses" (harnesses runtime)
    "gc" (gc! runtime (select-keys args [:run]))
    "about" (about)))

;; ---------------------------------------------------------------------------
;; Engine detection + reconciliation + install

(defn- on-path? [exe]
  (some (fn [dir]
          (let [f (io/file dir exe)]
            (and (.isFile f) (.canExecute f))))
        (str/split (or (System/getenv "PATH") "") #":")))

(defn- detect-engine! [runtime]
  (when-not (engine runtime)
    (cond
      (on-path? "docker") (reset! (engine-atom runtime) ["docker"])
      (on-path? "podman") (reset! (engine-atom runtime) ["podman"])
      :else nil)))

(def ^:private orphan-query
  [:and [:= :state "active"] [:= [:attr "bench/entry"] "true"]
   [:or [:= [:attr "bench/phase"] "preparing"] [:= [:attr "bench/phase"] "running"]]])

(defn reconcile!
  "Fail entries orphaned by a weaver restart and best-effort kill their
  containers.

  An in-flight executor claim is weaver-lifetime state, so after a restart any
  `preparing`/`running` entry with no claim is orphaned: it becomes `failed`
  with `bench/error \"orphaned by weaver restart\"` and its container is killed
  by name. Returns the reconciled entry ids."
  [runtime]
  (let [eng (engine runtime)
        live (in-flight-atom runtime)
        orphans (->> (weaver/list runtime orphan-query {})
                     (remove #(contains? @live (:id %))))]
    (doseq [entry orphans]
      (when eng
        (when-let [root (root-of runtime (:id entry))]
          (exec/kill-container! eng (exec/container-name root (attr-get entry "bench/slug")))))
      (weaver/update runtime (:id entry)
                  {:attributes {"bench/phase" "failed"
                                "bench/error" "orphaned by weaver restart"}}))
    (mapv :id orphans)))

(def ^:private bench-namespace-declaration
  "The bench-owned `bench/*` attribute namespace stamped onto run roots, entry
  strands, and the judge strand. The judge also carries `agent-run/*` keys in
  `:harness` mode and the bare cross-spool `body` convention key, neither of
  which bench owns. `:keys` is advisory."
  {:kind :attr-namespace
   :name "bench"
   :owner :skein/spools-bench
   :keys ["bench/run" "bench/suite" "bench/repo" "bench/sha" "bench/data-dir"
          "bench/entry" "bench/slug" "bench/harness" "bench/model" "bench/thinking"
          "bench/prompt-slug" "bench/phase" "bench/attempt" "bench/image-digest"
          "bench/error" "bench/error-detail"
          "bench/cost-usd" "bench/tokens-in" "bench/tokens-out" "bench/tokens-cache-read"
          "bench/turns" "bench/duration-ms" "bench/tools-file-reads"
          "bench/tools-file-writes" "bench/tools-file-edits" "bench/tools-bash"
          "bench/tool-errors" "bench/diff-files" "bench/diff-insertions"
          "bench/diff-deletions" "bench/validation-exit" "bench/exit-code"
          "bench/judge" "bench/run-id" "bench/judge-prompt" "bench/verdict"]
   :doc "Bench run-root, entry, and judge attributes written by skein.spools.bench/run!."})

(defn install!
  "Activate bench on the current runtime.

  Creates the runtime-owned state (bounded executor + registries + in-flight
  tracking), detects the container engine (docker then podman on PATH unless
  `set-engine!` already pinned one), registers the shipped
  `:generic`/`:claude`/`:pi`/`:codex` extractors (defaults; user registrations
  win), reconciles entries orphaned by a previous weaver lifetime, and registers
  the `bench` CLI op and the `bench-runs` named query. Registers no suites or
  harness definitions — those are trusted config. Called as a no-arg module
  `:call` at startup/reload."
  []
  (let [runtime (current/runtime)]
    (state runtime)
    (vocab/declare! runtime bench-namespace-declaration)
    (register-extractor! runtime :generic generic-extractor)
    (register-default-extractors! runtime)
    (detect-engine! runtime)
    (let [reconciled (reconcile! runtime)]
      {:installed true
       :namespace 'skein.spools.bench
       :engine (engine runtime)
       :harnesses (mapv :name (harnesses runtime))
       :suites (mapv :name (suites runtime))
       :reconciled reconciled
       :op (weaver/register-op! runtime 'bench
                             {:doc (:doc bench-arg-spec)
                              :arg-spec bench-arg-spec
                              :returns bench-returns
                              ;; run/reconcile may fetch a git mirror synchronously
                              :deadline-class :unbounded
                              :hook-class :mutating}
                             'skein.spools.bench/bench-op)
       :query (graph/register-query! runtime 'bench-runs
                                   [:and [:= :state "active"] [:= [:attr "bench/run"] "true"]])})))
