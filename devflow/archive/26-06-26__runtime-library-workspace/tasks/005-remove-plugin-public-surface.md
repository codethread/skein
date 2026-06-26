# Remove Plugin Public Surface

**Document ID:** `RLW-TASK-005`
**Status:** Pending
**Plan:** [../runtime-library-workspace.plan.md](../runtime-library-workspace.plan.md)

## RLW-TASK-005.P1 Scope

Type: AFK

Replace the shipped plugin-system public surface with the runtime library workspace surface. This task removes or internalizes public `load-plugin!`, `atom-plugin.edn`, plugin metadata registry behavior, and bootstrap/prelude examples that depend on plugin metadata.

## RLW-TASK-005.P2 Required work

- **RLW-TASK-005.W1:** Remove `atom.plugin.alpha/load-plugin!` and plugin metadata helpers from the public documented API, or make any remaining implementation code clearly internal and unreferenced by shipped specs/docs/tests.
- **RLW-TASK-005.W2:** Remove daemon API operations that exist only for public plugin metadata/loader behavior, unless still needed internally and no longer exposed as product contract.
- **RLW-TASK-005.W3:** Update `atom.bootstrap.alpha/use-defaults!`: either remove it from public examples/contracts or redefine it so it does not register plugin metadata.
- **RLW-TASK-005.W4:** Update `atom.prelude.alpha` exports so it favors `atom.libs.alpha` and does not advertise plugin loading.
- **RLW-TASK-005.W5:** Replace or remove tests that assert old plugin loader behavior; add assertions that old plugin docs/examples are gone and new library workspace helpers are the public path.

## RLW-TASK-005.P3 Done when

- **RLW-TASK-005.D1:** Root-facing docs/tests no longer present plugin-directory loading as supported extension API.
- **RLW-TASK-005.D2:** Bootstrap/prelude behavior is coherent without plugin metadata registry.
- **RLW-TASK-005.D3:** Existing task/query/daemon behavior is unaffected.
- **RLW-TASK-005.D4:** Relevant Clojure tests pass.
