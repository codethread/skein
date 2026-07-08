# Workflow Ergonomics Proposal

**Document ID:** `PROP-Werg-001` **Last Updated:** 2026-07-02 **Related RFCs:** None **Related root specs:** [REPL API](../../specs/repl-api.md) (spool namespace reservation only); primary contracts are the spool docs [workflow.md](../../../src/skein/spools/workflow.md) and [devflow.md](../../../src/skein/spools/devflow.md)

## PROP-Werg-001.P1 Problem

Dogfooding the shipped workflow/devflow spools end-to-end (driving a real feature through every stage from the CLI) surfaced friction for all three personas: users cannot see where a run is or what a workflow looks like without reading source; workflow authors repeat boilerplate and rely on fragile stringly routing; driving agents hit invisible input requirements, ambiguous empty results, silent stage cutovers, and redundant ceremony.

## PROP-Werg-001.P2 Goals

- **PROP-Werg-001.G1:** A user (or an agent on their behalf) can ask "what does workflow X look like?" and "where is feature Y and what happened so far?" and get runtime answers, not source archaeology.
- **PROP-Werg-001.G2:** A workflow author expresses a revisable stage without hand-writing revision/entry wrapper fns, and routes between stages through stable names rather than raw fn symbols.
- **PROP-Werg-001.G3:** A driving agent gets everything needed in-band: declared choice-input requirements, done-state on every mutation result, stage context on ready steps, one verb to advance, and no bookkeeping-only steps to close by hand.
- **PROP-Werg-001.G4:** Long-lived workspaces stay clean: finished runs can be squashed to digests, and plain `strand ready` need not expose workflow plumbing to naive agents.

## PROP-Werg-001.P3 Non-goals

- **PROP-Werg-001.NG1:** No enforcement of HITL checkpoints — decision ownership stays provenance-only (TEN-002).
- **PROP-Werg-001.NG2:** No new CLI subcommands — the public CLI stays a thin JSON surface; new capabilities arrive as trusted ops/REPL fns (TEN-006).
- **PROP-Werg-001.NG3:** No change to the checkpoint-molecule branching model — a decision point remains a checkpoint; making branches lighter than a stage is out of scope.
- **PROP-Werg-001.NG4:** No durable registry storage — workflow-name registration stays weaver-lifetime runtime state, re-registered from startup config like queries and patterns.

## PROP-Werg-001.P4 Proposed scope

- **PROP-Werg-001.S1:** Checkpoint choices may declare expected input (keys, required-ness, description); the declaration is surfaced with choice details and violations fail loudly before any mutation.
- **PROP-Werg-001.S2:** Every run-mutating operation reports the run's resulting state (ready steps plus done-ness) in one result, removing the empty-vector ambiguity.
- **PROP-Werg-001.S3:** Ready-step views carry run context (run id, stage) so stage cutovers are visible in-band.
- **PROP-Werg-001.S4:** One advance verb drives a run regardless of whether the current step is a step or a checkpoint.
- **PROP-Werg-001.S5:** Procedure join steps close themselves when their inner work closes; agents never complete bookkeeping strands.
- **PROP-Werg-001.S6:** `:kind :human` is the single canonical HITL signal; derived attributes are stamped by the engine and prose prefixes are dropped.
- **PROP-Werg-001.S7:** Workflows can be registered under stable names; routing targets and revision loops reference names or declarative revise directives instead of raw fn symbols, collapsing the per-stage wrapper boilerplate.
- **PROP-Werg-001.S8:** A workflow definition can be described as data (stage map: steps, checkpoints, choices, routing targets) without pouring; a run's history (stages, outcomes, notes, in order) can be projected from the graph; a finished run can be archived to a digest.
- **PROP-Werg-001.S9:** The repo `.skein` surface exposes the above through ops and queries, including a ready-work query that excludes workflow plumbing, with agent guidance updated.

## PROP-Werg-001.P5 Open questions

- **PROP-Werg-001.Q1:** Resolved at sign-off: auto-closing procedure joins changes done-semantics for the run; accepted because joins carry no work and their manual closure was pure ceremony.
- **PROP-Werg-001.Q2:** Resolved at sign-off: mutation results change shape from vector to map (`{:ready [...] :done bool}`) — a breaking spool API change, acceptable under TEN-000 alpha with tests/docs updated in the same change.
