# Batch Task Refs Proposal

**Document ID:** `PROP-001`
**Last Updated:** 2026-06-24
**Related RFCs:** [RFC-001 Batch task refs](../../rfcs/2026-06-24-batch-task-refs.md)
**Related root specs:** [Task Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md)
**Configuration identification:** `PROP-001` is the proposal document for this feature. Every nested point ID is prefixed with `PROP-001`.

## PROP-001.P1 Problem

Database-owned task ids prevent callers from knowing durable ids before creation. Sequential scripts can create a task, capture its id, and link later work, but creating a small task DAG is unnecessarily verbose when several new tasks need to reference each other before any durable ids exist.

## PROP-001.P2 Goals

- **PROP-001.G1:** Let agents create a small DAG of tasks in one CLI call using batch-local refs.
- **PROP-001.G2:** Preserve database-owned ids as the only durable task identifiers.
- **PROP-001.G3:** Keep the batch API minimal, structured, and easy to drive from scripts.
- **PROP-001.G4:** Fail loudly and atomically when batch input is malformed or references cannot be resolved.

## PROP-001.P3 Non-goals

- **PROP-001.NG1:** Do not add durable aliases, user-controlled ids, or alternate task identity.
- **PROP-001.NG2:** Do not define a general import/export format beyond batch task creation with edges.
- **PROP-001.NG3:** Do not add file-specific input flags; callers can pipe or redirect files into stdin.
- **PROP-001.NG4:** Do not change existing single-task `add` or `link` command behavior.
- **PROP-001.NG5:** Do not add REPL batch helpers in this feature unless implementation uncovers a small shared helper that is worth exposing later.

## PROP-001.P4 Proposed scope

- **PROP-001.S1:** Add a CLI batch creation path that reads one EDN vector of task maps from standard input.
- **PROP-001.S2:** Allow each task map to include an optional symbolic `:ref` that exists only within the batch input and command result.
- **PROP-001.S3:** Allow each task map to include generic `:edges` declarations with `:type`, `:to`, and optional `:attributes`.
- **PROP-001.S4:** Resolve symbolic edge targets through batch-local refs and string edge targets through existing durable task ids.
- **PROP-001.S5:** Return machine-readable output that includes created task rows and a ref-to-generated-id map.
- **PROP-001.S6:** Create the whole batch atomically; no tasks or edges from the batch remain if any validation or write fails.

## PROP-001.P5 Open questions

- **PROP-001.Q1:** None. RFC-001 and council review settled the direction; spec deltas own the remaining exact contracts.
