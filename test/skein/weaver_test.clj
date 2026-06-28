(ns skein.weaver-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
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
    (let [rt @runtime/current-runtime
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

(defn replacement-view [{:keys [params]}]
  {:view :replacement :params params})

(def pattern-call-count (atom 0))

(defn test-pattern [{:keys [input]}]
  (let [title (or (:title input) (get input "title"))]
    [{:ref 'impl
      :title title
      :attributes {:kind "implementation"}}
     {:ref 'review
      :title (str "Review: " title)
      :attributes {:kind "review"}
      :edges [{:type "depends-on" :to 'impl}]}]))

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
        (is (= :skein.weaver.api (:event/source add-event)))
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
        (spit init "(require '[skein.events.alpha :as events])\n(events/register! :after-reload #{:x} 'skein.weaver-test/capture-event)\n")
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
        (reset! delivered-events [])
        (api/register-event-handler! rt :capture #{:batch/applied :strand/added :strand/updated :strand/burned}
                                     'skein.weaver-test/capture-event {})
        (let [result (api/apply-batch rt {:refs {:from (:id from) :to (:id to)}
                                          :edges [{:op :upsert :from :from :to :to :type "related-to"}]})
              events (wait-for-events 1)]
          (Thread/sleep 100)
          (is (= [:batch/applied] (mapv :event/type @delivered-events)))
          (is (= (:edges result) (:batch/edges (first events)))))))))

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

(deftest json-socket-public-operation-allowlist-stays-thin
  (is (= #{"init" "add" "update" "supersede" "show" "burn" "list" "ready" "list-query" "ready-query" "weave" "pattern-explain" "status" "stop"}
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
      (let [result (api/weave! rt :dev-task {:title "Implement weave"})]
        (is (= ["Implement weave" "Review: Implement weave"] (mapv :title (:created result))))
        (is (= #{"impl" "review"} (set (keys (:refs result)))))
        (is (= 1 (count (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"])))))
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

(deftest weaver-pattern-failures-validate-before-code-and-rollback
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (reset! pattern-call-count 0)
      (api/register-pattern! rt 'counting 'skein.weaver-test/counting-pattern ::never-valid)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern input failed spec validation"
                            (api/weave! rt :counting {:title "Nope"})))
      (is (= 0 @pattern-call-count))
      (is (empty? (api/list rt)))
      (api/register-pattern! rt 'bad-edge 'skein.weaver-test/bad-edge-pattern ::pattern-input)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch target strand not found"
                            (api/weave! rt :bad-edge {:title "Rollback"})))
      (is (empty? (api/list rt)))
      (is (empty? (db/execute! (:datasource rt) ["SELECT * FROM strand_edges"]))))))

(deftest weaver-reload-clears-patterns
  (with-runtime
    (fn [rt _]
      (let [init-file (io/file (get-in rt [:metadata :config-dir]) "init.clj")]
        (spit init-file "(require '[skein.libs.alpha :as libs])\n(libs/sync!)\n")
        (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
        (is (= 1 (count (api/patterns rt))))
        (api/reload-config! rt)
        (is (empty? (api/patterns rt)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Pattern not found"
                              (api/resolve-pattern rt 'dev-task)))))))

(deftest json-socket-weave-and-pattern-explain
  (with-runtime
    (fn [rt _]
      (api/init rt)
      (api/register-pattern! rt 'dev-task 'skein.weaver-test/test-pattern ::pattern-input)
      (let [explained (socket-request rt "pattern-explain" {"pattern" "dev-task"})]
        (is (true? (get explained "ok")))
        (is (= "dev-task" (get-in explained ["result" "name"]))))
      (let [woven (socket-request rt "weave" {"pattern" "dev-task" "input" {:title "From socket"}})]
        (is (true? (get woven "ok")))
        (is (= ["From socket" "Review: From socket"]
               (mapv #(get % "title") (get-in woven ["result" "created"]))))))))

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
      (spit init "(require '[skein.weaver.api :as api] '[skein.weaver.runtime :as runtime]) (api/register-query @runtime/current-runtime 'trusted [:= :state \"active\"])")
      (let [rt (runtime/start! nil {:world world})]
        (try
          (is (= {"trusted" [:= :state "active"]} (api/queries rt)))
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
                                                            "to" (get-in target ["result" "id"])}]})]
        (is (true? (get updated "ok")))
        (is (= [{:to_strand_id (get-in target ["result" "id"]) :edge_type "depends-on"}]
               (db/execute! (:datasource rt)
                            ["SELECT to_strand_id, edge_type FROM strand_edges WHERE from_strand_id = ?"
                             (get-in source ["result" "id"])]))))
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
