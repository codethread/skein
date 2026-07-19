(ns skein.api.runtime.alpha
  "Explicit-runtime API for trusted weaver runtime loader/config workflows.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. Use `skein.api.current.alpha/runtime` only at trusted in-process
  entry points that need to capture the active runtime.

  The module reads as the spool lifecycle: read the approved/declared config
  (`approved`, `declared`, `release-marker`), edit the primary `spools.edn`
  (`upsert-spool-entry!`, `remove-spool-entry!`), load approved roots
  (`sync!`, `syncs`), make updated code live (`reload!`, `reload-spool!`),
  activate modules (`use!`, `uses`, `use-entry`), and serve runtime-owned
  state and time to trusted spools (`spool-state`, `now`). Component
  sub-specs live in `skein.api.runtime.internal.shapes`; every registered
  key stays alpha-qualified."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [skein.api.runtime.internal.shapes :as shapes]
            [skein.api.runtime.internal.spools-edn :as spools-edn]
            [skein.api.spool.alpha :refer [require-valid!]]
            [skein.core.specs :as specs]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.weaver.spool-sync :as spool-sync]))

(declare running-release-marker spools-file
         validate-approved-result! validate-declared-result!
         validate-sync-result! validate-sync-ex-data!
         families-requiring
         validate-use-opts! record-use! skip-use
         use-spool-skip use-after-skip exception-data
         validate-spool-state-opts! versioned-value reinit-mismatched-state)

;; --- reading spool config ---------------------------------------------------

(s/def ::approved-result :skein.core.weaver.spool-sync/approved-result)
(s/def ::declared-result :skein.core.weaver.spool-sync/declared-result)
(s/def ::running-marker (s/or :marker string?
                              :unavailable #{:none}
                              :deferred nil?))

(defn approved
  "Return the normalized approved spool roots for `runtime`'s config dir.

  Each root entry includes `:provenance :spools-edn|:local-overlay`; overlay
  entries also include their explicit `:claims` marker. `:families` maps family
  symbols to the declared `spools.edn` entry, effective post-overlay coordinate,
  provenance, and overlay claim or nil. The result conforms to
  `::approved-result`."
  [runtime]
  (validate-approved-result!
   (spool-sync/approved-spools runtime (running-release-marker runtime))))

(s/fdef approved
  :args (s/cat :runtime map?)
  :ret ::approved-result)

(defn declared
  "Return declared spool families with release-floor validation as data.

  `:families` has the same declared/effective projection as `approved`.
  `:requirements` is valid with pending validations, or invalid with findings
  and bump suggestions. Stage-1 structural errors still throw. The explicit
  `running-marker` arity accepts nil to leave Skein floor checks pending. The
  result conforms to `::declared-result`."
  ([runtime]
   (declared runtime (running-release-marker runtime)))
  ([runtime running-marker]
   (validate-declared-result!
    (spool-sync/declared-spools runtime running-marker))))

(s/fdef declared
  :args (s/or :runtime (s/cat :runtime map?)
              :with-marker (s/cat :runtime map?
                                  :running-marker ::running-marker))
  :ret ::declared-result)

(defn release-marker
  "Return the running Skein release marker and its provenance.

  The result has marker `vN` and provenance `:claimed` for an explicit startup
  claim, marker `vN` and provenance `:tag` for an annotated tag on the source
  checkout's HEAD, or `{:marker nil :provenance :none}` when the checkout
  resource is absent or non-filesystem, or successful inspection finds no
  matching annotated tag. Git startup, checkout-root resolution, and nonzero
  Git command failures throw. Consumers that require marker arithmetic must
  reject `:none` explicitly. The result conforms to
  `:skein.core.specs/release-marker-result`; marker claims conform to
  `:skein.core.specs/release-marker-claim`."
  [runtime]
  (let [result (access/release-marker runtime)]
    (require-valid! ::specs/release-marker-result result
                    "runtime release marker has an invalid shape")
    result))

