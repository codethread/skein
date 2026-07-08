# Weaver Scheduler Plan

**Document ID:** `PLAN-weaver-scheduler-001` **Status:** Reviewed **Feature:** `weaver-scheduler` **Last Updated:** 2026-07-05 **Proposal:** [`PROP-weaver-scheduler-001`](./proposal.md) **Related RFCs:** [`RFC-009 Weaver Scheduler Primitive`](../../rfcs/2026-06-29-weaver-scheduler.md) **Related specs:** [`DELTA-weaver-scheduler-runtime-001`](./specs/daemon-runtime.delta.md), [`DELTA-weaver-scheduler-repl-001`](./specs/repl-api.delta.md), [`SPEC-004 Weaver Runtime`](../../specs/daemon-runtime.md), [`SPEC-003 REPL API`](../../specs/repl-api.md)

## PLAN-weaver-scheduler-001.P1 Goal and scope

Deliver the minimal no-poller scheduler primitive: trusted code can persist a wake keyed by caller identity, absolute instant, handler symbol, and payload; the weaver re-arms pending wakes across startup and reload; due wakes invoke handlers at least once through the existing serialized async lane; trusted Clojure callers can inspect pending/history/failure state as data.

Out of scope remains cron/recurrence/retry policy, exactly-once delivery, workflow timer gates, public mutating CLI scheduling, and representing wakes as strand records.

## PLAN-weaver-scheduler-001.P2 Approach

- **PLAN-weaver-scheduler-001.A1:** Keep persistence in `skein.core.db`: add scheduler schema initialization and focused scheduler CRUD helpers beside existing strand persistence. Scheduler SQL uses dedicated tables and JSON text for payloads/metadata where needed; unsupported payload values fail at the API boundary rather than being coerced.
- **PLAN-weaver-scheduler-001.A2:** Add an internal scheduler runtime module under `src/skein/core/weaver` that owns validation, clock access, due-row selection, timer arming, and dispatch coordination. It should be usable from tests with a deterministic clock/test seam.
- **PLAN-weaver-scheduler-001.A3:** Store in-memory scheduler resources through `skein.api.runtime.alpha/spool-state` with an explicit version and `:close-fn`. Startup may allocate scheduler state after database initialization, but pending-row handler resolution and timer arming happen only after selected startup files finish loading, so config/spool-provided handlers are available. Reload retrieves/reinitializes the versioned state and re-arms from pending rows after config reload without module-level atoms.
- **PLAN-weaver-scheduler-001.A4:** Reuse the existing event worker serialization instead of adding an independent mutation worker. If implementation needs a small internal envelope abstraction, keep it inside the weaver runtime and preserve current event handler behavior.
- **PLAN-weaver-scheduler-001.A5:** Add `skein.api.scheduler.alpha` as the blessed explicit-runtime namespace. It delegates to weaver API functions for `schedule!`, `cancel!`, and introspection; helper names may settle during implementation but remain data-first and explicit-runtime.
- **PLAN-weaver-scheduler-001.A6:** Initial public CLI behavior does not change. Read-only CLI introspection is deferred unless implementation discovers it is essentially free and stays a thin op; tasks should not depend on it.

## PLAN-weaver-scheduler-001.P3 Affected areas

- **PLAN-weaver-scheduler-001.AA1:** `src/skein/core/db` — scheduler tables, JSON/payload persistence helpers, pending/due/history/failure queries, schedule/cancel state transitions.
- **PLAN-weaver-scheduler-001.AA2:** `src/skein/core/weaver` — runtime startup/stop/reload integration, timer lifecycle, deterministic clock seam, shared async dispatch integration.
- **PLAN-weaver-scheduler-001.AA3:** `src/skein/api/weaver.alpha` — trusted weaver API entry points backing scheduler helpers and introspection.
- **PLAN-weaver-scheduler-001.AA4:** `src/skein/api/scheduler/alpha.clj` — explicit-runtime public API namespace with namespace docstring.
- **PLAN-weaver-scheduler-001.AA5:** `src/skein/repl.clj` and `src/skein/userland/alpha.clj` — only if implementation chooses to add terse helpers; not required for the MVP.
- **PLAN-weaver-scheduler-001.AA6:** `test/skein` — unit/integration coverage for persistence, lifecycle, dispatch, failure capture, and graph isolation.
- **PLAN-weaver-scheduler-001.AA7:** `devflow/specs` and feature-local specs — promote deltas when shipped; update root specs only at finish.

## PLAN-weaver-scheduler-001.P4 Implementation phases

### PLAN-weaver-scheduler-001.PH1 Persistence and validation core

