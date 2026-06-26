# Runtime Library Workspace Plan

**Document ID:** `RLW-PLAN-001`
**Status:** Shipped
**Last Updated:** 2026-06-26
**Proposal:** [proposal.md](./proposal.md)
**Spec deltas:** [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md), [cli.delta.md](./specs/cli.delta.md)
**Related RFCs:** None

## RLW-PLAN-001.P1 Goal and scope

Build a minimal runtime library workspace layer that makes config-dir approved local Clojure roots available to the daemon and lets trusted config/REPL helper code activate optional modules safely. This replaces plugin-directory loading as the blessed extension model and reframes extension work around normal Clojure namespaces, daemon-side `require`, and `use!`.

The implementation should add `atom.libs.alpha`, `libs.edn` approval handling for local roots, approved-library runtime sync through existing Clojure tooling where supported, daemon-lifetime module-use state, and a resilient `use!` primitive for layered boot. It should not fetch source, manage Git submodules, expose CLI package commands, sandbox trusted code, or promise direct helper-REPL `require` of daemon-synced libs.

## RLW-PLAN-001.P2 Approach

- **RLW-PLAN-001.A1:** Treat selected config-dir as the user-owned library workspace root. `libs.edn`, local roots, and user code are trusted config assets.
- **RLW-PLAN-001.A2:** Keep source acquisition outside the daemon. Document Git/submodules/manual copy as acquisition methods, but do not implement Git mutation in `use!` or daemon startup.
- **RLW-PLAN-001.A3:** Introduce `atom.libs.alpha` as the blessed config/REPL namespace. It should mirror `atom.plugin.alpha`'s daemon-routing shape: direct daemon API calls when in the daemon JVM, connected client calls otherwise.
- **RLW-PLAN-001.A4:** Be explicit about REPL process boundaries. `sync!` mutates daemon runtime availability; connected helper REPL direct `require` still uses the helper JVM classpath. `use!` and related helpers execute activation in the daemon.
- **RLW-PLAN-001.A5:** Store approved-lib sync results and module-use state in daemon memory. Approval is read from `libs.edn`; sync/use outcomes are daemon-lifetime runtime state. This replaces public plugin metadata registry state.
- **RLW-PLAN-001.A6:** Delegate classpath mechanics to Clojure runtime tooling after a blocking spike confirms what works in this daemon launch model. MVP proceeds with local roots only; Maven/remote downloads are deferred unless proven and explicitly added before tasks.
- **RLW-PLAN-001.A7:** Normalize `libs.edn` before sync. The MVP schema is exactly `{:libs {lib-symbol {:local/root "path"}}}`. Resolve relative `:local/root` entries against selected config-dir, accept absolute roots as explicit user-approved paths, canonicalize roots, reject malformed structure loudly, and record missing/unreadable roots as per-library sync failures rather than structural config errors.
- **RLW-PLAN-001.A8:** Implement `use!` as dependency-gated optional activation. It checks `:libs` and `:after` before requiring/loading target code, records structured skipped/failed/loaded outcomes, and supports `:required? true` for rethrow-after-recording.
- **RLW-PLAN-001.A9:** Keep raw `require` available for fail-fast required startup. Docs should show `use!` for optional/layered user and community modules.
- **RLW-PLAN-001.A10:** Remove plugin-directory loading from the blessed path. `atom.plugin.alpha/load-plugin!`, `atom-plugin.edn`, and plugin metadata registry should be removed from public docs/specs/tests as part of root spec promotion, not preserved as public compatibility surface. Redefine or remove `atom.bootstrap.alpha/use-defaults!` so it no longer depends on plugin metadata.

## RLW-PLAN-001.P3 Affected areas

