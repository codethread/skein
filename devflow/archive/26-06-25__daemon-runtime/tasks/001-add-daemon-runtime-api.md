# Add Daemon Runtime API

**Document ID:** `TASK-001` **Feature:** `daemon-runtime` **Plan:** [../daemon-runtime.plan.md](../daemon-runtime.plan.md) **Specs:** [../specs/daemon-runtime.md](../specs/daemon-runtime.md)

## TASK-001.P1 Scope

Type: AFK

Add the in-process daemon runtime foundation and `todo.daemon.api` boundary. This task does not route CLI/REPL clients over nREPL yet; it creates the daemon-owned datasource/runtime state and callable API used by later tasks.

## TASK-001.P2 Required work

- **TASK-001.W1:** Add the `nrepl/nrepl` dependency to `deps.edn` if needed by the daemon runtime namespace.
- **TASK-001.W2:** Add daemon/runtime namespaces under `src/todo/daemon` or equivalent, including start, stop, status, and runtime metadata helpers.
- **TASK-001.W3:** Runtime metadata must be EDN, atomically written, keyed by a stable hash of the canonical database path, and include pid, loopback/local endpoint, canonical db path, and nonce/identity.
- **TASK-001.W4:** Configure daemon transport to bind only to loopback/local access by default; do not expose a wildcard or remote network bind.
- **TASK-001.W5:** Add `todo.daemon.api` functions for existing operations: init, add, update, show, list, ready.
- **TASK-001.W6:** Delegate persistence to `todo.db`; do not move SQL out of `todo.db`.
- **TASK-001.W7:** Normalize JSON-bearing results consistently with current CLI/REPL behavior.

## TASK-001.P3 Done when

- **TASK-001.D1:** In-process tests can start a daemon runtime for a temp DB, call `todo.daemon.api` operations, and stop it.
- **TASK-001.D2:** Metadata tests cover canonical DB identity, stale/missing metadata shape, loopback/local endpoint recording, and atomic EDN contents.
- **TASK-001.D3:** Existing direct `todo.db` tests still pass.
