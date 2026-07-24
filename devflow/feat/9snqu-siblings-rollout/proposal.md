# Sibling Spools Mechanical Rollout Proposal

**Document ID:** `PROP-Sbl-001` **Last Updated:** 2026-07-24 **Related RFCs:** None **Related ADRs:** [ADR-003](../../adrs/0003-spool-activation-lifecycle.md) (P5 decision C, P7 conventions) **Related root specs:** [SPEC-003 repl-api](../../specs/repl-api.md), [SPEC-004 daemon-runtime](../../specs/daemon-runtime.md) (C46b) **Prior art:** [PROP-Itr-001](../rrvnn-intree-installer-removal/proposal.md) (in-tree removal, landed `ac5307e`)

## PROP-Sbl-001.P1 Problem

Three sibling spool repos still carry the retired imperative activation path. devflow.spool exports a no-op `install!` metadata shim (`devflow.clj:815`) whose own docstring says to use `contribute`/`reconcile`; its suite still calls it twice, and route publication in tests bypasses the module kernel via `registry/replace-owner!`. kanban.spool exports an `install!` (`kanban.clj:1574`) that duplicates its `contribute`/`reconcile` surface; its two suites activate through it, and peering prereq remedies point users at deleted installers (including skein-src's `skein.spools.guild/install!`, gone since `ac5307e`). agent-harness.spool's src is already clean (OLR F17), but two tests carry generation guards that `requiring-resolve` the deleted `ct.spools.delegation/install!` — always nil now, so the `agent spend` CLI-op path silently stopped being exercised and the agent-failures query test hand-registers what the module contributes.

The version plan drifted from the card (note j2s4g): iteration 10 shipped test-only compat releases devflow v4 / kanban v8 (in-tree activation converted to image-mode `module!`), and agent-harness is at v12 with the 7fg72 cookbook fix already shipped (`75b8a23`). This feature owns each repo's OWN installer surface.

## PROP-Sbl-001.P2 Goals

- **PROP-Sbl-001.G1:** No `defn install!` in devflow.spool or kanban.spool `src/`; agent-harness stays clean. (`install-peering!` is out of scope by recorded decision — see NG2.)
- **PROP-Sbl-001.G2:** Each converted repo exports its base module declaration datum (`<spool>/module`, the ADR-003.P7 `:ns`/`:contribute`/`:reconcile` triple) as the single authored source consumers and fixtures start from.
- **PROP-Sbl-001.G3:** Every suite activates its own spool via `runtime/module!` (image mode from the repo classpath); no test resolves a deleted installer or bypasses the kernel where the module path is the thing under test.
- **PROP-Sbl-001.G4:** Remedy strings and test prose describe the module lifecycle as current — no guidance pointing at deleted entry points.
- **PROP-Sbl-001.G5:** Releases: devflow v5 and kanban v9 with explicit compat-break records (deleting an exported fn IS a break; TEN-000@1 + ADR-003 authorize; NG6 precedent), agent-harness v13 test-only; annotated tags, peeled shas recorded on the card.

## PROP-Sbl-001.P3 Non-goals

- **PROP-Sbl-001.NG1:** No consuming workspace pin bumps — the cutover feature (rtnfv) owns them. skein-src is untouched except the staged `.skein/config_ops_test.clj` edit off `ct.spools.kanban/install!`, committed on this branch but landed with the cutover (it can only go green once the consuming pin drops the installer).
- **PROP-Sbl-001.NG2:** `install-peering!` stays imperative: kanban.spool commit 5e7cb5c records peering activation as a dedicated opt-in adapter module in consuming config; it is not a `defn install!` and the epic DONE-WHEN does not cover it. Its prereq remedies are reworded (G4), not removed.
- **PROP-Sbl-001.NG3:** dresser.spool — feature kst0n (parallel, real redesign).
- **PROP-Sbl-001.NG4:** notebook.spool and notes need no code change (grep-verified clean of `install!`); recorded as a card note, no releases.
- **PROP-Sbl-001.NG5:** agent-harness's file-based `activate-module!` test helper is not migrated to `:load :image` wholesale; the delegation conversion uses the existing helper. A helper-modernization pass without a driving defect is churn.

## PROP-Sbl-001.P4 Proposed scope

- **PROP-Sbl-001.S1 (devflow.spool → v5):** Export `devflow/module`. Delete `install!`. Convert `publish-devflow-routes!` in `devflow_test.clj` to declare the `:devflow` module via `runtime/module!` (image mode, `:after [:workflow]`), so the route-repoint and route-deletion tests exercise replacement and deletion-by-omission through the actual kernel instead of `registry/replace-owner!`; `with-runtime` activates workflow then devflow. The return-coverage test activates the module and keeps its provenance-filtered guard; the dependency-sentinel test drops the installer-metadata assertion and keeps the direct fn check.
- **PROP-Sbl-001.S2 (kanban.spool → v9):** Export `kanban/module`. Delete `install!` (behavior audit done: everything it registers is in `contribute`; vocab and spool-state live in `reconcile`, which install! never seeded — the module path is a superset). Convert `with-kanban` (`kanban_test.clj`) and the four `kanban_peering_test.clj` call sites to module activation. Rewrite `peering.clj` prereq remedies (lines ~693/697) and ns/docstring prose to name module activation; update the remedy regexes at `kanban_peering_test.clj:68,85`. Fix stale "install! declares/registered" assertion prose.
- **PROP-Sbl-001.S3 (agent-harness.spool → v13):** Fast-forward main to origin (`c7d2266`). Replace `delegation-install` (`agent_run_test.clj`) and the guard in `install-agent-failures-query!` (`subagent_test.clj`) with delegation module activation via the repo's `activate-module!` helper, making the spend test unconditionally exercise the `agent spend` op and the failures-query test consume the contributed query. Tidy stale installer wording in `bench_test.clj:793-794` assertion messages, `bench_metrics_test.clj`, `delegation_test.clj` testing strings, `subagent_test.clj:624` comment.
- **PROP-Sbl-001.S4 (releases):** Per repo: suite green against skein-src main (5bae1: `:test` aliases hard-code `../skein-src`; keep main checked out and green there), `bin/compat-alarm` vs previous marker with the break named explicitly for v5/v9, annotated tag, peeled sha + break record noted on the card. Tags are immutable; mistakes ride the next marker.
- **PROP-Sbl-001.S5 (staged skein-src edit):** Convert `.skein/config_ops_test.clj`'s `ct.spools.kanban/install!` resolve to the module path on this branch; do NOT land — noted for rtnfv.

## PROP-Sbl-001.P5 Open questions

- **PROP-Sbl-001.Q1:** None structural. ADR-003 settled the pattern; this is its mechanical application to the peers. Judgment calls recorded here: NG2 (peering stays imperative per 5e7cb5c), NG5 (no helper modernization), and S1's choice to route the devflow route-replacement tests through the module kernel (the test's own docstring already frames `replace-owner!` as a stand-in for module publication; with the shim gone, the honest form is the kernel itself).
