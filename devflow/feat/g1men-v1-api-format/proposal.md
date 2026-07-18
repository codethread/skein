# v1 api-module form proposal

**Document ID:** `PROP-Vaf-001`

**Last updated:** 2026-07-18

## PROP-Vaf-001.P1 Problem

The v1 tag is close, and the seventeen `skein.api.*.alpha` namespaces are the promise it stamps (SPEC-005.C2). Their form does not match their weight: private helpers sit inline above the public surface (runtime carries 27 private fns in an 805-line file, cli 22 in 485, weaver 20 in 619), so the promised functions arrive last and a reviewer pays for the plumbing before reaching the contract. Doc prose is wrapped in some files and runs wide in others (90 lines past column 96 across the tier). Nothing encodes a target shape, so the sixteen sibling refactor cards under epic `9nu0q` would each re-derive one.

## PROP-Vaf-001.P2 Goals

- **PROP-Vaf-001.G1:** One file is the promise. A converted module's `skein.api.<mod>.alpha` namespace holds only contract-bearing top-level forms — public promised vars with docstrings, and intentionally public spec registrations — ordered public-first: the surface at the top, lower-level detail below, `declare` where definition order fights reading order. Plumbing moves to a sibling `skein.api.<mod>.internal` namespace, which the exclusionary rule in SPEC-005.P1 already places outside the contract — no tier-membership change. A module with no plumbing ships no internal file. Two dependency rules are mechanical: an internal namespace never requires an alpha namespace, and only a module's own alpha (or a test) requires its internal.
- **PROP-Vaf-001.G2:** Wrapped prose is a gate, not a request. `quality.api-form` (run by `quality.conventions-check`) checks converted modules: no private or undocumented public vars in `alpha.clj`, and no source line past column 96. A `pending` set names the sixteen unconverted modules; each sibling card deletes its own entry as a deliberate act, and a stale entry is a finding.
- **PROP-Vaf-001.G3:** A new `source-form` seat in the `change-review` roster reviews what the gate cannot: reading order in changed Clojure, wrap quality of docstrings and comments, and the markdown counter-rule — markdown prose runs full length for the IDE to wrap, never hard-wrapped. The docs-style skill's line-length section is updated to carry the same two-sided rule.
- **PROP-Vaf-001.G4:** `skein.api.format.alpha` is the first converted module and the exemplar: surface audited against real usage (`fill` and `reflow` are both load-bearing across roster, workflow, batteries, repl, and workspace config — both stay), prose wrapped, ordering already public-first, no plumbing so no internal file.
- **PROP-Vaf-001.G5:** The playbook reaches the next agents through the surfaces they already read: a form clause beside SPEC-003.C19, the `pending` set in the gate source, and the sixteen card bodies updated to name the clause, the ratchet step, and this folder as the worked example.

## PROP-Vaf-001.P3 Non-goals

- **PROP-Vaf-001.NG1:** No split of `skein.core.*` or the DB layer. The internal-file pattern is scoped to the `skein.api.*` tier; extending it further is a later decision.
- **PROP-Vaf-001.NG2:** No repo-wide line-length rule. The 96-column gate applies to converted api modules only; everything else keeps the community-style defaults.
- **PROP-Vaf-001.NG3:** No mechanical check of reading order. Ordering is judgment and belongs to the `source-form` seat; the gate checks only what is mechanical (privacy, width, the pending set).
- **PROP-Vaf-001.NG4:** No surface removal in this card. Both public fns of the format module are used; removals in sibling cards follow SPEC-005.C9 through their own spec updates.

## PROP-Vaf-001.P4 Scope

- **PROP-Vaf-001.S1:** `devflow/specs/repl-api.md` gains SPEC-003.C19a stating the converted-module form contract; `devflow/specs/alpha-surface.md` P3 gains one sentence naming `skein.api.*.internal` as exclusionary-internal.
- **PROP-Vaf-001.S2:** `scripts/quality/conventions_check.clj` gains the converted-module check and the pending set, factored pure enough to unit-test the ratchet edges.
- **PROP-Vaf-001.S3:** `.skein/reviewers.clj` gains the `source-form` seat in `change-review`, authored as a `|`-margin block through `skein.api.format.alpha/reflow` — the module under audit, dogfooded in the roster that will review it. Picked up by `runtime/reload!`, never a weaver restart.
- **PROP-Vaf-001.S4:** `.claude/skills/docs-style/SKILL.md` line-length section carries the two-sided wrap rule. Kanban bodies for epic `9nu0q` and its sixteen open features are updated over the strand CLI (coordination data, not repo files).
- **PROP-Vaf-001.S5:** Validation: focused cold test runs for touched namespaces, `make fmt-check lint reflect-check docs-check`, `make api-docs` if any docstring changes, then a `change-review` roster run over the branch diff as the dogfood pass.

## PROP-Vaf-001.P5 Counsel outcomes

A two-seat council (sol-med seats, opus synthesis; blackboard `a1xrl`) reviewed this proposal before implementation.

- Adopted: the pending-set completion check was wrong — a conformant-but-pending module must not fail the gate, because an unrelated cleanup could make a pending module conformant and lint would then block that unrelated change; shrinking `pending` is the card's own deliberate act, and only stale entries are findings. Also adopted: the "public promised vars only" wording excluded the spec registrations alpha files legitimately carry (reworded to contract-bearing forms), the internal dependency rules as mechanical checks, and docstring presence on public alpha vars as a gate.
- Adopted in part: the per-card checklist grew the counsel's concrete hazards (private-var test references, Var identity and qualified spec keys, no re-exports); and because `format` has no plumbing, the extraction pattern gets its first real exemplar deliberately early — `return-shape` is the recommended second card.
- Rejected: sidecar marker files for the ratchet (tree noise; landing is serialized, so set-line merge conflicts are trivial), a two-tier 96/120 width rule (seventeen curated files do not earn the complexity; an indivisible-token collision restructures or moves to internal, revisit if it bites), and softening the markdown no-wrap rule (the owner's instruction is explicit; existing wrapped files are grandfathered, reflow-only churn stays forbidden).

## PROP-Vaf-001.P6 Decision links

- **PROP-Vaf-001.D1:** [Feature brief](./brief.md) fixes the four patterns and grants design freedom on the internal-namespace naming.
- **PROP-Vaf-001.D2:** [TEN-004](../../TENETS.md) owns minimum surface; the audit duty in every sibling card enforces it.
- **PROP-Vaf-001.D3:** [Devflow philosophy](../../PHILOSOPHY.md) "Prose guides, code decides" is why the mechanical rules land in the lint gate and only judgment lands in the roster seat.
- **PROP-Vaf-001.D4:** [SPEC-003.C19](../../specs/repl-api.md) owns namespace tiers and hosts the new form clause; [SPEC-005.P1/P3](../../specs/alpha-surface.md) owns the exclusionary-internal reading that makes `skein.api.<mod>.internal` contract-free.
