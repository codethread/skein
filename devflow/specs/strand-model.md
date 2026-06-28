# Strand Model

**Document ID:** `SPEC-001`
**Status:** Implemented
**Last Updated:** 2026-06-28
**Code:** `src/skein/db.clj`

## SPEC-001.P1 Purpose

The strand model defines the durable local data contract for the Skein graph: strand records, core active lifecycle state, explicit burn deletion, open-ended JSON attributes, typed strand-to-strand edges, and readiness semantics.

## SPEC-001.P2 Strand records

A strand has:

- `id` — generated unique text id.
- `title` — non-blank strand title.
- `active` — boolean liveness state. Active strands participate in readiness and can block dependents.
- `attributes` — userland JSON object stored as SQLite `TEXT`.
- `created_at` — set on insert.
- `updated_at` — changed on strand update.
- `inactive_at` — set when a strand becomes inactive; null while active.

`active` is the only core lifecycle state. There is no core `status`, `kind`, `type`, `outcome`, `reason`, retention flag, or final-status taxonomy. Worlds that need task-like outcomes, notes, pages, scratch concepts, or workflow categories store those concepts in attributes.

## SPEC-001.P3 Lifecycle and retention

Active strands have `active=true` and `inactive_at=null`. Deactivating a strand sets `active=false` and `inactive_at` to the transition time. Reactivating a strand sets `active=true` and clears `inactive_at`.

Burning a strand explicitly deletes the strand and all incident edges. Burn operations are raw deletion primitives intended for trusted workflows and userland composition.

## SPEC-001.P4 Attributes

Attributes are userland strand fields such as priority, owner, estimates, due dates, external references, categories, or outcomes. They are not core lifecycle metadata. Attribute values must encode to a JSON object; omitted or nil attributes normalize to `{}`.

## SPEC-001.P5 Edges

Strand edges connect `from_strand_id` to `to_strand_id`, have an `edge_type`, and have JSON object attributes. Allowed edge types are `depends-on`, `related-to`, `parent-of`, and `supersedes`.

A `depends-on` edge from strand `A` to strand `B` means `A` is blocked by `B` while `B` is active.

The edge graph is acyclic. Self-edges and writes that introduce a directed cycle fail.

## SPEC-001.P6 Batch graph mutation

Core storage exposes a transactional batch graph mutation primitive for trusted workflows. A batch payload may contain top-level `:refs`, `:strands`, `:edges`, and `:burn` entries and commits atomically: every valid strand, edge, and burn mutation commits, or no graph mutation commits.

Batch-local refs are unqualified, non-blank keywords. Top-level `:refs` bind local refs to existing durable strand ids, and each existing strand id may be bound by at most one ref in a payload. Missing bound ids, duplicate aliases, invalid ref names, duplicate strand refs, combined update/burn of one ref, and references to unknown refs fail loudly before mutation.

A strand entry addressed to a bound ref updates that strand. Supported patch fields are `:title`, `:active`, and `:attributes`. A strand entry addressed to an unbound ref creates a strand, requires a non-blank `:title`, accepts optional `:active` and `:attributes`, and extends the final ref table with the generated durable id.

Burn entries address existing bound refs only. Burning a newly created ref, burning an unknown ref, or burning a ref patched in the same payload fails loudly. Successful burns physically delete strands and incident edges using normal burn semantics.

Batch edge entries are explicit operations addressed by local refs. The initial supported operation is `{:op :upsert ...}` with `:from`, `:to`, `:type`, and optional `:attributes`. Edge upsert inserts a missing edge or replaces attributes on an existing matching `(from, to, type)` edge. Edge upsert validates endpoint refs, target existence after create/update resolution, allowed edge type, JSON object attributes, self-edge prohibition, final graph acyclicity, and that neither endpoint is burned in the same payload. Omitted edges do not imply deletion or replacement; unsupported edge operations fail loudly.

A batch result includes the final ref table and normalized summaries of created strands, updated before/after rows, burned ids with pre-delete rows, and edge outcomes.

## SPEC-001.P7 Readiness

A ready strand is an active strand with no direct `depends-on` dependency whose target strand is still active. Inactive strands do not block readiness.

## SPEC-001.P8 Persistence

The `strands` table stores lifecycle fields as columns and attributes as JSON `TEXT`. The `strand_edges` table stores typed relationships and edge attributes as JSON `TEXT`. The default weaver-owned database filename is `skein.sqlite`. JSONB assumptions are not part of this contract.

## SPEC-001.P9 Query fields

Queryable core fields include `:id`, `:title`, `:active`, `:inactive_at`, `:created_at`, `:updated_at`, and attribute paths. The removed `:status` and `:final_at` fields and old status values are not accepted by the core query compiler.

## SPEC-001.P10 Deferred

Parent-scoped lifecycle rules, attribute-level metadata, per-attribute timestamps, category/outcome taxonomies, and durable audit/tombstone records for deletion are not part of the current model.
