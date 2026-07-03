(ns config
  "Repo-local Skein runtime configuration for skein-src.

  Thin glue over the shipped spools: `skein.spools.devflow` owns the feature
  lifecycle and `skein.spools.workflow` is the engine (both activated from
  init.clj). This config only adds CLI-facing `strand op` wrappers over the
  devflow spool commands, a few named queries, the generic `agent-plan` weave
  pattern, the `current-dags` graph projection, and repo-local shuttle harness
  aliases."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.patterns.alpha :as patterns]
            [skein.spools.devflow :as devflow]
            [skein.spools.shuttle :as shuttle]
            [skein.spools.workflow :as workflow]
            [skein.api.weaver.alpha :as api]
            [skein.api.runtime.alpha :as runtime-alpha]))

;; ---------------------------------------------------------------------------
;; Delegation policy and agent-plan weave patterns
;; ---------------------------------------------------------------------------

(def delegation-policy-text
  "Repo-local policy text prepended to delegated-agent prompts."
  "Repo delegated-agent contract:\n- Read the assigned strand first with the pinned strand command.\n- Record progress with a `progress` attribute on the task strand and notes on the run.\n- Set `status=implemented` on the task only when validation is green.\n- Never close the assigned strand.\n- Never mutate sibling or parent strands unless the body says so.\n- Do not commit unless the body says so.")

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::feature ::non-blank-string)
(s/def ::title ::non-blank-string)
(s/def ::key ::non-blank-string)
(s/def ::kind #{"task" "review"})
(s/def ::body ::non-blank-string)
(s/def ::hitl boolean?)
(s/def ::depends_on (s/coll-of ::key :kind vector?))
(s/def ::owner ::non-blank-string)
(s/def ::branch ::non-blank-string)
(s/def ::validation (s/coll-of ::non-blank-string :kind vector? :min-count 1))
(s/def ::harness ::non-blank-string)
(s/def ::cwd ::non-blank-string)
(s/def ::max-attempts pos-int?)
(s/def ::task (s/keys :req-un [::key ::title]
                      :opt-un [::body ::kind ::hitl ::depends_on
                               ::owner ::branch ::validation ::harness ::cwd ::max-attempts]))
(s/def ::tasks (s/coll-of ::task :kind vector? :min-count 1))
(s/def ::agent-plan-input (s/keys :req-un [::feature ::title ::tasks]
                                  :opt-un [::body]))

(defn- ref-symbol
  "Return the batch ref symbol for a user-supplied task key."
  [key]
  (symbol key))

(defn- plan-strand
  "Return the feature/plan strand for an agent-plan input."
  [{:keys [feature title body tasks]}]
  {:ref 'plan
   :title title
   :attributes (cond-> {:feature feature
                        :kind "plan"
                        :workflow "agent-plan"}
                 body (assoc :body body))
   :edges (mapv (fn [{:keys [key]}]
                  {:type "parent-of" :to (ref-symbol key)})
                tasks)})

(defn- task-strand
  "Return one task/review strand for an agent-plan input task."
  [feature {:keys [key title body kind hitl owner branch validation depends_on harness cwd max-attempts] :as task}]
  (cond-> {:ref (ref-symbol key)
           :title title
           :attributes (cond-> {:feature feature
                                :kind (or kind "task")
                                :workflow "agent-plan"
                                :task_key key}
                         body (assoc :body body)
                         hitl (assoc :hitl true)
                         owner (assoc :owner owner)
                         branch (assoc :branch branch)
                         harness (assoc :harness harness)
                         cwd (assoc :cwd cwd)
                         max-attempts (assoc :max-attempts max-attempts)
                         (contains? task :validation) (assoc :validation validation))}
    (seq depends_on)
    (assoc :edges (mapv (fn [dep]
                          {:type "depends-on" :to (ref-symbol dep)})
                        depends_on))))

(defn agent-plan
  "Create a feature strand plus task/review children for agent work.

  Input requires `feature`, `title`, and a non-empty `tasks` vector. Optional
  `body` fields carry issue-style context on the plan or tasks. Task keys become
  local batch refs. Each task may set `kind` to `task` or `review`, `hitl` to
  true, and `depends_on` to a vector of task keys. The generated plan has
  `kind=plan`, children share `feature`, the plan has `parent-of` edges to each
  child, and task dependencies become `depends-on` edges. Optional coordination
  attributes include `owner`, `branch`, `validation`, `harness`, `cwd`, and `max-attempts`."
  [{:keys [input]}]
  (into [(plan-strand input)]
        (map #(task-strand (:feature input) %))
        (:tasks input)))

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
  (str delegation-policy-text "\n\n"
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
;; Named queries
;; ---------------------------------------------------------------------------

(def feature-active-query
  "Parameterized query for all active strands carrying a feature attribute."
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]]})

