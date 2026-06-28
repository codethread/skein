(ns skein.smoke
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string]
            [skein.weaver.metadata :as metadata]
            [skein.weaver.runtime :as runtime]
            [skein.repl :as repl]))

(def cli-smoke-db "smoke-cli.sqlite")
(def repl-smoke-db "smoke-repl.sqlite")
(def strand-bin (.getAbsolutePath (java.io.File. "cli/bin/strand")))
(def checkout-root (.getAbsolutePath (java.io.File. ".")))

(defn titles [rows]
  (mapv :title rows))

(defn delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm" ".client.json"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn smoke-config-dir [db-file]
  (java.nio.file.Paths/get (str db-file ".config-dir") (make-array String 0)))

(defn smoke-world-db [db-file]
  (str (.resolve (smoke-config-dir db-file) "data/skein.sqlite")))

(defn smoke-world [db-file]
  (let [config-dir (.getCanonicalPath (.toFile (smoke-config-dir db-file)))]
    {:config-dir config-dir
     :state-dir (str config-dir "/state")
     :data-dir (str config-dir "/data")
     :db-path (str config-dir "/data/skein.sqlite")}))

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
  (let [strand-file (java.io.File. strand-bin)
        bin-dir (.getParentFile strand-file)]
    (.delete strand-file)
    (when (and bin-dir (.isDirectory bin-dir) (empty? (seq (.list bin-dir))))
      (.delete bin-dir))))

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
  (run-process! "Go CLI build succeeds" ["go" "build" "-o" "./cli/bin/strand" "./cli/cmd/strand"])
  strand-bin)

(defn write-client-config! [db-file]
  (let [dir (.toFile (smoke-config-dir db-file))]
    (.mkdirs dir)
    (spit (java.io.File. dir "config.json") (json/write-str {:configFormat "alpha" :source checkout-root }))
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
          "(require '[skein.libs.alpha :as libs])\n(libs/sync!)\n(libs/use! :smoke/lib {:ns 'smoke.lib :libs #{'smoke/lib}})\n(libs/use! :smoke/layer {:ns 'smoke.layer :libs #{'smoke/lib} :after [:smoke/lib] :call 'smoke.layer/install!})\n(libs/use! :smoke/optional-missing {:ns 'smoke.missing :libs #{'smoke/missing}})\n")
    (.getCanonicalPath marker)))

(defn outside-repo-dir []
  (doto (java.io.File. (System/getProperty "java.io.tmpdir") "skein-smoke-outside-repo")
    (.mkdirs)))

(defn write-client-config-to-dir! [config-dir]
  (.mkdirs (java.io.File. config-dir))
  (spit (java.io.File. config-dir "config.json") (json/write-str {:configFormat "alpha" :source checkout-root }))
  config-dir)

(defn run-cli-config! [config-dir & args]
  (run-process! "Go CLI command succeeds" (outside-repo-dir) nil (into [strand-bin "--config-dir" config-dir] args)))

(defn run-cli-config-stdin! [config-dir stdin & args]
  (run-process! "Go CLI stdin command succeeds" (outside-repo-dir) stdin (into [strand-bin "--config-dir" config-dir] args)))

(defn run-cli! [db-file & args]
  (apply run-cli-config! (write-client-config! db-file) args))

(defn run-cli-stdin! [db-file stdin & args]
  (apply run-cli-config-stdin! (write-client-config! db-file) stdin args))

