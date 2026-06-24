# Agent Tool Interface Plan

**Document ID:** `PLAN-001`
**Feature:** `agent-tool-interface`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** None
**Root specs:** None yet
**Feature specs:** None
**Status:** Shipped
**Last Updated:** 2026-06-24
**Configuration identification:** `PLAN-001` is the first plan in this repository. Every nested point ID is prefixed with `PLAN-001`.

## PLAN-001.P1 Goal and scope

Deliver the next MVP increment from [PROP-001](./proposal.md): make the todo graph usable by coding agents through deterministic CLI commands and a compact REPL namespace, while keeping the existing SQLite/next.jdbc/JSON1 storage model intact.

## PLAN-001.P2 Approach

- **PLAN-001.A1:** Keep `todo.db` as the storage/query boundary and add missing domain operations there only when both CLI and REPL need them.
- **PLAN-001.A2:** Add a new `todo.cli` namespace as the scriptable entrypoint with subcommands, explicit database selection, repeatable attribute input, transitive dependency access, and machine-readable output modes.
- **PLAN-001.A3:** Add a new `todo.repl` namespace that wraps datasource management and common operations behind a small set of agent-friendly functions.
- **PLAN-001.A4:** Prefer simple parser/formatter code over introducing a full CLI framework until command complexity requires it.
- **PLAN-001.A5:** Keep the existing TUI available, but document CLI and REPL as the primary interfaces for agents.

## PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-001.AA1 | `src/todo/db.clj` | Add shared operations for status updates, attribute queries, reverse dependencies, ready tasks, and transitive dependency traversal as needed by the CLI/REPL. |
| PLAN-001.AA2 | `src/todo/cli.clj` | New CLI entrypoint for agent automation. |
| PLAN-001.AA3 | `src/todo/repl.clj` | New convenience namespace for interactive agent use. |
| PLAN-001.AA4 | `deps.edn` | Add an agent CLI alias and keep REPL JVM opts warning-free. |
| PLAN-001.AA5 | `dev/todo/smoke.clj` and/or new smoke files | Demonstrate CLI and REPL-facing workflows. |
| PLAN-001.AA6 | `README.md` | Add an agent quickstart with command and REPL vocabulary. |

## PLAN-001.P4 Contract and migration impact

- **PLAN-001.CM1:** CLI command names and output formats become the initial agent-facing contract for the MVP.
- **PLAN-001.CM2:** REPL helper function names become a lightweight interactive contract for agents.
- **PLAN-001.CM3:** The database schema remains compatible with the current MVP; any new status behavior should be represented in JSON attributes unless a later feature justifies schema changes.

## PLAN-001.P5 Implementation phases

### PLAN-001.PH1 Shared domain operations

Outcome: The database layer exposes enough operations for task status, attribute querying, dependency views, reverse dependency views, and ready-task selection without coupling callers to SQL.

### PLAN-001.PH2 Agent CLI

Outcome: Agents can initialize a database and perform core task/edge/query/status operations from shell commands with predictable EDN/JSON/human output.

### PLAN-001.PH3 Agent REPL namespace

Outcome: Agents can require one namespace, open a database, and use a small stable function vocabulary for common interactive operations.

### PLAN-001.PH4 Documentation and smoke validation

Outcome: README and smoke coverage demonstrate the supported agent workflows end to end.

## PLAN-001.P6 Validation strategy

- **PLAN-001.V1:** Run `clojure -M:smoke` to ensure existing behavior still works.
- **PLAN-001.V2:** Run representative `clojure -M:todo ...` commands against a disposable database and verify machine-readable output for at least one query.
- **PLAN-001.V3:** Run a REPL-oriented smoke namespace or scripted REPL API calls to prove the convenience functions compose.

## PLAN-001.P7 Risks and open questions

- **PLAN-001.R1:** A hand-rolled CLI parser can become brittle; mitigate by keeping syntax small and covered by smoke examples.
- **PLAN-001.R2:** Status stored in attributes is flexible but less explicit than a column; this is acceptable for the MVP and keeps schema churn out of scope.