(def feature-work-query
  "Parameterized query for active task/review strands in a feature."
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]
           [:in [:attr :kind] ["task" "review"]]]})

(def feature-owner-work-query
  "Parameterized query for active task/review strands in a feature owned by one actor."
  {:params [:feature :owner]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]
           [:= [:attr :owner] [:param :owner]]
           [:in [:attr :kind] ["task" "review"]]]})

(def workflow-runs-query
  "Query for active workflow molecule roots (any family)."
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "molecule"]])

(def devflow-runs-query
  "Query for active devflow lifecycle roots."
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "molecule"]
   [:= [:attr "workflow/family"] "devflow"]])

(def feature-run-query
  "Parameterized query for the active strands of one workflow run/feature."
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr "workflow/run-id"] [:param :feature]]]})

(def work-query
  "Query for active actionable work, excluding workflow plumbing and shuttle run records."
  [:and
   [:= :state "active"]
   [:or [:missing [:attr "shuttle/run"]]
    [:not [:= [:attr "shuttle/run"] "true"]]]
   [:or
    [:missing [:attr "workflow/role"]]
    [:not [:in [:attr "workflow/role"] ["molecule" "digest" "procedure"]]]]])

;; ---------------------------------------------------------------------------
;; current-dags: generic active work-DAG projection
;; ---------------------------------------------------------------------------

(defn- active-strands-by-id
  "Return active strands keyed by id."
  [rt]
  (into {}
        (map (juxt :id identity))
        (api/list rt [:= :state "active"] {})))

(defn- internal-active-edges
  "Return edges whose endpoints are both active strands."
  [active-ids edges]
  (->> edges
       (filter #(and (contains? active-ids (:from_strand_id %))
                     (contains? active-ids (:to_strand_id %))))
       (sort-by (juxt :from_strand_id :to_strand_id :edge_type))
       vec))

(defn- parent-root-ids
  "Return active root ids for parent-child work DAGs."
  [active-ids parent-edges]
  (let [parents (set (map :from_strand_id parent-edges))
        children (set (map :to_strand_id parent-edges))]
    (->> (set/difference parents children)
         (filter active-ids)
         sort
         vec)))

(defn- summarize-strand
  "Return the compact strand shape used by repo-local operations."
  [strand]
  (select-keys strand [:id :title :state :attributes]))

(defn- descendants-by-root
  "Return the active parent-of subgraph below one root id."
  [rt active-ids root-id]
  (let [{:keys [strands edges]} (api/subgraph rt [root-id] {:type "parent-of"})
        active-strand-ids (set (keep (fn [{:keys [id state]}]
                                       (when (= "active" state) id))
                                     strands))
        included-ids (conj active-strand-ids root-id)]
    {:root-id root-id
     :strand-ids (->> included-ids (filter active-ids) sort vec)
     :parent-of (internal-active-edges active-ids edges)}))

(defn- dependency-edges-for
  "Return active depends-on edges internal to the included strand ids.

  Subgraph expansion walks outward to external blockers, so edges are filtered
  against the DAG-local id set to keep the projection self-contained: every
  returned edge endpoint appears in the DAG's own strand list."
  [rt strand-ids]
  (let [{:keys [edges]} (api/subgraph rt strand-ids {:type "depends-on"})]
    (internal-active-edges (set strand-ids) edges)))

(defn current-dags-op
  "Return active parent-of work DAGs and their active depends-on edges.

  Usage: `strand op current-dags`. This is an operation rather than only a named
  query because the CLI query surface returns flat strand rows; this handler
  projects roots, hierarchy edges, dependency edges, and compact strand rows
  into one JSON-compatible structure for agents and humans."
  [_ctx]
  (let [rt (runtime-alpha/current-runtime)
        active-by-id (active-strands-by-id rt)
        active-ids (set (keys active-by-id))
        all-active-ids (sort active-ids)
        parent-edges (->> (:edges (api/subgraph rt all-active-ids {:type "parent-of"}))
                          (internal-active-edges active-ids))
        roots (parent-root-ids active-ids parent-edges)
        dags (mapv (fn [root-id]
                     (let [{:keys [strand-ids parent-of]} (descendants-by-root rt active-ids root-id)]
                       {:root (summarize-strand (active-by-id root-id))
                        :strands (mapv (comp summarize-strand active-by-id) strand-ids)
                        :parent_of_edges parent-of
                        :depends_on_edges (dependency-edges-for rt strand-ids)}))
                   roots)]
    {:operation "current-dags"
     :roots roots
     :dags dags}))

;; ---------------------------------------------------------------------------
;; devflow ops: thin CLI wrappers over skein.spools.devflow
;; ---------------------------------------------------------------------------

