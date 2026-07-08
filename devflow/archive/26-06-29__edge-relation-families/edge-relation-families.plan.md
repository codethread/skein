# Edge Relation Families Plan

**Document ID:** `ERF-PLAN-001` **Feature:** `edge-relation-families` **Proposal:** [proposal.md](./proposal.md) **RFC:** [RFC-007 Edge Relation Families, Strand State, and Supersession](rfcs/2026-06-28-edge-relation-families.md) **Root specs:** [Strand Model](../../specs/strand-model.md), [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md), [TENETS](../../TENETS.md) **Feature specs:** [Strand Model delta](./specs/strand-model.delta.md), [Weaver Runtime delta](./specs/daemon-runtime.delta.md), [REPL API delta](./specs/repl-api.delta.md), [CLI Surface delta](./specs/cli.delta.md), [TENETS delta](./tenets.delta.md) **Status:** Shipped **Last Updated:** 2026-06-29

## ERF-PLAN-001.P1 Goal and scope

Deliver the RFC-007 breaking graph/lifecycle model: replace the old boolean lifecycle with `state`, make `depends-on`/`parent-of`/`supersedes` declared acyclic operational relations, add direct edge predicates and relation-scoped traversal, implement core supersession without a `replaced_by` field, and sweep current code/tests/docs so the shipped tree no longer uses the removed lifecycle schema.

## ERF-PLAN-001.P2 Approach

- **ERF-PLAN-001.A1:** Treat this as an alpha schema reset. Change the current schema and API directly; do not add live migration, compatibility aliases, or fallback behavior for old databases/user code.
- **ERF-PLAN-001.A2:** Convert lifecycle first across Clojure storage, query, weaver, socket, REPL, smoke, and Go CLI surfaces so all later graph behavior targets `state` consistently.
- **ERF-PLAN-001.A3:** Add durable acyclic relation declarations in storage, bootstrap `depends-on`, `parent-of`, and `supersedes`, then rescope cycle checking by relation.
- **ERF-PLAN-001.A4:** Add direct edge predicates inside the existing query compiler model before re-expressing readiness and custom query examples over relation predicates.
- **ERF-PLAN-001.A5:** Add relation-scoped traversal by parameterizing the existing parent traversal shape and requiring the requested relation to be declared acyclic.
- **ERF-PLAN-001.A6:** Implement supersession as one authoritative transaction over storage/weaver surfaces: state update, `supersedes` edge, dependent rewiring, cycle checks, and semantic event/result. Do not duplicate replacement truth in a strand column.
- **ERF-PLAN-001.A7:** Keep annotation vocabulary data-first in `skein.relations.alpha`; do not gate storage or grow workflow-specific commands for annotation relations.
- **ERF-PLAN-001.A8:** Finish with a current docs and grep sweep that removes old lifecycle schema references from code, public docs, canonical specs, smoke examples, and active feature docs, except `devflow/feat/batch-graph-upsert` while that feature is landing independently. Never edit `devflow/archive`; archived historical references are exempt. Non-schema English uses of "active" may remain.
- **ERF-PLAN-001.A9:** After batch graph upsert lands, adapt its shipped implementation/tests/current specs to the state/relation-family model as an explicit follow-up task instead of editing that active feature's planning artifacts out from under its owner.

