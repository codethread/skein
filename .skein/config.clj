(ns config
  "Repo-local Skein runtime configuration for skein-src."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.libs.ephemeral :as ephemeral]
            [skein.patterns.alpha :as patterns]
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
(s/def ::validation (s/coll-of ::non-blank-string :kind vector?))
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
    :else (throw (ex-info "task_id must be a non-blank string or integer" {:task_id task-id}))))

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
  [workflow feature {:keys [key body kind hitl task_file task_id owner branch validation]}]
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
    (seq validation) (assoc :validation validation)))

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
        ready (api/ready rt (if feature feature-work-query devflow-active-query) params)]
    {:operation "devflow-status"
     :feature feature
     :active_count (count active)
     :ready_count (count ready)
     :active (mapv summarize-strand active)
     :ready (mapv summarize-strand ready)}))

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
              :usage "strand list --query devflow-active"}]
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
         'devflow-active devflow-active-query}))

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
          'config/devflow-conventions-op)]
   :ephemeral {:namespace 'skein.libs.ephemeral
               :creator 'skein.libs.ephemeral/ephemeral!
               :burner 'skein.libs.ephemeral/burn-ephemeral!
               :query ephemeral/ephemeral-query}})
