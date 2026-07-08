# Verify Daemon via tmux

**Document ID:** `TASK-006` **Feature:** `daemon-runtime` **Plan:** [../daemon-runtime.plan.md](../daemon-runtime.plan.md) **Specs:** [../specs/daemon-runtime.md](../specs/daemon-runtime.md)

## TASK-006.P1 Scope

Type: AFK

Perform the required final manual-style verification with a daemon held in tmux and a separate client process using the new daemon connection path. This task is validation and cleanup only; do not make broad implementation changes here except fixing failures found by the verification.

## TASK-006.P2 Required work

- **TASK-006.W1:** Start a daemon in a tmux session named with the `agent-` prefix, e.g. `agent-todo-daemon-smoke`, from the repo root.
- **TASK-006.W2:** From a separate process, use CLI commands against a disposable DB to initialize, add tasks, update dependencies/status, show/list/ready data, and confirm actual task rows are visible.
- **TASK-006.W3:** From a separate REPL/dev invocation, load `dev/user.clj` or the smoke helper and run the daemon-backed demo/seed flow through `open!`.
- **TASK-006.W4:** Capture tmux output showing the daemon remained live during separate client operations.
- **TASK-006.W5:** Verify `daemon status --format edn` or JSON shows a loopback/local endpoint and the expected canonical database path.
- **TASK-006.W6:** Stop the daemon, exit or kill the tmux session, remove disposable SQLite/runtime files, and confirm `git status --short` has no generated artifacts.

## TASK-006.P3 Done when

- **TASK-006.D1:** The final report includes the tmux session name and captured evidence summary of daemon startup, loopback/local status identity, client operations, observed task data, and clean shutdown.
- **TASK-006.D2:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` pass after the tmux verification.
- **TASK-006.D3:** No `agent-` tmux session or generated SQLite/runtime artifact is left behind unless explicitly documented for follow-up.
