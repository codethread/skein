(ns todo.daemon-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [todo.daemon.api :as api]
            [todo.daemon.metadata :as metadata]
            [todo.daemon.runtime :as runtime]
            [todo.db :as db]
            [todo.db-test :as db-test]))

(defn delete-tree! [file]
  (doseq [f (reverse (file-seq file))]
    (.delete f)))

(defn with-runtime
  ([f] (with-runtime nil f))
  ([start-options f]
   (let [db-file (db-test/temp-db-file)
         rt (runtime/start! db-file (or start-options {}))]
     (try
       (f rt db-file)
       (finally
         (runtime/stop! rt)
         (db-test/delete-sqlite-family! db-file))))))

(deftest daemon-api-delegates-to-db-and-normalizes-results
  (with-runtime
    (fn [rt _]
      (is (= {:database "initialized"} (api/init rt)))
      (let [design (api/add rt {:title "Design" :status "done" :attributes {:priority "high"}})
            docs (api/add rt {:title "Docs" :attributes {:owner "agent"}})]
        (is (= {:priority "high"} (:attributes design)))
        (api/update rt (:id docs) {:attributes {:phase "write"}
                                   :edges [{:type "depends-on" :to (:id design)}]})
        (is (= {:owner "agent" :phase "write"} (:attributes (api/show rt (:id docs)))))
        (is (= #{(:id design) (:id docs)} (set (map :id (api/list rt)))))
        (is (= [(:id docs)] (mapv :id (api/ready rt))))))))

(deftest daemon-query-registry-add-load-list-and-resolve
  (with-runtime
    (fn [rt _]
      (let [open-query [:= :status "open"]
            owner-query {:params [:owner]
                         :where [:= [:attr :owner] [:param :owner]]}]
        (is (= {"mine" owner-query} (api/register-query rt 'mine owner-query)))
        (is (= owner-query (api/resolve-query rt :mine)))
        (is (= {"mine" owner-query} (api/queries rt)))
        (is (= {"open" open-query} (api/load-queries rt {:open open-query})))
        (is (= {"mine" owner-query
                "open" open-query}
               (api/queries rt)))))))

(deftest daemon-query-registry-fails-clearly
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Query not found"
                            (api/resolve-query rt 'missing)))
      (try
        (api/resolve-query rt 'missing)
        (is false "expected missing query error")
        (catch clojure.lang.ExceptionInfo e
          (is (= 'missing (:query (ex-data e))))
          (is (= "missing" (:canonical-query (ex-data e))))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (api/register-query rt 'user/mine [:= :status "open"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (api/load-queries rt {"mine" [:= :status "open"]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (api/load-queries rt {'user/mine [:= :status "open"]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown query operator"
                            (api/register-query rt :broken [:unknown :status "open"])))
      (api/register-query rt :ok [:= :status "open"])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown query operator"
                            (api/load-queries rt {:bad [:unknown :status "open"]})))
      (is (= {"ok" [:= :status "open"]} (api/queries rt))))))

(deftest daemon-api-update-preserves-domain-errors-and-rolls-back
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (let [source (api/add rt {:title "Source"})
            target (api/add rt {:title "Target"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Task not found"
                              (api/update rt "missing" {:edges [{:type "depends-on" :to (:id target)}]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"non-blank"
                              (api/update rt (:id source) {:title ""
                                                           :edges [{:type "depends-on" :to (:id target)}]})))
        (is (empty? (db/task-dependencies (:datasource rt) (:id source))))))))

(deftest runtime-metadata-records-canonical-loopback-identity
  (with-runtime
    (fn [rt db-file]
      (let [canonical (metadata/canonical-db-path db-file)
            status (runtime/status rt)
            file (:metadata-file rt)
            from-disk (edn/read-string (slurp file))]
        (is (= canonical (:canonical-db-path status)))
        (is (= status from-disk))
        (is (= file (metadata/metadata-file canonical)))
        (is (pos-int? (get-in status [:endpoint :port])))
        (is (string? (:nonce status)))
        (is (= :nrepl (:transport status)))
        (is (false? (metadata/stale-or-missing? status)))
        (is (= "127.0.0.1" (get-in status [:endpoint :host])))
        (is (.isLoopbackAddress (.getInetAddress (:server-socket (:server rt)))))))))

(deftest metadata-shape-detects-missing-and-stale-files
  (let [db-file (db-test/temp-db-file)
        canonical (metadata/canonical-db-path db-file)]
    (try
      (metadata/delete! canonical)
      (testing "missing metadata reads as nil and is stale"
        (is (nil? (metadata/read-metadata canonical)))
        (is (metadata/stale-or-missing? nil)))
      (testing "malformed metadata shape is stale"
        (is (metadata/stale-or-missing? {:pid 1 :canonical-db-path canonical})))
      (finally
        (metadata/delete! canonical)
        (db-test/delete-sqlite-family! db-file)))))

(deftest runtime-stop-removes-metadata
  (let [db-file (db-test/temp-db-file)
        rt (runtime/start! db-file)
        canonical (metadata/canonical-db-path db-file)]
    (try
      (runtime/stop! rt)
      (is (nil? (metadata/read-metadata canonical)))
      (finally
        (runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)))))

(deftest runtime-rejects-duplicate-live-metadata
  (let [db-file (db-test/temp-db-file)
        rt (runtime/start! db-file)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"already active"
                            (runtime/start! db-file)))
      (finally
        (runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)))))

(deftest runtime-starts-with-minimal-trusted-config
  (let [dir (java.nio.file.Files/createTempDirectory "todo-daemon-config" (make-array java.nio.file.attribute.FileAttribute 0))
        loaded (io/file (.toFile dir) "loaded.clj")
        config (io/file (.toFile dir) "config.edn")]
    (try
      (spit loaded "(ns todo.daemon-test-loaded) (def loaded? true)")
      (spit config "{:load-files [\"loaded.clj\"]}")
      (with-runtime {:config-file (.getPath config)}
        (fn [_rt _]
          (is (true? @(requiring-resolve 'todo.daemon-test-loaded/loaded?)))))
      (finally
        (delete-tree! (.toFile dir))))))

(deftest runtime-config-failures-do-not-publish-metadata
  (let [db-file (db-test/temp-db-file)
        canonical (metadata/canonical-db-path db-file)
        dir (java.nio.file.Files/createTempDirectory "todo-daemon-bad-config" (make-array java.nio.file.attribute.FileAttribute 0))
        malformed (io/file (.toFile dir) "malformed.edn")
        trailing (io/file (.toFile dir) "trailing.edn")
        unsupported (io/file (.toFile dir) "unsupported.edn")
        missing-load (io/file (.toFile dir) "missing-load.edn")
        bad-code (io/file (.toFile dir) "bad.clj")
        bad-code-config (io/file (.toFile dir) "bad-code.edn")]
    (try
      (spit malformed "{:load-files [")
      (is (thrown? Exception (runtime/start! db-file {:config-file (.getPath malformed)})))
      (is (nil? @runtime/current-runtime))
      (is (nil? (metadata/read-metadata canonical)))
      (spit trailing "{:load-files []} {:reload true}")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exactly one EDN map"
                            (runtime/start! db-file {:config-file (.getPath trailing)})))
      (is (nil? @runtime/current-runtime))
      (is (nil? (metadata/read-metadata canonical)))
      (spit unsupported "{:reload true}")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unsupported daemon config keys"
                            (runtime/start! db-file {:config-file (.getPath unsupported)})))
      (is (nil? @runtime/current-runtime))
      (is (nil? (metadata/read-metadata canonical)))
      (spit missing-load "{:load-files [\"missing.clj\"]}")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Daemon trusted load file not found"
                            (runtime/start! db-file {:config-file (.getPath missing-load)})))
      (is (nil? @runtime/current-runtime))
      (is (nil? (metadata/read-metadata canonical)))
      (spit bad-code "(throw (ex-info \"trusted code failed\" {}))")
      (spit bad-code-config "{:load-files [\"bad.clj\"]}")
      (is (thrown? Exception
                   (runtime/start! db-file {:config-file (.getPath bad-code-config)})))
      (is (nil? @runtime/current-runtime))
      (is (nil? (metadata/read-metadata canonical)))
      (finally
        (runtime/stop! @runtime/current-runtime)
        (metadata/delete! canonical)
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (.toFile dir))))))
