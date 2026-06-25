# Daemon Runtime Delta — Runtime Plugin System

**Document ID:** `RPS-DELTA-001`
**Status:** Draft
**Date:** 2026-06-25
**Blocked by:** `user-daemon-home` spec promotion
**Updates:** [Daemon Runtime](../../../specs/daemon-runtime.md)

## RPS-DELTA-001.P1 Summary

Define Atom's trusted runtime plugin/library model. The daemon loads user code from the selected config-dir world, ships source-visible blessed alpha libraries, can load local plugin directories by `load-file`, records loaded plugin metadata, and treats blessed APIs as recommended stable paths rather than exclusive capability boundaries.

## RPS-DELTA-001.P2 Contract changes

- **RPS-DELTA-001.C1:** A plugin is trusted Clojure runtime code loaded into the daemon process through selected config-dir startup (`init.clj`), `atom.plugin.alpha/load-plugin!`, or connected REPL workflows.
- **RPS-DELTA-001.C2:** Plugins run with daemon process authority. Sandboxing, untrusted execution, remote authorization, and capability restriction are outside this contract.
- **RPS-DELTA-001.C3:** Atom ships blessed alpha runtime libraries in the source checkout. Users may require these namespaces directly from `init.clj` or connected REPL sessions.
- **RPS-DELTA-001.C4:** Blessed libraries are documented, tested, and used by examples. They are guidance and maintenance promises, not enforcement boundaries.
- **RPS-DELTA-001.C5:** Trusted plugins may depend on lower-level namespaces or raw SQLite schema. Such code is valid but owns the compatibility cost of bypassing blessed APIs.
- **RPS-DELTA-001.C6:** A local plugin directory must contain `atom-plugin.edn` metadata and an executable `init.clj` entry file when loaded by `load-plugin!`.
- **RPS-DELTA-001.C7:** `atom.plugin.alpha/load-plugin!` accepts an absolute local plugin directory path or a path relative to the selected config-dir, reads `atom-plugin.edn`, validates metadata, loads the plugin `init.clj` with `load-file`, and records loader-owned metadata only after successful entry-file execution.
- **RPS-DELTA-001.C8:** MVP plugin loading does not mutate the JVM classpath. Plugin `init.clj` files run on the daemon's existing classpath and may require Atom/Clojure namespaces already available to Atom.
- **RPS-DELTA-001.C9:** Plugin-specific Maven/deps.edn dependencies, plugin-to-plugin dependency resolution, git fetching, package recipes, and lockfiles are deferred.
- **RPS-DELTA-001.C10:** The daemon exposes lightweight plugin metadata registration for loaded libraries: plugin name, optional version, optional source, optional required Atom version, and optional provided features.
- **RPS-DELTA-001.C11:** The daemon exposes plugin introspection for trusted REPL/config workflows so users can see what libraries/plugins are loaded in the current daemon lifetime.
- **RPS-DELTA-001.C12:** Plugin metadata is daemon-lifetime runtime state and is not persisted in SQLite.
- **RPS-DELTA-001.C13:** Startup plugin load errors fail daemon startup loudly and publish no ready metadata, following the `user-daemon-home` `init.clj` failure model.
- **RPS-DELTA-001.C14:** Plugin-authored side effects that occur before a thrown load error are trusted code effects and are not rolled back by the loader. The loader itself must not record the plugin's own metadata as successfully loaded when entry execution fails.
- **RPS-DELTA-001.C15:** Blessed `atom.*.alpha` namespaces are loaded from the selected world's configured Atom source checkout/classpath. Startup or REPL use fails loudly if those namespaces are unavailable.

## RPS-DELTA-001.P3 Plugin metadata convention

- **RPS-DELTA-001.M1:** `atom-plugin.edn` is an EDN map.
- **RPS-DELTA-001.M2:** `:atom.plugin/name` is required. The value is a symbol or keyword naming the plugin.
- **RPS-DELTA-001.M3:** Optional keys include `:atom.plugin/version`, `:atom.plugin/source`, `:atom.plugin/requires-atom`, and `:atom.plugin/provides`.
- **RPS-DELTA-001.M4:** `:atom.plugin/provides`, when present, is a vector of symbols or keywords describing features supplied by the plugin.
- **RPS-DELTA-001.M5:** Plugin names canonicalize to symbols. Symbol and keyword forms of the same namespaced name compare equal for registration, replacement, and lookup.
- **RPS-DELTA-001.M6:** Malformed or missing metadata fails loudly before loading the plugin entry file.
- **RPS-DELTA-001.M7:** Duplicate plugin metadata registration replaces prior metadata for the same canonical plugin name.

## RPS-DELTA-001.P4 Stability and coupling tiers

- **RPS-DELTA-001.T1:** Blessed alpha libraries: recommended, documented, tested, and used by Atom examples. Initial namespaces are `atom.plugin.alpha`, `atom.bootstrap.alpha`, and `atom.prelude.alpha`.
- **RPS-DELTA-001.T2:** Supported low-level libraries: available to trusted plugin code but closer to daemon/database implementation details.
- **RPS-DELTA-001.T3:** Internal implementation namespaces: inspectable and callable by trusted code, but may change freely.
- **RPS-DELTA-001.T4:** Raw SQLite schema: allowed for trusted code, but plugins own compatibility risk if schema or persistence helpers change.

## RPS-DELTA-001.P5 Non-goals retained

- **RPS-DELTA-001.N1:** Do not add a plugin marketplace, package registry, git fetcher, lockfile, or dependency solver in this feature.
- **RPS-DELTA-001.N2:** Do not persist plugin functions or runtime behavior in SQLite.
- **RPS-DELTA-001.N3:** Do not expose arbitrary plugin loading through the low-privilege JSON CLI surface.
