(ns skein.config-test
  "Tests for the repo-local .skein config: registration surface, the
  agent-plan weave pattern, and the devflow op wrappers over
  skein.spools.devflow."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [skein.db-test :as db-test]
            [skein.runtime.alpha :as runtime-alpha]
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
        config-dir (str "/tmp/skein-config-test-" (java.util.UUID/randomUUID))]
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
  (doseq [name ["init.clj" "config.clj" "spools.edn"]]
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

(defn- op!
  "Invoke a repo-local config op fn by name with a CLI-shaped argv."
  [op-name argv]
  ((requiring-resolve (symbol "config" (str op-name "-op"))) {:op/argv argv}))

(defn- assert-config-registrations
  "Assert the repo-local query/op/pattern registrations are present."
  [rt]
  (doseq [query-name ["feature-active" "feature-work" "feature-owner-work"
                      "feature-run" "workflow-runs" "devflow-runs" "work"]]
    (is (contains? (api/queries rt) query-name)))
  (doseq [op-name ["current-dags" "devflow-start" "devflow-next" "devflow-choices"
                   "devflow-choose" "devflow-complete" "devflow-advance"
                   "devflow-describe" "devflow-history" "devflow-archive"
                   "devflow-status" "workflow-runs" "devflow-conventions"]]
    (is (some #(= op-name (:name %)) (api/ops rt))))
  (is (some #(= "agent-plan" (:name %)) (api/patterns rt))))

(deftest agent-plan-weave-creates-plan-and-task-dag
  (with-config-runtime
    (fn [rt]
      (api/weave! rt :agent-plan
                  {:feature "plan-feature"
                   :title "Feature: plan feature"
                   :body "Problem and scope."
                   :tasks [{:key "impl"
                            :title "Implement it"
                            :owner "agent-a"
                            :validation ["clojure -M:test"]}
                           {:key "review"
                            :kind "review"
                            :title "Review it"
                            :hitl true
                            :depends_on ["impl"]}]})
      (let [strands (api/list rt (var-get (requiring-resolve 'config/feature-active-query))
                              {:feature "plan-feature"})
            by-key (into {} (map (juxt #(get-in % [:attributes :task_key]) identity)) strands)
            plan (first (filter #(= "plan" (get-in % [:attributes :kind])) strands))
            children (:edges (api/subgraph rt [(:id plan)] {:type "parent-of"}))
            ready (api/ready rt (var-get (requiring-resolve 'config/feature-work-query))
                             {:feature "plan-feature"})]
        (is (= 3 (count strands)))
        (is (= "agent-plan" (get-in plan [:attributes :workflow])))
        (is (= #{(:id (by-key "impl")) (:id (by-key "review"))}
               (set (map :to_strand_id children))))
        (is (= true (get-in (by-key "review") [:attributes :hitl])))
        ;; review depends on impl, so only impl is ready
        (is (= ["Implement it"] (mapv :title ready)))
        ;; current-dags stays self-contained: a blocker outside the plan DAG
        ;; must not surface as a dangling depends-on edge
        (let [external (api/add rt {:title "External blocker"
                                    :state "active"
                                    :attributes {:kind "task"}})]
          (api/update rt (:id (by-key "impl"))
                      {:edges [{:type "depends-on" :to (:id external)}]})
          (let [dag (->> (:dags (op! "current-dags" []))
                         (filter #(= (:id plan) (get-in % [:root :id])))
                         first)
                dag-ids (set (map :id (:strands dag)))]
            (is (some? dag))
            (is (every? #(and (contains? dag-ids (:from_strand_id %))
                              (contains? dag-ids (:to_strand_id %)))
                        (concat (:parent_of_edges dag) (:depends_on_edges dag))))))))))

(deftest agent-plan-rejects-invalid-input
  (with-config-runtime
    (fn [rt]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"agent-plan"
                            (api/weave! rt :agent-plan
                                        {:feature "bad" :title "Bad" :tasks []}))))))

(deftest devflow-ops-drive-intake-into-proposal
  (with-config-runtime
    (fn [_rt]
      (let [started (op! "devflow-start" ["ops-feature" "already-in-worktree-ok"])]
        (is (= "devflow-start" (:operation started)))
        (is (= "intake" (:stage (first (:ready started)))))
        (is (= "create-or-confirm-worktree" (:checkpoint (first (:ready started))))))
      (is (contains? (:choices (op! "devflow-choices" ["ops-feature"])) "abort"))
      (let [after-worktree (op! "devflow-choose" ["ops-feature" "already-in-worktree"])]
        (is (= "brief" (:artifact (first (:ready after-worktree))))))
      (let [after-brief (op! "devflow-complete" ["ops-feature" "brief captured"])]
        (is (= "discuss-scope" (:checkpoint (first (:ready after-brief))))))
      (let [in-proposal (op! "devflow-choose" ["ops-feature" "proposal-ready"])]
        (is (= "proposal" (:stage (first (:ready in-proposal)))))
        (is (= "devflow.proposal.orient" (:action-ref (first (:ready in-proposal))))))
      (let [status (op! "devflow-status" ["ops-feature"])]
        (is (false? (:done status)))
        (is (= "proposal" (get-in (first (:roots status)) [:attributes :devflow/stage])))
        (is (= [{:attributes {:devflow/stage "proposal"}}]
               (->> (:runs (op! "workflow-runs" ["devflow"]))
                    (mapv (fn [run] {:attributes (select-keys (:attributes run) [:devflow/stage])})))))))))

(deftest devflow-advance-describe-history-and-archive-ops
  (with-config-runtime
    (fn [_rt]
      (let [description (op! "devflow-describe" ["proposal"])]
        (is (= "devflow-describe" (:operation description)))
        (is (= "proposal" (:stage description)))
        (is (= "Devflow proposal: <feature>" (get-in description [:description :name]))))
      (op! "devflow-start" ["archive-feature" "already-in-worktree-ok"])
      (let [aborted (op! "devflow-advance" ["archive-feature" "abort" "{\"reason\":\"not now\"}"])]
        (is (= "devflow-advance" (:operation aborted)))
        (is (= "abort" (:stage (first (:ready aborted)))))
        (is (= "devflow.abort.record" (:action-ref (first (:ready aborted))))))
      (let [done (op! "devflow-advance" ["archive-feature" "recorded abort"])]
        (is (true? (:done done)))
        (is (empty? (:ready done))))
      (let [history (op! "devflow-history" ["archive-feature"])]
        (is (= "devflow-history" (:operation history)))
        (is (seq (:history history))))
      (let [archived (op! "devflow-archive" ["archive-feature"])]
        (is (= "devflow-archive" (:operation archived)))
        (is (= "digest" (get-in archived [:digest :attributes :workflow/role])))))))

(deftest work-query-excludes-workflow-plumbing-but-keeps-steps
  (with-config-runtime
    (fn [rt]
      (doseq [[title role] [["Root" "molecule"]
                            ["Procedure" "procedure"]
                            ["Digest" "digest"]
                            ["Step" "step"]
                            ["Checkpoint" "checkpoint"]
                            ["Plain task" nil]]]
        (api/add rt {:title title
                     :state "active"
                     :attributes (cond-> {:feature "work-query"}
                                   role (assoc :workflow/role role))}))
      (is (= #{"Step" "Checkpoint" "Plain task"}
             (set (map :title (api/list rt (var-get (requiring-resolve 'config/work-query)) {})))))
      (is (= #{"Step" "Checkpoint" "Plain task"}
             (set (map :title (api/ready rt (var-get (requiring-resolve 'config/work-query)) {}))))))))

(deftest devflow-ops-fail-loudly-on-bad-input
  (with-config-runtime
    (fn [_rt]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expects between"
                            (op! "devflow-start" [])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"worktree-check"
                            (op! "devflow-start" ["bad-feature" "nope"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"JSON input must be an object"
                            (op! "devflow-choose" ["any-feature" "abort" "[1,2]"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                            (op! "devflow-status" ["never-started"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at most one step"
                            (op! "devflow-complete" ["any-feature" "step=a" "step=b"])))
      (let [started (op! "devflow-start" ["checkpoint-feature"])
            checkpoint-id (:id (first (:ready started)))]
        ;; the step=<id> selector resolves independently of other optional args
        (is (contains? (:choices (op! "devflow-choices"
                                      ["checkpoint-feature" (str "step=" checkpoint-id)]))
                       "abort"))
        ;; the engine's checkpoint guard surfaces through the op wrapper
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot complete a checkpoint"
                              (op! "devflow-complete" ["checkpoint-feature"])))))))

(deftest repo-local-startup-and-reload-preserve-registrations
  (with-startup-config-runtime
    (fn [rt]
      (assert-config-registrations rt)
      (op! "devflow-start" ["startup-feature" "already-in-worktree-ok"])
      (is (= :loaded (:status (runtime-alpha/reload!))))
      (assert-config-registrations rt)
      ;; runtime registries reload; the strand graph and run state persist
      (let [status (op! "devflow-status" ["startup-feature"])]
        (is (false? (:done status)))
        (is (= "create-or-confirm-worktree" (:checkpoint (first (:ready status)))))))))
