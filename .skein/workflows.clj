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
            [skein.api.format.alpha :as format-alpha]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.delegation :as agents]
            [skein.spools.loom :as loom]
            [skein.api.spool.alpha :refer [attr-get]]
            [skein.spools.workflow :as workflow]))

(def ^:private merge-lock-kind
  "Singleton strand kind for the repo-wide land merge sentinel."
  "merge-lock")

(def ^:private merge-lock-monitor
  "JVM-local monitor for serialising merge-lock acquisition inside one weaver."
  (Object.))

(defn- attr-value
  "Return strand attribute k using the shared fail-loud attribute reader."
  [strand k]
  (when strand
    (attr-get strand k)))

(defn- active-merge-locks
  "Return active merge-lock strands."
  []
  (weaver/list (current/runtime) [:and [:= :state "active"] [:= [:attr "kind"] merge-lock-kind]] {}))

(defn- land-root
  "Return the active land root for feature, failing loudly when absent."
  [feature]
  (or (workflow/current-root feature)
      (throw (ex-info "land run not found" {:feature feature}))))

(defn- acquire-merge-lock!
  "Acquire the singleton merge lock for a land run, or fail when another run owns it."
  [feature]
  (locking merge-lock-monitor
    (let [rt (current/runtime)
          root (land-root feature)
          owner (:id root)
          locks (active-merge-locks)
          owned (some #(when (and (= owner (attr-value % :owner))
                                  (= feature (attr-value % :land/run-id))) %) locks)]
      (if owned
        owned
        (do
          (when-let [held (first locks)]
            (throw (ex-info "another land run holds the merge lock"
                            {:lock (:id held)
                             :owner (attr-value held :owner)
                             :land/run-id (attr-value held :land/run-id)})))
          (weaver/add rt {:title (str "Merge lock: " feature)
                          :attributes {:kind merge-lock-kind
                                       :owner owner
                                       :land/run-id feature}}))))))

(defn- release-merge-lock!
  "Release the merge lock held by feature, if one exists."
  [feature reason]
  (doseq [lock (active-merge-locks)
          :when (= feature (attr-value lock :land/run-id))]
    (weaver/update (current/runtime)
                   (:id lock)
                   {:state "closed"
                    :attributes {:land/released-reason reason}})))

(defn- inspect-merge-lock
  "Return the active merge-lock snapshot, or nil."
  []
  (some-> (first (active-merge-locks)) loom/summarize))

(defn- break-merge-lock!
  "Explicitly break a stale merge lock with a human-supplied reason."
  [reason]
  (config/require-non-blank! :reason reason)
  (let [locks (active-merge-locks)]
    (when (> (count locks) 1)
      (throw (ex-info "multiple active merge locks found; inspect and repair manually"
                      {:locks (mapv :id locks)})))
    (if-let [lock (first locks)]
      {:broken (loom/summarize (weaver/update (current/runtime)
                                              (:id lock)
                                              {:state "closed"
                                               :attributes {:land/broken-reason reason}}))}
      {:broken nil})))

