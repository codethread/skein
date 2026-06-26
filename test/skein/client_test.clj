(ns skein.client-test
  (:refer-clojure :exclude [list update])
  (:require [clojure.test :refer [deftest is]]
            [nrepl.core :as nrepl]
            [skein.client :as client]
            [skein.weaver.api :as api]
            [skein.weaver.config :as daemon-config]
            [skein.weaver.metadata :as metadata]
            [skein.weaver.runtime :as runtime]
            [skein.db-test :as db-test]))

(defn delete-tree! [file]
  (doseq [f (reverse (file-seq file))]
    (.delete f)))

(defn temp-world []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      (.toPath (java.io.File. "/tmp"))
                      "tcw"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (daemon-config/world (.getCanonicalPath dir))))

(defn with-default-world [world f]
  (let [real-world daemon-config/world]
    (with-redefs [daemon-config/world (fn
                                        ([] world)
                                        ([config-dir] (real-world config-dir)))]
      (f))))

(defn client-test-view [{:keys [params]}]
  {:client-view params})

(defn with-runtime [f]
  (let [db-file (db-test/temp-db-file)
        world (temp-world)]
    (with-default-world world
      (fn []
        (let [rt (runtime/start! db-file {:world world})]
          (try
            (f rt db-file)
            (finally
              (runtime/stop! rt)
              (db-test/delete-sqlite-family! db-file)
              (delete-tree! (java.io.File. (:config-dir world))))))))))

(deftest client-calls-running-daemon-and-returns-clojure-data
  (with-runtime
    (fn [_ db-file]
      (is (= {:database "initialized"} (client/init db-file)))
      (let [task (client/add db-file {:title "Bridge" :attributes {:owner "agent"}})]
        (is (= "Bridge" (:title task)))
        (is (= {:owner "agent"} (:attributes task)))
        (is (= task (client/show db-file (:id task))))
        (is (= [(:id task)] (mapv :id (client/list db-file))))
        (is (= [(:id task)] (mapv :id (client/ready db-file))))))))

(deftest client-query-registry-calls-share-daemon-state
  (with-runtime
    (fn [_ db-file]
      (let [query-def {:params [:owner]
                       :where [:= [:attr :owner] [:param :owner]]}]
        (is (= {"mine" query-def} (client/register-query db-file :mine query-def)))
        (is (= query-def (client/resolve-query db-file 'mine)))
        (is (= {"mine" query-def} (client/queries db-file)))
        (is (= {"done" [:= :active false]}
               (client/load-queries db-file {'done [:= :active false]})))
        (is (= {"done" [:= :active false]
                "mine" query-def}
               (client/queries db-file)))))))

(deftest client-routes-runtime-transformation-operations
  (with-runtime
    (fn [_ db-file]
      (client/init db-file)
      (let [agent (client/add db-file {:title "Agent" :attributes {:owner "agent"}})]
        (is (= {"mine" [:= [:attr :owner] "agent"]}
               (client/register-query db-file 'mine [:= [:attr :owner] "agent"])))
        (is (= [(:id agent)] (client/call db-file {} :query-ids 'mine {})))
        (is (= [agent] (client/call db-file {} :tasks-by-ids [(:id agent)])))
        (is (= [] (client/call db-file {} :ancestor-root-ids [(:id agent)] {:where [:= [:attr :kind] "feature"]})))
        (is (= {:root-ids [(:id agent)] :tasks [agent] :strands [agent] :edges []}
               (client/call db-file {} :subgraph [(:id agent)])))
        (is (= {:name "client" :fn 'skein.client-test/client-test-view}
               (client/call db-file {} :register-view! 'client 'skein.client-test/client-test-view)))
        (is (= [{:name "client" :fn 'skein.client-test/client-test-view}]
               (client/call db-file {} :views)))
        (is (= {:client-view {:ok true}}
               (client/call db-file {} :view! 'client {:ok true})))))))

(deftest client-query-registry-preserves-domain-errors
  (with-runtime
    (fn [_ db-file]
      (try
        (client/resolve-query db-file :missing)
        (is false "expected missing query error")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Weaver API call failed" (ex-message e)))
          (is (= "Query not found" (:weaver-message (ex-data e))))
          (is (= :missing (get-in (ex-data e) [:weaver-data :query])))))
      (try
        (client/load-queries db-file {"mine" [:= :active true]})
        (is false "expected invalid query name error")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Weaver API call failed" (ex-message e)))
          (is (= "Query names must be simple symbols or keywords" (:weaver-message (ex-data e)))))))))

(deftest client-fails-loudly-for-missing-and-stale-metadata
  (let [db-file (db-test/temp-db-file)
        canonical (metadata/canonical-db-path db-file)
        world (temp-world)]
    (with-default-world world
      (fn []
        (try
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"metadata is missing or stale"
                                (client/list db-file)))
          (metadata/publish! {:pid 1 :canonical-db-path canonical :state-dir (:state-dir world)})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"metadata is missing or stale"
                                (client/list db-file)))
          (finally
            (metadata/delete! world)
            (db-test/delete-sqlite-family! db-file)
            (delete-tree! (java.io.File. (:config-dir world)))))))))

(deftest client-fails-loudly-for-unreachable-and-non-local-endpoints
  (let [db-file (db-test/temp-db-file)
        canonical (metadata/canonical-db-path db-file)
        world (temp-world)
        meta (metadata/metadata-shape {:pid 1
                                       :host "127.0.0.1"
                                       :port 1
                                       :canonical-db-path canonical
                                       :nonce "unreachable"
                                       :world world})]
    (with-default-world world
      (fn []
        (try
          (metadata/publish! meta)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Unable to connect"
                                (client/list db-file {:timeout-ms 100})))
          (metadata/publish! (assoc-in meta [:endpoint :host] "203.0.113.1"))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"not loopback"
                                (client/list db-file {:timeout-ms 100})))
          (finally
            (metadata/delete! world)
            (db-test/delete-sqlite-family! db-file)
            (delete-tree! (java.io.File. (:config-dir world)))))))))

(deftest client-fails-loudly-for-wrong-daemon-identity
  (with-runtime
    (fn [rt db-file]
      (let [bad (assoc (:metadata rt) :nonce "wrong")]
        (metadata/publish! bad)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"identity does not match"
                              (client/list db-file)))))))

(deftest client-fails-loudly-for-daemon-thrown-domain-errors
  (with-runtime
    (fn [_ db-file]
      (client/init db-file)
      (try
        (client/add db-file {:title ""})
        (is false "expected daemon domain error")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Weaver API call failed" (ex-message e)))
          (is (= :skein.client/weaver-error (:type (ex-data e))))
          (is (= "Invalid strand" (:weaver-message (ex-data e))))
          (is (re-find #"non-blank" (:explain (:weaver-data (ex-data e))))))))))

(deftest client-fails-loudly-for-timeouts
  (with-redefs [nrepl/client (fn [conn _timeout-ms] conn)
                nrepl/client-session (fn [client _timeout-ms] client)
                nrepl/message (fn [_session _message]
                                (throw (java.net.SocketTimeoutException. "timed out")))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"timed out"
                          (client/eval-form :conn "(+ 1 1)" 50 {:operation :list})))))
