# Spool CLI style guide plan

**Document ID:** `PLAN-Ucs-001`
**Feature:** `uson2-cli-style-guide`
**Proposal:** [proposal.md](./proposal.md) (`PROP-Ucs-001`)
**RFC:** none
**Root specs:** [alpha-surface.md](../../specs/alpha-surface.md) (`SPEC-005`),
[daemon-runtime.md](../../specs/daemon-runtime.md) (`SPEC-004`)
**Feature specs:** [specs/alpha-surface.delta.md](./specs/alpha-surface.delta.md)
(`DELTA-Ucs-001`),
[specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md)
(`DELTA-Ucs-002`)
**Status:** Reviewed
**Last Updated:** 2026-07-14

## PLAN-Ucs-001.P1 Goal and scope

Ship the approved three-part affordance for consistent spool CLIs: a concise
authoring guide, four reusable arg-spec fragments, and dispatch-owned operation
labels for declared subcommands. The proposal owns the motivation and settled
scope. This plan owns sequencing, collision handling, migration, and validation.

The guide section is authored advice in
`docs/spools/writing-shared-spools.md`, not a root-spec contract. Its sole MUST
points authors at existing declared-parser behavior for text-bearing inputs;
it does not create another parser rule. The durable changes are only the Alpha
helper accretion and registered-op result semantics in the two linked deltas.

## PLAN-Ucs-001.P2 Approach

- **PLAN-Ucs-001.A1:** Add the style section beside the existing discovery
  section and link to the authoritative discovery-tier reference. State the
  role-based verb sets, shared flag names, collection split, positive op-shape
  rule, payload-reference MUST, and fix-on-touch policy without restating
  `help`/`about`/`prime`.
- **PLAN-Ucs-001.A2:** Add `note-surface`, `work-root`, `timeout-secs`, and
  `outcome` as documented public data in `skein.api.spool.alpha`. They are
  partial arg-spec maps composed through ordinary data merging. Tests pin their
  declared shapes and demonstrate composition with a spool-owned field; the
  parser gains no special flag types.
- **PLAN-Ucs-001.A3:** Stamp map results in `skein.api.weaver.alpha/op!`, after
  the handler returns and only when parsed args contain `:subcommand`. Derive
  the label from the canonical registered op name and selected subcommand. If a
  map already contains the exact label, preserve it; if it contains any other
  value, fail loudly with expected and actual values. Flat/raw ops, non-map
  results, failures, streams' emitted items, and the help alias stay unchanged.
- **PLAN-Ucs-001.A4:** Migrate only collisions required by the new boundary.
  The in-repo `agent spend` and `land` subcommand paths currently emit
  `agent-spend` and `land-*`; remove those hand-written labels now so dispatch
  produces `agent spend` and `land <verb>`. `roster about|prime` and
  `bench about` already emit the canonical value, so equal-label tolerance
  keeps them working and fix-on-touch defers their cleanup. Flat repo-config
  projections remain handler-labelled because no subcommand was selected.
- **PLAN-Ucs-001.A5:** Pinned kanban is a prerequisite to the dispatch slice.
  Its `task` subcommand currently returns the finer labels `kanban task add`
  and `kanban task list`, which would correctly trip the disagreement failure.
  Before the Skein boundary lands, update kanban upstream to omit the
  hand-written subcommand label, then move the paired `deps.edn` and
  `.skein/spools.edn` pins together. Other equal kanban labels may migrate
  later on fix-on-touch. Current-source evidence uses the proposal's citation
  key, `kanban.spool@03707e5/<path>`.

## PLAN-Ucs-001.P3 Affected areas

