(ns skein.core.weaver.module-refresh
  "Internal live module refresh coordinator.

  Full refresh collects and validates the layered startup graph before runtime
  mutation. Targeted refresh uses the active graph and includes dependents.
  Source/contribution failures retain the affected owner's prior declarations;
  changed contributions prevalidate across all registered kinds before
  publication; resource reconcilers run afterward with explicit degradation."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.tools.namespace.parse :as ns-parse]
            [skein.api.spool.alpha :as spool-api]
            [skein.core.format :as format]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.module-graph :as module-graph]
            [skein.core.weaver.module-publication :as publication]
            [skein.core.weaver.scheduler :as scheduler]
            [skein.core.weaver.spool-sync :as spool-sync]))

(def plan-caveat
  "The one honest side effect a dry-run plan still incurs (DELTA-OlrRepl-001.CC14)."
  (str "Collection may evaluate module source code; synced namespace loads may append "
       "to the load ledger. No registry publication or resource reconcile runs."))

(defn initial-state
  "Return the empty runtime-owned module coordinator state."
  []
  {:graph (sorted-map)
   :layers (sorted-map)
   :shadows (sorted-map)
   :startup/files []
   :contributions (sorted-map)
   :contribution-sources (sorted-map)
   :resolved-entry-points (sorted-map)
   :resources (sorted-map)
   :outcomes (sorted-map)
   :root-outcomes (sorted-map)
   :last-refresh nil})

(defn with-startup-file
  "Call `f` with startup-file declaration provenance dynamically bound."
  [startup-file f]
  (module-graph/with-startup-file startup-file f))

(defn collect-entry!
  "Collect one authoring-form entry for the module source being evaluated."
  ([kind-id entry-key value]
   (module-graph/collect-entry! kind-id entry-key value))
  ([kind-id entry-key value opts]
   (module-graph/collect-entry! kind-id entry-key value opts)))

(defn- informative-throwable
  "Return the deepest structured cause beneath compiler and loader wrappers."
  [throwable]
  (or (last (filter ex-data
                    (take-while some? (iterate ex-cause throwable))))
      throwable))

(defn- exception-data [throwable]
  (let [causes (vec (take-while some? (iterate ex-cause throwable)))
        informative (informative-throwable throwable)]
    {:message (ex-message informative)
     :class (str (class informative))
     :data (when (some ex-data causes)
             (reduce (fn [data cause]
                       (merge data (ex-data cause)))
                     {}
                     (reverse causes)))}))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- resolve-module-fn!
  "Resolve `callable` to the fn value its Var holds, failing loudly otherwise.

  A clojure Var is itself `ifn?` regardless of what it holds, so the check
  derefs the resolved Var and tests its root value: an unresolvable symbol, or a
  Var whose value is not a fn (a plain data Var, a keyword), fails loudly
  (PROP-Dsp-001.G5)."
  [module-key role callable]
  (let [ns-sym (some-> callable namespace symbol)
        var (or (when-let [loaded-ns (and ns-sym (find-ns ns-sym))]
                  (ns-resolve loaded-ns (symbol (name callable))))
                (requiring-resolve callable))]
    (when-not (and (var? var) (fn? (deref var)))
      (fail! "Module callable did not resolve to a function"
             {:module/key module-key
              :module/role role
              :module/callable callable}))
    (deref var)))

