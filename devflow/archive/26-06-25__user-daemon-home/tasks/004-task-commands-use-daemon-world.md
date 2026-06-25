# Task 4: Task commands use daemon world

**Document ID:** `TASK-004`
**Configuration identification:** `TASK-004` is the fourth task for `user-daemon-home`. Prefix every nested point ID with `TASK-004`.

## TASK-004.P1 Scope

Type: AFK

Complete the migration of normal task/query/status/stop commands so they operate from any working directory through the selected daemon world's fixed socket without Clojure source or database configuration.

## TASK-004.P2 Must implement exactly

- **TASK-004.MI1:** Update Go command option resolution so `init`, `add`, `update`, `show`, `list`, `ready`, `daemon status`, and `daemon stop` require only selected config-dir/world and output format.
- **TASK-004.MI2:** Ensure normal task/query/status/stop commands do not read or validate `source` and do not require Clojure on `PATH`.
- **TASK-004.MI3:** Update Go socket request construction to omit database path and include only protocol/version/request/daemon identity fields required by the revised protocol.
- **TASK-004.MI4:** Update Clojure socket handler/API integration as needed so task/query operations dispatch against the daemon-owned runtime database.
- **TASK-004.MI5:** Keep CLI query behavior limited to named query consumption with simple params; do not add query registry mutation/listing commands.
- **TASK-004.MI6:** Update integration tests so task/query/status/stop commands are invoked from a cwd outside the source checkout against a disposable `--config-dir` daemon.
- **TASK-004.MI7:** Include coverage for `show`, `update`, `list --query/--param`, and `ready --query/--param` so existing public command paths do not regress during world-discovery migration.

## TASK-004.P3 Done when

- **TASK-004.DW1:** From an arbitrary directory, the built `todo` binary can run `init`, `add`, `update`, `show`, `list`, `ready`, `daemon status`, and `daemon stop` against a running disposable daemon world.
- **TASK-004.DW2:** From an arbitrary directory, named query consumption still works through `list --query/--param` and `ready --query/--param` against daemon-registered query state.
- **TASK-004.DW3:** Removing or omitting `source` from `config.json` does not affect normal task/query/status/stop commands against an already-running daemon.
- **TASK-004.DW4:** Attempts to use missing/stale/malformed daemon state fail loudly with remediation to start the daemon for the selected config-dir.

## TASK-004.P4 Out of scope

- **TASK-004.OS1:** Do not implement interactive or stdin REPL behavior.
- **TASK-004.OS2:** Do not add fallback direct SQLite access when the daemon is not running.
- **TASK-004.OS3:** Do not add EDN/query authoring to the Go CLI.

## TASK-004.P5 References

- **TASK-004.REF1:** `UDH-PLAN-001.TC4`, `UDH-PLAN-001.PH2`, `UDH-PLAN-001.PH3`.
- **TASK-004.REF2:** `UDH-DELTA-002.C4`, `UDH-DELTA-002.C11`, `UDH-DELTA-002.N1`.
- **TASK-004.REF3:** Current command/client code: `cli/internal/command/command.go`, `cli/internal/client/client.go`, `src/todo/daemon/socket.clj`.
