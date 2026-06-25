(ns todo.daemon-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [todo.daemon.api :as api]
            [todo.daemon.config :as daemon-config]
            [todo.daemon.metadata :as metadata]
            [todo.daemon.runtime :as runtime]
            [todo.db :as db]
            [todo.db-test :as db-test])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(defn delete-tree! [file]
  (doseq [f (reverse (file-seq file))]
    (.delete f)))

(defn temp-world []
  (let [file (java.io.File/createTempFile "tdx" "")]
    (.delete file)
    (.mkdirs file)
    (daemon-config/world (.getCanonicalPath file))))

(defn with-runtime
  ([f] (with-runtime nil f))
  ([start-options f]
   (let [db-file (db-test/temp-db-file)
         world (or (:world start-options) (temp-world))
         rt (runtime/start! db-file (assoc (or start-options {}) :world world))]
     (try
       (f rt db-file)
       (finally
         (runtime/stop! rt)
         (db-test/delete-sqlite-family! db-file)
         (delete-tree! (io/file (:config-dir world))))))))

(defn socket-request [rt operation arguments]
  (let [m (:metadata rt)
        req {"protocol_version" 1
             "request_id" "test-request"
             "daemon_id" (:nonce m)
             "operation" operation
             "arguments" arguments
             "options" {"format" "json"}}]
    (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                     (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
      (.write wrt (json/write-str req))
      (.newLine wrt)
      (.flush wrt)
      (json/read-str (.readLine rdr)))))

(deftest daemon-world-resolution
  (let [home (System/getProperty "user.home")
        config-home (or (System/getenv "XDG_CONFIG_HOME") (str home "/.config"))
        state-home (or (System/getenv "XDG_STATE_HOME") (str home "/.local/state"))
        data-home (or (System/getenv "XDG_DATA_HOME") (str home "/.local/share"))]
    (is (= {:config-dir (str config-home "/atom")
            :state-dir (str state-home "/atom")
            :data-dir (str data-home "/atom")
            :config-file (str config-home "/atom/config.json")
            :db-path (str data-home "/atom/tasks.sqlite")}
           (daemon-config/world))))
  (let [dir (.getCanonicalPath (.toFile (java.nio.file.Files/createTempDirectory "todo-world" (make-array java.nio.file.attribute.FileAttribute 0))))]
    (is (= {:config-dir dir
            :state-dir (str dir "/state")
            :data-dir (str dir "/data")
            :config-file (str dir "/config.json")
            :db-path (str dir "/data/tasks.sqlite")}
           (daemon-config/world dir)))))

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

(deftest daemon-query-registry-accepts-parameterized-in-queries
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (let [agent (api/add rt {:title "Agent" :attributes {:owner "agent"}})
            human (api/add rt {:title "Human" :attributes {:owner "human"}})
            owners-query {:params [:owners]
                          :where [:in [:attr :owner] [:param :owners]]}]
        (is (= {"owners" owners-query} (api/register-query rt 'owners owners-query)))
        (is (= [(:id agent)] (mapv :id (api/list-query rt :owners {:owners ["agent"]}))))
        (is (= #{(:id agent) (:id human)}
               (set (map :id (api/list-query rt :owners {:owners ["agent" "human"]})))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":in values must be a non-empty collection"
                              (api/list-query rt :owners {:owners "agent"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":in values must be a non-empty collection"
                              (api/list-query rt :owners {:owners []})))))))

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

(deftest runtime-uses-world-default-database-and-directories
  (let [world (temp-world)
        rt (runtime/start! nil {:world world})]
    (try
      (is (= (.getPath (.getCanonicalFile (io/file (:db-path world))))
             (get-in rt [:metadata :canonical-db-path])))
      (is (.isDirectory (io/file (:state-dir world))))
      (is (.isDirectory (io/file (:data-dir world))))
      (is (= (str (:state-dir world) "/daemon.sock") (get-in rt [:metadata :socket-path])))
      (is (= (str (:state-dir world) "/daemon.edn") (.getPath (metadata/metadata-file world))))
      (is (= (str (:state-dir world) "/daemon.json") (.getPath (metadata/json-metadata-file world))))
      (finally
        (runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-loads-default-init-clj
  (let [world (temp-world)
        init (io/file (:config-dir world) "init.clj")]
    (try
      (spit init "(require '[todo.daemon.api :as api] '[todo.daemon.runtime :as runtime]) (api/register-query @runtime/current-runtime 'trusted [:= :status \"todo\"])")
      (let [rt (runtime/start! nil {:world world})]
        (try
          (is (= {"trusted" [:= :status "todo"]} (api/queries rt)))
          (finally
            (runtime/stop! rt))))
      (finally
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-init-failures-do-not-publish-metadata
  (let [world (temp-world)
        init (io/file (:config-dir world) "init.clj")]
    (try
      (spit init "(throw (ex-info \"init failed\" {}))")
      (is (thrown? Exception (runtime/start! nil {:world world})))
      (is (nil? @runtime/current-runtime))
      (is (nil? (metadata/read-metadata world)))
      (is (false? (.exists (metadata/json-metadata-file world))))
      (is (false? (.exists (metadata/socket-file world))))
      (finally
        (runtime/stop! @runtime/current-runtime)
        (metadata/delete! world)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-metadata-records-canonical-loopback-identity
  (with-runtime
    (fn [rt db-file]
      (let [canonical (metadata/canonical-db-path db-file)
            status (runtime/status rt)
            file (:metadata-file rt)
            from-disk (edn/read-string (slurp file))
            json-disk (json/read-str (slurp (metadata/json-metadata-file (:metadata rt))))]
        (is (= canonical (:canonical-db-path status)))
        (is (= status from-disk))
        (is (= file (metadata/metadata-file (:metadata rt))))
        (is (pos-int? (get-in status [:endpoint :port])))
        (is (string? (:nonce status)))
        (is (= :nrepl (:transport status)))
        (is (= 1 (:protocol-version status)))
        (is (string? (:socket-path status)))
        (is (= canonical (get json-disk "database_path")))
        (is (= (:nonce status) (get json-disk "daemon_id")))
        (is (= (:socket-path status) (get json-disk "socket_path")))
        (is (= "127.0.0.1" (get-in json-disk ["nrepl" "host"])))
        (is (false? (metadata/stale-or-missing? status)))
        (is (= "127.0.0.1" (get-in status [:endpoint :host])))
        (is (.isLoopbackAddress (.getInetAddress (:server-socket (:server rt)))))))))

(deftest json-socket-dispatches-success-domain-and-protocol-errors
  (with-runtime
    (fn [rt _]
      (is (= true (get (socket-request rt "init" {}) "ok")))
      (let [added (socket-request rt "add" {"title" "Socket task" "status" "todo" "attributes" {"owner" "go"}})]
        (is (true? (get added "ok")))
        (is (= "Socket task" (get-in added ["result" "title"])))
        (is (= {"owner" "go"} (get-in added ["result" "attributes"]))))
      (let [target (socket-request rt "add" {"title" "Target" "status" "done" "attributes" {}})
            source (socket-request rt "add" {"title" "Source" "status" "todo" "attributes" {}})
            updated (socket-request rt "update" {"id" (get-in source ["result" "id"])
                                                  "title" nil
                                                  "status" nil
                                                  "attributes" nil
                                                  "edges" [{"type" "depends-on"
                                                            "to" (get-in target ["result" "id"])}]})]
        (is (true? (get updated "ok")))
        (is (= [(get-in target ["result" "id"])]
               (mapv :id (db/task-dependencies (:datasource rt) (get-in source ["result" "id"]))))))
      (let [missing (socket-request rt "update" {"id" "missing" "title" nil "status" nil "attributes" nil "edges" []})]
        (is (false? (get missing "ok")))
        (is (= "domain" (get-in missing ["error" "type"]))))
      (let [rejected (socket-request rt "queries" {})]
        (is (false? (get rejected "ok")))
        (is (= "protocol/operation-not-allowed" (get-in rejected ["error" "code"])))))))

(deftest json-socket-reports-uninitialized-database
  (with-runtime
    (fn [rt _]
      (let [response (socket-request rt "list" {})]
        (is (= false (get response "ok")))
        (is (= "domain" (get-in response ["error" "type"])))
        (is (= "database/not-initialized" (get-in response ["error" "code"])))
        (is (= "Database is not initialized; run `todo init` first"
               (get-in response ["error" "message"])))))))

(deftest json-socket-rejects-identity-mismatch
  (with-runtime
    (fn [rt _]
      (let [m (:metadata rt)
            req {"protocol_version" 1 "request_id" "bad-identity" "daemon_id" "wrong"
                 "operation" "stop" "arguments" {} "options" {"format" "json"}}]
        (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                         (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                    rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                    wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
          (.write wrt (json/write-str req))
          (.newLine wrt)
          (.flush wrt)
          (let [response (json/read-str (.readLine rdr))]
            (is (= false (get response "ok")))
            (is (= "protocol/identity-mismatch" (get-in response ["error" "code"]))))))
      (Thread/sleep 100)
      (is (some? @runtime/current-runtime)))))

(deftest json-socket-rejects-malformed-stop-without-shutdown
  (with-runtime
    (fn [rt _]
      (let [m (:metadata rt)
            req {"protocol_version" 1 "request_id" "bad-stop" "daemon_id" (:nonce m)
                 "operation" "stop" "arguments" {"force" true} "options" {"format" "json"}}]
        (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                         (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                    rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                    wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
          (.write wrt (json/write-str req))
          (.newLine wrt)
          (.flush wrt)
          (let [response (json/read-str (.readLine rdr))]
            (is (= false (get response "ok")))
            (is (= "protocol/malformed-request" (get-in response ["error" "code"]))))))
      (Thread/sleep 100)
      (is (some? @runtime/current-runtime)))))

(deftest json-socket-stop-cleans-runtime
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        rt (runtime/start! db-file {:world world})]
    (try
      (let [response (socket-request rt "stop" {})]
        (is (true? (get response "ok")))
        (is (= true (get-in response ["result" "stopping"]))))
      (Thread/sleep 250)
      (is (nil? @runtime/current-runtime))
      (is (false? (.exists (metadata/socket-file (:metadata rt)))))
      (is (false? (.exists (metadata/json-metadata-file (:metadata rt)))))
      (finally
        (runtime/stop! @runtime/current-runtime)
        (db-test/delete-sqlite-family! db-file)))))

(deftest metadata-shape-detects-missing-and-stale-files
  (let [db-file (db-test/temp-db-file)
        canonical (metadata/canonical-db-path db-file)
        world (temp-world)]
    (try
      (metadata/delete! world)
      (testing "missing metadata reads as nil and is stale"
        (is (nil? (metadata/read-metadata world)))
        (is (metadata/stale-or-missing? nil)))
      (testing "malformed metadata shape is stale"
        (is (metadata/stale-or-missing? {:pid 1 :canonical-db-path canonical})))
      (finally
        (metadata/delete! world)
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-refuses-orphaned-socket-without-metadata
  (let [world (temp-world)
        socket-file (metadata/socket-file world)]
    (try
      (.mkdirs (io/file (:state-dir world)))
      (spit socket-file "orphaned")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"cannot prove daemon world is stale"
                            (runtime/start! nil {:world world})))
      (is (.exists socket-file))
      (finally
        (metadata/delete! world)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-stop-removes-metadata
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        rt (runtime/start! db-file {:world world})]
    (try
      (runtime/stop! rt)
      (is (nil? (metadata/read-metadata world)))
      (is (false? (.exists (metadata/json-metadata-file (:metadata rt)))))
      (is (false? (.exists (metadata/socket-file (:metadata rt)))))
      (finally
        (runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)))))

(deftest runtime-rejects-duplicate-live-metadata
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        rt (runtime/start! db-file {:world world})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"already active"
                            (runtime/start! db-file {:world world})))
      (finally
        (runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)))))