(defn- validate-spool-value!
  "Validate a module `spool` var value against the shared `::spool` spec.

  The runtime enforcement path runs over the same spec authors validate with
  (PROP-Dsp-001.G6), so a non-map value, unknown keys, an absent entry point, or
  a non-symbol entry point (ADR-002.O1) all fail loudly with explain data."
  [module-key module-ns value]
  (when-not (s/valid? ::spool-api/spool value)
    (fail! (format/reflow
            "|Module spool declaration is malformed: it must be a map carrying a
             |:contribute and/or :reconcile symbol and no other keys")
           {:module/key module-key
            :module/namespace module-ns
            :spool value
            :explain (s/explain-data ::spool-api/spool value)}))
  value)

(defn- module-namespace
  "Return the namespace symbol that owns a module's `spool` var, or nil.

  `:ns` and `:load :image` targets own their declared namespace; a `:file`
  target owns the single namespace its file declares (nil when the file declares
  no `ns`, which keeps the authoring-forms-only path)."
  [declaration context]
  (or (:ns declaration) (:source/namespace context)))

(defn- public-spool-var
  "Return the module namespace's public `spool` var, or nil.

  Only a public interned var named `spool` in the loaded `module-ns` is the
  declaration; private and referred vars are ignored (PROP-Dsp-001.G5)."
  [module-ns]
  (when (and module-ns (find-ns module-ns))
    (get (ns-publics module-ns) 'spool)))

(defn- qualify-entry-point [module-ns sym]
  (if (namespace sym)
    sym
    (symbol (name module-ns) (name sym))))

(defn- resolve-entry-points
  "Return the effective `{:contribute sym :reconcile sym}` for a module.

  Explicit Phase A declaration keys win per key (F14 precedence); every absent
  field is filled from the module namespace's public `spool` var, whose value is
  validated against `::spool` and whose unqualified symbols are qualified against
  the declaring namespace (PROP-Dsp-001.G1/G2). Present keys only."
  [module-key declaration module-ns]
  (let [explicit (select-keys declaration [:contribute :reconcile])
        absent (remove #(contains? explicit %) [:contribute :reconcile])
        from-var (when (seq absent)
                   (when-let [spool-var (public-spool-var module-ns)]
                     (into {}
                           (map (fn [[field sym]]
                                  [field (qualify-entry-point module-ns sym)]))
                           (select-keys
                            (validate-spool-value! module-key module-ns @spool-var)
                            absent))))]
    (merge from-var explicit)))

(defn- normalize-kind-contribution [kind-id value]
  (when-not (keyword? kind-id)
    (fail! "Module contribution kind must be a keyword"
           {:kind kind-id}))
  (when-not (map? value)
    (fail! "Module contribution entries must be a map"
           {:kind kind-id :value value}))
  (let [partition? (or (contains? value :entries)
                       (contains? value :overrides))
        unknown (when partition?
                  (seq (remove #{:entries :overrides} (keys value))))
        entries (if partition? (:entries value) value)
        overrides (if partition? (or (:overrides value) #{}) #{})]
    (when unknown
      (fail! "Module contribution partition contains unknown keys"
             {:kind kind-id :unknown (vec (sort-by pr-str unknown))}))
    (when-not (map? entries)
      (fail! "Module contribution :entries must be a map"
             {:kind kind-id :entries entries}))
    (when-not (set? overrides)
      (fail! "Module contribution :overrides must be a set"
             {:kind kind-id :overrides overrides}))
    (when-let [orphan (seq (remove (set (keys entries)) overrides))]
      (fail! "Module contribution overrides absent entries"
             {:kind kind-id :keys (vec (sort-by pr-str orphan))}))
    {:entries entries :overrides overrides}))

(defn- normalize-contribution [value]
  (when-not (map? value)
    (fail! "Module contribution function must return a map"
           {:contribution value}))
  (into (sorted-map)
        (map (fn [[kind-id entries]]
               [kind-id (normalize-kind-contribution kind-id entries)]))
        value))

(defn- successful-sync? [outcome]
  (#{:loaded :already-available} (:status outcome)))

(defn- sync-root-outcomes [sync-result]
  (into (sorted-map)
        (map (fn [[root-lib outcome]]
               [root-lib (if (successful-sync? outcome)
                           {:status :synced :sync outcome}
                           {:status :failed :sync outcome
                            :reason (:reason outcome)})]))
        (:spools sync-result)))

(defn- reloadable-diff? [diff]
  (and (seq (:redefinitions diff))
       (empty? (remove #{:redefinitions :namespace-residuals} (keys diff)))
       (every? #(= :changed-bytes (:reason %))
               (:namespace-residuals diff))))

(defn- ledger-count [runtime root-lib]
  (count (filter #(= root-lib (:root-lib %))
                 (spool-sync/namespace-load-ledger runtime))))

(defn- reload-redefined-roots! [runtime redefinitions]
  (reduce
   (fn [result {:keys [lib]}]
     (let [before (ledger-count runtime lib)]
       (try
         (let [reload (spool-sync/reload-synced-spool! runtime lib)]
           (assoc result lib {:status :source-reloaded
                              :reload reload}))
         (catch Throwable throwable
           (let [after (ledger-count runtime lib)]
             (assoc result lib
                    {:status (if (< before after)
                               :partial-source-reload
                               :source-reload-failed)
                     :loaded-records (- after before)
                     :error (exception-data throwable)}))))))
   (sorted-map)
   redefinitions))

(defn- conflict-root-libs [diff]
  (set
   (concat
    (keep :lib (:removed-roots diff))
    (keep :lib (:changed-roots diff))
    (keep :lib (:redefinitions diff))
    (keep #(get-in % [:binding :root-lib]) (:namespace-residuals diff))
    (mapcat #(keep :root-lib (:providers %)) (:hard-conflicts diff)))))

(defn- conflict-root-outcomes [diff error]
  (let [root-libs (conflict-root-libs diff)
        outcome {:status :hard-conflict
                 :conflict diff
                 :remedy (:remedy error)}]
    (into (sorted-map)
          (map (fn [root-lib] [root-lib outcome]))
          root-libs)))

(defn- sync-roots!
  "Synchronize approvals, owning the ordinary changed-source reload path."
  [runtime]
  (try
    (let [sync-result (spool-sync/sync-approved-spools runtime)]
      {:sync sync-result
       :roots (sync-root-outcomes sync-result)
       :conflicts []
       :remedies []})
    (catch clojure.lang.ExceptionInfo throwable
      (let [{:keys [reason diff] :as error} (ex-data throwable)]
        (if (and (= :non-additive-sync-diff reason)
                 (reloadable-diff? diff))
          (let [reloads (reload-redefined-roots! runtime (:redefinitions diff))
                failed? (some #(#{:partial-source-reload :source-reload-failed}
                                (:status %))
                              (vals reloads))]
            (if failed?
              {:sync (spool-sync/approved-spool-syncs runtime)
               :roots reloads
               :conflicts (vec (:namespace-residuals diff))
               :remedies (cond-> [] (:remedy error) (conj (:remedy error)))}
              (try
                (let [sync-result (spool-sync/sync-approved-spools runtime)]
                  {:sync sync-result
                   :roots (merge (sync-root-outcomes sync-result) reloads)
                   :conflicts []
                   :remedies []})
                (catch Throwable retry-failure
                  {:fatal (exception-data retry-failure)
                   :roots reloads
                   :conflicts []
                   :remedies []}))))
          (if (= :non-additive-sync-diff reason)
            (let [sync-result (spool-sync/approved-spool-syncs runtime)]
              {:sync sync-result
               :roots (merge (sync-root-outcomes sync-result)
                             (conflict-root-outcomes diff error))
               :conflicts (vec (mapcat #(get diff %)
                                       [:removed-roots :changed-roots
                                        :redefinitions :namespace-residuals
                                        :hard-conflicts :maven-version-bumps]))
               :remedies (cond-> [] (:remedy error) (conj (:remedy error)))})
            {:fatal (exception-data throwable)
             :roots (sorted-map)
             :conflicts []
             :remedies []}))))
    (catch Throwable throwable
      {:fatal (exception-data throwable)
       :roots (sorted-map)
       :conflicts []
       :remedies []})))

(defn- current-root-state [runtime]
  (let [sync-result (spool-sync/approved-spool-syncs runtime)]
    {:sync sync-result
     :roots (sync-root-outcomes sync-result)
     :conflicts []
     :remedies []}))

(defn- module-root-problem [root-outcomes declaration]
  (some (fn [root-lib]
          (let [outcome (get root-outcomes root-lib)]
            (cond
              (nil? outcome)
              {:reason :not-approved :root-lib root-lib}

              (#{:synced :source-reloaded} (:status outcome))
              nil

              :else
              {:reason (or (:reason outcome) (:status outcome))
               :root-lib root-lib
               :root/outcome outcome})))
        (:spools declaration)))

(defn- dependency-problem [outcomes declaration]
  (some (fn [dependency]
          (let [outcome (get outcomes dependency)]
            (when-not (#{:ready :applied :unchanged} (:status outcome))
              {:reason :missing-dependency
               :dependency dependency
               :dependency/outcome outcome})))
        (:after declaration)))

(defn- retained-outcome [key declaration problem]
  (merge {:module/key key
          :required? (:required? declaration)
          :status (cond
                    (= :hard-conflict (:reason problem)) :refused
                    (:required? declaration) :failed
                    :else :skipped)
          :contribution/status :retained}
         problem))

(defn- latest-source-binding [runtime ns-sym]
  (->> (spool-sync/namespace-load-ledger runtime)
       (filter #(= ns-sym (:namespace %)))
       last))

(defn- source-stamp [source-binding]
  (some-> source-binding (select-keys [:root-lib :file :sha256])))

(defn- classpath-binding [runtime ns-sym]
  (some #(when (= ns-sym (:namespace %)) %)
        (:classpath-bindings (spool-sync/loaded-namespace-status runtime))))

(defn- classpath-source-file
  "Return the on-disk source path a classpath-owned namespace loaded from, or nil
  when its source is not a reachable file (a packaged jar resource, or an
  inherited-JVM binding with no recorded source URL). A classpath-owned namespace
  lives on `deps.edn :paths`, not a synced root, so its source is resolved from
  the classpath binding's recorded resource URL rather than from
  `synced-namespace-file`, which searches only synced roots."
  [classpath-binding]
  (when-let [source (:source classpath-binding)]
    (try
      (let [uri (java.net.URI. source)]
        (when (= "file" (.getScheme uri))
          (.getCanonicalPath (java.io.File. uri))))
      (catch Exception _ nil))))

(defn- ns-source-file
  "Resolve the on-disk source path for a module `:ns` target.

  A synced provider wins; a classpath-owned namespace with no synced provider
  falls back to its classpath binding's source file. Returns nil when neither is
  reachable, so callers stay non-throwing over classpath-only namespaces."
  [runtime ns-sym]
  (or (when-let [synced (try (spool-sync/synced-namespace-file runtime ns-sym)
                             (catch Exception _ nil))]
        synced)
      (classpath-source-file (classpath-binding runtime ns-sym))))

(defn- declared-file-namespace [file]
  (with-open [reader (java.io.PushbackReader. (io/reader (io/file file)))]
    (some-> (ns-parse/read-ns-decl reader)
            ns-parse/name-from-ns-decl)))

(defn- collection-context [runtime key declaration]
  (if-let [ns-sym (:ns declaration)]
    {:module/key key
     :source/file (ns-source-file runtime ns-sym)
     :source/namespace ns-sym}
    (let [file (spool-sync/module-file runtime (:file declaration))]
      {:module/key key
       :source/file file
       :source/namespace (declared-file-namespace file)})))

(defn- load-module-file! [runtime file result]
  (spool-sync/with-namespace-load-observation
    runtime #(do (load-file file) result)))

(defn- load-source!
  [runtime with-loader key declaration previous-source]
  (if-let [ns-sym (:ns declaration)]
    (let [source-binding (latest-source-binding runtime ns-sym)
          classpath-binding (classpath-binding runtime ns-sym)
          synced-file (try (spool-sync/synced-namespace-file runtime ns-sym)
                           (catch Exception _ nil))]
      (cond
        (and source-binding
             (not= previous-source (source-stamp source-binding)))
        (with-loader #(load-module-file!
                       runtime (:file source-binding)
                       {:ns ns-sym
                        :file (:file source-binding)
                        :collection/reload? true}))

        synced-file
        (with-loader #(spool-sync/load-synced-namespace! runtime ns-sym key))

        (and (find-ns ns-sym) (nil? source-binding) classpath-binding)
        (if-let [file (classpath-source-file classpath-binding)]
          (with-loader #(load-module-file!
                         runtime file
                         {:ns ns-sym
                          :file file
                          :collection/reload? true
                          :classpath-binding classpath-binding}))
          ;; No reachable on-disk source: the namespace is already live in the
          ;; image, so its Vars come from the inherited/classpath image and any
          ;; declaration contribution from an explicit :contribute. Report an
          ;; unchanged source rather than reloading.
          {:ns ns-sym :classpath-binding classpath-binding})

        :else
        (with-loader #(spool-sync/load-synced-namespace! runtime ns-sym key))))
    (let [file (spool-sync/module-file runtime (:file declaration))]
      (with-loader #(load-module-file! runtime file {:file file})))))

(defn- evaluate-image-module
  "Evaluate a `:load :image` module: trust the already-loaded JVM image for its
  `:ns` target with no source load and no contribution-collection scope. Entry
  points resolve from the namespace's `spool` var (or an explicit Phase A
  `:contribute`); image mode collects no authoring forms, so a namespace with no
  resolvable `:contribute` fails loudly (PROP-Dsp-001.G4/G5). The outcome carries
  `:source/status :image`, its resolved entry points, and no source stamp."
  [runtime with-loader key declaration]
  (let [ns-sym (:ns declaration)]
    (when-not (find-ns ns-sym)
      (fail! (format/reflow
              "|Image module namespace is not loaded in the JVM image; load or
               |require it before the module activates")
             {:module/key key :ns ns-sym :load :image}))
    (let [resolved (resolve-entry-points key declaration ns-sym)
          contribute (:contribute resolved)]
      (when-not contribute
        (fail! (format/reflow
                "|Image module resolves no :contribute entry point; its namespace
                 |needs a public spool var (or an explicit :contribute) because
                 |image mode collects no authoring forms")
               {:module/key key :ns ns-sym :load :image}))
      (let [contribution (with-loader
                           #(let [contribute-fn (resolve-module-fn!
                                                 key :contribute contribute)]
                              (contribute-fn {:runtime runtime
                                              :module/key key
                                              :module/declaration declaration})))]
        {:status :ready
         :module/key key
         :source/status :image
         :module/resolved resolved
         :contribution (normalize-contribution contribution)}))))

(defn- evaluate-module
  [runtime with-loader key declaration previous-contribution previous-source]
  (try
    (if (= :image (:load declaration))
      (evaluate-image-module runtime with-loader key declaration)
      (let [context (collection-context runtime key declaration)
            {:keys [return] collected :contribution}
            (module-graph/with-contribution-collection
              context
              #(load-source! runtime with-loader key declaration previous-source))
            source-status (if (and (:ns declaration) (nil? (:file return)))
                            :unchanged
                            :loaded)
            module-ns (module-namespace declaration context)
            resolved (resolve-entry-points key declaration module-ns)
            contribute (:contribute resolved)
            contribution (cond
                           contribute
                           (do
                             (when (and (seq collected)
                                        (not (contains? declaration :contribute)))
                               (fail! (format/reflow
                                       "|Module's spool var supplies a :contribute entry
                                        |point yet its source collected authoring forms; the
                                        |function would silently discard them, so choose one
                                        |source")
                                      {:module/key key
                                       :module/namespace module-ns
                                       :contribute contribute
                                       :collected/kinds (vec (keys collected))}))
                             (with-loader
                               #(let [contribute-fn (resolve-module-fn!
                                                     key :contribute contribute)]
                                  (contribute-fn {:runtime runtime
                                                  :module/key key
                                                  :module/declaration declaration}))))
                           (and (= :unchanged source-status)
                                (some? previous-contribution))
                           previous-contribution

                           :else collected)
            normalized (normalize-contribution contribution)]
        {:status :ready
         :module/key key
         :source/status source-status
         :source/result return
         :source/stamp (when-let [ns-sym (:ns declaration)]
                         (source-stamp (latest-source-binding runtime ns-sym)))
         :module/resolved resolved
         :contribution normalized}))
    (catch Throwable throwable
      {:status :failed
       :module/key key
       :source/status :failed
       :contribution/status :retained
       :error (exception-data throwable)})))

(defn- evaluate-affected
  [runtime with-loader graph order root-outcomes previous-contributions
   previous-sources]
  (let [unaffected (set/difference (set (keys graph)) (set order))
        seeded (into (sorted-map)
                     (map (fn [key] [key {:status :unchanged}]))
                     unaffected)]
    (select-keys
     (reduce
      (fn [outcomes key]
        (let [declaration (get graph key)
              problem (or (module-root-problem root-outcomes declaration)
                          (dependency-problem outcomes declaration))]
          (assoc outcomes key
                 (if problem
                   (retained-outcome key declaration problem)
                   (evaluate-module runtime with-loader key declaration
                                    (get previous-contributions key)
                                    (get previous-sources key))))))
      seeded
      order)
     order)))

(defn- previous-module [state key]
  {:module/declaration (get-in state [:graph key])
   :module/contribution (get-in state [:contributions key])
   :module/outcome (get-in state [:outcomes key])
   :module/resource (get-in state [:resources key])})

(defn- legacy-resolved-entry-points
  "Bootstrap resolved entry points from pre-Phase-A coordinator state.

  A live weaver may pick up this coordinator without restarting. Its existing
  state has no `:resolved-entry-points`, but its authored graph still carries
  the explicit Phase A keys. Seed those keys so a module omitted by the first
  post-upgrade full refresh can still run its retained removal reconciler."
  [state]
  (into (sorted-map)
        (keep (fn [[key declaration]]
                (let [resolved (select-keys declaration [:contribute :reconcile])]
                  (when (seq resolved)
                    [key resolved]))))
        (:graph state)))

(defn- store-source-stamp [result key raw-outcome]
  (if-let [stamp (:source/stamp raw-outcome)]
    (assoc-in result [:source-stamps key] stamp)
    (update result :source-stamps dissoc key)))

(defn- publishable-outcome [raw-outcome]
  (dissoc raw-outcome :contribution :source/stamp))

(defn- stage-publications
  [backends candidate-map graph order raw previous-contributions previous-sources]
  (let [unaffected (set/difference (set (keys graph)) (set order))
        seeded (into (sorted-map)
                     (map (fn [key] [key {:status :unchanged}]))
                     unaffected)
        staged
        (reduce
         (fn [{:keys [candidates outcomes] :as result} key]
           (let [declaration (get graph key)
                 raw-outcome (get raw key)
                 dependency (dependency-problem outcomes declaration)]
             (cond
               dependency
               (assoc-in result [:outcomes key]
                         (retained-outcome key declaration dependency))

               (not= :ready (:status raw-outcome))
               (assoc-in result [:outcomes key] raw-outcome)

               (= (get previous-contributions key) (:contribution raw-outcome))
               (-> result
                   (store-source-stamp key raw-outcome)
                   (assoc-in [:outcomes key]
                             (-> raw-outcome
                                 publishable-outcome
                                 (assoc :status :unchanged
                                        :contribution/status :unchanged))))

               :else
               (try
                 (let [next-candidates (publication/stage-owner
                                        backends candidates key
                                        (:contribution raw-outcome))]
                   (-> result
                       (assoc :candidates next-candidates)
                       (store-source-stamp key raw-outcome)
                       (assoc-in [:contributions key] (:contribution raw-outcome))
                       (assoc-in [:outcomes key]
                                 (-> raw-outcome
                                     publishable-outcome
                                     (assoc :status :applied
                                            :contribution/status :replaced)))))
                 (catch Throwable throwable
                   (assoc-in result [:outcomes key]
                             (-> raw-outcome
                                 publishable-outcome
                                 (assoc :status :failed
                                        :contribution/status :retained
                                        :error (exception-data throwable)))))))))
         {:candidates candidate-map
          :outcomes seeded
          :contributions previous-contributions
          :source-stamps previous-sources}
         order)]
    (update staged :outcomes #(select-keys % order))))

(defn- provisional-result
  [mode roots conflicts remedies shadows outcomes removed resolved-entry-points]
  {:status :unchanged
   :mode mode
   :modules (into (sorted-map)
                  (concat (map (fn [key]
                                 [key {:module/key key
                                       :status :removed
                                       :contribution/status :removed}])
                               removed)
                          outcomes))
   :roots roots
   :residuals []
   :conflicts conflicts
   :remedies remedies
   :resolved/entry-points resolved-entry-points
   :declaration/shadows shadows})

(defn- reconcile-one
  [runtime with-loader state graph resolved-entry-points result key outcome]
  (let [declaration (get graph key)
        previous (previous-module state key)
        ;; The reconciler comes from the module's retained resolved entry-point
        ;; set, so removal-by-omission still tears down (its source is never
        ;; re-loaded on the removal path) — PROP-Dsp-001.G2a/F11.
        reconcile (get-in resolved-entry-points [key :reconcile])]
    (if (or (nil? reconcile)
            (not (#{:applied :removed} (:status outcome))))
      {:outcome outcome}
      (try
        (let [return (with-loader
                       #(let [reconcile-fn (resolve-module-fn!
                                            key :reconcile reconcile)]
                          (reconcile-fn
                           {:runtime runtime
                            :module/key key
                            :module/declaration declaration
                            :module/previous previous
                            :module/contribution outcome
                            :refresh/result result})))]
          (when-not (dispatch/data-first-value? return)
            (fail! "Module reconcile return must be data-first"
                   {:module/key key :return return}))
          {:outcome (assoc outcome :reconcile/status :applied
                           :reconcile/result return)
           :resource {:status :applied :result return}})
        (catch Throwable throwable
          {:outcome (assoc outcome :status :degraded
                           :reconcile/status :failed
                           :reconcile/error (exception-data throwable))
           :resource {:status :degraded
                      :error (exception-data throwable)}})))))

(defn- reconcile-modules
  [runtime with-loader state graph resolved-entry-points result order]
  (reduce
   (fn [{:keys [outcomes resources]} key]
     (let [{:keys [outcome resource]}
           (reconcile-one runtime with-loader state graph resolved-entry-points
                          result key (get outcomes key))]
       {:outcomes (assoc outcomes key outcome)
        :resources (cond-> resources resource (assoc key resource))}))
   {:outcomes (:modules result)
    :resources (:resources state)}
   order))

(defn- top-status [graph outcomes roots changed-kinds]
  (let [module-values (vals outcomes)
        failures (filter #(#{:failed :degraded :refused} (:status %))
                         module-values)
        required-roots (into #{}
                             (comp (filter (comp :required? val))
                                   (mapcat (comp :spools val)))
                             graph)
        root-failures (keep (fn [[root-lib outcome]]
                              (when (and (contains? required-roots root-lib)
                                         (#{:failed :hard-conflict
                                            :partial-source-reload
                                            :source-reload-failed}
                                          (:status outcome)))
                                outcome))
                            roots)
        changed? (or (seq changed-kinds)
                     (some #(#{:applied :removed :degraded} (:status %))
                           module-values))]
    (cond
      (and (seq failures)
           (every? #(= :refused (:status %)) module-values)
           (not changed?)) :refused
      (or (seq failures) (seq root-failures)) :partial
      changed? :applied
      :else :unchanged)))

(defn- safe-loaded-status [runtime]
  (try
    (spool-sync/loaded-namespace-status runtime)
    (catch Throwable throwable
      {:residuals []
       :hard-conflicts []
       :classification/error (exception-data throwable)})))

(defn- plan-result
  "Assemble the dry-run intentions from staged candidates without publishing.

  Diffs the staged candidate snapshots against the live backends, classifies
  loaded code, and returns a refresh-result-shaped map flagged `:dry-run?` with
  the honest caveat. No registry publication, resource reconcile, or coordinator
  state write occurs; source loads during collection already happened."
  [runtime sync-result staged provisional backends graph]
  (let [changed-kinds (publication/changed-kinds backends (:candidates staged))
        loaded-status (safe-loaded-status runtime)
        outcomes (:modules provisional)
        status (top-status graph outcomes (:roots sync-result) changed-kinds)]
    (assoc provisional
           :status status
           :dry-run? true
           :caveat plan-caveat
           :residuals (:residuals loaded-status)
           :conflicts (vec (concat (:conflicts provisional)
                                   (:hard-conflicts loaded-status)))
           :publication/kinds (vec (sort-by pr-str changed-kinds)))))

(defn- record-result!
  [runtime collection contributions contribution-sources resolved-entry-points
   resources outcomes roots result]
  (swap! (:module-state runtime)
         (fn [state]
           (-> state
               (assoc :graph (:graph collection)
                      :layers (:layers collection)
                      :shadows (:shadows collection)
                      :startup/files (mapv #(dissoc % :return)
                                           (:files collection))
                      :contributions (into (sorted-map) contributions)
                      :contribution-sources (into (sorted-map) contribution-sources)
                      :resolved-entry-points (into (sorted-map) resolved-entry-points)
                      :resources (into (sorted-map) resources)
                      :outcomes (into (sorted-map) outcomes)
                      :root-outcomes (into (sorted-map) roots)
                      :last-refresh result))))
  result)

(defn- refused-result [mode error]
  {:status :refused
   :mode mode
   :modules (sorted-map)
   :roots (sorted-map)
   :residuals []
   :conflicts [error]
   :remedies []
   :resolved/entry-points (sorted-map)
   :declaration/shadows (sorted-map)})

(defn- record-refused-result! [runtime opts result]
  (if (:dry-run? opts)
    (assoc result :dry-run? true :caveat plan-caveat)
    (do
      (swap! (:module-state runtime) assoc :last-refresh result)
      result)))

(defn- select-refresh
  [runtime load-startup-files! opts]
  (let [state @(:module-state runtime)]
    (cond
      (:declare opts)
      (let [[key declaration] (:declare opts)
            declaration (module-graph/normalize-declaration key declaration)
            graph (assoc (:graph state) key declaration)
            {:keys [order]} (module-graph/validate-graph graph)]
        {:mode :targeted
         :collection (assoc (select-keys state [:layers :shadows :startup/files])
                            :files (:startup/files state)
                            :graph graph
                            :order order)
         :selected #{key}})

      (contains? opts :only)
      (let [selected (:only opts)]
        (when-not (and (coll? selected) (seq selected) (every? keyword? selected))
          (fail! "Targeted refresh :only must be a non-empty collection of module keys"
                 {:only selected}))
        (let [selected (set selected)
              unknown (set/difference selected (set (keys (:graph state))))]
          (when (seq unknown)
            (fail! "Targeted refresh names unknown module keys"
                   {:unknown (vec (sort-by pr-str unknown))}))
          {:mode :targeted
           :collection (assoc (select-keys state [:graph :layers :shadows])
                              :files (:startup/files state)
                              :order (module-graph/dependency-order (:graph state)))
           :selected selected}))

      :else
      {:mode :full
       :collection (module-graph/collect-modules load-startup-files!)})))

(defn refresh!
  "Collect or select modules and reconcile the live runtime.

  `context` supplies `:load-startup-files!` and `:with-loader` callbacks owned
  by the daemon runtime namespace. `opts` is empty for full refresh, carries
  `:only` for targeted refresh, or internal `:declare` for module declaration
  outside startup collection. `:dry-run? true` uses the current synchronized
  roots, then runs collection, source-load, and staging without synchronizing,
  publishing, reconciling, or recording coordinator state (CC14)."
  [runtime {:keys [load-startup-files! with-loader]} opts]
  ;; The runtime slot is one dedicated Object monitor. Splint cannot see the
  ;; stable object behind the map lookup; refreshes serialize so two collectors
  ;; never publish interleaved desired graphs.
  #_{:splint/disable [lint/locking-object]}
  (locking (:module-refresh-lock runtime)
    (let [selection
          (try
            (select-refresh runtime load-startup-files! opts)
            (catch Throwable throwable
              (if (:startup? opts)
                (throw throwable)
                (if (or (contains? opts :only) (:declare opts))
                  (throw throwable)
                  {:refused
                   (record-refused-result!
                    runtime opts
                    (refused-result :full (exception-data throwable)))}))))]
      (if-let [refused (:refused selection)]
        refused
        (let [{:keys [mode collection selected]} selection
              state @(:module-state runtime)
              old-graph (:graph state)
              graph (:graph collection)
              removed (if (= :full mode)
                        (set/difference (set (keys old-graph)) (set (keys graph)))
                        #{})
              selected (or selected (set (keys graph)))
              order (module-graph/affected-modules graph selected)
              ;; An empty graph needs no acquisition pass. Any desired or current
              ;; module graph owns synchronization through this coordinator.
              sync-result (if (:dry-run? opts)
                            (current-root-state runtime)
                            (if (or (seq graph) (seq old-graph))
                              (sync-roots! runtime)
                              (current-root-state runtime)))]
          (if-let [fatal (:fatal sync-result)]
            (if (:startup? opts)
              (throw (ex-info "Initial module refresh could not synchronize approved roots"
                              fatal))
              (record-refused-result! runtime opts (refused-result mode fatal)))
            (try
              (let [previous-contributions (:contributions state)
                    previous-sources (:contribution-sources state)
                    raw (evaluate-affected runtime with-loader graph order
                                           (:roots sync-result)
                                           previous-contributions previous-sources)
                    ;; Retain each module's last-good resolved entry-point set:
                    ;; a failed evaluation keeps the prior set, and removed
                    ;; modules keep theirs through the removal reconcile before
                    ;; dropping out of the exposed/stored set (PROP-Dsp-001.G2a).
                    resolved-entry-points
                    (merge (legacy-resolved-entry-points state)
                           (:resolved-entry-points state)
                           (into {}
                                 (keep (fn [[key outcome]]
                                         (when (contains? outcome :module/resolved)
                                           [key (:module/resolved outcome)]))
                                       raw)))
                    exposed-resolved (apply dissoc resolved-entry-points removed)
                    backends (publication/backends runtime)
                    base-candidates (reduce publication/remove-owner
                                            (publication/candidates backends)
                                            removed)
                    staged (stage-publications backends base-candidates graph order raw
                                               previous-contributions previous-sources)
                    _ (publication/validate-op-candidates! backends (:candidates staged))
                    provisional (provisional-result mode (:roots sync-result)
                                                    (:conflicts sync-result)
                                                    (:remedies sync-result)
                                                    (:shadows collection)
                                                    (:outcomes staged)
                                                    removed
                                                    exposed-resolved)]
                ;; A dry-run stops here: it has collected, classified, staged and
                ;; validated candidates but publishes nothing, reconciles nothing,
                ;; and records no coordinator state (DELTA-OlrRepl-001.CC14).
                (if (:dry-run? opts)
                  (plan-result runtime sync-result staged provisional backends graph)
                  (let [live-candidates (publication/candidates backends)
                        changed-kinds (publication/publish! backends (:candidates staged))
                        removal-order (->> (module-graph/dependency-order old-graph)
                                           reverse
                                           (filter removed))
                        reconcile-order (vec (concat removal-order order))
                        reconciled (reconcile-modules runtime with-loader state graph
                                                      resolved-entry-points
                                                      provisional reconcile-order)
                        _ (try
                            (publication/validate-op-glossary-refs!
                             runtime backends (:candidates staged))
                            (catch Throwable throwable
                              (publication/publish! backends live-candidates)
                              (throw throwable)))
                        contributions (apply dissoc (:contributions staged) removed)
                        contribution-sources (apply dissoc (:source-stamps staged) removed)
                        loaded-status (safe-loaded-status runtime)
                        outcomes (:outcomes reconciled)
                        state-outcomes (-> (:outcomes state)
                                           (merge outcomes)
                                           (#(apply dissoc % removed)))
                        status (top-status graph outcomes (:roots sync-result) changed-kinds)
                        result (assoc provisional
                                      :status status
                                      :modules outcomes
                                      :residuals (:residuals loaded-status)
                                      :conflicts (vec (concat (:conflicts provisional)
                                                              (:hard-conflicts loaded-status)))
                                      :publication/kinds (vec (sort-by pr-str changed-kinds)))]
                    (record-result! runtime collection contributions contribution-sources
                                    exposed-resolved (:resources reconciled) state-outcomes
                                    (:roots sync-result) result))))
              (catch Throwable throwable
                ;; Source loads may already have occurred, but no publication
                ;; follows a coordinator-wide validation failure.
                (if (:startup? opts)
                  (throw throwable)
                  (record-refused-result!
                   runtime opts
                   (refused-result mode (exception-data throwable))))))))))))

(defn module!
  "Stage a module during startup collection, otherwise declare and refresh it."
  [runtime context key opts]
  (if (module-graph/collecting-modules?)
    (module-graph/stage-module! key opts)
    (refresh! runtime context {:declare [key opts]})))

(defn status
  "Return the coordinator's offline module state joined with loaded-code state."
  [runtime]
  (let [state @(:module-state runtime)
        loaded (safe-loaded-status runtime)]
    {:modules (:graph state)
     :declaration/layers (:layers state)
     :declaration/shadows (:shadows state)
     :contributions (:contributions state)
     ;; The last-good resolved entry points sit alongside the authored `:modules`
     ;; graph, never inside it (PROP-Dsp-001.G2a).
     :resolved/entry-points (:resolved-entry-points state)
     :module/outcomes (:outcomes state)
     :resource/outcomes (:resources state)
     :root/outcomes (:root-outcomes state)
     :pending-generation @(:pending-spool-generation runtime)
     :scheduler/wakes (scheduler/wake-status runtime)
     :loaded loaded
     :last-refresh (:last-refresh state)}))
