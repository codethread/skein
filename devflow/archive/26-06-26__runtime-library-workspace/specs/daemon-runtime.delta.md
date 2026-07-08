# Daemon Runtime Delta: Runtime Library Workspace

**Document ID:** `RLW-DELTA-001` **Status:** Merged into root spec **Last Updated:** 2026-06-25 **Root spec:** [Daemon Runtime](../../../specs/daemon-runtime.md) **Proposal:** [../proposal.md](../proposal.md)

## RLW-DELTA-001.P1 Summary

This feature revises the daemon runtime contract so trusted Clojure library workspaces replace plugin-directory loading as the extension model. The daemon remains the live runtime core, and selected config-dir may contain user-approved library configuration and local source roots that can be hot-added to the daemon classpath through Clojure tooling when supported.

## RLW-DELTA-001.P2 Changes to SPEC-004.P2 Runtime model

- **RLW-DELTA-001.C1:** Replace `SPEC-004.C1`'s loaded-plugin metadata registry with daemon-lifetime library workspace state. A daemon owns exactly one active SQLite datasource, one in-memory named-query registry, one in-memory approved-library sync state, and one in-memory module-use registry for its lifetime.
- **RLW-DELTA-001.C2:** The old loaded-plugin metadata registry is removed from the public daemon runtime contract. If code retains transitional internal state during implementation, root specs and public helpers must not expose it as extension introspection.

## RLW-DELTA-001.P3 Changes to SPEC-004.P7 Configuration and user code

- **RLW-DELTA-001.C3:** The selected config-dir is also the trusted library workspace root. User-owned config may include `init.clj`, `libs.edn`, local source directories, and user code.
- **RLW-DELTA-001.C4:** `libs.edn`, when present, is trusted user config that declares approved daemon-wide libraries and local roots. Relative local roots resolve against the selected config-dir.
- **RLW-DELTA-001.C5:** The daemon may expose trusted helpers that read approved library config and ask Clojure dependency tooling to add approved local roots to the running runtime. The daemon does not implement dependency resolution itself.
- **RLW-DELTA-001.C6:** Missing `libs.edn` fails loudly only when a helper explicitly requires approved library config. Malformed `libs.edn` is a structural config error and fails loudly. Per-library unavailable local roots are sync outcomes, not structural config errors, so optional `use!` flows can skip without bricking daemon startup.

## RLW-DELTA-001.P4 Changes to SPEC-004.P9 Runtime plugin/library model

- **RLW-DELTA-001.C7:** The extension abstraction becomes normal Clojure libraries/modules made available to the daemon classpath, then required and called by daemon-side trusted startup or daemon-routed REPL helper code. The `atom.plugin.alpha/load-plugin!` plugin-directory loader is removed from the canonical extension contract.
- **RLW-DELTA-001.C8:** Atom ships blessed `atom.*.alpha` namespaces as normal Clojure libraries. User/community modules use the same daemon classpath and `require` mechanics; Atom does not provide a separate namespace system for plugins.
- **RLW-DELTA-001.C9:** Runtime library availability and runtime activation are distinct. Making a local root available allows daemon-side `require`; activation remains explicit trusted Clojure such as `use!`, direct function calls, or selected-config-dir-relative `load-file`.
- **RLW-DELTA-001.C10:** Runtime dependency loading is daemon-wide. There is no per-plugin/per-module version isolation, namespaced-by-version requiring, or unloading guarantee. Version replacement may require daemon restart for a clean world.
- **RLW-DELTA-001.C11:** Runtime source acquisition is outside normal daemon boot. Trusted helpers must not silently clone repositories, add Git submodules, or fetch source as part of ordinary module activation.
- **RLW-DELTA-001.C12:** The MVP supports approved local roots first. Maven/remote dependency downloads are deferred unless an implementation spike proves the daemon launch/runtime can support them deterministically and the feature contract is revised before tasking.
- **RLW-DELTA-001.C13:** The runtime tracks module-use attempts and outcomes for daemon-lifetime introspection. This replaces the loaded-plugin metadata registry as the blessed runtime extension introspection state and is not durable package state.

## RLW-DELTA-001.P5 Non-goal additions

- **RLW-DELTA-001.C14:** Atom does not become a package manager in this feature. Package registries, source installers, Git update workflows, lockfiles, dependency solving beyond delegated Clojure tooling, and CLI package commands remain outside the daemon runtime contract.
