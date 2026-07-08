(ns workflows
  "This repo's hand-authored coordination workflows and their command surface:
  the coordinator `land` workflow (family \"land\") with its `land` op, and the
  `delegate-pipeline` weave pattern for sequential delegated subagent gates.

  The devflow lifecycle itself is the external `skein.spools.devflow` spool;
  its thin CLI wrapper ops live in config.clj. This file is loaded after
  config.clj and reuses its public CLI-tail helpers (`config/pop-step-selector`
  and friends) so the `step=<id>` tail convention has one definition."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as api]
            [skein.spools.agents :as agents]
            [skein.spools.loom :as loom]
            [skein.spools.workflow :as workflow]))

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

;; ---------------------------------------------------------------------------
;; delegate-pipeline weave pattern
;; ---------------------------------------------------------------------------

(s/def ::non-blank-string non-blank-string?)
(s/def ::title ::non-blank-string)
(s/def ::body ::non-blank-string)
(s/def ::harness ::non-blank-string)
(s/def ::cwd ::non-blank-string)
(s/def ::max-attempts pos-int?)

(s/def ::id ::non-blank-string)
(s/def ::run_id ::non-blank-string)
(s/def ::accept boolean?)
(s/def ::pipeline-task (s/keys :req-un [::id ::title]
                               :opt-un [::body ::harness ::cwd ::max-attempts]))
