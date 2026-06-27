# Weaver Event System Plan

**Document ID:** `PLAN-001`
**Status:** Reviewed
**Last Updated:** 2026-06-27
**Proposal:** [Weaver Event System Proposal](./proposal.md)
**Feature Specs:** [Weaver Runtime Delta](./specs/daemon-runtime.delta.md), [REPL API Delta](./specs/repl-api.delta.md)
**Root Specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [Strand Model](../../specs/strand-model.md)

## PLAN-001.P1 Goal and scope

- **PLAN-001.G1:** Add a weaver-owned semantic event system for strand mutations, with async handler dispatch and trusted Clojure registration helpers.
- **PLAN-001.G2:** Keep the public CLI thin. No event registration commands or JSON socket event subscription are in scope.
- **PLAN-001.G3:** Make the MVP sufficient for userland cleanup workflows such as burning strands tagged with an attribute after relevant strand changes.

## PLAN-001.P2 Approach

- **PLAN-001.A1:** Treat `skein.weaver.api` as the semantic mutation boundary. Emit domain events from API-level operations after DB mutations succeed, rather than watching SQLite table changes.
- **PLAN-001.A2:** Store event system state in the weaver runtime map: handler registry, dispatch queue/worker, and bounded recent handler failures.
- **PLAN-001.A3:** Use a single sequential background worker for MVP dispatch. This mirrors a simple goroutine/channel mental model while avoiding concurrent handler races by default. Use a bounded queue for the MVP and fail loudly at enqueue time when the queue is full; keep recent handler failures bounded to the latest 100 entries.
- **PLAN-001.A4:** Represent handlers as fully qualified symbols resolved in the weaver JVM under the runtime library classloader; validate symbol/key/callability at registration so config mistakes fail loudly. Invoke handlers on the worker under the same library classloader.
- **PLAN-001.A5:** Let handlers call existing weaver APIs if they want to mutate graph state. The event system should not impose a separate DSL for handler behavior.
- **PLAN-001.A6:** Handler registration includes explicit event type filters. Handlers only receive events whose `:event/type` is in their registered `:types` set.

## PLAN-001.P3 Affected areas

- **PLAN-001.AA1:** `src/skein/weaver/runtime.clj` — runtime state initialization, event worker lifecycle, shutdown cleanup.
- **PLAN-001.AA2:** `src/skein/weaver/api.clj` — event emission from add/update/burn/batch-like semantic operations and helper operations for registry introspection.
- **PLAN-001.AA3:** New `src/skein/events/alpha.clj` or equivalent blessed namespace — trusted user-facing helper API.
- **PLAN-001.AA4:** `src/skein/client.clj` — nREPL fixed forms for event helper operations if helpers route through the client when called outside the weaver.
- **PLAN-001.AA5:** Tests under `test/skein/*` — event registration, async dispatch, payloads, failure recording, reload replacement, and no JSON socket expansion.
- **PLAN-001.AA6:** Specs/docs — promote event runtime contract when shipped.

## PLAN-001.P4 Implementation phases

- **PLAN-001.IP1:** Add internal event runtime primitives: registry, dispatch queue, worker startup/shutdown, event enqueue, failure recording, and focused unit tests.
- **PLAN-001.IP2:** Emit semantic strand events from public weaver mutation operations with stable payloads for add, update, and burn. `update` covers both row patches and edge writes performed through the update operation; include the submitted patch, including `:edges`, in the event. Defer batch events until a blessed batch mutation API exists.
- **PLAN-001.IP3:** Add `skein.events.alpha` trusted helpers and client routing so config libraries and connected REPL code can register/list/unregister handlers.
- **PLAN-001.IP4:** Add integration coverage with a real handler that responds to an update event and burns userland ephemeral strands without blocking the original operation.
- **PLAN-001.IP5:** Update specs/docs and smoke coverage for the event system MVP.

## PLAN-001.P5 Validation strategy

- **PLAN-001.V1:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`.
- **PLAN-001.V2:** Run `(cd cli && go test ./...)` to ensure JSON socket allowlist and CLI behavior remain stable.
- **PLAN-001.V3:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` after integration wiring.
- **PLAN-001.V4:** Add a focused smoke or test case proving handler work is asynchronous enough that the mutation returns before a deliberately slow handler finishes.

## PLAN-001.P6 Key design decisions

- **PLAN-001.D1:** Emit semantic events from Skein operations, not SQLite events. SQLite events are storage-level and cannot reliably express before/after domain intent.
- **PLAN-001.D2:** Handler errors do not roll back or fail an already successful mutation in the MVP. They are recorded for introspection in a bounded latest-100 failure buffer.
- **PLAN-001.D3:** A single bounded worker queue is preferred for MVP determinism and daemon stability. A pool can be introduced later if workloads justify concurrency controls.
- **PLAN-001.D4:** Event registration is trusted Clojure-only. The CLI should remain a low-privilege JSON control surface.
- **PLAN-001.D5:** Runtime config reload must stop event dispatch, discard pending queued events, clear event handlers and recent event failures, then restart dispatch before re-running `init.clj`; stable-key replacement alone is insufficient because deleted handler registrations would otherwise survive reload. Reload does not unload Clojure namespaces or remove already-loaded vars; code removal/replacement may still require a weaver restart.

## PLAN-001.P7 Task context

- **PLAN-001.TC1:** The current weaver already owns runtime registries for queries, views, approved library syncs, and module use. The event system should follow that pattern rather than adding durable DB tables.
- **PLAN-001.TC2:** Event payloads should be normalized Clojure data, matching existing weaver API return conventions.
- **PLAN-001.TC3:** Avoid putting event logic in `skein.db` directly unless a small internal callback seam is needed. `db.clj` should remain persistence-focused; `weaver.api` knows user-facing operation semantics.
- **PLAN-001.TC4:** Ensure handlers cannot accidentally multiply on config reload; registration by stable key should replace prior entries, and `skein.weaver.api/reload-config!` must stop dispatch, discard pending events, clear handlers/failures, restart dispatch, and then reinstall config.
- **PLAN-001.TC5:** Event registry introspection should return data-first maps containing handler keys, subscribed event type sets, metadata, and function symbols, not function values or internal worker objects.
- **PLAN-001.TC6:** Registration can require the handler symbol to be resolvable in the weaver JVM; user config must sync/use libraries before registering handlers from those libraries.
- **PLAN-001.TC7:** Burn events should include pre-delete strand rows because callers cannot rehydrate burned rows after deletion.

## PLAN-001.P8 Developer Notes

- **PLAN-001.DN1:** 2026-06-27 — Feature created from discussion about replacing polling/cron cleanup with core pub/sub primitives. Direction chosen: Skein-owned semantic event lifecycle, async worker, trusted `skein.events.alpha` helpers, no SQLite watcher API.
- **PLAN-001.DN2:** 2026-06-27 — Deep review tightened MVP scope: remove batch events until a blessed batch API exists, require reload clearing for event handlers/failures, validate handler symbols at registration, require data-first introspection, and add the missing docs/smoke task.
