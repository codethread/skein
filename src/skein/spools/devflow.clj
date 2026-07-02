(ns skein.spools.devflow
  "Clojure-native workflow definitions for the devflow lifecycle.

  These helpers encode the agent-facing devflow checkpoints as Skein workflow
  data. They intentionally produce ordinary workflow definitions that callers
  can inspect, compose, pour as molecules, or materialize as wisps."
  (:require [skein.graph.alpha :as graph]
            [skein.repl :as repl]
            [skein.spools.workflow :as workflow]))

(defn- titled
  ([prefix]
   (titled prefix ""))
  ([prefix suffix]
   (fn [{:keys [feature]}]
     (str prefix feature suffix))))

(defn- param-value [k]
  (fn [params]
    (get params k)))

(defn intake-workflow
  "Return the mandatory brief intake workflow.

  The first strand is a HITL decision/checkpoint that requires worktree creation
  before substantive discovery. `:worktree-check` may be `:required` for a fresh
  brief or `:already-in-worktree-ok` for agents launched directly inside the
  feature worktree. On a revision round (`:revision true`), the worktree
  checkpoint is skipped because it was already satisfied on the first pass;
  F4's splice reattaches `:capture-brief` as the entry step."
  [{:keys [feature worktree-check revision]
    :or {worktree-check :required}}]
  (workflow/workflow
    (titled "Devflow intake: ")
    {:params {:feature (workflow/param :required true)
              :worktree-check (workflow/param :default (name worktree-check))
              :revision (workflow/param :default (boolean revision))}
     :attributes {"devflow/stage" "intake"
                  "devflow/feature" (param-value :feature)
                  "devflow/worktree-check" (param-value :worktree-check)}}
    (workflow/checkpoint :create-or-confirm-worktree
                         (titled "[HITL] Create or confirm feature worktree for ")
                         :kind :human
                         :condition [:!= :revision true]
                         :choices [{:key :created-worktree
                                    :label "Created worktree"
                                    :description "A new feature worktree was created; continue intake there."}
                                   {:key :already-in-worktree
                                    :label "Already in worktree"
                                    :description "This agent is already running in the correct feature worktree; continue intake."}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop the feature before any substantive work begins."
                                    :next 'skein.spools.devflow/abort-workflow}]
                         :attributes {"workflow/hitl" "true"
                                      "workflow/decision-point" "worktree-ready"
                                      "workflow/action-ref" "devflow.worktree.ensure"
                                      "workflow/instruction" "Create a new feature worktree before doing discovery or code work. If this agent is already running inside the correct feature worktree, choose already-in-worktree."})
    (workflow/step :capture-brief
                   (titled "Capture user brief for ")
                   :depends-on [:create-or-confirm-worktree]
                   :attributes {"devflow/artifact" "brief"})
    (workflow/checkpoint :discuss-scope
                         (titled "Discuss scope and open questions for ")
                         :depends-on [:capture-brief]
                         :kind :agent
                         :choices [{:key :proposal-ready
                                    :label "Proposal ready"
                                    :description "Scope is clear enough; create the proposal workflow next."
                                    :next 'skein.spools.devflow/enter-proposal-workflow}
                                   {:key :needs-more-brief
                                    :label "Needs more brief"
                                    :description "Scope is incomplete; route back through intake to gather more brief before proposing."
                                    :next 'skein.spools.devflow/intake-revision-workflow}]
                         :attributes {"workflow/decision-point" "scope-ready"})))

(defn intake-revision-workflow
  "Return the intake workflow for a revision round.

  Routed to from `:discuss-scope`'s `:needs-more-brief` choice; re-runs intake
  with `:revision true` so the already-satisfied worktree checkpoint is skipped
  and discovery resumes at `:capture-brief`. Returns `{:workflow w :params p}`
  so the revision params (including the carried-forward start opts) are
  authoritative over the new round's params and persisted context."
  [opts]
  (let [params (assoc opts :revision true)]
    {:workflow (intake-workflow params) :params params}))

(defn agent-review-workflow
  "Return a reusable one-step agent review procedure."
  [_opts]
  (workflow/workflow
    (fn [{:keys [feature artifact]}]
      (str "Agent review: " feature " " artifact))
    {:params {:feature (workflow/param :required true)
              :artifact (workflow/param :required true)}}
    (workflow/step :review
                   (fn [{:keys [feature artifact]}]
                     (str "Run agent review for " feature " " artifact))
                   :attributes {"review" "agent"})))

(defn proposal-workflow
  "Return the proposal gate workflow.

  This encodes: inspect RFCs/spikes/specs first, write proposal, run agent
  review, then stop for human sign-off. On a revision round (`:revision true`),
  `:inspect-context` is skipped because orientation was done on the first pass;
  F4's splice reattaches `:write-proposal` as the entry step."
  [{:keys [revision] :as _opts}]
  (workflow/workflow
    (titled "Devflow proposal: ")
    {:params {:feature (workflow/param :required true)
              :revision (workflow/param :default (boolean revision))}
     :attributes {"devflow/stage" "proposal"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :inspect-context
                   (titled "Inspect relevant RFCs, spikes, root specs, and active feature context for ")
                   :condition [:!= :revision true]
                   :attributes {"workflow/action-ref" "devflow.proposal.orient"
                                "workflow/instruction" "Inspect relevant active RFCs, spikes, root specs, active feature folders, and affected code before writing the proposal."})
    (workflow/step :write-proposal
                   (titled "Write devflow proposal for ")
                   :depends-on [:inspect-context]
                   :attributes {"devflow/artifact" "proposal.md"
                                "skills" "devflow"})
    (workflow/call :agent-review-proposal
                   agent-review-workflow
                   {:artifact "proposal"}
                   :title (titled "Complete agent review for " " proposal")
                   :depends-on [:write-proposal])
    (workflow/checkpoint :human-signoff-proposal
                         (titled "[HITL] Human sign-off for " " proposal")
                         :depends-on [:agent-review-proposal]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Proposal is accepted; continue to spec and plan work."
                                    :next 'skein.spools.devflow/enter-spec-plan-workflow}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Proposal needs changes; route back through the proposal stage to revise and re-review before proceeding."
                                    :next 'skein.spools.devflow/proposal-revision-workflow}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature intentionally. Do not proceed to spec or plan work."
                                    :next 'skein.spools.devflow/abort-workflow}]
                         :attributes {"workflow/hitl" "true"
                                      "workflow/decision-point" "proposal-signed-off"})))

(defn proposal-revision-workflow
  "Return the proposal workflow for a revision round.

  Routed to from `:human-signoff-proposal`'s `:revise` choice; re-runs the
  proposal stage with `:revision true` so `:inspect-context` is skipped and
  work resumes at `:write-proposal`. Returns `{:workflow w :params p}` so the
  revision params are authoritative over the new round's params and persisted
  context, regardless of the choice input."
  [opts]
  (let [params (assoc opts :revision true)]
    {:workflow (proposal-workflow params) :params params}))

(defn route-after-plan-workflow
  "Return the post-plan route-choice workflow."
  [_opts]
  (workflow/workflow
    (titled "Devflow route after plan: ")
    {:params {:feature (workflow/param :required true)}
     :attributes {"devflow/stage" "route-after-plan"
                  "devflow/feature" (param-value :feature)}}
    (workflow/checkpoint :route-after-plan
                         (titled "Recommend next workflow: tasks or direct implementation for ")
                         :kind :agent
                         :choices [{:key :task-breakdown
                                    :label "Task breakdown"
                                    :description "Create an AFK/HITL task queue before implementation."
                                    :next 'skein.spools.devflow/enter-task-breakdown-workflow}
                                   {:key :direct-implementation
                                    :label "Direct implementation"
                                    :description "Proceed directly to implementation because the reviewed plan is small and settled."
                                    :next 'skein.spools.devflow/enter-direct-implementation-workflow}]
                         :attributes {"workflow/decision-point" "choose-tasks-or-implementation"})))

(defn spec-plan-workflow
  "Return the spec-delta and plan gate workflow.

  After review and human sign-off, approval routes to the task/direct
  implementation decision workflow. A revision round (`:revision true`) re-runs
  the whole spec/plan stage."
  [{:keys [revision] :as _opts}]
  (workflow/workflow
    (titled "Devflow spec and plan: ")
    {:params {:feature (workflow/param :required true)
              :revision (workflow/param :default (boolean revision))}
     :attributes {"devflow/stage" "spec-plan"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :write-spec-deltas
                   (titled "Write needed spec deltas for ")
                   :attributes {"devflow/artifact" "specs/*.delta.md"
                                "skills" "devflow"})
    (workflow/step :write-plan
                   (titled "Write implementation plan for ")
                   :depends-on [:write-spec-deltas]
                   :attributes {"devflow/artifact" "<feature>.plan.md"
                                "skills" "devflow"})
    (workflow/call :agent-review-spec-plan
                   agent-review-workflow
                   {:artifact "spec deltas and plan"}
                   :title (titled "Complete agent review for " " spec deltas and plan")
                   :depends-on [:write-plan])
    (workflow/checkpoint :human-signoff-spec-plan
                         (titled "[HITL] Human sign-off for " " spec deltas and plan")
                         :depends-on [:agent-review-spec-plan]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Spec deltas and plan are accepted; choose tasks or direct implementation next."
                                    :next 'skein.spools.devflow/enter-route-after-plan-workflow}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Spec deltas or plan need changes; route back through the spec/plan stage to revise and re-review before proceeding."
                                    :next 'skein.spools.devflow/spec-plan-revision-workflow}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature intentionally before implementation."
                                    :next 'skein.spools.devflow/abort-workflow}]
                         :attributes {"workflow/hitl" "true"
                                      "workflow/decision-point" "plan-signed-off"})))

(defn spec-plan-revision-workflow
  "Return the spec/plan workflow for a revision round.

  Routed to from `:human-signoff-spec-plan`'s `:revise` choice; re-runs the
  spec/plan stage with `:revision true`. Returns `{:workflow w :params p}` so
  the revision params are authoritative over the new round's params and persisted
  context, regardless of the choice input."
  [opts]
  (let [params (assoc opts :revision true)]
    {:workflow (spec-plan-workflow params) :params params}))

(defn run-afk-loop-workflow
  "Return the post-task-signoff AFK loop workflow."
  [_opts]
  (workflow/workflow
    (titled "Devflow AFK execution: ")
    {:params {:feature (workflow/param :required true)}
     :attributes {"devflow/stage" "afk"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :run-afk-loop
                   (titled "Run or hand off AFK task loop for ")
                   :attributes {"workflow/action-ref" "devflow.tasks.run-afk-loop"
                                "workflow/instruction" "Run or hand off the devflow AFK task loop for this feature after task sign-off."})))

(defn task-breakdown-workflow
  "Return the reviewed task queue workflow.

  A revision round (`:revision true`) re-runs the whole task-breakdown stage."
  [{:keys [revision] :as _opts}]
  (workflow/workflow
    (titled "Devflow task breakdown: ")
    {:params {:feature (workflow/param :required true)
              :revision (workflow/param :default (boolean revision))}
     :attributes {"devflow/stage" "tasks"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :write-tasks
                   (titled "Write AFK/HITL task queue for ")
                   :attributes {"devflow/artifact" "tasks/index.yml"
                                "skills" "devflow"})
    (workflow/call :agent-review-tasks
                   agent-review-workflow
                   {:artifact "task queue"}
                   :title (titled "Complete agent review for " " task queue")
                   :depends-on [:write-tasks])
    (workflow/checkpoint :human-signoff-tasks
                         (titled "[HITL] Human sign-off for " " task queue")
                         :depends-on [:agent-review-tasks]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Task queue is accepted; run or hand off the AFK loop next."
                                    :next 'skein.spools.devflow/enter-run-afk-loop-workflow}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Task queue needs changes; route back through the task-breakdown stage to revise and re-review before execution."
                                    :next 'skein.spools.devflow/task-breakdown-revision-workflow}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature before task execution."
                                    :next 'skein.spools.devflow/abort-workflow}]
                         :attributes {"workflow/hitl" "true"
                                      "workflow/decision-point" "tasks-signed-off"})))

(defn task-breakdown-revision-workflow
  "Return the task-breakdown workflow for a revision round.

  Routed to from `:human-signoff-tasks`'s `:revise` choice; re-runs the
  task-breakdown stage with `:revision true`. Returns `{:workflow w :params p}`
  so the revision params are authoritative over the new round's params and
  persisted context, regardless of the choice input."
  [opts]
  (let [params (assoc opts :revision true)]
    {:workflow (task-breakdown-workflow params) :params params}))

(defn direct-implementation-workflow
  "Return the post-plan direct implementation workflow for small, settled changes.

  A revision round (`:revision true`) re-runs the whole implementation stage."
  [{:keys [revision] :as _opts}]
  (workflow/workflow
    (titled "Devflow direct implementation: ")
    {:params {:feature (workflow/param :required true)
              :revision (workflow/param :default (boolean revision))}
     :attributes {"devflow/stage" "implementation"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :implement
                   (titled "Implement reviewed plan for ")
                   :attributes {"workflow/action-ref" "devflow.implementation.direct"
                                "workflow/instruction" "Implement the reviewed plan directly because the signed-off scope does not need a separate task breakdown."})
    (workflow/step :validate
                   (titled "Validate implementation for ")
                   :depends-on [:implement]
                   :attributes {"workflow/action-ref" "devflow.implementation.validate"
                                "workflow/instruction" "Run validation relevant to the touched implementation and report failures before review."})
    (workflow/call :review-implementation
                   agent-review-workflow
                   {:artifact "implementation"}
                   :title (titled "Complete implementation review for ")
                   :depends-on [:validate])
    (workflow/checkpoint :human-acceptance
                         (titled "[HITL] Human acceptance for " " implementation")
                         :depends-on [:review-implementation]
                         :kind :human
                         :choices [{:key :accepted
                                    :label "Accept"
                                    :description "Implementation is accepted; continue to finish/archive work."}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Implementation needs changes; route back through the implementation stage to revise and re-review before acceptance."
                                    :next 'skein.spools.devflow/direct-implementation-revision-workflow}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop or abandon this feature after implementation review."
                                    :next 'skein.spools.devflow/abort-workflow}]
                         :attributes {"workflow/hitl" "true"
                                      "workflow/decision-point" "implementation-accepted"})))

(defn direct-implementation-revision-workflow
  "Return the direct implementation workflow for a revision round.

  Routed to from `:human-acceptance`'s `:revise` choice; re-runs the
  implementation stage with `:revision true`. Returns `{:workflow w :params p}`
  so the revision params are authoritative over the new round's params and
  persisted context, regardless of the choice input."
  [opts]
  (let [params (assoc opts :revision true)]
    {:workflow (direct-implementation-workflow params) :params params}))

(defn- fresh-stage-entry
  "Return continuation params/workflow for entering `constructor` as a new stage.

  Loop-control state like `:revision` is stage-local; without resetting it, a
  round approved after a revise would carry `:revision true` in context into
  every downstream stage."
  [constructor params]
  (let [params (dissoc params :revision)]
    {:workflow (constructor params) :params params}))

(defn enter-proposal-workflow
  "Route into the proposal stage with stage-local loop state reset."
  [params]
  (fresh-stage-entry proposal-workflow params))

(defn enter-spec-plan-workflow
  "Route into the spec-plan stage with stage-local loop state reset."
  [params]
  (fresh-stage-entry spec-plan-workflow params))

(defn enter-route-after-plan-workflow
  "Route into the post-plan route choice with stage-local loop state reset."
  [params]
  (fresh-stage-entry route-after-plan-workflow params))

(defn enter-task-breakdown-workflow
  "Route into the task breakdown stage with stage-local loop state reset."
  [params]
  (fresh-stage-entry task-breakdown-workflow params))

(defn enter-run-afk-loop-workflow
  "Route into the AFK execution stage with stage-local loop state reset."
  [params]
  (fresh-stage-entry run-afk-loop-workflow params))

(defn enter-direct-implementation-workflow
  "Route into the direct implementation stage with stage-local loop state reset."
  [params]
  (fresh-stage-entry direct-implementation-workflow params))

(defn abort-workflow
  "Return a tiny workflow that records intentional feature abortion."
  [_opts]
  (workflow/workflow
    (titled "Abort devflow feature: ")
    {:params {:feature (workflow/param :required true)
              :reason (workflow/param :required true)}
     :attributes {"devflow/stage" "abort"
                  "devflow/feature" (param-value :feature)}}
    (workflow/step :record-abort
                   (fn [{:keys [feature reason]}]
                     (str "Record abort for " feature ": " reason))
                   :attributes {"workflow/action-ref" "devflow.abort.record"
                                "workflow/instruction" "Record the abort reason in the feature plan or conversation summary, then stop the active workflow."})))

(defn devflow-cycle
  "Return the ordered composable devflow workflow definitions for `opts`.

  Callers can pour the first workflow, then use decision-point strand outcomes to
  choose and pour the next workflow."
  [opts]
  [(intake-workflow opts)
   (proposal-workflow opts)
   (spec-plan-workflow opts)
   (route-after-plan-workflow opts)
   (task-breakdown-workflow opts)
   (run-afk-loop-workflow opts)
   (direct-implementation-workflow opts)])

(defn start!
  "Start the devflow intake workflow for `feature` and return the initial
  agent-facing ready step views (a vector, possibly with more than one entry)."
  ([feature]
   (start! feature {}))
  ([feature opts]
   ;; keyword opt values (e.g. :worktree-check :required) are coerced to strings
   ;; so they survive JSON round-tripping in workflow/context; stage
   ;; constructors read them back through `name`, which accepts strings
   ;; unchanged
   (let [context (reduce-kv (fn [m k v] (assoc m k (if (keyword? v) (name v) v)))
                            {:feature feature}
                            opts)]
     (workflow/start!
      feature
      (intake-workflow context)
      {:feature feature}
      {:family "devflow"
       :definition 'skein.spools.devflow/intake-workflow
       ;; seed start opts into context so they survive intake revision loops
       ;; rather than resetting to their defaults
       :context context}))))

(defn feature-roots
  "Return active devflow workflow roots for `feature`."
  [feature]
  (let [root (workflow/current-root feature)]
    (if root [root] [])))

(defn next-steps
  "Return agent-facing ready devflow steps for `feature`."
  [feature]
  (workflow/next-steps feature))

(defn next-step
  "Return the single agent-facing ready devflow step for `feature`, or fail if ambiguous."
  [feature]
  (workflow/next-step feature))

(defn choice-details
  "Return choice explanations for the current devflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints."
  ([feature]
   (choice-details feature {}))
  ([feature opts]
   (workflow/choice-details feature opts)))

(defn choice-detail
  "Return one choice explanation for the current devflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints."
  ([feature choice]
   (choice-detail feature choice {}))
  ([feature choice opts]
   (workflow/choice-detail feature choice opts)))

(defn complete!
  "Close the current devflow step for `feature` and return the next
  agent-facing ready step views.

  opts may include `:step`, `:notes`, and `:attributes`; see
  `skein.spools.workflow/complete!`."
  ([feature]
   (complete! feature {}))
  ([feature opts]
   (workflow/complete! feature opts)))

(defn choose!
  "Record a devflow checkpoint choice and return the next agent-facing ready
  step views.

  opts may include `:step`; see `skein.spools.workflow/choose!`."
  ([feature choice]
   (workflow/choose! feature choice))
  ([feature choice input]
   (workflow/choose! feature choice input))
  ([feature choice input opts]
   (workflow/choose! feature choice input opts)))

(def workflow-registry
  "Workflow constructors exposed by the devflow spool."
  {:intake 'skein.spools.devflow/intake-workflow
   :intake-revision 'skein.spools.devflow/intake-revision-workflow
   :proposal 'skein.spools.devflow/proposal-workflow
   :proposal-revision 'skein.spools.devflow/proposal-revision-workflow
   :spec-plan 'skein.spools.devflow/spec-plan-workflow
   :spec-plan-revision 'skein.spools.devflow/spec-plan-revision-workflow
   :route-after-plan 'skein.spools.devflow/route-after-plan-workflow
   :tasks 'skein.spools.devflow/task-breakdown-workflow
   :tasks-revision 'skein.spools.devflow/task-breakdown-revision-workflow
   :run-afk-loop 'skein.spools.devflow/run-afk-loop-workflow
   :direct-implementation 'skein.spools.devflow/direct-implementation-workflow
   :direct-implementation-revision 'skein.spools.devflow/direct-implementation-revision-workflow
   :agent-review 'skein.spools.devflow/agent-review-workflow
   :cycle 'skein.spools.devflow/devflow-cycle})

(def command-registry
  "Agent-facing commands exposed by the devflow spool."
  {:start 'skein.spools.devflow/start!
   :next-step 'skein.spools.devflow/next-step
   :next-steps 'skein.spools.devflow/next-steps
   :choice-details 'skein.spools.devflow/choice-details
   :choice-detail 'skein.spools.devflow/choice-detail
   :choose 'skein.spools.devflow/choose!
   :complete 'skein.spools.devflow/complete!})

(defn workflows
  "Return devflow workflow constructors by stable key."
  []
  workflow-registry)

(defn commands
  "Return agent-facing devflow commands by stable key."
  []
  command-registry)

(defn install!
  "Return installation metadata for the devflow workflow spool."
  []
  {:installed true
   :namespace 'skein.spools.devflow
   :commands command-registry
   :workflows workflow-registry})