(s/def ::pipeline-tasks (s/coll-of ::pipeline-task :kind vector? :min-count 1))
(s/def ::delegate-pipeline-input
  (s/and map?
         #(s/valid? ::run_id (:run_id %))
         #(s/valid? ::pipeline-tasks (:tasks %))
         #(or (not (contains? % :harness)) (s/valid? ::harness (:harness %)))
         #(or (not (contains? % :cwd)) (s/valid? ::cwd (:cwd %)))
         #(or (not (contains? % :accept)) (s/valid? ::accept (:accept %)))))

(defn- task-value
  "Return task field `k`, accepting keyword or string keyed task maps."
  [task k]
  (or (get task k) (get task (name k))))

(defn- pipeline-task-prompt
  "Return the prompt for one delegate-pipeline task."
  [run-id item]
  (str agents/worker-contract "\n\n"
       "Delegated pipeline run: " run-id "\n"
       "Task: " (task-value item :title) "\n\n"
       (or (task-value item :body) (task-value item :title))))

(defn- compiled-workflow-strands
  "Return workflow compile output as a weave-compatible strand vector."
  [{:keys [strands edges]}]
  (let [ref-symbol #(if (keyword? %) (symbol (name %)) %)
        edges-by-from (group-by :from edges)]
    (mapv (fn [{:keys [ref] :as strand}]
            (let [edge-specs (mapv (fn [edge]
                                     (merge {:type (:type edge) :to (ref-symbol (:to edge))}
                                            (select-keys edge [:attributes])))
                                   (get edges-by-from ref))]
              (cond-> (-> strand
                          (update :ref ref-symbol)
                          (update :attributes #(into {} (remove (comp nil? val)) %)))
                (seq edge-specs) (assoc :edges edge-specs))))
          strands)))

(defn delegate-pipeline
  "Create a chain-loop workflow for sequential delegated pipeline gates."
  [{:keys [input]}]
  (let [{:keys [run_id tasks harness cwd accept]} input
        task-gate (workflow/gate
                   :task
                   (fn [{:keys [item]}]
                     (str "Delegate pipeline task " (task-value item :id)))
                   :subagent
                   :loop {:each :tasks :chain true}
                   :attributes {"shuttle/harness" (fn [{:keys [item harness]}]
                                                    (or (task-value item :harness) harness))
                                "shuttle/prompt" (fn [{:keys [run-id item]}]
                                                   (pipeline-task-prompt run-id item))
                                "shuttle/cwd" (fn [{:keys [item cwd]}]
                                                (or (task-value item :cwd) cwd))
                                "shuttle/max-attempts" (fn [{:keys [item]}]
                                                         (task-value item :max-attempts))
                                "delegate-pipeline/task" (fn [{:keys [item]}]
                                                           (task-value item :id))})
        accept-checkpoint (workflow/checkpoint
                           :accept
                           "Accept delegated pipeline"
                           :depends-on [:task]
                           :kind :human
                           :choices [{:key :accepted
                                      :label "Accept"
                                      :description "Delegated pipeline output is accepted."}])]
    (doseq [task tasks]
      (when-not (non-blank-string? (or (task-value task :harness) harness))
        (throw (ex-info "delegate-pipeline task missing harness resolution"
                        {:task task :harness harness}))))
    (compiled-workflow-strands
     (workflow/compile
      (apply workflow/workflow
             (str "Delegated pipeline: " run_id)
             {:params {:run-id (workflow/param :default run_id)
                       :tasks (workflow/param :default tasks)
                       :harness (workflow/param :default harness)
                       :cwd (workflow/param :default cwd)}
              :attributes {"workflow/family" "delegate-pipeline"}}
             (cond-> [task-gate]
               accept (conj accept-checkpoint)))
      {:run-id run_id :tasks tasks :harness harness :cwd cwd}
      {:run-id run_id :family "delegate-pipeline"}))))

;; ---------------------------------------------------------------------------
;; land: the coordinator LANDING workflow (family "land")
;;
;; The encoded discipline a coordinator drives before a branch is considered
;; landed. COORDINATOR-ONLY: worker agents never land — they stop at
;; implemented+committed. The ordering is the enforcement: sign-off is only
;; valid on a pushed branch with a draft PR and green CI, and a merge to main
;; requires green CI plus a green local smoke run. Step `workflow/instruction`
;; text is the enforcement surface, shipped as data on each step.
;; ---------------------------------------------------------------------------

(def ^:private land-abort-reason-input
  "Declared choice input for the land sign-off abort choice: a required
  `:reason` recorded on the abort step (workflow.md §5). `choose!` fails loudly
  before any mutation when it is omitted."
  [{:key :reason :required true
    :description "Why landing is being aborted; recorded on the abort step."}])

(defn land-abort-workflow
  "Return the continuation that records an intentional abort of a land run.

  Routed to by the sign-off checkpoint's `abort` choice: a hard cutover that
  force-closes the remaining land steps and pours this single record step.
  Nothing merges or pushes; the branch and worktree stay for follow-up."
  [_opts]
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Abort land: " branch))
   {:params {:branch (workflow/param :required true)
             :reason (workflow/param :required true)}
    :attributes {"workflow/family" "land"
                 "land/stage" "abort"}}
   (workflow/step :record-abort
                  (fn [{:keys [branch reason]}] (str "Record land abort for " branch ": " reason))
                  :self
                  :attributes {"workflow/action-ref" "land.abort.record"
                               "workflow/instruction" "Record the abort reason on the kanban card and work root, leave a handover note, then stop. Do NOT merge or push — nothing has landed; the branch and worktree stay for follow-up."})))

(defn land-workflow
  "Return the coordinator LANDING workflow for a feature branch (family \"land\").

  COORDINATOR-ONLY: worker agents never land. Sequential single molecule, one
  linear DAG plus an abort cutover: push + draft PR, green CI at HEAD,
  roster sign-off (only valid on a pushed branch with green CI), a coordinator
  sign-off checkpoint, squash-merge to LOCAL main with the full local
  verification gate, green main CI, then cleanup. `params` carry `:feature`,
  `:branch`, `:worktree`, and optional `:card`; step `workflow/instruction`
  text is command-precise and fail-loud, so the discipline lives in the data."
  [_opts]
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Land: " branch))
   {:params {:feature (workflow/param :required true)
             :branch (workflow/param :required true)
             :worktree (workflow/param :required true)
             :card (workflow/param :default nil)}
    :attributes {"workflow/family" "land"
                 "land/branch" (fn [{:keys [branch]}] branch)}}
   (workflow/step :push-draft-pr
                  (fn [{:keys [branch]}] (str "Push " branch " and open a draft PR"))
                  :self
                  :attributes {"workflow/action-ref" "land.pr.open"
                               "workflow/instruction"
                               (fn [{:keys [branch]}]
                                 (str "Push the branch to origin: `git push -u origin " branch "`."
                                      " Open a draft PR against main: `gh pr create --draft --title <semantic subject> --body <summary>`."
                                      " If an open PR for " branch " already exists, reuse it instead"
                                      " (`gh pr view " branch " --json url,number,state`). Record the PR url"
                                      " and number in this step's notes before completing."))})
   (workflow/step :ci-green
                  (fn [{:keys [branch]}] (str "Watch CI to green at " branch " HEAD"))
                  :self
                  :depends-on [:push-draft-pr]
                  :attributes {"workflow/action-ref" "land.ci.green"
                               "workflow/instruction"
                               "Watch CI to green at the current branch HEAD: `gh pr checks <pr> --watch`. ALL checks must pass at HEAD. If any check is red, fix it in the worktree, commit, `git push`, and re-watch — stay in THIS step until every check is green. Completing this step asserts green CI at the current HEAD sha; record the HEAD sha (`git rev-parse HEAD`) and the check evidence in notes."})
   (workflow/step :signoff-review
                  (fn [{:keys [branch]}] (str "Run roster sign-off review for " branch))
                  :self
                  :depends-on [:ci-green]
                  :attributes {"workflow/action-ref" "land.signoff.review"
                               "workflow/instruction"
                               (fn [{:keys [worktree]}]
                                 (str "Run the declared roster review:"
                                      " `strand agent review <work-root> --roster change-review --cwd " worktree
                                      " --commit-range origin/main..HEAD`. Drive every fix round to done; each fix"
                                      " round re-pushes the branch and MUST re-establish green CI (the ci-green bar)"
                                      " before this step may complete. SIGN-OFF IS ONLY VALID WITH A PUSHED BRANCH"
                                      " AND GREEN CI — that is why this step follows CI. Record the review pass ids"
                                      " and the final verdict in notes."))})
   (workflow/checkpoint :signoff
                        (fn [{:keys [branch]}] (str "Sign off landing " branch))
                        :depends-on [:signoff-review]
                        :kind :agent
                        :choices [{:key :approved
                                   :label "Approve"
                                   :description "Sign-off approved on a pushed branch with green CI; continue to the local squash-merge and verification. The coordinator holds this delegated sign-off authority."}
                                  {:key :abort
                                   :label "Abort"
                                   :description "Stop landing intentionally; nothing merges. Records the reason and leaves the branch/worktree for follow-up."
                                   :next :land-abort
                                   :input land-abort-reason-input}]
                        :attributes {"workflow/decision-point" "land-signed-off"})
   (workflow/step :merge-local-verify
                  (fn [{:keys [branch]}] (str "Squash-merge " branch " to local main and verify"))
                  :self
                  :depends-on [:signoff]
                  :attributes {"workflow/action-ref" "land.merge.local-verify"
                               "workflow/instruction"
                               (fn [{:keys [branch]}]
                                 (str "Squash-merge " branch " into LOCAL main without pushing (coding:git-merge"
                                      " semantics: a semantic squash subject plus a `Squashed commits` body)."
                                      " If spool docstrings changed, regenerate `make api-docs` into the squash."
                                      " Then, on the merged local main, run the full local verification gate:"
                                      " `PATH=\"/opt/homebrew/opt/openjdk/bin:$PATH\" flock -w 3600 /tmp/skein-test.lock clojure -M:test`,"
                                      " `(cd cli && go test ./...)`, `make fmt-check lint reflect-check docs-check`,"
                                      " and the smoke suite `clojure -M:smoke`. If any gate fails:"
                                      " `git reset --hard origin/main`, fix on the branch, and re-satisfy the"
                                      " ci-green and signoff-review steps before re-attempting. Record every gate"
                                      " result in notes. Do NOT push in this step."))})
   (workflow/step :push-main-ci-green
                  "Push main and watch main CI to green"
                  :self
                  :depends-on [:merge-local-verify]
                  :attributes {"workflow/action-ref" "land.main.ci-green"
                               "workflow/instruction"
                               "Push main: `git push origin main`. Watch ALL main workflows to completion (`gh run list --branch main`, `gh run watch <run-id>`). Transient infra failures may be re-run with `gh run rerun <run-id>`. Completing this step asserts green CI on the main sha. Record the run ids in notes."})
   (workflow/step :cleanup
                  (fn [{:keys [branch]}] (str "Clean up " branch " and close the land run"))
                  :self
                  :depends-on [:push-main-ci-green]
                  :attributes {"workflow/action-ref" "land.cleanup"
                               "workflow/instruction"
                               (fn [{:keys [branch card]}]
                                 (str "Delete the remote branch (`git push origin --delete " branch "`), which also"
                                      " closes the draft PR. Remove the worktree and local branch"
                                      " (`wktree remove --branch " branch " --force`; force is expected after the"
                                      " squash-merge)."
                                      (if (non-blank-string? card)
                                        (str " Finish the kanban card (`strand kanban finish " card " --outcome done`).")
                                        "")
                                      " Then close this land run's root to complete it."))})))

(defn- register-land-workflows!
  "Register the land run's routing targets (the abort continuation) with the
  engine's weaver-lifetime workflow registry, so the sign-off `abort` choice's
  `:next :land-abort` resolves at `choose!` time (re-registered on reload like
  named queries and ops)."
  []
  {:land-abort (workflow/register-workflow! :land-abort 'workflows/land-abort-workflow)})

(defn- land-start!
  "Pour and start the land run for a feature branch; run-id is the feature slug."
  [feature {:keys [branch worktree card] :as opts}]
  (config/require-non-blank! :feature feature)
  (config/require-non-blank! :branch branch)
  (config/require-non-blank! :worktree worktree)
  (when (and (contains? opts :card) (not (non-blank-string? card)))
    (throw (ex-info "card must be a non-blank string when provided"
                    {:argument :card :value card})))
  (let [context (cond-> {:feature feature :branch branch :worktree worktree}
                  (non-blank-string? card) (assoc :card card))]
    (workflow/start! feature
                     (land-workflow context)
                     context
                     {:family "land"
                      :definition 'workflows/land-workflow
                      :context context})))

(defn land-about
  "Return the coordinator landing discipline manual."
  []
  {:operation "land about"
   :summary "Coordinator-only landing workflow: the encoded discipline a coordinator drives before a branch is considered landed."
   :coordinator-only "Worker agents never land — they stop at implemented+committed. Only a coordinator, holding delegated sign-off authority, drives a land run."
   :discipline "Sign-off is only valid on a pushed branch with an open draft PR and green CI at HEAD — that is the point of the ordering. A merge is a squash into LOCAL main that must pass the full local verification gate (tests + go tests + fmt/lint/reflect/docs + smoke) before main is pushed; main is only landed once its own CI is green. Aborting at sign-off records a reason and leaves the branch/worktree untouched."
   :steps [{:step "push-draft-pr" :purpose "Push the branch and open (or reuse) a draft PR against main."}
           {:step "ci-green" :purpose "Watch CI to green at the branch HEAD; fix-push-repeat within the step."}
           {:step "signoff-review" :purpose "Run the declared roster review and drive fix rounds; every fix round re-establishes green CI."}
           {:step "signoff" :purpose "Coordinator sign-off checkpoint (:agent): approved continues in the molecule, abort routes to a reason-recording step."}
           {:step "merge-local-verify" :purpose "Squash-merge into local main without pushing, then run the full local verification gate + smoke."}
           {:step "push-main-ci-green" :purpose "Push main and watch all main workflows to green."}
           {:step "cleanup" :purpose "Delete the remote branch/PR, remove the worktree+branch, finish the card, and close the run."}]
   :commands [{:verb "start" :purpose "Pour and start the land run: land start <feature> --branch <b> --worktree <path> [--card <id>]."}
              {:verb "next" :purpose "Show the ready land step views for a feature."}
              {:verb "complete" :purpose "Close the current non-checkpoint land step, optionally with notes and a step=<id> selector."}
              {:verb "choose" :purpose "Decide the sign-off checkpoint: approved, or abort with {\"reason\":\"...\"}."}
              {:verb "status" :purpose "Show the land root, ready steps, done state, and run history for a feature."}]
   :discovery {:help "strand help land"
               :conventions "strand devflow-conventions"}})

(defn land-op
  "Dispatch parsed `strand land ...` subcommands over the land workflow."
  [ctx]
  (let [{:keys [subcommand feature choice tail] :as args} (:op/args ctx)]
    (case subcommand
      "about" (land-about)
      "start" (merge {:operation "land-start" :feature feature}
                     (land-start! feature (select-keys args [:branch :worktree :card])))
      "next" (do (config/require-non-blank! :feature feature)
                 {:operation "land-next"
                  :feature feature
                  :ready (workflow/next-steps feature)})
      "complete" (let [[rest-tokens step] (config/pop-step-selector "land complete" tail)
                       notes (first rest-tokens)]
                   (config/require-non-blank! :feature feature)
                   (when (> (count rest-tokens) 1)
                     (throw (ex-info "land complete accepts at most one notes argument"
                                     {:op "land complete" :help "strand help land" :extra (vec (rest rest-tokens))})))
                   (merge {:operation "land-complete" :feature feature}
                          (workflow/complete! feature (cond-> {}
                                                        notes (assoc :notes notes)
                                                        step (assoc :step step)))))
      "choose" (let [[rest-tokens step] (config/pop-step-selector "land choose" tail)
                     raw-input (first rest-tokens)]
                 (config/require-non-blank! :feature feature)
                 (when (> (count rest-tokens) 1)
                   (throw (ex-info "land choose accepts at most one JSON-input argument"
                                   {:op "land choose" :help "strand help land" :extra (vec (rest rest-tokens))})))
                 (let [input (if raw-input (config/parse-json-object-arg "land choose" raw-input) {})]
                   (merge {:operation "land-choose" :feature feature :choice choice}
                          (workflow/choose! feature (keyword choice) input (if step {:step step} {})))))
      "status" (do (config/require-non-blank! :feature feature)
                   (let [root (workflow/current-root feature)]
                     {:operation "land-status"
                      :feature feature
                      :roots (mapv loom/summarize (if root [root] []))
                      :done (workflow/done? feature)
                      :ready (workflow/next-steps feature)
                      :history (workflow/run-history feature)})))))

(def ^:private land-arg-spec
  "Declared command surface for the `land` op (one level of subcommands; the
  handler dispatches on the routed `:subcommand`, never a hand-written usage)."
  {:op "land"
   :doc "Drive the coordinator landing workflow for a feature branch. Run `strand land about` for the discipline manual."
   :subcommands
   {"about" {:doc "Return the landing discipline manual: purpose, step map, and coordinator-only note."}
    "start" {:doc "Pour and start the land run for a feature branch."
             :flags {:branch {:required? true
                              :doc "Feature branch to land."}
                     :worktree {:required? true
                                :doc "Worktree path for the branch."}
                     :card {:doc "Optional kanban card id to finish at cleanup."}}
             :positionals [{:name :feature
                            :required? true
                            :doc "Feature/branch slug; the land run id."}]}
    "next" {:doc "Show the ready land step views for a feature."
            :positionals [{:name :feature
                           :required? true
                           :doc "Land run id (feature/branch slug)."}]}
    "complete" {:doc "Close the current non-checkpoint land step for a feature."
                :positionals [{:name :feature
                               :required? true
                               :doc "Land run id."}
                              {:name :tail
                               :variadic? true
                               :doc "Optional notes and a trailing step=<id> selector."}]}
    "choose" {:doc "Decide the land sign-off checkpoint: approved or abort."
              :positionals [{:name :feature
                             :required? true
                             :doc "Land run id."}
                            {:name :choice
                             :required? true
                             :doc "Checkpoint choice: approved or abort."}
                            {:name :tail
                             :variadic? true
                             :doc "Optional JSON-object input (abort requires {\"reason\":\"...\"}) and a trailing step=<id> selector."}]}
    "status" {:doc "Show the land root, ready steps, done state, and run history for a feature."
              :positionals [{:name :feature
                             :required? true
                             :doc "Land run id."}]}}})

(defn install!
  "Install the repo's hand-authored workflows: the delegate-pipeline pattern
  and the coordinator land workflow with its op."
  []
  (let [runtime (current/runtime)]
    {:installed true
     :namespace 'workflows
     :patterns [(patterns/register-pattern!
                 runtime
                 'delegate-pipeline
                 "Create a sequential chain-loop workflow of subagent gates. Input: {run_id,tasks:[{id,title,body?,harness?,cwd?,max-attempts?}],harness?,cwd?,accept?}."
                 'workflows/delegate-pipeline
                 ::delegate-pipeline-input)]
     :ops [(api/register-op!
            runtime
            'land
            {:doc (:doc land-arg-spec)
             :arg-spec land-arg-spec}
            'workflows/land-op)]
     :land-workflows (register-land-workflows!)}))
