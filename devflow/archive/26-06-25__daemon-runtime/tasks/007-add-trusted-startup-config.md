# Add Trusted Startup Config

**Document ID:** `TASK-007` **Feature:** `daemon-runtime` **Plan:** [../daemon-runtime.plan.md](../daemon-runtime.plan.md) **Specs:** [../specs/daemon-runtime.md](../specs/daemon-runtime.md), [../specs/cli.delta.md](../specs/cli.delta.md)

## TASK-007.P1 Scope

Type: AFK

Implement the planned startup-only trusted config/user-code hook for `daemon start [--config <path>]`. This task does not add runtime reload commands, saved query semantics, Aero, SCI, or untrusted sandboxing.

## TASK-007.P2 Required work

- **TASK-007.W1:** Add parsing and wiring for the public `daemon start --config <path>` option using the global-option-compatible CLI shape from the CLI delta.
- **TASK-007.W2:** Define and implement the minimal config shape using core EDN/Clojure only. Keep it intentionally small and document it in code/docs touched by this task.
- **TASK-007.W3:** Support explicit trusted Clojure file loading at daemon startup when configured.
- **TASK-007.W4:** Fail daemon startup loudly on missing config files, malformed EDN, missing load files, read/compile/runtime errors while loading trusted code, or unsupported config keys.
- **TASK-007.W5:** Ensure a failed config/user-code load does not leave usable runtime metadata pointing at a broken daemon.

## TASK-007.P3 Done when

- **TASK-007.D1:** Tests cover successful startup with no config, successful startup with a minimal config/load file, malformed config failure, and user-code load failure.
- **TASK-007.D2:** `daemon start --config <path>` behavior is reflected in README/AGENTS or queued for the docs task with enough detail for that task to update examples.
- **TASK-007.D3:** No runtime reload or saved-query behavior is introduced.
