
-----
# <a name="skein.spools.batteries">skein.spools.batteries</a>


Shipped core strand command surface as parser-backed weaver ops.

  Batteries registers the everyday strand operations — add/update/show/supersede/
  burn/list/ready/subgraph, spool coordinate helpers, the create-only `weave`
  op, and the read-only `query`/`pattern` registry-introspection ops — as
  `register-op!` ops whose
  `:arg-spec` is parsed by `skein.api.cli.alpha`. Each op delegates to the same
  `skein.api.*.alpha` calls the JSON socket dispatch uses and returns
  the same JSON shapes, so the ops are reachable through `strand <name>` at the
  CLI root. The namespace owns no module-level state:
  op handlers read the runtime from their invocation context (`:op/runtime`).

  Ops adopt the discovery-tier pattern (DELTA-Dtf-003.CC2): their arg-specs drive
  help, and where it adds value they carry closed `:annotations` sub-maps
  (`use-when`/`notes`/`failure-modes`) and op-level `:about`/`:prime` prose.
  `failure-modes` reference the batteries-owned glossary outcomes reconciled by
  the batteries module (the load-order contract, DELTA-Dtf-002.CC7).

  Batteries also EXPORTS `default-help-transform` — the reference default help
  transform (DELTA-Dtf-002.CC1): one recursive renderer over the uniform fractal
  node (DELTA-Dtf-001.CC2) with no per-level branch. It is exported for trusted
  config election and never auto-registers.

  Attribute/edge flag semantics reproduce old SPEC-002.C6–C11: `--attr key=value`
  is a repeatable, highest-precedence string map whose values may be payload
  references; `--attributes` references a JSON object of typed bulk attributes at
  lowest precedence; `--edge edge-type:to-id` adds outgoing edges. `--state`
  accepts `active|closed` for mutations and `active|closed|replaced` for `list`
  filtering.




## <a name="skein.spools.batteries/add-op">`add-op`</a>
``` clojure
(add-op ctx)
```
Function.

Create a strand with merged attributes, optional state, and outgoing edges.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L724-L738">Source</a></sub></p>

## <a name="skein.spools.batteries/burn-op">`burn-op`</a>
``` clojure
(burn-op ctx)
```
Function.

Physically delete one strand by id and return the burn summary.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L774-L777">Source</a></sub></p>

## <a name="skein.spools.batteries/contribute">`contribute`</a>
``` clojure
(contribute _ctx)
```
Function.

Return batteries' complete stable-owner CLI operation contribution.

  The classpath spool remains explicitly required by workspace startup; this
  function only supplies its declarative operation partition. Each entry is
  assembled into the canonical `::op-entry` shape (string key, `:name`, `:fn`,
  provenance, deadline/hook class) exactly as `register-op!` would, so the module
  publication path is equivalent to direct registration. Batteries ships no
  `help` op of its own — the built-in help op stays effective and batteries
  elects only the reference help transform (DELTA-Dtf-002.D1) — so the partition
  declares no overrides over the lower defaults layer.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L1479-L1496">Source</a></sub></p>

## <a name="skein.spools.batteries/default-help-transform">`default-help-transform`</a>
``` clojure
(default-help-transform envelope)
```
Function.

Render a canonical help envelope (DELTA-Dtf-001.CC1) as readable text.

  The batteries reference default help transform (DELTA-Dtf-002.CC1): a full
  envelope → the string the CLI relays verbatim. It is EXPORTED for trusted
  `init.clj` election through `register-default-help-transform!` (Task 8) and is
  deliberately not auto-registered by the module, so a fresh world keeps the
  raw-JSON floor (DELTA-Dtf-002.D1).

  Both members of the one help-schema family render through the single uniform
  node renderer (`render-node`): the detail envelope carrying `node`, and the
  no-arg catalog carrying `ops[]` of summary nodes (DELTA-Dtf-001.CC3). The only
  branch is which envelope family this is — an envelope-shape choice, never a
  per-node-level one, so the recursive node renderer stays uniform at every depth
  (the forcing-function invariant, DELTA-Dtf-003.D1).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L1378-L1396">Source</a></sub></p>

## <a name="skein.spools.batteries/install!">`install!`</a>
``` clojure
(install!)
(install! rt)
```
Function.

Register the batteries core strand ops into a weaver runtime.

  Registers the batteries-owned glossary outcomes first, then the ops (the
  load-order contract, DELTA-Dtf-002.CC7): an op's `failure-modes` references are
  checked against the runtime glossary at registration. The reference default help
  transform (`default-help-transform`) is NOT registered here — it is elected by
  trusted config (DELTA-Dtf-002.D1).

  The no-arg arity registers into the active runtime for legacy direct callers;
  the explicit-runtime arity is for tests and trusted callers. New startup
  configuration declares the `contribute`/`reconcile` module entry points.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L1423-L1449">Source</a></sub></p>

## <a name="skein.spools.batteries/list-op">`list-op`</a>
``` clojure
(list-op ctx)
```
Function.

List lean-projected strands, optionally filtered by lifecycle state and/or a named query.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L779-L793">Source</a></sub></p>

## <a name="skein.spools.batteries/note-op">`note-op`</a>
``` clojure
(note-op ctx)
```
Function.