(s/fdef release-marker
  :args (s/cat :runtime map?)
  :ret :skein.core.specs/release-marker-result)

;; --- the spools.edn write seam ----------------------------------------------

(s/def ::spool-family symbol?)
(s/def ::spool-entry :skein.core.weaver.spool-sync/family-entry)
(s/def ::spool-write-result
  (s/and #(shapes/exact-keys? #{:status :lib :entry :file} %)
         #(s/valid? ::spool-write-status (:status %))
         #(s/valid? ::spool-family (:lib %))
         #(s/valid? ::spool-entry (:entry %))
         #(s/valid? ::specs/spools-file-result (:file %))))

(defn upsert-spool-entry!
  "Insert or replace `lib` in `runtime`'s primary `spools.edn`.

  `lib` and `entry` conform to `::spool-family` and `::spool-entry`. The full
  post-edit config is validated through sync's stage-1 contract before an atomic
  write. Only the `:spools` map is rewritten, so comments outside it are kept.
  The result conforms to `::spool-write-result`."
  [runtime lib entry]
  (require-valid! ::spool-family lib "upsert-spool-entry! lib must be a symbol")
  (let [^java.io.File file (spools-file runtime)
        original (spools-edn/read-primary file)
        config (spools-edn/parse-primary original file)
        _ (spool-sync/validate-shared-spools-config! file config)
        existed? (contains? (:spools config) lib)
        updated (assoc-in config [:spools lib] entry)]
    (spools-edn/write-primary! file original updated)
    {:status (if existed? :updated :inserted)
     :lib lib
     :entry entry
     :file file}))

(s/fdef upsert-spool-entry!
  :args (s/cat :runtime map? :lib ::spool-family :entry ::spool-entry)
  :ret ::spool-write-result)

(defn remove-spool-entry!
  "Remove `lib` from `runtime`'s primary `spools.edn`.

  Refuses a missing family or a family whose root libs appear in another
  family's `:requires`, naming all requirers. Inputs and result conform to
  `::spool-family` and `::spool-write-result`. Only the primary file is changed."
  [runtime lib]
  (require-valid! ::spool-family lib "remove-spool-entry! lib must be a symbol")
  (let [^java.io.File file (spools-file runtime)
        original (spools-edn/read-primary file)
        config (spools-edn/parse-primary original file)
        _ (spool-sync/validate-shared-spools-config! file config)
        entry (get-in config [:spools lib])]
    (when-not entry
      (throw (ex-info "Spool family is not present in spools.edn"
                      {:reason :spool-family-not-found :lib lib :file (.getPath file)})))
    (let [normalized (:families (spool-sync/validate-shared-spools-config! file config))
          target (some #(when (= lib (:family %)) %) normalized)
          roots (set (keys (:roots-map target)))
          requirers (families-requiring normalized lib roots)]
      (when (seq requirers)
        (throw (ex-info "Spool family is required by other families"
                        {:reason :spool-family-required
                         :lib lib
                         :roots roots
                         :requirers requirers})))
      (spools-edn/write-primary! file original (update config :spools dissoc lib))
      {:status :removed :lib lib :entry entry :file file})))

(s/fdef remove-spool-entry!
  :args (s/cat :runtime map? :lib ::spool-family)
  :ret ::spool-write-result)

;; --- syncing approved roots -------------------------------------------------

(s/def ::sync-result :skein.core.weaver.spool-sync/sync-result)
(s/def ::pending-generation
  (s/keys :req-un [::status ::generation ::diff ::approved-spools ::remedy]))
(s/def ::non-additive-sync-diff-ex-data
  (s/keys :req-un [::status ::reason ::diff ::pending-generation ::remedy]))

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
    (validate-sync-result!
     (spool-sync/sync-approved-spools runtime (running-release-marker runtime)))
    (catch clojure.lang.ExceptionInfo ex
      (validate-sync-ex-data! (ex-data ex))
      (throw ex))))

(s/fdef sync!
  :args (s/cat :runtime map?)
  :ret ::sync-result)

(defn syncs
  "Return `runtime`'s most recent approved-root sync state.

  The result is `{:spools ...}` and may include the latest recorded
  `:pending-generation` from a refused non-additive sync diff."
  [runtime]
  (validate-sync-result! (spool-sync/approved-spool-syncs runtime)))

(s/fdef syncs
  :args (s/cat :runtime map?)
  :ret ::sync-result)

;; --- making updated code live -----------------------------------------------

(defn reload!
  "Reload startup files from `runtime`'s config dir after clearing registries.

  Returns the core reload result map (`:status`, the loaded `:files`, and
  their `:returns`)."
  [runtime]
  (weaver-runtime/reload-config! runtime))

(s/fdef reload!
  :args (s/cat :runtime map?)
  :ret map?)

(s/def ::root-lib symbol?)
(s/def ::reload-spool-result
  (s/and #(shapes/exact-keys? #{:root-lib :root :namespaces} %)
         #(s/valid? ::root-lib (:root-lib %))
         #(s/valid? ::canonical-root (:root %))
         #(s/valid? ::namespaces (:namespaces %))))

(defn reload-spool!
  "Make `root-lib`'s latest synced source live in `runtime`.

  `root-lib` is a root-lib symbol from a family's effective `:roots` map (e.g.
  `skein.spools/kanban`). Sync state is keyed by root lib, not family or
  namespace. Returns a data-first map naming the root lib, its resolved
  canonical root, and the namespaces reloaded in reload order with their
  source files.

  Fills the gap neither existing reload path covers: `reload!` re-runs startup
  files but does not unload already-loaded namespaces or vars, and a bare
  `(require ns :reload)` is classloader-blind to per-spool synced roots — so
  neither picks up updated synced spool code. `reload-spool!` does. It reloads
  code only and leaves re-registration to the caller (a targeted re-`use!` of
  the spool's activation, or a full `reload!` when the bump changes
  registrations across the config).

  Redefinition semantics — this re-`load-file`s sources, rebinding vars in
  place; it unloads nothing, so definitions minted before the reload are not
  migrated. Concretely: a `defmulti` dispatch table survives (re-evaluating
  `defmulti` is a no-op, so methods registered against the prior table stay
  and a changed dispatch signature is not picked up), a re-evaluated
  `defprotocol` mints a fresh interface so instances built before the reload
  no longer satisfy the new protocol (`satisfies?`/`instance?` go false), and
  any instance or captured var from before the reload keeps its old
  definition. A revision that deletes or renames a namespace also leaves the
  old one loaded until restart.

  Fails loudly on an unresolvable `root-lib`, carrying a `:reason` keyword in
  ex-data. Successful results conform to
  `:skein.api.runtime.alpha/reload-spool-result`."
  [runtime root-lib]
  (require-valid! ::root-lib root-lib "reload-spool! root-lib must be a symbol")
  (let [result (spool-sync/reload-synced-spool! runtime root-lib)]
    (require-valid! ::reload-spool-result result
                    "reload-spool! result has an invalid shape")
    result))

(s/fdef reload-spool!
  :args (s/cat :runtime map? :root-lib ::root-lib)
  :ret ::reload-spool-result)

;; --- activating modules -----------------------------------------------------

(def ^:private allowed-use-keys #{:ns :file :spools :after :call :required?})

(s/def ::use-key keyword?)
(s/def ::use-opts
  (s/and (s/keys :opt-un [::ns ::file ::spools ::after ::call ::required?])
         #(every? allowed-use-keys (keys %))
         #(not= (contains? % :ns) (contains? % :file))))
(s/def ::use-registration (s/tuple ::use-key ::use-opts))
(s/def ::use-entry
  (s/or :loaded ::loaded-use-entry
        :skipped ::skipped-use-entry
        :failed ::failed-use-entry))
(s/def ::uses-result (s/map-of ::use-key ::use-entry))
(s/def ::use-result (s/nilable ::use-entry))

(defn use!
  "Load a runtime module and record its module-use state under keyword key.

  Opts load either a synced namespace via `:ns` or a file via `:file`, and may
  include `:call` to invoke a no-arg function after load. Returns a registry
  entry with status `:loaded`, `:skipped`, or `:failed`; failed required uses
  rethrow after recording failure metadata. The key/options pair conforms to
  `:skein.api.runtime.alpha/use-registration`; the returned and recorded entry
  conforms to `:skein.api.runtime.alpha/use-entry`."
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
          (record-use! runtime key
                       (cond-> {:key key
                                :opts opts
                                :status :loaded
                                :loaded load-result}
                         (contains? opts :call)
                         (assoc :call {:fn (:call opts) :return call-result}))))
        (catch Exception t
          (let [result (record-use! runtime key {:key key
                                                 :opts opts
                                                 :status :failed
                                                 :error (exception-data t)})]
            (when (:required? opts)
              (throw t))
            result))))))

(s/fdef use!
  :args (s/cat :runtime map? :key ::use-key :opts ::use-opts)
  :ret ::use-entry)

(defn uses
  "Return `runtime`'s module-use registry as data-first maps.

  The result conforms to `:skein.api.runtime.alpha/uses-result`."
  [runtime]
  (let [result (into (sorted-map) @(access/module-use-state runtime))]
    (require-valid! ::uses-result result "Module use registry has an invalid shape")
    result))

(s/fdef uses
  :args (s/cat :runtime map?)
  :ret ::uses-result)

(defn use-entry
  "Return one module-use registry entry from `runtime` by key.

  The nilable result conforms to `:skein.api.runtime.alpha/use-result`."
  [runtime key]
  (let [result (get @(access/module-use-state runtime) key)]
    (require-valid! ::use-result result "Module use entry has an invalid shape")
    result))

(s/fdef use-entry
  :args (s/cat :runtime map? :key ::use-key)
  :ret ::use-result)

;; --- runtime-owned services for trusted spools ------------------------------

(defn now
  "Return the current java.time.Instant from `runtime`'s clock seam.

  Defaults to the real wall clock; deterministic tests inject an advanceable
  clock through `skein.test.alpha/set-clock!`."
  [runtime]
  (weaver-runtime/now runtime))

(s/fdef now
  :args (s/cat :runtime map?)
  :ret inst?)

(def ^:private spool-state-opt-keys #{:version :migrate-fn})

;; ::version is also the metadata key stamped on versioned spool-state values;
;; renaming it would make every preserved versioned state look mismatched on
;; the next upgrade and force a spurious reinit.
(s/def ::version (s/or :integer integer? :keyword keyword? :string string?))
(s/def ::spool-state-opts
  (s/nilable
   (s/and (s/keys :opt-un [::version ::migrate-fn])
          #(every? spool-state-opt-keys (keys %))
          #(or (not (contains? % :migrate-fn))
               (contains? % :version)))))

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
  once a version is declared. Opts conform to
  `:skein.api.runtime.alpha/spool-state-opts`; a malformed map fails loudly at
  the call site rather than degrading to the unversioned path."
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
             (let [replacement (reinit-mismatched-state
                                runtime existing version migrate-fn init-fn)]
               (swap! state assoc key replacement)
               replacement))))))))

