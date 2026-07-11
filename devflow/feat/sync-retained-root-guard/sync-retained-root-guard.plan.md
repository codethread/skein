# Sync retained-root guard Plan

**Document ID:** `PLAN-srr-001`
**Feature:** `sync-retained-root-guard`
**Proposal:** [proposal.md](./proposal.md) (`PROP-srr-001`)
**RFC:** none
**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md) (`SPEC-004`),
[alpha-surface.md](../../specs/alpha-surface.md) (`SPEC-005`)
**Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (`DELTA-srr-dr-001`)
**Contract:** [brief.md](./brief.md) — the accepted design contract (card `pn7wh`); this plan
sequences it and never widens it.
**Status:** Draft
**Last Updated:** 2026-07-11

## PLAN-srr-001.P1 Goal and scope

Add one preflight to `sync!` so that when a session-retained `:local/root` spool lib is deleted
from disk **and** has left the approved allowlist, the sync fails loudly — naming the retained lib,
the deleted path, and the stub-or-restart remedies — before the next re-resolving `add-libs`
surfaces the misleading tools.deps canonicalization error that blames the wrong spool. This is the
mitigation half of the "own the spool classpath" concern; the classloader/retained-universe
redesign stays with the separate refinement card. Scope is exactly `PROP-srr-001.S1`–`.S3`: one
preflight, one loud failure, cold focused tests. No loader, classloader, config key, sync-state
field, `use!`, healthy-path, or per-entry `:missing-root` change. Why it matters and the full
alternatives history: [proposal.md](./proposal.md); durable contract change:
[daemon-runtime.delta.md](./specs/daemon-runtime.delta.md).

## PLAN-srr-001.P2 Approach

- **PLAN-srr-001.A1 — retained-root scan reading the same basis `add-libs` reads.** A private helper
  in `skein.core.weaver.spool-sync` reads `(:libs (clojure.java.basis/current-basis))` and returns
  the allowlist-orphan missing roots: each `:libs` entry that carries a `:local/root`, whose
  `io/file` no longer `.exists`, **and** whose lib symbol is absent from the current approved
  allowlist (`approved-spools` `:spools` keys). A nil/empty basis yields no orphans (no-op). Reading
  the basis is pure inspection — the helper never calls `update-basis!` or otherwise mutates
  `the-basis` (`DELTA-srr-dr-001.D3`/`PROP-srr-001.NG4`).
- **PLAN-srr-001.A2 — preflight throw at the top of `sync-approved-spools`.** Before the existing
  `(reset! (approved-spool-sync-state runtime) {})` and the per-entry map, run the scan once; when
  it returns a non-empty orphan set, throw an `ex-info` with the message and `ex-data`
  (`:missing-roots`, `:remedy` `{:stub-dir .. :restart ..}`, `:retained-universe-source`) specified
  in `DELTA-srr-dr-001.CC2`. Because the throw precedes the per-entry loop, no partial sync result
  is produced; the exception propagates through `sync!` and the reload path unchanged
  (`SPEC-004.C46`).
- **PLAN-srr-001.A3 — disjoint from the per-entry `:missing-root` path.** The allowlist-orphan
  filter (A1c) is what keeps the preflight from touching still-approved deleted roots, which stay on
  the soft `sync-approved-spool!` `:missing-root` outcome (`spool_sync.clj`, the
  `(not (.exists root-file))` branch). No edit to that branch (`DELTA-srr-dr-001.CC3`).
- **PLAN-srr-001.A4 — no new dependency, no alpha-surface change.** `clojure.java.basis` is already
  on the runtime classpath and `clojure.repl.deps` is already required in `spool-sync`. The
  preflight lives in the internal `skein.core.*` tier (`SPEC-005.C5`) and surfaces through the
  already-blessed `runtime/sync!` (`SPEC-005.C2`) by accretion, so no `alpha-surface.md` delta is
  needed (see `PLAN-srr-001.CM1`).

## PLAN-srr-001.P3 Affected areas

| ID                | Area                                        | Expected change                                                                                              |
| ----------------- | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| PLAN-srr-001.AA1  | `src/skein/core/weaver/spool_sync.clj`      | New private retained-root scan helper; preflight throw wired at the top of `sync-approved-spools` before the per-entry loop. |
| PLAN-srr-001.AA2  | `test/skein/runtime_deps_test.clj`          | New cold focused cases beside `keep-add-libs-root!`, each root-deleting case in an isolated temp world.        |

## PLAN-srr-001.P4 Contract and migration impact

