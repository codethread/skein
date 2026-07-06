# Table of contents
-  [`skein.spools.selvage`](#skein.spools.selvage)  - Attribute vocabulary linting for userland strand conventions.
    -  [`check`](#skein.spools.selvage/check) - Return vocabulary violations for one strand map or strand id.
    -  [`check-all`](#skein.spools.selvage/check-all) - Return vocabulary violations across active strands.
    -  [`clear-violations!`](#skein.spools.selvage/clear-violations!) - Clear recorded watch-mode violations.
    -  [`defvocab!`](#skein.spools.selvage/defvocab!) - Register or replace an attribute vocabulary for this weaver lifetime.
    -  [`install!`](#skein.spools.selvage/install!) - Install Selvage watch support into the active weaver and return metadata.
    -  [`record-event!`](#skein.spools.selvage/record-event!) - Event handler that records violations for strand added/updated events.
    -  [`remove-vocab!`](#skein.spools.selvage/remove-vocab!) - Remove a registered vocabulary by name.
    -  [`violations`](#skein.spools.selvage/violations) - Return recorded watch-mode violations in delivery order.
    -  [`vocabs`](#skein.spools.selvage/vocabs) - Return registered vocabulary metadata in deterministic order.
    -  [`watch!`](#skein.spools.selvage/watch!) - Register the asynchronous mutation watcher for post-hoc violation recording.

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L180-L193">Source</a></sub></p>

## <a name="skein.spools.selvage/check-all">`check-all`</a>
``` clojure
(check-all)
(check-all query-form)
```
Function.

Return vocabulary violations across active strands.

  With no arguments checks all active strands. With `query-form`, checks only
  strands selected by that predicate DSL query.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L195-L205">Source</a></sub></p>

## <a name="skein.spools.selvage/clear-violations!">`clear-violations!`</a>
``` clojure
(clear-violations!)
```
Function.

Clear recorded watch-mode violations.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L234-L238">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L97-L109">Source</a></sub></p>

## <a name="skein.spools.selvage/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install Selvage watch support into the active weaver and return metadata.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L240-L248">Source</a></sub></p>

## <a name="skein.spools.selvage/record-event!">`record-event!`</a>
``` clojure
(record-event! event)
```
Function.

Event handler that records violations for strand added/updated events.

  Intended for registration by `watch!`. Handler exceptions are deliberately not
  caught here so the weaver event failure surface records them.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L207-L220">Source</a></sub></p>

## <a name="skein.spools.selvage/remove-vocab!">`remove-vocab!`</a>
``` clojure
(remove-vocab! name)
```
Function.

Remove a registered vocabulary by name.

  Missing vocabularies fail loudly. Returns `{:removed name}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L116-L125">Source</a></sub></p>

## <a name="skein.spools.selvage/violations">`violations`</a>
``` clojure
(violations)
```
Function.

Return recorded watch-mode violations in delivery order.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L229-L232">Source</a></sub></p>

## <a name="skein.spools.selvage/vocabs">`vocabs`</a>
``` clojure
(vocabs)
```
Function.

Return registered vocabulary metadata in deterministic order.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L111-L114">Source</a></sub></p>

## <a name="skein.spools.selvage/watch!">`watch!`</a>
``` clojure
(watch!)
```
Function.

Register the asynchronous mutation watcher for post-hoc violation recording.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/selvage.clj#L222-L227">Source</a></sub></p>