(defn start-cli-daemon-config!
  ([config-dir] (start-cli-daemon-config! config-dir []))
  ([config-dir daemon-args]
   (let [process (-> (ProcessBuilder. (into [strand-bin "--config-dir" config-dir "weaver" "start"] daemon-args))
                     (.directory (outside-repo-dir))
                     (.redirectErrorStream true)
                     (.start))]
     (loop [attempts 50]
       (when-not (.isAlive process)
         (throw (ex-info "CLI weaver exited before becoming ready" {:output (slurp (.getInputStream process))})))
       (when (zero? attempts)
         (.destroy process)
         (throw (ex-info "CLI weaver did not become ready" {})))
       (when-not (try
                   (run-cli-config! config-dir "weaver" "status")
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
  (:id (parse-json (apply run-cli-config! config-dir "add" title args))))

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
        weaver (run-process! "Go CLI weaver help succeeds" [strand-bin "weaver" "--help"])
        start (run-process! "Go CLI weaver start help succeeds" [strand-bin "weaver" "start" "--help"])]
    (doseq [needle ["Available Commands:" "add" "list" "weaver"]]
      (assert-contains root needle "Go CLI root help shows command tree"))
    (doseq [needle ["add <title>" "--active" "--attr"]]
      (assert-contains add needle "Go CLI command help shows flags"))
    (doseq [needle ["start" "status" "stop"]]
      (assert-contains weaver needle "Go CLI subcommand help shows children"))
    (assert-contains start "--config-dir" "Go CLI nested subcommand help shows selected world flag")))

(defn stop-cli-daemon-config! [config-dir daemon]
  (when (.isAlive daemon)
    (run-cli-config! config-dir "weaver" "stop")
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
    (try
      (run-process! "clean bootstrap creates config-dir files before weaver is running"
                    (java.io.File. checkout-root)
                    nil
                    [strand-bin "--config-dir" config-dir "init"])
      (throw (ex-info "clean bootstrap init unexpectedly reached daemon" {}))
      (catch AssertionError e
        (let [message (ex-message e)]
          (when-not (or (clojure.string/includes? message "weaver socket unreachable")
                        (clojure.string/includes? message "no running weaver"))
            (throw e)))))
    (let [daemon (start-cli-daemon-config! config-dir)]
      (try
        (run-cli-config! config-dir "init")
        (assert (.isFile config-file) "clean bootstrap preserves/creates config.json")
        (assert-file-contents (java.io.File. config-dir "libs.edn") "{:libs {}}\n" "clean bootstrap creates empty libs.edn")
        (assert-file-contents (java.io.File. config-dir "init.clj") "(require '[skein.libs.alpha :as libs])\n\n(libs/sync!)\n" "clean bootstrap creates libs sync init.clj template")
        (assert (.isDirectory (java.io.File. config-dir "libs")) "clean bootstrap creates libs directory")
        (assert (.isDirectory (java.io.File. config-dir ".git")) "clean bootstrap initializes config-dir git repo")
        (let [strand-id (cli-add-config! config-dir "Bootstrap clean strand" "--attr" "owner=ct")]
          (assert= "Bootstrap clean strand"
                   (:title (parse-json (run-cli-config! config-dir "show" strand-id)))
                   "clean bootstrap can create and show strands after init"))
        (finally
          (stop-cli-daemon-config! config-dir daemon)
          (delete-tree! (smoke-config-dir-named (str db-file ".bootstrap-clean"))))))))

(defn smoke-bootstrap-dirty-config! [db-file]
  (let [config-dir (bootstrap-config-dir db-file "bootstrap-dirty")
        config-path (java.io.File. config-dir "config.json")
        libs-path (java.io.File. config-dir "libs.edn")
        init-path (java.io.File. config-dir "init.clj")
        original-config (str "{\"configFormat\":\"alpha\",\"source\":\"" checkout-root "\"}\n")
        original-libs "{:libs {}}\n;; user comment\n"
        original-init "(require '[skein.weaver.api :as api])\n(api/register-query! 'dirty [:= [:attr :owner] \"dirty\"])\n"]
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
        (cli-add-config! config-dir "Dirty owned strand" "--attr" "owner=dirty")
        (assert= ["Dirty owned strand"]
                 (titles (parse-json (run-cli-config! config-dir "list" "--query" "dirty")))
                 "dirty bootstrap keeps startup query usable from CLI")
        (finally
          (stop-cli-daemon-config! config-dir daemon)
          (delete-tree! (smoke-config-dir-named (str db-file ".bootstrap-dirty"))))))))

