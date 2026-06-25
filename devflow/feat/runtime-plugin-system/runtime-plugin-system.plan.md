# Runtime Plugin System Plan

**Document ID:** `RPS-PLAN-001`
**Status:** Draft
**Last Updated:** 2026-06-25
**Blocked by:** `devflow/feat/user-daemon-home` shipping and promoting config-dir daemon worlds, default `init.clj`, `connect!`, and connected `todo daemon repl --stdin`
**Proposal:** [proposal.md](./proposal.md)
**Spec deltas:** [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md), [cli.delta.md](./specs/cli.delta.md)
**Related PRD:** [Runtime Transformations PRD](../../prd/runtime-transformations.md)
**Research:** `straight.el` docs/source; vendored at `~/dev/vendor/straight.el`
**Related RFCs:** None

## RPS-PLAN-001.P1 Goal and scope

Establish Atom's Emacs-like trusted runtime plugin/library MVP before building runtime transformation libraries. This feature defines blessed source-visible alpha namespaces, local plugin directory loading via `load-file`, plugin metadata/introspection, bootstrap/prelude ergonomics, and stability/coupling tiers. It does not implement git fetching, package management, dependency solving, lockfiles, dynamic JVM classpath mutation, or public CLI plugin loading.

## RPS-PLAN-001.P2 Approach

- **RPS-PLAN-001.A1:** Treat the shipped source checkout as part of the extension system. Users can inspect and require Atom namespaces directly.
- **RPS-PLAN-001.A2:** Make selected config-dir `init.clj` the daemon-world entrypoint for trusted runtime customization after `user-daemon-home` lands.
- **RPS-PLAN-001.A3:** Use `atom.*.alpha` for new blessed public Clojure namespaces while retaining `todo.*` as existing implementation namespaces.
- **RPS-PLAN-001.A4:** Provide blessed libraries as conventions with maintenance promises, not capability boundaries. Users may use lower-level namespaces or raw SQLite when they accept the cost.
- **RPS-PLAN-001.A5:** Use local plugin directories for the MVP. `atom.plugin.alpha/load-plugin!` requires `atom-plugin.edn`, validates metadata, and `load-file`s the plugin `init.clj`. Absolute paths are used as-is; relative paths resolve against the selected config-dir.
- **RPS-PLAN-001.A6:** Avoid JVM classpath mutation. Plugin `init.clj` files run on Atom's existing classpath and cannot bring plugin-specific Maven/deps.edn dependencies automatically.
- **RPS-PLAN-001.A7:** Add small plugin metadata and introspection primitives so loaded runtime libraries are visible during a daemon lifetime.
- **RPS-PLAN-001.A8:** Add `atom.bootstrap.alpha` for side-effectful default setup and `atom.prelude.alpha` for optional broad REPL convenience imports.
- **RPS-PLAN-001.A9:** Keep `straight.el` lessons as background for a later package feature: user-controlled source, explicit refs, config plus lockfile, and no hidden package database.

## RPS-PLAN-001.P3 Affected areas

| ID | Area | Impact |
| --- | --- | --- |
| RPS-PLAN-001.AA1 | `src/atom/*` or equivalent public namespaces | Add `atom.plugin.alpha`, `atom.bootstrap.alpha`, and `atom.prelude.alpha`. |
| RPS-PLAN-001.AA2 | `src/todo/daemon/api.clj` or runtime state namespace | Add or expose daemon-lifetime plugin metadata registry operations. |
| RPS-PLAN-001.AA3 | `src/todo/repl.clj` | Make plugin helpers convenient from connected REPL workflows if needed. |
| RPS-PLAN-001.AA4 | `devflow/specs/*` | Stage daemon/repl/cli contract changes around trusted plugins. |
| RPS-PLAN-001.AA5 | `README.md`, `AGENTS.md`, smoke docs | Show `init.clj` using `atom.bootstrap.alpha/use-defaults!`, local `load-plugin!`, and explain autonomy/coupling tiers. |
| RPS-PLAN-001.AA6 | Tests/smoke | Verify startup plugin load, metadata introspection, local plugin directory loading, and connected REPL behavior after `user-daemon-home`. |

