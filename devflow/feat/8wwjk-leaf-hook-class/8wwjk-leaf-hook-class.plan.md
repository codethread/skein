# 8wwjk-leaf-hook-class plan

**Document ID:** `PLAN-Lhc-001`
**Status:** Draft
**Last Updated:** 2026-07-22
**Proposal:** [proposal.md](./proposal.md)
**Spec deltas:** [repl-api](./specs/repl-api.delta.md), [daemon-runtime](./specs/daemon-runtime.delta.md), [cli](./specs/cli.delta.md)

## PLAN-Lhc-001.P1 Goal and scope

Ship DELTA-Lhc-001/002/003: mandatory per-leaf `:hook-class`/`:deadline-class`,
arity-N fractal subcommands, leaf-resolving hook gate and deadlines, per-node help
classes, and the full owned-surface sweep (in-repo ops, publication-seam
constructors, sibling spools with pin bumps). Breaking, one queue, no migration.

## PLAN-Lhc-001.P2 Approach

One **design thin-slice first** (oracle seat): implement the fractal mechanism
end-to-end — recursive structural validation with leaf-class rules, recursive
parse routing with path-vector results, returns mirror-recursion, leaf-resolving
socket gate and deadline lookup, per-node help projection with the schema-version
bump — and apply it to exactly **one** op surface (batteries `spool`, folding
`spool-status` in as a `status` read verb) plus a synthetic depth-3 grammar in the
test namespace. The slice proves composition and the public contracts; it may
amend the deltas only with evidence from the applied seam. Everything after it is
mechanical and fans out to terra-med seats in file-disjoint slices, with sol-med
as the standing cross-vendor reviewer for the oracle-authored slice.

The mechanism lands breaking: the moment the validator requires leaf classes,
every unswept registration fails loudly at registration/publication. The queue is
therefore ordered so the in-repo sweep lands in the same acceptance window as the
mechanism, and sibling spool releases + pin bumps complete the window;
`spool-suite-gate` is expected red mid-queue and green at acceptance.

## PLAN-Lhc-001.P3 Affected areas

- `skein.api.cli` (`alpha`, `internal/validation`, `internal/help`): recursive
  validator, parse, explain; leaf-class node metadata; reserved names per level.
- `skein.api.weaver.internal.op-entry` + `skein.api.return-shape.alpha`: mandatory
  leaf classes, no defaults, returns mirror-recursion, alignment checks.
- `skein.core.weaver.socket`: leaf-resolving payload-hook gate and deadline
  default; pre-hook loud unknown/missing-verb failures.
- `skein.core.weaver.help`: node-key classes with per-kind null semantics,
  verb-path slicing, catalog rule, schema-version bump; `skein.core.weaver.core-registry`:
  publication entry validation seam.
- `skein.api.weaver.alpha`: dispatch label from path vector (retire the `:action`
  special-case); recursive annotation/glossary collection.
- `skein.test.alpha`: path-aware return selection; synthetic depth-N fixtures.
- In-repo registrants: batteries (all ops + reference renderer), `help` builtin,
  guild, text-search, `.skein/config.clj` ops, `.skein/workflows.clj`,
  `.skein/spools/macros` `defop`, test fixtures, smoke suite.
- Sibling spools (own repos, released by tag, pinned in `spools.edn`): kanban,
  delegation, bench, agent-harness, workflow.
- Docs: root specs (delta promotion), `docs/reference.md`, spool authoring guide
  CLI-style section, generated `*.api.md`.
- Go CLI: verification only (pre-op help alias vs deeper paths); no code changes
  expected.

## PLAN-Lhc-001.P4 Implementation phases

- **PLAN-Lhc-001.PH1 (design slice — oracle):** Mechanism end-to-end + the one
  applied op + focused cold tests; deltas amended if the seam disproves anything.
  Cross-vendor review (sol-med) gates the phase.
- **PLAN-Lhc-001.PH2 (in-repo sweep — terra-med, file-disjoint parallel):**
  (a) batteries ops + renderer + owner tests; (b) guild/text-search/publication
  constructors + smoke; (c) `.skein` config ops, workflows, `defop`, fixtures.
  Each slice: leaf classes declared, positional-action grammars folded to real
  verbs where the surface reads better, cold focused suites green.
- **PLAN-Lhc-001.PH3 (specs + docs — terra-med):** Promote deltas into root
  specs, sweep `docs/reference.md` + authoring guide, regenerate api docs,
  `make docs-check` green.
- **PLAN-Lhc-001.PH4 (sibling spool fan-out — terra-med, one seat per repo):**
  Per spool: adopt leaf classes (and real nesting where grammars used positional
  actions, e.g. kanban `task`), suite green against the feature checkout, release
  tag, pin bump in skein-src. Ceremony per repo conventions (`spool bump`,
  annotated `vN` tags).
- **PLAN-Lhc-001.PH5 (acceptance):** Full locked suite under the flock, Go tests,
  smoke, `spool-suite-gate`, quality gates at zero; land via the coordinator
  landing workflow.

## PLAN-Lhc-001.P5 Validation strategy

Per-slice: cold `clojure -M:test <owned namespaces>`. Phase gates: PH1 adds
socket-level integration coverage for pre-hook verb failures and read-leaf hook
skipping; PH2 keeps smoke green; PH4 runs each spool's own suite against this
checkout. Acceptance: full locked suite, `(cd cli && go test ./...)`,
`clojure -M:smoke`, `make spool-suite-gate`, `make fmt-check lint reflect-check
docs-check`, clean `git status --short`. Disposable workspaces for every runtime
experiment; the canonical `.skein` world is never touched by tests.

## PLAN-Lhc-001.P6 Task context

Workers receive: the three deltas as the binding contract; the decision notes
(`77xab` supersedes `mnl04`); TEN-000@1/TEN-003 as the license for breaking, loud
changes; the file-ownership boundaries above (sibling tasks never share files);
and the rule that warm-REPL output satisfies no gate. Sibling-spool workers work
in their spool's own repo checkout and must not touch skein-src beside the pin
bump task, which is coordinator-sequenced after all spool releases exist.

## PLAN-Lhc-001.P7 Developer Notes

- 2026-07-22 (coordinator): proposal review e8zr7 findings folded; note the
  publication seam (`core-registry` entry spec is `map?` today) is a required
  enforcement point, not an optional hardening.
- Live-weaver caution: the canonical weaver keeps running pre-change code until
  the user-sanctioned restart/refresh after landing; `.skein` config edits on
  this branch are inert until merged. Never refresh or restart the canonical
  weaver from this feature's tasks.
