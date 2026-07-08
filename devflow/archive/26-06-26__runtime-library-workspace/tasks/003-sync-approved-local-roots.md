# Sync Approved Local Roots

**Document ID:** `RLW-TASK-003` **Status:** Pending **Plan:** [../runtime-library-workspace.plan.md](../runtime-library-workspace.plan.md) **Spec delta:** [../specs/daemon-runtime.delta.md](../specs/daemon-runtime.delta.md)

## RLW-TASK-003.P1 Scope

Type: AFK

Implement daemon-side sync of approved local roots into the running runtime using the mechanism proven in task 1. Track daemon-lifetime sync state with structured per-library results.

## RLW-TASK-003.P2 Required work

- **RLW-TASK-003.W1:** Add daemon-lifetime approved-lib sync state to the runtime, replacing public reliance on loaded-plugin metadata state.
- **RLW-TASK-003.W2:** Implement `atom.libs.alpha/sync!` through daemon API routing.
- **RLW-TASK-003.W3:** For each approved lib, classify result as loaded, already available, or failed with structured reason/data.
- **RLW-TASK-003.W4:** Treat malformed `libs.edn` as a thrown structural error, but treat missing/unreadable local roots and runtime add failures as per-library failed sync outcomes.
- **RLW-TASK-003.W5:** Add introspection helper(s) for current sync state if needed by `use!` and tests.
- **RLW-TASK-003.W6:** Do not add Maven/remote dependency support or version ranges in this task.

## RLW-TASK-003.P3 Done when

- **RLW-TASK-003.D1:** A local root approved in `libs.edn` can be synced and then required daemon-side.
- **RLW-TASK-003.D2:** Missing local roots produce sync failure state rather than aborting optional startup flows.
- **RLW-TASK-003.D3:** Sync state is visible through trusted daemon/REPL helpers and is daemon-lifetime only.
- **RLW-TASK-003.D4:** Relevant Clojure tests pass.
