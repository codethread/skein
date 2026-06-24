# CLI Surface

**Document ID:** `SPEC-002`
**Status:** Implemented
**Last Updated:** 2026-06-24
**Related RFCs:** [RFC-002 Task Query DSL](../rfcs/2026-06-24-task-query-dsl.md)
**Code:** `src/todo/cli.clj`

## SPEC-002.P1 Purpose

The CLI is the primary scripted interface for coding agents. It exposes a deliberately small task surface: initialize storage, create tasks, update tasks, inspect tasks, list tasks, and ask for ready work.

## SPEC-002.P2 Interface

Entrypoint:

```text
clojure -M:todo [--db <path>] [--format human|edn|json] <command> [args]
```

Commands:

```text
init
add <title> [--status todo|done|failed|cancelled] [--attr key=value ...]
update <id> [--title title] [--status todo|done|failed|cancelled] [--attr key=value ...] [--edge edge-type:to-id ...]
show <id>
list
ready
daemon start
daemon stop
daemon status
```

## SPEC-002.P3 Contracts

- **SPEC-002.C1:** `--db` selects the daemon/database runtime identity and defaults to `todo.sqlite`; task commands require a matching reachable daemon and do not silently open SQLite directly.
- **SPEC-002.C2:** `--format` accepts `human`, `edn`, or `json` and defaults to `human`.
- **SPEC-002.C3:** `add` creates a task with generated id, first-class status, timestamps, and string-valued CLI attributes.
- **SPEC-002.C4:** `update` patches title, status, attributes, and task edges for one existing task.
- **SPEC-002.C5:** `--edge edge-type:to-id` creates or updates an outgoing edge from the updated task to the target task.
- **SPEC-002.C6:** `show`, `list`, and `ready` return task rows with normalized `attributes` in EDN/JSON output.
- **SPEC-002.C7:** `ready` returns non-final tasks whose direct `depends-on` dependencies are all final.
- **SPEC-002.C8:** Malformed options, invalid statuses, invalid edge targets, unknown commands, daemon transport/identity failures, and database/domain errors fail non-zero.
- **SPEC-002.C9:** `daemon start`, `daemon stop`, and `daemon status` manage the local daemon lifecycle for the selected database; `daemon status` respects `--format` and reports health, canonical database path, pid, endpoint, and daemon identity.

## SPEC-002.P4 Deferred

`by-attr`, bespoke dependency inspection commands, `link`, `done`, and `batch` are not part of the stripped public CLI. Future filtering for `list` and `ready` is tracked by RFC-002.
