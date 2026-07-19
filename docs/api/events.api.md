
-----
# <a name="skein.api.events.alpha">skein.api.events.alpha</a>


Explicit-runtime API for managing and inspecting weaver event handlers.

  Registration and unregistration mutate the runtime's weaver-lifetime
  handler registry; `handlers` and `recent-failures` are the data-first
  reads over registry and failure state. Every registration is validated
  loudly at the seam — stable key, non-empty keyword type set, fully
  qualified function symbol resolvable under the runtime spool
  classloader, data-first metadata — and entries replace by key for
  reload workflows. Event submission is not public surface: internal
  mutation APIs submit events through `skein.core.weaver.dispatch`
  (SPEC-004.C73), and the event-lane quiescence await ships in
  `skein.test.alpha` (SPEC-004.C74b).

  Callers own runtime selection and pass the target weaver runtime as
  the first argument.




## <a name="skein.api.events.alpha/handlers">`handlers`</a>
``` clojure
(handlers runtime)
```
Function.

Return `runtime`'s event handler registry as data-first entries.

  Each entry is `{:key :types :fn :metadata}` — never the resolved function
  value (SPEC-004.C66) — sorted by printed key so ordering is deterministic
  across mixed key types.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L58-L66">Source</a></sub></p>

## <a name="skein.api.events.alpha/recent-failures">`recent-failures`</a>
``` clojure
(recent-failures runtime)
```
Function.

Return `runtime`'s recent asynchronous handler failures, oldest first.

  Failures are bounded weaver-lifetime introspection state (SPEC-004.C67):
  each record carries `:handler/key`, `:handler/fn`, `:event/id`,
  `:event/type`, `:exception/message`, and `:failed/at`. Handler exceptions
  never fail the already-committed mutation that emitted the event.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L68-L76">Source</a></sub></p>

## <a name="skein.api.events.alpha/register-handler!">`register-handler!`</a>
``` clojure
(register-handler! runtime key types fn-sym)
(register-handler! runtime key types fn-sym metadata)
```
Function.

Register or replace an event handler in `runtime` for selected event types.

  Builds the registry entry from loudly validated pieces — `key` a keyword,
  symbol, or non-blank string; `types` a non-empty set of event type
  keywords; `fn-sym` a fully qualified symbol resolving to a callable under
  the runtime spool classloader (resolution happens here, so a bad symbol
  fails registration, not dispatch); `metadata` a data-first map — swaps it
  into the registry, replacing any prior entry with the same key, and
  returns the entry as data (the resolved function value stays internal).
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L26-L45">Source</a></sub></p>

## <a name="skein.api.events.alpha/unregister-handler!">`unregister-handler!`</a>
``` clojure
(unregister-handler! runtime key)
```
Function.

Unregister the event handler stored under `key` in `runtime`.

  Validates `key` like registration, removes any entry stored under it (a
  key with no entry is a quiet no-op, so unregistration is idempotent), and
  returns `{:unregistered key}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L47-L56">Source</a></sub></p>
