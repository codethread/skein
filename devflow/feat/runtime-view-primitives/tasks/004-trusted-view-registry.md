# Trusted View Registry

**Document ID:** `RVP-TASK-004`
**Status:** Blocked
**Plan:** [runtime-view-primitives.plan.md](../runtime-view-primitives.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md)

## RVP-TASK-004.P1 Scope

Type: AFK

Add a daemon-memory registry for read-only trusted Clojure view functions, plus registration and invocation operations.

## RVP-TASK-004.P2 Implementation notes

- **RVP-TASK-004.I1:** Add `register-view!` for registering or replacing one named view function.
- **RVP-TASK-004.I2:** Add `view!` for invoking a registered view by name with params.
- **RVP-TASK-004.I3:** Reuse named query normalization conventions for simple symbol/keyword names.
- **RVP-TASK-004.I4:** Keep view operations out of the JSON socket CLI allowlist.

## RVP-TASK-004.P3 Done when

- **RVP-TASK-004.D1:** Tests cover registration, replacement, invocation, missing view errors, and non-function registration errors.
- **RVP-TASK-004.D2:** Tests show view functions can call daemon primitives from this feature.
- **RVP-TASK-004.D3:** Relevant Clojure tests pass.
