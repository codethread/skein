
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
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L114-L119">Source</a></sub></p>

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
  carrying requested ids, burned ids, and before-images.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L170-L196">Source</a></sub></p>

## <a name="skein.api.graph.alpha/incoming-edges">`incoming-edges`</a>
``` clojure
(incoming-edges runtime to-ids edge-type)
```
Function.

Return normalized `edge-type` edges whose target is one of `to-ids`.

  One indexed lookup for a strand's parents/annotators; no graph traversal.
  Adjacency is lenient: an id absent from storage yields no rows rather than
  a missing-id error (unlike subgraph/ancestor-root-ids seeds).
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L143-L150">Source</a></sub></p>

## <a name="skein.api.graph.alpha/outgoing-edges">`outgoing-edges`</a>
``` clojure
(outgoing-edges runtime from-ids edge-type)
```
Function.

Return normalized `edge-type` edges whose source is one of `from-ids`.

  One indexed lookup for a strand's children; no graph traversal. Lenient
  adjacency: an absent id yields no rows rather than a missing-id error.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L156-L162">Source</a></sub></p>

## <a name="skein.api.graph.alpha/queries">`queries`</a>
``` clojure
(queries runtime)
```
Function.

Return registered query definitions keyed by canonical string name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L49-L52">Source</a></sub></p>

## <a name="skein.api.graph.alpha/query-explain">`query-explain`</a>
``` clojure
(query-explain runtime query-name)
```
Function.

Describe a registered query definition and how CLI callers invoke it.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L68-L81">Source</a></sub></p>

## <a name="skein.api.graph.alpha/query-ids">`query-ids`</a>
``` clojure
(query-ids runtime query-or-name params)
```
Function.

Return strand ids matching an ad hoc query definition or registered query name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L89-L95">Source</a></sub></p>

## <a name="skein.api.graph.alpha/register-query!">`register-query!`</a>
``` clojure
(register-query! runtime query-name query-def)
```
Function.

Register a named query definition and return its canonical API shape.

  Canonicalizes the simple symbol or keyword name and compiles the
  definition once before it reaches the registry, so malformed query data
  fails loudly at registration time; `skein.core.query` is the grammar
  authority for definitions.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L33-L43">Source</a></sub></p>

## <a name="skein.api.graph.alpha/resolve-query">`resolve-query`</a>
``` clojure
(resolve-query runtime query-name)
```
Function.

Return the registered query definition for a simple symbol or keyword name.

  Throws ex-info listing the available names when no definition matches.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L58-L63">Source</a></sub></p>

## <a name="skein.api.graph.alpha/strands-by-ids">`strands-by-ids`</a>
``` clojure
(strands-by-ids runtime ids)
```
Function.

Return normalized strands for ids, preserving first-seen input order.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L103-L106">Source</a></sub></p>

## <a name="skein.api.graph.alpha/subgraph">`subgraph`</a>
``` clojure
(subgraph runtime root-ids)
(subgraph runtime root-ids opts)
```
Function.

Return a normalized strand subgraph rooted at `root-ids`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/graph/alpha.clj#L126-L134">Source</a></sub></p>
