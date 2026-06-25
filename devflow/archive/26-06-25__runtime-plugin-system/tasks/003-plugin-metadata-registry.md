# Plugin Metadata Registry

**Document ID:** `RPS-TASK-003`
**Status:** Complete
**Plan:** [runtime-plugin-system.plan.md](../runtime-plugin-system.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), [repl-api.delta.md](../specs/repl-api.delta.md)

## RPS-TASK-003.P1 Scope

Type: AFK

Implement daemon-lifetime plugin metadata registration and introspection for trusted runtime libraries.

## RPS-TASK-003.P2 Implementation notes

- **RPS-TASK-003.I1:** Add registration for metadata fields: required `:format-version`, canonical symbol `:name`, optional string `:version`, optional string `:requires-atom`, optional vector `:provides`, and loader-owned `:source`, `:dir`, `:init-file`, and `:loaded-at` where supplied by the loader.
- **RPS-TASK-003.I2:** Normalize symbol and keyword plugin name inputs to canonical symbols for registration, replacement, and lookup.
- **RPS-TASK-003.I3:** Add introspection for loaded plugin metadata in the current daemon lifetime.
- **RPS-TASK-003.I4:** Expose helpers through `atom.plugin.alpha` and connected REPL workflows under the shipped `user-daemon-home` contracts.
- **RPS-TASK-003.I5:** Keep metadata out of SQLite persistence and out of the JSON socket CLI allowlist.
- **RPS-TASK-003.I6:** Duplicate registration replaces prior metadata; missing `plugin` lookup returns nil. Registration returns the recorded metadata map.

## RPS-TASK-003.P3 Done when

- **RPS-TASK-003.D1:** Tests cover registration return value, replacement behavior, symbol/keyword name normalization, nil missing lookup, introspection, unknown-key rejection, format-version validation, and invalid metadata.
- **RPS-TASK-003.D2:** Relevant Clojure tests pass.
