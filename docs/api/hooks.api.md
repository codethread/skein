
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

  Entries sort by `:order`, then printed key for a deterministic tie-break, and
  never carry the resolved `:fn-value`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L45-L52">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/register-hook!">`register-hook!`</a>
``` clojure
(register-hook! runtime key types fn-sym)
(register-hook! runtime key types fn-sym opts)
```
Function.

Register or replace a lifecycle hook in `runtime` for selected hook types.

  `key` is the stable registry identity (keyword, symbol, or non-blank string):
  registering an existing key replaces that entry. `types` is a non-empty set of
  hook type keywords, and `fn-sym` a fully qualified symbol resolved under the
  runtime's spool classloader. `opts` may carry an integer `:order` (default 0)
  plus data-first metadata. Returns the registered entry without its resolved
  function value.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L14-L34">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/unregister-hook!">`unregister-hook!`</a>
``` clojure
(unregister-hook! runtime key)
```
Function.

Unregister a lifecycle hook by stable key from `runtime` and return that key.

  Unregistering an absent key is a no-op returning the validated key.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L36-L43">Source</a></sub></p>
