
-----
# <a name="skein.api.hooks.alpha">skein.api.hooks.alpha</a>


Explicit-runtime API for registering and inspecting weaver lifecycle hooks.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns hook validation, function resolution, and
  registry state; synchronous invocation by later lifecycle gates lives in
  `skein.core.weaver.lifecycle`.




## <a name="skein.api.hooks.alpha/hooks">`hooks`</a>
``` clojure
(hooks runtime)
```
Function.

Return data-first lifecycle hook registry entries in execution order.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L80-L84">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/register!">`register!`</a>
``` clojure
(register! & args)
```
Function.

Renamed to register-hook! (card d6xgt); this alias is removed before the v1 stamp.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L70-L73">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/register-hook!">`register-hook!`</a>
``` clojure
(register-hook! runtime key types fn-sym)
(register-hook! runtime key types fn-sym opts)
```
Function.

Register or replace a lifecycle hook in `runtime` for selected hook types.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L48-L61">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/unregister!">`unregister!`</a>
``` clojure
(unregister! & args)
```
Function.

Renamed to unregister-hook! (card d6xgt); this alias is removed before the v1 stamp.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L75-L78">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/unregister-hook!">`unregister-hook!`</a>
``` clojure
(unregister-hook! runtime key)
```
Function.

Unregister a lifecycle hook by stable key from `runtime` and return that key.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L63-L68">Source</a></sub></p>
