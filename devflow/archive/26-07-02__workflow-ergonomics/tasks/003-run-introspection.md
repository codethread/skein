# Task 3: Run introspection

**Document ID:** `TASK-Werg-003`

## TASK-Werg-003.P1 Scope

Type: AFK

Implement DELTA-Werg-001 items D1.9–D1.11 in `skein.spools.workflow` and the devflow wrappers (D2.4).

## TASK-Werg-003.P2 Must implement exactly

- **TASK-Werg-003.MI1 (D1.9):** `(describe workflow params)` — compile-time projection, no strands written: `{:name … :steps [{:id :title :kind :depends-on :condition :gate :choices [{:key :label :description :input :next|:revise} …]} …]}`. Loop/call expansion and condition filtering apply, so the description matches what would pour. Also `(describe workflow)` for definitions whose params all have defaults; fails loudly listing missing required params otherwise.
- **TASK-Werg-003.MI2 (D1.10):** `(run-history run-id)` — projection over all molecules ever poured for the run (any state), ordered by creation: per molecule `{:root {:id :title :stage(devflow attr if present) :state :created_at} :events [{:type :step-closed|:choice|:gate-closed :id :title :outcome :by :input :notes :at} …]}`. Uses `updated_at` for event ordering; read-only; fails loudly for an unknown run.
- **TASK-Werg-003.MI3 (D1.11):** `(archive-run! run-id)` / `(archive-run! run-id {:title … :attributes …})` — fails loudly if the run still has an active root; squashes every molecule subgraph of the run into one closed digest strand (via the existing `squash!` machinery or equivalent single digest) stamped `workflow/run-id`, `workflow/role "digest"`, plus a compact JSON-safe summary of the history (stage titles + checkpoint outcomes). Returns the digest strand.
- **TASK-Werg-003.MI4 (D2.4):** devflow wrappers keyed by feature: `describe` (accepts a registered stage key or defaults to the full cycle), `history`, `archive!`. Docstrings per repo convention.

## TASK-Werg-003.P3 Done when

- **TASK-Werg-003.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- **TASK-Werg-003.DW2:** Tests cover: describe of a devflow stage (checkpoint choices with declared input visible; condition-excluded step absent when params exclude it); history of a run with one revise loop and one routed hand-off showing ordered outcomes and notes; archive failing on an active run, then succeeding after completion and leaving exactly one digest (plus burned molecules) for the run.
- **TASK-Werg-003.DW3:** workflow.md gains a §6-adjacent "Describing and archiving" contract section; devflow.md documents the three wrappers.

## TASK-Werg-003.P4 Out of scope

- **TASK-Werg-003.OS1:** `.skein` ops (task 4); commits.

## TASK-Werg-003.P5 References

- **TASK-Werg-003.REF1:** [DELTA-Werg-001](../specs/workflow-spool-contract.delta.md) D1.9–D1.11, D2.4; [PLAN-Werg-001](../workflow-ergonomics.plan.md) PH3.
- **TASK-Werg-003.REF2:** `compile`, `squash!`, `current-root`, `root-strand-exists?` in `src/skein/spools/workflow.clj`; `skein.graph.alpha/subgraph`.
