# Edge Relation Families, Strand State, and Supersession

**Document ID:** `RFC-007` **Status:** Implemented **Created:** 2026-06-28 **Related specs:** [Strand Model](../specs/strand-model.md), [Weaver Runtime](../specs/daemon-runtime.md), [REPL API](../specs/repl-api.md), [CLI Surface](../specs/cli.md) **Related tenets:** TEN-000@1 (alpha software), TEN-004 (Less is More), TEN-005 (Task graphs are DAGs)

## RFC-007.P1 Problem

The current edge and lifecycle model bakes the wrong concepts into the core:

- Edge types are a fixed set of four — `depends-on`, `related-to`, `parent-of`, `supersedes` — enforced by storage and Clojure specs.
- Acyclicity is enforced globally across all edges, so annotation-like relationships cannot be cyclic or symmetric.
- Only `depends-on` and `parent-of` have engine behavior; `related-to` and `supersedes` are inert labels.
- The query DSL cannot ask relationship-aware questions.
- Strand lifecycle is represented by `active true|false`, which tells readers whether a strand participates in readiness but not why it stopped participating. For replacement workflows this is too lossy: listeners can observe inactive data and miss that a replacement exists unless they also know to inspect a convention edge.

This creates two related defects. Userland cannot name/query relationships freely, and core lifecycle cannot distinguish ordinary closure from replacement even though replacement changes how agents should reason about the active DAG.

## RFC-007.P2 Guiding philosophy

- **RFC-007.PH1 — Core owns graph mechanics and lifecycle truth.** The engine owns the durable node/edge/state substrate, write-time structural invariants, readiness semantics, and the few lifecycle transitions that change the active graph.
- **RFC-007.PH2 — Batteries are tested defaults, not a closed world.** Skein ships a small tested set of operational graph relations and helper operations for common agent workflows. These names are supported defaults, but open valid relation names remain available for userland.
- **RFC-007.PH3 — Annotation vocabulary stays flexible.** Rich domain-specific relationships, views, reports, and workflow policies are built in trusted config/libs over the primitives. Skein may document common annotation names, but it does not grow a command or engine rule per annotation.
- **RFC-007.PH4 — Alpha reset is acceptable.** Per TEN-000@1, this feature may require users to drop existing databases and update code that used the old `active` field. We will not carry live migration or compatibility aliases for the old schema.

## RFC-007.P3 Prior art: beads

`~/dev/vendor/beads` validates the useful mechanics while illustrating what Skein should avoid copying wholesale.

What beads gets right:

- **RFC-007.A1 — One relationship table with typed edges.** Relationships carry a type name in one table-shaped model rather than separate tables per relationship.
- **RFC-007.A2 — Per-type cycle checks.** Cycle checking is scoped to blocking dependency types rather than all relationship rows.
- **RFC-007.A3 — Unknown/custom types are accepted.** Unknown names degrade to non-blocking annotations rather than failing a closed allowlist.
- **RFC-007.A4 — Supersession is edge plus lifecycle, not edge-only annotation.** beads' `bd supersede` command writes a `supersedes` link and closes the old issue. It does not migrate every edge, freeze nodes, or make supersession affect readiness by edge existence alone.

What Skein rejects:

- **RFC-007.A5 — API-centric vocabulary growth.** beads ships a large well-known relationship list and bespoke commands. Skein should keep the core set smaller and expose userland composition through durable primitives and REPL/config helpers.

## RFC-007.P4 Goals

- **RFC-007.G1:** Replace the global DAG with per-relation acyclicity.
- **RFC-007.G2:** Let userland name custom annotation relationships without editing core.
- **RFC-007.G3:** Replace `active` with a schema-level `state` enum so lifecycle meaning is explicit.
- **RFC-007.G4:** Promote supersession to a core operational lifecycle transition: replacement lineage is acyclic, and replaced strands have `state="replaced"`.
- **RFC-007.G5:** Keep readiness precise: only `state="active"` strands participate, and only active `depends-on` targets block.
- **RFC-007.G6:** Add relationship-aware query predicates so custom views and named queries can compose without private SQL.
- **RFC-007.G7:** Add relation-scoped traversal over declared acyclic relations.
- **RFC-007.G8:** Avoid duplicate replacement storage; replacement lookup comes from indexed `supersedes` edges, not a `replaced_by` column.

