(ns skein.alpha-test
  "Tests for the blessed skein.api.*.alpha surfaces (batch, graph, hooks, views)."
  (:require [skein.api.batch.alpha :as batch]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.views.alpha :as views]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.db-test :as db-test]))
(defn test-world [config-dir]
  (weaver-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn reset-repl-state! []
  (reset! (var-get (ns-resolve 'skein.repl 'active-config-dir))
          (var-get (ns-resolve 'skein.repl 'no-connection))))

(defn with-runtime [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-alpha-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [rt (weaver-runtime/start! db-file {:world (test-world config-dir) :publish? false})]
      (try
        (f rt)
        (finally
          (reset-repl-state!)
          (weaver-runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file))))))

(defn test-view [{:keys [params]}]
  {:alpha-view params})

;; Namespace-level on purpose: hooks are registered by symbol and resolved
;; to top-level vars, so capture state cannot be a per-test local. Reset by
;; the :each fixture below; the runner never splits a namespace across threads.
(def alpha-hook-contexts (atom []))

(use-fixtures :each (fn [f] (reset! alpha-hook-contexts []) (f)))

(defn test-hook [_ctx]
  :ok)

(defn capture-alpha-hook [ctx]
  (swap! alpha-hook-contexts conj ctx)
  :ok)

(defn normalize-alpha-hook [ctx]
  (swap! alpha-hook-contexts conj ctx)
  {:hook/value (:hook/value ctx)})

(deftest alpha-helpers-route-directly-inside-daemon-runtime
  (with-runtime
    (fn [rt]
      (weaver/init rt)
      (let [feature (weaver/add! rt {:title "Feature" :attributes {:kind "feature"}})
            task (weaver/add! rt {:title "Task" :attributes {:owner "agent"}})]
        (weaver/update! rt (:id feature) {:edges [{:type "parent-of" :to (:id task)}]})
        (graph/register-query! rt 'agent-owned [:= [:attr :owner] "agent"])
        (is (= [(:id task)] (graph/query-ids rt 'agent-owned {})))
        (is (= [(:id task)] (mapv :id (graph/strands-by-ids rt [(:id task) (:id task)]))))
        (is (= {:strand-id (:id task)
                :keys ["owner"]
                :archived? true
                :changed 1}
               (weaver/archive! rt (:id task) [:owner])))
        (is (= [] (weaver/list rt [:= [:attr :owner] "agent"] {})))
        (is (= {:owner "agent"} (:attributes (weaver/show rt (:id task)))))
        (is (= {:strand-id (:id task)
                :keys ["owner"]
                :archived? false
                :changed 1}
               (weaver/unarchive! rt (:id task) [:owner])))
        (weaver/update! rt (:id task) {:attributes {:payload (str/join (repeat 1100 "x"))}})
        (let [lean-task (first (filter #(= (:id task) (:id %))
                                       (weaver/ready-lean rt 1024)))
              payload (get-in lean-task [:attributes :payload])]
          (is (true? (:skein/omitted payload)))
          (is (pos-int? (:bytes payload))))
        (weaver/update! rt (:id task) {:attributes {:payload nil}})
        (is (= [(:id feature)] (graph/ancestor-root-ids rt [(:id task)] {})))
        (is (= #{(:id feature) (:id task)}
               (set (map :id (:strands (graph/subgraph rt [(:id feature)]))))))
        (is (= {:name "daily" :fn 'skein.alpha-test/test-view}
               (views/register-view! rt :daily 'skein.alpha-test/test-view)))
        (is (= [{:name "daily" :fn 'skein.alpha-test/test-view}]
               (views/views rt)))
        (is (= {:alpha-view {:owner "agent"}}
               (views/view! rt 'daily {:owner "agent"})))
        (is (= {:key :policy
                :types #{:payload/received}
                :fn 'skein.alpha-test/test-hook
                :order 5
                :metadata {:doc "policy"}}
               (hooks/register-hook! rt :policy #{:payload/received} 'skein.alpha-test/test-hook {:order 5 :doc "policy"})))
        (is (= [{:key :policy
                 :types #{:payload/received}
                 :fn 'skein.alpha-test/test-hook
                 :order 5
                 :metadata {:doc "policy"}}]
               (hooks/hooks rt)))
        (is (= :policy (hooks/unregister-hook! rt :policy)))
        (let [batch-result (batch/apply! rt {:refs {:feature (:id feature)
                                                    :task (:id task)}
                                             :strands [{:ref :task
                                                        :state "closed"
                                                        :attributes {:owner "agent" :phase "batched"}}
                                                       {:ref :batch-task
                                                        :title "Batch task"
                                                        :attributes {:owner "agent"}}]
                                             :edges [{:op :upsert
                                                      :from :batch-task
                                                      :to :feature
                                                      :type "depends-on"}]})
              created (first (:created batch-result))
              updated (first (:updated batch-result))]
          (is (= (:id created) (get-in batch-result [:refs :batch-task])))
          (is (= {:title "Batch task" :state "active" :attributes {:owner "agent"}}
                 (select-keys created [:title :state :attributes])))
          (is (= {:id (:id task)
                  :state "closed"
                  :attributes {:owner "agent" :phase "batched"}}
                 (select-keys (:after updated) [:id :state :attributes]))))))))

(deftest current-runtime-fails-loudly-without-ambient-runtime
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No active Skein weaver runtime"
                        (#'current/runtime* (constantly nil))))
  ;; This namespace runs in the parallel batch, whose tests start unpublished
  ;; runtimes only; with the thread-local binding cleared, the public entry point
  ;; must see no ambient runtime and fail loudly.
  (binding [weaver-runtime/*runtime* nil]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"No active Skein weaver runtime"
                          (current/runtime)))))

(deftest current-with-runtime*-rejects-nil-runtime
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Cannot scope a nil Skein runtime"
                        (current/with-runtime* nil (constantly :never)))))
