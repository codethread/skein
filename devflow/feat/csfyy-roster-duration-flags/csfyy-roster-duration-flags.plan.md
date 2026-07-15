# Roster duration flags plan

**Document ID:** `PLAN-Rdf-001`
**Feature:** `csfyy-roster-duration-flags`
**Proposal:** [proposal.md](./proposal.md) (`PROP-Rdf-001`)
**RFC:** none
**Root specs:** [cli.md](../../specs/cli.md) (`SPEC-002`),
[alpha-surface.md](../../specs/alpha-surface.md) (`SPEC-005`)
**Feature specs:** none
**Status:** Reviewed
**Last Updated:** 2026-07-14

## PLAN-Rdf-001.P1 Goal and scope

Replace the roster CLI's millisecond duration flags with
`--timeout-secs` and `--stale-after-secs`, while leaving the roster's
millisecond-based Clojure API and internal poll interval unchanged. Remove the old
flags without aliases so parser misuse fails loudly. The approved scope and
four-file inventory are in [PROP-Rdf-001.P4](./proposal.md).

## PLAN-Rdf-001.P2 Approach

- **PLAN-Rdf-001.A1:** Rename the declared roster CLI flags and their generated
  usage text. The CLI continues to parse whole integers; the `list` and
  `await-quiet` handlers convert supplied seconds to milliseconds before calling
  `roster` or `await-quiet!`. Defaults remain owned by those Clojure APIs, so an
  omitted CLI flag is not converted or replaced at the handler boundary.
- **PLAN-Rdf-001.A2:** Keep the unit boundary narrow. Public Clojure options
  `:timeout-ms`, `:stale-after-ms`, and `:poll-ms`, their defaults, derived
  `:age-ms`, and sub-second API tests do not change. Only CLI argument names,
  CLI help, and CLI examples use seconds.
- **PLAN-Rdf-001.A3:** Update the executable CLI tests to pass seconds and retain
  the same semantic cases. Add an explicit parser-failure assertion for both
  removed flags so a later compatibility alias cannot silently restore the
  hazardous surface.
- **PLAN-Rdf-001.A4:** Update the roster contract synopsis and cookbook command
  example with second values equivalent to their current millisecond values.
  Do not rewrite the cookbook's Clojure example, which exercises the unchanged
  millisecond API.

## PLAN-Rdf-001.P3 Affected areas

| ID                  | Area                                             | Expected change |
| ------------------- | ------------------------------------------------ | --------------- |
| PLAN-Rdf-001.AA1    | `spools/roster/src/skein/spools/roster.clj`      | Rename CLI flags/usages and convert supplied seconds at the handler boundary. |
| PLAN-Rdf-001.AA2    | `test/skein/roster_test.clj`                     | Use the new flags in CLI round-trip coverage and assert that each old flag fails loudly as unknown. |
| PLAN-Rdf-001.AA3    | `spools/roster.md`                               | Update the `list` and `await-quiet` command synopses; leave the Clojure API contract in milliseconds. |
| PLAN-Rdf-001.AA4    | `spools/roster.cookbook.md`                      | Change the CLI await example to `--timeout-secs 60`; leave its Clojure example unchanged. |

## PLAN-Rdf-001.P4 Contract and migration context

- **PLAN-Rdf-001.CM1:** This is a breaking alpha CLI rename with no compatibility
  aliases. Existing callers must replace `--timeout-ms N` with
  `--timeout-secs S` and `--stale-after-ms N` with `--stale-after-secs S`, using
  equivalent whole-second values. The removed spellings fail as unknown flags.
- **PLAN-Rdf-001.CM2:** No root spec is falsified, so this feature stages no spec
  delta. `SPEC-002.P1` delegates registered spool per-op behavior to each spool's
  contract doc and specifically points roster to `spools/roster.md`.
  `SPEC-005.C3/C9` also places roster behavior in the spool-doc tier and requires
  a root-spec update only when tier membership changes. This feature changes the
  roster spool contract in place and does not move that surface between tiers.
