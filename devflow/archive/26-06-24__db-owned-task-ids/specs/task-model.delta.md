# Task Model Delta: DB-owned Task IDs

**Document ID:** `DELTA-001` **Status:** Merged **Target root spec:** [Task Model](../../../specs/strand-model.md) **Feature:** `db-owned-task-ids` **Last Updated:** 2026-06-24 **Configuration identification:** `DELTA-001` is the task-model delta for `db-owned-task-ids`. Every nested point ID is prefixed with `DELTA-001`.

## DELTA-001.P1 Summary

This feature changes task identity from caller-owned ids supplied during creation to application-owned short ids returned after creation.

## DELTA-001.P2 Contract changes

- **DELTA-001.C1:** Replace the current contract that callers provide a task `id` during creation with a contract where creation generates a durable id.
- **DELTA-001.C2:** Generated ids are short, stable text identifiers constrained to a shell-safe, colon-free alphabet.
- **DELTA-001.C3:** Keep `tasks.id TEXT PRIMARY KEY` as the durable identifier column after creation.
- **DELTA-001.C4:** Replace task creation upsert semantics with insert-only semantics. Generated-id collisions are retried internally and collision exhaustion fails loudly.
- **DELTA-001.C5:** Existing graph, query, status, and edge operations continue to address tasks by durable id after creation.

## DELTA-001.P3 Alpha compatibility position

- **DELTA-001.A1:** Per [TENETS](../../../TENETS.md), this alpha project does not preserve caller-owned task creation as a compatibility mode. Existing rows may remain in databases, but the shipped creation contract should drop old `add-task!` id ownership assumptions.
