# Task 1: Engine feedback shapes

**Document ID:** `TASK-Werg-001`

## TASK-Werg-001.P1 Scope

Type: AFK

Implement DELTA-Werg-001 items D1.1–D1.6 in `src/skein/spools/workflow.clj`,
with `src/skein/spools/workflow.md` and tests updated in the same change.

## TASK-Werg-001.P2 Must implement exactly

- **TASK-Werg-001.MI1 (D1.1):** `start!`, `complete!`, and `choose!` return `{:ready [step-view ...] :done boolean}`. `next-steps`/`next-step` keep their current shapes (pure queries). Update every in-repo caller: `skein.spools.devflow` wrappers, `.skein/config.clj` ops (mechanical: their `:ready` key now nests the map — ops should splice `:ready`/`:done` into the op result), all tests, and the worked examples in workflow.md §8 / devflow.md §4.
- **TASK-Werg-001.MI2 (D1.2):** `checkpoint` choice maps accept `:input` — a vector of `{:key kw :required bool :description str}` maps (unknown keys in these maps fail loudly, like other builder opts). Store under the choice's entry in `workflow/choice-details` (string-keyed, JSON-safe). `choice-details`/`choice-detail` surface it. `choose!` validates before any mutation: missing required input keys fail loudly with the declaration in ex-data.
- **TASK-Werg-001.MI3 (D1.3):** step views returned by `next-steps`, `next-step`, and inside mutation results carry `:run-id` (from the run root). Bare `step-view` on a strand without run context stays unchanged.
- **TASK-Werg-001.MI4 (D1.4):** `(advance! run-id)` / `(advance! run-id opts)`: when the resolved ready step is a checkpoint, `opts` must contain `:choice` (fail loudly otherwise) and it dispatches to `choose!` with `:input` (default `{}`) and pass-through opts; when it is a step, `:choice` must be absent (fail loudly otherwise) and it dispatches to `complete!` with pass-through opts (`:notes`, `:attributes`, `:step`, `:by`). Returns the D1.1 result shape.
- **TASK-Werg-001.MI5 (D1.5):** procedure join auto-close: when `complete!`/`choose!` closes the last active inner step beneath a `workflow/role "procedure"` join, the join closes in the same `batch/apply!` transaction (attribute e.g. `workflow/outcome-by "engine"` for provenance). Joins never surface as ready work; chained joins (a call whose last step is itself a call) cascade in the same transaction. The run done-rule text in workflow.md §4 stays valid: procedure strands still must be closed — the engine now closes them.
- **TASK-Werg-001.MI6 (D1.6):** `checkpoint` with `:kind :human` auto-stamps `workflow/hitl "true"`; explicit caller-supplied `workflow/hitl` attributes in devflow stage definitions are removed, as are the `[HITL] ` title prefixes (fix any tests asserting those titles).

## TASK-Werg-001.P3 Done when

- **TASK-Werg-001.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes (all namespaces).
- **TASK-Werg-001.DW2:** New tests cover: result-shape `:done true` on the final mutation of a run; declared choice input surfaced and enforced; `:run-id` present on views; `advance!` on both step kinds plus both loud-failure modes; a `call` procedure run completing without the join ever being ready or manually closed; hitl auto-stamp.
- **TASK-Werg-001.DW3:** workflow.md sections for run lifecycle (§4), checkpoints (§5), attribute table (§7), and the §8 example reflect the new shapes; devflow.md §4 example updated.

## TASK-Werg-001.P4 Out of scope

- **TASK-Werg-001.OS1:** Registry routing, `:revise`, describe/history/archive (tasks 2–3).
- **TASK-Werg-001.OS2:** New `.skein` ops or queries (task 4) — only mechanical result-shape fixes there.
- **TASK-Werg-001.OS3:** Git commits; do not commit or revert anything.

## TASK-Werg-001.P5 References

- **TASK-Werg-001.REF1:** [DELTA-Werg-001](../specs/workflow-spool-contract.delta.md) D1.1–D1.6; [PLAN-Werg-001](../workflow-ergonomics.plan.md) PH1, R1, R2, TC1.
- **TASK-Werg-001.REF2:** `src/skein/spools/workflow.clj` (`choose!` transaction + `route-plan`, `complete!`, `close-run-if-done!`, `step-view`, builder opt validation via its unknown-keys helper), `test/skein/spools/workflow_test.clj`, `test/skein/spools/devflow_test.clj`.
