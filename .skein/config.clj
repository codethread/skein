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
            [skein.patterns.alpha :as patterns]
            [skein.spools.devflow :as devflow]
            [skein.spools.shuttle :as shuttle]
            [skein.spools.workflow :as workflow]
            [skein.weaver.api :as api]
            [skein.weaver.runtime :as runtime]))

;; ---------------------------------------------------------------------------
;; agent-plan weave pattern: lightweight CLI-created work DAGs
;; ---------------------------------------------------------------------------

(s/def ::non-blank-string (s/and string? (complement str/blank?)))
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
(s/def ::task (s/keys :req-un [::key ::title]
                      :opt-un [::body ::kind ::hitl ::depends_on
                               ::owner ::branch ::validation]))
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
  [feature {:keys [key title body kind hitl owner branch validation depends_on] :as task}]
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
  attributes include `owner`, `branch`, and `validation`."
  [{:keys [input]}]
  (into [(plan-strand input)]
        (map #(task-strand (:feature input) %))
        (:tasks input)))

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
  "Query for active actionable work, excluding workflow plumbing strands."
  [:and
   [:= :state "active"]
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
  (let [rt @runtime/current-runtime
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
             :doc "src/skein/spools/workflow.md"
             :purpose "Workflow engine: definitions compiled to strand molecules with checkpoints, routing, and gates."}
            {:namespace "skein.spools.devflow"
             :doc "src/skein/spools/devflow.md"
             :purpose "Feature lifecycle (intake -> proposal -> spec-plan -> tasks/implementation) keyed by feature name."}
            {:namespace "skein.spools.ephemeral"
             :doc "src/skein/spools/ephemeral.md"
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
         {:name "agent-delegate" :usage "strand op agent-delegate <task-id> [--harness <name>] [--prompt <extra>] [--cwd <dir>] [--max-attempts <n>] [--spawned-by <run-id>]"}]
   :patterns [{:name "agent-plan"
               :purpose "Lightweight CLI-created plan/task DAG for general agent work outside the devflow lifecycle."}]
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
  (or (get-in @runtime/current-runtime [:metadata :config-dir])
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
         "Repo delegated-agent contract:\n"
         "- Record progress with a `progress` attribute on the task strand and notes on the run.\n"
         "- Set `status=implemented` on the task only when validation is green.\n"
         "- Never close the assigned strand.\n"
         "- Never mutate sibling or parent strands unless the body says so.\n"
         "- Do not commit unless the body says so.\n"
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
        rt @runtime/current-runtime
        task (active-task! rt task-id)
        max-attempts (some->> (get flags "--max-attempts")
                              (parse-long-flag! "--max-attempts"))]
    (shuttle/run-summary
     (shuttle/spawn-run! (cond-> {:harness (or (get flags "--harness") "pi-main")
                                  :prompt (agent-delegate-prompt task (get flags "--prompt"))
                                  :parent task-id
                                  :cwd (or (get flags "--cwd") (repo-root-dir))
                                  :title (str "Delegate: " (:title task))}
                           max-attempts (assoc :max-attempts max-attempts)
                           (get flags "--spawned-by") (assoc :spawned-by (get flags "--spawned-by")))))))

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
   :patterns [(patterns/register-pattern!
               'agent-plan
               "Create a feature strand plus task/review children for agent work. Input: {feature,title,body?,tasks:[{key,title,body?,kind?,hitl?,depends_on?,owner?,branch?,validation?}]}. Use body for delegated work context."
               'config/agent-plan
               ::agent-plan-input)]
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
          'config/agent-delegate-op)]})
