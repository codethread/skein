
-----
# <a name="skein.api.runtime.alpha">skein.api.runtime.alpha</a>


Explicit-runtime API for trusted weaver runtime loader/config workflows.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. Use `skein.api.current.alpha/runtime` only at trusted in-process
  entry points that need to capture the active runtime.

  The module reads as the spool lifecycle: read the approved/declared config
  (`approved`, `declared`, `release-marker`), edit the primary `spools.edn`
  (`upsert-spool-entry!`, `remove-spool-entry!`), load approved roots
  (`sync!`, `syncs`), make updated code live (`reload!`, `reload-spool!`),
  activate modules (`use!`, `uses`, `use-entry`), and serve runtime-owned
  state and time to trusted spools (`spool-state`, `now`). Component
  sub-specs live in `skein.api.runtime.internal.shapes`; every registered
  key stays alpha-qualified.




## <a name="skein.api.runtime.alpha/approved">`approved`</a>
``` clojure
(approved runtime)
```
Function.

Return the normalized approved spool roots for `runtime`'s config dir.

  Each root entry includes `:provenance :spools-edn|:local-overlay`; overlay
  entries also include their explicit `:claims` marker. `:families` maps family
  symbols to the declared `spools.edn` entry, effective post-overlay coordinate,
  provenance, and overlay claim or nil. The result conforms to
  `::approved-result`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L42-L52">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/declared">`declared`</a>
``` clojure
(declared runtime)
(declared runtime running-marker)
```
Function.

Return declared spool families with release-floor validation as data.

  `:families` has the same declared/effective projection as `approved`.
  `:requirements` is valid with pending validations, or invalid with findings
  and bump suggestions. Stage-1 structural errors still throw. The explicit
  `running-marker` arity accepts nil to leave Skein floor checks pending. The
  result conforms to `::declared-result`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L58-L70">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/now">`now`</a>
``` clojure
(now runtime)
```
Function.

Return the current java.time.Instant from `runtime`'s clock seam.

  Defaults to the real wall clock; deterministic tests inject an advanceable
  clock through `skein.test.alpha/set-clock!`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L356-L362">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/release-marker">`release-marker`</a>
``` clojure
(release-marker runtime)
```
Function.

Return the running Skein release marker and its provenance.

  The result has marker `vN` and provenance `:claimed` for an explicit startup
  claim, marker `vN` and provenance `:tag` for an annotated tag on the source
  checkout's HEAD, or `{:marker nil :provenance :none}` when the checkout
  resource is absent or non-filesystem, or successful inspection finds no
  matching annotated tag. Git startup, checkout-root resolution, and nonzero
  Git command failures throw. Consumers that require marker arithmetic must
  reject `:none` explicitly. The result conforms to
  `:skein.core.specs/release-marker-result`; marker claims conform to
  `:skein.core.specs/release-marker-claim`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L78-L94">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/reload!">`reload!`</a>
``` clojure
(reload! runtime)
```
Function.

Reload startup files from `runtime`'s config dir after clearing registries.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L208-L211">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/reload-spool!">`reload-spool!`</a>
``` clojure
(reload-spool! runtime root-lib)
```
Function.