(defn- require-argv-range!
  "Return argv when its count is within [min-n max-n], otherwise fail with usage."
  [op argv min-n max-n usage]
  (when-not (<= min-n (count argv) max-n)
    (throw (ex-info (str op " expects between " min-n " and " max-n " arguments")
                    {:argv argv :usage usage})))
  argv)

(defn- require-non-blank!
  "Return value when it is a non-blank string, otherwise fail with arg context."
  [arg value]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (ex-info (str (name arg) " must be a non-blank string")
                    {:argument arg :value value})))
  value)

(defn- parse-json-object-arg
  "Parse a CLI JSON-object argument into a keywordized map, failing loudly."
  [op raw]
  (let [value (json/read-str raw :key-fn keyword)]
    (when-not (map? value)
      (throw (ex-info (str op " JSON input must be an object")
                      {:input raw})))
    value))

(defn- split-step-arg
  "Split one optional trailing `step=<id>` selector out of argv.

  `step=` is an explicit token rather than a positional argument so it never
  collides with other optional args (notes, JSON input). Returns
  `[other-args step-id-or-nil]`, failing loudly on duplicate selectors or a
  blank id."
  [op argv usage]
  (let [{steps true others false} (group-by #(str/starts-with? % "step=") argv)]
    (when (> (count steps) 1)
      (throw (ex-info (str op " accepts at most one step=<id> selector")
                      {:argv argv :usage usage})))
    (when-let [step (first steps)]
      (require-non-blank! :step (subs step (count "step="))))
    [(vec others) (some-> (first steps) (subs (count "step=")))]))

(def ^:private worktree-check-values
  #{"required" "already-in-worktree-ok"})

(defn devflow-start-op
  "Start the devflow lifecycle for a feature.

  Usage: `strand op devflow-start <feature> [worktree-check]` where
  worktree-check is `required` (default) or `already-in-worktree-ok`.
  The feature name is the workflow run-id for all other devflow ops."
  [ctx]
  (let [usage "strand op devflow-start <feature> [required|already-in-worktree-ok]"
        [feature worktree-check] (require-argv-range! "devflow-start" (:op/argv ctx) 1 2 usage)
        _ (require-non-blank! :feature feature)
        _ (when (and worktree-check (not (contains? worktree-check-values worktree-check)))
            (throw (ex-info "worktree-check must be required or already-in-worktree-ok"
                            {:value worktree-check :usage usage})))
        opts (if worktree-check {:worktree-check worktree-check} {})]
    (merge {:operation "devflow-start"
            :feature feature}
           (devflow/start! feature opts))))

(defn devflow-next-op
  "Return the ready devflow step views for a feature.

  Usage: `strand op devflow-next <feature>`."
  [ctx]
  (let [usage "strand op devflow-next <feature>"
        [feature] (require-argv-range! "devflow-next" (:op/argv ctx) 1 1 usage)]
    {:operation "devflow-next"
     :feature feature
     :ready (devflow/next-steps feature)}))

(defn devflow-choices-op
  "Return choice explanations for the feature's current checkpoint.

  Usage: `strand op devflow-choices <feature> [step=<id>]`."
  [ctx]
  (let [usage "strand op devflow-choices <feature> [step=<id>]"
        [args step] (split-step-arg "devflow-choices" (:op/argv ctx) usage)
        [feature] (require-argv-range! "devflow-choices" args 1 1 usage)]
    {:operation "devflow-choices"
     :feature feature
     :choices (devflow/choice-details feature (if step {:step step} {}))}))

(defn devflow-choose-op
  "Record a devflow checkpoint choice, optionally with JSON input.

  Usage: `strand op devflow-choose <feature> <choice> [json-input] [step=<id>]`.
  `json-input` must be a JSON object; routed choices merge it into the next
  stage's params (an abort requires `{\"reason\":\"...\"}`)."
  [ctx]
  (let [usage "strand op devflow-choose <feature> <choice> [json-input] [step=<id>]"
        [args step] (split-step-arg "devflow-choose" (:op/argv ctx) usage)
        [feature choice raw-input] (require-argv-range! "devflow-choose" args 2 3 usage)
        input (if raw-input (parse-json-object-arg "devflow-choose" raw-input) {})]
    (merge {:operation "devflow-choose"
            :feature feature
            :choice choice}
           (devflow/choose! feature (keyword choice) input (if step {:step step} {})))))

(defn devflow-complete-op
  "Close the feature's current non-checkpoint devflow step.

  Usage: `strand op devflow-complete <feature> [notes] [step=<id>]`."
  [ctx]
  (let [usage "strand op devflow-complete <feature> [notes] [step=<id>]"
        [args step] (split-step-arg "devflow-complete" (:op/argv ctx) usage)
        [feature notes] (require-argv-range! "devflow-complete" args 1 2 usage)
        opts (cond-> {}
               notes (assoc :notes notes)
               step (assoc :step step))]
    (merge {:operation "devflow-complete"
            :feature feature}
           (devflow/complete! feature opts))))

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

(defn- parse-advance-argv
  "Parse devflow-advance positional args into a feature and workflow advance opts."
  [argv]
  (let [usage "strand op devflow-advance <feature> [choice] [json-input] [notes] [step=<id>]"
        [args step] (split-step-arg "devflow-advance" argv usage)
        [feature & rest-args] (require-argv-range! "devflow-advance" args 1 4 usage)
        _ (require-non-blank! :feature feature)
        [choice raw-input notes]
        (case (count rest-args)
          0 [nil nil nil]
          1 (let [arg (first rest-args)]
              (cond
                (str/starts-with? arg "{") [nil arg nil]
                (checkpoint-ready? feature step) [arg nil nil]
                :else [nil nil arg]))
          2 (let [[a b] rest-args]
              (if (str/starts-with? a "{")
                [nil a b]
                [a b nil]))
          3 rest-args)
        input (when raw-input
                (when-not (str/starts-with? raw-input "{")
                  (throw (ex-info "devflow-advance JSON input must start with {"
                                  {:input raw-input :usage usage})))
                (parse-json-object-arg "devflow-advance" raw-input))]
    [feature (cond-> {}
               step (assoc :step step)
               choice (assoc :choice (keyword choice))
               input (assoc :input input)
               notes (assoc :notes notes))]))

(defn devflow-advance-op
  "Advance the current devflow step or checkpoint for a feature.

  Usage: `strand op devflow-advance <feature> [choice] [json-input] [notes] [step=<id>]`.
  With one bare optional arg, the arg is a choice when the selected ready step is
  a checkpoint and notes otherwise. JSON input must be an object starting with
  `{`; abort choices require a JSON object with a reason key."
  [ctx]
  (let [[feature opts] (parse-advance-argv (:op/argv ctx))]
    (merge {:operation "devflow-advance"
            :feature feature}
           (devflow/advance! feature opts))))

(defn devflow-describe-op
  "Return the devflow cycle or one registered stage description.

  Usage: `strand op devflow-describe [stage-key]`, where stage-key is a stable
  devflow registry key such as `proposal` or `spec-plan`."
  [ctx]
  (let [usage "strand op devflow-describe [stage-key]"
        [stage] (require-argv-range! "devflow-describe" (:op/argv ctx) 0 1 usage)]
    {:operation "devflow-describe"
     :stage stage
     :description (if stage (devflow/describe (keyword stage)) (devflow/describe))}))

(defn devflow-history-op
  "Return ordered devflow run history for a feature.

  Usage: `strand op devflow-history <feature>`."
  [ctx]
  (let [usage "strand op devflow-history <feature>"
        [feature] (require-argv-range! "devflow-history" (:op/argv ctx) 1 1 usage)]
    {:operation "devflow-history"
     :feature feature
     :history (devflow/history feature)}))

(defn devflow-archive-op
  "Archive a finished devflow run into one closed digest strand.

  Usage: `strand op devflow-archive <feature>`. Fails loudly while any devflow
  stage root for the feature is still active."
  [ctx]
  (let [usage "strand op devflow-archive <feature>"
        [feature] (require-argv-range! "devflow-archive" (:op/argv ctx) 1 1 usage)]
    {:operation "devflow-archive"
     :feature feature
     :digest (devflow/archive! feature)}))

(defn devflow-status-op
  "Return the active devflow root, ready steps, and done state for a feature.

  Usage: `strand op devflow-status <feature>`. Fails loudly for a feature that
  never started a devflow run."
  [ctx]
  (let [usage "strand op devflow-status <feature>"
        [feature] (require-argv-range! "devflow-status" (:op/argv ctx) 1 1 usage)]
    {:operation "devflow-status"
     :feature feature
     :roots (mapv summarize-strand (devflow/feature-roots feature))
     :done (workflow/done? feature)
     :ready (devflow/next-steps feature)}))

(defn workflow-runs-op
  "Return active workflow molecule roots, optionally filtered by family.

  Usage: `strand op workflow-runs [family]`."
  [ctx]
  (let [usage "strand op workflow-runs [family]"
        [family] (require-argv-range! "workflow-runs" (:op/argv ctx) 0 1 usage)]
    {:operation "workflow-runs"
     :family family
     :runs (mapv summarize-strand
                 (if family (workflow/active-runs family) (workflow/active-runs)))}))

(defn devflow-conventions-op
  "Return the blessed repo conventions installed by this config."
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
             :purpose "Temporary parent-owned strands burned via a userland attribute."}]
   :ops [{:name "devflow-start" :usage "strand op devflow-start <feature> [required|already-in-worktree-ok]"}
         {:name "devflow-next" :usage "strand op devflow-next <feature>"}
         {:name "devflow-choices" :usage "strand op devflow-choices <feature> [step=<id>]"}
         {:name "devflow-choose" :usage "strand op devflow-choose <feature> <choice> [json-input] [step=<id>]"}
         {:name "devflow-complete" :usage "strand op devflow-complete <feature> [notes] [step=<id>]"}
         {:name "devflow-advance" :usage "strand op devflow-advance <feature> [choice] [json-input] [notes] [step=<id>]"}
         {:name "devflow-describe" :usage "strand op devflow-describe [stage-key]"}
         {:name "devflow-history" :usage "strand op devflow-history <feature>"}
         {:name "devflow-archive" :usage "strand op devflow-archive <feature>"}
         {:name "devflow-status" :usage "strand op devflow-status <feature>"}
         {:name "workflow-runs" :usage "strand op workflow-runs [family]"}
         {:name "current-dags" :usage "strand op current-dags"}
         {:name "agent-delegate" :usage "strand op agent-delegate <task-id> [--harness <name>] [--prompt <extra>] [--cwd <dir>] [--max-attempts <n>] [--spawned-by <run-id>]"}
         {:name "agent-review" :usage "strand op agent review <target-id> [--members n] [--harness a,b] [--synthesize]"}
         {:name "flow-await" :usage "strand op flow-await <workflow-run-id> [--timeout-secs <n>]"}
         {:name "flow-status" :usage "strand op flow-status <workflow-run-id>"}]
   :patterns [{:name "agent-plan"
               :purpose "Lightweight CLI-created plan/task DAG for general agent work outside the devflow lifecycle."}
              {:name "delegate-pipeline"
               :purpose "Sequential chain-loop workflow of subagent gates with optional acceptance checkpoint."}]
   :queries [{:name "feature-active"
              :usage "strand list --query feature-active --param feature=<feature>"}
             {:name "feature-work"
              :usage "strand ready --query feature-work --param feature=<feature>"}
             {:name "feature-owner-work"
              :usage "strand ready --query feature-owner-work --param feature=<feature> --param owner=<owner>"}
             {:name "feature-run"
              :usage "strand list --query feature-run --param feature=<feature>"}
             {:name "workflow-runs"
              :usage "strand list --query workflow-runs"}
             {:name "devflow-runs"
              :usage "strand list --query devflow-runs"}
             {:name "work"
              :usage "strand ready --query work"}]})