| ID | Area | Impact |
| --- | --- | --- |
| RLW-PLAN-001.AA1 | `src/atom/libs` | Add `atom.libs.alpha` helpers for approved config, sync, use, and introspection. |
| RLW-PLAN-001.AA2 | `src/todo/daemon/api.clj` / runtime state | Add semantic daemon operations and daemon-lifetime sync/use state. |
| RLW-PLAN-001.AA3 | `src/todo/daemon/runtime.clj` | Add runtime atoms for library sync/use state if needed. |
| RLW-PLAN-001.AA4 | `src/todo/client.clj` / connected REPL path | Allow trusted nREPL calls for new daemon API operations. Do not add JSON socket operations. |
| RLW-PLAN-001.AA5 | `test/todo` | Add Clojure tests for config parsing, sync behavior with local roots, `use!` gating, and connected helper routing. |
| RLW-PLAN-001.AA6 | `dev/todo/smoke.clj` and docs | Demonstrate config-dir `libs.edn`, local module source, `use!` layered boot, and introspection. |
| RLW-PLAN-001.AA7 | `devflow/specs/*` | Promote daemon/repl/cli contracts when shipped. |

## RLW-PLAN-001.P4 Contract and migration impact

- **RLW-PLAN-001.CM1:** Existing plugin MVP behavior is superseded. Because Atom is alpha software, this feature may remove `load-plugin!` and plugin metadata contracts rather than maintaining backward compatibility.
- **RLW-PLAN-001.CM2:** `libs.edn` is optional. Daemon startup without `libs.edn` still works unless user init explicitly invokes helpers that require it.
- **RLW-PLAN-001.CM3:** The public JSON CLI does not gain library operations. Trusted users use `init.clj`, `todo daemon repl`, or `todo daemon repl --stdin` to invoke daemon-routed helpers.
- **RLW-PLAN-001.CM4:** Runtime dependency addition is additive and daemon-wide. Clean version replacement/conflict recovery may require daemon restart.
- **RLW-PLAN-001.CM5:** This is not a security sandbox. It provides an auditable blessed path; trusted Clojure can still bypass it.
- **RLW-PLAN-001.CM6:** MVP `:libs` requirements are coordinate-key presence/availability checks. Version ranges and Maven remote downloads are not part of the taskable contract unless explicitly reintroduced after the spike.

## RLW-PLAN-001.P5 Implementation phases

### RLW-PLAN-001.PH1 Blocking runtime dependency spike

Confirm the exact available Clojure runtime dependency API under the project's Clojure version and daemon launch model. Prove or disprove hot-adding a selected-config-dir-relative local root so daemon-side `require` can load a namespace added after process start. Record the decision in Developer Notes before implementing broad helpers. If local roots cannot be supported reliably, stop and revise the feature instead of proceeding.

### RLW-PLAN-001.PH2 Approved local-root config

Implement `libs.edn` discovery, parsing, validation, and normalization for local roots. The accepted MVP shape is exactly `{:libs {lib-symbol {:local/root "path"}}}`. Resolve relative `:local/root` entries against selected config-dir, accept absolute paths, canonicalize roots, and keep original path plus canonical root in normalized output. Add tests for missing file behavior, malformed maps, unknown top-level/per-lib keys, bad coordinates, missing/non-string roots, absolute roots, relative roots, and missing local root sync classification.

### RLW-PLAN-001.PH3 Runtime sync wrapper

Implement `sync!` over approved local roots using the proven Clojure runtime mechanism. Track daemon-lifetime sync results and expose introspection. Structural `libs.edn` errors throw; per-library missing/unreadable/add-libs failures are recorded as failed sync results by default so optional `use!` can skip. Defer Maven dependency coverage unless PH1 explicitly proves it and the specs/plan are updated before tasks.

### RLW-PLAN-001.PH4 `use!` and module-use registry

Implement `use!` with `:ns`, `:file`, `:libs`, `:after`, `:call`, `:required?`, structured skipped/failed/loaded outcomes, duplicate-key replacement, and daemon-side execution. Ensure dependency gating happens before raw `require`/`load-file`.

Default result policy:

