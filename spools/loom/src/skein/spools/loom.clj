(ns skein.spools.loom
  "Read-only projections of the active strand graph into work views.

  A loom holds the whole warp under tension and reveals the developing cloth;
  this spool holds the active strand graph and projects it into the shapes
  consumers actually render: parent-of work DAGs with their depends-on edges,
  per-root branch progress views joined to a ready frontier, and workflow
  flow-status (history, frontier, subagent gates, delegated runs, and stalls)
  with a Mermaid gate chain. These projections were hand-rolled inside repo
  config; they are generic graph vocabulary that other code builds on, so they
  ship here on the classpath while a repo keeps only its own policy — which
  attribute names a branch, which query feeds the ready frontier.

  Every function is read-only: it composes the public weaver/graph surfaces and
  mutates no strands, edges, runtime config, or registered operations. Callers
  supply the active runtime explicitly."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.workflow :as workflow]
            [skein.api.spool.alpha :refer [attr-get entity-projection fail! reject-unknown-keys! require-valid!]]))

;; ---------------------------------------------------------------------------
;; Graph primitives
;; ---------------------------------------------------------------------------

(defn- active-by-id
  "Return active strands keyed by id."
  [rt]
  (into {}
        (map (juxt :id identity))
        (weaver/list rt [:= :state "active"] {})))

(defn summarize
  "Return the compact strand shape used by read-only projections."
  [strand]
  (entity-projection strand))

(defn- internal-active-edges
  "Return edges whose endpoints are both active strands, sorted and deduped-safe.

  Subgraph expansion walks outward to external strands, so edges are filtered
  against `active-ids` to keep a projection self-contained: every returned edge
  endpoint appears in the projection's own strand set."
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
  "Return the active parent-of subgraph below one root id.

  The result is `{:root-id :strand-ids :parent-of}`, where `:strand-ids`
  includes the root and its active parent-of descendants and `:parent-of` is the
  active-internal edge set."
  [rt active-ids root-id]
  (let [{:keys [strands edges]} (graph/subgraph rt [root-id] {:type "parent-of"})
        active-strand-ids (set (keep (fn [{:keys [id state]}]
                                       (when (= "active" state) id))
                                     strands))
        included-ids (conj active-strand-ids root-id)]
    {:root-id root-id
     :strand-ids (->> included-ids (filter active-ids) sort vec)
     :parent-of (internal-active-edges active-ids edges)}))

;; ---------------------------------------------------------------------------
;; work-dags: generic active work-DAG projection
;; ---------------------------------------------------------------------------

(defn- dependency-edges-for
  "Return active depends-on edges internal to the included strand ids.

  Subgraph expansion walks outward to external blockers, so edges are filtered
  against the DAG-local id set to keep the projection self-contained."
  [rt strand-ids]
  (let [{:keys [edges]} (graph/subgraph rt strand-ids {:type "depends-on"})]
    (internal-active-edges (set strand-ids) edges)))

(defn work-dags
  "Return active parent-of work DAGs and their active depends-on edges.

  Projects every active parent-of root, its hierarchy edges, dependency edges,
  and compact strand rows into one JSON-compatible `{:roots :dags}` structure —
  the flat CLI query surface returns strand rows, this joins them into DAGs."
  [rt]
  (let [active (active-by-id rt)
        active-ids (set (keys active))
        parent-edges (->> (:edges (graph/subgraph rt (sort active-ids) {:type "parent-of"}))
                          (internal-active-edges active-ids))
        roots (parent-root-ids active-ids parent-edges)
        dags (mapv (fn [root-id]
                     (let [{:keys [strand-ids parent-of]} (descendants-by-root rt active-ids root-id)]
                       {:root (summarize (active root-id))
                        :strands (mapv (comp summarize active) strand-ids)
                        :parent_of_edges parent-of
                        :depends_on_edges (dependency-edges-for rt strand-ids)}))
                   roots)]
    {:roots roots
     :dags dags}))

;; ---------------------------------------------------------------------------
;; branch views: per-root progress joined to a ready frontier
;; ---------------------------------------------------------------------------

