(ns skein.spools.util
  "Shared spool-authoring helpers, peer of `skein.spools.format`.

  Reference spools all need the same tiny fail-loud and validation seams: throw
  an `ex-info` with a contextual data map (TEN-003), reject unknown option keys,
  validate a boundary shape against a `clojure.spec` and attach its explain data,
  and coerce an attribute key to its string wire form. Those were copy-pasted -
  and had begun to drift - across the shipped spools. They live here so spools
  share one source instead of re-deriving them per file."
  (:require [clojure.spec.alpha :as s]))

(defn fail!
  "Throw an `ex-info` carrying `message` and a contextual `data` map (TEN-003).

  The optional `cause` arity threads an underlying throwable so a spool can fail
  loudly without discarding the original exception."
  ([message data]
   (throw (ex-info message data)))
  ([message data cause]
   (throw (ex-info message data cause))))

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
    (fail! message {:explain (s/explain-data spec value)}))
  value)

(defn attr-key->str
  "Coerce an attribute key to its string wire form.

  Keyword keys render as their bare name (dropping the leading colon), preserving
  any namespace; string keys pass through. This is the write-side key coercion,
  not a tolerant reader."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))
