# Runtime Deps Local-Root Spike

**Document ID:** `RLW-TASK-001` **Status:** Complete **Plan:** [../runtime-library-workspace.plan.md](../runtime-library-workspace.plan.md)

## RLW-TASK-001.P1 Scope

Type: AFK

Prove the runtime mechanism for adding a selected-config-dir local source root to the daemon JVM after process start, then record the exact decision in the feature plan. This task is a blocking tracer bullet: do not implement broad `atom.libs.alpha` helpers until this path is proven. If the path is not viable, do not mark this task complete; mark it blocked/in-progress with a Developer Note explaining that the feature must be revised.

## RLW-TASK-001.P2 Required work

- **RLW-TASK-001.W1:** Inspect the available Clojure runtime dependency APIs under the project Clojure version in `deps.edn`.
- **RLW-TASK-001.W2:** Create a focused test or dev-only spike that starts from the daemon/runtime context and attempts to add a temporary local root containing a namespace not present at process start.
- **RLW-TASK-001.W3:** Verify daemon-side `require` can load that namespace after the local root is added.
- **RLW-TASK-001.W4:** Verify the connected helper REPL boundary remains understood: direct helper-REPL `require` is not the success criterion; daemon-side execution is.
- **RLW-TASK-001.W5:** Append a Developer Note to `runtime-library-workspace.plan.md` with the mechanism used, limitations found, and an explicit statement that implementation may proceed. If implementation may not proceed, stop without completing this task.

## RLW-TASK-001.P3 Done when

- **RLW-TASK-001.D1:** There is a deterministic test or documented spike result proving daemon-side hot-add of a local root.
- **RLW-TASK-001.D2:** The feature plan Developer Notes record that implementation may proceed and note any constraints for later tasks.
- **RLW-TASK-001.D3:** Relevant Clojure tests for the spike pass with `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` or a narrower justified command recorded in the task result.