## ERF-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| ERF-PLAN-001.AA1 | `src/skein/db.clj` | State schema, relation declarations, relation-scoped cycle checks, edge predicates support helpers, relation traversal SQL, supersession transaction. |
| ERF-PLAN-001.AA2 | `src/skein/specs.clj` / `src/skein/query.clj` | State and relation-name specs; remove old lifecycle query fields; compile direct edge predicates. |
| ERF-PLAN-001.AA3 | `src/skein/weaver` / `src/skein/client.clj` | State-aware API/socket protocol, supersession operation/event, relation declaration/list operations. |
| ERF-PLAN-001.AA4 | `src/skein/repl.clj`, `src/skein/graph/alpha.clj`, `src/skein/relations/alpha.clj` | REPL state lifecycle, supersession helper, relation declaration/traversal helpers, annotation catalog namespace. |
| ERF-PLAN-001.AA5 | `cli/` | Replace lifecycle flags/output expectations with `state`; add supersession command; update Go tests. |
| ERF-PLAN-001.AA6 | `test/skein`, `dev/skein/smoke.clj`, `dev/user.clj` | Update tests/smoke to state schema, relation family behavior, edge predicates, traversal, and supersession. |
| ERF-PLAN-001.AA7 | `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `docs/`, `devflow/specs/` | Current documentation and canonical specs rewritten to the final model. |
| ERF-PLAN-001.AA8 | Batch graph upsert shipped code/docs | Deferred follow-up adapts the landed batch graph upsert contract to `state` and relation-family validation without pre-editing its active feature folder. |

## ERF-PLAN-001.P4 Contract and migration impact

- **ERF-PLAN-001.CM1:** Breaking database schema change: `state` replaces `legacy boolean lifecycle columns`. Existing old databases fail loudly and must be recreated by users.
- **ERF-PLAN-001.CM2:** Breaking API/CLI change: `--state`, `:state`, and `state` JSON replace the removed lifecycle field/flag/query names. Generic mutation accepts `active|closed`; `replaced` is reserved for supersession.
- **ERF-PLAN-001.CM3:** `depends-on`, `parent-of`, and `supersedes` are shipped declared acyclic relations. Annotation relations remain open and cyclic-tolerant except self-edges.
- **ERF-PLAN-001.CM4:** Supersession becomes a core operation; raw manual `state="replaced"` plus edge writing is not the blessed path for replacement workflows.
- **ERF-PLAN-001.CM5:** Replacement lookup is edge-based. No `replaced_by` field is added.
- **ERF-PLAN-001.CM6:** No compatibility layer or migration command is included in the implementation plan.

## ERF-PLAN-001.P5 Implementation phases

### ERF-PLAN-001.PH1 State lifecycle reset

Outcome: All existing strand lifecycle storage/API/CLI/query/test/smoke behavior uses `state` and rejects the old lifecycle schema.

### ERF-PLAN-001.PH2 Relation family invariants

Outcome: Valid open relation names store successfully, declared acyclic relations are enforced per relation, annotation cycles are allowed, and `depends-on`/`parent-of`/`supersedes` bootstrap as structural relations.

### ERF-PLAN-001.PH3 Relationship-aware reads

Outcome: Edge predicates work in named/ad hoc queries; readiness is state/relation based; graph helpers traverse one declared relation with preserved current traversal contracts.

### ERF-PLAN-001.PH4 Supersession transaction

Outcome: Core storage/weaver/REPL/CLI supersession writes replacement lineage, marks old strands replaced, rewires dependents, emits an explicit event/result, and rolls back on cycles or invalid input.

### ERF-PLAN-001.PH5 Catalog, docs, and final sweep

Outcome: Annotation catalog ships as source-visible data; current specs/docs/examples reflect the final schema; validation passes; old lifecycle schema references are gone from current code/docs.

## ERF-PLAN-001.P6 Validation strategy

- **ERF-PLAN-001.V1:** Run focused Clojure tests after each storage/weaver/query slice and full `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` before completion.
- **ERF-PLAN-001.V2:** Run `(cd cli && go test ./...)` after CLI state/supersession work.
- **ERF-PLAN-001.V3:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` after docs/smoke integration.
- **ERF-PLAN-001.V4:** Run final grep checks for removed lifecycle schema tokens across code/docs/current devflow files after the batch graph upsert adaptation task, excluding `devflow/archive`, including legacy boolean lifecycle flag, legacy boolean lifecycle query field, `legacy inactive timestamp column`, old JSON `active` field expectations, and old readiness examples; allow only non-schema English uses such as "active feature" or "active weaver".
- **ERF-PLAN-001.V5:** Verify no generated SQLite/runtime metadata artifacts remain in `git status --short` after validation.

## ERF-PLAN-001.P7 Risks and open questions