## RPS-PLAN-001.P4 Contract and migration impact

- **RPS-PLAN-001.CM1:** This feature depends on `user-daemon-home`; do not target database-path `open!` or public `daemon start --config` workflows.
- **RPS-PLAN-001.CM2:** The first plugin system is source/local runtime loading, not remote package installation.
- **RPS-PLAN-001.CM3:** Plugin metadata is daemon-lifetime state. Future package lockfiles are separate.
- **RPS-PLAN-001.CM4:** New `atom.*.alpha` blessed namespace names intentionally coexist with existing `todo.*` implementation namespaces. This creates a public/internal split until a broader rename is warranted.
- **RPS-PLAN-001.CM5:** Plugin-specific dependencies are not supported automatically. Users who need extra classpath entries must run Atom with an explicitly customized source checkout/classpath outside this feature's contract.

## RPS-PLAN-001.P5 Implementation phases

### RPS-PLAN-001.PH1 Plugin contract and local entry convention

Finalize spec wording for trusted plugins, `atom.*.alpha` namespaces, local plugin directories, `atom-plugin.edn`, executable plugin `init.clj`, `load-file` semantics, and stability/coupling tiers.

### RPS-PLAN-001.PH2 Plugin metadata registry

Add daemon-lifetime plugin metadata registration and introspection. Keep the data shape small: canonical symbol name, optional version, optional source, optional Atom version expectation, and optional provided features. Symbol and keyword inputs normalize to canonical symbols. Duplicate registration replaces prior metadata for REPL reload ergonomics. Missing `plugin` lookup returns nil.

### RPS-PLAN-001.PH3 Local plugin loader

Add `atom.plugin.alpha/load-plugin!` for local plugin directories. It validates required metadata before entry execution, resolves relative paths against the selected config-dir, loads `init.clj` with `load-file`, records loader-owned metadata only after successful entry execution, and fails loudly on missing files, malformed metadata, or load errors. Plugin-authored side effects before a thrown error are trusted code effects and are not rolled back.

### RPS-PLAN-001.PH4 Bootstrap and prelude namespaces

Add `atom.bootstrap.alpha/use-defaults!` and `atom.prelude.alpha`. In this first feature, defaults stay intentionally small: register Atom's built-in alpha plugin/library metadata and prepare the convention for later query/graph/view libraries. Prelude remains opt-in and is not loaded by `use-defaults!`.

### RPS-PLAN-001.PH5 Init, REPL, and smoke examples

Update docs and smoke examples to show a config-dir `init.clj` requiring `atom.bootstrap.alpha`, loading a local plugin directory, and inspecting plugin metadata from `todo daemon repl --stdin`.

### RPS-PLAN-001.PH6 Spec promotion prep

After implementation validation, reconcile shipped behavior into root specs during devflow finish/archive. The task queue includes an explicit promotion-prep task so canonical specs are not left stale.

## RPS-PLAN-001.P6 Validation strategy