| ID | Area | Expected change |
| -- | ---- | --------------- |
| PLAN-Ucs-001.AA1 | `docs/spools/writing-shared-spools.md` | Add the advisory CLI style section beside discovery. |
| PLAN-Ucs-001.AA2 | Spool Alpha API, focused test, generated API doc | Add and test the four public data fragments. |
| PLAN-Ucs-001.AA3 | `src/skein/api/weaver/alpha.clj`, `test/skein/weaver_test.clj` | Stamp declared-subcommand map results and reject conflicting labels. |
| PLAN-Ucs-001.AA4 | Agent/delegation spools, land workflow, tests | Remove mismatching labels and update expectations. |
| PLAN-Ucs-001.AA5 | Pinned kanban coordinate | Remove the `task add|list` mismatch upstream, then advance both repository pins before enabling fail-loud stamping. |
| PLAN-Ucs-001.AA6 | `devflow/specs/alpha-surface.md`, `devflow/specs/daemon-runtime.md` | Promote `DELTA-Ucs-001` and `DELTA-Ucs-002` when the feature ships. |

## PLAN-Ucs-001.P4 Contract and migration impact

- **PLAN-Ucs-001.CM1:** `DELTA-Ucs-001` accretes four values within the already
  blessed `skein.api.spool.alpha` tier. Their names and data shapes become Alpha
  compatibility commitments.
- **PLAN-Ucs-001.CM2:** `DELTA-Ucs-002` changes `SPEC-004.C63b`: declared
  subcommand map results gain a canonical operation label, and conflicting
  handler labels become loud failures.
- **PLAN-Ucs-001.CM3:** No `cli.md` delta is genuine. `SPEC-002.C37` already
  contracts payload-reference resolution through the blessed parser, and the
  dispatcher continues to relay the weaver result. No argv, envelope,
  transport, or public command changes.
- **PLAN-Ucs-001.CM4:** No stored-data migration or compatibility alias ships.
  The observable `agent-spend` and `land-*` result labels move directly to
  `agent spend` and `land <verb>` under TEN-000. Flat op labels do not move.

## PLAN-Ucs-001.P5 Implementation slices

Each slice fits one worker context. S1 and S2 may proceed in parallel. S3 is
code-independent of them but waits for the pinned-kanban prerequisite in A5.
Feature acceptance waits for all three.

### PLAN-Ucs-001.S1 Style-guide section

- **Depends-on:** none.
- **Owned files:** `docs/spools/writing-shared-spools.md`.
- **Outcome:** The shared-spool guide contains the approved role-based verb
  sets, flag lexicon, collection split, positive op-shape recommendation,
  payload-reference MUST, and fix-on-touch policy. It links to the existing
  discovery tiers and clearly marks naming advice as advisory.
- **Validation:** `make docs-check`.
- **Done-when:** All brief points appear once, the discovery contract is linked
  rather than copied, the docs-style sweep is clean, and `make docs-check`
  passes.

### PLAN-Ucs-001.S2 Composable arg-spec fragments

- **Depends-on:** none.
- **Owned files:** `src/skein/api/spool/alpha.clj`,
  `test/skein/api/spool_test.clj`, generated `docs/api/spool.api.md`.
- **Outcome:** Four public, documented partial arg-spec maps cover note input,
  work-root flags, `--timeout-secs`, and `--outcome`. Tests pin the data and show
  a fragment composed with domain-owned declarations.
- **Validation:** `clojure -M:test skein.api.spool-test`; `make api-docs`;
  `make docs-check`.
- **Done-when:** Public vars have useful docstrings, no parser code changes,
  the cold focused namespace passes, and generated API docs are current.

### PLAN-Ucs-001.S3 Dispatch-owned operation labels

- **Depends-on:** the pinned kanban `task` mismatch is removed and the paired
  pin update is available; no dependency on S1 or S2.
- **Owned files:** `src/skein/api/weaver/alpha.clj`,
  `test/skein/weaver_test.clj`, the agent spend producer/tests,
  `.skein/workflows.clj`, and its operation-label assertions.
- **Outcome:** Declared-subcommand map results receive `<op> <subcommand>` at
  dispatch. Tests cover absent, equal, and conflicting labels plus unchanged
  flat/raw/non-map/help behavior. Agent and land emit the canonical form;
  equal existing roster/bench labels and flat projections remain unchanged.
