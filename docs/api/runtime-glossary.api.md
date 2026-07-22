
-----
# <a name="skein.api.runtime.glossary.alpha">skein.api.runtime.glossary.alpha</a>


Explicit-runtime glossary of named failure outcomes for the discovery surface.

  A glossary outcome maps a qualified, stable name to a short canonical
  definition; the help projection (DELTA-Dtf-002.CC5) resolves per-verb
  `failure-modes` references against this one registry so lifecycle-failure prose
  is defined once and referenced by name, never restated per verb. Each owning
  spool registers its outcomes from its own `install!` before the ops that
  reference them (the load-order contract, DELTA-Dtf-003.CC2); trusted
  `init.clj`/REPL config may register outcomes directly.

  The registry is runtime-owned and **reload-cleared**: it follows the op-registry
  lifecycle (SPEC-004.C46/C63a/C63c), cleared by `reload!` before config re-runs,
  not the reload-surviving `spool-state` (SPEC-004.C95) that `skein.api.vocab.alpha`
  uses. It is a distinct layer from the vocab registry and reuses none of its
  machinery.

  Discipline (TEN-000@1, no-migration alpha): outcome names are qualified and
  stable; `register-glossary-outcome!` fails loudly on a name collision, naming
  both registrants, and changed semantics require a **new name**, never a
  redefinition. `replace-glossary-outcome!` is the deliberate override and
  requires the name to already exist.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument; nothing here reads the published ambient runtime.




## <a name="skein.api.runtime.glossary.alpha/glossary-outcomes">`glossary-outcomes`</a>
``` clojure
(glossary-outcomes runtime)
```
Function.

Return `runtime`'s registered glossary outcomes as full maps, sorted by name.

  The read/introspection projection: reads the runtime store explicitly, never
  the published ambient singleton.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/glossary/alpha.clj#L85-L94">Source</a></sub></p>

## <a name="skein.api.runtime.glossary.alpha/outcome-registered?">`outcome-registered?`</a>
``` clojure
(outcome-registered? runtime name)
```
Function.

True when `runtime`'s glossary carries an outcome named `name`.

  The existence predicate the op-registration glossary-ref check
  (DELTA-Dtf-003.CC2) consults for every `failure-modes` reference.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/glossary/alpha.clj#L100-L106">Source</a></sub></p>

## <a name="skein.api.runtime.glossary.alpha/register-glossary-outcome!">`register-glossary-outcome!`</a>
``` clojure
(register-glossary-outcome! runtime outcome)
```
Function.

Register `outcome` in `runtime`'s glossary and return it.

  `outcome` is a closed map `{:name :definition :owner}`: a qualified stable
  `:name` (`ns/term`), a non-blank `:definition`, and an `:owner` naming the
  registrant. Registering an already-registered name fails loudly, naming both
  the existing and attempting owners; use `replace-glossary-outcome!` for a
  deliberate override. The collision check runs inside the `swap!`, so two racing
  registrations of the same name cannot both clear a stale read.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/glossary/alpha.clj#L51-L63">Source</a></sub></p>

## <a name="skein.api.runtime.glossary.alpha/replace-glossary-outcome!">`replace-glossary-outcome!`</a>
``` clojure
(replace-glossary-outcome! runtime outcome)
```
Function.

Replace an already-registered glossary `outcome`, failing loudly when absent.

  Same map shape as `register-glossary-outcome!`. This is the deliberate override
  for a name that already exists; unlike the register path it requires the name
  to be present. A changed-semantics redefinition still deserves a new name — this
  path exists for trusted config to re-seat an outcome it owns.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/runtime/glossary/alpha.clj#L69-L79">Source</a></sub></p>
