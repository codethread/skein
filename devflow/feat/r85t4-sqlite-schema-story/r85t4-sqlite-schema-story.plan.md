# SQLite schema story Plan

**Document ID:** `PLAN-Sss-001`
**Feature:** `r85t4-sqlite-schema-story`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** [strand-model.md](../../specs/strand-model.md), [daemon-runtime.md](../../specs/daemon-runtime.md)
**Feature specs:** [specs/strand-model.delta.md](./specs/strand-model.delta.md), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md)
**Status:** Draft
**Last Updated:** 2026-07-22
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version:
`PLAN-Dwr-001` for v1 and `PLAN-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally
referenced document. Prefix every nested point ID with the full document ID so references are globally grepable and do not clash across documents.

## PLAN-Sss-001.P1 Goal and scope

Implement the persistence-evolution contract decided in `PROP-Sss-001` and staged in `DELTA-Sss-001`/`DELTA-Sss-002`: stamp schema
generation 1 in `PRAGMA user_version`, gate storage initialization on the stamp in both skew directions, adopt unstamped current-shape
databases via a canonical structural validator, and scaffold the migration-ladder home so the first real generation bump has a place to
land. Generation 1 ships with an empty ladder; the deliverable is the contract, the guard, and the stamp — kept as small as the decision
allows (TEN-004).

## PLAN-Sss-001.P2 Approach

- **PLAN-Sss-001.A1:** All work lands in `skein.core.db` (per the repo persistence boundary) plus one new dedicated migration namespace.
  No API-surface, CLI, or spool changes: the stamp and gate sit entirely behind `init!`, which every storage entry point already funnels
  through.
- **PLAN-Sss-001.A2 (canonical validator):** Rather than hand-maintaining introspection predicates per table (today's
  `ensure-current-schema!` approach, which only rejects known-past shapes), construct a fresh generation-1 reference database in memory
  from `schema-sql` (the `:sqlite-memory` seam, `SPEC-004.C93`, already runs identical schema code), introspect both databases —
  `sqlite_master` plus `PRAGMA table_info`/`index_list`/`index_info`/`foreign_key_list` — into normalized shape maps, and compare.
  Comparison covers exact columns, order, declared types, nullability, defaults, primary keys, foreign keys, CHECK constraints, index
  columns/order/uniqueness, and partial-index predicates, for every Skein-owned table and index, rejecting extra columns on managed
  tables (`DELTA-Sss-002.CC2`). The reference-db construction makes the validator self-maintaining: schema drift can never diverge from
  the check.
- **PLAN-Sss-001.A3 (generation gate):** `init!` reads `user_version` before any DDL. Newer than the binary's `current-generation` →
  refuse untouched; older stamped generation → refuse naming the migration path; unstamped and canonically matching → stamp 1
  (idempotent, metadata-only); unstamped and mismatched → refuse without stamping. The existing legacy-shape throws
  (`ensure-current-schema!`) fold into the validator's mismatch reporting rather than surviving as a parallel mechanism.
- **PLAN-Sss-001.A4 (ladder home):** A dedicated migration namespace owns `current-generation` and an ordered (initially empty) step
  registry, with the composition loop and the one-transaction-per-step discipline documented at the definition site. This is
  deliberately a scaffold: invocation surface and mill-vs-JVM home are deferred (`DELTA-Sss-002.Q1`) until the first real step exists,
  so nothing here commits to an execution path beyond "steps live in one place and compose in order".

## PLAN-Sss-001.P3 Affected areas

| ID                  | Area                                | Expected change                                                              |
| ------------------- | ----------------------------------- | ---------------------------------------------------------------------------- |
| PLAN-Sss-001.AA1    | `src/skein/core/db.clj`             | Generation gate in `init!`; canonical validator replaces shape predicates     |
| PLAN-Sss-001.AA2    | new migration namespace under `skein.core` | `current-generation`, empty ordered step registry, step discipline     |
| PLAN-Sss-001.AA3    | `test/skein/core/`                  | Generation/adoption/skew coverage beside existing `db_test.clj` schema tests  |
| PLAN-Sss-001.AA4    | `devflow/specs/`                    | Promote `DELTA-Sss-001`/`DELTA-Sss-002` into SPEC-001/SPEC-004 at finish      |

## PLAN-Sss-001.P4 Contract and migration impact

- **PLAN-Sss-001.CM1:** Contract changes are exactly `DELTA-Sss-001` (SPEC-001.P8 evolution classes + stamp) and `DELTA-Sss-002`
  (SPEC-004 generation-aware init, adoption, ladder, error contract). On-disk impact for existing healthy worlds is one metadata pragma
  write (`user_version` 0 → 1) at first open by a generation-aware weaver; no row data changes.

## PLAN-Sss-001.P5 Implementation phases

### PLAN-Sss-001.PH1 Canonical validator + generation gate + stamp

Outcome: `init!` enforces the full `DELTA-Sss-002.CC1/CC2/CC4` contract with the reference-db validator, gen-1 stamping and adoption,
both skew refusals with found/expected generations in the error data, and legacy-shape rejection folded in. Cold-run test coverage for:
fresh db stamped 1; unstamped matching db adopted idempotently; unstamped mismatched db (missing column, extra column, wrong index,
legacy document/edge-constraint shapes) refused without a stamp write; newer-generation db refused untouched; older-generation refusal
names the migration path.

### PLAN-Sss-001.PH2 Migration-ladder scaffold

Outcome: the migration namespace exists as the single home for `current-generation` and ordered steps, `init!` consumes
`current-generation` from it, and the composition/transaction discipline of `DELTA-Sss-002.CC3` is stated at the definition site. A
test proves the registry is empty at generation 1 and that the gate and registry agree on the current generation.

### PLAN-Sss-001.PH3 Spec promotion and index

Outcome: deltas merged into SPEC-001/SPEC-004 (marked Merged), `devflow/README.md` index updated, `rapv5` unblocked with a citable
contract. Runs with the devflow finish stage.

## PLAN-Sss-001.P6 Validation strategy

- **PLAN-Sss-001.V1:** Per-slice gate: cold `clojure -M:test` on the touched `skein.core` test namespaces (db + new generation tests).
- **PLAN-Sss-001.V2:** Adoption safety is proven by tests that assert `user_version` remains 0 after every refused adoption and that row
  data is byte-identical across a successful adoption.
- **PLAN-Sss-001.V3:** Queue acceptance: full locked suite, `clojure -M:smoke` (exercises real weaver boot on a fresh world → stamps
  gen 1), `(cd cli && go test ./...)`, and the `make fmt-check lint reflect-check docs-check` gates.
- **PLAN-Sss-001.V4:** Manual skew check in a disposable workspace: open a world, bump `user_version` by hand via `sqlite3`, confirm the
  refusal message carries found/expected generations.

## PLAN-Sss-001.P7 Risks and open questions

- **PLAN-Sss-001.R1:** SQLite introspection normalization (type affinity spellings, autoindexes, whitespace in `sqlite_master` SQL) can
  make the canonical comparison brittle. Mitigation: compare structured pragma output, not raw SQL text, except for CHECK/partial-index
  predicates where normalized SQL substrings are unavoidable; ignore `sqlite_autoindex_*` and non-Skein objects explicitly.
- **PLAN-Sss-001.R2:** Stamping at boot writes to a db the old code path treated read-only during validation. Mitigation: stamp only
  after full validation passes, in the same code path that already performs DDL writes.

## PLAN-Sss-001.P8 Task context

- **PLAN-Sss-001.TC1:** Read `PROP-Sss-001` and both deltas first; the deltas are the contract being implemented, clause by clause.
  Key code: `src/skein/core/db.clj` (`schema-sql`, `ensure-current-schema!`, `init!`, the `:sqlite-memory` constructor). Existing
  schema-rejection tests live in `test/skein/core/db_test.clj` (`init-rejects-old-document-strand-schema` etc.) and show the fixture
  style for building legacy shapes. Keep SQL in `skein.core.db`; runtimes in tests are `:publish? false`.

## PLAN-Sss-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
