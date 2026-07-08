# Task Model delta for batch task refs

**Document ID:** `DELTA-002` **Root spec:** [task-model.md](../../../specs/strand-model.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-24 **Configuration identification:** `DELTA-002` is the task-model delta for this feature. Every nested point ID is prefixed with `DELTA-002`.

## DELTA-002.P1 Summary

Batch-local refs are introduced as an interface-level creation aid. They do not change durable task identity, schema, edge semantics, readiness rules, or persisted attributes.

## DELTA-002.P2 Contract changes

- **DELTA-002.CC1:** A batch-local ref is an ephemeral identifier that may be accepted by an interface while creating multiple tasks in one operation.
- **DELTA-002.CC2:** Batch-local refs are not task ids and must not be stored in the `tasks` table, `task_edges` table, task attributes, or edge attributes unless a caller explicitly includes separate userland metadata unrelated to the reserved batch `:ref` field.
- **DELTA-002.CC3:** After batch creation, all persisted edges must use generated durable task ids in `from_task_id` and `to_task_id`.
- **DELTA-002.CC4:** Database-owned ids remain the only durable task identifiers.
- **DELTA-002.CC5:** Batch creation does not add acyclicity validation; dependency cycles remain outside the current task-model contract.

## DELTA-002.P3 Design decisions

### DELTA-002.D1 Ephemeral refs only

- **Decision:** Treat batch-local refs as an interface convenience, not a model-level identity feature.
- **Rationale:** This solves pre-creation graph references without reintroducing caller-owned ids or durable aliases.
- **Rejected:** Persisted aliases and user-selected durable ids remain rejected for the alpha task model.

## DELTA-002.P4 Open questions

- **DELTA-002.Q1:** None.
