# RFC-011 Implementation Review

## Findings

### blocking — `:for` misses treadle-run provenance because it reads the wrong attribute namespace

`spools/shuttle/src/skein/spools/shuttle.clj:485-488` makes `run-summary` prefer `(sattr run "treadle/gate")`, but `sattr` always reads `:shuttle/<k>` attributes. Treadle stamps run strands with `:treadle/gate` (`spools/shuttle/src/skein/spools/treadle.clj:142`), so treadle-backed runs will not report `:for` as their gate. That breaks RFC-011.REC3 and `strand op agent ps --for <gate-id>` for the primary treadle case. The implementation should read the normalized `:treadle/gate` attribute directly (or via a non-shuttle attr helper) before falling back to parent-of sources excluding `shuttle/spawned-by`.

### blocking — `stalled-gates` query marks every delegated active run gate as stalled

`spools/shuttle/src/skein/spools/treadle.clj:204-209` registers `stalled-gates` as active subagent gates with either `treadle/error` or merely an existing `treadle/run`. RFC-011.REC2 requires active gates with `treadle/error`, or `treadle/run` pointing at a failed/exhausted run. With the current query, a healthy in-progress delegated gate with a running run is reported as stalled. That will page the coordinator for normal work and contradicts the attention semantics in REC1. If the query DSL cannot join from gate to run phase, this should not be approximated broadly; it should be backed by a predicate/projection that checks the run, or narrowed to spawn-side `treadle/error` until a correct projection exists.

## Non-blocking notes

- `src/skein/spools/workflow.clj:1221-1248` implements the expected `await!` return shape for `:done`, `:checkpoint`, `:gate`, `:stalled`, and `:timeout`, with the stall predicate passed by name and registered separately. This keeps the workflow spool free of treadle/shuttle vocabulary as requested by RFC-010.NG5.
- `spools/shuttle/src/skein/spools/shuttle.clj:775-792` fails loudly when the `.out` or derived `.err` log path is missing, matching REC4/TEN-003.
- `.skein/config.clj:143-151` excludes only `shuttle/run = "true"` records while preserving active workflow steps/checkpoints/plain tasks, so the work-query change does not appear to hide genuinely assignable strands.
- `agent-failures` (`spools/shuttle/src/skein/spools/shuttle.clj:855-858`) and `blocked-deliveries` (`spools/shuttle/src/skein/spools/treadle.clj:210-213`) match the current attribute shapes; the correctness problem is specific to `stalled-gates`.

## Validation

- `PATH=/opt/homebrew/opt/openjdk/bin:$PATH clojure -M:test` — PASS: 279 tests, 1612 assertions, 0 failures, 0 errors.
- `git status --short` after validation showed only source/doc/test modifications already in the working tree; no generated SQLite/runtime artifacts were present.

## Verdict

request-changes — the implementation is close and the core await/stall-predicate shape aligns with RFC-011, but two REC2/REC3 issues are user-visible: treadle runs will not be discoverable with `ps --for <gate-id>`, and `stalled-gates` will over-report every active delegated gate with a run as stalled. Those should be corrected before approval.

## Fixes applied

- Fixed blocking finding: `run-summary` now reads exact `:treadle/gate` provenance before falling back to non-`spawned-by` `parent-of` sources, so `strand op agent ps --for <gate-id>` can find treadle-backed runs. Added `run-summary-reports-treadle-gate-provenance` coverage.
- Fixed blocking finding: narrowed the registered `stalled-gates` query to active subagent gates with `treadle/error`, avoiding false stalled reports for healthy in-progress delegated runs. The richer failed/exhausted run check remains correctly handled by the registered `:treadle` stall predicate until there is a query projection that can join gate to run phase. Added `stalled-gates-query-reports-only-spawn-errors` coverage.
- No nits were listed.

Validation after fixes: `PATH=/opt/homebrew/opt/openjdk/bin:$PATH clojure -M:test` passed with 281 tests, 1615 assertions, 0 failures, 0 errors. `git status --short` showed only existing source/doc/test/workspace modifications and the untracked attention-surface review directory; no generated SQLite/runtime artifacts were present.
