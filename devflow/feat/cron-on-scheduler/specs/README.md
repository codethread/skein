# Spec deltas for cron-on-scheduler

**Feature:** `cron-on-scheduler`
**Proposal:** [../proposal.md](../proposal.md) (`PROP-cron-on-scheduler-001`)
**Decision:** No root spec deltas.

## What was checked

`devflow/specs/alpha-surface.md` (SPEC-005) and the scheduler section of
`devflow/specs/daemon-runtime.md` (SPEC-004.P10d, `.C1a`, `.C91a`, `.C95`,
`.C97`–`.C105`) were read against the accepted proposal.

## Why no delta

The cron spool is explicitly userland, not shipped alpha surface
(`SPEC-005.C4` names `spools/cron` among repo-local approved spools whose
README/docs are their own contract). Cron's contract therefore lives in
`spools/cron/README.md` + `spools/cron.api.md`, not in a root spec, so its
rewrite carries no root-contract change. The scheduler primitive it now rides
is untouched per `PROP-cron-on-scheduler-001.NG1`: this feature *consumes*
`SPEC-004.P10d` unchanged, and `SPEC-004.C101` already blesses the exact move —
"a userland handler may schedule its own next wake." No contract in SPEC-003,
SPEC-004, or SPEC-005 changes shape.

## One finish-time cleanup (not a delta)

`SPEC-004.C1a`'s non-normative parenthetical lists "the userland cron spool"
as an example of a subsystem that arms its own `ScheduledExecutorService` timer
off the runtime clock and registers its own clock-consumer pump. After this
feature cron no longer does that (the scheduler's pump releases cron's wakes),
so that illustrative example goes stale. It is an example, not a contract, and
the normative clock behaviour is unchanged — no delta is warranted. Flagged
here so the finish/archive pass can drop the cron mention from `SPEC-004.C1a`
when the feature ships.
