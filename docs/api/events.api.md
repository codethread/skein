
-----
# <a name="skein.api.events.alpha">skein.api.events.alpha</a>


Explicit-runtime API for registering and inspecting weaver event handlers.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns event handler validation, function resolution,
  registry state, and asynchronous failure capture. Internal mutation APIs
  submit events through `skein.core.weaver.dispatch`; this public namespace is
  observe-only.




## <a name="skein.api.events.alpha/await-quiescent!">`await-quiescent!`</a>
``` clojure
(await-quiescent! runtime)
(await-quiescent! runtime {:keys [timeout-ms]})
```
Function.

Delegate to `skein.test.alpha/await-quiescent!`.

  This compatibility alias moves to the author-side test API and will be
  removed before the v1 stamp, after agent-harness.spool v3 migrates.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L92-L99">Source</a></sub></p>

## <a name="skein.api.events.alpha/handlers">`handlers`</a>
``` clojure
(handlers runtime)
```
Function.

Return data-first event handler registry entries from `runtime`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L81-L85">Source</a></sub></p>

## <a name="skein.api.events.alpha/recent-failures">`recent-failures`</a>
``` clojure
(recent-failures runtime)
```
Function.

Return recent asynchronous event handler failures from `runtime`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L87-L90">Source</a></sub></p>

## <a name="skein.api.events.alpha/register!">`register!`</a>
``` clojure
(register! & args)
```
Function.

Renamed to register-handler! (card d6xgt); this alias is removed before the v1 stamp.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L71-L74">Source</a></sub></p>

## <a name="skein.api.events.alpha/register-handler!">`register-handler!`</a>
``` clojure
(register-handler! runtime key types fn-sym)
(register-handler! runtime key types fn-sym metadata)
```
Function.

Register or replace an event handler in `runtime` for selected event types.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L51-L62">Source</a></sub></p>

## <a name="skein.api.events.alpha/unregister!">`unregister!`</a>
``` clojure
(unregister! & args)
```
Function.

Renamed to unregister-handler! (card d6xgt); this alias is removed before the v1 stamp.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L76-L79">Source</a></sub></p>

## <a name="skein.api.events.alpha/unregister-handler!">`unregister-handler!`</a>
``` clojure
(unregister-handler! runtime key)
```
Function.

Unregister an event handler by stable key from `runtime`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/events/alpha.clj#L64-L69">Source</a></sub></p>