;; ---------------------------------------------------------------------------
;; agent-delegate op: repo-local delegated-agent contract over shuttle
;; ---------------------------------------------------------------------------

(defn- config-dir
  "Return the active weaver config directory."
  []
  (or (get-in (runtime-alpha/current-runtime) [:metadata :config-dir])
      (throw (ex-info "agent-delegate requires an active weaver runtime" {}))))

(defn- repo-root-dir
  "Return the repo root for this config workspace."
  []
  (-> (java.io.File. (config-dir))
      .getParentFile
      .getCanonicalPath))

(defn- parse-op-argv
  "Parse op argv into positional args and single-value flags."
  [op argv flag-spec]
  (loop [remaining argv
         positional []
         flags {}]
    (if-let [arg (first remaining)]
      (if (str/starts-with? arg "--")
        (let [kind (or (get flag-spec arg)
                       (throw (ex-info (str op " unknown flag")
                                       {:flag arg :allowed (sort (keys flag-spec))})))
              value (or (second remaining)
                        (throw (ex-info (str op " flag requires a value")
                                        {:flag arg})))]
          (when-not (= :single kind)
            (throw (ex-info (str op " unsupported flag kind")
                            {:flag arg :kind kind})))
          (recur (drop 2 remaining) positional (assoc flags arg value)))
        (recur (rest remaining) (conj positional arg) flags))
      {:positional positional :flags flags})))

