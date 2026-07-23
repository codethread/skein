# Task 13: Convert delegation and bench registries

**Document ID:** `TASK-Olr-013`

## TASK-Olr-013.P1 Scope

Type: AFK

Continue in the agent-harness feature worktree. Convert `ct.spools/delegation` reviewer rosters and core contributions plus `ct.spools/bench` harness/suite/extractor declarations to owner-complete refresh. Preserve panel/review behavior and bench live resources.

## TASK-Olr-013.P2 Must implement exactly

- **TASK-Olr-013.MI1:** Partition reviewer rosters by owner. Review fan-out snapshots the current roster when created; each resulting agent process still resolves its harness at launch under Task 12 semantics.
- **TASK-Olr-013.MI2:** Contribute delegation ops, queries, patterns, event/executor declarations, and vocab under the stable delegation owner. Deleting a declaration removes it without global reload.
- **TASK-Olr-013.MI3:** Partition bench harnesses, suites, and extractors as declarations. Preserve bench engine/executor/semaphore/in-flight handles and close function in versioned state.
- **TASK-Olr-013.MI4:** Preserve panel blackboard/round barriers, council/review presets, task delegation, retry, serving-run rules, spend, return declarations, and frozen CLI vocabulary.
- **TASK-Olr-013.MI5:** Remove install-only activation from all three roots once their contribution/reconcile entries are used; this peer worktree owns its README, contract docs, docstrings, and generated API files for later release preparation.
- **TASK-Olr-013.MI6:** Continue using the exact immutable Skein baseline commit recorded by Task 12; if a later Skein API change is required, stop and route it through a Skein task before updating the baseline and rerunning both peer tasks.

## TASK-Olr-013.P3 Done when

- **TASK-Olr-013.DW1:** Tests prove roster and bench declaration add/replace/delete/override, live fan-out snapshot behavior, and no loss of active bench or agent-run handles during refresh.
- **TASK-Olr-013.DW2:** Existing delegation, subagent executor, review/council, bench lifecycle, return coverage, and state-shape tests remain green against the Skein feature worktree.
- **TASK-Olr-013.DW3:** `clojure -M:test ct.spools.delegation-test ct.spools.subagent-test ct.spools.bench-test ct.spools.bench-metrics-test`, repository format/lint/API-doc gates, and full test alias pass; no peer release is tagged.

## TASK-Olr-013.P4 Out of scope

- **TASK-Olr-013.OS1:** Do not rename `strand agent`, `agent-plan`, `agent-failures`, `:subagent`, attributes, or release v8.

## TASK-Olr-013.P5 References

- **TASK-Olr-013.REF1:** `delegation/src/ct/spools/delegation.clj`, `bench/src/ct/spools/bench.clj`, `SPEC-005.C10`, and `PLAN-Olr-001.AA8`.
