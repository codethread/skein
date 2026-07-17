(ns skein.spools.bobbin
  "Assemble compact, self-contained context packs for delegated strand work.

  Bobbin is a reference spool that composes public graph and weaver helper
  surfaces. It projects the strand graph around one target strand into a
  JSON-compatible bundle and renders that bundle as deterministic prompt text.

  Sections it did not invent are read through the primitive that owns them:
  `skein.api.notes.alpha` orders the notes section, `skein.api.spool.alpha`
  projects every strand row, and `skein.spools.workflow` resolves the active
  workflow root. The bundle's own vocabulary — the section shapes, `pack`, and
  `render` — is bobbin's."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.notes.alpha :as notes]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.workflow :as workflow]
            [skein.api.spool.alpha :refer [fail! attr-get attr-key->str entity-projection]]))

(def ^:private section-order [:strand :blockers :dependents :parents :children :notes :workflow])
(def ^:private selectable-sections (set section-order))

(defn- attr [strand k]
  (attr-get strand k))

(defn- summarize
  "Return the canonical entity projection of `strand`, extended with timestamps.

  A pack row is the canonical projection plus the timestamps a prompt reader
  needs to date the context (SPEC-005.C3 permits explicit extension). Routing
  through `entity-projection` means a strand missing a canonical field fails
  loudly here rather than shipping a partial row into a delegated prompt."
  [strand]
  (assoc (entity-projection strand)
         :created_at (:created_at strand)
         :updated_at (:updated_at strand)))

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
    (weaver/list rt (active-query [op relation [:= :id target-id]]) {})))

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

(defn- notes-section
  "Return the target's notes as a bobbin graph section, in the primitive's order.

  The `{:strands :edges}` section shape is bobbin's — `render` reads
  `(:strands section)` — but the note concept and its ordering belong to
  `skein.api.notes.alpha`: `notes` walks the incoming `notes` edges and parses
  `note/at` into an instant, because `Instant/toString` varies in fractional
  precision and a lexicographic sort misorders a note burst. Order is therefore
  read from the primitive and joined back to the summarized strand rows —
  `strands-by-ids` preserves input order — rather than re-sorted here."
  [rt target-id]
  (let [ordered-ids (mapv :id (notes/notes rt target-id {}))]
    {:strands (mapv summarize (graph/strands-by-ids rt ordered-ids))
     :edges []}))

(defn- workflow-attrs [strand]
  (into {}
        (filter (fn [[k _]] (str/starts-with? (attr-key->str k) "workflow/")))
        (:attributes strand)))

(defn- closed-root
  "Return the single closed workflow root for `run-id`, nil when absent.

  Only reached once `workflow/current-root` has ruled out an active root, so
  every root this query can find is closed. Bobbin deliberately resolves closed
  roots the workflow primitive cannot see: a pack is assemblable for a finished
  target — briefing a reviewer on completed work is a first-class use — and that
  target's run is closed along with its root. Ambiguity fails loudly rather than
  taking an arbitrary first, matching the primitive's contract for the active
  case."
  [rt run-id]
  (let [roots (weaver/list rt [:and
                               [:= [:attr "workflow/run-id"] run-id]
                               [:= [:attr "workflow/role"] "root"]] {})]
    (case (count roots)
      0 nil
      1 (first roots)
      (fail! "Multiple closed workflow roots found" {:run-id run-id :roots roots}))))

(defn- workflow-root
  "Return the workflow root strand for `run-id`, active if there is one.

  `workflow/current-root` owns the active case — including failing loudly on
  ambiguity — and `closed-root` covers the historical roots it filters out."
  [rt run-id]
  (or (workflow/current-root run-id)
      (closed-root rt run-id)))

(defn- workflow-section [rt strand]
  (when (seq (workflow-attrs strand))
    (let [run-id (attr strand :workflow/run-id)
          root (when run-id (workflow-root rt run-id))]
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
            :include (filterv include section-order)}
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
                :note-convention {:text :note/text
                                  :by :note/by
                                  :at :note/at}
                :workflow-prefix "workflow/"}
   :fns {'pack 'skein.spools.bobbin/pack
         'render 'skein.spools.bobbin/render}})
