# Add nREPL Client Bridge

**Document ID:** `TASK-002`
**Feature:** `daemon-runtime`
**Plan:** [../daemon-runtime.plan.md](../daemon-runtime.plan.md)
**Specs:** [../specs/daemon-runtime.md](../specs/daemon-runtime.md)

## TASK-002.P1 Scope

Type: AFK

Add the client-side connection layer that discovers daemon metadata, verifies daemon identity, and invokes fixed `todo.daemon.api` calls over nREPL.

## TASK-002.P2 Required work

- **TASK-002.W1:** Add `src/todo/client.clj` or equivalent for metadata discovery, endpoint connection, timeout handling, and API invocation.
- **TASK-002.W2:** Generate only fixed daemon API forms. Pass user-provided values as quoted data, never interpolated executable code.
- **TASK-002.W3:** Verify that the connected daemon reports the expected canonical database path and nonce/identity before accepting results.
- **TASK-002.W4:** Convert daemon exceptions, nREPL errors, connection failures, timeouts, stale metadata, and DB mismatch into loud `ExceptionInfo` failures with useful data.
- **TASK-002.W5:** Return Clojure values suitable for existing CLI/REPL formatting.

## TASK-002.P3 Done when

- **TASK-002.D1:** Client tests cover successful calls against a running daemon started in-process or as a subprocess.
- **TASK-002.D2:** Client tests cover missing metadata, unreachable endpoint, stale/wrong daemon identity, daemon-thrown domain errors, and timeout behavior.
- **TASK-002.D3:** No public CLI or REPL behavior has been changed yet except supporting code added for later tasks.
