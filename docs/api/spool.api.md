
-----
# <a name="skein.api.spool.alpha">skein.api.spool.alpha</a>


Blessed spool-authoring helpers: the accretion-compatible home for the shared
  fail-loud and validation seams every reference spool leans on.

  Living in the `skein.api.*.alpha` tier freezes this helper set
  (`entity-projection`, `fail!`, `reject-unknown-keys!`, `require-valid!`,
  `attr-key->str`, `attr-get`, `poll-until-deadline!`) as a compat commitment,
  so no blessed namespace has to reach down into a
  `skein.spools.*` peer to reuse them.

  Reference spools all need the same tiny fail-loud and validation seams: throw
  an `ex-info` with a contextual data map (TEN-003), reject unknown option keys,
  validate a boundary shape against a `clojure.spec` and attach its explain data,
  coerce an attribute key to its string wire form, tolerantly read an
  attribute back regardless of whether its key arrived keyword- or
  string-keyed, and poll a check fn until a deadline. Those were copy-pasted -
  and had begun to drift - across most shipped spools, and now share this one
  source instead of re-deriving them per file. `skein.spools.workflow` is a
  deliberate exception: it keeps its own branded `reject-unknown-keys!` rather
  than adopting this one.




## <a name="skein.api.spool.alpha/attr-get">`attr-get`</a>
``` clojure
(attr-get strand k)
```
Function.

Read attribute `k` from a normalized strand, tolerating keyword- or
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
  trusted spool readers require a raw full-fidelity attribute value.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/spool/alpha.clj#L80-L105">Source</a></sub></p>

## <a name="skein.api.spool.alpha/attr-key->str">`attr-key->str`</a>
``` clojure
(attr-key->str k)
```
Function.

Coerce an attribute key to its string wire form.

  Keyword keys render as their bare name (dropping the leading colon), preserving
  any namespace; string keys pass through. This is the write-side key coercion,
  not a tolerant reader.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/spool/alpha.clj#L71-L78">Source</a></sub></p>

## <a name="skein.api.spool.alpha/entity-projection">`entity-projection`</a>
``` clojure
(entity-projection strand)
```
Function.

Return the canonical exact strand entity projection.

  Fails loudly when any of `:id`, `:title`, `:state`, or `:attributes` is
  absent. Other fields are discarded.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/spool/alpha.clj#L36-L46">Source</a></sub></p>

## <a name="skein.api.spool.alpha/fail!">`fail!`</a>
``` clojure
(fail! message data)
(fail! message data cause)
```
Function.

Throw an `ex-info` carrying `message` and a contextual `data` map (TEN-003).

  The optional `cause` arity threads an underlying throwable so a spool can fail
  loudly without discarding the original exception.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/spool/alpha.clj#L26-L34">Source</a></sub></p>

## <a name="skein.api.spool.alpha/poll-until-deadline!">`poll-until-deadline!`</a>
``` clojure
(poll-until-deadline! {:keys [deadline poll-ms check pred->result on-timeout], :as opts})
```
Function.

The shared spool-tier long-poll skeleton behind `skein.spools.workflow/await!`
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
  (TEN-003) on option keys outside the five named here, when `deadline` is
  not a long, when `poll-ms` is not a non-negative integer, or when
  `check`/`pred->result`/`on-timeout` is not a function, rather than
  surfacing a bare NPE/`IllegalArgumentException` once the loop actually
  runs.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/spool/alpha.clj#L107-L142">Source</a></sub></p>

## <a name="skein.api.spool.alpha/reject-unknown-keys!">`reject-unknown-keys!`</a>
``` clojure
(reject-unknown-keys! context allowed m)
```
Function.

Return `m`, failing loudly when it carries keys outside `allowed`.

  `context` is a label (typically the builder/op name) that names the offending
  surface in the message, so a spool never silently ignores a mistyped option
  key. `allowed` is a set of permitted keys.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/spool/alpha.clj#L48-L58">Source</a></sub></p>

## <a name="skein.api.spool.alpha/require-valid!">`require-valid!`</a>
``` clojure
(require-valid! spec value message)
```
Function.

Return `value`, failing loudly with spec explain data when it is invalid.

  The canonical spool boundary-shape seam: pairs a `clojure.spec` check with an
  `:explain` payload (`s/explain-data`) so a rejected shape carries actionable
  context, not just the raw value.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/spool/alpha.clj#L60-L69">Source</a></sub></p>
