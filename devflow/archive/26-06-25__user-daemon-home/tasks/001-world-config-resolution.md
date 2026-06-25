# Task 1: World config resolution

**Document ID:** `TASK-001`
**Configuration identification:** `TASK-001` is the first task for `user-daemon-home`. Prefix every nested point ID with `TASK-001`.

## TASK-001.P1 Scope

Type: AFK

Implement the shared config-dir daemon world contract in Go and Clojure without changing socket transport behavior yet.

## TASK-001.P2 Must implement exactly

- **TASK-001.MI1:** Update Go config loading under `cli/internal/config` so the default selected world uses `$XDG_CONFIG_HOME/atom` or `~/.config/atom`, `$XDG_STATE_HOME/atom` or `~/.local/state/atom`, and `$XDG_DATA_HOME/atom` or `~/.local/share/atom`.
- **TASK-001.MI2:** Add explicit `--config-dir DIR` resolution where config lives in `DIR`, state lives in `DIR/state`, and data lives in `DIR/data`.
- **TASK-001.MI3:** Replace public `--config-path` CLI option with `--config-dir`; do not retain `--config-path` as an alias.
- **TASK-001.MI4:** Update JSON config shape to allow only supported low-privilege keys: `source` and `format`.
- **TASK-001.MI5:** Remove `db` from supported JSON config; malformed configs, unsupported keys, and old `db` key fail loudly.
- **TASK-001.MI6:** Validate `source` only for Clojure-spawning lifecycle commands: it must be an absolute existing directory containing `deps.edn`.
- **TASK-001.MI7:** Add equivalent Clojure world resolution helpers in the daemon/client side so daemon code can receive a selected config-dir and compute config/state/data paths consistently.
- **TASK-001.MI8:** Update Go and Clojure tests for config/world resolution and failure messages.

## TASK-001.P3 Done when

- **TASK-001.DW1:** `go test ./...` in `cli/` covers default XDG world, explicit `--config-dir`, unsupported `db`, unsupported keys, and `source` validation.
- **TASK-001.DW2:** Relevant Clojure tests cover default and explicit world path resolution.
- **TASK-001.DW3:** `todo --help` and daemon command help mention `--config-dir` and no longer mention `--config-path`.

## TASK-001.P4 Out of scope

- **TASK-001.OS1:** Do not change JSON socket request/response handling yet except as required to compile after option struct changes.
- **TASK-001.OS2:** Do not implement `daemon repl` in this task.
- **TASK-001.OS3:** Do not add DB path customization hooks.

## TASK-001.P5 References

- **TASK-001.REF1:** `UDH-PLAN-001.PH1`, `UDH-PLAN-001.A2`, `UDH-PLAN-001.TC3`.
- **TASK-001.REF2:** `UDH-DELTA-002.C1` through `UDH-DELTA-002.C5`.
- **TASK-001.REF3:** Current Go config code: `cli/internal/config/config.go`, `cli/internal/command/command.go`.
