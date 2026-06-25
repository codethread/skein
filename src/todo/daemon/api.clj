(ns todo.daemon.api
  (:refer-clojure :exclude [list update])
  (:require [next.jdbc :as jdbc]
            [todo.daemon.runtime :as runtime]
            [todo.db :as db]
            [todo.query :as query]))

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

(defn- plugin-registry [runtime]
  (:plugin-registry runtime))

(def supported-plugin-format-version 1)
(def plugin-authored-keys #{:format-version :name :version :requires-atom :provides})
(def plugin-loader-owned-keys #{:source :dir :init-file :loaded-at})
(def plugin-metadata-keys (into plugin-authored-keys plugin-loader-owned-keys))

(defn canonical-plugin-name [plugin-name]
  (cond
    (symbol? plugin-name) plugin-name
    (keyword? plugin-name) (symbol (namespace plugin-name) (name plugin-name))
    :else (throw (ex-info "Plugin name must be a symbol or keyword" {:name plugin-name}))))

(defn- canonical-provides [provides]
  (when-not (vector? provides)
    (throw (ex-info "Plugin :provides must be a vector" {:provides provides})))
  (mapv canonical-plugin-name provides))

(defn validate-plugin-metadata! [metadata]
  (when-not (map? metadata)
    (throw (ex-info "Plugin metadata must be a map" {:metadata metadata})))
  (let [keys-present (set (keys metadata))
        unknown (seq (remove plugin-metadata-keys keys-present))]
    (when unknown
      (throw (ex-info "Plugin metadata contains unknown keys" {:keys (vec unknown)})))
    (when-not (contains? metadata :format-version)
      (throw (ex-info "Plugin metadata requires :format-version" {})))
    (when-not (= supported-plugin-format-version (:format-version metadata))
      (throw (ex-info "Unsupported plugin metadata format version" {:format-version (:format-version metadata)})))
    (when-not (contains? metadata :name)
      (throw (ex-info "Plugin metadata requires :name" {})))
    (when (and (contains? metadata :version) (not (string? (:version metadata))))
      (throw (ex-info "Plugin :version must be a string" {:version (:version metadata)})))
    (when (and (contains? metadata :requires-atom) (not (string? (:requires-atom metadata))))
      (throw (ex-info "Plugin :requires-atom must be a string" {:requires-atom (:requires-atom metadata)})))
    (cond-> (assoc metadata :name (canonical-plugin-name (:name metadata)))
      (contains? metadata :provides) (clojure.core/update :provides canonical-provides))))

(defn register-plugin [runtime metadata]
  (let [recorded (validate-plugin-metadata! metadata)]
    (swap! (plugin-registry runtime) assoc (:name recorded) recorded)
    recorded))

(defn plugins [runtime]
  (vec (vals (into (sorted-map) @(plugin-registry runtime)))))

(defn plugin [runtime plugin-name]
  (get @(plugin-registry runtime) (canonical-plugin-name plugin-name)))

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
      (throw (ex-info "No daemon runtime is active" {}))))

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
  (let [{:keys [title status attributes edges]} patch]
    (jdbc/with-transaction [tx (ds runtime)]
      (when-not (db/get-task tx id)
        (throw (ex-info "Task not found" {:task-id id})))
      (apply-edges! tx id edges)
      (normalize (db/update-task! tx id {:title title
                                         :status status
                                         :attributes attributes})))))

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
