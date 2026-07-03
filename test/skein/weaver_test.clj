(ns skein.weaver-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skein.api.batch.alpha :as batch]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as api]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.metadata :as metadata]
            [skein.core.weaver.runtime :as runtime]
            [skein.core.db :as db]
            [skein.core.db-test :as db-test])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(defn delete-tree! [file]
  (doseq [f (reverse (file-seq file))]
    (.delete f)))

(defn temp-world []
  (let [root (java.io.File/createTempFile "tdx" "")]
    (.delete root)
    (.mkdirs root)
    (let [workspace (io/file root "config")
          state-dir (io/file root "state")
          data-dir (io/file root "data")]
      (.mkdirs workspace)
      (weaver-config/world (.getCanonicalPath workspace)
                           (.getCanonicalPath state-dir)
                           (.getCanonicalPath data-dir)))))

(defn with-runtime
  ([f] (with-runtime nil f))
  ([start-options f]
   (let [db-file (db-test/temp-db-file)
         world (or (:world start-options) (temp-world))
         rt (runtime/start! db-file (assoc (or start-options {}) :world world :publish? false))]
     (try
       (runtime/with-runtime-binding rt #(f rt db-file))
       (finally
         (runtime/stop! rt)
         (db-test/delete-sqlite-family! db-file)
         (delete-tree! (io/file (:config-dir world))))))))

(defn test-view [{:keys [params]}]
  {:view :test :params params})

(defn test-op [{:op/keys [name argv]}]
  {:operation name :argv argv})

;; Namespace-level on purpose: handlers/hooks/patterns are registered by
;; symbol and resolved to top-level vars, so their capture state cannot be
;; per-test locals. The runner never splits a namespace across threads, and
;; the :each fixture below resets this state between tests.
(def delivered-events (atom []))
(def handler-started (atom (promise)))
(def handler-release (atom (promise)))
(def cleanup-events (atom []))

(defn capture-event [event]
  (swap! delivered-events conj event))

(defn slow-capture-event [event]
  (deliver @handler-started true)
  @@handler-release
  (swap! delivered-events conj event))

(defn failing-event [event]
  (throw (ex-info "handler failed" {:event event})))

(defn burn-temporary-children-on-inactive-parent [event]
  (when (and (= "active" (get-in event [:strand/before :state]))
             (= "closed" (get-in event [:strand/after :state])))
    (let [rt (current/runtime)
          root-id (:strand/id event)
          children (remove #(= root-id (:id %)) (:strands (api/subgraph rt [root-id])))
          temporary-child-ids (->> children
                                   (filter #(= "true" (get-in % [:attributes :temporary])))
                                   (mapv :id))]
      (when (seq temporary-child-ids)
        (api/burn-by-ids rt temporary-child-ids))
      (swap! cleanup-events conj {:root root-id :burned temporary-child-ids}))))

(defn wait-for-events [n]
  (loop [remaining 20]
    (cond
      (<= n (count @delivered-events)) @delivered-events
      (zero? remaining) @delivered-events
      :else (do
              (Thread/sleep 50)
              (recur (dec remaining))))))

(defn wait-until [pred]
  (loop [remaining 20]
    (cond
      (pred) true
      (zero? remaining) false
      :else (do
              (Thread/sleep 50)
              (recur (dec remaining))))))

(defn test-event [type id]
  {:event/type type
   :event/id id
   :event/at "2026-06-27T00:00:00Z"
   :event/source :test})

(def not-callable-event-handler 42)

(def hook-contexts (atom []))

(defn capture-hook [ctx]
  (swap! hook-contexts conj ctx)
  :ok)

(defn rejecting-hook [ctx]
  (swap! hook-contexts conj ctx)
  (throw (ex-info "mutation rejected" {:code "policy/rejected" :ctx ctx})))

(defn non-json-rejecting-hook [_ctx]
  (throw (ex-info "non-json rejected" {:code "policy/non-json"
                                        :hook-stage :strand/add-before-commit
                                        :nested {:reason :policy/non-json}
                                        :opaque (Object.)})))

(defn parse-story-points-hook [ctx]
  (swap! hook-contexts conj ctx)
  (let [attrs (:hook/value ctx)
        value (or (get attrs "storyPoints") (get attrs :storyPoints))]
    {:hook/value (cond-> (dissoc attrs "storyPoints" :storyPoints)
                   value (assoc :storyPoints (parse-long value)))}))

(defn add-normalized-flag-hook [ctx]
  {:hook/value (assoc (:hook/value ctx) :normalized true)})

(defn noop-normalize-hook [ctx]
  {:hook/value (:hook/value ctx)})

(defn nil-normalize-hook [_ctx]
  nil)

(defn non-wrapper-normalize-hook [ctx]
  (:hook/value ctx))

(defn invalid-attributes-hook [_ctx]
  {:hook/value {:opaque (Object.)}})

(defn rejecting-normalize-hook [_ctx]
  (throw (ex-info "normalize rejected" {:code "policy/rejected" :reason :test})))

(defn wrapping-rejecting-normalize-hook [_ctx]
  (throw (ex-info "wrapped" {:outer true}
                  (ex-info "inner" {:code "policy/inner"}))))

(def expected-hook-loader (atom nil))

(defn asserting-classloader-hook [ctx]
  (when-not (identical? @expected-hook-loader (.getContextClassLoader (Thread/currentThread)))
    (throw (ex-info "wrong classloader" {:code "test/wrong-classloader"})))
  {:hook/value (:hook/value ctx)})

(def not-callable-hook 42)

(defn replacement-view [{:keys [params]}]
  {:view :replacement :params params})

(def pattern-call-count (atom 0))

(use-fixtures :each
  (fn [f]
    (reset! delivered-events [])
    (reset! handler-started (promise))
    (reset! handler-release (promise))
    (reset! cleanup-events [])
    (reset! hook-contexts [])
    (reset! expected-hook-loader nil)
    (reset! pattern-call-count 0)
    (f)))

(defn test-pattern [{:keys [input]}]
  (let [title (or (:title input) (get input "title"))]
    [{:ref 'impl
      :title title
      :attributes {:kind "implementation"}}
     {:ref 'review
      :title (str "Review: " title)
      :attributes {:kind "review"}
      :edges [{:type "depends-on" :to 'impl}]}]))

(defn points-pattern [{:keys [input]}]
  [{:ref 'impl
    :title (:title input)
    :attributes {"storyPoints" "8"}}])

(defn bad-edge-pattern [_]
  [{:title "Should roll back"
    :edges [{:type "depends-on" :to "missing"}]}])

(defn counting-pattern [_]
  (swap! pattern-call-count inc)
  [{:title "Should not run"}])

(s/def ::title string?)
(s/def ::pattern-input (s/keys :req-un [::title]))
(s/def ::json-pattern-input #(string? (get % "title")))
(s/def ::never-valid (constantly false))

(defn write-view-lib! [workspace lib ns-sym]
  (let [root (io/file workspace "spools" (name lib))
        ns-path (-> (str ns-sym)
                    (.replace \- \_)
                    (.replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file (str "(ns " ns-sym ")\n"
                        "(defn render [{:keys [params]}] {:lib-view params})\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    root))

(defn socket-request-envelope [rt req]
  (let [m (:metadata rt)]
    (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                     (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
      (.write wrt (json/write-str req))
      (.newLine wrt)
      (.flush wrt)
      (json/read-str (.readLine rdr)))))

(defn socket-request [rt operation arguments]
  (let [m (:metadata rt)]
    (socket-request-envelope rt {"protocol_version" 1
                                 "request_id" "test-request"
                                 "weaver_id" (:nonce m)
                                 "operation" operation
                                 "arguments" arguments
                                 "options" {}})))

(deftest weaver-world-resolution
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Skein workspace selected"
                        (weaver-config/world)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Skein workspace selected"
                        (weaver-config/world nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"state-dir"
                        (weaver-config/world "/tmp/config" nil "/tmp/data")))
  (let [root (.getCanonicalFile (.toFile (java.nio.file.Files/createTempDirectory "tdx" (make-array java.nio.file.attribute.FileAttribute 0))))
        workspace (.getPath (io/file root "config"))
        state-dir (.getPath (io/file root "state"))
        data-dir (.getPath (io/file root "data"))]
    (is (= {:config-dir workspace
            :state-dir state-dir
            :data-dir data-dir
            :config-file (str workspace "/config.json")
            :db-path (str data-dir "/skein.sqlite")}
           (weaver-config/world workspace state-dir data-dir)))))

(deftest startup-uses-independent-xdg-world-dirs-and-initializes-storage
  (let [world (temp-world)
        rt (runtime/start! nil {:world world :publish? false})]
    (try
      (let [metadata (:metadata rt)]
        (is (= (:config-dir world) (:config-dir metadata)))
        (is (= (:state-dir world) (:state-dir metadata)))
        (is (= (:data-dir world) (:data-dir metadata)))
        (is (= (:db-path world) (:canonical-db-path metadata)))
        (is (.isFile (io/file (:state-dir world) "weaver.edn")))
        (is (.isFile (io/file (:state-dir world) "weaver.json")))
        (is (.exists (io/file (:state-dir world) "weaver.sock")))
        (is (.isFile (io/file (:data-dir world) "skein.sqlite")))
        (is (= ["depends-on" "parent-of" "supersedes"] (api/acyclic-relations rt)))
        (is (seq (db/execute! (:datasource rt) ["SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'strands'"]))))
      (finally
        (runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest runtime-owns-a-file-storage-handle
  (let [world (temp-world)
        rt (runtime/start! nil {:world world :publish? false})]
    (try
      (let [storage (:storage rt)]
        (is (= :sqlite-file (:storage-kind storage)))
        (is (= (:db-path world) (:canonical-db-path storage)))
        (is (= (:canonical-db-path storage) (:storage-label storage)))
        (is (= (:datasource rt) (:connectable storage)))
        (is (nil? (:close-fn storage))))
      (finally
        (runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest memory-storage-runtime-serves-weaver-api-without-a-db-file
  (let [world (temp-world)
        rt (runtime/start! nil {:world world :publish? false :storage :sqlite-memory})]
    (try
      (is (= :sqlite-memory (get-in rt [:storage :storage-kind])))
      (is (nil? (get-in rt [:storage :canonical-db-path])))
      (is (false? (.exists (io/file (:data-dir world) "skein.sqlite"))))
      (testing "metadata and status report memory storage without a fake path"
        (is (= :sqlite-memory (get-in rt [:metadata :storage-kind])))
        (is (nil? (get-in rt [:metadata :canonical-db-path])))
        (is (false? (metadata/stale-or-missing? (:metadata rt))))
        (let [json-disk (json/read-str (slurp (metadata/json-metadata-file (:metadata rt))))]
          (is (= "sqlite-memory" (get json-disk "database_kind")))
          (is (= (get-in rt [:metadata :storage-label]) (get json-disk "database_label")))
          (is (contains? json-disk "database_path"))
          (is (nil? (get json-disk "database_path"))))
        (let [status (socket-request rt "status" {})]
          (is (true? (get status "ok")))
          (is (= "sqlite-memory" (get-in status ["result" "database_kind"])))
          (is (nil? (get-in status ["result" "database_path"])))))
      (let [strand (api/add rt {:title "Mem strand" :attributes {:owner "mem"}})]
        (is (= [(:id strand)] (mapv :id (api/ready rt)))))
      (testing "concurrent weaver API calls at test scale"
        (let [ids (->> (range 10)
                       (mapv (fn [i] (future (:id (api/add rt {:title (str "c" i)})))))
                       (mapv deref))]
          (is (= 10 (count (distinct ids))))
          (is (= 11 (count (api/list rt))))))
      (finally
        (runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))
    (testing "storage is destroyed with the runtime"
      (is (thrown? java.sql.SQLException (db/all-strands (:datasource rt)))))))

(deftest storage-selection-fails-loudly-on-bad-input
  (let [world (temp-world)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not take a database file"
                            (runtime/start! (db-test/temp-db-file)
                                            {:world world :publish? false :storage :sqlite-memory})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown weaver storage kind"
                            (runtime/start! nil {:world world :publish? false :storage :postgres})))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest unpublished-runtimes-coexist-with-isolated-storage-and-registries
  (let [world-a (temp-world)
        world-b (temp-world)
        db-a (db-test/temp-db-file)
        db-b (db-test/temp-db-file)
        rt-a (runtime/start! db-a {:world world-a :publish? false})
        rt-b (runtime/start! db-b {:world world-b :publish? false})]
    (try
      (api/init rt-a)
      (api/init rt-b)
      (api/register-query rt-a 'mine [:= [:attr :owner] "a"])
      (api/register-query rt-b 'mine [:= [:attr :owner] "b"])
      (let [a (api/add rt-a {:title "A" :attributes {:owner "a"}})
            b (api/add rt-b {:title "B" :attributes {:owner "b"}})]
        (is (= [(:id a)] (mapv :id (api/list-query rt-a 'mine {}))))
        (is (= [(:id b)] (mapv :id (api/list-query rt-b 'mine {}))))
        (is (nil? (api/show rt-a (:id b))))
        (is (nil? (api/show rt-b (:id a)))))
      (finally
        (runtime/stop! rt-a)
        (runtime/stop! rt-b)
        (db-test/delete-sqlite-family! db-a)
        (db-test/delete-sqlite-family! db-b)
        (delete-tree! (io/file (:config-dir world-a) ".."))
        (delete-tree! (io/file (:config-dir world-b) ".."))))))

(deftest unpublished-startup-config-resolves-current-runtime
  (let [world (temp-world)
        marker (io/file (:config-dir world) "startup-runtime.edn")]
    (spit (io/file (:config-dir world) "init.clj")
          (str "(require '[skein.api.current.alpha :as current])\n"
               "(spit " (pr-str (str marker)) " (pr-str (get-in (current/runtime) [:metadata :nonce])))\n"))
    (let [rt (runtime/start! nil {:world world :publish? false})]
      (try
        (is (= (get-in rt [:metadata :nonce]) (read-string (slurp marker))))
        (finally
          (runtime/stop! rt)
          (delete-tree! (io/file (:config-dir world) "..")))))))

(deftest startup-fails-clearly-when-required-main-dirs-are-missing
  (let [parse-main-args (ns-resolve 'skein.core.weaver.runtime 'parse-main-args)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--workspace is required"
                          (parse-main-args [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--state-dir is required"
                          (parse-main-args ["--workspace" "/tmp/c" "--data-dir" "/tmp/d"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--data-dir is required"
                          (parse-main-args ["--workspace" "/tmp/c" "--state-dir" "/tmp/s"])))))

(deftest startup-failing-init-aborts-before-ready-metadata
  (let [world (temp-world)]
    (try
      (spit (io/file (:config-dir world) "init.clj")
            "(throw (ex-info \"init boom\" {:source :shared}))\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"startup file failed"
                            (runtime/start! nil {:world world :publish? false})))
      (is (nil? (metadata/read-metadata world)))
      (is (not (.exists (io/file (:state-dir world) "weaver.json"))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest startup-loads-layered-init-files-in-order
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        order-file (io/file (:config-dir world) "startup-order.edn")]
    (try
      (spit (io/file (:config-dir world) "init.clj")
            (str "(spit " (pr-str (str order-file)) " (pr-str [:shared]))\n:shared\n"))
      (spit (io/file (:config-dir world) "init.local.clj")
            (str "(let [xs (read-string (slurp " (pr-str (str order-file)) "))]\n"
                 "  (spit " (pr-str (str order-file)) " (pr-str (conj xs :local))))\n:local\n"))
      (let [rt (runtime/start! db-file {:world world :publish? false})]
        (try
          (is (= [:shared :local] (read-string (slurp order-file))))
          (finally
            (runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest startup-skips-missing-local-init-file
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        marker (io/file (:config-dir world) "shared.edn")]
    (try
      (spit (io/file (:config-dir world) "init.clj")
            (str "(spit " (pr-str (str marker)) " (pr-str :shared))\n"))
      (let [rt (runtime/start! db-file {:world world :publish? false})]
        (try
          (is (= :shared (read-string (slurp marker))))
          (finally
            (runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest startup-fails-loudly-when-local-init-file-fails
  (let [db-file (db-test/temp-db-file)
        world (temp-world)]
    (try
      (spit (io/file (:config-dir world) "init.local.clj")
            "(throw (ex-info \"local boom\" {:source :local}))\n")
      (try
        (runtime/start! db-file {:world world :publish? false})
        (is false "expected startup failure")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Selected workspace startup file failed to load" (ex-message e)))
          (is (= (.getCanonicalPath (io/file (:config-dir world) "init.local.clj"))
                 (:file (ex-data e))))
          (is (nil? (metadata/read-metadata world)))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest reload-loads-layered-init-files-in-order
  (with-runtime
    (fn [rt _]
      (let [workspace (get-in rt [:metadata :config-dir])
            order-file (io/file workspace "reload-order.edn")]
        (spit (io/file workspace "init.clj")
              (str "(spit " (pr-str (str order-file)) " (pr-str [:shared]))\n:shared\n"))
        (spit (io/file workspace "init.local.clj")
              (str "(let [xs (read-string (slurp " (pr-str (str order-file)) "))]\n"
                   "  (spit " (pr-str (str order-file)) " (pr-str (conj xs :local))))\n:local\n"))
        (is (= {:status :loaded
                :files [{:name "init.clj"
                         :file (.getCanonicalPath (io/file workspace "init.clj"))
                         :return :shared}
                        {:name "init.local.clj"
                         :file (.getCanonicalPath (io/file workspace "init.local.clj"))
                         :return :local}]
                :returns [:shared :local]}
               (api/reload-config! rt)))
        (is (= [:shared :local] (read-string (slurp order-file))))))))

(deftest reload-failing-local-init-fails-loudly-without-shared-only-state
  (with-runtime
    (fn [rt _]
      (let [workspace (get-in rt [:metadata :config-dir])]
        (api/register-query rt 'prior [:= [:attr :owner] "prior"])
        (reset! delivered-events [])
        (api/register-event-handler! rt :prior #{:strand/added} 'skein.weaver-test/capture-event {})
        (api/register-hook! rt :prior #{:payload/received} 'skein.weaver-test/capture-hook {})
        (spit (io/file workspace "init.clj")
              "(require '[skein.api.current.alpha :as current]\n         '[skein.api.weaver.alpha :as api]\n         '[skein.api.events.alpha :as events])\n(let [rt (current/runtime)]\n  (api/register-query rt 'shared [:= [:attr :owner] \"shared\"])\n  (events/register! rt :shared #{:strand/added} 'skein.weaver-test/capture-event)\n  (api/enqueue-event! rt {:event/type :strand/added :event/id \"shared-only\" :event/at \"2026-06-29T00:00:00Z\" :event/source :test}))\n")
        (spit (io/file workspace "init.local.clj")
              "(throw (ex-info \"local boom\" {:source :local}))\n")
        (try
          (api/reload-config! rt)
          (is false "expected reload failure")
          (catch clojure.lang.ExceptionInfo e
            (is (= (.getCanonicalPath (io/file workspace "init.local.clj"))
                   (:file (ex-data e))))))
        (is (= {} (api/queries rt)))
        (is (= [] (api/event-handlers rt)))
        (is (= [] (api/hooks rt)))
        (is (= ["help"] (mapv :name (api/ops rt))))
        (is (not (wait-until #(some (fn [event] (= "shared-only" (:event/id event)))
                                    @delivered-events))))))))

(deftest reload-layering-clears-events-and-hooks-before-local-overlay
  (with-runtime
    (fn [rt _]
      (let [workspace (get-in rt [:metadata :config-dir])]
        (api/register-event-handler! rt :stale #{:strand/added} 'skein.weaver-test/capture-event {})
        (api/register-hook! rt :stale #{:payload/received} 'skein.weaver-test/capture-hook {})
        (api/register-event-handler! rt :fails #{:strand/added} 'skein.weaver-test/failing-event {})
        (api/enqueue-event! rt (test-event :strand/added "before-reload"))
        (Thread/sleep 250)
        (is (seq (api/recent-event-failures rt)))
        (spit (io/file workspace "init.clj")
              "(require '[skein.api.current.alpha :as current]\n         '[skein.api.events.alpha :as events]\n         '[skein.api.hooks.alpha :as hooks])\n(let [rt (current/runtime)]\n  (events/register! rt :shared #{:strand/added} 'skein.weaver-test/capture-event)\n  (hooks/register! rt :shared #{:payload/received} 'skein.weaver-test/capture-hook))\n")
        (spit (io/file workspace "init.local.clj")
              "(require '[skein.api.current.alpha :as current]\n         '[skein.api.events.alpha :as events]\n         '[skein.api.hooks.alpha :as hooks])\n(let [rt (current/runtime)]\n  (events/register! rt :local #{:strand/updated} 'skein.weaver-test/capture-event)\n  (hooks/register! rt :local #{:strand/add-before-commit} 'skein.weaver-test/capture-hook))\n")
        (api/reload-config! rt)
        (is (= #{:shared :local} (set (mapv :key (api/event-handlers rt)))))
        (is (= #{:shared :local} (set (mapv :key (api/hooks rt)))))
        (is (= [] (api/recent-event-failures rt)))))))

(deftest weaver-api-delegates-to-db-and-normalizes-results
  (with-runtime
    (fn [rt _]
      (is (= {:database "initialized"} (api/init rt)))
      (let [design (api/add rt {:title "Design" :state "closed" :attributes {:priority "high"}})
            docs (api/add rt {:title "Docs" :attributes {:owner "agent"}})]
        (is (= ["depends-on" "parent-of" "supersedes"] (api/acyclic-relations rt)))
        (is (= {:relation "blocks" :acyclic true} (api/declare-acyclic-relation! rt "blocks")))
        (is (= ["blocks" "depends-on" "parent-of" "supersedes"] (api/acyclic-relations rt)))
        (is (= {:priority "high"} (:attributes design)))
        (api/update rt (:id docs) {:attributes {:phase "write"}
                                   :edges [{:type "depends-on" :to (:id design)}]})
        (is (= {:owner "agent" :phase "write"} (:attributes (api/show rt (:id docs)))))
        (is (= #{(:id design) (:id docs)} (set (map :id (api/list rt)))))
        (is (= [(:id docs)] (mapv :id (api/ready rt))))))))

(deftest weaver-event-runtime-registers-dispatches-and-records-failures
  (with-runtime
    (fn [rt _]
      (reset! delivered-events [])
      (let [entry (api/register-event-handler! rt :capture #{:strand/added} 'skein.weaver-test/capture-event {:purpose :test})]
        (is (= {:key :capture
                :types #{:strand/added}
                :fn 'skein.weaver-test/capture-event
                :metadata {:purpose :test}}
               entry))
        (is (= [entry] (api/event-handlers rt)))
        (is (= {:key :capture
                :types #{:strand/updated}
                :fn 'skein.weaver-test/capture-event
                :metadata {:purpose :replacement}}
               (api/register-event-handler! rt :capture #{:strand/updated} 'skein.weaver-test/capture-event {:purpose :replacement})))
        (is (= [] @delivered-events))
        (api/enqueue-event! rt (test-event :strand/added "ignored"))
        (Thread/sleep 100)
        (is (= [] @delivered-events))
        (api/enqueue-event! rt (test-event :strand/updated "delivered"))
        (Thread/sleep 250)
        (is (= [(test-event :strand/updated "delivered")] @delivered-events))
        (api/register-event-handler! rt :fails #{:strand/updated} 'skein.weaver-test/failing-event {})
        (api/enqueue-event! rt (test-event :strand/updated "fails"))
        (Thread/sleep 250)
        (let [failure (last (api/recent-event-failures rt))]
          (is (= :fails (:handler/key failure)))
          (is (= 'skein.weaver-test/failing-event (:handler/fn failure)))
          (is (= "fails" (:event/id failure)))
          (is (= :strand/updated (:event/type failure)))
          (is (= "handler failed" (:exception/message failure)))
          (is (string? (:failed/at failure))))
        (is (= {:unregistered :capture} (api/unregister-event-handler! rt :capture)))
        (is (= [:fails] (mapv :key (api/event-handlers rt))))))))

(deftest weaver-supersession-emits-semantic-event
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:strand/superseded} 'skein.weaver-test/capture-event {})
      (let [old (api/add rt {:title "Old"})
            replacement (api/add rt {:title "Replacement"})
            dependent (api/add rt {:title "Dependent"})]
        (api/update rt (:id dependent) {:edges [{:type "depends-on" :to (:id old)}]})
        (reset! delivered-events [])
        (let [result (api/supersede rt (:id old) (:id replacement))
              event (first (wait-for-events 1))]
          (is (= "replaced" (get-in result [:old :after :state])))
          (is (= (:id replacement) (:replacement-id result)))
          (is (= :strand/superseded (:event/type event)))
          (is (= (:id old) (:strand/old-id event)))
          (is (= (:id replacement) (:strand/replacement-id event)))
          (is (= "active" (get-in event [:strand/before :state])))
          (is (= "replaced" (get-in event [:strand/after :state])))
          (is (= (:supersedes-edge result) (:supersession/supersedes-edge event)))
          (is (= (:rewired-dependencies result) (:supersession/rewired-dependencies event))))))))

(deftest strand-supersede-pre-commit-hook-inspects-and-rejects-atomically
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:strand/superseded} 'skein.weaver-test/capture-event {})
      (api/register-hook! rt :capture-supersede #{:strand/supersede-before-commit} 'skein.weaver-test/capture-hook {})
      (let [old (api/add rt {:title "Old"})
            replacement (api/add rt {:title "Replacement"})
            dependent (api/add rt {:title "Dependent"})]
        (api/update rt (:id dependent) {:edges [{:type "depends-on" :to (:id old) :attributes {:reason "old"}}]})
        (reset! delivered-events [])
        (let [result (api/supersede rt (:id old) (:id replacement))
              event (first (wait-for-events 1))
              context (last @hook-contexts)]
          (is (= :strand/supersede-before-commit (:hook/type context)))
          (is (= :weaver-api (:request/source context)))
          (is (= :supersede (:request/operation context)))
          (is (= :strand/supersede (:mutation/operation context)))
          (is (= (:id old) (:strand/old-id context)))
          (is (= (:id replacement) (:strand/replacement-id context)))
          (is (= (get-in result [:old :before]) (:strand/before context)))
          (is (= (get-in result [:old :after]) (:strand/after context)))
          (is (= (:supersedes-edge result) (:supersession/supersedes-edge context)))
          (is (= (:rewired-dependencies result) (:supersession/rewired-dependencies context)))
          (is (= {:reason "old"} (get-in result [:rewired-dependencies 0 :deleted-edge :attributes])))
          (is (= {:reason "old"} (get-in result [:rewired-dependencies 0 :edge :attributes])))
          (is (= :strand/superseded (:event/type event)))
          (is (= (:supersedes-edge result) (:supersession/supersedes-edge event))))
        (api/unregister-hook! rt :capture-supersede)
        (let [reject-old (api/add rt {:title "Reject old"})
              reject-replacement (api/add rt {:title "Reject replacement"})
              reject-dependent (api/add rt {:title "Reject dependent"})]
          (api/update rt (:id reject-dependent) {:edges [{:type "depends-on" :to (:id reject-old) :attributes {:reason "rollback"}}]})
          (reset! delivered-events [])
          (api/register-hook! rt :reject-supersede #{:strand/supersede-before-commit} 'skein.weaver-test/rejecting-hook {})
          (try
            (api/supersede rt (:id reject-old) (:id reject-replacement))
            (is false "expected supersede hook rejection")
            (catch clojure.lang.ExceptionInfo e
              (is (= "hook/failed" (:code (ex-data e))))
              (is (= :strand/supersede-before-commit (:hook/type (ex-data e))))
              (is (= :reject-supersede (:hook/key (ex-data e))))
              (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
          (is (= "active" (:state (api/show rt (:id reject-old)))))
          (is (= [{:from_strand_id (:id reject-dependent)
                   :to_strand_id (:id reject-old)
                   :edge_type "depends-on"
                   :attributes {:reason "rollback"}}]
                 (mapv #(update % :attributes db/<-json)
                       (db/execute! (:datasource rt)
                                    ["SELECT from_strand_id, to_strand_id, edge_type, attributes
                                      FROM strand_edges
                                      WHERE from_strand_id = ?
                                      ORDER BY to_strand_id, edge_type"
                                     (:id reject-dependent)]))))
          (is (empty? (db/execute! (:datasource rt)
                                   ["SELECT 1 FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = 'supersedes'"
                                    (:id reject-replacement) (:id reject-old)])))
          (is (empty? @delivered-events)))))))

(deftest strand-supersede-api-validation-failures-stay-loud-and-ungated
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:strand/superseded} 'skein.weaver-test/capture-event {})
      (api/register-hook! rt :capture-supersede #{:strand/supersede-before-commit} 'skein.weaver-test/capture-hook {})
      (let [old (api/add rt {:title "Old"})
            replacement (api/add rt {:title "Replacement"})
            closed-replacement (api/add rt {:title "Closed" :state "closed"})
            dependent (api/add rt {:title "Dependent"})]
        (api/update rt (:id dependent) {:edges [{:type "depends-on" :to (:id old)}]})
        (api/update rt (:id replacement) {:edges [{:type "depends-on" :to (:id dependent)}]})
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (let [before (db-test/graph-snapshot (:datasource rt))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Replacement strand must be active"
                                (api/supersede rt (:id old) (:id closed-replacement))))
          (is (empty? @hook-contexts))
          (is (empty? @delivered-events))
          (is (= before (db-test/graph-snapshot (:datasource rt)))))
        (let [before (db-test/graph-snapshot (:datasource rt))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (api/supersede rt (:id old) (:id replacement))))
          (is (empty? @hook-contexts))
          (is (empty? @delivered-events))
          (is (= before (db-test/graph-snapshot (:datasource rt)))))))))

(deftest weaver-strand-mutations-emit-events-after-success
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:strand/added :strand/updated :strand/burned} 'skein.weaver-test/capture-event {})
      (let [added (api/add rt {:title "Evented" :attributes {:owner "agent"}})
            add-event (first (wait-for-events 1))]
        (is (= :strand/added (:event/type add-event)))
        (is (string? (:event/id add-event)))
        (is (string? (:event/at add-event)))
        (is (= :skein.api.weaver.alpha (:event/source add-event)))
        (is (= (:id added) (:strand/id add-event)))
        (is (= added (:strand add-event)))
        (let [updated (api/update rt (:id added) {:state "closed" :attributes {:phase "done"}})
              update-event (second (wait-for-events 2))]
          (is (= :strand/updated (:event/type update-event)))
          (is (= (:id added) (:strand/id update-event)))
          (is (= {:state "closed" :attributes {:phase "done"}} (:strand/patch update-event)))
          (is (= "active" (get-in update-event [:strand/before :state])))
          (is (= {:owner "agent"} (get-in update-event [:strand/before :attributes])))
          (is (= "closed" (get-in update-event [:strand/after :state])))
          (is (= {:owner "agent" :phase "done"} (get-in update-event [:strand/after :attributes])))
          (is (= updated (:strand/after update-event))))
        (let [edge-target (api/add rt {:title "Target"})]
          (reset! delivered-events [])
          (let [edge-patch {:edges [{:type "depends-on" :to (:id edge-target)}]}
                result (api/update rt (:id added) edge-patch)
                update-event (first (filter #(= :strand/updated (:event/type %)) (wait-for-events 2)))]
            (is (= result (:strand/after update-event)))
            (is (= edge-patch (:strand/patch update-event)))))
        (reset! delivered-events [])
        (let [pre-burn (api/show rt (:id added))
              burn-result (api/burn-by-id rt (:id added))
              burn-event (first (wait-for-events 1))]
          (is (= {:burned [(:id added)] :count 1} burn-result))
          (is (= :strand/burned (:event/type burn-event)))
          (is (= [(:id added)] (:strand/requested-ids burn-event)))
          (is (= [(:id added)] (:strand/burned-ids burn-event)))
          (is (= [pre-burn] (:strand/before burn-event))))))))

(deftest trusted-handler-burns-temporary-children-after-parent-update
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! cleanup-events [])
      (api/register-event-handler! rt :cleanup-temporary #{:strand/updated}
                                   'skein.weaver-test/burn-temporary-children-on-inactive-parent
                                   {:purpose :integration-cleanup})
      (let [parent (api/add rt {:title "Parent"})
            temporary-child (api/add rt {:title "Temporary child" :attributes {:temporary "true"}})
            durable-child (api/add rt {:title "Durable child" :attributes {:temporary "false"}})
            unrelated-temporary (api/add rt {:title "Unrelated temporary" :attributes {:temporary "true"}})]
        (api/update rt (:id parent) {:edges [{:type "parent-of" :to (:id temporary-child)}
                                             {:type "parent-of" :to (:id durable-child)}]})
        (api/update rt (:id parent) {:state "closed"})
        (is (wait-until #(= [{:root (:id parent) :burned [(:id temporary-child)]}]
                            @cleanup-events)))
        (is (nil? (api/show rt (:id temporary-child))))
        (is (= (:id durable-child) (:id (api/show rt (:id durable-child)))))
        (is (= (:id unrelated-temporary) (:id (api/show rt (:id unrelated-temporary)))))))))

(deftest event-handler-slowness-and-failure-do-not-fail-original-mutation
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! delivered-events [])
      (reset! handler-started (promise))
      (reset! handler-release (promise))
      (api/register-event-handler! rt :slow #{:strand/updated} 'skein.weaver-test/slow-capture-event {})
      (api/register-event-handler! rt :fails #{:strand/updated} 'skein.weaver-test/failing-event {})
      (let [strand (api/add rt {:title "Slow handler target"})
            update-result (future (api/update rt (:id strand) {:state "closed"}))]
        (try
          (is (deref @handler-started 1000 false))
          (let [updated (deref update-result 1000 ::mutation-blocked)]
            (is (not= ::mutation-blocked updated))
            (is (= "closed" (:state updated))))
          (is (= [] @delivered-events))
          (deliver @handler-release true)
          (is (wait-until #(= 1 (count @delivered-events))))
          (is (wait-until #(some (fn [failure]
                                    (= :fails (:handler/key failure)))
                                  (api/recent-event-failures rt))))
          (finally
            (deliver @handler-release true)))))))

(deftest event-queue-capacity-and-reload-semantics
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! delivered-events [])
      (reset! handler-started (promise))
      (reset! handler-release (promise))
      (api/register-event-handler! rt :slow #{:x} 'skein.weaver-test/slow-capture-event {})
      (api/enqueue-event! rt (test-event :x "started"))
      (is (deref @handler-started 1000 false))
      (doseq [n (range runtime/event-queue-capacity)]
        (api/enqueue-event! rt (test-event :x (str "queued-" n))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"queue is full"
                            (api/enqueue-event! rt (test-event :x "full"))))
      (let [init (io/file (get-in rt [:metadata :config-dir]) "init.clj")]
        (spit init "(require '[skein.api.current.alpha :as current]\n         '[skein.api.events.alpha :as events])\n(let [rt (current/runtime)]\n  (events/register! rt :after-reload #{:x} 'skein.weaver-test/capture-event))\n")
        (api/reload-config! rt)
        (deliver @handler-release true)
        (is (= [:after-reload] (mapv :key (api/event-handlers rt))))
        (is (= [] (api/recent-event-failures rt)))
        (is (not (wait-until #(some (fn [event] (= "queued-0" (:event/id event)))
                                    @delivered-events))))
        (api/enqueue-event! rt (test-event :x "after-reload"))
        (is (wait-until #(some (fn [event] (= "after-reload" (:event/id event)))
                              @delivered-events)))))))

(deftest weaver-apply-batch-emits-batch-event-before-compatibility-fanout
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (let [existing-b (api/add rt {:title "Existing B" :attributes {:owner "agent"}})
            existing-a (api/add rt {:title "Existing A" :attributes {:owner "agent"}})
            burned (api/add rt {:title "Burned"})]
        (reset! delivered-events [])
        (api/register-event-handler! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                                     'skein.weaver-test/capture-event {})
        (let [result (api/apply-batch rt {:refs {:existing-b (:id existing-b)
                                                 :existing-a (:id existing-a)
                                                 :burned (:id burned)}
                                          :strands [{:ref :existing-b
                                                     :state "closed"
                                                     :attributes {:phase "done-b"}}
                                                    {:ref :created-z
                                                     :title "Created Z"
                                                     :attributes {:kind "z"}}
                                                    {:ref :existing-a
                                                     :attributes {:phase "done-a"}}
                                                    {:ref :created-a
                                                     :title "Created A"
                                                     :attributes {:kind "a"}}]
                                          :edges [{:op :upsert
                                                   :from :created-z
                                                   :to :existing-b
                                                   :type "depends-on"
                                                   :attributes {:reason "test"}}]
                                          :burn [:burned]})
              events (wait-for-events 6)
              [batch-event add-z-event add-a-event update-b-event update-a-event burn-event] events
              batch-id (:batch/id batch-event)
              batch-keys (fn [event]
                           (set (filter #(= "batch" (namespace %)) (keys event))))]
          (is (= [:batch/applied :strand/added :strand/added :strand/updated :strand/updated :strand/burned]
                 (mapv :event/type events)))
          (is (string? batch-id))
          (is (= (repeat 5 batch-id)
                 (map :batch/id [add-z-event add-a-event update-b-event update-a-event burn-event])))
          (is (= #{:refs :created :updated :burned :edges} (set (keys result))))
          (is (= #{:existing-b :existing-a :burned :created-z :created-a} (set (keys (:refs result)))))
          (is (= 2 (count (:created result))))
          (is (= 2 (count (:updated result))))
          (is (= 1 (count (:burned result))))
          (is (= 1 (count (:edges result))))
          (is (= (:refs result) (:batch/refs batch-event)))
          (is (= (:created result) (:batch/created batch-event)))
          (is (= (:updated result) (:batch/updated batch-event)))
          (is (= (:burned result) (:batch/burned batch-event)))
          (is (= (:edges result) (:batch/edges batch-event)))
          (is (= #{:batch/id} (batch-keys add-z-event) (batch-keys add-a-event)
                 (batch-keys update-b-event) (batch-keys update-a-event) (batch-keys burn-event)))
          (is (= (mapv :id (:created result))
                 (mapv :strand/id [add-z-event add-a-event])))
          (is (= (mapv :id (:updated result))
                 (mapv :strand/id [update-b-event update-a-event])))
          (is (= (:id existing-b) (:strand/id update-b-event)))
          (is (= {:state "closed" :attributes {:phase "done-b"}} (:strand/patch update-b-event)))
          (is (= (:id existing-a) (:strand/id update-a-event)))
          (is (= {:attributes {:phase "done-a"}} (:strand/patch update-a-event)))
          (is (= [(:id burned)] (:strand/burned-ids burn-event)))
          (is (= [burned] (:strand/before burn-event)))
          (Thread/sleep 100)
          (is (= [:batch/applied :strand/added :strand/added :strand/updated :strand/updated :strand/burned]
                 (mapv :event/type @delivered-events))))))))

(deftest weaver-apply-batch-edge-only-emits-only-batch-event
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (let [from (api/add rt {:title "From"})
            to (api/add rt {:title "To"})]
        (Thread/sleep 100)
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (api/register-event-handler! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                                     'skein.weaver-test/capture-event {})
        (api/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
        (let [result (api/apply-batch rt {:refs {:from (:id from) :to (:id to)}
                                          :edges [{:op :upsert :from :from :to :to :type "related-to"}]})
              events (wait-for-events 1)
              batch-event (first (filter #(= :batch/applied (:event/type %)) events))
              context (last @hook-contexts)]
          (Thread/sleep 100)
          (is (= [:batch/applied] (mapv :event/type @delivered-events)))
          (is (= (:edges result) (:batch/edges batch-event)))
          (is (= [] (:batch/created context) (:batch/updated context) (:batch/burned context)))
          (is (= (:edges result) (:batch/edge-ops context))))))))

(deftest weaver-apply-batch-hooks-normalize-context-and-reject-atomically
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                                   'skein.weaver-test/capture-event {})
      (api/register-hook! rt :parse #{:attributes/normalize} 'skein.weaver-test/parse-story-points-hook {})
      (api/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
      (let [existing (api/add rt {:title "Existing" :attributes {:owner "agent"}})
            burnable (api/add rt {:title "Burnable"})]
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (let [payload {:refs {:existing (:id existing) :burnable (:id burnable)}
                       :strands [{:ref :existing :attributes {"storyPoints" "5"}}
                                 {:ref :created :title "Created" :attributes {"storyPoints" "3"}}]
                       :edges [{:op :upsert :from :created :to :existing :type "depends-on" :attributes {:raw "edge"}}]
                       :burn [:burnable]}
              result (batch/apply! rt payload)
              context (last @hook-contexts)
              batch-event (first (filter #(= :batch/applied (:event/type %)) (wait-for-events 5)))]
          (is (= {:storyPoints 3} (get-in result [:created 0 :attributes])))
          (is (= {:owner "agent" :storyPoints 5} (get-in result [:updated 0 :after :attributes])))
          (is (= :batch/apply-before-commit (:hook/type context)))
          (is (= :weaver-api (:request/source context)))
          (is (= :apply-batch (:request/operation context)))
          (is (= :batch/apply (:mutation/operation context)))
          (is (= :apply (:batch/source context)))
          (is (= #{:refs :strands :edges :burn} (set (keys (:batch/payload context)))))
          (is (= "3" (get-in context [:batch/payload :strands 1 :attributes "storyPoints"])))
          (is (= (:refs result) (:batch/refs context)))
          (is (= (:created result) (:batch/created context)))
          (is (= (:updated result) (:batch/updated context)))
          (is (= (:burned result) (:batch/burned context)))
          (is (= (:edges result) (:batch/edge-ops context)))
          (is (= result {:refs (:batch/refs batch-event)
                         :created (:batch/created batch-event)
                         :updated (:batch/updated batch-event)
                         :burned (:batch/burned batch-event)
                         :edges (:batch/edges batch-event)})))
        (api/unregister-hook! rt :capture-batch)
        (api/register-hook! rt :reject-batch #{:batch/apply-before-commit} 'skein.weaver-test/rejecting-hook {})
        (let [keep (api/add rt {:title "Keep" :attributes {:stable true}})
              burn-reject (api/add rt {:title "Burn reject"})
              before (db-test/graph-snapshot (:datasource rt))]
          (reset! delivered-events [])
          (try
            (api/apply-batch rt {:refs {:keep (:id keep) :burn (:id burn-reject)}
                                 :strands [{:ref :keep :attributes {"storyPoints" "8"}}
                                           {:ref :created :title "Rejected create" :attributes {"storyPoints" "13"}}]
                                 :edges [{:op :upsert :from :created :to :keep :type "depends-on"}]
                                 :burn [:burn]})
            (is false "expected batch hook rejection")
            (catch clojure.lang.ExceptionInfo e
              (is (= "hook/failed" (:code (ex-data e))))
              (is (= :batch/apply-before-commit (:hook/type (ex-data e))))
              (is (= :reject-batch (:hook/key (ex-data e))))
              (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
          (Thread/sleep 100)
          (is (= before (db-test/graph-snapshot (:datasource rt))))
          (is (empty? @delivered-events)))))))

(deftest weaver-burn-by-ids-event-captures-pre-delete-rows-and-requested-ids
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:strand/burned} 'skein.weaver-test/capture-event {})
      (let [a (api/add rt {:title "A"})
            b (api/add rt {:title "B"})
            requested [(:id b) (:id a) (:id b)]
            result (api/burn-by-ids rt requested)
            burn-event (first (wait-for-events 1))]
        (is (= {:burned [(:id b) (:id a)] :count 2} result))
        (is (= requested (:strand/requested-ids burn-event)))
        (is (= [(:id b) (:id a)] (:strand/burned-ids burn-event)))
        (is (= [b a] (:strand/before burn-event)))
        (is (= [] (api/list rt)))))))

(deftest weaver-event-runtime-fails-loudly-on-invalid-registration
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key" (api/register-event-handler! rt [] #{:x} 'skein.weaver-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty" (api/register-event-handler! rt :bad #{} 'skein.weaver-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"set" (api/register-event-handler! rt :bad [:x] 'skein.weaver-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified" (api/register-event-handler! rt :bad #{:x} 'capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not be resolved" (api/register-event-handler! rt :bad #{:x} 'missing.ns/handler {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callable" (api/register-event-handler! rt :bad #{:x} 'skein.weaver-test/not-callable-event-handler {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"metadata" (api/register-event-handler! rt :bad #{:x} 'skein.weaver-test/capture-event :opaque)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Event requires key" (api/enqueue-event! rt {:event/type :x :event/id "missing-shape"}))))))

(deftest weaver-hook-registry-registers-replaces-orders-and-unregisters
  (with-runtime
    (fn [rt _]
      (let [entry (api/register-hook! rt :capture #{:payload/received} 'skein.weaver-test/capture-hook {:doc "Capture"})]
        (is (= {:key :capture
                :types #{:payload/received}
                :fn 'skein.weaver-test/capture-hook
                :order 0
                :metadata {:doc "Capture"}}
               entry))
        (is (= [entry] (api/hooks rt)))
        (is (not (contains? (first (api/hooks rt)) :fn-value)))
        (is (ifn? (:fn-value (get @(:hook-registry rt) :capture))))
        (let [replacement (api/register-hook! rt :capture #{:strand/add-before-commit} 'skein.weaver-test/capture-hook {:order 10 :doc "Replaced"})
              early (api/register-hook! rt "early" #{:payload/received} 'skein.weaver-test/capture-hook {:order -1})
              peer-a (api/register-hook! rt :a #{:payload/received} 'skein.weaver-test/capture-hook {})
              peer-b (api/register-hook! rt :b #{:payload/received} 'skein.weaver-test/capture-hook {})]
          (is (= ["early" :a :b :capture] (mapv :key (api/hooks rt))))
          (is (= [early peer-a peer-b replacement] (api/hooks rt)))
          (is (= :a (api/unregister-hook! rt :a)))
          (is (= ["early" :b :capture] (mapv :key (api/hooks rt)))))))))

(deftest weaver-hook-registry-validates-inputs
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key" (api/register-hook! rt [] #{:x} 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty" (api/register-hook! rt :bad #{} 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"set" (api/register-hook! rt :bad [:x] 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keywords" (api/register-hook! rt :bad #{"x"} 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified" (api/register-hook! rt :bad #{:x} 'capture-hook {})))
      (is (thrown? Throwable (api/register-hook! rt :bad #{:x} 'missing.ns/hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callable" (api/register-hook! rt :bad #{:x} 'skein.weaver-test/not-callable-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts" (api/register-hook! rt :bad #{:x} 'skein.weaver-test/capture-hook :opaque)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"data-first" (api/register-hook! rt :bad #{:x} 'skein.weaver-test/capture-hook {:opaque (Object.)})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer" (api/register-hook! rt :bad #{:x} 'skein.weaver-test/capture-hook {:order 1.5}))))))

(deftest reload-config-clears-and-reinstalls-hooks
  (with-runtime
    (fn [rt _]
      (let [init (io/file (get-in rt [:metadata :config-dir]) "init.clj")]
        (api/register-hook! rt :stale #{:payload/received} 'skein.weaver-test/capture-hook {})
        (spit init "(require '[skein.api.current.alpha :as current]\n         '[skein.api.hooks.alpha :as hooks])\n(let [rt (current/runtime)]\n  (hooks/register! rt :fresh #{:payload/received} 'skein.weaver-test/capture-hook {:order 2}))\n")
        (api/reload-config! rt)
        (is (= [{:key :fresh
                 :types #{:payload/received}
                 :fn 'skein.weaver-test/capture-hook
                 :order 2
                 :metadata {}}]
               (api/hooks rt)))))))

(deftest attribute-normalize-hooks-thread-transform-results-for-add-and-update
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:strand/added :strand/updated} 'skein.weaver-test/capture-event {})
      (api/register-hook! rt :parse #{:attributes/normalize} 'skein.weaver-test/parse-story-points-hook {:order 0})
      (api/register-hook! rt :flag #{:attributes/normalize} 'skein.weaver-test/add-normalized-flag-hook {:order 1})
      (let [added (api/add rt {:title "Normalize" :attributes {"storyPoints" "3"}})
            _add-event (first (wait-for-events 1))
            updated (api/update rt (:id added) {:attributes {:storyPoints "5"}})
            update-event (second (wait-for-events 2))]
        (is (= {:storyPoints 3 :normalized true} (:attributes added)))
        (is (= {:storyPoints 5 :normalized true} (:attributes updated)))
        (is (= {:attributes {:storyPoints 5 :normalized true}} (:strand/patch update-event)))
        (is (= [:weaver-api :weaver-api] (mapv :request/source @hook-contexts)))
        (is (= [:add :update] (mapv :request/operation @hook-contexts)))
        (is (= [:strand/add :strand/update] (mapv :mutation/operation @hook-contexts)))
        (is (= (:id added) (:strand/id (second @hook-contexts))))))))

(deftest attribute-normalize-hooks-run-through-runtime-spool-classloader
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! expected-hook-loader (:spool-classloader rt))
      (api/register-hook! rt :classloader #{:attributes/normalize} 'skein.weaver-test/asserting-classloader-hook {})
      (is (= {:a "b"} (:attributes (api/add rt {:title "Classloader" :attributes {:a "b"}})))))))

(deftest attribute-normalize-hooks-require-wrapper-and-json-compatible-values
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (api/register-hook! rt :noop #{:attributes/normalize} 'skein.weaver-test/noop-normalize-hook {})
      (is (= {:a "b"} (:attributes (api/add rt {:title "Noop" :attributes {:a "b"}}))))
      (doseq [[k f] [[:nil 'skein.weaver-test/nil-normalize-hook]
                     [:plain 'skein.weaver-test/non-wrapper-normalize-hook]
                     [:invalid 'skein.weaver-test/invalid-attributes-hook]]]
        (api/register-hook! rt k #{:attributes/normalize} f {})
        (is (thrown? clojure.lang.ExceptionInfo
                     (api/add rt {:title (str "Bad " k) :attributes {:a "b"}})))
        (api/unregister-hook! rt k)))))

(deftest attribute-normalize-hook-failures-rollback-and-preserve-cause-data
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:strand/added :strand/updated} 'skein.weaver-test/capture-event {})
      (api/register-hook! rt :reject #{:attributes/normalize} 'skein.weaver-test/rejecting-normalize-hook {})
      (try
        (api/add rt {:title "Rejected" :attributes {:a "b"}})
        (is false "expected hook rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= :attributes/normalize (:hook/type (ex-data e))))
          (is (= :reject (:hook/key (ex-data e))))
          (is (= 'skein.weaver-test/rejecting-normalize-hook (:hook/fn (ex-data e))))
          (is (= "policy/rejected" (:hook/cause-code (ex-data e))))
          (is (= {:code "policy/rejected" :reason :test} (:exception/data (ex-data e))))))
      (Thread/sleep 100)
      (is (empty? (api/list rt)))
      (is (empty? @delivered-events))
      (api/unregister-hook! rt :reject)
      (api/register-hook! rt :wrapped #{:attributes/normalize} 'skein.weaver-test/wrapping-rejecting-normalize-hook {})
      (try
        (api/add rt {:title "Wrapped" :attributes {:a "b"}})
        (is false "expected wrapped hook rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= "policy/inner" (:hook/cause-code (ex-data e))))))
      (api/unregister-hook! rt :wrapped)
      (let [strand (api/add rt {:title "Stored" :attributes {:a "b"}})]
        (wait-for-events 1)
        (reset! delivered-events [])
        (api/register-hook! rt :reject #{:attributes/normalize} 'skein.weaver-test/rejecting-normalize-hook {})
        (is (thrown? clojure.lang.ExceptionInfo
                     (api/update rt (:id strand) {:attributes {:c "d"}})))
        (Thread/sleep 100)
        (is (= {:a "b"} (:attributes (api/show rt (:id strand)))))
        (is (empty? @delivered-events))))))

(deftest strand-pre-commit-hooks-gate-add-update-and-burn
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:strand/added :strand/updated :strand/burned} 'skein.weaver-test/capture-event {})
      (api/register-hook! rt :capture-add #{:strand/add-before-commit} 'skein.weaver-test/capture-hook {})
      (let [created (api/add rt {:title "Hooked" :attributes {:owner "agent"}})
            add-event (first (wait-for-events 1))
            add-context (first @hook-contexts)]
        (is (= :strand/add-before-commit (:hook/type add-context)))
        (is (= :capture-add (:hook/key add-context)))
        (is (= 'skein.weaver-test/capture-hook (:hook/fn add-context)))
        (is (= :weaver-api (:request/source add-context)))
        (is (= :add (:request/operation add-context)))
        (is (= :strand/add (:mutation/operation add-context)))
        (is (nil? (:strand/before add-context)))
        (is (= created (:strand/after add-context)))
        (is (= {:owner "agent"} (get-in add-context [:strand/after :attributes])))
        (is (= :strand/added (:event/type add-event)))
        (is (= created (:strand add-event)))
        (api/register-hook! rt :reject-add #{:strand/add-before-commit} 'skein.weaver-test/rejecting-hook {})
        (try
          (api/add rt {:title "Rejected" :attributes {:owner "blocked"}})
          (is false "expected add hook rejection")
          (catch clojure.lang.ExceptionInfo e
            (is (= "hook/failed" (:code (ex-data e))))
            (is (= :strand/add-before-commit (:hook/type (ex-data e))))
            (is (= :reject-add (:hook/key (ex-data e))))
            (is (= 'skein.weaver-test/rejecting-hook (:hook/fn (ex-data e))))
            (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
        (Thread/sleep 100)
        (is (nil? (some #(when (= "Rejected" (:title %)) %) (api/list rt))))
        (is (= 1 (count @delivered-events)))
        (api/unregister-hook! rt :reject-add)
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (let [target (api/add rt {:title "Target"})]
          (wait-for-events 1)
          (reset! hook-contexts [])
          (reset! delivered-events [])
          (api/register-hook! rt :capture-update #{:strand/update-before-commit} 'skein.weaver-test/capture-hook {})
          (let [patch {:title "Updated"
                       :state "closed"
                       :attributes {:phase "done"}
                       :edges [{:type "depends-on" :to (:id target)}]}
                updated (api/update rt (:id created) patch)
                update-event (first (wait-for-events 1))
                update-context (first @hook-contexts)]
            (is (= :strand/update-before-commit (:hook/type update-context)))
            (is (= :capture-update (:hook/key update-context)))
            (is (= 'skein.weaver-test/capture-hook (:hook/fn update-context)))
            (is (= :update (:request/operation update-context)))
            (is (= :strand/update (:mutation/operation update-context)))
            (is (= (:id created) (:strand/id update-context)))
            (is (= patch (:strand/patch update-context)))
            (is (= created (:strand/before update-context)))
            (is (= updated (:strand/after update-context)))
            (is (= [{:type "depends-on" :to (:id target)}]
                   (:strand/edge-ops update-context)))
            (is (= :strand/updated (:event/type update-event)))
            (is (= updated (:strand/after update-event)))
            (api/register-hook! rt :reject-update #{:strand/update-before-commit} 'skein.weaver-test/rejecting-hook {})
            (try
              (api/update rt (:id created) {:title "Rejected update"
                                            :attributes {:phase "blocked"}
                                            :edges [{:type "parent-of" :to (:id target)}]})
              (is false "expected update hook rejection")
              (catch clojure.lang.ExceptionInfo e
                (is (= "hook/failed" (:code (ex-data e))))
                (is (= :strand/update-before-commit (:hook/type (ex-data e))))
                (is (= :reject-update (:hook/key (ex-data e))))
                (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
            (Thread/sleep 100)
            (is (= updated (api/show rt (:id created))))
            (is (empty? (db/execute! (:datasource rt)
                                     ["SELECT 1 FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = 'parent-of'"
                                      (:id created) (:id target)])))
            (is (= 1 (count @delivered-events)))
            (api/unregister-hook! rt :reject-update)))
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (api/register-hook! rt :capture-burn #{:strand/burn-before-commit} 'skein.weaver-test/capture-hook {})
        (let [requested [(:id created) (:id created)]
              burn-result (api/burn-by-ids rt requested)
              burn-event (first (wait-for-events 1))
              burn-context (first @hook-contexts)]
          (is (= {:burned [(:id created)] :count 1} burn-result))
          (is (= :strand/burn-before-commit (:hook/type burn-context)))
          (is (= :capture-burn (:hook/key burn-context)))
          (is (= 'skein.weaver-test/capture-hook (:hook/fn burn-context)))
          (is (= :burn (:request/operation burn-context)))
          (is (= :strand/burn (:mutation/operation burn-context)))
          (is (= requested (:strand/requested-ids burn-context)))
          (is (= (:strand/before burn-event) (:strand/before burn-context)))
          (is (= :strand/burned (:event/type burn-event))))
        (let [burn-target (api/add rt {:title "Burn reject"})
              edge-target (api/add rt {:title "Burn edge target"})]
          (api/update rt (:id burn-target) {:edges [{:type "depends-on" :to (:id edge-target)}]})
          (let [burn-target (api/show rt (:id burn-target))]
            (Thread/sleep 100)
            (reset! delivered-events [])
            (api/register-hook! rt :reject-burn #{:strand/burn-before-commit} 'skein.weaver-test/rejecting-hook {})
            (try
              (api/burn-by-ids rt [(:id burn-target)])
              (is false "expected burn hook rejection")
              (catch clojure.lang.ExceptionInfo e
                (is (= "hook/failed" (:code (ex-data e))))
                (is (= :strand/burn-before-commit (:hook/type (ex-data e))))
                (is (= :reject-burn (:hook/key (ex-data e))))
                (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
            (Thread/sleep 100)
            (is (= burn-target (api/show rt (:id burn-target))))
            (is (= [{:found 1}]
                   (db/execute! (:datasource rt)
                                ["SELECT 1 AS found FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = 'depends-on'"
                                 (:id burn-target) (:id edge-target)])))
            (is (empty? @delivered-events))))))))

(deftest weaver-query-registry-add-load-list-and-resolve
  (with-runtime
    (fn [rt _]
      (let [open-query [:= :state "active"]
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

(deftest weaver-query-registry-accepts-edge-predicates
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (let [blocker (api/add rt {:title "Blocker"})
            blocked (api/add rt {:title "Blocked" :attributes {:owner "agent"}})
            edge-query {:params [:relation]
                        :where [:edge/out [:param :relation] [:= :state "active"]]}]
        (api/update rt (:id blocked) {:edges [{:type "depends-on" :to (:id blocker)}]})
        (is (= {"blocked" edge-query} (api/register-query rt 'blocked edge-query)))
        (is (= [(:id blocked)] (mapv :id (api/list-query rt :blocked {:relation "depends-on"}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nested edge predicates"
                              (api/register-query rt 'bad-edge
                                                  [:edge/out "depends-on"
                                                   [:edge/in "depends-on" [:= :state "active"]]])))
        (is (= {"blocked" edge-query} (api/queries rt)))))))

(deftest weaver-query-introspection-api-describes-registered-definitions
  (with-runtime
    (fn [rt _]
      (let [open-query [:= :state "active"]
            owner-query {:params [:owner]
                         :where [:= [:attr :owner] [:param :owner]]}
            declared-unused-query {:params [:owner :unused]
                                   :where [:= [:attr :owner] [:param :owner]]}
            owners-query {:params [:owners]
                          :where [:in [:attr :owner] [:param :owners]]}
            literal-query [:= [:attr :payload] [[:param :literal-value]]]
            relation-query {:params [:relation :owner]
                            :where [:edge/out [:param :relation]
                                    [:and
                                     [:= [:attr :owner] [:param :owner]]
                                     [:= :state "active"]]]}]
        (api/load-queries rt {:open open-query
                              :mine owner-query
                              :declared-unused declared-unused-query
                              :owners owners-query
                              :literal literal-query
                              :blocked relation-query})
        (is (= [{:name "blocked" :params [:relation :owner] :referenced-params [:relation :owner]}
                {:name "declared-unused" :params [:owner :unused] :referenced-params [:owner]}
                {:name "literal" :params [] :referenced-params []}
                {:name "mine" :params [:owner] :referenced-params [:owner]}
                {:name "open" :params [] :referenced-params []}
                {:name "owners" :params [:owners] :referenced-params [:owners]}]
               (api/query-metadata rt)))

        (is (= {:name "mine"
                :params [:owner]
                :referenced-params [:owner]
                :where (:where owner-query)
                :definition owner-query
                :where-form (pr-str (:where owner-query))
                :definition-form (pr-str owner-query)
                :summary "Invoke this query with `strand list --query <name>` or `strand ready --query <name>` and pass runtime values with repeated `--param key=value` arguments."}
               (api/query-explain rt :mine)))

        (try
          (api/query-explain rt :missing)
          (is false "expected query explain missing query failure")
          (catch clojure.lang.ExceptionInfo e
            (is (= "Query not found" (ex-message e)))
            (is (= {:query :missing
                    :canonical-query "missing"
                    :available ["blocked" "declared-unused" "literal" "mine" "open" "owners"]}
                   (ex-data e)))))))))

(deftest json-socket-operation-surface-stays-thin
  (with-runtime
    (fn [rt _]
      (let [status (socket-request rt "status" {})
            rejected (socket-request rt "queries" {})]
        (is (true? (get status "ok")))
        (is (= "protocol/operation-not-allowed" (get-in rejected ["error" "code"])))))))

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
        (.addURL ^clojure.lang.DynamicClassLoader (:spool-classloader rt)
                 (.toURL (.toURI (io/file root "src"))))
        (load-file (str (io/file root "src" (str (-> (str ns-sym)
                                                       (.replace \- \_)
                                                       (.replace \. java.io.File/separatorChar))
                                                    ".clj"))))
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

(deftest weaver-op-registry-and-built-in-help
  (with-runtime
    (fn [rt _]
      (is (= {:name "custom" :doc "Echo argv" :fn 'skein.weaver-test/test-op}
             (api/register-op! rt 'custom "Echo argv" 'skein.weaver-test/test-op)))
      (is (= {:operation "custom" :argv ["--flag" "value"]}
             (api/op! rt 'custom ["--flag" "value"])))
      (let [help (api/op! rt 'help [])]
        (is (= "strand op <name> [args...]" (:usage help)))
        (is (some #(= "help" (:name %)) (:registered help))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Operation not found"
                            (api/op! rt 'missing [])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Operation function"
                            (api/register-op! rt 'bad 'unqualified))))))

(deftest weaver-pattern-registry-and-weave
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (is (= {:name "dev-task" :fn 'skein.weaver-test/test-pattern :input-spec ::pattern-input}
             (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)))
      (is (= [{:name "dev-task" :fn 'skein.weaver-test/test-pattern :input-spec ::pattern-input}]
             (api/patterns rt)))
      (is (= {:name "documented-task"
              :doc "Create implementation and review strands."
              :fn 'skein.weaver-test/test-pattern
              :input-spec ::pattern-input}
             (api/register-pattern! rt 'documented-task "Create implementation and review strands."
                                    'skein.weaver-test/test-pattern ::pattern-input)))
      (is (= "Create implementation and review strands."
             (:doc (api/pattern-explain rt :documented-task))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern doc"
                            (api/register-pattern! rt 'bad-doc "" 'skein.weaver-test/test-pattern ::pattern-input)))
      (is (clojure.string/includes? (:spec-form (api/pattern-explain rt :dev-task))
                                    "clojure.spec.alpha/keys"))
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture-weave #{:batch/applied}
                                   'skein.weaver-test/capture-event {})
      (let [result (api/weave! rt :dev-task {:title "Implement weave"})]
        (is (= ["Implement weave" "Review: Implement weave"] (mapv :title (:created result))))
        (is (= #{"impl" "review"} (set (keys (:refs result)))))
        (is (= 1 (count (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"]))))
        ;; a weave is a batch apply: event-driven spools must see the created
        ;; strands without waiting for an unrelated mutation
        (let [event (do (Thread/sleep 300) (first @delivered-events))]
          (is (= :batch/applied (:event/type event)))
          (is (= "dev-task" (str (:pattern/name event))))
          (is (= 2 (count (:batch/created event))))))
      (api/unregister-event-handler! rt :capture-weave)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern input failed spec validation"
                            (api/weave! rt :dev-task {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern not found"
                            (api/weave! rt :missing {:title "x"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern function"
                            (api/register-pattern! rt 'bad 'unqualified ::pattern-input))))))

(deftest weaver-weave-create-only-contract-remains-compatible
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (let [result (api/weave! rt :dev-task {:title "Compatible weave"})
            [impl review] (:created result)]
        (is (= #{:refs :created} (set (keys result))))
        (is (= {"impl" (:id impl) "review" (:id review)} (:refs result)))
        (is (= ["Compatible weave" "Review: Compatible weave"] (mapv :title (:created result))))
        (is (= [{:from_strand_id (:id review)
                 :to_strand_id (:id impl)
                 :edge_type "depends-on"}]
               (db/execute! (:datasource rt)
                            ["SELECT from_strand_id, to_strand_id, edge_type FROM strand_edges"])))))))

(deftest weaver-weave-runs-create-only-batch-hooks-once-and-normalizes-attributes
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! hook-contexts [])
      (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (api/register-pattern! rt 'points 'skein.weaver-test/points-pattern ::pattern-input)
      (api/register-hook! rt :parse #{:attributes/normalize} 'skein.weaver-test/parse-story-points-hook {})
      (api/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
      (let [points-result (api/weave! rt :points {:title "Pointed"})]
        (is (= {:storyPoints 8} (get-in points-result [:created 0 :attributes])))
        (is (= {:storyPoints 8}
               (:attributes (some #(when (= "Pointed" (:title %)) %) (api/list rt))))))
      (reset! hook-contexts [])
      (let [result (api/weave! rt :dev-task {:title "Hooked weave"})
            [impl review] (:created result)
            contexts @hook-contexts
            normalize-contexts (filter #(= :attributes/normalize (:hook/type %)) contexts)
            batch-contexts (filter #(= :batch/apply-before-commit (:hook/type %)) contexts)
            batch-context (first batch-contexts)]
        (is (= 2 (count normalize-contexts)))
        (is (= 1 (count batch-contexts)))
        (is (= {:kind "implementation"} (:attributes impl)))
        (is (= {:kind "review"} (:attributes review)))
        (is (= :weave (:request/operation batch-context)))
        (is (= :batch/apply (:mutation/operation batch-context)))
        (is (= :weave (:batch/source batch-context)))
        (is (= "dev-task" (:pattern/name batch-context)))
        (is (= {:title "Hooked weave"} (:pattern/input batch-context)))
        (is (= #{:refs :strands :edges :burn} (set (keys (:batch/payload batch-context)))))
        (is (= #{{:kind "implementation"} {:kind "review"}}
               (set (map :attributes (get-in batch-context [:batch/payload :strands])))))
        (is (every? #(not (contains? % :edges)) (get-in batch-context [:batch/payload :strands])))
        (is (= [{:op :upsert :from "review" :to "impl" :type "depends-on"}]
               (get-in batch-context [:batch/payload :edges])))
        (is (= (:refs result) (:batch/refs batch-context)))
        (is (= (:created result) (:batch/created batch-context)))
        (is (= [] (:batch/updated batch-context) (:batch/burned batch-context)))
        (is (= 1 (count (:batch/edge-ops batch-context))))
        (is (= "review" (get-in batch-context [:batch/edge-ops 0 :from])))
        (is (= "impl" (get-in batch-context [:batch/edge-ops 0 :to])))
        (is (= "depends-on" (get-in batch-context [:batch/edge-ops 0 :type])))))))

(deftest weaver-pattern-failures-validate-before-code-and-rollback
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! pattern-call-count 0)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:strand/added :batch/applied} 'skein.weaver-test/capture-event {})
      (api/register-pattern! rt 'counting 'skein.weaver-test/counting-pattern ::never-valid)
      (api/register-hook! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern input failed spec validation"
                            (api/weave! rt :counting {:title "Nope"})))
      (is (= 0 @pattern-call-count))
      (is (empty? @hook-contexts))
      (is (empty? (api/list rt)))
      (api/register-pattern! rt 'bad-edge 'skein.weaver-test/bad-edge-pattern ::pattern-input)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch target strand not found"
                            (api/weave! rt :bad-edge {:title "Rollback"})))
      (is (empty? (api/list rt)))
      (is (empty? (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"])))
      (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (api/unregister-hook! rt :capture-batch)
      (api/register-hook! rt :reject-batch #{:batch/apply-before-commit} 'skein.weaver-test/rejecting-hook {})
      (try
        (api/weave! rt :dev-task {:title "Rejected weave"})
        (is false "expected weave hook rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= :batch/apply-before-commit (:hook/type (ex-data e))))
          (is (= :reject-batch (:hook/key (ex-data e))))
          (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
      (Thread/sleep 100)
      (is (empty? (api/list rt)))
      (is (empty? (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"])))
      (is (empty? @delivered-events)))))

(deftest weaver-reload-clears-patterns
  (with-runtime
    (fn [rt _]
      (let [init-file (io/file (get-in rt [:metadata :config-dir]) "init.clj")]
        (spit init-file "(require '[skein.api.current.alpha :as current]\n         '[skein.api.runtime.alpha :as runtime-alpha])\n(runtime-alpha/sync! (current/runtime))\n")
        (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
        (is (= 1 (count (api/patterns rt))))
        (api/reload-config! rt)
        (is (empty? (api/patterns rt)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Pattern not found"
                              (api/resolve-pattern rt 'dev-task)))))))

(deftest json-socket-weave-and-pattern-list-and-explain
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (let [listed (socket-request rt "pattern-list" {})]
        (is (true? (get listed "ok")))
        (is (= ["dev-task"] (mapv #(get % "name") (get listed "result")))))
      (let [explained (socket-request rt "pattern-explain" {"pattern" "dev-task"})]
        (is (true? (get explained "ok")))
        (is (= "dev-task" (get-in explained ["result" "name"]))))
      (let [woven (socket-request rt "weave" {"pattern" "dev-task" "input" {:title "From socket"}})]
        (is (true? (get woven "ok")))
        (is (= ["From socket" "Review: From socket"]
               (mapv #(get % "title") (get-in woven ["result" "created"]))))))))

(deftest json-socket-query-list-and-explain
  (with-runtime
    (fn [rt _]
      (let [owner-query {:params [:owner]
                         :where [:= [:attr :owner] [:param :owner]]}
            blocked-query {:params [:relation :owner]
                           :where [:edge/out [:param :relation]
                                   [:and
                                    [:= [:attr :owner] [:param :owner]]
                                    [:= :state "active"]]]}]
        (api/load-queries rt {:mine owner-query
                              :blocked blocked-query})
        (let [listed (socket-request rt "query-list" {})]
          (is (true? (get listed "ok")))
          (is (= [{"name" "blocked" "params" ["relation" "owner"] "referenced-params" ["relation" "owner"]}
                  {"name" "mine" "params" ["owner"] "referenced-params" ["owner"]}]
                 (get listed "result"))))
        (let [explained (socket-request rt "query-explain" {"query" ":blocked"})]
          (is (true? (get explained "ok")))
          (is (= {"name" "blocked"
                  "params" ["relation" "owner"]
                  "referenced-params" ["relation" "owner"]
                  "where" ["edge/out" ["param" "relation"]
                           ["and"
                            ["=" ["attr" "owner"] ["param" "owner"]]
                            ["=" "state" "active"]]]
                  "definition" {"params" ["relation" "owner"]
                                "where" ["edge/out" ["param" "relation"]
                                         ["and"
                                          ["=" ["attr" "owner"] ["param" "owner"]]
                                          ["=" "state" "active"]]]}
                  "where-form" (pr-str (:where blocked-query))
                  "definition-form" (pr-str blocked-query)
                  "summary" "Invoke this query with `strand list --query <name>` or `strand ready --query <name>` and pass runtime values with repeated `--param key=value` arguments."}
                 (get explained "result"))))
        (let [missing (socket-request rt "query-explain" {"query" "missing"})]
          (is (false? (get missing "ok")))
          (is (= "domain" (get-in missing ["error" "type"])))
          (is (= "query/not-found" (get-in missing ["error" "code"])))
          (is (= "missing" (get-in missing ["error" "details" "canonical-query"])))
          (is (= ["blocked" "mine"] (get-in missing ["error" "details" "available"]))))
        (doseq [[op args] [["query-list" {"extra" "nope"}]
                           ["query-explain" {}]
                           ["query-explain" {"query" 1}]
                           ["query-explain" {"query" "mine" "extra" "nope"}]]]
          (let [bad (socket-request rt op args)]
            (is (false? (get bad "ok")) (str op " " args))
            (is (= "protocol/malformed-request" (get-in bad ["error" "code"])))))
        (let [blank (socket-request rt "query-explain" {"query" "  :  "})]
          (is (false? (get blank "ok")))
          (is (= "domain" (get-in blank ["error" "type"])))
          (is (= "domain/error" (get-in blank ["error" "code"])))
          (is (= "Query names must not be blank" (get-in blank ["error" "message"]))))))))

(deftest json-socket-op-dispatch
  (with-runtime
    (fn [rt _]
      (api/register-op! rt 'custom 'skein.weaver-test/test-op)
      (let [custom (socket-request rt "op" {"name" "custom" "args" ["--flag" "value"]})]
        (is (true? (get custom "ok")))
        (is (= {"operation" "custom" "argv" ["--flag" "value"]}
               (get custom "result"))))
      (let [help (socket-request rt "op" {"name" "help" "args" []})]
        (is (true? (get help "ok")))
        (is (= "strand op <name> [args...]" (get-in help ["result" "usage"]))))
      (let [bad (socket-request rt "op" {"name" "custom" "args" [1]})]
        (is (false? (get bad "ok")))
        (is (= "protocol/malformed-request" (get-in bad ["error" "code"])))))))

(deftest json-socket-payload-hooks-gate-selected-operations
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! hook-contexts [])
      (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (api/register-op! rt 'custom 'skein.weaver-test/test-op)
      (let [source (api/add rt {:title "Payload source"})
            target (api/add rt {:title "Payload target"})
            replacement (api/add rt {:title "Payload replacement"})]
        (api/register-hook! rt :payload #{:payload/received} 'skein.weaver-test/capture-hook {})
        (is (true? (get (socket-request rt "add" {"title" "Payload add" "state" "active" "attributes" {"owner" "agent"}}) "ok")))
        (is (true? (get (socket-request rt "update" {"id" (:id source) "attributes" {"priority" "high"}}) "ok")))
        (is (true? (get (socket-request rt "supersede" {"old_id" (:id target) "replacement_id" (:id replacement)}) "ok")))
        (is (true? (get (socket-request rt "burn" {"id" (:id source)}) "ok")))
        (is (true? (get (socket-request rt "weave" {"pattern" "dev-task" "input" {"title" "Payload weave"}}) "ok")))
        (is (true? (get (socket-request rt "op" {"name" "custom" "args" ["--flag"]}) "ok")))
        (is (= [:add :update :supersede :burn :weave :op]
               (mapv :request/operation @hook-contexts)))
        (let [add-context (first @hook-contexts)
              update-context (second @hook-contexts)]
          (is (= :payload/received (:hook/type add-context)))
          (is (= :payload (:hook/key add-context)))
          (is (= 'skein.weaver-test/capture-hook (:hook/fn add-context)))
          (is (= :json-socket (:request/source add-context)))
          (is (= "test-request" (:request/id add-context)))
          (is (= {"title" "Payload add" "state" "active" "attributes" {"owner" "agent"}}
                 (:request/args add-context)))
          (is (= {} (:request/options add-context)))
          (is (= {"priority" "high"} (get-in update-context [:request/args "attributes"]))))))))

(deftest json-socket-payload-hooks-skip-exempt-operations-and-protocol-errors
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! hook-contexts [])
      (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (api/register-query rt 'all [:= :state "active"])
      (let [strand-id (get-in (socket-request rt "add" {"title" "Exempt target" "attributes" {}}) ["result" "id"])]
        (api/register-hook! rt :payload #{:payload/received} 'skein.weaver-test/rejecting-hook {})
        (reset! hook-contexts [])
        (doseq [[op args] [["status" {}]
                           ["show" {"id" strand-id}]
                           ["list" {}]
                           ["ready" {}]
                           ["list-query" {"query" "all" "params" {}}]
                           ["ready-query" {"query" "all" "params" {}}]
                           ["pattern-list" {}]
                           ["pattern-explain" {"pattern" "dev-task"}]
                           ["query-list" {}]
                           ["query-explain" {"query" "all"}]]]
          (is (true? (get (socket-request rt op args) "ok")) op))
        (is (empty? @hook-contexts))
        (let [bad (socket-request rt "op" {"name" "custom" "args" [1]})]
          (is (= "protocol/malformed-request" (get-in bad ["error" "code"])))
          (is (empty? @hook-contexts)))
        (let [wrong-identity (socket-request-envelope rt {"protocol_version" 1
                                                          "request_id" "wrong-identity"
                                                          "weaver_id" "wrong"
                                                          "operation" "add"
                                                          "arguments" {"title" "No hook" "attributes" {}}
                                                          "options" {}})]
          (is (= "protocol/identity-mismatch" (get-in wrong-identity ["error" "code"])))
          (is (empty? @hook-contexts)))
        (let [disallowed (socket-request rt "queries" {})]
          (is (= "protocol/operation-not-allowed" (get-in disallowed ["error" "code"])))
          (is (empty? @hook-contexts)))))))

(deftest json-socket-payload-hook-rejection-is-domain-error-before-dispatch
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! hook-contexts [])
      (api/register-hook! rt :reject-payload #{:payload/received} 'skein.weaver-test/rejecting-hook {})
      (let [response (socket-request rt "add" {"title" "Rejected payload" "attributes" {}})]
        (is (false? (get response "ok")))
        (is (= "domain" (get-in response ["error" "type"])))
        (is (= "hook/failed" (get-in response ["error" "code"])))
        (is (= "policy/rejected" (get-in response ["error" "details" "hook/cause-code"])))
        (is (= "Rejected payload" (get-in response ["error" "details" "exception/data" "ctx" "request/args" "title"])))
        (is (empty? (api/list rt)))))))

(deftest json-socket-semantic-hooks-receive-socket-request-context
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! hook-contexts [])
      (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (api/register-hook! rt :normalize #{:attributes/normalize} 'skein.weaver-test/parse-story-points-hook {})
      (api/register-hook! rt :add #{:strand/add-before-commit} 'skein.weaver-test/capture-hook {})
      (api/register-hook! rt :batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
      (is (true? (get (socket-request rt "add" {"title" "Socket add" "attributes" {"owner" "agent"}}) "ok")))
      (is (true? (get (socket-request rt "weave" {"pattern" "dev-task" "input" {"title" "Socket weave"}}) "ok")))
      (let [contexts @hook-contexts]
        (is (= #{:json-socket} (set (map :request/source contexts))))
        (is (= [:add :add :weave :weave :weave]
               (mapv :request/operation contexts)))
        (is (= [:normalize :add :normalize :normalize :batch]
               (mapv :hook/key contexts)))))))

(deftest json-socket-hook-failure-details-are-json-safe
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (api/register-hook! rt :non-json #{:strand/add-before-commit} 'skein.weaver-test/non-json-rejecting-hook {})
      (let [response (socket-request rt "add" {"title" "Non JSON" "attributes" {}})]
        (is (false? (get response "ok")))
        (is (= "hook/failed" (get-in response ["error" "code"])))
        (is (= "strand/add-before-commit" (get-in response ["error" "details" "hook/type"])))
        (is (= "policy/non-json" (get-in response ["error" "details" "hook/cause-code"])))
        (is (= "strand/add-before-commit" (get-in response ["error" "details" "exception/data" "hook-stage"])))
        (is (= "policy/non-json" (get-in response ["error" "details" "exception/data" "nested" "reason"])))
        (is (string? (get-in response ["error" "details" "exception/data" "opaque"])))))))

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
                            (api/register-query rt 'user/mine [:= :state "active"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (api/load-queries rt {"mine" [:= :state "active"]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (api/load-queries rt {'user/mine [:= :state "active"]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown query operator"
                            (api/register-query rt :broken [:unknown :state "active"])))
      (api/register-query rt :ok [:= :state "active"])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown query operator"
                            (api/load-queries rt {:bad [:unknown :state "active"]})))
      (is (= {"ok" [:= :state "active"]} (api/queries rt))))))

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
        rt (runtime/start! nil {:world world :publish? false})]
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
      (spit init "(require '[skein.api.current.alpha :as current] '[skein.api.weaver.alpha :as api]) (api/register-query (current/runtime) 'trusted [:= :state \"active\"])")
      (let [rt (runtime/start! nil {:world world :publish? false})]
        (try
          (is (= {"trusted" [:= :state "active"]} (api/queries rt)))
          (finally
            (runtime/stop! rt))))
      (finally
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-metadata-rejects-blank-friendly-name
  (let [world (temp-world)
        db-file (db-test/temp-db-file)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Weaver name must not be blank"
                            (metadata/metadata-shape {:pid 1
                                                      :host "127.0.0.1"
                                                      :port 5555
                                                      :canonical-db-path (metadata/canonical-db-path db-file)
                                                      :nonce "weaver"
                                                      :world world
                                                      :name "  "
                                                      :started-at "now"})))
      (is (true? (metadata/stale-or-missing?
                  {:pid 1
                   :transport :nrepl
                   :protocol-version 1
                   :endpoint {:host "127.0.0.1" :port 5555}
                   :config-dir (:config-dir world)
                   :state-dir (:state-dir world)
                   :data-dir (:data-dir world)
                   :canonical-db-path (metadata/canonical-db-path db-file)
                   :nonce "weaver"
                   :socket-path (str (:state-dir world) "/weaver.sock")
                   :started-at "now"
                   :name "  "})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"--name requires a non-blank value"
                            ((ns-resolve 'skein.core.weaver.runtime 'parse-main-args)
                             ["--workspace" (:config-dir world)
                              "--state-dir" (:state-dir world)
                              "--data-dir" (:data-dir world)
                              "--name" "  "])))
      (finally
        (metadata/delete! world)
        (db-test/delete-sqlite-family! db-file)
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
        (is (= :sqlite-file (:storage-kind status)))
        (is (= canonical (:storage-label status)))
        (is (= status from-disk))
        (is (= file (metadata/metadata-file (:metadata rt))))
        (is (pos-int? (get-in status [:endpoint :port])))
        (is (string? (:nonce status)))
        (is (= (.getName (io/file (:config-dir status))) (:name status)))
        (is (= (:name status) (get json-disk "name")))
        (is (= :nrepl (:transport status)))
        (is (= 1 (:protocol-version status)))
        (is (string? (:socket-path status)))
        (is (= canonical (get json-disk "database_path")))
        (is (= "sqlite-file" (get json-disk "database_kind")))
        (is (= canonical (get json-disk "database_label")))
        (is (= (:nonce status) (get json-disk "weaver_id")))
        (is (= (:socket-path status) (get json-disk "socket_path")))
        (is (= "127.0.0.1" (get-in json-disk ["nrepl" "host"])))
        (is (false? (metadata/stale-or-missing? status)))
        (is (false? (metadata/stale-or-missing? (assoc status :pid (long (:pid status))))))
        (is (= "127.0.0.1" (get-in status [:endpoint :host])))
        (is (.isLoopbackAddress (.getInetAddress (:server-socket (:server rt)))))))))

(deftest json-socket-dispatches-success-domain-and-protocol-errors
  (with-runtime
    (fn [rt _]
      (let [removed-init (socket-request rt "init" {})]
        (is (false? (get removed-init "ok")))
        (is (= "protocol/operation-not-allowed" (get-in removed-init ["error" "code"]))))
      (let [added (socket-request rt "add" {"title" "Socket task" "state" "active" "attributes" {"owner" "go"}})]
        (is (true? (get added "ok")))
        (is (= "Socket task" (get-in added ["result" "title"])))
        (is (= "active" (get-in added ["result" "state"])))
        (is (not (contains? (get added "result") "active")))
        (is (not (contains? (get added "result") "inactive_at")))
        (is (= {"owner" "go"} (get-in added ["result" "attributes"]))))
      (let [target (socket-request rt "add" {"title" "Target" "state" "closed" "attributes" {}})
            source (socket-request rt "add" {"title" "Source" "state" "active" "attributes" {}})
            updated (socket-request rt "update" {"id" (get-in source ["result" "id"])
                                                  "title" nil
                                                  "state" nil
                                                  "attributes" nil
                                                  "edges" [{"type" "depends-on"
                                                            "to" (get-in target ["result" "id"])
                                                            "attributes" {"reason" "socket"}}]})]
        (is (true? (get updated "ok")))
        (is (= [{:to_strand_id (get-in target ["result" "id"])
                 :edge_type "depends-on"
                 :attributes {:reason "socket"}}]
               (mapv #(update % :attributes db/<-json)
                     (db/execute! (:datasource rt)
                                  ["SELECT to_strand_id, edge_type, attributes FROM strand_edges WHERE from_strand_id = ?"
                                   (get-in source ["result" "id"])]))))
        (let [subgraph (socket-request rt "subgraph" {"root_ids" [(get-in source ["result" "id"])]
                                                       "type" "depends-on"})]
          (is (true? (get subgraph "ok")))
          (is (= [(get-in source ["result" "id"])] (get-in subgraph ["result" "root_ids"])))
          (is (= #{(get-in source ["result" "id"]) (get-in target ["result" "id"])}
                 (set (map #(get % "id") (get-in subgraph ["result" "strands"])))))
          (is (= [{"from_strand_id" (get-in source ["result" "id"])
                   "to_strand_id" (get-in target ["result" "id"])
                   "edge_type" "depends-on"
                   "attributes" {"reason" "socket"}}]
                 (get-in subgraph ["result" "edges"])))))
      (let [missing (socket-request rt "update" {"id" "missing" "title" nil "state" nil "attributes" nil "edges" []})]
        (is (false? (get missing "ok")))
        (is (= "domain" (get-in missing ["error" "type"]))))
      (let [old (socket-request rt "add" {"title" "Old supersession" "attributes" {}})
            replacement (socket-request rt "add" {"title" "Replacement" "attributes" {}})
            superseded (socket-request rt "supersede" {"old_id" (get-in old ["result" "id"])
                                                        "replacement_id" (get-in replacement ["result" "id"])})]
        (is (true? (get superseded "ok")))
        (is (= "replaced" (get-in superseded ["result" "old" "after" "state"])))
        (is (= "supersedes" (get-in superseded ["result" "supersedes-edge" "edge_type"]))))
      (let [bad-supersede (socket-request rt "supersede" {"old_id" "missing"})]
        (is (false? (get bad-supersede "ok")))
        (is (= "protocol/malformed-request" (get-in bad-supersede ["error" "code"]))))
      (let [replaced-update (socket-request rt "update" {"id" (get-in (socket-request rt "add" {"title" "Cannot replace" "attributes" {}}) ["result" "id"])
                                                 "state" "replaced"})]
        (is (false? (get replaced-update "ok")))
        (is (= "protocol/malformed-request" (get-in replaced-update ["error" "code"]))))
      (let [old-lifecycle (socket-request rt "add" {"title" "Old lifecycle" "active" true "attributes" {}})]
        (is (false? (get old-lifecycle "ok")))
        (is (= "protocol/malformed-request" (get-in old-lifecycle ["error" "code"]))))
      (let [rejected (socket-request rt "queries" {})]
        (is (false? (get rejected "ok")))
        (is (= "protocol/operation-not-allowed" (get-in rejected ["error" "code"])))))))

(deftest json-socket-update-event-patch-preserves-submitted-keys
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! delivered-events [])
      (api/register-event-handler! rt :capture #{:strand/updated} 'skein.weaver-test/capture-event {})
      (let [added (socket-request rt "add" {"title" "Socket patch" "state" "active" "attributes" {}})
            updated (socket-request rt "update" {"id" (get-in added ["result" "id"])
                                                  "state" "closed"})]
        (is (true? (get updated "ok")))
        (let [event (first (wait-for-events 1))]
          (is (= :strand/updated (:event/type event)))
          (is (= {:state "closed"} (:strand/patch event))))))))

(deftest json-socket-store-is-ready-after-startup
  (with-runtime
    (fn [rt _]
      (let [response (socket-request rt "list" {})]
        (is (= true (get response "ok")))
        (is (= [] (get response "result")))))))

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
      (is (.exists (metadata/socket-file (:metadata rt)))))))

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
      (is (.exists (metadata/socket-file (:metadata rt)))))))

(deftest json-socket-stop-cleans-runtime
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        rt (runtime/start! db-file {:world world :publish? false})]
    (try
      (reset! hook-contexts [])
      (api/register-hook! rt :payload #{:payload/received} 'skein.weaver-test/capture-hook {})
      (let [response (socket-request rt "stop" {})]
        (is (true? (get response "ok")))
        (is (= true (get-in response ["result" "stopping"])))
        (is (empty? @hook-contexts)))
      (Thread/sleep 250)
      (is (false? (.exists (metadata/socket-file (:metadata rt)))))
      (is (false? (.exists (metadata/json-metadata-file (:metadata rt)))))
      (finally
        (runtime/stop! rt)
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
                            (runtime/start! nil {:world world :publish? false})))
      (is (.exists socket-file))
      (finally
        (metadata/delete! world)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-stop-removes-metadata
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        rt (runtime/start! db-file {:world world :publish? false})]
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
        rt (runtime/start! db-file {:world world :publish? false})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"metadata already exists"
                            (runtime/start! db-file {:world world :publish? false})))
      (finally
        (runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)))))
