(ns skein.api.weaver.alpha
  "Trusted in-process API for manipulating strands and weaver runtime registries."
  (:refer-clojure :exclude [list update use])
  (:require [clojure.java.io :as io]
            [clojure.repl.deps :as repl-deps]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [skein.core.weaver.runtime :as runtime]
            [skein.core.db :as db]
            [skein.core.query :as query])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- normalize-row
  "Decode JSON-backed row fields returned by persistence."
  [row]
  (cond-> row
    (string? (:attributes row)) (clojure.core/update :attributes db/<-json)))

(defn- normalize
  "Recursively decode persistence-shaped rows into Clojure data."
  [result]
  (cond
    (map? result) (into {} (map (fn [[k v]] [k (normalize v)])) (normalize-row result))
    (sequential? result) (mapv normalize result)
    :else result))

(declare enqueue-event! register-built-in-ops! runtime? apply-edges!)

(defn- ds [runtime]
  (:datasource runtime))

(defn- query-registry [runtime]
  (:query-registry runtime))

(defn- view-registry [runtime]
  (:view-registry runtime))

(defn- pattern-registry [runtime]
  (:pattern-registry runtime))

(defn- op-registry [runtime]
  (:op-registry runtime))

(defn- hook-registry [runtime]
  (:hook-registry runtime))

(defn- approved-spool-sync-state [runtime]
  (:approved-spool-sync-state runtime))

(defn- module-use-state [runtime]
  (:module-use-state runtime))

(defn- event-system [runtime]
  (:event-system runtime))

(defn- with-spool-classloader [runtime f]
  (let [thread (Thread/currentThread)
        previous-loader (.getContextClassLoader thread)
        loader (:spool-classloader runtime)]
    (try
      (.setContextClassLoader thread loader)
      (f)
      (finally
        (.setContextClassLoader thread previous-loader)))))

(defn- config-dir [runtime]
  (get-in runtime [:metadata :config-dir]))

(defn- spools-file [runtime name]
  (io/file (config-dir runtime) name))

(defn- expand-user-home [path]
  (cond
    (= "~" path) (System/getProperty "user.home")
    (str/starts-with? path "~/") (str (System/getProperty "user.home") (subs path 1))
    :else path))

(defn- canonical-root [runtime path]
  (let [expanded-path (expand-user-home path)
        file (io/file expanded-path)
        resolved (if (.isAbsolute file)
                   file
                   (io/file (config-dir runtime) expanded-path))]
    (.getCanonicalPath resolved)))

