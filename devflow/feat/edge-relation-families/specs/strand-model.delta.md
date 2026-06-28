# Strand Model delta for edge relation families

**Document ID:** `ERF-DELTA-001`
**Root spec:** [strand-model.md](../../../specs/strand-model.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Reviewed
**Last Updated:** 2026-06-28

## ERF-DELTA-001.P1 Summary

This delta replaces boolean `active` lifecycle and closed/global edge behavior with explicit strand state, open relation names, durable per-relation acyclicity declarations, core supersession, annotation-by-default edges, and direct relationship-aware query predicates.

## ERF-DELTA-001.P2 Contract changes

- **ERF-DELTA-001.CC1:** Strand records replace `active` and `legacy inactive timestamp column` with required `state`. Valid values are `active`, `closed`, and `replaced`.
- **ERF-DELTA-001.CC2:** `state="active"` means the strand participates in readiness and can block dependents through active `depends-on` edges.
- **ERF-DELTA-001.CC3:** `state="closed"` means the strand is intentionally inactive with no core replacement semantics.
- **ERF-DELTA-001.CC4:** `state="replaced"` means the strand is inactive because another strand supersedes it. The replacement is discovered by querying incoming `supersedes` edges, not by a `replaced_by` column.
- **ERF-DELTA-001.CC5:** The shipped schema and public APIs do not accept old lifecycle fields or aliases: no `active`, no `legacy inactive timestamp column`, no legacy boolean lifecycle query field, and no compatibility booleans.
- **ERF-DELTA-001.CC5a:** Generic create/update/batch/pattern lifecycle inputs accept only `active` or `closed`. `replaced` is reserved for the core supersession transaction so no replaced strand can exist without replacement lineage.
- **ERF-DELTA-001.CC6:** An edge's `edge_type` is a relation name: a lowercase portable string matching `[a-z0-9][a-z0-9._/-]*`. Relation names are stored exactly as supplied and are not case-folded or otherwise canonicalized.
- **ERF-DELTA-001.CC7:** `strand_edges` stores any valid relation name. Valid relation names outside Skein's operational batteries and annotation catalog are accepted as userland relation names.
- **ERF-DELTA-001.CC8:** Skein ships three operational relation batteries: `depends-on`, `parent-of`, and `supersedes`.
- **ERF-DELTA-001.CC9:** Operational directions are: `work --depends-on--> blocker`, `parent --parent-of--> child`, and `replacement --supersedes--> replaced`.
- **ERF-DELTA-001.CC10:** `depends-on`, `parent-of`, and `supersedes` are declared acyclic by storage initialization. The durable declaration model is the same model trusted userland uses for custom acyclic relations.
- **ERF-DELTA-001.CC11:** A relation absent from the durable acyclicity declaration schema is an annotation relation by default. Annotation relations do not affect readiness, lifecycle, or traversal.
- **ERF-DELTA-001.CC12:** Edge writes validate source/target existence, JSON-object edge attributes, valid relation names, and self-edge rejection for every relation.
- **ERF-DELTA-001.CC13:** Edge writes for declared acyclic relations reject only cycles within that same relation. The reachability check never walks all edge types together.
- **ERF-DELTA-001.CC14:** Edge writes for annotation relations do not run acyclicity checks. Annotation relations may form directed non-self cycles and symmetric pairs, but consumers must not treat them as DAGs.
- **ERF-DELTA-001.CC15:** Userland acyclic declarations are declare-before-structural-use: declaring a relation acyclic after annotation edges of that relation already exist fails loudly and requires explicit migration by the user. Re-declaring an already acyclic relation is idempotent; public undeclare/rebind behavior is not part of this feature.
- **ERF-DELTA-001.CC16:** Existing databases with the old schema are not migrated. Initializing or starting against incompatible old tables fails loudly with guidance to recreate the alpha world.
- **ERF-DELTA-001.CC17:** The shipped readiness rule is: a ready strand has `state="active"` and no direct outgoing `depends-on` edge whose target strand has `state="active"`.
- **ERF-DELTA-001.CC18:** The core supersession operation writes `replacement --supersedes--> old`, sets `old.state="replaced"`, and rewires incoming `depends-on` edges from `old` to `replacement` in one transaction.
- **ERF-DELTA-001.CC19:** Supersession rewiring affects every direct dependent regardless of dependent state: each `dependent --depends-on--> old` edge is replaced by `dependent --depends-on--> replacement`.
- **ERF-DELTA-001.CC20:** Supersession fails loudly and rolls back if the `supersedes` lineage or any rewired `depends-on` edge would create a cycle.
- **ERF-DELTA-001.CC21:** Supersession does not copy old outgoing dependencies onto the replacement; the replacement strand owns its own blockers.
- **ERF-DELTA-001.CC22:** Query predicates over strands gain direct existential relation predicates: `[:edge/out relation target-query]` and `[:edge/in relation source-query]`. They test whether the candidate strand has at least one outgoing/incoming edge of the named relation to/from an endpoint strand matching the nested query expression.
- **ERF-DELTA-001.CC23:** Relation operands in edge predicates are valid relation-name strings or query parameter references such as `[:param :relation]`. Endpoint query expressions use the same runtime parameter map as the containing query.
- **ERF-DELTA-001.CC24:** Endpoint query expressions in this feature are strand-local: they may use core field, attribute, comparison, set-membership, existence/missing, and logical predicates, but may not contain nested `:edge/out` or `:edge/in` predicates.
- **ERF-DELTA-001.CC25:** Edge predicates are direct edge-existence predicates, not path traversal, edge-attribute filters, fixed-depth multi-hop filters, or generic SQL join escape hatches.
- **ERF-DELTA-001.CC26:** Burning a strand continues to delete all incident edges regardless of relation declaration status.

## ERF-DELTA-001.P3 Design decisions

### ERF-DELTA-001.D1 State replaces boolean activity

- **Decision:** Lifecycle state is a required enum field, not a boolean plus implied inactive reason.
- **Rationale:** Agents and event listeners need to distinguish ordinary closure from replacement without consulting optional annotation conventions. `state` makes replacement lifecycle explicit at the schema boundary.
- **Rejected:** Keeping `active` and asking listeners to inspect `supersedes` edges to infer replacement.

### ERF-DELTA-001.D2 Supersession is structural but not a replacement pointer column

- **Decision:** `supersedes` is a declared acyclic structural relation and the supersession operation owns the transaction, but replacement lookup remains edge-based.
- **Rationale:** Replacement lineage is graph data. A `replaced_by` field would duplicate edge truth and introduce consistency conflicts.
- **Rejected:** `replaced_by` columns, edge-only annotation supersession, and automatic copying of all old edges to the replacement.

### ERF-DELTA-001.D3 Durable declarations instead of runtime semantics

- **Decision:** Acyclicity declarations live in durable storage, not in `init.clj`, a weaver-lifetime registry, or loaded library code.
- **Rationale:** Edge rows must remain self-describing across weaver restart and config reload. A runtime registry would let the same durable edge set mean different things depending on which config happened to load.
- **Rejected:** Runtime-configurable edge-type semantics and hardcoded all-edge cycle checks.

### ERF-DELTA-001.D4 Annotation-by-default remains open-world behavior

- **Decision:** Undeclared valid relation names are accepted as annotations rather than rejected.
- **Rationale:** Relation naming is still userland vocabulary outside Skein's small operational set. Rejecting unknown names would recreate the closed-core problem.
- **Rejected:** A storage allowlist or broad beads-style relationship behavior table.

## ERF-DELTA-001.P4 Open questions

- **ERF-DELTA-001.Q1:** None for contract scope.
