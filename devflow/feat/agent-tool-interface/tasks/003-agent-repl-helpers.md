# Task 3: Add agent REPL helpers

**Document ID:** `TASK-003`
**Configuration identification:** `TASK-003` is the third task in `agent-tool-interface`. Every nested point ID is prefixed with `TASK-003`.

## TASK-003.P1 Scope

Type: AFK

## TASK-003.P2 Must implement exactly

- **TASK-003.MI1:** Add `src/todo/repl.clj` as the namespace agents can require for interactive exploration.
- **TASK-003.MI2:** Provide a compact function vocabulary wrapping the shared DB API with these exact public helper names: `open!`, `init!`, `task!`, `depends!`, `edge!`, `done!`, `tasks`, `task`, `deps`, `transitive-deps`, `blocking`, `ready`, `by-attr`, and `graph`.
- **TASK-003.MI3:** Make the default REPL flow concise: `(require '[todo.repl :refer :all])`, `(open! "agent.sqlite")`, then helper calls without manually passing a datasource every time.
- **TASK-003.MI4:** Keep datasource state explicit enough to avoid surprising hidden fallback behavior; if no database is opened, helper calls must fail loudly with a useful message.
- **TASK-003.MI5:** Ensure the existing `:repl` alias remains warning-free with the native-access JVM option.

## TASK-003.P3 Done when

- **TASK-003.DW1:** A scripted Clojure invocation can require `todo.repl`, open a disposable database, create tasks and dependencies, mark status, and query `ready` successfully.
- **TASK-003.DW2:** Calling a helper that requires a database before `open!` produces a clear failure rather than silently using an unintended default.
- **TASK-003.DW3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` still passes.

## TASK-003.P4 Out of scope

- **TASK-003.OS1:** Do not build a custom nREPL server or editor integration.
- **TASK-003.OS2:** Do not make REPL helpers parse CLI-style strings; Clojure data is the REPL interface.
- **TASK-003.OS3:** Do not introduce global mutable behavior beyond the active datasource needed for concise interactive use.

## TASK-003.P5 References

- **TASK-003.REF1:** Proposal: `devflow/feat/agent-tool-interface/proposal.md`.
- **TASK-003.REF2:** Plan: `devflow/feat/agent-tool-interface/agent-tool-interface.plan.md`, especially `PLAN-001.PH3`.
- **TASK-003.REF3:** Shared DB operations from Task 1 in `src/todo/db.clj`.
- **TASK-003.REF4:** Current REPL alias in `deps.edn`.
