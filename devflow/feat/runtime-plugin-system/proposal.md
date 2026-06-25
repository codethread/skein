# Runtime Plugin System Proposal

**Document ID:** `RPS-PROP-001`
**Status:** Draft
**Date:** 2026-06-25
**Prerequisite shipped:** [`devflow/archive/26-06-25__user-daemon-home`](../../archive/26-06-25__user-daemon-home/) — config-dir daemon worlds, default `init.clj`, and connected REPL workflows
**Related PRD:** [Runtime Transformations PRD](../../prd/runtime-transformations.md)
**Research:** [`straight.el`](https://github.com/radian-software/straight.el), vendored for study at `~/dev/vendor/straight.el`
**Related RFCs:** None
**Relevant root specs:** [Daemon Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md)

## RPS-PROP-001.P1 Problem

Atom wants rich runtime transformations, but adding every useful query, graph, view, or workflow primitive directly to daemon core risks turning core into the extension system. That conflicts with the Emacs-shaped philosophy: trusted users should be able to inspect the shipped source, load libraries, replace conventions, couple to lower-level APIs when worth it, and share better approaches.

The missing first abstraction is a tiny trusted runtime plugin/library model. Atom should ship source-visible blessed alpha libraries and a bootstrap/prelude path, while users can load local plugin source from `init.clj`, use or ignore the blessed path, and knowingly accept compatibility costs when they depend on lower-level internals or raw schema.

## RPS-PROP-001.P2 Goals

- **RPS-PROP-001.G1:** Define Atom plugins as trusted Clojure runtime code loaded by daemon startup config or connected REPL workflows.
- **RPS-PROP-001.G2:** Make the shipped source tree part of the user-facing extension story: users can require Atom libraries, inspect source, and write compatible or alternative libraries.
- **RPS-PROP-001.G3:** Establish blessed alpha library conventions with maintenance promises, not capability boundaries.
- **RPS-PROP-001.G4:** Define stability/coupling tiers so users know the price of bypassing blessed libraries.
- **RPS-PROP-001.G5:** Add ergonomic alpha namespaces for plugin loading, bootstrap defaults, and optional REPL convenience.
- **RPS-PROP-001.G6:** Preserve autonomy: plugins may use blessed APIs, lower-level namespaces, or raw SQLite/schema access if they accept the maintenance cost.
- **RPS-PROP-001.G7:** Keep the public CLI thin. Plugin loading happens through trusted Clojure, not through task/query CLI commands.

## RPS-PROP-001.P3 Non-goals

- **RPS-PROP-001.NG1:** Do not build a marketplace, package registry, dependency solver, lockfile system, or git package fetcher in this feature.
- **RPS-PROP-001.NG2:** Do not sandbox plugins or support untrusted plugin execution.
- **RPS-PROP-001.NG3:** Do not prevent raw DB/schema coupling by trusted user code.
- **RPS-PROP-001.NG4:** Do not add public CLI view invocation or CLI plugin package commands.
- **RPS-PROP-001.NG5:** Do not mutate the JVM classpath or support plugin Maven/deps.edn dependencies in the MVP.

## RPS-PROP-001.P4 Proposed scope

- **RPS-PROP-001.S1:** Specify the plugin/library model: trusted Clojure code, source-visible, loaded through selected config-dir `init.clj` or connected REPL.
- **RPS-PROP-001.S2:** Define stability/coupling tiers: blessed alpha libraries, supported low-level libraries, internal implementation, and raw schema access.
- **RPS-PROP-001.S3:** Add initial shipped alpha namespaces: `atom.plugin.alpha`, `atom.bootstrap.alpha`, and `atom.prelude.alpha`.
- **RPS-PROP-001.S4:** Define a local plugin directory convention: required `atom-plugin.edn` metadata plus an executable `init.clj` entry file.
- **RPS-PROP-001.S5:** Add `atom.plugin.alpha/load-plugin!` for local plugin directories. It reads metadata, validates it, loads the plugin `init.clj` with `load-file`, and records loaded metadata.
- **RPS-PROP-001.S6:** Add plugin metadata helpers for loaded libraries/plugins: registration, lookup, and introspection.
- **RPS-PROP-001.S7:** Document a recommended user `init.clj` default using `atom.bootstrap.alpha/use-defaults!`, plus manual clone + `load-plugin!` for shared plugins.

## RPS-PROP-001.P5 Decisions

- **RPS-PROP-001.D1:** Use `atom.*.alpha` namespaces for new blessed user-facing libraries while existing `todo.*` namespaces remain implementation/legacy surfaces until a later rename is warranted.
- **RPS-PROP-001.D2:** Use `load-file` for MVP plugin loading. Plugin `init.clj` files run on the daemon's existing classpath and may require Atom/Clojure namespaces already available to Atom.
- **RPS-PROP-001.D3:** Plugin-specific Maven dependencies, dynamic classpath mutation, and plugin-to-plugin dependency resolution are deferred.
- **RPS-PROP-001.D4:** Users may clone plugin repos manually wherever they choose, commonly under their selected config-dir, then call `load-plugin!` from `init.clj`.
- **RPS-PROP-001.D5:** Git-backed helpers such as `(plugin! {:type :git ...})`, latest-tag resolution, pinning, freeze/thaw, and package recipes are deferred to a later feature.
- **RPS-PROP-001.D6:** `atom-plugin.edn` uses a required unqualified `:format-version 1` key plus unqualified plugin metadata keys. Unknown keys fail loudly.
- **RPS-PROP-001.D7:** Plugin names canonicalize to symbols. Metadata may use a symbol or keyword input, but lookup/replacement compares canonical symbols.
- **RPS-PROP-001.D8:** Duplicate plugin metadata registration replaces prior metadata to support REPL reload workflows.
- **RPS-PROP-001.D9:** Missing `atom.plugin.alpha/plugin` lookup returns nil; loading and registration failures still fail loudly.
- **RPS-PROP-001.D10:** `atom.plugin.alpha/load-plugin!` returns the recorded plugin metadata map on success.
- **RPS-PROP-001.D11:** `load-plugin!` owns plugin metadata registration from `atom-plugin.edn`; plugin `init.clj` should not self-register its own plugin metadata.
- **RPS-PROP-001.D12:** `atom.bootstrap.alpha/use-defaults!` does not load `atom.prelude.alpha`; prelude remains opt-in.

## RPS-PROP-001.P6 Open questions

- **RPS-PROP-001.Q1:** None for MVP. Git-backed package helpers, lockfiles, dependency handling, and richer plugin lifecycle behavior remain future design work.
