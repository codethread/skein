# Backlog

Backlog sections define how items may be acted on:

- **In progress** — has a backlog strand and tracked work in Skein.
- **Ready** — ready to turn into a strand and delegate.
- **Refinement** — needs an explicit human command before anyone acts on it.

## In progress

_No active items currently listed here._

## Ready

- [ ] `uuy5f` Build the shared roster/backlog-style feature tracking spool for active work: consistent attributes, `track!`/`finish!`, roster query, and `await-quiet!`. Source: `devflow/rfcs/2026-07-02-feature-tracking-registry.md` (`RFC-014.REC1`, `RFC-014.REC3`).
- [ ] `le0lm` Fix strand op agent status listing closed tasks as awaiting_verification
- [ ] `d5af5` Declarative review fan-out: small-agent reviewer registry in .skein
- [ ] `0nd97` Fix agent delegate --ready double-reporting a just-delegated task in both delegated and skipped. Source: interactive-runs smoke; lazy skip-reason evaluation in `op-delegate` after delegation mutates run state.
- [ ] `e8azs` Chime example rule: notify the human when an interactive shuttle session is waiting (attach hint on `shuttle/mode=interactive` entering `running`). Source: interactive-runs design council follow-up; deliberately userland so shuttle stays decoupled from chime.

## Refinement

- [ ] `emsff` Evaluate CLI-backed smoke/conformance helpers for library authors after daemon-world helpers prove out. Source: `RFC-005.NG7`, `RFC-005.REC6` (archived at `devflow/archive/26-07-03__library-author-testing-support/rfcs/`). Note: retain as a post-`skein.test.alpha` follow-up; prerequisites shipped 2026-07-03 via `wbs6r`, so this is now eligible for re-evaluation on explicit human go-ahead; keep CLI subprocess support out of the first helper contract.
- [ ] `q8qcw` Decide scheduler missed-fire and clock-jump policy, including whether to add a slow safety tick. Source: `RFC-009.Q2`, `RFC-009.Q3`. Note: resolve during scheduler design after storage shape; keep policy minimal with fail-loud malformed schedules, at-least-once delivery, userland recurrence, and an explicit safety-tick decision.
- [ ] `jbwxz` Decide whether scheduler wakes share the existing async dispatch queue and whether read-only CLI scheduler introspection is worth adding. Source: `RFC-009.Q4`, `RFC-009.Q5`. Note: merge into scheduler primitive design; confirm shared async queue as the default, then decide whether initial introspection is REPL/API-only or also read-only CLI.
- [ ] `a00co` Build timer/deadline workflow gates on top of the scheduler primitive once it exists. Source: `RFC-009.C8`; related stable features: `spools/workflow.md`, `spools/shuttle/treadle.md`. Note: retain as a dependent userland follow-up after the scheduler primitive ships; avoid adding core workflow scheduler semantics.
- [ ] `hphfn` Integrate active-work tracking with workflow/devflow roots, AFK loops, and ad hoc sessions so registry presence is automatic where possible and one explicit call elsewhere. Source: `RFC-014.REC2`, `RFC-014.C1`. Note: retain as roster-spool integration scope; auto-stamp workflow/devflow roots where possible and require explicit `track!`/`finish!` for AFK/ad hoc sessions.
- [ ] `w1t3o` Decide active-work heartbeat/staleness semantics and finished-entry lifecycle for the roster spool. Source: `RFC-014.Q1`, `RFC-014.Q2`, `RFC-014.C4`. Note: retain as roster design slice; define heartbeat threshold, stale display, and deliberate cleanup/finish behavior without auto-hiding stale entries.
- [ ] `ti9yj` Expose roster entries through weaver-guild peering so manager weavers can inspect in-flight work across repos. Source: `RFC-014.C3`; related stable feature: `spools/guild.md`. Note: retain as post-roster/guild integration; once roster exists, publish a versioned guild op such as `guild.roster.v1` or include roster in a describe-compatible repo API.
- [ ] `d0cbq` Design and implement a minimal weaver scheduler primitive for proactive durable wakeups: `wake-at` + handler symbol, restart re-arm, reload-safe lifecycle, and data-first introspection. Source: `devflow/rfcs/2026-06-29-weaver-scheduler.md` (`RFC-009.OUT1`). Note: storage shape has been decided in `RFC-009.Q1.OUT`: use dedicated weaver-owned SQLite tables, not first-class strand records.
- [ ] `spya2` Decide an interactive-session concurrency cap. Source: interactive-runs design (deferred per TEN-004). Note: readiness plus per-task interactive delegation bounds it in practice; revisit only if real usage shows session pile-up, and build it as agents-spool supervision policy, not shuttle engine scheduling.

