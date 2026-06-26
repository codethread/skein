(ns skein.weaver.api
  (:refer-clojure :exclude [list update use])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.repl.deps :as repl-deps]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [skein.weaver.runtime :as runtime]
            [skein.db :as db]
            [skein.query :as query]))

(defn normalize-row [row]
  (cond-> row
    (string? (:attributes row)) (clojure.core/update :attributes db/<-json)))

(defn normalize [result]
  (cond
    (map? result) (normalize-row result)
    (sequential? result) (mapv normalize-row result)
    :else result))

(defn- ds [runtime]
  (:datasource runtime))

(defn- query-registry [runtime]
  (:query-registry runtime))

(defn- view-registry [runtime]
  (:view-registry runtime))

(defn- approved-lib-sync-state [runtime]
  (:approved-lib-sync-state runtime))

(defn- module-use-state [runtime]
  (:module-use-state runtime))

(defn- with-library-classloader [runtime f]
  (let [thread (Thread/currentThread)
        previous-loader (.getContextClassLoader thread)
        loader (:library-classloader runtime)]
    (try
      (.setContextClassLoader thread loader)
      (f)
      (finally
        (.setContextClassLoader thread previous-loader)))))

(defn- config-dir [runtime]
  (get-in runtime [:metadata :config-dir]))

(defn- libs-file [runtime]
  (io/file (config-dir runtime) "libs.edn"))

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

(defn- validate-approved-lib-entry! [lib entry]
  (when-not (symbol? lib)
    (throw (ex-info "Library coordinate must be a symbol" {:lib lib})))
  (when-not (map? entry)
    (throw (ex-info "Library entry must be a map" {:lib lib :entry entry})))
  (when-let [unknown (seq (remove #{:local/root} (keys entry)))]
    (throw (ex-info "Library entry contains unknown keys" {:lib lib :keys (vec unknown)})))
  (when-not (and (string? (:local/root entry)) (not (str/blank? (:local/root entry))))
    (throw (ex-info "Library entry requires non-blank string :local/root" {:lib lib :local/root (:local/root entry)}))))

(defn normalize-approved-libs [runtime config]
  (when-not (map? config)
    (throw (ex-info "libs.edn must contain a map" {:config config})))
  (when-let [unknown (seq (remove #{:libs} (keys config)))]
    (throw (ex-info "libs.edn contains unknown top-level keys" {:keys (vec unknown)})))
  (when-not (map? (:libs config))
    (throw (ex-info "libs.edn requires :libs map" {:libs (:libs config)})))
  {:libs (into {}
               (map (fn [[lib entry]]
                      (validate-approved-lib-entry! lib entry)
                      [lib {:local/root (:local/root entry)
                            :root (canonical-root runtime (:local/root entry))}]))
               (:libs config))})

(defn approved-libs [runtime]
  (let [file (libs-file runtime)]
    (cond
      (not (.exists file))
      {:libs {}}

      (not (.isFile file))
      (throw (ex-info "libs.edn is malformed or unreadable" {:file (.getPath file)}))

      :else
      (normalize-approved-libs
       runtime
       (try
         (edn/read-string (slurp file))
         (catch Throwable t
           (throw (ex-info "libs.edn is malformed or unreadable" {:file (.getPath file)} t))))))))

(defn- sync-failed [lib entry reason data]
  [lib (merge {:lib lib
               :local/root (:local/root entry)
               :root (:root entry)
               :status :failed
               :reason reason}
              data)])

(defn- root-paths [root]
  (let [deps-file (io/file root "deps.edn")]
    (when-not (.isFile deps-file)
      (throw (ex-info "Local root must contain deps.edn" {:root root})))
    (let [deps (edn/read-string (slurp deps-file))
          paths (or (:paths deps) ["src"])]
      (when-not (and (vector? paths) (every? string? paths))
        (throw (ex-info "Local root deps.edn :paths must be a vector of strings" {:root root :paths paths})))
      (mapv #(.getCanonicalFile (io/file root %)) paths))))

(defn- add-root-paths-to-library-loader! [runtime root]
  (let [loader (:library-classloader runtime)]
    (doseq [path (root-paths root)]
      (when-not (.isDirectory path)
        (throw (ex-info "Local root classpath entry must be a directory" {:root root :path (.getPath path)})))
      (.addURL ^clojure.lang.DynamicClassLoader loader (.toURL (.toURI path))))))

(defn- sync-approved-lib! [runtime lib entry]
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
        (let [added (with-library-classloader
                      runtime
                      #(binding [clojure.core/*repl* true]
                         (repl-deps/add-libs {lib {:local/root (:root entry)}})))]
          (add-root-paths-to-library-loader! runtime (:root entry))
          [lib {:lib lib
                :local/root (:local/root entry)
                :root (:root entry)
                :status (if (seq added) :loaded :already-available)}])
        (catch Throwable t
          (sync-failed lib entry :runtime-add-failed {:message (ex-message t)
                                                      :class (str (class t))}))))))

(defn sync-approved-libs [runtime]
  (reset! (approved-lib-sync-state runtime) {})
  (let [approved (approved-libs runtime)
        results (into (sorted-map)
                      (map (fn [[lib entry]] (sync-approved-lib! runtime lib entry)))
                      (:libs approved))]
    (reset! (approved-lib-sync-state runtime) results)
    {:libs results}))

(defn approved-lib-syncs [runtime]
  {:libs (into (sorted-map) @(approved-lib-sync-state runtime))})

(def allowed-use-keys #{:ns :file :libs :after :call :required?})

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
  (when (and (contains? opts :libs)
             (not (or (vector? (:libs opts)) (set? (:libs opts)))))
    (throw (ex-info "Module use :libs must be a vector or set of symbols" {:key key :libs (:libs opts)})))
  (doseq [lib (:libs opts)]
    (when-not (symbol? lib)
      (throw (ex-info "Module use :libs entries must be symbols" {:key key :lib lib}))))
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

(defn- use-lib-skip [runtime opts]
  (let [approved (approved-libs runtime)
        syncs @(approved-lib-sync-state runtime)]
    (some (fn [lib]
            (cond
              (not (contains? (:libs approved) lib))
              [:not-approved {:lib lib}]

              (not (contains? syncs lib))
              [:not-synced {:lib lib}]

              (= :failed (:status (get syncs lib)))
              [:sync-failed {:lib lib :sync (get syncs lib)}]))
          (:libs opts))))

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
          @(approved-lib-sync-state runtime)))

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
            (throw (ex-info "Could not locate namespace source in synced library roots"
                            {:ns ns-sym
                             :relative-path relative-path
                             :searched-roots searched-roots}))))))))

