(ns todo.smoke
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string]
            [todo.daemon.metadata :as metadata]
            [todo.daemon.runtime :as runtime]
            [todo.repl :as repl]))

(def cli-smoke-db "smoke-cli.sqlite")
(def repl-smoke-db "smoke-repl.sqlite")
(def todo-bin (.getAbsolutePath (java.io.File. "cli/bin/todo")))
(def checkout-root (.getAbsolutePath (java.io.File. ".")))

(defn titles [rows]
  (mapv :title rows))

(defn delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm" ".client.json"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn smoke-config-dir [db-file]
  (java.nio.file.Paths/get (str db-file ".config-dir") (make-array String 0)))

(defn smoke-world-db [db-file]
  (str (.resolve (smoke-config-dir db-file) "data/tasks.sqlite")))

(defn smoke-world [db-file]
  (let [config-dir (.getCanonicalPath (.toFile (smoke-config-dir db-file)))]
    {:config-dir config-dir
     :state-dir (str config-dir "/state")
     :data-dir (str config-dir "/data")}))

(defn delete-runtime-metadata! [db-file]
  (metadata/delete! (smoke-world db-file)))

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

(defn run-process!
  ([message command]
   (run-process! message nil nil command))
  ([message cwd stdin command]
   (let [builder (doto (ProcessBuilder. command)
                   (.redirectErrorStream true))
         _ (when cwd (.directory builder cwd))
         process (.start builder)]
     (when stdin
       (with-open [writer (java.io.OutputStreamWriter. (.getOutputStream process))]
         (.write writer stdin)))
     (let [output (slurp (.getInputStream process))
           exit-code (.waitFor process)]
       (assert (= 0 exit-code)
               (str message ": " (pr-str command) "\n" output))
       output))))

(defn build-cli! []
  (run-process! "Go CLI build succeeds" ["go" "build" "-o" "./cli/bin/todo" "./cli/cmd/todo"])
  todo-bin)

(defn write-client-config! [db-file]
  (let [dir (.toFile (smoke-config-dir db-file))]
    (.mkdirs dir)
    (spit (java.io.File. dir "config.json") (json/write-str {:configFormat "alpha" :source checkout-root :format "human"}))
    (.getCanonicalPath dir)))

(defn write-library-startup-config! [db-file]
  (let [config-dir (write-client-config! db-file)
        lib-dir (java.io.File. config-dir "libs/smoke-lib")
        src-dir (java.io.File. lib-dir "src/smoke")
        marker (java.io.File. config-dir "library-loaded.txt")]
    (.mkdirs src-dir)
    (spit (java.io.File. lib-dir "deps.edn") "{:paths [\"src\"]}\n")
    (spit (java.io.File. src-dir "lib.clj")
          "(ns smoke.lib)\n(defn value [] :base)\n")
    (spit (java.io.File. src-dir "layer.clj")
          (str "(ns smoke.layer\n  (:require [smoke.lib :as base]))\n"
               "(defn install! [] (spit " (pr-str (.getCanonicalPath marker)) " (str (name (base/value)) \" layered\")) :layered)\n"))
    (spit (java.io.File. config-dir "libs.edn")
          "{:libs {smoke/lib {:local/root \"libs/smoke-lib\"}\n        smoke/missing {:local/root \"libs/missing-lib\"}}}\n")
    (spit (java.io.File. config-dir "init.clj")
          "(require '[atom.libs.alpha :as libs])\n(libs/sync!)\n(libs/use! :smoke/lib {:ns 'smoke.lib :libs #{'smoke/lib}})\n(libs/use! :smoke/layer {:ns 'smoke.layer :libs #{'smoke/lib} :after [:smoke/lib] :call 'smoke.layer/install!})\n(libs/use! :smoke/optional-missing {:ns 'smoke.missing :libs #{'smoke/missing}})\n")
    (.getCanonicalPath marker)))