## Completed

- [x] `wbs6r` Complete library author testing support: shipped storage handles, in-memory SQLite for trusted tests, explicit storage metadata/status, `skein.test.alpha`, docs, and dogfooding. Feature archived at `devflow/archive/26-07-03__library-author-testing-support/` with RFC-005 and its spikes.
- [x] `nq2pg` Document library author setup and testing tiers: shipped as `docs/library-authoring.md` (local-root dependency guidance, testing tiers, weaver/test JVM classpath boundaries) via `wbs6r`.
- [x] `sh835` Add real Xerial SQLite in-memory daemon-world support with explicit storage metadata/status semantics: implemented within `wbs6r` scope (RFC-005.NG4a keeps in-memory in the library-testing feature); closed as merged into that feature.
- [x] `8kisd` Investigate refinement backlog items: audited refinement entries for current relevance; results synthesized from delegated run `4z4f9`. Outdated delegated-agent failure visibility item was removed; retained items now carry notes.
- [x] `h5lcx` Decide scheduler durable wake storage shape: delegated run `osia1` recommended dedicated weaver-owned SQLite storage; decision recorded in `devflow/rfcs/2026-06-29-weaver-scheduler.md`.
  - amend: user does not agree, veto
- [x] `r4268` Rebase spool-git-distribution + devflow-extraction onto main, revalidate, HITL merge sign-off (gate for all fold-in work)
- [x] `fyu6x` Docs pass: git spool distribution + devflow extraction (spools/README, devflow.md banner, root AGENTS.md coordination section, docs/skein.md, writing-shared-spools link)
- [x] `16u3p` Promote spool-checkout-root from skein.spools.test-support into skein.test.alpha (LAT-PLAN-001.DN3)
- [x] `sjd2m` Make devflow.spool self-hosting: own test suite (skein.test.alpha tiers) + contract doc, resync dual sha pins in skein-src
- [x] `aybst` Harden git spool fetch: concurrent same-sha fetch race resolves as cache hit; add fetched-but-missing-effective-root test (SGD review follow-ups)
- [x] `xrip3` RFC-018: spool.edn :needs targeting skein-shipped namespaces (first case: devflow.spool cannot declare its skein.spools.workflow dependency)
- [x] `7xq0a` Nested agent docs convention: .skein/AGENTS.md root treatment + CLAUDE.md symlink (confirm interpretation with owner first)
- [x] `g12p5` REFINEMENT: consent policy for tools.deps :deps in local-kind spool roots (shared-file vs local-file asymmetry) — human decision required
- [x] `o3syz` Fold session flake evidence into test-concurrency RFC (chime notifier, shuttle reap-manual, weaver batch-event handler race + cheap targeted fix)
- [x] `lw9fq` Bulk work-item authoring ergonomics: attr-file/stdin drift, batch creation path for bodies+edges (friction observed 2026-07-03)
- [ ] `9v0p0` Shuttle records a run as done when the harness process exits 0 with an empty result after a transport error: pi run 1vhai died with 'WebSocket closed 1000' mid-task, wrote nothing, yet phase=done/state=closed with empty shuttle/result, dodging agent-failures and retry (retry refuses: nothing failed to supersede; delegate refuses: successful run awaits verification). A done run with empty result should either fail loudly or be retryable; also consider surfacing harness-side stopReason=error from pi session tail.
- [x] `uqb6y` Op-only CLI: drop builtin strand commands and the op prefix, root-level op dispatch via invoke envelope, mill absorbs init/weaver lifecycle, shipped surface moves to skein.spools.batteries; see devflow/archive/26-07-04__op-only-cli/rfcs/2026-07-04-op-only-cli.md (RFC-019)
- [x] `ixerb` Fix .skein/config.clj config-attr forward reference breaking config-test; move/declare helper before backlog-root-orphan? and validate config tests
