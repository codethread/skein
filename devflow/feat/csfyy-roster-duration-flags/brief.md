# Brief: csfyy-roster-duration-flags

Feature card `csfyy` under epic `3o7le` (Spool CLI consistency).

## Problem

`roster await-quiet` and `roster list` take `--timeout-ms` and `--stale-after-ms` while
every other duration flag on the surface is `--timeout-secs` (`agent await`, `flow-await`).
An agent pattern-matching from those passes seconds into a milliseconds flag and gets a
1000x shorter timeout that appears to work — a silent misbehavior, TEN-003 in spirit.

## Deliverable

Converge roster to `--timeout-secs` / `--stale-after-secs`. If a genuine sub-second
precision need exists (unlikely at 1-5s poll intervals) document it instead and keep ms
with the reason stated in the flag doc.

- Alpha (TEN-000): no compatibility aliases; the old flags fail loudly as unknown flags.
- Update roster spool docs/cookbook (`spools/roster.md`, `spools/roster.cookbook.md`,
  regenerate `spools/roster.api.md` if docstrings change) and any callers in-repo.

## Constraints

- The epic's style-guide decision (card `1dw6d`): durations are unit-suffixed,
  seconds-first (`--timeout-secs`); ms only with a documented reason. This card is the
  outlier fix, not a rename-only churn — the flags are a correctness hazard.
