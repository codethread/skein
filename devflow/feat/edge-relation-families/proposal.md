# Edge Relation Families Proposal

**Document ID:** `ERF-PROP-001`
**Status:** Reviewed
**Last Updated:** 2026-06-28
**Related RFCs:** [RFC-007 Edge Relation Families, Strand State, and Supersession](../../rfcs/2026-06-28-edge-relation-families.md)
**Related Specs:** [Strand Model](../../specs/strand-model.md), [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md)
**Feature Deltas:** [Strand Model delta](specs/strand-model.delta.md), [Weaver Runtime delta](specs/daemon-runtime.delta.md), [REPL API delta](specs/repl-api.delta.md), [CLI Surface delta](specs/cli.delta.md), [TENETS delta](tenets.delta.md)

## ERF-PROP-001.P1 Problem

Skein needs a graph model that agents can trust without forcing every relationship into one global DAG. The current model has a closed edge-type list, global cycle checking, no relationship-aware query predicates, and a boolean `active` lifecycle field that hides why a strand stopped participating in readiness.

Supersession exposes the weakness most clearly. If replacement is modeled as an annotation edge plus `active=false`, listeners must remember to inspect both lifecycle and edges to understand that a new strand replaces an old one. If they forget, the active dependency DAG can become misleading: dependents may become ready simply because an old blocker was deactivated, rather than being redirected to the replacement.

## ERF-PROP-001.P2 Goals

- **ERF-PROP-001.G1:** Replace `active`/`inactive_at` with an explicit `state` lifecycle field: `active`, `closed`, or `replaced`.
- **ERF-PROP-001.G2:** Replace the closed edge-type vocabulary with open, valid relation-name strings.
- **ERF-PROP-001.G3:** Make acyclicity a durable per-relation declaration, not a global edge invariant and not a mutable runtime registry.
- **ERF-PROP-001.G4:** Ship tested operational graph batteries for `depends-on`, `parent-of`, and `supersedes`.
- **ERF-PROP-001.G5:** Add a core supersession transaction that records replacement lineage, marks the old strand `replaced`, and preserves active dependency intent.
- **ERF-PROP-001.G6:** Add relationship-aware query predicates so named queries, `list`, `ready`, and REPL workflows can compose over direct edge existence without private SQL.
- **ERF-PROP-001.G7:** Generalize graph traversal helpers to traverse one named declared-acyclic relation at a time.
- **ERF-PROP-001.G8:** Keep replacement lookup edge-based with indexed `supersedes` edges; do not add `replaced_by` or duplicate replacement storage.
- **ERF-PROP-001.G9:** Leave non-operational relationships as annotation conventions over open relation names.

## ERF-PROP-001.P3 Non-goals

- **ERF-PROP-001.NG1:** Do not provide live migration for existing databases or compatibility aliases for `active`, `inactive_at`, `--active`, or old query fields.
- **ERF-PROP-001.NG2:** Do not add a `replaced_by` column or any duplicated replacement pointer.
- **ERF-PROP-001.NG3:** Do not add a runtime-configurable relation semantics registry.
- **ERF-PROP-001.NG4:** Do not add workflow-specific commands or core engine semantics for duplicates, tracking, gates, verification, or arbitrary annotation relations.
- **ERF-PROP-001.NG5:** Do not add edge-attribute predicates, path-query grammar, arbitrary multi-hop filters in `list`/`ready`, or cycle-aware traversal over annotation relations in the initial scope.
- **ERF-PROP-001.NG6:** Do not preserve old docs/code references to the removed schema when the feature ships; current contracts should read as if `state` was the original lifecycle model.

## ERF-PROP-001.P4 Proposed scope

- **ERF-PROP-001.S1:** Change strand lifecycle to `state` with values `active`, `closed`, and `replaced`. `state="active"` is the only lifecycle state that participates in readiness and blocking; `state="replaced"` is reserved for the supersession transaction.
- **ERF-PROP-001.S2:** Remove the public `active` field, `inactive_at` field, `--active` CLI flag, `:active` query field, and related compatibility behavior.
- **ERF-PROP-001.S3:** Replace the shipped edge-type enum with valid relation names using a portable lowercase grammar (`[a-z0-9][a-z0-9._/-]*`) shared by Clojure and CLI entry points.
- **ERF-PROP-001.S4:** Add durable acyclic relation declarations in the weaver-owned SQLite world schema. Absence from that schema means annotation-by-default, not rejection.
- **ERF-PROP-001.S5:** Bootstrap `depends-on`, `parent-of`, and `supersedes` as shipped acyclic declarations during storage initialization. Existing incompatible databases fail loudly; users must recreate alpha worlds.
- **ERF-PROP-001.S6:** Rescope edge writes so self-edges fail universally, declared acyclic relations get relation-local cycle checks, and annotation relations can form non-self cycles or symmetric pairs.
- **ERF-PROP-001.S7:** Define `supersedes` direction as `replacement --supersedes--> replaced`. The replacement for an old strand is found by an incoming `supersedes` edge, not by a strand column.
- **ERF-PROP-001.S8:** Add a core supersession operation that atomically writes the `supersedes` edge, sets the old strand to `state="replaced"`, and rewires incoming `depends-on` edges from old to replacement.
- **ERF-PROP-001.S9:** The supersession operation fails loudly if replacement lineage or dependency rewiring would create a cycle; no partial lifecycle/edge mutation commits.
- **ERF-PROP-001.S10:** Add direct existential query predicates for incoming/outgoing relation existence to matching endpoint strands. Built-in readiness is equivalent to `[:and [:= :state "active"] [:not [:edge/out "depends-on" [:= :state "active"]]]]`.
- **ERF-PROP-001.S11:** Generalize runtime graph traversal helpers with a relation type option. Traversal over annotation relations fails loudly unless a future explicitly cycle-aware helper is designed.
- **ERF-PROP-001.S12:** Ship a data-first annotation catalog for common workflow vocabulary such as `related-to`, `duplicates`, `references`, `implements`, `verifies`, `tracks`, and `caused-by`, while keeping those names behavior-free conventions.
- **ERF-PROP-001.S13:** Replace public CLI lifecycle flags and outputs with `state`, and add a simple supersession command over the weaver operation.
- **ERF-PROP-001.S14:** Coordinate with active batch graph upsert work so batch payloads use `state`, relation-name validation, and declaration-scoped acyclicity.
- **ERF-PROP-001.S15:** Promote the staged TEN-005 replacement when the feature ships so the tenet protects declared structural DAGs rather than a whole-graph DAG.

## ERF-PROP-001.P5 Open questions

- **ERF-PROP-001.Q1:** None for proposal scope. Implementation planning must pin exact function/command names and task sequencing without changing the contracts staged in the feature deltas.
