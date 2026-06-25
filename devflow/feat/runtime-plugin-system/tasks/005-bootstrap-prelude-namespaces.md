# Bootstrap Prelude Namespaces

**Document ID:** `RPS-TASK-005`
**Status:** Blocked
**Plan:** [runtime-plugin-system.plan.md](../runtime-plugin-system.plan.md)
**Specs:** [repl-api.delta.md](../specs/repl-api.delta.md)

## RPS-TASK-005.P1 Scope

Type: AFK

Add the initial blessed user-facing alpha namespaces for plugin ergonomics: `atom.bootstrap.alpha` and `atom.prelude.alpha`.

## RPS-TASK-005.P2 Implementation notes

- **RPS-TASK-005.I1:** Add `atom.bootstrap.alpha/use-defaults!` for side-effectful recommended setup.
- **RPS-TASK-005.I2:** Add `atom.prelude.alpha` for optional interactive convenience imports.
- **RPS-TASK-005.I3:** Keep first defaults intentionally small: load/register base Atom alpha library metadata and prepare the convention for future blessed query/graph/view libraries.
- **RPS-TASK-005.I4:** Prelude remains opt-in and `use-defaults!` must not automatically load it.
- **RPS-TASK-005.I5:** Do not implement query/graph/view libraries in this task.

## RPS-TASK-005.P3 Done when

- **RPS-TASK-005.D1:** Namespaces load from the configured source checkout.
- **RPS-TASK-005.D2:** `(atom.bootstrap.alpha/use-defaults!)` is idempotent enough for daemon startup/reload workflows.
- **RPS-TASK-005.D3:** Tests cover namespace loadability and metadata effects.
