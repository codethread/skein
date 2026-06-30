(ns config
  "Repo-local Skein runtime configuration for skein-src."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.libs.ephemeral :as ephemeral]
            [skein.patterns.alpha :as patterns]
            [skein.views.alpha :as views]
            [skein.weaver.api :as api]
            [skein.weaver.runtime :as runtime]))

(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def ::feature ::non-blank-string)
(s/def ::title ::non-blank-string)
(s/def ::key ::non-blank-string)
(s/def ::kind #{"task" "review"})
(s/def ::body ::non-blank-string)
(s/def ::hitl boolean?)
(s/def ::depends_on (s/coll-of ::key :kind vector?))
(s/def ::task_file ::non-blank-string)
(s/def ::task_id (s/or :string ::non-blank-string :int int?))
(s/def ::owner ::non-blank-string)
(s/def ::branch ::non-blank-string)
(s/def ::validation (s/coll-of ::non-blank-string :kind vector? :min-count 1))
(s/def ::task (s/keys :req-un [::key ::title]
                      :opt-un [::body ::kind ::hitl ::depends_on
                               ::task_file ::task_id ::owner ::branch ::validation]))
(s/def ::tasks (s/coll-of ::task :kind vector? :min-count 1))
(s/def ::agent-plan-input (s/keys :req-un [::feature ::title ::tasks]
                                  :opt-un [::body]))
(s/def ::devflow-plan-input ::agent-plan-input)

(def current-dag-roots-query
  "Query for active strands that own active child work through `parent-of`."
  [:and
   [:= :state "active"]
   [:edge/out "parent-of" [:= :state "active"]]])

(def feature-active-query
  "Parameterized query for all active strands in a feature."
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

(def feature-task-scope-query
  "Parameterized query for one active feature task by key, id, or task file."
  {:params [:feature :task]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]
           [:or
            [:= [:attr :task_key] [:param :task]]
            [:= [:attr :task_id] [:param :task]]
            [:= [:attr :task_file] [:param :task]]]]})

(def devflow-active-query
  "Query for active strands created by the devflow-plan pattern."
  [:and
   [:= :state "active"]
   [:= [:attr :workflow] "devflow"]])

(def devflow-feature-query
  "Query for active devflow feature/plan strands."
  [:and
   [:= :state "active"]
   [:= [:attr :workflow] "devflow"]
   [:= [:attr :kind] "plan"]])

(def devflow-work-query
  "Query for active devflow task/review strands."
  [:and
   [:= :state "active"]
   [:= [:attr :workflow] "devflow"]
   [:in [:attr :kind] ["task" "review"]]])

(defn- ref-symbol
  "Return the batch ref symbol for a user-supplied task key."
  [key]
  (symbol key))

(defn- task-id-string
  "Return a stable string task id for query parameters."
  [task-id]
  (cond
    (nil? task-id) nil
    (integer? task-id) (str task-id)
    (and (string? task-id) (not (str/blank? task-id))) task-id
    :else (throw (ex-info "task_id must be a non-blank string or integer"
                          {:code "devflow/invalid-coordination-attribute"
                           :attribute :task_id
                           :value task-id}))))

(def ^:private devflow-workflows
  #{"devflow" "agent-plan"})

(def ^:private devflow-kinds
  #{"plan" "task" "review"})

(defn- require-non-blank-string!
  "Return value when it is a non-blank string, otherwise fail with attr context."
  [attr value]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (ex-info (str (name attr) " must be a non-blank string")
                    {:code "devflow/invalid-coordination-attribute"
                     :attribute attr
                     :value value})))
  value)

(defn- require-enum!
  "Return value when it is in allowed, otherwise fail with attr context."
  [attr allowed value]
  (when-not (contains? allowed value)
    (throw (ex-info (str (name attr) " must be one of " (pr-str (sort allowed)))
                    {:code "devflow/invalid-coordination-attribute"
                     :attribute attr
                     :allowed (sort allowed)
                     :value value})))
  value)

