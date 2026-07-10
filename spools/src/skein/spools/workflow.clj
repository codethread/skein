(ns skein.spools.workflow
  "Alpha workflow spool for molecule and wisp-style strand graphs.

  A workflow definition is plain data. `compile` turns that data into a Skein
  batch payload, while `pour!` and `wisp!` materialize persistent molecules and
  ephemeral wisps through the public batch alpha surface. This namespace owns no
  privileged runtime state; it composes existing strand graph primitives."
  (:refer-clojure :exclude [compile])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.batch.alpha :as batch]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as api]
            [skein.spools.format :as fmt]
            [skein.spools.util :refer [fail! require-valid! attr-get attr-key->str poll-until-deadline!]]))

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
(s/def ::chain boolean?)
;; :each resolves against params at expansion time: a literal sequential, a
;; keyword naming a resolved param, or a fn of the resolved params map.
(s/def ::each #(or (sequential? %) (keyword? %) (fn? %)))
(s/def ::depends-on (s/coll-of ::id-ref :kind vector?))
(s/def ::timeout-secs (s/and integer? (complement neg?)))
(s/def ::poll-ms (s/and integer? (complement neg?)))
(s/def ::self-waiter #{:self})
(s/def ::external-waiter #(and (or (keyword? %) (symbol? %) (non-blank-string? %))
                               (not= :self %)))
(s/def ::condition #(or (keyword? %)
                        (and (vector? %) (#{:= :!=} (first %)) (= 3 (count %)))))
(s/def ::loop (s/keys :opt-un [::count ::each ::chain]))
(s/def ::procedure any?)
(s/def ::call (s/keys :req-un [::id ::procedure]
                      :opt-un [::title :skein.spools.workflow.values/params ::depends-on ::attributes]))
(s/def ::step (s/keys :req-un [::id ::title]
                      :opt-un [::description ::attributes ::state ::depends-on ::condition ::loop]))
(s/def ::workflow-item (s/or :step ::step :call ::call))
(s/def ::steps (s/coll-of ::workflow-item :kind vector?))
(s/def ::workflow (s/keys :req-un [::name ::steps]
                          :opt-un [::params ::attributes ::state ::phase]))

(declare executor-registry)

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
(def ^:private choice-opt-keys #{:key :label :description :next :input :revise})
(def ^:private choice-input-opt-keys #{:key :required :description})

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
                                                 (step :design (fn [{:keys [feature]}] (str "Design " feature)) :self)
                                                 (checkpoint :signoff "Approve design" :choices [:approved :revise])))
                :fields {:params (fmt/reflow "
                                  |Workflow-level map of keyword param names to param definitions. Param
                                  |definitions support boolean :required and optional :default.")}
                :runtime {:start! (fmt/reflow "
                                   |(start! run-id workflow params opts) accepts a workflow map, constructor var,
                                   |or registered workflow keyword. Var/keyword starts derive :definition; absent
                                   |:context defaults from JSON-safe params after keyword values are stringified.")
                          :next-steps "(next-steps run-id selector) filters ready views by keys such as :kind, :gate, :checkpoint, or :checkpoint-kind."
                          :next-gates "(next-gates run-id) or (next-gates run-id waiter) returns ready gate views."
                          :next-checkpoint "Returns the single ready checkpoint view, nil when none, and fails loudly when ambiguous."}
                :registry {:register-workflow! "Register keyword -> fully-qualified constructor symbol for named :next routes and keyword start!/describe."}
                :step (explain :step)
                :gate (explain :gate)
                :checkpoint (explain :checkpoint)
                :call (explain :call)}
     :step {:topic :step
            :summary "A step is one unit of work owned by the driving agent itself. Do the work, then complete it."
            :contract (spec-entry ::step
                                  (fmt/reflow "
                                  |A step definition contains :id and :title plus optional fields; the step
                                  |builder separately validates its required waiter against ::self-waiter,
                                  |which only accepts :self. A non-:self waiter fails loudly, directing to
                                  |gate.")
                                  '(step :implement (fn [{:keys [feature]}] (str "Implement " feature)) :self :depends-on [:design] :attributes {"skills" "clojure"}))
            :fields {:id "Stable local ref, keyword/symbol/string."
                     :title "Human-readable instruction."
                     :waiter (fmt/reflow "
                              |Must be :self — the driving agent does the work itself. Any other value
                              |fails loudly and directs to gate. :self carries no workflow/gate attribute,
                              |so compiled output is identical to a bare step.")
                     :depends-on "Vector of local refs this step waits for."
                     :attributes "Plain metadata stored on the materialized strand."
                     :condition "Keyword param truthiness, or [:= :param value] / [:!= :param value]."
                     :loop (fmt/reflow "
                            |Expansion: {:count n} (items 1..n) or {:each xs} where xs is a literal
                            |sequential, a keyword naming a param, or a fn of params. Add :chain true to
                            |make expansion i depend on expansion i-1 while expansion 0 keeps the step's
                            |declared deps. Expanded steps render against (merge params {:item item :i
                            |idx}); conditions remain params-only/uniform; a downstream :depends-on on the
                            |base loop id fans in to every expanded id.")}}
     :gate {:topic :gate
            :summary "A gate is a step whose completion belongs to an external actor. Wait for the waiter; don't do the work yourself."
            :contract (spec-entry ::step
                                  (fmt/reflow "
                                  |A gate returns step data with a workflow/gate actor hint. Its required
                                  |waiter is separately validated against ::external-waiter: a keyword,
                                  |symbol, or non-blank string, never :self. It takes the same optional
                                  |fields as a step.")
                                  '(gate :ci-green "Wait for CI to pass" :ci :depends-on [:push]))
            :fields {:waiter (fmt/reflow "
                              |Freeform actor hint (keyword/symbol/string) stored as workflow/gate, e.g.
                              |:ci, :human, :subagent; never :self. register-executor! keys a stall
                              |predicate by this same name.")
                     :others "Same optional fields as step: :depends-on, :attributes, :condition, :loop, :description, :state."
                     :workflow/gate (fmt/reflow "
                                     |Marks the step an external wait point, surfaced by step-view as :gate;
                                     |complete! requires :by to close it. A waiter with no registered
                                     |executor always needs attention; a registered executor's stall
                                     |predicate decides.")}}
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
  and the workflow params. With `:chain true`, expansion i depends on expansion
  i-1 while expansion 0 keeps the step's declared dependencies. Non-loop steps
  pass through unchanged."
  [step params]
  (if-let [loop-spec (:loop step)]
    (let [base-id (normalize-ref (:id step) [:step :id])
          items (vec (loop-values loop-spec params))
          expansion-id (fn [idx item]
                         (keyword (str (name base-id) "-" (loop-suffix loop-spec item idx))))]
      (vec (map-indexed
            (fn [idx item]
              (let [env (merge params {:item item :i idx})
                    expanded-id (expansion-id idx item)]
                (cond-> (-> step
                            (dissoc :loop)
                            (assoc :id expanded-id)
                            (update :title render env)
                            (update :description render env)
                            (update :attributes render env))
                  (and (:chain loop-spec) (pos? idx))
                  (assoc :depends-on [(expansion-id (dec idx) (nth items (dec idx)))]))))
            items)))
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
  (condp contains? ref
    included-ids #{ref}
    excluded-deps
    (if (contains? seen ref)
      #{}
      (into #{}
            (mapcat #(resolve-dep-refs included-ids excluded-deps (conj seen ref) ref %))
            (get excluded-deps ref)))
    (fail! "Workflow step depends on an unknown ref" {:step owner-id :missing ref})))

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

(defn- step*
  [id title opts]
  (merge {:id id :title title} opts))

(defn step
  "Return a workflow step definition — a unit of work the driving agent does
  itself.

  `waiter` must be `:self`; there is never a named step owner. Any other value
  fails loudly, directing the caller to `gate` instead. A `:self` step carries
  no `workflow/gate` attribute, so its compiled output is identical to a bare
  step. The result is plain data and may be passed to `workflow` or
  transformed by user code before compilation."
  [id title waiter & {:as opts}]
  (reject-unknown-keys! opts step-opt-keys :step)
  (when-not (s/valid? ::self-waiter waiter)
    (fail! "Step waiter must be :self; use gate for a step an external actor owns"
           {:id id :waiter waiter :explain (s/explain-data ::self-waiter waiter)}))
  (step* id title opts))

(defn gate
  "Return a workflow gate step definition — a step whose completion belongs to
  an external actor rather than the driving agent.

  A gate stays an ordinary step (role `\"step\"`, so done-semantics are
  untouched) stamped with `workflow/gate <waiter>`, a freeform actor hint such
  as `:ci`, `:human`, or `:subagent`. `step-view` surfaces it as `:gate`, and
  `complete!` refuses to close it without a `:by` recording who closed it. The
  driving agent should treat a ready gate as a poll/hand-off point, not work to
  do. `register-executor!` keys a stall predicate by this same waiter name, so
  `await!` can stay silent on a healthy executor-owned gate. Accepts the same
  opts as `step`."
  [id title waiter & {:as opts}]
  (reject-unknown-keys! opts step-opt-keys :gate)
  (when-not (s/valid? ::external-waiter waiter)
    (fail! "Gate waiter must be a keyword, symbol, or non-blank string other than :self"
           {:id id :waiter waiter :explain (s/explain-data ::external-waiter waiter)}))
  (-> (step* id title opts)
      (update :attributes merge {"workflow/gate" (name waiter)})))

(defn- choice-key [choice]
  (cond
    (map? choice) (:key choice)
    :else choice))

(defn- choice-name [choice]
  (let [k (choice-key choice)]
    (cond
      (or (keyword? k) (symbol? k)) (name k)
      (non-blank-string? k) k
      :else (fail! "Workflow checkpoint choices require a non-blank key" {:choice choice}))))

(defn- input-key-name [decl]
  (let [k (:key decl)]
    (cond
      (or (keyword? k) (symbol? k)) (name k)
      (non-blank-string? k) k
      :else (fail! "Workflow choice :input entries require a non-blank :key" {:input decl}))))

(defn- choice-input-attr
  "Return the JSON-safe stored form of a checkpoint choice's `:input` declaration:
  a vector of string-keyed maps carrying each input key's name, its required flag,
  and an optional description. Rejects unknown declaration keys loudly (TEN-003),
  matching the other builder opts."
  [input]
  (when-not (vector? input)
    (fail! "Workflow choice :input must be a vector of declaration maps" {:input input}))
  (mapv (fn [decl]
          (require-map! decl [:choice :input])
          (reject-unknown-keys! decl choice-input-opt-keys :choice-input)
          (when (and (contains? decl :required) (not (boolean? (:required decl))))
            (fail! "Workflow choice :input :required must be a boolean" {:input decl}))
          (when (and (contains? decl :description) (not (string? (:description decl))))
            (fail! "Workflow choice :input :description must be a string" {:input decl}))
          (cond-> {"key" (input-key-name decl)
                   "required" (boolean (:required decl))}
            (:description decl) (assoc "description" (:description decl))))
        input))

(defn- revise-params-attr
  "Return the override params stored for a checkpoint choice's `:revise` directive.
  `:revise` must be a map carrying a `:params` map (TEN-003); the params are the
  authoritative overrides re-poured over the run's own definition at `choose!`."
  [revise]
  (when-not (and (map? revise) (map? (:params revise)))
    (fail! "Workflow choice :revise must be a map with a :params map" {:revise revise}))
  (:params revise))

(defn- choice-detail-attr [choice]
  (when (map? choice)
    (let [k (choice-name choice)]
      [k (cond-> {}
           (:label choice) (assoc "label" (:label choice))
           (:description choice) (assoc "description" (:description choice))
           (:next choice) (assoc "next" (str (:next choice)))
           (:revise choice) (assoc "revise" (revise-params-attr (:revise choice)))
           (:input choice) (assoc "input" (choice-input-attr (:input choice))))])))

(defn- choice-details-attr [choices]
  (not-empty (into {} (keep choice-detail-attr choices))))

(defn- reject-unknown-choice-keys! [choices]
  (doseq [choice choices :when (map? choice)]
    (reject-unknown-keys! choice choice-opt-keys :choice))
  choices)

(defn- reject-next-and-revise! [choices]
  (doseq [choice choices
          :when (and (map? choice) (contains? choice :next) (contains? choice :revise))]
    (fail! "Workflow choice :next and :revise are mutually exclusive"
           {:choice (choice-name choice)}))
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
  maps with `:key`, `:label`, `:description`, optional `:next` routing (a symbol
  or a registered workflow name — see `register-workflow!`), an optional
  `:revise {:params {...}}` directive (mutually exclusive with `:next`) that
  re-pours the run's own definition with authoritative param overrides, and an
  optional `:input` declaration (a vector of `{:key :required :description}` maps
  surfaced with the choice and enforced by `choose!`).

  A `:kind :human` checkpoint (the default) is the canonical human-in-the-loop
  signal: the builder auto-stamps `workflow/hitl \"true\"` so callers never set
  it by hand."
  [id title & {:as opts}]
  (reject-unknown-keys! opts checkpoint-opt-keys :checkpoint)
  (let [kind (or (:kind opts) :human)
        choices (some-> (:choices opts) reject-unknown-choice-keys! reject-next-and-revise! require-unique-choice-keys!)
        details (choice-details-attr choices)]
    (-> (step* id title (dissoc opts :kind :choices))
        (update :attributes merge
                {"workflow/role" "checkpoint"
                 "workflow/checkpoint" (name id)
                 "workflow/checkpoint-kind" (name kind)}
                (when (= kind :human)
                  {"workflow/hitl" "true"})
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

(defn- resolve-and-normalize
  "Return `[rendered-workflow resolved-params root-ref normalized-steps]` — the
  materialization-free front half shared by `compile` and `describe`.

  Validates the workflow and params, resolves params (defaults + required
  checks), renders workflow-level fields (step render fns stay live so
  `normalize-steps` can render loop steps against per-item params), and
  expands/condition-filters/splices the steps. Materializes nothing."
  [workflow params opts]
  (require-map! workflow [:workflow])
  (require-valid! :skein.spools.workflow.values/params params "Invalid workflow params")
  (require-valid-workflow! workflow)
  (let [params (resolve-params workflow params)
        rendered (assoc (render (dissoc workflow :steps) params)
                        :steps (:steps workflow))
        _ (require-non-blank! (:name rendered) [:name])
        root-ref (normalize-ref (or (:root-ref opts) :molecule) [:root-ref])
        steps (normalize-steps rendered params root-ref)]
    [rendered params root-ref steps]))

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
   (let [phase (or (:phase opts) (:phase workflow) :molecule)
         [workflow _params root-ref steps] (resolve-and-normalize workflow params opts)
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

(defn- step-attr
  "Read string-keyed workflow attribute `k` off a normalized step's `:attributes`
  map (builders write the `workflow/*` vocabulary string-keyed)."
  [step k]
  (get (:attributes step) k))

(defn- describe-choice
  "Project one checkpoint choice into `{:key … :label … :description … :input …
  :next|:revise …}` from its stored `workflow/choice-details` entry (`detail`,
  nil for a bare-keyword choice)."
  [name detail]
  (cond-> {:key name}
    (get detail "label") (assoc :label (get detail "label"))
    (get detail "description") (assoc :description (get detail "description"))
    (get detail "input") (assoc :input (get detail "input"))
    (get detail "next") (assoc :next (get detail "next"))
    (get detail "revise") (assoc :revise (get detail "revise"))))

(defn- describe-choices
  "Project a checkpoint step's choices in declared order, or nil for a non-checkpoint."
  [step]
  (when-let [choices (step-attr step "workflow/choices")]
    (let [details (or (step-attr step "workflow/choice-details") {})]
      (mapv #(describe-choice % (get details %)) choices))))

(defn- describe-step
  "Project one normalized step into its compile-time description."
  [step]
  (cond-> {:id (normalize-ref (:id step) [:steps :id])
           :title (:title step)
           :kind (or (step-attr step "workflow/role") "step")
           :depends-on (:depends-on step)}
    (:condition step) (assoc :condition (:condition step))
    (step-attr step "workflow/gate") (assoc :gate (step-attr step "workflow/gate"))
    (describe-choices step) (assoc :choices (describe-choices step))))

(defn- workflow-var-symbol [v]
  (let [m (meta v)]
    (symbol (str (ns-name (:ns m))) (str (:name m)))))

(declare workflow-definition)

(defn- finite-json-number? [value]
  (cond
    (instance? Double value) (Double/isFinite value)
    (instance? Float value) (Float/isFinite value)
    :else true))

(defn- json-safe-context-value [value path]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    (and (number? value) (finite-json-number? value)) value
    (number? value) (fail! "Workflow params cannot be defaulted into workflow/context; non-finite numbers are not JSON-safe"
                           {:path path :value value :type (some-> value type str)})
    (or (string? value) (boolean? value)) value
    (map? value) (into {} (map (fn [[k v]] [k (json-safe-context-value v (conj path k))])) value)
    (sequential? value) (mapv (fn [[idx v]] (json-safe-context-value v (conj path idx))) (map-indexed vector value))
    :else (fail! "Workflow params cannot be defaulted into workflow/context; pass :context explicitly"
                 {:path path :value value :type (some-> value type str)})))

(defn- default-context [params]
  (when-not (map? params)
    (fail! "Workflow context params must be a map" {:params params}))
  (json-safe-context-value params []))

(defn- workflow-input-plan [workflow-input params]
  (cond
    (var? workflow-input)
    (let [sym (workflow-var-symbol workflow-input)]
      {:workflow (@workflow-input params) :definition sym})

    (keyword? workflow-input)
    (let [sym (workflow-definition workflow-input)
          f (or (requiring-resolve sym)
                (fail! "Registered workflow cannot be resolved" {:name workflow-input :definition sym}))]
      {:workflow (f params) :definition sym})

    :else {:workflow workflow-input}))

(defn describe
  "Return a compile-time projection of `workflow` without materializing any strand.

  `workflow` may be a workflow map, a constructor var, or a registered workflow
  keyword. Loop/call expansion and condition filtering apply exactly as
  `compile` runs them, so the description matches what would pour for `params`:
  excluded steps are absent, procedure joins appear as `:procedure` steps, and
  each checkpoint's choices carry their declared `:input` and their
  `:next`/`:revise` routing. The result is `{:name … :steps [{:id :title :kind
  :depends-on :condition :gate :choices [{:key :label :description :input
  :next|:revise} …]} …]}`.

  `(describe workflow)` resolves param defaults and fails loudly listing any
  required params without a default; pass `params` to describe a definition that
  needs them."
  ([workflow]
   (describe workflow {}))
  ([workflow params]
   (let [{workflow :workflow} (workflow-input-plan workflow params)
         [rendered _ _ steps] (resolve-and-normalize workflow params {})]
     {:name (:name rendered)
      :steps (mapv describe-step steps)})))

(defn- pour-with-rt!
  [rt workflow params opts]
  (batch/apply! rt (compile workflow params (merge opts {:phase :molecule}))))

(defn pour!
  "Materialize `workflow` as a persistent molecule strand graph."
  ([workflow]
   (pour! workflow {}))
  ([workflow params]
   (pour! workflow params {}))
  ([workflow params opts]
   (let [rt (current/runtime)]
     (pour-with-rt! rt workflow params opts))))

(defn- wisp-with-rt!
  [rt workflow params opts]
  (batch/apply! rt (compile workflow params (merge opts {:phase :wisp}))))

(defn wisp!
  "Materialize `workflow` as an ephemeral wisp strand graph.

  Wisps are normal Skein strands marked with workflow attributes so userland can
  burn or squash them explicitly."
  ([workflow]
   (wisp! workflow {}))
  ([workflow params]
   (wisp! workflow params {}))
  ([workflow params opts]
   (let [rt (current/runtime)]
     (wisp-with-rt! rt workflow params opts))))

(defn- attr
  "Read attribute `k` (a keyword such as `:workflow/role`) from `strand`'s
  attribute map, tolerating either keyword- or string-keyed maps, via the shared
  spool-tier tolerant reader (`skein.spools.util/attr-get`)."
  [strand k]
  (attr-get strand k))

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
  (let [rt (current/runtime)]
    (api/update rt right-id {:edges [{:type "depends-on" :to left-id
                                      :attributes {:workflow/bond "sequential"}}]})))

(defn- burn-with-rt!
  [rt root-id]
  (let [ids (mapv :id (:strands (graph/subgraph rt [root-id])))]
    (if (seq ids)
      (graph/burn-by-ids! rt ids)
      {:burned [] :count 0})))

(defn burn!
  "Burn a materialized molecule or wisp subgraph rooted at `root-id`."
  [root-id]
  (let [rt (current/runtime)]
    (burn-with-rt! rt root-id)))

(defn squash!
  "Replace a materialized wisp/molecule with one digest strand, then burn its graph."
  ([root-id title]
   (squash! root-id title {}))
  ([root-id title attributes]
   (let [rt (current/runtime)
         subgraph (graph/subgraph rt [root-id])
         digest (api/add rt {:title title
                             :state "closed"
                             :attributes (merge {"workflow/role" "digest"
                                                 "workflow/squashed-root" root-id
                                                 "workflow/squashed-count" (count (:strands subgraph))}
                                                attributes)})]
     (burn-with-rt! rt root-id)
     digest)))

(defn- active-runs-with-rt
  ([rt]
   (api/list rt [:and [:= :state "active"] [:= [:attr "workflow/role"] "molecule"]] {}))
  ([rt family]
   (api/list rt [:and
                 [:= :state "active"]
                 [:= [:attr "workflow/role"] "molecule"]
                 [:= [:attr "workflow/family"] family]] {})))

(defn active-runs
  "Return active workflow root strands, optionally filtered by family."
  ([]
   (let [rt (current/runtime)]
     (active-runs-with-rt rt)))
  ([family]
   (let [rt (current/runtime)]
     (active-runs-with-rt rt family))))

(defn- current-root-with-rt
  [rt run-id]
  (let [roots (api/list rt [:and
                            [:= :state "active"]
                            [:= [:attr "workflow/run-id"] run-id]
                            [:= [:attr "workflow/role"] "molecule"]] {})]
    (case (count roots)
      0 nil
      1 (first roots)
      (throw (ex-info "Multiple active workflow roots found" {:run-id run-id :roots roots})))))

(defn current-root
  "Return the single active workflow root for run-id, nil when absent, or fail if ambiguous."
  [run-id]
  (let [rt (current/runtime)]
    (current-root-with-rt rt run-id)))

(defn- raw-next-steps
  "Return the run's ready workflow work strands.

  A root with an active depends-on blocker (a `bond!` from another molecule)
  parent-blocks the whole run: its steps stay hidden until the blocking root
  closes, even though each step's own deps may be satisfied."
  [rt run-id]
  (let [root (current-root-with-rt rt run-id)
        ready (api/ready rt)
        root-ready? (and root (some #(= (:id %) (:id root)) ready))
        ids (when root (set (map :id (:strands (graph/subgraph rt [(:id root)])))))]
    (if-not root-ready?
      []
      (->> ready
           (filter #(contains? ids (:id %)))
           (remove #(contains? #{"molecule" "procedure"} (attr % :workflow/role)))
           vec))))

(defn- raw-next-step [rt run-id]
  (let [steps (raw-next-steps rt run-id)]
    (case (count steps)
      0 nil
      1 (first steps)
      (throw (ex-info "Multiple workflow next steps are ready" {:run-id run-id :steps steps})))))

(defn- ready-step-by-id
  "Return the ready step matching id among run-id's currently ready steps, or nil."
  [rt run-id id]
  (some #(when (= (:id %) id) %) (raw-next-steps rt run-id)))

(defn- resolve-ready-step
  "Return the ready workflow step to act on for run-id.

  Honors an optional `:step` selector in opts (a materialized strand id),
  resolved against the run's currently ready steps; fails loudly if the
  requested step is not ready. Without `:step`, falls back to the single
  ready step, returning nil when none is ready and throwing when more than
  one is ready (ambiguous)."
  [rt run-id opts]
  (if-let [wanted (:step opts)]
    (or (ready-step-by-id rt run-id wanted)
        (fail! "Requested workflow step is not ready" {:run-id run-id :step wanted
                                                       :ready (mapv :id (raw-next-steps rt run-id))}))
    (raw-next-step rt run-id)))

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

(defn- next-steps-with-rt
  [rt run-id selector]
  (require-map! selector [:selector])
  (let [matches? (fn [step]
                   (every? (fn [[k v]] (= v (get step k))) selector))]
    (->> (raw-next-steps rt run-id)
         (map #(assoc (step-view %) :run-id run-id))
         (filter matches?)
         vec)))

(defn next-steps
  "Return agent-facing ready workflow steps for run-id.

  Each view carries `:run-id` so a stage cutover is visible in-band; a bare
  `step-view` on a strand without run context stays unchanged. An optional
  selector map filters by `:kind`, `:gate`, `:checkpoint`, or
  `:checkpoint-kind`."
  ([run-id]
   (next-steps run-id {}))
  ([run-id selector]
   (let [rt (current/runtime)]
     (next-steps-with-rt rt run-id selector))))

(defn next-gates
  "Return ready gate step views for run-id, optionally filtered by waiter."
  ([run-id]
   (let [rt (current/runtime)]
     (filterv :gate (next-steps-with-rt rt run-id {}))))
  ([run-id waiter]
   (let [rt (current/runtime)]
     (next-steps-with-rt rt run-id {:gate (name waiter)}))))

(defn next-checkpoint
  "Return the single ready checkpoint view for run-id, nil if none, or fail if ambiguous."
  [run-id]
  (let [rt (current/runtime)
        steps (next-steps-with-rt rt run-id {:kind "checkpoint"})]
    (case (count steps)
      0 nil
      1 (first steps)
      (fail! "Multiple workflow checkpoints are ready" {:run-id run-id :steps steps}))))

(defn next-step
  "Return the single ready workflow step for run-id, or fail if ambiguous.

  The view carries `:run-id` (see `next-steps`)."
  [run-id]
  (let [rt (current/runtime)]
    (some-> (raw-next-step rt run-id) step-view (assoc :run-id run-id))))

(def ^:private workflow-work-roles #{"step" "checkpoint" "procedure"})

(defn- workflow-role [strand]
  (attr strand :workflow/role))

(defn- workflow-work-strand? [strand]
  (contains? workflow-work-roles (workflow-role strand)))

(defn- run-work-done?
  "True when every step, checkpoint, and procedure strand in root-id's subgraph is closed."
  [rt root-id]
  (every? #(= "closed" (:state %))
          (filter workflow-work-strand? (:strands (graph/subgraph rt [root-id])))))

(defn- root-strand-exists?
  "True when run-id has ever had a root molecule strand, active or closed."
  [rt run-id]
  (boolean (seq (api/list rt [:and
                              [:= [:attr "workflow/run-id"] run-id]
                              [:= [:attr "workflow/role"] "molecule"]] {}))))

(defn- done-with-rt?
  [rt run-id]
  (when-not (root-strand-exists? rt run-id)
    (fail! "Unknown workflow run" {:run-id run-id}))
  (let [root (current-root-with-rt rt run-id)]
    (or (nil? root) (run-work-done? rt (:id root)))))

(defn done?
  "Return true when run-id has no active workflow root, or its active root's
  step, checkpoint, and procedure strands are all closed.

  Fails loudly for a run-id that has never had a root strand."
  [run-id]
  (let [rt (current/runtime)]
    (done-with-rt? rt run-id)))

(defn- run-molecule-roots
  "Return every molecule root ever poured for run-id (active or closed), ordered
  by creation. Empty when the run never existed."
  [rt run-id]
  (->> (api/list rt [:and
                     [:= [:attr "workflow/run-id"] run-id]
                     [:= [:attr "workflow/role"] "molecule"]] {})
       (sort-by :created_at)
       vec))

(def ^:private history-event-roles
  ;; Procedure joins (role "procedure") are engine bookkeeping with no
  ;; user-facing outcome, so run-history projects only step/checkpoint closes.
  #{"step" "checkpoint"})

(defn- history-event
  "Project one closed step/checkpoint strand into a history event. A checkpoint is
  a `:choice`, a closed gate is a `:gate-closed`, and any other step is a
  `:step-closed`; `:at` is the strand's `updated_at`, used for event ordering."
  [strand]
  (let [role (attr strand :workflow/role)
        gate (attr strand :workflow/gate)
        type (cond
               (= "checkpoint" role) :choice
               gate :gate-closed
               :else :step-closed)]
    (cond-> {:type type
             :id (:id strand)
             :title (:title strand)
             :at (:updated_at strand)}
      (attr strand :workflow/outcome) (assoc :outcome (attr strand :workflow/outcome))
      (attr strand :workflow/outcome-by) (assoc :by (attr strand :workflow/outcome-by))
      (attr strand :workflow/outcome-input) (assoc :input (attr strand :workflow/outcome-input))
      (attr strand :workflow/outcome-notes) (assoc :notes (attr strand :workflow/outcome-notes)))))

(defn- molecule-history
  "Project one molecule root into `{:root {…} :events [event …]}`, its events being
  every closed step/checkpoint strand in the root's subgraph, ordered by `:at`."
  [rt root]
  (let [events (->> (:strands (graph/subgraph rt [(:id root)]))
                    (filter #(and (= "closed" (:state %))
                                  (contains? history-event-roles (attr % :workflow/role))))
                    (map history-event)
                    (sort-by :at)
                    vec)]
    {:root (cond-> {:id (:id root)
                    :title (:title root)
                    :state (:state root)
                    :created_at (:created_at root)}
             (attr root :devflow/stage) (assoc :stage (attr root :devflow/stage)))
     :events events}))

(defn run-history
  "Return a read-only, creation-ordered projection of every molecule ever poured
  for run-id (any state) as a vector of
  `{:root {:id :title :stage :state :created_at} :events [{:type :id :title
  :outcome :by :input :notes :at} …]}` maps.

  `:type` is `:step-closed`, `:choice`, or `:gate-closed`; events are ordered by
  their strand's `updated_at`; `:stage` is present only when a molecule carries a
  `devflow/stage`. Writes nothing and fails loudly (TEN-003) for a run that never
  had a root strand."
  [run-id]
  (let [rt (current/runtime)
        roots (run-molecule-roots rt run-id)]
    (when (empty? roots)
      (fail! "Unknown workflow run" {:run-id run-id}))
    (mapv #(molecule-history rt %) roots)))

(defn- run-summary
  "Return a compact, JSON-safe summary of `history`: one string-keyed entry per
  molecule (in creation order) carrying its stage title, its `devflow/stage` when
  present, and the ordered checkpoint outcomes recorded in that molecule."
  [history]
  (mapv (fn [{:keys [root events]}]
          (cond-> {"title" (:title root)
                   "outcomes" (->> events (filter #(= :choice (:type %))) (mapv :outcome))}
            (:stage root) (assoc "stage" (:stage root))))
        history))

(defn archive-run!
  "Squash a finished run's molecules into one closed digest strand and return it.

  Fails loudly (TEN-003) for an unknown run or one that still has an active root.
  Every molecule subgraph of the run is burned; the single digest is stamped
  `workflow/role \"digest\"`, `workflow/run-id`, `workflow/squashed-count`, and a
  compact JSON-safe `workflow/summary` of the history (stage titles + checkpoint
  outcomes). opts may override the digest `:title` and merge extra `:attributes`."
  ([run-id]
   (archive-run! run-id {}))
  ([run-id {:keys [title attributes]}]
   (let [rt (current/runtime)]
     (when-not (root-strand-exists? rt run-id)
       (fail! "Unknown workflow run" {:run-id run-id}))
     (when (current-root-with-rt rt run-id)
       (fail! "Cannot archive a run with an active root" {:run-id run-id}))
     (let [roots (run-molecule-roots rt run-id)
           summary (run-summary (mapv #(molecule-history rt %) roots))
           squashed-count (reduce + 0 (map #(count (:strands (graph/subgraph rt [(:id %)]))) roots))
           digest (api/add rt {:title (or title (str "Digest for run " run-id))
                               :state "closed"
                               :attributes (merge {"workflow/role" "digest"
                                                   "workflow/run-id" run-id
                                                   "workflow/squashed-count" squashed-count
                                                   "workflow/summary" summary}
                                                  attributes)})]
       (doseq [root roots]
         (burn-with-rt! rt (:id root)))
       digest))))

(defn- executor-for
  "Return the registered stall predicate for a ready gate's `waiter` name, or nil."
  [waiter]
  (get @executor-registry waiter))

(defn- attention
  "Return the current attention state for workflow run-id.

  `:done` when finished; `:checkpoint` when a checkpoint is ready; `:step`
  when a ready `:self` step needs the driving agent (kills the footgun of a
  ready step burying itself under `:waiting`); `:gate` when a ready gate's
  waiter has no registered executor; `:stalled` when a registered executor's
  stall predicate reports detail for one of its gates; else `:waiting`, which
  now means the whole ready frontier is executor-owned and healthy."
  [rt run-id]
  (let [ready (next-steps-with-rt rt run-id {})
        done (done-with-rt? rt run-id)
        checkpoint (first (filter #(= "checkpoint" (:kind %)) ready))
        self-step (first (filter #(and (not= "checkpoint" (:kind %)) (not (:gate %))) ready))
        unowned-gate (first (filter #(and (:gate %) (not (executor-for (:gate %)))) ready))
        stalled (some (fn [step]
                        (when-let [pred (and (:gate step) (executor-for (:gate step)))]
                          (when-let [detail (pred step)]
                            {:gate step :stall detail})))
                      ready)]
    (cond
      done {:reason :done :ready ready :done true}
      checkpoint {:reason :checkpoint :ready ready :done false :detail checkpoint}
      self-step {:reason :step :ready ready :done false :detail self-step}
      unowned-gate {:reason :gate :ready ready :done false :detail unowned-gate}
      stalled {:reason :stalled :ready ready :done false :detail stalled}
      :else {:reason :waiting :ready ready :done false})))

(defn- timeout-secs-opt
  [opts]
  (require-valid! ::timeout-secs (get opts :timeout-secs 1800)
                  "await! :timeout-secs must be a non-negative integer"))

(defn- poll-ms-opt
  [opts]
  (require-valid! ::poll-ms (get opts :poll-ms 250)
                  "await! :poll-ms must be a non-negative integer"))

(defn await!
  "Block until workflow run-id is done, at a checkpoint, at a ready `:self`
  step, at a gate whose waiter has no registered executor, at an
  executor-owned gate whose stall predicate reports detail, or timed out.

  opts: `:timeout-secs` (default 1800) and `:poll-ms` (default 250, matching
  the agent-run await surface). Fails loudly for a non-negative-integer
  violation on either, agreeing with `skein.spools.roster/await-quiet!`'s
  `:timeout-ms`/`:poll-ms` validation.

  The three-arg `(runtime run-id opts)` arity threads the target runtime
  explicitly, agreeing with `skein.spools.roster/await-quiet!`; the shorter
  arities resolve `current/runtime` as the ergonomic default for trusted
  in-process callers."
  ([run-id]
   (await! run-id {}))
  ([run-id opts]
   (await! (current/runtime) run-id opts))
  ([runtime run-id opts]
   (let [timeout-secs (timeout-secs-opt opts)
         poll-ms (poll-ms-opt opts)]
     (poll-until-deadline!
      {:deadline (+ (System/currentTimeMillis) (* 1000 (long timeout-secs)))
       :poll-ms poll-ms
       :check #(attention runtime run-id)
       :pred->result (fn [state] (when (not= :waiting (:reason state)) state))
       :on-timeout (fn [state] (assoc state :reason :timeout))}))))

(defn- run-result
  "Return the run-mutation result shape: the run's ready step views plus its
  done-ness, in one map. Every run-mutating op (`start!`, `complete!`,
  `choose!`, `advance!`) returns this so callers never guess whether an empty
  `:ready` means the run finished or merely stalled."
  [rt run-id]
  {:ready (next-steps-with-rt rt run-id {})
   :done (done-with-rt? rt run-id)})

(declare close-run-if-done!)

(defn start!
  "Start a workflow run and return the `{:ready [step-view ...] :done boolean}`
  result shape.

  `run-id` is the stable active workflow instance handle. `workflow` may be a
  pre-built workflow map, a constructor var, or a registered workflow keyword.
  Var/keyword starts derive `:definition`; when `:context` is absent, params are
  persisted as context after keyword values are stringified and non-JSON-safe
  values are rejected loudly. `opts` may include :family, :definition, :context,
  and :root-attributes. `:ready` is empty when the run has no ready workflow work
  (e.g. an empty workflow, which also reports `:done true`)."
  ([run-id workflow params]
   (start! run-id workflow params {}))
  ([run-id workflow params opts]
   (let [rt (current/runtime)]
     (when (current-root-with-rt rt run-id)
       (fail! "Active workflow run already exists" {:run-id run-id}))
     (let [{resolved-workflow :workflow derived-definition :definition} (workflow-input-plan workflow params)
           opts (cond-> opts
                  (and derived-definition (not (contains? opts :definition)))
                  (assoc :definition derived-definition)
                  (not (contains? opts :context))
                  (assoc :context (default-context params)))]
       (pour-with-rt! rt resolved-workflow params (merge opts {:run-id run-id})))
     (close-run-if-done! rt run-id)
     (run-result rt run-id))))

(defn- string-keyed [m]
  (into {} (map (fn [[k v]] [(attr-key->str k) v])) m))

(defn- detail-view
  "Return a checkpoint choice's stored detail map with string keys. The nested
  `input` declaration (a vector of maps) is string-keyed too, because the JSON
  round-trip keywordizes nested map keys on read (`skein.core.db/<-json`)."
  [detail]
  (reduce-kv (fn [acc k v]
               (let [k (attr-key->str k)]
                 (assoc acc k (if (= k "input") (mapv string-keyed v) v))))
             {}
             detail))

(defn choice-details
  "Return choice explanations for run-id's current workflow checkpoint, keyed by
  choice name with string-keyed detail maps (the same shape `choice-detail`
  returns for a single choice).

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready."
  ([run-id]
   (choice-details run-id {}))
  ([run-id opts]
   (let [rt (current/runtime)]
     (require-map! opts [:opts])
     (let [step (or (resolve-ready-step rt run-id opts)
                    (fail! "No ready workflow step" {:run-id run-id}))
           details (attr step :workflow/choice-details)]
       (when-not (= "checkpoint" (attr step :workflow/role))
         (fail! "Current workflow step is not a checkpoint" {:run-id run-id :step (step-view step)}))
       (into {} (map (fn [[k v]] [(attr-key->str k) (detail-view v)])) details)))))

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

(defn- close-run-if-done! [rt run-id]
  (when-let [root (current-root-with-rt rt run-id)]
    (when (run-work-done? rt (:id root))
      (close-workflow-root! rt root))))

(defn- close-attributes!
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

(defn- cascade-join-ids
  "Return the `procedure` join strand ids under root-id's run that become fully
  satisfied once `closing-ids` close, cascading through chained joins.

  A join (role `\"procedure\"`) depends-on its expansion's exit steps, so it is
  closeable once every strand it depends-on is closed (or is itself closing);
  closing the last inner step beneath a join thus closes the join in the same
  transaction, and a join that is the last inner step of an outer join cascades
  likewise. Joins never surface as ready work (see `raw-next-steps`)."
  [rt root-id closing-ids]
  (let [strands (:strands (graph/subgraph rt [root-id]))
        by-id (into {} (map (juxt :id identity)) strands)
        deps (depends-on-edges rt (map :id strands))
        joins (filter #(= "procedure" (attr % :workflow/role)) strands)]
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

(defn- close-batch
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

(defn complete!
  "Close the current ready non-checkpoint workflow step for run-id and return
  the `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step` (materialized strand id) to select among multiple
  ready steps; without it, exactly one step must be ready. opts may also
  include `:notes` (string, stored as \"workflow/outcome-notes\") and `:attributes`
  (map merged onto the closed step). A non-blank `:by` is recorded as
  \"workflow/outcome-by\" on any step it is supplied for, but is only required
  when closing a gate step (one built with `gate`).

  When the closed step is the last active inner step beneath a `procedure`
  join, the join closes in the same transaction (see `cascade-join-ids`). All
  validation happens before any mutation."
  ([run-id]
   (complete! run-id {}))
  ([run-id opts]
   (let [rt (current/runtime)]
     (require-map! opts [:opts])
     (let [step (or (resolve-ready-step rt run-id opts)
                    (fail! "No ready workflow step" {:run-id run-id}))]
       (when (= "checkpoint" (attr step :workflow/role))
         (fail! "Cannot complete a checkpoint; use choose!" {:run-id run-id :step (step-view step)}))
       (let [gate (attr step :workflow/gate)
             by (:by opts)]
         (when (and gate (not (non-blank-string? by)))
           (fail! "Gate steps require a non-blank :by to record who closed them"
                  {:run-id run-id :step (step-view step) :gate gate :by by}))
         (let [attrs (cond-> (or (close-attributes! opts) {})
                       (non-blank-string? by) (assoc "workflow/outcome-by" by))
               root (current-root-with-rt rt run-id)
               join-ids (cascade-join-ids rt (:id root) #{(:id step)})]
           (batch/apply! rt (close-batch (:id step) (not-empty attrs) join-ids))
           (close-run-if-done! rt run-id)
           (run-result rt run-id)))))))

(defn- raw-choice-detail [step choice]
  (let [details (attr step :workflow/choice-details)]
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
  (when-let [decls (some-> (raw-choice-detail step choice) detail-view (get "input"))]
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

(defn- close-workflow-root! [rt root]
  (doseq [strand (:strands (graph/subgraph rt [(:id root)]))]
    (when (and (= "active" (:state strand))
               (contains? #{"molecule" "step" "checkpoint" "procedure"}
                          (attr strand :workflow/role)))
      (api/update rt (:id strand) {:state "closed"}))))

(defonce ^{:private true
           :doc "Weaver-lifetime map of registered workflow name (keyword) ->
  fully qualified constructor symbol. Re-registered from startup code like named
  queries and patterns; `defonce` so a bare namespace reload keeps existing runs'
  named routes resolvable until startup re-registration runs."}
  workflow-name-registry
  (atom {}))

(defonce ^{:private true
           :doc "Weaver-lifetime map of gate waiter name (string) -> executor stall
  predicate. A predicate receives a ready gate step view and returns truthy
  detail when its executor believes coordinator attention is needed. A waiter
  with no registered executor always surfaces immediately as :gate; a
  registered executor's gate stays silent until its predicate fires."}
  executor-registry
  (atom {}))

(defn register-workflow!
  "Register a workflow constructor under a stable keyword `name`.

  `name` is a keyword; `constructor-sym` is a fully qualified symbol resolving to
  a workflow constructor. Registration is weaver-lifetime in-memory state
  (mirroring named queries/patterns), re-established from startup config. A
  duplicate `name` replaces the prior entry, so reloading a workflow re-points
  existing in-flight runs' named `:next` routes at the new constructor. Returns
  `name`."
  [name constructor-sym]
  (when-not (keyword? name)
    (fail! "Workflow registry name must be a keyword" {:name name}))
  (when-not (qualified-symbol? constructor-sym)
    (fail! "Workflow registry constructor must be a fully qualified symbol"
           {:name name :constructor constructor-sym}))
  (swap! workflow-name-registry assoc name constructor-sym)
  name)

(defn register-executor!
  "Register a stall predicate for gate waiter `waiter` (a keyword/symbol/string
  matching a `gate` waiter hint, e.g. `:subagent`).

  The predicate receives a ready gate step view and returns nil/false while the
  executor is still fulfilling the gate, or truthy detail when coordinator
  attention is needed. Registration is weaver-lifetime runtime state, mirroring
  `register-workflow!`. Returns the registered waiter as a keyword."
  [waiter pred]
  (when-not (s/valid? ::external-waiter waiter)
    (fail! "Executor waiter must be a keyword, symbol, or non-blank string other than :self"
           {:waiter waiter :explain (s/explain-data ::external-waiter waiter)}))
  (when-not (ifn? pred)
    (fail! "Executor predicate must be invokable" {:waiter waiter}))
  (swap! executor-registry assoc (name waiter) pred)
  (keyword (name waiter)))

(defn registered-executors
  "Return the current registry map of gate waiter name (keyword) -> stall predicate."
  []
  (into {} (map (fn [[k v]] [(keyword k) v])) @executor-registry))

(defn workflow-definition
  "Return the constructor symbol registered under keyword `name`, failing loudly
  (TEN-003) when `name` is not registered."
  [name]
  (or (get @workflow-name-registry name)
      (fail! "Unknown registered workflow"
             {:name name :registered (vec (keys @workflow-name-registry))})))

(defn registered-workflows
  "Return the current registry map of workflow name (keyword) -> constructor symbol."
  []
  @workflow-name-registry)

(defn- resolve-next-symbol
  "Resolve a checkpoint choice's stored `:next` target to a workflow constructor
  symbol. A stored keyword name (`\":proposal\"`) resolves through the
  weaver-lifetime registry (failing loudly on an unregistered name); any other
  value is read as a fully qualified fn symbol directly."
  [next-str]
  (if (str/starts-with? next-str ":")
    (workflow-definition (keyword (subs next-str 1)))
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
  (mapv keyword (or (attr root :workflow/stage-params) [])))

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
  (let [next-sym (resolve-next-symbol next-str)
        workflow-fn (or (requiring-resolve next-sym)
                        (fail! "Choice next workflow cannot be resolved"
                               {:run-id run-id :next next-sym}))
        root (current-root-with-rt rt run-id)
        context (or (attr root :workflow/context) {})
        call-params (apply dissoc (merge context input) (stage-param-keys root))
        {:keys [workflow params]} (continuation-plan (workflow-fn call-params) call-params)
        payload (compile workflow params
                         {:run-id run-id
                          :family (attr root :workflow/family)
                          :definition next-sym
                          :context params
                          :phase :molecule})]
    {:old-root root :payload payload}))

(defn- revise-plan
  "Return the routing plan for a `:revise` choice: re-pour the current root's own
  `workflow/definition` under the same run-id with authoritative override params.

  Params are `(merge context choice-input override-params)`, the `:revise`
  overrides winning, and persist as the new root's `workflow/context`; the
  overridden keys are recorded as stage-local (see `stage-params-attrs`). Fails
  loudly (TEN-003) when the root has no resolvable `workflow/definition`."
  [rt run-id _step choice input override-params]
  (let [root (current-root-with-rt rt run-id)
        def-str (or (attr root :workflow/definition)
                    (fail! "Cannot revise a run whose root has no workflow/definition"
                           {:run-id run-id :choice choice}))
        definition-sym (symbol def-str)
        definition-fn (or (requiring-resolve definition-sym)
                          (fail! "Root workflow/definition cannot be resolved"
                                 {:run-id run-id :definition definition-sym}))
        context (or (attr root :workflow/context) {})
        params (merge context input override-params)
        result (definition-fn params)
        workflow (if (and (map? result) (contains? result :workflow)) (:workflow result) result)
        payload (compile workflow params
                         {:run-id run-id
                          :family (attr root :workflow/family)
                          :definition definition-sym
                          :context params
                          :root-attributes (stage-params-attrs override-params)
                          :phase :molecule})]
    {:old-root root :payload payload}))

(defn- route-plan
  "Return the routing plan for a checkpoint choice, or nil for a terminal choice.

  A `:next` choice routes to a continuation (symbol or registered name; see
  `next-plan`); a `:revise` choice re-pours the run's own definition with
  override params (see `revise-plan`). The plan carries the old root and the
  continuation batch payload, compiled once before any mutation and applied only
  after the old root closes, so two active roots never share one run-id."
  [rt run-id step choice input]
  (let [detail (some-> (raw-choice-detail step choice) detail-view)
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
                                 (contains? #{"molecule" "step" "checkpoint" "procedure"}
                                            (attr strand :workflow/role))))
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

(defn- choose!*
  [run-id choice input opts apply-batch!]
  (let [rt (current/runtime)]
    (require-map! opts [:opts])
    (let [choice (if (keyword? choice) (name choice) (str choice))
          step (or (resolve-ready-step rt run-id opts)
                   (fail! "No ready workflow checkpoint" {:run-id run-id}))]
      (when-not (map? input)
        (fail! "Choice input must be a map" {:run-id run-id :choice choice :input input}))
      (when-not (= "checkpoint" (attr step :workflow/role))
        (fail! "Current workflow step is not a checkpoint" {:run-id run-id :step (step-view step)}))
      (let [choices (set (attr step :workflow/choices))]
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
                                        (cascade-join-ids rt (:id (current-root-with-rt rt run-id)) #{(:id step)}))))
        ;; also covers a routed continuation that poured no active work, so the
        ;; new root cannot linger active on a logically finished run
        (close-run-if-done! rt run-id)
        (run-result rt run-id)))))

(defn choose!
  "Record a checkpoint choice for run-id, optionally pour its continuation,
  and return the `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready. opts may
  also include `:by`, recorded as \"workflow/outcome-by\" on the closed
  checkpoint alongside \"workflow/outcome\"/\"workflow/outcome-input\" to
  persist who made the choice (unenforced per TEN-002).

  When the chosen choice declares required `:input` keys, `choose!` fails loudly
  before any mutation if `input` omits them (see `validate-choice-input!`). A
  routed choice — one carrying `:next` (a symbol or registered name) or
  `:revise` (re-pour the run's own definition with override params) — closes out
  the current workflow's remaining steps and pours the continuation under the
  same run-id, all in one transactional `batch/apply!` (see `routed-batch`); a
  terminal choice that closes the last inner step beneath a `procedure` join
  closes the join in the same transaction. All validation happens before any
  mutation."
  ([run-id choice]
   (choose! run-id choice {} {}))
  ([run-id choice input]
   (choose! run-id choice input {}))
  ([run-id choice input opts]
   (choose!* run-id choice input opts batch/apply!)))

(defn advance!
  "Advance run-id by one ready step regardless of its kind, returning the
  `{:ready [step-view ...] :done boolean}` result shape.

  Resolves the ready step (honoring an optional `:step` selector). When it is a
  checkpoint, `opts` must carry `:choice` (fail loudly otherwise); `advance!`
  dispatches to `choose!` with that choice, its `:input` (default `{}`), and the
  pass-through `:by`/`:step` opts. When it is a plain step, `:choice` must be
  absent (fail loudly otherwise); `advance!` dispatches to `complete!` with the
  pass-through `:notes`/`:attributes`/`:step`/`:by` opts."
  ([run-id]
   (advance! run-id {}))
  ([run-id opts]
   (let [rt (current/runtime)]
     (require-map! opts [:opts])
     (let [step (or (resolve-ready-step rt run-id opts)
                    (fail! "No ready workflow step" {:run-id run-id}))]
       (if (= "checkpoint" (attr step :workflow/role))
         (do
           (when-not (contains? opts :choice)
             (fail! "advance! on a checkpoint requires a :choice"
                    {:run-id run-id :step (step-view step)}))
           (choose! run-id (:choice opts) (get opts :input {})
                    (select-keys opts [:by :step])))
         (do
           (when (contains? opts :choice)
             (fail! "advance! on a step must not supply a :choice"
                    {:run-id run-id :step (step-view step)}))
           (complete! run-id (select-keys opts [:notes :attributes :step :by]))))))))

(defn install!
  "Return installation metadata for this alpha workflow spool.

  Also seeds the `workflow/*` attribute namespace into the runtime vocabulary
  registry, owned by this spool's use-key, so the workflow attributes `compile`
  and the step/gate/checkpoint builders write are discoverable data."
  []
  (vocab/declare! (current/runtime)
                  {:kind :attr-namespace
                   :name "workflow"
                   :owner :skein/spools-workflow
                   :keys ["workflow/role" "workflow/phase" "workflow/run-id"
                          "workflow/family" "workflow/definition" "workflow/context"
                          "workflow/wisp" "workflow/gate" "workflow/checkpoint"
                          "workflow/checkpoint-kind" "workflow/hitl" "workflow/choices"
                          "workflow/choice-details" "workflow/procedure" "workflow/outcome"
                          "workflow/outcome-by" "workflow/outcome-notes" "workflow/outcome-input"
                          "workflow/summary" "workflow/stage-params" "workflow/squashed-root"
                          "workflow/squashed-count"]
                   :doc "Workflow molecule/wisp attributes written by the workflow spool's compile and builders."})
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
              :burn 'skein.spools.workflow/burn!
              :describe 'skein.spools.workflow/describe}
   :runtime {:start 'skein.spools.workflow/start!
             :current-root 'skein.spools.workflow/current-root
             :active-runs 'skein.spools.workflow/active-runs
             :next-step 'skein.spools.workflow/next-step
             :next-steps 'skein.spools.workflow/next-steps
             :next-gates 'skein.spools.workflow/next-gates
             :next-checkpoint 'skein.spools.workflow/next-checkpoint
             :complete 'skein.spools.workflow/complete!
             :choose 'skein.spools.workflow/choose!
             :advance 'skein.spools.workflow/advance!
             :choice-detail 'skein.spools.workflow/choice-detail
             :choice-details 'skein.spools.workflow/choice-details
             :done? 'skein.spools.workflow/done?
             :run-history 'skein.spools.workflow/run-history
             :archive-run 'skein.spools.workflow/archive-run!}
   :registry {:register-workflow 'skein.spools.workflow/register-workflow!
              :workflow-definition 'skein.spools.workflow/workflow-definition
              :registered-workflows 'skein.spools.workflow/registered-workflows}})
