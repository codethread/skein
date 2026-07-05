# Weaver Scheduler Runtime Delta

**Document ID:** `DELTA-weaver-scheduler-runtime-001`
**Status:** Promoted (into `SPEC-004.P10d`, `SPEC-004.C1`, `SPEC-004.C91a`, `SPEC-004.C97`–`C105`)
**Feature:** `weaver-scheduler`
**Root spec:** [`SPEC-004 Weaver Runtime`](../../../specs/daemon-runtime.md)
**Related RFCs:** [`RFC-009 Weaver Scheduler Primitive`](../../../rfcs/2026-06-29-weaver-scheduler.md)
**Related proposal:** [`PROP-weaver-scheduler-001`](../proposal.md)

## DELTA-weaver-scheduler-runtime-001.P1 Summary

Add one weaver-owned scheduler subsystem for proactive durable wakeups. The scheduler stores wake rows in dedicated SQLite tables, arms the next due wake inside runtime-owned state, and dispatches due handlers through the same serialized async mutation lane used by post-commit events. It is a substrate for the no-poller case, not a replacement for pull-based `wake-at` attributes plus views.

## DELTA-weaver-scheduler-runtime-001.P2 Contract changes

- **DELTA-weaver-scheduler-runtime-001.CC1:** Amend `SPEC-004.C1`: a weaver owns exactly one runtime scheduler state in addition to the existing registries and event dispatch state. Scheduler state is runtime-owned, not module-level; it is acquired through `skein.api.runtime.alpha/spool-state` with a declared version and a close function.
- **DELTA-weaver-scheduler-runtime-001.CC2:** Amend `SPEC-004.P3a`: weaver storage initialization creates dedicated scheduler tables beside the strand graph. Scheduler rows are not strands, never appear in strand `list`/`ready`/query/traversal/burn/update paths, and are owned by the weaver runtime contract rather than user graph lifecycle.
- **DELTA-weaver-scheduler-runtime-001.CC3:** A scheduler wake row contains at least a stable caller key, absolute wake instant, fully qualified handler symbol, persisted payload, status, attempt/fire/failure bookkeeping, and creation/update timestamps. Valid statuses include pending, firing/completed terminal history, cancelled, and failed; implementation may choose exact table split as long as pending and recent history/failure introspection are data-first.
- **DELTA-weaver-scheduler-runtime-001.CC4:** Scheduling validates loudly before durable write: key must be stable and non-blank/non-nil, `wake-at` must be an absolute instant shape the API accepts, handler must be a fully qualified symbol resolvable in the weaver JVM/runtime classloader, and payload encoding must be supported. Unsupported payload shapes and malformed timestamps are domain errors, not coerced defaults.
- **DELTA-weaver-scheduler-runtime-001.CC5:** Startup and trusted config reload re-arm pending scheduler rows only after selected startup files finish loading, so handlers supplied by approved spools/config are available before resolution. Scheduler resources may initialize earlier, but durable-row handler resolution and timer arming are post-config steps. Re-arm cancels/cleans existing in-memory timers first and rebuilds from durable pending rows without leaking timer threads or double-arming rows. Runtime stop closes scheduler resources before storage closes via the spool-state close-on-stop lifecycle.
- **DELTA-weaver-scheduler-runtime-001.CC6:** Reload safety builds on `SPEC-004.C95` versioned spool-state and `SPEC-004.C96` reload behavior. The scheduler must not introduce a parallel module-level atom or bespoke reload registry; if scheduler state shape changes, its spool-state version changes so stale executors/timers are closed and replaced.
- **DELTA-weaver-scheduler-runtime-001.CC7:** Due detection is minimal: any pending wake with `wake-at <= now` is due and must be enqueued at least once. Malformed persisted rows fail loudly into failure introspection. Core does not implement cron, recurrence, retry/backoff, jitter, DST policy, missed-fire catch-up batching, or exactly-once semantics.
- **DELTA-weaver-scheduler-runtime-001.CC8:** Scheduler handler invocation shares the existing serialized async dispatch worker by default. Clock-triggered handlers and post-commit event handlers therefore observe one mutation lane rather than racing through independent worker pools. Handler-resolution failures are captured loudly in scheduler failure state. Queue-full is a transient delivery failure: it records an introspection failure but leaves or returns the durable wake to pending, then re-arms it, so saturation cannot permanently drop a due wake before handler invocation.
- **DELTA-weaver-scheduler-runtime-001.CC9:** Handler functions receive one context map containing the runtime, wake row metadata, stable key, wake instant, attempt information, and decoded payload. Return values are ignored. Handler exceptions do not crash the weaver; they are captured in bounded recent failure/history data, and idempotency remains the handler author's responsibility.
- **DELTA-weaver-scheduler-runtime-001.CC10:** The runtime exposes data-first scheduler introspection for pending wakes, next wake, recent fires, recent cancellations, and recent failures. Recent history is bounded; the initial retention target is the latest 100 fires, cancellations, and failures per category, pruned during the write that records a new history entry. Introspection returns serializable maps and never exposes executor, timer, thread, or function objects.
- **DELTA-weaver-scheduler-runtime-001.CC11:** Tests must use deterministic clock/test seams. The runtime contract requires tests for schema initialization, schedule/cancel/list persistence, startup re-arm, reload re-arm without duplicate fires, malformed schedule rejection, unresolvable handler failure capture, handler exception capture, and absence of scheduler rows from normal strand operations.

## DELTA-weaver-scheduler-runtime-001.P3 Design decisions

### DELTA-weaver-scheduler-runtime-001.D1 Dedicated tables keep the strand graph semantic

Scheduler rows are durable runtime coordination state, not user work items. Dedicated tables avoid polluting task queries, readiness, relation traversals, graph burn/update semantics, and userland attribute conventions while still giving the weaver enough durable state to re-arm after restart.

### DELTA-weaver-scheduler-runtime-001.D2 Shared async lane is the default safety boundary

A separate scheduler worker would create a second mutation lane with ordering/race behavior different from post-commit events. Treating the clock as another trigger into the existing serialized worker keeps the minimal primitive easier to reason about and matches `RFC-009.PH2`.

### DELTA-weaver-scheduler-runtime-001.D3 No safety tick in the core contract until a concrete lost-arm window exists

The planned contract requires precise re-arm after scheduling, startup, and reload plus deterministic tests around due-row enqueue. A periodic safety tick is not part of the initial contract; if implementation reveals a concrete lost-arm window that precise re-arm cannot close, the plan may add the smallest safety tick with documented rationale before task sign-off.

## DELTA-weaver-scheduler-runtime-001.P4 Open questions

- **DELTA-weaver-scheduler-runtime-001.Q1:** Exact payload encoding remains a plan-level decision. It must be durable, inspectable enough for SQLite/debugging, and fail loudly on unsupported trusted Clojure values.
