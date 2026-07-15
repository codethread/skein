(ns skein.weaver-test
  "Tests for the weaver runtime: transport, op dispatch, and lifecycle."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skein.api.batch.alpha :as batch]
            [skein.api.current.alpha :as current]
            [skein.api.events.alpha :as events]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.views.alpha :as views]
            [skein.api.graph.alpha :as graph]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.metadata :as metadata]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.db :as db]
            [skein.core.db-test :as db-test]
            [skein.spools.test-support :as test-support])
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
         rt (weaver-runtime/start! db-file (assoc (or start-options {}) :world world :publish? false))]
     (try
       (weaver-runtime/with-runtime-binding rt #(f rt db-file))
       (finally
         (weaver-runtime/stop! rt)
         (db-test/delete-sqlite-family! db-file)
         (delete-tree! (io/file (:config-dir world))))))))

(defn test-view [{:keys [params]}]
  {:view :test :params params})

(defn test-op [{:op/keys [name argv]}]
  {:operation name :argv argv})

(defn context-echo-op
  "Return the handler context so tests can inspect threaded envelope fields."
  [ctx]
  ctx)

(defn envelope-echo-op
  "Return only the JSON-safe envelope fields (the full context carries the
  runtime, which cannot cross the JSON socket)."
  [ctx]
  {:cwd (:op/cwd ctx)
   :worktree-root (:op/worktree-root ctx)
   :timeout (:op/timeout ctx)
   :payloads (:op/payloads ctx)})

;; Stream/op transport fixtures. Namespace-level for the same by-symbol
;; registration reason as the hooks/events above; the :each fixture resets
;; `stream-gate` and `op-side-effects`.
(def stream-gate (atom (promise)))
(def op-side-effects (atom []))

(defn gated-stream-op
  "Emit line 0, block until the test releases the gate, then emit line 1.

  Proves incremental flush: the test reads line 0 off the socket before it
  delivers the gate, so line 0 cannot have been buffered until the op returned."
  [{emit! :op/emit!}]
  (emit! {"i" 0})
  @@stream-gate
  (emit! {"i" 1})
  {"emitted" 2})

(defn stream-error-op
  "Emit one line, then throw so the socket writes an error terminator."
  [{emit! :op/emit!}]
  (emit! {"i" 0})
  (throw (ex-info "stream blew up" {:code "stream/failed"})))

(defn slow-op
  "Sleep past any short deadline, recording that it ran to completion."
  [_ctx]
  (Thread/sleep 3000)
  (swap! op-side-effects conj :slow-finished)
  {:slow true})

(defn side-effecting-op
  "Record that the handler ran, so a hook rejection before dispatch is provable."
  [{:op/keys [name]}]
  (swap! op-side-effects conj name)
  {:ran name})

(defn throwing-op
  "Throw rich, partly non-JSON ex-data to exercise json-safe error rendering."
  [_ctx]
  (throw (ex-info "op blew up" {:code "op/failed"
                                :nested {:reason :policy/nope}
                                :opaque (Object.)})))

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
          children (remove #(= root-id (:id %)) (:strands (graph/subgraph rt [root-id])))
          temporary-child-ids (->> children
                                   (filter #(= "true" (get-in % [:attributes :temporary])))
                                   (mapv :id))]
      (when (seq temporary-child-ids)
        (graph/burn-by-ids! rt temporary-child-ids))
      (swap! cleanup-events conj {:root root-id :burned temporary-child-ids}))))

(defn wait-for-events [n]
  (test-support/poll-until #(when (<= n (count @delivered-events)) @delivered-events)
                           {:timeout-ms (test-support/await-budget-ms 1000)
                            :on-timeout (fn [] @delivered-events)}))

(defn wait-until [pred]
  (test-support/poll-until #(when (pred) true)
                           {:timeout-ms (test-support/await-budget-ms 1000)
                            :on-timeout (constantly false)}))

(defn test-event [type id]
  {:event/type type
   :event/id id
   :event/at "2026-06-27T00:00:00Z"
   :event/source :test})

;; Event handlers are registered by var symbol, not by closure, so the test
;; drain handler receives the per-call promise through namespace state. This
;; namespace is deliberately run as a serial test island.
(def ^:private event-drain-signal (atom nil))

(defn event-drain-handler
  "Signal that the event drain sentinel has reached the event worker."
  [_event]
  (deliver @event-drain-signal true))

(defn drain-events!
  "Block until every event enqueued before this call has been delivered.

  Relies on the runtime event worker being a single FIFO consumer."
  [rt]
  (let [signal (promise)]
    (reset! event-drain-signal signal)
    (events/register! rt :event-drain #{:test/event-drain}
                      'skein.weaver-test/event-drain-handler {})
    (try
      (events/enqueue! rt (test-event :test/event-drain (str (random-uuid))))
      (when-not (deref signal (test-support/await-budget-ms 5000) false)
        (throw (ex-info "Timed out draining event queue" {})))
      (finally
        (events/unregister! rt :event-drain)))))

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
    (reset! stream-gate (promise))
    (reset! op-side-effects [])
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
                    (str/replace \- \_)
                    (str/replace \. java.io.File/separatorChar))
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

(defn invoke-request
  "Send an `invoke` request carrying an op envelope, returning the parsed frame.

  `extra` merges extra envelope fields (e.g. cwd/timeout) into the arguments."
  ([rt name argv] (invoke-request rt name argv {} {}))
  ([rt name argv payloads] (invoke-request rt name argv payloads {}))
  ([rt name argv payloads extra]
   (socket-request rt "invoke" (merge {"name" name
                                       "argv" (vec argv)
                                       "payloads" payloads}
                                      extra))))