- **RPS-PLAN-001.V1:** Clojure tests cover plugin metadata registration, replacement behavior, validation, lookup, and introspection.
- **RPS-PLAN-001.V2:** Clojure tests cover `atom.plugin.alpha/load-plugin!` success, missing directory/file failures, malformed `atom-plugin.edn`, and plugin `init.clj` load failure.
- **RPS-PLAN-001.V3:** Clojure tests cover `atom.bootstrap.alpha/use-defaults!` and `atom.prelude.alpha` loadability from the configured source checkout.
- **RPS-PLAN-001.V4:** Integration/smoke covers selected config-dir `init.clj` loading bootstrap defaults and a local plugin directory, then querying loaded plugin metadata through connected REPL/stdin.
- **RPS-PLAN-001.V5:** Full validation remains `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

## RPS-PLAN-001.P7 Risks and open questions

- **RPS-PLAN-001.R1:** Introducing `atom.*.alpha` namespaces while code still uses `todo.*` may create public/internal naming tension. Mitigation: explicitly document `atom.*.alpha` as the blessed user-facing alpha layer and `todo.*` as existing implementation surface.
- **RPS-PLAN-001.R2:** Users may over-couple to internals and later experience breakage. Mitigation: document tiers and make the cost explicit rather than restricting autonomy.
- **RPS-PLAN-001.R3:** `load-file` is less powerful than proper classpath/package loading. Mitigation: embrace it as the MVP and defer package/classpath support to a separate feature.
- **RPS-PLAN-001.R4:** `use-defaults!` could become placeholder ceremony if it does too little. Mitigation: make it register built-in Atom alpha library metadata and serve as the stable bootstrap hook for the next blessed-library feature.

## RPS-PLAN-001.P8 Task context

- **RPS-PLAN-001.TC1:** This feature is blocked until `user-daemon-home` ships or the human explicitly records a decision to proceed earlier.
- **RPS-PLAN-001.TC2:** Do not implement git package fetching, latest-tag resolution, lockfiles, dependency solving, package registries, dynamic classpath mutation, or CLI plugin package commands in this feature.
- **RPS-PLAN-001.TC3:** Do not restrict trusted code from lower-level namespace or raw DB access.
- **RPS-PLAN-001.TC4:** Keep all examples Emacs-like: source-visible libraries, `init.clj`, connected REPL, manual clone/local plugin directory, user autonomy.
- **RPS-PLAN-001.TC5:** The next likely feature after this is blessed runtime libraries for query/graph/view behavior that satisfy the Runtime Transformations PRD MVP.
- **RPS-PLAN-001.TC6:** If users need plugin-specific dependencies before a package/classpath feature exists, the answer is explicit user-controlled source checkout/classpath customization, not hidden loader behavior.

## RPS-PLAN-001.P9 Developer Notes

### RPS-PLAN-001.DN1 Pivot from runtime view primitives — 2026-06-25

Dropped the earlier `runtime-view-primitives` feature draft. User clarified that Atom should first define a plugin/library model: blessed APIs are recommended and maintained, but users remain free to write or adopt better APIs and couple to the DB/schema when they accept the price. This better matches the Emacs philosophy and makes Atom's own future query/graph/view libraries plugin-style libraries rather than privileged daemon magic.

### RPS-PLAN-001.DN2 straight.el research — 2026-06-25

Studied `straight.el` and vendored it to `~/dev/vendor/straight.el` for source inspection. Useful lessons: user-controlled source, explicit recipes/refs, reproducible freeze/thaw, and no hidden package database. For this feature, keep only the philosophy; implement local `load-file` plugins first and defer git package helpers.

### RPS-PLAN-001.DN3 Council MVP narrowing — 2026-06-25

Council recommended avoiding package-manager scope and resolving the Clojure classpath issue explicitly. Revised the plan around local plugin directories, `atom-plugin.edn`, plugin `init.clj`, `load-file`, `atom.*.alpha` namespaces, no dynamic classpath mutation, no plugin dependencies, no git fetch/ref resolution, and concrete smoke coverage for manual clone/local plugin loading.

### RPS-PLAN-001.DN4 Deep review revisions — 2026-06-25

Deep review found unresolved API decisions and contract inconsistencies. Resolved them: `atom-plugin.edn` is required for `load-plugin!`; plugin names canonicalize to symbols with keyword inputs accepted; duplicate registration replaces; missing `plugin` lookup returns nil; relative `load-plugin!` paths resolve against selected config-dir; `use-defaults!` does not load prelude; loader-owned metadata is recorded only after successful plugin entry execution; full validation includes Go tests; and the task queue now includes explicit root spec promotion prep.
