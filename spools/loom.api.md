
-----
# <a name="skein.spools.loom">skein.spools.loom</a>


Read-only projections of the active strand graph into work views.

  A loom holds the whole warp under tension and reveals the developing cloth;
  this spool holds the active strand graph and projects it into the shapes
  consumers actually render: parent-of work DAGs with their depends-on edges,
  per-root branch progress views joined to a ready frontier, and workflow
  flow-status (history, frontier, subagent gates, delegated runs, and stalls)
  with a Mermaid gate chain. These projections were hand-rolled inside repo
  config; they are generic graph vocabulary that other code builds on, so they
  ship here on the classpath while a repo keeps only its own policy — which
  attribute names a branch, which query feeds the ready frontier.

  Every function is read-only: it composes the public weaver/graph surfaces and
  mutates no strands, edges, runtime config, or registered operations. Callers
  supply the active runtime explicitly.




## <a name="skein.spools.loom/branch-views">`branch-views`</a>
``` clojure
(branch-views rt opts)
```
Function.

Group active branch-stamped work roots into per-branch progress views.

  A branch root is an active strand carrying `:branch-attr` that is not itself a
  parent-of child of another active strand; each is joined to its active
  descendants and the ready frontier of `:ready-query`. Options:

  - `:branch-attr` — attribute key naming the branch (default `:branch`).
  - `:ready-query` — required inline query expression (vector or map, as
    accepted by `skein.api.weaver.alpha/ready`) whose ready frontier feeds each
    root view. Fails loudly when absent; a named query symbol/keyword is not
    resolved.
  - `:branch` — optional branch name to scope the projection to one branch.

  Fails loudly on unknown opt keys, a non-keyword `:branch-attr`, or a
  non-string `:branch`. Returns a vector of `{:branch :roots}` sorted by
  branch name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/loom.clj#L141-L184">Source</a></sub></p>

## <a name="skein.spools.loom/flow-status">`flow-status`</a>
``` clojure
(flow-status rt run-id)
```
Function.

Return workflow flow status by joining history, frontier, gates, runs, and stalls.

  The JSON-compatible payload is read-only and suitable for renderers; no
  workflow, agent-run, or gate state is mutated. Failure summaries are scoped to
  this run's own gates and their delegated runs so records from other workflows
  never surface in an unrelated run's payload. Includes a `:dev/mermaid` gate
  chain rendered by `gate-chain-mermaid`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/loom.clj#L267-L300">Source</a></sub></p>

## <a name="skein.spools.loom/gate-chain-mermaid">`gate-chain-mermaid`</a>
``` clojure
(gate-chain-mermaid gates ready-ids)
```
Function.

Return a dev-only Mermaid chain showing ready, stalled, and closed gates.

  `gates` are compact gate projections (as from `flow-status`); `ready-ids` is
  the set of ids on the ready frontier. This is the single render helper for the
  gate chain, so any op reusing it renders identical marker/node/link logic.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/loom.clj#L247-L265">Source</a></sub></p>

## <a name="skein.spools.loom/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Return loom installation metadata for trusted registration by name.

  Loom registers no ops; it is a read-only projection library that repo config
  and other spools compose. This metadata mirrors the other read-only spools for
  discovery symmetry.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/loom.clj#L302-L315">Source</a></sub></p>

## <a name="skein.spools.loom/summarize">`summarize`</a>
``` clojure
(summarize strand)
```
Function.

Return the compact strand shape used by read-only projections.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/loom.clj#L36-L39">Source</a></sub></p>

## <a name="skein.spools.loom/work-dags">`work-dags`</a>
``` clojure
(work-dags rt)
```
Function.

Return active parent-of work DAGs and their active depends-on edges.

  Projects every active parent-of root, its hierarchy edges, dependency edges,
  and compact strand rows into one JSON-compatible `{:roots :dags}` structure —
  the flat CLI query surface returns strand rows, this joins them into DAGs.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/loom.clj#L93-L113">Source</a></sub></p>
