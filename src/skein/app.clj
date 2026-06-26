(ns skein.app
  (:require [clojure.string :as str]
            [skein.db :as db]))

(defn prompt [label]
  (print label)
  (flush)
  (read-line))

(defn parse-attrs [s]
  (if (str/blank? s)
    {}
    (into {}
          (for [pair (str/split s #",")
                :let [[k v] (map str/trim (str/split pair #"=" 2))]
                :when (and (seq k) (some? v))]
            [(keyword k) v]))))

(defn print-rows [rows]
  (if (seq rows)
    (doseq [row rows] (println row))
    (println "(no rows)")))

(defn add-task [ds]
  (let [title (prompt "title: ")
        attrs (prompt "attributes (priority=high,due-date=2026-07-01): ")]
    (println (db/add-task! ds {:title title :attributes (parse-attrs attrs)}))))

(defn add-dependency [ds]
  (let [from (prompt "task id: ")
        to (prompt "depends on task id: ")]
    (println (db/add-edge! ds {:from from :to to :type "depends-on" :attributes {}}))))

(defn menu []
  (println)
  (println "Todo graph")
  (println "1. list tasks")
  (println "2. add task")
  (println "3. add dependency")
  (println "4. blocked tasks")
  (println "5. tasks by priority")
  (println "6. dependencies for task")
  (println "7. related edges for task")
  (println "q. quit"))

(defn loop! [ds]
  (menu)
  (case (str/trim (or (prompt "> ") ""))
    "1" (do (print-rows (db/all-tasks ds)) (recur ds))
    "2" (do (add-task ds) (recur ds))
    "3" (do (add-dependency ds) (recur ds))
    "4" (do (print-rows (db/blocked-tasks ds)) (recur ds))
    "5" (do (print-rows (db/tasks-by-priority ds (prompt "priority: "))) (recur ds))
    "6" (do (print-rows (db/task-dependencies ds (prompt "task id: "))) (recur ds))
    "7" (do (print-rows (db/related-tasks ds (prompt "task id: "))) (recur ds))
    "q" (println "bye")
    (do (println "unknown command") (recur ds))))

(defn -main [& [db-file]]
  (let [ds (db/init! (db/datasource (or db-file db/default-db-file)))]
    (loop! ds)))
