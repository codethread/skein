# Task 1: Add shared agent query operations

**Document ID:** `TASK-001`
**Configuration identification:** `TASK-001` is the first task in `agent-tool-interface`. Every nested point ID is prefixed with `TASK-001`.

## TASK-001.P1 Scope

Type: AFK

## TASK-001.P2 Must implement exactly

- **TASK-001.MI1:** Extend `src/todo/db.clj` with shared functions needed by both CLI and REPL callers: update task attributes/status, get one task by id, query by arbitrary JSON attribute key/value, reverse dependencies/blocking tasks, ready tasks whose direct `depends-on` dependencies are done, and transitive dependencies for a task.
- **TASK-001.MI2:** Preserve the existing schema shape: task status must be stored in `tasks.attributes` rather than a new column.
- **TASK-001.MI3:** Keep JSON stored as valid SQLite JSON text and continue to use JSON1 functions for attribute reads/updates where appropriate.
- **TASK-001.MI4:** Ensure existing public functions used by `todo.app` and `todo.smoke` keep working.
- **TASK-001.MI5:** Add focused smoke or test coverage in the existing project style if needed to prove the new DB functions work before CLI/REPL wrappers exist.

## TASK-001.P3 Done when

- **TASK-001.DW1:** A caller can mark a task done through the DB API and `ready tasks` excludes tasks blocked by incomplete dependencies.
- **TASK-001.DW2:** A caller can find tasks by a non-hard-coded JSON attribute key/value.
- **TASK-001.DW3:** A caller can retrieve direct reverse blockers and transitive dependencies for a task.
- **TASK-001.DW4:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes.

## TASK-001.P4 Out of scope

- **TASK-001.OS1:** Do not add CLI parsing or REPL convenience wrappers in this task.
- **TASK-001.OS2:** Do not add schemas for userland attributes.
- **TASK-001.OS3:** Do not change the SQLite table definitions unless a DB operation is impossible without doing so.

## TASK-001.P5 References

- **TASK-001.REF1:** Proposal: `devflow/feat/agent-tool-interface/proposal.md`.
- **TASK-001.REF2:** Plan: `devflow/feat/agent-tool-interface/agent-tool-interface.plan.md`, especially `PLAN-001.PH1`.
- **TASK-001.REF3:** Existing DB implementation: `src/todo/db.clj`.
- **TASK-001.REF4:** Existing smoke demo: `dev/todo/smoke.clj`.