(defn- normalize-validation!
  "Return validation when it is a non-empty vector of non-blank strings."
  [validation]
  (when-not (and (vector? validation)
                 (seq validation)
                 (every? #(and (string? %) (not (str/blank? %))) validation))
    (throw (ex-info "validation must be a non-empty vector of non-blank strings"
                    {:code "devflow/invalid-coordination-attribute"
                     :attribute :validation
                     :value validation})))
  validation)

(def ^:private devflow-coordination-attrs
  [:workflow :feature :kind :task_key :task_id :task_file :owner :branch :validation])

(defn- reject-duplicate-logical-attrs!
  "Fail when attrs contains both keyword and JSON string forms of attr."
  [attrs attr]
  (when (and (contains? attrs attr)
             (contains? attrs (name attr)))
    (throw (ex-info (str (name attr) " must not be supplied with both keyword and string keys")
                    {:code "devflow/duplicate-coordination-attribute"
                     :attribute attr
                     :keys [attr (name attr)]}))))

(defn- contains-attr?
  "Return true when attrs contains attr as a keyword or JSON string key."
  [attrs attr]
  (or (contains? attrs attr)
      (contains? attrs (name attr))))

(defn- get-attr
  "Return attr value from attrs, preferring keyword key over JSON string key."
  [attrs attr]
  (if (contains? attrs attr)
    (get attrs attr)
    (get attrs (name attr))))

(defn- update-present-attr
  "Update attr for both keyword and JSON string keys when present."
  [attrs attr f]
  (cond-> attrs
    (contains? attrs attr) (update attr f)
    (contains? attrs (name attr)) (update (name attr) f)))

(defn normalize-devflow-coordination-attrs
  "Normalize and validate repo-local devflow coordination attributes.

  This lifecycle hook is intentionally narrow: it only checks coordination
  attributes that are present, normalizes `task_id` integers to strings, and
  fails loudly for malformed values instead of filling in missing attrs."
  [ctx]
  (let [value (:hook/value ctx)
        _ (doseq [attr devflow-coordination-attrs]
            (reject-duplicate-logical-attrs! value attr))
        attrs (update-present-attr value :task_id task-id-string)]
    (doseq [attr [:feature :task_key :task_file :owner :branch]
            :when (contains-attr? attrs attr)]
      (require-non-blank-string! attr (get-attr attrs attr)))
    (when (contains-attr? attrs :workflow)
      (require-enum! :workflow devflow-workflows (get-attr attrs :workflow)))
    (when (contains-attr? attrs :kind)
      (require-enum! :kind devflow-kinds (get-attr attrs :kind)))
    (when (contains-attr? attrs :validation)
      (normalize-validation! (get-attr attrs :validation)))
    {:hook/value attrs}))

(defn- plan-strand
  "Return the feature/plan strand for an agent/devflow plan input."
  [workflow {:keys [feature title body tasks]}]
  {:ref 'plan
   :title title
   :attributes (cond-> {:feature feature
                        :kind "plan"
                        :workflow workflow}
                 body (assoc :body body))
   :edges (mapv (fn [{:keys [key]}]
                  {:type "parent-of" :to (ref-symbol key)})
                tasks)})

(defn- task-attributes
  "Return task attributes shared by agent and devflow plan patterns."
  [workflow feature {:keys [key body kind hitl task_file task_id owner branch validation] :as task}]
  (cond-> {:feature feature
           :kind (or kind "task")
           :workflow workflow
           :task_key key}
    body (assoc :body body)
    hitl (assoc :hitl true)
    task_file (assoc :task_file task_file)
    task_id (assoc :task_id (task-id-string task_id))
    owner (assoc :owner owner)
    branch (assoc :branch branch)
    (contains? task :validation) (assoc :validation validation)))

(defn- task-strand
  "Return one task/review strand for an agent/devflow input task."
  [workflow feature {:keys [key title depends_on] :as task}]
  (cond-> {:ref (ref-symbol key)
           :title title
           :attributes (task-attributes workflow feature task)}
    (seq depends_on)
    (assoc :edges (mapv (fn [dep]
                          {:type "depends-on" :to (ref-symbol dep)})
                        depends_on))))

(defn- plan-batch
  "Return the batch rows for a plan pattern using workflow as a convention marker."
  [workflow input]
  (into [(plan-strand workflow input)]
        (map #(task-strand workflow (:feature input) %))
        (:tasks input)))

(defn agent-plan
  "Create a feature strand plus task/review children for agent work.

  Input requires `feature`, `title`, and a non-empty `tasks` vector. Optional
  `body` fields carry issue-style context on the plan or tasks. Task keys become
  local batch refs. Each task may set `kind` to `task` or `review`, `hitl` to
  true, and `depends_on` to a vector of task keys. The generated plan has
  `kind=plan`, children share `feature`, the plan has `parent-of` edges to each
  child, and task dependencies become `depends-on` edges. Optional coordination
  attributes include `task_file`, `task_id`, `owner`, `branch`, and `validation`."
  [{:keys [input]}]
  (plan-batch "agent-plan" input))

(defn devflow-plan
  "Create a devflow-scoped feature plan plus task/review children.

  This pattern is the blessed repo convention for coordinating devflow work with
  strands. It accepts the same shape as `agent-plan`, adds `workflow=devflow`,
  records every task `key` as `task_key`, and preserves optional `task_file`,
  `task_id`, `owner`, `branch`, and `validation` attributes so feature- and
  task-scoped ready queries can target delegated agents precisely."
  [{:keys [input]}]
  (plan-batch "devflow" input))

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
  "Return active depends-on edges reachable from the included strand ids."
  [rt active-ids strand-ids]
  (let [{:keys [edges]} (api/subgraph rt strand-ids {:type "depends-on"})]
    (internal-active-edges active-ids edges)))

(defn- summarize-strand
  "Return the compact strand shape used by devflow coordination operations."
  [strand]
  (select-keys strand [:id :title :state :attributes]))

(defn- coordination-metadata
  "Return devflow coordination metadata from strand attributes."
  [{:keys [attributes]}]
  (select-keys attributes [:feature :kind :task_key :task_id :task_file :owner :branch :validation]))

(defn- summarize-work-item
  "Return dashboard summary data for one task/review strand."
  [strand]
  (merge (select-keys strand [:id :title :state])
         {:metadata (coordination-metadata strand)}))

(defn- feature-slug
  "Return the required feature slug from a devflow strand."
  [strand]
  (or (get-in strand [:attributes :feature])
      (throw (ex-info "Devflow strand is missing feature attribute"
                      {:strand_id (:id strand)}))))

(defn- active-devflow-work
  "Return active devflow task/review strands, optionally scoped by feature."
  [rt feature]
  (if feature
    (api/list rt feature-work-query {:feature feature})
    (api/list rt devflow-work-query {})))

(defn- active-devflow-features
  "Return active devflow feature/plan strands, optionally scoped by feature."
  [rt feature]
  (if feature
    (api/list rt [:and devflow-feature-query [:= [:attr :feature] [:param :feature]]] {:feature feature})
    (api/list rt devflow-feature-query {})))

(defn- ready-devflow-work
  "Return ready active devflow task/review strands, optionally scoped by feature."
  [rt feature]
  (if feature
    (api/ready rt feature-work-query {:feature feature})
    (api/ready rt devflow-work-query {})))

(defn- blocking-dependencies
  "Return active depends-on targets for one work item."
  [rt active-by-id strand-id]
  (let [active-ids (set (keys active-by-id))
        {:keys [edges]} (api/subgraph rt [strand-id] {:type "depends-on"})]
    (->> edges
         (filter #(and (= strand-id (:from_strand_id %))
                       (contains? active-ids (:to_strand_id %))))
         (sort-by (juxt :to_strand_id :edge_type))
         (mapv (fn [{:keys [to_strand_id edge_type attributes]}]
                 {:id to_strand_id
                  :title (:title (active-by-id to_strand_id))
                  :edge_type edge_type
                  :metadata (coordination-metadata (active-by-id to_strand_id))
                  :attributes attributes})))))

(defn- summarize-blocked-item
  "Return dashboard summary data for a blocked task/review strand."
  [rt active-by-id strand]
  (assoc (summarize-work-item strand)
         :blocked_by (blocking-dependencies rt active-by-id (:id strand))))

(defn devflow-dashboard-view
  "Return a read-only devflow feature dashboard projection.

  Optional params: `{:feature \"slug\"}`. The projection is JSON-compatible and
  groups active features with ready and blocked task/review work, count totals,
  and coordination metadata such as owner and task_file."
  [{:keys [params]}]
  (let [rt @runtime/current-runtime
        feature (:feature params)
        active-by-id (active-strands-by-id rt)
        features (active-devflow-features rt feature)
        work (active-devflow-work rt feature)
        ready (ready-devflow-work rt feature)
        ready-ids (set (map :id ready))
        blocked (remove #(contains? ready-ids (:id %)) work)
        work-by-feature (group-by feature-slug work)
        ready-by-feature (group-by feature-slug ready)
        blocked-by-feature (group-by feature-slug blocked)]
    {:view "devflow-dashboard"
     :feature feature
     :counts {:features (count features)
              :active_work (count work)
              :ready_work (count ready)
              :blocked_work (count blocked)}
     :features (mapv (fn [feature-strand]
                       (let [slug (feature-slug feature-strand)
                             feature-work (get work-by-feature slug [])
                             feature-ready (get ready-by-feature slug [])
                             feature-blocked (get blocked-by-feature slug [])]
                         (merge (summarize-work-item feature-strand)
                                {:feature slug
                                 :counts {:active_work (count feature-work)
                                          :ready_work (count feature-ready)
                                          :blocked_work (count feature-blocked)}
                                 :ready (mapv summarize-work-item feature-ready)
                                 :blocked (mapv #(summarize-blocked-item rt active-by-id %) feature-blocked)})))
                     (sort-by feature-slug features))}))

(defn current-dags-op
  "Return active parent-of work DAGs and their active depends-on edges.

  This is intentionally an operation rather than only a named query because the
  CLI query surface returns flat strand rows; this handler projects the active
  parent-child roots, hierarchy edges, dependency edges, and compact strand rows
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
                        :depends_on_edges (dependency-edges-for rt active-ids strand-ids)}))
                   roots)]
    {:query "current-dag-roots"
     :roots roots
     :dags dags}))

(defn- require-zero-or-one-arg!
  "Return optional argv value, failing loudly on extra args."
  [op argv usage]
  (when (> (count argv) 1)
    (throw (ex-info (str op " expects zero or one argument")
                    {:argv argv :usage usage})))
  (first argv))

(defn- require-two-args!
  "Return exactly two argv values, failing loudly on missing or extra args."
  [op argv usage]
  (when-not (= 2 (count argv))
    (throw (ex-info (str op " expects exactly two arguments")
                    {:argv argv :usage usage})))
  argv)

(defn devflow-status-op
  "Return active devflow coordination status, optionally scoped by feature.

  Usage: `strand op devflow-status [feature]`.
  Returns active strands and ready task/review strands using the same named
  query contracts exposed to `strand list --query` and `strand ready --query`."
  [ctx]
  (let [rt @runtime/current-runtime
        argv (:op/argv ctx)
        feature (require-zero-or-one-arg! "devflow-status" argv "strand op devflow-status [feature]")
        query-def (if feature feature-active-query devflow-active-query)
        params (if feature {:feature feature} {})
        active (api/list rt query-def params)
        ready (api/ready rt (if feature feature-work-query devflow-work-query) params)]
    {:operation "devflow-status"
     :feature feature
     :active_count (count active)
     :ready_count (count ready)
     :active (mapv summarize-strand active)
     :ready (mapv summarize-strand ready)}))

(defn- active-devflow-task?
  "Return true when strand is active devflow task/review work."
  [{:keys [state attributes]}]
  (and (= "active" state)
       (= "devflow" (:workflow attributes))
       (#{"task" "review"} (:kind attributes))))

(defn- strands-by-existing-id
  "Return the strand matching id, or an empty vector when id does not exist."
  [rt strand-id]
  (try
    (api/strands-by-ids rt [strand-id])
    (catch clojure.lang.ExceptionInfo ex
      (if (= :strands-by-ids (:context (ex-data ex)))
        []
        (throw ex)))))

(defn- task-candidates
  "Return active devflow task/review candidates for a strand id or task key.

  Exact active strand id matches take precedence. Task-key lookup is only used
  when the supplied reference does not identify an active devflow task/review."
  [rt task-ref]
  (let [by-id (filterv active-devflow-task? (strands-by-existing-id rt task-ref))]
    (if (seq by-id)
      by-id
      (api/list rt [:and devflow-work-query
                    [:= [:attr :task_key] [:param :task]]]
                {:task task-ref}))))

(defn- require-one-task!
  "Return the unique task candidate or fail loudly."
  [task-ref candidates]
  (case (count candidates)
    0 (throw (ex-info "No active devflow task matched root lookup" {:task task-ref}))
    1 (first candidates)
    (throw (ex-info "Multiple active devflow tasks matched root lookup"
                    {:task task-ref
                     :matches (mapv summarize-work-item candidates)}))))

(defn- owning-feature-roots
  "Return devflow plan roots that own task through parent-of ancestors."
  [rt task-id]
  (let [root-ids (api/ancestor-root-ids rt [task-id] {:type "parent-of"
                                                      :where devflow-feature-query})]
    (api/strands-by-ids rt root-ids)))

(defn task-root-op
  "Return the devflow feature/plan roots that own one active task or review.

  Usage: `strand op task-root <strand-id-or-task-key>`. The lookup accepts a
  strand id or an active devflow task_key. Task-key lookup must match exactly
  one active devflow task/review. Roots are discovered with ancestor-root-ids
  over the declared parent-of relation and filtered to active devflow plan
  strands."
  [ctx]
  (let [rt @runtime/current-runtime
        task-ref (or (require-zero-or-one-arg! "task-root" (:op/argv ctx) "strand op task-root <strand-id-or-task-key>")
                     (throw (ex-info "task-root requires a strand id or task_key"
                                     {:usage "strand op task-root <strand-id-or-task-key>"})))
        task (require-one-task! task-ref (task-candidates rt task-ref))
        roots (owning-feature-roots rt (:id task))]
    {:operation "task-root"
     :task (summarize-work-item task)
     :root_count (count roots)
     :roots (mapv summarize-work-item roots)}))

(defn task-root-view
  "Return a read-only projection of devflow feature/plan roots for a task.

  Params: `{:task \"<strand-id-or-task-key>\"}`. This view mirrors `task-root-op`
  for coordinator dashboards and other trusted config consumers."
  [{:keys [params]}]
  (task-root-op {:op/argv [(:task params)]}))

(defn- task-key-supersession-candidates
  "Return active devflow task/review candidates matching task-key ref."
  [rt ref]
  (api/list rt [:and devflow-work-query
                [:= [:attr :task_key] [:param :task]]]
            {:task ref}))

(defn- reject-unsupported-supersession-id!
  "Fail loudly when ref is an existing id that is not an active task/review."
  [role ref strand]
  (throw (ex-info (str "Existing strand id is not a supported devflow " role " supersession target")
                  {:ref ref
                   :id (:id strand)
                   :state (:state strand)
                   :workflow (get-in strand [:attributes :workflow])
                   :kind (get-in strand [:attributes :kind])})))

(defn- require-one-supersession-target!
  "Resolve one devflow task/review supersession target or fail loudly.

  Existing strand ids are authoritative: active devflow task/review ids resolve,
  while plan ids, non-active task/review ids, and other unsupported ids fail
  instead of falling back to task_key lookup. Non-id refs resolve by unique active
  devflow task_key."
  [role rt ref]
  (let [by-id (strands-by-existing-id rt ref)]
    (if (seq by-id)
      (let [strand (first by-id)]
        (if (active-devflow-task? strand)
          strand
          (reject-unsupported-supersession-id! role ref strand)))
      (let [candidates (task-key-supersession-candidates rt ref)]
        (case (count candidates)
          0 (throw (ex-info (str "No active devflow " role " matched supersession ref")
                            {:ref ref}))
          1 (first candidates)
          (throw (ex-info (str "Multiple active devflow " role " strands matched supersession ref")
                          {:ref ref
                           :matches (mapv summarize-work-item candidates)})))))))

(defn devflow-supersede-op
  "Supersede one active devflow task/review with another explicit task/review.

  Usage: `strand op devflow-supersede <old-id-or-task-key> <replacement-id-or-task-key>`.
  Exact active strand ids take precedence. Task-key lookup is allowed for active
  devflow task/review strands and must resolve uniquely. Plan supersession is
  intentionally unsupported because core supersession rewires `depends-on` but
  not `parent-of` ownership edges. The operation delegates to Skein core
  supersession so the old strand becomes `replaced`, a `supersedes` edge is
  recorded, and incoming `depends-on` edges are rewired to the replacement. It
  never searches for or mutates replacements automatically."
  [ctx]
  (let [rt @runtime/current-runtime
        usage "strand op devflow-supersede <old-id-or-task-key> <replacement-id-or-task-key>"
        [old-ref replacement-ref] (require-two-args! "devflow-supersede" (:op/argv ctx) usage)
        old (require-one-supersession-target! "old" rt old-ref)
        replacement (require-one-supersession-target! "replacement" rt replacement-ref)
        result (api/supersede rt (:id old) (:id replacement))]
    {:operation "devflow-supersede"
     :old (summarize-work-item old)
     :replacement (summarize-work-item replacement)
     :result result}))

(defn devflow-conventions-op
  "Return the blessed devflow strand conventions installed by this config."
  [_ctx]
  {:operation "devflow-conventions"
   :patterns [{:name "devflow-plan"
               :purpose "Create a feature plan plus task/review children with devflow coordination attributes."}
              {:name "agent-plan"
               :purpose "General agent work DAG pattern; also records task_key and optional coordination attrs."}]
   :queries [{:name "feature-active"
              :usage "strand list --query feature-active --param feature=<feature>"}
             {:name "feature-work"
              :usage "strand ready --query feature-work --param feature=<feature>"}
             {:name "feature-owner-work"
              :usage "strand ready --query feature-owner-work --param feature=<feature> --param owner=<owner>"}
             {:name "feature-task-scope"
              :usage "strand ready --query feature-task-scope --param feature=<feature> --param task=<task-key-or-task-file>"}
             {:name "devflow-active"
              :usage "strand list --query devflow-active"}
             {:name "devflow-features"
              :usage "strand list --query devflow-features"}
             {:name "devflow-work"
              :usage "strand ready --query devflow-work"}]
   :views [{:name "devflow-dashboard"
            :usage "(skein.views.alpha/view! 'devflow-dashboard {}) or {:feature \"<feature>\"}"
            :purpose "Read-only feature dashboard with active features, ready work, blocked work, counts, and coordination metadata."}
           {:name "task-root"
            :usage "(skein.views.alpha/view! 'task-root {:task \"<strand-id-or-task-key>\"})"
            :purpose "Read-only lookup for the devflow feature/plan root that owns one task or review."}]
   :ops [{:name "devflow-supersede"
          :usage "strand op devflow-supersede <old-id-or-task-key> <replacement-id-or-task-key>"
          :purpose "Explicitly supersede a devflow task/review through core supersession, rewiring incoming depends-on edges to the replacement. Plan supersession is intentionally unsupported."}]
   :attributes [{:name "workflow" :values ["devflow" "agent-plan"]}
                {:name "feature" :meaning "Feature slug for feature-scoped queries."}
                {:name "kind" :values ["plan" "task" "review"]}
                {:name "task_key" :meaning "Pattern-local stable task key."}
                {:name "task_id" :meaning "Optional devflow numeric task id stored as a string."}
                {:name "task_file" :meaning "Path to the devflow task file."}
                {:name "owner" :meaning "Optional assignee or agent scope."}
                {:name "branch" :meaning "Optional worktree/branch coordination hint."}
                {:name "validation" :meaning "Optional vector of validation commands or expectations."}]})

(defn- register-query-map!
  "Register repo-local named queries and return registration metadata."
  []
  (into {}
        (map (fn [[query-name query-def]]
               [query-name (api/register-query! query-name query-def)]))
        {'current-dag-roots current-dag-roots-query
         'feature-active feature-active-query
         'feature-work feature-work-query
         'feature-owner-work feature-owner-work-query
         'feature-task-scope feature-task-scope-query
         'devflow-active devflow-active-query
         'devflow-features devflow-feature-query
         'devflow-work devflow-work-query}))

(defn install!
  "Install repo-local Skein runtime configuration."
  []
  (patterns/register-pattern!
   'agent-plan
   "Create a feature strand plus task/review children for agent work. Input: {feature,title,body?,tasks:[{key,title,body?,kind?,hitl?,depends_on?,task_file?,task_id?,owner?,branch?,validation?}]}. Use body for delegated work context."
   'config/agent-plan
   ::agent-plan-input)
  (patterns/register-pattern!
   'devflow-plan
   "Create a devflow feature strand plus task/review children. Input: {feature,title,body?,tasks:[{key,title,body?,kind?,hitl?,depends_on?,task_file?,task_id?,owner?,branch?,validation?}]}. Adds workflow=devflow plus task_key/task_file/task_id attrs for scoped ready queries."
   'config/devflow-plan
   ::devflow-plan-input)
  {:installed true
   :namespace 'config
   :queries (register-query-map!)
   :views [(views/register-view!
         'devflow-dashboard
         'config/devflow-dashboard-view)
        (views/register-view!
         'task-root
         'config/task-root-view)]
   :hooks [(api/register-hook!
            :devflow-coordination-attrs
            #{:attributes/normalize}
            'config/normalize-devflow-coordination-attrs
            {:doc "Normalize and validate repo-local devflow coordination attributes."})]
   :ops [(api/register-op!
          'current-dags
          "Show active parent-of work DAGs with active depends-on edges"
          'config/current-dags-op)
         (api/register-op!
          'devflow-status
          "Show active/ready devflow coordination status, optionally scoped by feature"
          'config/devflow-status-op)
         (api/register-op!
          'devflow-conventions
          "Show repo-local devflow strand patterns, queries, and attributes"
          'config/devflow-conventions-op)
         (api/register-op!
          'task-root
          "Show the devflow feature/plan root that owns one task or review"
          'config/task-root-op)
         (api/register-op!
          'devflow-supersede
          "Explicitly supersede a devflow task/review and rewire dependencies"
          'config/devflow-supersede-op)]
   :ephemeral {:namespace 'skein.libs.ephemeral
               :creator 'skein.libs.ephemeral/ephemeral!
               :burner 'skein.libs.ephemeral/burn-ephemeral!
               :query ephemeral/ephemeral-query}})