(defn- exception-data [t]
  {:message (ex-message t)
   :class (str (class t))
   :data (ex-data t)})

(defn use! [runtime key opts]
  (validate-use-opts! key opts)
  (when-let [file (:file opts)]
    (module-file runtime file))
  (if-let [[reason data] (use-lib-skip runtime opts)]
    (skip-use runtime key opts reason data)
    (if-let [[reason data] (use-after-skip runtime opts)]
      (skip-use runtime key opts reason data)
      (try
        (let [load-result (with-library-classloader
                            runtime
                            #(if-let [ns-sym (:ns opts)]
                               (load-synced-namespace! runtime ns-sym)
                               (let [file (module-file runtime (:file opts))]
                                 (load-file file)
                                 {:file file})))
              call-result (when-let [call-sym (:call opts)]
                            (with-library-classloader
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

(defn uses [runtime]
  (into (sorted-map) @(module-use-state runtime)))

(defn use [runtime key]
  (get @(module-use-state runtime) key))

(defn- validated-query-entry [[query-name query-def]]
  [(query/canonical-query-name query-name)
   (query/validate-query-def! query-def)])

(defn register-query [runtime query-name query-def]
  (let [entry (validated-query-entry [query-name query-def])]
    (swap! (query-registry runtime) conj entry)
    (into {} [entry])))

(defn load-queries [runtime query-defs]
  (let [validated-query-defs (into {} (map validated-query-entry) query-defs)]
    (swap! (query-registry runtime) merge validated-query-defs)
    validated-query-defs))

(defn- current-runtime []
  (or @runtime/current-runtime
      (throw (ex-info "No weaver runtime is active" {}))))

(defn register-query! [query-name query-def]
  (register-query (current-runtime) query-name query-def))

(defn load-queries! [query-defs]
  (load-queries (current-runtime) query-defs))

(defn queries [runtime]
  (into (sorted-map) @(query-registry runtime)))

(defn resolve-query [runtime query-name]
  (query/query-def @(query-registry runtime) query-name))

(defn init [runtime]
  (db/init! (ds runtime))
  {:database "initialized"})

(defn add [runtime task]
  (normalize (db/add-task! (ds runtime) task)))

(defn- apply-edges! [tx id edges]
  (doseq [{:keys [to type attributes]} edges]
    (when-not (db/get-task tx to)
      (throw (ex-info "Edge target task not found" {:to to :type type})))
    (db/add-edge! tx {:from id :to to :type type :attributes (or attributes {})})))

(defn update [runtime id patch]
  (let [{:keys [title active ephemeral attributes edges]} patch]
    (jdbc/with-transaction [tx (ds runtime)]
      (when-not (db/get-task tx id)
        (throw (ex-info "Task not found" {:task-id id})))
      (apply-edges! tx id edges)
      (normalize (db/update-task! tx id (cond-> {}
                                          (contains? patch :title) (assoc :title title)
                                          (contains? patch :active) (assoc :active active)
                                          (contains? patch :ephemeral) (assoc :ephemeral ephemeral)
                                          (contains? patch :attributes) (assoc :attributes attributes)))))))

(defn show [runtime id]
  (normalize (db/get-task (ds runtime) id)))

(defn list
  ([runtime]
   (normalize (db/all-tasks (ds runtime))))
  ([runtime query-def params]
   (normalize (db/all-tasks (ds runtime) query-def params))))

(defn list-query [runtime query-name params]
  (list runtime (resolve-query runtime query-name) params))

(defn ready
  ([runtime]
   (normalize (db/ready-tasks (ds runtime))))
  ([runtime query-def params]
   (normalize (db/ready-tasks (ds runtime) query-def params))))

(defn ready-query [runtime query-name params]
  (ready runtime (resolve-query runtime query-name) params))

(defn query-ids [runtime query-or-name params]
  (let [query-def (if (or (vector? query-or-name) (map? query-or-name))
                    query-or-name
                    (resolve-query runtime query-or-name))]
    (db/query-task-ids (ds runtime) query-def params)))

(defn tasks-by-ids [runtime ids]
  (normalize (db/tasks-by-ids (ds runtime) ids)))

(defn ancestor-root-ids
  ([runtime seed-ids]
   (ancestor-root-ids runtime seed-ids {}))
  ([runtime seed-ids opts]
   (db/ancestor-root-ids (ds runtime) seed-ids opts)))

(defn subgraph [runtime root-ids]
  (let [{:keys [strands tasks edges] :as result} (db/subgraph (ds runtime) root-ids)
        rows (or strands tasks)]
    (assoc result
           :tasks (normalize rows)
           :strands (normalize rows)
           :edges (normalize edges))))

(defn- canonical-view-name [view-name]
  (query/canonical-query-name view-name))

(defn- validate-view-fn-symbol! [fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "View function must be a fully qualified symbol" {:fn fn-sym})))
  fn-sym)

(defn register-view! [runtime view-name fn-sym]
  (let [name (canonical-view-name view-name)
        entry {:name name :fn (validate-view-fn-symbol! fn-sym)}]
    (swap! (view-registry runtime) assoc name entry)
    entry))

(defn views [runtime]
  (mapv val (sort-by key @(view-registry runtime))))

(defn- resolve-view [runtime view-name]
  (let [canonical-name (canonical-view-name view-name)]
    (or (get @(view-registry runtime) canonical-name)
        (throw (ex-info "View not found" {:view view-name
                                           :canonical-view canonical-name
                                           :available (sort (keys @(view-registry runtime)))})))))

(defn view! [runtime view-name params]
  (let [{fn-sym :fn} (resolve-view runtime view-name)]
    (with-library-classloader
      runtime
      #((requiring-resolve fn-sym) {:params params}))))
