# Plugin Contract Specs

**Document ID:** `RPS-TASK-002`
**Status:** Complete
**Plan:** [runtime-plugin-system.plan.md](../runtime-plugin-system.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), [repl-api.delta.md](../specs/repl-api.delta.md), [cli.delta.md](../specs/cli.delta.md)

## RPS-TASK-002.P1 Scope

Type: AFK

Finalize the feature-local specs for trusted local plugins, `atom.*.alpha` namespaces, required versioned `atom-plugin.edn`, plugin `init.clj`, `load-file` semantics, metadata validation, blessed library tiers, and deferred package/classpath work.

## RPS-TASK-002.P2 Implementation notes

- **RPS-TASK-002.I1:** Ensure specs make clear that blessed libraries are recommended/maintained paths, not restrictions.
- **RPS-TASK-002.I2:** Preserve user autonomy to use lower-level namespaces or raw SQLite schema with explicit compatibility cost.
- **RPS-TASK-002.I3:** Keep git fetching, latest-tag resolution, lockfiles, dependency solving, dynamic classpath mutation, and plugin deps out of scope.
- **RPS-TASK-002.I4:** Commit to `atom.plugin.alpha`, `atom.bootstrap.alpha`, and `atom.prelude.alpha` for new public alpha namespaces.
- **RPS-TASK-002.I5:** Preserve settled decisions unless review/user changes them: unqualified metadata keys, required `:format-version 1`, unknown keys fail loudly, canonical symbol plugin names, duplicate registration replaces, missing lookup returns nil, `load-plugin!` returns metadata, prelude opt-in, and relative plugin paths resolve against the daemon's selected config-dir.
- **RPS-TASK-002.I6:** Do not add implementation beyond spec/doc edits in this task.

## RPS-TASK-002.P3 Done when

- **RPS-TASK-002.D1:** Feature deltas are internally consistent and no longer leave core plugin model wording ambiguous.
- **RPS-TASK-002.D2:** Plan Developer Notes record any changed decisions from implementation review.
