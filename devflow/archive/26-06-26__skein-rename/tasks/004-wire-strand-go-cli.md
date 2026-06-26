# Wire strand go cli

**Document ID:** `TASK-004`

## TASK-004.P1 Scope

Type: AFK

Rename and rewire the public Go CLI to the Skein/weaver/strand contract, using the strand model and weaver metadata established by prior Clojure tasks.

## TASK-004.P2 Must implement exactly

- **TASK-004.MI1:** Rename the public CLI command from `todo` to `strand` in Cobra root use/help, build/install paths, integration tests, and command examples. Do not provide a `todo` compatibility binary.
- **TASK-004.MI2:** Rename daemon lifecycle subcommands to `weaver start`, `weaver repl`, `weaver repl --stdin`, `weaver status`, and `weaver stop`.
- **TASK-004.MI3:** Replace `--status` parsing/validation with `--active true|false` and `--ephemeral true|false` according to [cli.delta.md](../specs/cli.delta.md). Enforce create/update failure contracts for invalid inactive-ephemeral and same-command active/ephemeral changes.
- **TASK-004.MI4:** Preserve `--edge edge-type:to-id`, `--attr key=value`, `--query`, and repeated `--param key=value` behavior for the renamed commands, including `ready --query/--param`.
- **TASK-004.MI5:** Update Go config/client code to use default `skein` worlds, `skein.sqlite`, `weaver.json`, `weaver.edn`, and `weaver.sock`.
- **TASK-004.MI6:** Update generated `init.clj` to require `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`, then call `(libs/sync!)`.
- **TASK-004.MI7:** Update Go unit and integration tests for command parsing, help, config resolution, metadata validation, startup command messages, and JSON row fields.

## TASK-004.P3 Done when

- **TASK-004.DW1:** `(cd cli && go test ./...)` passes with the renamed CLI surface.
- **TASK-004.DW2:** Go integration tests build and invoke `strand`, not `todo`.
- **TASK-004.DW3:** CLI JSON output expectations use `active`, `ephemeral`, and `inactive_at`, not `status` or `final_at`.
- **TASK-004.DW4:** Help and error text uses `strand`/`weaver` and fails loudly for removed `--status` usage.

## TASK-004.P4 Out of scope

- **TASK-004.OS1:** Do not add CLI query authoring, package commands, storage selection, or view invocation.
- **TASK-004.OS2:** Do not support old `daemon` subcommand aliases.
- **TASK-004.OS3:** Do not mutate the user's default world during tests; use explicit temporary config dirs.

## TASK-004.P5 References

- **TASK-004.REF1:** [CLI delta](../specs/cli.delta.md)
- **TASK-004.REF2:** [Daemon runtime delta](../specs/daemon-runtime.delta.md)
- **TASK-004.REF3:** [Plan](../skein-rename.plan.md) `SR-PLAN-001.PH4`
- **TASK-004.REF4:** Current anchors from scout: `cli/internal/command/command.go`, `cli/internal/config/config.go`, `cli/internal/client/client.go`, `cli/cmd/todo/main.go`, `cli/integration_test.go`, and `cli/internal/*/*_test.go`.
