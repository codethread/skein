# Task 1: Add hook registry helpers

**Document ID:** `WLH-TASK-001`

## WLH-TASK-001.P1 Scope

Type: AFK

Add the weaver-lifetime lifecycle hook registry and trusted helper surface without changing mutation behavior yet.

## WLH-TASK-001.P2 Must implement exactly

- **WLH-TASK-001.MI1:** Add `:hook-registry (atom {})` to runtime startup state in `src/skein/weaver/runtime.clj`.
- **WLH-TASK-001.MI2:** Add `skein.weaver.api` operations for registering, unregistering, and listing hooks. Registry entries must validate key, non-empty hook type set, fully qualified function symbol, data-first metadata, and integer `:order` defaulting to `0`.
- **WLH-TASK-001.MI3:** Store resolved callable state for invocation but omit callable values from public/introspection results.
- **WLH-TASK-001.MI4:** Return `hooks` in deterministic execution order: ascending `:order`, then hook key `pr-str`.
- **WLH-TASK-001.MI5:** Clear hook registry state in `reload-config!` along with other weaver-lifetime registries, before loading `init.clj`.
- **WLH-TASK-001.MI6:** Add fixed client operation mappings in `src/skein/client.clj` for hook registration, unregistration, and listing.
- **WLH-TASK-001.MI7:** Add `src/skein/hooks/alpha.clj` exposing `(register! key types fn-sym)`, `(register! key types fn-sym opts)`, `(unregister! key)`, and `(hooks)`, following the direct-or-connected routing pattern in `skein.events.alpha`.
- **WLH-TASK-001.MI8:** Do not add public JSON socket operations or Go CLI commands for hook registry workflows.

## WLH-TASK-001.P3 Done when

- **WLH-TASK-001.DW1:** Tests cover successful registration, replacement by key, unregister, deterministic ordered listing, resolved function validation, invalid key/type/fn/metadata/order failures, and no callable values in introspection.
- **WLH-TASK-001.DW2:** Tests cover `skein.hooks.alpha` direct in-weaver routing and connected-helper routing through `skein.client`.
- **WLH-TASK-001.DW3:** Tests cover `reload-config!` clearing hook state and allowing `init.clj` to reinstall hooks.
- **WLH-TASK-001.DW4:** Relevant Clojure tests pass with `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` or a narrower test command justified in Developer Notes.

## WLH-TASK-001.P4 Out of scope

- **WLH-TASK-001.OS1:** Do not invoke hooks from mutation, socket, batch, or pattern paths in this task.
- **WLH-TASK-001.OS2:** Do not add async failure queues or hook persistence.
- **WLH-TASK-001.OS3:** Do not change existing event handler semantics.

## WLH-TASK-001.P5 References

- **WLH-TASK-001.REF1:** [Plan](../weaver-lifecycle-hooks.plan.md) `WLH-PLAN-001.PH1`.
- **WLH-TASK-001.REF2:** [Weaver Runtime delta](../specs/daemon-runtime.delta.md) `WLH-DELTA-001.CC1` through `WLH-DELTA-001.CC5`, `WLH-DELTA-001.CC25`.
- **WLH-TASK-001.REF3:** [REPL API delta](../specs/repl-api.delta.md) `WLH-DELTA-002.CC1` through `WLH-DELTA-002.CC9`.
- **WLH-TASK-001.REF4:** Existing patterns: `src/skein/events/alpha.clj`, `src/skein/weaver/api.clj` event registration functions, and `src/skein/client.clj` `api-symbols`.
