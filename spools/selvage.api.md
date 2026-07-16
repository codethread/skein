
-----
# <a name="skein.spools.selvage">skein.spools.selvage</a>


Attribute vocabulary linting for userland strand conventions.

  Selvage keeps opt-in attribute invariants in trusted spool state. It never
  changes the core open-attribute contract: callers register data-first
  checksets, run checks on demand, or watch asynchronous mutation events for
  post-hoc detection.




## <a name="skein.spools.selvage/check">`check`</a>
``` clojure
(check strand-or-id)
```
Function.

Return checkset violations for one strand map or strand id.

  Missing strand ids fail loudly through the public graph surfaces. A clean
  strand returns an empty vector.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L184-L197">Source</a></sub></p>

## <a name="skein.spools.selvage/check-all">`check-all`</a>
``` clojure
(check-all)
(check-all query-form)
```
Function.

Return checkset violations across active strands.

  With no arguments checks all active strands. With `query-form`, checks only
  strands selected by that predicate DSL query.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L199-L209">Source</a></sub></p>

## <a name="skein.spools.selvage/checksets">`checksets`</a>
``` clojure
(checksets)
```
Function.

Return registered checkset metadata in deterministic order.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L114-L117">Source</a></sub></p>

## <a name="skein.spools.selvage/clear-violations!">`clear-violations!`</a>
``` clojure
(clear-violations!)
```
Function.

Clear recorded watch-mode violations.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L273-L277">Source</a></sub></p>

## <a name="skein.spools.selvage/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install Selvage watch support into the active weaver and return metadata.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L279-L287">Source</a></sub></p>

## <a name="skein.spools.selvage/record-event!">`record-event!`</a>
``` clojure
(record-event! event)
```
Function.

Event handler that records violations for strand added/updated events.

  Intended for registration by `watch!`. Handler exceptions are deliberately not
  caught here so the weaver event failure surface records them.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L246-L259">Source</a></sub></p>

## <a name="skein.spools.selvage/register-checkset!">`register-checkset!`</a>
``` clojure
(register-checkset! name spec)
```
Function.

Register or replace a named checkset for this weaver lifetime.

  `spec` is data with `:checks`, a vector of maps. Supported checks are
  `{:attr s :enum [...]}`, `{:attr s :type t}`, and
  `{:attr s :required-with other-attr}`. Unknown keys and unknown types throw
  `ex-info` with allowed values. Returns the registered metadata.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L100-L112">Source</a></sub></p>

## <a name="skein.spools.selvage/undeclared-checks">`undeclared-checks`</a>
``` clojure
(undeclared-checks)
```
Function.

Return registered selvage checks whose `:attr` namespace no vocabulary
  declaration owns.

  Opt-in cross-check between selvage's checksets and the ownership registry
  (`skein.api.vocab.alpha`): reads the declared `:attr-namespace` names for the
  active runtime and returns one entry per registered check whose attribute
  namespace segment is undeclared, as
  `{:checkset name :attr s :namespace s :check check}` sorted like `checksets`.

  Read-only composition sugar over `checksets` — it references the registry,
  never enforces it. Registered nowhere by default: no watch, no new enforcement
  path, and no change to selvage's `:enum`/`:type`/`:required-with` linting
  model.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L220-L244">Source</a></sub></p>

## <a name="skein.spools.selvage/unregister-checkset!">`unregister-checkset!`</a>
``` clojure
(unregister-checkset! name)
```
Function.

Unregister a checkset by name.

  Missing checksets fail loudly. Returns `{:unregistered name}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L119-L129">Source</a></sub></p>

## <a name="skein.spools.selvage/violations">`violations`</a>
``` clojure
(violations)
```
Function.

Return recorded watch-mode violations in delivery order.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L268-L271">Source</a></sub></p>

## <a name="skein.spools.selvage/watch!">`watch!`</a>
``` clojure
(watch!)
```
Function.

Register the asynchronous mutation watcher for post-hoc violation recording.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/selvage/src/skein/spools/selvage.clj#L261-L266">Source</a></sub></p>