(defn- parse-long-flag!
  "Parse a long-valued flag, failing loudly on malformed input."
  [flag value]
  (try
    (Long/parseLong value)
    (catch NumberFormatException _
      (throw (ex-info (str flag " must be an integer")
                      {:flag flag :value value})))))

(defn- active-task!
  "Return task-id's active strand, failing loudly when missing or inactive."
  [rt task-id]
  (let [task (or (api/show rt task-id)
                 (throw (ex-info "agent-delegate task not found" {:task-id task-id})))]
    (when-not (= "active" (:state task))
      (throw (ex-info "agent-delegate task must be active"
                      {:task-id task-id :state (:state task)})))
    task))

(defn- task-attr-string
  "Return task attribute k when it is a non-blank string, fail on malformed values."
  [task k]
  (let [v (get-in task [:attributes k])]
    (cond
      (nil? v) nil
      (non-blank-string? v) v
      :else (throw (ex-info (str "agent-delegate task attribute " (name k) " must be a non-blank string")
                            {:task-id (:id task) :attribute k :value v})))))

(defn- task-attr-long
  "Return task attribute k as a positive long, fail on malformed values."
  [task k]
  (let [v (get-in task [:attributes k])]
    (cond
      (nil? v) nil
      (pos-int? v) v
      (and (integer? v) (pos? v)) (long v)
      :else (throw (ex-info (str "agent-delegate task attribute " (name k) " must be a positive integer")
                            {:task-id (:id task) :attribute k :value v})))))

