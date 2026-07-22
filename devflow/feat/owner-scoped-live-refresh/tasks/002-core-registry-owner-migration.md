# Task 2: Migrate core registries to owner partitions

**Document ID:** `TASK-Olr-002`

## TASK-Olr-002.P1 Scope

Type: AFK

Migrate CLI op, named-query, weave-pattern, lifecycle-hook, and event-handler storage onto the owner-registry kernel. Own `src/skein/core/weaver/runtime.clj`, `src/skein/core/weaver/access.clj`, `src/skein/api/weaver/alpha.clj`, `src/skein/api/graph/alpha.clj`, `src/skein/api/patterns/alpha.clj`, `src/skein/api/events/alpha.clj`, `src/skein/api/hooks/alpha.clj`, their internal storage helpers, and focused registry mutation tests.

## TASK-Olr-002.P2 Must implement exactly

- **TASK-Olr-002.MI1:** Give every direct registration write an explicit validated owner and preserve the current effective read/dispatch return shapes.
- **TASK-Olr-002.MI2:** Add owner-complete replacement for each registry using its existing entry validators. Validate all five candidate families before publishing any family for a module contribution.
- **TASK-Olr-002.MI3:** Preserve built-in help under a system owner and require explicit authorization for deliberate workspace replacement.
- **TASK-Olr-002.MI4:** Preserve existing effective reads and dispatch behavior through temporary internal projections; Task 25 owns snapshot concurrency and expanded owner introspection.
- **TASK-Olr-002.MI5:** Keep temporary internal adapters for current callers only as needed to maintain a green branch; mark them for removal in Task 16 and do not publish them as the target contract.

## TASK-Olr-002.P3 Done when

- **TASK-Olr-002.DW1:** Existing op/query/pattern/hook/event behavior remains green under owner-backed storage.
- **TASK-Olr-002.DW2:** New tests prove owner deletion, unauthorized collision, authorized override/restoration, and no unrelated-owner changes for all five registries.
- **TASK-Olr-002.DW3:** `clojure -M:test skein.alpha-test skein.api.events.alpha-test skein.api.hooks.alpha-test skein.macros.patterns-test` passes.
- **TASK-Olr-002.DW4:** `make fmt-check lint reflect-check` passes.

## TASK-Olr-002.P4 Out of scope

- **TASK-Olr-002.OS1:** Do not implement module collection, source reload, spool-domain registries, or final public API removals.

## TASK-Olr-002.P5 References

- **TASK-Olr-002.REF1:** `DELTA-OlrDrt-001.CC2–CC4/CC9`, `DELTA-OlrRepl-001.CC10`, and `PLAN-Olr-001.PH1`.
