# Scheduler REPL/API Delta

**Document ID:** `DELTA-weaver-scheduler-repl-001` **Status:** Promoted (into `SPEC-003.P4a`, `SPEC-003.C58`, `SPEC-003.C59a`–`C59d`) **Feature:** `weaver-scheduler` **Root spec:** [`SPEC-003 REPL API`](../../../specs/repl-api.md) **Related RFCs:** [`RFC-009 Weaver Scheduler Primitive`](../../../rfcs/2026-06-29-weaver-scheduler.md) **Related proposal:** [`PROP-weaver-scheduler-001`](../proposal.md)

## DELTA-weaver-scheduler-repl-001.P1 Summary

Add a blessed explicit-runtime scheduler namespace for trusted Clojure callers. The first public surface is REPL/API-only: schedule, cancel, and data-first introspection helpers. No mutating public `strand schedule` command is introduced in this feature.

## DELTA-weaver-scheduler-repl-001.P2 Contract changes

- **DELTA-weaver-scheduler-repl-001.CC1:** `SPEC-003.P4` gains `skein.api.scheduler.alpha`, a blessed source-visible explicit-runtime namespace for trusted config, activated spools, and live in-weaver REPL forms.
- **DELTA-weaver-scheduler-repl-001.CC2:** The namespace takes the target runtime as its first argument for every operation. It never performs connected-client routing or implicit ambient lookup; callers capture runtime with `skein.api.current.alpha/runtime` only at trusted entry points.
- **DELTA-weaver-scheduler-repl-001.CC3:** `(schedule! runtime wake)` persists or replaces one durable wake. `wake` is a map containing a stable `:key`, absolute `:wake-at`, fully qualified `:handler` symbol, and optional `:payload`. Unknown keys, malformed values, unsupported payloads, and unresolvable handlers fail loudly.
- **DELTA-weaver-scheduler-repl-001.CC4:** `(cancel! runtime key)` cancels a pending wake by stable key and returns data-first cancellation information. Missing keys fail loudly; no separately named idempotent cancel helper ships.
- **DELTA-weaver-scheduler-repl-001.CC5:** Read helpers expose serializable scheduler state: pending wakes, next wake, recent fires, recent cancellations, and recent failures. Names are finalized in implementation, but the API must not expose functions, executors, timer handles, or raw JDBC rows.
- **DELTA-weaver-scheduler-repl-001.CC6:** Handler symbols resolve in the weaver JVM/runtime classloader, matching event/view/pattern conventions. The API persists handler identity as a symbol/string representation that can survive restart; closures and anonymous functions are rejected.
- **DELTA-weaver-scheduler-repl-001.CC7:** Scheduler handlers receive one context map and may perform trusted side effects, including Skein API calls. They run through the weaver's serialized async lane. Return values are ignored; exceptions are captured in scheduler failure introspection.
- **DELTA-weaver-scheduler-repl-001.CC8:** The default recommendation remains pull-based `wake-at` strand attributes plus views when a poller exists. The scheduler API is documented as the no-poller proactive wakeup escape hatch.

## DELTA-weaver-scheduler-repl-001.P3 Design decisions

### DELTA-weaver-scheduler-repl-001.D1 REPL/API-first, no initial read-only CLI

Initial introspection stays in trusted Clojure so the feature can prove storage, lifecycle, and failure semantics before expanding the public command surface. A thin read-only CLI can be added later if a concrete user needs it; mutating CLI scheduling remains out of scope.

### DELTA-weaver-scheduler-repl-001.D2 Explicit runtime keeps scheduler ownership visible

Scheduler state belongs to one weaver runtime and one selected workspace. Keeping the API explicit-runtime matches the rest of `skein.api.*.alpha` and avoids hidden cross-world scheduling.

## DELTA-weaver-scheduler-repl-001.P4 Open questions

- **DELTA-weaver-scheduler-repl-001.Q1:** Final helper names for the read surfaces (`scheduled`, `pending`, `recent-fires`, `recent-failures`, etc.) are left to implementation, but the returned shape must stay data-first and serializable.
