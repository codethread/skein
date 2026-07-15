(ns skein.api.spool.alpha
  "Blessed spool-authoring helpers: the accretion-compatible home for the shared
  fail-loud and validation seams every reference spool leans on.

  Living in the `skein.api.*.alpha` tier freezes this helper set (`fail!`,
  `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`,
  `poll-until-deadline!`) as a compat commitment, so no blessed namespace has to
  reach down into a `skein.spools.*` peer to reuse them.

  Reference spools all need the same tiny fail-loud and validation seams: throw
  an `ex-info` with a contextual data map (TEN-003), reject unknown option keys,
  validate a boundary shape against a `clojure.spec` and attach its explain data,
  coerce an attribute key to its string wire form, tolerantly read an
  attribute back regardless of whether its key arrived keyword- or
  string-keyed, and poll a check fn until a deadline. Those were copy-pasted -
  and had begun to drift - across most shipped spools, and now share this one
  source instead of re-deriving them per file. `skein.spools.workflow` is a
  deliberate exception: it keeps its own branded `reject-unknown-keys!` rather
  than adopting this one."
  (:require [clojure.spec.alpha :as s]
            [skein.core.specs :as specs]))

(defn fail!
  "Throw an `ex-info` carrying `message` and a contextual `data` map (TEN-003).

  The optional `cause` arity threads an underlying throwable so a spool can fail
  loudly without discarding the original exception."
  ([message data]
   (throw (ex-info message data)))
  ([message data cause]
   (throw (ex-info message data cause))))

(def ^:private entity-projection-keys [:id :title :state :attributes])

(defn entity-projection
  "Return the canonical exact strand entity projection.

  Fails loudly when any of `:id`, `:title`, `:state`, or `:attributes` is
  absent. Other fields are discarded."
  [strand]
  (let [missing (filterv #(not (contains? strand %)) entity-projection-keys)]
    (when (seq missing)
      (fail! "Strand is missing canonical entity fields"
             {:missing missing :required entity-projection-keys :strand strand}))
    (select-keys strand entity-projection-keys)))

(defn reject-unknown-keys!
  "Return `m`, failing loudly when it carries keys outside `allowed`.

  `context` is a label (typically the builder/op name) that names the offending
  surface in the message, so a spool never silently ignores a mistyped option
  key. `allowed` is a set of permitted keys."
  [context allowed m]
  (when-let [unknown (seq (remove allowed (keys m)))]
    (fail! (str context " received unknown keys")
           {:unknown (vec unknown) :allowed (vec (sort allowed))}))
  m)

(defn require-valid!
  "Return `value`, failing loudly with spec explain data when it is invalid.

  The canonical spool boundary-shape seam: pairs a `clojure.spec` check with an
  `:explain` payload (`s/explain-data`) so a rejected shape carries actionable
  context, not just the raw value."
  [spec value message]
  (when-not (s/valid? spec value)
    (fail! message {:value value :explain (s/explain-data spec value)}))
  value)

(defn attr-key->str
  "Coerce an attribute key to its string wire form.

  Keyword keys render as their bare name (dropping the leading colon), preserving
  any namespace; string keys pass through. This is the write-side key coercion,
  not a tolerant reader."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(s/def ::deadline int?)
(s/def ::poll-ms (s/and integer? (complement neg?)))
(s/def ::poll-fn ifn?)

(defn poll-until-deadline!
  "The shared spool-tier long-poll skeleton behind `skein.spools.workflow/await!`
  and `skein.spools.roster/await-quiet!`: call `check` (a zero-arg fn) once,
  test its value with `pred->result`, and repeat every `poll-ms` until either
  `pred->result` returns a non-nil result or `deadline` (a `System/currentTimeMillis`
  epoch millis value, as already computed by each caller from its own
  `:timeout-secs`/`:timeout-ms` option) has passed.

  `pred->result` receives each `check` value and returns a non-nil result to
  stop and return it, or nil to keep polling. Once `deadline` passes with
  `pred->result` still nil, `on-timeout` receives the last `check` value and
  its return value becomes the result. `deadline` and `poll-ms` are both
  required — this helper does not supply timeout/cadence defaults; those stay
  owned by each caller so existing behavior is unchanged. Fails loudly
  (TEN-003) when `deadline` is not a long, `poll-ms` is not a non-negative
  integer, or `check`/`pred->result`/`on-timeout` is not a function, rather
  than surfacing a bare NPE/`IllegalArgumentException` once the loop actually
  runs."
  [{:keys [deadline poll-ms check pred->result on-timeout]}]
  (require-valid! ::deadline deadline "poll-until-deadline! :deadline must be a long")
  (require-valid! ::poll-ms poll-ms "poll-until-deadline! :poll-ms must be a non-negative integer")
  (require-valid! ::poll-fn check "poll-until-deadline! :check must be a function")
  (require-valid! ::poll-fn pred->result "poll-until-deadline! :pred->result must be a function")
  (require-valid! ::poll-fn on-timeout "poll-until-deadline! :on-timeout must be a function")
  (loop []
    (let [value (check)]
      (or (pred->result value)
          (if (>= (System/currentTimeMillis) deadline)
            (on-timeout value)
            (do (Thread/sleep (long poll-ms)) (recur)))))))

(defn- reject-omitted-attribute! [strand k value]
  (when (specs/omitted-attribute-descriptor? value)
    (let [strand-id (:id strand)]
      (fail! "Attribute value was omitted from this lean read; fetch the full strand before reading it"
             {:key k
              :strand-id strand-id
              :recovery (str "show " strand-id)})))
  value)

(defn attr-get
  "Read attribute `k` from a normalized strand, tolerating keyword- or
  string-keyed attribute maps.

  Strand attributes arrive keyword-keyed on the native path but string-keyed
  after a JSON round-trip through the weaver, so a reader that checks only one
  keying silently returns nil for the other. `attr-get` coerces `k` to a keyword,
  prefers the keyword entry, and falls back to the bare-string wire key
  (`attr-key->str`) only when the keyword key is absent.

  The chosen tolerant semantics are deliberate: presence is tested with
  `contains?`, not truthiness, so a key stored with an explicit `false`/`nil`
  value reads back as that value instead of falling through to the string key.
  Neither the keying a strand happens to carry nor a falsey value may change what
  an attribute means. This is the canonical spool read companion to
  `attr-key->str`; spools require it rather than re-deriving a per-file reader.

  Fails loudly if the selected value is a lean-read omission descriptor, because
  trusted spool readers require a raw full-fidelity attribute value."
  [strand k]
  (let [attrs (:attributes strand)
        kw (keyword k)
        value (if (contains? attrs kw)
                (get attrs kw)
                (get attrs (attr-key->str kw)))]
    (reject-omitted-attribute! strand k value)))
