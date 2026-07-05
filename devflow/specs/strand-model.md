# Strand Model

**Document ID:** `SPEC-001`
**Status:** Implemented
**Last Updated:** 2026-07-02
**Code:** `src/skein/core/db.clj`

## SPEC-001.P1 Purpose

The strand model defines the durable local data contract for the Skein graph: strand records, core state lifecycle, explicit burn deletion, open-ended JSON attributes, named strand-to-strand relations, durable acyclic relation declarations, and readiness semantics.

## SPEC-001.P2 Strand records

A strand has:

- `id` — generated unique text id.
- `title` — non-blank strand title.
- `state` — lifecycle state: `active`, `closed`, or `replaced`.
- `attributes` — userland JSON object stored as SQLite `TEXT`.
- `created_at` — set on insert.
- `updated_at` — changed on strand update.

`state="active"` is the only lifecycle state that participates in readiness and can block dependents. Generic create/update paths accept `active` and `closed`; `replaced` is reserved for the core supersession operation. There is no core `status`, `kind`, `type`, `outcome`, `reason`, retention flag, or final-status taxonomy. Worlds that need task-like outcomes, notes, pages, scratch concepts, or workflow categories store those concepts in attributes.

## SPEC-001.P3 Lifecycle and retention

Active strands have `state="active"`. Closing a strand sets `state="closed"`. Replacing a strand sets `state="replaced"` only through the supersession operation.

Burning a strand explicitly deletes the strand and all incident edges. Burn operations are raw deletion primitives intended for trusted workflows and userland composition.

## SPEC-001.P4 Attributes

Attributes are userland strand fields such as priority, owner, estimates, due dates, external references, categories, or outcomes. They are not core lifecycle metadata. Attribute values must encode to a JSON object; omitted or nil attributes normalize to `{}`.

Attribute map keys are Clojure keywords or strings. Keyword keys serialize to their full `namespace/name` text when a namespace is present, and JSON attribute reads keywordize object keys. Namespaced userland vocabularies such as `workflow/*` and `shuttle/*` therefore round-trip without collapsing distinct namespaces onto the same local name.

## SPEC-001.P5 Edges

Strand edges connect `from_strand_id` to `to_strand_id`, have an `edge_type` relation name, and have JSON object attributes. Relation names are valid strings matching `[a-z0-9][a-z0-9._/-]*`; valid names outside Skein's shipped operational batteries are accepted as userland annotation relations.

A `depends-on` edge from strand `A` to strand `B` means `A` is blocked by `B` while `B` is active. Shipped storage initialization declares `depends-on`, `parent-of`, and `supersedes` acyclic. Userland may declare additional acyclic relations before writing edges of that relation.

Self-edges fail for every relation. Writes to declared acyclic relations fail when they introduce a cycle within that same relation. Undeclared annotation relations may form non-self cycles.

The blessed `skein.api.relations.alpha` namespace ships a source-visible advisory catalog of this relation vocabulary for agents, config, and REPL workflows: `catalog` data plus `relation`, `operational-relations`, and `annotation-relations` lookups, each entry carrying the relation's family (operational battery vs behavior-free annotation), direction gloss, declared-acyclicity flag, and help text. It is documentation-only — not a storage allowlist or runtime relation-semantics registry — so relation names outside the catalog remain valid userland annotations. As an `skein.api.*.alpha` namespace it carries accretion-based compatibility within the subnamespace.

## SPEC-001.P6 Batch graph mutation

Core storage exposes a transactional batch graph mutation primitive for trusted workflows. A batch payload may contain top-level `:refs`, `:strands`, `:edges`, and `:burn` entries and commits atomically: every valid strand, edge, and burn mutation commits, or no graph mutation commits.

Batch-local refs are unqualified, non-blank keywords. Top-level `:refs` bind local refs to existing durable strand ids, and each existing strand id may be bound by at most one ref in a payload. Missing bound ids, duplicate aliases, invalid ref names, duplicate strand refs, combined update/burn of one ref, and references to unknown refs fail loudly before mutation.

A strand entry addressed to a bound ref updates that strand. Supported patch fields are `:title`, `:state`, and `:attributes`. A strand entry addressed to an unbound ref creates a strand, requires a non-blank `:title`, accepts optional `:state` and `:attributes`, and extends the final ref table with the generated durable id.

Burn entries address existing bound refs only. Burning a newly created ref, burning an unknown ref, or burning a ref patched in the same payload fails loudly. Successful burns physically delete strands and incident edges using normal burn semantics.

Batch edge entries are explicit operations addressed by local refs. The initial supported operation is `{:op :upsert ...}` with `:from`, `:to`, `:type`, and optional `:attributes`. Edge upsert inserts a missing edge or replaces attributes on an existing matching `(from, to, type)` edge. Edge upsert validates endpoint refs, target existence after create/update resolution, valid relation name, JSON object attributes, universal self-edge prohibition, declared-relation acyclicity, and that neither endpoint is burned in the same payload. Omitted edges do not imply deletion or replacement; unsupported edge operations fail loudly.

A batch result includes the final ref table and normalized summaries of created strands, updated before/after rows, burned ids with pre-delete rows, and edge outcomes.

## SPEC-001.P7 Readiness

A ready strand is a strand with `state="active"` and no direct `depends-on` dependency whose target strand has `state="active"`. Closed and replaced strands do not block readiness.

## SPEC-001.P8 Persistence

The `strands` table stores lifecycle state as a column and attributes as JSON `TEXT`. The `strand_edges` table stores relation names and edge attributes as JSON `TEXT`. The `acyclic_relations` table stores durable per-relation acyclicity declarations. The default weaver-owned database filename is `skein.sqlite`. JSONB assumptions are not part of this contract.

## SPEC-001.P9 Query fields

Queryable core fields include `:id`, `:title`, `:state`, `:created_at`, `:updated_at`, and attribute paths. Removed lifecycle fields such as `:active`, `:inactive_at`, `:status`, and `:final_at` are not accepted by the core query compiler.

## SPEC-001.P10 Deferred

Parent-scoped lifecycle rules, attribute-level metadata, per-attribute timestamps, category/outcome taxonomies, and durable audit/tombstone records for deletion are not part of the current model.
