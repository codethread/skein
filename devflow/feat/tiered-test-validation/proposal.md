# Tiered Test Validation Proposal

**Document ID:** `PROP-Ttv-001`
**Last Updated:** 2026-07-08
**Related RFCs:** [RFC-016 Test Concurrency and Multi-Runtime JVM Support](../../rfcs/2026-07-03-test-concurrency.md)
**Related root specs:** None

## PROP-Ttv-001.P1 Problem

Every documented validation path runs the full test suite. AGENTS.md prescribes
`flock -w 3600 /tmp/skein-test.lock clojure -M:test` as the only safe test
command, so plan and task authors rationally copy it into every AFK slice's
Done-when block. A serial queue of N slices then pays N full-suite runs under a
lock contended by sibling features — on the macros feature (2026-07-08, ~6.5h
wall, 9 slices) this was a large share of the timeline.

There is no supported focused alternative: `skein.test-runner/-main` accepts no
namespace selection, so a focused run requires an ad hoc
`-Sdeps :extra-paths` eval that is fragile in practice — a wrong ad hoc
classpath produced 19 false errors for a coordinator, and the trick silently
drops `:test`-alias git deps such as `devflow.spool`. The suite's safety
structure (parallel pool, serial JVM-global islands, add-libs subprocess
shards per RFC-016) lives only inside the runner, so nothing outside it can
honor island placement.

Evidence: macros tasks 001/002 Done-when blocks, plan PLAN-Srm-001.V1 ("full
suite after every slice"), session timeline analysis on kanban card `n7aya`;
the currently pending `attr-scaling-ship-now` queue repeats the same
full-suite-per-slice prescription.

## PROP-Ttv-001.P2 Goals

- **PROP-Ttv-001.G1:** A blessed focused test entrypoint exists: agents can run
  a chosen subset of test namespaces with the correct `:test`-alias classpath
  while the runner keeps honoring parallel/serial/shard island placement.
- **PROP-Ttv-001.G2:** A focused run of an undeclared test namespace fails
  loudly (TEN-003), so island placement stays the single source of truth. This
  adds no new registration burden: the runner has no auto-discovery, so a test
  namespace already must be declared in one of its island sets before the full
  suite runs it at all; the error message names the known namespaces so
  authors of new test namespaces are routed to the declaration site.
- **PROP-Ttv-001.G3:** A documented tiered validation convention: focused
  namespace runs per slice; the full locked suite exactly once as the final
  acceptance gate of a queue and again at land `merge-local-verify`.
- **PROP-Ttv-001.G4:** Guidance that plan/task authors actually copy is updated
  to prescribe the tiers: AGENTS.md testing rules, the agents spool
  `about`-manual policy prose, and the pending `attr-scaling-ship-now` task
  queue.
- **PROP-Ttv-001.G5:** Focused runs and the exclusive full-suite lock compose:
  concurrent focused runs from sibling agents must not starve a locked full
  suite's timing budgets, and vice versa, without reintroducing
  one-suite-at-a-time serialization for small runs.

## PROP-Ttv-001.P3 Non-goals

- **PROP-Ttv-001.NG1:** No change to what the full suite covers, its island
  assignments, or its wall-clock profile; RFC-016's structure is consumed, not
  revised.
- **PROP-Ttv-001.NG2:** No per-var/per-test selection, watch modes, or test
  frameworks; namespace granularity only (TEN-004).
- **PROP-Ttv-001.NG3:** The land workflow's `merge-local-verify` full gate and
  CI are unchanged; tiers apply to per-slice validation, not to landing.
- **PROP-Ttv-001.NG4:** No changes to the external devflow.spool or the
  devflow plugin's authoring references — neither prescribes full-suite runs;
  the prescriptions live in this repo's guidance.

## PROP-Ttv-001.P4 Proposed scope

- **PROP-Ttv-001.S1:** The test runner gains a focused mode selecting declared
  test namespaces, preserving each namespace's island semantics (parallel,
  serial, or its add-libs shard subprocess) and failing loudly on unknown
  namespaces.
- **PROP-Ttv-001.S2:** The tiered validation convention is documented where
  agents read testing rules (AGENTS.md), stating when a slice uses a focused
  run, when the full locked suite is required, and how locking works for each
  tier.
- **PROP-Ttv-001.S3:** The agents spool manual's task-sizing/policy prose
  prescribes tiered validation for delegated task contracts.
- **PROP-Ttv-001.S4:** The pending `attr-scaling-ship-now` task queue is swept
  to the tiers: per-slice Done-when blocks become focused runs; its final
  validation-sweep task keeps the full locked suite.

## PROP-Ttv-001.P5 Open questions

- **PROP-Ttv-001.Q1:** The full validation contract for focused runs, decided
  in the plan: lock discipline against the exclusive full-suite lock (shared
  `flock -s`, a lower `-w` wait, or no lock), whether focused runs of
  timing-sensitive serial namespaces need `SKEIN_TEST_AWAIT_SCALE` guidance,
  and explicit confirmation that focused mode is additive — CI's
  `clojure -M:test` and the land `merge-local-verify` full gate invoke the
  unchanged parent mode. Standardize on the util-linux `flock` path the task
  templates already use (`/opt/homebrew/opt/util-linux/bin/flock`; macOS's
  own `flock` lacks `-w`).
- **PROP-Ttv-001.Q2:** Whether a focused run that selects namespaces from an
  add-libs shard should spawn the shard subprocess (faithful, slower) or may
  run them in-process when the selection touches exactly one shard — decide in
  the plan; correctness of add-libs basis handling wins over speed.
