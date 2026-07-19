(ns skein.spools.workflow.internal.routing
  "Run-mutation mechanics for the workflow spool: cascading closes, checkpoint
  choice routing, and the continuation pours a routed choice fans out into.

  A routed choice (`:next` or `:revise`) closes the old root's remaining strands
  and pours its continuation under the same run-id in one transactional batch, so
  two active roots never share a run-id. `choose!*` is the fault-injection seam:
  it takes the batch applier explicitly so a test can inject a failing apply and
  assert the run stays resumable."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.spool.alpha :refer [fail!]]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.workflow.internal.compile :as cmp]
            [skein.spools.workflow.internal.query :as query]
            [skein.spools.workflow.internal.registry :as registry]
            [skein.spools.workflow.internal.util :as util]))

(defn close-attributes!
  "Return attributes to merge onto a step closed by complete!, from optional
  `:notes` (string, stored as \"workflow/outcome-notes\") and `:attributes` (map)
  opts. Returns nil when neither is present."
  [opts]
  (let [{:keys [notes attributes]} opts]
    (when (and (contains? opts :notes) (not (string? notes)))
      (fail! "Workflow :notes must be a string" {:notes notes}))
    (when (and (contains? opts :attributes) (not (map? attributes)))
      (fail! "Workflow :attributes must be a map" {:attributes attributes}))
    (not-empty (merge (or attributes {})
                      (when notes {"workflow/outcome-notes" notes})))))

(defn- depends-on-edges
  "Return the depends-on adjacency (from-id -> #{to-id ...}) internal to
  `strand-ids`. Subgraph expansion can reach external blockers, so edges with an
  endpoint outside the set are dropped to keep join readiness run-local."
  [rt strand-ids]
  (let [ids (set strand-ids)]
    (reduce (fn [acc {:keys [from_strand_id to_strand_id]}]
              (if (and (contains? ids from_strand_id) (contains? ids to_strand_id))
                (update acc from_strand_id (fnil conj #{}) to_strand_id)
                acc))
            {}
            (:edges (graph/subgraph rt strand-ids {:type "depends-on"})))))

(defn cascade-join-ids
  "Return the `procedure` join strand ids under root-id's run that become fully
  satisfied once `closing-ids` close, cascading through chained joins.

  A join (role `\"procedure\"`) depends-on its expansion's exit steps, so it is
  closeable once every strand it depends-on is closed (or is itself closing);
  closing the last inner step beneath a join thus closes the join in the same
  transaction, and a join that is the last inner step of an outer join cascades
  likewise. Joins never surface as ready work (see `raw-ready`)."
  [rt root-id closing-ids]
  (let [strands (:strands (graph/subgraph rt [root-id]))
        by-id (into {} (map (juxt :id identity)) strands)
        deps (depends-on-edges rt (map :id strands))
        joins (filter #(= "procedure" (query/attr % :workflow/role)) strands)]
    (loop [closed (set closing-ids)
           result #{}]
      (let [newly (for [join joins
                        :let [id (:id join)]
                        :when (and (= "active" (:state join))
                                   (not (contains? closed id))
                                   (every? (fn [to]
                                             (or (contains? closed to)
                                                 (= "closed" (:state (by-id to)))))
                                           (get deps id #{})))]
                    id)]
        (if (empty? newly)
          result
          (recur (into closed newly) (into result newly)))))))

(defn close-batch
  "Return one batch payload closing `primary-id` (merging `primary-attrs`) plus
  each cascaded procedure `join-ids` (stamped `workflow/outcome-by \"engine\"`
  for provenance), updating each existing strand in place by its durable id ref."
  [primary-id primary-attrs join-ids]
  (let [primary (cond-> {:ref (keyword primary-id) :state "closed"}
                  (seq primary-attrs) (assoc :attributes primary-attrs))
        joins (mapv (fn [id]
                      {:ref (keyword id) :state "closed"
                       :attributes {"workflow/outcome-by" "engine"}})
                    join-ids)
        strands (into [primary] joins)]
    {:refs (into {} (map (fn [s] [(:ref s) (name (:ref s))])) strands)
     :strands strands}))

(defn- close-workflow-root! [rt root]
  (doseq [strand (:strands (graph/subgraph rt [(:id root)]))]
    (when (and (= "active" (:state strand))
               (contains? #{"root" "step" "checkpoint" "procedure"}
                          (query/attr strand :workflow/role)))
      (weaver/update! rt (:id strand) {:state "closed"}))))

(defn close-run-if-done!
  "Close run-id's active workflow root and its remaining strands once every step,
  checkpoint, and procedure strand in the root's subgraph is closed."
  [rt run-id]
  (when-let [root (query/current-root-with-rt rt run-id)]
    (when (query/run-work-done? rt (:id root))
      (close-workflow-root! rt root))))

(defn- raw-choice-detail [step choice]
  (let [details (query/attr step :workflow/choice-details)]
    (or (get details choice)
        (get details (keyword choice)))))

(defn- validate-choice-input!
  "Fail loudly (TEN-003) before any mutation when a checkpoint choice declares
  required `:input` keys absent from `input`.

  The declaration travels in the checkpoint's stored `workflow/choice-details`
  (see D1.2) under string key names; a required key counts as supplied whether
  the caller's `input` map names it as a keyword or a string, so the surfaced
  declaration round-trips straight back into `choose!`."
  [run-id step choice input]
  (when-let [decls (some-> (raw-choice-detail step choice) query/detail-view (get "input"))]
    (let [required (->> decls (filter #(get % "required")) (map #(get % "key")))
          supplied? (fn [k] (and (map? input)
                                 (or (contains? input (keyword k))
                                     (contains? input k))))
          missing (remove supplied? required)]
      (when (seq missing)
        (fail! "Choice input is missing required keys"
               {:run-id run-id :choice choice
                :missing (vec missing)
                :input-declaration decls})))))

(defn- resolve-next-symbol
  "Resolve a checkpoint choice's stored `:next` target to a workflow constructor
  symbol. A stored keyword name (`\":proposal\"`) resolves through the
  weaver-lifetime registry (failing loudly on an unregistered name); any other
  value is read as a fully qualified fn symbol directly."
  [rt next-str]
  (if (str/starts-with? next-str ":")
    (registry/workflow-definition rt (keyword (subs next-str 1)))
    (symbol next-str)))

(defn- continuation-plan
  "Interpret a `:next` fn's return value into the continuation workflow and its
  authoritative params.

  A `:next` fn may return a workflow map (compiled with the merged
  context+input `call-params`, as before) or `{:workflow w :params p}` to own
  its own params — `p` then compiles the continuation and becomes the new
  root's persisted `workflow/context`. This lets a continuation (e.g. a
  revision round) control its own loop/param state instead of inheriting whatever
  the caller happened to pass as choice input."
  [result call-params]
  (if (and (map? result) (contains? result :workflow))
    {:workflow (:workflow result) :params (get result :params call-params)}
    {:workflow result :params call-params}))

(defn- stage-param-keys
  "Return the stage-local override param keys recorded on `root` (as keywords).

  A `:revise` route stamps the keys it overrode under `workflow/stage-params`;
  a later `:next` route drops them from the continuation params so a stage-local
  loop flag (e.g. `:revision`) never leaks downstream once the stage is left."
  [root]
  (mapv keyword (or (query/attr root :workflow/stage-params) [])))

(defn- stage-params-attrs
  "Return the root attribute recording `override-params`' keys as stage-local, or
  nil when there are no overrides."
  [override-params]
  (when (seq override-params)
    {"workflow/stage-params" (mapv name (keys override-params))}))

(defn- next-plan
  "Return the routing plan for a `:next` continuation (a symbol or registered
  name). Continuation params come from the `:next` fn: either the merged
  context+input (workflow-map return) or the fn's own `:params`
  (`{:workflow w :params p}` return, see `continuation-plan`). The current root's
  stage-local override keys (see `stage-param-keys`) are dropped from those
  params so leaving the stage sheds its loop state. Params persist as the new
  root's `workflow/context`, and the resolved constructor symbol as its
  `workflow/definition` so a later `:revise` can re-pour the stage."
  [rt run-id _step next-str input]
  (let [next-sym (resolve-next-symbol rt next-str)
        workflow-fn (or (requiring-resolve next-sym)
                        (fail! "Choice next workflow cannot be resolved"
                               {:run-id run-id :next next-sym}))
        root (query/current-root-with-rt rt run-id)
        context (or (query/attr root :workflow/context) {})
        call-params (apply dissoc (merge context input) (stage-param-keys root))
        {:keys [workflow params]} (continuation-plan (workflow-fn call-params) call-params)
        payload (cmp/compile workflow params
                             {:run-id run-id
                              :family (query/attr root :workflow/family)
                              :definition next-sym
                              :context params
                              :form :molecule})]
    {:old-root root :payload payload}))

(defn- revise-plan
  "Return the routing plan for a `:revise` choice: re-pour the current root's own
  `workflow/definition` under the same run-id with authoritative override params.

  Params are `(merge context choice-input override-params)`, the `:revise`
  overrides winning, and persist as the new root's `workflow/context`; the
  overridden keys are recorded as stage-local (see `stage-params-attrs`). Fails
  loudly (TEN-003) when the root has no resolvable `workflow/definition`."
  [rt run-id _step choice input override-params]
  (let [root (query/current-root-with-rt rt run-id)
        def-str (or (query/attr root :workflow/definition)
                    (fail! "Cannot revise a run whose root has no workflow/definition"
                           {:run-id run-id :choice choice}))
        definition-sym (symbol def-str)
        definition-fn (or (requiring-resolve definition-sym)
                          (fail! "Root workflow/definition cannot be resolved"
                                 {:run-id run-id :definition definition-sym}))
        context (or (query/attr root :workflow/context) {})
        params (merge context input override-params)
        result (definition-fn params)
        workflow (if (and (map? result) (contains? result :workflow)) (:workflow result) result)
        payload (cmp/compile workflow params
                             {:run-id run-id
                              :family (query/attr root :workflow/family)
                              :definition definition-sym
                              :context params
                              :root-attributes (stage-params-attrs override-params)
                              :form :molecule})]
    {:old-root root :payload payload}))

(defn- route-plan
  "Return the routing plan for a checkpoint choice, or nil for a terminal choice.

  A `:next` choice routes to a continuation (symbol or registered name; see
  `next-plan`); a `:revise` choice re-pours the run's own definition with
  override params (see `revise-plan`). The plan carries the old root and the
  continuation batch payload, compiled once before any mutation and applied only
  after the old root closes, so two active roots never share one run-id."
  [rt run-id step choice input]
  (let [detail (some-> (raw-choice-detail step choice) query/detail-view)
        next-str (get detail "next")
        revise-params (get detail "revise")]
    (cond
      next-str (next-plan rt run-id step next-str input)
      revise-params (revise-plan rt run-id step choice input revise-params)
      :else nil)))

(defn- routed-batch
  "Return one batch payload that atomically closes the chosen checkpoint (with
  its `outcome`), force-closes every other still-active workflow strand in the
  old root's subgraph, and pours the continuation.

  Existing strands are bound by their durable id as the batch ref, so their
  entries update in place rather than create; only the continuation's own
  symbolic-ref strands are new. Folding the closes and the pour into a single
  `batch/apply!` keeps the routed cutover transactional: if the apply fails, no
  strand is mutated, so the old root and its checkpoint stay active and the run
  stays resumable (a plain `repl/update!` close before the pour would instead
  strand the run in a false terminal state)."
  [rt route step outcome]
  (let [checkpoint-id (:id step)
        closeable (filter (fn [strand]
                            (and (= "active" (:state strand))
                                 (contains? #{"root" "step" "checkpoint" "procedure"}
                                            (query/attr strand :workflow/role))))
                          (:strands (graph/subgraph rt [(:id (:old-root route))])))
        close-strands (mapv (fn [strand]
                              (cond-> {:ref (keyword (:id strand)) :state "closed"}
                                (= (:id strand) checkpoint-id) (assoc :attributes outcome)))
                            closeable)
        close-refs (into {} (map (fn [strand] [(keyword (:id strand)) (:id strand)])) closeable)
        payload (:payload route)]
    {:refs close-refs
     :strands (into close-strands (:strands payload))
     :edges (:edges payload)}))

(defn choose!*
  "Record a checkpoint `choice` for run-id and apply its close (and any routed
  continuation) via `apply-batch!`, returning the `{:ready … :done …}` result.

  The fault-injection seam behind the public `choose!`: `apply-batch!` is
  normally `skein.api.batch.alpha/apply!`, but a test can pass a failing applier
  to prove a routed cutover commits nothing and leaves the run resumable. All
  validation happens before any mutation."
  [run-id choice input opts apply-batch!]
  (let [rt (current/runtime)]
    (util/require-map! opts [:opts])
    (let [choice (if (keyword? choice) (name choice) (str choice))
          step (or (query/resolve-ready-step rt run-id opts)
                   (fail! "No ready workflow checkpoint" {:run-id run-id}))]
      (when-not (map? input)
        (fail! "Choice input must be a map" {:run-id run-id :choice choice :input input}))
      (when-not (= "checkpoint" (query/attr step :workflow/role))
        (fail! "Current workflow step is not a checkpoint" {:run-id run-id :step (query/strand->view step)}))
      (let [choices (set (query/attr step :workflow/choices))]
        (when-not (contains? choices choice)
          (fail! "Choice is not valid for checkpoint" {:run-id run-id :choice choice :valid choices})))
      (validate-choice-input! run-id step choice input)
      (let [route (route-plan rt run-id step choice input)
            outcome (cond-> {"workflow/outcome" choice
                             "workflow/outcome-input" input}
                      (contains? opts :by) (assoc "workflow/outcome-by" (:by opts)))]
        (if route
          (apply-batch! rt (routed-batch rt route step outcome))
          (apply-batch! rt (close-batch (:id step) outcome
                                        (cascade-join-ids rt (:id (query/current-root-with-rt rt run-id)) #{(:id step)}))))
        ;; also covers a routed continuation that poured no active work, so the
        ;; new root cannot linger active on a logically finished run
        (close-run-if-done! rt run-id)
        (query/run-result rt run-id)))))
