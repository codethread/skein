# Consumer Cutover Proposal

**Document ID:** `PROP-Cut-001` **Last Updated:** 2026-07-24 **Related RFCs:** None **Related ADRs:** [ADR-003](../../adrs/0003-spool-activation-lifecycle.md) **Related root specs:** [SPEC-004 daemon-runtime](../../specs/daemon-runtime.md) **Prior art:** [PROP-Sbl-001](../9snqu-siblings-rollout/proposal.md) (sibling releases, landed `545ebf8`), OLR cutover precedent (epic tsofs notes nqiog/i4hpi)

## PROP-Cut-001.P1 Problem

Every install!-retirement release is published but nothing consumes it yet. The canonical skein-src
world pins devflow v4 / kanban v8 / agent-run v12; four sibling worlds (devflow.spool,
kanban.spool, dresser.spool, agent-harness.spool) pin kanban v7; the notes world pins agent-run
v12. The epic's user-visible payoff — event-driven chime notifications through the module path —
was verified once at iteration 1 (ifenn), but the epic's DONE-WHEN wants it re-verified at final
state with all pins current, plus the docs debt closed (7fg72, grep gate, CLAUDE.md staleness).

Live-state facts this proposal builds on (verified 2026-07-24):

- Only the canonical skein-src weaver is running (pid 85020). The devflow.spool, kanban.spool,
  dresser.spool, and notes weavers are all `state: none` — their cutover is a config edit picked
  up at next start, not a live refresh.
- `test/skein/config_ops_test.clj` already activates kanban via
  `skein.spools.test-support/activate-spool!` with a v8/v9-compatible literal triple (9snqu S5);
  there is no staged edit to land, contrary to the card body's earlier plan.
