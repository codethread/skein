
-----
# <a name="skein.api.runtime.alpha">skein.api.runtime.alpha</a>


Explicit-runtime API for trusted weaver runtime loader/config workflows.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. Use `skein.api.current.alpha/runtime` only at trusted in-process entry
  points that need to capture the active runtime.




## <a name="skein.api.runtime.alpha/approved">`approved`</a>
``` clojure
(approved runtime)
```
Function.

Return the normalized approved spool roots for `runtime`'s config dir.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L19-L22">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/now">`now`</a>
``` clojure
(now runtime)
```
Function.

Return the current java.time.Instant from `runtime`'s clock seam.

  Defaults to the real wall clock; deterministic tests inject an advanceable
  clock through `skein.test.alpha/set-clock!`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L69-L75">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/reload!">`reload!`</a>
``` clojure
(reload! runtime)
```
Function.

Reload startup files from `runtime`'s config dir after clearing registries.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L34-L37">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/reload-spool!">`reload-spool!`</a>
``` clojure
(reload-spool! runtime coord)
```
Function.

Make `coord`'s latest synced source live in `runtime`.

  `coord` is a `spools.edn` coordinate symbol (e.g. `skein.spools/kanban`) — a
  spool is many namespaces and sync state is keyed by coordinate, not namespace.
  Returns a data-first map naming the coordinate, its resolved canonical root, and
  the namespaces reloaded in reload order with their source files.

  Fills the gap neither existing reload path covers: `reload!` re-runs startup
  files but does not unload already-loaded namespaces or vars, and a bare `(require
  ns :reload)` is classloader-blind to per-spool synced roots — so neither picks up
  updated synced spool code. `reload-spool!` does. It reloads code only and leaves
  re-registration to the caller (a targeted re-`use!` of the spool's activation, or
  a full `reload!` when the bump changes registrations across the config).

  Redefinition semantics — this re-`load-file`s sources, rebinding vars in place;
  it unloads nothing, so definitions minted before the reload are not migrated.
  Concretely: a `defmulti` dispatch table survives (re-evaluating `defmulti` is a
  no-op, so methods registered against the prior table stay and a changed dispatch
  signature is not picked up), a re-evaluated `defprotocol` mints a fresh interface
  so instances built before the reload no longer satisfy the new protocol
  (`satisfies?`/`instance?` go false), and any instance or captured var from before
  the reload keeps its old definition. A revision that deletes or renames a
  namespace also leaves the old one loaded until restart.

  Fails loudly on an unresolvable `coord`, carrying a `:reason` keyword in ex-data.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L39-L67">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/spool-state">`spool-state`</a>
``` clojure
(spool-state runtime key init-fn)
(spool-state runtime key opts init-fn)
```
Function.

Return runtime-owned state for a spool key, creating it with `init-fn` once.

  The runtime stores spool state under arbitrary keys in its `:spool-state`
  atom. `init-fn` is called only when `key` has not been installed for this
  runtime; the returned value is then reused for the rest of the runtime
  lifetime. Spools should use this accessor instead of reaching into runtime
  internals.

  Spool state survives `reload!` by design, so a spool whose state shape changed
  between deploys would otherwise silently reuse a preserved value that is
  missing the new keys. The four-arg arity guards against that: pass opts
  `{:version v :migrate-fn f}` and, when a preserved value's stored version does
  not `=` `version`, the runtime deliberately reinits (or, with `:migrate-fn`,
  hands the old value to `f` to produce the new one) instead of reusing a
  shape-mismatched map. Silent reuse of shape-mismatched state is impossible
  once a version is declared. A malformed opts map fails loudly at the call site
  (see `validate-spool-state-opts!`) rather than degrading to the unversioned
  path.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L295-L348">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/sync!">`sync!`</a>
``` clojure
(sync! runtime)
```
Function.

Load approved local roots into `runtime`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L24-L27">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/syncs">`syncs`</a>
``` clojure
(syncs runtime)
```
Function.

Return `runtime`'s most recent approved-root sync state.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L29-L32">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/use">`use`</a>
``` clojure
(use runtime key)
```
Function.

Return one module-use registry entry from `runtime` by key.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L200-L203">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/use!">`use!`</a>
``` clojure
(use! runtime key opts)
```
Function.

Load a runtime module and record its module-use state under keyword key.

  Opts load either a synced namespace via `:ns` or a file via `:file`, and may
  include `:call` to invoke a no-arg function after load. Returns a registry
  entry with status `:loaded`, `:skipped`, or `:failed`; failed required uses
  rethrow after recording failure metadata.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L153-L193">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/uses">`uses`</a>
``` clojure
(uses runtime)
```
Function.

Return `runtime`'s module-use registry as data-first maps.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L195-L198">Source</a></sub></p>
