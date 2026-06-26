# Strand Model

**Document ID:** `SPEC-001`
**Status:** Implemented
**Last Updated:** 2026-06-26
**Code:** `src/skein/db.clj`

## SPEC-001.P1 Purpose

The strand model defines the durable local data contract for the Skein graph: strand records, core active/ephemeral lifecycle and retention state, open-ended JSON attributes, typed strand-to-strand edges, and readiness semantics.

## SPEC-001.P2 Strand records

A strand has:

- `id` — generated unique text id.
- `title` — non-blank strand title.
- `active` — boolean liveness state. Active strands participate in readiness and can block dependents.
- `ephemeral` — boolean retention behavior. Ephemeral strands are persisted while active and deleted when deactivated.
- `attributes` — userland JSON object stored as SQLite `TEXT`.
- `created_at` — set on insert.
- `updated_at` — changed on strand update.
- `inactive_at` — set when a persistent strand becomes inactive; null while active.

`active` is the only core lifecycle state. There is no core `status`, `kind`, `type`, `outcome`, `reason`, or final-status taxonomy. Worlds that need task-like outcomes, notes, pages, or workflow categories store those concepts in attributes.

## SPEC-001.P3 Lifecycle and retention

Active persistent strands have `active=true`, `ephemeral=false`, and `inactive_at=null`. Deactivating a persistent strand sets `active=false` and `inactive_at` to the transition time. Reactivating a persistent strand sets `active=true` and clears `inactive_at`.

Ephemeral strands have `ephemeral=true` and are persisted only while active. Deactivating an ephemeral strand deletes the strand and all incident edges instead of retaining an inactive row.

Inactive ephemeral rows are invalid. Creating a strand with `active=false` and `ephemeral=true` fails loudly. Updating `active` and `ephemeral` in the same patch fails loudly; callers that want destructive delete-on-deactivate must first mark an active strand ephemeral, then deactivate it in a later patch.

## SPEC-001.P4 Attributes

Attributes are userland strand fields such as priority, owner, estimates, due dates, external references, categories, or outcomes. They are not core lifecycle metadata. Attribute values must encode to a JSON object; omitted or nil attributes normalize to `{}`.

## SPEC-001.P5 Edges

Strand edges connect `from_strand_id` to `to_strand_id`, have an `edge_type`, and have JSON object attributes. Allowed edge types are `depends-on`, `related-to`, `parent-of`, and `supersedes`.

A `depends-on` edge from strand `A` to strand `B` means `A` is blocked by `B` while `B` is active.

The edge graph is acyclic. Self-edges and writes that introduce a directed cycle fail.

## SPEC-001.P6 Readiness

A ready strand is an active strand with no direct `depends-on` dependency whose target strand is still active. Inactive strands do not block readiness.

## SPEC-001.P7 Persistence

The `strands` table stores lifecycle fields as columns and attributes as JSON `TEXT`. The `strand_edges` table stores typed relationships and edge attributes as JSON `TEXT`. The default weaver-owned database filename is `skein.sqlite`. JSONB assumptions are not part of this contract.

## SPEC-001.P8 Query fields

Queryable core fields include `:id`, `:title`, `:active`, `:ephemeral`, `:inactive_at`, `:created_at`, `:updated_at`, and attribute paths. The removed `:status` and `:final_at` fields and old status values are not accepted by the core query compiler.

## SPEC-001.P9 Deferred

Attribute-level metadata, per-attribute timestamps, category/outcome taxonomies, and durable audit/tombstone records for ephemeral deletion are not part of the current model.
