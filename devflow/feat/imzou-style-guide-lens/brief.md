# Brief: imzou-style-guide-lens

Feature card `imzou` under epic `3o7le` (Spool CLI consistency). Depends on feature
`uson2-cli-style-guide` landing first — the lens cites the style-guide section that
feature adds to `docs/spools/writing-shared-spools.md`.

## Deliverable

Extend the `surface-minimalism` reviewer contract in `.skein/reviewers.clj`
(change-review roster): for every new or changed op, verb, or flag in a change, check the
name and shape against the style-guide section in `docs/spools/writing-shared-spools.md`
and whether the blessed `skein.api.spool.alpha` fragments were used; report divergence as
ADVISORY findings for the synthesizer, never a gate.

The single must-fix finding class: a text-bearing flag or positional declared outside the
declared arg-spec parser, because it silently loses `:stdin`/`:payload/<name>` references
(correctness, not style).

## Constraints

- Guide not rule per PHILOSOPHY ("prose guides, code decides"): the lens surfaces
  divergence to human review; it does not police.
- This is repo config (`.skein/reviewers.clj`), picked up per the reload ladder — no
  weaver restart; roster definitions are read at review fan-out from config, so the change
  takes effect for new reviews after `runtime/reload!` (verify the pickup path; never
  restart the weaver).
- Keep the contract within the existing roster-entry budget style (call budgets, scope
  notes) and consistent with neighboring lens contracts.
