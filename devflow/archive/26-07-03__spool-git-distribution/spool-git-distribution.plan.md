# Spool git distribution Plan

**Document ID:** `SGD-PLAN-001` **Feature:** `spool-git-distribution` **Proposal:** [proposal.md](./proposal.md) **RFC:** [Registry-free git distribution for spools](../../rfcs/2026-07-03-spool-git-distribution.md) **Root specs:** [repl-api.md](../../specs/repl-api.md), [daemon-runtime.md](../../specs/daemon-runtime.md) **Feature specs:** [specs/repl-api.delta.md](./specs/repl-api.delta.md), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) **Status:** Shipped **Last Updated:** 2026-07-03

## SGD-PLAN-001.P1 Goal and scope

Implement RFC-017: git-coordinate spool distribution (SHA-pinned, content-addressed cache, tag verification) and the `spool.edn` manifest (provides/needs) with loud sync outcomes and `use!` activation gating, plus spec deltas and publishing docs. Why: [proposal](./proposal.md).

## SGD-PLAN-001.P2 Approach

- **SGD-PLAN-001.A1:** All engine work lands in `skein.api.weaver.alpha` (`src/skein/api/weaver/alpha.clj`), which already owns approved-spool grammar (`validate-approved-spool-entry!`, `normalize-approved-spools-file`), sync (`sync-approved-spool!`, `sync-approved-spools`), and use gating (`use-spool-skip`, `use!`). Extend those seams in place; no new namespaces.
- **SGD-PLAN-001.A2:** Sequence grammar → fetch → manifest/gating → docs, each slice independently green, because fetch consumes normalized git entries and gating consumes fetched roots. Same-file slices are strictly serialized.
- **SGD-PLAN-001.A3:** Fetch shells out to system git (lean on existing tooling/auth per RFC-017.REC1); temp-dir + atomic rename gives crash-safe cache population; tests use `file://` fixture repos and per-test `XDG_CACHE_HOME`, never the network.
- **SGD-PLAN-001.A4:** Manifest is parsed during per-spool sync; needs satisfaction is computed in `sync-approved-spools` after all entries settle; `use!` gates on the recorded sync results, keeping activation decisions data-driven off sync state.

## SGD-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| SGD-PLAN-001.AA1 | `src/skein/api/weaver/alpha.clj` | Grammar, fetch/cache, manifest, gating |
| SGD-PLAN-001.AA2 | `test/skein/spools_test.clj` | Coverage for all new contracts |
| SGD-PLAN-001.AA3 | `docs/writing-shared-spools.md`, `spools/README.md` | Publishing/consuming guidance |
| SGD-PLAN-001.AA4 | `devflow/specs/` (at ship) | Merge feature deltas |

## SGD-PLAN-001.P4 Contract and migration impact

- **SGD-PLAN-001.CM1:** Durable contract changes are fully described in the two feature spec deltas; existing `:local/root` workspaces are untouched (grammar is additive, TEN-000@1 alpha—no migration needed).

## SGD-PLAN-001.P5 Implementation phases

### SGD-PLAN-001.PH1 Grammar

Outcome: git coordinate kind validated/normalized (`:kind`, computed cache `:root`), full test coverage, existing local behavior unchanged.

### SGD-PLAN-001.PH2 Fetch

Outcome: `sync!` materializes git coordinates into the content-addressed cache with tag verification and loud per-spool fetch outcomes; `file://` fixture tests prove fetch/cache-hit/failure paths.

### SGD-PLAN-001.PH3 Manifest and gating

Outcome: `spool.edn` parsed and validated, unmet-needs computed and reported, `use!` refuses activation on unmet needs / unloadable provides; tests prove each outcome and gate.

### SGD-PLAN-001.PH4 Docs

Outcome: publishing/consuming guidance in `docs/writing-shared-spools.md` and `spools/README.md` consistent with the spec deltas.

## SGD-PLAN-001.P6 Validation strategy

- **SGD-PLAN-001.V1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green after every phase; `(cd cli && go test ./...)` untouched but run before ship; `clojure -M:smoke` before merge.
- **SGD-PLAN-001.V2:** Every phase is independently reviewed (two-reviewer agent review with synthesis) before its task is closed.

## SGD-PLAN-001.P7 Risks and open questions

- **SGD-PLAN-001.R1:** Sha-addressed shallow fetch (`git fetch --depth 1 <url> <sha>`) is rejected by some servers → fall back to full fetch; covered in PH2 contract.
- **SGD-PLAN-001.R2:** `use!` provides-verification requiring namespaces inside the spool classloader must mirror existing module-load classloader handling to avoid loading into the wrong loader.