Make `root-lib`'s latest synced source live in `runtime`.

  `root-lib` is a root-lib symbol from a family's effective `:roots` map (e.g.
  `skein.spools/kanban`). Sync state is keyed by root lib, not family or
  namespace. Returns a data-first map naming the root lib, its resolved
  canonical root, and the namespaces reloaded in reload order with their
  source files.

  Fills the gap neither existing reload path covers: `reload!` re-runs startup
  files but does not unload already-loaded namespaces or vars, and a bare
  `(require ns :reload)` is classloader-blind to per-spool synced roots — so
  neither picks up updated synced spool code. `reload-spool!` does. It reloads
  code only and leaves re-registration to the caller (a targeted re-`use!` of
  the spool's activation, or a full `reload!` when the bump changes
  registrations across the config).

  Redefinition semantics — this re-`load-file`s sources, rebinding vars in
  place; it unloads nothing, so definitions minted before the reload are not
  migrated. Concretely: a `defmulti` dispatch table survives (re-evaluating
  `defmulti` is a no-op, so methods registered against the prior table stay
  and a changed dispatch signature is not picked up), a re-evaluated
  `defprotocol` mints a fresh interface so instances built before the reload
  no longer satisfy the new protocol (`satisfies?`/`instance?` go false), and
  any instance or captured var from before the reload keeps its old
  definition. A revision that deletes or renames a namespace also leaves the
  old one loaded until restart.

  Fails loudly on an unresolvable `root-lib`, carrying a `:reason` keyword in
  ex-data. Successful results conform to
  `:skein.api.runtime.alpha/reload-spool-result`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L220-L256">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/remove-spool-entry!">`remove-spool-entry!`</a>
``` clojure
(remove-spool-entry! runtime lib)
```
Function.

Remove `lib` from `runtime`'s primary `spools.edn`.

  Refuses a missing family or a family whose root libs appear in another
  family's `:requires`, naming all requirers. Inputs and result conform to
  `::spool-family` and `::spool-write-result`. Only the primary file is changed.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L132-L159">Source</a></sub></p>

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
  once a version is declared. Opts conform to
  `:skein.api.runtime.alpha/spool-state-opts`; a malformed map fails loudly at
  the call site rather than degrading to the unversioned path.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L377-L431">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/sync!">`sync!`</a>
``` clojure
(sync! runtime)
```
Function.

Load approved spool roots and Maven jars into `runtime`.

  Returns `{:spools ...}` plus `:retained-spool-state` when preserved spool-state
  entries are from an older or unknown generation. Refuses non-additive diffs,
  including Maven version changes for already-loaded coordinates, by throwing
  ExceptionInfo with `:reason :non-additive-sync-diff`, `:diff`,
  `:pending-generation`, and `:remedy`; later successful calls include the
  pending generation until the weaver process is replaced.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L173-L188">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/syncs">`syncs`</a>
``` clojure
(syncs runtime)
```
Function.

Return `runtime`'s most recent approved-root sync state.

  The result is `{:spools ...}` and may include the latest recorded
  `:pending-generation` from a refused non-additive sync diff.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L194-L200">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/upsert-spool-entry!">`upsert-spool-entry!`</a>
``` clojure
(upsert-spool-entry! runtime lib entry)
```
Function.

Insert or replace `lib` in `runtime`'s primary `spools.edn`.

  `lib` and `entry` conform to `::spool-family` and `::spool-entry`. The full
  post-edit config is validated through sync's stage-1 contract before an atomic
  write. Only the `:spools` map is rewritten, so comments outside it are kept.
  The result conforms to `::spool-write-result`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L107-L126">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/use!">`use!`</a>
``` clojure
(use! runtime key opts)
```
Function.

Load a runtime module and record its module-use state under keyword key.

  Opts load either a synced namespace via `:ns` or a file via `:file`, and may
  include `:call` to invoke a no-arg function after load. Returns a registry
  entry with status `:loaded`, `:skipped`, or `:failed`; failed required uses
  rethrow after recording failure metadata. The key/options pair conforms to
  `:skein.api.runtime.alpha/use-registration`; the returned and recorded entry
  conforms to `:skein.api.runtime.alpha/use-entry`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L279-L322">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/use-entry">`use-entry`</a>
``` clojure
(use-entry runtime key)
```
Function.

Return one module-use registry entry from `runtime` by key.

  The nilable result conforms to `:skein.api.runtime.alpha/use-result`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L341-L348">Source</a></sub></p>

## <a name="skein.api.runtime.alpha/uses">`uses`</a>
``` clojure
(uses runtime)
```
Function.

Return `runtime`'s module-use registry as data-first maps.

  The result conforms to `:skein.api.runtime.alpha/uses-result`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/alpha.clj#L328-L335">Source</a></sub></p>
