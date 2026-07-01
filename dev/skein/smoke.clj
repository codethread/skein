(ns skein.smoke
  "Run end-to-end smoke coverage for disposable Skein CLI and REPL worlds."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string]
            [skein.weaver.metadata :as metadata]
            [skein.weaver.runtime :as runtime]
            [skein.repl :as repl]))

(def cli-smoke-db "smoke-cli.sqlite")
(def repl-smoke-db "smoke-repl.sqlite")
(def strand-bin (.getAbsolutePath (java.io.File. "cli/bin/strand")))
(def mill-bin (.getAbsolutePath (java.io.File. "cli/bin/mill")))
(def checkout-root (.getAbsolutePath (java.io.File. ".")))
(def smoke-run-root
  (doto (java.io.File. "/tmp" (str "sk" (.pid (java.lang.ProcessHandle/current))))
    (.mkdirs)))
(def smoke-xdg-state-home (str (.resolve (.toPath smoke-run-root) "xdg-state")) )

(defn titles [rows]
  (mapv :title rows))

(defn delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm" ".client.json"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn smoke-workspace [db-file]
  (.resolve (.toPath smoke-run-root) (str db-file ".workspace")))

(defn smoke-world-db [db-file]
  (str (.resolve (smoke-workspace db-file) "data/skein.sqlite")))

(defn smoke-world [db-file]
  (let [workspace (.getCanonicalPath (.toFile (smoke-workspace db-file)))]
    {:config-dir workspace
     :state-dir (str workspace "/state")
     :data-dir (str workspace "/data")
     :db-path (str workspace "/data/skein.sqlite")}))

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
  (delete-tree! (smoke-workspace db-file)))

(defn delete-built-cli! []
  (let [strand-file (java.io.File. strand-bin)
        mill-file (java.io.File. mill-bin)
        bin-dir (.getParentFile strand-file)]
    (.delete strand-file)
    (.delete mill-file)
    (when (and bin-dir (.isDirectory bin-dir) (empty? (seq (.list bin-dir))))
      (.delete bin-dir))))

