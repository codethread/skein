(ns todo.client-test
  (:refer-clojure :exclude [list update])
  (:require [clojure.test :refer [deftest is]]
            [todo.client :as client]
            [todo.daemon.api :as api]
            [todo.daemon.metadata :as metadata]
            [todo.daemon.runtime :as runtime]
            [todo.db-test :as db-test]))

(defn with-runtime [f]
  (let [db-file (db-test/temp-db-file)
        rt (runtime/start! db-file)]
    (try
      (f rt db-file)
      (finally
        (runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)))))

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

(deftest client-fails-loudly-for-missing-and-stale-metadata
  (let [db-file (db-test/temp-db-file)
        canonical (metadata/canonical-db-path db-file)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"metadata is missing or stale"
                            (client/list db-file)))
      (metadata/publish! {:pid 1 :canonical-db-path canonical})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"metadata is missing or stale"
                            (client/list db-file)))
      (finally
        (metadata/delete! canonical)
        (db-test/delete-sqlite-family! db-file)))))

(deftest client-fails-loudly-for-unreachable-and-non-local-endpoints
  (let [db-file (db-test/temp-db-file)
        canonical (metadata/canonical-db-path db-file)
        meta (metadata/metadata-shape {:pid 1
                                       :host "127.0.0.1"
                                       :port 1
                                       :canonical-db-path canonical
                                       :nonce "unreachable"})]
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
        (metadata/delete! canonical)
        (db-test/delete-sqlite-family! db-file)))))

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
          (is (= "Daemon API call failed" (ex-message e)))
          (is (= :todo.client/daemon-error (:type (ex-data e))))
          (is (= "Invalid task" (:daemon-message (ex-data e))))
          (is (re-find #"non-blank" (:explain (:daemon-data (ex-data e))))))))))

(deftest client-fails-loudly-for-timeouts
  (with-runtime
    (fn [_ db-file]
      (with-redefs [api/list (fn [_] (Thread/sleep 250) [])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"timed out"
                              (client/list db-file {:timeout-ms 50})))))))