- `docs/spools/testing.md` already carries the blessed test-activation story ("Activating spool
  modules from test fixtures", ADR-003.P7 conventions + the test-support helper) — shipped by
  rrvnn. The card's "testing.md gains the story" item is verification, not authoring.
- The 7fg72 material (stale install! phrase in the agent-run cookbook) is fixed on agent-harness
  main since `75b8a23`, i.e. shipped in v12 and v13; `rg install!` over the repo's `agent-run/`
  and `docs/` trees is clean today.
- No workspace anywhere pins or module-declares dresser.spool (checked every world's `spools.edn`
  and `init.clj`); dresser v1 has no consumer to cut over.

## PROP-Cut-001.P2 Goals

- **PROP-Cut-001.G1:** skein-src `.skein/spools.edn` pins move together (tag + peeled sha per
  entry): devflow v4→v5 (`98ecdd8a…`), kanban v8→v9 (`46c4101b…`), agent-run v12→v13
  (`35655ca2…`). No root-mapping changes — no new opt-ins. Landed to main via the `strand land`
  workflow with all blocking gates green.
- **PROP-Cut-001.G2:** The canonical weaver picks the new pins up via `runtime/refresh!` after
  landing — no restart unless the refresh itself records a pending generation (then the epic's
  AUTHORITY restart discipline applies). All modules end `:applied`/`:unchanged` with no
  conflicts or unledgered residuals attributable to this change.
- **PROP-Cut-001.G3:** Sibling world cutover: kanban v7→v9 in devflow.spool, kanban.spool,
  dresser.spool, and agent-harness.spool `.skein/spools.edn`; agent-run v12→v13 in notes. Each
  world's weaver is verified stopped at edit time (else refreshed the same way as G2); evidence
  recorded on the card.
- **PROP-Cut-001.G4:** Chime verification at final state, recorded on the card with command
  output: the canonical world's event handlers include `:chime/engine`; a real attention rule
  fires a notification with zero failures; `strand runtime status` (or the runtime-status op the
  world registers) shows no module problems.
- **PROP-Cut-001.G5:** Docs closure: 7fg72 closed with the shipped-fix evidence; the epic
  grep-gate rerun recorded (expected surviving matches only: `.skein/kanban_tracker.clj`
  config-level definition, the historical line in `docs/spools/writing-shared-spools.md`, the
  historical comment in `test/skein/config_test.clj`); CLAUDE.md files verified free of stale
  install! guidance; `docs-check` green.
- **PROP-Cut-001.G6:** Acceptance gates: full locked suite (`flock` on `/tmp/skein-test.lock`),
  `(cd cli && go test ./...)`, `clojure -M:smoke`, `make fmt-check lint reflect-check docs-check`,
  `make spool-suite-gate` (pinned external suites vs this checkout — it must hold with the new
  pins), and clean `git status` after validation.

## PROP-Cut-001.P3 Non-goals

- **PROP-Cut-001.NG1:** No dresser.spool pin adoption. The card scopes dresser "where consumed";
  nothing consumes it (P1). Wiring dresser into a world for the first time is new scope with its
  own executor-prereq decisions, not a cutover. Recorded as a card note instead.
- **PROP-Cut-001.NG2:** The notes world's `local_adapters.clj` install!-style local modules and
  skein-src's `.skein/kanban_tracker.clj` stay as they are — workspace config files, explicitly
  out of the epic's DONE-WHEN.
- **PROP-Cut-001.NG3:** No sibling weaver starts. Their worlds cut over as stopped configs; when
  next started they load v9/v13 (validated in the disposable smoke, S1).
- **PROP-Cut-001.NG4:** notebook.spool has no `.skein/spools.edn`; nothing to cut over
  (9snqu verified the repo install!-clean).
- **PROP-Cut-001.NG5:** No new testing.md authoring — the story shipped with rrvnn (P1);
  this feature only verifies and records.

## PROP-Cut-001.P4 Proposed scope

- **PROP-Cut-001.S1 (disposable smoke, before any real config changes):** One disposable
  workspace from `mktemp -d` (guarded `${ws:?}`) whose `spools.edn` pins exactly the new
  coordinates (devflow v5, kanban v9, agent-run v13 with the canonical root mappings) and whose
  `init.clj` declares the corresponding guarded modules; start its weaver, assert every module
  `:applied` and a representative op from each family responds (`strand kanban about`,
  devflow op registration, `strand agent`-surface presence); tear it down. This validates all
  three git coordinates and the deleted-installer surface end to end before any real-world edit.
- **PROP-Cut-001.S2 (skein-src pin bump):** Edit `.skein/spools.edn` per G1 on this branch.
  Focused validation: `clojure -M:test skein.config-ops-test` plus the spool-facing namespaces;
  full gates at acceptance (G6). Land via `strand land` (coordinator-only) to main.
- **PROP-Cut-001.S3 (canonical refresh + verification):** After land (canonical main
  fast-forwarded by the land pull-main gate), `runtime/refresh!` on the live weaver via its
  nREPL; assert module statuses (G2); run the chime verification (G4); record outputs on the
  card. Restart only if refresh records a pending generation, per AUTHORITY discipline.
- **PROP-Cut-001.S4 (sibling world cutover):** Apply G3's five config edits. Weavers verified
  stopped immediately before each edit (`mill weaver status --workspace`). The coordinates were
  proven by S1; no per-world weaver start.
- **PROP-Cut-001.S5 (docs closure + epic hygiene):** G5's checks and records; close 7fg72;
  grep-gate output and dresser/notebook "clean/no-consumer" records on the rtnfv card; card
  finished done; epic waq0l finished done once rtnfv is the last open feature (board check).

## PROP-Cut-001.P5 Open questions

- **PROP-Cut-001.Q1:** None structural. Judgment calls recorded here: NG1 (dresser stays
  unconsumed rather than inventing a consumer), NG3 (stopped worlds cut over cold), and treating
  the card's testing.md/staged-edit items as verify-and-record because prior features already
  shipped them (P1).
