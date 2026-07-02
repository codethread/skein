(ns skein.spools.workflow
  "Alpha workflow spool for molecule and wisp-style strand graphs.

  A workflow definition is plain data. `compile` turns that data into a Skein
  batch payload, while `pour!` and `wisp!` materialize persistent molecules and
  ephemeral wisps through the public batch alpha surface. This namespace owns no
  privileged runtime state; it composes existing strand graph primitives."
  (:refer-clojure :exclude [compile])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.batch.alpha :as batch]
            [skein.graph.alpha :as graph]
            [skein.repl :as repl]))

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(s/def ::phase #{:molecule :wisp})
(s/def ::id-ref #(or (keyword? %) (symbol? %) (non-blank-string? %)))
(s/def ::id ::id-ref)
(s/def ::renderable #(or (non-blank-string? %) (fn? %)))
(s/def ::name ::renderable)
(s/def ::title ::renderable)
(s/def ::description #(or (string? %) (fn? %)))
(s/def ::state #{"active" "closed"})
(s/def ::attributes map?)
(s/def ::required boolean?)
(s/def ::default any?)
(s/def ::param-def (s/keys :opt-un [::required ::default]))
(s/def ::params (s/map-of keyword? ::param-def))
;; caller-supplied param values (compile args, call-site :params); the aux
;; namespace keeps the un-namespaced :params key while the shape differs from
;; the declaration map above
(s/def :skein.spools.workflow.values/params (s/map-of keyword? any?))
(s/def ::count pos-int?)
;; :each resolves against params at expansion time: a literal sequential, a
;; keyword naming a resolved param, or a fn of the resolved params map.
(s/def ::each #(or (sequential? %) (keyword? %) (fn? %)))
(s/def ::depends-on (s/coll-of ::id-ref :kind vector?))
(s/def ::condition #(or (keyword? %)
                        (and (vector? %) (#{:= :!=} (first %)) (= 3 (count %)))))
(s/def ::loop (s/keys :opt-un [::count ::each]))
(s/def ::procedure any?)
(s/def ::call (s/keys :req-un [::id ::procedure]
                      :opt-un [::title :skein.spools.workflow.values/params ::depends-on ::attributes]))
(s/def ::step (s/keys :req-un [::id ::title]
                      :opt-un [::description ::attributes ::state ::depends-on ::condition ::loop]))
(s/def ::workflow-item (s/or :step ::step :call ::call))
(s/def ::steps (s/coll-of ::workflow-item :kind vector?))
(s/def ::workflow (s/keys :req-un [::name ::steps]
                         :opt-un [::params ::attributes ::state ::phase]))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- reject-unknown-keys!
  "Fail loudly (TEN-003) when `m` carries keys outside `allowed`, so a builder
  never silently ignores a mistyped option key (`:require`, `:depend-on`, …).
  Returns `m` for threading."
  [m allowed context]
  (when-let [unknown (seq (remove allowed (keys m)))]
    (fail! "Unknown workflow option keys"
           {:context context :unknown (vec unknown) :allowed allowed}))
  m)

(def ^:private param-opt-keys #{:required :default})
(def ^:private step-opt-keys #{:description :attributes :state :depends-on :condition :loop})
(def ^:private checkpoint-opt-keys (into step-opt-keys #{:kind :choices}))
(def ^:private call-opt-keys #{:title :depends-on :attributes})
(def ^:private workflow-opt-keys #{:params :attributes :state :phase})
(def ^:private choice-opt-keys #{:key :label :description :next})

(defn- require-valid! [spec value message]
  (when-not (s/valid? spec value)
    (fail! message {:explain (s/explain-data spec value)}))
  value)

(defn- require-valid-workflow! [workflow]
  (require-valid! ::workflow workflow "Invalid workflow definition"))

(defn- spec-entry [spec-name doc example]
  {:spec (str spec-name)
   :doc doc
   :spec-form (pr-str (s/form spec-name))
   :example example})

(defn explain
  "Return self-documenting workflow spool input contracts.

  Agents can call this before constructing workflow data. It reports the stable
  public builders, valid step/checkpoint fields, and concrete examples without
  exposing batch payload internals."
  ([]
   (explain :workflow))
  ([topic]
   (case topic
     :workflow {:topic :workflow
                :summary "Clojure-native workflow data compiled into a Skein molecule or wisp."
                :builders {'workflow 'skein.spools.workflow/workflow
                           'step 'skein.spools.workflow/step
                           'gate 'skein.spools.workflow/gate
                           'checkpoint 'skein.spools.workflow/checkpoint
                           'call 'skein.spools.workflow/call
                           'param 'skein.spools.workflow/param}
                :contract (spec-entry ::workflow
                                      "A workflow requires a non-blank :name and vector :steps."
                                      '(workflow (fn [{:keys [feature]}] (str "Ship " feature))
                                         {:params {:feature (param :required true)}}
                                         (step :design (fn [{:keys [feature]}] (str "Design " feature)))
                                         (checkpoint :signoff "Approve design" :choices [:approved :revise])))
                :fields {:params "Workflow-level map of keyword param names to param definitions. Param definitions support boolean :required and optional :default."}
                :step (explain :step)
                :gate (explain :gate)
                :checkpoint (explain :checkpoint)
                :call (explain :call)}
     :step {:topic :step
            :summary "A step is one unit of work. Do the work, then complete it."
            :contract (spec-entry ::step
                                  "A step requires :id and :title; optional fields include :depends-on, :attributes, :condition, :loop, :description, and :state."
                                  '(step :implement (fn [{:keys [feature]}] (str "Implement " feature)) :depends-on [:design] :attributes {"skills" "clojure"}))
            :fields {:id "Stable local ref, keyword/symbol/string."
                     :title "Human-readable instruction."
                     :depends-on "Vector of local refs this step waits for."
                     :attributes "Plain metadata stored on the materialized strand."
                     :condition "Keyword param truthiness, or [:= :param value] / [:!= :param value]."
                     :loop "Expansion: {:count n} (items 1..n) or {:each xs} where xs is a literal sequential, a keyword naming a param, or a fn of params. Expanded steps render against (merge params {:item item :i idx}); a downstream :depends-on on the base loop id fans in to every expanded id."}}
     :gate {:topic :gate
            :summary "A gate is a step whose completion belongs to an external actor. Wait for the waiter; don't do the work yourself."
            :contract (spec-entry ::step
                                  "A gate returns step data with a workflow/gate actor hint. It takes the same optional fields as a step."
                                  '(gate :ci-green "Wait for CI to pass" :ci :depends-on [:push]))
            :fields {:waiter "Freeform actor hint (keyword/symbol/string) stored as workflow/gate, e.g. :ci, :human, :subagent."
                     :others "Same optional fields as step: :depends-on, :attributes, :condition, :loop, :description, :state."
                     :workflow/gate "Marks the step an external wait point, surfaced by step-view as :gate; complete! requires :by to close it."}}
     :call {:topic :call
            :summary "A call is a procedure-style inline reuse of another workflow, without a choice branch."
            :contract (spec-entry ::call
                                  "A call requires :id and :procedure; optional fields include :params, :depends-on, :title, and :attributes."
                                  '(call :review-proposal review-workflow {:artifact "proposal.md"} :depends-on [:write-proposal]))
            :fields {:id "Stable local ref for the procedure call. Downstream parent steps may depend on this id."
                     :procedure "Workflow map, zero-arg function, one-arg function receiving params, or symbol resolving to one of those."
                     :params "Procedure-local params merged with parent workflow params."
                     :depends-on "Parent refs that the procedure entry steps wait for."}}
     :checkpoint {:topic :checkpoint
                  :summary "A checkpoint is a step that requires an explicit choice. Use choose! in higher-level workflow spools."
                  :contract (spec-entry ::step
                                        "Checkpoint returns step data with workflow/checkpoint metadata and optional workflow/choices."
                                        '(checkpoint :route "Choose next path" :kind :agent :choices [:tasks :direct]))
                  :fields {:kind "Decision owner such as :human or :agent."
                           :choices "Allowed outcomes, stored as strings."
                           :workflow/checkpoint "Stable checkpoint id, derived from the local step id."
                           :workflow/checkpoint-kind "Decision owner stored as a string."
                           :workflow/choices "Allowed choices stored as strings."}}
     (fail! "Unknown workflow explain topic" {:topic topic :topics [:workflow :step :gate :checkpoint :call]}))))

(defn- require-non-blank! [value path]
  (when-not (non-blank-string? value)
    (fail! "Workflow value must be a non-blank string" {:path path :value value}))
  value)

(defn- require-map! [value path]
  (when-not (map? value)
    (fail! "Workflow value must be a map" {:path path :value value}))
  value)

(defn- require-vector! [value path]
  (when-not (vector? value)
    (fail! "Workflow value must be a vector" {:path path :value value}))
  value)

(defn- require-keyword! [value path]
  (when-not (keyword? value)
    (fail! "Workflow value must be a keyword" {:path path :value value}))
  value)

(defn- normalize-ref [value path]
  (cond
    (keyword? value) value
    (symbol? value) (keyword (name value))
    (non-blank-string? value) (keyword value)
    :else (fail! "Workflow step references must be keywords, symbols, or non-blank strings"
                 {:path path :value value})))

(defn- render [value params]
  (cond
    (fn? value) (value params)
    (map? value) (into {} (map (fn [[k v]] [k (render v params)])) value)
    (vector? value) (mapv #(render % params) value)
    (sequential? value) (mapv #(render % params) value)
    :else value))

(defn- param-defaults [declared]
  (reduce-kv (fn [acc k spec]
               (require-keyword! k [:params k])
               (if (and (map? spec) (contains? spec :default))
                 (assoc acc k (:default spec))
                 acc))
             {}
             (or declared {})))

(defn- required-params [declared]
  (->> (or declared {})
       (keep (fn [[k spec]] (when (and (map? spec) (:required spec)) k)))
       set))

(defn- resolve-params [workflow params]
  (let [declared (:params workflow)
        resolved (merge (param-defaults declared) params)
        missing (seq (remove #(contains? resolved %) (required-params declared)))]
    (when missing
      (fail! "Workflow required params are missing" {:missing (vec missing)}))
    resolved))

(defn- include-step? [step params]
  (let [condition (:condition step)]
    (cond
      (nil? condition) true
      (keyword? condition) (boolean (get params condition))
      (and (vector? condition) (= := (first condition))) (= (get params (second condition)) (nth condition 2))
      (and (vector? condition) (= :!= (first condition))) (not= (get params (second condition)) (nth condition 2))
      :else (fail! "Unsupported workflow step condition" {:step (:id step) :condition condition}))))

(defn- loop-values
  "Return the sequence of items a `:loop` expands over, resolved against `params`.

  `{:count n}` yields 1..n. `{:each xs}` accepts a literal sequential, a keyword
  naming a resolved param, or a fn of the resolved params map; a param value or fn
  result that is not sequential fails loudly (TEN-003)."
  [loop-spec params]
  (cond
    (nil? loop-spec) [nil]
    (integer? (:count loop-spec)) (range 1 (inc (:count loop-spec)))
    (contains? loop-spec :each)
    (let [each (:each loop-spec)
          resolved (cond
                     (sequential? each) each
                     (keyword? each) (get params each)
                     (fn? each) (each params))]
      (if (sequential? resolved)
        resolved
        (fail! "Workflow loop :each must resolve to a sequential"
               {:each each :resolved resolved})))
    :else (fail! "Workflow loop requires :count integer or :each collection" {:loop loop-spec})))

(defn- loop-suffix
  "Return the id suffix for one expanded loop item: the number for `:count`,
  `(:id item)` when the item is a map carrying `:id`, else the 1-based position."
  [loop-spec item idx]
  (let [suffix (cond
                 (integer? (:count loop-spec)) item
                 (and (map? item) (contains? item :id)) (:id item)
                 :else (inc idx))]
    (if (or (keyword? suffix) (symbol? suffix)) (name suffix) suffix)))

(defn- expand-loop-step
  "Expand a `:loop` step into one step per item, rendering `:title`,
  `:description`, and `:attributes` against `(merge params {:item item :i idx})`
  so loop steps see both the per-iteration binding (`:i` is the 0-based index)
  and the workflow params. Non-loop steps pass through unchanged."
  [step params]
  (if-let [loop-spec (:loop step)]
    (vec (map-indexed
          (fn [idx item]
            (let [env (merge params {:item item :i idx})
                  suffix (loop-suffix loop-spec item idx)]
              (-> step
                  (dissoc :loop)
                  (update :id #(keyword (str (name (normalize-ref % [:step :id])) "-" suffix)))
                  (update :title render env)
                  (update :description render env)
                  (update :attributes render env))))
          (loop-values loop-spec params)))
    [step]))

(defn- call-step? [step]
  (contains? step :procedure))

(declare compile)

(def ^:private ^:dynamic *procedure-path*
  ;; Conditions filter steps only after procedure expansion, so a cyclic
  ;; procedure reference can never terminate; re-entry must fail loudly
  ;; (TEN-003) instead of overflowing the stack.
  [])

(defn- require-acyclic-procedure! [call-id procedure]
  (when (some #(= % procedure) *procedure-path*)
    (fail! "Workflow procedure call is cyclic"
           {:call call-id
            :procedure procedure
            :path (mapv #(if (symbol? %) % (type %)) *procedure-path*)})))

(defn- resolve-procedure
  ;; Deref so a symbol yields the same canonical value (map or fn) as a direct
  ;; reference — cycle detection and dispatch must not depend on which form the
  ;; author used.
  [procedure]
  (if (symbol? procedure)
    (if-let [resolved (requiring-resolve procedure)]
      @resolved
      (fail! "Workflow procedure symbol cannot be resolved" {:procedure procedure}))
    procedure))

(defn- procedure-workflow [procedure params]
  (cond
    (map? procedure) procedure
    (fn? procedure) (procedure params)
    :else (fail! "Workflow procedure must be a workflow map, function, or resolvable symbol"
                 {:procedure procedure})))

(defn- prefixed-ref [call-id ref]
  (keyword (str (name call-id) "--" (name (normalize-ref ref [:procedure :ref])))))

(defn- entry-refs [steps]
  (->> steps (remove #(seq (:depends-on %))) (map :id) vec))

(defn- exit-refs [steps]
  (let [depended (set (mapcat :depends-on steps))]
    (->> steps (map :id) (remove depended) vec)))

(defn- expand-call-step [call-step params]
  (let [call-id (normalize-ref (:id call-step) [:call :id])
        procedure (resolve-procedure (:procedure call-step))
        _ (require-acyclic-procedure! call-id procedure)
        params (merge params (or (:params call-step) {}))
        workflow (procedure-workflow procedure params)
        payload (binding [*procedure-path* (conj *procedure-path* procedure)]
                  (compile workflow params))
        steps (mapv (fn [strand]
                      {:id (:ref strand)
                       :title (:title strand)
                       :state (:state strand)
                       :attributes (:attributes strand)})
                    (rest (:strands payload)))
        edges (:edges payload)
        internal-deps (reduce (fn [acc {:keys [from to type]}]
                                (if (= "depends-on" type)
                                  (update acc from (fnil conj []) to)
                                  acc))
                              {}
                              edges)
        steps-with-deps (mapv #(assoc % :depends-on (get internal-deps (:id %) [])) steps)
        entries (entry-refs steps-with-deps)
        exits (exit-refs steps-with-deps)
        prefixed (mapv (fn [step]
                         (let [id (:id step)
                               internal (mapv #(prefixed-ref call-id %) (get internal-deps id []))
                               entry-extra (when (some #{id} entries) (:depends-on call-step))]
                           (-> step
                               (assoc :id (prefixed-ref call-id id))
                               (assoc :depends-on (vec (concat internal entry-extra))))))
                       steps)
        join-title (or (:title call-step) (str "Complete " (name call-id)))]
    (conj prefixed {:id call-id
                    :title join-title
                    :depends-on (mapv #(prefixed-ref call-id %) exits)
                    :attributes (merge {"workflow/role" "procedure"
                                        "workflow/procedure" (name call-id)}
                                       (:attributes call-step))})))

(defn- expand-procedures [steps params]
  (mapcat (fn [step]
            (if (call-step? step)
              (expand-call-step step params)
              [step]))
          steps))

(defn- require-no-root-collision! [rendered root-ref]
  (doseq [step rendered]
    (let [ref (normalize-ref (:id step) [:steps :id])]
      (when (= ref root-ref)
        (fail! "Workflow step ref collides with the root ref" {:step ref :root-ref root-ref})))))

(defn- excluded-dep-map [excluded]
  (into {}
        (map (fn [step]
               [(normalize-ref (:id step) [:steps :id])
                (mapv #(normalize-ref % [:steps (:id step) :depends-on]) (:depends-on step))]))
        excluded))

(defn- resolve-dep-refs
  "Resolve one dependency ref owned by `owner-id` (the step whose own
  :depends-on literally names `ref`), splicing transitively through
  condition-excluded steps until reaching included steps, matching beads'
  behavior for conditional steps. Recursing into an excluded step's own deps
  reattributes `owner-id` to that excluded step, so a bad ref is always
  blamed on whichever step's definition actually names it. A ref matching
  neither an included nor an excluded step never existed in the definition
  and fails loudly."
  [included-ids excluded-deps seen owner-id ref]
  (cond
    (contains? included-ids ref) #{ref}
    (contains? excluded-deps ref)
    (if (contains? seen ref)
      #{}
      (into #{}
            (mapcat #(resolve-dep-refs included-ids excluded-deps (conj seen ref) ref %))
            (get excluded-deps ref)))
    :else (fail! "Workflow step depends on an unknown ref" {:step owner-id :missing ref})))

(defn- splice-depends-on [included excluded]
  (let [included-ids (set (map #(normalize-ref (:id %) [:steps :id]) included))
        excluded-deps (excluded-dep-map excluded)]
    (mapv (fn [step]
            (let [dependent-id (normalize-ref (:id step) [:steps :id])
                  deps (mapv #(normalize-ref % [:steps (:id step) :depends-on]) (:depends-on step))
                  spliced (into [] (distinct)
                                (mapcat #(resolve-dep-refs included-ids excluded-deps #{} dependent-id %) deps))]
              (assoc step :depends-on spliced)))
          included)))

(defn- fan-in-deps
  "Rewrite each step's `:depends-on`: a ref naming a loop step's base id (a key
  of `fanin`) fans out to all that loop's expanded ids, so a downstream step can
  depend on the loop as a whole. Other refs pass through untouched for F4's
  unknown-ref validation to check later."
  [steps fanin]
  (mapv (fn [step]
          (if-let [deps (:depends-on step)]
            (assoc step :depends-on
                   (into [] (distinct)
                         (mapcat (fn [dep]
                                   (let [ref (normalize-ref dep [:steps (:id step) :depends-on])]
                                     (get fanin ref [dep])))
                                 deps)))
            step))
        steps))

(defn- require-unique-base-ids!
  "Fail loudly on any collision among steps' pre-expansion ids (loop base ids,
  step ids, call ids) or with `root-ref`. Fan-in keys deps on the base loop id,
  so a base-id collision must be rejected before it can silently misroute a
  dependency; the later expanded-ref checks cannot see base ids."
  [steps root-ref]
  (let [base-ids (mapv #(normalize-ref (:id %) [:steps :id]) steps)]
    (when-let [dupes (seq (for [[ref n] (frequencies base-ids) :when (> n 1)] ref))]
      (fail! "Workflow step ids must be unique" {:duplicates (vec dupes)}))
    (when-let [collision (some #{root-ref} base-ids)]
      (fail! "Workflow step ref collides with the root ref" {:step collision :root-ref root-ref}))))

(defn- normalize-steps [workflow params root-ref]
  (let [steps (require-vector! (:steps workflow) [:steps])
        _ (require-unique-base-ids! steps root-ref)
        expansions (mapv (fn [step]
                           (let [expanded (expand-loop-step step params)]
                             {:base (when (:loop step) (normalize-ref (:id step) [:steps :id]))
                              :ids (mapv #(normalize-ref (:id %) [:steps :id]) expanded)
                              :steps expanded}))
                         steps)
        fanin (into {} (keep (fn [{:keys [base ids]}] (when base [base ids])) expansions))
        expanded (fan-in-deps (vec (mapcat :steps expansions)) fanin)
        procedures (expand-procedures expanded params)
        rendered (mapv #(render % params) procedures)
        _ (require-no-root-collision! rendered root-ref)
        by-condition (group-by #(include-step? % params) rendered)
        included (vec (get by-condition true))
        excluded (vec (get by-condition false))
        spliced (splice-depends-on included excluded)
        refs (mapv #(normalize-ref (:id %) [:steps :id]) spliced)
        duplicates (seq (for [[ref n] (frequencies refs) :when (> n 1)] ref))]
    (when duplicates
      (fail! "Workflow step ids must be unique" {:duplicates (vec duplicates)}))
    (doseq [[idx step] (map-indexed vector spliced)]
      (require-map! step [:steps idx])
      (require-non-blank! (:title step) [:steps idx :title]))
    spliced))

(defn- step-strand [step phase]
  {:ref (normalize-ref (:id step) [:steps :id])
   :title (:title step)
   :state (or (:state step) "active")
   :attributes (merge {"workflow/role" "step"
                       "workflow/phase" (name phase)}
                      (:attributes step)
                      (when-let [description (:description step)]
                        {"description" description}))})

(defn- dependency-edges [steps]
  (mapcat (fn [step]
            (let [from (normalize-ref (:id step) [:steps :id])]
              (for [dep (:depends-on step)]
                {:op :upsert
                 :from from
                 :to (normalize-ref dep [:steps (:id step) :depends-on])
                 :type "depends-on"})))
          steps))

(defn- parent-edges [root-ref steps]
  (mapv (fn [step]
          {:op :upsert
           :from root-ref
           :to (normalize-ref (:id step) [:steps :id])
           :type "parent-of"})
        steps))

(defn param
  "Return a workflow param definition.

  This is a Clojure-native replacement for Beads' TOML variable blocks.
  For example, pass `:required true` or `:default` values; the result is plain
  data that `compile` consumes."
  [& {:as opts}]
  (reject-unknown-keys! opts param-opt-keys :param)
  opts)

(defn step
  "Return a workflow step definition.

  The result is plain data and may be passed to `workflow` or transformed by
  user code before compilation."
  [id title & {:as opts}]
  (reject-unknown-keys! opts step-opt-keys :step)
  (merge {:id id :title title} opts))

(defn gate
  "Return a workflow gate step definition — a step whose completion belongs to
  an external actor rather than the driving agent.

  A gate stays an ordinary step (role `\"step\"`, so done-semantics are
  untouched) stamped with `workflow/gate <waiter>`, a freeform actor hint such
  as `:ci`, `:human`, or `:subagent`. `step-view` surfaces it as `:gate`, and
  `complete!` refuses to close it without a `:by` recording who closed it. The
  driving agent should treat a ready gate as a poll/hand-off point, not work to
  do. Accepts the same opts as `step`."
  [id title waiter & {:as opts}]
  (reject-unknown-keys! opts step-opt-keys :gate)
  (-> (apply step id title (apply concat (seq opts)))
      (update :attributes merge {"workflow/gate" (name waiter)})))

(defn- choice-key [choice]
  (cond
    (map? choice) (:key choice)
    :else choice))

(defn- choice-name [choice]
  (let [k (choice-key choice)]
    (cond
      (keyword? k) (name k)
      (symbol? k) (name k)
      (non-blank-string? k) k
      :else (fail! "Workflow checkpoint choices require a non-blank key" {:choice choice}))))

(defn- choice-detail-attr [choice]
  (when (map? choice)
    (let [k (choice-name choice)]
      [k (cond-> {}
           (:label choice) (assoc "label" (:label choice))
           (:description choice) (assoc "description" (:description choice))
           (:next choice) (assoc "next" (str (:next choice))))])))

(defn- choice-details-attr [choices]
  (not-empty (into {} (keep choice-detail-attr choices))))

(defn- reject-unknown-choice-keys! [choices]
  (doseq [choice choices :when (map? choice)]
    (reject-unknown-keys! choice choice-opt-keys :choice))
  choices)

(defn- require-unique-choice-keys! [choices]
  (let [names (mapv choice-name choices)
        duplicate (some (fn [[k n]] (when (< 1 n) k)) (frequencies names))]
    (when duplicate
      (fail! "Workflow checkpoint choice keys must be unique" {:choice duplicate :choices names})))
  choices)

(defn call
  "Return a procedure-style workflow call.

  The callee workflow is expanded inline at compile time. Downstream parent
  steps depend on the call id, which represents completion of the expanded
  procedure's exit steps."
  [id procedure params & {:as opts}]
  (reject-unknown-keys! opts call-opt-keys :call)
  (merge {:id id :procedure procedure :params params} opts))

(defn checkpoint
  "Return a workflow checkpoint step definition.

  Checkpoints are ordinary strands with consistent workflow metadata for HITL,
  review, routing, or external wait points. `:choices` may be simple keywords or
  maps with `:key`, `:label`, `:description`, and optional `:next` metadata."
  [id title & {:as opts}]
  (reject-unknown-keys! opts checkpoint-opt-keys :checkpoint)
  (let [kind (or (:kind opts) :human)
        choices (some-> (:choices opts) reject-unknown-choice-keys! require-unique-choice-keys!)
        details (choice-details-attr choices)]
    (-> (apply step id title (apply concat (seq (dissoc opts :kind :choices))))
        (update :attributes merge
                {"workflow/role" "checkpoint"
                 "workflow/checkpoint" (name id)
                 "workflow/checkpoint-kind" (name kind)}
                (when choices
                  {"workflow/choices" (mapv choice-name choices)})
                (when details
                  {"workflow/choice-details" details})))))

(defn workflow
  "Return a Clojure-native workflow definition.

  The returned map is the same data shape accepted by `compile`, but avoids a
  separate TOML/JSON formula language."
  [name & body]
  (let [[opts steps] (if (and (map? (first body))
                             (not (contains? (first body) :id)))
                       [(first body) (rest body)]
                       [{} body])]
    (reject-unknown-keys! opts workflow-opt-keys :workflow)
    (merge opts {:name name :steps (vec steps)})))

(defn compile
  "Return a batch payload for a workflow molecule or wisp.

  `workflow` accepts plain maps or values produced by the `workflow` builder.
  Each step requires `:id` and `:title`, and may include
  `:description`, `:attributes`, `:state`, `:depends-on`, `:condition`, or a
  simple `:loop` of `{:count n}` / `{:each xs}`. Dynamic names, titles,
  descriptions, and attribute values may be functions of the resolved params map.

  A `:depends-on` ref pointing at a `:condition`-excluded step is spliced onto
  that step's own deps, transitively, matching beads' behavior for conditional
  steps. A ref that matches neither an included nor an excluded step, or a step
  ref colliding with the root ref (`:molecule`, overridable via opts
  `:root-ref`), fails loudly."
  ([workflow]
   (compile workflow {}))
  ([workflow params]
   (compile workflow params {}))
  ([workflow params opts]
   (require-map! workflow [:workflow])
   (require-valid! :skein.spools.workflow.values/params params "Invalid workflow params")
   (require-valid-workflow! workflow)
   (let [phase (or (:phase opts) (:phase workflow) :molecule)
         params (resolve-params workflow params)
         ;; Render only workflow-level fields here; step render fns stay live
         ;; so normalize-steps can render loop steps against per-item params.
         workflow (assoc (render (dissoc workflow :steps) params)
                         :steps (:steps workflow))
         _ (require-non-blank! (:name workflow) [:name])
         root-ref (normalize-ref (or (:root-ref opts) :molecule) [:root-ref])
         steps (normalize-steps workflow params root-ref)
         root {:ref root-ref
               :title (:name workflow)
               :state (or (:state workflow) "active")
               :attributes (merge {"workflow/role" "molecule"
                                   "workflow/phase" (name phase)}
                                  (:attributes workflow)
                                  (:root-attributes opts)
                                  (when-let [run-id (:run-id opts)]
                                    {"workflow/run-id" run-id})
                                  (when-let [family (:family opts)]
                                    {"workflow/family" family})
                                  (when-let [definition (:definition opts)]
                                    {"workflow/definition" (str definition)})
                                  (when-let [context (:context opts)]
                                    {"workflow/context" context})
                                  (when (= phase :wisp)
                                    {"workflow/wisp" "true"}))}]
     {:strands (into [root] (mapv #(step-strand % phase) steps))
      :edges (vec (concat (parent-edges root-ref steps)
                          (dependency-edges steps)))})))

(defn pour!
  "Materialize `workflow` as a persistent molecule strand graph."
  ([workflow]
   (pour! workflow {}))
  ([workflow params]
   (pour! workflow params {}))
  ([workflow params opts]
   (batch/apply! (compile workflow params (merge opts {:phase :molecule})))))

(defn wisp!
  "Materialize `workflow` as an ephemeral wisp strand graph.

  Wisps are normal Skein strands marked with workflow attributes so userland can
  burn or squash them explicitly."
  ([workflow]
   (wisp! workflow {}))
  ([workflow params]
   (wisp! workflow params {}))
  ([workflow params opts]
   (batch/apply! (compile workflow params (merge opts {:phase :wisp})))))

(defn- attr
  "Read attribute `k` (a keyword such as `:workflow/role`) from `strand`'s
  attribute map, tolerating either keyword- or string-keyed maps. Strand
  attributes arrive keyword-keyed in-memory but string-keyed after a JSON
  round-trip through the weaver; this is the one place that reconciles both."
  [strand k]
  (let [attrs (:attributes strand)]
    (if (contains? attrs k)
      (get attrs k)
      (get attrs (subs (str k) 1)))))

(defn molecule-id
  "Return the materialized root molecule id from a `pour!` or `wisp!` result."
  [result]
  (or (get-in result [:refs :molecule])
      (some (fn [strand]
              (when (= "molecule" (attr strand :workflow/role)) (:id strand)))
            (:created result))))

(defn bond!
  "Bond two materialized molecules: `right-id` depends on `left-id`.

  The `workflow/bond` edge attribute distinguishes a cross-molecule bond from
  the intra-molecule dependency edges `compile` emits."
  [left-id right-id]
  (repl/update! right-id {:edges [{:type "depends-on" :to left-id
                                   :attributes {:workflow/bond "sequential"}}]}))

(defn burn!
  "Burn a materialized molecule or wisp subgraph rooted at `root-id`."
  [root-id]
  (let [ids (mapv :id (:strands (graph/subgraph [root-id])))]
    (if (seq ids)
      (graph/burn-by-ids! ids)
      {:burned [] :count 0})))

(defn squash!
  "Replace a materialized wisp/molecule with one digest strand, then burn its graph."
  ([root-id title]
   (squash! root-id title {}))
  ([root-id title attributes]
   (let [subgraph (graph/subgraph [root-id])
         digest (repl/strand! title
                              (merge {"workflow/role" "digest"
                                      "workflow/squashed-root" root-id
                                      "workflow/squashed-count" (count (:strands subgraph))}
                                     attributes)
                              {:state "closed"})]
     (burn! root-id)
     digest)))

(defn active-runs
  "Return active workflow root strands, optionally filtered by family."
  ([]
   (repl/query [:and [:= :state "active"] [:= [:attr "workflow/role"] "molecule"]]))
  ([family]
   (repl/query [:and
                [:= :state "active"]
                [:= [:attr "workflow/role"] "molecule"]
                [:= [:attr "workflow/family"] family]])))

(defn current-root
  "Return the single active workflow root for run-id, nil when absent, or fail if ambiguous."
  [run-id]
  (let [roots (repl/query [:and
                           [:= :state "active"]
                           [:= [:attr "workflow/run-id"] run-id]
                           [:= [:attr "workflow/role"] "molecule"]])]
    (case (count roots)
      0 nil
      1 (first roots)
      (throw (ex-info "Multiple active workflow roots found" {:run-id run-id :roots roots})))))

(defn- raw-next-steps
  "Return the run's ready workflow work strands.

  A root with an active depends-on blocker (a `bond!` from another molecule)
  parent-blocks the whole run: its steps stay hidden until the blocking root
  closes, even though each step's own deps may be satisfied."
  [run-id]
  (let [root (current-root run-id)
        ready (repl/ready)
        root-ready? (and root (some #(= (:id %) (:id root)) ready))
        ids (when root (set (map :id (:strands (graph/subgraph [(:id root)])))))]
    (if-not root-ready?
      []
      (->> ready
           (filter #(contains? ids (:id %)))
           (remove #(= "molecule" (attr % :workflow/role)))
           vec))))

(defn- raw-next-step [run-id]
  (let [steps (raw-next-steps run-id)]
    (case (count steps)
      0 nil
      1 (first steps)
      (throw (ex-info "Multiple workflow next steps are ready" {:run-id run-id :steps steps})))))

(defn- ready-step-by-id
  "Return the ready step matching id among run-id's currently ready steps, or nil."
  [run-id id]
  (some #(when (= (:id %) id) %) (raw-next-steps run-id)))

(defn- resolve-ready-step
  "Return the ready workflow step to act on for run-id.

  Honors an optional `:step` selector in opts (a materialized strand id),
  resolved against the run's currently ready steps; fails loudly if the
  requested step is not ready. Without `:step`, falls back to the single
  ready step, returning nil when none is ready and throwing when more than
  one is ready (ambiguous)."
  [run-id opts]
  (if-let [wanted (:step opts)]
    (or (ready-step-by-id run-id wanted)
        (fail! "Requested workflow step is not ready" {:run-id run-id :step wanted
                                                        :ready (mapv :id (raw-next-steps run-id))}))
    (raw-next-step run-id)))

(defn step-view
  "Return the agent-facing view of a workflow step."
  [step]
  (when step
    (cond-> {:id (:id step)
             :title (:title step)
             :state (:state step)
             :kind (attr step :workflow/role)}
      (attr step :workflow/gate) (assoc :gate (attr step :workflow/gate))
      (attr step :workflow/checkpoint) (assoc :checkpoint (attr step :workflow/checkpoint))
      (attr step :workflow/checkpoint-kind) (assoc :checkpoint-kind (attr step :workflow/checkpoint-kind))
      (attr step :workflow/choices) (assoc :choices (attr step :workflow/choices))
      (attr step :workflow/decision-point) (assoc :decision-point (attr step :workflow/decision-point))
      (or (attr step :workflow/artifact) (attr step :devflow/artifact)) (assoc :artifact (or (attr step :workflow/artifact) (attr step :devflow/artifact)))
      (attr step :workflow/action-ref) (assoc :action-ref (attr step :workflow/action-ref))
      (attr step :workflow/instruction) (assoc :instruction (attr step :workflow/instruction))
      (attr step :skills) (assoc :skills (attr step :skills)))))

(defn next-steps
  "Return agent-facing ready workflow steps for run-id."
  [run-id]
  (mapv step-view (raw-next-steps run-id)))

(defn next-step
  "Return the single ready workflow step for run-id, or fail if ambiguous."
  [run-id]
  (step-view (raw-next-step run-id)))

(def ^:private workflow-work-roles #{"step" "checkpoint" "procedure"})

(defn- workflow-role [strand]
  (attr strand :workflow/role))

(defn- workflow-work-strand? [strand]
  (contains? workflow-work-roles (workflow-role strand)))

(defn- run-work-done?
  "True when every step, checkpoint, and procedure strand in root-id's subgraph is closed."
  [root-id]
  (every? #(= "closed" (:state %))
          (filter workflow-work-strand? (:strands (graph/subgraph [root-id])))))

(defn- root-strand-exists?
  "True when run-id has ever had a root molecule strand, active or closed."
  [run-id]
  (boolean (seq (repl/query [:and
                            [:= [:attr "workflow/run-id"] run-id]
                            [:= [:attr "workflow/role"] "molecule"]]))))

(defn done?
  "Return true when run-id has no active workflow root, or its active root's
  step, checkpoint, and procedure strands are all closed.

  Fails loudly for a run-id that has never had a root strand."
  [run-id]
  (when-not (root-strand-exists? run-id)
    (fail! "Unknown workflow run" {:run-id run-id}))
  (let [root (current-root run-id)]
    (or (nil? root) (run-work-done? (:id root)))))

(declare close-run-if-done!)

(defn start!
  "Start a workflow run and return its agent-facing ready step views.

  `run-id` is the stable active workflow instance handle. `opts` may include
  :family, :definition, :context, and :root-attributes. Returns an empty
  vector when the run has no ready workflow work (e.g. an empty workflow)."
  ([run-id workflow params]
   (start! run-id workflow params {}))
  ([run-id workflow params opts]
   (when (current-root run-id)
     (fail! "Active workflow run already exists" {:run-id run-id}))
   (pour! workflow params (merge opts {:run-id run-id}))
   (close-run-if-done! run-id)
   (next-steps run-id)))

(defn- detail-view [detail]
  (into {} (map (fn [[k v]] [(if (keyword? k) (name k) k) v])) detail))

(defn choice-details
  "Return choice explanations for run-id's current workflow checkpoint, keyed by
  choice name with string-keyed detail maps (the same shape `choice-detail`
  returns for a single choice).

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready."
  ([run-id]
   (choice-details run-id {}))
  ([run-id opts]
   (require-map! opts [:opts])
   (let [step (or (resolve-ready-step run-id opts)
                  (fail! "No ready workflow step" {:run-id run-id}))
         details (attr step :workflow/choice-details)]
     (when-not (= "checkpoint" (attr step :workflow/role))
       (fail! "Current workflow step is not a checkpoint" {:run-id run-id :step (step-view step)}))
     (into {} (map (fn [[k v]] [(if (keyword? k) (name k) k) (detail-view v)])) details))))

(defn choice-detail
  "Return one choice explanation for run-id's current workflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready."
  ([run-id choice]
   (choice-detail run-id choice {}))
  ([run-id choice opts]
   (let [choice (if (keyword? choice) (name choice) (str choice))
         details (choice-details run-id opts)]
     (or (get details choice)
         (fail! "Choice detail not found" {:run-id run-id :choice choice})))))

(declare close-workflow-root!)

(defn- close-run-if-done! [run-id]
  (when-let [root (current-root run-id)]
    (when (run-work-done? (:id root))
      (close-workflow-root! root))))

(defn- close-attributes!
  "Return attributes to merge onto a step closed by complete!, from optional
  `:notes` (string, stored as \"workflow/notes\") and `:attributes` (map)
  opts. Returns nil when neither is present."
  [opts]
  (let [{:keys [notes attributes]} opts]
    (when (and (contains? opts :notes) (not (string? notes)))
      (fail! "Workflow :notes must be a string" {:notes notes}))
    (when (and (contains? opts :attributes) (not (map? attributes)))
      (fail! "Workflow :attributes must be a map" {:attributes attributes}))
    (not-empty (merge (or attributes {})
                      (when notes {"workflow/notes" notes})))))

(defn complete!
  "Close the current ready non-checkpoint workflow step for run-id and return
  the next agent-facing ready step views.

  opts may include `:step` (materialized strand id) to select among multiple
  ready steps; without it, exactly one step must be ready. opts may also
  include `:notes` (string, stored as \"workflow/notes\") and `:attributes`
  (map merged onto the closed step). Closing a gate step (one built with
  `gate`) additionally requires a non-blank `:by`, recorded as
  \"workflow/outcome-by\" to persist who closed it. All validation happens
  before any mutation."
  ([run-id]
   (complete! run-id {}))
  ([run-id opts]
   (require-map! opts [:opts])
   (let [step (or (resolve-ready-step run-id opts)
                  (fail! "No ready workflow step" {:run-id run-id}))]
     (when (= "checkpoint" (attr step :workflow/role))
       (fail! "Cannot complete a checkpoint; use choose!" {:run-id run-id :step (step-view step)}))
     (let [gate (attr step :workflow/gate)]
       (when (and gate (not (non-blank-string? (:by opts))))
         (fail! "Gate steps require a non-blank :by to record who closed them"
                {:run-id run-id :step (step-view step) :gate gate :by (:by opts)}))
       (let [attrs (cond-> (or (close-attributes! opts) {})
                     gate (assoc "workflow/outcome-by" (:by opts)))]
         (repl/update! (:id step) (cond-> {:state "closed"}
                                     (seq attrs) (assoc :attributes attrs)))
         (close-run-if-done! run-id)
         (next-steps run-id))))))

(defn- raw-choice-detail [step choice]
  (let [details (attr step :workflow/choice-details)]
    (or (get details choice)
        (get details (keyword choice)))))

(defn- close-workflow-root! [root]
  (doseq [strand (:strands (graph/subgraph [(:id root)]))]
    (when (and (= "active" (:state strand))
               (contains? #{"molecule" "step" "checkpoint" "procedure"}
                          (attr strand :workflow/role)))
      (repl/update! (:id strand) {:state "closed"}))))

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

(defn- route-plan
  "Return the routing plan for a checkpoint choice with a `:next` continuation,
  or nil for a terminal choice.

  The plan carries the old root and the continuation batch payload, compiled
  once against the same run-id/family/phase `pour!` uses. Continuation params
  come from the `:next` fn: either the merged context+input (workflow-map
  return) or the fn's own `:params` (`{:workflow w :params p}` return, see
  `continuation-plan`); those params are also persisted as the new root's
  `workflow/context`, making the continuation authoritative over its own
  params. The payload is compiled once and applied only after the old root
  closes, so two active roots never share one run-id."
  [run-id step choice input]
  (when-not (map? input)
    (fail! "Choice input must be a map" {:run-id run-id :choice choice :input input}))
  (when-let [next-sym (some-> (raw-choice-detail step choice) detail-view (get "next") symbol)]
    (let [workflow-fn (or (requiring-resolve next-sym)
                          (fail! "Choice next workflow cannot be resolved"
                                 {:run-id run-id :choice choice :next next-sym}))
          root (current-root run-id)
          context (or (attr root :workflow/context) {})
          call-params (merge context input)
          {:keys [workflow params]} (continuation-plan (workflow-fn call-params) call-params)
          payload (compile workflow params
                           {:run-id run-id
                            :family (attr root :workflow/family)
                            :context params
                            :phase :molecule})]
      {:old-root root :payload payload})))

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
  [route step outcome]
  (let [checkpoint-id (:id step)
        closeable (filter (fn [strand]
                            (and (= "active" (:state strand))
                                 (contains? #{"molecule" "step" "checkpoint" "procedure"}
                                            (attr strand :workflow/role))))
                          (:strands (graph/subgraph [(:id (:old-root route))])))
        close-strands (mapv (fn [strand]
                              (cond-> {:ref (keyword (:id strand)) :state "closed"}
                                (= (:id strand) checkpoint-id) (assoc :attributes outcome)))
                            closeable)
        close-refs (into {} (map (fn [strand] [(keyword (:id strand)) (:id strand)])) closeable)
        payload (:payload route)]
    {:refs close-refs
     :strands (into close-strands (:strands payload))
     :edges (:edges payload)}))

(defn choose!
  "Record a checkpoint choice for run-id, optionally pour its continuation,
  and return the next agent-facing ready step views.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready. opts may
  also include `:by`, recorded as \"workflow/outcome-by\" on the closed
  checkpoint alongside \"workflow/outcome\"/\"workflow/outcome-input\" to
  persist who made the choice (unenforced per TEN-002).

  A routed choice (one whose selection carries `:next`) closes out the current
  workflow's remaining steps and pours the continuation under the same run-id,
  all in one transactional `batch/apply!` (see `routed-batch`). All validation
  happens before any mutation."
  ([run-id choice]
   (choose! run-id choice {} {}))
  ([run-id choice input]
   (choose! run-id choice input {}))
  ([run-id choice input opts]
   (require-map! opts [:opts])
   (let [choice (if (keyword? choice) (name choice) (str choice))
         step (or (resolve-ready-step run-id opts)
                  (fail! "No ready workflow checkpoint" {:run-id run-id}))]
     (when-not (= "checkpoint" (attr step :workflow/role))
       (fail! "Current workflow step is not a checkpoint" {:run-id run-id :step (step-view step)}))
     (let [choices (set (attr step :workflow/choices))]
       (when-not (contains? choices choice)
         (fail! "Choice is not valid for checkpoint" {:run-id run-id :choice choice :valid choices})))
     (let [route (route-plan run-id step choice input)
           outcome (cond-> {"workflow/outcome" choice
                            "workflow/outcome-input" input}
                     (contains? opts :by) (assoc "workflow/outcome-by" (:by opts)))]
       (if route
         (batch/apply! (routed-batch route step outcome))
         (repl/update! (:id step) {:attributes outcome :state "closed"}))
       ;; also covers a routed continuation that poured no active work, so the
       ;; new root cannot linger active on a logically finished run
       (close-run-if-done! run-id)
       (next-steps run-id)))))

(defn install!
  "Return installation metadata for this alpha workflow spool."
  []
  {:installed true
   :namespace 'skein.spools.workflow
   :workflow {:builder 'skein.spools.workflow/workflow
              :step 'skein.spools.workflow/step
              :gate 'skein.spools.workflow/gate
              :checkpoint 'skein.spools.workflow/checkpoint
              :call 'skein.spools.workflow/call
              :param 'skein.spools.workflow/param
              :compiler 'skein.spools.workflow/compile
              :pourer 'skein.spools.workflow/pour!
              :wisp 'skein.spools.workflow/wisp!
              :bond 'skein.spools.workflow/bond!
              :squash 'skein.spools.workflow/squash!
              :burn 'skein.spools.workflow/burn!}
   :runtime {:start 'skein.spools.workflow/start!
             :current-root 'skein.spools.workflow/current-root
             :active-runs 'skein.spools.workflow/active-runs
             :next-step 'skein.spools.workflow/next-step
             :next-steps 'skein.spools.workflow/next-steps
             :complete 'skein.spools.workflow/complete!
             :choose 'skein.spools.workflow/choose!
             :choice-detail 'skein.spools.workflow/choice-detail
             :choice-details 'skein.spools.workflow/choice-details
             :done? 'skein.spools.workflow/done?}})