(defn run-process!
  ([message command]
   (run-process! message nil nil command))
  ([message cwd stdin command]
   (let [builder (doto (ProcessBuilder. command)
                   (.redirectErrorStream true))
         _ (doto (.environment builder)
             (.put "XDG_STATE_HOME" smoke-xdg-state-home)
             (.put "SKEIN_SOURCE" checkout-root))
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

(defn run-process-fails!
  [message cwd command]
  (let [builder (doto (ProcessBuilder. command)
                  (.redirectErrorStream true))
        _ (doto (.environment builder)
             (.put "XDG_STATE_HOME" smoke-xdg-state-home)
             (.put "SKEIN_SOURCE" checkout-root))
        _ (when cwd (.directory builder cwd))
        process (.start builder)
        output (slurp (.getInputStream process))
        exit-code (.waitFor process)]
    (assert (not= 0 exit-code)
            (str message ": expected failure from " (pr-str command) "\n" output))
    output))

(defn build-cli! []
  (run-process! "Go strand CLI build succeeds" ["go" "build" "-o" "./cli/bin/strand" "./cli/cmd/strand"])
  (run-process! "Go mill CLI build succeeds" ["go" "build" "-o" "./cli/bin/mill" "./cli/cmd/mill"])
  strand-bin)

(defn start-mill! []
  (delete-tree! (.toPath (java.io.File. smoke-xdg-state-home)))
  (let [builder (doto (ProcessBuilder. [mill-bin "start"])
                  (.redirectErrorStream true))
        _ (doto (.environment builder)
             (.put "XDG_STATE_HOME" smoke-xdg-state-home)
             (.put "SKEIN_SOURCE" checkout-root))
        process (.start builder)
        metadata (java.io.File. smoke-run-root "xdg-state/skein/mill.json")]
    (loop [attempts 100]
      (cond
        (.isFile metadata) process
        (zero? attempts) (throw (ex-info "mill did not publish metadata" {:output (slurp (.getInputStream process))}))
        :else (do (Thread/sleep 50) (recur (dec attempts)))))))

(defn write-client-config! [db-file]
  (let [dir (.toFile (smoke-workspace db-file))]
    (.mkdirs dir)
    (spit (java.io.File. dir "config.json") (json/write-str {:configFormat "alpha"}))
    (.getCanonicalPath dir)))

(defn write-spool-startup-config! [db-file]
  (let [workspace (write-client-config! db-file)
        lib-dir (java.io.File. workspace "spools/smoke-lib")
        src-dir (java.io.File. lib-dir "src/smoke")
        marker (java.io.File. workspace "spool-loaded.txt")]
    (.mkdirs src-dir)
    (spit (java.io.File. lib-dir "deps.edn") "{:paths [\"src\"]}\n")
    (spit (java.io.File. src-dir "lib.clj")
          "(ns smoke.lib)\n(defn value [] :base)\n")
    (spit (java.io.File. src-dir "layer.clj")
          (str "(ns smoke.layer\n  (:require [smoke.lib :as base]))\n"
               "(defn install! [] (spit " (pr-str (.getCanonicalPath marker)) " (str (name (base/value)) \" layered\")) :layered)\n"))
    (spit (java.io.File. workspace "spools.edn")
          "{:spools {smoke/lib {:local/root \"spools/smoke-lib\"}\n        smoke/missing {:local/root \"spools/missing-lib\"}}}\n")
    (spit (java.io.File. workspace "init.clj")
          "(require '[skein.runtime.alpha :as runtime-alpha])\n(runtime-alpha/sync!)\n(runtime-alpha/use! :smoke/lib {:ns 'smoke.lib :spools #{'smoke/lib}})\n(runtime-alpha/use! :smoke/layer {:ns 'smoke.layer :spools #{'smoke/lib} :after [:smoke/lib] :call 'smoke.layer/install!})\n(runtime-alpha/use! :smoke/optional-missing {:ns 'smoke.missing :spools #{'smoke/missing}})\n")
    (.getCanonicalPath marker)))

(defn outside-repo-dir []
  (doto (java.io.File. smoke-run-root "outside-repo")
    (.mkdirs)))

(defn write-client-config-to-dir! [workspace]
  (.mkdirs (java.io.File. workspace))
  (spit (java.io.File. workspace "config.json") (json/write-str {:configFormat "alpha"}))
  workspace)

(defn run-cli-config! [workspace & args]
  (run-process! "Go CLI command succeeds" (outside-repo-dir) nil (into [strand-bin "--workspace" workspace] args)))

(defn run-cli-config-stdin! [workspace stdin & args]
  (run-process! "Go CLI stdin command succeeds" (outside-repo-dir) stdin (into [strand-bin "--workspace" workspace] args)))

(defn run-cli-config-fails! [workspace & args]
  (run-process-fails! "Go CLI command fails" (outside-repo-dir) (into [strand-bin "--workspace" workspace] args)))

(defn run-cli! [db-file & args]
  (apply run-cli-config! (write-client-config! db-file) args))

(defn run-cli-stdin! [db-file stdin & args]
  (apply run-cli-config-stdin! (write-client-config! db-file) stdin args))

(declare parse-json)

(defn start-cli-daemon-config!
  ([workspace] (start-cli-daemon-config! workspace []))
  ([workspace daemon-args]
   (let [builder (doto (ProcessBuilder. (into [strand-bin "--workspace" workspace "weaver" "start"] daemon-args))
                   (.directory (outside-repo-dir))
                   (.redirectErrorStream true))
         _ (doto (.environment builder)
             (.put "XDG_STATE_HOME" smoke-xdg-state-home)
             (.put "SKEIN_SOURCE" checkout-root))
         process (.start builder)]
     (let [start-output (slurp (.getInputStream process))
           exit-code (.waitFor process)]
       (assert (= 0 exit-code)
               (str "CLI weaver start request succeeds\n" start-output)))
     (loop [attempts 50]
       (when (zero? attempts)
         (throw (ex-info "CLI weaver did not become ready" {})))
       (let [running? (try
                        (= "running" (:state (parse-json (run-cli-config! workspace "weaver" "status"))))
                        (catch AssertionError _ false))]
         (when-not running?
           (Thread/sleep 200)
           (recur (dec attempts)))))
     process)))

(defn start-cli-daemon!
  ([db-file] (start-cli-daemon! db-file []))
  ([db-file daemon-args]
   (start-cli-daemon-config! (write-client-config! db-file) daemon-args)))

(defn parse-json [s]
  (json/read-str s :key-fn keyword))

(defn cli-add-config! [workspace title & args]
  (:id (parse-json (apply run-cli-config! workspace "add" title args))))

(defn cli-add! [db-file title & args]
  (apply cli-add-config! (write-client-config! db-file) title args))

(defn assert= [expected actual message]
  (assert (= expected actual)
          (str message "\nexpected: " (pr-str expected) "\nactual: " (pr-str actual))))

(defn assert-contains [haystack needle message]
  (assert (clojure.string/includes? haystack needle)
          (str message "\nmissing: " (pr-str needle) "\nin: " haystack)))

(defn smoke-cli-help! []
  (let [root (run-process! "Go CLI root help succeeds" [strand-bin "--help"])
        add (run-process! "Go CLI add help succeeds" [strand-bin "add" "--help"])
        op (run-process! "Go CLI op help succeeds" [strand-bin "op" "--help"])
        pattern (run-process! "Go CLI pattern help succeeds" [strand-bin "pattern" "--help"])
        weaver (run-process! "Go CLI weaver help succeeds" [strand-bin "weaver" "--help"])
        start (run-process! "Go CLI weaver start help succeeds" [strand-bin "weaver" "start" "--help"])
        repl (run-process! "Go CLI weaver repl help succeeds" [strand-bin "weaver" "repl" "--help"])]
    (doseq [needle ["Available Commands:" "add" "list" "weaver"]]
      (assert-contains root needle "Go CLI root help shows command tree"))
    (doseq [needle ["add <title>" "--state" "--attr"]]
      (assert-contains add needle "Go CLI command help shows flags"))
    (doseq [needle ["op <name> [args...]" "weaver-registered operation"]]
      (assert-contains op needle "Go CLI op help shows argv passthrough surface"))
    (doseq [needle ["list" "explain"]]
      (assert-contains pattern needle "Go CLI pattern help shows children"))
    (doseq [needle ["start" "status" "stop"]]
      (assert-contains weaver needle "Go CLI subcommand help shows children"))
    (assert-contains start "--workspace" "Go CLI nested subcommand help shows selected workspace flag")
    (doseq [needle ["Attach directly to the selected workspace's live weaver nREPL"
                    "send stdin Clojure forms to the running weaver"]]
      (assert-contains repl needle "Go CLI weaver repl help shows live attach semantics"))))

(defn stop-cli-daemon-config! [workspace daemon]
  (run-cli-config! workspace "weaver" "stop")
  (when (.isAlive daemon)
    (.waitFor daemon)))

(defn stop-cli-daemon! [db-file daemon]
  (stop-cli-daemon-config! (write-client-config! db-file) daemon))

(defn smoke-workspace-named [name]
  (.resolve (.toPath smoke-run-root) (str name ".workspace")))

(defn bootstrap-workspace [db-file label]
  (.getCanonicalPath (.toFile (smoke-workspace-named (str db-file "." label)))))

(defn assert-file-contents [file expected message]
  (assert= expected (slurp file) message))

(defn smoke-bootstrap-clean-config! [db-file]
  (let [workspace (bootstrap-workspace db-file "bootstrap-clean")
        config-file (java.io.File. workspace "config.json")]
    (delete-tree! (smoke-workspace-named (str db-file ".bootstrap-clean")))
    (run-process! "clean bootstrap creates workspace files before weaver is running"
                  (java.io.File. checkout-root)
                  nil
                  [strand-bin "--workspace" workspace "init"])
    (let [daemon (start-cli-daemon-config! workspace)]
      (try
        (run-cli-config! workspace "weaver" "status")
        (assert (.isFile config-file) "clean bootstrap preserves/creates config.json")
        (assert-file-contents (java.io.File. workspace "spools.edn") "{:spools {}}\n" "clean bootstrap creates empty spools.edn")
        (assert-file-contents (java.io.File. workspace "init.clj") "(require '[skein.runtime.alpha :as runtime-alpha])\n\n(runtime-alpha/sync!)\n" "clean bootstrap creates runtime sync init.clj template")
        (assert (.isDirectory (java.io.File. workspace "spools")) "clean bootstrap creates spools directory")
        (assert (not (.exists (java.io.File. workspace ".git"))) "clean bootstrap does not run git init")
        (let [strand-id (cli-add-config! workspace "Bootstrap clean strand" "--attr" "owner=ct")]
          (assert= "Bootstrap clean strand"
                   (:title (parse-json (run-cli-config! workspace "show" strand-id)))
                   "clean bootstrap can create and show strands after init"))
        (finally
          (stop-cli-daemon-config! workspace daemon)
          (delete-tree! (smoke-workspace-named (str db-file ".bootstrap-clean"))))))))

(defn smoke-bootstrap-dirty-config! [db-file]
  (let [workspace (bootstrap-workspace db-file "bootstrap-dirty")
        config-path (java.io.File. workspace "config.json")
        spools-path (java.io.File. workspace "spools.edn")
        init-path (java.io.File. workspace "init.clj")
        original-config "{\"configFormat\":\"alpha\"}\n"
        original-spools "{:spools {}}\n;; user comment\n"
        original-init "(require '[skein.weaver.api :as api])\n(api/register-query! 'dirty [:= [:attr :owner] \"dirty\"])\n"]
    (delete-tree! (smoke-workspace-named (str db-file ".bootstrap-dirty")))
    (.mkdirs (java.io.File. workspace))
    (.mkdirs (java.io.File. workspace ".git"))
    (spit config-path original-config)
    (spit spools-path original-spools)
    (spit init-path original-init)
    (run-cli-config! workspace "init")
    (let [daemon (start-cli-daemon-config! workspace)]
      (try
        (run-cli-config! workspace "weaver" "status")
        (assert-file-contents config-path original-config "dirty bootstrap does not rewrite existing config.json")
        (assert-file-contents spools-path original-spools "dirty bootstrap does not rewrite existing spools.edn")
        (assert-file-contents init-path original-init "dirty bootstrap does not rewrite existing init.clj")
        (assert (.isDirectory (java.io.File. workspace "spools")) "dirty bootstrap fills missing spools directory")
        (cli-add-config! workspace "Dirty owned strand" "--attr" "owner=dirty")
        (assert= ["Dirty owned strand"]
                 (titles (parse-json (run-cli-config! workspace "list" "--query" "dirty")))
                 "dirty bootstrap keeps startup query usable from CLI")
        (finally
          (stop-cli-daemon-config! workspace daemon)
          (delete-tree! (smoke-workspace-named (str db-file ".bootstrap-dirty"))))))))

(defn smoke-startup-transformations! [db-file]
  (let [workspace (bootstrap-workspace db-file "startup-transform")
        init-path (java.io.File. workspace "init.clj")
        event-marker (java.io.File. workspace "event-handler.txt")
        lib-root (java.io.File. workspace "spools/smoke-runtime-lib")]
    (delete-tree! (smoke-workspace-named (str db-file ".startup-transform")))
    (write-client-config-to-dir! workspace)
    (.mkdirs (java.io.File. lib-root "src"))
    (spit (java.io.File. lib-root "deps.edn") "{:paths [\"src\"]}\n")
    (spit (java.io.File. workspace "spools.edn") "{:spools {smoke/runtime-lib {:local/root \"spools/smoke-runtime-lib\"}}}\n")
    (spit init-path
          (str "(ns smoke.startup\n  (:require [clojure.spec.alpha :as s]\n            [skein.runtime.alpha :as runtime]\n            [skein.events.alpha :as events]\n            [skein.graph.alpha :as graph]\n            [skein.hooks.alpha :as hooks]\n            [skein.views.alpha :as views]\n            [skein.weaver.api :as api]))\n(runtime/sync!)\n(api/register-query! 'smoke-owned [:= [:attr :owner] \"smoke\"])\n(api/register-query! 'smoke-owner {:params [:owner] :where [:= [:attr :owner] [:param :owner]]})\n(s/def ::title string?)\n(s/def ::review-input (s/keys :req-un [::title]))\n(defn reject-blocked-owner [ctx]\n  (when (= \"blocked\" (get-in ctx [:strand/after :attributes :owner]))\n    (throw (ex-info \"smoke hook rejected blocked owner\" {:code :smoke/blocked-owner}))))\n(hooks/register! :smoke/reject-blocked-owner #{:strand/add-before-commit} 'smoke.startup/reject-blocked-owner)\n(defn review-pattern [{:keys [input]}]\n  (let [title (:title input)]\n    [{:ref 'impl :title title :attributes {:owner \"smoke\"}}\n     {:ref 'review :title (str \"Review: \" title) :attributes {:kind \"review\"} :edges [{:type \"depends-on\" :to 'impl}]}]))\n(api/register-pattern! 'review-task 'smoke.startup/review-pattern ::review-input)\n(def event-marker " (pr-str (.getCanonicalPath event-marker)) ")\n(defn record-added! [event]\n  (spit event-marker (:title (:strand event))))\n(defn smoke-owned-view [{:keys [params]}]\n  (let [ids (graph/query-ids! 'smoke-owned {})]\n    {:params params\n     :ids ids\n     :strands (graph/strands-by-ids ids)}))\n(views/register-view! 'smoke-owned-view 'smoke.startup/smoke-owned-view)\n(events/register! :smoke/record-added #{:strand/added} 'smoke.startup/record-added! {:source :smoke})\n"))
    (let [daemon (start-cli-daemon-config! workspace)]

      (try
        (run-cli-config! workspace "weaver" "status")
        (let [loader-state (edn/read-string
                            (run-cli-config-stdin!
                             workspace
                             "(do\n  (require '[skein.runtime.alpha :as runtime-alpha])\n  {:approved (runtime-alpha/approved)\n   :syncs (runtime-alpha/syncs)})\n"
                             "weaver" "repl" "--stdin"))]
          (assert= "spools/smoke-runtime-lib"
                   (get-in loader-state [:approved :spools 'smoke/runtime-lib :local/root])
                   "live REPL runtime loader reads real approved spool config")
          (assert= :loaded
                   (get-in loader-state [:syncs :spools 'smoke/runtime-lib :status])
                   "live REPL runtime loader reads real approved spool sync state"))
        (let [strand-id (cli-add-config! workspace "Startup transformed strand" "--attr" "owner=smoke")
              rejected-output (run-cli-config-fails! workspace "add" "Hook rejected strand" "--attr" "owner=blocked")
              _ (assert-contains rejected-output "hook/failed" "startup hook rejection reaches CLI as hook/failed")
              _ (loop [attempts 50]
                  (when-not (.isFile event-marker)
                    (when (zero? attempts)
                      (throw (ex-info "event handler did not record async add event" {})))
                    (Thread/sleep 100)
                    (recur (dec attempts))))
              payload (edn/read-string (run-cli-config-stdin! workspace "(do (require '[skein.graph.alpha :as graph] '[skein.views.alpha :as views]) {:query-ids (graph/query-ids! 'smoke-owned {}) :view (views/view! 'smoke-owned-view {:source \"stdin\"}) :views (views/views)})\n" "weaver" "repl" "--stdin"))]
          (assert= [strand-id] (:query-ids payload) "startup registered query is available through graph helper")
          (assert= {:source "stdin"} (get-in payload [:view :params]) "startup view receives params")
          (assert= [strand-id] (get-in payload [:view :ids]) "startup view can call graph/query-ids!")
          (assert= ["Startup transformed strand"] (titles (get-in payload [:view :strands])) "startup view can hydrate graph strands")
          (assert= [{:name "smoke-owned-view" :fn 'smoke.startup/smoke-owned-view}]
                   (:views payload)
                   "startup registered view is introspectable")
          (assert= "Startup transformed strand" (slurp event-marker) "startup event handler observes async strand add event")
          (let [query-entry (some #(when (= "smoke-owner" (:name %)) %) (parse-json (run-cli-config! workspace "query" "list")))
                explanation (parse-json (run-cli-config! workspace "query" "explain" "smoke-owner"))]
            (assert= {:name "smoke-owner" :params ["owner"] :referenced-params ["owner"]}
                     query-entry
                     "query list exposes registered query metadata")
            (assert= "smoke-owner" (:name explanation) "query explain exposes registered query name")
            (assert= ["owner"] (:params explanation) "query explain exposes declared params")
            (assert= ["owner"] (:referenced-params explanation) "query explain exposes referenced params")
            (assert-contains (:summary explanation) "list --query" "query explain exposes CLI invocation summary"))
          (let [patterns (parse-json (run-cli-config! workspace "pattern" "list"))
                explanation (parse-json (run-cli-config! workspace "pattern" "explain" "review-task"))
                woven (parse-json (run-cli-config-stdin! workspace "{\"title\":\"Patterned smoke\"}\n" "weave" "--pattern" "review-task"))]

            (assert= ["review-task"] (mapv :name patterns) "pattern list exposes registered patterns")
            (assert= "review-task" (:name explanation) "pattern explain exposes registered pattern")
            (assert= ["Patterned smoke" "Review: Patterned smoke"]
                     (titles (:created woven))
                     "weave applies startup pattern through JSON CLI"))
          (let [runtime-woven (edn/read-string
                               (run-cli-config-stdin!
                                workspace
                                "(do\n  (defpattern! 'runtime-review 'smoke.startup/review-pattern :smoke.startup/review-input)\n  (weave! 'runtime-review {:title \"Runtime patterned smoke\"}))\n"
                                "weaver" "repl" "--stdin"))]
            (assert= ["Runtime patterned smoke" "Review: Runtime patterned smoke"]
                     (titles (:created runtime-woven))
                     "running weaver accepts runtime pattern registration through live REPL attach"))
          (spit init-path
                (clojure.string/replace
                 (slurp init-path)
                 "(api/register-pattern! 'review-task 'smoke.startup/review-pattern ::review-input)\n"
                 "(api/register-pattern! 'review-task 'smoke.startup/review-pattern ::review-input)\n(api/register-pattern! 'reload-review 'smoke.startup/review-pattern ::review-input)\n"))
          (let [reload-payload (edn/read-string
                                (run-cli-config-stdin!
                                 workspace
                                 "(do\n  (require '[skein.runtime.alpha :as runtime-alpha])\n  (runtime-alpha/reload!)\n  {:patterns (patterns)\n   :woven (weave! 'reload-review {:title \"Reload patterned smoke\"})})\n"
                                 "weaver" "repl" "--stdin"))]
            (assert= ["reload-review" "review-task"]
                     (mapv :name (:patterns reload-payload))
                     "config reload refreshes pattern registry with new config-defined pattern")
            (assert= ["Reload patterned smoke" "Review: Reload patterned smoke"]
                     (titles (get-in reload-payload [:woven :created]))
                     "weave applies pattern added by config reload")))
        (finally
          (stop-cli-daemon-config! workspace daemon)
          (delete-tree! (smoke-workspace-named (str db-file ".startup-transform"))))))))

(defn wait-for-repo-weaver! [repo]
  (loop [attempts 50]
    (when (zero? attempts)
      (throw (ex-info "repo weaver did not become ready" {})))
    (let [running? (try
                     (= "running" (:state (parse-json (run-process! "repo weaver status succeeds" repo nil [strand-bin "weaver" "status"]))))
                     (catch AssertionError _ false))]
      (when-not running?
        (Thread/sleep 200)
        (recur (dec attempts))))))

(defn smoke-git-repo-world! []
  (let [repo (java.io.File. smoke-run-root "git-repo-world")]
    (delete-tree! (.toPath repo))
    (.mkdirs repo)
    (run-process! "smoke repo git init succeeds" repo nil ["git" "init"])
    (run-process! "repo bootstrap initializes .skein through mill" repo nil [strand-bin "init"])
    (run-process! "repo weaver start succeeds" repo nil [strand-bin "weaver" "start"])
    (wait-for-repo-weaver! repo)
    (try
      (let [_strand-id (:id (parse-json (run-process! "repo add strand succeeds" repo nil [strand-bin "add" "Repo smoke strand" "--attr" "owner=smoke"])))
            listed (parse-json (run-process! "repo list succeeds" repo nil [strand-bin "list"]))
            runtime-out (run-process! "repo stdin repl succeeds" repo "@skein.weaver.runtime/current-runtime\n" [strand-bin "weaver" "repl" "--stdin"])]
        (assert= ["Repo smoke strand"] (titles listed) "repo world list sees CLI-created strand")
        (assert (clojure.string/includes? runtime-out ":metadata") "repo world stdin REPL evaluates in the live weaver JVM")
        (assert (clojure.string/includes? runtime-out (str (.getCanonicalPath repo) "/.skein")) "repo world stdin REPL uses the selected running weaver"))
      (finally
        (run-process! "repo weaver stop succeeds" repo nil [strand-bin "weaver" "stop"])
        (delete-tree! (.toPath repo))))))

(defn smoke-bootstrap! [db-file]
  (smoke-git-repo-world!)
  (smoke-bootstrap-clean-config! db-file)
  (smoke-bootstrap-dirty-config! db-file)
  (smoke-startup-transformations! db-file))


(defn smoke-cli! [db-file]
  (clean-runtime-artifacts! db-file)
  (delete-built-cli!)
  (try
    (build-cli!)
    (let [mill (start-mill!)]
      (try
        (smoke-cli-help!)
        (smoke-bootstrap! db-file)
        (finally
          (.destroy mill)
          (.waitFor mill))))
    (finally
      (clean-runtime-artifacts! db-file)
      (delete-built-cli!))))

(defn smoke-repl! [db-file]
  (clean-runtime-artifacts! db-file)
  (try
    (let [world (smoke-world db-file)
          runtime (runtime/start! nil {:world world})]
      (try
        (repl/connect! (:config-dir world))
        (repl/init!)
        (let [a (:id (repl/strand! "First strand" {} {:state "closed"}))
              b (:id (repl/strand! "Second strand" {:owner "agent"}))]
          (repl/update! b {:edges [{:type "depends-on" :to a}]})
          (assert= ["Second strand"] (titles (repl/ready)) "skein.repl ready returns strands with closed dependencies")
          (repl/defquery! 'agent-owner '[:= [:attr :owner] "agent"])
          (assert= ["Second strand"]
                   (titles (repl/strands 'agent-owner))
                   "skein.repl consumes a query registered during the weaver lifetime")
          (assert= ["Second strand"]
                   (titles (repl/query '[:= [:attr :owner] "agent"]))
                   "skein.repl retains EDN-rich ad hoc query debugging")
          (repl/update! b {:state "closed"})
          (let [closed-b (repl/strand b)]
            (assert= "closed" (:state closed-b) "skein.repl update! updates state"))
          (let [scratch (:id (repl/strand! "Scratch REPL strand" {:temporary "true"}))]
            (repl/burn! scratch)
            (assert (nil? (repl/strand scratch))
                    "skein.repl burn! deletes a scratch strand row"))
          (let [old (:id (repl/strand! "Old REPL strand"))
                replacement (:id (repl/strand! "Replacement REPL strand"))
                dependent (:id (repl/strand! "Dependent REPL strand"))]
            (repl/update! dependent {:edges [{:type "depends-on" :to old}]})
            (let [result (repl/supersede! old replacement)]
              (assert= "replaced" (get-in result [:old :after :state]) "skein.repl supersede! marks old strand replaced")
              (assert= replacement (:replacement-id result) "skein.repl supersede! reports replacement id")
              (assert= #{dependent}
                       (set (map :from (:rewired-dependencies result)))
                       "skein.repl supersede! rewires direct dependents"))))
        (finally
          (runtime/stop! runtime))))
    (finally
      (clean-runtime-artifacts! db-file))))

(defn -main [& [db-file]]
  (try
    (smoke-cli! (if db-file (str db-file ".cli") cli-smoke-db))
    (smoke-repl! (if db-file (str db-file ".repl") repl-smoke-db))
    (println "\nSmoke completed with weaver-backed Go CLI and REPL flows.")
    (finally
      (delete-tree! (.toPath smoke-run-root)))))