- **PLAN-srr-001.CM1:** One durable contract change — a new loud whole-sync failure mode on
  `sync!` — staged in [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md)
  (`DELTA-srr-dr-001`), amending the `SPEC-004.C43`/`.C44` sync-outcome area. **No
  `alpha-surface.md` (SPEC-005) delta:** the proposal cites `SPEC-005.C2` (`runtime.alpha` blessed)
  and `.C5` (`spool-sync` internal) only as existing code facts; neither classification changes.
  The preflight is added inside the internal `spool-sync` tier and surfaces through the blessed
  `sync!` by accretion, and `SPEC-005` carries no behavioral `sync!` clause (`.C5a` covers only the
  clock/quiescence seams), so there is nothing to delta there. No data model, config key, or CLI
  surface change; no migration.

## PLAN-srr-001.P5 Implementation phases

### PLAN-srr-001.PH1 Retained-root preflight and loud failure

Outcome: `sync!` gains the retained-root scan (A1) and the preflight throw (A2). A retained
`:local/root` that has left the allowlist and been deleted from disk aborts the whole sync with an
`ex-info` whose `:missing-roots` names the lib and path and whose `:remedy` names `:stub-dir` and
`:restart`; healthy retained roots and still-approved deleted roots are untouched (A3). Cold
focused cases cover: allowlist-orphan fails loudly with the specified `ex-data`; healthy retained
roots are a no-op (`PROP-srr-001.G4`); multiple missing roots are all reported in `:missing-roots`.

### PLAN-srr-001.PH2 Stub-dir remedy round-trip and shard isolation

Outcome: a cold focused case proves the `:stub-dir` remedy — recreating a bare directory at the
deleted path — unblocks a re-sync, closing the loop on the named remedy. Each root-deleting case
runs in an isolated worktree/temp world so a poisoned root never leaks into a sibling test shard's
process-global basis, consistent with `keep-add-libs-root!`'s shard-isolation caution
(`PROP-srr-001.S3`).

## PLAN-srr-001.P6 Validation strategy

- **PLAN-srr-001.V1 (per-slice cold gate):** Each phase gates on the cold focused run
  `clojure -M:test skein.runtime-deps-test` — the touched namespace. Warm (`make test-warm
  NS="skein.runtime-deps-test"`) is for iteration only and never satisfies the gate.
- **PLAN-srr-001.V2 (quality gates):** `make fmt-check lint reflect-check` clean at zero findings
  (the delta touches no docstrings on `skein.api.*.alpha`, so `docs-check`/`make api-docs` are not
  implicated; confirm `docs-check` stays green).
- **PLAN-srr-001.V3 (full suite — acceptance/land only):** The full locked suite
  `flock -w 3600 /tmp/skein-test.lock clojure -M:test` runs only at queue acceptance and at land
  `merge-local-verify`, never as a per-slice gate. No Go, smoke, or api-docs surface changes are
  expected (`PROP-srr-001.S3`).

## PLAN-srr-001.P7 Risks and open questions

- **PLAN-srr-001.R1 — test poisons a sibling shard's basis.** A case that deletes a retained root
  can leak the poisoned root into the process-global basis other shards share. Mitigation: run each
  root-deleting case in an isolated temp world and never delete a root the shared shard basis
  retains, exactly as `keep-add-libs-root!` documents (PH2).
- **PLAN-srr-001.R2 — residual still-approved-deleted-root window.** By design (`DELTA-srr-dr-001.D2`)
  the preflight ignores still-approved deleted roots, so that narrow case can still surface the
  misleading tools.deps error. Accepted limitation; it closes only with the deferred classloader
  redesign. Not in scope here.
- **PLAN-srr-001.Q1:** None blocking task generation.

## PLAN-srr-001.P8 Task context

- **PLAN-srr-001.TC1:** The retained-universe hazard is real and already documented in-repo:
  `test/skein/runtime_deps_test.clj` `keep-add-libs-root!` works *around* it; this feature makes it
  diagnosable. Anchors an implementer needs: `sync-approved-spools` and `approved-spools` (allowlist
  keys) in `src/skein/core/weaver/spool_sync.clj`; the per-entry `:missing-root` branch in
  `sync-approved-spool!` (leave unchanged); `(:libs (clojure.java.basis/current-basis))` as the
  retained universe, the same value `clojure.repl.deps/add-libs` re-canonicalizes. Exact `ex-info`
  shape is in `DELTA-srr-dr-001.CC2`; the four required test cases are in `PROP-srr-001.S3`. Owned
  file scope: `spool_sync.clj` + `runtime_deps_test.clj` only — no Go, smoke, or api-docs surface.

## PLAN-srr-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-srr-001.DN1 Task bu8rq: spec-plan authoring — 2026-07-11

- Spec-surface judgement: exactly one durable contract change (new loud `sync!` failure mode),
  staged as `DELTA-srr-dr-001` on `daemon-runtime.md`. No `alpha-surface.md` delta — argument
  recorded in `PLAN-srr-001.CM1` and in a task note. Verified `SPEC-005.C2`/`.C5`/`.C5a` carry no
  behavioral `sync!` contract to amend.
