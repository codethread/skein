(ns skein.api.runtime.alpha
  "Explicit-runtime API for trusted weaver runtime loader/config workflows.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. Use `skein.api.current.alpha/runtime` only at trusted in-process entry
  points that need to capture the active runtime."
  (:refer-clojure :exclude [sync use])
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.spool.alpha :refer [require-valid!]]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.weaver.spool-sync :as spool-sync]))

(s/def ::coord symbol?)
(s/def ::status keyword?)
(s/def ::lib symbol?)
(s/def ::kind keyword?)
(s/def ::root any?)
(s/def ::source any?)
(s/def ::previous-root any?)
(s/def ::new-root any?)
(s/def ::loaded-namespaces (s/coll-of symbol? :kind vector?))
(s/def ::coordinate symbol?)
(s/def ::previous-version string?)
(s/def ::new-version string?)
(s/def ::removed-root (s/keys :req-un [::lib ::kind ::root]
                              :opt-un [::source]))
(s/def ::changed-root (s/keys :req-un [::lib ::previous-root ::new-root]))
(s/def ::redefinition (s/keys :req-un [::lib ::root ::loaded-namespaces]))
(s/def ::maven-version-bump (s/keys :req-un [::coordinate ::previous-version ::new-version]))
(s/def ::removed-roots (s/coll-of ::removed-root :kind vector?))
(s/def ::changed-roots (s/coll-of ::changed-root :kind vector?))
(s/def ::redefinitions (s/coll-of ::redefinition :kind vector?))
(s/def ::maven-version-bumps (s/coll-of ::maven-version-bump :kind vector?))
(s/def ::diff (s/keys :opt-un [::removed-roots ::changed-roots ::redefinitions ::maven-version-bumps]))
(s/def ::generation (s/or :id string? :unknown #{:unknown}))
(s/def ::approved-spools set?)
(s/def ::remedy string?)
(s/def ::pending-generation (s/keys :req-un [::status ::generation ::diff ::approved-spools ::remedy]))
(s/def ::key any?)
(s/def ::current-generation string?)
(s/def ::reason keyword?)
(s/def ::retained-spool-state-entry (s/keys :req-un [::key ::generation ::current-generation]
                                            :opt-un [::reason]))
(s/def ::retained-spool-state (s/coll-of ::retained-spool-state-entry :kind vector?))
(s/def ::spools map?)
(s/def ::sync-result (s/keys :req-un [::spools]
                             :opt-un [::pending-generation ::retained-spool-state]))
(s/def ::non-additive-sync-diff-ex-data
  (s/keys :req-un [::status ::reason ::diff ::pending-generation ::remedy]))

(defn- validate-sync-result! [result]
  (require-valid! ::sync-result result "runtime sync result has an invalid shape")
  result)

(defn- validate-sync-ex-data! [data]
  (when (= :non-additive-sync-diff (:reason data))
    (require-valid! ::non-additive-sync-diff-ex-data data "runtime sync exception data has an invalid shape")))

(defn approved
  "Return the normalized approved spool roots for `runtime`'s config dir."
  [runtime]
  (spool-sync/approved-spools runtime))

(defn release-marker
  "Return the running Skein release marker and its provenance.

  The result has marker `vN` and provenance `:claimed` for an explicit startup
  claim, marker `vN` and provenance `:tag` for an annotated tag on the source
  checkout's HEAD, or `{:marker nil :provenance :none}` when neither resolves.
  Consumers that require marker arithmetic must reject `:none` explicitly."
  [runtime]
  (access/release-marker runtime))

(defn config-dir
  "Return the selected config directory path for `runtime`."
  [runtime]
  (access/config-dir runtime))

(defn spools-file
  "Return the `java.io.File` for `runtime`'s shared `spools.edn`."
  [runtime]
  (access/spools-file runtime "spools.edn"))

(defn sync!
  "Load approved spool roots and Maven jars into `runtime`.

  Returns `{:spools ...}` plus `:retained-spool-state` when preserved spool-state
  entries are from an older or unknown generation. Refuses non-additive diffs,
  including Maven version changes for already-loaded coordinates, by throwing
  ExceptionInfo with `:reason :non-additive-sync-diff`, `:diff`,
  `:pending-generation`, and `:remedy`; later successful calls include the
  pending generation until the weaver process is replaced."
  [runtime]
  (try
    (validate-sync-result! (spool-sync/sync-approved-spools runtime))
    (catch clojure.lang.ExceptionInfo ex
      (validate-sync-ex-data! (ex-data ex))
      (throw ex))))

(defn syncs
  "Return `runtime`'s most recent approved-root sync state.

  The result is `{:spools ...}` and may include the latest recorded
  `:pending-generation` from a refused non-additive sync diff."
  [runtime]
  (validate-sync-result! (spool-sync/approved-spool-syncs runtime)))

(defn reload!
  "Reload startup files from `runtime`'s config dir after clearing registries."
  [runtime]
  (weaver-runtime/reload-config! runtime))

(defn reload-spool!
  "Make `coord`'s latest synced source live in `runtime`.

  `coord` is a `spools.edn` coordinate symbol (e.g. `skein.spools/kanban`) — a
  spool is many namespaces and sync state is keyed by coordinate, not namespace.
  Returns a data-first map naming the coordinate, its resolved canonical root, and
  the namespaces reloaded in reload order with their source files.

  Fills the gap neither existing reload path covers: `reload!` re-runs startup
  files but does not unload already-loaded namespaces or vars, and a bare `(require
  ns :reload)` is classloader-blind to per-spool synced roots — so neither picks up
  updated synced spool code. `reload-spool!` does. It reloads code only and leaves
  re-registration to the caller (a targeted re-`use!` of the spool's activation, or
  a full `reload!` when the bump changes registrations across the config).

  Redefinition semantics — this re-`load-file`s sources, rebinding vars in place;
  it unloads nothing, so definitions minted before the reload are not migrated.
  Concretely: a `defmulti` dispatch table survives (re-evaluating `defmulti` is a
  no-op, so methods registered against the prior table stay and a changed dispatch
  signature is not picked up), a re-evaluated `defprotocol` mints a fresh interface
  so instances built before the reload no longer satisfy the new protocol
  (`satisfies?`/`instance?` go false), and any instance or captured var from before
  the reload keeps its old definition. A revision that deletes or renames a
  namespace also leaves the old one loaded until restart.

  Fails loudly on an unresolvable `coord`, carrying a `:reason` keyword in ex-data."
  [runtime coord]
  (require-valid! ::coord coord "reload-spool! coord must be a spool coordinate symbol")
  (spool-sync/reload-synced-spool! runtime coord))

(defn now
  "Return the current java.time.Instant from `runtime`'s clock seam.

  Defaults to the real wall clock; deterministic tests inject an advanceable
  clock through `skein.test.alpha/set-clock!`."
  [runtime]
  (weaver-runtime/now runtime))

(def ^:private allowed-use-keys #{:ns :file :spools :after :call :required?})

(defn- validate-use-opts! [key opts]
  (when-not (keyword? key)
    (throw (ex-info "Module use key must be a keyword" {:key key})))
  (when-not (map? opts)
    (throw (ex-info "Module use opts must be a map" {:key key :opts opts})))
  (when-let [unknown (seq (remove allowed-use-keys (keys opts)))]
    (throw (ex-info "Module use opts contain unknown keys" {:key key :keys (vec unknown)})))
  (when (= (contains? opts :ns) (contains? opts :file))
    (throw (ex-info "Module use opts require exactly one of :ns or :file" {:key key :opts opts})))
  (when (and (contains? opts :ns) (not (symbol? (:ns opts))))
    (throw (ex-info "Module use :ns must be a symbol" {:key key :ns (:ns opts)})))
  (when (and (contains? opts :file) (not (and (string? (:file opts)) (not (str/blank? (:file opts))))))
    (throw (ex-info "Module use :file must be a non-blank string" {:key key :file (:file opts)})))
  (when (and (contains? opts :file) (.isAbsolute (io/file (:file opts))))
    (throw (ex-info "Module use :file must be relative to selected config-dir" {:key key :file (:file opts)})))
  (when (and (contains? opts :spools)
             (not (or (vector? (:spools opts)) (set? (:spools opts)))))
    (throw (ex-info "Module use :spools must be a vector or set of symbols" {:key key :spools (:spools opts)})))
  (doseq [lib (:spools opts)]
    (when-not (symbol? lib)
      (throw (ex-info "Module use :spools entries must be symbols" {:key key :lib lib}))))
  (when (and (contains? opts :after) (not (vector? (:after opts))))
    (throw (ex-info "Module use :after must be a vector" {:key key :after (:after opts)})))
  (doseq [after (:after opts)]
    (when-not (keyword? after)
      (throw (ex-info "Module use :after entries must be keywords" {:key key :after after}))))
  (when (and (contains? opts :call) (not (symbol? (:call opts))))
    (throw (ex-info "Module use :call must be a fully qualified symbol" {:key key :call (:call opts)})))
  (when (and (symbol? (:call opts)) (nil? (namespace (:call opts))))
    (throw (ex-info "Module use :call must be a fully qualified symbol" {:key key :call (:call opts)})))
  (when (and (contains? opts :required?) (not (boolean? (:required? opts))))
    (throw (ex-info "Module use :required? must be boolean" {:key key :required? (:required? opts)}))))

(defn- record-use! [runtime key result]
  (swap! (access/module-use-state runtime) assoc key result)
  result)

(defn- skip-use [runtime key opts reason data]
  (let [result (record-use! runtime key (merge {:key key :opts opts :status :skipped :reason reason} data))]
    (when (and (:required? opts) (#{:not-approved :not-synced :sync-failed} reason))
      (throw (ex-info "Required module use was skipped" result)))
    result))

(defn- use-spool-skip [runtime opts]
  (let [approved (spool-sync/approved-spools runtime)
        syncs @(access/approved-spool-sync-state runtime)]
    (some (fn [lib]
            (let [sync-entry (get syncs lib)]
              (cond
                (not (contains? (:spools approved) lib))
                [:not-approved {:lib lib}]

                (not (contains? syncs lib))
                [:not-synced {:lib lib}]

                (= :failed (:status sync-entry))
                [:sync-failed {:lib lib :sync sync-entry}]

                :else
                nil)))
          (:spools opts))))

(defn- use-after-skip [runtime opts]
  (let [uses @(access/module-use-state runtime)]
    (some (fn [after]
            (when-not (= :loaded (:status (get uses after)))
              [:missing-after {:after after :use (get uses after)}]))
          (:after opts))))

(defn- exception-data [t]
  {:message (ex-message t)
   :class (str (class t))
   :data (ex-data t)})

(defn use!
  "Load a runtime module and record its module-use state under keyword key.

  Opts load either a synced namespace via `:ns` or a file via `:file`, and may
  include `:call` to invoke a no-arg function after load. Returns a registry
  entry with status `:loaded`, `:skipped`, or `:failed`; failed required uses
  rethrow after recording failure metadata."
  [runtime key opts]
  (validate-use-opts! key opts)
  (when-let [file (:file opts)]
    (spool-sync/module-file runtime file))
  (if-let [[reason data] (use-spool-skip runtime opts)]
    (skip-use runtime key opts reason data)
    (if-let [[reason data] (use-after-skip runtime opts)]
      (skip-use runtime key opts reason data)
      (try
        (let [load-result (access/with-spool-classloader
                            runtime
                            #(if-let [ns-sym (:ns opts)]
                               (spool-sync/load-synced-namespace! runtime ns-sym)
                               (let [file (spool-sync/module-file runtime (:file opts))]
                                 (load-file file)
                                 {:file file})))
              call-result (when-let [call-sym (:call opts)]
                            (access/with-spool-classloader
                              runtime
                              #((requiring-resolve call-sym))))]
          (record-use! runtime key (cond-> {:key key
                                            :opts opts
                                            :status :loaded
                                            :loaded load-result}
                                     (contains? opts :call) (assoc :call {:fn (:call opts)
                                                                          :return call-result}))))
        (catch Exception t
          (let [result (record-use! runtime key {:key key
                                                 :opts opts
                                                 :status :failed
                                                 :error (exception-data t)})]
            (when (:required? opts)
              (throw t))
            result))))))

(defn uses
  "Return `runtime`'s module-use registry as data-first maps."
  [runtime]
  (into (sorted-map) @(access/module-use-state runtime)))

(defn use
  "Return one module-use registry entry from `runtime` by key."
  [runtime key]
  (get @(access/module-use-state runtime) key))

(defn- warn!
  "Emit a loud-but-non-fatal runtime warning to the weaver's stderr log.

  Used where discarding a signal entirely would be worse than continuing but a
  hard failure is not warranted (a best-effort resource cleanup that fails during
  a version-mismatch reinit): the reinit still proceeds and the divergence stays
  visible in the weaver log instead of vanishing."
  [message data]
  (binding [*out* *err*]
    (println (str "[runtime] WARN " message " " (pr-str data)))))

(def ^:private spool-state-opt-keys #{:version :migrate-fn})

(defn- validate-spool-state-opts!
  "Reject a malformed spool-state opts map before it can silently degrade.

  A misspelled or unknown key (e.g. `{:versoin 2}`) would otherwise leave
  `version` nil and the accessor would reuse a shape-mismatched preserved value —
  exactly the silent state-reuse bug class the version guard exists to close,
  just moved one level up to the opts map. So the map is closed to
  `:version`/`:migrate-fn`, `:version` must be a non-nil comparable tag, and
  `:migrate-fn` (which only fires on a version mismatch) requires a `:version`.
  Mirrors the closed-key discipline `ct.spools.agent-run/register-harness!` already
  applies to its own def map."
  [opts]
  (when (some? opts)
    (when-not (map? opts)
      (throw (ex-info "spool-state opts must be a map or nil" {:opts opts})))
    (let [unknown (remove spool-state-opt-keys (keys opts))]
      (when (seq unknown)
        (throw (ex-info "spool-state opts has unknown keys"
                        {:unknown (vec unknown) :allowed spool-state-opt-keys :opts opts}))))
    (when (contains? opts :version)
      (let [v (:version opts)]
        (when-not (or (integer? v) (keyword? v) (string? v))
          (throw (ex-info "spool-state :version must be a non-nil integer, keyword, or string"
                          {:version v :class (some-> v class)})))))
    (when (contains? opts :migrate-fn)
      (when-not (ifn? (:migrate-fn opts))
        (throw (ex-info "spool-state :migrate-fn must be a function"
                        {:migrate-fn (:migrate-fn opts)})))
      (when-not (contains? opts :version)
        (throw (ex-info "spool-state :migrate-fn requires a :version to compare against"
                        {:opts opts})))))
  opts)

(defn- tag-spool-state-generation
  "Tag `value` with the runtime generation that created it, when metadata permits it."
  [runtime value]
  (if (instance? clojure.lang.IObj value)
    (vary-meta value assoc :skein.runtime/generation (:generation-id runtime))
    value))

(defn- versioned-value
  "Tag `value` with its declared spool-state `version` for later reload checks.

  Version nil (the unversioned default) leaves `value` untouched. A declared
  version is stored as value metadata, so `close-fn` lookups and consumers still
  see the plain state value; versioned state must therefore support metadata."
  [runtime value version]
  (tag-spool-state-generation
   runtime
   (if (nil? version)
     value
     (if (instance? clojure.lang.IObj value)
       (vary-meta value assoc ::version version)
       (throw (ex-info "Versioned spool state must support metadata"
                       {:version version :class (class value)}))))))

(defn- reinit-mismatched-state
  "Build the replacement value when preserved `existing` state mismatches the
  declared `version`.

  With a `migrate-fn`, it owns `existing` (including any resources it holds) and
  returns the new value. Without one, `existing`'s `:close-fn` runs best-effort
  so a stale executor or scheduler is released, then `init-fn` builds fresh
  state — preserving nothing. The result is re-tagged with `version`."
  [runtime existing version migrate-fn init-fn]
  (versioned-value
   runtime
   (if migrate-fn
     (migrate-fn existing)
     (do (when-let [close-fn (:close-fn existing)]
           (try (close-fn)
                (catch Throwable t
                  (warn! "spool-state reinit close-fn failed; a stale executor may leak"
                         {:version version :exception/message (ex-message t)}))))
         (init-fn)))
   version))

(defn spool-state
  "Return runtime-owned state for a spool key, creating it with `init-fn` once.

  The runtime stores spool state under arbitrary keys in its `:spool-state`
  atom. `init-fn` is called only when `key` has not been installed for this
  runtime; the returned value is then reused for the rest of the runtime
  lifetime. Spools should use this accessor instead of reaching into runtime
  internals.

  Spool state survives `reload!` by design, so a spool whose state shape changed
  between deploys would otherwise silently reuse a preserved value that is
  missing the new keys. The four-arg arity guards against that: pass opts
  `{:version v :migrate-fn f}` and, when a preserved value's stored version does
  not `=` `version`, the runtime deliberately reinits (or, with `:migrate-fn`,
  hands the old value to `f` to produce the new one) instead of reusing a
  shape-mismatched map. Silent reuse of shape-mismatched state is impossible
  once a version is declared. A malformed opts map fails loudly at the call site
  (see `validate-spool-state-opts!`) rather than degrading to the unversioned
  path."
  ([runtime key init-fn] (spool-state runtime key nil init-fn))
  ([runtime key opts init-fn]
   (validate-spool-state-opts! opts)
   (when-not (and runtime (:spool-state runtime))
     (throw (ex-info "Runtime does not support spool state" {:key key})))
   (let [{:keys [version migrate-fn]} opts
         state (:spool-state runtime)
         reuse? (fn [existing] (= version (::version (meta existing))))
         m @state]
     ;; Lock-free fast path: a present, version-matching value is reused as-is.
     (if (and (contains? m key) (reuse? (get m key)))
       (get m key)
       ;; Build path (first init OR version-mismatch reinit). Serialize it per
       ;; runtime so init-fn/migrate-fn — and the executors/schedulers they
       ;; allocate — run at most once. A lock-free CAS loser would discard its
       ;; freshly-built state and leak that value's live daemon threads for the
       ;; JVM lifetime (nothing else references it to shut it down). Reinit is
       ;; rare (a version bump on reload), so a coarse per-runtime lock is cheap;
       ;; only builders take it, readers on the fast path never do.
       (locking state
         (let [m* @state
               existing (get m* key)]
           (cond
             (not (contains? m* key))
             (let [value (versioned-value runtime (init-fn) version)]
               (swap! state assoc key value)
               value)

             (reuse? existing)
             existing

             :else
             (let [replacement (reinit-mismatched-state runtime existing version migrate-fn init-fn)]
               (swap! state assoc key replacement)
               replacement))))))))
