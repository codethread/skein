# DB-owned Task IDs Proposal

**Document ID:** `PROP-001` **Last Updated:** 2026-06-24 **Related RFCs:** None for core id fix; batch refs are tracked separately in [RFC-001](../../rfcs/2026-06-24-batch-task-refs.md) **Related root specs:** [Task Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md) **Related tenets:** [TENETS](../../TENETS.md) **Configuration identification:** `PROP-001` is the first active feature proposal in this repository. Every nested point ID is prefixed with `PROP-001`.

## PROP-001.P1 Problem

Task creation currently requires callers to choose the durable task id. Because `add-task!` and the CLI `add` path upsert on id conflict, two users or agents can accidentally pick the same id and overwrite task content. Task identity should be owned by the database/application and returned to callers after creation.

## PROP-001.P2 Goals

- **PROP-001.G1:** Generate short, stable, collision-resistant task ids during task creation.
- **PROP-001.G2:** Return the generated id from DB, CLI, and REPL creation paths so callers can use it in edge commands.
- **PROP-001.G3:** Remove implicit task upsert from normal task creation so id conflicts fail or are retried internally instead of overwriting user work.
- **PROP-001.G4:** Preserve existing graph operations over durable ids once they have been returned.

## PROP-001.P3 Non-goals

- **PROP-001.NG1:** Batch creation and batch-local `:ref` aliases are out of scope; see [RFC-001](../../rfcs/2026-06-24-batch-task-refs.md).
- **PROP-001.NG2:** This feature does not add user-controlled global aliases.
- **PROP-001.NG3:** This feature does not migrate existing databases beyond remaining compatible with existing text id rows.

## PROP-001.P4 Proposed scope

- **PROP-001.S1:** Change task creation so callers provide task content, not the durable id.
- **PROP-001.S2:** Generated task ids are short strings suitable for CLI use and remain stable after creation.
- **PROP-001.S3:** `add "Title"` returns the created task row/id in machine-readable output and prints the id in human output.
- **PROP-001.S4:** Creation-time edge shorthand uses repeated `--link <edge-type>:<to-id>` values, where the new task is the edge source and the referenced existing task id is the edge target.
- **PROP-001.S5:** Existing query, update, done, and link operations continue to address tasks by durable id.
- **PROP-001.S6:** Generated ids are constrained to a colon-free shell-safe alphabet so `--link <edge-type>:<to-id>` can parse loudly and unambiguously.

## PROP-001.P5 Open questions

- **PROP-001.Q1:** None for current planning; batch-local refs remain separate RFC scope.
