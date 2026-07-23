# Task 4: Build module graph and live refresh coordinator

**Document ID:** `TASK-Olr-004`

## TASK-Olr-004.P1 Scope

Type: AFK

Implement internal stable module declarations, layered startup collection, full/targeted refresh orchestration, contribution staging/publication, resource reconcile dispatch, and joined result assembly in `skein.core.weaver.runtime` plus focused runtime tests.

## TASK-Olr-004.P2 Must implement exactly

- **TASK-Olr-004.MI1:** Validate a module declaration keyed independently of namespace/file/root, with exactly one source target, root prerequisites, `:after`, contribution/reconcile symbols, and required policy.
- **TASK-Olr-004.MI2:** Evaluate `init.clj` then `init.local.clj` under a collector that stages complete declarations without loading module source or applying effects. Validate duplicate keys and the dependency DAG before mutation.
- **TASK-Olr-004.MI3:** Full refresh detects added/changed/removed modules; targeted refresh accepts known non-empty keys and includes affected dependents. Whole-module deletion occurs only through successful full-graph omission.
- **TASK-Olr-004.MI4:** Orchestrate approved-root synchronization, changed source reload, contribution evaluation, all-core-family validation/publication, then optional reconcile. Preserve prior core contribution on contribution failure; report reconcile effects/degradation without generic rollback.
- **TASK-Olr-004.MI5:** Never globally clear registries, module state, event queue, recent failures, or unrelated spool-state. Required failure affects overall status but does not erase unrelated owners.
- **TASK-Olr-004.MI6:** Produce one top-level applied/partial/unchanged/refused result with per-module/root outcomes, residuals, conflicts, and remedies.

## TASK-Olr-004.P3 Done when

- **TASK-Olr-004.DW1:** Disposable-runtime tests cover layered collection, ordering, full/targeted refresh, add/change/remove, unknown/empty target refusal, optional skip, required failure, contribution retention, reconcile degradation, and unrelated-owner stability.
- **TASK-Olr-004.DW2:** Tests prove event queue/failure history survives and that direct startup uses the same empty-runtime path.
- **TASK-Olr-004.DW3:** Partial source load and hard conflict produce truthful scoped outcomes without pretending rollback.
- **TASK-Olr-004.DW4:** `clojure -M:test skein.weaver-test skein.spools-test skein.api.runtime.alpha-test` and `make fmt-check lint` pass.

## TASK-Olr-004.P4 Out of scope

- **TASK-Olr-004.OS1:** Do not publish final alpha functions, convert production spools/config, or remove temporary current lifecycle functions.

## TASK-Olr-004.P5 References

- **TASK-Olr-004.REF1:** `DELTA-OlrDrt-001.CC1/CC5–CC9/CC16`; `DELTA-OlrRepl-001.CC3–CC7`; `PLAN-Olr-001.A2–A4`.
