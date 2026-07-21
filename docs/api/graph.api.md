
-----
# <a name="skein.api.graph.alpha">skein.api.graph.alpha</a>


Explicit-runtime API for the named-query registry, query selection, strand
  hydration, graph traversal, and burn.

  This namespace owns the blessed named-query registry surface and its
  validation, ad hoc and registered query id selection, strand hydration by
  id, relation-scoped traversal, edge adjacency, and burn with its pre-commit
  gate and event fanout — and reads in that order. The query compiler lives
  in `skein.core.query`, the SQL engine in `skein.core.db`, and the shared
  lifecycle and dispatch plumbing in `skein.core.weaver.*`.

  Callers own runtime selection and pass the target weaver runtime as the
  first argument to every function here.




## <a name="skein.api.graph.alpha/ancestor-root-ids">`ancestor-root-ids`</a>
``` clojure
(ancestor-root-ids runtime seed-ids)
(ancestor-root-ids runtime seed-ids opts)
```
Function.

Return ancestor root ids reachable from `seed-ids`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L233-L238">Source</a></sub></p>

## <a name="skein.api.graph.alpha/burn-by-ids!">`burn-by-ids!`</a>
``` clojure
(burn-by-ids! runtime ids)
(burn-by-ids! runtime ids req-ctx)
```
Function.

Delete strands by id and enqueue burn events for removed rows.

  Loads the before-images and deletes inside one transaction, running the
  `:strand/burn-before-commit` validation gate between the two so a rejecting
  hook rolls the whole burn back; then enqueues the `:strand/burned` event
  carrying requested ids, burned ids, and before-images. The `req-ctx` arity
  threads an explicit request-context map (the same shape
  `skein.api.batch.alpha/apply!` accepts) into the gate; the two-argument
  form derives its own burn context.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L297-L326">Source</a></sub></p>

## <a name="skein.api.graph.alpha/coerce-declared-params">`coerce-declared-params`</a>
``` clojure
(coerce-declared-params query-def params)
```
Function.

Coerce string-keyed CLI `params` to a definition's declared keyword names.

  Restricts `params` to `query-def`'s declared `:params`, returning a map keyed
  by the declared keywords for the names actually supplied. Unknown param names
  fail loudly with the offending names and the full declared set in ex-data,
  mirroring the socket read path's contract (batteries hand-rolled this against
  the JSON dispatch) so a spool's `--query` support rejects exactly the params
  the built-in path does. A definition with no declared `:params` accepts an
  empty map and rejects every name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L165-L184">Source</a></sub></p>

## <a name="skein.api.graph.alpha/conjoin-where">`conjoin-where`</a>
``` clojure
(conjoin-where query-def extra-where)
(conjoin-where query-def extra-where params)
```
Function.

Return a query definition that conjoins `extra-where` onto `query-def`.

  Resolves `query-def` to its where-expression — validating `params` against
  any declared `:params` — and returns the canonical
  `[:and <where> <extra-where>]` shape a caller then lists or readies with the
  same `params`. A nil `extra-where` returns `query-def` unchanged so callers
  thread an optional overlay (a state filter, say) without a surrounding
  conditional. `skein.core.query` owns the where grammar and resolves
  `[:param name]` references at compile time, not here.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L142-L156">Source</a></sub></p>

## <a name="skein.api.graph.alpha/incoming-edges">`incoming-edges`</a>
``` clojure
(incoming-edges runtime to-ids edge-type)
```
Function.

Return normalized `edge-type` edges whose target is one of `to-ids`.

  One indexed lookup for a strand's parents/annotators; no graph traversal.
  Adjacency is lenient: an id absent from storage yields no rows rather than
  a missing-id error (unlike subgraph/ancestor-root-ids seeds).
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L266-L273">Source</a></sub></p>

## <a name="skein.api.graph.alpha/lookup-name">`lookup-name`</a>
``` clojure
(lookup-name query-name)
```
Function.

Return the canonical registry lookup key for a query name from CLI input.

  Trims a string name and drops a leading `:`, and canonicalizes a simple
  symbol or keyword; namespaced, blank, or non-name inputs fail loudly. This is
  the blessed coercion for a raw `--query` argument. `resolve-query` accepts the
  same raw forms directly, so a caller that only needs the definition skips this
  and looks up the name in one step.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L205-L214">Source</a></sub></p>

## <a name="skein.api.graph.alpha/outgoing-edges">`outgoing-edges`</a>
``` clojure
(outgoing-edges runtime from-ids edge-type)
```
Function.

Return normalized `edge-type` edges whose source is one of `from-ids`.

  One indexed lookup for a strand's children; no graph traversal. Lenient
  adjacency: an absent id yields no rows rather than a missing-id error.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L279-L285">Source</a></sub></p>

## <a name="skein.api.graph.alpha/queries">`queries`</a>
``` clojure
(queries runtime)
```
Function.

Return registered query definitions keyed by canonical string name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L61-L64">Source</a></sub></p>

## <a name="skein.api.graph.alpha/query-explain">`query-explain`</a>
``` clojure
(query-explain runtime query-name)
```
Function.

Describe a registered query definition and how CLI callers invoke it.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L94-L108">Source</a></sub></p>

## <a name="skein.api.graph.alpha/query-ids">`query-ids`</a>
``` clojure
(query-ids runtime query-or-name params)
```
Function.

Return strand ids matching an ad hoc query definition or registered query name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L116-L122">Source</a></sub></p>

## <a name="skein.api.graph.alpha/referenced-params">`referenced-params`</a>
``` clojure
(referenced-params query-def)
```
Function.

Return ordered distinct `[:param name]` keyword references in `query-def`.

  Reads the definition's where-expression — a map's `:where` or a bare vector —
  and reports each referenced parameter name in first-seen order without
  compiling SQL. This is the composable read a spool uses to describe a query's
  runtime params; `query-explain` is the by-name descriptive projection that
  carries this same list beside the definition's declared `:params`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L190-L199">Source</a></sub></p>

## <a name="skein.api.graph.alpha/register-query!">`register-query!`</a>
``` clojure
(register-query! runtime query-name query-def)
```
Function.

Register a named query definition and return its canonical API shape.

  Canonicalizes the simple symbol or keyword name and validates that the
  definition compiles before it reaches the registry, so malformed query
  data fails loudly at registration time; `skein.core.query` is the
  grammar authority for definitions and compiles the stored definition at
  each use.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L44-L55">Source</a></sub></p>

## <a name="skein.api.graph.alpha/resolve-query">`resolve-query`</a>
``` clojure
(resolve-query runtime query-name)
```
Function.

Return the registered query definition for a simple symbol or keyword name.

  Throws ex-info listing the available names when no definition matches.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L70-L75">Source</a></sub></p>

## <a name="skein.api.graph.alpha/strands-by-ids">`strands-by-ids`</a>
``` clojure
(strands-by-ids runtime ids)
```
Function.

Return normalized strands for ids, preserving first-seen input order.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L222-L225">Source</a></sub></p>

## <a name="skein.api.graph.alpha/subgraph">`subgraph`</a>
``` clojure
(subgraph runtime root-ids)
(subgraph runtime root-ids opts)
```
Function.

Return a normalized strand subgraph rooted at `root-ids`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L249-L257">Source</a></sub></p>
