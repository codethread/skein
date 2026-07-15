# Roster duration flags proposal

**Document ID:** `PROP-Rdf-001`

**Last updated:** 2026-07-14

**Related brief:** [brief.md](./brief.md)

**Related decisions:** [TEN-000 and TEN-003](../../TENETS.md); spool CLI style-guide card `1dw6d`

## PROP-Rdf-001.P1 Problem

The roster CLI is the duration-unit outlier. `roster list` declares
`--stale-after-ms`, while `roster await-quiet` declares both `--timeout-ms` and
`--stale-after-ms` (`spools/roster/src/skein/spools/roster.clj:745-758`). The
handlers pass those values into the roster's millisecond-based Clojure API
without changing units (`spools/roster/src/skein/spools/roster.clj:688-709`).
An agent copying the seconds convention from another await command can
therefore request a timeout one thousand times shorter than intended without
an error.

The comparison surface is consistently seconds-based. `agent await` parses and
declares `--timeout-secs`
(`spools/delegation/src/skein/spools/delegation.clj:702-707`,
`spools/delegation/src/skein/spools/delegation.clj:1972-1975`), and
`flow-await` declares the same unit (`.skein/config.clj:636-654`).

Discovery found no CLI need for sub-second timeout or staleness values. The
defaults are thirty and fifteen minutes
(`spools/roster/src/skein/spools/roster.clj:37-39`,
`spools/roster/src/skein/spools/roster.clj:349-356`). The implementation polls
every 50 ms, but that separate internal `:poll-ms` option remains millisecond
based (`spools/roster/src/skein/spools/roster.clj:354-373`,
`spools/roster/src/skein/spools/roster.clj:391-398`). The only sub-second
timeout found is a focused Clojure API test, not a CLI caller
(`test/skein/roster_test.clj:360-375`). The evidence therefore supports seconds
for the CLI flags.

## PROP-Rdf-001.P2 Goal

- **PROP-Rdf-001.G1:** Make the roster CLI use `--timeout-secs` and
  `--stale-after-secs`, matching `agent await`, `flow-await`, and card `1dw6d`.
- **PROP-Rdf-001.G2:** Make the removed millisecond flags fail as unknown flags.
  TEN-000 calls for a direct alpha-surface change, and TEN-003 rules out a
  silent interpretation fallback.
- **PROP-Rdf-001.G3:** Keep timeout and staleness behavior unchanged apart from
  the CLI unit presented to callers.

## PROP-Rdf-001.P3 Non-goals

- **PROP-Rdf-001.NG1:** No compatibility aliases for `--timeout-ms` or
  `--stale-after-ms`.
- **PROP-Rdf-001.NG2:** No change to the millisecond-based Clojure API, derived
  `:age-ms` values, or internal `:poll-ms` interval.
- **PROP-Rdf-001.NG3:** No other roster command, query, tracking, staleness, or
  await behavior changes.
- **PROP-Rdf-001.NG4:** No edits to archived roster specifications; they record
  the surface that shipped at that time.

## PROP-Rdf-001.P4 Scope and inventory

The complete implementation inventory is four files:

- `spools/roster/src/skein/spools/roster.clj`: rename the command usages at
  lines 609-612, handler inputs at lines 688-709, and declared CLI flags at
  lines 745-758.
- `test/skein/roster_test.clj`: update the only executable in-repo CLI callers
  at lines 377-390 and assert that the old flags fail loudly. Keep the
  sub-second Clojure API coverage at lines 360-375.
- `spools/roster.md`: update the command synopsis at lines 98-99.
- `spools/roster.cookbook.md`: update the only documented command invocation at
  line 100.

The repository-wide search found no callers in source, `.skein` config, or
other cookbooks. It found only the test and cookbook invocation above, plus the
two command synopses and historical/archive references.

`spools/roster.api.md` is generated from public docstrings: the generator maps
the roster source to that output at `scripts/generate_api_docs.clj:6-15`, and
`make api-docs` runs it (`Makefile:47-52`). This CLI-only change does not alter
the public Clojure docstrings, so the generated file is outside the touched-file
inventory. If source edits move generated source links, regenerate it with
`make api-docs` and include the resulting mechanical update.

## PROP-Rdf-001.P5 Open questions

- **PROP-Rdf-001.Q1:** None. The brief and card `1dw6d` settle the unit choice;
  discovery found no reason to retain millisecond CLI flags.