## PLAN-001.P8 Task context

- **PLAN-001.TC1:** Existing code anchors are `src/todo/db.clj`, `src/todo/app.clj`, `dev/todo/smoke.clj`, `deps.edn`, and `README.md`.
- **PLAN-001.TC2:** Task slices should keep the app runnable after each completed task and should not implement schema validation, daemon/MCP behavior, or a replacement TUI.
- **PLAN-001.TC3:** Prefer EDN output for Clojure-native agent workflows and JSON output for general shell automation.

## PLAN-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-001.DN1 Plan sanity check — 2026-06-24

- Reviewed against the current codebase shape after reading `todo.db`, `todo.app`, `deps.edn`, and the smoke namespace. The scope is sliceable into shared DB operations, CLI, REPL wrapper, and docs/smoke validation without unresolved product decisions.

### PLAN-001.DN2 Task queue review fixes — 2026-06-24

- Clarified the REPL helper vocabulary to exact names, added CLI exposure for transitive dependencies, and required CLI attribute authoring so attribute queries are not read-only from the primary agent interface.

### PLAN-001.DN3 Task 1 implementation — 2026-06-24

- Added shared `todo.db` operations for status/attribute updates, arbitrary top-level JSON attribute lookup, reverse dependency lookup, ready-task selection, and transitive `depends-on` traversal while keeping status in `tasks.attributes`.
- Smoke coverage now asserts single-task lookup, JSON attribute patching, readiness changes after marking a dependency done, arbitrary attribute queries, reverse blockers, and transitive dependencies.

### PLAN-001.DN4 Task 2 implementation — 2026-06-24

- Added `todo.cli` with the small command vocabulary from Task 2, global `--db` and `--format` options, repeatable string `--attr key=value` parsing, and EDN/JSON/human query output.
- Simplified the MVP CLI after review: attributes are plain strings at the shell boundary, while query output still expands stored JSON attribute columns for readable EDN/JSON results.

### PLAN-001.DN5 Task 3 implementation — 2026-06-24

- Added `todo.repl` as the compact interactive namespace with one active datasource opened by `open!`; database helpers fail loudly with a clear `ExceptionInfo` if called before opening a database.
- REPL helper results normalize JSON-bearing columns to Clojure data so interactive callers do not need to know the storage encoding.
- Smoke validation covers the exact public helper vocabulary, the concise open/init/task/dependency/status/ready flow, and the pre-open failure path.
- Follow-up YAGNI pass simplified `todo.repl` to exact JSON columns and removed broad wrapper smoke assertions that duplicated the DB smoke coverage.

### PLAN-001.DN6 Task 4 implementation — 2026-06-24

- README now treats the CLI as the primary agent interface, with the REPL helpers as the interactive interface and the TUI as secondary.
- Smoke validation now starts with an agent-facing subprocess CLI flow that creates tasks, links `depends-on` edges, marks status, and queries both JSON1 attributes and graph relationships before running the broader DB and REPL demonstrations.
- Representative README CLI commands passed against a disposable `/tmp` SQLite database; generated smoke databases and `.repl` sidecars remain covered by the gitignore patterns.
- YAGNI follow-up removed duplicate in-process CLI smoke coverage after the subprocess CLI path covered the published agent boundary directly.

### PLAN-001.DN7 Final shipped scope — 2026-06-24

- Shipped the full planned MVP scope: shared DB operations, `todo.cli` command surface, `todo.repl` helper namespace, README agent quickstart, and smoke coverage for CLI/REPL/JSON1/graph workflows.
- No planned task scope was cut. At archive time, no feature-local specs required promotion because the stable contracts were documented in README for this MVP rather than staged as root specs.

### PLAN-001.DN8 Post-archive spec consolidation — 2026-06-24

- Current shipped contracts were later consolidated into root specs under `devflow/specs/`: task model, CLI surface, and REPL API. Treat those root specs as canonical over this historical archive note.