(s/fdef spool-state
  :args (s/or :unversioned (s/cat :runtime map? :key any? :init-fn ifn?)
              :versioned (s/cat :runtime map? :key any?
                                :opts ::spool-state-opts :init-fn ifn?))
  :ret any?)

;; --- release marker and spools.edn access -----------------------------------

(defn- running-release-marker [runtime]
  (let [result (access/release-marker runtime)
        _ (require-valid! ::specs/release-marker-result result
                          "runtime release marker has an invalid shape")
        {:keys [marker provenance]} result]
    (if (= :none provenance) :none marker)))

(defn- spools-file
  "Return the `java.io.File` for `runtime`'s shared `spools.edn`.

  The result conforms to `:skein.core.specs/spools-file-result`."
  [runtime]
  (let [result (access/spools-file runtime "spools.edn")]
    (require-valid! ::specs/spools-file-result result
                    "runtime spools file has an invalid shape")
    result))

(defn- families-requiring
  "Return `{:family :roots}` rows for the families other than `lib` whose
  `:requires` name any root in `roots`."
  [normalized lib roots]
  (->> normalized
       (remove #(= lib (:family %)))
       (keep (fn [{:keys [family requires]}]
               (let [required (set (filter roots (keys requires)))]
                 (when (seq required)
                   {:family family :roots required}))))
       vec))

;; --- result-shape validators ------------------------------------------------

(defn- validate-sync-result! [result]
  (require-valid! ::sync-result result "runtime sync result has an invalid shape")
  result)

(defn- validate-approved-result! [result]
  (require-valid! ::approved-result result "runtime approved spool config has an invalid shape")
  result)

(defn- validate-declared-result! [result]
  (require-valid! ::declared-result result "runtime declared spool config has an invalid shape")
  result)

(defn- validate-sync-ex-data! [data]
  (when (= :non-additive-sync-diff (:reason data))
    (require-valid! ::non-additive-sync-diff-ex-data data
                    "runtime sync exception data has an invalid shape")))

;; --- module-use bookkeeping -------------------------------------------------

(defn- validate-use-opts! [key opts]
  (require-valid! ::use-registration [key opts]
                  "Module use key/options have an invalid shape")
  (when (and (contains? opts :file) (.isAbsolute (io/file (:file opts))))
    (throw (ex-info "Module use :file must be relative to selected config-dir"
                    {:key key :file (:file opts)})))
  opts)

(defn- record-use! [runtime key result]
  (require-valid! ::use-entry result "Module use result has an invalid shape")
  (swap! (access/module-use-state runtime) assoc key result)
  result)

(defn- skip-use [runtime key opts reason data]
  (let [result (record-use! runtime key
                            (merge {:key key :opts opts :status :skipped :reason reason} data))]
    (when (and (:required? opts) (#{:not-approved :not-synced :sync-failed} reason))
      (throw (ex-info "Required module use was skipped" result)))
    result))

(defn- use-spool-skip
  "Return the first `[reason data]` skip for `opts`' `:spools` prerequisites."
  [runtime opts]
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

(defn- use-after-skip
  "Return the `[reason data]` skip when an `:after` use is not loaded."
  [runtime opts]
  (let [uses @(access/module-use-state runtime)]
    (some (fn [after]
            (when-not (= :loaded (:status (get uses after)))
              [:missing-after {:after after :use (get uses after)}]))
          (:after opts))))

(defn- exception-data [t]
  {:message (ex-message t)
   :class (str (class t))
   :data (ex-data t)})

;; --- spool-state versioning -------------------------------------------------

(defn- warn!
  "Emit a loud-but-non-fatal runtime warning to the weaver's stderr log.

  Used where discarding a signal entirely would be worse than continuing but a
  hard failure is not warranted (a best-effort resource cleanup that fails
  during a version-mismatch reinit): the reinit still proceeds and the
  divergence stays visible in the weaver log instead of vanishing."
  [message data]
  (binding [*out* *err*]
    (println (str "[runtime] WARN " message " " (pr-str data)))))

(defn- validate-spool-state-opts!
  "Validate spool-state opts against their owning public spec."
  [opts]
  (require-valid! ::spool-state-opts opts
                  "spool-state opts have an invalid shape"))

(defn- tag-spool-state-generation
  "Tag `value` with the runtime generation that created it, when metadata
  permits it."
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
