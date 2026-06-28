# REPL API delta for edge relation families

**Document ID:** `ERF-DELTA-003`
**Root spec:** [repl-api.md](../../../specs/repl-api.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Reviewed
**Last Updated:** 2026-06-28

## ERF-DELTA-003.P1 Summary

This delta extends trusted Clojure workflows with state-based lifecycle, core supersession helpers, open relation names, edge-aware query forms, relation-schema helpers, and relation-scoped graph traversal options.

## ERF-DELTA-003.P2 Contract changes

- **ERF-DELTA-003.CC1:** `skein.repl/strand!` accepts `:state` in its options map. Valid create values are `"active"` and `"closed"`; omitted state defaults to `"active"`. `"replaced"` is produced only by the supersession helper.
- **ERF-DELTA-003.CC2:** `skein.repl/update!` accepts `:state` values `"active"` and `"closed"`; it no longer accepts `:active` or removed lifecycle fields, and it does not manually set `"replaced"`.
- **ERF-DELTA-003.CC3:** REPL-returned strand rows expose `state` and do not expose `active` or `inactive_at`.
- **ERF-DELTA-003.CC4:** `ready` returns strands with `state="active"` whose direct `depends-on` targets are not `state="active"`.
- **ERF-DELTA-003.CC5:** `skein.repl` exposes or routes a core supersession helper, with final name to be set during implementation planning. The helper accepts old/replacement ids and delegates to the weaver supersession operation.
- **ERF-DELTA-003.CC6:** The supersession helper atomically records `replacement --supersedes--> old`, sets `old.state="replaced"`, and rewires incoming `depends-on` edges from old to replacement. It fails loudly on missing ids, invalid states, self-supersession, or cycles.
- **ERF-DELTA-003.CC7:** `defquery!`, `load-queries!`, `query`, `strands`, and `ready` accept query definitions containing edge predicates once the selected weaver supports this feature.
- **ERF-DELTA-003.CC8:** The outgoing edge predicate form is `[:edge/out relation target-query]`. It matches candidate strands with at least one outgoing edge of `relation` to a target strand satisfying `target-query`.
- **ERF-DELTA-003.CC9:** The incoming edge predicate form is `[:edge/in relation source-query]`. It matches candidate strands with at least one incoming edge of `relation` from a source strand satisfying `source-query`.
- **ERF-DELTA-003.CC10:** `relation` in an edge predicate is either a valid relation-name string or a query parameter reference such as `[:param :relation]`. The nested endpoint query uses the same parameter map as the containing query.
- **ERF-DELTA-003.CC11:** Endpoint queries are strand-local in this feature: they may use ordinary field, attribute, comparison, set-membership, existence/missing, and logical predicates, but may not contain nested edge predicates.
- **ERF-DELTA-003.CC12:** A custom ready-like query can be authored through existing named-query workflows, for example `[:and [:= :state "active"] [:not [:edge/out "requires" [:= :state "active"]]]]`, then consumed through `query` or `strand list --query`. The built-in `ready` helper continues to use the shipped `depends-on` readiness battery.
- **ERF-DELTA-003.CC13:** `skein.relations.alpha` exposes the annotation catalog as data-first Clojure values and simple lookup helpers. The catalog is advisory and is not required to write valid custom annotation relation names.
- **ERF-DELTA-003.CC14:** `skein.graph.alpha` exposes trusted helpers to declare and inspect acyclic relations. The helpers route through the selected weaver when called from connected helper REPLs and execute directly inside the active weaver JVM when called from trusted config/library code.
- **ERF-DELTA-003.CC15:** Acyclic declaration helpers are not preloaded into `skein.repl` as bare convenience names. Users explicitly require the blessed graph namespace when they need relation-schema operations.
- **ERF-DELTA-003.CC16:** `skein.graph.alpha/ancestor-root-ids` accepts seed ids plus opts containing an optional `:type` relation name and existing `:where`/`:params` filtering. Omitted `:type` uses the shipped `parent-of` battery.
- **ERF-DELTA-003.CC17:** `skein.graph.alpha/subgraph` accepts root ids plus optional opts containing `:type`. Omitted `:type` uses the shipped `parent-of` battery.
- **ERF-DELTA-003.CC18:** Graph traversal helpers fail loudly when asked to traverse a relation that is not declared acyclic. They do not provide cycle-aware traversal over annotation relations in this feature.

## ERF-DELTA-003.P3 Design decisions

### ERF-DELTA-003.D1 Supersession gets a helper because it is a lifecycle transition

- **Decision:** Supersession is exposed through a helper over the weaver operation rather than as manual `update!` plus `:edges` choreography.
- **Rationale:** The operation must update lifecycle, lineage, and dependency edges transactionally. Manual composition would recreate the listener/confusion problem this feature fixes.
- **Rejected:** Modeling supersession as annotation-only userland code.

### ERF-DELTA-003.D2 Keep relation vocabulary data-first

- **Decision:** Common annotation names live in `skein.relations.alpha` as data and lookup helpers, not as separate workflow APIs.
- **Rationale:** Agents get a shared vocabulary without forcing Skein to grow commands like duplicate, track, or verify.
- **Rejected:** A large command surface or a hard allowlist of relation names.

### ERF-DELTA-003.D3 Edge predicates are composable queries, not traversal helpers

- **Decision:** Query edge predicates answer direct existential relationship questions and compose with `:not`/`:and`/`:or`; multi-hop traversal stays in `skein.graph.alpha` helpers.
- **Rationale:** This preserves the simple predicate-over-strands query model and keeps expensive graph traversal out of `list`/`ready` filters.
- **Rejected:** Adding path expressions or recursive graph syntax to named queries.

## ERF-DELTA-003.P4 Open questions

- **ERF-DELTA-003.Q1:** None for contract scope. Exact helper names for supersession and relation declaration may be finalized during planning.
