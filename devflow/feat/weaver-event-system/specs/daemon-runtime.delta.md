# Weaver Runtime Delta: Event System

**Document ID:** `SPEC-004-DELTA-001`
**Status:** Draft
**Last Updated:** 2026-06-27
**Extends:** [Weaver Runtime](../../../specs/daemon-runtime.md)

## SPEC-004-DELTA-001.P1 Runtime state

- **SPEC-004-DELTA-001.C1:** A weaver owns one in-memory event handler registry, one asynchronous event dispatch worker, one bounded event queue, and one bounded recent-failure buffer for its lifetime.
- **SPEC-004-DELTA-001.C2:** Event registry contents are runtime state, not durable storage, and are reinstalled by trusted startup config or connected REPL workflows after weaver restart.
- **SPEC-004-DELTA-001.C2a:** Runtime config reload stops event dispatch, discards queued events that have not started handling, clears event handlers and recent handler failures, restarts dispatch, then re-runs selected config-dir `init.clj`, matching query/view/module-use reload semantics for registry state. Reload does not unload Clojure namespaces or remove already-loaded vars; code removal/replacement may require a weaver restart.
- **SPEC-004-DELTA-001.C3:** Event handlers run with weaver process authority. They have the same trust model as runtime library code and views.

## SPEC-004-DELTA-001.P2 Event shape

- **SPEC-004-DELTA-001.C4:** Events are Clojure maps with at least `:event/type`, `:event/id`, `:event/at`, and `:event/source`.
- **SPEC-004-DELTA-001.C5:** Strand mutation events use namespaced event types such as `:strand/added`, `:strand/updated`, and `:strand/burned`.
- **SPEC-004-DELTA-001.C6:** Update events include the strand id, the submitted user patch/delta, including any `:edges` written through the update operation, the normalized row before mutation when available, and the normalized row after mutation when available. Edge writes performed through `update` are reported by the same `:strand/updated` event in the MVP.
- **SPEC-004-DELTA-001.C7:** Burn events include the requested ids, burned ids, and normalized pre-delete strand rows for the burned ids. Row data must be captured before deletion because burned rows cannot be rehydrated afterward.
- **SPEC-004-DELTA-001.C8:** Batch mutation events are deferred until Skein exposes a blessed weaver batch mutation API.

## SPEC-004-DELTA-001.P3 Emission and dispatch

- **SPEC-004-DELTA-001.C9:** Events are emitted by Skein mutation operations after the database mutation succeeds, not by SQLite triggers or table watchers.
- **SPEC-004-DELTA-001.C10:** Event dispatch is asynchronous relative to the caller. A slow handler must not delay the CLI/REPL mutation response after the mutation succeeds.
- **SPEC-004-DELTA-001.C11:** The MVP dispatch model is a single sequential worker with a bounded queue. Enqueueing a mutation event fails loudly when the queue is full; recent handler failures are bounded to the latest 100 entries. Registration fails loudly on invalid input, but handler exceptions must not fail the already-committed mutation.
- **SPEC-004-DELTA-001.C12:** Handler failures are recorded in bounded weaver-lifetime introspection state with enough data to identify the handler key, handler function symbol, event id/type, exception message, and time.

## SPEC-004-DELTA-001.P4 Trusted helper API

- **SPEC-004-DELTA-001.C13:** Skein ships a blessed `skein.events.alpha` namespace for trusted config and connected REPL workflows.
- **SPEC-004-DELTA-001.C14:** The helper API includes registration, unregistration, registry introspection, and recent failure introspection.
- **SPEC-004-DELTA-001.C15:** Handler registration names are user-chosen keys. Re-registering a key replaces the previous handler for reload workflows. Registration includes a non-empty set of event types to receive.
- **SPEC-004-DELTA-001.C16:** A handler is referenced by fully qualified function symbol, not by client-side function value, so it can resolve in the weaver JVM. Registration fails loudly when the key is malformed, the event type set is malformed or empty, the symbol is not fully qualified, the namespace/function cannot be resolved in the weaver JVM under the runtime library classloader, or the value is not callable. Config that registers library handlers must sync/use those libraries first.
- **SPEC-004-DELTA-001.C16a:** Registry introspection returns data-first entries containing stable keys, subscribed event type sets, handler function symbols, and metadata; it does not expose function objects or worker internals.
- **SPEC-004-DELTA-001.C17:** Handlers receive one event map and return ignored data. Worker invocation runs under the runtime library classloader. Mutating handlers may call Skein APIs and must avoid infinite event loops through their own filters/guards.

## SPEC-004-DELTA-001.P5 JSON socket boundary

- **SPEC-004-DELTA-001.C18:** Event handler registration and introspection are excluded from the public JSON socket CLI allowlist. They belong to trusted REPL/config workflows.
