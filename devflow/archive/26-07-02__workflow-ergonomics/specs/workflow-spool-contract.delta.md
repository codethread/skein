# Workflow/Devflow Spool Contract Delta

**Document ID:** `DELTA-Werg-001`
**Last Updated:** 2026-07-02
**Status:** Merged
**Target contracts:** [`src/skein/spools/workflow.md`](../../../../src/skein/spools/workflow.md), [`src/skein/spools/devflow.md`](../../../../src/skein/spools/devflow.md), repo-local [`.skein/AGENTS.md`](../../../../.skein/AGENTS.md)

The spool contract docs live beside their code and are updated in the same
change as the implementation; this delta enumerates the contract-visible
changes so plan/tasks stay honest and finish/archive can verify them.

## DELTA-Werg-001.D1 Engine (`skein.spools.workflow`)

- **D1.1 Mutation result shape (breaking):** `start!`, `complete!`, `choose!` return `{:ready [step-view ...] :done boolean}` instead of a bare vector. `next-steps` stays a vector query.
- **D1.2 Choice input declarations:** choice maps accept `:input [{:key k :required bool :description s} ...]`; stored in `workflow/choice-details`; surfaced by `choice-details`/`choice-detail`; `choose!` fails loudly before mutation when required keys are missing.
- **D1.3 Run context on views:** `step-view`s returned by run-scoped fns carry `:run-id`; devflow adds `:stage`.
- **D1.4 `advance!`:** one verb — `(advance! run-id)` completes a ready step; `(advance! run-id {:choice k ...})` chooses on a ready checkpoint; opts pass through (`:input`, `:notes`, `:step`, `:by`, `:attributes`).
- **D1.5 Procedure joins auto-close:** when the last inner step of a `call` expansion closes, the join step closes in the same transaction; joins never appear as ready work.
- **D1.6 HITL canonicalization:** `checkpoint :kind :human` auto-stamps `workflow/hitl "true"`; kind is the canonical signal.
- **D1.7 Named workflow registry:** `register-workflow!`/`workflow-definition` (weaver-lifetime, startup-registered); `:next` accepts a registered keyword name in addition to a symbol.
- **D1.8 Declarative revision:** a choice may declare `:revise {:params {...}}` to re-pour the current root's own `workflow/definition` with authoritative param overrides merged over context — no hand-written revision wrapper fns.
- **D1.9 `describe`:** compile-time projection of a workflow definition (steps, checkpoints, choices with input/routing targets, conditions, gates) without pouring.
- **D1.10 `run-history`:** ordered projection of a run's molecules (all states): stage roots, checkpoint outcomes (+ `by`, input), step notes, timestamps.
- **D1.11 `archive-run!`:** squash a finished (no active root) run's molecules into one closed digest strand.

## DELTA-Werg-001.D2 Devflow (`skein.spools.devflow`)

- **D2.1** Stage constructors registered under stable names; forward routing via registry names; revise choices via `:revise` — `<stage>-revision-workflow` wrapper fns removed.
- **D2.2** `[HITL]` title prefixes removed; explicit `workflow/hitl` attributes removed (derived per D1.6).
- **D2.3** Abort's required `{:reason ...}` declared via D1.2 choice input.
- **D2.4** Wrappers return the D1.1 result shape; `next-steps` views carry `:stage`; new `describe`, `history`, `archive!` wrappers.

## DELTA-Werg-001.D3 Repo config (`.skein`)

- **D3.1** Ops updated to new result shapes; new ops `devflow-advance`, `devflow-describe`, `devflow-history`, `devflow-archive`.
- **D3.2** Named query `work`: ready-facing query excluding workflow plumbing (`workflow/role` molecule/digest) so naive `strand ready --query work` shows only actionable strands; AGENTS.md and the strand skill updated to teach it.
