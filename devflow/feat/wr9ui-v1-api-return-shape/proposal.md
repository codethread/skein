# v1 return-shape form conversion proposal

**Document ID:** `PROP-Vrs-001`

**Last updated:** 2026-07-18

## PROP-Vrs-001.P1 Problem

`skein.api.return-shape.alpha` carries three promised fns (`validate!`, `explain`, `check!` — the SPEC-005.C2 in-contract list) behind sixteen private helpers and two private tables, in helpers-first order with three lines past column 96. Card `wr9ui` converts it to the SPEC-003.C19a form contract as the epic's first real plumbing extraction — the exemplar the counsel asked for (PROP-Vaf-001.P5).

## PROP-Vrs-001.P2 Approach

Mechanical form conversion under the playbook at [PLAN-Vaf-001.P2](../g1men-v1-api-format/g1men-v1-api-format.plan.md); no behavior change, no surface change, so no root-spec delta beyond the pending-set deletion (SPEC-005.C9 internal-only).

- **PROP-Vrs-001.A1:** Surface audit: all three public fns are load-bearing (`weaver.alpha` validates and explains registry returns, `test.alpha/check-op-return!` checks, guild consumes) — all stay; nothing else is public today, so nothing to cut.
- **PROP-Vrs-001.A2:** All plumbing moves to `skein.api.return-shape.internal`; `alpha.clj` keeps the three documented fns delegating thinly, public-first. The published ex-data key stays literally `:skein.api.return-shape.alpha/error` in internal — the qualified-key hazard the counsel named; consumers and error shapes see zero change. "No surface change" means promised surface: moved helpers are plain public defns in internal, which SPEC-005.C5b places outside the contract regardless of var visibility.
- **PROP-Vrs-001.A3:** Wrap the three wide lines; delete `"return-shape"` from `quality.api-form/pending` so `make lint` gates the conversion.
- **PROP-Vrs-001.A4:** Existing `skein.api.return-shape.alpha-test` runs unchanged through the public surface and is the behavior lock; the gates and the `change-review` roster (first pass with the registered `source-form` seat) validate the form.

## PROP-Vrs-001.P3 Recalibration

The first cut of this conversion moved every helper to internal, leaving alpha as three delegation husks. The owner reviewed it against reference files of theirs and recalibrated the epic's form contract: the public fns must carry the top-level composition — the story — with helpers as named steps (file-local privates or internal, placement is taste), and internal reserved for plumbing that would drown the file. Encoded in this branch: SPEC-003.C19a rewritten, the `quality.api-form` no-privates-in-alpha check dropped (docstrings, width, dependency rules, and the ratchet remain mechanical), the `source-form` seat brief now hunts delegation husks, the clojure skill gained clojure-ised story-shape examples, and this module was reworked — grammar walking in alpha, error construction and scalar semantics in internal.
