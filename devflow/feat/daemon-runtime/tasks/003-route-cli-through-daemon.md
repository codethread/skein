# Route CLI Through Daemon

**Document ID:** `TASK-003`
**Feature:** `daemon-runtime`
**Plan:** [../daemon-runtime.plan.md](../daemon-runtime.plan.md)
**Specs:** [../specs/cli.delta.md](../specs/cli.delta.md), [../specs/daemon-runtime.md](../specs/daemon-runtime.md)

## TASK-003.P1 Scope

Type: AFK

Change `todo.cli` so daemon lifecycle commands are available and existing task commands route through the daemon client instead of opening SQLite directly.

## TASK-003.P2 Required work

- **TASK-003.W1:** Add global-option-compatible lifecycle commands: `[--db <path>] daemon start`, `[--db <path>] daemon stop`, and `[--db <path>] [--format ...] daemon status`.
- **TASK-003.W2:** Do not expose `--port` as a public CLI option; if tests need deterministic endpoints, use internal test wiring or env/config outside the public contract.
- **TASK-003.W3:** Route `init`, `add`, `update`, `show`, `list`, and `ready` through `todo.client`.
- **TASK-003.W4:** Preserve current command parsing, output formats, add-human-id behavior, and non-zero failure behavior.
- **TASK-003.W5:** Fail loudly when no matching daemon is reachable; do not silently fall back to direct SQLite.
- **TASK-003.W6:** Ensure `daemon status --format edn|json` reports structured health and identity, including canonical database path, pid, endpoint, and daemon identity/nonce or equivalent.

## TASK-003.P3 Done when

- **TASK-003.D1:** CLI tests cover lifecycle parsing and task command routing through a daemon.
- **TASK-003.D2:** CLI tests cover no-daemon and wrong-daemon failures.
- **TASK-003.D3:** CLI tests assert `daemon status` EDN/JSON payload shape includes health and canonical database identity fields.
- **TASK-003.D4:** `clojure -M:todo --db <tmp> daemon start`, task commands, `daemon status`, and `daemon stop` work in a manual disposable DB run.
