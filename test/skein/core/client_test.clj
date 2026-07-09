(ns skein.core.client-test
  "Tests for skein.core.client: routing CLI calls to a running weaver over nREPL."
  (:refer-clojure :exclude [list update])
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [skein.core.client :as client]
            [skein.api.hooks.alpha :as hooks]
            [skein.core.weaver.config :as daemon-config]
            [skein.core.weaver.metadata :as metadata]
            [skein.core.weaver.runtime :as runtime]
            [skein.core.db-test :as db-test]))
(defn test-world [config-dir]
  (daemon-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn delete-tree! [file]
  (doseq [f (reverse (file-seq file))]
    (.delete f)))

(defn temp-world []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      (.toPath (java.io.File. "/tmp"))
                      "tcw"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (test-world (.getCanonicalPath dir))))

(defn client-test-view [{:keys [params]}]
  {:client-view params})

;; Namespace-level on purpose: hooks are registered by symbol and resolved
;; to top-level vars, so capture state cannot be a per-test local. Reset by
;; the :each fixture below; the runner never splits a namespace across threads.
(def client-hook-contexts (atom []))

(use-fixtures :each (fn [f] (reset! client-hook-contexts []) (f)))

(defn client-capture-hook [ctx]
  (swap! client-hook-contexts conj ctx)
  :ok)

(defn client-normalize-hook [ctx]
  (swap! client-hook-contexts conj ctx)
  {:hook/value (:hook/value ctx)})

(defn with-runtime [f]
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        rt (runtime/start! db-file {:world world :publish? false})]
    (try
      (f rt world db-file)
      (finally
        (runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (java.io.File. (:config-dir world)))))))

(defn call-world [world op & args]
  (apply client/call-world (:config-dir world) {} op args))

(defn call-world-with-opts [world opts op & args]
  (apply client/call-world (:config-dir world) opts op args))

(deftest client-routes-each-unpublished-nrepl-endpoint-to-own-runtime
  (let [db-a (db-test/temp-db-file)
        db-b (db-test/temp-db-file)
        world-a (temp-world)
        world-b (temp-world)
        rt-a (runtime/start! db-a {:world world-a :publish? false})
        rt-b (runtime/start! db-b {:world world-b :publish? false})]
    (try
      (is (= {:database "initialized"} (call-world world-a :init)))
      (is (= {:database "initialized"} (call-world world-b :init)))
      (let [a (call-world world-a :add {:title "From A" :attributes {:runtime "a"}})
            b (call-world world-b :add {:title "From B" :attributes {:runtime "b"}})]
        (is (= [a] (call-world world-a :list)))
        (is (= [b] (call-world world-b :list)))
        (is (= (:metadata rt-a) (client/status-world (:config-dir world-a) {:state-dir (:state-dir world-a)})))
        (is (= (:metadata rt-b) (client/status-world (:config-dir world-b) {:state-dir (:state-dir world-b)}))))
      (finally
        (runtime/stop! rt-a)
        (runtime/stop! rt-b)
        (db-test/delete-sqlite-family! db-a)
        (db-test/delete-sqlite-family! db-b)
        (delete-tree! (java.io.File. (:config-dir world-a)))
        (delete-tree! (java.io.File. (:config-dir world-b)))))))

(deftest client-calls-running-daemon-and-returns-clojure-data
  (with-runtime
    (fn [_ world _db-file]
      (is (= {:database "initialized"} (call-world world :init)))
      (let [task (call-world world :add {:title "Bridge" :attributes {:owner "agent"}})]
        (is (= "Bridge" (:title task)))
        (is (= {:owner "agent"} (:attributes task)))
        (is (= task (call-world world :show (:id task))))
        (is (= [(:id task)] (mapv :id (call-world world :list))))
        (is (= [(:id task)] (mapv :id (call-world world :ready))))))))

(deftest client-finds-metadata-in-explicit-state-dir
  (with-runtime
    (fn [rt world _db-file]
      (is (not (.exists (java.io.File. (:config-dir world) "weaver.edn"))))
      (is (= (:metadata rt)
             (client/status-world (:config-dir world) {:state-dir (:state-dir world)}))))))

(deftest client-supersede-routes-to-weaver-and-preserves-domain-errors
  (with-runtime
    (fn [_ world _db-file]
      (call-world world :init)
      (let [old (call-world world :add {:title "Old"})
            replacement (call-world world :add {:title "New"})
            result (call-world world :supersede (:id old) (:id replacement))]
        (is (= "replaced" (get-in result [:old :after :state])))
        (is (= [(:id replacement) (:id old) "supersedes"]
               ((juxt :from_strand_id :to_strand_id :edge_type) (:supersedes-edge result))))
        (try
          (call-world world :supersede (:id old) (:id replacement))
          (is false "expected supersession domain error")
          (catch clojure.lang.ExceptionInfo e
            (is (= "Weaver API call failed" (ex-message e)))
            (is (= "Old strand is already replaced" (:weaver-message (ex-data e))))))))))