## RFC-007.P5 Non-goals

- **RFC-007.NG1:** No live migration for old databases or compatibility support for the old `active` field, `inactive_at`, `--active`, or `:active` query field.
- **RFC-007.NG2:** No `replaced_by` column or other duplicated replacement pointer.
- **RFC-007.NG3:** No runtime-configurable relation semantics registry.
- **RFC-007.NG4:** No broad beads-style command set. Supersession earns a core operation because it changes lifecycle and active dependency topology; duplicates/tracking/verification/etc. remain annotation conventions.
- **RFC-007.NG5:** No edge-attribute predicates, path-query grammar, arbitrary multi-hop filters in `list`/`ready`, or cycle-aware traversal over annotation relations in the initial scope.

## RFC-007.P6 Strand state

The core lifecycle field becomes `state` with values:

- **RFC-007.ST1 — `active`:** The strand participates in readiness and can block dependents through `depends-on`.
- **RFC-007.ST2 — `closed`:** The strand is intentionally no longer active and has no core replacement semantics.
- **RFC-007.ST3 — `replaced`:** The strand is no longer active because another strand supersedes it. The replacement is found by an incoming `supersedes` edge where `replacement --supersedes--> replaced`.

`state` is the source of lifecycle truth. `active`, `inactive_at`, and compatibility aliases are removed from the shipped schema and public APIs. Generic create/update surfaces may set `active` or `closed`; `replaced` is reserved for the supersession transaction so replacement lineage cannot be missing. State changes update the normal strand `updated_at`; this feature does not add a separate `replaced_by` or state timestamp field.

## RFC-007.P7 Operational relations

Skein ships three core operational relation names:

- **RFC-007.OP1 — `depends-on`:** `work --depends-on--> blocker`; declared acyclic; drives readiness.
- **RFC-007.OP2 — `parent-of`:** `parent --parent-of--> child`; declared acyclic; drives hierarchy traversal defaults.
- **RFC-007.OP3 — `supersedes`:** `replacement --supersedes--> replaced`; declared acyclic; records replacement lineage and is written by the core supersession operation.

These relations are tested batteries owned by Skein. Valid custom relation names remain storable; unless declared acyclic, they are annotations.

## RFC-007.P8 Supersession operation

Skein owns supersession as a core transaction because it changes lifecycle and preserves the active dependency DAG.

- **RFC-007.SUP1:** `supersede! old replacement` validates that both strands exist, they are distinct, `replacement.state="active"`, and `old.state` is not already `"replaced"`.
- **RFC-007.SUP2:** The transaction writes `replacement --supersedes--> old`, enforcing the `supersedes` DAG.
- **RFC-007.SUP3:** The transaction sets `old.state="replaced"`.
- **RFC-007.SUP4:** Strands that directly depended on `old` are rewired to depend on `replacement`: their `depends-on old` edge is replaced by `depends-on replacement` inside the same transaction, regardless of the dependent strand's current state.
- **RFC-007.SUP5:** If any inserted `depends-on` or `supersedes` edge would create a cycle, the whole supersession fails loudly and no partial lifecycle/edge changes commit.
- **RFC-007.SUP6:** Supersession does not copy `old`'s outgoing dependencies onto `replacement`; the replacement strand owns its own blockers.
- **RFC-007.SUP7:** Supersession does not freeze or burn the old strand. Reopening/replacing-policy beyond the transaction above is outside the initial scope.

## RFC-007.P9 Annotation catalog