(defn outside-repo-dir []
  (doto (java.io.File. (System/getProperty "java.io.tmpdir") "atom-smoke-outside-repo")
    (.mkdirs)))

(defn write-client-config-to-dir! [config-dir]
  (.mkdirs (java.io.File. config-dir))
  (spit (java.io.File. config-dir "config.json") (json/write-str {:configFormat "alpha" :source checkout-root :format "human"}))
  config-dir)

(defn run-cli-config! [config-dir & args]
  (run-process! "Go CLI command succeeds" (outside-repo-dir) nil (into [todo-bin "--config-dir" config-dir] args)))

(defn run-cli-config-stdin! [config-dir stdin & args]
  (run-process! "Go CLI stdin command succeeds" (outside-repo-dir) stdin (into [todo-bin "--config-dir" config-dir] args)))

(defn run-cli! [db-file & args]
  (apply run-cli-config! (write-client-config! db-file) args))

(defn run-cli-stdin! [db-file stdin & args]
  (apply run-cli-config-stdin! (write-client-config! db-file) stdin args))

(defn start-cli-daemon-config!
  ([config-dir] (start-cli-daemon-config! config-dir []))
  ([config-dir daemon-args]
   (let [process (-> (ProcessBuilder. (into [todo-bin "--config-dir" config-dir "daemon" "start"] daemon-args))
                     (.directory (outside-repo-dir))
                     (.redirectErrorStream true)
                     (.start))]
     (loop [attempts 50]
       (when-not (.isAlive process)
         (throw (ex-info "CLI daemon exited before becoming ready" {:output (slurp (.getInputStream process))})))
       (when (zero? attempts)
         (.destroy process)
         (throw (ex-info "CLI daemon did not become ready" {})))
       (when-not (try
                   (run-cli-config! config-dir "--format" "json" "daemon" "status")
                   true
                   (catch AssertionError _ false))
         (Thread/sleep 200)
         (recur (dec attempts))))
     process)))

(defn start-cli-daemon!
  ([db-file] (start-cli-daemon! db-file []))
  ([db-file daemon-args]
   (start-cli-daemon-config! (write-client-config! db-file) daemon-args)))

(defn parse-json [s]
  (json/read-str s :key-fn keyword))

(defn cli-add-config! [config-dir title & args]
  (:id (parse-json (apply run-cli-config! config-dir "--format" "json" "add" title args))))

(defn cli-add! [db-file title & args]
  (apply cli-add-config! (write-client-config! db-file) title args))

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

(defn stop-cli-daemon-config! [config-dir daemon]
  (when (.isAlive daemon)
    (run-cli-config! config-dir "daemon" "stop")
    (.waitFor daemon)))

(defn stop-cli-daemon! [db-file daemon]
  (stop-cli-daemon-config! (write-client-config! db-file) daemon))

(defn smoke-config-dir-named [name]
  (java.nio.file.Paths/get (str name ".config-dir") (make-array String 0)))

(defn bootstrap-config-dir [db-file label]
  (.getCanonicalPath (.toFile (smoke-config-dir-named (str db-file "." label)))))

(defn assert-file-contents [file expected message]
  (assert= expected (slurp file) message))

