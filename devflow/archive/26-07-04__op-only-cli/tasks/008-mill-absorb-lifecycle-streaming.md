# Task 8: Mill absorbs init and weaver lifecycle, stream relay

**Document ID:** `TASK-Ooc-008`

## TASK-Ooc-008.P1 Scope

Type: AFK

Complete the Go cutover per SPEC-002-D004.C9/C10: mill gains `init` and `weaver start|stop|status|repl [--stdin]`, the mill forward proxy relays streams, and the whole Go suite (including integration tests) goes green against the new protocol.

## TASK-Ooc-008.P2 Must implement exactly

- **TASK-Ooc-008.MI1:** Move the existing `strand init` and `strand weaver *` implementations behind `mill init` and `mill weaver start|stop|status|repl [--stdin]` subcommands with `--workspace` support and unchanged semantics (old SPEC-002.C14a/C16–C20 behavior preserved). `mill start`, `mill status`, `mill weaver list` unchanged. All mill command output JSON.
- **TASK-Ooc-008.MI2:** `mill init`'s generated `init.clj` template additionally activates `skein.spools.batteries` (the runtime/use! form consistent with how the template requires/synces today), so fresh workspaces get the shipped surface.
- **TASK-Ooc-008.MI3:** `mill weaver stop` stops the supervised child via signal (task 6's shutdown path) instead of the removed socket `stop`; it still cleans/validates runtime metadata and reports JSON. Non-supervised (metadata-discovered) weavers: signal by pid with the same loud staleness checks the old stop used.
- **TASK-Ooc-008.MI4:** Stream relay through the mill forward proxy (`cli/cmd/mill/forward.go`): a stream header frame switches the proxy to line-relay mode until terminator, flushing per line, without buffering the whole response and without starving concurrent connections. Mill's op-deadline special-casing updates to the new model (envelope timeout / unbounded stream ops) — remove the old `op`-name-based long-deadline hack.
- **TASK-Ooc-008.MI5:** Rewrite `cli/integration_test.go` and mill lifecycle tests to the new surface end-to-end: mill init → mill start → mill weaver start → strand invoke ops (add/list/help), payload flags, a streaming op relayed through mill (load the pinned task-6 fixture `test/fixtures/stream-op-init.clj` from the test workspace's `init.clj` via `load-file`, then invoke `strand test-stream --count 5`), unknown-op failure shape, mill weaver stop, artifact cleanup.
- **TASK-Ooc-008.MI6:** Delete any remaining dead strand-command Go code/tests task 7 left pending.

## TASK-Ooc-008.P3 Done when

- **TASK-Ooc-008.DW1:** `(cd cli && go test ./...)` fully green.
- **TASK-Ooc-008.DW2:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` still green.
- **TASK-Ooc-008.DW3:** Manual disposable-workspace pass of the full lifecycle with locally built binaries (TC1 rules; never the canonical workspace).

## TASK-Ooc-008.P4 Out of scope

- **TASK-Ooc-008.OS1:** Spool/config/docs cutover (tasks 9–10); smoke rewrite (task 10).

## TASK-Ooc-008.P5 References

- **TASK-Ooc-008.REF1:** cli delta SPEC-002-D004.C9/C10/U5; plan A5/R2/PH4; `cli/cmd/mill/` (main, lifecycle, forward), `cli/internal/client`, `cli/integration_test.go`, `cli/mill_test.go`.
