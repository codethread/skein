(ns todo.smoke
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [todo.db :as db]
            [todo.repl :as repl]))

(def smoke-db "smoke.sqlite")
(def cli-smoke-db "smoke-cli.sqlite")

(defn titles [rows]
  (mapv :title rows))

(defn delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn run-cli! [db-file & args]
  (let [command (into ["clojure" "-M:todo" "--db" db-file] args)
        process (-> (ProcessBuilder. command)
                    (.redirectErrorStream true)
                    (.start))
        output (slurp (.getInputStream process))
        exit-code (.waitFor process)]
    (assert (= 0 exit-code)
            (str "CLI command succeeds: " (pr-str command) "\n" output))
    output))

(defn start-cli-daemon! [db-file]
  (let [process (-> (ProcessBuilder. ["clojure" "-M:todo" "--db" db-file "daemon" "start"])
                    (.redirectErrorStream true)
                    (.start))]
    (loop [attempts 50]
      (when-not (.isAlive process)
        (throw (ex-info "CLI daemon exited before becoming ready" {:output (slurp (.getInputStream process))})))
      (when (zero? attempts)
        (throw (ex-info "CLI daemon did not become ready" {})))
      (when-not (try
                  (run-cli! db-file "daemon" "status")
                  true
                  (catch AssertionError _ false))
        (Thread/sleep 200)
        (recur (dec attempts))))
    process))

(defn cli-add! [db-file title & args]
  (str/trim (apply run-cli! db-file "add" title args)))

(defn assert= [expected actual message]
  (assert (= expected actual)
          (str message "\nexpected: " (pr-str expected) "\nactual: " (pr-str actual))))

(defn section [title rows]
  (println "\n--" title "--")
  (doseq [row rows] (println row)))

(defn -main [& [db-file]]
  (let [ds (db/datasource (or db-file smoke-db))
        cli-db (if db-file (str db-file ".cli") cli-smoke-db)]
    (delete-sqlite-family! cli-db)
    (let [daemon (start-cli-daemon! cli-db)]
      (try
        (run-cli! cli-db "init")
        (let [design (cli-add! cli-db "Sketch task graph model" "--status" "done" "--attr" "priority=high")
              schema (cli-add! cli-db "Create SQLite schema" "--attr" "priority=high")
              docs (cli-add! cli-db "Write usage notes" "--attr" "owner=agent")]
      (run-cli! cli-db "update" schema "--edge" (str "depends-on:" design))
      (run-cli! cli-db "update" docs "--edge" (str "depends-on:" schema))
      (assert= ["Create SQLite schema"]
               (titles (read-string (run-cli! cli-db "--format" "edn" "ready")))
               "CLI ready sees tasks with final dependencies")
      (run-cli! cli-db "update" schema "--status" "done")
      (assert= ["Write usage notes"]
               (titles (json/read-str (run-cli! cli-db "--format" "json" "ready") :key-fn keyword))
               "CLI update status changes readiness")
        (assert= "done"
                 (:status (read-string (run-cli! cli-db "--format" "edn" "show" schema)))
                 "CLI show exposes first-class status"))
      (section "agent CLI process ready" (read-string (run-cli! cli-db "--format" "edn" "ready")))
      (finally
        (when (.isAlive daemon)
          (run-cli! cli-db "daemon" "stop")
          (.waitFor daemon)))))

    (db/reset-db! ds)
    (let [design (:id (db/add-task! ds {:title "Sketch model" :status "done" :attributes {:priority "high"}}))
          docs (:id (db/add-task! ds {:title "Write docs" :attributes {:owner "agent"}}))]
      (db/add-edge! ds {:from docs :to design :type "depends-on" :attributes {}})
      (assert= ["Write docs"] (titles (db/ready-tasks ds)) "DB ready uses first-class status")
      (db/update-task! ds docs {:status "done"})
      (let [row (db/get-task ds docs)]
        (assert= "done" (:status row) "DB update sets status")
        (assert (some? (:final_at row)) "DB update sets final_at"))
      (section "all tasks" (db/all-tasks ds)))

    (let [repl-db (str (or db-file smoke-db) ".repl")
          expected-repl-helpers '#{open! init! task! update! task tasks ready}]
      (assert= expected-repl-helpers
               (set (keys (select-keys (ns-publics 'todo.repl) expected-repl-helpers)))
               "todo.repl exposes the stripped helper vocabulary")
      (try
        (repl/ready)
        (throw (ex-info "Expected todo.repl helpers to fail before open!" {}))
        (catch clojure.lang.ExceptionInfo e
          (assert (re-find #"No todo database is open" (.getMessage e)))))
      (delete-sqlite-family! repl-db)
      (repl/open! repl-db)
      (repl/init!)
      (let [a (:id (repl/task! "First task" "done" {}))
            b (:id (repl/task! "Second task" {:owner "agent"}))]
        (repl/update! b {:edges [{:type "depends-on" :to a}]})
        (assert= ["Second task"] (titles (repl/ready)) "todo.repl ready returns tasks with final dependencies")
        (repl/update! b {:status "done"})
        (assert= "done" (:status (repl/task b)) "todo.repl update! updates status")))
    (println "\nSmoke database:" (or db-file smoke-db))))
