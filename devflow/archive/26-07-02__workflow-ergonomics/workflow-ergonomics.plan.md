# Workflow Ergonomics Plan

**Document ID:** `PLAN-Werg-001`
**Feature:** `workflow-ergonomics`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** none directly; spool contracts are [workflow.md](../../../src/skein/spools/workflow.md) / [devflow.md](../../../src/skein/spools/devflow.md)
**Feature specs:** [specs/workflow-spool-contract.delta.md](./specs/workflow-spool-contract.delta.md)
**Status:** Shipped
**Last Updated:** 2026-07-02

## PLAN-Werg-001.P1 Goal and scope

Deliver the ergonomics fixes enumerated in [PROP-Werg-001](./proposal.md) across
the workflow engine, the devflow spool, and the repo `.skein` surface, with the
contract-visible changes tracked in [DELTA-Werg-001](./specs/workflow-spool-contract.delta.md).

## PLAN-Werg-001.P2 Approach

- **PLAN-Werg-001.A1:** Change the engine bottom-up in three slices (feedback shapes → routing/authoring → introspection), then rewire the devflow spool and repo config on top. Each slice lands with its tests and contract-doc updates in the same change; the suite stays green after every task.
- **PLAN-Werg-001.A2:** Work happens in the current checkout on `main` (single-branch mode agreed with the user; the base includes the uncommitted spool-alignment change set). Implementation tasks are delegated to subagents one at a time; the coordinator validates and closes each task strand.
- **PLAN-Werg-001.A3:** The running canonical weaver is only reloaded/restarted by the coordinator between slices; subagents validate with `clojure -M:test` (and `-M:smoke` for the final slice) without touching user weavers, using disposable `--workspace` worlds if they need a live weaver.

## PLAN-Werg-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Werg-001.AA1 | `src/skein/spools/workflow.clj` (+ `workflow.md`, `test/skein/spools/workflow_test.clj`) | result shapes, choice input, advance!, join auto-close, hitl derivation, registry/:revise routing, describe/run-history/archive-run! |
| PLAN-Werg-001.AA2 | `src/skein/spools/devflow.clj` (+ `devflow.md`, `test/skein/spools/devflow_test.clj`) | registry-named stages, wrapper removal, prefix/attr cleanup, describe/history/archive wrappers |
| PLAN-Werg-001.AA3 | `.skein/config.clj` (+ `.skein/AGENTS.md`, `test/skein/config_test.clj`) | op result shapes, new ops, `work` query |
| PLAN-Werg-001.AA4 | `.agents/skills/strand/SKILL.md` | teach `ready --query work` for naive agents |

## PLAN-Werg-001.P4 Contract and migration impact

- **PLAN-Werg-001.CM1:** Breaking spool API change (mutation result shape) and done-semantics change (join auto-close), per DELTA-Werg-001.D1.1/D1.5; TEN-000 alpha, no migration path, all in-repo callers updated in the same change. No public CLI or root-spec contract changes.

## PLAN-Werg-001.P5 Implementation phases

### PLAN-Werg-001.PH1 Engine feedback shapes

Outcome: D1.1–D1.6 implemented and tested; workflow.md updated; devflow spool and config compile against the new shapes (mechanical updates only).

### PLAN-Werg-001.PH2 Engine routing and authoring

Outcome: D1.7–D1.8 implemented and tested; devflow rewired (D2.1–D2.3): registry names, `:revise` choices, wrappers removed, prefixes/attrs cleaned.

### PLAN-Werg-001.PH3 Introspection

Outcome: D1.9–D1.11 implemented and tested; devflow describe/history/archive wrappers (D2.4).

### PLAN-Werg-001.PH4 Userland surface

Outcome: D3.1–D3.2: `.skein` ops/queries updated and extended, AGENTS.md and strand skill updated, config tests green.

### PLAN-Werg-001.PH5 Validation and finish

Outcome: full `clojure -M:test`, `cli go test ./...`, `clojure -M:smoke`, `code-review --deep` actioned; weaver reloaded; a live verification run exercises the new surface end-to-end.

## PLAN-Werg-001.P6 Validation strategy

- **PLAN-Werg-001.V1:** `clojure -M:test` green after every task; smoke green at finish.
- **PLAN-Werg-001.V2:** A live devflow run driven through the new ops must show: declared abort input in choice details, `:done` on mutation results, `:stage` on views, no ready join steps, `advance!` driving both step kinds, history/describe/archive outputs.