(defn- validation-text
  "Return the task validation attribute as prompt text."
  [validation]
  (cond
    (nil? validation) nil
    (sequential? validation) (str/join "\n" validation)
    :else (str validation)))

(defn- agent-delegate-prompt
  "Build the repo-local delegated-agent prompt for an active task strand.

  The shuttle engine prepends its pinned-command run preamble; this prompt only
  supplies task context, validation, and the repo delegated-agent contract. A
  task with no body requires explicit extra prompt text, so accidental context-
  free delegations fail loudly."
  [task extra]
  (let [task-id (:id task)
        title (:title task)
        attrs (:attributes task)
        body-text (some-> (:body attrs) str str/trim not-empty)
        validation (:validation attrs)
        extra (some-> extra str/trim not-empty)]
    (when (and (nil? body-text) (nil? extra))
      (throw (ex-info "agent-delegate requires task body or --prompt"
                      {:task-id task-id})))
    (str "You are the delegated implementer for strand `" task-id "` (`" title "`) in the skein-src repo.\n"
         "Read the assigned strand first with `strand show " task-id "` using the pinned command from the shuttle preamble.\n\n"
         (when body-text
           (str "Task body:\n" body-text "\n\n"))
         (when-let [gate (validation-text validation)]
           (str "Validation gate:\n" gate "\n\n"))
         delegation-policy-text "\n"
         (when extra
           (str "\nExtra instructions:\n" extra "\n")))))

(defn agent-delegate-op
  "Spawn a shuttle run to implement an existing active task strand.

  Usage: `strand op agent-delegate <task-id> [--harness <name>] [--prompt <extra>] [--cwd <dir>] [--max-attempts <n>] [--spawned-by <run-id>]`."
  [ctx]
  (let [usage "strand op agent-delegate <task-id> [--harness <name>] [--prompt <extra>] [--cwd <dir>] [--max-attempts <n>] [--spawned-by <run-id>]"
        {:keys [positional flags]}
        (parse-op-argv "agent-delegate" (:op/argv ctx)
                       {"--harness" :single "--prompt" :single "--cwd" :single
                        "--max-attempts" :single "--spawned-by" :single})
        [task-id] (require-argv-range! "agent-delegate" positional 1 1 usage)
        _ (require-non-blank! :task-id task-id)
        rt (runtime-alpha/current-runtime)
        task (active-task! rt task-id)
        harness (or (get flags "--harness") (task-attr-string task :harness) "pi-main")
        cwd (or (get flags "--cwd") (task-attr-string task :cwd) (repo-root-dir))
        max-attempts (or (some->> (get flags "--max-attempts")
                                  (parse-long-flag! "--max-attempts"))
                         (task-attr-long task :max-attempts))]
    (shuttle/run-summary
     (shuttle/spawn-run! (cond-> {:harness harness
                                  :prompt (agent-delegate-prompt task (get flags "--prompt"))
                                  :parent task-id
                                  :cwd cwd
                                  :title (str "Delegate: " (:title task))}
                           max-attempts (assoc :max-attempts max-attempts)
                           (get flags "--spawned-by") (assoc :spawned-by (get flags "--spawned-by")))))))

(defn flow-await-op
  "Block until a workflow run is done or needs coordinator attention.

  Usage: `strand op flow-await <workflow-run-id> [--timeout-secs <n>]`. Uses the
  treadle stall predicate registered at spool install time."
  [ctx]
  (let [usage "strand op flow-await <workflow-run-id> [--timeout-secs <n>]"
        {:keys [positional flags]} (parse-op-argv "flow-await" (:op/argv ctx)
                                                  {"--timeout-secs" :single})
        [run-id] (require-argv-range! "flow-await" positional 1 1 usage)]
    (workflow/await! run-id (cond-> {:stall-predicate :treadle}
                              (get flags "--timeout-secs")
                              (assoc :timeout-secs (parse-long-flag! "--timeout-secs" (get flags "--timeout-secs")))))))

(defn- config-attr
  "Read strand attribute k, tolerating keyword- or string-keyed maps."
  [strand k]
  (let [attrs (:attributes strand)]
    (or (get attrs k) (get attrs (subs (str k) 1)))))