(defn invoke-frame
  "Build a raw invoke request frame for tests that drive the socket by hand
  (e.g. streaming, which reads more than one response line)."
  [rt name argv]
  {"protocol_version" 1
   "request_id" "test-request"
   "weaver_id" (:nonce (:metadata rt))
   "operation" "invoke"
   "arguments" {"name" name "argv" (vec argv) "payloads" {}}
   "options" {}})

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
        rt (weaver-runtime/start! nil {:world world :publish? false})]
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
        (is (= ["depends-on" "notes" "parent-of" "serves" "supersedes"] (weaver/acyclic-relations rt)))
        (is (seq (db/execute! (:datasource rt) ["SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'strands'"]))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest runtime-owns-a-file-storage-handle
  (let [world (temp-world)
        rt (weaver-runtime/start! nil {:world world :publish? false})]
    (try
      (let [storage (:storage rt)]
        (is (= :sqlite-file (:storage-kind storage)))
        (is (= (:db-path world) (:canonical-db-path storage)))
        (is (= (:canonical-db-path storage) (:storage-label storage)))
        (is (= (:datasource rt) (:connectable storage)))
        (is (nil? (:close-fn storage))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest memory-storage-runtime-serves-weaver-api-without-a-db-file
  (let [world (temp-world)
        rt (weaver-runtime/start! nil {:world world :publish? false :storage :sqlite-memory})]
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
      (let [strand (weaver/add rt {:title "Mem strand" :attributes {:owner "mem"}})]
        (is (= [(:id strand)] (mapv :id (weaver/ready rt)))))
      (testing "concurrent weaver API calls at test scale"
        (let [ids (->> (range 10)
                       (mapv (fn [i] (future (:id (weaver/add rt {:title (str "c" i)})))))
                       (mapv deref))]
          (is (= 10 (count (distinct ids))))
          (is (= 11 (count (weaver/list rt))))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))
    (testing "storage is destroyed with the runtime"
      (is (thrown? java.sql.SQLException (db/all-strands (:datasource rt)))))))

(deftest storage-selection-fails-loudly-on-bad-input
  (let [world (temp-world)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not take a database file"
                            (weaver-runtime/start! (db-test/temp-db-file)
                                                   {:world world :publish? false :storage :sqlite-memory})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown weaver storage kind"
                            (weaver-runtime/start! nil {:world world :publish? false :storage :postgres})))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest unpublished-runtimes-coexist-with-isolated-storage-and-registries
  (let [world-a (temp-world)
        world-b (temp-world)
        db-a (db-test/temp-db-file)
        db-b (db-test/temp-db-file)
        rt-a (weaver-runtime/start! db-a {:world world-a :publish? false})
        rt-b (weaver-runtime/start! db-b {:world world-b :publish? false})]
    (try
      (weaver/init rt-a)
      (weaver/init rt-b)
      (graph/register-query! rt-a 'mine [:= [:attr :owner] "a"])
      (graph/register-query! rt-b 'mine [:= [:attr :owner] "b"])
      (let [a (weaver/add rt-a {:title "A" :attributes {:owner "a"}})
            b (weaver/add rt-b {:title "B" :attributes {:owner "b"}})]
        (is (= [(:id a)] (mapv :id (weaver/list-query rt-a 'mine {}))))
        (is (= [(:id b)] (mapv :id (weaver/list-query rt-b 'mine {}))))
        (is (nil? (weaver/show rt-a (:id b))))
        (is (nil? (weaver/show rt-b (:id a)))))
      (finally
        (weaver-runtime/stop! rt-a)
        (weaver-runtime/stop! rt-b)
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
    (let [rt (weaver-runtime/start! nil {:world world :publish? false})]
      (try
        (is (= (get-in rt [:metadata :nonce]) (read-string (slurp marker))))
        (finally
          (weaver-runtime/stop! rt)
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
                            (weaver-runtime/start! nil {:world world :publish? false})))
      (is (nil? (metadata/read-metadata world)))
      (is (not (.exists (io/file (:state-dir world) "weaver.json"))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(defn- live-thread-names [prefix]
  (->> (Thread/getAllStackTraces)
       keys
       (filter #(.isAlive ^Thread %))
       (map #(.getName ^Thread %))
       (filter #(str/starts-with? % prefix))
       sort
       vec))

(defn- throwable-messages [t]
  (loop [messages []
         t t]
    (if t
      (recur (conj messages (ex-message t)) (ex-cause t))
      messages)))

(deftest startup-failure-closes-spool-state-before-storage
  (let [world (temp-world)
        thread-prefix (str "skein-test-startup-spool-" (random-uuid))]
    (try
      (spit (io/file (:config-dir world) "init.clj")
            (str "(require '[skein.api.runtime.alpha :as runtime]\n"
                 "         '[skein.core.weaver.runtime :as weaver-runtime])\n"
                 "(let [thread-prefix " (pr-str thread-prefix) "\n"
                 "      worker (doto (Thread. (reify Runnable\n"
                 "                               (run [_]\n"
                 "                                 (try\n"
                 "                                   (while true\n"
                 "                                     (Thread/sleep 100))\n"
                 "                                   (catch Throwable _ nil))))\n"
                 "                             (str thread-prefix \"-worker\"))\n"
                 "               (.setDaemon true))\n"
                 "      rt weaver-runtime/*runtime*]\n"
                 "  (.start worker)\n"
                 "  (runtime/spool-state rt :test/executor\n"
                 "                       (fn [] {:close-fn (fn []\n"
                 "                                          (.interrupt worker)\n"
                 "                                          (.join worker 1000))}))\n"
                 "  (runtime/spool-state rt :test/bad-close\n"
                 "                       (fn [] {:close-fn (fn []\n"
                 "                                          (throw (ex-info \"close boom\" {:source :spool-close})))}))\n"
                 "  (throw (ex-info \"post spool boom\" {:source :startup})))\n"))
      (let [failure (try
                      (weaver-runtime/start! nil {:world world :publish? false})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
        (is failure "expected startup failure")
        (is (= "Selected workspace startup file failed to load" (ex-message failure)))
        (is (some #(= "post spool boom" %) (throwable-messages failure)))
        (is (some #(= "Spool state close hook failed" (ex-message %))
                  (.getSuppressed failure))))
      (is (empty? (live-thread-names thread-prefix)))
      (is (nil? (metadata/read-metadata world)))
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
      (let [rt (weaver-runtime/start! db-file {:world world :publish? false})]
        (try
          (is (= [:shared :local] (read-string (slurp order-file))))
          (finally
            (weaver-runtime/stop! rt))))
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
      (let [rt (weaver-runtime/start! db-file {:world world :publish? false})]
        (try
          (is (= :shared (read-string (slurp marker))))
          (finally
            (weaver-runtime/stop! rt))))
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
        (weaver-runtime/start! db-file {:world world :publish? false})
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
               (runtime/reload! rt)))
        (is (= [:shared :local] (read-string (slurp order-file))))))))

(deftest reload-failure-preserves-earlier-registrations-and-fails-loudly
  ;; A failed reload must never leave a world with no useful ops (SPEC-004.C96).
  ;; The initial clear wipes pre-reload registrations and reinstalls built-ins;
  ;; a later startup file that throws still leaves every registration that
  ;; loaded before the failure in place, then rethrows loudly. The failure path
  ;; deliberately does NOT re-clear — that would take already-loaded userland
  ;; ops down with the failure, which is exactly the "zero useful ops until a
  ;; manual reset" cliff.
  (with-runtime
    (fn [rt _]
      (let [workspace (get-in rt [:metadata :config-dir])]
        (graph/register-query! rt 'prior [:= [:attr :owner] "prior"])
        (reset! delivered-events [])
        (events/register! rt :prior #{:strand/added} 'skein.weaver-test/capture-event {})
        (hooks/register! rt :prior #{:payload/received} 'skein.weaver-test/capture-hook {})
        (spit (io/file workspace "init.clj")
              (str "(require '[skein.api.current.alpha :as current]\n"
                   "         '[skein.api.graph.alpha :as graph]\n"
                   "         '[skein.api.events.alpha :as events])\n"
                   "(let [rt (current/runtime)]\n"
                   "  (graph/register-query! rt 'shared [:= [:attr :owner] \"shared\"])\n"
                   "  (events/register! rt :shared #{:strand/added} 'skein.weaver-test/capture-event)\n"
                   "  (events/enqueue! rt {:event/type :strand/added :event/id \"shared-only\" "
                   ":event/at \"2026-06-29T00:00:00Z\" :event/source :test}))\n"))
        (spit (io/file workspace "init.local.clj")
              "(throw (ex-info \"local boom\" {:source :local}))\n")
        (try
          (runtime/reload! rt)
          (is false "expected reload failure")
          (catch clojure.lang.ExceptionInfo e
            (is (= (.getCanonicalPath (io/file workspace "init.local.clj"))
                   (:file (ex-data e))))))
        ;; pre-reload registrations were wiped by the initial clear, but the
        ;; registrations init.clj made before init.local.clj threw survive.
        (is (= #{"shared"} (set (keys (graph/queries rt)))))
        (is (= [:shared] (mapv :key (events/handlers rt))))
        (is (= [] (hooks/hooks rt)))
        ;; built-in ops stay registered, so the world is never left op-less.
        (is (= ["help"] (mapv :name (weaver/ops rt))))
        ;; dispatch resumes on the failure path too, so init.clj's own enqueued
        ;; event reaches the handler it successfully registered.
        (is (wait-until #(some (fn [event] (= "shared-only" (:event/id event)))
                               @delivered-events)))))))

(deftest reload-layering-clears-events-and-hooks-before-local-overlay
  (with-runtime
    (fn [rt _]
      (let [workspace (get-in rt [:metadata :config-dir])]
        (events/register! rt :stale #{:strand/added} 'skein.weaver-test/capture-event {})
        (hooks/register! rt :stale #{:payload/received} 'skein.weaver-test/capture-hook {})
        (events/register! rt :fails #{:strand/added} 'skein.weaver-test/failing-event {})
        (events/enqueue! rt (test-event :strand/added "before-reload"))
        (events/await-quiescent! rt)
        (is (seq (events/recent-failures rt)))
        (spit (io/file workspace "init.clj")
              (str "(require '[skein.api.current.alpha :as current]\n"
                   "         '[skein.api.events.alpha :as events]\n"
                   "         '[skein.api.hooks.alpha :as hooks])\n"
                   "(let [rt (current/runtime)]\n"
                   "  (events/register! rt :shared #{:strand/added} 'skein.weaver-test/capture-event)\n"
                   "  (hooks/register! rt :shared #{:payload/received} 'skein.weaver-test/capture-hook))\n"))
        (spit (io/file workspace "init.local.clj")
              (str "(require '[skein.api.current.alpha :as current]\n"
                   "         '[skein.api.events.alpha :as events]\n"
                   "         '[skein.api.hooks.alpha :as hooks])\n"
                   "(let [rt (current/runtime)]\n"
                   "  (events/register! rt :local #{:strand/updated} 'skein.weaver-test/capture-event)\n"
                   "  (hooks/register! rt :local #{:strand/add-before-commit} 'skein.weaver-test/capture-hook))\n"))
        (runtime/reload! rt)
        (is (= #{:shared :local} (set (mapv :key (events/handlers rt)))))
        (is (= #{:shared :local} (set (mapv :key (hooks/hooks rt)))))
        (is (= [] (events/recent-failures rt)))))))

(deftest weaver-api-delegates-to-db-and-normalizes-results
  (with-runtime
    (fn [rt _]
      (is (= {:database "initialized"} (weaver/init rt)))
      (let [design (weaver/add rt {:title "Design" :state "closed" :attributes {:priority "high"}})
            docs (weaver/add rt {:title "Docs" :attributes {:owner "agent"}})]
        (is (= ["depends-on" "notes" "parent-of" "serves" "supersedes"] (weaver/acyclic-relations rt)))
        (is (= {:relation "blocks" :acyclic true} (weaver/declare-acyclic-relation! rt "blocks")))
        (is (= ["blocks" "depends-on" "notes" "parent-of" "serves" "supersedes"] (weaver/acyclic-relations rt)))
        (is (= {:priority "high"} (:attributes design)))
        (weaver/update rt (:id docs) {:attributes {:phase "write"}
                                      :edges [{:type "depends-on" :to (:id design)}]})
        (is (= {:owner "agent" :phase "write"} (:attributes (weaver/show rt (:id docs)))))
        (is (= #{(:id design) (:id docs)} (set (map :id (weaver/list rt)))))
        (is (= [(:id docs)] (mapv :id (weaver/ready rt))))))))

(deftest weaver-event-runtime-registers-dispatches-and-records-failures
  (with-runtime
    (fn [rt _]
      (reset! delivered-events [])
      (let [entry (events/register! rt :capture #{:strand/added} 'skein.weaver-test/capture-event {:purpose :test})]
        (is (= {:key :capture
                :types #{:strand/added}
                :fn 'skein.weaver-test/capture-event
                :metadata {:purpose :test}}
               entry))
        (is (= [entry] (events/handlers rt)))
        (is (= {:key :capture
                :types #{:strand/updated}
                :fn 'skein.weaver-test/capture-event
                :metadata {:purpose :replacement}}
               (events/register! rt :capture #{:strand/updated} 'skein.weaver-test/capture-event {:purpose :replacement})))
        (is (= [] @delivered-events))
        (events/enqueue! rt (test-event :strand/added "ignored"))
        (events/await-quiescent! rt)
        (is (= [] @delivered-events))
        (events/enqueue! rt (test-event :strand/updated "delivered"))
        (events/await-quiescent! rt)
        (is (= [(test-event :strand/updated "delivered")] @delivered-events))
        (events/register! rt :fails #{:strand/updated} 'skein.weaver-test/failing-event {})
        (events/enqueue! rt (test-event :strand/updated "fails"))
        (events/await-quiescent! rt)
        (let [failure (last (events/recent-failures rt))]
          (is (= :fails (:handler/key failure)))
          (is (= 'skein.weaver-test/failing-event (:handler/fn failure)))
          (is (= "fails" (:event/id failure)))
          (is (= :strand/updated (:event/type failure)))
          (is (= "handler failed" (:exception/message failure)))
          (is (string? (:failed/at failure))))
        (is (= {:unregistered :capture} (events/unregister! rt :capture)))
        (is (= [:fails] (mapv :key (events/handlers rt))))))))

(deftest weaver-supersession-emits-semantic-event
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (events/register! rt :capture #{:strand/superseded} 'skein.weaver-test/capture-event {})
      (let [old (weaver/add rt {:title "Old"})
            replacement (weaver/add rt {:title "Replacement"})
            dependent (weaver/add rt {:title "Dependent"})]
        (weaver/update rt (:id dependent) {:edges [{:type "depends-on" :to (:id old)}]})
        (reset! delivered-events [])
        (let [result (weaver/supersede rt (:id old) (:id replacement))
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
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register! rt :capture #{:strand/superseded} 'skein.weaver-test/capture-event {})
      (hooks/register! rt :capture-supersede #{:strand/supersede-before-commit} 'skein.weaver-test/capture-hook {})
      (let [old (weaver/add rt {:title "Old"})
            replacement (weaver/add rt {:title "Replacement"})
            dependent (weaver/add rt {:title "Dependent"})]
        (weaver/update rt (:id dependent) {:edges [{:type "depends-on" :to (:id old) :attributes {:reason "old"}}]})
        (reset! delivered-events [])
        (let [result (weaver/supersede rt (:id old) (:id replacement))
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
        (hooks/unregister! rt :capture-supersede)
        (let [reject-old (weaver/add rt {:title "Reject old"})
              reject-replacement (weaver/add rt {:title "Reject replacement"})
              reject-dependent (weaver/add rt {:title "Reject dependent"})]
          (weaver/update rt (:id reject-dependent) {:edges [{:type "depends-on" :to (:id reject-old) :attributes {:reason "rollback"}}]})
          (reset! delivered-events [])
          (hooks/register! rt :reject-supersede #{:strand/supersede-before-commit} 'skein.weaver-test/rejecting-hook {})
          (try
            (weaver/supersede rt (:id reject-old) (:id reject-replacement))
            (is false "expected supersede hook rejection")
            (catch clojure.lang.ExceptionInfo e
              (is (= "hook/failed" (:code (ex-data e))))
              (is (= :strand/supersede-before-commit (:hook/type (ex-data e))))
              (is (= :reject-supersede (:hook/key (ex-data e))))
              (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
          (is (= "active" (:state (weaver/show rt (:id reject-old)))))
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
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register! rt :capture #{:strand/superseded} 'skein.weaver-test/capture-event {})
      (hooks/register! rt :capture-supersede #{:strand/supersede-before-commit} 'skein.weaver-test/capture-hook {})
      (let [old (weaver/add rt {:title "Old"})
            replacement (weaver/add rt {:title "Replacement"})
            closed-replacement (weaver/add rt {:title "Closed" :state "closed"})
            dependent (weaver/add rt {:title "Dependent"})]
        (weaver/update rt (:id dependent) {:edges [{:type "depends-on" :to (:id old)}]})
        (weaver/update rt (:id replacement) {:edges [{:type "depends-on" :to (:id dependent)}]})
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (let [before (db-test/graph-snapshot (:datasource rt))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Replacement strand must be active"
                                (weaver/supersede rt (:id old) (:id closed-replacement))))
          (is (empty? @hook-contexts))
          (is (empty? @delivered-events))
          (is (= before (db-test/graph-snapshot (:datasource rt)))))
        (let [before (db-test/graph-snapshot (:datasource rt))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (weaver/supersede rt (:id old) (:id replacement))))
          (is (empty? @hook-contexts))
          (is (empty? @delivered-events))
          (is (= before (db-test/graph-snapshot (:datasource rt)))))))))

(deftest weaver-strand-mutations-emit-events-after-success
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (events/register! rt :capture #{:strand/added :strand/updated :strand/burned} 'skein.weaver-test/capture-event {})
      (let [added (weaver/add rt {:title "Evented" :attributes {:owner "agent"}})
            add-event (first (wait-for-events 1))]
        (is (= :strand/added (:event/type add-event)))
        (is (string? (:event/id add-event)))
        (is (string? (:event/at add-event)))
        (is (= :skein.api.weaver.alpha (:event/source add-event)))
        (is (= (:id added) (:strand/id add-event)))
        (is (= added (:strand add-event)))
        (let [updated (weaver/update rt (:id added) {:state "closed" :attributes {:phase "done"}})
              update-event (second (wait-for-events 2))]
          (is (= :strand/updated (:event/type update-event)))
          (is (= (:id added) (:strand/id update-event)))
          (is (= {:state "closed" :attributes {:phase "done"}} (:strand/patch update-event)))
          (is (= "active" (get-in update-event [:strand/before :state])))
          (is (= {:owner "agent"} (get-in update-event [:strand/before :attributes])))
          (is (= "closed" (get-in update-event [:strand/after :state])))
          (is (= {:owner "agent" :phase "done"} (get-in update-event [:strand/after :attributes])))
          (is (= updated (:strand/after update-event))))
        (let [edge-target (weaver/add rt {:title "Target"})]
          (reset! delivered-events [])
          (let [edge-patch {:edges [{:type "depends-on" :to (:id edge-target)}]}
                result (weaver/update rt (:id added) edge-patch)
                update-event (first (filter #(= :strand/updated (:event/type %)) (wait-for-events 2)))]
            (is (= result (:strand/after update-event)))
            (is (= edge-patch (:strand/patch update-event)))))
        (reset! delivered-events [])
        (let [pre-burn (weaver/show rt (:id added))
              burn-result (graph/burn-by-id! rt (:id added))
              burn-event (first (wait-for-events 1))]
          (is (= {:burned [(:id added)] :count 1} burn-result))
          (is (= :strand/burned (:event/type burn-event)))
          (is (= [(:id added)] (:strand/requested-ids burn-event)))
          (is (= [(:id added)] (:strand/burned-ids burn-event)))
          (is (= [pre-burn] (:strand/before burn-event))))))))

(deftest trusted-handler-burns-temporary-children-after-parent-update
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! cleanup-events [])
      (events/register! rt :cleanup-temporary #{:strand/updated}
                        'skein.weaver-test/burn-temporary-children-on-inactive-parent
                        {:purpose :integration-cleanup})
      (let [parent (weaver/add rt {:title "Parent"})
            temporary-child (weaver/add rt {:title "Temporary child" :attributes {:temporary "true"}})
            durable-child (weaver/add rt {:title "Durable child" :attributes {:temporary "false"}})
            unrelated-temporary (weaver/add rt {:title "Unrelated temporary" :attributes {:temporary "true"}})]
        (weaver/update rt (:id parent) {:edges [{:type "parent-of" :to (:id temporary-child)}
                                                {:type "parent-of" :to (:id durable-child)}]})
        (weaver/update rt (:id parent) {:state "closed"})
        (is (wait-until #(= [{:root (:id parent) :burned [(:id temporary-child)]}]
                            @cleanup-events)))
        (is (nil? (weaver/show rt (:id temporary-child))))
        (is (= (:id durable-child) (:id (weaver/show rt (:id durable-child)))))
        (is (= (:id unrelated-temporary) (:id (weaver/show rt (:id unrelated-temporary)))))))))

(deftest event-handler-slowness-and-failure-do-not-fail-original-mutation
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (reset! handler-started (promise))
      (reset! handler-release (promise))
      (events/register! rt :slow #{:strand/updated} 'skein.weaver-test/slow-capture-event {})
      (events/register! rt :fails #{:strand/updated} 'skein.weaver-test/failing-event {})
      (let [strand (weaver/add rt {:title "Slow handler target"})
            update-result (future (weaver/update rt (:id strand) {:state "closed"}))]
        (try
          (is (deref @handler-started (test-support/await-budget-ms 1000) false))
          (let [updated (deref update-result (test-support/await-budget-ms 1000) ::mutation-blocked)]
            (is (not= ::mutation-blocked updated))
            (is (= "closed" (:state updated))))
          (is (= [] @delivered-events))
          (deliver @handler-release true)
          (is (wait-until #(= 1 (count @delivered-events))))
          (is (wait-until #(some (fn [failure]
                                   (= :fails (:handler/key failure)))
                                 (events/recent-failures rt))))
          (finally
            (deliver @handler-release true)))))))

(deftest event-queue-capacity-and-reload-semantics
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (reset! handler-started (promise))
      (reset! handler-release (promise))
      (events/register! rt :slow #{:x} 'skein.weaver-test/slow-capture-event {})
      (events/enqueue! rt (test-event :x "started"))
      (is (deref @handler-started (test-support/await-budget-ms 1000) false))
      (doseq [n (range weaver-runtime/event-queue-capacity)]
        (events/enqueue! rt (test-event :x (str "queued-" n))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"queue is full"
                            (events/enqueue! rt (test-event :x "full"))))
      (let [init (io/file (get-in rt [:metadata :config-dir]) "init.clj")]
        (spit init (str "(require '[skein.api.current.alpha :as current]\n"
                        "         '[skein.api.events.alpha :as events])\n"
                        "(let [rt (current/runtime)]\n"
                        "  (events/register! rt :after-reload #{:x} 'skein.weaver-test/capture-event))\n"))
        (runtime/reload! rt)
        (deliver @handler-release true)
        (is (= [:after-reload] (mapv :key (events/handlers rt))))
        (is (= [] (events/recent-failures rt)))
        (is (not (wait-until #(some (fn [event] (= "queued-0" (:event/id event)))
                                    @delivered-events))))
        (events/enqueue! rt (test-event :x "after-reload"))
        (is (wait-until #(some (fn [event] (= "after-reload" (:event/id event)))
                               @delivered-events)))))))

(deftest weaver-apply-batch-emits-batch-event-before-compatibility-fanout
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [existing-b (weaver/add rt {:title "Existing B" :attributes {:owner "agent"}})
            existing-a (weaver/add rt {:title "Existing A" :attributes {:owner "agent"}})
            burned (weaver/add rt {:title "Burned"})]
        (drain-events! rt)
        (reset! delivered-events [])
        (events/register! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                          'skein.weaver-test/capture-event {})
        (let [result (batch/apply! rt {:refs {:existing-b (:id existing-b)
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
          (drain-events! rt)
          (is (= [:batch/applied :strand/added :strand/added :strand/updated :strand/updated :strand/burned]
                 (mapv :event/type @delivered-events))))))))

(deftest weaver-apply-batch-edge-only-emits-only-batch-event
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [from (weaver/add rt {:title "From"})
            to (weaver/add rt {:title "To"})]
        (events/await-quiescent! rt)
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (events/register! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                          'skein.weaver-test/capture-event {})
        (hooks/register! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
        (let [result (batch/apply! rt {:refs {:from (:id from) :to (:id to)}
                                       :edges [{:op :upsert :from :from :to :to :type "related-to"}]})
              events (wait-for-events 1)
              batch-event (first (filter #(= :batch/applied (:event/type %)) events))
              context (last @hook-contexts)]
          (events/await-quiescent! rt)
          (is (= [:batch/applied] (mapv :event/type @delivered-events)))
          (is (= (:edges result) (:batch/edges batch-event)))
          (is (= [] (:batch/created context) (:batch/updated context) (:batch/burned context)))
          (is (= (:edges result) (:batch/edge-ops context))))))))

(deftest weaver-apply-batch-hooks-normalize-context-and-reject-atomically
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                        'skein.weaver-test/capture-event {})
      (hooks/register! rt :parse #{:attributes/normalize} 'skein.weaver-test/parse-story-points-hook {})
      (hooks/register! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
      (let [existing (weaver/add rt {:title "Existing" :attributes {:owner "agent"}})
            burnable (weaver/add rt {:title "Burnable"})]
        (drain-events! rt)
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
        (hooks/unregister! rt :capture-batch)
        (hooks/register! rt :reject-batch #{:batch/apply-before-commit} 'skein.weaver-test/rejecting-hook {})
        (let [keep (weaver/add rt {:title "Keep" :attributes {:stable true}})
              burn-reject (weaver/add rt {:title "Burn reject"})
              before (db-test/graph-snapshot (:datasource rt))]
          (drain-events! rt)
          (reset! delivered-events [])
          (try
            (batch/apply! rt {:refs {:keep (:id keep) :burn (:id burn-reject)}
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
          (drain-events! rt)
          (is (= before (db-test/graph-snapshot (:datasource rt))))
          (is (empty? @delivered-events)))))))

(deftest weaver-burn-by-ids-event-captures-pre-delete-rows-and-requested-ids
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (events/register! rt :capture #{:strand/burned} 'skein.weaver-test/capture-event {})
      (let [a (weaver/add rt {:title "A"})
            b (weaver/add rt {:title "B"})
            requested [(:id b) (:id a) (:id b)]
            result (graph/burn-by-ids! rt requested)
            burn-event (first (wait-for-events 1))]
        (is (= {:burned [(:id b) (:id a)] :count 2} result))
        (is (= requested (:strand/requested-ids burn-event)))
        (is (= [(:id b) (:id a)] (:strand/burned-ids burn-event)))
        (is (= [b a] (:strand/before burn-event)))
        (is (= [] (weaver/list rt)))))))

(deftest weaver-event-runtime-fails-loudly-on-invalid-registration
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key" (events/register! rt [] #{:x} 'skein.weaver-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty" (events/register! rt :bad #{} 'skein.weaver-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"set" (events/register! rt :bad [:x] 'skein.weaver-test/capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified" (events/register! rt :bad #{:x} 'capture-event {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not be resolved" (events/register! rt :bad #{:x} 'missing.ns/handler {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callable" (events/register! rt :bad #{:x} 'skein.weaver-test/not-callable-event-handler {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"metadata" (events/register! rt :bad #{:x} 'skein.weaver-test/capture-event :opaque)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Event requires key" (events/enqueue! rt {:event/type :x :event/id "missing-shape"}))))))

(deftest weaver-hook-registry-registers-replaces-orders-and-unregisters
  (with-runtime
    (fn [rt _]
      (let [entry (hooks/register! rt :capture #{:payload/received} 'skein.weaver-test/capture-hook {:doc "Capture"})]
        (is (= {:key :capture
                :types #{:payload/received}
                :fn 'skein.weaver-test/capture-hook
                :order 0
                :metadata {:doc "Capture"}}
               entry))
        (is (= [entry] (hooks/hooks rt)))
        (is (not (contains? (first (hooks/hooks rt)) :fn-value)))
        (is (ifn? (:fn-value (get @(:hook-registry rt) :capture))))
        (let [replacement (hooks/register! rt :capture #{:strand/add-before-commit} 'skein.weaver-test/capture-hook {:order 10 :doc "Replaced"})
              early (hooks/register! rt "early" #{:payload/received} 'skein.weaver-test/capture-hook {:order -1})
              peer-a (hooks/register! rt :a #{:payload/received} 'skein.weaver-test/capture-hook {})
              peer-b (hooks/register! rt :b #{:payload/received} 'skein.weaver-test/capture-hook {})]
          (is (= ["early" :a :b :capture] (mapv :key (hooks/hooks rt))))
          (is (= [early peer-a peer-b replacement] (hooks/hooks rt)))
          (is (= :a (hooks/unregister! rt :a)))
          (is (= ["early" :b :capture] (mapv :key (hooks/hooks rt)))))))))

(deftest weaver-hook-registry-validates-inputs
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key" (hooks/register! rt [] #{:x} 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty" (hooks/register! rt :bad #{} 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"set" (hooks/register! rt :bad [:x] 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keywords" (hooks/register! rt :bad #{"x"} 'skein.weaver-test/capture-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified" (hooks/register! rt :bad #{:x} 'capture-hook {})))
      (is (thrown? Throwable (hooks/register! rt :bad #{:x} 'missing.ns/hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callable" (hooks/register! rt :bad #{:x} 'skein.weaver-test/not-callable-hook {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts" (hooks/register! rt :bad #{:x} 'skein.weaver-test/capture-hook :opaque)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"data-first" (hooks/register! rt :bad #{:x} 'skein.weaver-test/capture-hook {:opaque (Object.)})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer" (hooks/register! rt :bad #{:x} 'skein.weaver-test/capture-hook {:order 1.5}))))))

(deftest reload-config-clears-and-reinstalls-hooks
  (with-runtime
    (fn [rt _]
      (let [init (io/file (get-in rt [:metadata :config-dir]) "init.clj")]
        (hooks/register! rt :stale #{:payload/received} 'skein.weaver-test/capture-hook {})
        (spit init (str "(require '[skein.api.current.alpha :as current]\n"
                        "         '[skein.api.hooks.alpha :as hooks])\n"
                        "(let [rt (current/runtime)]\n"
                        "  (hooks/register! rt :fresh #{:payload/received} 'skein.weaver-test/capture-hook {:order 2}))\n"))
        (runtime/reload! rt)
        (is (= [{:key :fresh
                 :types #{:payload/received}
                 :fn 'skein.weaver-test/capture-hook
                 :order 2
                 :metadata {}}]
               (hooks/hooks rt)))))))

(deftest attribute-normalize-hooks-thread-transform-results-for-add-and-update
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register! rt :capture #{:strand/added :strand/updated} 'skein.weaver-test/capture-event {})
      (hooks/register! rt :parse #{:attributes/normalize} 'skein.weaver-test/parse-story-points-hook {:order 0})
      (hooks/register! rt :flag #{:attributes/normalize} 'skein.weaver-test/add-normalized-flag-hook {:order 1})
      (let [added (weaver/add rt {:title "Normalize" :attributes {"storyPoints" "3"}})
            _add-event (first (wait-for-events 1))
            updated (weaver/update rt (:id added) {:attributes {:storyPoints "5"}})
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
      (weaver/init rt)
      (reset! expected-hook-loader (:spool-classloader rt))
      (hooks/register! rt :classloader #{:attributes/normalize} 'skein.weaver-test/asserting-classloader-hook {})
      (is (= {:a "b"} (:attributes (weaver/add rt {:title "Classloader" :attributes {:a "b"}})))))))

(deftest attribute-normalize-hooks-require-wrapper-and-json-compatible-values
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (hooks/register! rt :noop #{:attributes/normalize} 'skein.weaver-test/noop-normalize-hook {})
      (is (= {:a "b"} (:attributes (weaver/add rt {:title "Noop" :attributes {:a "b"}}))))
      (doseq [[k f] [[:nil 'skein.weaver-test/nil-normalize-hook]
                     [:plain 'skein.weaver-test/non-wrapper-normalize-hook]
                     [:invalid 'skein.weaver-test/invalid-attributes-hook]]]
        (hooks/register! rt k #{:attributes/normalize} f {})
        (is (thrown? clojure.lang.ExceptionInfo
                     (weaver/add rt {:title (str "Bad " k) :attributes {:a "b"}})))
        (hooks/unregister! rt k)))))

(deftest attribute-normalize-hook-failures-rollback-and-preserve-cause-data
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! delivered-events [])
      (events/register! rt :capture #{:strand/added :strand/updated} 'skein.weaver-test/capture-event {})
      (hooks/register! rt :reject #{:attributes/normalize} 'skein.weaver-test/rejecting-normalize-hook {})
      (try
        (weaver/add rt {:title "Rejected" :attributes {:a "b"}})
        (is false "expected hook rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= :attributes/normalize (:hook/type (ex-data e))))
          (is (= :reject (:hook/key (ex-data e))))
          (is (= 'skein.weaver-test/rejecting-normalize-hook (:hook/fn (ex-data e))))
          (is (= "policy/rejected" (:hook/cause-code (ex-data e))))
          (is (= {:code "policy/rejected" :reason :test} (:exception/data (ex-data e))))))
      (events/await-quiescent! rt)
      (is (empty? (weaver/list rt)))
      (is (empty? @delivered-events))
      (hooks/unregister! rt :reject)
      (hooks/register! rt :wrapped #{:attributes/normalize} 'skein.weaver-test/wrapping-rejecting-normalize-hook {})
      (try
        (weaver/add rt {:title "Wrapped" :attributes {:a "b"}})
        (is false "expected wrapped hook rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= "policy/inner" (:hook/cause-code (ex-data e))))))
      (hooks/unregister! rt :wrapped)
      (let [strand (weaver/add rt {:title "Stored" :attributes {:a "b"}})]
        (wait-for-events 1)
        (reset! delivered-events [])
        (hooks/register! rt :reject #{:attributes/normalize} 'skein.weaver-test/rejecting-normalize-hook {})
        (is (thrown? clojure.lang.ExceptionInfo
                     (weaver/update rt (:id strand) {:attributes {:c "d"}})))
        (events/await-quiescent! rt)
        (is (= {:a "b"} (:attributes (weaver/show rt (:id strand)))))
        (is (empty? @delivered-events))))))

(deftest strand-pre-commit-hooks-gate-add-update-and-burn
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register! rt :capture #{:strand/added :strand/updated :strand/burned} 'skein.weaver-test/capture-event {})
      (hooks/register! rt :capture-add #{:strand/add-before-commit} 'skein.weaver-test/capture-hook {})
      (let [created (weaver/add rt {:title "Hooked" :attributes {:owner "agent"}})
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
        (hooks/register! rt :reject-add #{:strand/add-before-commit} 'skein.weaver-test/rejecting-hook {})
        (try
          (weaver/add rt {:title "Rejected" :attributes {:owner "blocked"}})
          (is false "expected add hook rejection")
          (catch clojure.lang.ExceptionInfo e
            (is (= "hook/failed" (:code (ex-data e))))
            (is (= :strand/add-before-commit (:hook/type (ex-data e))))
            (is (= :reject-add (:hook/key (ex-data e))))
            (is (= 'skein.weaver-test/rejecting-hook (:hook/fn (ex-data e))))
            (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
        (events/await-quiescent! rt)
        (is (nil? (some #(when (= "Rejected" (:title %)) %) (weaver/list rt))))
        (is (= 1 (count @delivered-events)))
        (hooks/unregister! rt :reject-add)
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (let [target (weaver/add rt {:title "Target"})]
          (wait-for-events 1)
          (reset! hook-contexts [])
          (reset! delivered-events [])
          (hooks/register! rt :capture-update #{:strand/update-before-commit} 'skein.weaver-test/capture-hook {})
          (let [patch {:title "Updated"
                       :state "closed"
                       :attributes {:phase "done"}
                       :edges [{:type "depends-on" :to (:id target)}]}
                updated (weaver/update rt (:id created) patch)
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
            (hooks/register! rt :reject-update #{:strand/update-before-commit} 'skein.weaver-test/rejecting-hook {})
            (try
              (weaver/update rt (:id created) {:title "Rejected update"
                                               :attributes {:phase "blocked"}
                                               :edges [{:type "parent-of" :to (:id target)}]})
              (is false "expected update hook rejection")
              (catch clojure.lang.ExceptionInfo e
                (is (= "hook/failed" (:code (ex-data e))))
                (is (= :strand/update-before-commit (:hook/type (ex-data e))))
                (is (= :reject-update (:hook/key (ex-data e))))
                (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
            (events/await-quiescent! rt)
            (is (= updated (weaver/show rt (:id created))))
            (is (empty? (db/execute! (:datasource rt)
                                     ["SELECT 1 FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = 'parent-of'"
                                      (:id created) (:id target)])))
            (is (= 1 (count @delivered-events)))
            (hooks/unregister! rt :reject-update)))
        (reset! hook-contexts [])
        (reset! delivered-events [])
        (hooks/register! rt :capture-burn #{:strand/burn-before-commit} 'skein.weaver-test/capture-hook {})
        (let [requested [(:id created) (:id created)]
              burn-result (graph/burn-by-ids! rt requested)
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
        (let [burn-target (weaver/add rt {:title "Burn reject"})
              edge-target (weaver/add rt {:title "Burn edge target"})]
          (weaver/update rt (:id burn-target) {:edges [{:type "depends-on" :to (:id edge-target)}]})
          (let [burn-target (weaver/show rt (:id burn-target))]
            (events/await-quiescent! rt)
            (reset! delivered-events [])
            (hooks/register! rt :reject-burn #{:strand/burn-before-commit} 'skein.weaver-test/rejecting-hook {})
            (try
              (graph/burn-by-ids! rt [(:id burn-target)])
              (is false "expected burn hook rejection")
              (catch clojure.lang.ExceptionInfo e
                (is (= "hook/failed" (:code (ex-data e))))
                (is (= :strand/burn-before-commit (:hook/type (ex-data e))))
                (is (= :reject-burn (:hook/key (ex-data e))))
                (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
            (events/await-quiescent! rt)
            (is (= burn-target (weaver/show rt (:id burn-target))))
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
        (is (= {"mine" owner-query} (graph/register-query! rt 'mine owner-query)))
        (is (= owner-query (graph/resolve-query rt :mine)))
        (is (= {"mine" owner-query} (graph/queries rt)))
        (is (= {"open" open-query} (graph/load-queries! rt {:open open-query})))
        (is (= {"mine" owner-query
                "open" open-query}
               (graph/queries rt)))))))

(deftest weaver-query-registry-accepts-parameterized-in-queries
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [agent (weaver/add rt {:title "Agent" :attributes {:owner "agent"}})
            human (weaver/add rt {:title "Human" :attributes {:owner "human"}})
            owners-query {:params [:owners]
                          :where [:in [:attr :owner] [:param :owners]]}]
        (is (= {"owners" owners-query} (graph/register-query! rt 'owners owners-query)))
        (is (= [(:id agent)] (mapv :id (weaver/list-query rt :owners {:owners ["agent"]}))))
        (is (= #{(:id agent) (:id human)}
               (set (map :id (weaver/list-query rt :owners {:owners ["agent" "human"]})))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":in values must be a non-empty collection"
                              (weaver/list-query rt :owners {:owners "agent"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":in values must be a non-empty collection"
                              (weaver/list-query rt :owners {:owners []})))))))

(deftest weaver-query-registry-accepts-edge-predicates
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [blocker (weaver/add rt {:title "Blocker"})
            blocked (weaver/add rt {:title "Blocked" :attributes {:owner "agent"}})
            edge-query {:params [:relation]
                        :where [:edge/out [:param :relation] [:= :state "active"]]}]
        (weaver/update rt (:id blocked) {:edges [{:type "depends-on" :to (:id blocker)}]})
        (is (= {"blocked" edge-query} (graph/register-query! rt 'blocked edge-query)))
        (is (= [(:id blocked)] (mapv :id (weaver/list-query rt :blocked {:relation "depends-on"}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nested edge predicates"
                              (graph/register-query! rt 'bad-edge
                                                     [:edge/out "depends-on"
                                                      [:edge/in "depends-on" [:= :state "active"]]])))
        (is (= {"blocked" edge-query} (graph/queries rt)))))))

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
        (graph/load-queries! rt {:open open-query
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
               (graph/query-metadata rt)))

        (is (= {:name "mine"
                :params [:owner]
                :referenced-params [:owner]
                :where (:where owner-query)
                :definition owner-query
                :where-form (pr-str (:where owner-query))
                :definition-form (pr-str owner-query)
                :summary (str "Invoke this query with `strand list --query <name>` or `strand ready --query <name>` "
                              "and pass runtime values with repeated `--param key=value` arguments.")}
               (graph/query-explain rt :mine)))

        (try
          (graph/query-explain rt :missing)
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
      (weaver/init rt)
      (let [agent (weaver/add rt {:title "Agent" :attributes {:owner "agent"}})
            human (weaver/add rt {:title "Human" :attributes {:owner "human"}})
            feature (weaver/add rt {:title "Feature" :attributes {:kind "feature"}})]
        (weaver/update rt (:id feature) {:edges [{:type "parent-of" :to (:id agent)}
                                                 {:type "parent-of" :to (:id human)}]})
        (graph/register-query! rt 'agent-owned {:params [:owner]
                                                :where [:= [:attr :owner] [:param :owner]]})
        (is (= [(:id agent)] (graph/query-ids rt 'agent-owned {:owner "agent"})))
        (is (= [(:id human)] (graph/query-ids rt [:= [:attr :owner] "human"] {})))
        (is (= [(:id human) (:id agent)]
               (mapv :id (graph/strands-by-ids rt [(:id human) (:id agent) (:id human)]))))
        (is (= [(:id feature)] (graph/ancestor-root-ids rt [(:id agent)])))
        (is (= #{(:id feature) (:id agent) (:id human)}
               (set (map :id (:strands (graph/subgraph rt [(:id feature)]))))))))))

(deftest weaver-view-registry-operations
  (with-runtime
    (fn [rt _]
      (is (= {:name "daily" :fn 'skein.weaver-test/test-view}
             (views/register-view! rt 'daily 'skein.weaver-test/test-view)))
      (is (= [{:name "daily" :fn 'skein.weaver-test/test-view}]
             (views/views rt)))
      (is (= {:view :test :params {:owner "agent"}}
             (views/view! rt :daily {:owner "agent"})))
      (is (= {:name "daily" :fn 'skein.weaver-test/replacement-view}
             (views/register-view! rt :daily 'skein.weaver-test/replacement-view)))
      (is (= [{:name "daily" :fn 'skein.weaver-test/replacement-view}]
             (views/views rt)))
      (is (= {:view :replacement :params {}}
             (views/view! rt 'daily {})))
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            lib (symbol (str "view-" suffix))
            ns-sym (symbol (str "demo.view-" suffix))
            root (write-view-lib! (get-in rt [:metadata :config-dir]) lib ns-sym)]
        (.addURL ^clojure.lang.DynamicClassLoader (:spool-classloader rt)
                 (.toURL (.toURI (io/file root "src"))))
        (load-file (str (io/file root "src" (str (-> (str ns-sym)
                                                     (str/replace \- \_)
                                                     (str/replace \. java.io.File/separatorChar))
                                                 ".clj"))))
        (views/register-view! rt 'synced-lib (symbol (str ns-sym) "render"))
        (is (= {:lib-view {:from :synced}}
               (views/view! rt 'synced-lib {:from :synced}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"View not found"
                            (views/view! rt 'missing {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fully qualified"
                            (views/register-view! rt 'bad 'unqualified)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (views/register-view! rt 'user/daily 'skein.weaver-test/test-view))))))

(deftest weaver-op-registry-and-built-in-help
  (with-runtime
    (fn [rt _]
      (is (= {:name "custom"
              :fn 'skein.weaver-test/test-op
              :stream? false
              :deadline-class :standard
              :hook-class :mutating
              :provenance 'skein.weaver-test
              :doc "Echo argv"}
             (weaver/register-op! rt 'custom "Echo argv" 'skein.weaver-test/test-op)))
      (is (= {:operation "custom" :argv ["--flag" "value"]}
             (weaver/op! rt 'custom ["--flag" "value"])))
      (let [help (weaver/op! rt 'help [])]
        (is (some #(= "help" (:name %)) (:ops help))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Operation not found"
                            (weaver/op! rt 'missing [])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Operation function"
                            (weaver/register-op! rt 'bad 'unqualified))))))

(deftest weaver-op-metadata-defaults-and-validation
  (with-runtime
    (fn [rt _]
      (testing "no-metadata registration records defaults and provenance"
        (is (= {:name "bare"
                :fn 'skein.weaver-test/test-op
                :stream? false
                :deadline-class :standard
                :hook-class :mutating
                :provenance 'skein.weaver-test}
               (weaver/register-op! rt 'bare 'skein.weaver-test/test-op))))
      (testing "full metadata map is recorded; stream ops default to :unbounded"
        (is (= {:name "streamer"
                :fn 'skein.weaver-test/test-op
                :stream? true
                :deadline-class :unbounded
                :hook-class :read
                :provenance 'skein.weaver-test
                :doc "Stream op"
                :arg-spec {:opts [:limit]}}
               (weaver/register-op! rt 'streamer
                                    {:doc "Stream op"
                                     :arg-spec {:opts [:limit]}
                                     :stream? true
                                     :hook-class :read}
                                    'skein.weaver-test/test-op))))
      (testing "valid return declarations are retained"
        (is (= {:type :collection :items :string}
               (:returns (weaver/register-op! rt 'declared
                                              {:returns {:type :collection :items :string}}
                                              'skein.weaver-test/test-op))))
        (is (= {:subcommands
                {"list" {:stream {:emits :string :result :boolean}}}}
               (:returns
                (weaver/register-op! rt 'declared-subcommands
                                     {:arg-spec {:op "declared-subcommands"
                                                 :subcommands {"list" {}}}
                                      :stream? true
                                      :returns {:subcommands
                                                {"list" {:stream {:emits :string
                                                                  :result :boolean}}}}}
                                     'skein.weaver-test/test-op)))))
      (testing "return routing and stream alignment fail before registration"
        (doseq [[name opts reason]
                [['bad-return-shape
                  {:returns [:nullable :json]}
                  :invalid-nullable]
                 ['flat-with-subcommands
                  {:returns {:subcommands {"run" :string}}}
                  :return-routing-misalignment]
                 ['subcommands-missing-case
                  {:arg-spec {:op "subcommands-missing-case"
                              :subcommands {"run" {} "list" {}}}
                   :returns {:subcommands {"run" :string}}}
                  :return-subcommand-misalignment]
                 ['stream-with-flat-return
                  {:stream? true :returns :string}
                  :return-stream-misalignment]
                 ['flat-with-stream-return
                  {:returns {:stream {:emits :string :result :boolean}}}
                  :return-stream-misalignment]]]
          (let [before (weaver/ops rt)
                e (is (thrown? clojure.lang.ExceptionInfo
                               (weaver/register-op! rt name opts 'skein.weaver-test/test-op)))]
            (is (= reason (:reason (ex-data e))))
            (is (= before (weaver/ops rt)))
            (is (not-any? #(= (clojure.core/name name) (:name %)) (weaver/ops rt))))))
      (testing "flat arg-specs are validated at registration"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-flat
                                                           {:arg-spec {:op "bad-flat"
                                                                       :flags {"limit" {:type :int}}}}
                                                           'skein.weaver-test/test-op)))]
          (is (= "bad-flat" (:operation (ex-data e))))
          (is (= :invalid-flag (:reason (ex-data e))))))
      (testing "subcommand arg-specs are validated at registration"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-subcommands
                                                           {:arg-spec {:op "bad-subcommands"
                                                                       :flags {:verbose {:type :boolean}}
                                                                       :subcommands {"run" {:doc "Run"}}}}
                                                           'skein.weaver-test/test-op)))]
          (is (= "bad-subcommands" (:operation (ex-data e))))
          (is (= :invalid-subcommands (:reason (ex-data e))))
          (is (= :flags (:field (ex-data e))))))
      (testing "registration preserves structured nested arg-spec validation context"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-nested-subcommand
                                                           {:arg-spec {:op "bad-nested-subcommand"
                                                                       :subcommands {"run" 42}}}
                                                           'skein.weaver-test/test-op)))]
          (is (= "bad-nested-subcommand" (:operation (ex-data e))))
          (is (= :invalid-subcommand-spec (:reason (ex-data e))))
          (is (= "run" (:subcommand (ex-data e))))
          (is (= :subcommands (:field (ex-data e))))
          (is (= 42 (:value (ex-data e))))))
      (testing "reserved help token subcommand names fail loudly"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/register-op! rt 'bad-help-subcommand
                                                           {:arg-spec {:op "bad-help-subcommand"
                                                                       :subcommands {"help" {:doc "Reserved"}}}}
                                                           'skein.weaver-test/test-op)))]
          (is (= "bad-help-subcommand" (:operation (ex-data e))))
          (is (= :reserved-subcommand-name (:reason (ex-data e))))
          (is (= "help" (:name (ex-data e))))))
      (testing "replace-op! also validates subcommand arg-specs before replacing"
        (weaver/register-op! rt 'replaceable 'skein.weaver-test/test-op)
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"arg-spec is invalid"
                                      (weaver/replace-op! rt 'replaceable
                                                          {:arg-spec {:op "replaceable"
                                                                      :subcommands {"run" {:subcommands {"again" {}}}}}}
                                                          'skein.weaver-test/context-echo-op)))]
          (is (= "replaceable" (:operation (ex-data e))))
          (is (= :invalid-subcommands (:reason (ex-data e))))
          (is (= 'skein.weaver-test/test-op (:fn (weaver/resolve-op rt 'replaceable))))))
      (testing "replace-op! retains the old entry when returns are invalid"
        (weaver/register-op! rt 'replace-returns
                             {:returns :string}
                             'skein.weaver-test/test-op)
        (let [before (weaver/resolve-op rt 'replace-returns)
              e (is (thrown? clojure.lang.ExceptionInfo
                             (weaver/replace-op! rt 'replace-returns
                                                 {:stream? true :returns :string}
                                                 'skein.weaver-test/context-echo-op)))]
          (is (= :return-stream-misalignment (:reason (ex-data e))))
          (is (= before (weaver/resolve-op rt 'replace-returns)))))
      (testing "explicit deadline-class overrides the stream default"
        (is (= :standard
               (:deadline-class (weaver/register-op! rt 'bounded-stream
                                                     {:stream? true :deadline-class :standard}
                                                     'skein.weaver-test/test-op)))))
      (testing "unknown metadata keys fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"unknown keys"
                              (weaver/register-op! rt 'nope {:bogus true} 'skein.weaver-test/test-op))))
      (testing "invalid metadata values fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":stream\? must be a boolean"
                              (weaver/register-op! rt 'nope {:stream? "yes"} 'skein.weaver-test/test-op)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":deadline-class must be"
                              (weaver/register-op! rt 'nope {:deadline-class :soon} 'skein.weaver-test/test-op)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":hook-class must be"
                              (weaver/register-op! rt 'nope {:hook-class :both} 'skein.weaver-test/test-op)))))))

(deftest weaver-op-registration-collision-and-replace
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'custom 'skein.weaver-test/test-op)
      (testing "re-registering a name fails loudly, naming both provenances"
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (weaver/register-op! rt 'custom 'skein.peers-test/peer-test-op)))]
          (is (= "custom" (:operation (ex-data e))))
          (is (= 'skein.weaver-test (:existing-provenance (ex-data e))))
          (is (= 'skein.peers-test (:attempted-provenance (ex-data e))))))
      (testing "replace-op! requires an existing name"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"cannot replace"
                              (weaver/replace-op! rt 'absent 'skein.weaver-test/test-op))))
      (testing "replace-op! overrides an existing entry"
        (is (= 'skein.peers-test
               (:provenance (weaver/replace-op! rt 'custom 'skein.peers-test/peer-test-op))))
        (is (= 'skein.peers-test
               (:provenance (weaver/resolve-op rt 'custom))))))))

(deftest weaver-op-caller-supplied-provenance-rejected
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":provenance is registry-recorded"
                            (weaver/register-op! rt 'custom
                                                 {:provenance 'evil.spoofed}
                                                 'skein.weaver-test/test-op))))))

(deftest weaver-op-envelope-threads-into-handler-context
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'ctx 'skein.weaver-test/context-echo-op)
      (testing "empty envelope threads only default payloads"
        (let [ctx (weaver/op! rt 'ctx ["a"])]
          (is (= {} (:op/payloads ctx)))
          (is (not (contains? ctx :op/cwd)))
          (is (not (contains? ctx :op/timeout)))))
      (testing "full envelope threads all fields into handler context"
        (let [ctx (weaver/op! rt 'ctx ["a"]
                              {:payloads {"body" "hello"}
                               :cwd "/tmp/work"
                               :worktree-root "/tmp/wt"
                               :git-common-dir "/tmp/wt/.git"
                               :timeout 5000})]
          (is (= {"body" "hello"} (:op/payloads ctx)))
          (is (= "/tmp/work" (:op/cwd ctx)))
          (is (= "/tmp/wt" (:op/worktree-root ctx)))
          (is (= "/tmp/wt/.git" (:op/git-common-dir ctx)))
          (is (= 5000 (:op/timeout ctx))))))))

(deftest weaver-op-registry-reload-is-collision-free
  (with-runtime
    (fn [rt _]
      (is (= ["help"] (mapv :name (weaver/ops rt))))
      ;; reload clears the registry before re-running init, so the built-in
      ;; help op re-registers without tripping the collision check.
      (runtime/reload! rt)
      (is (= ["help"] (mapv :name (weaver/ops rt)))))))

(deftest weaver-op-parser-integration
  (with-runtime
    (fn [rt _]
      (testing "arg-spec ops receive parsed :op/args before the handler"
        (weaver/register-op! rt 'parsed
                             {:arg-spec {:op "parsed"
                                         :flags {:limit {:type :int}}
                                         :positionals [{:name :name :required? true}]}}
                             'skein.weaver-test/context-echo-op)
        (let [ctx (weaver/op! rt 'parsed ["--limit" "5" "widget"])]
          (is (= {:limit 5 :name "widget"} (:op/args ctx)))
          (is (= ["--limit" "5" "widget"] (:op/argv ctx)))))
      (testing "parse failures throw the parser's structured error and short-circuit"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Unknown flag"
                                      (weaver/op! rt 'parsed ["--bogus" "x" "widget"])))]
          (is (= :unknown-flag (:reason (ex-data e))))))
      (testing "arg-spec ops resolve payload references into :op/args"
        (weaver/register-op! rt 'payloaded
                             {:arg-spec {:op "payloaded"
                                         :positionals [{:name :body}]}}
                             'skein.weaver-test/context-echo-op)
        (let [ctx (weaver/op! rt 'payloaded [":stdin"] {:payloads {"stdin" "hello"}})]
          (is (= {:body "hello"} (:op/args ctx)))
          (is (= {"stdin" "hello"} (:op/payloads ctx)))))
      (testing "subcommand arg-specs route before the handler"
        (weaver/register-op! rt 'subbed
                             {:arg-spec {:op "subbed"
                                         :subcommands {"add" {:doc "Add an item"
                                                              :flags {:force {:type :boolean}}
                                                              :positionals [{:name :title :required? true}]}
                                                       "list" {:doc "List items"}}}}
                             'skein.weaver-test/context-echo-op)
        (let [ctx (weaver/op! rt 'subbed ["add" "--force" "Widget"])]
          (is (= {:subcommand "add" :force true :title "Widget"} (:op/args ctx)))
          (is (= ["add" "--force" "Widget"] (:op/argv ctx)))))
      (testing "help aliases return detail and skip the handler for subcommand ops"
        (let [expected (weaver/op! rt 'help ["subbed"])]
          (doseq [token ["help" "-h" "--help"]]
            (is (= expected (weaver/op! rt 'subbed [token]))))))
      (testing "unknown subcommands fail during parse before the handler runs"
        (weaver/register-op! rt 'subbed-side-effect
                             {:arg-spec {:op "subbed-side-effect"
                                         :subcommands {"ok" {:doc "Run"}}}}
                             'skein.weaver-test/side-effecting-op)
        (reset! op-side-effects [])
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Unknown subcommand"
                                      (weaver/op! rt 'subbed-side-effect ["bogus"])))]
          (is (= :unknown-subcommand (:reason (ex-data e))))
          (is (= ["ok"] (:available-subcommands (ex-data e))))
          (is (empty? @op-side-effects))))
      (testing "help aliases do not mask malformed subcommand invocations"
        (doseq [[argv envelope] [[["help" "extra"] {}]
                                 [["help"] {:payloads {"stdin" "attached"}}]]]
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                        #"Unknown subcommand|Attached payloads"
                                        (weaver/op! rt 'subbed-side-effect argv envelope)))]
            (is (#{:unknown-subcommand :unused-payloads} (:reason (ex-data e)))))))
      (testing "flat and raw-envelope ops do not trigger help aliases"
        (weaver/register-op! rt 'flat-no-positionals
                             {:arg-spec {:op "flat-no-positionals"
                                         :flags {:verbose {:type :boolean}}}}
                             'skein.weaver-test/context-echo-op)
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Unexpected extra arguments"
                                      (weaver/op! rt 'flat-no-positionals ["help"])))]
          (is (= :trailing-tokens (:reason (ex-data e)))))
        (weaver/register-op! rt 'raw 'skein.weaver-test/context-echo-op)
        (let [ctx (weaver/op! rt 'raw ["help"])]
          (is (not (contains? ctx :op/args)))
          (is (= ["help"] (:op/argv ctx)))))
      (testing "raw-envelope ops receive no :op/args and keep the raw payloads map"
        (let [ctx (weaver/op! rt 'raw ["a" "b"] {:payloads {"stdin" "hi"}})]
          (is (not (contains? ctx :op/args)))
          (is (= {"stdin" "hi"} (:op/payloads ctx)))
          (is (= ["a" "b"] (:op/argv ctx))))))))

(deftest weaver-op-help-projection
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'custom
                           {:doc "Echo argv"
                            :arg-spec {:op "custom"
                                       :flags {:limit {:type :int :doc "Max"}}
                                       :positionals [{:name :name}]}
                            :returns {:type :collection :items :string}}
                           'skein.weaver-test/test-op)
      (weaver/register-op! rt 'subbed
                           {:doc "Subcommand op"
                            :arg-spec {:op "subbed"
                                       :doc "Subcommands"
                                       :subcommands {"add" {:doc "Add an item"
                                                            :flags {:force {:type :boolean :doc "Force add"}}
                                                            :positionals [{:name :title :required? true :doc "Item title"}]}
                                                     "list" {:doc "List items"}}}
                            :returns {:subcommands
                                      {"add" {:type :map :required {:id :integer}}
                                       "list" {:type :collection :items :string}}}}
                           'skein.weaver-test/context-echo-op)
      (weaver/register-op! rt 'streamed
                           {:stream? true
                            :returns {:stream {:emits :string
                                               :result [:nullable :boolean]}}}
                           'skein.weaver-test/test-op)
      (weaver/register-op! rt 'raw "Raw op" 'skein.weaver-test/context-echo-op)
      (testing "no argv lists every op summary sorted by name"
        (let [{:keys [ops]} (weaver/op! rt 'help [])]
          (is (= ["custom" "help" "raw" "streamed" "subbed"] (mapv :name ops)))
          (is (every? #(not (contains? % :returns)) ops))
          (let [help-entry (first (filter #(= "help" (:name %)) ops))]
            (is (= :read (:hook-class help-entry)))
            (is (= 'skein.api.weaver.alpha (:provenance help-entry)))
            (is (false? (:stream? help-entry)))
            (is (= :standard (:deadline-class help-entry)))
            (is (string? (:doc help-entry))))))
      (testing "op name returns arg-spec detail via explain"
        (let [detail (weaver/op! rt 'help ["custom"])]
          (is (= "Echo argv" (:doc detail)))
          (is (= "custom" (get-in detail [:arg-spec :op])))
          (is (= [{:name "limit" :flag "--limit" :type "int" :required false
                   :repeat false :parse nil :doc "Max"}]
                 (get-in detail [:arg-spec :flags])))
          (is (= {:type "collection" :items "string"} (:returns detail)))
          (is (not (contains? detail :raw-envelope)))))
      (testing "subcommand op detail includes parser-rendered subcommands"
        (let [detail (weaver/op! rt 'help ["subbed"])]
          (is (= "Subcommand op" (:doc detail)))
          (is (= "subbed" (get-in detail [:arg-spec :op])))
          (is (= ["add" "list"] (mapv :name (get-in detail [:arg-spec :subcommands]))))
          (is (= {:name "add"
                  :doc "Add an item"
                  :flags [{:name "force" :flag "--force" :type "boolean" :required false
                           :repeat false :parse nil :doc "Force add"}]
                  :positionals [{:name "title" :type "string" :required true
                                 :variadic false :parse nil :doc "Item title"}]}
                 (first (get-in detail [:arg-spec :subcommands]))))
          (is (= {:subcommands
                  {"add" {:type "map"
                          :required {"id" "integer"}
                          :optional {}}
                   "list" {:type "collection" :items "string"}}}
                 (:returns detail)))))
      (testing "streaming op detail includes its channel projections"
        (is (= {:stream {:emits "string"
                         :result ["nullable" "boolean"]}}
               (:returns (weaver/op! rt 'help ["streamed"])))))
      (testing "raw-envelope op detail carries a marker instead of an arg-spec"
        (let [detail (weaver/op! rt 'help ["raw"])]
          (is (true? (:raw-envelope detail)))
          (is (not (contains? detail :arg-spec)))))
      (testing "unknown op name fails loudly carrying available names"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Operation not found"
                                      (weaver/op! rt 'help ["nope"])))]
          (is (some #{"help"} (:available (ex-data e)))))))))

(deftest weaver-pattern-registry-and-weave
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (is (= {:name "dev-task" :fn 'skein.weaver-test/test-pattern :input-spec ::pattern-input}
             (patterns/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)))
      (is (= [{:name "dev-task" :fn 'skein.weaver-test/test-pattern :input-spec ::pattern-input}]
             (patterns/patterns rt)))
      (is (= {:name "documented-task"
              :doc "Create implementation and review strands."
              :fn 'skein.weaver-test/test-pattern
              :input-spec ::pattern-input}
             (patterns/register-pattern! rt 'documented-task "Create implementation and review strands."
                                         'skein.weaver-test/test-pattern ::pattern-input)))
      (is (= "Create implementation and review strands."
             (:doc (patterns/explain rt :documented-task))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern doc"
                            (patterns/register-pattern! rt 'bad-doc "" 'skein.weaver-test/test-pattern ::pattern-input)))
      (is (str/includes? (:spec-form (patterns/explain rt :dev-task))
                         "clojure.spec.alpha/keys"))
      (reset! delivered-events [])
      (events/register! rt :capture-weave #{:batch/applied}
                        'skein.weaver-test/capture-event {})
      (let [result (patterns/weave! rt :dev-task {:title "Implement weave"})]
        (is (= ["Implement weave" "Review: Implement weave"] (mapv :title (:created result))))
        (is (= #{"impl" "review"} (set (keys (:refs result)))))
        (is (= 1 (count (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"]))))
        ;; a weave is a batch apply: event-driven spools must see the created
        ;; strands without waiting for an unrelated mutation
        (let [event (do (events/await-quiescent! rt) (first @delivered-events))]
          (is (= :batch/applied (:event/type event)))
          (is (= "dev-task" (str (:pattern/name event))))
          (is (= 2 (count (:batch/created event))))))
      (events/unregister! rt :capture-weave)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern input failed spec validation"
                            (patterns/weave! rt :dev-task {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern not found"
                            (patterns/weave! rt :missing {:title "x"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern function"
                            (patterns/register-pattern! rt 'bad 'unqualified ::pattern-input))))))

(deftest weaver-weave-create-only-contract-remains-compatible
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (patterns/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (let [result (patterns/weave! rt :dev-task {:title "Compatible weave"})
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
      (weaver/init rt)
      (reset! hook-contexts [])
      (patterns/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (patterns/register-pattern! rt 'points 'skein.weaver-test/points-pattern ::pattern-input)
      (hooks/register! rt :parse #{:attributes/normalize} 'skein.weaver-test/parse-story-points-hook {})
      (hooks/register! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
      (let [points-result (patterns/weave! rt :points {:title "Pointed"})]
        (is (= {:storyPoints 8} (get-in points-result [:created 0 :attributes])))
        (is (= {:storyPoints 8}
               (:attributes (some #(when (= "Pointed" (:title %)) %) (weaver/list rt))))))
      (reset! hook-contexts [])
      (let [result (patterns/weave! rt :dev-task {:title "Hooked weave"})
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
      (weaver/init rt)
      (reset! pattern-call-count 0)
      (reset! hook-contexts [])
      (reset! delivered-events [])
      (events/register! rt :capture #{:strand/added :batch/applied} 'skein.weaver-test/capture-event {})
      (patterns/register-pattern! rt 'counting 'skein.weaver-test/counting-pattern ::never-valid)
      (hooks/register! rt :capture-batch #{:batch/apply-before-commit} 'skein.weaver-test/capture-hook {})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern input failed spec validation"
                            (patterns/weave! rt :counting {:title "Nope"})))
      (is (zero? @pattern-call-count))
      (is (empty? @hook-contexts))
      (is (empty? (weaver/list rt)))
      (patterns/register-pattern! rt 'bad-edge 'skein.weaver-test/bad-edge-pattern ::pattern-input)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch target strand not found"
                            (patterns/weave! rt :bad-edge {:title "Rollback"})))
      (is (empty? (weaver/list rt)))
      (is (empty? (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"])))
      (patterns/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (hooks/unregister! rt :capture-batch)
      (hooks/register! rt :reject-batch #{:batch/apply-before-commit} 'skein.weaver-test/rejecting-hook {})
      (try
        (patterns/weave! rt :dev-task {:title "Rejected weave"})
        (is false "expected weave hook rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= :batch/apply-before-commit (:hook/type (ex-data e))))
          (is (= :reject-batch (:hook/key (ex-data e))))
          (is (= "policy/rejected" (:hook/cause-code (ex-data e))))))
      (events/await-quiescent! rt)
      (is (empty? (weaver/list rt)))
      (is (empty? (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"])))
      (is (empty? @delivered-events)))))

(deftest weaver-reload-clears-patterns
  (with-runtime
    (fn [rt _]
      (let [init-file (io/file (get-in rt [:metadata :config-dir]) "init.clj")]
        (spit init-file "(require '[skein.api.current.alpha :as current]\n         '[skein.api.runtime.alpha :as runtime])\n(runtime/sync! (current/runtime))\n")
        (patterns/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
        (is (= 1 (count (patterns/patterns rt))))
        (runtime/reload! rt)
        (is (empty? (patterns/patterns rt)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Pattern not found"
                              (patterns/pattern rt 'dev-task)))))))

(deftest json-socket-invoke-dispatch
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'custom 'skein.weaver-test/test-op)
      (testing "invoke dispatches to the op registry with argv and payloads"
        (let [custom (invoke-request rt "custom" ["--flag" "value"])]
          (is (true? (get custom "ok")))
          (is (= {"operation" "custom" "argv" ["--flag" "value"]}
                 (get custom "result")))))
      (testing "the built-in help op is reachable through invoke"
        (let [help (invoke-request rt "help" [])]
          (is (true? (get help "ok")))
          (is (some #(= "help" (get % "name")) (get-in help ["result" "ops"]))))
        (let [detail (invoke-request rt "help" ["help"])]
          (is (true? (get detail "ok")))
          (is (= "help" (get-in detail ["result" "arg-spec" "op"])))
          (is (nil? (get-in detail ["result" "raw-envelope"])))))
      (testing "context envelope fields ride the invoke arguments"
        (weaver/register-op! rt 'ctx 'skein.weaver-test/envelope-echo-op)
        (let [echoed (invoke-request rt "ctx" ["a"] {"body" "hi"} {"cwd" "/tmp/work"
                                                                   "worktree_root" "/tmp/wt"
                                                                   "git_common_dir" "/tmp/wt/.git"
                                                                   "timeout" 5000})]
          (is (true? (get echoed "ok")))
          (is (= "/tmp/work" (get-in echoed ["result" "cwd"])))
          (is (= "/tmp/wt" (get-in echoed ["result" "worktree-root"])))
          (is (= 5000 (get-in echoed ["result" "timeout"])))
          (is (= {"body" "hi"} (get-in echoed ["result" "payloads"])))))
      (testing "unknown ops fail loudly with the registry's available names"
        (let [missing (invoke-request rt "nope" [])]
          (is (false? (get missing "ok")))
          (is (= "domain" (get-in missing ["error" "type"])))
          (is (= "nope" (get-in missing ["error" "details" "operation"])))
          (is (some #{"help"} (get-in missing ["error" "details" "available"])))))
      (testing "malformed invoke arguments are protocol errors"
        (doseq [args [{"name" "custom" "argv" [1] "payloads" {}}
                      {"name" "" "argv" [] "payloads" {}}
                      {"name" "custom" "argv" [] "payloads" {"k" 1}}
                      {"name" "custom" "argv" []}
                      {"name" "custom" "argv" [] "payloads" {} "bogus" true}]]
          (let [bad (socket-request rt "invoke" args)]
            (is (false? (get bad "ok")) (pr-str args))
            (is (= "protocol/malformed-request" (get-in bad ["error" "code"])) (pr-str args))))))))

(deftest json-socket-stream-invoke-framing
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'streamer {:stream? true} 'skein.weaver-test/gated-stream-op)
      (let [m (:metadata rt)]
        (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                         (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                    rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                    wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
          (.write wrt (json/write-str (invoke-frame rt "streamer" [])))
          (.newLine wrt)
          (.flush wrt)
          (let [header (json/read-str (.readLine rdr))]
            (is (true? (get header "stream")))
            (is (= "test-request" (get header "request_id"))))
          ;; line 0 is readable before the gate is delivered → incremental flush
          (is (= {"i" 0} (json/read-str (.readLine rdr))))
          (deliver @stream-gate true)
          (is (= {"i" 1} (json/read-str (.readLine rdr))))
          (let [terminator (json/read-str (.readLine rdr))]
            (is (true? (get terminator "done")))
            (is (true? (get terminator "success")))
            (is (= {"emitted" 2} (get terminator "result")))
            (is (= "test-request" (get terminator "request_id")))))))))

(deftest json-socket-stream-invoke-error-terminator
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'streamer-error {:stream? true} 'skein.weaver-test/stream-error-op)
      (let [m (:metadata rt)]
        (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                         (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                    rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                    wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
          (.write wrt (json/write-str (invoke-frame rt "streamer-error" [])))
          (.newLine wrt)
          (.flush wrt)
          (is (true? (get (json/read-str (.readLine rdr)) "stream")))
          (is (= {"i" 0} (json/read-str (.readLine rdr))))
          (let [terminator (json/read-str (.readLine rdr))]
            (is (true? (get terminator "done")))
            (is (false? (get terminator "success")))
            (is (= "domain" (get-in terminator ["error" "type"])))
            (is (= "stream/failed" (get-in terminator ["error" "code"])))))))))

(deftest json-socket-stream-op-fixture-file-loads-and-runs
  ;; Guards the shipped test/fixtures/stream-op-init.clj that tasks 8/10 load
  ;; from a disposable workspace init.clj.
  (with-runtime
    (fn [rt _]
      (load-file "test/fixtures/stream-op-init.clj")
      (let [m (:metadata rt)]
        (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                         (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                    rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                    wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
          (.write wrt (json/write-str (invoke-frame rt "test-stream" ["--count" "2"])))
          (.newLine wrt)
          (.flush wrt)
          (is (true? (get (json/read-str (.readLine rdr)) "stream")))
          (is (= {"i" 0} (json/read-str (.readLine rdr))))
          (is (= {"i" 1} (json/read-str (.readLine rdr))))
          (let [terminator (json/read-str (.readLine rdr))]
            (is (true? (get terminator "done")))
            (is (true? (get terminator "success")))
            (is (= {"emitted" 2} (get terminator "result")))))))))

(deftest json-socket-invoke-honors-op-deadline
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'slow 'skein.weaver-test/slow-op)
      (let [timed-out (invoke-request rt "slow" [] {} {"timeout" 100})]
        (is (false? (get timed-out "ok")))
        (is (= "domain" (get-in timed-out ["error" "type"])))
        (is (= "operation/deadline-exceeded" (get-in timed-out ["error" "code"]))))
      ;; The deadline cancels the future with interruption, so the handler's
      ;; sleep is aborted and its side effect never records — no orphan work
      ;; survives a reported timeout.
      (Thread/sleep 3200)
      (is (= [] @op-side-effects)))))

(deftest json-socket-invoke-payload-hooks-gate-mutating-ops
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'mutate 'skein.weaver-test/test-op)
      (weaver/register-op! rt 'reader {:hook-class :read} 'skein.weaver-test/test-op)
      (hooks/register! rt :payload #{:payload/received} 'skein.weaver-test/capture-hook {})
      (is (true? (get (invoke-request rt "mutate" ["--flag" "value"] {"body" "hi"}) "ok")))
      ;; a read-class op skips payload hooks, preserving the old read exemption
      (is (true? (get (invoke-request rt "reader" ["x"]) "ok")))
      (is (= 1 (count @hook-contexts)))
      (let [ctx (first @hook-contexts)]
        (is (= :payload/received (:hook/type ctx)))
        (is (= :payload (:hook/key ctx)))
        (is (= 'skein.weaver-test/capture-hook (:hook/fn ctx)))
        (is (= :json-socket (:request/source ctx)))
        (is (= :invoke (:request/operation ctx)))
        (is (= "test-request" (:request/id ctx)))
        (is (= "mutate" (:op/name ctx)))
        (is (= {"name" "mutate" "argv" ["--flag" "value"] "payloads" {"body" "hi"}}
               (:request/args ctx)))
        (is (= {} (:request/options ctx))))
      (testing "subcommand help aliases resolve before mutating hook gating"
        (weaver/register-op! rt 'subbed-mutate
                             {:arg-spec {:op "subbed-mutate"
                                         :subcommands {"run" {:doc "Run"}}}}
                             'skein.weaver-test/side-effecting-op)
        (reset! hook-contexts [])
        (reset! op-side-effects [])
        (let [help-detail (invoke-request rt "help" ["subbed-mutate"])
              alias (invoke-request rt "subbed-mutate" ["--help"])]
          (is (true? (get alias "ok")))
          (is (= (get help-detail "result") (get alias "result")))
          (is (empty? @hook-contexts))
          (is (empty? @op-side-effects)))
        (let [real-call (invoke-request rt "subbed-mutate" ["run"])]
          (is (true? (get real-call "ok")))
          (is (= 1 (count @hook-contexts)))
          (is (= ["subbed-mutate"] @op-side-effects)))))))

(deftest json-socket-invoke-read-ops-skip-hooks-and-protocol-errors
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'reader {:hook-class :read} 'skein.weaver-test/test-op)
      (hooks/register! rt :payload #{:payload/received} 'skein.weaver-test/rejecting-hook {})
      (is (true? (get (invoke-request rt "reader" []) "ok")))
      (is (true? (get (socket-request rt "status" {}) "ok")))
      (is (empty? @hook-contexts))
      (let [bad (socket-request rt "invoke" {"name" "reader" "argv" [1] "payloads" {}})]
        (is (= "protocol/malformed-request" (get-in bad ["error" "code"])))
        (is (empty? @hook-contexts)))
      (let [wrong-identity (socket-request-envelope rt {"protocol_version" 1
                                                        "request_id" "wrong-identity"
                                                        "weaver_id" "wrong"
                                                        "operation" "invoke"
                                                        "arguments" {"name" "reader" "argv" [] "payloads" {}}
                                                        "options" {}})]
        (is (= "protocol/identity-mismatch" (get-in wrong-identity ["error" "code"])))
        (is (empty? @hook-contexts)))
      (let [disallowed (socket-request rt "queries" {})]
        (is (= "protocol/operation-not-allowed" (get-in disallowed ["error" "code"])))
        (is (empty? @hook-contexts))))))

(deftest json-socket-invoke-payload-hook-rejection-is-domain-error-before-dispatch
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'mutate 'skein.weaver-test/side-effecting-op)
      (hooks/register! rt :reject-payload #{:payload/received} 'skein.weaver-test/rejecting-hook {})
      (let [response (invoke-request rt "mutate" ["arg"] {"body" "payload"})]
        (is (false? (get response "ok")))
        (is (= "domain" (get-in response ["error" "type"])))
        (is (= "hook/failed" (get-in response ["error" "code"])))
        (is (= "policy/rejected" (get-in response ["error" "details" "hook/cause-code"])))
        (is (= {"name" "mutate" "argv" ["arg"] "payloads" {"body" "payload"}}
               (get-in response ["error" "details" "exception/data" "ctx" "request/args"])))
        ;; the rejection precedes dispatch: the op handler never ran
        (is (empty? @op-side-effects))))))

(deftest json-socket-invoke-error-details-are-json-safe
  (with-runtime
    (fn [rt _]
      (weaver/register-op! rt 'boom 'skein.weaver-test/throwing-op)
      (let [response (invoke-request rt "boom" [])]
        (is (false? (get response "ok")))
        (is (= "domain" (get-in response ["error" "type"])))
        (is (= "op/failed" (get-in response ["error" "code"])))
        (is (= "policy/nope" (get-in response ["error" "details" "nested" "reason"])))
        (is (string? (get-in response ["error" "details" "opaque"])))))))

(deftest weaver-query-registry-fails-clearly
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Query not found"
                            (graph/resolve-query rt 'missing)))
      (try
        (graph/resolve-query rt 'missing)
        (is false "expected missing query error")
        (catch clojure.lang.ExceptionInfo e
          (is (= 'missing (:query (ex-data e))))
          (is (= "missing" (:canonical-query (ex-data e))))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (graph/register-query! rt 'user/mine [:= :state "active"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (graph/load-queries! rt {"mine" [:= :state "active"]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (graph/load-queries! rt {'user/mine [:= :state "active"]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown query operator"
                            (graph/register-query! rt :broken [:unknown :state "active"])))
      (graph/register-query! rt :ok [:= :state "active"])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown query operator"
                            (graph/load-queries! rt {:bad [:unknown :state "active"]})))
      (is (= {"ok" [:= :state "active"]} (graph/queries rt))))))

(deftest weaver-api-update-preserves-domain-errors-and-rolls-back
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [source (weaver/add rt {:title "Source"})
            target (weaver/add rt {:title "Target"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Strand not found"
                              (weaver/update rt "missing" {:edges [{:type "depends-on" :to (:id target)}]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"non-blank"
                              (weaver/update rt (:id source) {:title ""
                                                              :edges [{:type "depends-on" :to (:id target)}]})))
        (is (empty? (db/execute! (:datasource rt) ["SELECT 1 FROM strand_edges WHERE from_strand_id = ?" (:id source)])))))))

(deftest runtime-uses-world-default-database-and-directories
  (let [world (temp-world)
        rt (weaver-runtime/start! nil {:world world :publish? false})]
    (try
      (is (= (.getPath (.getCanonicalFile (io/file (:db-path world))))
             (get-in rt [:metadata :canonical-db-path])))
      (is (.isDirectory (io/file (:state-dir world))))
      (is (.isDirectory (io/file (:data-dir world))))
      (is (= (str (:state-dir world) "/weaver.sock") (get-in rt [:metadata :socket-path])))
      (is (= (str (:state-dir world) "/weaver.edn") (.getPath (metadata/metadata-file world))))
      (is (= (str (:state-dir world) "/weaver.json") (.getPath (metadata/json-metadata-file world))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-loads-default-init-clj
  (let [world (temp-world)
        init (io/file (:config-dir world) "init.clj")]
    (try
      (spit init "(require '[skein.api.current.alpha :as current] '[skein.api.graph.alpha :as graph]) (graph/register-query! (current/runtime) 'trusted [:= :state \"active\"])")
      (let [rt (weaver-runtime/start! nil {:world world :publish? false})]
        (try
          (is (= {"trusted" [:= :state "active"]} (graph/queries rt)))
          (finally
            (weaver-runtime/stop! rt))))
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
            status (weaver-runtime/status rt)
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
        (is (false? (metadata/stale-or-missing? (update status :pid long))))
        (is (= "127.0.0.1" (get-in status [:endpoint :host])))
        (is (.isLoopbackAddress (.getInetAddress (:server-socket (:server rt)))))))))

(deftest json-socket-removed-builtin-operations-are-not-available
  (with-runtime
    (fn [rt _]
      ;; The old fixed command surface (add/update/... and the socket stop op)
      ;; is gone; only invoke and status remain.
      (doseq [op ["init" "add" "update" "supersede" "show" "burn" "list" "ready"
                  "list-query" "ready-query" "weave" "subgraph"
                  "pattern-list" "query-list" "op" "stop"]]
        (let [rejected (socket-request rt op {})]
          (is (false? (get rejected "ok")) op)
          (is (= "protocol/operation-not-allowed" (get-in rejected ["error" "code"])) op)))
      (is (true? (get (socket-request rt "status" {}) "ok"))))))

(deftest json-socket-rejects-identity-mismatch
  (with-runtime
    (fn [rt _]
      (let [m (:metadata rt)
            req {"protocol_version" 1 "request_id" "bad-identity" "weaver_id" "wrong"
                 "operation" "status" "arguments" {} "options" {}}]
        (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                         (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                    rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                    wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
          (.write wrt (json/write-str req))
          (.newLine wrt)
          (.flush wrt)
          (let [response (json/read-str (.readLine rdr))]
            (is (false? (get response "ok")))
            (is (= "protocol/identity-mismatch" (get-in response ["error" "code"]))))))
      (Thread/sleep 100)
      (is (.exists (metadata/socket-file (:metadata rt)))))))

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
                            (weaver-runtime/start! nil {:world world :publish? false})))
      (is (.exists socket-file))
      (finally
        (metadata/delete! world)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest runtime-stop-removes-metadata
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        rt (weaver-runtime/start! db-file {:world world :publish? false})]
    (try
      (weaver-runtime/stop! rt)
      (is (nil? (metadata/read-metadata world)))
      (is (false? (.exists (metadata/json-metadata-file (:metadata rt)))))
      (is (false? (.exists (metadata/socket-file (:metadata rt)))))
      (finally
        (weaver-runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)))))

(deftest runtime-rejects-duplicate-live-metadata
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        rt (weaver-runtime/start! db-file {:world world :publish? false})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"metadata already exists"
                            (weaver-runtime/start! db-file {:world world :publish? false})))
      (finally
        (weaver-runtime/stop! rt)
        (db-test/delete-sqlite-family! db-file)))))
