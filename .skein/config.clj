(ns config
  "Repo-local Skein runtime configuration for skein-src.

  Thin glue over the shipped spools: `skein.spools.devflow` owns the feature
  lifecycle, `skein.spools.workflow` is the engine, `skein.spools.agents` owns
  the `strand agent` surface plus the `agent-plan` pattern, and
  `skein.spools.loom` owns the read-only work-graph projections (all activated
  from init.clj). This config holds only repo policy and wiring: CLI-facing
  root ops wrapping the devflow and loom projections, a few named queries, the
  `delegate-pipeline` weave pattern, repo-local shuttle harness aliases, this
  repo's chime attention rules, and the default review contract. The generic
  graph-projection logic behind `current-dags`, `branches`, and `flow-status`
  lives in `skein.spools.loom`; the ops here supply repo policy — which
  attribute names a branch, which query feeds the ready frontier — and register
  the ops."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.patterns.alpha :as patterns]
            [skein.spools.agents :as agents]
            [skein.spools.carder :as carder]
            [skein.spools.chime :as chime]
            [skein.spools.cron :as cron]
            [skein.spools.devflow :as devflow]
            [skein.spools.loom :as loom]
            [skein.spools.shuttle :as shuttle]
            [skein.spools.workflow :as workflow]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as api])
  (:import [java.time Instant]
           [java.util Random]))

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
;; current-dags: active work-DAG projection (loom)
;; ---------------------------------------------------------------------------

(defn current-dags-op
  "Return active parent-of work DAGs and their active depends-on edges.

  This is an operation rather than only a named query because the CLI query
  surface returns flat strand rows; `skein.spools.loom/work-dags` projects
  roots, hierarchy edges, dependency edges, and compact strand rows into one
  JSON-compatible structure for agents and humans."
  [_ctx]
  (merge {:operation "current-dags"}
         (loom/work-dags (current/runtime))))

;; ---------------------------------------------------------------------------
;; devflow ops: thin CLI wrappers over skein.spools.devflow
;; ---------------------------------------------------------------------------

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