(defn smoke-bootstrap-clean-config! [db-file]
  (let [config-dir (bootstrap-config-dir db-file "bootstrap-clean")
        config-file (java.io.File. config-dir "config.json")]
    (delete-tree! (smoke-config-dir-named (str db-file ".bootstrap-clean")))
    (write-client-config-to-dir! config-dir)
    (let [daemon (start-cli-daemon-config! config-dir)]
      (try
        (run-cli-config! config-dir "init")
        (assert (.isFile config-file) "clean bootstrap preserves/creates config.json")
        (assert-file-contents (java.io.File. config-dir "libs.edn") "{:libs {}}\n" "clean bootstrap creates empty libs.edn")
        (assert-file-contents (java.io.File. config-dir "init.clj") "(require '[atom.libs.alpha :as libs])\n(libs/sync!)\n" "clean bootstrap creates minimal init.clj")
        (assert (.isDirectory (java.io.File. config-dir "libs")) "clean bootstrap creates libs directory")
        (assert (.isDirectory (java.io.File. config-dir ".git")) "clean bootstrap initializes config-dir git repo")
        (let [task-id (cli-add-config! config-dir "Bootstrap clean task" "--attr" "owner=ct")]
          (assert= "Bootstrap clean task"
                   (:title (parse-json (run-cli-config! config-dir "--format" "json" "show" task-id)))
                   "clean bootstrap can create and show tasks after init"))
        (let [payload (edn/read-string (run-cli-config-stdin! config-dir "(do (require '[atom.libs.alpha :as libs]) {:approved (libs/approved) :syncs (libs/syncs) :uses (libs/uses)})\n" "daemon" "repl" "--stdin"))]
          (assert= {:libs {}} (:approved payload) "clean bootstrap approved libs are empty")
          (assert= {:libs {}} (:syncs payload) "clean bootstrap sync state is empty after default sync")
          (assert= {} (:uses payload) "clean bootstrap module use state is empty"))
        (finally
          (stop-cli-daemon-config! config-dir daemon)
          (delete-tree! (smoke-config-dir-named (str db-file ".bootstrap-clean"))))))))

(defn smoke-bootstrap-dirty-config! [db-file]
  (let [config-dir (bootstrap-config-dir db-file "bootstrap-dirty")
        config-path (java.io.File. config-dir "config.json")
        libs-path (java.io.File. config-dir "libs.edn")
        init-path (java.io.File. config-dir "init.clj")
        original-config (str "{\"configFormat\":\"alpha\",\"source\":\"" checkout-root "\",\"format\":\"json\"}\n")
        original-libs "{:libs {}}\n;; user comment\n"
        original-init "(require '[todo.daemon.api :as api])\n(api/register-query! 'dirty [:= [:attr :owner] \"dirty\"])\n"]
    (delete-tree! (smoke-config-dir-named (str db-file ".bootstrap-dirty")))
    (.mkdirs (java.io.File. config-dir))
    (.mkdirs (java.io.File. config-dir ".git"))
    (spit config-path original-config)
    (spit libs-path original-libs)
    (spit init-path original-init)
    (let [daemon (start-cli-daemon-config! config-dir)]
      (try
        (run-cli-config! config-dir "init")
        (assert-file-contents config-path original-config "dirty bootstrap does not rewrite existing config.json")
        (assert-file-contents libs-path original-libs "dirty bootstrap does not rewrite existing libs.edn")
        (assert-file-contents init-path original-init "dirty bootstrap does not rewrite existing init.clj")
        (assert (.isDirectory (java.io.File. config-dir "libs")) "dirty bootstrap fills missing libs directory")
        (cli-add-config! config-dir "Dirty owned task" "--attr" "owner=dirty")
        (assert= ["Dirty owned task"]
                 (titles (parse-json (run-cli-config! config-dir "list" "--query" "dirty")))
                 "dirty bootstrap keeps startup query usable from CLI")
        (finally
          (stop-cli-daemon-config! config-dir daemon)
          (delete-tree! (smoke-config-dir-named (str db-file ".bootstrap-dirty"))))))))

(defn smoke-bootstrap! [db-file]
  (smoke-bootstrap-clean-config! db-file)
  (smoke-bootstrap-dirty-config! db-file))

