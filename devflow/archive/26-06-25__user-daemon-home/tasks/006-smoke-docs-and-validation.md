# Task 6: Smoke docs and validation

**Document ID:** `TASK-006`
**Configuration identification:** `TASK-006` is the sixth task for `user-daemon-home`. Prefix every nested point ID with `TASK-006`.

## TASK-006.P1 Scope

Type: AFK

Update smoke workflow, README/AGENTS examples, and validation coverage so the shipped user-daemon-home UX is documented and proven end-to-end.

## TASK-006.P2 Must implement exactly

- **TASK-006.MI1:** Update smoke validation to build `todo`, create a disposable `--config-dir` world, write `config.json` with absolute `source` pointing to this checkout, start the daemon, run task/query/status commands from outside the repo, run `todo daemon repl --stdin`, and stop/clean the daemon world.
- **TASK-006.MI2:** Ensure smoke verifies direct `--stdin` output semantics without a CLI response envelope.
- **TASK-006.MI3:** Update root `README.md` examples to show default config-dir setup, `source` config, `todo daemon start`, normal task commands, `todo daemon repl`, and `todo daemon repl --stdin`.
- **TASK-006.MI4:** Update `AGENTS.md` quick reference to use `--config-dir` for disposable worlds and remove DB-path-first workflows.
- **TASK-006.MI5:** Update user/contributor docs outside root devflow specs that mention `--config-path`, client config `db`, DB-path `open!`, DB-hashed runtime metadata, or public `daemon start --config`.
- **TASK-006.MI6:** Run the project validation commands and fix doc/test/smoke issues caused by the feature.

## TASK-006.P3 Done when

- **TASK-006.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- **TASK-006.DW2:** `(cd cli && go test ./...)` passes.
- **TASK-006.DW3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes and cleans generated state/data/config artifacts.
- **TASK-006.DW4:** Documentation presents config-dir daemon worlds and connected REPL as the blessed workflow.

## TASK-006.P4 Out of scope

- **TASK-006.OS1:** Do not add packaging/install docs beyond the source checkout `source` config required for this feature.
- **TASK-006.OS2:** Do not add profile aliases or alternate world selector flags.
- **TASK-006.OS3:** Do not create new durable behavior beyond making docs/tests match implemented contracts.

## TASK-006.P5 References

- **TASK-006.REF1:** `UDH-PLAN-001.PH5`, `UDH-PLAN-001.V1` through `UDH-PLAN-001.V5`.
- **TASK-006.REF2:** Project validation guidance in `AGENTS.md`.
- **TASK-006.REF3:** Current smoke/docs: `dev/todo/smoke.clj`, `README.md`, `AGENTS.md`.
