# Local Plugin Loader

**Document ID:** `RPS-TASK-004`
**Status:** Pending
**Plan:** [runtime-plugin-system.plan.md](../runtime-plugin-system.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), [repl-api.delta.md](../specs/repl-api.delta.md)

## RPS-TASK-004.P1 Scope

Type: AFK

Implement `atom.plugin.alpha/load-plugin!` for local plugin directories using the required `atom-plugin.edn` plus `init.clj` convention.

## RPS-TASK-004.P2 Implementation notes

- **RPS-TASK-004.I1:** `load-plugin!` accepts an absolute local directory path or a path relative to the daemon's selected config-dir, then canonicalizes the resolved plugin directory path.
- **RPS-TASK-004.I2:** Read `atom-plugin.edn` as EDN and validate metadata before executing plugin code: required `:format-version 1`, required `:name`, supported optional keys only, and loud failure on unknown keys.
- **RPS-TASK-004.I3:** Require plugin `init.clj` at the plugin directory root and load it with `load-file`.
- **RPS-TASK-004.I4:** Do not mutate the JVM classpath or support plugin-specific Maven/deps.edn dependencies.
- **RPS-TASK-004.I5:** Fail loudly on missing directory, missing `atom-plugin.edn`, missing `init.clj`, malformed metadata, unknown metadata keys, unsupported format versions, or plugin load errors. Wrap plugin load errors with plugin path/name context where practical.
- **RPS-TASK-004.I6:** Record loader-owned plugin metadata only after successful entry execution and return the recorded metadata map. Do not attempt to roll back arbitrary plugin-authored side effects if trusted plugin code throws.
- **RPS-TASK-004.I7:** Document/encode that plugin `init.clj` should not call registration helpers for its own loader-owned metadata; `load-plugin!` owns registration from `atom-plugin.edn`.

## RPS-TASK-004.P3 Done when

- **RPS-TASK-004.D1:** Tests cover successful local plugin loading, returned metadata, loader-owned source facts, and metadata registration.
- **RPS-TASK-004.D2:** Tests cover absolute and config-dir-relative plugin paths resolving against the daemon's selected config-dir.
- **RPS-TASK-004.D3:** Tests cover missing directory, missing metadata, missing `init.clj`, malformed `atom-plugin.edn`, unknown keys, unsupported format version, and thrown plugin load errors.
- **RPS-TASK-004.D4:** Relevant Clojure tests pass.