(defn- compact-run
  "Return a compact shuttle/treadle state projection for a run strand."
  [run]
  (when run
    (cond-> {:id (:id run)
             :title (:title run)
             :state (:state run)
             :shuttle/phase (config-attr run :shuttle/phase)}
      (config-attr run :shuttle/harness) (assoc :shuttle/harness (config-attr run :shuttle/harness))
      (config-attr run :shuttle/error) (assoc :shuttle/error (config-attr run :shuttle/error))
      (config-attr run :shuttle/result) (assoc :shuttle/result (config-attr run :shuttle/result))
      (config-attr run :treadle/delivered) (assoc :treadle/delivered (config-attr run :treadle/delivered))
      (config-attr run :treadle/delivery-blocked) (assoc :treadle/delivery-blocked (config-attr run :treadle/delivery-blocked)))))

(defn- compact-gate
  "Return a compact workflow gate projection joined to its treadle run."
  [rt failed-run-ids stalled-gate-ids gate]
  (let [run-id (config-attr gate :treadle/run)
        run (when run-id (api/show rt run-id))
        run-failed? (contains? failed-run-ids run-id)
        spawn-stalled? (contains? stalled-gate-ids (:id gate))]
    (cond-> {:id (:id gate)
             :title (:title gate)
             :state (:state gate)
             :gate (config-attr gate :workflow/gate)
             :treadle/run run-id
             :run (compact-run run)
             :stalled? (boolean (or spawn-stalled? run-failed?))}
      (config-attr gate :treadle/error) (assoc :treadle/error (config-attr gate :treadle/error))
      spawn-stalled? (assoc :stall/reason "spawn-error")
      run-failed? (assoc :stall/reason "agent-failure"))))