(defn smoke-startup-transformations! [db-file]
  (let [config-dir (bootstrap-config-dir db-file "startup-transform")
        init-path (java.io.File. config-dir "init.clj")
        event-marker (java.io.File. config-dir "event-handler.txt")]
    (delete-tree! (smoke-config-dir-named (str db-file ".startup-transform")))
    (write-client-config-to-dir! config-dir)
    (spit (java.io.File. config-dir "libs.edn") "{:libs {}}\n")
    (spit init-path
          (str "(ns smoke.startup\n  (:require [clojure.spec.alpha :as s]\n            [skein.libs.alpha :as libs]\n            [skein.events.alpha :as events]\n            [skein.graph.alpha :as graph]\n            [skein.views.alpha :as views]\n            [skein.weaver.api :as api]))\n(libs/sync!)\n(api/register-query! 'smoke-owned [:= [:attr :owner] \"smoke\"])\n(s/def ::title string?)\n(s/def ::review-input (s/keys :req-un [::title]))\n(defn review-pattern [{:keys [input]}]\n  (let [title (:title input)]\n    [{:ref 'impl :title title :attributes {:owner \"smoke\"}}\n     {:ref 'review :title (str \"Review: \" title) :attributes {:kind \"review\"} :edges [{:type \"depends-on\" :to 'impl}]}]))\n(api/register-pattern! 'review-task 'smoke.startup/review-pattern ::review-input)\n(def event-marker " (pr-str (.getCanonicalPath event-marker)) ")\n(defn record-added! [event]\n  (spit event-marker (:title (:strand event))))\n(defn smoke-owned-view [{:keys [params]}]\n  (let [ids (graph/query-ids! 'smoke-owned {})]\n    {:params params\n     :ids ids\n     :strands (graph/strands-by-ids ids)}))\n(views/register-view! 'smoke-owned-view 'smoke.startup/smoke-owned-view)\n(events/register! :smoke/record-added #{:strand/added} 'smoke.startup/record-added! {:source :smoke})\n"))
    (let [daemon (start-cli-daemon-config! config-dir)]
      (try
        (run-cli-config! config-dir "init")
        (let [strand-id (cli-add-config! config-dir "Startup transformed strand" "--attr" "owner=smoke")
              _ (loop [attempts 50]
                  (when-not (.isFile event-marker)
                    (when (zero? attempts)
                      (throw (ex-info "event handler did not record async add event" {})))
                    (Thread/sleep 100)
                    (recur (dec attempts))))
              payload (edn/read-string (run-cli-config-stdin! config-dir "(do (require '[skein.graph.alpha :as graph] '[skein.views.alpha :as views]) {:query-ids (graph/query-ids! 'smoke-owned {}) :view (views/view! 'smoke-owned-view {:source \"stdin\"}) :views (views/views)})\n" "weaver" "repl" "--stdin"))]
          (assert= [strand-id] (:query-ids payload) "startup registered query is available through graph helper")
          (assert= {:source "stdin"} (get-in payload [:view :params]) "startup view receives params")
          (assert= [strand-id] (get-in payload [:view :ids]) "startup view can call graph/query-ids!")
          (assert= ["Startup transformed strand"] (titles (get-in payload [:view :strands])) "startup view can hydrate graph strands")
          (assert= [{:name "smoke-owned-view" :fn 'smoke.startup/smoke-owned-view}]
                   (:views payload)
                   "startup registered view is introspectable")
          (assert= "Startup transformed strand" (slurp event-marker) "startup event handler observes async strand add event")
          (let [explanation (parse-json (run-cli-config! config-dir "pattern" "explain" "review-task"))
                woven (parse-json (run-cli-config-stdin! config-dir "{\"title\":\"Patterned smoke\"}\n" "weave" "--pattern" "review-task"))]
            (assert= "review-task" (:name explanation) "pattern explain exposes registered pattern")
            (assert= ["Patterned smoke" "Review: Patterned smoke"]
                     (titles (:created woven))
                     "weave applies startup pattern through JSON CLI"))
          (let [runtime-woven (edn/read-string
                               (run-cli-config-stdin!
                                config-dir
                                "(do\n  (defpattern! 'runtime-review 'smoke.startup/review-pattern :smoke.startup/review-input)\n  (weave! 'runtime-review {:title \"Runtime patterned smoke\"}))\n"
                                "weaver" "repl" "--stdin"))]
            (assert= ["Runtime patterned smoke" "Review: Runtime patterned smoke"]
                     (titles (:created runtime-woven))
                     "running weaver accepts runtime pattern registration through connected REPL"))
          (spit init-path
                (clojure.string/replace
                 (slurp init-path)
                 "(api/register-pattern! 'review-task 'smoke.startup/review-pattern ::review-input)\n"
                 "(api/register-pattern! 'review-task 'smoke.startup/review-pattern ::review-input)\n(api/register-pattern! 'reload-review 'smoke.startup/review-pattern ::review-input)\n"))
          (let [reload-payload (edn/read-string
                                (run-cli-config-stdin!
                                 config-dir
                                 "(do\n  (require '[skein.libs.alpha :as libs])\n  (libs/reload!)\n  {:patterns (patterns)\n   :woven (weave! 'reload-review {:title \"Reload patterned smoke\"})})\n"
                                 "weaver" "repl" "--stdin"))]
            (assert= ["reload-review" "review-task"]
                     (mapv :name (:patterns reload-payload))
                     "config reload refreshes pattern registry with new config-defined pattern")
            (assert= ["Reload patterned smoke" "Review: Reload patterned smoke"]
                     (titles (get-in reload-payload [:woven :created]))
                     "weave applies pattern added by config reload")))
        (finally
          (stop-cli-daemon-config! config-dir daemon)
          (delete-tree! (smoke-config-dir-named (str db-file ".startup-transform"))))))))

(defn smoke-bootstrap! [db-file]
  (smoke-bootstrap-clean-config! db-file)
  (smoke-bootstrap-dirty-config! db-file)
  (smoke-startup-transformations! db-file))


(defn smoke-cli! [db-file]
  (clean-runtime-artifacts! db-file)
  (delete-built-cli!)
  (try
    (build-cli!)
    (smoke-cli-help!)
    (smoke-bootstrap! db-file)
    (let [marker (write-library-startup-config! db-file)
          weaver (start-cli-daemon! db-file)]
      (try
        (assert= "base layered" (slurp marker) "selected config-dir init.clj activates layered local library during weaver startup")
        (run-cli! db-file "init")
            (let [design (cli-add! db-file "Sketch strand graph model" "--active=false" "--attr" "priority=high")
                  schema (cli-add! db-file "Create SQLite schema" "--attr" "priority=high")
                  docs (cli-add! db-file "Write usage notes" "--attr" "owner=agent")]
              (run-cli! db-file "update" schema "--edge" (str "depends-on:" design))
              (run-cli! db-file "update" docs "--edge" (str "depends-on:" schema))
              (assert= ["Create SQLite schema"]
                       (titles (parse-json (run-cli! db-file "ready")))
                       "Go CLI ready sees strands with inactive dependencies")
              (run-cli! db-file "update" schema "--active=false")
              (assert= ["Write usage notes"]
                       (titles (parse-json (run-cli! db-file "ready")))
                       "Go CLI update active changes readiness")
              (let [inactive-schema (parse-json (run-cli! db-file "show" schema))]
                (assert= false
                         (:active inactive-schema)
                         "Go CLI show exposes active lifecycle")
                (assert (:inactive_at inactive-schema)
                        (str "Go CLI show exposes inactive_at for inactive persistent strands\n" inactive-schema)))
              (let [scratch (cli-add! db-file "Temporary scratch strand" "--attr" "temporary=true")]
                (run-cli! db-file "burn" scratch)
                (assert (not (some #{"Temporary scratch strand"}
                                   (titles (parse-json (run-cli! db-file "list")))))
                        "Go CLI burn deletes a scratch strand row"))
              (let [status (parse-json (run-cli! db-file "weaver" "status"))]
                (assert= true
                         (:healthy status)
                         "Go CLI weaver status checks socket health")
                (assert= (.getPath (metadata/socket-file (smoke-world db-file)))
                         (:socket_path status)
                         "Go CLI weaver status reports socket metadata")
                (let [stdin-output (run-cli-stdin! db-file "(do\n  (require '[skein.libs.alpha :as libs])\n  (defquery! 'agent-owned '[:= [:attr :owner] \"agent\"])\n  {:strand-count (count (strands))\n   :ready-titles (mapv :title (ready))\n   :syncs (libs/syncs)\n   :base (libs/use :smoke/lib)\n   :layer (libs/use :smoke/layer)\n   :optional (libs/use :smoke/optional-missing)})\n" "weaver" "repl" "--stdin")
                      payload (edn/read-string stdin-output)]
                  (assert= 3 (:strand-count payload) "Go CLI weaver repl --stdin prints direct form result")
                  (assert= ["Write usage notes"] (:ready-titles payload) "Go CLI weaver repl --stdin has connected helper context")
                  (assert= :loaded (get-in payload [:syncs :libs 'smoke/lib :status]) "Go CLI weaver repl --stdin introspects loaded library sync state")
                  (assert= :failed (get-in payload [:syncs :libs 'smoke/missing :status]) "Go CLI weaver repl --stdin introspects missing library sync failure")
                  (assert= :loaded (get-in payload [:base :status]) "Go CLI weaver repl --stdin sees base module use state")
                  (assert= :loaded (get-in payload [:layer :status]) "Go CLI weaver repl --stdin sees layered module use state")
                  (assert= :layered (get-in payload [:layer :call :return]) "Go CLI weaver repl --stdin sees layered module call result")
                  (assert= :skipped (get-in payload [:optional :status]) "Go CLI weaver repl --stdin sees optional missing module skipped without bricking startup")
                  (assert (not (clojure.string/includes? stdin-output "\"result\""))
                          (str "Go CLI weaver repl --stdin must not wrap output in a CLI response envelope\n" stdin-output))
                  (assert= ["Write usage notes"]
                           (titles (parse-json (run-cli! db-file "list" "--query" "agent-owned")))
                           "Go CLI list --query consumes weaver query state from outside the repo"))
                (let [batch-output (run-cli-stdin!
                                    db-file
                                    (str "(do\n"
                                         "  (require '[skein.batch.alpha :as batch])\n"
                                         "  (let [result (batch/apply! {:refs {:docs \"" docs "\" :design \"" design "\"}\n"
                                         "                              :strands [{:ref :docs\n"
                                         "                                         :active true\n"
                                         "                                         :attributes {:owner \"agent\" :batch \"updated\"}}\n"
                                         "                                        {:ref :batch-followup\n"
                                         "                                         :title \"Batch follow-up\"\n"
                                         "                                         :attributes {:owner \"agent\" :batch \"created\"}}]\n"
                                         "                              :edges [{:op :upsert\n"
                                         "                                       :from :batch-followup\n"
                                         "                                       :to :docs\n"
                                         "                                       :type \"depends-on\"\n"
                                         "                                       :attributes {:source \"smoke\"}}]\n"
                                         "                              :burn [:design]})]\n"
                                         "    {:result result\n"
                                         "     :docs (strand \"" docs "\")\n"
                                         "     :batch-followup (strand (get-in result [:refs :batch-followup]))\n"
                                         "     :ready-titles (mapv :title (ready))}))\n")
                                    "weaver" "repl" "--stdin")
                      batch-payload (edn/read-string batch-output)
                      batch-result (:result batch-payload)
                      batch-followup-id (get-in batch-result [:refs :batch-followup])
                      batch-created (first (:created batch-result))
                      batch-updated (first (:updated batch-result))
                      batch-burned (first (:burned batch-result))]
                  (assert= 1 (count (:created batch-result)) "batch smoke returns exactly one created row")
                  (assert= 1 (count (:updated batch-result)) "batch smoke returns exactly one updated row")
                  (assert= 1 (count (:burned batch-result)) "batch smoke returns exactly one burned row")
                  (assert= docs (get-in batch-result [:refs :docs]) "batch smoke keeps bound existing ref in final refs")
                  (assert= design (get-in batch-result [:refs :design]) "batch smoke keeps burned ref in final refs")
                  (assert= batch-followup-id (:id batch-created) "batch smoke returns created row matching new ref")
                  (assert= {:title "Batch follow-up" :active true :attributes {:owner "agent" :batch "created"}}
                           (select-keys batch-created [:title :active :attributes])
                           "batch smoke returns normalized created row")
                  (assert= {:ref :docs :id docs}
                           (select-keys batch-updated [:ref :id])
                           "batch smoke returns updated row identity")
                  (assert= {:id docs :title "Write usage notes" :active true :attributes {:owner "agent"}}
                           (select-keys (:before batch-updated) [:id :title :active :attributes])
                           "batch smoke returns updated before row")
                  (assert= {:id docs :active true :attributes {:owner "agent" :batch "updated"}}
                           (select-keys (:after batch-updated) [:id :active :attributes])
                           "batch smoke returns updated after row")
                  (assert= {:ref :design :id design}
                           (select-keys batch-burned [:ref :id])
                           "batch smoke returns burned row identity")
                  (assert= {:id design :title "Sketch strand graph model" :active false :attributes {:priority "high"}}
                           (select-keys (:before batch-burned) [:id :title :active :attributes])
                           "batch smoke returns burned before row")
                  (assert= {:id docs :title "Write usage notes" :active true :attributes {:owner "agent" :batch "updated"}}
                           (select-keys (:docs batch-payload) [:id :title :active :attributes])
                           "batch smoke can observe updated graph state through REPL reads")
                  (assert= {:id batch-followup-id :title "Batch follow-up" :active true :attributes {:owner "agent" :batch "created"}}
                           (select-keys (:batch-followup batch-payload) [:id :title :active :attributes])
                           "batch smoke can observe created graph state through REPL reads")
                  (assert= ["Write usage notes"]
                           (:ready-titles batch-payload)
                           "batch smoke observes persisted depends-on edge through connected helper readiness reads")
                  (assert (not (some #{"Sketch strand graph model"}
                                     (titles (parse-json (run-cli! db-file "list")))))
                          "batch smoke burn removes existing strand from public CLI reads"))))
        (finally
          (stop-cli-daemon! db-file weaver))))
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
        (let [a (:id (repl/strand! "First strand" {} {:active false}))
              b (:id (repl/strand! "Second strand" {:owner "agent"}))]
          (repl/update! b {:edges [{:type "depends-on" :to a}]})
          (assert= ["Second strand"] (titles (repl/ready)) "skein.repl ready returns strands with inactive dependencies")
          (repl/defquery! 'agent-owner '[:= [:attr :owner] "agent"])
          (assert= ["Second strand"]
                   (titles (repl/strands 'agent-owner))
                   "skein.repl consumes a query registered during the weaver lifetime")
          (assert= ["Second strand"]
                   (titles (repl/query '[:= [:attr :owner] "agent"]))
                   "skein.repl retains EDN-rich ad hoc query debugging")
          (repl/update! b {:active false})
          (let [inactive-b (repl/strand b)]
            (assert= false (:active inactive-b) "skein.repl update! updates active")
            (assert (:inactive_at inactive-b)
                    (str "skein.repl exposes inactive_at for inactive persistent strands\n" inactive-b)))
          (let [scratch (:id (repl/strand! "Scratch REPL strand" {:temporary "true"}))]
            (repl/burn! scratch)
            (assert (nil? (repl/strand scratch))
                    "skein.repl burn! deletes a scratch strand row")))
        (finally
          (runtime/stop! runtime))))
    (finally
      (clean-runtime-artifacts! db-file))))

(defn -main [& [db-file]]
  (smoke-cli! (if db-file (str db-file ".cli") cli-smoke-db))
  (smoke-repl! (if db-file (str db-file ".repl") repl-smoke-db))
  (println "\nSmoke completed with weaver-backed Go CLI and REPL flows."))
