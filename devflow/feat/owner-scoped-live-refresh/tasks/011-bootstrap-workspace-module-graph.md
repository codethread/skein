# Task 11: Cut bootstrap and workspace config to modules

**Document ID:** `TASK-Olr-011`

## TASK-Olr-011.P1 Scope

Type: AFK

Convert generated fresh-world config and this repository's complete `.skein/init.clj` layering to stable `runtime/module!` declarations and contribution/reconcile entry points. Own Go bootstrap templates, smoke/config fixtures, and workspace module ordering.

## TASK-Olr-011.P2 Must implement exactly

- **TASK-Olr-011.MI1:** Replace generated `sync!`/`use!` startup with a batteries module declaration that preserves the explicit classpath source and yields a working zero-approval world.
- **TASK-Olr-011.MI2:** Express every `.skein/init.clj` module under its existing stable use key, preserving `:spools`, `:after`, and required policy. The stable key becomes owner identity.
- **TASK-Olr-011.MI3:** Split each workspace file into contribution and reconcile entry points where needed. Harness/reviewer/peer modules may use clearly marked branch-only adapters until peer Tasks 12–15 land; Task 16 must remove all adapters.
- **TASK-Olr-011.MI4:** Preserve init.local layering and notifier binding without letting an unchanged local file create duplicate owner entries or effects.
- **TASK-Olr-011.MI5:** Update `skein.test.alpha` fixture defaults, Go integration assertions, smoke worlds, and config wiring assertions to the new declaration graph.

## TASK-Olr-011.P3 Done when

- **TASK-Olr-011.DW1:** A fresh `mill init` disposable workspace starts with batteries and supports `strand help`, add, list, and ready using no old lifecycle call.
- **TASK-Olr-011.DW2:** A disposable copy of the repo workspace starts, reports every expected module owner/dependency, and performs full plus targeted refresh without clearing unrelated state.
- **TASK-Olr-011.DW3:** `(cd cli && go test ./...)`, `clojure -M:smoke`, `clojure -M:test skein.config-test skein.spools-test`, and `make fmt-check lint reflect-check` pass.

## TASK-Olr-011.P4 Out of scope

- **TASK-Olr-011.OS1:** Do not update released peer pins or canonical runtime. Temporary peer adapters may not survive Task 16.

## TASK-Olr-011.P5 References

- **TASK-Olr-011.REF1:** `.skein/init.clj`, `cli/internal/config/bootstrap.go`, `DELTA-OlrRepl-001.CC3–CC7`, `PLAN-Olr-001.PH3`.
