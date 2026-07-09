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
  argument, per the blessed-namespace convention."
  (:require [clojure.string :as str]
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

(defn note!
  "Append an immutable note strand to `target-id`'s memory and return its id.

  The note is born closed, carries `note/text`, a sub-second `note/at`
  timestamp, optional `note/by`/`note/round`, and any caller-supplied decorating
  attrs, and links to the target by an outgoing `notes` edge — never a
  `note/for` attribute. Fails loudly on blank text or a missing target."
  [runtime target-id text {:keys [by round] :as opts}]
  (when (str/blank? text)
    (throw (ex-info "Note text must be non-blank" {})))
  (when-not (weaver/show runtime target-id)
    (throw (ex-info "Note target strand not found" {:id target-id})))
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
  each note as `{:id :note :at}` plus `:by`/`:round` when present."
  [runtime target-id {:keys [round]}]
  (let [note-ids (mapv :from_strand_id (graph/incoming-edges runtime [target-id] "notes"))]
    (->> (graph/strands-by-ids runtime note-ids)
         (filter (fn [note] (or (nil? round) (= round (note-attr note "round")))))
         (sort-by (juxt #(note-attr % "at") :created_at :id))
         (mapv (fn [note]
                 (cond-> {:id (:id note)
                          :note (note-attr note "text")
                          :at (or (note-attr note "at") (:created_at note))}
                   (note-attr note "by") (assoc :by (note-attr note "by"))
                   (note-attr note "round") (assoc :round (note-attr note "round"))))))))