;; The blessed arg-spec parser (skein.api.cli.alpha) binds positionals strictly
;; by order, so it cannot express the position-independent `step=<id>` selector
;; these ops accept, nor disambiguate the optional json-input/notes slots it can
;; sit among (docs/skein.md "Discovery tiers"). We therefore declare the fixed
;; positionals in each arg-spec — which drives generated `strand help <op>` — and
;; collect the optional tail into one variadic positional, then split `step=<id>`
;; out of that tail here. Fail-loud errors reference `strand help <op>` in their
;; data instead of a hand-written usage string.
(defn- pop-step-selector
  "Split one optional `step=<id>` selector out of variadic tail tokens.

  `step=` is a whole token rather than a positional slot so it never collides
  with the other optional args (notes, JSON input) sharing the tail. Returns
  `[other-tokens step-id-or-nil]`, failing loudly on a duplicate selector or a
  blank id."
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

(defn devflow-start-op
  "Start the devflow lifecycle for a feature.

  The feature name is the workflow run-id for all other devflow ops;
  worktree-check is `required` (default) or `already-in-worktree-ok`."
  [ctx]
  (let [{:keys [feature worktree-check]} (:op/args ctx)]
    (require-non-blank! :feature feature)
    (when (and worktree-check (not (contains? worktree-check-values worktree-check)))
      (throw (ex-info "worktree-check must be required or already-in-worktree-ok"
                      {:value worktree-check :help "strand help devflow-start"})))
    (merge {:operation "devflow-start"
            :feature feature}
           (devflow/start! feature (if worktree-check {:worktree-check worktree-check} {})))))

(defn devflow-next-op
  "Return the ready devflow step views for a feature."
  [ctx]
  (let [{:keys [feature]} (:op/args ctx)]
    {:operation "devflow-next"
     :feature feature
     :ready (devflow/next-steps feature)}))

(defn devflow-choices-op
  "Return choice explanations for the feature's current checkpoint."
  [ctx]
  (let [{:keys [feature step-selector]} (:op/args ctx)
        [extra step] (pop-step-selector "devflow-choices" step-selector)]
    (when (seq extra)
      (throw (ex-info "devflow-choices accepts only a feature and an optional step=<id> selector"
                      {:op "devflow-choices" :help "strand help devflow-choices" :extra extra})))
    {:operation "devflow-choices"
     :feature feature
     :choices (devflow/choice-details feature (if step {:step step} {}))}))

(defn devflow-choose-op
  "Record a devflow checkpoint choice, optionally with JSON input.

  `json-input` must be a JSON object; routed choices merge it into the next
  stage's params (an abort requires `{\"reason\":\"...\"}`)."
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

(defn devflow-complete-op
  "Close the feature's current non-checkpoint devflow step."
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

(defn devflow-advance-op
  "Advance the current devflow step or checkpoint for a feature."
  [ctx]
  (let [{:keys [feature tail]} (:op/args ctx)]
    (require-non-blank! :feature feature)
    (merge {:operation "devflow-advance"
            :feature feature}
           (devflow/advance! feature (parse-advance-tail feature tail)))))

(defn devflow-describe-op
  "Return the devflow cycle or one registered stage description.

  stage-key is a stable devflow registry key such as `proposal` or `spec-plan`."
  [ctx]
  (let [{:keys [stage-key]} (:op/args ctx)]
    {:operation "devflow-describe"
     :stage stage-key
     :description (if stage-key (devflow/describe (keyword stage-key)) (devflow/describe))}))

(defn devflow-history-op
  "Return ordered devflow run history for a feature."
  [ctx]
  (let [{:keys [feature]} (:op/args ctx)]
    {:operation "devflow-history"
     :feature feature
     :history (devflow/history feature)}))

(defn devflow-archive-op
  "Archive a finished devflow run into one closed digest strand.

  Fails loudly while any devflow stage root for the feature is still active."
  [ctx]
  (let [{:keys [feature]} (:op/args ctx)]
    {:operation "devflow-archive"
     :feature feature
     :digest (devflow/archive! feature)}))

(defn devflow-status-op
  "Return the active devflow root, ready steps, and done state for a feature.

  Fails loudly for a feature that never started a devflow run."
  [ctx]
  (let [{:keys [feature]} (:op/args ctx)]
    {:operation "devflow-status"
     :feature feature
     :roots (mapv loom/summarize (devflow/feature-roots feature))
     :done (workflow/done? feature)
     :ready (devflow/next-steps feature)}))

(defn workflow-runs-op
  "Return active workflow molecule roots, optionally filtered by family."
  [ctx]
  (let [{:keys [family]} (:op/args ctx)]
    {:operation "workflow-runs"
     :family family
     :runs (mapv loom/summarize
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
   :ops [{:name "kanban" :help "strand help kanban" :manual "strand kanban about"}
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
         {:name "agent" :help "strand help agent" :manual "strand agent about"}
         {:name "flow-await" :help "strand help flow-await"}
         {:name "flow-status" :help "strand help flow-status"}
         {:name "hitl" :help "strand help hitl" :purpose "Interactive user+agent session with a self-terminating tracking strand."}
         {:name "land" :help "strand help land" :manual "strand land about" :purpose "Coordinator-only landing workflow: push+draft-PR, green CI, roster sign-off, squash-merge to local main with full verification, then green main CI."}]
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

  This is a read-only wrapper around `skein.spools.carder/report` for checking
  stale active strands, orphaned strands, and work blocked by failed agent runs.
  Repo-local expected kanban board roots are suppressed from the orphan list."
  [ctx]
  (let [{:keys [days include-plumbing]} (:op/args ctx)]
    (suppress-expected-carder-orphans
     (carder/report (cond-> {}
                      days (assoc :days days)
                      (some? include-plumbing) (assoc :include-plumbing? include-plumbing))))))

(defn flow-await-op
  "Block until a workflow run is done or needs coordinator attention.

  Usage: `strand flow-await <workflow-run-id> [--timeout-secs <n>]`. Workflow
  executor registrations decide which ready gates can stay waiting silently and
  which stalled gates need coordinator attention."
  [ctx]
  (let [{:keys [workflow-run-id timeout-secs]} (:op/args ctx)]
    (workflow/await! workflow-run-id (cond-> {}
                                       timeout-secs (assoc :timeout-secs timeout-secs)))))

(defn flow-status-op
  "Return workflow flow status by joining history, frontier, gates, runs, and stalls.

  The JSON payload is read-only and suitable for renderers; no workflow,
  shuttle, or treadle state is mutated. The join and Mermaid gate chain live in
  `skein.spools.loom/flow-status`; this op only names the run and stamps the
  operation."
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

(defn hitl-op
  "Open an interactive HITL working session for a human + agent pair.

  Usage: `strand hitl <parent-id> <title> --context <text> [--cwd <dir>]
  [--harness <name>] [--backend <name>]`. Creates a tracking strand under
  `parent-id` (a kanban card, plan, or work root), composes the required
  `--context` brief with the tracking contract, and spawns an interactive
  multiplexer run serving the tracking strand (default harness `hitl-build`,
  backend `tmux`). The session ends when the session agent closes the tracking
  strand after writing its outcome; the coordinator then reads the tracking
  strand for notes and outcome. Returns the tracking id and pending run
  summary — `strand agent ps` carries the session name and attach command once
  the session is live."
  [ctx]
  (let [{:keys [parent-id title context cwd harness backend]} (:op/args ctx)
        rt (current/runtime)]
    (require-non-blank! :context context)
    (when-not (api/show rt parent-id)
      (throw (ex-info "hitl parent strand not found" {:parent parent-id})))
    (let [tracking (api/add rt {:title (str "HITL: " title)
                                :attributes {"hitl" "true"
                                             "body" (str "Tracking strand for the interactive HITL session \"" title "\"."
                                                         " The session agent appends closed note children for decisions,"
                                                         " writes a final outcome attr, then closes this strand to end its"
                                                         " run and tear down the session.")}})]
      (api/update rt parent-id {:edges [{:type "parent-of" :to (:id tracking)}]})
      (let [run (shuttle/spawn-run! {:harness (or harness "hitl-build")
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
                                (fn [{:keys [branch]}]
                                  (str "Delete the remote branch (`git push origin --delete " branch "`), which also"
                                       " closes the draft PR. Remove the worktree and local branch"
                                       " (`wktree remove --branch " branch " --force`; force is expected after the"
                                       " squash-merge). Finish or annotate the kanban card"
                                       " (`strand kanban finish <card> --outcome done` when a card is set). Then close"
                                       " this land run's root to complete it."))})))

(defn- register-land-workflows!
  "Register the land run's routing targets (the abort continuation) with the
  engine's weaver-lifetime workflow registry, so the sign-off `abort` choice's
  `:next :land-abort` resolves at `choose!` time (re-registered on reload like
  named queries and ops)."
  []
  {:land-abort (workflow/register-workflow! :land-abort 'config/land-abort-workflow)})

(defn- land-start!
  "Pour and start the land run for a feature branch; run-id is the feature slug."
  [feature {:keys [branch worktree card]}]
  (require-non-blank! :feature feature)
  (require-non-blank! :branch branch)
  (require-non-blank! :worktree worktree)
  (let [context (cond-> {:feature feature :branch branch :worktree worktree}
                  (non-blank-string? card) (assoc :card card))]
    (workflow/start! feature
                     (land-workflow context)
                     context
                     {:family "land"
                      :definition 'config/land-workflow
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
      "next" (do (require-non-blank! :feature feature)
                 {:operation "land-next"
                  :feature feature
                  :ready (workflow/next-steps feature)})
      "complete" (let [[rest-tokens step] (pop-step-selector "land complete" tail)
                       notes (first rest-tokens)]
                   (require-non-blank! :feature feature)
                   (when (> (count rest-tokens) 1)
                     (throw (ex-info "land complete accepts at most one notes argument"
                                     {:op "land complete" :help "strand help land" :extra (vec (rest rest-tokens))})))
                   (merge {:operation "land-complete" :feature feature}
                          (workflow/complete! feature (cond-> {}
                                                        notes (assoc :notes notes)
                                                        step (assoc :step step)))))
      "choose" (let [[rest-tokens step] (pop-step-selector "land choose" tail)
                     raw-input (first rest-tokens)]
                 (require-non-blank! :feature feature)
                 (when (> (count rest-tokens) 1)
                   (throw (ex-info "land choose accepts at most one JSON-input argument"
                                   {:op "land choose" :help "strand help land" :extra (vec (rest rest-tokens))})))
                 (let [input (if raw-input (parse-json-object-arg "land choose" raw-input) {})]
                   (merge {:operation "land-choose" :feature feature :choice choice}
                          (workflow/choose! feature (keyword choice) input (if step {:step step} {})))))
      "status" (do (require-non-blank! :feature feature)
                   (let [root (workflow/current-root feature)]
                     {:operation "land-status"
                      :feature feature
                      :roots (mapv loom/summarize (if root [root] []))
                      :done (workflow/done? feature)
                      :ready (workflow/next-steps feature)
                      :history (workflow/run-history feature)})))))

;; ---------------------------------------------------------------------------
;; branches: branch-visibility projection over work-root strands (loom)
;; ---------------------------------------------------------------------------

(defn branches-op
  "Group active branch-stamped work roots into a per-branch progress view.

  The repo convention stamps `branch` (plus `owner`/`worktree`) on exactly one
  active work root per branch — `kanban claim` does this for cards — and hangs
  all execution strands beneath that root with parent-of edges. This op supplies
  the repo policy — the `branch` attribute names the branch and the `work` query
  feeds the ready frontier (so workflow plumbing and shuttle run records stay
  hidden) — and delegates the projection to `skein.spools.loom/branch-views`.
  A scoping `branch` argument that matches no stamped root fails loudly."
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

(def ^:private parked-run-threshold-ms
  "How long a ready, unclaimed pending run may sit before it counts as silently
  parked rather than momentarily between scans."
  (* 5 60 1000))

(def ^:private sqlite-timestamp-formatter
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(def ^:private logged-ts-parse-failures
  "Distinct unparseable `updated_at` values already warned about, so a
  persistently malformed row does not respam the log on every chime scan."
  (atom #{}))

(defn- strand-age-ms
  "Milliseconds since a strand's last mutation, parsing SQLite's UTC
  `yyyy-MM-dd HH:mm:ss` updated_at. Returns nil when absent or unparseable.

  A parse failure would silently disable the parked-run detector for that strand
  (its whole point is catching silent failures), so an unparseable timestamp is
  warned to stderr once per distinct value rather than swallowed — a timestamp
  format drift surfaces instead of defeating the detector unnoticed."
  [strand]
  (when-let [ts (:updated_at strand)]
    (try
      (- (System/currentTimeMillis)
         (-> (java.time.LocalDateTime/parse ts sqlite-timestamp-formatter)
             (.toInstant java.time.ZoneOffset/UTC)
             (.toEpochMilli)))
      (catch Exception e
        (when-not (contains? @logged-ts-parse-failures ts)
          (swap! logged-ts-parse-failures conj ts)
          (binding [*out* *err*]
            (println (str "[config] WARN parked-run detector could not parse strand updated_at;"
                          " the detector is disabled for this strand "
                          (pr-str {:strand (:id strand) :updated_at ts
                                   :exception/message (ex-message e)})))))
        nil))))

(defn parked-run-rule
  "Notify when a ready pending shuttle run has sat unclaimed past the threshold.

  This is the silent-parking detector: the morning incident left runs ready and
  pending forever because scan! launched them onto a nil executor. A run that is
  ready (blockers cleared), still `pending`, not tracked in-flight, and older
  than the threshold is one the launch path should have spawned but did not."
  [{:keys [strand ready-ids]}]
  (when (and (= "active" (:state strand))
             (= "true" (config-attr strand :shuttle/run))
             (= "pending" (config-attr strand :shuttle/phase))
             (contains? ready-ids (:id strand))
             (not (contains? (shuttle/in-flight-run-ids) (:id strand)))
             (when-let [age (strand-age-ms strand)]
               (>= age parked-run-threshold-ms)))
    {:title (str "Agent run parked: " (:title strand))
     :body (str "Shuttle run " (:id strand) " has been ready and pending for over "
                (quot parked-run-threshold-ms 60000) " minutes with no in-flight claim."
                " This is the silent-parking signature — verify the weaver's shuttle"
                " executors are healthy and the run was not dropped by a reload.")}))

(defn- register-chime-rules!
  "Register the repo's attention rules with the chime engine."
  []
  [(chime/defrule! :hitl-checkpoint-ready 'config/hitl-checkpoint-ready-rule)
   (chime/defrule! :agent-failure 'config/agent-failure-rule)
   (chime/defrule! :treadle-error 'config/treadle-error-rule)
   (chime/defrule! :kanban-started 'config/kanban-started-rule)
   (chime/defrule! :kanban-completed 'config/kanban-completed-rule)
   (chime/defrule! :kanban-blocked 'config/kanban-blocked-rule)
   (chime/defrule! :parked-run 'config/parked-run-rule)])

;; ---------------------------------------------------------------------------
;; repo-local op arg-specs
;; ---------------------------------------------------------------------------

(def ^:private feature-positional
  "Required `<feature>` positional shared by the devflow wrapper ops."
  {:name :feature
   :type :string
   :required? true
   :doc "Feature name; the workflow run id shared by all devflow ops."})

;; Ops that carry a position-independent `step=<id>` selector alongside other
;; optional args declare a single variadic `:tail` positional; the arg-spec
;; parser binds positionals strictly by order, so pop-step-selector interprets
;; that tail in the handler (see the comment above that fn).
(def ^:private devflow-start-arg-spec
  {:op "devflow-start"
   :doc "Start the devflow lifecycle for a feature."
   :positionals [feature-positional
                 {:name :worktree-check
                  :type :string
                  :doc "Worktree policy: required (default) or already-in-worktree-ok."}]})

(def ^:private devflow-next-arg-spec
  {:op "devflow-next"
   :doc "Show the ready devflow step views for a feature."
   :positionals [feature-positional]})

(def ^:private devflow-choices-arg-spec
  {:op "devflow-choices"
   :doc "Explain the current devflow checkpoint choices for a feature."
   :positionals [feature-positional
                 {:name :step-selector
                  :type :string
                  :variadic? true
                  :doc "Optional trailing `step=<id>` selector for a specific ready step."}]})

(def ^:private devflow-choose-arg-spec
  {:op "devflow-choose"
   :doc "Record a devflow checkpoint choice for a feature, optionally with JSON input."
   :positionals [feature-positional
                 {:name :choice
                  :type :string
                  :required? true
                  :doc "Checkpoint choice key, e.g. approved or abort."}
                 {:name :tail
                  :type :string
                  :variadic? true
                  :doc "Optional JSON-object input and a trailing `step=<id>` selector."}]})

(def ^:private devflow-complete-arg-spec
  {:op "devflow-complete"
   :doc "Close the current non-checkpoint devflow step for a feature."
   :positionals [feature-positional
                 {:name :tail
                  :type :string
                  :variadic? true
                  :doc "Optional notes and a trailing `step=<id>` selector."}]})

(def ^:private devflow-advance-arg-spec
  {:op "devflow-advance"
   :doc "Advance the current devflow step or checkpoint for a feature."
   :positionals [feature-positional
                 {:name :tail
                  :type :string
                  :variadic? true
                  :doc "Optional `[choice] [json-input] [notes]` and a trailing `step=<id>` selector."}]})

(def ^:private devflow-describe-arg-spec
  {:op "devflow-describe"
   :doc "Describe the devflow cycle or one registered stage."
   :positionals [{:name :stage-key
                  :type :string
                  :doc "Optional stage key such as proposal or spec-plan."}]})

(def ^:private devflow-history-arg-spec
  {:op "devflow-history"
   :doc "Show ordered devflow run history for a feature."
   :positionals [feature-positional]})

(def ^:private devflow-archive-arg-spec
  {:op "devflow-archive"
   :doc "Archive a finished devflow run into one digest strand."
   :positionals [feature-positional]})

(def ^:private devflow-status-arg-spec
  {:op "devflow-status"
   :doc "Show the devflow root, ready steps, and done state for a feature."
   :positionals [feature-positional]})

(def ^:private current-dags-arg-spec
  {:op "current-dags"
   :doc "Show active parent-of work DAGs with active depends-on edges."})

(def ^:private branches-arg-spec
  {:op "branches"
   :doc "Show active branch-stamped work roots grouped by branch."
   :positionals [{:name :branch
                  :type :string
                  :doc "Optional branch name to scope the projection."}]})

(def ^:private carder-report-arg-spec
  {:op "carder-report"
   :doc "Show the read-only carder graph hygiene report."
   :flags {:days {:type :int
                  :doc "Maximum active age, in days, before a strand is stale."}
           :include-plumbing {:type :boolean-token
                              :doc "Whether to include workflow/shuttle plumbing: true or false."}}})

(def ^:private workflow-runs-arg-spec
  {:op "workflow-runs"
   :doc "Show active workflow molecule roots, optionally filtered by family."
   :positionals [{:name :family
                  :type :string
                  :doc "Optional workflow family, e.g. devflow."}]})

(def ^:private devflow-conventions-arg-spec
  {:op "devflow-conventions"
   :doc "Show repo-local spools, ops, patterns, and queries."})

(def ^:private flow-await-arg-spec
  {:op "flow-await"
   :doc "Block until a workflow run needs coordinator attention."
   :flags {:timeout-secs {:type :int
                          :doc "Optional timeout in seconds."}}
   :positionals [{:name :workflow-run-id
                  :type :string
                  :required? true
                  :doc "Workflow run id."}]})

(def ^:private hitl-arg-spec
  {:op "hitl"
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
                     :doc "Interactive-capable harness (prompt-via :arg TUI, e.g. hitl-build — the default). Headless harnesses like build/pi-main die in a pane."}
           :backend {:type :string
                     :doc "Multiplexer backend (default tmux)."}}})

(def ^:private flow-status-arg-spec
  {:op "flow-status"
   :doc "Show workflow flow status for renderer consumption."
   :positionals [{:name :workflow-run-id
                  :type :string
                  :required? true
                  :doc "Workflow run id."}]})

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

(defn- op-metadata
  "Return parser-backed op metadata for repo-local wrapper ops."
  [arg-spec]
  {:doc (:doc arg-spec)
   :arg-spec arg-spec})

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
   ;; Interactive TUI seat for `strand hitl` sessions. This cannot alias
   ;; :claude: the shipped harness is headless (`claude -p`, prompt on stdin)
   ;; and exits immediately inside a multiplexer pane — an interactive launch
   ;; requires the prompt as the initial argv message (the session owns stdin).
   (shuttle/defharness! :hitl-build
     {:argv ["claude" "--model" "opus" "--dangerously-skip-permissions"]
      :parse :raw
      :doc "Claude Opus interactive TUI for hitl multiplexer sessions; prompt rides as the initial argv message."})
   ;; oracle is deliberately scarce: single-case briefs with mandatory
   ;; incremental card notes — a context-overflowed Fable run that wrote
   ;; nothing costs an order of magnitude more than any other seat.
   (shuttle/defalias! :oracle
     {:alias-of :claude
      :extra-args ["--model" "claude-fable-5"]
      :doc "Claude Fable oracle: reserved for extreme diagnosis cases only — deep forensics and design diagnosis where cheaper seats have failed or the blast radius justifies it."})
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

;; ---------------------------------------------------------------------------
;; Scheduled NVD deep scan (skein.spools.cron job :nvd-scan)
;;
;; `make deps-report` runs the clj-watson NVD deep scan + govulncheck locally
;; (the fast github-advisory gate stays in CI). This job runs that scan on every
;; maintainer's weaver every ~6 days. Because every weaver runs it, a "scan-lock
;; running" GitHub issue is a best-effort lock — an OPEN one means another
;; maintainer is scanning right now — and +/-1h jitter keeps concurrent weavers
;; from all firing at once. Coordination is best-effort (a double-scan is
;; harmless); silent failure is not, so a missing API key or a gh/scan error
;; lands loudly in `(cron/failures)`.
;;
;; Every side effect (gh, the login-shell scan, the kanban card) is injected
;; into `run-nvd-scan!` so the seed/jitter/lock flow is unit-testable without
;; shelling out — see test/skein/nvd_scan_test.clj.
;; ---------------------------------------------------------------------------

(def ^:private nvd-scan-interval-ms
  "Base cadence: 6 days between NVD deep scans."
  (* 6 24 60 60 1000))

(def ^:private nvd-scan-jitter-ms
  "Uniform +/-1 hour jitter applied to each scheduled scan start."
  (* 60 60 1000))

(def ^:private scan-lock-title
  "Exact title of the coordination issue; matched exactly, not by gh search."
  "scan-lock running")

(def ^:private nvd-card-body-limit
  "Cap the scan output commented on the issue / carried on a card, keeping gh
  argv and the strand attribute bounded."
  60000)

(defn- run-command
  "Run `argv` as a subprocess from the weaver's cwd (the repo root), returning
  `{:exit int :out string}` with stderr merged into stdout.

  The single argv-shaped side-effect seam for the NVD job; tests inject a fake."
  [argv]
  (let [^ProcessBuilder pb (doto (ProcessBuilder. ^java.util.List (mapv str argv))
                             (.redirectErrorStream true))
        ^Process proc (.start pb)
        out (slurp (.getInputStream proc))
        exit (.waitFor proc)]
    {:exit exit :out out}))

(defn- lock-issues
  "Return the `scan-lock running` issues (exact-title match) in `state`
  (\"all\" or \"open\") as maps, via the injected `run-cmd`."
  [run-cmd state fields]
  (let [{:keys [exit out]} (run-cmd ["gh" "issue" "list"
                                     "--search" (str "\"" scan-lock-title "\" in:title")
                                     "--state" state
                                     "--json" fields
                                     "--limit" "50"])]
    (when-not (zero? exit)
      (throw (ex-info "gh issue list failed" {:state state :exit exit :out out})))
    (->> (json/read-str out :key-fn keyword)
         (filter #(= scan-lock-title (:title %))))))

(defn nvd-seed-delay-ms
  "Pure first-fire delay: ms from `now` until the first scan.

  first fire = (`last-created` or `now`) + interval + jitter. A computed past
  instant floors to a near-immediate fire (a maintainer has not scanned in over
  a cadence). `last-created` may be nil (no lock issue ever); `rng` seeds the
  jitter deterministically for tests."
  [^Instant now ^Instant last-created ^Random rng]
  (let [base (or last-created now)
        jitter (cron/jitter-offset-ms nvd-scan-jitter-ms rng)
        target (-> base (.plusMillis nvd-scan-interval-ms) (.plusMillis jitter))]
    (max 0 (- (.toEpochMilli target) (.toEpochMilli now)))))

(defn- most-recent-lock-created
  "Return the created-at Instant of the most recently created lock issue (any
  state), or nil when none exists, via the injected `run-cmd`."
  [run-cmd]
  (some->> (lock-issues run-cmd "all" "title,createdAt")
           (sort-by :createdAt)
           last
           :createdAt
           Instant/parse))

(defn- nvd-key-present?
  "Return true when CLJ_WATSON_NVD_API_KEY is set in the login shell env."
  [run-cmd]
  (zero? (:exit (run-cmd ["zsh" "-lc" "[ -n \"$CLJ_WATSON_NVD_API_KEY\" ]"]))))

(defn- create-lock-issue!
  "Create the lock issue and return its number, via the injected `run-cmd`."
  [run-cmd]
  (let [{:keys [exit out]} (run-cmd ["gh" "issue" "create"
                                     "--title" scan-lock-title
                                     "--body" "Automated NVD deep-scan lock created by the skein cron :nvd-scan job; closed when the scan finishes."])]
    (when-not (zero? exit)
      (throw (ex-info "gh issue create failed" {:exit exit :out out})))
    (or (some-> (re-find #"/issues/(\d+)" out) second parse-long)
        (throw (ex-info "Could not parse created lock issue number" {:out out})))))

(defn- comment-issue!
  "Comment `body` on issue `number`, via the injected `run-cmd`."
  [run-cmd number body]
  (let [{:keys [exit out]} (run-cmd ["gh" "issue" "comment" (str number)
                                     "--body" (subs body 0 (min (count body) nvd-card-body-limit))])]
    (when-not (zero? exit)
      (throw (ex-info "gh issue comment failed" {:issue number :exit exit :out out})))))

(defn- close-issue!
  "Close issue `number`, via the injected `run-cmd`."
  [run-cmd number]
  (let [{:keys [exit out]} (run-cmd ["gh" "issue" "close" (str number)])]
    (when-not (zero? exit)
      (throw (ex-info "gh issue close failed" {:issue number :exit exit :out out})))))

(defn- clj-watson-vuln-count
  "Return N from clj-watson's 'Vulnerable dependencies found: N', or nil when
  the summary line is absent (the scan did not run to completion)."
  [out]
  (some-> (re-find #"Vulnerable dependencies found:\s*(\d+)" out) second parse-long))

(defn- govulncheck-findings?
  "Return true when govulncheck reported at least one vulnerability."
  [out]
  (boolean (re-find #"Vulnerability #\d+" out)))

(defn- govulncheck-completed?
  "Return true when govulncheck ran to a verdict: explicit findings or an
  explicit clean report."
  [out]
  (boolean (or (re-find #"Vulnerability #\d+" out)
               (re-find #"No vulnerabilities found" out))))

(defn run-nvd-scan!
  "Run one NVD deep-scan tick with every side effect injected. Returns an
  inspectable outcome map; throws (fail loudly) on a missing key or gh error.

  seams:
  - `:run-cmd`     (fn [argv] -> {:exit :out}) — gh + login-shell subprocesses.
  - `:raise-card!` (fn [{:keys [title body]}]) — raise a p1 kanban card.

  Flow: skip when another maintainer holds an OPEN lock issue; fail loudly if
  the NVD API key is absent in the login shell (no keyless fallback); else
  acquire the lock (create the issue), run `make deps-report`, verify both
  scanners' completion markers (a marker-less output is a failed scan, never a
  clean one), raise a p1 card when the scan reports vulnerable dependencies,
  comment the findings, and release the lock in a `finally`. The key is checked
  before the lock is acquired so a purely local misconfiguration never churns a
  GitHub issue; the card is raised before the comment so a gh failure cannot
  drop the alert."
  [{:keys [run-cmd raise-card!]}]
  (cond
    (seq (lock-issues run-cmd "open" "number,title"))
    {:outcome :skipped-locked}

    (not (nvd-key-present? run-cmd))
    (throw (ex-info "NVD scan aborted: CLJ_WATSON_NVD_API_KEY absent in the login shell" {}))

    :else
    (let [number (create-lock-issue! run-cmd)]
      (try
        (let [{:keys [out]} (run-cmd ["zsh" "-lc" "make deps-report"])
              watson-n (clj-watson-vuln-count out)
              govuln? (govulncheck-findings? out)]
          ;; deps-report deliberately masks recipe exits (it is a report, not a
          ;; gate), so completion is judged by the scanners' own summary
          ;; markers: marker-less output means the scan crashed or was garbled
          ;; and must land in cron failures, never read as a clean result.
          (when-not (and watson-n (govulncheck-completed? out))
            (throw (ex-info "NVD scan output lacks completion markers; treating scan as failed"
                            {:clj-watson-summary? (some? watson-n)
                             :govulncheck-verdict? (govulncheck-completed? out)
                             :out (subs out 0 (min (count out) nvd-card-body-limit))})))
          ;; The card is the alert of record: raise it on the local weaver
          ;; before any further gh call so a failed comment cannot drop it.
          (when (or (pos? watson-n) govuln?)
            (raise-card! {:title (str "NVD scan: vulnerable dependencies found"
                                      (when (pos? watson-n) (str " (clj-watson: " watson-n ")"))
                                      (when govuln? " (govulncheck)"))
                          :body (str "The scheduled NVD deep scan (make deps-report) reported "
                                     "vulnerable dependencies.\n\n"
                                     "clj-watson vulnerable dependencies: " watson-n "\n"
                                     "govulncheck findings: " (if govuln? "yes" "no") "\n\n"
                                     "Full scan output:\n\n```\n"
                                     (subs out 0 (min (count out) nvd-card-body-limit))
                                     "\n```")}))
          (comment-issue! run-cmd number out)
          {:outcome :scanned :clj-watson watson-n :govulncheck govuln?})
        (finally
          (close-issue! run-cmd number))))))

(defn nvd-seed-delay
  "cron `:initial-delay-fn`: ms until the first scan, seeded from the most recent
  lock issue's creation time via real gh."
  [_runtime]
  (nvd-seed-delay-ms (Instant/now)
                     (most-recent-lock-created run-command)
                     (Random.)))

(defn nvd-scan-tick
  "cron `:run!`: run one NVD deep scan with the real gh, login-shell, and kanban
  seams. `runtime` scopes the kanban card write so it lands in the right world."
  [runtime]
  (run-nvd-scan! {:run-cmd run-command
                  :raise-card! (fn [{:keys [title body]}]
                                 (current/with-runtime runtime
                                   ((requiring-resolve 'skein.spools.kanban/add!)
                                    title {"--body" body "--priority" "p1"})))}))

(defn register-nvd-scan-job!
  "Register the every-6-days NVD deep-scan cron job on the active runtime.

  Called from init.clj rather than `install!` so `config_test`, which loads
  config.clj and calls `install!` directly, never triggers the startup gh seed
  against the real repo. Shared across every maintainer's weaver; the +/-1h
  jitter and the 'scan-lock running' issue lock keep concurrent maintainer
  weavers from all scanning at once."
  []
  {:nvd-scan (cron/register! (current/runtime)
                             {:id :nvd-scan
                              :interval-ms nvd-scan-interval-ms
                              :jitter-ms nvd-scan-jitter-ms
                              :run! 'config/nvd-scan-tick
                              :initial-delay-fn 'config/nvd-seed-delay})})

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
            (op-metadata current-dags-arg-spec)
            'config/current-dags-op)
           (api/register-op!
            runtime
            'branches
            (op-metadata branches-arg-spec)
            'config/branches-op)
           (api/register-op!
            runtime
            'carder-report
            (op-metadata carder-report-arg-spec)
            'config/carder-report-op)
           (api/register-op!
            runtime
            'devflow-start
            (op-metadata devflow-start-arg-spec)
            'config/devflow-start-op)
           (api/register-op!
            runtime
            'devflow-next
            (op-metadata devflow-next-arg-spec)
            'config/devflow-next-op)
           (api/register-op!
            runtime
            'devflow-choices
            (op-metadata devflow-choices-arg-spec)
            'config/devflow-choices-op)
           (api/register-op!
            runtime
            'devflow-choose
            (op-metadata devflow-choose-arg-spec)
            'config/devflow-choose-op)
           (api/register-op!
            runtime
            'devflow-complete
            (op-metadata devflow-complete-arg-spec)
            'config/devflow-complete-op)
           (api/register-op!
            runtime
            'devflow-advance
            (op-metadata devflow-advance-arg-spec)
            'config/devflow-advance-op)
           (api/register-op!
            runtime
            'devflow-describe
            (op-metadata devflow-describe-arg-spec)
            'config/devflow-describe-op)
           (api/register-op!
            runtime
            'devflow-history
            (op-metadata devflow-history-arg-spec)
            'config/devflow-history-op)
           (api/register-op!
            runtime
            'devflow-archive
            (op-metadata devflow-archive-arg-spec)
            'config/devflow-archive-op)
           (api/register-op!
            runtime
            'devflow-status
            (op-metadata devflow-status-arg-spec)
            'config/devflow-status-op)
           (api/register-op!
            runtime
            'workflow-runs
            (op-metadata workflow-runs-arg-spec)
            'config/workflow-runs-op)
           (api/register-op!
            runtime
            'devflow-conventions
            (op-metadata devflow-conventions-arg-spec)
            'config/devflow-conventions-op)
           (api/register-op!
            runtime
            'flow-await
            (assoc (op-metadata flow-await-arg-spec) :deadline-class :unbounded)
            'config/flow-await-op)
           (api/register-op!
            runtime
            'flow-status
            (op-metadata flow-status-arg-spec)
            'config/flow-status-op)
           (api/register-op!
            runtime
            'hitl
            (op-metadata hitl-arg-spec)
            'config/hitl-op)
           (api/register-op!
            runtime
            'land
            (op-metadata land-arg-spec)
            'config/land-op)]
     :land-workflows (register-land-workflows!)}))
