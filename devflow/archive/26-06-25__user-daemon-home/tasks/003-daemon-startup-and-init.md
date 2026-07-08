# Task 3: Daemon startup and init

**Document ID:** `TASK-003` **Configuration identification:** `TASK-003` is the third task for `user-daemon-home`. Prefix every nested point ID with `TASK-003`.

## TASK-003.P1 Scope

Type: AFK

Make daemon startup use the selected daemon world end-to-end: configured source launcher, daemon-owned default database, fixed state publication, and default trusted `init.clj` loading.

## TASK-003.P2 Must implement exactly

- **TASK-003.MI1:** Update `todo daemon start` to require valid `source` from the selected world's `config.json`, launch Clojure from that source regardless of caller cwd, and pass the selected config-dir into the daemon entrypoint.
- **TASK-003.MI2:** Remove public `daemon start --config <trusted.edn>` parsing/help and Clojure startup handling from the blessed CLI path.
- **TASK-003.MI3:** Change daemon runtime startup so the default database path is `tasks.sqlite` under the selected data world.
- **TASK-003.MI4:** Ensure daemon startup creates required selected state/data directories as needed and publishes fixed metadata only after startup succeeds.
- **TASK-003.MI5:** Load `init.clj` from the selected config-dir by default when present; absence is not an error, but read/compile/runtime errors fail startup loudly and publish no ready metadata.
- **TASK-003.MI6:** Preserve local-only transports: JSON socket under state world and nREPL on loopback.
- **TASK-003.MI7:** Update daemon lifecycle tests for source launch arguments where unit-testable, default database path, `init.clj` success/failure, and no metadata on failed init.

## TASK-003.P3 Done when

- **TASK-003.DW1:** A daemon started with a disposable `--config-dir` uses `DIR/data/tasks.sqlite`, `DIR/state/daemon.sock`, `DIR/state/daemon.json`, and `DIR/state/daemon.edn`.
- **TASK-003.DW2:** `init.clj` can register trusted daemon runtime state through existing REPL/daemon APIs, and startup fails loudly if `init.clj` throws.
- **TASK-003.DW3:** CLI and specs/tests no longer expose public `daemon start --config`.

## TASK-003.P4 Out of scope

- **TASK-003.OS1:** Do not add DB path override hooks.
- **TASK-003.OS2:** Do not implement `daemon repl` in this task.
- **TASK-003.OS3:** Do not design install packaging beyond using configured source checkout.

## TASK-003.P5 References

- **TASK-003.REF1:** `UDH-PLAN-001.PH3`, `UDH-PLAN-001.CM5`, `UDH-PLAN-001.TC2`.
- **TASK-003.REF2:** `UDH-DELTA-001.C7`, `UDH-DELTA-001.C8`, `UDH-DELTA-002.C6`, `UDH-DELTA-002.C7`.
- **TASK-003.REF3:** Current startup code: todo daemon runtime/config namespaces, todo CLI namespace, and Go command implementation.
