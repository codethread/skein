(ns skein.weaver-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skein.weaver.api :as api]
            [skein.weaver.config :as weaver-config]
            [skein.weaver.metadata :as metadata]
            [skein.weaver.runtime :as runtime]
            [skein.weaver.socket :as socket]
            [skein.db :as db]
            [skein.db-test :as db-test])
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
    (weaver-config/world (.getCanonicalPath file))))

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

(defn test-view [{:keys [params]}]
  {:view :test :params params})

(defn replacement-view [{:keys [params]}]
  {:view :replacement :params params})

(defn write-view-lib! [config-dir lib ns-sym]
  (let [root (io/file config-dir "libs" (name lib))
        ns-path (-> (str ns-sym)
                    (.replace \- \_)
                    (.replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file (str "(ns " ns-sym ")\n"
                        "(defn render [{:keys [params]}] {:lib-view params})\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    root))

(defn socket-request [rt operation arguments]
  (let [m (:metadata rt)
        req {"protocol_version" 1
             "request_id" "test-request"
             "weaver_id" (:nonce m)
             "operation" operation
             "arguments" arguments
             "options" {}}]
    (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                     (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
      (.write wrt (json/write-str req))
      (.newLine wrt)
      (.flush wrt)
      (json/read-str (.readLine rdr)))))

(deftest weaver-world-resolution
  (let [home (System/getProperty "user.home")
        config-home (or (System/getenv "XDG_CONFIG_HOME") (str home "/.config"))
        state-home (or (System/getenv "XDG_STATE_HOME") (str home "/.local/state"))
        data-home (or (System/getenv "XDG_DATA_HOME") (str home "/.local/share"))]
    (is (= {:config-dir (str config-home "/skein")
            :state-dir (str state-home "/skein")
            :data-dir (str data-home "/skein")
            :config-file (str config-home "/skein/config.json")
            :db-path (str data-home "/skein/skein.sqlite")}
           (weaver-config/world))))
  (let [dir (.getCanonicalPath (.toFile (java.nio.file.Files/createTempDirectory "tdx" (make-array java.nio.file.attribute.FileAttribute 0))))]
    (is (= {:config-dir dir
            :state-dir (str dir "/state")
            :data-dir (str dir "/data")
            :config-file (str dir "/config.json")
            :db-path (str dir "/data/skein.sqlite")}
           (weaver-config/world dir)))))

(deftest weaver-api-delegates-to-db-and-normalizes-results
  (with-runtime
    (fn [rt _]
      (is (= {:database "initialized"} (api/init rt)))
      (let [design (api/add rt {:title "Design" :active false :attributes {:priority "high"}})
            docs (api/add rt {:title "Docs" :attributes {:owner "agent"}})]
        (is (= {:priority "high"} (:attributes design)))
        (api/update rt (:id docs) {:attributes {:phase "write"}
                                   :edges [{:type "depends-on" :to (:id design)}]})
        (is (= {:owner "agent" :phase "write"} (:attributes (api/show rt (:id docs)))))
        (is (= #{(:id design) (:id docs)} (set (map :id (api/list rt)))))
        (is (= [(:id docs)] (mapv :id (api/ready rt))))))))

(deftest weaver-query-registry-add-load-list-and-resolve
  (with-runtime
    (fn [rt _]
      (let [open-query [:= :active true]
            owner-query {:params [:owner]
                         :where [:= [:attr :owner] [:param :owner]]}]
        (is (= {"mine" owner-query} (api/register-query rt 'mine owner-query)))
        (is (= owner-query (api/resolve-query rt :mine)))
        (is (= {"mine" owner-query} (api/queries rt)))
        (is (= {"open" open-query} (api/load-queries rt {:open open-query})))
        (is (= {"mine" owner-query
                "open" open-query}
               (api/queries rt)))))))

(deftest weaver-query-registry-accepts-parameterized-in-queries
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

(deftest json-socket-public-operation-allowlist-stays-thin
  (is (= #{"init" "add" "update" "show" "burn" "list" "ready" "list-query" "ready-query" "status" "stop"}
         socket/allowed-operations)))

(deftest weaver-runtime-transformation-primitives
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (let [agent (api/add rt {:title "Agent" :attributes {:owner "agent"}})
            human (api/add rt {:title "Human" :attributes {:owner "human"}})
            feature (api/add rt {:title "Feature" :attributes {:kind "feature"}})]
        (api/update rt (:id feature) {:edges [{:type "parent-of" :to (:id agent)}
                                              {:type "parent-of" :to (:id human)}]})
        (api/register-query rt 'agent-owned {:params [:owner]
                                             :where [:= [:attr :owner] [:param :owner]]})
        (is (= [(:id agent)] (api/query-ids rt 'agent-owned {:owner "agent"})))
        (is (= [(:id human)] (api/query-ids rt [:= [:attr :owner] "human"] {})))
        (is (= [(:id human) (:id agent)]
               (mapv :id (api/strands-by-ids rt [(:id human) (:id agent) (:id human)]))))
        (is (= [(:id feature)] (api/ancestor-root-ids rt [(:id agent)])))
        (is (= #{(:id feature) (:id agent) (:id human)}
               (set (map :id (:strands (api/subgraph rt [(:id feature)]))))))))))

(deftest weaver-view-registry-operations
  (with-runtime
    (fn [rt _]
      (is (= {:name "daily" :fn 'skein.weaver-test/test-view}
             (api/register-view! rt 'daily 'skein.weaver-test/test-view)))
      (is (= [{:name "daily" :fn 'skein.weaver-test/test-view}]
             (api/views rt)))
      (is (= {:view :test :params {:owner "agent"}}
             (api/view! rt :daily {:owner "agent"})))
      (is (= {:name "daily" :fn 'skein.weaver-test/replacement-view}
             (api/register-view! rt :daily 'skein.weaver-test/replacement-view)))
      (is (= [{:name "daily" :fn 'skein.weaver-test/replacement-view}]
             (api/views rt)))
      (is (= {:view :replacement :params {}}
             (api/view! rt 'daily {})))
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            lib (symbol (str "view-" suffix))
            ns-sym (symbol (str "demo.view-" suffix))
            root (write-view-lib! (get-in rt [:metadata :config-dir]) lib ns-sym)]
        (.addURL ^clojure.lang.DynamicClassLoader (:library-classloader rt)
                 (.toURL (.toURI (io/file root "src"))))
        (api/register-view! rt 'synced-lib (symbol (str ns-sym) "render"))
        (is (= {:lib-view {:from :synced}}
               (api/view! rt 'synced-lib {:from :synced}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"View not found"
                            (api/view! rt 'missing {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fully qualified"
                            (api/register-view! rt 'bad 'unqualified)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (api/register-view! rt 'user/daily 'skein.weaver-test/test-view))))))

(deftest weaver-query-registry-fails-clearly
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
                            (api/register-query rt 'user/mine [:= :active true])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (api/load-queries rt {"mine" [:= :active true]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (api/load-queries rt {'user/mine [:= :active true]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown query operator"
                            (api/register-query rt :broken [:unknown :active true])))
      (api/register-query rt :ok [:= :active true])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown query operator"
                            (api/load-queries rt {:bad [:unknown :active true]})))
      (is (= {"ok" [:= :active true]} (api/queries rt))))))

(deftest weaver-api-update-preserves-domain-errors-and-rolls-back
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (let [source (api/add rt {:title "Source"})
            target (api/add rt {:title "Target"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Strand not found"
                              (api/update rt "missing" {:edges [{:type "depends-on" :to (:id target)}]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"non-blank"
                              (api/update rt (:id source) {:title ""
                                                           :edges [{:type "depends-on" :to (:id target)}]})))
        (is (empty? (db/execute! (:datasource rt) ["SELECT 1 FROM strand_edges WHERE from_strand_id = ?" (:id source)])))))))

(deftest runtime-uses-world-default-database-and-directories
  (let [world (temp-world)
        rt (runtime/start! nil {:world world})]
    (try
      (is (= (.getPath (.getCanonicalFile (io/file (:db-path world))))
             (get-in rt [:metadata :canonical-db-path])))
      (is (.isDirectory (io/file (:state-dir world))))
      (is (.isDirectory (io/file (:data-dir world))))
      (is (= (str (:state-dir world) "/weaver.sock") (get-in rt [:metadata :socket-path])))
      (is (= (str (:state-dir world) "/weaver.edn") (.getPath (metadata/metadata-file world))))
      (is (= (str (:state-dir world) "/weaver.json") (.getPath (metadata/json-metadata-file world))))
      (finally
        (runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-loads-default-init-clj
  (let [world (temp-world)
        init (io/file (:config-dir world) "init.clj")]
    (try
      (spit init "(require '[skein.weaver.api :as api] '[skein.weaver.runtime :as runtime]) (api/register-query @runtime/current-runtime 'trusted [:= :active true])")
      (let [rt (runtime/start! nil {:world world})]
        (try
          (is (= {"trusted" [:= :active true]} (api/queries rt)))
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
        (is (= (:nonce status) (get json-disk "weaver_id")))
        (is (= (:socket-path status) (get json-disk "socket_path")))
        (is (= "127.0.0.1" (get-in json-disk ["nrepl" "host"])))
        (is (false? (metadata/stale-or-missing? status)))
        (is (= "127.0.0.1" (get-in status [:endpoint :host])))
        (is (.isLoopbackAddress (.getInetAddress (:server-socket (:server rt)))))))))

(deftest json-socket-dispatches-success-domain-and-protocol-errors
  (with-runtime
    (fn [rt _]
      (is (= true (get (socket-request rt "init" {}) "ok")))
      (let [added (socket-request rt "add" {"title" "Socket task" "active" true "attributes" {"owner" "go"}})]
        (is (true? (get added "ok")))
        (is (= "Socket task" (get-in added ["result" "title"])))
        (is (= {"owner" "go"} (get-in added ["result" "attributes"]))))
      (let [target (socket-request rt "add" {"title" "Target" "active" false "attributes" {}})
            source (socket-request rt "add" {"title" "Source" "active" true "attributes" {}})
            updated (socket-request rt "update" {"id" (get-in source ["result" "id"])
                                                  "title" nil
                                                  "active" nil
                                                  "attributes" nil
                                                  "edges" [{"type" "depends-on"
                                                            "to" (get-in target ["result" "id"])}]})]
        (is (true? (get updated "ok")))
        (is (= [{:to_strand_id (get-in target ["result" "id"]) :edge_type "depends-on"}]
               (db/execute! (:datasource rt)
                            ["SELECT to_strand_id, edge_type FROM strand_edges WHERE from_strand_id = ?"
                             (get-in source ["result" "id"])]))))
      (let [missing (socket-request rt "update" {"id" "missing" "title" nil "active" nil "attributes" nil "edges" []})]
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
        (is (= "Database is not initialized; run `strand init` first"
               (get-in response ["error" "message"])))))))

(deftest json-socket-rejects-identity-mismatch
  (with-runtime
    (fn [rt _]
      (let [m (:metadata rt)
            req {"protocol_version" 1 "request_id" "bad-identity" "weaver_id" "wrong"
                 "operation" "stop" "arguments" {} "options" {}}]
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
            req {"protocol_version" 1 "request_id" "bad-stop" "weaver_id" (:nonce m)
                 "operation" "stop" "arguments" {"force" true} "options" {}}]
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
                            #"cannot prove weaver world is stale"
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
