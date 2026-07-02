(ns skein.spools.devflow-test
  "Tests for the skein.spools.devflow lifecycle spool: stage workflows,
  decision-point checkpoints, revision loops, and the small operational
  loop layered over skein.spools.workflow runs."
  (:require [clojure.test :refer [deftest is]]
            [skein.spools.devflow :as devflow]
            [skein.spools.test-support :refer [with-runtime]]
            [skein.spools.workflow :as workflow]))

(deftest devflow-proposal-revise-loops-back-through-the-proposal-stage
  (with-runtime
    (fn [_ _]
      (workflow/start! "prop-run"
                       (devflow/proposal-workflow {:feature "widgets"})
                       {:feature "widgets"}
                       {:family "devflow"
                        :definition 'skein.spools.devflow/proposal-workflow
                        :context {:feature "widgets"}})
      (is (= "Inspect relevant RFCs, spikes, root specs, and active feature context for widgets"
             (:title (workflow/next-step "prop-run"))))
      (is (= "Write devflow proposal for widgets" (:title (first (:ready (workflow/complete! "prop-run"))))))
      (is (= "Run agent review for widgets proposal" (:title (first (:ready (workflow/complete! "prop-run"))))))
      ;; completing the inner review step auto-closes the agent-review join, so
      ;; the sign-off checkpoint is next with no join step to complete
      (is (= "Human sign-off for widgets proposal" (:title (first (:ready (workflow/complete! "prop-run"))))))
      ;; revise routes back into a fresh proposal round that skips :inspect-context
      (let [remaining (:ready (workflow/choose! "prop-run" :revise))]
        (is (= [{:title "Write devflow proposal for widgets" :kind "step"}]
               (mapv #(select-keys % [:title :kind]) remaining))))
      (is (= "Run agent review for widgets proposal" (:title (first (:ready (workflow/complete! "prop-run"))))))
      (is (= "Human sign-off for widgets proposal" (:title (first (:ready (workflow/complete! "prop-run"))))))
      ;; :approved routes on to the spec/plan stage; the poured spec-plan root
      ;; presenting its entry step is enough to confirm the loop closed
      (is (= [{:title "Write needed spec deltas for widgets" :kind "step"}]
             (mapv #(select-keys % [:title :kind]) (:ready (workflow/choose! "prop-run" :approved)))))
      (let [root (workflow/current-root "prop-run")]
        (is (= "Devflow spec and plan: widgets" (:title root)))
        ;; entering a fresh stage resets stage-local loop state: the revised
        ;; round's :revision flag must not ride forward in downstream context
        (is (not (contains? (get-in root [:attributes :workflow/context]) :revision)))))))

(deftest devflow-revise-input-does-not-override-revision-round
  (with-runtime
    (fn [_ _]
      (workflow/start! "prop-input"
                       (devflow/proposal-workflow {:feature "widgets"})
                       {:feature "widgets"}
                       {:family "devflow"
                        :definition 'skein.spools.devflow/proposal-workflow
                        :context {:feature "widgets"}})
      ;; inspect-context, write-proposal, then the inner agent-review step (whose
      ;; completion auto-closes the join) reach the sign-off checkpoint
      (dotimes [_ 3] (workflow/complete! "prop-input"))
      ;; a caller passing {:revision false} must not un-skip :inspect-context:
      ;; the revision wrapper's :params are authoritative over the choice input
      (let [remaining (:ready (workflow/choose! "prop-input" :revise {:revision false}))]
        (is (= [{:title "Write devflow proposal for widgets" :kind "step"}]
               (mapv #(select-keys % [:title :kind]) remaining)))))))

(deftest devflow-intake-revision-preserves-start-opts
  (with-runtime
    (fn [_ _]
      (devflow/start! "intake-loop" {:worktree-check :already-in-worktree-ok})
      (devflow/choose! "intake-loop" :already-in-worktree)
      (devflow/complete! "intake-loop")
      ;; the revision round skips the worktree checkpoint and resumes at capture-brief
      (is (= "Capture user brief for intake-loop"
             (:title (first (:ready (devflow/choose! "intake-loop" :needs-more-brief))))))
      ;; the start opt survived the loop: the fresh intake root still records it
      (is (= "already-in-worktree-ok"
             (get-in (workflow/current-root "intake-loop")
                     [:attributes :devflow/worktree-check]))))))

(deftest devflow-spool-composes-decision-point-workflows
  (with-runtime
    (fn [_ _]
      (let [intake (devflow/intake-workflow {:worktree-check :already-in-worktree-ok})
            proposal (devflow/proposal-workflow {})
            route (devflow/route-after-plan-workflow {})
            intake-result (workflow/pour! intake {:feature "workflow-stress"})
            intake-root (first (:created intake-result))
            proposal-payload (workflow/compile proposal {:feature "workflow-stress"})
            route-payload (workflow/compile route {:feature "workflow-stress"})]
        (is (= "already-in-worktree-ok"
               (get-in intake-root [:attributes :devflow/worktree-check])))
        (is (some #(= "Create or confirm feature worktree for workflow-stress" (:title %))
                  (:created intake-result)))
        (is (some #(= "devflow" (get-in % [:attributes "skills"]))
                  (:strands proposal-payload)))
        (is (some #(= {"workflow/hitl" "true"
                       "workflow/decision-point" "proposal-signed-off"}
                      (select-keys (:attributes %) ["workflow/hitl" "workflow/decision-point"]))
                  (:strands proposal-payload)))
        (is (some #(= ["task-breakdown" "direct-implementation"]
                      (get-in % [:attributes "workflow/choices"]))
                  (:strands route-payload)))))))

(deftest devflow-spool-exposes-small-operational-loop
  (with-runtime
    (fn [_ _]
      (devflow/start! "workflow-loop" {:worktree-check :already-in-worktree-ok})
      (let [first-step (devflow/next-step "workflow-loop")]
        (is (= "checkpoint" (:kind first-step)))
        (is (= "intake" (:stage first-step)))
        (is (= "create-or-confirm-worktree" (:checkpoint first-step)))
        (is (= "already-in-worktree-ok"
               (get-in (first (devflow/feature-roots "workflow-loop"))
                       [:attributes :devflow/worktree-check])))
        (is (= ["created-worktree" "already-in-worktree" "abort"]
               (:choices first-step)))
        (is (= {"label" "Abort"
                "description" "Stop the feature before any substantive work begins."
                "next" ":abort"
                "input" [{"key" "reason" "required" true
                          "description" "Why the feature is being aborted; recorded on the abort step."}]}
               (devflow/choice-detail "workflow-loop" :abort)))
        (is (not (contains? first-step :choice-details)))
        (let [ready (first (:ready (devflow/choose! "workflow-loop" :already-in-worktree)))]
          (is (= "Capture user brief for workflow-loop" (:title ready)))
          (is (= "intake" (:stage ready))))))))

(deftest devflow-afk-loop-delegation-shapes-gates-and-legacy-step
  (let [legacy (workflow/describe (devflow/run-afk-loop-workflow {}) {:feature "widgets"})
        delegated (workflow/describe
                   (devflow/run-afk-loop-workflow
                    {:feature "widgets"
                     :tasks [{:id "a" :title "Do A" :body "Body A"}
                             {:id "b" :title "Do B"}]
                     :delegate-harness "pi-main"
                     :delegate-cwd "/tmp/widgets"})
                   {:feature "widgets"})
        steps (into {} (map (juxt :id identity)) (:steps delegated))]
    (is (= [:run-afk-loop] (mapv :id (:steps legacy))))
    (is (= [:task-a :task-b :human-acceptance-afk]
           (mapv :id (:steps delegated))))
    (is (= "subagent" (:gate (steps :task-a))))
    (is (= "subagent" (:gate (steps :task-b))))
    (is (= [] (:depends-on (steps :task-a))))
    (is (= [:task-a] (:depends-on (steps :task-b))))
    (is (= [:task-a :task-b] (:depends-on (steps :human-acceptance-afk))))
    (is (= ["accepted" "revise" "abort"]
           (mapv :key (:choices (steps :human-acceptance-afk))))))
  (let [payload (workflow/compile
                 (devflow/run-afk-loop-workflow
                  {:feature "widgets"
                   :tasks [{:id "a" :title "Do A" :body "Body A"}
                           {:id "b" :title "Do B" :harness "pi-alt"}]
                   :delegate-harness "pi-main"
                   :delegate-cwd "/tmp/widgets"})
                 {:feature "widgets"})
        by-local-id (into {} (map (juxt :ref identity)) (:strands payload))]
    (is (= {"workflow/gate" "subagent"
            "devflow/task" "a"
            "shuttle/harness" "pi-main"
            "shuttle/cwd" "/tmp/widgets"}
           (select-keys (get-in by-local-id [:task-a :attributes])
                        ["workflow/gate" "devflow/task" "shuttle/harness" "shuttle/cwd"])))
    (is (= "Devflow AFK task for widgets: Do A\n\nBody A"
           (get-in by-local-id [:task-a :attributes "shuttle/prompt"])))
    (is (= "pi-alt"
           (get-in by-local-id [:task-b :attributes "shuttle/harness"])))))

(deftest devflow-afk-loop-prompt-renders-from-params
  ;; feature supplied only as a workflow param, not a constructor opt: the
  ;; prompt must render at compile time like the title instead of baking nil
  (let [payload (workflow/compile
                 (devflow/run-afk-loop-workflow
                  {:tasks [{:id "a" :title "Do A" :body "Body A"}]
                   :delegate-harness "pi-main"})
                 {:feature "widgets"})
        task-a (first (filter #(= :task-a (:ref %)) (:strands payload)))]
    (is (= "Devflow AFK task for widgets: Do A\n\nBody A"
           (get-in task-a [:attributes "shuttle/prompt"])))))

(deftest devflow-afk-loop-delegation-fails-loudly
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
                        (devflow/run-afk-loop-workflow {:tasks [] :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token-safe"
                        (devflow/run-afk-loop-workflow {:tasks [{:title "No id"}]
                                                       :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token-safe"
                        (devflow/run-afk-loop-workflow {:tasks [{:id "has space" :title "Bad id"}]
                                                       :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token-safe"
                        (devflow/run-afk-loop-workflow {:tasks [{:id :kw-id :title "Bad id"}]
                                                       :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"harness must be a non-blank string"
                        (devflow/run-afk-loop-workflow {:tasks [{:id "a" :title "A" :harness :pi}]
                                                       :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ids must be unique"
                        (devflow/run-afk-loop-workflow {:tasks [{:id "a" :title "A"}
                                                                {:id "a" :title "Again"}]
                                                       :delegate-harness "pi"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing harness resolution"
                        (devflow/run-afk-loop-workflow {:tasks [{:id "a" :title "A"}]}))))

(deftest devflow-afk-loop-routing-and-revision-pour-delegated-gates
  (with-runtime
    (fn [_ _]
      (workflow/start! "afk-route"
                       (devflow/task-breakdown-workflow {:feature "afk-route"})
                       {:feature "afk-route"}
                       {:family "devflow"
                        :definition 'skein.spools.devflow/task-breakdown-workflow
                        :context {:feature "afk-route"}})
      (dotimes [_ 2] (workflow/complete! "afk-route"))
      (let [ready (:ready (devflow/choose! "afk-route" :approved
                                           {"tasks" [{"id" "one" "title" "One" "body" "Do one"}
                                                      {"id" "two" "title" "Two"}]
                                            "delegate-harness" "sh"}))]
        (is (= [{:title "Delegate AFK task one for afk-route" :gate "subagent"}]
               (mapv #(select-keys % [:title :gate]) ready)))
        (is (= "afk" (get-in (workflow/current-root "afk-route") [:attributes :devflow/stage]))))
      (let [after-first (:ready (devflow/complete! "afk-route" {:by "run-one"}))]
        (is (= [{:title "Delegate AFK task two for afk-route" :gate "subagent"}]
               (mapv #(select-keys % [:title :gate]) after-first))))
      (devflow/complete! "afk-route" {:by "run-two"})
      (is (= "human-acceptance-afk" (:checkpoint (devflow/next-step "afk-route"))))
      (let [revised (:ready (devflow/choose! "afk-route" :revise))]
        (is (= [{:title "Delegate AFK task one for afk-route" :gate "subagent"}]
               (mapv #(select-keys % [:title :gate]) revised)))
        (is (= "afk-route" (:run-id (first revised))))))))

(deftest devflow-registered-routes-cover-later-stage-runtime-paths
  (with-runtime
    (fn [_ _]
      (devflow/start! "route-happy" {:worktree-check :already-in-worktree-ok})
      (devflow/advance! "route-happy" {:choice :already-in-worktree})
      (devflow/advance! "route-happy" {:notes "brief captured"})
      (devflow/advance! "route-happy" {:choice :proposal-ready})
      (dotimes [_ 3] (devflow/advance! "route-happy" {:notes "proposal work"}))
      (devflow/advance! "route-happy" {:choice :approved})
      (dotimes [_ 3] (devflow/advance! "route-happy" {:notes "spec-plan work"}))
      (let [route (first (:ready (devflow/advance! "route-happy" {:choice :approved})))]
        (is (= "route-after-plan" (:stage route)))
        (is (= "route-after-plan" (:checkpoint route))))
      (let [implementation (first (:ready (devflow/advance! "route-happy" {:choice :direct-implementation})))]
        (is (= "implementation" (:stage implementation)))
        (is (= "devflow.implementation.direct" (:action-ref implementation))))
      (dotimes [_ 3] (devflow/advance! "route-happy" {:notes "implementation work"}))
      (is (= {:ready [] :done true}
             (devflow/advance! "route-happy" {:choice :accepted}))))))

(deftest devflow-choice-next-workflow-validates-lazily
  (with-runtime
    (fn [_ _]
      (devflow/start! "workflow-abort" {:worktree-check :required})
      ;; the abort choice declares a required :reason input, so omitting it fails
      ;; loudly before any mutation (D1.2), leaving the checkpoint active
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required keys"
                            (devflow/choose! "workflow-abort" :abort)))
      (is (= "create-or-confirm-worktree"
             (:checkpoint (devflow/next-step "workflow-abort"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Choice input must be a map"
                            (devflow/choose! "workflow-abort" :abort [:bad])))
      (let [ready (first (:ready (devflow/choose! "workflow-abort" :abort {:reason "cancelled"})))]
        (is (= "Record abort for workflow-abort: cancelled" (:title ready)))
        (is (= "abort" (:stage ready)))))))

(deftest devflow-next-step-fails-on-multiple-active-roots
  (with-runtime
    (fn [_ _]
      (devflow/start! "workflow-duplicate-root" {:worktree-check :required})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Active workflow run already exists"
                            (devflow/start! "workflow-duplicate-root" {:worktree-check :required}))))))

(deftest devflow-describe-surfaces-stage-choices-and-conditioned-steps
  ;; describing a stage projects its shape without pouring: the proposal stage's
  ;; sign-off checkpoint carries its routing and declared abort input, and the
  ;; agent-review call expands into a :procedure step.
  (let [proposal (devflow/describe :proposal)
        ids (set (map :id (:steps proposal)))
        signoff (first (filter #(= "checkpoint" (:kind %)) (:steps proposal)))
        choices (into {} (map (juxt :key identity)) (:choices signoff))]
    (is (contains? ids :inspect-context))
    (is (some #(= "procedure" (:kind %)) (:steps proposal)))
    (is (= ":spec-plan" (:next (get choices "approved"))))
    (is (= [{"key" "reason" "required" true
             "description" "Why the feature is being aborted; recorded on the abort step."}]
           (:input (get choices "abort")))))
  ;; a revision round condition-excludes the orientation step
  (is (not (contains? (set (map :id (:steps (workflow/describe (devflow/proposal-workflow {:revision true})
                                                               {:feature "widgets"}))))
                      :inspect-context))))

(deftest devflow-describe-defaults-to-the-full-cycle
  (let [cycle (devflow/describe)]
    (is (= 7 (count cycle)))
    (is (= "Devflow intake: <feature>" (:name (first cycle))))
    (is (every? #(seq (:steps %)) cycle))))

(deftest devflow-history-and-archive-project-then-squash-a-run
  (with-runtime
    (fn [_ _]
      (devflow/start! "af-run" {:worktree-check :already-in-worktree-ok})
      ;; abort the feature: intake routes to the abort stage, then record the abort
      (devflow/choose! "af-run" :abort {:reason "not needed"})
      (devflow/complete! "af-run")
      (is (workflow/done? "af-run"))
      (let [history (devflow/history "af-run")
            intake-mol (first (filter #(= "intake" (get-in % [:root :stage])) history))
            ;; the abort route also force-closes intake's later discuss-scope
            ;; checkpoint (a decision-less :choice event), so select by outcome
            abort-choice (first (filter #(= "abort" (:outcome %)) (:events intake-mol)))]
        (is (= 2 (count history)))
        (is (= #{"intake" "abort"} (set (keep #(get-in % [:root :stage]) history))))
        (is (= :choice (:type abort-choice)))
        (is (= {:reason "not needed"} (:input abort-choice))))
      (let [digest (devflow/archive! "af-run")]
        (is (= "digest" (get-in digest [:attributes :workflow/role])))
        (is (= "af-run" (get-in digest [:attributes :workflow/run-id])))
        (is (contains? (set (keep :stage (get-in digest [:attributes :workflow/summary])))
                       "intake"))
        ;; the run's molecules are burned, so history now fails loudly
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                              (devflow/history "af-run")))))))
