# REPL API Delta: Event Helpers

**Document ID:** `SPEC-003-DELTA-001`
**Status:** Draft
**Last Updated:** 2026-06-27
**Extends:** [REPL API](../../../specs/repl-api.md)

## SPEC-003-DELTA-001.P1 Blessed helper namespace

- **SPEC-003-DELTA-001.C1:** Skein ships `skein.events.alpha` as a blessed source-visible namespace for trusted config and connected REPL workflows.
- **SPEC-003-DELTA-001.C2:** `skein.events.alpha` helpers route directly to the active weaver runtime when called inside the weaver JVM and route through the selected connected weaver world when called from helper REPL clients.
- **SPEC-003-DELTA-001.C3:** Helpers include registering/replacing a handler with event-type filters, unregistering a handler, listing handler registry entries, and inspecting recent handler failures.

## SPEC-003-DELTA-001.P2 Handler contract

- **SPEC-003-DELTA-001.C4:** Handler registration accepts a stable key, a non-empty set of event types, and a fully qualified function symbol resolvable in the weaver JVM under the runtime library classloader.
- **SPEC-003-DELTA-001.C5:** Handler functions receive one event map and may perform trusted side effects, including calling Skein APIs.
- **SPEC-003-DELTA-001.C6:** Registry and failure introspection returns data-first Clojure maps suitable for agents and REPL users. Registry entries include keys, event type filters, handler symbols, and metadata, not function values.

## SPEC-003-DELTA-001.P3 Runtime lifetime

- **SPEC-003-DELTA-001.C7:** Event handlers and recent failures are weaver-lifetime runtime state.
- **SPEC-003-DELTA-001.C8:** Runtime config reload through `skein.weaver.api/reload-config!` / `skein.libs.alpha/reload!` stops dispatch, discards pending queued events, clears handlers and recent failures, restarts dispatch, then reloads selected config-dir `init.clj`. Reload clears registry state, but does not unload Clojure namespaces or remove already-loaded vars; code removal/replacement may require a weaver restart.
