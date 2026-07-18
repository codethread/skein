
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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L590-L602">Source</a></sub></p>

## <a name="skein.spools.batteries/burn-op">`burn-op`</a>
``` clojure
(burn-op ctx)
```
Function.

Physically delete one strand by id and return the burn summary.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L630-L633">Source</a></sub></p>

## <a name="skein.spools.batteries/install!">`install!`</a>
``` clojure
(install!)
(install! rt)
```
Function.

Register the batteries core strand ops into a weaver runtime.

  The no-arg arity registers into the active runtime for `use!`-style
  installation; the explicit-runtime arity is for tests and trusted callers.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L1017-L1033">Source</a></sub></p>

## <a name="skein.spools.batteries/list-op">`list-op`</a>
``` clojure
(list-op ctx)
```
Function.

List lean-projected strands, optionally filtered by lifecycle state and/or a named query.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L635-L649">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L709-L721">Source</a></sub></p>

## <a name="skein.spools.batteries/notes-op">`notes-op`</a>
``` clojure
(notes-op ctx)
```
Function.

Return a target strand's notes from every primitive writer in note/at order,
  optionally filtered to one review round.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L723-L728">Source</a></sub></p>

## <a name="skein.spools.batteries/pattern-op">`pattern-op`</a>
``` clojure
(pattern-op ctx)
```
Function.

Introspect registered weave patterns: list all metadata or explain one.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L698-L707">Source</a></sub></p>

## <a name="skein.spools.batteries/query-op">`query-op`</a>
``` clojure
(query-op ctx)
```
Function.

Introspect registered named queries: list all metadata or explain one.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L687-L696">Source</a></sub></p>

## <a name="skein.spools.batteries/read-limit">`read-limit`</a>
``` clojure
(read-limit rt)
```
Function.

Return the runtime's batteries read-result cap for CLI list/ready ops.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L148-L151">Source</a></sub></p>

## <a name="skein.spools.batteries/ready-op">`ready-op`</a>
``` clojure
(ready-op ctx)
```
Function.

List lean-projected ready strands, optionally from the result set of a named query.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L651-L664">Source</a></sub></p>

## <a name="skein.spools.batteries/set-read-limit!">`set-read-limit!`</a>
``` clojure
(set-read-limit! rt limit)
```
Function.

Set the runtime's batteries read-result cap for CLI list/ready ops.

  Intended for trusted workspace config. Invalid values fail loudly instead of
  falling back to the default cap.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L153-L161">Source</a></sub></p>

## <a name="skein.spools.batteries/show-op">`show-op`</a>
``` clojure
(show-op ctx)
```
Function.

Return one normalized strand by id.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L619-L622">Source</a></sub></p>

## <a name="skein.spools.batteries/spool-op">`spool-op`</a>
``` clojure
(spool-op ctx)
```
Function.

Dispatch parsed `strand spool ...` subcommands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L475-L489">Source</a></sub></p>

## <a name="skein.spools.batteries/subgraph-op">`subgraph-op`</a>
``` clojure
(subgraph-op ctx)
```
Function.

Return a relation-scoped subgraph rooted at one strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L666-L675">Source</a></sub></p>

## <a name="skein.spools.batteries/supersede-op">`supersede-op`</a>
``` clojure
(supersede-op ctx)
```
Function.

Replace one strand with another and return the supersession result.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L624-L628">Source</a></sub></p>

## <a name="skein.spools.batteries/update-op">`update-op`</a>
``` clojure
(update-op ctx)
```
Function.

Patch one strand's title, state, attributes, and outgoing edges.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L604-L617">Source</a></sub></p>

## <a name="skein.spools.batteries/vocab-op">`vocab-op`</a>
``` clojure
(vocab-op ctx)
```
Function.

List the runtime's vocabulary declarations as an ordered array of C1 maps,
  string-keyed at the wire boundary, optionally narrowed to one --kind.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L730-L737">Source</a></sub></p>

## <a name="skein.spools.batteries/weave-op">`weave-op`</a>
``` clojure
(weave-op ctx)
```
Function.

Apply a registered create-only weave pattern to one JSON input value.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/batteries/src/skein/spools/batteries.clj#L677-L685">Source</a></sub></p>
