
-----
# <a name="skein.spools.guild">skein.spools.guild</a>


Reference spool for declaring a versioned public weaver operation API.

  Guild ops are ordinary CLI operations registered in the weaver op registry.
  Names are documented as dotted, version-suffixed handles such as
  `gate.close.v1`; the underlying registry requires simple unqualified handles
  and therefore rejects namespaced keyword or symbol names. Optional input specs
  validate the parsed op input before the declared handler runs. Deprecation
  replaces an op with a stub that always fails loudly with structured data.




## <a name="skein.spools.guild/defop!">`defop!`</a>
``` clojure
(defop! name opts handler-fn-sym)
```
Function.

Register a guild operation in the CLI operation registry.

  `name` is a simple unqualified registry handle, conventionally dotted and
  version-suffixed such as `gate.close.v1`. `opts` supports `:doc` and optional
  `:spec`; unknown options fail loudly. `handler-fn-sym` must be a fully
  qualified symbol resolving in the weaver JVM. The handler receives the usual
  op context plus parsed JSON input at `:guild/input`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L116-L140">Source</a></sub></p>

## <a name="skein.spools.guild/deprecate!">`deprecate!`</a>
``` clojure
(deprecate! name opts)
```
Function.

Replace a registered guild operation with a loud deprecation stub.

  `opts` requires `:replacement` and may include `:since`. Deprecated ops never
  return success; invocation throws ex-info with `:code :op/deprecated`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L148-L166">Source</a></sub></p>

## <a name="skein.spools.guild/deprecated-op">`deprecated-op`</a>
``` clojure
(deprecated-op {:op/keys [name], :as ctx})
```
Function.

Fail loudly for a deprecated guild operation.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L105-L114">Source</a></sub></p>

## <a name="skein.spools.guild/describe-op">`describe-op`</a>
``` clojure
(describe-op {:op/keys [runtime-metadata], :as ctx})
```
Function.

Return JSON-safe metadata describing the installed guild API.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L168-L183">Source</a></sub></p>

## <a name="skein.spools.guild/dispatch-op">`dispatch-op`</a>
``` clojure
(dispatch-op {:op/keys [name args], :as ctx})
```
Function.

Dispatch a guild-declared operation after parsing and validating input.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L97-L103">Source</a></sub></p>

## <a name="skein.spools.guild/install!">`install!`</a>
``` clojure
(install!)
(install! guild-name)
```
Function.

Install the built-in `guild.describe` operation.

  The guild name is read from runtime metadata when available. Passing
  `guild-name` records a fallback value for contexts without runtime metadata.
  Re-running is reload-safe and clears prior guild declarations in this weaver JVM.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L185-L205">Source</a></sub></p>