- **ERF-PLAN-001.R1:** This is a high-blast-radius breaking change. Mitigation: slice by semantic layer, run focused tests after each layer, and reserve a final grep/docs sweep.
- **ERF-PLAN-001.R2:** Supersession rewiring may need internal edge deletion even though public edge deletion remains out of scope. Mitigation: implement only the narrow storage helper needed by the supersession transaction and keep public edge-delete API deferred.
- **ERF-PLAN-001.R3:** Event compatibility may be confusing if supersession is represented as both generic update and semantic replacement. Mitigation: make the supersession event authoritative and document any compatibility fanout explicitly.
- **ERF-PLAN-001.R4:** Old lifecycle references may remain in archived devflow history. Mitigation: never edit `devflow/archive`; scope cleanup and grep gates to current code/docs and active devflow files.
- **ERF-PLAN-001.Q1:** None blocking task generation.

## ERF-PLAN-001.P8 Task context

- **ERF-PLAN-001.TC1:** Implement RFC-007 and feature deltas as the source of truth. The final code should read as if `state` and operational relation families were the original design.
- **ERF-PLAN-001.TC2:** Do not add migration code, compatibility aliases, old CLI flags, or fallback behavior for old databases. Incompatible old worlds fail loudly.
- **ERF-PLAN-001.TC3:** Do not add a `replaced_by` field. Use indexed `supersedes` edge lookups for replacement discovery.
- **ERF-PLAN-001.TC4:** Generic create/update/batch/pattern surfaces must not set `state="replaced"`; only the supersession transaction may do that.
- **ERF-PLAN-001.TC5:** Supersession direction is `replacement --supersedes--> replaced`; helper/CLI argument order must be documented unambiguously.
- **ERF-PLAN-001.TC6:** Keep the CLI thin. State and supersession are core operations; relation declaration and raw query authoring remain trusted Clojure workflows.

## ERF-PLAN-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### ERF-PLAN-001.DN1 Plan creation — 2026-06-28

- Created after RFC/proposal revision promoted supersession into the core operational graph and replaced boolean lifecycle with `state`. Reviewed enough for initial AFK task breakdown; deltas may receive a later editorial pass before implementation begins.

### ERF-PLAN-001.DN2 Task queue creation — 2026-06-28

- Created the initial eight-task AFK queue. The queue intentionally included fixes for current code/tests/docs and active feature artifacts, but excluded live migration and compatibility aliases per TEN-000. DN3 extends this to a nine-task queue with an explicit batch graph upsert handoff.

### ERF-PLAN-001.DN3 Batch feature handoff — 2026-06-28

- Added Task 9 to defer batch graph upsert adaptation until after that feature lands. This preserves the edge relation intent (`state`, valid relation names, declaration-scoped acyclicity, supersession-owned replacement) without editing the active batch feature artifacts from underneath their owner.

### ERF-PLAN-001.DN4 Task 1 blocked — 2026-06-28

- Converted core Clojure storage/query/weaver lifecycle paths to `state` and focused `clojure -M:test` passes. Full project validation is blocked because `(cd cli && go test ./...)` still fails in Go CLI integration, which depends on the out-of-scope CLI lifecycle surface scheduled for Task 2.

### ERF-PLAN-001.DN5 Task 1 verification and unblock — 2026-06-28

- Re-verified Task 1 scope: full Clojure test suite passed (`107 tests`, `542 assertions`), removed lifecycle schema grep only reports the intentional rejection set in `src/skein/db.clj`, and generic create/update/batch paths reject `state="replaced"`. Marked Task 1 complete and removed Task 2's dependency so CLI/socket state work is ready but not started.

### ERF-PLAN-001.DN6 Task 2 CLI/socket state surface — 2026-06-28

- Replaced Go CLI lifecycle flags/payloads with `--state`, kept generic mutation restricted to `active|closed`, allowed list filtering for `active|closed|replaced`, and added socket rejection coverage for old `active` arguments. Focused Clojure and Go suites pass; full smoke is not claimed for this slice because this long worktree path currently trips the smoke world's Unix socket path-length limit before later pending lifecycle sweeps (including batch graph upsert in Task 9).

### ERF-PLAN-001.DN7 Task 3 relation declarations — 2026-06-28

- Added durable `acyclic_relations`, bootstrapped `depends-on`, `parent-of`, and `supersedes`, replaced closed edge-type validation with relation-name validation, and scoped cycle checks to declared relations only. Annotation relations such as `related-to` and `agent.notes/v1` can now form non-self cycles. Focused Clojure and Go suites pass. Full smoke still needs the later lifecycle/batch sweep: from this worktree it first hits the known Unix socket path-length limit, and from a short copied path it proceeds further to existing out-of-slice smoke payloads that still use legacy boolean lifecycle query field in batch input.

