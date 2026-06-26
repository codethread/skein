(ns skein.repl-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skein.client]
            [skein.weaver.config :as daemon-config]
            [skein.weaver.runtime :as runtime]
            [skein.db-test :as db-test]
            [skein.repl :as repl]))

(defn reset-open-state! []
  (reset! (var-get (ns-resolve 'skein.repl 'active-config-dir))
          (var-get (ns-resolve 'skein.repl 'no-connection))))

(defn with-runtime [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/td-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [world (daemon-config/world config-dir)
          rt (runtime/start! db-file {:world world})]
      (try
        (f rt db-file)
        (finally
          (reset-open-state!)
          (runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file))))))

(deftest helpers-fail-before-connect
  (reset-open-state!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Skein weaver world is connected"
                        (repl/tasks)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Skein weaver world is connected"
                        (repl/load-queries! "/path/does/not/matter.edn"))))

(deftest connect-without-arg-checks-default-world-not-explicit-config-dir
  (let [calls (atom [])]
    (with-redefs [skein.client/status-world (fn [config-dir]
                                             (swap! calls conj config-dir)
                                             {:ok true})]
      (is (= (:config-dir (daemon-config/world)) (repl/connect!)))
      (is (= [nil] @calls)))
    (reset-open-state!)))

(deftest connect-fails-without-selecting-a-daemon
  (let [config-dir (str "/tmp/td-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"metadata is missing or stale"
                            (repl/connect! config-dir)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No Skein weaver world is connected"
                            (repl/tasks)))
      (finally
        (reset-open-state!)))))

(deftest failed-connect-clears-previous-selection
  (with-runtime
    (fn [rt db-file]
      (repl/connect! (:config-dir (:metadata rt)))
      (spit db-file "not a config dir")
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"connect! expects a daemon config directory"
                              (repl/connect! db-file)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Skein weaver world is connected"
                              (repl/tasks)))
        (finally
          (db-test/delete-sqlite-family! db-file))))))

(deftest dev-user-namespace-loads
  (require 'user :reload)
  (is (some? (ns-resolve 'user 'demo!))))

(deftest helpers-use-daemon-backed-task-flow
  (with-runtime
    (fn [rt db-file]
      (is (= (:config-dir (:metadata rt)) (repl/connect! (:config-dir (:metadata rt)))))
      (is (= {:database "initialized"} (repl/init!)))
      (let [design (repl/task! "Sketch model" false {:priority "high"})
            docs (repl/task! "Write docs" {:owner "agent"})]
        (is (= {:priority "high"} (:attributes design)))
        (repl/update! (:id docs) {:edges [{:type "depends-on" :to (:id design)}]})
        (is (= {:owner "agent"} (:attributes (repl/task (:id docs)))))
        (is (= #{(:id design) (:id docs)} (set (map :id (repl/tasks)))))
        (is (= [(:id docs)] (mapv :id (repl/ready))))))))

(deftest stdin-main-evaluates-multiple-forms-in-helper-context
  (with-runtime
    (fn [rt _]
      (let [out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader. "(init!)\n(tasks)\n(ready)\n")
                  *out* out
                  *err* (java.io.StringWriter.)
                  *ns* (the-ns 'user)]
          (repl/-main "--stdin" (:config-dir (:metadata rt))))
        (let [lines (str/split-lines (str out))]
          (is (= 3 (count lines)))
          (is (= {:database "initialized"} (read-string (first lines))))
          (is (= [] (read-string (second lines))))
          (is (= [] (read-string (nth lines 2)))))))))

(deftest libs-alpha-helpers-work-from-connected-stdin-repl
  (with-runtime
    (fn [rt _]
      (let [out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader. "(require '[atom.libs.alpha :as libs])\n(libs/approved)\n(libs/syncs)\n(libs/uses)\n")
                  *out* out
                  *err* (java.io.StringWriter.)
                  *ns* (the-ns 'user)]
          (repl/-main "--stdin" (:config-dir (:metadata rt))))
        (let [lines (str/split-lines (str out))]
          (is (= 4 (count lines)))
          (is (= {:libs {}} (read-string (second lines))))
          (is (= {:libs {}} (read-string (nth lines 2))))
          (is (= {} (read-string (nth lines 3)))))))))

(deftest query-helpers-use-daemon-backed-task-flow
  (with-runtime
    (fn [rt db-file]
      (repl/connect! (:config-dir (:metadata rt)))
      (repl/init!)
      (let [design (:id (repl/task! "Design" false {:owner "agent"}))
            docs (:id (repl/task! "Docs" {:owner "agent"}))
            misc (:id (repl/task! "Misc" {:owner "human"}))]
        (repl/update! docs {:edges [{:type "depends-on" :to design}]})
        (is (= {"agent-ready" {:params [:owner]
                                :where [:= [:attr :owner] [:param :owner]]}}
               (repl/defquery! 'agent-ready {:params [:owner]
                                             :where [:= [:attr :owner] [:param :owner]]})))
        (is (= {"agent-ready" {:params [:owner]
                                :where [:= [:attr :owner] [:param :owner]]}}
               (repl/queries)))
        (is (= #{design docs}
               (set (map :id (repl/tasks 'agent-ready {:owner "agent"})))))
        (is (= [docs]
               (mapv :id (repl/ready [:= [:attr :owner] "agent"]))))
        (is (= [docs]
               (mapv :id (repl/ready :agent-ready {:owner "agent"}))))
        (is (= [misc]
               (mapv :id (repl/query :agent-ready {:owner "human"}))))))))

(deftest query-registry-helpers-use-daemon-memory
  (with-runtime
    (fn [rt db-file]
      (repl/connect! (:config-dir (:metadata rt)))
      (repl/init!)
      (let [agent (:id (repl/task! "Agent task" {:owner "agent"}))
            human (:id (repl/task! "Human task" {:owner "human"}))]
        (is (= {"mine" [:= [:attr :owner] "agent"]}
               (repl/defquery! :mine [:= [:attr :owner] "agent"])))
        (is (= {"mine" [:= [:attr :owner] "agent"]}
               (repl/queries)))
        (is (= [agent] (mapv :id (repl/tasks 'mine))))
        (runtime/stop! rt)
        (let [fresh-rt (runtime/start! db-file {:world (daemon-config/world (:config-dir (:metadata rt)))})]
          (try
            (is (= {} (repl/queries)))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Query not found"
                                  (repl/tasks :mine)))
            (let [query-file (java.io.File/createTempFile "todo-queries" ".edn")]
              (try
                (spit query-file (pr-str {'mine [:= [:attr :owner] "human"]}))
                (is (= {"mine" [:= [:attr :owner] "human"]}
                       (repl/load-queries! (.getAbsolutePath query-file))))
                (is (= [human] (mapv :id (repl/query :mine))))
                (spit query-file "{mine [:= [:attr :owner] \"agent\"]} {:extra true}")
                (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"exactly one form"
                                      (repl/load-queries! (.getAbsolutePath query-file))))
                (finally
                  (.delete query-file))))
            (finally
              (runtime/stop! fresh-rt))))))))

(deftest helpers-fail-loudly-when-daemon-becomes-unavailable
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/td-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [world (daemon-config/world config-dir)
          rt (runtime/start! db-file {:world world})]
      (try
        (repl/connect! (:config-dir (:metadata rt)))
        (runtime/stop! rt)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"metadata is missing or stale"
                              (repl/tasks)))
        (finally
          (reset-open-state!)
          (runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file))))))