(defn- root-view
  "Return one work root with its active descendants and ready frontier.

  Descendants are the active parent-of subgraph below `root` excluding the root
  itself; `:ready` is the subset of the root and its descendants present in
  `ready-ids`."
  [rt active-ids ready-ids root]
  (let [{:keys [strands]} (graph/subgraph rt [(:id root)] {:type "parent-of"})
        descendants (->> strands
                         (filter #(and (contains? active-ids (:id %))
                                       (not= (:id root) (:id %))))
                         (sort-by :id)
                         (mapv summarize))]
    {:root (summarize root)
     :active_descendants descendants
     :ready (filterv #(contains? ready-ids (:id %))
                     (into [(summarize root)] descendants))}))

(s/def ::branch-attr keyword?)
(s/def ::branch string?)
(def ^:private branch-views-opts-keys #{:branch-attr :ready-query :branch})

(defn branch-views
  "Group active branch-stamped work roots into per-branch progress views.

  A branch root is an active strand carrying `:branch-attr` that is not itself a
  parent-of child of another active strand; each is joined to its active
  descendants and the ready frontier of `:ready-query`. Options:

  - `:branch-attr` — attribute key naming the branch (default `:branch`).
  - `:ready-query` — required inline query expression (vector or map, as
    accepted by `skein.api.weaver.alpha/ready`) whose ready frontier feeds each
    root view. Fails loudly when absent; a named query symbol/keyword is not
    resolved.
  - `:branch` — optional branch name to scope the projection to one branch.

  Fails loudly on unknown opt keys, a non-keyword `:branch-attr`, or a
  non-string `:branch`. Returns a vector of `{:branch :roots}` sorted by
  branch name."
  [rt opts]
  (when-not (map? opts)
    (fail! "loom/branch-views opts must be a map" {:opts opts}))
  (reject-unknown-keys! "loom/branch-views" branch-views-opts-keys opts)
  (let [{:keys [branch-attr ready-query branch] :or {branch-attr :branch}} opts]
    (when (nil? ready-query)
      (fail! "loom/branch-views requires a :ready-query" {:opts {:branch-attr branch-attr :branch branch}}))
    (require-valid! ::branch-attr branch-attr "loom/branch-views :branch-attr must be a keyword")
    (when (some? branch)
      (require-valid! ::branch branch "loom/branch-views :branch must be a string"))
    (let [active (active-by-id rt)
          active-ids (set (keys active))
          parent-edges (->> (:edges (graph/subgraph rt (sort active-ids) {:type "parent-of"}))
                            (internal-active-edges active-ids))
          child-ids (set (map :to_strand_id parent-edges))
          roots (->> (vals active)
                     (filter #(attr-get % branch-attr))
                     (remove #(contains? child-ids (:id %)))
                     (filter #(or (nil? branch) (= branch (attr-get % branch-attr)))))
          ready-ids (set (map :id (weaver/ready rt ready-query {})))]
      (->> roots
           (group-by #(attr-get % branch-attr))
           (sort-by key)
           (mapv (fn [[branch-name branch-roots]]
                   {:branch branch-name
                    :roots (mapv (partial root-view rt active-ids ready-ids)
                                 (sort-by :id branch-roots))}))))))

;; ---------------------------------------------------------------------------
;; flow-status: workflow history/frontier/gate/run/stall join with Mermaid
;; ---------------------------------------------------------------------------

(defn- compact-run
  "Return a compact agent-run/gate state projection for a run strand."
  [run]
  (when run
    (cond-> {:id (:id run)
             :title (:title run)
             :state (:state run)
             :agent-run/phase (attr-get run :agent-run/phase)}
      (attr-get run :agent-run/harness) (assoc :agent-run/harness (attr-get run :agent-run/harness))
      (attr-get run :agent-run/error) (assoc :agent-run/error (attr-get run :agent-run/error))
      (attr-get run :agent-run/result) (assoc :agent-run/result (attr-get run :agent-run/result))
      (attr-get run :gate/delivered) (assoc :gate/delivered (attr-get run :gate/delivered))
      (attr-get run :gate/delivery-blocked) (assoc :gate/delivery-blocked (attr-get run :gate/delivery-blocked)))))

(defn- gate-serving-run-id
  "The gate's current delegated run id, or nil: the source of an incoming `serves`
  edge whose run is not superseded. Mirrors the agent-run serving-resolution rule
  (a superseded run carries an incoming `supersedes` edge / `agent-run/phase
  \"superseded\"`) so flow-status and the subagent executor agree on the live run."
  [rt gate-id]
  (let [run-ids (mapv :from_strand_id (graph/incoming-edges rt [gate-id] "serves"))
        superseded (set (map :to_strand_id (graph/incoming-edges rt run-ids "supersedes")))]
    (->> run-ids
         (remove superseded)
         (map #(weaver/show rt %))
         (remove #(= "superseded" (attr-get % :agent-run/phase)))
         (map :id)
         first)))

(defn- compact-gate
  "Return a compact workflow gate projection joined to its delegated run."
  [rt gate->run failed-run-ids stalled-gate-ids gate]
  (let [run-id (get gate->run (:id gate))
        run (when run-id (weaver/show rt run-id))
        run-failed? (contains? failed-run-ids run-id)
        spawn-stalled? (contains? stalled-gate-ids (:id gate))]
    (cond-> {:id (:id gate)
             :title (:title gate)
             :state (:state gate)
             :gate (attr-get gate :workflow/gate)
             :gate/run run-id
             :run (compact-run run)
             :stalled? (boolean (or spawn-stalled? run-failed?))}
      (attr-get gate :gate/error) (assoc :gate/error (attr-get gate :gate/error))
      spawn-stalled? (assoc :stall/reason "spawn-error")
      run-failed? (assoc :stall/reason "agent-failure"))))

(defn- run-subagent-gates
  "Return all subagent gate strands reachable from run-history roots."
  [rt history]
  (->> history
       (mapcat (fn [{:keys [root]}]
                 (:strands (graph/subgraph rt [(:id root)] {:type "parent-of"}))))
       (filter #(= "subagent" (attr-get % :workflow/gate)))
       (sort-by :created_at)
       vec))

(defn gate-chain-mermaid
  "Return a dev-only Mermaid chain showing ready, stalled, and closed gates.

  `gates` are compact gate projections (as from `flow-status`); `ready-ids` is
  the set of ids on the ready frontier. This is the single render helper for the
  gate chain, so any op reusing it renders identical marker/node/link logic."
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

(defn flow-status
  "Return workflow flow status by joining history, frontier, gates, runs, and stalls.

  The JSON-compatible payload is read-only and suitable for renderers; no
  workflow, agent-run, or gate state is mutated. Failure summaries are scoped to
  this run's own gates and their delegated runs so records from other workflows
  never surface in an unrelated run's payload. Includes a `:dev/mermaid` gate
  chain rendered by `gate-chain-mermaid`."
  [rt run-id]
  (let [history (workflow/run-history run-id)
        frontier (workflow/ready run-id)
        done (workflow/done? run-id)
        run-gates (run-subagent-gates rt history)
        run-gate-ids (set (map :id run-gates))
        gate->run (into {} (keep (fn [g] (when-let [r (gate-serving-run-id rt (:id g))] [(:id g) r]))) run-gates)
        run-delegated-ids (set (vals gate->run))
        stalled-gates (filterv #(contains? run-gate-ids (:id %))
                               (weaver/list rt [:and [:= :state "active"]
                                                [:= [:attr "workflow/gate"] "subagent"]
                                                [:exists [:attr "gate/error"]]] {}))
        agent-failures (filterv #(contains? run-delegated-ids (:id %))
                                (weaver/list rt [:in [:attr "agent-run/phase"] ["failed" "exhausted"]] {}))
        stalled-gate-ids (set (map :id stalled-gates))
        failed-run-ids (set (map :id agent-failures))
        gates (mapv (partial compact-gate rt gate->run failed-run-ids stalled-gate-ids) run-gates)
        ready-ids (set (map :id frontier))]
    {:run-id run-id
     :history history
     :frontier frontier
     :gates gates
     :stalled-gates (mapv summarize stalled-gates)
     :agent-failures (mapv summarize agent-failures)
     :done done
     :dev/mermaid (gate-chain-mermaid gates ready-ids)}))

(defn install!
  "Return loom installation metadata for trusted registration by name.

  Loom registers no ops; it is a read-only projection library that repo config
  and other spools compose. This metadata mirrors the other read-only spools for
  discovery symmetry."
  []
  {:installed true
   :namespace 'skein.spools.loom
   :loom {:work-dags 'skein.spools.loom/work-dags
          :branch-views 'skein.spools.loom/branch-views
          :flow-status 'skein.spools.loom/flow-status
          :gate-chain-mermaid 'skein.spools.loom/gate-chain-mermaid
          :read-only true}})