(defn- run-subagent-gates
  "Return all subagent gate strands reachable from run-history roots."
  [rt history]
  (->> history
       (mapcat (fn [{:keys [root]}]
                 (:strands (api/subgraph rt [(:id root)] {:type "parent-of"}))))
       (filter #(= "subagent" (config-attr % :workflow/gate)))
       (sort-by :created_at)
       vec))

(defn- flow-status-mermaid
  "Return a dev-only Mermaid chain showing ready, stalled, and closed gates."
  [gates ready-ids]
  (let [marker (fn [{:keys [id state stalled?]}]
                 (cond
                   stalled? "stalled"
                   (contains? ready-ids id) "ready"
                   (= "closed" state) "closed"
                   :else state))
        nodes (map-indexed (fn [idx gate]
                             (str "  G" idx "[\"" (:title gate) " (" (marker gate) ")\"]"))
                           gates)
        links (map (fn [idx] (str "  G" idx " --> G" (inc idx)))
                   (range (dec (count gates))))]
    (str/join "\n" (concat ["flowchart LR"] nodes links))))

(defn flow-status-op
  "Return workflow flow status by joining history, frontier, gates, runs, and stalls.

  Usage: `strand op flow-status <workflow-run-id>`. The JSON payload is read-only
  and suitable for renderers; no workflow, shuttle, or treadle state is mutated."
  [ctx]
  (let [usage "strand op flow-status <workflow-run-id>"
        [run-id] (require-argv-range! "flow-status" (:op/argv ctx) 1 1 usage)
        rt (runtime-alpha/current-runtime)
        history (workflow/run-history run-id)
        frontier (workflow/next-steps run-id)
        done (workflow/done? run-id)
        run-gates (run-subagent-gates rt history)
        ;; scope failure summaries to this run's gates and their delegated
        ;; runs: global stalled/failed records from other workflows must not
        ;; surface in an unrelated run's payload
        run-gate-ids (set (map :id run-gates))
        run-delegated-ids (set (keep #(config-attr % :treadle/run) run-gates))
        stalled-gates (filterv #(contains? run-gate-ids (:id %))
                               (api/list rt [:and [:= :state "active"]
                                             [:= [:attr "workflow/gate"] "subagent"]
                                             [:exists [:attr "treadle/error"]]] {}))
        agent-failures (filterv #(contains? run-delegated-ids (:id %))
                                (api/list rt [:in [:attr "shuttle/phase"] ["failed" "exhausted"]] {}))
        stalled-gate-ids (set (map :id stalled-gates))
        failed-run-ids (set (map :id agent-failures))
        gates (mapv (partial compact-gate rt failed-run-ids stalled-gate-ids) run-gates)
        ready-ids (set (map :id frontier))]
    {:operation "flow-status"
     :run-id run-id
     :history history
     :frontier frontier
     :gates gates
     :stalled-gates (mapv summarize-strand stalled-gates)
     :agent-failures (mapv summarize-strand agent-failures)
     :done done
     :dev/mermaid (flow-status-mermaid gates ready-ids)}))

;; ---------------------------------------------------------------------------
;; install!
;; ---------------------------------------------------------------------------

(defn- register-query-map!
  "Register repo-local named queries and return registration metadata."
  []
  (into {}
        (map (fn [[query-name query-def]]
               [query-name (api/register-query! query-name query-def)]))
        {'feature-active feature-active-query
         'feature-work feature-work-query
         'feature-owner-work feature-owner-work-query
         'feature-run feature-run-query
         'workflow-runs workflow-runs-query
         'devflow-runs devflow-runs-query
         'work work-query}))

(defn- register-harness-aliases!
  "Register repo-local shuttle harness aliases (weaver-lifetime state, so
  startup config re-registers them like queries and ops)."
  []
  [(shuttle/defalias! :pi-main
     {:alias-of :pi
      :extra-args ["--agent" "main"]
      :doc "pi main agent with scout subagents; preferred delegation harness."})
   ;; claude tiers mirror how we use agents: haiku explores, sonnet does
   ;; tests/grunt work, opus builds features and sits on councils
   (shuttle/defalias! :explore
     {:alias-of :claude
      :extra-args ["--model" "haiku"]
      :doc "Claude Haiku: fast read-only exploration and fan-out search."})
   (shuttle/defalias! :grunt
     {:alias-of :claude
      :extra-args ["--model" "sonnet"]
      :doc "Claude Sonnet: tests, mechanical edits, and grunt work."})
   (shuttle/defalias! :build
     {:alias-of :claude
      :extra-args ["--model" "opus"]
      :doc "Claude Opus: feature building, reviews, and council seats."})])

(defn install!
  "Install repo-local Skein runtime configuration."
  []
  {:installed true
   :namespace 'config
   :harnesses (register-harness-aliases!)
   ;; agent review consumes the one authoritative policy text by default
   :review-contract (shuttle/set-default-review-contract! delegation-policy-text)
   :patterns [(patterns/register-pattern!
               'agent-plan
               "Create a feature strand plus task/review children for agent work. Input: {feature,title,body?,tasks:[{key,title,body?,kind?,hitl?,depends_on?,owner?,branch?,validation?,harness?,cwd?,max-attempts?}]}. Use body for delegated work context."
               'config/agent-plan
               ::agent-plan-input)
              (patterns/register-pattern!
               'delegate-pipeline
               "Create a sequential chain-loop workflow of subagent gates. Input: {run_id,tasks:[{id,title,body?,harness?,cwd?,max-attempts?}],harness?,cwd?,accept?}."
               'config/delegate-pipeline
               ::delegate-pipeline-input)]
   :queries (register-query-map!)
   :ops [(api/register-op!
          'current-dags
          "Show active parent-of work DAGs with active depends-on edges"
          'config/current-dags-op)
         (api/register-op!
          'devflow-start
          "Start the devflow lifecycle for a feature"
          'config/devflow-start-op)
         (api/register-op!
          'devflow-next
          "Show ready devflow steps for a feature"
          'config/devflow-next-op)
         (api/register-op!
          'devflow-choices
          "Explain the current devflow checkpoint choices for a feature"
          'config/devflow-choices-op)
         (api/register-op!
          'devflow-choose
          "Record a devflow checkpoint choice for a feature"
          'config/devflow-choose-op)
         (api/register-op!
          'devflow-complete
          "Close the current devflow step for a feature"
          'config/devflow-complete-op)
         (api/register-op!
          'devflow-advance
          "Advance the current devflow step or checkpoint for a feature"
          'config/devflow-advance-op)
         (api/register-op!
          'devflow-describe
          "Describe the devflow cycle or one stage"
          'config/devflow-describe-op)
         (api/register-op!
          'devflow-history
          "Show ordered devflow run history for a feature"
          'config/devflow-history-op)
         (api/register-op!
          'devflow-archive
          "Archive a finished devflow run into one digest strand"
          'config/devflow-archive-op)
         (api/register-op!
          'devflow-status
          "Show devflow root, ready steps, and done state for a feature"
          'config/devflow-status-op)
         (api/register-op!
          'workflow-runs
          "Show active workflow molecule roots, optionally by family"
          'config/workflow-runs-op)
         (api/register-op!
          'devflow-conventions
          "Show repo-local spools, ops, patterns, and queries"
          'config/devflow-conventions-op)
         (api/register-op!
          'agent-delegate
          "Delegate an active task strand to a shuttle agent run"
          'config/agent-delegate-op)
         (api/register-op!
          'flow-await
          "Block until a workflow run needs coordinator attention"
          'config/flow-await-op)
         (api/register-op!
          'flow-status
          "Show workflow flow status for renderer consumption"
          'config/flow-status-op)]})