- malformed `opts` throw immediately and are not optional skips;
- unmet `:libs`/`:after` gates record and return `{:status :skipped ...}`;
- load/call exceptions record and return `{:status :failed ...}` by default;
- `:required? true` records failure then rethrows load/call exceptions.

### RLW-PLAN-001.PH5 Connected REPL and docs

Expose `atom.libs.alpha` helpers through connected daemon workflows. Update README examples to show config-dir as a Git/submodule-friendly workspace, `libs.edn`, layered `use!` boot, user code gating, fix-forward introspection, and the helper-REPL classpath boundary.

### RLW-PLAN-001.PH6 Smoke and spec promotion prep

Add smoke coverage for disposable config-dir local roots and `use!` boot resiliency. Prepare root spec promotion once behavior is validated.

## RLW-PLAN-001.P6 Validation strategy

- **RLW-PLAN-001.V1:** Clojure unit tests for exact `libs.edn` schema validation, absolute/relative local-root normalization, symlink/canonical path behavior where practical, and per-library missing-root sync failure classification.
- **RLW-PLAN-001.V2:** Clojure unit tests for runtime sync using a local source root made available after daemon start; skip Maven/download coverage unless explicitly added after PH1.
- **RLW-PLAN-001.V3:** Clojure unit tests for `use!` outcomes: loaded namespace, loaded file, not-approved skipped before require, not-synced skipped before require, sync-failed skipped before require, missing `:after` skipped, load exception recorded, `:required? true` rethrows, duplicate key replacement.
- **RLW-PLAN-001.V4:** Connected REPL tests verify helpers route to daemon state from client context and document that direct helper-REPL `require` is not the daemon-side load path.
- **RLW-PLAN-001.V5:** Smoke test starts a disposable daemon world with `libs.edn`, a local module root, layered `use!` in `init.clj`, and REPL/stdin introspection after startup.
- **RLW-PLAN-001.V6:** Full validation remains `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

## RLW-PLAN-001.P7 Risks and open questions

- **RLW-PLAN-001.R1:** Runtime dependency APIs may not support the desired local-root addition in this launch model. Mitigation: PH1 is blocking and can stop/revise the feature.
- **RLW-PLAN-001.R2:** `use!` could hide serious startup errors if too forgiving. Mitigation: malformed options throw; failures are inspectable; docs show raw `require` and `:required? true` for strict config.
- **RLW-PLAN-001.R3:** Version-range syntax can grow into dependency solving. Mitigation: MVP cuts ranges and uses coordinate-key approval/availability checks only.
- **RLW-PLAN-001.R4:** Users may expect `use!` to install source. Mitigation: keep source acquisition docs explicit and separate; no silent Git/network source mutation.
- **RLW-PLAN-001.R5:** `libs.edn` could overlap with Clojure `deps.edn`. Mitigation: define it as Atom policy/approval input, not a replacement for tools.deps semantics.

## RLW-PLAN-001.P8 Task context

- **RLW-PLAN-001.TC1:** The implementation should build on the archived/shipped runtime plugin system and revise the now-canonical root specs so normal libraries and `use!` replace the plugin-directory extension model.
- **RLW-PLAN-001.TC2:** Do not add Go CLI commands or JSON socket allowlist operations for library workflows.
- **RLW-PLAN-001.TC3:** Do not implement Git source sync in this feature; leave room for a later explicit `sync-sources!` feature.
- **RLW-PLAN-001.TC4:** Use local-root tests to avoid flaky network dependency downloads.
- **RLW-PLAN-001.TC5:** Keep examples Emacs-like and Clojure-native: config-dir, trusted `init.clj`, connected REPL helper calls, normal namespaces, explicit function calls.
- **RLW-PLAN-001.TC6:** Do not plan tasks for Maven/remote deps or version ranges unless PH1 proves support and these artifacts are revised first.

## RLW-PLAN-001.P9 Developer Notes

### RLW-PLAN-001.DN1 Initial design conversation — 2026-06-25

User clarified that the crux is not plugin metadata but normal Clojure module availability: the mechanism that works for `atom.*.alpha` should work for user/community modules too. Requirements include config-dir as a Git/submodule-friendly workspace, user-owned `libs.edn` approval, Clojure tooling for dependency/classpath mechanics, no source fetching during boot, and a resilient layered `use!` primitive so optional modules can skip/fail without bricking daemon startup.

### RLW-PLAN-001.DN2 Deep review revisions — 2026-06-25

Deep review found three blocking ambiguities: connected helper REPL direct `require` cannot see daemon classpath mutations; `use!` failure/strictness semantics were too open; and Maven/version-range hot-loading overcommitted before proving runtime classpath support. Revised proposal/specs/plan to scope MVP to daemon-side activation, approved local roots first, no version ranges, explicit `use!` result semantics, and a blocking runtime-deps spike before tasking.

### RLW-PLAN-001.DN3 Rebased on archived plugin system — 2026-06-25

Another agent finished and archived `runtime-plugin-system`, promoting its plugin/library contracts into canonical root specs. Re-evaluated this feature as direct deltas against root `daemon-runtime`, `repl-api`, and `cli` specs rather than deltas of feature-local plugin deltas. User clarified that, per TEN-000, the better `use!`/library-workspace design should replace rather than preserve the old plugin loader. The intended relationship is now explicit: runtime-library-workspace supersedes `atom.plugin.alpha/load-plugin!`, `atom-plugin.edn`, and plugin metadata as public extension contracts.

### RLW-PLAN-001.DN4 Final deep review tightening — 2026-06-25

Final deep review found three blockers before tasking: runtime model still named loaded-plugin metadata in root `SPEC-004.C1`, bootstrap semantics were orphaned after removing plugin metadata, and `libs.edn`/`sync!` failure semantics were too vague for resilient boot. Revised deltas and plan so daemon extension state is approved-lib sync plus module-use state, `atom.bootstrap.alpha/use-defaults!` must be removed or redefined without plugin metadata, `libs.edn` has an exact local-root schema, structural config errors throw, and missing/unreadable roots become per-library sync failures available to `use!` gating.

### RLW-PLAN-001.DN5 Runtime deps local-root spike — 2026-06-25

`clojure.repl.deps/add-libs` is available under the project's Clojure 1.12.0 runtime. A deterministic daemon-runtime test proves that a daemon nREPL form can hot-add a selected-config-dir local/root tools.deps project created after daemon start, then daemon-side `require` can load a namespace from that root and call it. The nREPL path works because evaluation occurs inside the daemon JVM with REPL context. Direct non-REPL calls from test/main code fail because `add-libs` requires `*repl*` and a dynamic classloader; later startup/init-time sync code must explicitly provide an equivalent daemon-side REPL/classloader context or route sync through daemon nREPL evaluation. The local root must be a valid tools.deps project root with a manifest such as `deps.edn`; pointing `:local/root` directly at a raw `src` directory fails. Connected helper-REPL direct `require` remains out of scope for success: the proven success criterion is daemon-side classpath mutation and daemon-side evaluation. Implementation may proceed for approved local roots with the classloader-context constraint above; Maven/remote dependency support remains deferred.

### RLW-PLAN-001.DN6 Approved libs config implementation — 2026-06-25

Added `atom.libs.alpha/approved` and daemon API support for selected-config-dir `libs.edn` normalization. Missing `libs.edn` returns an empty approved config, while malformed/unreadable EDN and structural schema violations fail loudly with `ex-info`. Normalization preserves original `:local/root` strings and records canonical `:root` paths without rejecting missing local roots; sync classification remains for the next task.

### RLW-PLAN-001.DN7 Approved local-root sync implementation — 2026-06-25

Added daemon-lifetime approved-lib sync state plus `atom.libs.alpha/sync!` and `syncs` helpers routed through daemon API calls. Sync records `:loaded`, `:already-available`, and structured `:failed` outcomes; missing roots, non-directory roots, unreadable roots, and runtime add failures are per-library results while malformed `libs.edn` still throws structurally. Direct in-process sync and `init.clj` loading use a daemon-owned dynamic classloader/repl binding. Tests keep runtime-added local roots on disk for the process lifetime because tools.deps basis entries are global and later dependency resolution can fail if a previously added local root is deleted.

### RLW-PLAN-001.DN8 Daemon use registry implementation — 2026-06-25

Added daemon-lifetime module-use state plus `atom.libs.alpha/use!`, `uses`, and `use` helpers routed through daemon API calls. `use!` validates malformed options loudly, gates approved/synced libs and prior loaded module uses before activation, performs daemon-side require/load-file and optional zero-arity calls with the daemon library classloader, records loaded/skipped/failed results, rethrows recorded failures for `:required? true`, and replaces duplicate keys for reload workflows.

### RLW-PLAN-001.DN9 Task 4 review revisions — 2026-06-25

Deep review tightened `use!` around the published workspace contract: `:file` now rejects absolute paths and always resolves relative to selected config-dir, module-use keys are keyword-only for the MVP to keep registry ordering and `:after` lookup simple, fatal JVM `Error`s are no longer downgraded to optional module failures, and tests now cover daemon nREPL execution plus gating-before-side-effects.

### RLW-PLAN-001.DN10 Remove plugin public surface — 2026-06-25

Removed the public `atom.plugin.alpha` namespace, daemon API plugin metadata/loader operations, connected-client plugin operation routing, and daemon plugin registry state. A later YAGNI pass removed the now-empty `atom.bootstrap.alpha/use-defaults!` surface entirely. Root docs/specs and smoke coverage now present `libs.edn`, `sync!`, and `use!` as the public extension path.

### RLW-PLAN-001.DN11 Docs and smoke workflow — 2026-06-25

Expanded README guidance around config-dir as a Git/submodule/manual-copy library workspace, `libs.edn` local-root approval, canonicalized relative/absolute roots, layered `use!`, fix-forward introspection, and the connected-helper-REPL classpath boundary; AGENTS now links to that single narrative to avoid drift. Smoke now boots a disposable world with a local tools.deps root, layered daemon-side module activation, connected REPL sync/use introspection, and an optional missing local root that records failed sync/skipped use state without preventing daemon startup.

### RLW-PLAN-001.DN12 Spec promotion validation — 2026-06-25

Promoted runtime library workspace contracts into root daemon runtime and REPL API specs, confirmed the CLI spec already preserves the thin no-library-command boundary, and marked feature-local deltas as merged. Full validation passed: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`. `git status --short` showed only source/spec/task edits and no generated SQLite/runtime artifacts after smoke.

### RLW-PLAN-001.DN13 MVP simplification pass — 2026-06-25

Removed the thin `atom.prelude.alpha` alias namespace and its tests/docs references after a YAGNI review. It duplicated `atom.libs.alpha` without adding MVP behavior, so the shipped public library-workspace surface is now a single explicit namespace.

### RLW-PLAN-001.DN14 Alignment fix-forward — 2026-06-25

Post-build alignment review found one blessed-path boundary bug: `use! :file` rejected absolute paths but allowed relative traversal such as `../mod.clj`, which contradicted the selected-config-dir-relative contract. Fixed `todo.daemon.api/use!` to reject `:file` targets whose canonical path escapes the selected config-dir, added regression coverage, and updated root/feature REPL specs. Validation passed afterward: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

### RLW-PLAN-001.DN15 Shipped and archived — 2026-06-26

Feature is complete and root specs are canonical for the runtime library workspace model: `atom.libs.alpha`, `libs.edn`, approved local roots, daemon-side `sync!`/`use!`, module-use introspection, and the removal of plugin-directory loading as public extension API. No RFCs are archived with this feature. Further config bootstrap/refactor work is tracked outside this feature.
