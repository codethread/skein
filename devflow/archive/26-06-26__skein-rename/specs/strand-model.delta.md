# Strand Model delta for skein-rename

**Document ID:** `SR-DELTA-001`
**Root spec:** [strand-model.md](../../../specs/strand-model.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-06-26

## SR-DELTA-001.P1 Summary

The task model becomes the strand model. The durable unit is renamed from task to strand and the task-oriented status enum is replaced by core liveness and retention fields: `active`, `inactive_at`, and `ephemeral`. Domain subtypes such as task, note, page, done, failed, or cancelled move entirely to user attributes.

## SR-DELTA-001.P2 Contract changes

- **SR-DELTA-001.CC1:** Rename the root spec from Task Model to Strand Model and promote `devflow/specs/task-model.md` to `devflow/specs/strand-model.md` when the feature ships.
- **SR-DELTA-001.CC2:** A strand record has generated `id`, non-blank `title`, boolean `active`, boolean `ephemeral`, JSON object `attributes`, `created_at`, `updated_at`, and nullable `inactive_at`.
- **SR-DELTA-001.CC3:** `active` is the only core lifecycle state. Active strands are live graph participants and can block readiness through `depends-on`. Inactive strands do not block readiness.
- **SR-DELTA-001.CC4:** `inactive_at` is null while a persistent strand is active. Deactivating a persistent strand sets `active=false` and `inactive_at` to the transition time. Reactivating a persistent strand sets `active=true` and clears `inactive_at`.
- **SR-DELTA-001.CC5:** `ephemeral` is core retention behavior, not a user attribute. Ephemeral strands are persisted while active. Deactivating an ephemeral strand deletes the strand and all incident edges instead of retaining an inactive row.
- **SR-DELTA-001.CC5a:** An inactive ephemeral strand is not a valid persisted state. Creating a strand with `active=false` and `ephemeral=true` fails loudly. Updating `active` and `ephemeral` in the same patch fails loudly; callers that want destructive delete-on-deactivate must mark the active strand ephemeral before a later deactivation patch.
- **SR-DELTA-001.CC6:** There is no core `status`, `kind`, `type`, `outcome`, `reason`, or final-status taxonomy. Worlds that need those concepts store them in JSON attributes.
- **SR-DELTA-001.CC7:** Rename storage from `tasks` to `strands`, `task_edges` to `strand_edges`, task id edge columns to strand id edge columns, and default database filename from `tasks.sqlite` to `skein.sqlite`.
- **SR-DELTA-001.CC8:** Edge types remain `depends-on`, `related-to`, `parent-of`, and `supersedes`. The directed acyclic invariant remains unchanged.
- **SR-DELTA-001.CC9:** Readiness becomes: a ready strand is an active strand with no direct `depends-on` dependency whose target strand is still active.
- **SR-DELTA-001.CC10:** Queryable core fields replace `:status` and `:final_at` with `:active`, `:ephemeral`, and `:inactive_at`. Existing status values are not accepted by the core query compiler after the feature ships.

## SR-DELTA-001.P3 Design decisions

### SR-DELTA-001.D1 Core liveness over task statuses

- **Decision:** Replace `todo`/`done`/`failed`/`cancelled` with `active`.
- **Rationale:** Skein stores neutral strands. The core only needs to know whether a strand participates in readiness and default live filtering.
- **Rejected:** Keeping status for compatibility, adding a smaller outcome enum, or adding core `kind` because those preserve task-like domain assumptions.

### SR-DELTA-001.D2 Ephemeral means delete on deactivation

- **Decision:** `ephemeral=true` strands are persisted while active and deleted when deactivated.
- **Rationale:** This supports scratch/planning strands that should disappear after completion while keeping active ephemeral strands queryable and dependency-aware.
- **Rejected:** `transient`, because it conflicts with Clojure terminology and can imply never persisted; `retain`, because it inverts the default and is less direct at the CLI for the common scratch case.

### SR-DELTA-001.D3 Deactivation may mutate graph topology

- **Decision:** Deleting an ephemeral strand removes incident edges via the same deletion semantics as any strand delete.
- **Rationale:** Once the strand is intentionally non-retained, dangling edges would be invalid. Agents must treat deactivating ephemeral strands as destructive graph mutation.
- **Rejected:** Retaining tombstones or converting edges to audit records because that would reintroduce persistence for data marked ephemeral.

## SR-DELTA-001.P4 Open questions

- **SR-DELTA-001.Q1:** None for MVP.