Add scheduler storage tables and low-level helpers. Define the row shape, key uniqueness/replacement semantics, payload encoding, timestamp normalization, status transitions, and introspection query shapes. Cover schema creation, schedule/cancel/list persistence, malformed input rejection, and proof that scheduler rows do not appear in normal strand operations.

### PLAN-weaver-scheduler-001.PH2 Runtime lifecycle and clock dispatch

Add runtime scheduler state with versioned spool-state, close-on-stop cleanup, deterministic clock/test seam, next-wake arming, due-row enqueue, post-config startup re-arm, and post-config reload re-arm. Integrate due wake delivery with the existing serialized async lane and capture handler resolution/exception failures in scheduler history. Queue-full on the shared lane is treated as transient: the wake remains/returns pending, a failure signal is recorded, and the scheduler re-arms instead of losing at-least-once delivery.

### PLAN-weaver-scheduler-001.PH3 Blessed API namespace and introspection

Expose `skein.api.scheduler.alpha` over the internal implementation: schedule, cancel, pending/next/history/failure reads. Keep every call explicit-runtime and data-first. Add examples or docstrings that frame scheduler as the no-poller escape hatch and warn that handlers must be idempotent.

### PLAN-weaver-scheduler-001.PH4 End-to-end hardening and docs/spec reconciliation

Exercise restart/reload behavior in disposable worlds or in-process test worlds, verify no duplicate fires, verify queue/failure behavior, run the standard validation gate, and prepare root spec promotion notes. Decide whether any discovered safety tick is necessary; absent a demonstrated lost-arm window, keep it out.

## PLAN-weaver-scheduler-001.P5 Validation strategy

- **PLAN-weaver-scheduler-001.V1:** Unit tests for scheduler storage: schema creation, JSON payload encoding/decoding, loud validation errors, schedule replacement/update behavior, cancel behavior, pending/due/history/failure query ordering, and bounded pruning of recent fire/cancel/failure history (initially latest 100 per category).
- **PLAN-weaver-scheduler-001.V2:** Runtime tests with deterministic clock: due wakes fire when time advances, future wakes do not fire early, overdue persisted rows fire at least once on post-config re-arm, reload/startup do not duplicate delivery, and shared-queue saturation does not permanently drop a due wake before handler invocation.
- **PLAN-weaver-scheduler-001.V3:** Handler tests: fully qualified symbol resolution succeeds under the weaver classloader, unresolvable symbols and handler exceptions land in failure introspection without crashing the worker.
- **PLAN-weaver-scheduler-001.V4:** Isolation tests: scheduler rows are absent from `strands`, `ready`, query traversal, burn/update paths, and normal strand events except for mutations the handler itself performs.
- **PLAN-weaver-scheduler-001.V5:** Standard gate before implementation sign-off: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`, with clean/intended `git status --short`.

## PLAN-weaver-scheduler-001.P6 Task context

- **PLAN-weaver-scheduler-001.TC1:** Storage must use dedicated weaver-owned SQLite tables, per `RFC-009.Q1.OUT`; do not encode scheduler wakes as strands.
- **PLAN-weaver-scheduler-001.TC2:** Missed-fire policy is intentionally small: pending `wake-at <= now` is due and delivered at least once; recurrence and catch-up behavior are userland handler work.
- **PLAN-weaver-scheduler-001.TC3:** Shared async lane is a requirement for the first slice; do not add a second mutation worker unless a review checkpoint explicitly changes the design.
- **PLAN-weaver-scheduler-001.TC4:** Scheduler lifecycle should build on versioned spool-state and close-on-stop, not a parallel registry or module-level atom.
- **PLAN-weaver-scheduler-001.TC5:** Initial introspection is REPL/API-only. If a later task proposes a read-only CLI, it needs a scoped note explaining why the extra public surface is worth it.
- **PLAN-weaver-scheduler-001.TC6:** `a00co` workflow timer/deadline gates are a follow-up and must not be pulled into this implementation.

## PLAN-weaver-scheduler-001.P7 Developer Notes

- **2026-07-05 b3vno:** Drafted after proposal approval and spec deltas. Payload encoding and exact read helper names remain implementation choices constrained by fail-loud durable encoding and data-first introspection. Safety tick remains deliberately excluded unless implementation finds a concrete lost-arm window.
- **2026-07-05 b3vno:** Addressed plan review findings: re-arm is post-config so spool handlers resolve after startup/reload; queue-full preserves at-least-once by keeping wakes pending and re-arming; recent history is bounded/pruned; smoke is part of implementation sign-off.
