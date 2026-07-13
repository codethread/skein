(ns config
  "Repo-local Skein runtime configuration for skein-src: named queries and the
  thin CLI op surface over the shipped spools.

  Thin glue only: `skein.spools.devflow` owns the feature lifecycle,
  `skein.spools.workflow` is the engine, `skein.spools.delegation` owns the
  `strand agent` surface plus the `agent-plan` pattern, and `skein.spools.loom`
  owns the read-only work-graph projections (all activated from init.clj).
  This file registers the devflow wrapper ops, the loom projection ops
  (`current-dags`/`branches`/`flow-status` — the generic projection logic lives
  in the spool; the ops here supply repo policy such as which attribute names a
  branch and which query feeds the ready frontier), the `kanban-tree` board
  projection op, the `hitl` session op, and a few named queries. Sibling
  init.clj modules hold the rest of the repo
  policy: hand-authored workflows in workflows.clj, harness seats in
  harnesses.clj, chime attention rules in attention.clj, the NVD scan cron job
  in nvd_scan.clj, and reviewer rosters in reviewers.clj."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [skein.macros.ops :refer [defop forget-ops! install-ops!]]
            [skein.macros.queries :refer [defquery forget-queries! install-queries! remembered-queries]]
            [skein.spools.carder :as carder]
            [skein.spools.devflow :as devflow]
            [skein.spools.loom :as loom]
            [skein.spools.agent-run :as shuttle]
            [skein.spools.workflow :as workflow]
            [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.graph.alpha :as graph]
            [skein.api.spool.alpha :refer [attr-get]]
            [skein.api.weaver.alpha :as weaver]))

;; Reload correctness: clear this namespace's remembered ops/queries before the
;; def forms below re-register them, so a targeted reload (load-file + reload!)
;; installs exactly what this file's current source defines rather than also
;; re-registering ops/queries since renamed or removed (TEN-003).
(forget-queries! 'config)
(forget-ops! 'config)

;; ---------------------------------------------------------------------------
;; Named queries
;; ---------------------------------------------------------------------------

(defquery feature-active-query
  "Parameterized query for all active strands carrying a feature attribute."
  {:usage "strand list --query feature-active --param feature=<feature>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]]})

(defquery feature-work-query
  "Parameterized query for active task/review strands in a feature."
  {:usage "strand ready --query feature-work --param feature=<feature>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]
           [:in [:attr :kind] ["task" "review"]]]})

(defquery feature-owner-work-query
  "Parameterized query for active task/review strands in a feature owned by one actor."
  {:usage "strand ready --query feature-owner-work --param feature=<feature> --param owner=<owner>"}
  {:params [:feature :owner]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]
           [:= [:attr :owner] [:param :owner]]
           [:in [:attr :kind] ["task" "review"]]]})

(defquery feature-run-query
  "Parameterized query for the active strands of one workflow run/feature."
  {:usage "strand list --query feature-run --param feature=<feature>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr "workflow/run-id"] [:param :feature]]]})

(defquery workflow-runs-query
  "Query for active workflow molecule roots (any family)."
  {:usage "strand list --query workflow-runs"}
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "molecule"]])

(defquery devflow-runs-query
  "Query for active devflow lifecycle roots."
  {:usage "strand list --query devflow-runs"}
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "molecule"]
   [:= [:attr "workflow/family"] "devflow"]])

(defquery work-query
  "Query for active actionable work, excluding workflow plumbing, agent run records, and inert kanban refinement cards."
  {:usage "strand ready --query work"}
  [:and
   [:= :state "active"]
   [:or [:missing [:attr "agent-run/run"]]
    [:not [:= [:attr "agent-run/run"] "true"]]]
   [:or [:missing [:attr "kanban/status"]]
    [:not [:= [:attr "kanban/status"] "refinement"]]]
   [:or
    [:missing [:attr "workflow/role"]]
    [:not [:in [:attr "workflow/role"] ["molecule" "digest" "procedure"]]]]])

;; ---------------------------------------------------------------------------
;; current-dags: active work-DAG projection (loom)
;; ---------------------------------------------------------------------------

(defop current-dags
  "Return active parent-of work DAGs and their active depends-on edges.

  This is an operation rather than only a named query because the CLI query
  surface returns flat strand rows; `skein.spools.loom/work-dags` projects
  roots, hierarchy edges, dependency edges, and compact strand rows into one
  JSON-compatible structure for agents and humans."
  {:arg-spec {:op "current-dags"
              :doc "Show active parent-of work DAGs with active depends-on edges."}}
  [_ctx]
  (merge {:operation "current-dags"}
         (loom/work-dags (current/runtime))))