(defn- validate-approved-spool-entry! [source lib entry]
  (when-not (symbol? lib)
    (throw (ex-info "Spool coordinate must be a symbol" (assoc source :lib lib))))
  (when-not (map? entry)
    (throw (ex-info "Spool entry must be a map" (assoc source :lib lib :entry entry))))
  (when-let [unknown (seq (remove #{:local/root} (keys entry)))]
    (throw (ex-info "Spool entry contains unknown keys" (assoc source :lib lib :keys (vec unknown)))))
  (when-not (and (string? (:local/root entry)) (not (str/blank? (:local/root entry))))
    (throw (ex-info "Spool entry requires non-blank string :local/root"
                    (assoc source :lib lib :local/root (:local/root entry))))))

(defn- normalize-approved-spools-file
  "Validate one approved-spool config file and resolve roots for this runtime."
  [runtime name source config]
  (when-not (map? config)
    (throw (ex-info (str name " must contain a map") (assoc source :config config))))
  (when-let [unknown (seq (remove #{:spools} (keys config)))]
    (throw (ex-info (str name " contains unknown top-level keys") (assoc source :keys (vec unknown)))))
  (when-not (map? (:spools config))
    (throw (ex-info (str name " requires :spools map") (assoc source :spools (:spools config)))))
  {:spools (into {}
               (map (fn [[lib entry]]
                      (validate-approved-spool-entry! source lib entry)
                      [lib {:local/root (:local/root entry)
                            :root (canonical-root runtime (:local/root entry))
                            :source source}]))
               (:spools config))})

(defn- approved-spools-file [runtime name kind]
  (let [file (spools-file runtime name)
        source {:kind kind
                :file (.getPath file)}]
    (cond
      (and (not (.exists file))
           (not (java.nio.file.Files/isSymbolicLink (.toPath file))))
      {:spools {}}

      (not (.isFile file))
      (throw (ex-info (str name " is malformed or unreadable") source))

      :else
      (normalize-approved-spools-file
       runtime
       name
       source
       (try
         (query/read-edn-file file)
         (catch Throwable t
           (throw (ex-info (str name " is malformed or unreadable") source t))))))))

(defn- legacy-config-present? [file]
  (or (.exists file)
      (java.nio.file.Files/isSymbolicLink (.toPath file))))

(defn- reject-legacy-spool-config! [runtime]
  (let [legacy-files (filter #(legacy-config-present? (spools-file runtime %)) ["libs.edn" "libs.local.edn"])]
    (when (seq legacy-files)
      (throw (ex-info "Legacy runtime library config files are no longer supported; rename libs.edn/libs.local.edn to spools.edn/spools.local.edn and change top-level :libs to :spools"
                      {:legacy-files (vec legacy-files)
                       :config-dir (config-dir runtime)})))))

(defn approved-spools
  "Read and validate the effective runtime spool allowlist.

  The effective allowlist is `spools.edn` overlaid by `spools.local.edn`; local
  entries replace shared entries with the same coordinate. Missing files
  contribute no spools, while malformed present files fail loudly."
  [runtime]
  (reject-legacy-spool-config! runtime)
  {:spools (merge (:spools (approved-spools-file runtime "spools.edn" :shared))
                  (:spools (approved-spools-file runtime "spools.local.edn" :local)))})

(defn- sync-failed [lib entry reason data]
  [lib (merge {:lib lib
               :local/root (:local/root entry)
               :root (:root entry)
               :source (:source entry)
               :status :failed
               :reason reason}
              data)])

(defn- root-paths [root]
  (let [deps-file (io/file root "deps.edn")]
    (when-not (.isFile deps-file)
      (throw (ex-info "Local root must contain deps.edn" {:root root})))
    (let [deps (query/read-edn-file deps-file)
          paths (or (:paths deps) ["src"])]
      (when-not (and (vector? paths) (every? string? paths))
        (throw (ex-info "Local root deps.edn :paths must be a vector of strings" {:root root :paths paths})))
      (mapv #(.getCanonicalFile (io/file root %)) paths))))

(defn- add-root-paths-to-spool-loader! [runtime root]
  (let [loader (:spool-classloader runtime)]
    (doseq [path (root-paths root)]
      (when-not (.isDirectory path)
        (throw (ex-info "Local root classpath entry must be a directory" {:root root :path (.getPath path)})))
      (.addURL ^clojure.lang.DynamicClassLoader loader (.toURL (.toURI path))))))

(defn- sync-approved-spool! [runtime lib entry]
  (let [root-file (io/file (:root entry))]
    (cond
      (not (.exists root-file))
      (sync-failed lib entry :missing-root {})

      (not (.isDirectory root-file))
      (sync-failed lib entry :unreadable-root {})

      (not (.canRead root-file))
      (sync-failed lib entry :unreadable-root {})

      :else
      (try
        (let [added (with-spool-classloader
                      runtime
                      #(binding [clojure.core/*repl* true]
                         (repl-deps/add-libs {lib {:local/root (:root entry)}})))]
          (add-root-paths-to-spool-loader! runtime (:root entry))
          [lib {:lib lib
                :local/root (:local/root entry)
                :root (:root entry)
                :source (:source entry)
                :status (if (seq added) :loaded :already-available)}])
        (catch Throwable t
          (sync-failed lib entry :runtime-add-failed {:message (ex-message t)
                                                      :class (str (class t))}))))))

(defn sync-approved-spools
  "Load approved local spools into the runtime classloader and record sync status."
  [runtime]
  (reset! (approved-spool-sync-state runtime) {})
  (let [approved (approved-spools runtime)
        results (into (sorted-map)
                      (map (fn [[lib entry]] (sync-approved-spool! runtime lib entry)))
                      (:spools approved))]
    (reset! (approved-spool-sync-state runtime) results)
    {:spools results}))

(defn approved-spool-syncs
  "Return the most recent approved spool sync results."
  [runtime]
  {:spools (into (sorted-map) @(approved-spool-sync-state runtime))})

(defn- clear-reload-state! [runtime]
  (reset! (approved-spool-sync-state runtime) {})
  (reset! (module-use-state runtime) {})
  (reset! (query-registry runtime) {})
  (reset! (view-registry runtime) {})
  (reset! (pattern-registry runtime) {})
  (reset! (op-registry runtime) {})
  (reset! (hook-registry runtime) {})
  (runtime/clear-event-system-for-reload! runtime)
  (register-built-in-ops! runtime))

(defn reload-config!
  "Reload selected config-dir startup files after clearing runtime registries."
  [runtime]
  (try
    (clear-reload-state! runtime)
    (let [world {:config-dir (config-dir runtime)}
          files (runtime/load-startup-files! runtime world)]
      (runtime/resume-event-system! runtime)
      {:status :loaded
       :files files
       :returns (mapv :return files)})
    (catch Throwable t
      (clear-reload-state! runtime)
      (runtime/resume-event-system! runtime)
      (throw t))))

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
  (swap! (module-use-state runtime) assoc key result)
  result)

(defn- skip-use [runtime key opts reason data]
  (record-use! runtime key (merge {:key key :opts opts :status :skipped :reason reason} data)))

(defn- use-spool-skip [runtime opts]
  (let [approved (approved-spools runtime)
        syncs @(approved-spool-sync-state runtime)]
    (some (fn [lib]
            (cond
              (not (contains? (:spools approved) lib))
              [:not-approved {:lib lib}]

              (not (contains? syncs lib))
              [:not-synced {:lib lib}]

              (= :failed (:status (get syncs lib)))
              [:sync-failed {:lib lib :sync (get syncs lib)}]))
          (:spools opts))))

(defn- use-after-skip [runtime opts]
  (let [uses @(module-use-state runtime)]
    (some (fn [after]
            (when-not (= :loaded (:status (get uses after)))
              [:missing-after {:after after :use (get uses after)}]))
          (:after opts))))

(defn- module-file [runtime path]
  (let [base (.getCanonicalFile (io/file (config-dir runtime)))
        file (.getCanonicalFile (io/file base path))
        base-path (.getPath base)
        file-path (.getPath file)]
    (when-not (or (= base-path file-path)
                  (str/starts-with? file-path (str base-path java.io.File/separator)))
      (throw (ex-info "Module use :file must stay within selected config-dir"
                      {:file path
                       :config-dir base-path
                       :resolved file-path})))
    file-path))

(defn- ns-relative-path [ns-sym]
  (str (-> (name ns-sym)
           (str/replace "-" "_")
           (str/replace "." java.io.File/separator))
       ".clj"))

(defn- synced-root-paths [runtime]
  (mapcat (fn [[_ {:keys [root status]}]]
            (when (#{:loaded :already-available} status)
              (root-paths root)))
          @(approved-spool-sync-state runtime)))

(defn- locate-synced-namespace-file [runtime ns-sym]
  (let [relative (ns-relative-path ns-sym)
        roots (vec (synced-root-paths runtime))
        file (some (fn [root]
                     (let [candidate (io/file root relative)]
                       (when (.isFile candidate)
                         (.getCanonicalPath candidate))))
                   roots)]
    {:file file
     :relative-path relative
     :searched-roots (mapv #(.getCanonicalPath %) roots)}))

(defn- load-synced-namespace! [runtime ns-sym]
  (if (find-ns ns-sym)
    {:ns ns-sym}
    (let [{:keys [file relative-path searched-roots]} (locate-synced-namespace-file runtime ns-sym)]
      (if file
        (do
          (load-file file)
          {:ns ns-sym :file file})
        (try
          (require ns-sym)
          {:ns ns-sym}
          (catch java.io.FileNotFoundException _
            (throw (ex-info "Could not locate namespace source in synced spool roots"
                            {:ns ns-sym
                             :relative-path relative-path
                             :searched-roots searched-roots}))))))))

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
    (module-file runtime file))
  (if-let [[reason data] (use-spool-skip runtime opts)]
    (skip-use runtime key opts reason data)
    (if-let [[reason data] (use-after-skip runtime opts)]
      (skip-use runtime key opts reason data)
      (try
        (let [load-result (with-spool-classloader
                            runtime
                            #(if-let [ns-sym (:ns opts)]
                               (load-synced-namespace! runtime ns-sym)
                               (let [file (module-file runtime (:file opts))]
                                 (load-file file)
                                 {:file file})))
              call-result (when-let [call-sym (:call opts)]
                            (with-spool-classloader
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
  "Return module-use registry entries keyed by keyword."
  [runtime]
  (into (sorted-map) @(module-use-state runtime)))

(defn use
  "Return the module-use registry entry for key, or nil when absent."
  [runtime key]
  (get @(module-use-state runtime) key))

(defn- validated-query-entry [[query-name query-def]]
  [(query/canonical-query-name query-name)
   (query/validate-query-def! query-def)])

(defn register-query
  "Register a named query definition in the runtime query registry."
  [runtime query-name query-def]
  (let [entry (validated-query-entry [query-name query-def])]
    (swap! (query-registry runtime) conj entry)
    (into {} [entry])))

(defn load-queries
  "Merge validated named query definitions into the runtime query registry."
  [runtime query-defs]
  (let [validated-query-defs (into {} (map validated-query-entry) query-defs)]
    (swap! (query-registry runtime) merge validated-query-defs)
    validated-query-defs))

(defn- current-runtime []
  (or @runtime/current-runtime
      (throw (ex-info "No weaver runtime is active" {}))))

(defn register-query!
  "Register a named query definition and return its canonical API shape."
  [query-name query-def]
  (register-query (current-runtime) query-name query-def))

(defn load-queries!
  "Load multiple named query definitions and return their canonical API shape."
  [query-defs]
  (load-queries (current-runtime) query-defs))

(defn queries
  "Return registered query definitions keyed by canonical string name."
  [runtime]
  (into (sorted-map) @(query-registry runtime)))

(defn resolve-query
  "Return the registered query definition for a simple symbol or keyword name."
  [runtime query-name]
  (query/query-def @(query-registry runtime) query-name))

(defn- query-where [query-def]
  (if (map? query-def)
    (:where query-def)
    query-def))

(defn- query-metadata-entry [[name query-def]]
  {:name name
   :params (if (map? query-def) (vec (:params query-def)) [])
   :referenced-params (query/referenced-params (query-where query-def))})

(defn query-metadata
  "Return registered query caller metadata ordered by canonical name."
  ([]
   (query-metadata (current-runtime)))
  ([runtime]
   (mapv query-metadata-entry (queries runtime))))

(defn query-explain
  "Describe a registered query definition and how CLI callers invoke it."
  ([query-name]
   (query-explain (current-runtime) query-name))
  ([runtime query-name]
   (let [query-def (resolve-query runtime query-name)
         name (query/query-lookup-name query-name)
         where (query-where query-def)]
     (assoc (query-metadata-entry [name query-def])
            :where where
            :definition query-def
            :where-form (pr-str where)
            :definition-form (pr-str query-def)
            :summary "Invoke this query with `strand list --query <name>` or `strand ready --query <name>` and pass runtime values with repeated `--param key=value` arguments."))))

(defn init
  "Initialize the runtime database schema."
  [runtime]
  (db/init! (ds runtime))
  {:database "initialized"})

(defn- event-base [type]
  {:event/type type
   :event/id (str (UUID/randomUUID))
   :event/at (str (Instant/now))
   :event/source :skein.api.weaver.alpha})

(defn- hooks-for-type [runtime hook-type]
  (filter #(contains? (:types %) hook-type)
          (sort-by (juxt :order (comp pr-str :key)) (vals @(hook-registry runtime)))))

(defn- cause-code [throwable]
  (loop [t throwable]
    (when t
      (let [data (ex-data t)]
        (or (:code data)
            (recur (ex-cause t)))))))

(defn- hook-failure-data [hook-type {:keys [key fn]} throwable]
  (let [data (ex-data throwable)
        code (cause-code throwable)]
    (cond-> {:code "hook/failed"
             :hook/type hook-type
             :hook/key key
             :hook/fn fn
             :exception/class (str (class throwable))
             :exception/message (ex-message throwable)}
      data (assoc :exception/data data)
      code (assoc :hook/cause-code code))))

(defn- hook-context [hook-type hook ctx]
  (assoc ctx
         :hook/type hook-type
         :hook/key (:key hook)
         :hook/fn (:fn hook)))

(defn- invoke-hook! [runtime hook-type hook ctx]
  (try
    (with-spool-classloader runtime #((:fn-value hook) ctx))
    (catch Throwable t
      (throw (ex-info "Lifecycle hook failed"
                      (hook-failure-data hook-type hook t)
                      t)))))

(defn- run-validation-hooks! [runtime hook-type ctx]
  (doseq [hook (hooks-for-type runtime hook-type)]
    (invoke-hook! runtime hook-type hook (hook-context hook-type hook ctx)))
  nil)

(defn run-payload-received-hooks!
  "Run validation-only hooks for a decoded JSON socket request payload."
  [runtime ctx]
  (run-validation-hooks! runtime :payload/received ctx))

(defn- require-transform-wrapper! [hook-type hook result]
  (when-not (and (map? result) (contains? result :hook/value))
    (throw (ex-info "Transform hook must return {:hook/value replacement}"
                    {:code "hook/invalid-return"
                     :hook/type hook-type
                     :hook/key (:key hook)
                     :hook/fn (:fn hook)
                     :hook/return result})))
  result)

(defn- require-json-attributes! [attrs]
  (db/->json attrs)
  attrs)

(defn- invoke-transform-hook! [runtime hook-type hook ctx]
  (try
    (require-json-attributes!
     (:hook/value
      (require-transform-wrapper!
       hook-type
       hook
       (with-spool-classloader runtime #((:fn-value hook) ctx)))))
    (catch Throwable t
      (throw (ex-info "Lifecycle hook failed"
                      (hook-failure-data hook-type hook t)
                      t)))))

(defn- run-transform-hooks [runtime hook-type ctx]
  (reduce (fn [value hook]
            (invoke-transform-hook! runtime hook-type hook (assoc (hook-context hook-type hook ctx) :hook/value value)))
          (require-json-attributes! (:hook/value ctx))
          (hooks-for-type runtime hook-type)))

(defn- request-context [operation]
  {:request/source :weaver-api
   :request/operation operation})

(defn add
  "Create a strand, enqueue a creation event, and return the normalized strand."
  ([runtime strand]
   (add runtime strand (request-context :add)))
  ([runtime strand req-ctx]
   (let [created (jdbc/with-transaction [tx (ds runtime)]
                   (let [edges (:edges strand)
                         strand (cond-> strand
                                  true (dissoc :edges)
                                  (some? (:attributes strand))
                                  (assoc :attributes (run-transform-hooks runtime
                                                                           :attributes/normalize
                                                                           (merge req-ctx
                                                                                  {:hook/value (:attributes strand)
                                                                                   :mutation/operation :strand/add
                                                                                   :strand/patch strand}))))
                         created (normalize (db/add-strand! tx strand))]
                     (apply-edges! tx (:id created) edges)
                     (run-validation-hooks! runtime
                                            :strand/add-before-commit
                                            (merge req-ctx
                                                   {:mutation/operation :strand/add
                                                    :strand/before nil
                                                    :strand/after created
                                                    :strand/edge-ops (vec edges)}))
                     created))]
     (enqueue-event! runtime (assoc (event-base :strand/added)
                                    :strand/id (:id created)
                                    :strand created))
     created)))

(defn- strand-patch-for-ref [payload ref]
  (some (fn [strand]
          (when (= ref (:ref strand))
            (dissoc strand :ref)))
        (:strands payload)))

(defn- enqueue-batch-fanout! [runtime batch-id payload result]
  (doseq [created (:created result)]
    (enqueue-event! runtime (assoc (event-base :strand/added)
                                   :batch/id batch-id
                                   :strand/id (:id created)
                                   :strand created)))
  (doseq [{:keys [ref id before after]} (:updated result)]
    (enqueue-event! runtime (assoc (event-base :strand/updated)
                                   :batch/id batch-id
                                   :strand/id id
                                   :strand/patch (strand-patch-for-ref payload ref)
                                   :strand/before before
                                   :strand/after after)))
  (when (seq (:burned result))
    (enqueue-event! runtime (assoc (event-base :strand/burned)
                                   :batch/id batch-id
                                   :strand/requested-ids (mapv :id (:burned result))
                                   :strand/burned-ids (mapv :id (:burned result))
                                   :strand/before (mapv :before (:burned result))))))

(defn- normalize-batch-strand-attributes [runtime req-ctx payload]
  (clojure.core/update payload :strands
                       (fn [strands]
                         (mapv (fn [{:keys [ref attributes] :as strand}]
                                 (if (nil? attributes)
                                   strand
                                   (assoc strand :attributes
                                          (run-transform-hooks runtime
                                                               :attributes/normalize
                                                               (merge req-ctx
                                                                      {:hook/value attributes
                                                                       :mutation/operation :batch/apply
                                                                       :batch/ref ref
                                                                       :strand/patch strand})))))
                               strands))))

(defn- batch-apply-context [req-ctx payload result]
  (merge req-ctx
         {:mutation/operation :batch/apply
          :batch/source :apply
          :batch/payload payload
          :batch/refs (:refs result)
          :batch/created (:created result)
          :batch/updated (:updated result)
          :batch/burned (:burned result)
          :batch/edge-ops (:edges result)}))

(defn apply-batch
  "Apply a graph batch atomically and enqueue batch plus strand fanout events."
  ([runtime payload]
   (apply-batch runtime payload (request-context :apply-batch)))
  ([runtime payload req-ctx]
  (let [submitted-payload payload
        normalized-payload (normalize-batch-strand-attributes runtime req-ctx (db/normalize-batch-payload! payload))
        result (jdbc/with-transaction [tx (ds runtime)]
                 (let [result (normalize (db/apply-batch-in-transaction! tx normalized-payload))]
                   (run-validation-hooks! runtime
                                          :batch/apply-before-commit
                                          (batch-apply-context req-ctx submitted-payload result))
                   result))
        batch-id (str (UUID/randomUUID))]
    (enqueue-event! runtime (assoc (event-base :batch/applied)
                                   :batch/id batch-id
                                   :batch/refs (:refs result)
                                   :batch/created (:created result)
                                   :batch/updated (:updated result)
                                   :batch/burned (:burned result)
                                   :batch/edges (:edges result)))
    (enqueue-batch-fanout! runtime batch-id normalized-payload result)
    result)))

(defn- apply-edges! [tx id edges]
  (doseq [{:keys [to type attributes]} edges]
    (when-not (db/get-strand tx to)
      (throw (ex-info "Edge target strand not found" {:to to :type type})))
    (db/add-edge! tx {:from id :to to :type type :attributes (or attributes {})})))

(def ^:private update-patch-keys #{:title :state :attributes :edges})

(defn- reject-unknown-update-keys! [patch]
  (let [unknown (seq (remove update-patch-keys (keys patch)))]
    (when unknown
      (throw (ex-info "Unknown strand update fields" {:fields (vec unknown)})))))

(defn update
  "Update a strand and/or add edges atomically, then enqueue an update event."
  ([runtime id patch]
   (update runtime id patch (request-context :update)))
  ([runtime id patch req-ctx]
  (reject-unknown-update-keys! patch)
  (let [{:keys [title state edges]} patch
        result (jdbc/with-transaction [tx (ds runtime)]
                 (let [before (or (some-> (db/get-strand tx id) normalize)
                                  (throw (ex-info "Strand not found" {:strand-id id})))
                       patch (if (some? (:attributes patch))
                               (assoc patch :attributes (run-transform-hooks runtime
                                                                              :attributes/normalize
                                                                              (merge req-ctx
                                                                                     {:hook/value (:attributes patch)
                                                                                      :mutation/operation :strand/update
                                                                                      :strand/id id
                                                                                      :strand/before before
                                                                                      :strand/patch patch})))
                               patch)
                       attributes (:attributes patch)]
                   (apply-edges! tx id edges)
                   (let [after (normalize (db/update-strand! tx id (cond-> {}
                                                                     (contains? patch :title) (assoc :title title)
                                                                     (contains? patch :state) (assoc :state state)
                                                                     (contains? patch :attributes) (assoc :attributes attributes))))]
                     (run-validation-hooks! runtime
                                            :strand/update-before-commit
                                            (merge req-ctx
                                                   {:mutation/operation :strand/update
                                                    :strand/id id
                                                    :strand/patch patch
                                                    :strand/before before
                                                    :strand/after after
                                                    :strand/edge-ops (vec edges)}))
                     {:before before :after after :patch patch})))]
    (enqueue-event! runtime (assoc (event-base :strand/updated)
                                   :strand/id id
                                   :strand/patch (:patch result)
                                   :strand/before (:before result)
                                   :strand/after (:after result)))
    (:after result))))

(defn- supersede-context [old-id replacement-id result]
  {:strand/id old-id
   :strand/old-id old-id
   :strand/replacement-id replacement-id
   :strand/before (get-in result [:old :before])
   :strand/after (get-in result [:old :after])
   :supersession/supersedes-edge (:supersedes-edge result)
   :supersession/rewired-dependencies (:rewired-dependencies result)})

(defn supersede
  "Replace one strand with another and enqueue a supersession event."
  ([runtime old-id replacement-id]
   (supersede runtime old-id replacement-id (request-context :supersede)))
  ([runtime old-id replacement-id req-ctx]
  (let [result (jdbc/with-transaction [tx (ds runtime)]
                 (let [result (normalize (db/supersede-strand-in-transaction! tx old-id replacement-id))]
                   (run-validation-hooks! runtime
                                          :strand/supersede-before-commit
                                          (merge req-ctx
                                                 {:mutation/operation :strand/supersede}
                                                 (supersede-context old-id replacement-id result)))
                   result))]
    (enqueue-event! runtime (merge (event-base :strand/superseded)
                                   (supersede-context old-id replacement-id result)))
    result)))

(defn declare-acyclic-relation!
  "Declare an edge relation as acyclic for future graph writes."
  [runtime relation]
  (db/declare-acyclic-relation! (ds runtime) relation))

(defn acyclic-relations
  "Return declared acyclic edge relation names."
  [runtime]
  (db/list-acyclic-relations (ds runtime)))

(defn show
  "Return one normalized strand by id, or nil when absent."
  [runtime id]
  (normalize (db/get-strand (ds runtime) id)))

(defn burn-by-ids
  "Delete strands by id and enqueue burn events for removed rows."
  ([runtime ids]
   (burn-by-ids runtime ids (request-context :burn)))
  ([runtime ids req-ctx]
  (let [requested-ids (vec ids)
        {:keys [before result]} (jdbc/with-transaction [tx (ds runtime)]
                                  (let [before (normalize (db/strands-by-ids tx requested-ids))]
                                    (run-validation-hooks! runtime
                                                           :strand/burn-before-commit
                                                           (merge req-ctx
                                                                  {:mutation/operation :strand/burn
                                                                   :strand/requested-ids requested-ids
                                                                   :strand/before before}))
                                    {:before before
                                     :result (db/burn-by-ids! tx requested-ids)}))]
    (enqueue-event! runtime (assoc (event-base :strand/burned)
                                   :strand/requested-ids requested-ids
                                   :strand/burned-ids (:burned result)
                                   :strand/before before))
    result)))

(defn burn-by-id
  "Delete one strand by id and return burn metadata."
  ([runtime id]
   (burn-by-ids runtime [id]))
  ([runtime id req-ctx]
   (burn-by-ids runtime [id] req-ctx)))

(defn list
  "Return strands visible to `runtime`, optionally filtered by a query definition."
  ([runtime]
   (normalize (db/all-strands (ds runtime))))
  ([runtime query-def params]
   (normalize (db/all-strands (ds runtime) query-def params))))

(defn list-query
  "Return strands matching a registered query definition."
  [runtime query-name params]
  (list runtime (resolve-query runtime query-name) params))

(defn ready
  "Return ready strands for `runtime`, optionally filtered by a query definition."
  ([runtime]
   (normalize (db/ready-strands (ds runtime))))
  ([runtime query-def params]
   (normalize (db/ready-strands (ds runtime) query-def params))))

(defn ready-query
  "Return ready strands from the result set of a registered query definition."
  [runtime query-name params]
  (ready runtime (resolve-query runtime query-name) params))

(defn query-ids
  "Return strand ids matching a query expression or registered query definition."
  [runtime query-or-name params]
  (let [query-def (if (or (vector? query-or-name) (map? query-or-name))
                    query-or-name
                    (resolve-query runtime query-or-name))]
    (db/query-strand-ids (ds runtime) query-def params)))

(defn strands-by-ids
  "Return normalized strands for ids, preserving first-seen input order."
  [runtime ids]
  (normalize (db/strands-by-ids (ds runtime) ids)))

(defn ancestor-root-ids
  "Return ancestor root ids reachable from `seed-ids`."
  ([runtime seed-ids]
   (ancestor-root-ids runtime seed-ids {}))
  ([runtime seed-ids opts]
   (db/ancestor-root-ids (ds runtime) seed-ids opts)))

(defn subgraph
  "Return a normalized strand subgraph rooted at `root-ids`."
  ([runtime root-ids]
   (subgraph runtime root-ids {}))
  ([runtime root-ids opts]
   (let [{:keys [strands edges] :as result} (db/subgraph (ds runtime) root-ids opts)]
     (assoc result
            :strands (normalize strands)
            :edges (normalize edges)))))

(defn- canonical-view-name [view-name]
  (query/canonical-query-name view-name))

(defn- validate-fn-symbol! [label fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info (str label " function must be a fully qualified symbol") {:fn fn-sym})))
  fn-sym)

(defn- validate-view-fn-symbol! [fn-sym]
  (validate-fn-symbol! "View" fn-sym))

(defn- validate-pattern-fn-symbol! [fn-sym]
  (validate-fn-symbol! "Pattern" fn-sym))

(defn- validate-op-fn-symbol! [fn-sym]
  (validate-fn-symbol! "Operation" fn-sym))

(defn register-view!
  "Register a named view function for trusted in-process rendering."
  [runtime view-name fn-sym]
  (let [name (canonical-view-name view-name)
        entry {:name name :fn (validate-view-fn-symbol! fn-sym)}]
    (swap! (view-registry runtime) assoc name entry)
    entry))

(defn views
  "Return registered view metadata ordered by name."
  [runtime]
  (mapv val (sort-by key @(view-registry runtime))))

(defn- resolve-view [runtime view-name]
  (let [canonical-name (canonical-view-name view-name)]
    (or (get @(view-registry runtime) canonical-name)
        (throw (ex-info "View not found" {:view view-name
                                           :canonical-view canonical-name
                                           :available (sort (keys @(view-registry runtime)))})))))

(defn view!
  "Invoke a registered view function with params."
  [runtime view-name params]
  (let [{fn-sym :fn} (resolve-view runtime view-name)]
    (with-spool-classloader
      runtime
      #((requiring-resolve fn-sym) {:params params}))))

(defn- canonical-op-name [op-name]
  (query/canonical-query-name op-name))

(defn- validate-op-doc! [doc]
  (when-not (and (string? doc) (not (str/blank? doc)))
    (throw (ex-info "Operation doc must be a non-blank string" {:doc doc})))
  doc)

(defn register-op!
  "Register a trusted weaver-side CLI operation.

  Registered operations are invoked by `strand op <name> [args...]`. The handler
  symbol must resolve to a function that accepts one context map with `:op/name`
  and `:op/argv`, and returns JSON-compatible data. Registry contents live only
  for the current weaver lifetime and are normally installed from init.clj or a
  live REPL or explicit connected client. Duplicate names replace prior entries for reload workflows."
  ([op-name fn-sym]
   (register-op! (current-runtime) op-name nil fn-sym))
  ([a b c]
   (if (runtime? a)
     (register-op! a b nil c)
     (register-op! (current-runtime) a b c)))
  ([runtime op-name doc fn-sym]
   (let [entry (cond-> {:name (canonical-op-name op-name)
                        :fn (validate-op-fn-symbol! fn-sym)}
                 doc (assoc :doc (validate-op-doc! doc)))]
     (swap! (op-registry runtime) assoc (:name entry) entry)
     entry)))

(defn ops
  "Return registered CLI operation entries for the current weaver runtime."
  [runtime]
  (mapv val (sort-by key @(op-registry runtime))))

(defn resolve-op
  "Return the registered CLI operation entry for `op-name`, or fail loudly."
  [runtime op-name]
  (let [canonical-name (canonical-op-name op-name)]
    (or (get @(op-registry runtime) canonical-name)
        (throw (ex-info "Operation not found" {:operation op-name
                                                :canonical-operation canonical-name
                                                :available (sort (keys @(op-registry runtime)))})))))

(defn op!
  "Invoke a registered CLI operation with raw string argv from `strand op`."
  [runtime op-name argv]
  (let [{fn-sym :fn name :name} (resolve-op runtime op-name)]
    (with-spool-classloader
      runtime
      #((requiring-resolve fn-sym) {:op/name name
                                    :op/argv (vec argv)
                                    :op/runtime-metadata (:metadata runtime)}))))

(defn op-help-handler
  "Return help for `strand op` and currently registered operations."
  [_ctx]
  {:summary "strand op invokes trusted weaver-side operations by name."
   :usage "strand op <name> [args...]"
   :details ["The Go CLI stops parsing after the operation name and forwards the remaining arguments as strings."
             "Register handlers from trusted init.clj, activated spools, or the live REPL with skein.api.weaver.alpha/register-op!."
             "Handlers receive {:op/name <canonical-name> :op/argv [<strings>...]} and return JSON-compatible data."]
   :registered (ops (current-runtime))})

(defn register-built-in-ops!
  "Install Skein-provided CLI operations into the runtime op registry."
  [runtime]
  (register-op! runtime 'help "Explain how strand op invokes custom weaver operations" 'skein.api.weaver.alpha/op-help-handler))

(defn- canonical-pattern-name [pattern-name]
  (query/canonical-query-name pattern-name))

(defn- validate-pattern-spec! [spec-name]
  (when-not (or (keyword? spec-name) (symbol? spec-name))
    (throw (ex-info "Pattern input spec must be a keyword or symbol" {:spec spec-name})))
  spec-name)

(defn- validate-pattern-doc! [doc]
  (when-not (and (string? doc) (not (str/blank? doc)))
    (throw (ex-info "Pattern doc must be a non-blank string" {:doc doc})))
  doc)

(defn- runtime? [x]
  (and (map? x) (contains? x :pattern-registry)))

(defn- pattern-entry [pattern-name doc fn-sym input-spec]
  (cond-> {:name (canonical-pattern-name pattern-name)
           :fn (validate-pattern-fn-symbol! fn-sym)
           :input-spec (validate-pattern-spec! input-spec)}
    doc (assoc :doc (validate-pattern-doc! doc))))

(defn register-pattern!
  "Register a trusted weaver pattern handler and input spec."
  ([pattern-name fn-sym input-spec]
   (register-pattern! (current-runtime) pattern-name fn-sym input-spec))
  ([a b c d]
   (if (runtime? a)
     (let [entry (pattern-entry b nil c d)]
       (swap! (pattern-registry a) assoc (:name entry) entry)
       entry)
     (register-pattern! (current-runtime) a b c d)))
  ([runtime pattern-name doc fn-sym input-spec]
   (let [entry (pattern-entry pattern-name doc fn-sym input-spec)]
     (swap! (pattern-registry runtime) assoc (:name entry) entry)
     entry)))

(defn patterns
  "Return registered weave pattern metadata ordered by name."
  [runtime]
  (mapv val (sort-by key @(pattern-registry runtime))))

(defn resolve-pattern
  "Return the registered weave pattern for a simple symbol or keyword name."
  [runtime pattern-name]
  (let [canonical-name (canonical-pattern-name pattern-name)]
    (or (get @(pattern-registry runtime) canonical-name)
        (throw (ex-info "Pattern not found" {:pattern pattern-name
                                              :canonical-pattern canonical-name
                                              :available (sort (keys @(pattern-registry runtime)))})))))

(defn- spec-form [spec-name]
  (let [form (s/form spec-name)]
    (when (= ::s/unknown form)
      (throw (ex-info "Pattern input spec is not registered" {:input-spec spec-name})))
    form))

(defn- spec-summary [spec-name]
  {:spec (str spec-name)
   :spec-form (pr-str (spec-form spec-name))})

(defn- key-spec-summary [key-spec]
  (merge {:key (name key-spec)}
         (try
           (spec-summary key-spec)
           (catch clojure.lang.ExceptionInfo _
             {:spec (str key-spec)
              :spec-form "<unregistered>"}))))

(defn- keys-spec-summary [form]
  (when (and (seq? form) (= 'clojure.spec.alpha/keys (first form)))
    (let [opts (apply hash-map (rest form))]
      {:required (mapv key-spec-summary (concat (:req opts) (:req-un opts)))
       :optional (mapv key-spec-summary (concat (:opt opts) (:opt-un opts)))})))

(defn- pattern-input-contract [input-spec]
  (let [form (spec-form input-spec)
        keys-summary (keys-spec-summary form)]
    (cond-> (spec-summary input-spec)
      true (assoc :summary "Input must satisfy this clojure.spec contract. For key specs, see required/optional entries for each key's own predicate.")
      keys-summary (merge keys-summary))))

(defn pattern-explain
  "Describe a registered weave pattern and its input spec."
  [runtime pattern-name]
  (let [{:keys [name doc fn input-spec]} (resolve-pattern runtime pattern-name)
        contract (pattern-input-contract input-spec)]
    (cond-> (merge {:name name
                    :fn (str fn)
                    :input-spec (str input-spec)
                    :spec-form (:spec-form contract)}
                   (select-keys contract [:summary :required :optional]))
      doc (assoc :doc doc))))

(defn- missing-key [problem]
  (let [pred (pr-str (:pred problem))]
    (when (str/includes? pred "contains?")
      (or (last (:path problem))
          (some->> (re-find #"contains\? % (:?[A-Za-z0-9._/-]+)" pred) second keyword)))))

(defn- problem-message [contract problem]
  (if-let [key-spec (missing-key problem)]
    (let [key-contract (some #(when (= (name key-spec) (:key %)) %)
                             (:required contract))]
      (str "missing required key `" (name key-spec) "`"
           (when key-contract
             (str " (expected " (:spec key-contract) " " (:spec-form key-contract) ")"))))
    (str "value at " (pr-str (:in problem)) " failed predicate " (pr-str (:pred problem)))))

(defn- pattern-validation-message [pattern-name contract explain]
  (let [problems (::s/problems explain)]
    (str "Pattern input failed spec validation for `" (canonical-pattern-name pattern-name) "`"
         (when (seq problems)
           (str ": " (str/join "; " (map #(problem-message contract %) problems)))))))

(defn- require-pattern-batch-vector! [batch]
  (when-not (vector? batch)
    (throw (ex-info "Pattern must return a batch strand vector" {:value batch})))
  batch)

(defn- normalize-weave-strand-attributes [runtime req-ctx pattern-name input batch]
  (mapv (fn [{:keys [ref attributes] :as strand}]
          (if (nil? attributes)
            strand
            (assoc strand :attributes
                   (run-transform-hooks runtime
                                        :attributes/normalize
                                        (merge req-ctx
                                               {:hook/value attributes
                                                :mutation/operation :batch/apply
                                                :batch/ref ref
                                                :strand/patch strand
                                                :pattern/name pattern-name
                                                :pattern/input input})))))
        (require-pattern-batch-vector! batch)))

(defn- weave-payload [strands]
  {:refs {}
   :strands (mapv #(dissoc % :edges) strands)
   :edges (into []
                (mapcat (fn [{:keys [ref edges]}]
                          (map (fn [edge]
                                 (merge {:op :upsert
                                         :from (some-> ref str)
                                         :to (cond-> (:to edge)
                                               (symbol? (:to edge)) str)}
                                        (select-keys edge [:type :attributes])))
                               edges)))
                strands)
   :burn []})

(defn- weave-batch-context [req-ctx pattern-name input payload result]
  (merge req-ctx
         {:mutation/operation :batch/apply
          :batch/source :weave
          :batch/payload payload
          :batch/refs (:refs result)
          :batch/created (:created result)
          :batch/updated []
          :batch/burned []
          :batch/edge-ops (:edges result)
          :pattern/name pattern-name
          :pattern/input input}))

(defn weave!
  "Validate pattern input, invoke the pattern, and apply its create-only batch."
  ([runtime pattern-name input]
   (weave! runtime pattern-name input (request-context :weave)))
  ([runtime pattern-name input req-ctx]
  (let [{fn-sym :fn input-spec :input-spec} (resolve-pattern runtime pattern-name)
        canonical-name (canonical-pattern-name pattern-name)]
    (spec-form input-spec)
    (when-not (s/valid? input-spec input)
      (let [explain (s/explain-data input-spec input)
            contract (pattern-input-contract input-spec)]
        (throw (ex-info (pattern-validation-message pattern-name contract explain)
                        {:code "pattern/input-invalid"
                         :pattern canonical-name
                         :input-spec (str input-spec)
                         :contract contract
                         :problems (mapv #(problem-message contract %) (::s/problems explain))
                         :explain explain}))))
    (let [batch (with-spool-classloader
                  runtime
                  #((requiring-resolve fn-sym) {:input input}))
          normalized-batch (normalize-weave-strand-attributes runtime req-ctx canonical-name input batch)
          normalized-payload (weave-payload normalized-batch)
          result (jdbc/with-transaction [tx (ds runtime)]
                   (let [result (normalize (db/add-strand-batch-in-transaction! tx normalized-batch))]
                     (run-validation-hooks! runtime
                                            :batch/apply-before-commit
                                            (weave-batch-context req-ctx canonical-name input normalized-payload result))
                     result))]
      ;; a weave is a create-only batch apply; without this event, event-driven
      ;; spools (shuttle, treadle) never see pattern-created strands until an
      ;; unrelated mutation happens to trigger their next scan
      (enqueue-event! runtime (assoc (event-base :batch/applied)
                                     :batch/id (str (UUID/randomUUID))
                                     :pattern/name canonical-name
                                     :batch/refs (:refs result)
                                     :batch/created (:created result)))
      (select-keys result [:created :refs])))))

(declare data-first-value?)

(defn- validate-hook-key! [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Hook key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Hook key string must be non-blank" {:key key})))
  key)

(defn- validate-hook-types! [types]
  (when-not (set? types)
    (throw (ex-info "Hook types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Hook types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Hook types must be keywords" {:type type :types types}))))
  types)

(defn- resolve-hook-fn! [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Hook function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (with-spool-classloader runtime #(requiring-resolve fn-sym))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Hook symbol must resolve to a callable value" {:fn fn-sym :resolved-class (str (class value))})))
    resolved))

(defn- validate-hook-opts! [opts]
  (let [opts (or opts {})]
    (when-not (map? opts)
      (throw (ex-info "Hook opts must be a map" {:opts opts})))
    (when-not (data-first-value? opts)
      (throw (ex-info "Hook opts must contain only data-first values" {:opts opts})))
    (when (and (contains? opts :order) (not (integer? (:order opts))))
      (throw (ex-info "Hook :order must be an integer" {:order (:order opts)})))
    opts))

(defn register-hook!
  "Register a trusted lifecycle hook for selected hook types."
  ([key types fn-sym]
   (register-hook! (current-runtime) key types fn-sym {}))
  ([a b c d]
   (if (runtime? a)
     (register-hook! a b c d {})
     (register-hook! (current-runtime) a b c d)))
  ([runtime key types fn-sym opts]
   (let [opts (validate-hook-opts! opts)
         entry {:key (validate-hook-key! key)
                :types (validate-hook-types! types)
                :fn fn-sym
                :fn-value (resolve-hook-fn! runtime fn-sym)
                :order (get opts :order 0)
                :metadata (dissoc opts :order)}]
     (swap! (hook-registry runtime) assoc (:key entry) entry)
     (dissoc entry :fn-value))))

(defn unregister-hook!
  "Remove a registered lifecycle hook by key and return that key."
  [runtime key]
  (let [key (validate-hook-key! key)]
    (swap! (hook-registry runtime) dissoc key)
    key))

(defn hooks
  "Return lifecycle hook registry entries in deterministic execution order."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (juxt :order (comp pr-str :key)) (vals @(hook-registry runtime)))))

(defn- validate-event-handler-key! [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Event handler key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Event handler key string must be non-blank" {:key key})))
  key)

(defn- validate-event-types! [types]
  (when-not (set? types)
    (throw (ex-info "Event handler types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Event handler types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Event handler types must be keywords" {:type type :types types}))))
  types)

(defn- resolve-event-handler-fn! [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Event handler function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (try
                   (with-spool-classloader runtime #(requiring-resolve fn-sym))
                   (catch Throwable t
                     (throw (ex-info "Event handler function could not be resolved" {:fn fn-sym} t))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Event handler symbol must resolve to a callable value" {:fn fn-sym :resolved-class (str (class value))})))
    value))

(defn- data-first-value? [value]
  (cond
    (nil? value) true
    (or (string? value)
        (number? value)
        (keyword? value)
        (symbol? value)
        (boolean? value)
        (inst? value)
        (uuid? value)) true
    (map? value) (and (every? data-first-value? (keys value))
                      (every? data-first-value? (vals value)))
    (vector? value) (every? data-first-value? value)
    (set? value) (every? data-first-value? value)
    :else false))

(defn- validate-event-handler-metadata! [metadata]
  (let [metadata (or metadata {})]
    (when-not (map? metadata)
      (throw (ex-info "Event handler metadata must be a map" {:metadata metadata})))
    (when-not (data-first-value? metadata)
      (throw (ex-info "Event handler metadata must contain only data-first values" {:metadata metadata})))
    metadata))

(defn register-event-handler!
  "Register a trusted event handler for selected event types."
  [runtime key types fn-sym metadata]
  (let [entry {:key (validate-event-handler-key! key)
               :types (validate-event-types! types)
               :fn fn-sym
               :fn-value (resolve-event-handler-fn! runtime fn-sym)
               :metadata (validate-event-handler-metadata! metadata)}]
    (swap! (:handler-registry (event-system runtime)) assoc (:key entry) entry)
    (dissoc entry :fn-value)))

(defn unregister-event-handler!
  "Remove a registered event handler by key."
  [runtime key]
  (let [key (validate-event-handler-key! key)]
    (swap! (:handler-registry (event-system runtime)) dissoc key)
    {:unregistered key}))

(defn event-handlers
  "Return registered event handler metadata."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (comp pr-str :key) (vals @(:handler-registry (event-system runtime))))))

(defn recent-event-failures
  "Return recent asynchronous event handler failures."
  [runtime]
  @(:recent-failures (event-system runtime)))

(defn enqueue-event!
  "Submit an event to the runtime event system."
  [runtime event]
  (when-not (map? event)
    (throw (ex-info "Event must be a map" {:event event})))
  (doseq [k [:event/type :event/id :event/at :event/source]]
    (when-not (contains? event k)
      (throw (ex-info "Event requires key" {:key k :event event}))))
  (when-not (keyword? (:event/type event))
    (throw (ex-info "Event :event/type must be a keyword" {:event/type (:event/type event)})))
  (when-not (.offer (:queue (event-system runtime)) event)
    (throw (ex-info "Event queue is full" {:event/type (:event/type event) :event/id (:event/id event)})))
  {:enqueued true :event/id (:event/id event) :event/type (:event/type event)})
