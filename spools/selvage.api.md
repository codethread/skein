
-----
# <a name="skein.spools.selvage">skein.spools.selvage</a>


Attribute vocabulary linting for userland strand conventions.

  Selvage keeps opt-in attribute invariants in trusted spool state. It never
  changes the core open-attribute contract: callers register data-first
  vocabularies, run checks on demand, or watch asynchronous mutation events for
  post-hoc detection.




## <a name="skein.spools.selvage/check">`check`</a>
``` clojure
(check strand-or-id)
```
Function.

Return vocabulary violations for one strand map or strand id.

  Missing strand ids fail loudly through the public graph surfaces. A clean
  strand returns an empty vector.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L181-L194">Source</a></sub></p>

## <a name="skein.spools.selvage/check-all">`check-all`</a>
``` clojure
(check-all)
(check-all query-form)
```
Function.

Return vocabulary violations across active strands.

  With no arguments checks all active strands. With `query-form`, checks only
  strands selected by that predicate DSL query.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L196-L206">Source</a></sub></p>

## <a name="skein.spools.selvage/clear-violations!">`clear-violations!`</a>
``` clojure
(clear-violations!)
```
Function.

Clear recorded watch-mode violations.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L269-L273">Source</a></sub></p>

## <a name="skein.spools.selvage/defvocab!">`defvocab!`</a>
``` clojure
(defvocab! name spec)
```
Function.

Register or replace an attribute vocabulary for this weaver lifetime.

  `spec` is data with `:checks`, a vector of maps. Supported checks are
  `{:attr s :enum [...]}`, `{:attr s :kind k}`, and
  `{:attr s :required-with other-attr}`. Unknown keys and unknown kinds throw
  `ex-info` with allowed values. Returns the registered metadata.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L98-L110">Source</a></sub></p>

## <a name="skein.spools.selvage/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install Selvage watch support into the active weaver and return metadata.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L275-L283">Source</a></sub></p>

## <a name="skein.spools.selvage/record-event!">`record-event!`</a>
``` clojure
(record-event! event)
```
Function.

Event handler that records violations for strand added/updated events.

  Intended for registration by `watch!`. Handler exceptions are deliberately not
  caught here so the weaver event failure surface records them.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L242-L255">Source</a></sub></p>

## <a name="skein.spools.selvage/remove-vocab!">`remove-vocab!`</a>
``` clojure
(remove-vocab! name)
```
Function.

Remove a registered vocabulary by name.

  Missing vocabularies fail loudly. Returns `{:removed name}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L117-L126">Source</a></sub></p>

## <a name="skein.spools.selvage/undeclared-checks">`undeclared-checks`</a>
``` clojure
(undeclared-checks)
```
Function.

Return registered selvage checks whose `:attr` namespace no vocabulary
  declaration owns.

  Opt-in cross-check between selvage's linting vocabularies and the ownership
  registry (`skein.api.vocab.alpha`): reads the declared `:attr-namespace` names
  for the active runtime and returns one entry per registered check whose
  attribute namespace segment is undeclared, as
  `{:vocab name :attr s :namespace s :check check}` sorted like `vocabs`.

  Read-only composition sugar over `vocabs` — it references the registry, never
  enforces it. Registered nowhere by default: no watch, no new enforcement path,
  and no change to selvage's `:enum`/`:kind`/`:required-with` linting model.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L217-L240">Source</a></sub></p>

## <a name="skein.spools.selvage/violations">`violations`</a>
``` clojure
(violations)
```
Function.

Return recorded watch-mode violations in delivery order.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L264-L267">Source</a></sub></p>

## <a name="skein.spools.selvage/vocabs">`vocabs`</a>
``` clojure
(vocabs)
```
Function.

Return registered vocabulary metadata in deterministic order.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L112-L115">Source</a></sub></p>

## <a name="skein.spools.selvage/watch!">`watch!`</a>
``` clojure
(watch!)
```
Function.

Register the asynchronous mutation watcher for post-hoc violation recording.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L257-L262">Source</a></sub></p>
