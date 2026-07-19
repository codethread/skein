
-----
# <a name="skein.spools.batteries">skein.spools.batteries</a>


Shipped core strand command surface as parser-backed weaver ops.

  Batteries registers the everyday strand operations — add/update/show/supersede/
  burn/list/ready/subgraph, spool coordinate helpers, the create-only `weave`
  op, and the read-only `query`/`pattern` registry-introspection ops — as
  `register-op!` ops whose
  `:arg-spec` is parsed by `skein.api.cli.alpha`. Each op delegates to the same
  `skein.api.*.alpha` calls the JSON socket dispatch uses today and returns
  the same JSON shapes, so the ops are reachable through `strand <name>` at the
  CLI root. The namespace owns no module-level state:
  op handlers read the runtime from their invocation context (`:op/runtime`).

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L729-L743">Source</a></sub></p>

## <a name="skein.spools.batteries/burn-op">`burn-op`</a>
``` clojure
(burn-op ctx)
```
Function.

Physically delete one strand by id and return the burn summary.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L779-L782">Source</a></sub></p>

## <a name="skein.spools.batteries/install!">`install!`</a>
``` clojure
(install!)
(install! rt)
```
Function.

Register the batteries core strand ops into a weaver runtime.

  The no-arg arity registers into the active runtime for `use!`-style
  installation; the explicit-runtime arity is for tests and trusted callers.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L1179-L1195">Source</a></sub></p>

## <a name="skein.spools.batteries/list-op">`list-op`</a>
``` clojure
(list-op ctx)
```
Function.

List lean-projected strands, optionally filtered by lifecycle state and/or a named query.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L784-L798">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L858-L870">Source</a></sub></p>

## <a name="skein.spools.batteries/notes-op">`notes-op`</a>
``` clojure
(notes-op ctx)
```
Function.

Return a target strand's notes from every primitive writer in note/at order,
  optionally filtered to one review round.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L872-L877">Source</a></sub></p>

## <a name="skein.spools.batteries/pattern-op">`pattern-op`</a>
``` clojure
(pattern-op ctx)
```
Function.

Introspect registered weave patterns: list all metadata or explain one.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L847-L856">Source</a></sub></p>

## <a name="skein.spools.batteries/query-op">`query-op`</a>
``` clojure
(query-op ctx)
```
Function.

Introspect registered named queries: list all metadata or explain one.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L836-L845">Source</a></sub></p>

## <a name="skein.spools.batteries/read-limit">`read-limit`</a>
``` clojure
(read-limit rt)
```
Function.

Return the runtime's batteries read-result cap for CLI list/ready ops.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L198-L201">Source</a></sub></p>

## <a name="skein.spools.batteries/ready-op">`ready-op`</a>
``` clojure
(ready-op ctx)
```
Function.

List lean-projected ready strands, optionally from the result set of a named query.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L800-L813">Source</a></sub></p>

## <a name="skein.spools.batteries/set-read-limit!">`set-read-limit!`</a>
``` clojure
(set-read-limit! rt limit)
```
Function.

Set the runtime's batteries read-result cap for CLI list/ready ops.

  Intended for trusted workspace config. Invalid values fail loudly instead of
  falling back to the default cap.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L203-L211">Source</a></sub></p>

## <a name="skein.spools.batteries/show-op">`show-op`</a>
``` clojure
(show-op ctx)
```
Function.

Return one normalized strand by id.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L768-L771">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L590-L605">Source</a></sub></p>

## <a name="skein.spools.batteries/spool-status-op">`spool-status-op`</a>
``` clojure
(spool-status-op ctx)
```
Function.

Return validated offline spool declaration and adoption status.

  Input uses `::spool-status-op-context`; the result uses
  `::spool-status-result`. Its closed result maps also use the named
  `exact-keys?` predicate because `clojure.spec.alpha/keys` accepts extra keys.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L607-L618">Source</a></sub></p>

## <a name="skein.spools.batteries/subgraph-op">`subgraph-op`</a>
``` clojure
(subgraph-op ctx)
```
Function.

Return a relation-scoped subgraph rooted at one strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L815-L824">Source</a></sub></p>

## <a name="skein.spools.batteries/supersede-op">`supersede-op`</a>
``` clojure
(supersede-op ctx)
```
Function.

Replace one strand with another and return the supersession result.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L773-L777">Source</a></sub></p>

## <a name="skein.spools.batteries/update-op">`update-op`</a>
``` clojure
(update-op ctx)
```
Function.

Patch one strand's title, state, attributes, and outgoing edges.

  Attributes are a JSON Merge Patch: `--attr` string values merge on top of the
  typed `--attributes` object (add precedence), and a JSON null in `--attributes`
  removes that key. Passing no attribute flag leaves the attribute map untouched.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L745-L766">Source</a></sub></p>

## <a name="skein.spools.batteries/vocab-op">`vocab-op`</a>
``` clojure
(vocab-op ctx)
```
Function.

List the runtime's vocabulary declarations as an ordered array of C1 maps,
  string-keyed at the wire boundary, optionally narrowed to one --kind.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L879-L886">Source</a></sub></p>

## <a name="skein.spools.batteries/weave-op">`weave-op`</a>
``` clojure
(weave-op ctx)
```
Function.

Apply a registered create-only weave pattern to one JSON input value.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L826-L834">Source</a></sub></p>
