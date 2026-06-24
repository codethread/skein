(ns todo.daemon.api
  (:refer-clojure :exclude [list update])
  (:require [next.jdbc :as jdbc]
            [todo.db :as db]))

(def json-columns #{:attributes})

(declare normalize)

(defn normalize-row [row]
  (reduce-kv (fn [m k v]
               (assoc m k (cond
                            (and (json-columns k) (string? v)) (db/<-json v)
                            (map? v) (normalize v)
                            (sequential? v) (mapv normalize v)
                            :else v)))
             {}
             row))

(defn normalize [result]
  (cond
    (map? result) (normalize-row result)
    (sequential? result) (mapv normalize result)
    :else result))

(defn- ds [runtime]
  (:datasource runtime))

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

(defn list [runtime]
  (normalize (db/all-tasks (ds runtime))))

(defn ready [runtime]
  (normalize (db/ready-tasks (ds runtime))))