- **Validation:** `clojure -M:test skein.weaver-test skein.delegation-test`;
  `make api-docs` if public docstrings change; `make docs-check`. The
  add-libs-sharded `skein.agent-run-test` and `skein.config-test` cannot run in
  cold focused mode and are proved by the land-time locked suite.
- **Done-when:** No mismatching in-repo subcommand label remains, disagreement
  throws instead of overriding, the cold focused namespaces pass, and the
  pinned kanban suite accepts the advanced pin.

## PLAN-Ucs-001.P6 Validation strategy

- **PLAN-Ucs-001.V1:** Use each slice's named cold focused namespace as its
  Done-when gate. Warm runs may iterate but never satisfy the gate.
- **PLAN-Ucs-001.V2:** Regenerate `docs/api/spool.api.md` after fragment
  docstrings and any affected spool API docs after label cleanup. `make
  docs-check` must pass after each documentation-affecting slice.
- **PLAN-Ucs-001.V3:** Run `make fmt-check lint reflect-check` once all source
  slices are assembled.
- **PLAN-Ucs-001.V4:** The full locked Clojure suite runs only at land-time,
  alongside `(cd cli && go test ./...)`, `clojure -M:smoke`,
  `make spool-suite-gate`, and the blocking quality gates. The locked suite is
  the authoritative proof for the add-libs-sharded agent/config tests.
- **PLAN-Ucs-001.V5:** End validation with `git status --short`; no generated
  SQLite or runtime metadata may remain.

## PLAN-Ucs-001.P7 Risks and open questions

- **PLAN-Ucs-001.R1:** Pinned kanban's `task add|list` labels disagree with the
  approved one-level `<op> <subcommand>` rule. Mitigation: treat its upstream
  cleanup and paired pin movement as an explicit S3 prerequisite; do not weaken
  the fail-loud boundary or add a kanban-specific exception.
- **PLAN-Ucs-001.R2:** A handler may return a collection or scalar, which cannot
  carry `:operation` without a breaking wrapper shape. Mitigation: stamp maps
  only, as `DELTA-Ucs-002.CC1` states, and leave other result shapes unchanged.
- **PLAN-Ucs-001.Q1:** None. Collision semantics and migration scope are
  resolved above.

## PLAN-Ucs-001.P8 Task context

- **PLAN-Ucs-001.TC1:** The approved source is `PROP-Ucs-001`; do not reopen the
  parser-level type system, compatibility aliases, output specs, or rename-only
  migrations rejected there.
- **PLAN-Ucs-001.TC2:** The parser already resolves whole-value `:stdin` and
  `:payload/<name>` references recursively. The style-guide MUST tells authors
  to use that declared path for text-bearing inputs; no parser change belongs
  in this feature.
- **PLAN-Ucs-001.TC3:** Derive labels from the canonical registry entry and the
  parsed `:subcommand`, not raw argv. Use `contains?` to distinguish an absent
  handler label from an explicit nil disagreement.
- **PLAN-Ucs-001.TC4:** For pinned kanban evidence, cite
  `kanban.spool@03707e5/<path>`, resolved at
  `~/.gitlibs/libs/io.github.codethread/kanban.spool/03707e525185cbd5685522a45c6e779b22ceb6b8/<path>`.
- **PLAN-Ucs-001.TC5:** Equal hand-written labels are compatible but still
  duplicate dispatch knowledge. Remove them when their owning surface is next
  changed; this feature removes only disagreements required for S3.

## PLAN-Ucs-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Ucs-001.DN1 Plan creation — 2026-07-14

Root-spec review found two genuine deltas. `SPEC-005.C3` enumerates the
`skein.api.spool.alpha` helpers, and `SPEC-004.C63b` says handler results are
transported without the new operation-label rule. `cli.md` remains true, and
the style section is authored guidance rather than a durable root contract.
