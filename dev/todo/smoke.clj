(ns todo.smoke
  (:require [clojure.data.json :as json]
            [clojure.string]
            [todo.daemon.metadata :as metadata]
            [todo.daemon.runtime :as runtime]
            [todo.repl :as repl]))

(def cli-smoke-db "smoke-cli.sqlite")
(def repl-smoke-db "smoke-repl.sqlite")
(def todo-bin "cli/bin/todo")

(defn titles [rows]
  (mapv :title rows))

(defn delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm" ".client.json"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn smoke-config-dir [db-file]
  (java.nio.file.Paths/get (str db-file ".config-dir") (make-array String 0)))

(defn smoke-world-db [db-file]
  (str (.resolve (smoke-config-dir db-file) "data/tasks.sqlite")))

(defn delete-runtime-metadata! [db-file]
  (metadata/delete! (metadata/canonical-db-path db-file)))

(defn delete-tree! [file]
  (when file
    (doseq [f (reverse (file-seq (.toFile file)))]
      (.delete f))))

(defn clean-runtime-artifacts! [db-file]
  (delete-sqlite-family! db-file)
  (delete-runtime-metadata! db-file)
  (delete-sqlite-family! (smoke-world-db db-file))
  (delete-runtime-metadata! (smoke-world-db db-file))
  (delete-tree! (smoke-config-dir db-file)))

(defn delete-built-cli! []
  (delete-tree! (java.nio.file.Paths/get "cli/bin" (make-array String 0))))

(defn run-process! [message command]
  (let [process (-> (ProcessBuilder. command)
                    (.redirectErrorStream true)
                    (.start))
        output (slurp (.getInputStream process))
        exit-code (.waitFor process)]
    (assert (= 0 exit-code)
            (str message ": " (pr-str command) "\n" output))
    output))

(defn build-cli! []
  (run-process! "Go CLI build succeeds" ["go" "build" "-o" "./cli/bin/todo" "./cli/cmd/todo"])
  todo-bin)

(defn write-client-config! [db-file]
  (let [dir (.toFile (smoke-config-dir db-file))]
    (.mkdirs dir)
    (spit (java.io.File. dir "config.json") (json/write-str {:source (.getAbsolutePath (java.io.File. ".")) :format "human"}))
    (.getPath dir)))

(defn run-cli! [db-file & args]
  (run-process! "Go CLI command succeeds" (into [todo-bin "--config-dir" (write-client-config! db-file)] args)))

(defn start-cli-daemon!
  ([db-file] (start-cli-daemon! db-file []))
  ([db-file daemon-args]
   (let [process (-> (ProcessBuilder. (into [todo-bin "--config-dir" (write-client-config! db-file) "daemon" "start"] daemon-args))
                     (.redirectErrorStream true)
                     (.start))]
     (loop [attempts 50]
       (when-not (.isAlive process)
         (throw (ex-info "CLI daemon exited before becoming ready" {:output (slurp (.getInputStream process))})))
       (when (zero? attempts)
         (.destroy process)
         (throw (ex-info "CLI daemon did not become ready" {})))
       (when-not (try
                   (run-cli! db-file "--format" "json" "daemon" "status")
                   true
                   (catch AssertionError _ false))
         (Thread/sleep 200)
         (recur (dec attempts))))
     process)))

(defn parse-json [s]
  (json/read-str s :key-fn keyword))

(defn cli-add! [db-file title & args]
  (:id (parse-json (apply run-cli! db-file "--format" "json" "add" title args))))

(defn assert= [expected actual message]
  (assert (= expected actual)
          (str message "\nexpected: " (pr-str expected) "\nactual: " (pr-str actual))))

(defn assert-contains [haystack needle message]
  (assert (clojure.string/includes? haystack needle)
          (str message "\nmissing: " (pr-str needle) "\nin: " haystack)))

(defn smoke-cli-help! []
  (let [root (run-process! "Go CLI root help succeeds" [todo-bin "--help"])
        add (run-process! "Go CLI add help succeeds" [todo-bin "add" "--help"])
        daemon (run-process! "Go CLI daemon help succeeds" [todo-bin "daemon" "--help"])
        start (run-process! "Go CLI daemon start help succeeds" [todo-bin "daemon" "start" "--help"])]
    (doseq [needle ["Available Commands:" "add" "list" "daemon"]]
      (assert-contains root needle "Go CLI root help shows command tree"))
    (doseq [needle ["add <title>" "--status" "--attr"]]
      (assert-contains add needle "Go CLI command help shows flags"))
    (doseq [needle ["start" "status" "stop"]]
      (assert-contains daemon needle "Go CLI subcommand help shows children"))
    (assert-contains start "--config-dir" "Go CLI nested subcommand help shows selected world flag")))

(defn stop-cli-daemon! [db-file daemon]
  (when (.isAlive daemon)
    (run-cli! db-file "daemon" "stop")
    (.waitFor daemon)))

(defn smoke-cli! [db-file]
  (clean-runtime-artifacts! db-file)
  (delete-built-cli!)
  (try
    (build-cli!)
    (smoke-cli-help!)
    (let [daemon (start-cli-daemon! db-file)]
      (try
        (run-cli! db-file "init")
            (let [design (cli-add! db-file "Sketch task graph model" "--status" "done" "--attr" "priority=high")
                  schema (cli-add! db-file "Create SQLite schema" "--attr" "priority=high")
                  docs (cli-add! db-file "Write usage notes" "--attr" "owner=agent")]
              (run-cli! db-file "update" schema "--edge" (str "depends-on:" design))
              (run-cli! db-file "update" docs "--edge" (str "depends-on:" schema))
              (assert= ["Create SQLite schema"]
                       (titles (parse-json (run-cli! db-file "--format" "json" "ready")))
                       "Go CLI ready sees tasks with final dependencies")
              (run-cli! db-file "update" schema "--status" "done")
              (assert= ["Write usage notes"]
                       (titles (parse-json (run-cli! db-file "--format" "json" "ready")))
                       "Go CLI update status changes readiness")
              (assert= "done"
                       (:status (parse-json (run-cli! db-file "--format" "json" "show" schema)))
                       "Go CLI show exposes first-class status")
              (let [status (parse-json (run-cli! db-file "--format" "json" "daemon" "status"))]
                (assert= true
                         (:healthy status)
                         "Go CLI daemon status checks socket health")
                (assert= (.getPath (metadata/socket-file (metadata/canonical-db-path (smoke-world-db db-file))))
                         (:socket_path status)
                         "Go CLI daemon status reports socket metadata")))
        (finally
          (stop-cli-daemon! db-file daemon))))
    (finally
      (clean-runtime-artifacts! db-file)
      (delete-built-cli!))))

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
                   (titles (repl/tasks 'agent-owner))
                   "todo.repl consumes a query registered during the daemon lifetime")
          (assert= ["Second task"]
                   (titles (repl/query '[:= [:attr :owner] "agent"]))
                   "todo.repl retains EDN-rich ad hoc query debugging")
          (repl/update! b {:status "done"})
          (assert= "done" (:status (repl/task b)) "todo.repl update! updates status"))
        (finally
          (runtime/stop! runtime))))
    (finally
      (clean-runtime-artifacts! db-file))))

(defn -main [& [db-file]]
  (smoke-cli! (if db-file (str db-file ".cli") cli-smoke-db))
  (smoke-repl! (if db-file (str db-file ".repl") repl-smoke-db))
  (println "\nSmoke completed with daemon-backed Go CLI and REPL flows."))
