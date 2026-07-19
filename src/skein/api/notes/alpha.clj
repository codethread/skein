(ns skein.api.notes.alpha
  "Explicit-runtime cross-spool note primitive for strand memory.

  A note is an immutable, born-closed strand (memory, not work) linked to its
  target by an outgoing `notes` annotation edge. `note!` writes that strand and
  edge; `notes` walks the incoming `notes` edges to a target and projects each
  note in `note/at` order. The link is the edge alone — no `note/for` attribute
  — so a target's deletion cascades the edge and the note becomes unreachable
  through the read with no dangling pointer, regardless of the decorating
  attributes a caller layers on its notes.

  Note content is immutable by storage enforcement, not convention: `note/text`
  and `note/at` are declared write-once keys (SPEC-001.P4), so once a note is
  written its content and timestamp cannot be rewritten, deleted, or archived on
  any mutation path. Only the caller's decorating attributes stay mutable.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument, per the blessed-namespace convention. `writer-ref->prompt` renders
  a plain-data `{:target :by :decoration}` ref as a note-writing CLI fragment."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.graph.alpha :as graph]
            [skein.api.notes.internal :as internal]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.specs :as specs]))

(defn note!
  "Append an immutable note strand to `target-id`'s memory and return its id.

  The note is born closed, carries `note/text`, a sub-second `note/at`
  timestamp, optional `note/by`/`note/round`, and any caller-supplied decorating
  attrs, and links to the target by an outgoing `notes` edge — never a
  `note/for` attribute. `note/text` and `note/at` are storage-enforced
  write-once (SPEC-001.P4): the birth write here is legal, but no later mutation
  path can rewrite, delete, or archive them. Fails loudly on blank text, a
  missing target, or a non-integer `:round` (the `note/round` contract is
  single-typed)."
  [runtime target-id text {:keys [by round] :as opts}]
  (when (str/blank? text)
    (throw (ex-info "Note text must be non-blank" {})))
  (when-not (weaver/show runtime target-id)
    (throw (ex-info "Note target strand not found" {:id target-id})))
  (internal/require-int-round round)
  (let [decorating (dissoc opts :by :round)
        note (weaver/add! runtime
                          {:title (internal/truncate text 72)
                           :state "closed"
                           ;; note/at carries sub-second precision the
                           ;; seconds-only created_at column cannot, so it
                           ;; orders a note burst.
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
  (internal/require-int-round round)
  (let [note-ids (mapv :from_strand_id (graph/incoming-edges runtime [target-id] "notes"))]
    (->> (graph/strands-by-ids runtime note-ids)
         (filter (fn [note] (or (nil? round) (= round (internal/note-attr note "round")))))
         (sort-by (juxt internal/at-instant :created_at :id))
         (mapv internal/note-view))))

(defn writer-ref->prompt
  "Render `ref` as the note-writing CLI instruction fragment.

  This is the single renderer of the write fragment
  `agent note <target> \"<text>\" --by <author> --attr k=v …` — `<text>` stays a
  placeholder the agent fills in. `ref` must contain a string `:target`, an
  optional string `:by`, and an optional map of string `:decoration` entries;
  malformed refs fail loudly naming the offending field. Renders only the write
  instruction — no read/`agent notes` string."
  [ref]
  (let [{:keys [target decoration by]} (internal/require-writer-ref ref)]
    (str "agent note " target " \"<text>\""
         (when by (str " --by " by))
         ;; sort keeps the rendered flags deterministic across map orderings
         (str/join (for [[k v] (sort decoration)] (str " --attr " k "=" v))))))

;; --- seam specs ---------------------------------------------------------------

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

(s/def ::id ::specs/id)
(s/def ::target ::specs/id)
(s/def ::note string?)
(s/def ::at string?)
(s/def ::by string?)
(s/def ::round integer?)

;; The read projection of one note strand; `:by`/`:round` appear only when the
;; note carries them.
(s/def ::note-view (s/keys :req-un [::id ::note ::at] :opt-un [::by ::round]))

;; Write opts stay an open map: `:by`/`:round` ride beside caller-owned
;; decorating attributes, and the single-typed round contract is enforced
;; fail-loud in the body, which is its grammar authority.
(s/fdef note!
  :args (s/cat :runtime ::runtime :target-id ::specs/id :text string? :opts (s/nilable map?))
  :ret (s/keys :req-un [::id ::target]))

(s/fdef notes
  :args (s/cat :runtime ::runtime :target-id ::specs/id :opts (s/nilable map?))
  :ret (s/coll-of ::note-view :kind vector?))

;; `writer-ref->prompt` is itself the authority for the writer-ref grammar: its
;; docstring documents the shape and its validation defines it (SPEC-003.C19a),
;; so no spec mirrors it here.