### ERF-PLAN-001.DN8 Task 4 edge predicates and traversal — 2026-06-28

- Added direct `:edge/out` and `:edge/in` query predicates with parameterized relation operands and endpoint nested-edge rejection. Generalized `ancestor-root-ids` and `subgraph` over declared acyclic relation `:type`, preserving `parent-of` defaults and failing loudly for annotation relations. Focused Clojure tests and Go CLI tests pass. Full smoke remains outside this slice: the repo path still hits the known Unix socket length limit, and a short-path copy reaches existing out-of-slice smoke failures in pending lifecycle/batch sweep coverage.

### ERF-PLAN-001.DN9 Task 5 core supersession transaction — 2026-06-28

- Added transactional storage supersession and weaver semantic API/event. Supersession writes `replacement --supersedes--> old`, marks old `replaced`, rewires incoming `depends-on` edges regardless of dependent state, and rolls back on lineage or dependency cycles. Focused Clojure tests and Go CLI tests pass. Full smoke is still not claimed for this slice: repo-path smoke hits the known Unix socket path-length limit, and a short-path smoke copy reaches the existing pending batch/lifecycle smoke payload that still uses legacy boolean lifecycle query field.

### ERF-PLAN-001.DN10 Task 6 supersession helpers and CLI — 2026-06-28

- Exposed supersession through Clojure client routing, `skein.repl/supersede!`, JSON socket operation `supersede`, and Go CLI `strand supersede <old-id> <replacement-id>`. Generic socket/REPL/CLI update paths continue to reject `state="replaced"`; helpers and CLI return the weaver-normalized supersession result. Full Clojure and Go test suites pass. Smoke remains blocked outside this slice: the repo path hits the known Unix socket path-length limit, and a short-path smoke copy reaches an existing pending smoke payload failure in later lifecycle/batch sweep scope.

### ERF-PLAN-001.DN11 Task 7 relation catalog and active feature docs — 2026-06-28

- Added `skein.relations.alpha` as source-visible advisory data for shipped operational batteries and behavior-free annotation conventions, with lookup/family helpers and focused catalog tests. Aligned active edge-relation feature artifacts so the Task 7 stale-contract grep passes while leaving `devflow/feat/batch-graph-upsert` untouched. Full Clojure and Go test suites pass. Smoke remains blocked outside this slice: the repo path hits the known Unix socket path-length limit, and a short-path smoke copy reaches the pending batch smoke payload that still uses the legacy lifecycle schema owned by Task 9.

### ERF-PLAN-001.DN12 Task 9 batch graph upsert adaptation — 2026-06-28

- Updated batch smoke coverage to use `:state` and state-bearing normalized rows, added focused batch tests for rejected removed lifecycle fields, rejected manual `state="replaced"`, valid relation-name validation, declared-relation cycle rejection, annotation cycles, and edge attribute replacement. Current docs/examples touched by the stale lifecycle grep were aligned to `--state`/`:state`. Full Clojure and Go suites pass. Repo-path smoke still hits the known Unix socket path-length limit, but the same smoke suite passes from `/tmp/skein-smoke-erf9`.

### ERF-PLAN-001.DN13 Task 8 docs smoke final validation — 2026-06-28

- Promoted TEN-005 to declared structural relation DAGs, aligned remaining docs/spec examples, and changed smoke config-dir worlds to short `/tmp` paths so repo-path smoke avoids Unix socket path-length failures. Smoke now exercises `--state`, state-based readiness, edge predicates through a registered query, CLI supersession rewiring, and batch state results. Full Clojure tests, Go tests, and smoke pass; generated smoke artifacts were cleaned.

### ERF-PLAN-001.DN14 Feature finish — 2026-06-29

- Marked the feature shipped. Root specs and TENETS already reflected the shipped state lifecycle, relation families, edge predicates, relation-scoped traversal, annotation catalog, and core supersession behavior. All queued tasks were complete; no scope was cut. Archived RFC-007 with the feature.