- **PLAN-Rdf-001.CM3:** No data, config, or runtime migration is required. The
  Clojure API remains millisecond-based, and the handlers preserve its behavior
  by converting seconds before invocation.

## PLAN-Rdf-001.P5 Implementation phases

### PLAN-Rdf-001.PH1 CLI boundary and executable contract

Outcome: the roster op exposes only `--timeout-secs` and
`--stale-after-secs`, converts them at the handler boundary, and preserves the
existing await/list results. Focused CLI round-trip tests use the new flags and
prove both old spellings fail loudly. This slice is independently verified by
the cold focused test run `clojure -M:test skein.roster-test`.

### PLAN-Rdf-001.PH2 Contract docs and cookbook

Outcome: `spools/roster.md` and `spools/roster.cookbook.md` show the seconds-based
CLI surface while continuing to document the Clojure API in milliseconds. This
slice is independently verified by `make docs-check` and a docs-style review.

## PLAN-Rdf-001.P6 Validation strategy

- **PLAN-Rdf-001.V1:** The slice gate for the implementation is the cold focused
  run `clojure -M:test skein.roster-test`. It must cover equivalent seconds-to-ms
  behavior for `list` and `await-quiet`, plus loud unknown-flag failures for
  `--timeout-ms` and `--stale-after-ms`.
- **PLAN-Rdf-001.V2:** `make docs-check` gates the documentation slice and final
  feature work. The generated `spools/roster.api.md` is not expected to change:
  no public Clojure docstring changes are planned. Run `make api-docs` only if a
  source edit changes generated source links; include any resulting mechanical
  update rather than hand-editing the generated file.
- **PLAN-Rdf-001.V3:** The full locked Clojure suite is a land-time gate only.
  It is outside these implementation slices and must not be used as their
  done-when check.

## PLAN-Rdf-001.P7 Risks and open questions

- **PLAN-Rdf-001.R1:** A partial rename could leave help, handler destructuring,
  or docs using the wrong unit. Mitigation: keep the four-file inventory closed,
  search the live source/docs for both old spellings, and distinguish historical
  archive references from active surfaces.
- **PLAN-Rdf-001.R2:** Multiplying defaults at the CLI boundary would duplicate
  API policy and could change behavior. Mitigation: convert only present CLI
  values and let the existing Clojure API supply omitted defaults.
- **PLAN-Rdf-001.Q1:** None. The approved proposal settles the unit and migration
  choices.

## PLAN-Rdf-001.P8 Task context

- **PLAN-Rdf-001.TC1:** Treat [PROP-Rdf-001.P4](./proposal.md) as the complete
  implementation inventory. Historical and archived roster specifications are
  evidence only and must not be edited.
- **PLAN-Rdf-001.TC2:** Preserve the API/CLI unit distinction: seconds in the
  declared `strand roster` flags; milliseconds in `roster`, `await-quiet!`,
  defaults, `:poll-ms`, and `:age-ms`.
- **PLAN-Rdf-001.TC3:** The required negative test must invoke each removed flag
  through the registered roster op and assert an unknown-flag failure, not merely
  search the arg-spec map.
- **PLAN-Rdf-001.TC4:** Use the cold focused namespace gate
  `clojure -M:test skein.roster-test`. Leave the full locked suite to landing.

## PLAN-Rdf-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Rdf-001.DN1 Planning and spec review — 2026-07-14

- Read the affected source, tests, roster contract/cookbook, `SPEC-002`, and
  `SPEC-005`. The root specs delegate roster flag details to `spools/roster.md`;
  no feature-local spec delta is warranted.
- `spools/roster.api.md` remains outside the planned inventory because the change
  touches no public Clojure docstring. Regenerate only if source-link movement
  produces a mechanical diff.
