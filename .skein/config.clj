(ns config
  "Repo-local Skein runtime configuration for skein-src.

  Thin glue over the shipped spools: `skein.spools.devflow` owns the feature
  lifecycle, `skein.spools.workflow` is the engine, and `skein.spools.agents`
  owns the `strand agent` surface plus the `agent-plan` pattern (all
  activated from init.clj). This config shrinks to genuine workspace tuning:
  CLI-facing root ops wrapping the devflow spool commands, a few named
  queries, the `delegate-pipeline` weave pattern, the `current-dags` graph
  projection, repo-local shuttle harness aliases, chime attention rules, and
  the default review contract."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.patterns.alpha :as patterns]
            [skein.spools.agents :as agents]
            [skein.spools.carder :as carder]
            [skein.spools.chime :as chime]
            [skein.spools.devflow :as devflow]
            [skein.spools.shuttle :as shuttle]
            [skein.spools.workflow :as workflow]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as api]))

;; ---------------------------------------------------------------------------
;; delegate-pipeline weave pattern
;; ---------------------------------------------------------------------------

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

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

  Usage: `strand current-dags`. This is an operation rather than only a named
  query because the CLI query surface returns flat strand rows; this handler
  projects roots, hierarchy edges, dependency edges, and compact strand rows
  into one JSON-compatible structure for agents and humans."
  [_ctx]
  (let [rt (current/runtime)
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

  Usage: `strand devflow-start <feature> [worktree-check]` where
  worktree-check is `required` (default) or `already-in-worktree-ok`.
  The feature name is the workflow run-id for all other devflow ops."
  [ctx]
  (let [usage "strand devflow-start <feature> [required|already-in-worktree-ok]"
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

  Usage: `strand devflow-next <feature>`."
  [ctx]
  (let [usage "strand devflow-next <feature>"
        [feature] (require-argv-range! "devflow-next" (:op/argv ctx) 1 1 usage)]
    {:operation "devflow-next"
     :feature feature
     :ready (devflow/next-steps feature)}))

(defn devflow-choices-op
  "Return choice explanations for the feature's current checkpoint.

  Usage: `strand devflow-choices <feature> [step=<id>]`."
  [ctx]
  (let [usage "strand devflow-choices <feature> [step=<id>]"
        [args step] (split-step-arg "devflow-choices" (:op/argv ctx) usage)
        [feature] (require-argv-range! "devflow-choices" args 1 1 usage)]
    {:operation "devflow-choices"
     :feature feature
     :choices (devflow/choice-details feature (if step {:step step} {}))}))

(defn devflow-choose-op
  "Record a devflow checkpoint choice, optionally with JSON input.

  Usage: `strand devflow-choose <feature> <choice> [json-input] [step=<id>]`.
  `json-input` must be a JSON object; routed choices merge it into the next
  stage's params (an abort requires `{\"reason\":\"...\"}`)."
  [ctx]
  (let [usage "strand devflow-choose <feature> <choice> [json-input] [step=<id>]"
        [args step] (split-step-arg "devflow-choose" (:op/argv ctx) usage)
        [feature choice raw-input] (require-argv-range! "devflow-choose" args 2 3 usage)
        input (if raw-input (parse-json-object-arg "devflow-choose" raw-input) {})]
    (merge {:operation "devflow-choose"
            :feature feature
            :choice choice}
           (devflow/choose! feature (keyword choice) input (if step {:step step} {})))))

(defn devflow-complete-op
  "Close the feature's current non-checkpoint devflow step.

  Usage: `strand devflow-complete <feature> [notes] [step=<id>]`."
  [ctx]
  (let [usage "strand devflow-complete <feature> [notes] [step=<id>]"
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
  (let [usage "strand devflow-advance <feature> [choice] [json-input] [notes] [step=<id>]"
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

  Usage: `strand devflow-advance <feature> [choice] [json-input] [notes] [step=<id>]`.
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

  Usage: `strand devflow-describe [stage-key]`, where stage-key is a stable
  devflow registry key such as `proposal` or `spec-plan`."
  [ctx]
  (let [usage "strand devflow-describe [stage-key]"
        [stage] (require-argv-range! "devflow-describe" (:op/argv ctx) 0 1 usage)]
    {:operation "devflow-describe"
     :stage stage
     :description (if stage (devflow/describe (keyword stage)) (devflow/describe))}))

(defn devflow-history-op
  "Return ordered devflow run history for a feature.

  Usage: `strand devflow-history <feature>`."
  [ctx]
  (let [usage "strand devflow-history <feature>"
        [feature] (require-argv-range! "devflow-history" (:op/argv ctx) 1 1 usage)]
    {:operation "devflow-history"
     :feature feature
     :history (devflow/history feature)}))

(defn devflow-archive-op
  "Archive a finished devflow run into one closed digest strand.

  Usage: `strand devflow-archive <feature>`. Fails loudly while any devflow
  stage root for the feature is still active."
  [ctx]
  (let [usage "strand devflow-archive <feature>"
        [feature] (require-argv-range! "devflow-archive" (:op/argv ctx) 1 1 usage)]
    {:operation "devflow-archive"
     :feature feature
     :digest (devflow/archive! feature)}))

(defn devflow-status-op
  "Return the active devflow root, ready steps, and done state for a feature.

  Usage: `strand devflow-status <feature>`. Fails loudly for a feature that
  never started a devflow run."
  [ctx]
  (let [usage "strand devflow-status <feature>"
        [feature] (require-argv-range! "devflow-status" (:op/argv ctx) 1 1 usage)]
    {:operation "devflow-status"
     :feature feature
     :roots (mapv summarize-strand (devflow/feature-roots feature))
     :done (workflow/done? feature)
     :ready (devflow/next-steps feature)}))

(defn workflow-runs-op
  "Return active workflow molecule roots, optionally filtered by family.

  Usage: `strand workflow-runs [family]`."
  [ctx]
  (let [usage "strand workflow-runs [family]"
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
             :purpose "Temporary parent-owned strands burned via a userland attribute."}
            {:namespace "skein.spools.kanban"
             :doc "spools/kanban.md"
             :purpose "User-facing kanban board: feature/epic cards with refinement/pending/claimed lanes, notes, and handovers."}]
   :ops [{:name "kanban" :usage "strand kanban <about|add|board|card|next|promote|claim|note|finish> ..."}
         {:name "branches" :usage "strand branches [branch]"}
         {:name "devflow-start" :usage "strand devflow-start <feature> [required|already-in-worktree-ok]"}
         {:name "devflow-next" :usage "strand devflow-next <feature>"}
         {:name "devflow-choices" :usage "strand devflow-choices <feature> [step=<id>]"}
         {:name "devflow-choose" :usage "strand devflow-choose <feature> <choice> [json-input] [step=<id>]"}
         {:name "devflow-complete" :usage "strand devflow-complete <feature> [notes] [step=<id>]"}
         {:name "devflow-advance" :usage "strand devflow-advance <feature> [choice] [json-input] [notes] [step=<id>]"}
         {:name "devflow-describe" :usage "strand devflow-describe [stage-key]"}
         {:name "devflow-history" :usage "strand devflow-history <feature>"}
         {:name "devflow-archive" :usage "strand devflow-archive <feature>"}
         {:name "devflow-status" :usage "strand devflow-status <feature>"}
         {:name "workflow-runs" :usage "strand workflow-runs [family]"}
         {:name "current-dags" :usage "strand current-dags"}
         {:name "carder-report" :usage "strand carder-report [--days <n>] [--include-plumbing true|false]"}
         {:name "agent" :usage "strand agent about — the delegation manual (spawn/delegate/retry/status/ps/await/logs/kill/note/notes/council/review); shipped by skein.spools.agents"}
         {:name "flow-await" :usage "strand flow-await <workflow-run-id> [--timeout-secs <n>]"}
         {:name "flow-status" :usage "strand flow-status <workflow-run-id>"}]
   :patterns [{:name "agent-plan"
               :purpose "Create a feature strand plus task/review children for agent work; now shipped by skein.spools.agents, not this config."}
              {:name "delegate-pipeline"
               :purpose "Sequential chain-loop workflow of subagent gates with optional acceptance checkpoint."}]
   :queries [{:name "kanban-cards"
              :usage "strand list --query kanban-cards"}
             {:name "kanban-unstarted"
              :usage "strand ready --query kanban-unstarted"}
             {:name "feature-active"
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
;; op argv helpers shared by the ops below
;; ---------------------------------------------------------------------------

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

(defn- parse-boolean-flag!
  "Parse a boolean-valued flag, failing loudly on malformed input."
  [flag value]
  (case value
    "true" true
    "false" false
    (throw (ex-info (str flag " must be true or false")
                    {:flag flag :value value}))))

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

(defn carder-report-op
  "Return the carder graph hygiene report for active work.

  Usage: `strand carder-report [--days <n>] [--include-plumbing true|false]`.
  This is a read-only wrapper around `skein.spools.carder/report` for checking
  stale active strands, orphaned strands, and work blocked by failed agent runs.
  Repo-local expected kanban board roots are suppressed from the orphan list."
  [ctx]
  (let [{:keys [flags]} (parse-op-argv "carder-report" (:op/argv ctx)
                                       {"--days" :single
                                        "--include-plumbing" :single})]
    (suppress-expected-carder-orphans
     (carder/report (cond-> {}
                      (get flags "--days")
                      (assoc :days (parse-long-flag! "--days" (get flags "--days")))
                      (get flags "--include-plumbing")
                      (assoc :include-plumbing? (parse-boolean-flag! "--include-plumbing"
                                                                     (get flags "--include-plumbing"))))))))

(defn flow-await-op
  "Block until a workflow run is done or needs coordinator attention.

  Usage: `strand flow-await <workflow-run-id> [--timeout-secs <n>]`. Uses the
  treadle stall predicate registered at spool install time."
  [ctx]
  (let [usage "strand flow-await <workflow-run-id> [--timeout-secs <n>]"
        {:keys [positional flags]} (parse-op-argv "flow-await" (:op/argv ctx)
                                                  {"--timeout-secs" :single})
        [run-id] (require-argv-range! "flow-await" positional 1 1 usage)]
    (workflow/await! run-id (cond-> {:stall-predicate :treadle}
                              (get flags "--timeout-secs")
                              (assoc :timeout-secs (parse-long-flag! "--timeout-secs" (get flags "--timeout-secs")))))))

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

  Usage: `strand flow-status <workflow-run-id>`. The JSON payload is read-only
  and suitable for renderers; no workflow, shuttle, or treadle state is mutated."
  [ctx]
  (let [usage "strand flow-status <workflow-run-id>"
        [run-id] (require-argv-range! "flow-status" (:op/argv ctx) 1 1 usage)
        rt (current/runtime)
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
;; branches: branch-visibility projection over work-root strands
;; ---------------------------------------------------------------------------

(defn- branch-root-view
  "Return one branch work root with its active descendants and ready frontier."
  [rt active-ids ready-ids root]
  (let [{:keys [strands]} (api/subgraph rt [(:id root)] {:type "parent-of"})
        descendants (->> strands
                         (filter #(and (contains? active-ids (:id %))
                                       (not= (:id root) (:id %))))
                         (sort-by :id)
                         (mapv summarize-strand))]
    {:root (summarize-strand root)
     :active_descendants descendants
     :ready (filterv #(contains? ready-ids (:id %))
                     (into [(summarize-strand root)] descendants))}))

(defn branches-op
  "Group active branch-stamped work roots into a per-branch progress view.

  Usage: `strand branches [branch]`. The repo convention stamps `branch`
  (plus `owner`/`worktree`) on exactly one active work root per branch —
  `kanban claim` does this for cards — and hangs all execution
  strands beneath that root with parent-of edges. This op answers \"what is
  going on inside each feature branch\" by joining those roots to their
  active descendants and the ready frontier (`work` query, so workflow
  plumbing and shuttle run records stay hidden)."
  [ctx]
  (let [usage "strand branches [branch]"
        [branch] (require-argv-range! "branches" (:op/argv ctx) 0 1 usage)
        rt (current/runtime)
        active-by-id (active-strands-by-id rt)
        active-ids (set (keys active-by-id))
        parent-edges (->> (:edges (api/subgraph rt (sort active-ids) {:type "parent-of"}))
                          (internal-active-edges active-ids))
        child-ids (set (map :to_strand_id parent-edges))
        roots (->> (vals active-by-id)
                   (filter #(config-attr % :branch))
                   (remove #(contains? child-ids (:id %)))
                   (filter #(or (nil? branch) (= branch (config-attr % :branch)))))
        ready-ids (set (map :id (api/ready rt work-query {})))
        branches (->> roots
                      (group-by #(config-attr % :branch))
                      (sort-by key)
                      (mapv (fn [[branch-name branch-roots]]
                              {:branch branch-name
                               :roots (mapv (partial branch-root-view rt active-ids ready-ids)
                                            (sort-by :id branch-roots))})))]
    (when (and branch (empty? branches))
      (throw (ex-info "no active work root is stamped with this branch"
                      {:branch branch :usage usage})))
    {:operation "branches"
     :branches branches}))

;; ---------------------------------------------------------------------------
;; chime attention rules: what this repo's devflow considers worth a human's
;; attention. The chime engine is vocabulary-agnostic; these rules own the
;; workflow/shuttle/treadle knowledge. Developers bind how they are notified
;; in gitignored init.local.clj with (chime/set-notifier! {:argv [...]}).
;; ---------------------------------------------------------------------------

(defn hitl-checkpoint-ready-rule
  "Notify when a human-in-the-loop workflow checkpoint is ready to decide."
  [{:keys [strand ready-ids]}]
  (let [hitl (config-attr strand :workflow/hitl)]
    (when (and (= "active" (:state strand))
               (= "checkpoint" (config-attr strand :workflow/role))
               (or (= true hitl) (= "true" hitl))
               (contains? ready-ids (:id strand)))
      {:title (str "HITL checkpoint ready: " (:title strand))
       :body (str "Checkpoint " (:id strand) " is ready for human attention.")})))

(defn agent-failure-rule
  "Notify when a shuttle run has failed or exhausted its attempts."
  [{:keys [strand]}]
  (let [phase (config-attr strand :shuttle/phase)]
    (when (contains? #{"failed" "exhausted"} phase)
      {:title (str "Agent run " phase ": " (:title strand))
       :body (str "Strand " (:id strand) " entered shuttle/phase " phase
                  (when-let [error (config-attr strand :shuttle/error)]
                    (str "\n\n" error)))})))

(defn treadle-error-rule
  "Notify when a workflow gate is stamped with a treadle error."
  [{:keys [strand]}]
  (when-let [error (config-attr strand :treadle/error)]
    {:title (str "Treadle error: " (:title strand))
     :body (str "Strand " (:id strand) " has treadle/error:\n\n" error)}))

(defn kanban-started-rule
  "Notify when a kanban card is claimed and work starts."
  [{:keys [strand]}]
  (when (and (= "active" (:state strand))
             (= "true" (config-attr strand :kanban/card))
             (= "claimed" (config-attr strand :kanban/status)))
    {:title (str "Kanban started: " (:title strand))
     :body (str "Kanban card " (:id strand) " has been claimed and work has started.")}))

(defn kanban-completed-rule
  "Notify when a kanban card reaches the explicit done outcome."
  [{:keys [strand]}]
  (when (and (= "closed" (:state strand))
             (= "true" (config-attr strand :kanban/card))
             (= "done" (config-attr strand :kanban/status)))
    {:title (str "Kanban done: " (:title strand))
     :body (str "Kanban card " (:id strand) " completed fully.")}))

(defn- failed-blocker?
  "Return true when strand is an active failed/exhausted blocker."
  [strand]
  (and (= "active" (:state strand))
       (contains? #{"failed" "exhausted"} (config-attr strand :shuttle/phase))))

(defn- active-descendants
  "Return active strands below a kanban card over parent-of, including the card."
  [rt root-id]
  (->> (:strands (api/subgraph rt [root-id] {:type "parent-of"}))
       (filter #(= "active" (:state %)))
       vec))

(defn- blocking-failures
  "Return failed/exhausted depends-on blockers for active strands under root."
  [rt root-id]
  (let [work (active-descendants rt root-id)
        work-ids (set (map :id work))
        blocker-ids (->> (:edges (api/subgraph rt (vec work-ids) {:type "depends-on"}))
                         (filter #(contains? work-ids (:from_strand_id %)))
                         (map :to_strand_id)
                         distinct)]
    (->> blocker-ids
         (map #(api/show rt %))
         (filter failed-blocker?)
         (sort-by :id)
         vec)))

(defn kanban-blocked-rule
  "Notify when active card work is blocked by failed/exhausted delegated work."
  [{:keys [strand]}]
  (when (and (= "active" (:state strand))
             (= "true" (config-attr strand :kanban/card)))
    (let [rt (current/runtime)
          blockers (blocking-failures rt (:id strand))]
      (when (seq blockers)
        {:title (str "Kanban blocked: " (:title strand))
         :body (str "Kanban card " (:id strand)
                    " is blocked by failed/exhausted work:\n"
                    (str/join "\n" (map #(str "- " (:id %) " " (:title %)
                                               " (" (config-attr % :shuttle/phase) ")")
                                         blockers)))}))))

(defn- register-chime-rules!
  "Register the repo's attention rules with the chime engine."
  []
  [(chime/defrule! :hitl-checkpoint-ready 'config/hitl-checkpoint-ready-rule)
   (chime/defrule! :agent-failure 'config/agent-failure-rule)
   (chime/defrule! :treadle-error 'config/treadle-error-rule)
   (chime/defrule! :kanban-started 'config/kanban-started-rule)
   (chime/defrule! :kanban-completed 'config/kanban-completed-rule)
   (chime/defrule! :kanban-blocked 'config/kanban-blocked-rule)])

;; ---------------------------------------------------------------------------
;; install!
;; ---------------------------------------------------------------------------

(defn- register-query-map!
  "Register repo-local named queries and return registration metadata."
  [rt]
  (into {}
        (map (fn [[query-name query-def]]
               [query-name (api/register-query! rt query-name query-def)]))
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
   ;; codex exec prints only the final message on stdout (activity log goes to
   ;; stderr), so :raw parses cleanly. Sessions persist to disk so `codex exec
   ;; resume <session-id>` can continue them; :resume splices that subcommand
   ;; ahead of the prompt (the global flags before it propagate into resume).
   ;; :raw does not capture a session id, so resume stays declared-but-inert
   ;; until a codex-json parse lands — persistence is never required
   ;; (PLAN-Pnl-001.R1/NG4), and a resume attempt fails loudly on the missing
   ;; id rather than starting cold. The bypass flag mirrors the shipped claude
   ;; harness: runs must reach the weaver socket outside any codex sandbox —
   ;; redefine with --sandbox workspace-write to tighten. Env inheritance must
   ;; be explicit: codex's default shell_environment_policy strips the PATH
   ;; entries that carry the strand/mill CLIs, leaving workers unable to reach
   ;; the coordination surface.
   (shuttle/defharness! :codex
     {:argv ["codex" "exec" "--skip-git-repo-check" "--color" "never"
             "--dangerously-bypass-approvals-and-sandbox"
             "-c" "shell_environment_policy.inherit=all"]
      :parse :raw
      :resume ["resume" :shuttle/session-id]
      :doc "Codex CLI (gpt-5.5) headless; final message on stdout."})
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
      :doc "Claude Opus: feature building, reviews, and council seats."})
   ;; GPT seats for cross-vendor validation. Routing policy: build (opus) is
   ;; favoured for anything prose/docs-heavy, but never signs off its own
   ;; work — sign-off review of opus-authored output always includes a GPT
   ;; harness. review-gpt is the standing reviewer seat; hard-gpt is for the
   ;; occasional difficult implementation task that wants a non-Claude model.
   (shuttle/defalias! :review-gpt
     {:alias-of :pi
      :extra-args ["--provider" "openai" "--model" "gpt-5.4" "--thinking" "high"]
      :doc "GPT-5.4 high reasoning via pi: independent review/validation seat; required sign-off reviewer for opus-authored docs work."})
   (shuttle/defalias! :hard-gpt
     {:alias-of :codex
      :extra-args ["-m" "gpt-5.5" "-c" "model_reasoning_effort=medium"]
      :doc "GPT-5.5 medium reasoning via codex exec: occasional difficult implementation tasks needing a second frontier model."})])

(defn install!
  "Install repo-local Skein runtime configuration."
  []
  (let [runtime (current/runtime)]
    {:installed true
   :namespace 'config
   :harnesses (register-harness-aliases!)
   ;; agent review consumes the one authoritative policy text by default; the
   ;; text itself now ships from skein.spools.agents, set-default-review-contract!
   ;; still lives on the shuttle engine
   :review-contract (shuttle/set-default-review-contract! agents/review-contract)
   :chime-rules (register-chime-rules!)
   :patterns [(patterns/register-pattern!
               runtime
               'delegate-pipeline
               "Create a sequential chain-loop workflow of subagent gates. Input: {run_id,tasks:[{id,title,body?,harness?,cwd?,max-attempts?}],harness?,cwd?,accept?}."
               'config/delegate-pipeline
               ::delegate-pipeline-input)]
   :queries (register-query-map! runtime)
   :ops [(api/register-op!
          runtime
          'current-dags
          "Show active parent-of work DAGs with active depends-on edges"
          'config/current-dags-op)
         (api/register-op!
          runtime
          'branches
          "Show active branch-stamped work roots grouped by branch"
          'config/branches-op)
         (api/register-op!
          runtime
          'carder-report
          "Show read-only carder graph hygiene report"
          'config/carder-report-op)
         (api/register-op!
          runtime
          'devflow-start
          "Start the devflow lifecycle for a feature"
          'config/devflow-start-op)
         (api/register-op!
          runtime
          'devflow-next
          "Show ready devflow steps for a feature"
          'config/devflow-next-op)
         (api/register-op!
          runtime
          'devflow-choices
          "Explain the current devflow checkpoint choices for a feature"
          'config/devflow-choices-op)
         (api/register-op!
          runtime
          'devflow-choose
          "Record a devflow checkpoint choice for a feature"
          'config/devflow-choose-op)
         (api/register-op!
          runtime
          'devflow-complete
          "Close the current devflow step for a feature"
          'config/devflow-complete-op)
         (api/register-op!
          runtime
          'devflow-advance
          "Advance the current devflow step or checkpoint for a feature"
          'config/devflow-advance-op)
         (api/register-op!
          runtime
          'devflow-describe
          "Describe the devflow cycle or one stage"
          'config/devflow-describe-op)
         (api/register-op!
          runtime
          'devflow-history
          "Show ordered devflow run history for a feature"
          'config/devflow-history-op)
         (api/register-op!
          runtime
          'devflow-archive
          "Archive a finished devflow run into one digest strand"
          'config/devflow-archive-op)
         (api/register-op!
          runtime
          'devflow-status
          "Show devflow root, ready steps, and done state for a feature"
          'config/devflow-status-op)
         (api/register-op!
          runtime
          'workflow-runs
          "Show active workflow molecule roots, optionally by family"
          'config/workflow-runs-op)
         (api/register-op!
          runtime
          'devflow-conventions
          "Show repo-local spools, ops, patterns, and queries"
          'config/devflow-conventions-op)
         (api/register-op!
          runtime
          'flow-await
          {:doc "Block until a workflow run needs coordinator attention"
           :deadline-class :unbounded}
          'config/flow-await-op)
         (api/register-op!
          runtime
          'flow-status
          "Show workflow flow status for renderer consumption"
          'config/flow-status-op)]}))
