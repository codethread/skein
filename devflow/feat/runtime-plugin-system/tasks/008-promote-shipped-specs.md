# Promote Shipped Specs

**Document ID:** `RPS-TASK-008`
**Status:** Pending
**Plan:** [runtime-plugin-system.plan.md](../runtime-plugin-system.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), [repl-api.delta.md](../specs/repl-api.delta.md), [cli.delta.md](../specs/cli.delta.md)

## RPS-TASK-008.P1 Scope

Type: AFK

Promote the implemented runtime plugin system contracts into the root specs during feature finish/archive.

## RPS-TASK-008.P2 Implementation notes

- **RPS-TASK-008.I1:** Reconcile feature-local daemon runtime, REPL API, and CLI deltas into `devflow/specs/`.
- **RPS-TASK-008.I2:** Keep package-manager, git fetch, dependency solving, lockfile, dynamic classpath, and CLI plugin package commands out of the shipped specs.
- **RPS-TASK-008.I3:** Archive or finish the feature only through the normal devflow finish path.

## RPS-TASK-008.P3 Done when

- **RPS-TASK-008.D1:** Root specs describe the shipped `atom.*.alpha` plugin/library MVP accurately.
- **RPS-TASK-008.D2:** Feature-local deltas have either been promoted or clearly superseded by archived feature notes.
- **RPS-TASK-008.D3:** Root specs do not imply unsupported package-manager behavior.
