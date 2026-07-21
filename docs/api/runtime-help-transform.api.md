
-----
# <a name="skein.api.runtime.help-transform.alpha">skein.api.runtime.help-transform.alpha</a>


Explicit-runtime at-most-one slot for the default help transform.

  The slot holds at most one transform: a function from the full canonical help
  response envelope (DELTA-Dtf-001.CC1) to the rendered string the CLI relays
  verbatim (JSON or text — the transform's choice). The `help` op renders through
  it when present, else emits the raw canonical envelope; `--json` always bypasses
  it, so a broken transform never bricks help (DELTA-Dtf-001.CC4). `about`/`prime`
  output is never transformed.

  The slot is runtime-owned and **reload-cleared**: it follows the op-registry
  lifecycle (SPEC-004.C46/C63a/C63c), cleared by `reload!` before config re-runs,
  not the reload-surviving `spool-state` (SPEC-004.C95). It is registered only by
  trusted `init.clj`/REPL config — no spool `install!` auto-registers it, so a
  fresh world (batteries absent, or present but not electing) keeps the raw-JSON
  floor (DELTA-Dtf-002.D1). This is the deliberate contrast with the glossary
  registry (`skein.api.runtime.glossary.alpha`), which each owning spool registers
  from its own `install!`.

  Discipline (TEN-002): the slot is at-most-one and set explicitly.
  `register-default-help-transform!` fails loudly when the slot is occupied,
  naming both registrants; `replace-default-help-transform!` is the deliberate
  override and requires a transform to already be registered.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument; nothing here reads the published ambient runtime.




## <a name="skein.api.runtime.help-transform.alpha/default-help-transform">`default-help-transform`</a>
``` clojure
(default-help-transform runtime)
```
Function.

Return `runtime`'s registered default-help-transform registration map, or nil.

  The read/introspection projection: reports whether a transform is registered
  (`some?`) and its provenance (`:owner`), reading the runtime store explicitly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/help_transform/alpha.clj#L75-L81">Source</a></sub></p>

## <a name="skein.api.runtime.help-transform.alpha/default-help-transform-registered?">`default-help-transform-registered?`</a>
``` clojure
(default-help-transform-registered? runtime)
```
Function.

True when `runtime`'s default-help-transform slot holds a transform.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/help_transform/alpha.clj#L87-L90">Source</a></sub></p>

## <a name="skein.api.runtime.help-transform.alpha/register-default-help-transform!">`register-default-help-transform!`</a>
``` clojure
(register-default-help-transform! runtime registration)
```
Function.

Register `registration` as `runtime`'s default help transform and return it.

  `registration` is a closed map `{:transform :owner}`: a `:transform` fn (full
  envelope → rendered string) and an `:owner` naming the registrant. Registering
  when the slot is already occupied fails loudly, naming both the existing and
  attempting owners; use `replace-default-help-transform!` for a deliberate
  override. The occupancy check runs inside the `swap!`, so two racing
  registrations cannot both clear a stale read.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/help_transform/alpha.clj#L41-L53">Source</a></sub></p>

## <a name="skein.api.runtime.help-transform.alpha/replace-default-help-transform!">`replace-default-help-transform!`</a>
``` clojure
(replace-default-help-transform! runtime registration)
```
Function.

Replace `runtime`'s registered default help transform, failing loudly when
  the slot is empty.

  Same map shape as `register-default-help-transform!`. This is the deliberate
  override for an occupied slot; unlike the register path it requires a transform
  to already be registered.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/help_transform/alpha.clj#L59-L69">Source</a></sub></p>
