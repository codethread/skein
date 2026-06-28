# Weaver Runtime delta for edge relation families

**Document ID:** `ERF-DELTA-002`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Reviewed
**Last Updated:** 2026-06-28

## ERF-DELTA-002.P1 Summary

This delta adds durable relation-schema operations, state-based readiness, core supersession, relation-aware query compilation, and relation-scoped traversal behavior to the weaver runtime.

## ERF-DELTA-002.P2 Contract changes

- **ERF-DELTA-002.CC1:** Weaver API strand rows expose `state` and do not expose old `active` or `legacy inactive timestamp column` lifecycle fields.
- **ERF-DELTA-002.CC2:** Generic add/update/batch/pattern mutation paths accept `state` where they previously accepted `active`, but only with values `active` or `closed`. The `replaced` state is set only by the supersession operation.
- **ERF-DELTA-002.CC3:** The weaver-owned storage schema includes durable acyclic relation declarations. Runtime startup/config reload does not own relation semantics; it reads and mutates durable relation declarations only through storage-backed operations.
- **ERF-DELTA-002.CC4:** Storage initialization installs the shipped acyclic declarations for `depends-on`, `parent-of`, and `supersedes` before accepting edge writes that rely on those batteries.
- **ERF-DELTA-002.CC5:** The weaver API exposes trusted semantic operations to declare one acyclic relation and list declared acyclic relations. These operations validate relation-name syntax, are idempotent for an already declared relation, and fail loudly when declaration would rebind an already-used annotation relation.
- **ERF-DELTA-002.CC6:** Weaver edge mutation paths delegate relation validation and cycle checking to storage. This includes single-strand update edges, create-with-edges paths, weave/pattern-created batch edges, supersession rewiring, and any active batch graph mutation work when it ships.
- **ERF-DELTA-002.CC7:** The weaver exposes a core supersession operation. It validates old/replacement ids, writes `replacement --supersedes--> old`, sets the old strand to `state="replaced"`, rewires incoming `depends-on` edges from old to replacement, and returns normalized before/after/edge outcome data.
- **ERF-DELTA-002.CC8:** Supersession is transactional. If validation, state update, edge insertion, edge deletion, or dependency rewiring fails, no partial mutation commits.
- **ERF-DELTA-002.CC9:** Supersession emits a semantic event after commit, with enough data for listeners to distinguish replacement from ordinary state updates. Compatibility `:strand/updated` event behavior may be retained, but the replacement event is authoritative for supersession workflows.
- **ERF-DELTA-002.CC10:** The query compiler accepts `[:edge/out relation target-query]` and `[:edge/in relation source-query]` predicates. They compile as correlated direct-edge existence checks within the existing strand-filter query model.
- **ERF-DELTA-002.CC11:** Edge predicates compose with existing top-level logical operators, comparisons, parameterized named queries, and `ready` filtering. Endpoint subqueries are strand-local and reject nested edge predicates in this feature. Malformed relation operands, malformed endpoint queries, missing params, invalid relation names, or nested edge predicates fail during query validation/compilation.
- **ERF-DELTA-002.CC12:** The weaver named-query registry can store query definitions containing edge predicates. Registry validation compiles the full query, including endpoint subqueries, before registration succeeds.
- **ERF-DELTA-002.CC13:** The `ready` operation remains a weaver semantic operation with the shipped `depends-on` readiness battery. It must be implemented by, or remain semantically equivalent to, `[:and [:= :state "active"] [:not [:edge/out "depends-on" [:= :state "active"]]]]`.
- **ERF-DELTA-002.CC14:** Runtime graph traversal operations are relation-scoped. `ancestor-root-ids` and `subgraph` accept a relation type option, default to the shipped `parent-of` battery, and traverse only that one relation.
- **ERF-DELTA-002.CC15:** Relation-scoped traversal operations require the selected relation to be declared acyclic. Traversal over annotation relations fails loudly rather than risking cycle-specific reasoning or unbounded recursive SQL.
- **ERF-DELTA-002.CC16:** Existing traversal contracts for empty input, missing ids, stable ordering, duplicate handling, `:where` filtering for ancestor roots, returned edge shape, and subgraph result shape are preserved unless explicitly changed by a later plan.
- **ERF-DELTA-002.CC17:** The JSON socket operation allowlist includes public state-aware strand operations and a simple supersession operation. Relation declaration/listing and raw graph traversal remain trusted Clojure workflows, not public CLI graph-management commands.
- **ERF-DELTA-002.CC18:** The runtime ships a source-visible `skein.relations.alpha` namespace containing the annotation catalog as data. The catalog does not gate storage; valid relation names outside the catalog remain valid userland annotations.

## ERF-DELTA-002.P3 Design decisions

### ERF-DELTA-002.D1 Storage owns structural graph invariants

- **Decision:** State transitions, acyclic relation declarations, cycle checks, and supersession rewiring are enforced at the storage/weaver semantic boundary.
- **Rationale:** Event handlers run after commit and cannot safely enforce write-time invariants. Structural graph correctness must be transactional and central.
- **Rejected:** Enforcing supersession through post-commit event handlers or CLI-side orchestration.

### ERF-DELTA-002.D2 Supersession gets an authoritative event

- **Decision:** Supersession emits an explicit semantic event rather than appearing only as a generic state update plus edge write.
- **Rationale:** The feature exists partly because listeners should not forget to check replacement lineage. The event should make replacement observable at the same abstraction level as the operation.
- **Rejected:** Requiring listeners to infer replacement by diffing `state` and edge tables.

### ERF-DELTA-002.D3 Edge predicates stay inside the filter model

- **Decision:** Edge predicates are direct existential predicates that compile into the current strand-query WHERE-fragment model via correlated subqueries.
- **Rationale:** This gives userland relationship-aware reads without turning `list`/`ready` into a generic graph traversal or SQL authoring API.
- **Rejected:** Adding arbitrary joins, path queries, or multi-hop traversal syntax to the query DSL.

### ERF-DELTA-002.D4 Catalog data is not a runtime registry

- **Decision:** The annotation catalog is shipped as source-visible data and docs, while structural enforcement remains the durable acyclic declaration schema.
- **Rationale:** A catalog gives agents shared vocabulary without making unknown relation names invalid or introducing restart-sensitive behavior.
- **Rejected:** Treating the catalog as an allowlist or as a mutable runtime relation-semantics registry.

## ERF-DELTA-002.P4 Open questions

- **ERF-DELTA-002.Q1:** None for contract scope. Exact event type and operation names may be finalized in the implementation plan while preserving the semantic behavior above.
