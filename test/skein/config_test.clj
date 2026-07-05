(ns skein.config-test
  "Tests for the repo-local .skein config: registration surface, the
  delegate-pipeline weave pattern, and the devflow op wrappers over
  skein.spools.devflow."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.core.db-test :as db-test]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime-alpha]
            [skein.api.weaver.alpha :as api]
            [skein.core.weaver.config :as daemon-config]
            [skein.core.weaver.runtime :as runtime]
            [skein.spools.workflow :as workflow]
            [skein.test.alpha :as test-alpha]))

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
        ;; config.clj's pi-main alias layers over the shipped :pi harness,
        ;; which shuttle/install! registers in real startups; this fixture
        ;; loads config.clj alone, so register the defaults here.
        ((requiring-resolve 'skein.spools.shuttle/register-default-harnesses!))
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
  (doseq [name ["init.clj" "config.clj" "reviewers.clj" "spools.edn"]]
    (io/copy (io/file ".skein" name) (io/file target name)))
  ;; The shipped spools.edn approves local roots relative to the config dir,
  ;; which does not resolve from a copy. Rewrite it to the repo's canonical
  ;; spool roots so the whole test JVM syncs one root per lib
  ;; (tools.deps add-libs state is JVM-global; see skein.test-runner's
  ;; ordering note).
  (spit (io/file target "spools.edn")
        (pr-str {:spools {'skein.spools/shuttle
                          {:local/root (.getCanonicalPath (io/file "spools/shuttle"))}
                          'skein.spools/agents
                          {:local/root (.getCanonicalPath (io/file "spools/agents"))}
                          'skein.spools/chime
                          {:local/root (.getCanonicalPath (io/file "spools/chime"))}
                          'skein.spools/kanban
                          {:local/root (.getCanonicalPath (io/file "spools/kanban"))}
                          ;; init.clj requires this spool; the omission used to be
                          ;; masked by fail-quiet required use!, which now throws.
                          ;; Its root lives inside the workspace (.skein/spools/macros),
                          ;; unlike the repo-root spools above.
                          'skein.macros/macros
                          {:local/root (.getCanonicalPath (io/file ".skein/spools/macros"))}
                          'codethread/devflow
                          {:local/root (.getCanonicalPath (test-alpha/spool-checkout-root "skein/spools/devflow.clj"))}}}))
  ;; The shipped config leaves chime's notifier to each developer's personal
  ;; init.local.clj. Bind an inert command through that same overlay hook
  ;; (loaded after init.clj on startup and on every reload) so the test also
  ;; exercises the overlay path, a developer's real init.local.clj is never
  ;; read, and test-created HITL checkpoints record no notifier-missing noise.
  (spit (io/file target "init.local.clj")
        (pr-str '(do (require '[skein.spools.chime :as chime])
                     (chime/set-notifier! {:argv ["true"]})))))

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
  "Invoke a repo-local registered op by name with a CLI-shaped argv."
  [op-name argv]
  (api/op! (current/runtime) (symbol op-name) argv))

