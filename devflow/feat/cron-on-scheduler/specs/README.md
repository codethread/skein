# Spec deltas for cron-on-scheduler

**Feature:** `cron-on-scheduler`
**Proposal:** [../proposal.md](../proposal.md) (`PROP-cron-on-scheduler-001`)
**Decision:** One root spec delta — [`daemon-runtime.delta.md`](./daemon-runtime.delta.md)
(`DELTA-cron-on-scheduler-runtime-001`), staging a `SPEC-004` amendment.

## What was checked

`devflow/specs/alpha-surface.md` (SPEC-005) and the scheduler section of
`devflow/specs/daemon-runtime.md` (SPEC-004.P10d, `.C1a`, `.C91a`, `.C95`,
`.C97`–`.C105`) were read against the accepted proposal.

## The one delta

The cron spool itself is userland, not shipped alpha surface (`SPEC-005.C4`
names `spools/cron` among repo-local approved spools whose README/docs are
their own contract), so cron's own contract lives in `spools/cron/README.md`
+ `spools/cron.api.md`, not in a root spec — its rewrite carries no root change.

Building cron on the scheduler, however, surfaced a latent primitive defect
that the proposal folds in one narrow fix for (`PROP-cron-on-scheduler-001.NG1`,
`.P1`): scheduler wake retirement is key-only, so a `SPEC-004.C101`-blessed
handler that schedules its own next same-key wake loses that replacement the
instant its delivery completes. The fix — generation-aware retirement, mirroring
the generation-specific delivery-attempt increment `SPEC-004.C102` already
defines — is a change to the shipped scheduler contract, so it is staged as
[`daemon-runtime.delta.md`](./daemon-runtime.delta.md): amend `SPEC-004.C102`
and add `SPEC-004.C102b`. The delta is promoted into the root spec at finish
(see `PLAN-cron-on-scheduler-001.PH0` / `.V6`). No other contract in SPEC-003,
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
