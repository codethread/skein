# Task 5: Connected daemon repl

**Document ID:** `TASK-005` **Configuration identification:** `TASK-005` is the fifth task for `user-daemon-home`. Prefix every nested point ID with `TASK-005`.

## TASK-005.P1 Scope

Type: AFK

Add the connected REPL UX: public `connect!` helper, `todo daemon repl` interactive launcher, and `todo daemon repl --stdin` non-TTY agent path.

## TASK-005.P2 Must implement exactly

- **TASK-005.MI1:** Update `src/todo/repl.clj` so the public connection helper is `connect!`, with zero-arg default world and one-arg explicit config-dir world forms.
- **TASK-005.MI2:** Remove database-path `open!` from the public REPL API and local REPL tests; helper error messages should no longer ask users to call `(open! "path/to.sqlite")`.
- **TASK-005.MI3:** Ensure task/query helpers use selected-world client connection state and fail loudly before connection with remediation pointing to `todo daemon repl` or `connect!`.
- **TASK-005.MI4:** Add a Clojure REPL main/entrypoint that preloads `todo.repl`, calls `connect!` for the selected config-dir, and starts a plain connected helper REPL.
- **TASK-005.MI5:** Add `todo daemon repl` in the Go CLI. It requires valid `source`, verifies the selected daemon world is reachable, and launches the Clojure REPL entrypoint from `source`.
- **TASK-005.MI6:** Add `todo daemon repl --stdin`. It requires valid `source`, verifies daemon reachability, reads forms from stdin, evaluates them in the connected helper context, prints one direct normal Clojure result per top-level form, and exits non-zero on read/eval errors.
- **TASK-005.MI7:** Do not add `--eval` or any additional non-interactive REPL execution API.
- **TASK-005.MI8:** Add tests for `connect!`, pre-connection helper failure, `daemon repl` command construction, and `--stdin` multi-form output semantics where feasible.

## TASK-005.P3 Done when

- **TASK-005.DW1:** Users can run `todo daemon repl` from outside the repo and get a prompt where `(tasks)` works without `(open! ...)`.
- **TASK-005.DW2:** Agents can run `todo daemon repl --stdin` with `(tasks)` and `(ready)` forms and receive one printed result per top-level form without a CLI response envelope.
- **TASK-005.DW3:** A single top-level `(do ...)` or `(let ...)` form can produce one machine-readable payload chosen by the caller.
- **TASK-005.DW4:** REPL code and local tests use `connect!` rather than DB-path `open!`; broader README/AGENTS updates remain for Task 6 and root spec promotion remains for Task 7.

## TASK-005.P4 Out of scope

- **TASK-005.OS1:** Do not add Rebel readline integration.
- **TASK-005.OS2:** Do not add `--eval`.
- **TASK-005.OS3:** Do not expose arbitrary eval over the JSON socket.
- **TASK-005.OS4:** Do not make the Go CLI parse EDN or JSON emitted by REPL forms.

## TASK-005.P5 References

- **TASK-005.REF1:** `UDH-PLAN-001.PH4`, `UDH-PLAN-001.A6`, `UDH-PLAN-001.A7`, `UDH-PLAN-001.TC5`.
- **TASK-005.REF2:** `UDH-DELTA-003.C1` through `UDH-DELTA-003.C7`.
- **TASK-005.REF3:** `UDH-DELTA-002.C8` through `UDH-DELTA-002.C10`.
- **TASK-005.REF4:** Current REPL/client code: `src/todo/repl.clj`, `src/todo/client.clj`, `deps.edn`, `cli/internal/command/command.go`.
