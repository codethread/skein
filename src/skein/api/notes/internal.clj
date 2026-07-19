(ns skein.api.notes.internal
  "Plumbing for `skein.api.notes.alpha`: note attribute reads and projection,
  the single-typed round contract, chronological ordering, writer-ref
  validation, and leaf string mechanics. Implementation tier per SPEC-005.C5b —
  no compatibility promise.")

;; --- note attribute mechanics ------------------------------------------------

(defn note-attr
  "Read the `note/<k>` memory attribute from a normalized note strand."
  [note k]
  (get (:attributes note) (keyword "note" k)))

(defn at-instant
  "Chronological sort key for a note: its `note/at` parsed as an Instant, else
  epoch. `Instant/toString` varies in fractional precision, so lexicographic
  comparison misorders notes; parsing restores chronological order."
  [note]
  (if-let [at (note-attr note "at")]
    (try (java.time.Instant/parse at)
         (catch Exception _ java.time.Instant/EPOCH))
    java.time.Instant/EPOCH))

(defn note-view
  "Project a normalized note strand as `{:id :note :at}` plus `:by`/`:round`
  when present."
  [note]
  (cond-> {:id (:id note)
           :note (note-attr note "text")
           :at (or (note-attr note "at") (:created_at note))}
    (note-attr note "by") (assoc :by (note-attr note "by"))
    (note-attr note "round") (assoc :round (note-attr note "round"))))

;; --- round contract ----------------------------------------------------------

(defn require-int-round
  "Return `round` when it is an integer (or nil); otherwise fail loudly.

  `note/round` is single-typed by contract: every writer stores an integer and
  the read filter compares integers, so a round written through one surface is
  always visible through another."
  [round]
  (when (and (some? round) (not (integer? round)))
    (throw (ex-info "note/round must be an integer" {:round round :type (type round)})))
  round)

;; --- writer-ref validation ----------------------------------------------------

(defn require-writer-ref
  "Return `ref` when it satisfies the writer-ref grammar; otherwise fail loudly
  naming the offending field: a map of string `:target`, optional string `:by`,
  and optional map of string-to-string `:decoration` entries."
  [ref]
  (when-not (map? ref)
    (throw (ex-info "writer-ref shape invalid" {:field :root :value ref})))
  (let [{:keys [target decoration by]} ref]
    (when-not (string? target)
      (throw (ex-info "writer-ref target must be a string" {:field :target :value target})))
    (when-not (or (nil? by) (string? by))
      (throw (ex-info "writer-ref by must be a string" {:field :by :value by})))
    (when-not (or (nil? decoration)
                  (and (map? decoration)
                       (every? (fn [[k v]] (and (string? k) (string? v))) decoration)))
      (throw (ex-info "writer-ref decoration must be a map of strings"
                      {:field :decoration :value decoration}))))
  ref)

;; --- leaf mechanics -----------------------------------------------------------

(defn truncate
  "Return `s` capped at `n` characters, ellipsizing when it overflows."
  [s n]
  (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))
