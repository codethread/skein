(ns skein.api.runtime.alpha
  "Explicit-runtime API for trusted weaver runtime loader/config workflows.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. Use `skein.api.current.alpha/runtime` only at trusted in-process entry
  points that need to capture the active runtime."
  (:refer-clojure :exclude [sync use])
  (:require [skein.api.weaver.alpha :as api]
            [skein.core.weaver.spool-sync :as spool-sync]))

(defn approved
  "Return the normalized approved spool roots for `runtime`'s config dir."
  [runtime]
  (spool-sync/approved-spools runtime))

(defn sync!
  "Load approved local roots into `runtime`."
  [runtime]
  (spool-sync/sync-approved-spools runtime))

(defn syncs
  "Return `runtime`'s most recent approved-root sync state."
  [runtime]
  (spool-sync/approved-spool-syncs runtime))

(defn reload!
  "Reload startup files from `runtime`'s config dir after clearing registries."
  [runtime]
  (api/reload-config! runtime))

(defn use!
  "Activate a weaver-side module in `runtime` and record its use state."
  [runtime key opts]
  (api/use! runtime key opts))

(defn uses
  "Return `runtime`'s module-use registry as data-first maps."
  [runtime]
  (api/uses runtime))

(defn use
  "Return one module-use registry entry from `runtime` by key."
  [runtime key]
  (api/use runtime key))

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
  Mirrors the closed-key discipline `skein.spools.shuttle/defharness!` already
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

(defn- versioned-value
  "Tag `value` with its declared spool-state `version` for later reload checks.

  Version nil (the unversioned default) leaves `value` untouched. A declared
  version is stored as value metadata, so `close-fn` lookups and consumers still
  see the plain state value; versioned state must therefore support metadata."
  [value version]
  (if (nil? version)
    value
    (if (instance? clojure.lang.IObj value)
      (vary-meta value assoc ::version version)
      (throw (ex-info "Versioned spool state must support metadata"
                      {:version version :class (class value)})))))

(defn- reinit-mismatched-state
  "Build the replacement value when preserved `existing` state mismatches the
  declared `version`.

  With a `migrate-fn`, it owns `existing` (including any resources it holds) and
  returns the new value. Without one, `existing`'s `:close-fn` runs best-effort
  so a stale executor or scheduler is released, then `init-fn` builds fresh
  state — preserving nothing. The result is re-tagged with `version`."
  [existing version migrate-fn init-fn]
  (versioned-value
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
             (let [value (versioned-value (init-fn) version)]
               (swap! state assoc key value)
               value)

             (reuse? existing)
             existing

             :else
             (let [replacement (reinit-mismatched-state existing version migrate-fn init-fn)]
               (swap! state assoc key replacement)
               replacement))))))))
