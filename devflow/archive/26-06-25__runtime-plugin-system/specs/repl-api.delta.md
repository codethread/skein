# REPL API Delta — Runtime Plugin System

**Document ID:** `RPS-DELTA-002` **Status:** Merged **Date:** 2026-06-25 **Prerequisite:** `user-daemon-home` shipped in `devflow/archive/26-06-25__user-daemon-home` **Updates:** [REPL API](../../../specs/repl-api.md)

## RPS-DELTA-002.P1 Summary

Expose plugin/library ergonomics through trusted Clojure: users can require blessed alpha libraries, bootstrap defaults, load local plugin directories, register plugin metadata, and inspect loaded runtime libraries.

## RPS-DELTA-002.P2 Contract changes

- **RPS-DELTA-002.C1:** Add `atom.plugin.alpha` as the blessed alpha namespace for plugin metadata and local plugin loading helpers.
- **RPS-DELTA-002.C2:** `atom.plugin.alpha/register!` registers or replaces daemon-lifetime plugin metadata by canonical plugin name. It accepts the same plugin-authored metadata shape as `atom-plugin.edn`; unknown keys fail loudly.
- **RPS-DELTA-002.C3:** `atom.plugin.alpha/plugins` returns loaded plugin metadata for the current daemon lifetime.
- **RPS-DELTA-002.C4:** `atom.plugin.alpha/plugin` returns metadata for one loaded plugin by name, or nil when no plugin metadata is registered for that name.
- **RPS-DELTA-002.C5:** `atom.plugin.alpha/load-plugin!` loads a local plugin directory using the required `atom-plugin.edn` plus `init.clj` convention. Absolute paths are used as-is; relative paths resolve against the daemon's selected config-dir, not the client's shell working directory. On success it returns the recorded plugin metadata map.
- **RPS-DELTA-002.C6:** Add `atom.bootstrap.alpha/use-defaults!` for minimal recommended startup setup from selected config-dir `init.clj`. In the MVP it registers Atom's built-in alpha library metadata and returns useful registry/metadata state; it does not load plugins from disk and does not require the optional prelude.
- **RPS-DELTA-002.C7:** Add `atom.prelude.alpha` for optional broad interactive helper imports. Prelude use is opt-in and `use-defaults!` does not automatically load it.
- **RPS-DELTA-002.C8:** `todo daemon repl` and `todo daemon repl --stdin` from `user-daemon-home` are the blessed ways for users/agents to evaluate plugin code against a running daemon world.
- **RPS-DELTA-002.C9:** REPL docs must distinguish blessed alpha library use from lower-level/internal use without forbidding the latter.

## RPS-DELTA-002.P3 Example init

A minimal recommended config-dir `init.clj` may look like:

```clojure
(require '[atom.bootstrap.alpha :as atom]
         '[atom.plugin.alpha :as plugin])

(atom/use-defaults!)

(plugin/load-plugin! "/Users/me/.config/atom/plugins/my-plugin")
```

The plugin directory may look like:

```text
my-plugin/
|-- atom-plugin.edn
`-- init.clj
```

```clojure
;; my-plugin/atom-plugin.edn
{:format-version 1
 :name my/my-plugin
 :version "0.1.0-alpha"
 :provides [:example/helpers]}
```

## RPS-DELTA-002.P4 Stability and autonomy

Blessed alpha namespaces are the documented and maintained path for startup and REPL workflows. They do not forbid trusted users from requiring `todo.*` namespaces, calling lower-level daemon/database helpers, or reading raw SQLite tables. Code that bypasses the blessed path owns the compatibility cost when internals or schemas change.

## RPS-DELTA-002.P5 Non-goals retained

- **RPS-DELTA-002.N1:** Do not require users to use the prelude or defaults.
- **RPS-DELTA-002.N2:** Do not hide lower-level namespaces from trusted REPL users.
- **RPS-DELTA-002.N3:** Do not add CLI plugin authoring or package installation commands.
- **RPS-DELTA-002.N4:** Do not support plugin-specific Maven dependencies or dynamic classpath mutation in this feature.
