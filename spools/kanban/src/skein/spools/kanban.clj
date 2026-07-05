(ns skein.spools.kanban
  "User-facing kanban board over Skein strands.

  Cards are the user<->agent tracking surface: everything a user asks for is a
  `feature` card (occasionally grouped under an `epic`), and every agent
  working directly with a user works under a claimed card. All card state
  lives under `kanban/*` attributes; `kanban/status` is the board lane
  (`refinement` -> `pending` -> `claimed` -> explicit closed outcome).

  Cards are work roots: claiming stamps `owner`/`branch`/`worktree`, and
  plans, devflow runs, and task DAGs hang beneath the card with `parent-of`
  edges — the kanban spool complements those workflows, it does not replace
  them. Notes and handovers are closed child note strands, so a cold agent
  can self-discover in-flight work: `kanban board` -> `kanban card <id>` ->
  latest handover."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as api]
            [skein.spools.format :as fmt]))

(def ^:private card-attr :kanban/card)
(def ^:private status-attr :kanban/status)
(def ^:private type-attr :kanban/type)
(def ^:private note-attr :kanban/note)
(def ^:private handover-attr :kanban/handover)

(def ^:private addable-statuses #{"pending" "refinement"})
(def ^:private card-types #{"feature" "epic"})

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- require-non-blank!
  "Return v when it is a non-blank string, otherwise throw with arg context."
  [arg v]
  (when-not (non-blank-string? v)
    (throw (ex-info (str (name arg) " must be a non-blank string")
                    {:argument arg :value v})))
  v)

(defn- require-flag!
  "Return the value of flag, failing loudly when it is absent."
  [op flags flag]
  (or (get flags flag)
      (throw (ex-info (str op " requires " flag)
                      {:flag flag :provided (sort (keys flags))}))))

(defn- attr-value
  "Return a strand attribute by keyword or string key."
  [strand k]
  (let [attrs (:attributes strand)
        kw (keyword k)]
    (or (get attrs kw)
        (get attrs (subs (str kw) 1)))))

(defn- card-type
  "Return a card's kanban type, defaulting to feature."
  [strand]
  (or (attr-value strand type-attr) "feature"))

(defn- card-attributes
  "Return the attributes for a newly added kanban card strand."
  [flags]
  (let [status (or (get flags "--status") "pending")
        type (or (get flags "--type") "feature")]
    (when-not (contains? addable-statuses status)
      (throw (ex-info "kanban add --status must be pending or refinement"
                      {:status status :allowed (sort addable-statuses)})))
    (when-not (contains? card-types type)
      (throw (ex-info "kanban add --type must be feature or epic"
                      {:type type :allowed (sort card-types)})))
    (cond-> {card-attr "true"
             status-attr status
             type-attr type
             :kind type}
      (get flags "--body") (assoc :body (get flags "--body"))
      (get flags "--source") (assoc :kanban/source (get flags "--source")))))

(defn- compact-card
  "Return the compact card shape used in board/next output."
  [strand]
  (cond-> {:id (:id strand)
           :title (:title strand)
           :state (:state strand)
           :status (attr-value strand status-attr)
           :type (card-type strand)
           :created_at (:created_at strand)}
    (attr-value strand :owner) (assoc :owner (attr-value strand :owner))
    (attr-value strand :branch) (assoc :branch (attr-value strand :branch))
    (attr-value strand :worktree) (assoc :worktree (attr-value strand :worktree))
    (attr-value strand :kanban/source) (assoc :source (attr-value strand :kanban/source))))

(defn- card-strand
  "Return id's kanban card strand, failing loudly if it is absent or not a card."
  [id]
  (let [strand (or (api/show (current/runtime) id)
                   (throw (ex-info "Kanban strand not found" {:id id})))]
    (when-not (= "true" (attr-value strand card-attr))
      (throw (ex-info "Strand is not a kanban card" {:id id :attributes (:attributes strand)})))
    strand))

(defn- epic-strand
  "Return id's epic card strand, failing loudly for non-epic cards."
  [id]
  (let [strand (card-strand id)]
    (when-not (= "epic" (card-type strand))
      (throw (ex-info "Strand is not an epic card" {:id id :type (card-type strand)})))
    strand))

(defn add!
  "Create a kanban card in the pending (or refinement) lane.

  `--type epic` creates a grouping epic; `--epic <id>` hangs a new feature
  under an existing epic with a parent-of edge."
  [title flags]
  (let [title (require-non-blank! :title title)
        rt (current/runtime)
        epic-id (get flags "--epic")]
    (when (and epic-id (= "epic" (get flags "--type")))
      (throw (ex-info "kanban epics cannot nest under other epics" {:epic epic-id})))
    (let [epic (some-> epic-id epic-strand)
          strand (api/add rt {:title title
                              :attributes (card-attributes flags)})]
      (when epic
        (api/update rt (:id epic) {:edges [{:type "parent-of" :to (:id strand)}]}))
      (cond-> {:operation "kanban add"
               :card (select-keys strand [:id :title :state :attributes])}
        epic (assoc :epic (:id epic))))))

;; kanban-batch weave pattern
(s/def ::non-blank-string non-blank-string?)
(s/def ::key ::non-blank-string)
(s/def ::title ::non-blank-string)
(s/def ::body ::non-blank-string)
(s/def ::deps (s/coll-of ::non-blank-string :kind vector?))
(def ^:private batch-item-keys #{:key :title :body :deps})
(def ^:private batch-input-keys #{:items})

(defn- known-keys?
  "Return true when map m contains only allowed keys."
  [allowed m]
  (empty? (remove allowed (keys m))))

(s/def ::batch-item
  (s/and map?
         #(known-keys? batch-item-keys %)
         (s/keys :req-un [::key ::title]
                 :opt-un [::body ::deps])))
(s/def ::items (s/coll-of ::batch-item :kind vector? :min-count 1))
(s/def ::kanban-batch-input
  (s/and map?
         #(known-keys? batch-input-keys %)
         (s/keys :req-un [::items])))

(defn- duplicate-item
  "Return the first duplicate value in xs, or nil."
  [xs]
  (some (fn [[v n]] (when (> n 1) v)) (frequencies xs)))

(defn- item-ref
  "Return the batch-local symbol for item key."
  [key]
  (symbol key))

(defn kanban-batch
  "Create pending feature cards with bodies and depends-on edges.

  Input shape: {:items [{:key \"slug\" :title \"Title\" :body \"optional\"
  :deps [\"sibling-key-or-existing-strand-id\"]}]}. `deps` values matching sibling
  keys become batch-local edges; all other values are treated as durable strand
  ids and fail loudly if absent."
  [{:keys [input]}]
  (let [{:keys [items]} input
        keys (mapv :key items)]
    (when-let [duplicate-key (duplicate-item keys)]
      (throw (ex-info "kanban-batch item keys must be unique" {:key duplicate-key})))
    (let [sibling-keys (set keys)]
      (mapv (fn [{:keys [key title body deps]}]
              (cond-> {:ref (item-ref key)
                       :title title
                       :attributes (card-attributes (cond-> {}
                                                      body (assoc "--body" body)))}
                (seq deps)
                (assoc :edges (mapv (fn [dep]
                                      {:type "depends-on"
                                       :to (if (contains? sibling-keys dep)
                                             (item-ref dep)
                                             dep)})
                                    deps))))
            items))))

(defn- require-status!
  "Return strand when it is active with the expected kanban status."
  [op strand expected]
  (when-not (= "active" (:state strand))
    (throw (ex-info (str "Kanban card must be active to " op)
                    {:id (:id strand) :state (:state strand)})))
  (when-not (= expected (attr-value strand status-attr))
    (throw (ex-info (str "Kanban card must be " expected " to " op)
                    {:id (:id strand) :status (attr-value strand status-attr)})))
  strand)

(defn- merge-attrs
  "Merge keyword-keyed attrs into strand attributes for api/update.

  Stored attributes come back keyword-keyed; merging string keys here would
  create duplicate logical keys with undefined precedence on write."
  [strand attrs]
  (merge (:attributes strand) attrs))

(defn- update-card!
  "Merge attrs (and optional state) onto a kanban card strand."
  [strand attrs state]
  (api/update (current/runtime)
              (:id strand)
              (cond-> {:attributes (merge-attrs strand attrs)}
                state (assoc :state state))))

(defn promote!
  "Move a refinement card into the pending lane (an explicit human act)."
  [id]
  (let [strand (require-status! "promote" (card-strand (require-non-blank! :id id)) "refinement")
        updated (update-card! strand {status-attr "pending"} nil)]
    {:operation "kanban promote"
     :card (select-keys updated [:id :title :state :attributes])}))

(defn claim!
  "Claim a pending feature card, stamping the work-root attributes.

  `--owner` and `--branch` are mandatory so every claimed card answers who is
  driving it and on which branch; `--worktree` is optional (direct work in the
  main checkout has no separate worktree). Epics group work and are never
  claimed themselves."
  [id flags]
  (let [strand (require-status! "claim" (card-strand (require-non-blank! :id id)) "pending")]
    (when (= "epic" (card-type strand))
      (throw (ex-info "Kanban epics cannot be claimed; claim a feature under the epic"
                      {:id (:id strand)})))
    (let [owner (require-flag! "kanban claim" flags "--owner")
          branch (require-flag! "kanban claim" flags "--branch")
          attrs (cond-> {status-attr "claimed"
                         :owner owner
                         :branch branch}
                  (get flags "--worktree") (assoc :worktree (get flags "--worktree")))
          updated (update-card! strand attrs nil)]
      {:operation "kanban claim"
       :card (select-keys updated [:id :title :state :attributes])})))

(defn finish!
  "Close a kanban card with an explicit outcome status."
  [id flags]
  (let [id (require-non-blank! :id id)
        strand (card-strand id)
        outcome (or (get flags "--outcome") "done")]
    (when-not (= "active" (:state strand))
      (throw (ex-info "Kanban card must be active to finish" {:id id :state (:state strand)})))
    (let [updated (update-card! strand {status-attr outcome} "closed")]
      {:operation "kanban finish"
       :card (select-keys updated [:id :title :state :attributes])})))

;; ---------------------------------------------------------------------------
;; notes and handovers
;; ---------------------------------------------------------------------------

(defn- note-title
  "Return a compact strand title for a note's text."
  [handover? text]
  (let [prefix (if handover? "Handover: " "Note: ")
        line (first (str/split-lines text))]
    (str prefix (if (> (count line) 90) (str (subs line 0 90) "...") line))))

(defn note!
  "Append a note (or `--handover` note) as a closed child strand of a card.

  Notes are strands rather than attributes so concurrent agents never race a
  read-merge-write cycle and every note keeps its own timestamp and author.
  A handover note is the crash/stop contract: record what is done, what is
  next, validation state, and gotchas so any agent can resume from
  `kanban card <id>` alone."
  [id text flags]
  (let [card (card-strand (require-non-blank! :id id))
        text (require-non-blank! :text text)
        handover? (boolean (get flags "--handover"))
        rt (current/runtime)
        note (api/add rt {:title (note-title handover? text)
                          :state "closed"
                          :attributes (cond-> {note-attr "true"
                                               :kind "note"
                                               :body text}
                                        handover? (assoc handover-attr "true")
                                        (get flags "--author") (assoc :author (get flags "--author")))})]
    (api/update rt (:id card) {:edges [{:type "parent-of" :to (:id note)}]})
    {:operation "kanban note"
     :card (:id card)
     :note (select-keys note [:id :title :state :attributes])}))

(defn- compact-note
  "Return the compact note shape used in card output."
  [strand]
  (cond-> {:id (:id strand)
           :title (:title strand)
           :body (attr-value strand :body)
           :created_at (:created_at strand)
           :handover (= "true" (attr-value strand handover-attr))}
    (attr-value strand :author) (assoc :author (attr-value strand :author))))

(defn- summarize-strand
  "Return the compact strand shape used in card subtree output."
  [strand]
  (select-keys strand [:id :title :state :attributes]))

(defn- note-strand?
  "Return true when strand is a kanban note."
  [strand]
  (= "true" (attr-value strand note-attr)))

(defn- truthy-attr?
  "Return true for a JSON-decoded boolean true or its string form."
  [v]
  (or (true? v) (= "true" v)))

(defn- review-item?
  "Return true when strand marks itself for human review.

  Any of hitl, workflow/hitl (boolean true or \"true\"), or kind \"review\"."
  [strand]
  (or (truthy-attr? (attr-value strand :hitl))
      (truthy-attr? (attr-value strand :workflow/hitl))
      (= "review" (attr-value strand :kind))))

(defn- card-relations
  "Return depends-on relations touching card-id, sorted by other-endpoint id.

  Roots the subgraph at every strand id because depends-on expansion only
  walks outgoing edges: rooting at the card (or even all cards) never yields
  edges whose dependent is an unrelated strand, so incoming edges from
  non-card work would be dropped. The full root set keeps every edge incident
  to the card visible, both directions, any strand, any state."
  [rt card-id]
  (let [all-ids (mapv :id (api/list rt))
        {:keys [strands edges]} (api/subgraph rt all-ids {:type "depends-on"})
        by-id (into {} (map (juxt :id identity)) strands)]
    (->> edges
         (keep (fn [{:keys [from_strand_id to_strand_id]}]
                 (cond
                   (= card-id from_strand_id) [to_strand_id "depends-on"]
                   (= card-id to_strand_id) [from_strand_id "depended-on-by"]
                   :else nil)))
         (sort-by first)
         (mapv (fn [[other relation]]
                 {:relation relation :strand (summarize-strand (by-id other))})))))

(defn- card-subtree
  "Return the card's parent-of subgraph split into notes and work strands."
  [rt card]
  (let [{:keys [strands edges]} (api/subgraph rt [(:id card)] {:type "parent-of"})
        child-ids (->> edges
                       (filter #(= (:id card) (:from_strand_id %)))
                       (map :to_strand_id)
                       set)
        others (remove #(= (:id card) (:id %)) strands)
        notes (->> others
                   (filter #(and (note-strand? %) (contains? child-ids (:id %))))
                   (sort-by (juxt :created_at :id))
                   reverse
                   vec)
        work (->> others
                  (remove note-strand?)
                  (sort-by :id)
                  vec)]
    {:notes notes :work work}))

(defn card-view
  "Return one card joined to its notes, latest handover, work, and frontier.

  This is the resume entry point: everything an agent needs to continue a
  card lives here."
  [id]
  (let [rt (current/runtime)
        card (card-strand (require-non-blank! :id id))
        {:keys [notes work]} (card-subtree rt card)
        active-work (filterv #(= "active" (:state %)) work)
        work-ids (set (map :id active-work))
        ready (filterv #(contains? work-ids (:id %)) (api/ready rt))]
    {:operation "kanban card"
     :card (select-keys card [:id :title :state :attributes :created_at :updated_at])
     :latest-handover (some->> notes (filter #(= "true" (attr-value % handover-attr))) first compact-note)
     :notes (mapv compact-note notes)
     :active-work (mapv summarize-strand active-work)
     :ready (mapv summarize-strand ready)
     :related (card-relations rt (:id card))}))

;; ---------------------------------------------------------------------------
;; board
;; ---------------------------------------------------------------------------

(defn- cards
  "Return all kanban card strands."
  []
  (api/list (current/runtime) [:= [:attr "kanban/card"] "true"] {}))

(defn- by-created
  "Return strands sorted oldest first."
  [strands]
  (sort-by (juxt :created_at :id) strands))

(defn next-card
  "Return the oldest active pending feature card, or nil."
  []
  (some->> (cards)
           (filter #(and (= "active" (:state %))
                         (= "pending" (attr-value % status-attr))
                         (= "feature" (card-type %))))
           by-created
           first
           compact-card))

(defn- epic-membership
  "Return {feature-card-id epic-id} for direct features under active epics."
  [rt epics]
  (into {}
        (mapcat (fn [epic]
                  (let [{:keys [edges]} (api/subgraph rt [(:id epic)] {:type "parent-of"})]
                    (->> edges
                         (filter #(= (:id epic) (:from_strand_id %)))
                         (map (fn [edge] [(:to_strand_id edge) (:id epic)]))))))
        epics))

(defn- latest-handover-for
  "Return the compact latest handover note for a card, or nil."
  [rt card]
  (some->> (:notes (card-subtree rt card))
           (filter #(= "true" (attr-value % handover-attr)))
           first
           compact-note))

(defn- needs-review-entries
  "Return review-frontier entries across claimed feature cards.

  An entry qualifies when a card descendant is active, in the engine ready
  frontier, and marks human review. Sorted by card id then item id."
  [rt claimed-features]
  (let [ready-ids (set (map :id (api/ready rt)))]
    (->> claimed-features
         (mapcat (fn [card]
                   (let [{:keys [work]} (card-subtree rt card)
                         branch (attr-value card :branch)]
                     (->> work
                          (filter #(and (= "active" (:state %))
                                        (contains? ready-ids (:id %))
                                        (review-item? %)))
                          (map (fn [item]
                                 (cond-> {:card (:id card) :item (summarize-strand item)}
                                   branch (assoc :branch branch))))))))
         (sort-by (juxt :card #(get-in % [:item :id])))
         vec)))

(defn board
  "Return the grouped board snapshot: epics, feature lanes, closed count.

  Claimed cards carry their latest handover so a cold agent can see in one
  call who is working where and how to pick up interrupted work.
  `:needs-review` aggregates the human-review frontier across claimed cards."
  []
  (let [rt (current/runtime)
        all (cards)
        active (filter #(= "active" (:state %)) all)
        epics (filterv #(= "epic" (card-type %)) active)
        features (remove #(= "epic" (card-type %)) active)
        claimed-features (filter #(= "claimed" (attr-value % status-attr)) features)
        membership (epic-membership rt epics)
        with-epic (fn [card]
                    (cond-> (compact-card card)
                      (membership (:id card)) (assoc :epic (membership (:id card)))))
        lane (fn [status]
               (->> features
                    (filter #(= status (attr-value % status-attr)))
                    by-created
                    (mapv with-epic)))
        known-lanes #{"refinement" "pending" "claimed"}
        unknown (->> features
                     (remove #(contains? known-lanes (attr-value % status-attr)))
                     by-created
                     (mapv with-epic))]
    (cond-> {:operation "kanban board"
             :epics (mapv compact-card (by-created epics))
             :refinement (lane "refinement")
             :pending (lane "pending")
             :claimed (mapv (fn [card]
                              (cond-> (with-epic card)
                                (latest-handover-for rt card)
                                (assoc :latest-handover (latest-handover-for rt card))))
                            (by-created claimed-features))
             :needs-review (needs-review-entries rt claimed-features)
             :closed {:count (count (filter #(= "closed" (:state %)) all))}}
      ;; active cards outside the known lanes are drift; surface them loudly
      (seq unknown) (assoc :unknown-status unknown))))

;; ---------------------------------------------------------------------------
;; ASCII board: REPL human view (the CLI stays JSON-only per TEN-006)
;; ---------------------------------------------------------------------------

(def ^:private board-width 100)

(defn- clip
  "Return s truncated with an ellipsis to fit within n characters."
  [n s]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 (- n 3)) "...") s)))

(defn- card-line
  "Return one ASCII board row for a compact card map."
  [{:keys [id title owner branch epic]}]
  (let [tags (cond-> []
               branch (conj (str "@" branch))
               owner (conj owner)
               epic (conj (str "epic:" epic)))
        prefix (str "  " id "  " (when (seq tags) (str "[" (str/join " " tags) "] ")))]
    (str prefix (clip (- board-width (count prefix)) title))))

(defn- lane-lines
  "Return the ASCII section for one board lane."
  [label entries row-fn]
  (into [(str label " (" (count entries) ")")]
        (if (seq entries)
          (mapv row-fn entries)
          ["  (none)"])))

(defn- handover-line
  "Return the indented latest-handover row for a claimed card, or nil."
  [{:keys [latest-handover]}]
  (when latest-handover
    (str "         " (clip (- board-width 9)
                           (str (:created_at latest-handover) "  "
                                (first (str/split-lines (or (:body latest-handover) ""))))))))

(defn- review-line
  "Return one ASCII row for a needs-review entry."
  [{:keys [card branch item]}]
  (let [prefix (str "  " (:id item) "  [card " card (when branch (str " @" branch)) "] ")]
    (str prefix (clip (- board-width (count prefix)) (:title item)))))

(defn board-str
  "Render a `board` result map as a stacked-lane ASCII board string."
  [{:keys [epics refinement pending claimed needs-review closed unknown-status]}]
  (let [rule (apply str (repeat board-width \=))]
    (->> (concat
          [(str "KANBAN BOARD  (closed: " (:count closed) ")") rule]
          (lane-lines "EPICS" epics card-line)
          [""]
          (lane-lines "REFINEMENT" refinement card-line)
          [""]
          (lane-lines "PENDING" pending card-line)
          [""]
          (lane-lines "CLAIMED / WIP" claimed
                      (fn [card]
                        (if-let [handover (handover-line card)]
                          (str (card-line card) "\n" handover)
                          (card-line card))))
          [""]
          (lane-lines "NEEDS REVIEW" needs-review review-line)
          (when (seq unknown-status)
            (into [""] (lane-lines "UNKNOWN STATUS (drift!)" unknown-status card-line))))
         (str/join "\n"))))

(defn print-board!
  "Print the live board as ASCII; the human view for `mill weaver repl`."
  []
  (println (board-str (board))))

(defn about
  "Return the kanban convention and installed helper surface."
  []
  {:operation "kanban about"
   :summary "Kanban cards are the user<->agent work board; agents working directly with a user work under a claimed card."
   :lanes {:refinement "not actionable until an explicit human `kanban promote`"
           :pending "actionable queue; `kanban next` serves the oldest feature first"
           :claimed "work started; owner/branch (and worktree) stamped at claim"
           :closed "finished with kanban/status recording the outcome (done, abandoned, ...)"}
   :attributes {card-attr "true"
                type-attr "feature (default) | epic (grouping; parent-of its features)"
                status-attr "refinement|pending|claimed|<outcome>"
                note-attr "true on note strands (closed parent-of children of a card)"
                handover-attr "true on handover notes"
                :kanban/source "optional path or URL for design context"
                :owner "claimant, required at claim"
                :branch "work branch, required at claim"
                :worktree "optional worktree path"}
   :convention (fmt/reflow "
                 |The card is the work root: claim stamps owner/branch, and plans, devflow runs, and
                 |task DAGs hang under it with parent-of. Kanban complements devflow and delegation;
                 |it never tracks shuttle runs directly.")
   :handover-contract (fmt/reflow "
                        |Before stopping (or at any interruption risk), write `kanban note <id> --handover`
                        |covering: what is done, what is next, validation state, gotchas, and where the
                        |work lives (branch/worktree). Resume path for a cold agent: `kanban board` ->
                        |`kanban card <id>` -> latest handover.")
   :commands [{:usage "strand kanban prime — full agent priming: working discipline + command surface (repo agent docs point here)"}
              {:usage (str "strand kanban add <title> [--body <text>] [--source <path-or-url>] "
                           "[--status pending|refinement] [--type feature|epic] [--epic <epic-id>]")}
              {:usage "strand weave --pattern kanban-batch --input '<json>'"}
              {:usage "strand kanban board"}
              {:usage "strand kanban card <id>"}
              {:usage "strand kanban next"}
              {:usage "strand kanban promote <id>"}
              {:usage "strand kanban claim <id> --owner <name> --branch <branch> [--worktree <path>]"}
              {:usage "strand kanban note <id> <text> [--author <name>] [--handover]"}
              {:usage "strand kanban finish <id> [--outcome done|abandoned]"}
              {:usage "(skein.spools.kanban/print-board!) — ASCII board from mill weaver repl (CLI output stays JSON-only)"}]
   :patterns [{:name "kanban-batch"
               :input {:items [{:key "slug"
                                :title "Feature title"
                                :body "optional body"
                                :deps ["sibling-key-or-existing-strand-id"]}]}}]})

(defn prime
  "Return the full agent-priming payload for working the kanban board.

  The single source of truth for kanban usage discipline: repo agent docs
  point here (`strand kanban prime`) rather than duplicating conventions that
  then drift from the spool. A superset of `about` — it reuses the same lane,
  attribute, command, and pattern surface and adds the working agreement,
  pick-up flow, notes/handover discipline, adjacent-work awareness, and branch
  visibility that an agent needs before touching the board."
  []
  (assoc (about)
         :operation "kanban prime"
         :working-agreement
         (fmt/fill "
               |Every user request is a feature card; occasionally group related cards under an
               |`epic` (`--type epic`, link features with `--epic <id>`).
               |
               |Every agent working directly with the user works under a claimed card — claim
               |before starting user work.
               |
               |Kanban complements devflow, agent plans, and delegation; those hang beneath a
               |card via `parent-of`. Kanban never tracks shuttle runs directly.
               |
               |Half-formed ideas go to the refinement lane (`kanban add \"...\" --status
               |refinement`); they stay inert until a human `kanban promote`s them.")
         :pick-up-next-card
         (fmt/fill "
               |`kanban next` serves the oldest pending feature card.
               |
               |Claim it: `kanban claim <id> --owner <name> --branch <branch> [--worktree
               |<path>]` — owner and branch are mandatory; the claim is what makes branch work
               |discoverable.
               |
               |Create feature plans, devflow runs, or task DAGs under the card via `parent-of`.
               |The card is the parent/audit root; child strands are the executable work.
               |
               |`kanban finish <id> [--outcome done|abandoned]` after merge, archive, or
               |explicit abandonment.")
         :notes-and-handovers
         (fmt/fill "
               |Record significant decisions as you go: `kanban note <id> \"...\" --author
               |<name>`.
               |
               |Always leave a `--handover` note before stopping or at any interruption risk,
               |covering: what is done, what is next, validation state, gotchas, and where the
               |work lives (branch/worktree).
               |
               |Crash recovery is self-discovering: `kanban board` shows claimed cards with their
               |latest handover; `kanban card <id>` returns the card, notes, active work, and
               |ready frontier.")
         :staying-aware
         (fmt/fill "
               |`kanban board` returns `needs-review`: the human-review frontier aggregated
               |across claimed cards (ready hitl/review work grouped by card and branch).
               |
               |Inside a feature branch, `strand branches \"$(git branch --show-current)\"`
               |shows the feature cards worked on there and their substrands.
               |
               |Relate adjacent work with `depends-on` edges (`strand update <a> --edge
               |depends-on:<b>`) and check `related` in `kanban card <id>` when claiming or
               |resuming, so blockers and dependents surface.")
         :branch-visibility
         (fmt/reflow "
               |Every piece of work on a branch has exactly one active work root strand stamped
               |`branch` (plus `owner`, and `worktree` when one exists), with execution strands
               |beneath it via `parent-of`. `kanban claim` stamps card roots; for non-card roots
               |(ad hoc agent-plan roots, coordination strands) stamp them yourself: `strand
               |update <root-id> --attr branch=<branch> --attr owner=<name>`. Children are
               |reachable from the root and need no `branch` attr of their own.")))

(def ^:private kanban-arg-spec
  "Declared command surface for the `kanban` op."
  {:op "kanban"
   :doc "Manage the user-facing kanban work board. Run `strand kanban about` for the convention manual."
   :subcommands
   {"about" {:doc "Return the kanban convention and installed helper surface."}
    "prime" {:doc "Return full agent priming for working the kanban board."}
    "add" {:doc "Create a feature or epic card."
           :flags {:body {:doc "Longer card context."}
                   :source {:doc "Path or URL for design context."}
                   :status {:doc "Initial lane: pending or refinement."}
                   :type {:doc "Card type: feature or epic."}
                   :epic {:doc "Existing epic card id to parent this feature under."}}
           :positionals [{:name :title
                          :required? true
                          :variadic? true
                          :doc "Card title words."}]}
    "board" {:doc "Return the grouped board snapshot."}
    "card" {:doc "Return one card's resume view."
            :positionals [{:name :id :required? true :doc "Kanban card id."}]}
    "next" {:doc "Return the oldest active pending feature card."}
    "promote" {:doc "Move a refinement card into the pending lane."
               :positionals [{:name :id :required? true :doc "Kanban card id."}]}
    "claim" {:doc "Claim a pending feature card."
             :flags {:owner {:doc "Claimant name (required by handler)."}
                     :branch {:doc "Work branch (required by handler)."}
                     :worktree {:doc "Optional worktree path."}}
             :positionals [{:name :id :required? true :doc "Kanban card id."}]}
    "note" {:doc "Append a note or handover as a closed child strand."
            :flags {:author {:doc "Note author."}
                    :handover {:type :boolean :doc "Mark this note as a handover."}}
            :positionals [{:name :id :required? true :doc "Kanban card id."}
                          {:name :text
                           :required? true
                           :variadic? true
                           :doc "Note text words."}]}
    "finish" {:doc "Close a kanban card with an explicit outcome status."
              :flags {:outcome {:doc "Closed outcome status; defaults to done."}}
              :positionals [{:name :id :required? true :doc "Kanban card id."}]}}})

(defn- legacy-flags
  "Return parsed keyword flags in the string-keyed shape expected by handlers."
  [args]
  (into {}
        (keep (fn [[k v]]
                (when (and (not= k :subcommand)
                           (some? v)
                           (not (contains? #{:id :title :text} k)))
                  [(str "--" (name k)) v])))
        args))

(defn kanban-op
  "Dispatch parsed `strand kanban ...` subcommands."
  [{:op/keys [args]}]
  (let [flags (legacy-flags args)]
    (case (:subcommand args)
      "about" (about)
      "prime" (prime)
      "add" (add! (str/join " " (:title args)) flags)
      "board" (board)
      "card" (card-view (:id args))
      "next" {:operation "kanban next" :next (next-card)}
      "promote" (promote! (:id args))
      "claim" (claim! (:id args) flags)
      "note" (note! (:id args) (str/join " " (:text args)) flags)
      "finish" (finish! (:id args) flags))))

(defn install!
  "Install the kanban op, batch pattern, and board queries into the active weaver."
  []
  (let [rt (current/runtime)]
    {:installed true
     :namespace 'skein.spools.kanban
     :ops [(api/register-op! rt 'kanban
                             {:doc "Manage the user-facing kanban work board. Run `strand kanban about` for the convention manual."
                              :arg-spec kanban-arg-spec
                              :hook-class :mutating}
                             'skein.spools.kanban/kanban-op)]
     :pattern (patterns/register-pattern! rt 'kanban-batch
                                          "Create pending feature cards with bodies and depends-on edges."
                                          'skein.spools.kanban/kanban-batch
                                          ::kanban-batch-input)
     :queries [(api/register-query! rt 'kanban-cards [:= [:attr "kanban/card"] "true"])
               (api/register-query! rt 'kanban-unstarted
                                    [:and
                                     [:= :state "active"]
                                     [:= [:attr "kanban/card"] "true"]
                                     [:= [:attr "kanban/status"] "pending"]])]}))
