(ns skein.config-dashboard-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [skein.db-test :as db-test]
            [skein.events.alpha :as events]
            [skein.libs.alpha :as libs]
            [skein.views.alpha :as views]
            [skein.weaver.api :as api]
            [skein.weaver.config :as daemon-config]
            [skein.weaver.runtime :as runtime]))

(defn- delete-directory!
  "Delete a directory tree rooted at `path` if it exists."
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [child (reverse (file-seq file))]
        (io/delete-file child true)))))

(defn- test-world
  "Return an isolated test world rooted in a temporary directory."
  [config-dir]
  (daemon-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn- with-config-runtime
  "Run f with an isolated runtime and the repo-local .skein config loaded."
  [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-config-dashboard-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [rt (runtime/start! db-file {:world (test-world config-dir)})]
      (try
        (load-file ".skein/config.clj")
        ((requiring-resolve 'config/install!))
        (f rt)
        (finally
          (runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- copy-config-dir!
  "Copy the repo-local config files into a temporary config dir."
  [target]
  (.mkdirs (io/file target))
  (doseq [name ["init.clj" "config.clj" "libs.edn"]]
    (io/copy (io/file ".skein" name) (io/file target name))))

(defn- with-startup-config-runtime
  "Run f with an isolated runtime started through copied .skein/init.clj."
  [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-config-startup-" (java.util.UUID/randomUUID))]
    (copy-config-dir! config-dir)
    (let [rt (runtime/start! db-file {:world (test-world config-dir)})]
      (try
        (f rt)
        (finally
          (runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- add-devflow!
  "Create an active devflow strand for dashboard tests."
  [rt title kind feature attrs edges]
  (api/add rt (cond-> {:title title
                       :state "active"
                       :attributes (merge {:workflow "devflow"
                                           :kind kind
                                           :feature feature}
                                          attrs)}
                (seq edges) (assoc :edges edges))))

(defn- assert-dashboard-registrations
  "Assert repo-local devflow query/view/op registrations are present."
  [rt]
  (is (contains? (api/queries rt) "devflow-work"))
  (is (contains? (api/queries rt) "devflow-features"))
  (is (some #(= "devflow-dashboard" (:name %)) (views/views)))
  (is (some #(= "task-root" (:name %)) (views/views)))
  (is (some #(= "devflow-summaries" (:name %)) (views/views)))
  (is (some #(= :devflow-coordination-attrs (:key %)) (api/hooks rt)))
  (is (some #(= "devflow-status" (:name %)) (api/ops rt)))
  (is (some #(= "task-root" (:name %)) (api/ops rt)))
  (is (some #(= "devflow-assign" (:name %)) (api/ops rt)))
  (is (some #(= "devflow-close-feature" (:name %)) (api/ops rt)))
  (is (some #(= "devflow-supersede" (:name %)) (api/ops rt)))
  (is (some #(= "devflow-summaries" (:name %)) (api/ops rt)))
  (is (some #(= :devflow-summary-recorder (:key %)) (events/handlers))))

(deftest devflow-coordination-hook-normalizes-task-id-and-validates-present-attrs
  (with-config-runtime
    (fn [rt]
      (let [created (api/add rt {:title "Numeric task id"
                                 :state "active"
                                 :attributes {:workflow "devflow"
                                              :kind "task"
                                              :feature "normalize"
                                              :task_key "yqztl"
                                              :task_id 42
                                              :validation ["clojure -M:test"]}})
            socket-shaped (api/add rt {:title "Socket numeric task id"
                                       :state "active"
                                       :attributes {"workflow" "devflow"
                                                    "kind" "task"
                                                    "feature" "normalize"
                                                    "task_key" "socket"
                                                    "task_id" 43
                                                    "validation" ["clojure -M:test"]}})]
        (is (= "42" (get-in created [:attributes :task_id])))
        (is (= "43" (get-in socket-shaped [:attributes :task_id]))))
      (try
        (api/add rt {:title "Bad task id"
                     :state "active"
                     :attributes {:workflow "devflow"
                                  :kind "task"
                                  :feature "normalize"
                                  :task_key "bad"
                                  :task_id ""}})
        (is false "expected malformed task_id rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= :attributes/normalize (:hook/type (ex-data e))))
          (is (= :devflow-coordination-attrs (:hook/key (ex-data e))))
          (is (= "devflow/invalid-coordination-attribute" (:hook/cause-code (ex-data e))))))
      (try
        (api/add rt {:title "Bad string task id"
                     :state "active"
                     :attributes {"workflow" "devflow"
                                  "kind" "task"
                                  "feature" "normalize"
                                  "task_key" "bad-string"
                                  "task_id" ""}})
        (is false "expected malformed string-keyed task_id rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= "devflow/invalid-coordination-attribute" (:hook/cause-code (ex-data e))))))
      (try
        (api/add rt {:title "Bad validation"
                     :state "active"
                     :attributes {:workflow "devflow"
                                  :kind "task"
                                  :feature "normalize"
                                  :task_key "bad-validation"
                                  :validation []}})
        (is false "expected empty validation rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "hook/failed" (:code (ex-data e))))
          (is (= "devflow/invalid-coordination-attribute" (:hook/cause-code (ex-data e))))))
      (doseq [[title attrs] [["Duplicate task id" {:workflow "devflow"
                                                    :kind "task"
                                                    :feature "normalize"
                                                    :task_key "dupe-task-id"
                                                    :task_id 1
                                                    "task_id" 2}]
                            ["Duplicate workflow" {:workflow "devflow"
                                                    "workflow" "agent-plan"
                                                    :kind "task"
                                                    :feature "normalize"
                                                    :task_key "dupe-workflow"}]]]
        (try
          (api/add rt {:title title
                       :state "active"
                       :attributes attrs})
          (is false "expected duplicate logical coordination key rejection")
          (catch clojure.lang.ExceptionInfo e
            (is (= "hook/failed" (:code (ex-data e))))
            (is (= "devflow/duplicate-coordination-attribute" (:hook/cause-code (ex-data e)))))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (api/weave! rt :devflow-plan {:feature "normalize"
                                                 :title "Plan"
                                                 :tasks [{:key "empty-validation"
                                                          :title "Empty validation"
                                                          :validation []}]}))))))

(deftest devflow-coordination-hook-allows-generic-owner-and-kind-when-not-coordination
  (with-config-runtime
    (fn [rt]
      (let [task (api/add rt {:title "Generic owner kind"
                             :state "active"
                             :attributes {:owner "infra"
                                          :kind "doc"}})]
        (is (= "doc" (get-in task [:attributes :kind])))
        (is (= "spike" (get-in (api/update rt (:id task)
                                {:attributes {:kind "spike" :owner "infra"}})
                               [:attributes :kind])))
        (is (= "platform" (get-in (api/update rt (:id task)
                                   {:attributes {:kind "doc" :owner "platform"}})
                                   [:attributes :owner]))))))

(deftest devflow-coordination-hook-normalizes-and-rejects-on-update-in-context
  (with-config-runtime
    (fn [rt]
      (let [task (api/add rt {:title "Contexted task"
                             :state "active"
                             :attributes {:workflow "devflow"
                                          :kind "task"
                                          :task_key "update-me"
                                          :feature "update"}})
            updated (api/update rt (:id task) {:attributes {:task_id 77}})]
        (is (= "77" (get-in updated [:attributes :task_id])))
        ;; kind-only updates do not assert coordination context by default.
        (is (= "doc" (get-in (api/update rt (:id task)
                               {:attributes {:kind "doc"}})
                               [:attributes :kind])))
        (try
          (api/update rt (:id task) {:attributes {:workflow "bad-workflow" :task_key "update-me"}})
          (is false "expected lifecycle rejection for invalid workflow")
          (catch clojure.lang.ExceptionInfo e
            (is (= "hook/failed" (:code (ex-data e))))
            (is (= "devflow/invalid-coordination-attribute" (:hook/cause-code (ex-data e)))))
        )
        (try
          (api/update rt (:id task) {:attributes {:kind "doc" :task_key "update-me"}})
          (is false "expected lifecycle rejection for invalid kind")
          (catch clojure.lang.ExceptionInfo e
            (is (= "hook/failed" (:code (ex-data e))))
            (is (= "devflow/invalid-coordination-attribute" (:hook/cause-code (ex-data e))))))))))

(deftest devflow-status-unscoped-ready-excludes-plan-strands
  (with-config-runtime
    (fn [rt]
      (add-devflow! rt "Feature" "plan" "dashboard-review" {} [])
      (add-devflow! rt "Ready task" "task" "dashboard-review" {:task_key "ready"} [])
      (let [status ((requiring-resolve 'config/devflow-status-op) {:op/argv []})]
        (is (= 2 (:active_count status)))
        (is (= 1 (:ready_count status)))
        (is (= ["Ready task"] (mapv :title (:ready status))))))))

(deftest devflow-dashboard-includes-active-blockers-outside-feature-candidate-set
  (with-config-runtime
    (fn [rt]
      (add-devflow! rt "Feature" "plan" "dashboard-review" {} [])
      (let [external-blocker (api/add rt {:title "External blocker"
                                          :state "active"
                                          :attributes {:kind "task"
                                                       :feature "other-feature"
                                                       :task_key "external"
                                                       :owner "outside"}})]
        (add-devflow! rt "Blocked work" "task" "dashboard-review"
                      {:task_key "blocked"
                       :task_file "devflow/feat/dashboard/tasks/blocked.md"
                       :owner "agent"}
                      [{:type "depends-on" :to (:id external-blocker)}])
        (let [dashboard (views/view! 'devflow-dashboard {:feature "dashboard-review"})
              blocked (get-in dashboard [:features 0 :blocked 0])]
          (is (= {:features 1 :active_work 1 :ready_work 0 :blocked_work 1}
                 (:counts dashboard)))
          (is (= "Blocked work" (:title blocked)))
          (is (= [{:id (:id external-blocker)
                   :title "External blocker"
                   :edge_type "depends-on"
                   :metadata {:feature "other-feature"
                              :kind "task"
                              :task_key "external"
                              :owner "outside"}
                   :attributes {}}]
                 (:blocked_by blocked))))))))

(deftest task-root-op-and-view-return-owning-devflow-plan
  (with-config-runtime
    (fn [rt]
      (let [feature (add-devflow! rt "Feature" "plan" "root-lookup" {} [])
            task (add-devflow! rt "Implement root lookup" "task" "root-lookup"
                               {:task_key "root-lookup" :owner "agent"}
                               [])]
        (api/update rt (:id feature) {:edges [{:type "parent-of" :to (:id task)}]})
        (let [by-key ((requiring-resolve 'config/task-root-op) {:op/argv ["root-lookup"]})
              by-id (views/view! 'task-root {:task (str (:id task))})]
          (is (= "task-root" (:operation by-key)))
          (is (= {:feature "root-lookup" :kind "task" :task_key "root-lookup" :owner "agent"}
                 (get-in by-key [:task :metadata])))
          (is (= 1 (:root_count by-key)))
          (is (= [(:id feature)] (mapv :id (:roots by-key))))
          (is (= [(:id feature)] (mapv :id (:roots by-id)))))))))

(deftest task-root-op-prefers-strand-id-over-colliding-task-key
  (with-config-runtime
    (fn [rt]
      (let [feature-by-id (add-devflow! rt "Feature by id" "plan" "root-by-id" {} [])
            task-by-id (add-devflow! rt "Task by id" "task" "root-by-id"
                                     {:task_key "original-key"}
                                     [])
            feature-by-key (add-devflow! rt "Feature by key" "plan" "root-by-key" {} [])
            task-by-key (add-devflow! rt "Task by key" "task" "root-by-key"
                                      {:task_key (:id task-by-id)}
                                      [])]
        (api/update rt (:id feature-by-id) {:edges [{:type "parent-of" :to (:id task-by-id)}]})
        (api/update rt (:id feature-by-key) {:edges [{:type "parent-of" :to (:id task-by-key)}]})
        (let [result ((requiring-resolve 'config/task-root-op) {:op/argv [(:id task-by-id)]})]
          (is (= (:id task-by-id) (get-in result [:task :id])))
          (is (= [(:id feature-by-id)] (mapv :id (:roots result)))))))))

(deftest task-root-op-fails-on-ambiguous-task-key
  (with-config-runtime
    (fn [rt]
      (add-devflow! rt "Task A" "task" "root-lookup-a" {:task_key "duplicate"} [])
      (add-devflow! rt "Task B" "review" "root-lookup-b" {:task_key "duplicate"} [])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Multiple active devflow tasks matched root lookup"
                            ((requiring-resolve 'config/task-root-op) {:op/argv ["duplicate"]}))))))

(deftest devflow-assign-updates-one-task-metadata-atomically
  (with-config-runtime
    (fn [rt]
      (let [task (add-devflow! rt "Assignable" "task" "batch-ops"
                               {:task_key "impl" :owner "old"}
                               [])
            other (add-devflow! rt "Other" "task" "batch-ops"
                                {:task_key "other" :owner "old"}
                                [])
            result ((requiring-resolve 'config/devflow-assign-op)
                    {:op/argv ["batch-ops" "impl" "agent" "devflow-skein-batch-ops"]})
            updated (api/strands-by-ids rt [(:id task)])
            untouched (api/strands-by-ids rt [(:id other)])]
        (is (= "devflow-assign" (:operation result)))
        (is (= (:id task) (get-in result [:updated :id])))
        (is (= "active" (get-in result [:updated :state])))
        (is (= {:feature "batch-ops"
                :kind "task"
                :task_key "impl"
                :owner "agent"
                :branch "devflow-skein-batch-ops"}
               (get-in result [:updated :metadata])))
        (is (= "agent" (get-in (first updated) [:attributes :owner])))
        (is (= "devflow-skein-batch-ops" (get-in (first updated) [:attributes :branch])))
        (is (= "old" (get-in (first untouched) [:attributes :owner])))
        (is (nil? (get-in (first untouched) [:attributes :branch])))))))

(deftest devflow-assign-fails-without-partial-mutation
  (with-config-runtime
    (fn [rt]
      (let [task (add-devflow! rt "Assignable" "task" "batch-ops"
                               {:task_key "impl" :owner "old"}
                               [])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"branch must be a non-blank string"
                              ((requiring-resolve 'config/devflow-assign-op)
                               {:op/argv ["batch-ops" "impl" "agent" ""]})))
        (is (= {:workflow "devflow" :kind "task" :feature "batch-ops" :task_key "impl" :owner "old"}
               (:attributes (first (api/strands-by-ids rt [(:id task)])))))))))

(deftest devflow-close-feature-closes-active-feature-dag-atomically
  (with-config-runtime
    (fn [rt]
      (let [feature (add-devflow! rt "Feature" "plan" "batch-ops" {} [])
            impl (add-devflow! rt "Impl" "task" "batch-ops" {:task_key "impl"} [])
            review (add-devflow! rt "Review" "review" "batch-ops" {:task_key "review"} [])
            nested-plan (add-devflow! rt "Nested plan" "plan" "batch-ops" {:task_key "nested-plan"} [])
            nested-task (add-devflow! rt "Nested task" "task" "batch-ops" {:task_key "nested-task"} [])
            orphan (add-devflow! rt "Orphan" "task" "batch-ops" {:task_key "orphan"} [])
            same-feature-non-devflow (api/add rt {:title "Non devflow"
                                                  :state "active"
                                                  :attributes {:workflow "agent-plan"
                                                               :kind "task"
                                                               :feature "batch-ops"}})
            outside (add-devflow! rt "Outside" "task" "other" {:task_key "outside"} [])]
        (api/update rt (:id feature) {:edges [{:type "parent-of" :to (:id impl)}
                                             {:type "parent-of" :to (:id review)}
                                             {:type "parent-of" :to (:id nested-plan)}]})
        (api/update rt (:id nested-plan) {:edges [{:type "parent-of" :to (:id nested-task)}]})
        (let [result ((requiring-resolve 'config/devflow-close-feature-op)
                      {:op/argv ["batch-ops"]})
              closed (api/strands-by-ids rt [(:id feature) (:id impl) (:id review) (:id nested-plan) (:id nested-task)])
              open (api/strands-by-ids rt [(:id outside) (:id same-feature-non-devflow) (:id orphan)])]
          (is (= "devflow-close-feature" (:operation result)))
          (is (= 5 (:closed_count result)))
          (is (= (:id feature) (get-in result [:root :id])))
          (is (= {:feature "batch-ops" :kind "plan"}
                 (get-in result [:root :metadata])))
          (is (= #{"closed"} (set (map :state (:closed result)))))
          (is (= #{(:id feature) (:id impl) (:id review) (:id nested-plan) (:id nested-task)}
                 (set (map :id (:closed result)))))
          (is (= #{"closed"} (set (map :state closed))))
          (is (= #{"active"} (set (map :state open)))))))))

(deftest devflow-close-feature-skip-non-devflow-children
  (with-config-runtime
    (fn [rt]
      (let [feature (add-devflow! rt "Feature" "plan" "batch-ops" {} [])
            impl (add-devflow! rt "Impl" "task" "batch-ops" {:task_key "impl"} [])
            non-devflow-child (api/add rt {:title "Non devflow child"
                                          :state "active"
                                          :attributes {:workflow "agent-plan"
                                                       :kind "task"
                                                       :feature "batch-ops"
                                                       :task_key "note"}})]
        (api/update rt (:id feature) {:edges [{:type "parent-of" :to (:id impl)}
                                             {:type "parent-of" :to (:id non-devflow-child)}]})
        (let [result ((requiring-resolve 'config/devflow-close-feature-op)
                      {:op/argv ["batch-ops"]})
              impl-after (api/show rt (:id impl))
              nondev-after (api/show rt (:id non-devflow-child))]
          (is (= 2 (:closed_count result)))
          (is (= "closed" (:state impl-after)))
          (is (= "active" (:state nondev-after)))))))

(deftest devflow-close-feature-fails-when-feature-has-no-active-plan-root
  (with-config-runtime
    (fn [_rt]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No active devflow plan root matched feature"
                            ((requiring-resolve 'config/devflow-close-feature-op)
                             {:op/argv ["missing"]}))))))

(deftest devflow-close-feature-fails-on-two-active-plan-roots
  (with-config-runtime
    (fn [rt]
      (add-devflow! rt "Feature A" "plan" "duplicate-root" {} [])
      (add-devflow! rt "Feature B" "plan" "duplicate-root" {} [])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Multiple active devflow plan roots matched feature"
                            ((requiring-resolve 'config/devflow-close-feature-op)
                             {:op/argv ["duplicate-root"]}))))))
(deftest devflow-supersede-op-rewires-dependencies-by-task-key
  (with-config-runtime
    (fn [rt]
      (let [old (add-devflow! rt "Old task" "task" "supersession" {:task_key "old"} [])
            replacement (add-devflow! rt "Replacement task" "task" "supersession" {:task_key "new"} [])
            dependent (add-devflow! rt "Dependent task" "task" "supersession" {:task_key "dependent"}
                                    [{:type "depends-on" :to (:id old)}])
            result ((requiring-resolve 'config/devflow-supersede-op) {:op/argv ["old" "new"]})
            old-after (api/show rt (:id old))
            dependent-graph (api/subgraph rt [(:id dependent)] {:type "depends-on"})]
        (is (= "devflow-supersede" (:operation result)))
        (is (= (:id old) (get-in result [:old :id])))
        (is (= (:id replacement) (get-in result [:replacement :id])))
        (is (= "replaced" (:state old-after)))
        (is (= [(:id replacement) (:id old) "supersedes"]
               ((juxt :from_strand_id :to_strand_id :edge_type)
                (get-in result [:result :supersedes-edge]))))
        (is (= #{[(:id dependent) (:id replacement) "depends-on"]}
               (set (map (juxt :from_strand_id :to_strand_id :edge_type)
                         (:edges dependent-graph)))))))))

(deftest devflow-supersede-op-rejects-plan-ids-and-ambiguous-refs
  (with-config-runtime
    (fn [rt]
      (let [old-plan (add-devflow! rt "Old plan" "plan" "plan-supersession" {} [])
            new-plan (add-devflow! rt "New plan" "plan" "plan-supersession" {} [])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Existing strand id is not a supported devflow old supersession target"
                              ((requiring-resolve 'config/devflow-supersede-op)
                               {:op/argv [(:id old-plan) (:id new-plan)]}))))
      (add-devflow! rt "Task A" "task" "supersession-a" {:task_key "duplicate"} [])
      (add-devflow! rt "Task B" "review" "supersession-b" {:task_key "duplicate"} [])
      (add-devflow! rt "Replacement" "task" "supersession-c" {:task_key "replacement"} [])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Multiple active devflow old strands matched supersession ref"
                            ((requiring-resolve 'config/devflow-supersede-op)
                             {:op/argv ["duplicate" "replacement"]}))))))

(deftest devflow-supersede-op-does-not-fallback-from-existing-unsupported-id-to-task-key
  (with-config-runtime
    (fn [rt]
      (let [plan (add-devflow! rt "Plan" "plan" "plan-id-collision" {} [])
            colliding-task (add-devflow! rt "Colliding task" "task" "plan-id-collision" {:task_key (:id plan)} [])
            replacement (add-devflow! rt "Replacement" "task" "plan-id-collision" {:task_key "replacement"} [])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Existing strand id is not a supported devflow old supersession target"
                              ((requiring-resolve 'config/devflow-supersede-op)
                               {:op/argv [(:id plan) (:id replacement)]})))
        (is (= "active" (:state (api/show rt (:id plan)))))
        (is (= "active" (:state (api/show rt (:id colliding-task)))))
        (is (= "active" (:state (api/show rt (:id replacement)))))
        (is (empty? (:edges (api/subgraph rt [(:id replacement)] {:type "supersedes"}))))))))

(deftest task-root-still-resolves-supported-task-supersession-replacement
  (with-config-runtime
    (fn [rt]
      (let [feature (add-devflow! rt "Feature" "plan" "task-supersession-root" {} [])
            old (add-devflow! rt "Old task" "task" "task-supersession-root" {:task_key "old-rooted"} [])
            replacement (add-devflow! rt "Replacement review" "review" "task-supersession-root" {:task_key "new-rooted"} [])]
        (api/update rt (:id feature) {:edges [{:type "parent-of" :to (:id old)}
                                             {:type "parent-of" :to (:id replacement)}]})
        ((requiring-resolve 'config/devflow-supersede-op) {:op/argv [(:id old) (:id replacement)]})
        (let [root ((requiring-resolve 'config/task-root-op) {:op/argv [(:id replacement)]})]
          (is (= (:id replacement) (get-in root [:task :id])))
          (is (= [(:id feature)] (mapv :id (:roots root)))))))))

(defn- wait-for-notifications
  "Return devflow summary notifications once pred matches, or nil after timeout."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (let [notifications (:notifications ((requiring-resolve 'config/devflow-summaries-op) {}))]
        (cond
          (pred notifications) notifications
          (< (System/currentTimeMillis) deadline) (do (Thread/sleep 25) (recur))
          :else nil)))))

(deftest devflow-summary-event-records-when-last-active-work-closes
  (with-config-runtime
    (fn [rt]
      (let [feature (add-devflow! rt "Feature" "plan" "events" {} [])
            impl (add-devflow! rt "Impl" "task" "events" {:task_key "impl"} [])
            review (add-devflow! rt "Review" "review" "events" {:task_key "review"} [])]
        (api/update rt (:id feature) {:edges [{:type "parent-of" :to (:id impl)}
                                             {:type "parent-of" :to (:id review)}]})
        (api/update rt (:id impl) {:state "closed"})
        (is (nil? (wait-for-notifications seq)))
        (api/update rt (:id review) {:state "closed"})
        (let [notifications (wait-for-notifications seq)
              summary (last notifications)
              via-view (views/view! 'devflow-summaries {})]
          (is (= :devflow/feature-ready-for-summary (:notification/type summary)))
          (is (= "events" (:feature summary)))
          (is (= (:id feature) (get-in summary [:root :id])))
          (is (= {:work_items 2 :closed 2 :replaced 0} (:counts summary)))
          (is (= #{(:id impl) (:id review)} (set (map :id (:work summary)))))
          (is (= notifications (:notifications via-view)))
          (is (= "closed" (:state (api/show rt (:id review))))))))))

(deftest devflow-close-feature-records-one-top-level-summary-with-nested-plan
  (with-config-runtime
    (fn [rt]
      (let [feature (add-devflow! rt "Feature" "plan" "events-close" {} [])
            impl (add-devflow! rt "Impl" "task" "events-close" {:task_key "impl"} [])
            nested-plan (add-devflow! rt "Nested plan" "plan" "events-close" {:task_key "nested"} [])
            nested-task (add-devflow! rt "Nested task" "task" "events-close" {:task_key "nested-task"} [])]
        (api/update rt (:id feature) {:edges [{:type "parent-of" :to (:id impl)}
                                             {:type "parent-of" :to (:id nested-plan)}]})
        (api/update rt (:id nested-plan) {:edges [{:type "parent-of" :to (:id nested-task)}]})
        ((requiring-resolve 'config/devflow-close-feature-op) {:op/argv ["events-close"]})
        (let [notifications (wait-for-notifications #(= 1 (count %)))
              summary (first notifications)]
          (is (= 1 (count notifications)))
          (is (= "events-close" (:feature summary)))
          (is (= (:id feature) (get-in summary [:root :id])))
          (is (not= (:id nested-plan) (get-in summary [:root :id])))
          (is (= {:work_items 2 :closed 2 :replaced 0} (:counts summary)))
          (is (= #{(:id impl) (:id nested-task)} (set (map :id (:work summary)))))
          (is (some? (:batch/id summary))))))))

(deftest repo-local-startup-and-reload-preserve-dashboard-registrations
  (with-startup-config-runtime
    (fn [rt]
      (assert-dashboard-registrations rt)
      (add-devflow! rt "Feature" "plan" "startup-dashboard" {} [])
      (let [external-blocker (api/add rt {:title "External blocker"
                                          :state "active"
                                          :attributes {:kind "task"
                                                       :feature "external"
                                                       :task_key "external"}})]
        (add-devflow! rt "Ready work" "task" "startup-dashboard"
                      {:task_key "ready"
                       :task_file "devflow/feat/startup/tasks/ready.md"
                       :owner "agent"}
                      [])
        (add-devflow! rt "Blocked work" "review" "startup-dashboard"
                      {:task_key "blocked"
                       :task_file "devflow/feat/startup/tasks/blocked.md"
                       :owner "reviewer"}
                      [{:type "depends-on" :to (:id external-blocker)}])
        (let [before-status ((requiring-resolve 'config/devflow-status-op) {:op/argv ["startup-dashboard"]})
              before-dashboard (views/view! 'devflow-dashboard {:feature "startup-dashboard"})]
          (is (= 3 (:active_count before-status)))
          (is (= 1 (:ready_count before-status)))
          (is (= {:features 1 :active_work 2 :ready_work 1 :blocked_work 1}
                 (:counts before-dashboard)))
          (is (= "External blocker" (get-in before-dashboard [:features 0 :blocked 0 :blocked_by 0 :title]))))
        (is (= :loaded (:status (libs/reload!))))
        (assert-dashboard-registrations rt)
        (let [after-status ((requiring-resolve 'config/devflow-status-op) {:op/argv ["startup-dashboard"]})
              after-dashboard (views/view! 'devflow-dashboard {:feature "startup-dashboard"})]
          (is (= 3 (:active_count after-status)))
          (is (= 1 (:ready_count after-status)))
          (is (= ["Ready work"] (mapv :title (:ready after-status))))
          (is (= {:features 1 :active_work 2 :ready_work 1 :blocked_work 1}
                 (:counts after-dashboard)))
          (is (= "External blocker" (get-in after-dashboard [:features 0 :blocked 0 :blocked_by 0 :title]))))))))))
