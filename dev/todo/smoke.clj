(ns todo.smoke
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [todo.daemon.metadata :as metadata]
            [todo.daemon.runtime :as runtime]
            [todo.repl :as repl]))

(def cli-smoke-db "smoke-cli.sqlite")
(def repl-smoke-db "smoke-repl.sqlite")

(defn titles [rows]
  (mapv :title rows))

(defn delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn delete-runtime-metadata! [db-file]
  (metadata/delete! (metadata/canonical-db-path db-file)))

(defn delete-tree! [file]
  (when file
    (doseq [f (reverse (file-seq (.toFile file)))]
      (.delete f))))

(defn clean-runtime-artifacts! [db-file]
  (delete-sqlite-family! db-file)
  (delete-runtime-metadata! db-file))

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

(defn start-cli-daemon!
  ([db-file] (start-cli-daemon! db-file []))
  ([db-file daemon-args]
   (let [process (-> (ProcessBuilder. (into ["clojure" "-M:todo" "--db" db-file "daemon" "start"] daemon-args))
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
    process)))

(defn cli-add! [db-file title & args]
  (str/trim (apply run-cli! db-file "add" title args)))

(defn assert= [expected actual message]
  (assert (= expected actual)
          (str message "\nexpected: " (pr-str expected) "\nactual: " (pr-str actual))))

(defn section [title rows]
  (println "\n--" title "--")
  (doseq [row rows] (println row)))

(defn stop-cli-daemon! [db-file daemon]
  (when (.isAlive daemon)
    (run-cli! db-file "daemon" "stop")
    (.waitFor daemon)))

(defn smoke-cli! [db-file]
  (clean-runtime-artifacts! db-file)
  (try
    (let [config-dir (java.nio.file.Files/createTempDirectory "todo-smoke-query-config" (make-array java.nio.file.attribute.FileAttribute 0))
          query-file (java.io.File. (.toFile config-dir) "queries.clj")
          config-file (java.io.File. (.toFile config-dir) "daemon.edn")]
      (try
        (spit query-file "(require '[todo.daemon.api :as api]) (api/register-query! 'configured-agent '[:= [:attr :owner] \"agent\"])")
        (spit config-file "{:load-files [\"queries.clj\"]}")
        (let [daemon (start-cli-daemon! db-file ["--config" (.getPath config-file)])]
          (try
            (run-cli! db-file "init")
            (let [design (cli-add! db-file "Sketch task graph model" "--status" "done" "--attr" "priority=high")
                  schema (cli-add! db-file "Create SQLite schema" "--attr" "priority=high")
                  docs (cli-add! db-file "Write usage notes" "--attr" "owner=agent")]
              (run-cli! db-file "update" schema "--edge" (str "depends-on:" design))
              (run-cli! db-file "update" docs "--edge" (str "depends-on:" schema))
              (assert= ["Create SQLite schema"]
                       (titles (read-string (run-cli! db-file "--format" "edn" "ready")))
                       "CLI ready sees tasks with final dependencies")
              (run-cli! db-file "update" schema "--status" "done")
              (assert= ["Write usage notes"]
                       (titles (json/read-str (run-cli! db-file "--format" "json" "ready") :key-fn keyword))
                       "CLI update status changes readiness")
              (assert= ["Write usage notes"]
                       (titles (read-string (run-cli! db-file "--format" "edn" "list" "--query" "configured-agent")))
                       "CLI consumes a query registered by trusted daemon startup config")
              (assert= "done"
                       (:status (read-string (run-cli! db-file "--format" "edn" "show" schema)))
                       "CLI show exposes first-class status"))
            (section "agent CLI process ready" (read-string (run-cli! db-file "--format" "edn" "ready")))
            (finally
              (stop-cli-daemon! db-file daemon))))
        (finally
          (delete-tree! config-dir))))
    (finally
      (clean-runtime-artifacts! db-file))))

(defn smoke-repl! [db-file]
  (clean-runtime-artifacts! db-file)
  (try
    (let [runtime (runtime/start! db-file)]
      (try
        (repl/open! db-file)
        (repl/init!)
        (let [a (:id (repl/task! "First task" "done" {}))
              b (:id (repl/task! "Second task" {:owner "agent"}))]
          (repl/update! b {:edges [{:type "depends-on" :to a}]})
          (assert= ["Second task"] (titles (repl/ready)) "todo.repl ready returns tasks with final dependencies")
          (repl/defquery! 'agent-owner '[:= [:attr :owner] "agent"])
          (assert= ["Second task"]
                   (titles (read-string (run-cli! db-file "--format" "edn" "list" "--query" "agent-owner")))
                   "CLI consumes a query registered through todo.repl during the daemon lifetime")
          (repl/update! b {:status "done"})
          (assert= "done" (:status (repl/task b)) "todo.repl update! updates status"))
        (finally
          (runtime/stop! runtime))))
    (finally
      (clean-runtime-artifacts! db-file))))

(defn -main [& [db-file]]
  (smoke-cli! (if db-file (str db-file ".cli") cli-smoke-db))
  (smoke-repl! (if db-file (str db-file ".repl") repl-smoke-db))
  (println "\nSmoke completed with daemon-backed CLI and REPL flows."))
