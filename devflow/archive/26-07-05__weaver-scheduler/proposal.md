# Weaver Scheduler Proposal

**Document ID:** `PROP-weaver-scheduler-001` **Status:** Draft **Feature:** `weaver-scheduler` **Card:** `d0cbq` **Related RFCs:** [`RFC-009 Weaver Scheduler Primitive`](../../rfcs/2026-06-29-weaver-scheduler.md) **Related specs:** [`SPEC-004 Weaver Runtime`](../../specs/daemon-runtime.md), [`SPEC-002 CLI Surface`](../../specs/cli.md), [`SPEC-003 REPL API`](../../specs/repl-api.md)

## PROP-weaver-scheduler-001.P1 Problem

Skein's weaver is reactive: it mutates and dispatches effects after client requests, but it has no weaver-owned clock trigger. Libraries that need proactive wakeups must each invent durable timer storage, restart re-arming, reload-safe teardown, handler resolution, and missed-fire behavior. That duplicates subtle daemon lifecycle work and risks divergent policies.

Pull-based `wake-at` attributes plus views remain the default answer when a poller exists. The gap this feature owns is the no-poller case: trusted code needs a minimal durable primitive that says "at instant `T`, invoke handler symbol `H` with payload `P`".

## PROP-weaver-scheduler-001.P2 Goals

- **PROP-weaver-scheduler-001.G1:** Add a minimal weaver-owned scheduler primitive for durable `wake-at` records keyed by a stable user key, handler symbol, and persisted payload.
- **PROP-weaver-scheduler-001.G2:** Persist scheduler rows in dedicated weaver-owned SQLite tables, not strand records, matching `RFC-009.Q1.OUT`.
- **PROP-weaver-scheduler-001.G3:** Re-arm pending wakes on weaver startup and on trusted config reload without leaking or double-arming timers.
- **PROP-weaver-scheduler-001.G4:** Resolve handlers by fully qualified symbols in the weaver JVM/runtime classloader and fail loudly for malformed schedules or unresolvable handlers.
- **PROP-weaver-scheduler-001.G5:** Deliver due wakes at least once through the existing serialized async dispatch path by default, so clock-triggered handlers do not race post-commit event handlers through a parallel mutation lane.
- **PROP-weaver-scheduler-001.G6:** Expose data-first trusted introspection for pending wakes, next wake, recent fires, and recent failures.

## PROP-weaver-scheduler-001.P3 Non-goals

- **PROP-weaver-scheduler-001.NG1:** No cron, recurrence DSL, retry/backoff policy, jitter, or DST policy in core; userland handlers may schedule their next wake.
- **PROP-weaver-scheduler-001.NG2:** No exactly-once delivery guarantee. Handlers must be idempotent and tolerate at-least-once invocation.
- **PROP-weaver-scheduler-001.NG3:** No public mutating `strand schedule` CLI in this feature. Scheduling remains trusted REPL/config/API surface.
- **PROP-weaver-scheduler-001.NG4:** No workflow timer/deadline gates in this feature; card `a00co` builds those later on top of the primitive.
- **PROP-weaver-scheduler-001.NG5:** No strand graph representation for scheduler rows; they must not appear in `list`, `ready`, relation traversals, burn/update paths, or user task queries.
- **PROP-weaver-scheduler-001.NG6:** Do not reposition proactive scheduling as the default timing pattern. Documentation must keep pull-based `wake-at` plus views as the recommended default when a poller exists, with the scheduler framed as the no-poller escape hatch.

## PROP-weaver-scheduler-001.P4 Proposed scope

- **PROP-weaver-scheduler-001.S1:** Define and implement the scheduler durable storage contract: pending/completed/cancelled/failed rows, wake instant, key, handler symbol, payload, attempt/fire/failure metadata, and schema initialization.
- **PROP-weaver-scheduler-001.S2:** Provide a blessed explicit-runtime namespace, tentatively `skein.api.scheduler.alpha`, for `schedule!`, `cancel!`, and introspection helpers.
- **PROP-weaver-scheduler-001.S3:** Bind scheduler lifecycle to weaver startup, stop, and trusted config reload with re-arm and cleanup semantics that do not leak timers or double-fire from duplicate arms.
- **PROP-weaver-scheduler-001.S4:** Use the weaver's serialized async execution lane as the default handler dispatch model so scheduler handlers and post-commit event handlers do not race through independent mutation lanes.
- **PROP-weaver-scheduler-001.S5:** Adopt minimal missed-fire and clock-jump policy: any pending wake whose `wake-at <= now` is due and enqueued at least once; malformed schedules reject loudly; recurrence and catch-up batching stay userland.
- **PROP-weaver-scheduler-001.S6:** Include a controllable clock/test seam so timing, due-row, restart, and reload behavior can be tested deterministically.
- **PROP-weaver-scheduler-001.S7:** Add read-only introspection in trusted Clojure first. Public read-only CLI introspection is optional only if it stays thin and can be delivered without expanding the mutating CLI surface.

## PROP-weaver-scheduler-001.P5 Open questions

- **PROP-weaver-scheduler-001.Q1:** What persisted payload encoding should the scheduler use so trusted Clojure callers get useful data while the durable SQLite row remains inspectable and fail-loud on unsupported shapes?
- **PROP-weaver-scheduler-001.Q2:** Is read-only public CLI introspection worth including in the first implementation slice, or should it remain REPL/API-only until real users ask for it?
- **PROP-weaver-scheduler-001.Q3:** Does the implementation need any safety-tick mechanism beyond precise timer re-arm and due-row enqueue to close a concrete lost-arm window?
