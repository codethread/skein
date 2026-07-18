# v1-api-format plan

**Document ID:** `PLAN-Vaf-001`

**Last updated:** 2026-07-18

## PLAN-Vaf-001.P1 Slices

- **PLAN-Vaf-001.S1:** Spec the form contract — SPEC-003.C19a (converted-module shape: public-only `alpha`, public-first order, sibling `.internal`, 96-column wrap) and SPEC-005.C5b (internal namespaces are exclusionary-internal). Done when both clauses read against the shipped tree.
- **PLAN-Vaf-001.S2:** Mechanical gate — `quality.api-form` under `scripts/`, called from `quality.conventions-check`, with the shrinking `pending` set and its stale/conformant self-checks; ratchet edges pinned by `skein.quality.conventions-check-test`. Done when `make lint` is green with `format` converted and all sixteen pending entries genuinely nonconformant.
- **PLAN-Vaf-001.S3:** Judgment seat — `source-form` in the `change-review` roster, brief authored as a `|`-margin block through `skein.api.format.alpha/reflow`; docs-style and clojure skills updated to carry the two-sided wrap rule. Done when the roster registers via `runtime/reload!` after landing.
- **PLAN-Vaf-001.S4:** Exemplar conversion — audit `skein.api.format.alpha` (surface usage, order, wrap; no plumbing so no internal file) and pin the blessed `fill`/`reflow` contract with `skein.api.format.alpha-test`. Done when the focused cold run passes.
- **PLAN-Vaf-001.S5:** Playbook handoff — epic `9nu0q` and the sixteen open feature cards updated to carry the conversion playbook (P2) and pointers to the clause, the gate, and this folder.
- **PLAN-Vaf-001.S6:** Validation and review — focused cold runs, `make fmt-check lint reflect-check docs-check`, then a `change-review` roster pass plus an ad hoc run of the new seat's brief over this branch as the dogfood.

## PLAN-Vaf-001.P2 Conversion playbook for the sibling cards

Each remaining card under epic `9nu0q` converts one module and should run this loop:

1. Claim the card, take a worktree, `devflow-start` the feature; read SPEC-003.C19a and this folder first.
2. Audit the public surface: for every public var in `alpha.clj`, find real callers (`rg` across `src`, `spools`, `test`, `.skein`, and the external spool pins). Trivial or derivable functions are removal candidates — removals reshape in-contract surface, so update the owning root spec in the same change (SPEC-005.C9).
3. Classify every top-level form: contract-bearing (public promised var, intentionally public spec registration — these stay in `alpha.clj`, spec keys unchanged) or plumbing (moves to `skein.api.<module>.internal`). Specs are public where they identify a public fn's interface: the named argument/return specs or `s/fdef` register from alpha; reusable sub-specs may live in internal when they are plumbing, but every public fn has something in alpha naming its shape. Reorder `alpha.clj` public-first (`declare` where needed). Do not create an internal file for a module with no plumbing. Hazards the counsel named: tests or `with-redefs` reaching into privates you are moving; Var identity and metadata that callers alias; never re-export an internal var from alpha — public vars stay defined in alpha. Moved helpers become plain public defns in internal: SPEC-005.C5b draws the contract line at the tier, not Clojure var visibility, so do not re-privatize them (reviewers: an internal var being public is not a surface finding). The dependency rules are gated: internal never requires alpha, and only the module's own alpha (or a test) requires its internal.
4. Hard-wrap docstrings and comments; nothing past column 96 in the module's files; every public alpha var carries a docstring.
5. Delete the module's entry from `quality.api-form/pending` — this is your card's deliberate act; nothing forces it. `make lint` then gates the conversion: any leftover private var, undocumented public var, wide line, or dependency-rule breach fails.
6. `make api-docs` when any docstring changed; focused cold test run for the module's namespaces; the full quality gates.
7. `strand agent review <task-id> --roster change-review --cwd <worktree> --commit-range <base>..HEAD` — the `source-form` seat carries the form lens.

## PLAN-Vaf-001.P3 Risks

- **PLAN-Vaf-001.R1:** The pending-set ratchet assumes cards land one module at a time; landing is serialized under the merge lock, and two cards deleting adjacent set lines is at worst a trivial rebase conflict.
- **PLAN-Vaf-001.R2:** Moving vars into `.internal` can break require cycles or test requires that reached into `alpha.clj` privates; the per-module cold run and the gated dependency rules are the catch.
- **PLAN-Vaf-001.R3:** `format` has no plumbing, so this card never exercises the extraction pattern; the counsel recommends `return-shape` (16 privates, self-contained) as the deliberate second card, making the extraction exemplar early while fifteen cards can still copy it.
