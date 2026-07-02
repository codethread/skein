(ns skein.spools.devflow-test
  "Tests for the skein.spools.devflow lifecycle spool: stage workflows,
  decision-point checkpoints, revision loops, and the small operational
  loop layered over skein.spools.workflow runs."
  (:require [clojure.test :refer [deftest is]]
            [skein.spools.devflow :as devflow-lib]
            [skein.spools.test-support :refer [with-runtime]]
            [skein.spools.workflow :as workflow]))

(deftest devflow-proposal-revise-loops-back-through-the-proposal-stage
  (with-runtime
    (fn [_ _]
      (workflow/start! "prop-run"
                       (devflow-lib/proposal-workflow {:feature "widgets"})
                       {:feature "widgets"}
                       {:family "devflow"
                        :definition 'skein.spools.devflow/proposal-workflow
                        :context {:feature "widgets"}})
      (is (= "Inspect relevant RFCs, spikes, root specs, and active feature context for widgets"
             (:title (workflow/next-step "prop-run"))))
      (is (= "Write devflow proposal for widgets" (:title (first (workflow/complete! "prop-run")))))
      (is (= "Run agent review for widgets proposal" (:title (first (workflow/complete! "prop-run")))))
      (is (= "Complete agent review for widgets proposal" (:title (first (workflow/complete! "prop-run")))))
      (is (= "[HITL] Human sign-off for widgets proposal" (:title (first (workflow/complete! "prop-run")))))
      ;; revise routes back into a fresh proposal round that skips :inspect-context
      (let [remaining (workflow/choose! "prop-run" :revise)]
        (is (= [{:title "Write devflow proposal for widgets" :kind "step"}]
               (mapv #(select-keys % [:title :kind]) remaining))))
      (is (= "Run agent review for widgets proposal" (:title (first (workflow/complete! "prop-run")))))
      (is (= "Complete agent review for widgets proposal" (:title (first (workflow/complete! "prop-run")))))
      (is (= "[HITL] Human sign-off for widgets proposal" (:title (first (workflow/complete! "prop-run")))))
      ;; :approved routes on to the spec/plan stage; the poured spec-plan root
      ;; presenting its entry step is enough to confirm the loop closed
      (is (= [{:title "Write needed spec deltas for widgets" :kind "step"}]
             (mapv #(select-keys % [:title :kind]) (workflow/choose! "prop-run" :approved))))
      (let [root (workflow/current-root "prop-run")]
        (is (= "Devflow spec and plan: widgets" (:title root)))
        ;; entering a fresh stage resets stage-local loop state: the revised
        ;; round's :revision flag must not ride forward in downstream context
        (is (not (contains? (get-in root [:attributes :workflow/context]) :revision)))))))

(deftest devflow-revise-input-does-not-override-revision-round
  (with-runtime
    (fn [_ _]
      (workflow/start! "prop-input"
                       (devflow-lib/proposal-workflow {:feature "widgets"})
                       {:feature "widgets"}
                       {:family "devflow"
                        :definition 'skein.spools.devflow/proposal-workflow
                        :context {:feature "widgets"}})
      (dotimes [_ 4] (workflow/complete! "prop-input"))
      ;; a caller passing {:revision false} must not un-skip :inspect-context:
      ;; the revision wrapper's :params are authoritative over the choice input
      (let [remaining (workflow/choose! "prop-input" :revise {:revision false})]
        (is (= [{:title "Write devflow proposal for widgets" :kind "step"}]
               (mapv #(select-keys % [:title :kind]) remaining)))))))

(deftest devflow-intake-revision-preserves-start-opts
  (with-runtime
    (fn [_ _]
      (devflow-lib/start! "intake-loop" {:worktree-check :already-in-worktree-ok})
      (devflow-lib/choose! "intake-loop" :already-in-worktree)
      (devflow-lib/complete! "intake-loop")
      ;; the revision round skips the worktree checkpoint and resumes at capture-brief
      (is (= "Capture user brief for intake-loop"
             (:title (first (devflow-lib/choose! "intake-loop" :needs-more-brief)))))
      ;; the start opt survived the loop: the fresh intake root still records it
      (is (= "already-in-worktree-ok"
             (get-in (workflow/current-root "intake-loop")
                     [:attributes :devflow/worktree-check]))))))

(deftest devflow-spool-composes-decision-point-workflows
  (with-runtime
    (fn [_ _]
      (let [intake (devflow-lib/intake-workflow {:worktree-check :already-in-worktree-ok})
            proposal (devflow-lib/proposal-workflow {})
            route (devflow-lib/route-after-plan-workflow {})
            intake-result (workflow/pour! intake {:feature "workflow-stress"})
            intake-root (first (:created intake-result))
            proposal-payload (workflow/compile proposal {:feature "workflow-stress"})
            route-payload (workflow/compile route {:feature "workflow-stress"})]
        (is (= "already-in-worktree-ok"
               (get-in intake-root [:attributes :devflow/worktree-check])))
        (is (some #(= "[HITL] Create or confirm feature worktree for workflow-stress" (:title %))
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
      (devflow-lib/start! "workflow-loop" {:worktree-check :already-in-worktree-ok})
      (let [first-step (devflow-lib/next-step "workflow-loop")]
        (is (= "checkpoint" (:kind first-step)))
        (is (= "create-or-confirm-worktree" (:checkpoint first-step)))
        (is (= "already-in-worktree-ok"
               (get-in (first (devflow-lib/feature-roots "workflow-loop"))
                       [:attributes :devflow/worktree-check])))
        (is (= ["created-worktree" "already-in-worktree" "abort"]
               (:choices first-step)))
        (is (= {"label" "Abort"
                "description" "Stop the feature before any substantive work begins."
                "next" "skein.spools.devflow/abort-workflow"}
               (devflow-lib/choice-detail "workflow-loop" :abort)))
        (is (not (contains? first-step :choice-details)))
        (is (= "Capture user brief for workflow-loop"
               (:title (first (devflow-lib/choose! "workflow-loop" :already-in-worktree)))))))))

(deftest devflow-choice-next-workflow-validates-lazily
  (with-runtime
    (fn [_ _]
      (devflow-lib/start! "workflow-abort" {:worktree-check :required})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"required params"
                            (devflow-lib/choose! "workflow-abort" :abort)))
      (is (= "create-or-confirm-worktree"
             (:checkpoint (devflow-lib/next-step "workflow-abort"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Choice input must be a map"
                            (devflow-lib/choose! "workflow-abort" :abort [:bad])))
      (is (= "Record abort for workflow-abort: cancelled"
             (:title (first (devflow-lib/choose! "workflow-abort" :abort {:reason "cancelled"}))))))))

(deftest devflow-next-step-fails-on-multiple-active-roots
  (with-runtime
    (fn [_ _]
      (devflow-lib/start! "workflow-duplicate-root" {:worktree-check :required})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Active workflow run already exists"
                            (devflow-lib/start! "workflow-duplicate-root" {:worktree-check :required}))))))
