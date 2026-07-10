(ns skein.api.notes.alpha
  "Explicit-runtime cross-spool note primitive: the blessed writer and reader
  for strand memory.

  A note is an immutable, born-closed strand (memory, not work) linked to its
  target by an outgoing `notes` annotation edge. `note!` writes that strand and
  edge; `notes` walks the incoming `notes` edges to a target and projects each
  note in `note/at` order. The link is the edge alone — no `note/for` attribute
  — so a target's deletion cascades the edge and the note becomes unreachable
  through the read with no dangling pointer, and every writer that uses this
  primitive is visible to every reader regardless of the decorating attributes a
  caller layers on its notes.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument, per the blessed-namespace convention.

  Beside the primitive lives a writer *value* family: `writer` carries a target
  (or a thunk resolving one), a default decoration, and an author; `write!`
  appends through `note!` merging per-call decoration over the default;
  `writer-ref` freezes the writer to a plain-data ref that ships into
  subprocesses; and `writer-ref->prompt` is the single renderer of the
  note-writing CLI instruction fragment. There is deliberately no `ref->writer`
  — the constructor reconstructs from a ref.

  The writer and writer-ref shapes are specced (`::writer`, `::writer-ref`) and
  every entry validates through one boundary (`check-shape!`), so the family has
  a single fail-loud, field-named contract instead of hand-rolled checks."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.graph.alpha :as graph]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.weaver.alpha :as weaver]))

(defn- truncate
  "Return `s` capped at `n` characters, ellipsizing when it overflows."
  [s n]
  (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

(defn- note-attr
  "Read the `note/<k>` memory attribute from a normalized note strand."
  [note k]
  (get (:attributes note) (keyword "note" k)))

(defn- require-int-round
  "Return `round` when it is an integer (or nil); otherwise fail loudly.

  `note/round` is single-typed by contract: every writer stores an integer and
  the read filter compares integers, so a round written through one surface is
  always visible through another."
  [round]
  (when (and (some? round) (not (integer? round)))
    (throw (ex-info "note/round must be an integer" {:round round :type (type round)})))
  round)

(defn- at-instant
  "Chronological sort key for a note: its `note/at` parsed as an Instant, else
  epoch. `Instant/toString` varies in fractional precision, so lexicographic
  comparison misorders notes; parsing restores chronological order."
  [note]
  (if-let [at (note-attr note "at")]
    (try (java.time.Instant/parse at)
         (catch Exception _ java.time.Instant/EPOCH))
    java.time.Instant/EPOCH))

(defn note!
  "Append an immutable note strand to `target-id`'s memory and return its id.

  The note is born closed, carries `note/text`, a sub-second `note/at`
  timestamp, optional `note/by`/`note/round`, and any caller-supplied decorating
  attrs, and links to the target by an outgoing `notes` edge — never a
  `note/for` attribute. Fails loudly on blank text, a missing target, or a
  non-integer `:round` (the `note/round` contract is single-typed)."
  [runtime target-id text {:keys [by round] :as opts}]
  (when (str/blank? text)
    (throw (ex-info "Note text must be non-blank" {})))
  (when-not (weaver/show runtime target-id)
    (throw (ex-info "Note target strand not found" {:id target-id})))
  (require-int-round round)
  (let [decorating (dissoc opts :by :round)
        note (weaver/add runtime
                         {:title (truncate text 72)
                          :state "closed"
                          ;; note/at carries sub-second precision the seconds-only
                          ;; created_at column cannot, so it orders a note burst.
                          :attributes (cond-> (merge decorating
                                                     {"note/text" text
                                                      "note/at" (str (runtime/now runtime))})
                                        by (assoc "note/by" by)
                                        round (assoc "note/round" round))
                          :edges [{:type "notes" :to target-id}]})]
    {:id (:id note) :target target-id}))

(defn notes
  "Return `target-id`'s notes in `note/at` order, optionally one `:round`.

  Walks the incoming `notes` edges to the target, so it returns notes from every
  writer that used the primitive regardless of their decorating attrs. Projects
  each note as `{:id :note :at}` plus `:by`/`:round` when present. `:round` must
  be an integer (fails loudly otherwise); ordering parses `note/at` so mixed
  fractional-precision timestamps still sort chronologically."
  [runtime target-id {:keys [round]}]
  (require-int-round round)
  (let [note-ids (mapv :from_strand_id (graph/incoming-edges runtime [target-id] "notes"))]
    (->> (graph/strands-by-ids runtime note-ids)
         (filter (fn [note] (or (nil? round) (= round (note-attr note "round")))))
         (sort-by (juxt at-instant :created_at :id))
         (mapv (fn [note]
                 (cond-> {:id (:id note)
                          :note (note-attr note "text")
                          :at (or (note-attr note "at") (:created_at note))}
                   (note-attr note "by") (assoc :by (note-attr note "by"))
                   (note-attr note "round") (assoc :round (note-attr note "round"))))))))

;; Decoration keys are ordinary strand attrs on the note strand, so both key and
;; value must already be strings — no silent coercion. A writer target may be a
;; strand-id string or a 0-arg thunk; a writer-ref target is always the resolved
;; string. Both keep the unqualified key `:target` so one boundary check names it.
(s/def :skein.api.notes.alpha.writer/target (s/or :id string? :thunk fn?))
(s/def :skein.api.notes.alpha.writer-ref/target string?)
(s/def ::decoration (s/nilable (s/map-of string? string?)))
(s/def ::by (s/nilable string?))
(s/def ::writer
  (s/keys :req-un [:skein.api.notes.alpha.writer/target]
          :opt-un [::decoration ::by]))
(s/def ::writer-ref
  (s/keys :req-un [:skein.api.notes.alpha.writer-ref/target]
          :opt-un [::decoration ::by]))

(defn- check-shape!
  "Validate `value` against `spec`, failing loudly and naming the offending
  field. The writer family's single validation boundary: writer/write!/
  writer-ref->prompt route here so a malformed shape throws one consistent,
  field-named ex-info instead of each entry hand-rolling predicate checks. The
  explain string always names the failing field (path or missing key), so the
  historical fail-loud named-field contract survives; `label` prefixes it. A
  top-level non-map (nil, string, vector) has an empty problem path, so `:field`
  is `:root` there — never nil."
  [spec label value]
  (when-not (s/valid? spec value)
    (let [problem (first (::s/problems (s/explain-data spec value)))]
      (throw (ex-info (str label " shape invalid: " (s/explain-str spec value))
                      {:field (or (first (:in problem)) :root) :value value}))))
  value)

(defn- resolve-target
  "Resolve a writer target to a strand-id string, calling a thunk each time.

  A thunk returning a non-string (nil, keyword, number) fails loudly here,
  naming the bad return, rather than surfacing later in a downstream `note!` or
  `show` call against a nonsensical id."
  [target-or-thunk]
  (let [id (if (fn? target-or-thunk) (target-or-thunk) target-or-thunk)]
    (when-not (string? id)
      (throw (ex-info "writer target must resolve to a strand-id string"
                      {:field :target :resolved id :type (type id)})))
    id))

(defn writer
  "Return a writer value bound to `runtime`, wrapping the `note!` primitive.

  `target-or-thunk` is a strand-id string or a 0-arg fn returning one — the
  target may not exist at construction time, so a thunk resolves lazily at each
  `write!`/`writer-ref`. `:decoration` is a map of string attr-key → string
  value defaulted onto every write; `:by` is the default author. Validates these
  shapes and fails loudly naming the offending field. `note!`/`notes` are
  untouched; the writer wraps the low-level primitive."
  [runtime target-or-thunk {:keys [decoration by]}]
  (check-shape! ::writer "writer"
                (cond-> {:target target-or-thunk}
                  (some? decoration) (assoc :decoration decoration)
                  (some? by) (assoc :by by)))
  {:runtime runtime
   :target target-or-thunk
   :decoration (or decoration {})
   :by by})

(defn write!
  "Append a note through `w`, returning `note!`'s `{:id :target}`.

  Per-call `:decoration` shallow-merges per key OVER the writer default; per-call
  `:by` overrides the writer default; `:round` passes through. A thunk target
  resolves at each call; a missing or deleted target fails loudly with the
  primitive's \"Note target strand not found\"."
  [w text {:keys [decoration by round]}]
  (check-shape! ::decoration "write! :decoration" decoration)
  (let [target (resolve-target (:target w))
        merged (merge (:decoration w) (or decoration {}))
        author (or by (:by w))]
    (note! (:runtime w) target text
           (merge merged
                  (cond-> {}
                    author (assoc :by author)
                    (some? round) (assoc :round round))))))

(defn writer-ref
  "Freeze `w` to the plain-data ref `{:target :decoration :by}`.

  A thunk target resolves exactly once, here, and the ref freezes that id — refs
  ship into subprocesses, so late rebinding across a process boundary is out of
  scope. The constructor reconstructs a writer from this ref, so no `ref->writer`
  sugar ships."
  [w]
  {:target (resolve-target (:target w))
   :decoration (:decoration w)
   :by (:by w)})

(defn writer-ref->prompt
  "Render `ref` as the note-writing CLI instruction fragment.

  This is the single renderer of the write fragment
  `agent note <target> \"<text>\" --by <author> --attr k=v …` — `<text>` stays a
  placeholder the agent fills in. Validates the ref shape and fails loudly naming
  the offending field; a malformed ref never renders silently. Renders only the
  write instruction — no read/`agent notes` string."
  [ref]
  (check-shape! ::writer-ref "writer-ref" ref)
  (let [{:keys [target decoration by]} ref]
    (str "agent note " target " \"<text>\""
         (when by (str " --by " by))
         ;; sort keeps the rendered flags deterministic across map orderings
         (str/join (for [[k v] (sort decoration)] (str " --attr " k "=" v))))))
