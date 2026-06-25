# Plugin Metadata Registry

**Document ID:** `RPS-TASK-003`
**Status:** Blocked
**Plan:** [runtime-plugin-system.plan.md](../runtime-plugin-system.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), [repl-api.delta.md](../specs/repl-api.delta.md)

## RPS-TASK-003.P1 Scope

Type: AFK

Implement daemon-lifetime plugin metadata registration and introspection for trusted runtime libraries.

## RPS-TASK-003.P2 Implementation notes

- **RPS-TASK-003.I1:** Add registration for metadata fields: canonical symbol name, optional version, optional source, optional Atom version expectation, and optional provided features.
- **RPS-TASK-003.I2:** Normalize symbol and keyword plugin name inputs to canonical symbols for registration, replacement, and lookup.
- **RPS-TASK-003.I3:** Add introspection for loaded plugin metadata in the current daemon lifetime.
- **RPS-TASK-003.I4:** Expose helpers through `atom.plugin.alpha` and connected REPL workflows after `user-daemon-home`.
- **RPS-TASK-003.I5:** Keep metadata out of SQLite persistence and out of the JSON socket CLI allowlist.
- **RPS-TASK-003.I6:** Duplicate registration replaces prior metadata; missing `plugin` lookup returns nil.

## RPS-TASK-003.P3 Done when

- **RPS-TASK-003.D1:** Tests cover registration, replacement behavior, symbol/keyword name normalization, nil missing lookup, introspection, and invalid metadata.
- **RPS-TASK-003.D2:** Relevant Clojure tests pass.