;; ---------------------------------------------------------------------------
;; kanban-tree: epic -> feature -> task projection for the agent dashboard
;; ---------------------------------------------------------------------------

;; The kanban board links its three tiers (epic -> feature -> task) only through
;; parent-of edges — no tier carries its parent's id as an attribute — so the CLI
;; query surface, which filters flat strand rows by attribute, cannot join them.
;; This projection walks each tier with one batched single-hop `outgoing-edges`
;; call (no transitive subgraph, so note/agent-run descendants never leak in) and
;; derives task status exactly as `kanban card` does, letting a renderer build the
;; collapsible tree from a single poll rather than a round trip per feature. The
;; task-tier helpers below mirror the kanban spool's private `feature-tasks` /
;; `derive-task-status` / `tasks-with-status`, which are not part of its API.

(defn- kanban-card-type
  "Return a kanban card's type, defaulting to feature (cards predating the epic tier)."
  [strand]
  (or (attr-get strand :kanban/type) "feature"))

(defn- kanban-task?
  "Return true when strand carries the kanban task marker."
  [strand]
  (= "true" (attr-get strand :kanban/task)))

(defn- kanban-task-status
  "Derive a task's status from core graph state and the core `owner` attr only:
  `done` when closed, `blocked` while any dependency is unclosed, then
  `doing`/`ready` on whether an owner is stamped."
  [task dep-states]
  (cond
    (= "closed" (:state task)) "done"
    (some #(not= "closed" %) dep-states) "blocked"
    (some? (attr-get task :owner)) "doing"
    :else "ready"))

(defn- kanban-tasks-with-status
  "Return a map of task id -> compact task view decorated with derived status,
  batching the depends-on frontier so status resolves without a per-task round
  trip."
  [rt tasks]
  (let [dep-edges (graph/outgoing-edges rt (mapv :id tasks) "depends-on")
        target-state (into {}
                           (map (juxt :id :state))
                           (graph/strands-by-ids rt (into [] (map :to_strand_id) dep-edges)))
        deps-by-task (reduce (fn [m {:keys [from_strand_id to_strand_id]}]
                               (update m from_strand_id (fnil conj []) to_strand_id))
                             {} dep-edges)]
    (into {}
          (map (fn [task]
                 [(:id task)
                  (cond-> {:id (:id task)
                           :title (:title task)
                           :state (:state task)
                           :status (kanban-task-status
                                    task
                                    (map target-state (get deps-by-task (:id task))))}
                    (attr-get task :owner) (assoc :owner (attr-get task :owner)))]))
          tasks)))

(defn- kanban-tree-projection
  "Join the kanban card tiers into cards carrying their parent epic id and their
  tasks with derived status. `all?` includes closed cards and tasks; otherwise
  only active cards and their non-closed tasks are returned."
  [rt all?]
  (let [all-cards (graph/strands-by-ids rt (graph/query-ids rt 'kanban-cards {}))
        cards (if all? (vec all-cards) (filterv #(= "active" (:state %)) all-cards))
        epics (filterv #(= "epic" (kanban-card-type %)) cards)
        features (filterv #(= "feature" (kanban-card-type %)) cards)
        feature-ids (set (map :id features))
        ;; epic -> feature: direct parent-of children that are feature cards on the board
        epic-of-feature (into {}
                              (comp (filter #(feature-ids (:to_strand_id %)))
                                    (map (juxt :to_strand_id :from_strand_id)))
                              (graph/outgoing-edges rt (mapv :id epics) "parent-of"))
        ;; feature -> task: direct parent-of children carrying the task marker
        task-edges (graph/outgoing-edges rt (mapv :id features) "parent-of")
        tasks (cond->> (filter kanban-task? (graph/strands-by-ids rt (mapv :to_strand_id task-edges)))
                (not all?) (filter #(not= "closed" (:state %)))
                :always vec)
        task-view (kanban-tasks-with-status rt tasks)
        tasks-of-feature (reduce (fn [m {:keys [from_strand_id to_strand_id]}]
                                   (if-let [t (task-view to_strand_id)]
                                     (update m from_strand_id (fnil conj []) t)
                                     m))
                                 {} task-edges)]
    {:operation "kanban-tree"
     :cards (mapv (fn [s]
                    {:id (:id s)
                     :title (:title s)
                     :state (:state s)
                     :attributes (:attributes s)
                     :created_at (:created_at s)
                     :updated_at (:updated_at s)
                     :type (kanban-card-type s)
                     :epic (get epic-of-feature (:id s))
                     :tasks (vec (sort-by :id (get tasks-of-feature (:id s) [])))})
                  cards)}))

(defop kanban-tree
  "Return the kanban board as an epic -> feature -> task hierarchy in one call.

  Each card carries its `type` (epic/feature), the `epic` id it hangs under (nil
  for top-level cards), and its `tasks` with derived status, so a renderer builds
  the collapsible board without a round trip per feature. Read-only; mirrors the
  `kanban-cards` query's active-by-default scope, widened with `--all true`."
  {:arg-spec {:op "kanban-tree"
              :doc "Project the epic -> feature -> task kanban hierarchy with derived task status."
              :flags {:all {:type :boolean-token
                            :doc "Include closed cards and tasks: true or false (default false)."}}}
   :hook-class :read}
  [ctx]
  (let [{:keys [all]} (:op/args ctx)]
    (kanban-tree-projection (current/runtime) (boolean all))))

;; ---------------------------------------------------------------------------
;; devflow ops: thin CLI wrappers over skein.spools.devflow
;; ---------------------------------------------------------------------------

(defn require-non-blank!
  "Return value when it is a non-blank string, otherwise fail with arg context.

  Public: workflows.clj (loaded after this file) reuses it for the land op."
  [arg value]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (ex-info (str (name arg) " must be a non-blank string")
                    {:argument arg :value value})))
  value)

(defn parse-json-object-arg
  "Parse a CLI JSON-object argument into a keywordized map, failing loudly.

  Public: workflows.clj (loaded after this file) reuses it for the land op."
  [op raw]
  (let [value (json/read-str raw :key-fn keyword)]
    (when-not (map? value)
      (throw (ex-info (str op " JSON input must be an object")
                      {:input raw})))
    value))

;; The blessed arg-spec parser (skein.api.cli.alpha) binds positionals strictly
;; by order, so it cannot express the position-independent `step=<id>` selector
;; these ops accept, nor disambiguate the optional json-input/notes slots it can
;; sit among (docs/skein.md "Discovery tiers"). We therefore declare the fixed
;; positionals in each arg-spec — which drives generated `strand help <op>` — and
;; collect the optional tail into one variadic positional, then split `step=<id>`
;; out of that tail here. Fail-loud errors reference `strand help <op>` in their
;; data instead of a hand-written usage string.
(defn pop-step-selector
  "Split one optional `step=<id>` selector out of variadic tail tokens.

  `step=` is a whole token rather than a positional slot so it never collides
  with the other optional args (notes, JSON input) sharing the tail. Returns
  `[other-tokens step-id-or-nil]`, failing loudly on a duplicate selector or a
  blank id. Public: workflows.clj (loaded after this file) reuses it for the
  land op's tail convention."
  [op tail]
  (let [{steps true others false} (group-by #(str/starts-with? % "step=") tail)]
    (when (> (count steps) 1)
      (throw (ex-info (str op " accepts at most one step=<id> selector")
                      {:op op :help (str "strand help " op) :tail (vec tail)})))
    (when-let [step (first steps)]
      (require-non-blank! :step (subs step (count "step="))))
    [(vec others) (some-> (first steps) (subs (count "step=")))]))

(def ^:private worktree-check-values
  #{"required" "already-in-worktree-ok"})

(def ^:private feature-positional
  "Required `<feature>` positional shared by the devflow wrapper ops."
  {:name :feature
   :type :string
   :required? true
   :doc "Feature name; the workflow run id shared by all devflow ops."})

(defop devflow-start
  "Start the devflow lifecycle for a feature.

  The feature name is the workflow run-id for all other devflow ops;
  worktree-check is `required` (default) or `already-in-worktree-ok`."
  {:arg-spec {:op "devflow-start"
              :doc "Start the devflow lifecycle for a feature."
              :positionals [feature-positional
                            {:name :worktree-check
                             :type :string
                             :doc "Worktree policy: required (default) or already-in-worktree-ok."}]}}
  [ctx]
  (let [{:keys [feature worktree-check]} (:op/args ctx)]
    (require-non-blank! :feature feature)
    (when (and worktree-check (not (contains? worktree-check-values worktree-check)))
      (throw (ex-info "worktree-check must be required or already-in-worktree-ok"
                      {:value worktree-check :help "strand help devflow-start"})))
    (merge {:operation "devflow-start"
            :feature feature}
           (devflow/start! feature (if worktree-check {:worktree-check worktree-check} {})))))

(defop devflow-next
  "Return the ready devflow step views for a feature."
  {:arg-spec {:op "devflow-next"
              :doc "Show the ready devflow step views for a feature."
              :positionals [feature-positional]}}
  [ctx]
  (let [{:keys [feature]} (:op/args ctx)]
    {:operation "devflow-next"
     :feature feature
     :ready (devflow/next-steps feature)}))

(defop devflow-choices
  "Return choice explanations for the feature's current checkpoint."
  {:arg-spec {:op "devflow-choices"
              :doc "Explain the current devflow checkpoint choices for a feature."
              :positionals [feature-positional
                            {:name :step-selector
                             :type :string
                             :variadic? true
                             :doc "Optional trailing `step=<id>` selector for a specific ready step."}]}}
  [ctx]
  (let [{:keys [feature step-selector]} (:op/args ctx)
        [extra step] (pop-step-selector "devflow-choices" step-selector)]
    (when (seq extra)
      (throw (ex-info "devflow-choices accepts only a feature and an optional step=<id> selector"
                      {:op "devflow-choices" :help "strand help devflow-choices" :extra extra})))
    {:operation "devflow-choices"
     :feature feature
     :choices (devflow/choice-details feature (if step {:step step} {}))}))

(defop devflow-choose
  "Record a devflow checkpoint choice, optionally with JSON input.

  `json-input` must be a JSON object; routed choices merge it into the next
  stage's params (an abort requires `{\"reason\":\"...\"}`)."
  {:arg-spec {:op "devflow-choose"
              :doc "Record a devflow checkpoint choice for a feature, optionally with JSON input."
              :positionals [feature-positional
                            {:name :choice
                             :type :string
                             :required? true
                             :doc "Checkpoint choice key, e.g. approved or abort."}
                            {:name :tail
                             :type :string
                             :variadic? true
                             :doc "Optional JSON-object input and a trailing `step=<id>` selector."}]}}
  [ctx]
  (let [{:keys [feature choice tail]} (:op/args ctx)
        [rest-tokens step] (pop-step-selector "devflow-choose" tail)
        raw-input (first rest-tokens)]
    (when (> (count rest-tokens) 1)
      (throw (ex-info "devflow-choose accepts at most one JSON-input argument"
                      {:op "devflow-choose" :help "strand help devflow-choose" :extra (vec (rest rest-tokens))})))
    (let [input (if raw-input (parse-json-object-arg "devflow-choose" raw-input) {})]
      (merge {:operation "devflow-choose"
              :feature feature
              :choice choice}
             (devflow/choose! feature (keyword choice) input (if step {:step step} {}))))))

(defop devflow-complete
  "Close the feature's current non-checkpoint devflow step."
  {:arg-spec {:op "devflow-complete"
              :doc "Close the current non-checkpoint devflow step for a feature."
              :positionals [feature-positional
                            {:name :tail
                             :type :string
                             :variadic? true
                             :doc "Optional notes and a trailing `step=<id>` selector."}]}}
  [ctx]
  (let [{:keys [feature tail]} (:op/args ctx)
        [rest-tokens step] (pop-step-selector "devflow-complete" tail)
        notes (first rest-tokens)]
    (when (> (count rest-tokens) 1)
      (throw (ex-info "devflow-complete accepts at most one notes argument"
                      {:op "devflow-complete" :help "strand help devflow-complete" :extra (vec (rest rest-tokens))})))
    (merge {:operation "devflow-complete"
            :feature feature}
           (devflow/complete! feature (cond-> {}
                                        notes (assoc :notes notes)
                                        step (assoc :step step))))))

(defn- checkpoint-ready?
  "Return true when the selected ready devflow step for feature is a checkpoint."
  [feature step]
  (let [ready (devflow/next-steps feature)
        selected (if step
                   (or (first (filter #(= step (:id %)) ready))
                       (throw (ex-info "step selector did not match a ready devflow step"
                                       {:feature feature :step step :ready (mapv :id ready)})))
                   (devflow/next-step feature))]
    (= "checkpoint" (:kind selected))))

(defn- parse-advance-tail
  "Parse devflow-advance tail tokens into workflow advance opts.

  The tail carries the optional `[choice] [json-input] [notes]` args plus a
  position-independent `step=<id>` selector. With one bare optional arg it is a
  choice when the selected ready step is a checkpoint and notes otherwise; JSON
  input must be an object starting with `{`."
  [feature tail]
  (let [[args step] (pop-step-selector "devflow-advance" tail)
        _ (when (> (count args) 3)
            (throw (ex-info "devflow-advance accepts at most a choice, JSON input, and notes"
                            {:op "devflow-advance" :help "strand help devflow-advance" :extra (vec args)})))
        [choice raw-input notes]
        (case (count args)
          0 [nil nil nil]
          1 (let [arg (first args)]
              (cond
                (str/starts-with? arg "{") [nil arg nil]
                (checkpoint-ready? feature step) [arg nil nil]
                :else [nil nil arg]))
          2 (let [[a b] args]
              (if (str/starts-with? a "{")
                [nil a b]
                [a b nil]))
          3 args)
        input (when raw-input
                (when-not (str/starts-with? raw-input "{")
                  (throw (ex-info "devflow-advance JSON input must start with {"
                                  {:op "devflow-advance" :help "strand help devflow-advance" :input raw-input})))
                (parse-json-object-arg "devflow-advance" raw-input))]
    (cond-> {}
      step (assoc :step step)
      choice (assoc :choice (keyword choice))
      input (assoc :input input)
      notes (assoc :notes notes))))

(defop devflow-advance
  "Advance the current devflow step or checkpoint for a feature."
  {:arg-spec {:op "devflow-advance"
              :doc "Advance the current devflow step or checkpoint for a feature."
              :positionals [feature-positional
                            {:name :tail
                             :type :string
                             :variadic? true
                             :doc "Optional `[choice] [json-input] [notes]` and a trailing `step=<id>` selector."}]}}
  [ctx]
  (let [{:keys [feature tail]} (:op/args ctx)]
    (require-non-blank! :feature feature)
    (merge {:operation "devflow-advance"
            :feature feature}
           (devflow/advance! feature (parse-advance-tail feature tail)))))

(defop devflow-describe
  "Return the devflow cycle or one registered stage description.

  stage-key is a stable devflow registry key such as `proposal` or `spec-plan`."
  {:arg-spec {:op "devflow-describe"
              :doc "Describe the devflow cycle or one registered stage."
              :positionals [{:name :stage-key
                             :type :string
                             :doc "Optional stage key such as proposal or spec-plan."}]}}
  [ctx]
  (let [{:keys [stage-key]} (:op/args ctx)]
    {:operation "devflow-describe"
     :stage stage-key
     :description (if stage-key (devflow/describe (keyword stage-key)) (devflow/describe))}))

(defop devflow-history
  "Return ordered devflow run history for a feature."
  {:arg-spec {:op "devflow-history"
              :doc "Show ordered devflow run history for a feature."
              :positionals [feature-positional]}}
  [ctx]
  (let [{:keys [feature]} (:op/args ctx)]
    {:operation "devflow-history"
     :feature feature
     :history (devflow/history feature)}))

(defop devflow-archive
  "Archive a finished devflow run into one closed digest strand.

  Fails loudly while any devflow stage root for the feature is still active."
  {:arg-spec {:op "devflow-archive"
              :doc "Archive a finished devflow run into one digest strand."
              :positionals [feature-positional]}}
  [ctx]
  (let [{:keys [feature]} (:op/args ctx)]
    {:operation "devflow-archive"
     :feature feature
     :digest (devflow/archive! feature)}))

(defop devflow-status
  "Return the active devflow root, ready steps, and done state for a feature.

  Fails loudly for a feature that never started a devflow run."
  {:arg-spec {:op "devflow-status"
              :doc "Show the devflow root, ready steps, and done state for a feature."
              :positionals [feature-positional]}}
  [ctx]
  (let [{:keys [feature]} (:op/args ctx)]
    {:operation "devflow-status"
     :feature feature
     :roots (mapv loom/summarize (devflow/feature-roots feature))
     :done (workflow/done? feature)
     :ready (devflow/next-steps feature)}))

(defop workflow-runs
  "Return active workflow molecule roots, optionally filtered by family."
  {:arg-spec {:op "workflow-runs"
              :doc "Show active workflow molecule roots, optionally filtered by family."
              :positionals [{:name :family
                             :type :string
                             :doc "Optional workflow family, e.g. devflow."}]}}
  [ctx]
  (let [{:keys [family]} (:op/args ctx)]
    {:operation "workflow-runs"
     :family family
     :runs (mapv loom/summarize
                 (if family (workflow/active-runs family) (workflow/active-runs)))}))

(defop devflow-conventions
  "Return the blessed repo conventions installed by this config."
  {:arg-spec {:op "devflow-conventions"
              :doc "Show repo-local spools, ops, patterns, and queries."}}
  [_ctx]
  {:operation "devflow-conventions"
   :spools [{:namespace "skein.spools.workflow"
             :doc "spools/workflow.md"
             :purpose "Workflow engine: definitions compiled to strand molecules with checkpoints, routing, and gates."}
            {:namespace "skein.spools.devflow"
             :doc "spools/devflow.md"
             :purpose "Feature lifecycle (intake -> proposal -> spec-plan -> tasks/implementation) keyed by feature name."}
            {:namespace "skein.spools.ephemeral"
             :doc "spools/ephemeral.md"
             :purpose "Temporary parent-owned strands burned via a userland attribute."}
            {:namespace "skein.spools.kanban"
             :doc "spools/kanban.md"
             :purpose "User-facing kanban board: feature/epic cards with refinement/pending/claimed/in_review lanes."}]
   ;; The config-owned entries below stay hand-authored: their editorial grouping
   ;; (e.g. branches leading, current-dags sitting beside carder-report) does not
   ;; match the defop author order in this file, and reordering the defop forms
   ;; to force a match would trade that grouping away (RFC-020.Q2 tradeoff; see
   ;; PLAN-Srm-001.DN1). :queries has no such mismatch and derives below.
   :ops [{:name "kanban" :help "strand help kanban" :manual "strand kanban about"}
         {:name "kanban-export" :help "strand help kanban-export"}
         {:name "kanban-tree" :help "strand help kanban-tree"
          :purpose "Epic -> feature -> task kanban hierarchy with derived task status, in one projection for renderers."}
         {:name "branches" :help "strand help branches"}
         {:name "devflow-start" :help "strand help devflow-start"}
         {:name "devflow-next" :help "strand help devflow-next"}
         {:name "devflow-choices" :help "strand help devflow-choices"}
         {:name "devflow-choose" :help "strand help devflow-choose"}
         {:name "devflow-complete" :help "strand help devflow-complete"}
         {:name "devflow-advance" :help "strand help devflow-advance"}
         {:name "devflow-describe" :help "strand help devflow-describe"}
         {:name "devflow-history" :help "strand help devflow-history"}
         {:name "devflow-archive" :help "strand help devflow-archive"}
         {:name "devflow-status" :help "strand help devflow-status"}
         {:name "workflow-runs" :help "strand help workflow-runs"}
         {:name "current-dags" :help "strand help current-dags"}
         {:name "carder-report" :help "strand help carder-report"}
         {:name "feature-costs" :help "strand help feature-costs"
          :purpose "Agent-run cost/usage rollup beneath a work root, as pure data. Registered by .skein/analytics.clj."}
         {:name "agent" :help "strand help agent" :manual "strand agent about"}
         {:name "flow-await" :help "strand help flow-await"}
         {:name "flow-status" :help "strand help flow-status"}
         {:name "hitl" :help "strand help hitl" :purpose "Interactive user+agent session with a self-terminating tracking strand."}
         {:name "land" :help "strand help land" :manual "strand land about"
          :purpose (format-alpha/reflow
                    "|Coordinator-only landing workflow: push+draft-PR, green CI, roster
                     |sign-off, squash-merge to local main with full verification, then
                     |green main CI. Registered by .skein/workflows.clj.")}]
   :patterns [{:name "agent-plan"
               :purpose "Create a feature strand plus task/review children for agent work; shipped by skein.spools.delegation."}
              {:name "delegate-pipeline"
               :purpose "Sequential chain-loop workflow of subagent gates with optional acceptance checkpoint. Registered by .skein/workflows.clj."}]
   :queries (into [{:name "kanban-cards"
                    :usage "strand list --query kanban-cards"}
                   {:name "kanban-unstarted"
                    :usage "strand ready --query kanban-unstarted"}]
                  (map #(update % :name str))
                  (remembered-queries 'config))})

(defn- config-attr
  "Read strand attribute k, tolerating keyword- or string-keyed maps."
  [strand k]
  (let [attrs (:attributes strand)]
    (or (get attrs k) (get attrs (subs (str k) 1)))))

(defn- kanban-card-orphan?
  "Return true when an orphan row is an unclaimed kanban card.

  Refinement and pending cards (and unclaimed epics) intentionally sit as
  root strands on the board; they are queue entries, not graph hygiene
  problems. Claimed cards keep surfacing so missing task/devflow children
  stay visible."
  [row]
  (and (= "true" (config-attr row :kanban/card))
       (contains? #{"pending" "refinement"} (config-attr row :kanban/status))))

(defn- suppress-expected-carder-orphans
  "Return report with expected repo-local orphan rows removed."
  [report]
  (let [rows (remove kanban-card-orphan? (get-in report [:orphans :rows]))]
    (assoc report :orphans {:count (count rows) :rows (vec rows)})))

(defop carder-report
  "Return the carder graph hygiene report for active work.

  This is a read-only wrapper around `skein.spools.carder/report` for checking
  stale active strands, orphaned strands, and work blocked by failed agent runs.
  Repo-local expected kanban board roots are suppressed from the orphan list."
  {:arg-spec {:op "carder-report"
              :doc "Show the read-only carder graph hygiene report."
              :flags {:days {:type :int
                             :doc "Maximum active age, in days, before a strand is stale."}
                      :include-plumbing {:type :boolean-token
                                         :doc "Whether to include workflow and agent-run plumbing: true or false."}}}}
  [ctx]
  (let [{:keys [days include-plumbing]} (:op/args ctx)]
    (suppress-expected-carder-orphans
     (carder/report (cond-> {}
                      days (assoc :days days)
                      (some? include-plumbing) (assoc :include-plumbing? include-plumbing))))))

(defop flow-await
  "Block until a workflow run is done or needs coordinator attention.

  Usage: `strand flow-await <workflow-run-id> [--timeout-secs <n>]`. Workflow
  executor registrations decide which ready gates can stay waiting silently and
  which stalled gates need coordinator attention."
  {:arg-spec {:op "flow-await"
              :doc "Block until a workflow run needs coordinator attention."
              :flags {:timeout-secs {:type :int
                                     :doc "Optional timeout in seconds."}}
              :positionals [{:name :workflow-run-id
                             :type :string
                             :required? true
                             :doc "Workflow run id."}]}
   :deadline-class :unbounded}
  [ctx]
  (let [{:keys [workflow-run-id timeout-secs]} (:op/args ctx)]
    (workflow/await! workflow-run-id (cond-> {}
                                       timeout-secs (assoc :timeout-secs timeout-secs)))))

(defop flow-status
  "Return workflow flow status by joining history, frontier, gates, runs, and stalls.

  The JSON payload is read-only and suitable for renderers; no workflow,
  agent-run, or gate state is mutated. The join and Mermaid gate chain live in
  `skein.spools.loom/flow-status`; this op only names the run and stamps the
  operation."
  {:arg-spec {:op "flow-status"
              :doc "Show workflow flow status for renderer consumption."
              :positionals [{:name :workflow-run-id
                             :type :string
                             :required? true
                             :doc "Workflow run id."}]}}
  [ctx]
  (let [{run-id :workflow-run-id} (:op/args ctx)]
    (merge {:operation "flow-status"}
           (loom/flow-status (current/runtime) run-id))))

;; ---------------------------------------------------------------------------
;; hitl: interactive human-in-the-loop working sessions
;; ---------------------------------------------------------------------------

(defn- hitl-prompt
  "Compose the session prompt: coordinator-supplied context plus the tracking
  contract that makes the session self-terminating (the session agent records
  notes and an outcome on the tracking strand, then closes it, which completes
  the run and tears down the multiplexer session)."
  [tracking-id context]
  (str "You are an INTERACTIVE HITL session: the user is attached to this"
       " terminal and you work through the task together, at their direction."
       " This is a working session, not a headless task — converse, propose,"
       " and act when they agree.\n\n"
       context
       "\n\n## Tracking contract (important)\n"
       "Your tracking strand is " tracking-id ". A coordinator agent reads it"
       " after this session to learn what happened — the user will not relay"
       " details.\n"
       "- Record each significant decision as a closed note child as you go:"
       " `strand add \"note: <decision>\" --state closed` then"
       " `strand update " tracking-id " --edge parent-of:<note-id>`.\n"
       "- When the user says you are done: write the outcome —"
       " `strand update " tracking-id " --attr outcome=\"<2-5 sentence summary:"
       " decisions, commits (shas), open questions>\"` — then close the strand:"
       " `strand update " tracking-id " --state closed`.\n"
       "- Closing " tracking-id " completes your run and tears down this"
       " session — make it your very last act, after the outcome attr is"
       " written and any final commit is made.\n"))

(defop hitl
  "Open an interactive HITL working session for a human + agent pair.

  Usage: `strand hitl <parent-id> <title> --context <text> [--cwd <dir>]
  [--harness <name>] [--backend <name>]`. Creates a tracking strand under
  `parent-id` (a kanban card, plan, or work root), composes the required
  `--context` brief with the tracking contract, and spawns an interactive
  multiplexer run serving the tracking strand (default harness `hitl-fable`,
  backend `tmux`). The session ends when the session agent closes the tracking
  strand after writing its outcome; the coordinator then reads the tracking
  strand for notes and outcome. Returns the tracking id and pending run
  summary — `strand agent ps` carries the session name and attach command once
  the session is live."
  {:arg-spec {:op "hitl"
              :doc "Open an interactive HITL session: tracking strand + multiplexer run."
              :positionals [{:name :parent-id
                             :type :string
                             :required? true
                             :doc "Strand to hang the tracking strand under (kanban card, plan, or work root)."}
                            {:name :title
                             :type :string
                             :required? true
                             :doc "Short session title."}]
              :flags {:context {:type :string
                                :doc "Required session brief: the situation, artifacts, findings, and what to work through together."}
                      :cwd {:type :string
                            :doc "Working directory for the session (defaults to the workspace root)."}
                      :harness {:type :string
                                :doc "Interactive-capable harness (prompt-via :arg TUI, e.g. hitl-fable — the default). Headless harnesses like build die in a pane."}
                      :backend {:type :string
                                :doc "Multiplexer backend (default tmux)."}}}}
  [ctx]
  (let [{:keys [parent-id title context cwd harness backend]} (:op/args ctx)
        rt (current/runtime)]
    (require-non-blank! :context context)
    (when-not (weaver/show rt parent-id)
      (throw (ex-info "hitl parent strand not found" {:parent parent-id})))
    (let [tracking (weaver/add rt {:title (str "HITL: " title)
                                   :attributes {"hitl" "true"
                                                "body" (str "Tracking strand for the interactive HITL session \"" title "\"."
                                                            " The session agent appends closed note children for decisions,"
                                                            " writes a final outcome attr, then closes this strand to end its"
                                                            " run and tear down the session.")}})]
      (weaver/update rt parent-id {:edges [{:type "parent-of" :to (:id tracking)}]})
      (let [run (shuttle/spawn-run! {:harness (or harness "hitl-fable")
                                     :prompt (hitl-prompt (:id tracking) context)
                                     :title (str "HITL: " title)
                                     :parent (:id tracking)
                                     :cwd cwd
                                     :mode :interactive
                                     :backend (or backend "tmux")})]
        {:operation "hitl"
         :tracking (:id tracking)
         :parent parent-id
         :run (shuttle/run-summary run)
         :next "strand agent ps shows the attach command once the session is live; the session ends when the tracking strand closes."}))))

;; ---------------------------------------------------------------------------
;; branches: branch-visibility projection over work-root strands (loom)
;; ---------------------------------------------------------------------------

(defop branches
  "Group active branch-stamped work roots into a per-branch progress view.

  The repo convention stamps `branch` (plus `owner`/`worktree`) on exactly one
  active work root per branch — `kanban claim` does this for cards — and hangs
  all execution strands beneath that root with parent-of edges. This op supplies
  the repo policy — the `branch` attribute names the branch and the `work` query
  feeds the ready frontier (so workflow plumbing and agent run records stay
  hidden) — and delegates the projection to `skein.spools.loom/branch-views`.
  A scoping `branch` argument that matches no stamped root fails loudly."
  {:arg-spec {:op "branches"
              :doc "Show active branch-stamped work roots grouped by branch."
              :positionals [{:name :branch
                             :type :string
                             :doc "Optional branch name to scope the projection."}]}}
  [ctx]
  (let [{:keys [branch]} (:op/args ctx)
        branches (loom/branch-views (current/runtime)
                                    {:branch-attr :branch
                                     :ready-query work-query
                                     :branch branch})]
    (when (and branch (empty? branches))
      (throw (ex-info "no active work root is stamped with this branch"
                      {:branch branch})))
    {:operation "branches"
     :branches branches}))

;; ---------------------------------------------------------------------------
;; install!
;; ---------------------------------------------------------------------------

(defn install!
  "Install the repo-local named queries and CLI op surface."
  []
  {:installed true
   :namespace 'config
   :queries (install-queries! 'config)
   :ops (install-ops! 'config)})