## PLAN-Werg-001.P7 Risks and open questions

- **PLAN-Werg-001.R1:** Join auto-close interacts with routed-choice force-closes and the done-check; mitigated by closing joins inside the same `complete!`/`choose!` transaction and keeping the done-rule unchanged (joins are `procedure` role and must still be closed — just by the engine).
- **PLAN-Werg-001.R2:** Result-shape change fans out across many tests; mitigated by landing it first (PH1) so later slices build on the new shape.
- **PLAN-Werg-001.R3:** `:revise` needs the root's `workflow/definition` to be resolvable; fail loudly when absent (TEN-003) and document that `:revise` requires `opts :definition` (devflow always sets it).

## PLAN-Werg-001.P8 Task context

- **PLAN-Werg-001.TC1:** Read `devflow/TENETS.md`, `devflow/PHILOSOPHY.md`, `src/skein/spools/workflow.md`, `src/skein/spools/devflow.md`, and this feature's proposal + delta before coding. The engine is userland spool code over `skein.repl`/`skein.graph.alpha`/`skein.batch.alpha`; it owns no privileged state. Every behavior change updates the matching contract doc section and tests in the same change. The base tree contains uncommitted work — do not revert or commit it.

## PLAN-Werg-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Werg-001.DN1 Task 1: Engine feedback shapes — 2026-07-02

- Landed D1.1–D1.6. Mutation results are `{:ready [...] :done bool}`; `advance!` at workflow.clj:1240; joins close via ready-join detection inside the same mutation transaction; hitl auto-stamped by the checkpoint builder (explicit attrs and `[HITL]` prefixes removed from devflow). Suite 226/0. Coordinator re-verified independently.

### PLAN-Werg-001.DN2 Coordination discovery: delegated agents and strands — 2026-07-02

- Task 1's subagent never touched the strand graph; coordination fell back to a /tmp sentinel file. From Task 2 on, delegated agents receive their strand id and record `progress`/`status=implemented` attributes on it; the coordinator watches the graph and remains the only closer (verify-then-close). Task 4 should encode this delegation convention in `.skein/AGENTS.md` (delegated-agent contract: read your strand, append progress attrs, never close, never touch siblings) so it outlives this session.

### PLAN-Werg-001.DN3 Task 2: Engine routing and authoring — 2026-07-02

- Landed D1.7–D1.8 + D2.1–D2.3: named workflow registry (`register-workflow!`/`workflow-definition`/`registered-workflows`), `:revise {:params …}` re-pour with stage-local param shedding, devflow stages route by registered names, five `*-revision-workflow` + `enter-*` wrappers deleted, abort `:reason` declared as choice input. Suite 232/0. Agent coordinated via strand attrs (progress/status) — the delegated-agent contract works.

### PLAN-Werg-001.DN4 Task 3: Run introspection — 2026-07-02

- Landed D1.9–D1.11 + D2.4: `describe`, `run-history`, and `archive-run!` in the workflow engine; devflow `describe`/`history`/`archive!` wrappers; contract docs and tests for descriptions, revision/routing history, and digest archiving. Coordinator re-verified with `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` (240 tests, 1421 assertions, 0 failures/errors).

### PLAN-Werg-001.DN5 Task 4: Userland surface — 2026-07-02

- Landed D3.1–D3.2: repo `.skein` ops for `devflow-advance`/`describe`/`history`/`archive`, existing devflow mutation ops now return spliced `:ready`/`:done`, `work` query hides molecule/procedure/digest plumbing, and `.skein/AGENTS.md` plus the strand skill document the ready loop and delegated-agent contract. Coordinator re-verified with `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` (242 tests, 1444 assertions, 0 failures/errors).

### PLAN-Werg-001.DN6 Task 5: Validation, review, live verification, and finish — 2026-07-02

- Full validation passed after review fixes: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` (243 tests, 1455 assertions), `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`. Deep review found missing devflow `:stage` on ready views and weak later-stage route coverage; fixed via devflow result decoration, `devflow/advance!`, config op routing through the devflow wrapper, docs, and regression tests. Canonical weaver was restarted and live run `werg-live-1783001864` verified described abort input, `:done`, `:stage`, no ready procedure join, unified advance on checkpoints/steps, history, and archive digest. Devflow run `workflow-ergonomics` was completed and archived to digest `q063d`; no scope was cut.
