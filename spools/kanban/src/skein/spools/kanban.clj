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
            [skein.api.weaver.alpha :as api]))

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

(defn- parse-op-argv
  "Parse op argv into positional args, single-value flags, and boolean flags."
  [op argv flag-spec]
  (loop [remaining argv
         positional []
         flags {}]
    (if-let [arg (first remaining)]
      (if (str/starts-with? arg "--")
        (let [kind (or (get flag-spec arg)
                       (throw (ex-info (str op " unknown flag")
                                       {:flag arg :allowed (sort (keys flag-spec))})))]
          (case kind
            :flag (recur (rest remaining) positional (assoc flags arg true))
            :single (let [value (or (second remaining)
                                    (throw (ex-info (str op " flag requires a value")
                                                    {:flag arg})))]
                      (recur (drop 2 remaining) positional (assoc flags arg value)))
            (throw (ex-info (str op " unsupported flag kind")
                            {:flag arg :kind kind}))))
        (recur (rest remaining) (conj positional arg) flags))
      {:positional positional :flags flags})))

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
   :convention "The card is the work root: claim stamps owner/branch, and plans, devflow runs, and task DAGs hang under it with parent-of. Kanban complements devflow and delegation; it never tracks shuttle runs directly."
   :handover-contract "Before stopping (or at any interruption risk), write `kanban note <id> --handover` covering: what is done, what is next, validation state, gotchas, and where the work lives (branch/worktree). Resume path for a cold agent: `kanban board` -> `kanban card <id>` -> latest handover."
   :commands [{:usage "strand kanban add <title> [--body <text>] [--source <path-or-url>] [--status pending|refinement] [--type feature|epic] [--epic <epic-id>]"}
              {:usage "strand weave --pattern kanban-batch --input '<json>'"}
              {:usage "strand kanban board"}
              {:usage "strand kanban card <id>"}
              {:usage "strand kanban next"}
              {:usage "strand kanban promote <id>"}
              {:usage "strand kanban claim <id> --owner <name> --branch <branch> [--worktree <path>]"}
              {:usage "strand kanban note <id> <text> [--author <name>] [--handover]"}
              {:usage "strand kanban finish <id> [--outcome done|abandoned]"}]
   :patterns [{:name "kanban-batch"
               :input {:items [{:key "slug"
                                :title "Feature title"
                                :body "optional body"
                                :deps ["sibling-key-or-existing-strand-id"]}]}}]})

(defn kanban-op
  "Dispatch `strand kanban ...` subcommands."
  [ctx]
  (let [[subcommand & argv] (:op/argv ctx)
        one-id! (fn [op {:keys [positional]}]
                  (when-not (= 1 (count positional))
                    (throw (ex-info (str op " expects one id") {:argv argv})))
                  (first positional))]
    (case subcommand
      "about" (about)
      "add" (let [{:keys [positional flags]}
                  (parse-op-argv "kanban add" argv {"--body" :single
                                                    "--source" :single
                                                    "--status" :single
                                                    "--type" :single
                                                    "--epic" :single})]
              (add! (str/join " " positional) flags))
      "board" (do
                (when (seq argv)
                  (throw (ex-info "kanban board expects no arguments" {:argv argv})))
                (board))
      "card" (card-view (one-id! "kanban card" (parse-op-argv "kanban card" argv {})))
      "next" {:operation "kanban next" :next (next-card)}
      "promote" (promote! (one-id! "kanban promote" (parse-op-argv "kanban promote" argv {})))
      "claim" (let [{:keys [flags] :as parsed}
                    (parse-op-argv "kanban claim" argv {"--owner" :single
                                                        "--branch" :single
                                                        "--worktree" :single})]
                (claim! (one-id! "kanban claim" parsed) flags))
      "note" (let [{:keys [positional flags]}
                   (parse-op-argv "kanban note" argv {"--author" :single
                                                      "--handover" :flag})
                   [id & text] positional]
               (note! id (str/join " " text) flags))
      "finish" (let [{:keys [flags] :as parsed}
                     (parse-op-argv "kanban finish" argv {"--outcome" :single})]
                 (finish! (one-id! "kanban finish" parsed) flags))
      (throw (ex-info "kanban expects a subcommand"
                      {:usage "strand kanban <about|add|board|card|next|promote|claim|note|finish> ..."
                       :subcommand subcommand})))))

(defn install!
  "Install the kanban op, batch pattern, and board queries into the active weaver."
  []
  (let [rt (current/runtime)]
    {:installed true
     :namespace 'skein.spools.kanban
     :ops [(api/register-op! rt 'kanban
                             "Manage the user-facing kanban work board"
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