(defn- assert-config-registrations
  "Assert the repo-local query/op/pattern registrations are present."
  [rt]
  (doseq [query-name ["kanban-cards" "kanban-unstarted" "feature-active" "feature-work"
                      "feature-owner-work" "feature-run" "workflow-runs" "devflow-runs" "work"]]
    (is (contains? (api/queries rt) query-name)))
  (doseq [op-name ["kanban" "branches" "current-dags" "devflow-start" "devflow-next" "devflow-choices"
                   "devflow-choose" "devflow-complete" "devflow-advance"
                   "devflow-describe" "devflow-history" "devflow-archive"
                   "devflow-status" "workflow-runs" "devflow-conventions"
                   "flow-await" "flow-status" "agent"]]
    (is (some #(= op-name (:name %)) (api/ops rt))))
  (is (some #(= "delegate-pipeline" (:name %)) (api/patterns rt)))
  ;; agent-plan is spool-owned now; a real startup wires the agents spool in
  ;; via init.clj, so it must still be registered end to end
  (is (some #(= "agent-plan" (:name %)) (api/patterns rt)))
  ;; agent review must consume the one authoritative policy text by default;
  ;; the text ships from skein.spools.agents, the accessor stays on shuttle
  (is (= (var-get (requiring-resolve 'skein.spools.agents/review-contract))
         ((requiring-resolve 'skein.spools.shuttle/default-review-contract-text))))
  ;; the repo owns chime's attention rules; the chime engine ships none
  (is (= [:agent-failure :hitl-checkpoint-ready :kanban-blocked :kanban-completed
          :kanban-started :parked-run :treadle-error]
         (mapv :name ((requiring-resolve 'skein.spools.chime/rules)))))
  ;; the declarative reviewer roster registers from .skein/reviewers.clj
  (let [rosters ((requiring-resolve 'skein.spools.agents/rosters))]
    (is (= [:change-review] (mapv :name rosters)))
    (is (some #(= "test-sleeps" (:name %)) (:reviewers (first rosters))))))

(deftest current-dags-op-builds-self-contained-plan-task-projection
  (with-config-runtime
    (fn [rt]
      (let [plan (api/add rt {:title "Feature: plan feature"
                              :state "active"
                              :attributes {:feature "plan-feature" :kind "plan" :workflow "agent-plan"}})
            impl (api/add rt {:title "Implement it"
                              :state "active"
                              :attributes {:feature "plan-feature" :kind "task" :workflow "agent-plan"
                                           :task_key "impl" :owner "agent-a" :harness "build"
                                           :cwd "/tmp/work" :validation ["clojure -M:test"]}})
            review (api/add rt {:title "Review it"
                                :state "active"
                                :attributes {:feature "plan-feature" :kind "review" :workflow "agent-plan"
                                             :task_key "review" :hitl true}})]
        (api/update rt (:id plan) {:edges [{:type "parent-of" :to (:id impl)}
                                           {:type "parent-of" :to (:id review)}]})
        (api/update rt (:id review) {:edges [{:type "depends-on" :to (:id impl)}]})
        (let [strands (api/list rt (var-get (requiring-resolve 'config/feature-active-query))
                                {:feature "plan-feature"})
              children (:edges (api/subgraph rt [(:id plan)] {:type "parent-of"}))
              ready (api/ready rt (var-get (requiring-resolve 'config/feature-work-query))
                               {:feature "plan-feature"})]
          (is (= 3 (count strands)))
          (is (= #{(:id impl) (:id review)}
                 (set (map :to_strand_id children))))
          ;; review depends on impl, so only impl is ready
          (is (= ["Implement it"] (mapv :title ready)))
          ;; current-dags stays self-contained: a blocker outside the plan DAG
          ;; must not surface as a dangling depends-on edge
          (let [external (api/add rt {:title "External blocker"
                                      :state "active"
                                      :attributes {:kind "task"}})]
            (api/update rt (:id impl)
                        {:edges [{:type "depends-on" :to (:id external)}]})
            (let [dag (->> (:dags (op! "current-dags" []))
                           (filter #(= (:id plan) (get-in % [:root :id])))
                           first)
                  dag-ids (set (map :id (:strands dag)))]
              (is (some? dag))
              (is (every? #(and (contains? dag-ids (:from_strand_id %))
                                (contains? dag-ids (:to_strand_id %)))
                          (concat (:parent_of_edges dag) (:depends_on_edges dag)))))))))))

(deftest branches-op-groups-branch-stamped-work-roots
  (with-config-runtime
    (fn [rt]
      (let [root (api/add rt {:title "Card: feature-x"
                              :state "active"
                              :attributes {:kanban/card "true" :kanban/status "claimed"
                                           :owner "agent-a" :branch "feature-x"
                                           :worktree "/tmp/feature-x"}})
            task (api/add rt {:title "Implement feature-x"
                              :state "active"
                              :attributes {:kind "task"}})
            review (api/add rt {:title "Review feature-x"
                                :state "active"
                                :attributes {:kind "review"}})]
        (api/update rt (:id root) {:edges [{:type "parent-of" :to (:id task)}
                                           {:type "parent-of" :to (:id review)}]})
        (api/update rt (:id review) {:edges [{:type "depends-on" :to (:id task)}]})
        ;; a branch-stamped child (task assigned owner+branch) must not
        ;; surface as a second work root for the branch
        (api/update rt (:id task) {:attributes {:kind "task" :branch "feature-x" :owner "agent-b"}})
        (let [result (op! "branches" [])
              branch (first (:branches result))
              view (first (:roots branch))]
          (is (= ["feature-x"] (mapv :branch (:branches result))))
          (is (= [(:id root)] (mapv #(get-in % [:root :id]) (:roots branch))))
          (is (= #{(:id task) (:id review)}
                 (set (map :id (:active_descendants view)))))
          ;; review depends on the task, so the frontier is root + task
          (is (= #{(:id root) (:id task)} (set (map :id (:ready view))))))
        (is (= 1 (count (:branches (op! "branches" ["feature-x"])))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no active work root"
                              (op! "branches" ["missing-branch"])))))))

(deftest delegate-pipeline-weave-creates-chain-loop-gates
  (with-config-runtime
    (fn [rt]
      (api/weave! rt :delegate-pipeline
                  {:run_id "pipe-test"
                   :harness "pi-main"
                   :accept true
                   :tasks [{:id "a" :title "Do A" :body "A body"}
                           {:id "b" :title "Do B"}]})
      (let [strands (api/list rt)
            by-task (into {} (keep (fn [s]
                                     (when-let [task (or (get-in s [:attributes :delegate-pipeline/task])
                                                         (get-in s [:attributes "delegate-pipeline/task"]))]
                                       [task s])) strands))
            attr (fn [s k]
                   (or (get-in s [:attributes k])
                       (get-in s [:attributes (name k)])))]
        (is (= #{"a" "b"} (set (keys by-task))))
        (is (str/includes? (attr (by-task "a") :shuttle/prompt)
                           "[worker contract]"))
        (is (= "pi-main" (attr (by-task "b") :shuttle/harness))))))
  (testing "acceptance checkpoint is optional and task max-attempts pass through"
    (with-config-runtime
      (fn [rt]
        (api/weave! rt :delegate-pipeline
                    {:run_id "pipe-no-accept"
                     :tasks [{:id "a" :title "Do A" :harness "pi-main" :max-attempts 4}]})
        (let [strands (api/list rt)
              task (first (filter #(= "a" (or (get-in % [:attributes :delegate-pipeline/task])
                                               (get-in % [:attributes "delegate-pipeline/task"])))
                                  strands))]
          (is (some? task))
          (is (= 4 (or (get-in task [:attributes :shuttle/max-attempts])
                       (get-in task [:attributes "shuttle/max-attempts"]))))
          (is (not-any? #(= "checkpoint" (or (get-in % [:attributes :workflow/role])
                                             (get-in % [:attributes "workflow/role"])))
                        strands)))))))

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
                            ["Run record" nil]
                            ["Plain task" nil]]]
        (api/add rt {:title title
                     :state "active"
                     :attributes (cond-> {:feature "work-query"}
                                   role (assoc :workflow/role role)
                                   (= title "Run record") (assoc :shuttle/run "true"))}))
      (is (= #{"Step" "Checkpoint" "Plain task"}
             (set (map :title (api/list rt (var-get (requiring-resolve 'config/work-query)) {})))))
      (is (= #{"Step" "Checkpoint" "Plain task"}
             (set (map :title (api/ready rt (var-get (requiring-resolve 'config/work-query)) {}))))))))

(deftest reviewers-file-registers-declarative-roster
  ;; exercises the same load path init.clj's :file+:call reviewers module runs
  (with-config-runtime
    (fn [_rt]
      (load-file ".skein/reviewers.clj")
      ((requiring-resolve 'reviewers/install!))
      (let [[roster :as rosters] ((requiring-resolve 'skein.spools.agents/rosters))]
        (is (= [:change-review] (mapv :name rosters)))
        (let [sleeps (first (filter #(= "test-sleeps" (:name %)) (:reviewers roster)))]
          (is (some? sleeps) "owner-required test-sleeps reviewer is declared")
          (is (str/includes? (:contract sleeps) "time itself is a genuine component")))
        (is (= :review-gpt (get-in roster [:synthesizer :harness]))
            "sign-off synthesis stays on the cross-vendor GPT seat")))))

(deftest codex-harness-persists-sessions-and-declares-resume
  ;; PLAN-Pnl-001.A2/PH2: the repo :codex harness drops --ephemeral (sessions
  ;; persist) and declares the verified `codex exec resume <session-id>` splice.
  (with-config-runtime
    (fn [_rt]
      (let [codex ((requiring-resolve 'skein.spools.shuttle/resolve-harness) :codex)]
        (is (not (some #{"--ephemeral"} (:argv codex)))
            "sessions persist so codex exec resume can continue them")
        (is (= ["resume" :shuttle/session-id] (:resume codex))
            "codex declares its verified resume subcommand splice")))))

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

(deftest flow-status-op-joins-history-frontier-gates-runs-and-stalls
  (with-config-runtime
    (fn [rt]
      (let [definition (workflow/workflow
                         "Flow status test"
                         (workflow/gate :a "Delegate A" :subagent)
                         (workflow/gate :b "Delegate B" :subagent :depends-on [:a])
                         (workflow/checkpoint :accept "Accept" :depends-on [:b]
                                              :choices [:accepted]))]
        (workflow/start! "flow-status-test" definition {})
        (let [gate-a (:id (first (workflow/next-steps "flow-status-test")))
              run-a (api/add rt {:title "Run A"
                                 :state "closed"
                                 :attributes {:shuttle/run "true"
                                              :shuttle/phase "done"
                                              :shuttle/result "A complete"
                                              :treadle/gate gate-a
                                              :treadle/run-id "flow-status-test"}})]
          (api/update rt gate-a {:attributes {:treadle/run (:id run-a)}})
          (workflow/complete! "flow-status-test" {:step gate-a :by (:id run-a)})
          (let [gate-b (:id (first (workflow/next-steps "flow-status-test")))
                run-b (api/add rt {:title "Run B"
                                   :state "active"
                                   :attributes {:shuttle/run "true"
                                                :shuttle/phase "failed"
                                                :shuttle/error "boom"
                                                :treadle/gate gate-b
                                                :treadle/run-id "flow-status-test"}})]
            (api/update rt gate-b {:attributes {:treadle/run (:id run-b)}})
            ;; failure summaries are scoped to the requested run: an unrelated
            ;; failed run and an unrelated error-stamped gate must not leak in
            (api/add rt {:title "Unrelated failed run"
                         :attributes {:shuttle/run "true"
                                      :shuttle/phase "failed"
                                      :shuttle/error "other workflow"}})
            (api/add rt {:title "Unrelated stalled gate"
                         :attributes {:workflow/gate "subagent"
                                      :treadle/error "spawn failed elsewhere"}})
            (let [status (op! "flow-status" ["flow-status-test"])
                  by-title (into {} (map (juxt :title identity)) (:gates status))]
              (is (= "flow-status" (:operation status)))
              (is (false? (:done status)))
              (is (= ["Delegate B"] (mapv :title (:frontier status))))
              (is (= [:gate-closed] (mapv :type (get-in status [:history 0 :events]))))
              (is (= "done" (get-in by-title ["Delegate A" :run :shuttle/phase])))
              (is (= "failed" (get-in by-title ["Delegate B" :run :shuttle/phase])))
              (is (true? (get-in by-title ["Delegate B" :stalled?])))
              (is (= #{(:id run-b)} (set (map :id (:agent-failures status)))))
              (is (empty? (:stalled-gates status)))
              (is (str/includes? (:dev/mermaid status) "Delegate B (stalled)")))))))))

(defn- assert-treadle-installed-after-config
  "Assert treadle loaded and declares :config in :after — its install! runs an
  initial gate scan, so config.clj's harness aliases must already exist or a
  durable ready gate would be stamped treadle/error on every cold start."
  [rt]
  (let [use (get (api/uses rt) :skein/spools-treadle)]
    (is (= :loaded (:status use)))
    (is (some #{:config} (get-in use [:opts :after])))))

(deftest repo-local-startup-and-reload-preserve-registrations
  (with-startup-config-runtime
    (fn [rt]
      (assert-config-registrations rt)
      (assert-treadle-installed-after-config rt)
      (op! "devflow-start" ["startup-feature" "already-in-worktree-ok"])
      (is (= :loaded (:status (runtime-alpha/reload! rt))))
      (assert-config-registrations rt)
      ;; runtime registries reload; the strand graph and run state persist
      (let [status (op! "devflow-status" ["startup-feature"])]
        (is (false? (:done status)))
        (is (= "create-or-confirm-worktree" (:checkpoint (first (:ready status)))))))))