## SGD-PLAN-001.P8 Task context

- **SGD-PLAN-001.TC1:** Tasks 1–3 are serialized (same owned files: `src/skein/api/weaver/alpha.clj`, `test/skein/spools_test.clj`); task 4 owns docs only. Execution mode: delegated headless `pi-main` runs via the agents spool from a disposable weaver workspace, per-task two-reviewer agent review, orchestrator verifies validation and commits after each accepted task. Workers never commit. Read `devflow/TENETS.md` (TEN-003 especially) before implementing.

## SGD-PLAN-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### SGD-PLAN-001.DN1 Planning review — 2026-07-03

- Agent review (run q9h5j) of the planning package found: (a) `XDG_CACHE_HOME` test isolation requires a redefinable private cache-base fn (peers `state-root` precedent) — added as SGD-TASK-001.MI5 and folded into both tasks' DW1; (b) missing DW case for `:deps/root` on local entries — added; (c) sync outcome maps hardcode `:local/root`, so per-kind outcome shape is now specified (SGD-TASK-001.MI6 + daemon-runtime delta D1); (d) `root-paths` deps.edn error message must become kind-neutral (SGD-TASK-002.MI6). Plan marked Reviewed after these amendments.

### SGD-PLAN-001.DN2 Task 2: content-addressed fetch — 2026-07-03

- Review (runs pzrru/oscla, synthesis cq4ag) found no blocking issues; the convergent finding — `sync-approved-spool!`'s outer `catch Throwable` swallowed non-fetch errors into `:fetch-failed` — was fixed by the orchestrator (extracted `materialize-git-spool-outcome` so only the materialization call translates throws into outcomes).
- Follow-up scope (not this feature): cache-base-level lock against concurrent fetches of the same `:git/sha` (racing atomic move surfaces as `:fetch-failed` despite valid content); MI5 "fetched but missing effective root" test case.
- Full-suite runs intermittently fail on pre-existing timing flakes unrelated to this feature (`chime_test/notifier-binding-and-manual-notify`, reproduced on unmodified main; `shuttle_test/reap-manual-leaves-the-session-to-the-human`). `skein.spools-test` and `skein.runtime-deps-test` were green on every run. See open RFC 2026-07-03-test-concurrency.

### SGD-PLAN-001.DN3 Task 3: manifest and gating — 2026-07-03

- Review (runs 010x9/dgugd, synthesis 9os4e): no blocking findings. Orchestrator applied the review fixes directly: manifest-failure outcomes now thread `:fetch` (kind-shaped consistency with D1); `manifest-invalid` shields `:manifest` (renamed to `:invalid-manifest` on failure) and `:reason` from validator ex-data; added tests for git+manifest-failure fetch retention, `:manifest` absence on failure, and `:required? true` throwing on `:provides-unloadable`.

### SGD-PLAN-001.DN4 Ship — 2026-07-03

- Task 4 docs reviewed (runs 8rdh3/m7st2, synthesis qhez0): one completeness gap (consent loop omitted `use!` gating), fixed. All four tasks complete; both spec deltas merged into root specs, including amending SPEC-004.C48/C49 (now `@2`) whose blanket git-fetch exclusion predated RFC-017, and adding SPEC-004.C91–C94.
- Cut/deferred scope (candidates for follow-up features): cache-base-level concurrent-fetch lock; `:needs` naming skein's own blessed namespaces with runtime-reported levels (SGD-PROP-001.Q1); MI5 fetched-but-missing-effective-root test case.
- Delegation record: implementation runs f03c7/kzzhi/8w6fn/eyyoe (pi), reviews per task with two reviewers + synthesizer, all coordinated over the agents spool from a disposable weaver workspace. RFC-017 archived with this feature.

### SGD-PLAN-001.DN5 Pre-merge deep review hardening — 2026-07-03

- Final `code-review --deep` found two P1s, both fixed pre-merge with regression tests: (1) spool `deps.edn` could bypass the consent model — `:paths` now must resolve inside the spool root, and git-kind spools may not declare tools.deps `:deps` (dependencies belong in `spool.edn` `:needs`); local-kind roots keep pre-existing `:deps` behavior as trusted user code — follow-up candidate if shared-repo `:local/root` abuse appears. (2) `delete-tree!` followed symlinks during temp-checkout cleanup, risking deletion outside the cache; replaced with a no-follow `walkFileTree` visitor. Final suite fully green: 378 tests, 2040 assertions, 0 failures.