(defn write-live-lib! [config-dir lib-dir ns-name body]
  (let [root (java.io.File. config-dir (str "libs/" lib-dir))
        src-dir (java.io.File. root "src")
        ns-path (-> ns-name
                    (clojure.string/replace "-" "_")
                    (clojure.string/replace "." java.io.File/separator))
        src-file (java.io.File. src-dir (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit (java.io.File. root "deps.edn") "{:paths [\"src\"]}\n")
    (spit src-file body)
    root))

(defn smoke-live-library-reload! [db-file]
  (let [config-dir (bootstrap-config-dir db-file "live-libs")
        marker (java.io.File. config-dir "live-lib-installed.edn")]
    (delete-tree! (smoke-config-dir-named (str db-file ".live-libs")))
    (write-client-config-to-dir! config-dir)
    (spit (java.io.File. config-dir "libs.edn") "{:libs {}}\n")
    (spit (java.io.File. config-dir "init.clj") "(require '[atom.libs.alpha :as libs])\n(libs/sync!)\n")
    (let [daemon (start-cli-daemon-config! config-dir)]
      (try
        (run-cli-config! config-dir "init")
        (let [initial (edn/read-string (run-cli-config-stdin! config-dir "(do (require '[atom.libs.alpha :as libs]) {:approved (libs/approved) :syncs (libs/syncs) :uses (libs/uses)})\n" "daemon" "repl" "--stdin"))]
          (assert= {:libs {}} (:approved initial) "live library smoke starts with no approved libs")
          (assert= {:libs {}} (:syncs initial) "live library smoke starts with no synced libs")
          (assert= {} (:uses initial) "live library smoke starts with no used modules"))

        (spit (java.io.File. config-dir "libs.edn") "{:libs {live/missing {:local/root \"libs/missing\"}}}\n")
        (let [bad-sync (edn/read-string (run-cli-config-stdin! config-dir "(do (require '[atom.libs.alpha :as libs]) (libs/sync!))\n" "daemon" "repl" "--stdin"))
              bad-use (edn/read-string (run-cli-config-stdin! config-dir "(do (require '[atom.libs.alpha :as libs]) (libs/use! :live/missing {:ns 'live.missing :libs #{'live/missing}}))\n" "daemon" "repl" "--stdin"))]
          (assert= :failed (get-in bad-sync [:libs 'live/missing :status]) "missing live library sync records failure")
          (assert= :missing-root (get-in bad-sync [:libs 'live/missing :reason]) "missing live library sync records missing-root reason")
          (assert= :skipped (:status bad-use) "failed live library use is skipped")
          (assert= :sync-failed (:reason bad-use) "failed live library use reports sync-failed"))

        (write-live-lib! config-dir "throwing-lib" "live.throwing"
                         "(ns live.throwing)\n(defn install! [] (throw (ex-info \"install boom\" {:phase :install})))\n")
        (spit (java.io.File. config-dir "libs.edn") "{:libs {live/throwing {:local/root \"libs/throwing-lib\"}}}\n")
        (let [throwing (edn/read-string (run-cli-config-stdin! config-dir "(do (require '[atom.libs.alpha :as libs]) (libs/sync!) (libs/use! :live/throwing {:ns 'live.throwing :libs #{'live/throwing} :call 'live.throwing/install!}))\n" "daemon" "repl" "--stdin"))]
          (assert= :failed (:status throwing) "throwing live library call records failed use")
          (assert= "install boom" (get-in throwing [:error :message]) "throwing live library call captures user-code error"))
        (assert= true (:healthy (parse-json (run-cli-config! config-dir "--format" "json" "daemon" "status"))) "daemon remains healthy after bad live library code")

        (write-live-lib! config-dir "good-lib" "live.good-alpha"
                         (str "(ns live.good-alpha\n  (:require [todo.daemon.api :as api]))\n"
                              "(defn install! []\n"
                              "  (spit " (pr-str (.getCanonicalPath marker)) " (pr-str :installed))\n"
                              "  (api/register-query! 'live-owned [:= [:attr :owner] \"live\"])\n"
                              "  :installed)\n"))
        (spit (java.io.File. config-dir "libs.edn") "{:libs {live/good {:local/root \"libs/good-lib\"}}}\n")
        (let [loaded (edn/read-string (run-cli-config-stdin! config-dir "(do (require '[atom.libs.alpha :as libs]) (libs/sync!) (libs/use! :live/good {:ns 'live.good-alpha :libs #{'live/good} :call 'live.good-alpha/install!}))\n" "daemon" "repl" "--stdin"))]
          (assert= :loaded (:status loaded) "new good library loads without daemon restart")
          (assert= :installed (get-in loaded [:call :return]) "new good library install call returns value")
          (assert= :installed (edn/read-string (slurp marker)) "new good library install call has visible side effect"))
        (cli-add-config! config-dir "Live owned task" "--attr" "owner=live")
        (assert= ["Live owned task"]
                 (titles (parse-json (run-cli-config! config-dir "--format" "json" "list" "--query" "live-owned")))
                 "CLI can consume query registered by live-loaded library")
        (finally
          (stop-cli-daemon-config! config-dir daemon)
          (delete-tree! (smoke-config-dir-named (str db-file ".live-libs"))))))))

(defn smoke-cli! [db-file]
  (clean-runtime-artifacts! db-file)
  (delete-built-cli!)
  (try
    (build-cli!)
    (smoke-cli-help!)
    (smoke-bootstrap! db-file)
    (smoke-live-library-reload! db-file)
    (let [marker (write-library-startup-config! db-file)
          daemon (start-cli-daemon! db-file)]
      (try
        (assert= "base layered" (slurp marker) "selected config-dir init.clj activates layered local library during daemon startup")
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
                (assert= (.getPath (metadata/socket-file (smoke-world db-file)))
                         (:socket_path status)
                         "Go CLI daemon status reports socket metadata")
                (let [stdin-output (run-cli-stdin! db-file "(do\n  (require '[atom.libs.alpha :as libs])\n  (defquery! 'agent-owned '[:= [:attr :owner] \"agent\"])\n  {:task-count (count (tasks))\n   :ready-titles (mapv :title (ready))\n   :syncs (libs/syncs)\n   :base (libs/use :smoke/lib)\n   :layer (libs/use :smoke/layer)\n   :optional (libs/use :smoke/optional-missing)})\n" "daemon" "repl" "--stdin")
                      payload (edn/read-string stdin-output)]
                  (assert= 3 (:task-count payload) "Go CLI daemon repl --stdin prints direct form result")
                  (assert= ["Write usage notes"] (:ready-titles payload) "Go CLI daemon repl --stdin has connected helper context")
                  (assert= :loaded (get-in payload [:syncs :libs 'smoke/lib :status]) "Go CLI daemon repl --stdin introspects loaded library sync state")
                  (assert= :failed (get-in payload [:syncs :libs 'smoke/missing :status]) "Go CLI daemon repl --stdin introspects missing library sync failure")
                  (assert= :loaded (get-in payload [:base :status]) "Go CLI daemon repl --stdin sees base module use state")
                  (assert= :loaded (get-in payload [:layer :status]) "Go CLI daemon repl --stdin sees layered module use state")
                  (assert= :layered (get-in payload [:layer :call :return]) "Go CLI daemon repl --stdin sees layered module call result")
                  (assert= :skipped (get-in payload [:optional :status]) "Go CLI daemon repl --stdin sees optional missing module skipped without bricking startup")
                  (assert (not (clojure.string/includes? stdin-output "\"result\""))
                          (str "Go CLI daemon repl --stdin must not wrap output in a CLI response envelope\n" stdin-output))
                  (assert= ["Write usage notes"]
                           (titles (parse-json (run-cli! db-file "--format" "json" "list" "--query" "agent-owned")))
                           "Go CLI list --query consumes daemon query state from outside the repo"))))
        (finally
          (stop-cli-daemon! db-file daemon))))
    (finally
      (clean-runtime-artifacts! db-file)
      (delete-built-cli!))))

(defn smoke-repl! [db-file]
  (clean-runtime-artifacts! db-file)
  (try
    (let [world (smoke-world db-file)
          runtime (runtime/start! db-file {:world world})]
      (try
        (repl/connect! (:config-dir world))
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