(deftest client-query-registry-calls-share-daemon-state
  (with-runtime
    (fn [_ world _db-file]
      (let [query-def {:params [:owner]
                       :where [:= [:attr :owner] [:param :owner]]}]
        (is (= {"mine" query-def} (call-world world :register-query :mine query-def)))
        (is (= query-def (call-world world :resolve-query 'mine)))
        (is (= {"mine" query-def} (call-world world :queries)))
        (is (= {"done" [:= :state "closed"]}
               (call-world world :load-queries {'done [:= :state "closed"]})))
        (is (= {"done" [:= :state "closed"]
                "mine" query-def}
               (call-world world :queries)))))))

(deftest client-mutations-thread-nrepl-request-context-to-hooks
  (with-runtime
    (fn [rt world _db-file]
      (call-world world :init)
      (reset! client-hook-contexts [])
      (hooks/register! rt :client-normalize #{:attributes/normalize} 'skein.core.client-test/client-normalize-hook {})
      (hooks/register! rt :client-add #{:strand/add-before-commit} 'skein.core.client-test/client-capture-hook {})
      (hooks/register! rt :client-update #{:strand/update-before-commit} 'skein.core.client-test/client-capture-hook {})
      (let [created (call-world world :add {:title "Hooked client" :attributes {:owner "agent"}})]
        (call-world world :update (:id created) {:attributes {:owner "agent" :phase "updated"}}))
      (is (= [:client-normalize :client-add :client-normalize :client-update]
             (mapv :hook/key @client-hook-contexts)))
      (is (= #{:nrepl} (set (map :request/source @client-hook-contexts))))
      (is (= [:add :add :update :update]
             (mapv :request/operation @client-hook-contexts))))))

(deftest client-routes-runtime-transformation-operations
  (with-runtime
    (fn [_ world _db-file]
      (call-world world :init)
      (let [agent (call-world world :add {:title "Agent" :attributes {:owner "agent"}})]
        (is (= {"mine" [:= [:attr :owner] "agent"]}
               (call-world world :register-query 'mine [:= [:attr :owner] "agent"])))
        (is (= [(:id agent)] (call-world world :query-ids 'mine {})))
        (is (= [agent] (call-world world :strands-by-ids [(:id agent)])))
        (is (= [] (call-world world :ancestor-root-ids [(:id agent)] {:where [:= [:attr :kind] "feature"]})))
        (is (= {:root-ids [(:id agent)] :strands [agent] :edges []}
               (call-world world :subgraph [(:id agent)])))
        (is (= {:name "client" :fn 'skein.core.client-test/client-test-view}
               (call-world world :register-view! 'client 'skein.core.client-test/client-test-view)))
        (is (= [{:name "client" :fn 'skein.core.client-test/client-test-view}]
               (call-world world :views)))
        (is (= {:client-view {:ok true}}
               (call-world world :view! 'client {:ok true})))))))

(deftest client-query-registry-preserves-domain-errors
  (with-runtime
    (fn [_ world _db-file]
      (try
        (call-world world :resolve-query :missing)
        (is false "expected missing query error")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Weaver API call failed" (ex-message e)))
          (is (= "Query not found" (:weaver-message (ex-data e))))
          (is (= :missing (get-in (ex-data e) [:weaver-data :query])))))
      (try
        (call-world world :load-queries {"mine" [:= :state "active"]})
        (is false "expected invalid query name error")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Weaver API call failed" (ex-message e)))
          (is (= "Query names must be simple symbols or keywords" (:weaver-message (ex-data e)))))))))

(deftest client-fails-loudly-for-missing-and-stale-metadata
  (let [db-file (db-test/temp-db-file)
        canonical (metadata/canonical-db-path db-file)
        world (temp-world)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"metadata is missing or stale"
                            (call-world world :list)))
      (metadata/publish! {:pid 1
                          :storage-kind :sqlite-file
                          :storage-label canonical
                          :canonical-db-path canonical
                          :state-dir (:state-dir world)})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"metadata is missing or stale"
                            (call-world world :list)))
      (finally
        (metadata/delete! world)
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (java.io.File. (:config-dir world)))))))

(deftest client-fails-loudly-for-unreachable-and-non-local-endpoints
  (let [db-file (db-test/temp-db-file)
        canonical (metadata/canonical-db-path db-file)
        world (temp-world)
        meta (metadata/metadata-shape {:pid 1
                                       :host "127.0.0.1"
                                       :port 1
                                       :storage-kind :sqlite-file
                                       :storage-label canonical
                                       :canonical-db-path canonical
                                       :nonce "unreachable"
                                       :world world})]
    (try
      (metadata/publish! meta)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unable to connect"
                            (call-world-with-opts world {:timeout-ms 100} :list)))
      (metadata/publish! (assoc-in meta [:endpoint :host] "203.0.113.1"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"not loopback"
                            (call-world-with-opts world {:timeout-ms 100} :list)))
      (finally
        (metadata/delete! world)
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (java.io.File. (:config-dir world)))))))

(deftest client-fails-loudly-for-wrong-daemon-identity
  (with-runtime
    (fn [rt world _db-file]
      (let [bad (assoc (:metadata rt) :nonce "wrong")]
        (metadata/publish! bad)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"identity does not match"
                              (call-world world :list)))))))

(deftest client-fails-loudly-for-daemon-thrown-domain-errors
  (with-runtime
    (fn [_ world _db-file]
      (call-world world :init)
      (try
        (call-world world :add {:title ""})
        (is false "expected daemon domain error")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Weaver API call failed" (ex-message e)))
          (is (= :skein.core.client/weaver-error (:type (ex-data e))))
          (is (= "Invalid strand" (:weaver-message (ex-data e))))
          (is (re-find #"non-blank" (:explain (:weaver-data (ex-data e))))))))))

(deftest client-fails-loudly-for-timeouts
  (with-runtime
    (fn [_ world _db-file]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"timed out"
                            (#'client/call-world* (:config-dir world)
                                                  {:timeout-ms 50}
                                                  :list
                                                  []
                                                  {:nrepl-client (fn [conn _timeout-ms] conn)
                                                   :nrepl-client-session (fn [client _timeout-ms] client)
                                                   :nrepl-message (fn [_session _message]
                                                                    (throw (java.net.SocketTimeoutException. "timed out")))}))))))