Skein may ship a source-visible `skein.relations.alpha` catalog for common annotation vocabulary. The catalog is advisory data, not a storage allowlist. Initial annotation conventions include `related-to`, `duplicates`, `references`, `implements`, `verifies`, `tracks`, and `caused-by`. They carry no automatic readiness, lifecycle, symmetry, duplicate-resolution, or traversal behavior.

## RFC-007.P10 Query and traversal primitives

- **RFC-007.QT1 — Edge predicates:** The query DSL gains direct existential predicates `[:edge/out relation target-query]` and `[:edge/in relation source-query]`. Endpoint subqueries are strand-local in the initial scope and cannot themselves contain edge predicates.
- **RFC-007.QT2 — Ready expression:** Built-in readiness is equivalent to `[:and [:= :state "active"] [:not [:edge/out "depends-on" [:= :state "active"]]]]`.
- **RFC-007.QT3 — Relation-scoped traversal:** Runtime graph helpers traverse one declared acyclic relation at a time and fail loudly on annotation relations unless a future explicitly cycle-aware helper is designed.

## RFC-007.P11 Options

- **RFC-007.O1 — Status quo.** Keep closed edge types, global DAG, and boolean `active`. Rejected: defects remain and replacement lifecycle stays lossy.
- **RFC-007.O2 — Pure open relation model.** Open edge names and per-relation acyclicity, but leave supersession as annotation plus userland lifecycle. Rejected: replacement is important enough to deserve schema-level lifecycle truth and a tested transaction.
- **RFC-007.O3 — Beads-style broad core vocabulary.** Hardcode many relationship names and workflow commands. Rejected: too API-centric for Skein.
- **RFC-007.O4 — Small operational core plus annotation catalog.** Core owns `state`, `depends-on`, `parent-of`, `supersedes`, acyclicity declarations, edge predicates, and traversal. Annotation names remain open and advisory.

## RFC-007.P12 Recommendation

Choose **RFC-007.O4**.

This is the purest reasonable core: lifecycle state is explicit; replacement lineage is structural and acyclic; readiness remains small and predictable; and userland still has open annotation vocabulary. The engine owns the active graph invariants that agents rely on, while trusted config/libs own richer domain workflows.

## RFC-007.P13 Tenet change

TEN-005 should narrow from a whole-graph DAG to declared structural relations:

> **TEN-005: Declared structural relations are DAGs.** The engine guarantees each declared acyclic relation is independently acyclic, and every engine traversal walks exactly one such relation or is explicitly cycle-aware. Annotation edges carry no acyclicity guarantee and may form cycles; consumers must not assume whole-graph acyclicity.

## RFC-007.P14 Consequences

- **RFC-007.C1 — Strand Model:** Replace `active`/`inactive_at` with `state`; define `active`, `closed`, and `replaced`; remove old lifecycle aliases.
- **RFC-007.C2 — Edge schema:** Drop the closed `edge_type` check, add durable acyclic relation declarations, and declare `depends-on`, `parent-of`, and `supersedes` acyclic by default.
- **RFC-007.C3 — Cycle checks:** Scope cycle checks by relation; annotation relations may be cyclic except self-edges remain invalid.
- **RFC-007.C4 — Supersession:** Add a core transaction and helper/CLI surfaces for replacement without adding `replaced_by` storage.
- **RFC-007.C5 — Query/traversal:** Add direct edge predicates and relation-scoped traversal helpers.
- **RFC-007.C6 — CLI/API:** Replace `--active` with `--state`; generic create/update accepts only `active|closed`; list/query outputs can show `replaced`; add a simple supersession command/API; public outputs use `state` only.
- **RFC-007.C7 — Breaking change:** Existing databases and user code that rely on `active`, `inactive_at`, or old edge-type restrictions must be rewritten or recreated. No live migration code is part of this feature.

## RFC-007.P15 Outcome

Implemented by the archived `edge-relation-families` feature. The shipped system uses `state`, declared acyclic relation families, core supersession, direct edge predicates, relation-scoped traversal, and an advisory annotation catalog.
