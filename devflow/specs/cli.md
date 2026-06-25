# CLI Surface

**Document ID:** `SPEC-002`
**Status:** Implemented
**Last Updated:** 2026-06-25
**Related RFCs:** [RFC-002 Task Query DSL](../rfcs/2026-06-24-task-query-dsl.md)
**Code:** `src/todo/cli.clj`

## SPEC-002.P1 Purpose

The CLI is the primary scripted interface for coding agents. It exposes a deliberately small task surface: initialize storage, create tasks, update tasks, inspect tasks, list tasks, ask for ready work, and manage the local daemon runtime.

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
list [--where EDN | --query name] [--param key=value ...]
ready [--where EDN | --query name] [--param key=value ...]
daemon start [--config <path>]
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
- **SPEC-002.C8:** `list` and `ready` accept an optional EDN query expression with `--where`.
- **SPEC-002.C9:** `list` and `ready` accept an optional named query from daemon memory with `--query` and repeated string-valued `--param key=value` runtime parameters.
- **SPEC-002.C10:** `--where` and `--query` are mutually exclusive; malformed query expressions, missing named queries, or missing parameters fail non-zero.
- **SPEC-002.C11:** The CLI has no query registry mutation/listing commands and does not accept `--query-file`; query loading is a trusted daemon config or REPL workflow, and registry contents last only for the daemon lifetime.
- **SPEC-002.C12:** Malformed options, invalid statuses, invalid edge targets, unknown commands, daemon transport/identity failures, and database/domain errors fail non-zero.
- **SPEC-002.C13:** `daemon start`, `daemon stop`, and `daemon status` manage the local daemon lifecycle for the selected database; `daemon status` respects `--format` and reports health, canonical database path, pid, endpoint, and daemon identity. `daemon start --config <path>` loads a trusted startup EDN config supporting only `{:load-files ["trusted.clj"]}` and fails before publishing runtime metadata if config or trusted code loading fails.

## SPEC-002.P4 Deferred

`by-attr`, bespoke dependency inspection commands, `link`, `done`, and `batch` are not part of the stripped public CLI.