(defn- move-card-to-review!
  "Move the optional land card into in_review when it is present."
  [card]
  (when (and (string? card) (not (str/blank? card)))
    (let [strand (weaver/show (current/runtime) card)]
      (when-not (= "true" (attr-value strand :kanban/card))
        (throw (ex-info "land card is not a kanban card" {:card card})))
      (case (attr-value strand :kanban/status)
        "claimed" ((requiring-resolve 'skein.spools.kanban/request-review!) card)
        "in_review" nil
        (throw (ex-info "land card must be claimed before review"
                        {:card card :status (attr-value strand :kanban/status)}))))))

(defn- suppressing-rollback!
  "Run f during error recovery, suppressing rollback failures on original."
  [^Throwable original f]
  (try
    (f)
    (catch Throwable rollback-error
      (.addSuppressed original rollback-error))))

(defn- move-card-to-rework!
  "Move the optional land card from in_review back to claimed after abort."
  [card]
  (when (and (string? card) (not (str/blank? card)))
    (let [strand (weaver/show (current/runtime) card)]
      (when-not (= "true" (attr-value strand :kanban/card))
        (throw (ex-info "land card is not a kanban card" {:card card})))
      (case (attr-value strand :kanban/status)
        "in_review" ((requiring-resolve 'skein.spools.kanban/rework!) card)
        "claimed" nil
        (throw (ex-info "land card must be in_review before abort rework"
                        {:card card :status (attr-value strand :kanban/status)}))))))

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
                   :attributes {"agent-run/harness" (fn [{:keys [item harness]}]
                                                      (or (task-value item :harness) harness))
                                "agent-run/prompt" (fn [{:keys [run-id item]}]
                                                     (pipeline-task-prompt run-id item))
                                "agent-run/cwd" (fn [{:keys [item cwd]}]
                                                  (or (task-value item :cwd) cwd))
                                "agent-run/max-attempts" (fn [{:keys [item]}]
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
;; requires green CI plus a green local smoke run. The two CI watches are
;; `:shell` gates the shell executor (skein.spools.executors.shell) fulfils
;; mechanically — a red watch stamps `shell/error` on the gate for a
;; fix-push-clear retry, and `land complete` refuses gates. Human steps keep
;; `workflow/instruction` text as the enforcement surface, shipped as data.
;; ---------------------------------------------------------------------------

(def ^:private main-ci-watch-script
  "POSIX script for the main-ci-green shell gate: resolve the pushed main
  sha, wait for its workflow runs to register, then watch every run to
  completion, failing on the first unsuccessful conclusion. The gate's
  `shell/timeout-secs` bounds the whole watch, including the initial wait
  for GitHub to register the runs."
  (str "set -eu\n"
       "sha=$(git rev-parse origin/main)\n"
       "runs=\"\"\n"
       "while [ -z \"$runs\" ]; do\n"
       "  runs=$(gh run list --commit \"$sha\" --json databaseId --jq '.[].databaseId')\n"
       "  [ -n \"$runs\" ] || sleep 10\n"
       "done\n"
       "for id in $runs; do\n"
       "  gh run watch \"$id\" --exit-status\n"
       "done\n"))

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
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Record the abort reason on the kanban card and work root, then stop.
                                 |Note as you go on the doing-task so a cold agent resumes from that
                                 |task plus its latest note. Do NOT merge or push — nothing has landed;
                                 |the branch and worktree stay for follow-up.")})))

(defn land-workflow
  "Return the coordinator LANDING workflow for a feature branch (family \"land\").

  COORDINATOR-ONLY: worker agents never land. Sequential single molecule, one
  linear DAG plus an abort cutover: push + draft PR, green CI at HEAD,
  roster sign-off (only valid on a pushed branch with green CI), a coordinator
  sign-off checkpoint, squash-merge to LOCAL main behind the singleton merge
  lock, push main, green main CI, then cleanup. Both CI watches are `:shell`
  gates the shell executor fulfils by running the recorded `gh` watch; the
  coordinator only sees them when a red watch stamps `shell/error`. Card-backed
  runs move the card to `in_review` when push-draft-pr completes (the automated
  CI watch and review pipeline starts there) and back to `claimed` on abort.
  `params` carry `:feature`, `:branch`, `:worktree`, and optional `:card`; step
  `workflow/instruction` text is command-precise and fail-loud, so the
  discipline lives in the data."
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
                                      " and number in this step's notes before completing. Completing this"
                                      " step starts the automated ci-green shell gate and, for card-backed"
                                      " runs, moves the kanban card to in_review."))})
   (workflow/gate :ci-green
                  (fn [{:keys [branch]}] (str "Watch CI to green at " branch " HEAD"))
                  :shell
                  :depends-on [:push-draft-pr]
                  :attributes {"workflow/action-ref" "land.ci.green"
                               "shell/argv" (fn [{:keys [branch]}]
                                              ["gh" "pr" "checks" branch "--watch" "--fail-fast"])
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 5400
                               "workflow/instruction"
                               (fn [{:keys [branch]}]
                                 (str "Machine gate: the shell executor watches CI at the branch HEAD"
                                      " (`gh pr checks " branch " --watch --fail-fast`) and closes this"
                                      " gate only when ALL checks are green — `land complete` refuses"
                                      " gates. A red or unstarted watch stamps `shell/error` with the"
                                      " captured output: fix in the worktree, commit, `git push`, then"
                                      " clear the stamp (`strand update <gate-id> --attr shell/error=`)"
                                      " to re-run the watch. The gate closing asserts green CI at the"
                                      " watched HEAD sha; the exit code and output tail are recorded on"
                                      " the gate."))})
   (workflow/step :signoff-review
                  (fn [{:keys [branch]}] (str "Run roster sign-off review for " branch))
                  :self
                  :depends-on [:ci-green]
                  :attributes {"workflow/action-ref" "land.signoff.review"
                               "workflow/instruction"
                               (fn [{:keys [worktree card]}]
                                 (str "Run the declared roster review against a TASK strand, never the kanban card"
                                      " or work root — findings append as notes on the review target, and card notes"
                                      " stay lean for handover. "
                                      (if card
                                        (str "Pick the card's task tracking this branch's work"
                                             " (`strand kanban task list " card "`), adding one first if none fits"
                                             " (`strand kanban task add " card " <title>`). ")
                                        "Target the task strand for this work under the work root. ")
                                      "Then: `strand agent review <task-id> --roster change-review --cwd " worktree
                                      " --commit-range origin/main..HEAD`. Drive every fix round to done; each fix"
                                      " round re-pushes the branch and MUST re-establish green CI at the new HEAD"
                                      " (`gh pr checks <branch> --watch` — the ci-green gate closed at an earlier sha"
                                      " and does not re-run) before this step may complete. SIGN-OFF IS ONLY VALID"
                                      " WITH A PUSHED BRANCH AND GREEN CI — that is why this step follows the CI"
                                      " gate. Record the review pass ids and the final verdict in notes. For"
                                      " card-backed land runs the card moved to in_review when push-draft-pr"
                                      " completed; aborting sign-off moves it back to claimed."))})
   (workflow/checkpoint :signoff
                        (fn [{:keys [branch]}] (str "Sign off landing " branch))
                        :depends-on [:signoff-review]
                        :kind :agent
                        :choices [{:key :approved
                                   :label "Approve"
                                   :description
                                   (format-alpha/reflow
                                    "|Sign-off approved on a pushed branch with green CI; continue to the
                                     |local squash-merge and verification. The coordinator holds this
                                     |delegated sign-off authority.")}
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
                                      " the smoke suite `clojure -M:smoke`, and the spool-suite gate"
                                      " `PATH=\"/opt/homebrew/opt/openjdk/bin:$PATH\" make spool-suite-gate`. If any gate fails:"
                                      " `git reset --hard origin/main`, fix on the branch, re-establish green CI at"
                                      " the new HEAD (`gh pr checks <branch> --watch`), and re-run the"
                                      " signoff-review bar before re-attempting. Record every gate result in notes."
                                      " Do NOT push in this step."))})
   (workflow/step :push-main
                  "Push the merged main to origin"
                  :self
                  :depends-on [:merge-local-verify]
                  :attributes {"workflow/action-ref" "land.main.push"
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Push main: `git push origin main`. Completing this step hands the
                                 |watch to the main-ci-green shell gate, which follows every workflow
                                 |run at the pushed sha. Record the pushed sha
                                 |(`git rev-parse origin/main`) in notes.")})
   (workflow/gate :main-ci-green
                  "Watch main CI to green at the pushed sha"
                  :shell
                  :depends-on [:push-main]
                  :attributes {"workflow/action-ref" "land.main.ci-green"
                               "shell/argv" ["sh" "-c" main-ci-watch-script]
                               ;; The feature worktree shares the repo's refs and outlives this
                               ;; gate (cleanup runs after), so it is a safe cwd for the watch.
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 5400
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Machine gate: the shell executor waits for every workflow run at the
                                 |pushed main sha to register, then watches each to completion
                                 |(`gh run list --commit <sha>`, `gh run watch <id> --exit-status`).
                                 |A failed run stamps `shell/error`: re-run transient infra failures
                                 |(`gh run rerun <run-id>`), then clear the stamp
                                 |(`strand update <gate-id> --attr shell/error=`) to re-watch. The
                                 |gate closing asserts green CI on the main sha; run output is
                                 |recorded on the gate.")})
   (workflow/step :cleanup
                  (fn [{:keys [branch]}] (str "Clean up " branch " and close the land run"))
                  :self
                  :depends-on [:main-ci-green]
                  :attributes {"workflow/action-ref" "land.cleanup"
                               "workflow/instruction"
                               (fn [{:keys [branch card worktree]}]
                                 (str "Stop the worktree's warm test REPL before removing it: run `make test-warm-stop`"
                                      " in " worktree " — it reaps the recorded PID from `.test-repl.pid` (by PID only,"
                                      " never `pkill -f`) and clears the `.test-repl-port`/`.test-repl.pid` files, so no"
                                      " orphaned warm JVM outlives the worktree."
                                      " Delete the remote branch (`git push origin --delete " branch "`), which also"
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
   :discipline (format-alpha/reflow
                "|Sign-off is only valid on a pushed branch with an open draft PR and
                 |green CI at HEAD — the ci-green shell gate enforces that ordering
                 |mechanically: the shell executor runs the recorded `gh pr checks`
                 |watch and only its green exit opens signoff-review. A red watch
                 |stamps shell/error on the gate; fix, push, and clear the stamp
                 |(`strand update <gate-id> --attr shell/error=`) to re-run it. For
                 |card-backed runs, completing push-draft-pr moves the card to
                 |in_review, and aborting sign-off moves it back to claimed. A merge
                 |is a squash into LOCAL main guarded by a singleton merge lock. The
                 |lock is acquired after sign-off approval, immediately before the
                 |local merge step, so review/CI work can run concurrently but only
                 |one coordinator mutates main. Local main must pass the full
                 |verification gate (tests + go tests + fmt/lint/reflect/docs +
                 |smoke) before main is pushed; main is only landed once the
                 |main-ci-green shell gate watches its CI to green. Aborting at
                 |sign-off records a reason and leaves the branch/worktree
                 |untouched.")
   :steps [{:step "push-draft-pr" :purpose "Push the branch and open (or reuse) a draft PR against main; completing it starts the automated CI watch and moves a card-backed run's card to in_review."}
           {:step "ci-green" :purpose "Machine shell gate: the executor watches CI to green at the branch HEAD; a red watch stamps shell/error for a fix-push-clear retry."}
           {:step "signoff-review" :purpose "Run the declared roster review and drive fix rounds; every fix round re-establishes green CI."}
           {:step "signoff" :purpose "Coordinator sign-off checkpoint (:agent): approved continues in the molecule, abort routes to a reason-recording step."}
           {:step "merge-local-verify" :purpose "Squash-merge into local main without pushing, then run the full local verification gate + smoke."}
           {:step "push-main" :purpose "Push main; hands the watch to the main-ci-green gate."}
           {:step "main-ci-green" :purpose "Machine shell gate: the executor watches every main workflow run at the pushed sha to green."}
           {:step "cleanup" :purpose "Delete the remote branch/PR, remove the worktree+branch, finish the card, and close the run."}]
   :commands [{:verb "start" :purpose "Pour and start the land run: land start <feature> --branch <b> --worktree <path> [--card <id>]."}
              {:verb "next" :purpose "Show the ready land step views for a feature."}
              {:verb "complete" :purpose "Close the current non-checkpoint land step, optionally with notes and a step=<id> selector. CI shell gates are closed by the executor, never by complete."}
              {:verb "choose" :purpose "Decide the sign-off checkpoint: approved, or abort with {\"reason\":\"...\"}."}
              {:verb "status" :purpose "Show the land root, ready steps, done state, run history, and merge lock."}
              {:verb "break-lock" :purpose "Explicitly break a stale merge lock with a reason."}]
   :discovery {:help "strand help land"
               :conventions "strand devflow-conventions"}})

(defn land-op
  "Dispatch parsed `strand land ...` subcommands over the land workflow."
  [ctx]
  (let [{:keys [subcommand feature choice tail] :as args} (:op/args ctx)]
    (condp = subcommand
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
                   (let [ready-before (workflow/next-steps feature)
                         releasing? (some #(contains? #{"land.cleanup" "land.abort.record"} (:action-ref %)) ready-before)
                         root (workflow/current-root feature)
                         context (attr-value root :workflow/context)
                         card (or (:card context) (get context "card"))
                         ;; Completing push-draft-pr starts the automated CI watch and the
                         ;; review pipeline; the shell executor closes the CI gates, so this
                         ;; is the last human completion before review.
                         reviewing? (some #(= "land.pr.open" (:action-ref %)) ready-before)]
                     (when reviewing?
                       (move-card-to-review! card))
                     (try
                       (let [result (workflow/complete! feature (cond-> {}
                                                                  notes (assoc :notes notes)
                                                                  step (assoc :step step)))]
                         (when releasing?
                           (release-merge-lock! feature "land terminal cleanup"))
                         (merge {:operation "land-complete" :feature feature} result))
                       (catch Throwable t
                         (when reviewing?
                           (suppressing-rollback! t #(move-card-to-rework! card)))
                         (throw t)))))
      "choose" (let [[rest-tokens step] (config/pop-step-selector "land choose" tail)
                     raw-input (first rest-tokens)]
                 (config/require-non-blank! :feature feature)
                 (when (> (count rest-tokens) 1)
                   (throw (ex-info "land choose accepts at most one JSON-input argument"
                                   {:op "land choose" :help "strand help land" :extra (vec (rest rest-tokens))})))
                 (let [input (if raw-input (config/parse-json-object-arg "land choose" raw-input) {})
                       context (attr-value (workflow/current-root feature) :workflow/context)
                       card (or (:card context) (get context "card"))
                       aborting? (= "abort" choice)
                       lock (when (= "approved" choice)
                              (acquire-merge-lock! feature))]
                   (when aborting?
                     (move-card-to-rework! card))
                   (try
                     (merge {:operation "land-choose" :feature feature :choice choice}
                            (workflow/choose! feature (keyword choice) input (if step {:step step} {})))
                     (catch Throwable t
                       (when lock
                         (suppressing-rollback! t #(release-merge-lock! feature "land choose failed")))
                       (when aborting?
                         (suppressing-rollback! t #(move-card-to-review! card)))
                       (throw t)))))
      "status" (do (config/require-non-blank! :feature feature)
                   (let [root (workflow/current-root feature)]
                     {:operation "land-status"
                      :feature feature
                      :roots (mapv loom/summarize (if root [root] []))
                      :done (workflow/done? feature)
                      :ready (workflow/next-steps feature)
                      :history (workflow/run-history feature)
                      :merge-lock (inspect-merge-lock)}))
      "break-lock" (let [reason (first tail)]
                     (when (> (count tail) 1)
                       (throw (ex-info "land break-lock accepts one reason argument"
                                       {:op "land break-lock" :extra (vec (rest tail))})))
                     (merge {:operation "land-break-lock"}
                            (break-merge-lock! reason))))))

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
    "status" {:doc "Show the land root, ready steps, done state, run history, and merge lock."
              :positionals [{:name :feature
                             :required? true
                             :doc "Land run id."}]}
    "break-lock" {:doc "Explicitly break a stale merge lock with a reason."
                  :positionals [{:name :tail
                                 :required? true
                                 :variadic? true
                                 :doc "Reason text."}]}}})

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
     :ops [(weaver/register-op!
            runtime
            'land
            {:doc (:doc land-arg-spec)
             :arg-spec land-arg-spec}
            'workflows/land-op)]
     :land-workflows (register-land-workflows!)}))
