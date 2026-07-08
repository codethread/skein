# REPL API delta for weaver lifecycle hooks

**Document ID:** `WLH-DELTA-002` **Root spec:** [repl-api.md](../../../specs/repl-api.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-29

## WLH-DELTA-002.P1 Summary

This delta adds `skein.hooks.alpha` as the blessed REPL/config namespace for lifecycle hook registration and introspection. Hooks are trusted weaver-side functions that run synchronously at lifecycle gates defined by the Weaver Runtime delta.

## WLH-DELTA-002.P2 Contract changes

- **WLH-DELTA-002.CC1:** Skein ships source-visible namespace `skein.hooks.alpha` for trusted config, activated runtime libraries, direct in-weaver code, and connected helper REPL workflows.
- **WLH-DELTA-002.CC2:** `skein.hooks.alpha` exposes `(register! key types fn-sym)`, `(register! key types fn-sym opts)`, `(unregister! key)`, and `(hooks)`.
- **WLH-DELTA-002.CC3:** `key` is a stable keyword, symbol, or non-blank string. `types` is a non-empty set of hook type keywords. `fn-sym` is a fully qualified symbol resolvable in the weaver JVM. `opts` is a data-first map and may include `:order`; remaining keys are metadata for introspection.
- **WLH-DELTA-002.CC4:** `register!` returns a data-first registry entry and replaces an existing entry with the same key. `unregister!` removes one entry by key and returns the unregistered key. `hooks` returns registry entries in deterministic execution order.
- **WLH-DELTA-002.CC5:** Helper calls execute directly when called inside the active weaver runtime. When called from a connected helper REPL, they route to the selected weaver world through fixed client forms, matching the existing alpha helper pattern.
- **WLH-DELTA-002.CC6:** `skein.hooks.alpha` is explicit and is not added to the default `skein.repl` helper symbol list. Users require it from `init.clj`, activated library code, or a connected REPL when they need hook workflows.
- **WLH-DELTA-002.CC7:** Hook functions receive one context map. Validation hooks return normally or throw; transform hooks must return `{:hook/value replacement}` as specified by the Weaver Runtime contract.
- **WLH-DELTA-002.CC8:** Hook functions should prefer pure validation and normalization. Durable side effects inside synchronous hooks are trusted-user choices and carry normal transaction, reentrancy, and rollback risks.
- **WLH-DELTA-002.CC9:** Hook registration, unregistration, and introspection are not public JSON socket operations and have no public CLI command. They are trusted Clojure workflows.

## WLH-DELTA-002.P3 Design decisions

### WLH-DELTA-002.D1 Hooks get their own alpha namespace

- **Decision:** Lifecycle hooks use `skein.hooks.alpha` rather than extending `skein.events.alpha`.
- **Rationale:** Hooks and events have opposite failure contracts. A separate namespace keeps blocking policy distinct from post-commit notification.
- **Rejected:** Adding synchronous modes or rollback flags to the event helper API.

### WLH-DELTA-002.D2 Hook helpers mirror existing runtime helper routing

- **Decision:** `skein.hooks.alpha` follows the same in-weaver/direct-or-connected-client routing pattern as existing blessed alpha namespaces.
- **Rationale:** Users should install hooks from startup config or connected REPLs without requiring a new transport or CLI registration surface.
- **Rejected:** Adding a `strand hook ...` command family or accepting hook definitions over JSON.

## WLH-DELTA-002.P4 Open questions

- **WLH-DELTA-002.Q1:** None.
