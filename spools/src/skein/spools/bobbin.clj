(ns skein.spools.bobbin
  "Assemble compact, self-contained context packs for delegated strand work.

  Bobbin is a reference spool that composes explicit-runtime public graph and
  weaver helper surfaces. It projects the strand graph around one target strand
  into a JSON-compatible bundle and renders that bundle as deterministic prompt
  text."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as api]
            [skein.spools.util :refer [fail! attr-get attr-key->str]]))

(def ^:private section-order [:strand :blockers :dependents :parents :children :notes :workflow])
(def ^:private selectable-sections (set section-order))
(def ^:private related-sections (disj selectable-sections :strand :workflow))

(defn- attr [strand k]
  (attr-get strand k))

(defn- summarize [strand]
  (select-keys strand [:id :title :state :attributes :created_at :updated_at]))

(defn- strand-by-id! [rt strand-id]
  (try
    (first (graph/strands-by-ids rt [strand-id]))
    (catch clojure.lang.ExceptionInfo e
      (fail! "Bobbin target strand was not found" {:strand-id strand-id} e))))

(defn- validate-include! [include]
  (let [include (or include selectable-sections)]
    (when-not (set? include)
      (fail! "Bobbin :include option must be a set" {:include include :allowed selectable-sections}))
    (when-let [unknown (seq (remove selectable-sections include))]
      (fail! "Bobbin :include option contains unknown sections"
             {:unknown (vec unknown) :allowed selectable-sections}))
    include))

(defn- active-query [expr]
  [:and [:= :state "active"] expr])

(defn- direct-active [rt relation direction target-id]
  (let [op (case direction :out :edge/out :in :edge/in)]
    (api/list rt (active-query [op relation [:= :id target-id]]) {})))

(defn- edge-summary [edge]
  (select-keys edge [:from_strand_id :to_strand_id :edge_type :attributes]))

(defn- internal-edges [edges ids]
  (let [ids (set ids)]
    (->> edges
         (filter #(and (contains? ids (:from_strand_id %))
                       (contains? ids (:to_strand_id %))))
         (mapv edge-summary))))

(defn- graph-section [strands edges]
  (let [strands (->> strands (sort-by :id) (mapv summarize))
        ids (mapv :id strands)]
    {:strands strands
     :edges (->> (internal-edges edges ids)
                 (sort-by (juxt :from_strand_id :to_strand_id :edge_type))
                 vec)}))

(defn- blockers-section [rt target-id]
  (let [sg (graph/subgraph rt [target-id] {:type "depends-on"})
        strands (->> (:strands sg)
                     (remove #(= target-id (:id %)))
                     (filter #(= "active" (:state %))))]
    (graph-section strands (:edges sg))))

(defn- dependents-section [rt target-id]
  (graph-section (direct-active rt "depends-on" :out target-id) []))

(defn- children-section [rt target-id]
  (graph-section (direct-active rt "parent-of" :in target-id) []))

(defn- direct-active-parents [rt target-id]
  (direct-active rt "parent-of" :out target-id))

(defn- parents-section [rt target-id]
  (loop [frontier [target-id]
         seen #{target-id}
         parents []]
    (if-let [id (first frontier)]
      (let [found (remove #(contains? seen (:id %)) (direct-active-parents rt id))]
        (recur (into (vec (rest frontier)) (map :id found))
               (into seen (map :id found))
               (into parents found)))
      (graph-section parents []))))

(defn- notes-section [rt target-id]
  (let [notes (->> (api/list rt [:edge/in "notes" [:= :id target-id]] {})
                   (sort-by (juxt #(or (attr % :shuttle/at) "") :created_at :id)))]
    (graph-section notes [])))

(defn- workflow-attrs [strand]
  (into {}
        (filter (fn [[k _]] (str/starts-with? (attr-key->str k) "workflow/")))
        (:attributes strand)))

(defn- workflow-section [rt strand]
  (when (seq (workflow-attrs strand))
    (let [run-id (attr strand :workflow/run-id)
          root (when run-id
                 (first (api/list rt [:and
                                      [:= [:attr "workflow/run-id"] run-id]
                                      [:= [:attr "workflow/role"] "molecule"]] {})))]
      (cond-> {:run-id run-id
               :role (attr strand :workflow/role)
               :attributes (workflow-attrs strand)}
        root (assoc :root (summarize root))))))

(defn pack
  "Return a JSON-compatible bobbin context bundle for strand-id.

  `opts` may include `:include`, a set drawn from `:strand`, `:blockers`,
  `:dependents`, `:parents`, `:children`, `:notes`, and `:workflow`. Unknown
  sections fail loudly with the allowed set in ex-data. Missing strand ids fail
  loudly. Every edge returned by a section references only strands summarized in
  that same section."
  ([strand-id]
   (pack strand-id {}))
  ([strand-id opts]
   (let [rt (current/runtime)
         include (validate-include! (:include opts))
         target (strand-by-id! rt strand-id)
         ;; nil section values (a target with no workflow attrs) are dropped so
         ;; consumers can detect workflow context by key presence
         section (fn [k f] (when (contains? include k)
                             (when-some [v (f)] [k v])))]
     (into {:bobbin/version 1
            :include (vec (filter include section-order))}
           (keep identity)
           [(section :strand #(summarize target))
            (section :blockers #(blockers-section rt strand-id))
            (section :dependents #(dependents-section rt strand-id))
            (section :parents #(parents-section rt strand-id))
            (section :children #(children-section rt strand-id))
            (section :notes #(notes-section rt strand-id))
            (section :workflow #(workflow-section rt target))]))))

(defn- salient-attrs [strand]
  (dissoc (:attributes strand) :body "body"))

(defn- strand-line [strand]
  (str "- " (:id strand) " | " (:title strand) " | " (:state strand)
       (when-let [attrs (not-empty (salient-attrs strand))]
         (str " | attrs " (pr-str (into (sorted-map) attrs))))))

(defn- section-lines [title strands]
  (into [(str "## " title)]
        (if (seq strands)
          (map strand-line strands)
          ["- none"])))

(defn render
  "Render a bobbin bundle as deterministic prompt text.

  Output uses stable section order and sorted related strands. The target strand
  is one compact line plus its `body` attribute in full when present."
  [bundle]
  (let [target (:strand bundle)
        body (or (attr target :body) (get-in target [:attributes "body"]))]
    (str/join
     "\n"
     (vec
      (concat ["# Bobbin context pack"
               "## Target"
               (strand-line target)]
              (when body ["### Body" body])
              (mapcat (fn [k]
                        (when-let [section (get bundle k)]
                          (section-lines (str/capitalize (name k)) (:strands section))))
                      (filter #(contains? bundle %) [:blockers :dependents :parents :children :notes]))
              (when-let [wf (:workflow bundle)]
                ["## Workflow" (pr-str (into (sorted-map) wf))]))))))

(defn install!
  "Return bobbin installation metadata for trusted registration by name."
  []
  {:installed true
   :namespace 'skein.spools.bobbin
   :attributes {:body :body
                :notes-edge "notes"
                :note-convention {:text :shuttle/note
                                  :by :shuttle/note-by
                                  :at :shuttle/at}
                :workflow-prefix "workflow/"}
   :fns {'pack 'skein.spools.bobbin/pack
         'render 'skein.spools.bobbin/render}})
