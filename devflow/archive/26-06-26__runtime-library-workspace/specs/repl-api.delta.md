# REPL API Delta: Runtime Library Workspace

**Document ID:** `RLW-DELTA-002` **Status:** Merged into root spec **Last Updated:** 2026-06-25 **Root spec:** [REPL API](../../../specs/repl-api.md) **Proposal:** [../proposal.md](../proposal.md)

## RLW-DELTA-002.P1 Summary

This feature adds an explicit blessed REPL/config namespace for daemon-side library workspace operations. `atom.libs.alpha` provides helpers for approved library config, runtime classpath sync, resilient optional module activation, and introspection.

## RLW-DELTA-002.P2 New `atom.libs.alpha` namespace

- **RLW-DELTA-002.C1:** `atom.libs.alpha` is a blessed alpha namespace for trusted config and connected REPL workflows. It is explicit and is not preloaded into `todo.repl`.
- **RLW-DELTA-002.C2:** The namespace exposes helpers for reading approved library config, syncing approved libraries into the daemon runtime, using optional modules, and inspecting module-use state.
- **RLW-DELTA-002.C3:** Helpers route to the selected daemon world when called from connected REPL clients, replacing the prior `atom.plugin.alpha` helper path as the blessed extension workflow.
- **RLW-DELTA-002.C4:** Direct `require` in the connected helper REPL remains local to the helper JVM. Newly synced daemon libraries are available to daemon-side `init.clj` and daemon-routed helper operations, not automatically to direct forms evaluated in the helper process.

## RLW-DELTA-002.P3 Approved library config

- **RLW-DELTA-002.C5:** Approved library config lives in `libs.edn` in the selected config-dir. Alternate config file selection is outside the MVP contract.
- **RLW-DELTA-002.C6:** The MVP config is an EDN map with exactly one top-level key, `:libs`. `:libs` is a map from symbol library coordinates to maps containing exactly one required key, `:local/root`, whose value is a non-blank string path. Unknown top-level keys, non-symbol coordinates, missing `:libs`, non-map entries, unknown per-lib keys, or missing/non-string `:local/root` fail loudly as structural config errors.
- **RLW-DELTA-002.C7:** Relative `:local/root` values resolve against selected config-dir; absolute roots are accepted as explicit user-approved paths. Normalized approved config canonicalizes root paths and returns entries shaped as `{lib-symbol {:local/root original-path :root canonical-path}}`.
- **RLW-DELTA-002.C8:** Per-library missing or unreadable local roots are not structural config errors. `(libs/sync!)` records them as failed sync outcomes so optional module activation can skip without aborting daemon startup.
- **RLW-DELTA-002.C9:** A helper such as `(libs/approved)` returns the normalized approved config. A helper such as `(libs/sync!)` uses Clojure runtime dependency tooling to add approved local roots and returns structured results for loaded/already-available/failed libraries.
- **RLW-DELTA-002.C10:** Maven/remote dependency coordinates and version-range matching are deferred unless the implementation spike proves deterministic runtime support and the contract is updated before tasks commit to them.

## RLW-DELTA-002.P4 Resilient module activation with `use!`

- **RLW-DELTA-002.C11:** `(libs/use! key opts)` records one daemon-lifetime module-use attempt under `key`. Duplicate keys replace prior use state for reload workflows.
- **RLW-DELTA-002.C12:** `opts` may identify load target with `:ns` for daemon-side `require` or `:file` for selected-config-dir-relative daemon-side `load-file`. `:file` must be relative and must resolve within the selected config-dir. Supplying neither or both fails loudly.
- **RLW-DELTA-002.C13:** `opts` may include `:libs`, a vector or set of symbol library coordinate keys that must be both approved and available in the daemon runtime before target loading. MVP `:libs` does not accept version ranges.
- **RLW-DELTA-002.C14:** Unmet `:libs` requirements cause a structured skipped result before raw `require`/`load-file` runs. Reasons distinguish at least `:not-approved`, `:not-synced`, and `:sync-failed` when that information is known.
- **RLW-DELTA-002.C15:** `opts` may include `:after`, a vector of prior `use!` keys that must have `:loaded` status. Missing, skipped, or failed prerequisites cause a structured skipped result with reason `:missing-after`.
- **RLW-DELTA-002.C16:** `opts` may include `:call`, a fully qualified zero-arity function symbol to resolve and call after successful `require`/`load-file`. The call return value is recorded but does not alter loaded status unless it throws.
- **RLW-DELTA-002.C17:** Default `use!` behavior is resilient: gating skips return and record `{:status :skipped ...}`; target load or call exceptions return and record `{:status :failed ...}`. `{:required? true}` rethrows load/call exceptions after recording failed state; malformed `opts` always throw.
- **RLW-DELTA-002.C18:** Raw `require` remains the strict fail-fast path for required config. `use!` is the blessed path for optional/layered boot.

## RLW-DELTA-002.P5 Introspection

- **RLW-DELTA-002.C19:** Helpers such as `(libs/uses)` and `(libs/use key)` expose daemon-lifetime module-use state, including loaded, skipped, and failed outcomes with structured reasons.
- **RLW-DELTA-002.C20:** Module-use state is not durable. Users reproduce it by re-running `init.clj` or connected REPL helper forms.
- **RLW-DELTA-002.C21:** `atom.plugin.alpha/load-plugin!`, `atom-plugin.edn`, and plugin metadata introspection are superseded by `atom.libs.alpha/use!`, `libs.edn`, and module-use introspection. They should be removed from the canonical REPL contract when this feature ships.
- **RLW-DELTA-002.C22:** `atom.bootstrap.alpha/use-defaults!` no longer registers plugin metadata. It is either removed from the canonical REPL contract or redefined only as a small library-workspace bootstrap helper that returns non-plugin runtime state. Shipped examples must not depend on bootstrap plugin metadata.

## RLW-DELTA-002.P6 Example

A resilient selected config-dir `init.clj` may be mostly layered `use!` calls:

```clojure
(require '[atom.libs.alpha :as libs])

(libs/sync!)

(libs/use! :graph
  {:ns 'community.graph.alpha
   :libs #{'community/graph}
   :call 'community.graph.alpha/install!})

(libs/use! :views
  {:ns 'community.views.alpha
   :after [:graph]
   :call 'community.views.alpha/install!})

(libs/use! :my/config
  {:file "my-code/mod.clj"
   :after [:graph :views]
   :call 'my-code.mod/install!})
```

`my-code/mod.clj` may then use raw `require` for `community.graph.alpha` and `community.views.alpha` because it is gated behind successful prior module activation.