Append a note to a target strand's memory via the note primitive.

  Its `note/text`/`note/at` content is storage-enforced write-once (SPEC-001.P4);
  the note strand stays open to decorating attrs. Returns the primitive's
  `{:id :target}` shape, where `target` is a projection of the `notes` edge rather
  than a stored attribute.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L853-L865">Source</a></sub></p>

## <a name="skein.spools.batteries/notes-op">`notes-op`</a>
``` clojure
(notes-op ctx)
```
Function.

Return a target strand's notes from every primitive writer in note/at order,
  optionally filtered to one review round.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L867-L872">Source</a></sub></p>

## <a name="skein.spools.batteries/pattern-op">`pattern-op`</a>
``` clojure
(pattern-op ctx)
```
Function.

Introspect registered weave patterns: list all metadata or explain one.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L842-L851">Source</a></sub></p>

## <a name="skein.spools.batteries/query-op">`query-op`</a>
``` clojure
(query-op ctx)
```
Function.

Introspect registered named queries: list all metadata or explain one.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L831-L840">Source</a></sub></p>

## <a name="skein.spools.batteries/read-limit">`read-limit`</a>
``` clojure
(read-limit rt)
```
Function.

Return the runtime's batteries read-result cap for CLI list/ready ops.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L218-L221">Source</a></sub></p>

## <a name="skein.spools.batteries/ready-op">`ready-op`</a>
``` clojure
(ready-op ctx)
```
Function.

List lean-projected ready strands, optionally from the result set of a named query.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L795-L808">Source</a></sub></p>

## <a name="skein.spools.batteries/reconcile">`reconcile`</a>
``` clojure
(reconcile {:keys [runtime]})
```
Function.

Seed batteries' owned glossary outcomes as a runtime resource.

  The declarative operation partition publishes through `contribute`; the
  glossary outcomes its ops' `failure-modes` reference are batteries-owned
  runtime resources (not declaration data), so the module lifecycle seeds them
  here rather than during direct registration (DELTA-OlrRepl-001.CC6). Module
  publication does not run the direct-registration glossary-ref check, so
  publishing before this reconcile is safe; help resolves the referenced-term
  closure against the seeded outcomes.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L1498-L1511">Source</a></sub></p>

## <a name="skein.spools.batteries/set-read-limit!">`set-read-limit!`</a>
``` clojure
(set-read-limit! rt limit)
```
Function.

Set the runtime's batteries read-result cap for CLI list/ready ops.

  Intended for trusted workspace config. Invalid values fail loudly instead of
  falling back to the default cap.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L223-L231">Source</a></sub></p>

## <a name="skein.spools.batteries/show-op">`show-op`</a>
``` clojure
(show-op ctx)
```
Function.

Return one normalized strand by id.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L763-L766">Source</a></sub></p>

## <a name="skein.spools.batteries/spool-op">`spool-op`</a>
``` clojure
(spool-op ctx)
```
Function.

Dispatch validated `strand spool about|add|bump` inputs and results.

  Input uses `::spool-op-context`; results use `::spool-about-result`,
  `::spool-add-result`, or `::spool-bump-result`. Producer manifests use
  `::advisory-manifest`. Each closed result/manifest map also uses the named
  `exact-keys?` predicate because `clojure.spec.alpha/keys` accepts extra keys.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L606-L621">Source</a></sub></p>

## <a name="skein.spools.batteries/spool-status-op">`spool-status-op`</a>
``` clojure
(spool-status-op ctx)
```
Function.

Return validated offline spool declaration and adoption status.

  Input uses `::spool-status-op-context`; the result uses
  `::spool-status-result`. Its closed result maps also use the named
  `exact-keys?` predicate because `clojure.spec.alpha/keys` accepts extra keys.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L623-L634">Source</a></sub></p>

## <a name="skein.spools.batteries/subgraph-op">`subgraph-op`</a>
``` clojure
(subgraph-op ctx)
```
Function.

Return a relation-scoped subgraph rooted at one strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L810-L819">Source</a></sub></p>

## <a name="skein.spools.batteries/supersede-op">`supersede-op`</a>
``` clojure
(supersede-op ctx)
```
Function.

Replace one strand with another and return the supersession result.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L768-L772">Source</a></sub></p>

## <a name="skein.spools.batteries/update-op">`update-op`</a>
``` clojure
(update-op ctx)
```
Function.

Patch one strand's title, state, attributes, and outgoing edges.

  Attributes are a JSON Merge Patch: `--attr` string values merge on top of the
  typed `--attributes` object (add precedence), and a JSON null in `--attributes`
  removes that key. Passing no attribute flag leaves the attribute map untouched.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L740-L761">Source</a></sub></p>

## <a name="skein.spools.batteries/vocab-op">`vocab-op`</a>
``` clojure
(vocab-op ctx)
```
Function.

List the runtime's vocabulary declarations as an ordered array of C1 maps,
  string-keyed at the wire boundary, optionally narrowed to one --kind.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L874-L881">Source</a></sub></p>

## <a name="skein.spools.batteries/weave-op">`weave-op`</a>
``` clojure
(weave-op ctx)
```
Function.

Apply a registered create-only weave pattern to one JSON input value.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L821-L829">Source</a></sub></p>
