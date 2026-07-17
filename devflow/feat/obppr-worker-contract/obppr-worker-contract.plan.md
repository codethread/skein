# Workspace-configurable worker contract Plan

**Document ID:** `PLAN-Wct-001`
**Feature:** `obppr-worker-contract`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md) (SPEC-004.C96 seam reload semantics, unchanged)
**Feature specs:** none — the preamble contract lives in the agent-run/delegation spool docs (the in-contract reference tier per [alpha-surface.md](../../specs/alpha-surface.md)); SPEC-004.C96 already covers set-once registrations generically, and the new slot adopts the review-contract setter shape rather than adding spec surface
**Status:** Draft
**Last Updated:** 2026-07-17

## PLAN-Wct-001.P1 Goal and scope

Split the injected worker contract into an engine-owned always-on core and
workspace-configurable remainders, mirroring the review-contract seam, so non-skein
workspaces stop receiving skein lore, broken instructions, and task-workflow conventions
their runs don't use ([proposal](./proposal.md), gh#81).

## PLAN-Wct-001.P2 Approach

- **PLAN-Wct-001.A1:** agent-run owns `generic-worker-contract` (PROP-Wct-001@2.S1): the
  engine-coupled invariants only — never close your assigned strand / never mutate
  siblings or parents; kill by PID only (one generic sentence: argv-carried prompts make
  pattern kills strafe siblings); keep delegation shallow, one mutator per file scope.
  The durable-notes discipline is not repeated in the core: the `[agent-run context]`
  block already carries the generic note one-liner, and the richer findings line
  (rendered through `skein.api.notes.alpha/writer-ref->prompt`) moves into delegation's
  task fragment where `<task-id>` renders concretely.
  The headless `preamble` includes it unconditionally, before any workspace text. The
  hardcoded spawn-helpers line naming `--harness explore` is dropped from injected text
  entirely: the `[agent-run context]` block already carries the spawn/await one-liners,
  and concrete harness names are workspace data (PROP-Wct-001@2.S4, PHILOSOPHY "no harness
  is home").
- **PLAN-Wct-001.A2:** A second workspace slot, `set-task-contract!` /
  `default-task-contract-text`-style, shaped exactly like the review-contract seam
  (non-blank-or-nil validation, nil restores the default, no conflict recording): its text
  is appended to the headless preamble only when the run has a `serves` target
  (PROP-Wct-001@2.G3/S2), resolved via the existing single-run serves lookup in
  `effective-prompt`'s reach. The engine substitutes the literal `<task-id>` placeholder
  in the registered text with the actual served strand id, so workers get a concrete
  command instead of placeholder prose. Default is nil: no task fragment unless the
  workspace registers one. `set-preamble-extension!` keeps its exact shipped semantics
  (SPEC-004.C96 conflict recording) as the unconditional workspace slot; delegation
  simply stops claiming it (PROP-Wct-001@2.S4).
- **PLAN-Wct-001.A3:** delegation's `worker-contract` var becomes the exported
  task-workflow fragment (read your assigned strand and notes; `progress=`;
  `status=implemented` gate; the durable-findings line via `writer-ref->prompt`; the
  pkill war story moves to the delegation README beside the generic rule). `install!`
  registers nothing on either preamble slot. Skein's own
  `.skein/harnesses.clj` `install!` opts in with `set-task-contract!` on the exported
  fragment (PROP-Wct-001@2.S5), keeping this repo's agent-plan
  `awaiting_verification` flow intact.
- **PLAN-Wct-001.A4:** `.skein/workflows.clj` `pipeline-task-prompt` drops its
  `worker-contract` prepend if pipeline runs carry `serves` edges (verify at
  implementation via treadle's delegate path); if they don't, the prepend stays as the
  workspace's own composition and the double-injection disappears anyway because
  delegation no longer claims the extension slot.

## PLAN-Wct-001.P3 Affected areas

| ID                | Area                                              | Expected change                                                        |
| ----------------- | ------------------------------------------------- | ---------------------------------------------------------------------- |
| PLAN-Wct-001.AA1  | `spools/agent-run/src/skein/spools/agent_run.clj` | `generic-worker-contract`, task-contract slot + setter, preamble composition, serves-scoped injection |
| PLAN-Wct-001.AA2  | `spools/delegation/src/skein/spools/delegation.clj` | `worker-contract` reduced to task-workflow fragment; `install!` stops claiming the seam |
| PLAN-Wct-001.AA3  | `.skein/harnesses.clj`, `.skein/workflows.clj`    | Workspace opt-in registration; pipeline prompt de-duplication           |
| PLAN-Wct-001.AA4  | `spools/delegation/README.md`, `spools/delegation.cookbook.md`, `spools/agent-run/README.md` | Contract text, explore-harness example, pkill anecdote relocation      |
| PLAN-Wct-001.AA5  | `spools/*.api.md`                                 | Regenerated via `make api-docs`                                         |
| PLAN-Wct-001.AA6  | `test/skein/agent_run_test.clj`, `test/skein/delegation_test.clj` | Coverage for core-always/extension/task-slot composition and install behavior |

## PLAN-Wct-001.P4 Contract and migration impact

- **PLAN-Wct-001.CM1:** `skein.spools.delegation/worker-contract` changes content
  (task-workflow fragment only) — TEN-000 alpha, no migration; consuming workspaces that
  want the old always-on behavior register the exported fragment themselves.
- **PLAN-Wct-001.CM2:** Any workspace already calling `set-preamble-extension!` stops
  colliding with delegation's claim; the conflict-recording behavior of that seam is
  unchanged (SPEC-004.C96).
- **PLAN-Wct-001.CM3:** New public agent-run surface: one setter pair for the
  task-contract slot plus the exported `generic-worker-contract`. Names follow the
  review-contract precedent; no new op, verb, or flag.

## PLAN-Wct-001.P5 Implementation phases

- **PLAN-Wct-001.PH1:** agent-run core + task-contract slot + preamble composition +
  focused tests (independently shippable: with no registrations, behavior is core-only).
- **PLAN-Wct-001.PH2:** delegation reduction + install change + focused tests.
- **PLAN-Wct-001.PH3:** workspace config opt-in + pipeline de-dup + docs sync + api-docs
  regen.

## PLAN-Wct-001.P6 Validation strategy

- Per-phase gate: cold focused `clojure -M:test skein.agent-run-test skein.delegation-test`
  (exact ns names per test files).
- Acceptance: full locked suite (`flock -w 3600 /tmp/skein-test.lock clojure -M:test`),
  `(cd cli && go test ./...)`, `clojure -M:smoke`, `make spool-suite-gate`,
  `make fmt-check lint reflect-check docs-check`, `make api-docs` with clean
  `git status --short`.
- Behavioral check in a disposable workspace: a bare spawn's preamble carries core only;
  a serving run's preamble carries core + task fragment with the real task id; this
  repo's `.skein` config keeps the full contract for serving runs.

## PLAN-Wct-001.P7 Task context

Single-worktree feature; phases are sequential (PH2 depends on PH1's exports, PH3 on
both). All three phases share the two spool source files' scope; no parallel mutators.

## PLAN-Wct-001.P8 Developer Notes

- The interactive preamble and `:preamble? false` paths are untouched (PROP-Wct-001@2.NG1);
  keep the interactive-preamble docstring's rationale accurate after the core split.
- `notes/writer-ref->prompt` stays the single renderer of the note-writing fragment.
- Watch delegation's `about-doc`/`prime-doc` exports and `delegation_test.clj:153`
  assertions on `:worker-contract` content.
