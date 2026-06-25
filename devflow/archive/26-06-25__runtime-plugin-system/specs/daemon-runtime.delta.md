# Daemon Runtime Delta — Runtime Plugin System

**Document ID:** `RPS-DELTA-001`
**Status:** Merged
**Date:** 2026-06-25
**Prerequisite:** `user-daemon-home` shipped in `devflow/archive/26-06-25__user-daemon-home`
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
- **RPS-DELTA-001.C7:** `atom.plugin.alpha/load-plugin!` accepts an absolute local plugin directory path or a path relative to the daemon's selected config-dir, canonicalizes the plugin directory path, reads `atom-plugin.edn`, validates metadata, loads the plugin `init.clj` with `load-file`, records loader-owned metadata only after successful entry-file execution, and returns the recorded metadata map. Relative plugin paths never resolve against the caller's shell working directory.
- **RPS-DELTA-001.C8:** MVP plugin loading does not mutate the JVM classpath. Plugin `init.clj` files run on the daemon's existing classpath and may require Atom/Clojure namespaces already available to Atom.
- **RPS-DELTA-001.C9:** Plugin-specific Maven/deps.edn dependencies, plugin-to-plugin dependency resolution, git fetching, latest-tag resolution, lockfiles, and dynamic classpath mutation are deferred.
- **RPS-DELTA-001.C10:** The daemon exposes lightweight plugin metadata registration for loaded libraries: plugin name, metadata format version, optional plugin version, optional required Atom version, optional provided features, and loader-owned source facts such as canonical plugin directory, init file, and load time.
- **RPS-DELTA-001.C11:** The daemon exposes plugin introspection for trusted REPL/config workflows so users can see what libraries/plugins are loaded in the current daemon lifetime.
- **RPS-DELTA-001.C12:** Plugin metadata is daemon-lifetime runtime state and is not persisted in SQLite.
- **RPS-DELTA-001.C13:** Startup plugin load errors fail daemon startup loudly and publish no ready metadata, following the `user-daemon-home` `init.clj` failure model.
- **RPS-DELTA-001.C14:** Plugin-authored side effects that occur before a thrown load error are trusted code effects and are not rolled back by the loader. The loader itself must not record the plugin's own metadata as successfully loaded when entry execution fails. Plugin `init.clj` should not self-register its own plugin metadata when loaded through `load-plugin!`; the loader owns registration from `atom-plugin.edn`.
- **RPS-DELTA-001.C15:** Blessed `atom.*.alpha` namespaces are loaded from the selected world's configured Atom source checkout/classpath. Startup or REPL use fails loudly if those namespaces are unavailable.

## RPS-DELTA-001.P3 Plugin metadata convention

- **RPS-DELTA-001.M1:** `atom-plugin.edn` is an EDN map with only supported unqualified keys. Namespaced keys and loader-owned keys are not accepted in plugin-authored metadata.
- **RPS-DELTA-001.M2:** Required keys are `:format-version` and `:name`.
- **RPS-DELTA-001.M3:** `:format-version` must be integer `1`. Missing format versions, non-integer versions, and unknown format versions fail loudly before loading the plugin entry file.
- **RPS-DELTA-001.M4:** `:name` is a symbol or keyword naming the plugin. Plugin names canonicalize to symbols. Symbol and keyword forms of the same namespaced name compare equal for registration, replacement, and lookup. String plugin names are invalid.
- **RPS-DELTA-001.M5:** Optional plugin-authored keys are `:version`, `:requires-atom`, and `:provides`. `:version` and `:requires-atom`, when present, are strings. `:provides`, when present, is a vector of symbols or keywords describing features supplied by the plugin; recorded `:provides` values canonicalize to symbols.
- **RPS-DELTA-001.M6:** Unknown metadata keys fail loudly before loading the plugin entry file.
- **RPS-DELTA-001.M7:** The loader augments recorded metadata with loader-owned facts including canonical `:source`, `:dir`, `:init-file`, and `:loaded-at` values. Loader-owned facts override any same-named values from plugin-authored metadata because those keys are not accepted in `atom-plugin.edn`.
- **RPS-DELTA-001.M8:** Malformed or missing metadata fails loudly before loading the plugin entry file.
- **RPS-DELTA-001.M9:** Duplicate plugin metadata registration replaces prior metadata for the same canonical plugin name.

## RPS-DELTA-001.P4 Stability and coupling tiers

- **RPS-DELTA-001.T1:** Blessed alpha libraries: recommended, documented, tested, and used by Atom examples. Initial namespaces are `atom.plugin.alpha`, `atom.bootstrap.alpha`, and `atom.prelude.alpha`. These namespaces are the maintained path, not a restriction mechanism.
- **RPS-DELTA-001.T2:** Supported low-level libraries: available to trusted plugin code but closer to daemon/database implementation details. Users may choose them when the coupling cost is worth it.
- **RPS-DELTA-001.T3:** Internal implementation namespaces: inspectable and callable by trusted code, but may change freely.
- **RPS-DELTA-001.T4:** Raw SQLite schema: allowed for trusted code, but plugins own compatibility risk if schema or persistence helpers change.

## RPS-DELTA-001.P5 Non-goals retained

- **RPS-DELTA-001.N1:** Do not add a plugin marketplace, package registry, git fetcher, lockfile, or dependency solver in this feature.
- **RPS-DELTA-001.N2:** Do not persist plugin functions or runtime behavior in SQLite.
- **RPS-DELTA-001.N3:** Do not expose arbitrary plugin loading through the low-privilege JSON CLI surface.
