# Runtime Library Workspace Proposal

**Document ID:** `RLW-PROP-001`
**Status:** Reviewed
**Date:** 2026-06-25
**Related RFCs:** None
**Prerequisite shipped:** [`devflow/archive/26-06-25__runtime-plugin-system`](../../archive/26-06-25__runtime-plugin-system/) — trusted local runtime plugin/library MVP and `atom.*.alpha` namespaces now promoted into root specs
**Relevant root specs:** [Daemon Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md)

## RLW-PROP-001.P1 Problem

The runtime plugin MVP established trusted local plugin loading, but its plugin-directory framing is not the right primary abstraction. The deeper model is simpler and more Clojure-native: make user/community Clojure libraries available to the daemon, then let trusted daemon-side `init.clj` and daemon-routed helper calls require/load namespaces and call functions. Treating every extension as one plugin directory is too limiting for multi-module repos, normal Clojure `require`, and Atom's own `atom.*.alpha` libraries.

This feature should therefore replace the plugin-system framing in the canonical specs. Per TEN-000, this alpha project should drop the weaker `load-plugin!` abstraction rather than preserve a compatibility layer around an inferior idea. The blessed extension story should become config-dir library workspaces, approved local roots, daemon-side module activation, and layered resilient boot through `use!`.

Atom needs a minimal config-dir library workspace model that separates source acquisition, classpath/library availability, and runtime activation. Users should be able to keep their config-dir as a Git repo, manage source with submodules/manual copy, approve runtime libraries in a user-owned file, sync approved local roots into the daemon, and use a resilient boot primitive for optional layered modules.

## RLW-PROP-001.P2 Goals

- **RLW-PROP-001.G1:** Reframe the blessed extension path around normal Clojure libraries/modules that daemon-side trusted code can `require` and call.
- **RLW-PROP-001.G2:** Define a selected config-dir workspace convention with user-owned source directories, approved library config, and optional user code.
- **RLW-PROP-001.G3:** Use existing infrastructure for acquisition and dependency mechanics: Git/submodules for source, Clojure runtime dependency tooling for classpath addition where supported.
- **RLW-PROP-001.G4:** Add a resilient `atom.libs.alpha/use!` boot primitive that can skip optional modules when source, approved libs, synced availability, or prior modules are unavailable, while keeping the daemon alive.
- **RLW-PROP-001.G5:** Support layered boot: user code can depend on earlier successful `use!` modules and then use strict raw `require` internally.
- **RLW-PROP-001.G6:** Preserve explicit user authority over third-party code and libraries. Runtime loading must not silently fetch plugin source or approve new dependencies.
- **RLW-PROP-001.G7:** Keep the public CLI thin; runtime library workflows stay in config and connected REPL helper surfaces.

## RLW-PROP-001.P3 Non-goals

- **RLW-PROP-001.NG1:** Do not build an Atom package registry, marketplace, dependency solver, lockfile format, or source installer in the MVP.
- **RLW-PROP-001.NG2:** Do not make `use!` run `git submodule add`, clone repositories, touch the network for source acquisition, or mutate `.gitmodules` during normal daemon boot.
- **RLW-PROP-001.NG3:** Do not provide per-plugin/per-module dependency isolation or version-namespaced library loading.
- **RLW-PROP-001.NG4:** Do not sandbox trusted Clojure code or prevent it from calling lower-level Clojure/JVM APIs.
- **RLW-PROP-001.NG5:** Do not add public CLI library/package commands in this feature.
- **RLW-PROP-001.NG6:** Do not make direct connected-helper-REPL `require` of newly synced daemon libs a supported MVP behavior; connected users use daemon-routed helpers for daemon-side activation.

## RLW-PROP-001.P4 Proposed scope

- **RLW-PROP-001.S1:** Specify selected config-dir as a user-owned library workspace, commonly a Git repo with local modules under `libs/` or another user-chosen directory, plus `init.clj` and approved library config.
- **RLW-PROP-001.S2:** Add an `atom.libs.alpha` namespace for approved library config, runtime sync through Clojure tooling, module-use tracking, and resilient optional activation.
- **RLW-PROP-001.S3:** Define `libs.edn` as the user-owned approval file for daemon-wide local roots first. Relative local roots resolve against selected config-dir. Maven/remote coordinates may be parsed later only after the runtime-deps spike proves support.
- **RLW-PROP-001.S4:** Define daemon-wide runtime dependency behavior: one shared classpath, hot-add approved local roots where Clojure tooling supports it, loud conflicts/failures, and restart recommended for clean version replacement.
- **RLW-PROP-001.S5:** Define `use!` semantics for `:ns`, `:file`, `:libs`, `:after`, `:call`, and `:required?`, with structured introspection of loaded/skipped/failed modules.
- **RLW-PROP-001.S6:** Update docs/examples to show `init.clj` as mostly `use!` calls, with user code gated behind community/library modules.
- **RLW-PROP-001.S7:** Rework the shipped plugin/library spec language so normal library availability plus `use!` replaces `atom.plugin.alpha/load-plugin!` as the blessed extension path. Remove plugin-directory metadata/loading from the canonical contract rather than maintaining it as a compatibility surface.

## RLW-PROP-001.P5 Decisions

- **RLW-PROP-001.D1:** Source acquisition and runtime activation are separate phases. Normal daemon startup may load only already-present local source and approved libraries.
- **RLW-PROP-001.D2:** `libs.edn` is the authority for blessed-path library availability; module declarations may request needs but cannot approve them.
- **RLW-PROP-001.D3:** The daemon has one shared dependency graph/classpath. Incompatible requirements fail loudly and are resolved by user choice or separate config-dir worlds.
- **RLW-PROP-001.D4:** `use!` is an ergonomic/resilience primitive, not a sandbox. Raw `require` remains available for fail-fast required config.
- **RLW-PROP-001.D5:** Future source declarations may feed an explicit `sync-sources!` workflow, but that is not part of MVP runtime boot.
- **RLW-PROP-001.D6:** MVP `:libs` checks are presence/availability checks by library coordinate key, not version-range solving. Examples must not use version ranges until a later feature defines them.
- **RLW-PROP-001.D7:** Default `use!` records gating skips and load/call failures without throwing, so optional modules do not brick daemon startup. `:required? true` rethrows after recording failed state.
- **RLW-PROP-001.D8:** Malformed `use!` options are programmer/config errors and fail loudly rather than being recorded as optional skips.
- **RLW-PROP-001.D9:** `atom.plugin.alpha/load-plugin!`, `atom-plugin.edn`, and the plugin metadata registry are superseded by `atom.libs.alpha`, `libs.edn`, and module-use introspection. This feature should remove or de-emphasize the old APIs instead of preserving compatibility.

## RLW-PROP-001.P6 Open questions

- **RLW-PROP-001.Q1:** Whether Maven/remote dependency hot-loading belongs in this feature depends on the implementation spike. If not proven early, it is deferred while local-root library work proceeds.
