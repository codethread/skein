# Spool-suite CI gate: run the pinned external spool suites against skein-src HEAD

**Document ID:** `PROP-ssc-001` **Last Updated:** 2026-07-11 **Related brief:** [brief.md](./brief.md) (scope is
the contract) **Kanban:** card `yhqfh` (p2). **Related feature:**
[`devflow/feat/unify-spool-classpath/`](../unify-spool-classpath/) — this gate was extracted from that work, which
moved `skein.spools.workflow` off Skein's main classpath and thereby broke `devflow.spool`'s standalone suite.
**Related sources (verified at authoring in the `spool-suite-ci-gate` worktree):** `deps.edn:11` (`:test`
`:extra-paths`), `deps.edn:16-26` (the `devflow.spool` / `kanban.spool` `:git/sha` pins), `.github/workflows/quality.yml`
(the `clojure-test` `:10-38`, `cli-go-test` `:40-67`, `smoke-test` `:69-95` jobs and their `actions/cache` blocks),
`.skein/workflows.clj:357-373` (the land `merge-local-verify` step), `Makefile:1` (`.PHONY` header) and `Makefile:97`
(the `test-warm` target pattern), `spools/workflow/deps.edn` (`{:paths ["src"]}`). **External sources (fetched at the
pinned shas):** `codethread/devflow.spool` `deps.edn` @ `e9b28f5` and `codethread/kanban.spool` `deps.edn` @ `54eea43`.

**Reading context.** A *spool* here is an external Clojure library (`codethread/devflow.spool`,
`codethread/kanban.spool`) that skein-src pins as a `:test` git dependency and that, in turn, tests itself against a
live skein-src checkout. The two directions of consumption are asymmetric, and only one is currently gated. This
proposal designs the missing gate. Every point ID is a grepable anchor; source citations name a stable site (a job,
a config key, a workflow step) and any `file:line` is secondary, verified at authoring.

## PROP-ssc-001.P1 Problem

skein-src and the spools consume each other in opposite directions:

- **skein-src → spools, at a pin.** `deps.edn:16-26` pins `io.github.codethread/devflow.spool`
  (`e9b28f5db61820b28a1ac1b590cc87e70c835cac`) and `io.github.codethread/kanban.spool`
  (`54eea43e3afc50c3f17335d297a74af8d6767704`) as `:test` `:extra-deps`, kept synchronized with `.skein/spools.edn`
  (`config_test` enforces the pairing, per the `deps.edn:13-26` comments). skein-src's own `clojure -M:test` exercises
  the spools **at those frozen shas**.
- **spools → skein-src, at HEAD.** Each spool's `deps.edn` `:test` alias resolves
  `io.skein/skein {:local/root "../skein-src"}` — the **live** sibling checkout. The spool suites test whatever
  skein-src currently is, not a pin.

skein-src CI runs only the first direction. The second is untested, so a skein-src change can silently break a
downstream spool suite with no signal in skein-src's own gates. **This is not hypothetical.** The
`unify-spool-classpath` feature moved `skein.spools.workflow` from Skein's main classpath into the per-spool root
`spools/workflow/src`. `skein.spools.devflow` (the one spool `kanban.spool` depends on) requires
`skein.spools.workflow`, so `devflow.spool`'s standalone `-m skein.spools.devflow-test` suite — which assembles its
classpath from `io.skein/skein`'s `:paths` — lost the workflow namespace and broke. `kanban.spool` worked around it by
adding `io.skein/workflow-spool {:local/root "../skein-src/spools/workflow"}` to its own `:test` alias
(`spools/workflow/deps.edn` is `{:paths ["src"]}`, so that local root re-adds exactly the moved source). Both the
breakage and the fix were found by hand. No skein-src gate saw it, and none will see the next one.

The concrete inversion this gate tests, and why the existing suite cannot: skein-src's suite loads the spools **at
the pin** onto skein-src's *own* assembled classpath (`deps.edn:11` lists every spool root explicitly, including
`spools/workflow/src`), so within skein-src the workflow namespace is always present. The spool's *standalone* suite
assembles a *different* classpath — the spool's `:paths` plus whatever `io.skein/skein`'s `:local/root` transitively
supplies — and that is the classpath the move broke. Only running the spool's own `clojure -M:test` against HEAD
reproduces it.

## PROP-ssc-001.G Goals

- **PROP-ssc-001.G1:** A blocking CI job runs `devflow.spool`'s and `kanban.spool`'s own suites against skein-src
  HEAD on every PR and every push to main, failing the build when HEAD breaks a pinned spool suite.
- **PROP-ssc-001.G2:** The spool shas live in exactly one place — skein-src's `deps.edn` `:test` `:extra-deps`
  (`deps.edn:16-26`). The workflow reads them at job time; it never restates a sha.
- **PROP-ssc-001.G3:** Fail loudly and legibly (TEN-003). A red run names which spool failed, at which sha, and the
  single command that reproduces it locally.
- **PROP-ssc-001.G4:** One local-reproduction surface, reused by CI, the land step, and manual runs (TEN-004) — no
  copy of the checkout-and-run logic per call site.

## PROP-ssc-001.NG Non-goals

- **PROP-ssc-001.NG1:** No pin-bumping automation. The gate tests HEAD against the *current* pins; advancing a sha
  (and re-synchronizing `.skein/spools.edn`) stays a deliberate human change (brief "Deliberately not built").
- **PROP-ssc-001.NG2:** No changes to `codethread/devflow.spool` or `codethread/kanban.spool`. They already declare
  the sibling-layout `:test` defaults this job consumes; this feature depends on those defaults, it does not edit
  them.
- **PROP-ssc-001.NG3:** No new coordinate grammar and no persisted cross-repo paths. The layout is arranged at run
  time from a symlink/checkout, not written into any committed config (mirrors the spools' own `:local/root
  "../skein-src"` convention).
- **PROP-ssc-001.NG4:** Not a replacement for skein-src's own suite. This gate is additive; it tests the spool→HEAD
  direction the existing `clojure-test` job structurally cannot (P1).

## PROP-ssc-001.C1 — the sibling layout the spool `:test` aliases require

Both spools' `:test` aliases resolve skein-src through a fixed relative local root, verified at the pinned shas:

- **`devflow.spool` @ `e9b28f5`** — `:test` `:extra-deps` is `{io.skein/skein {:local/root "../skein-src"}}`; runner
  `-m skein.spools.devflow-test`; the only other dep is `camel-snake-kebab`.
- **`kanban.spool` @ `54eea43`** — `:test` `:extra-deps` is
  `{io.skein/skein {:local/root "../skein-src"} io.skein/workflow-spool {:local/root "../skein-src/spools/workflow"}
  io.github.codethread/devflow.spool {:git/sha "e9b28f5…"}}`; runner `-m skein.spools.kanban-test`. Its comment states
  the defaults suit "local owner checkouts and CI layouts that place the repos as siblings," and pins devflow to the
  **same** `e9b28f5` skein-src approves — so kanban resolves devflow from its own deps, not from skein-src.

The load-bearing invariant: from each spool checkout, the relative path `../skein-src` must resolve to the candidate
skein-src at HEAD, and `../skein-src/spools/workflow` (kanban) to `spools/workflow/` inside it. The layout is
therefore:

```
<work>/
  skein-src/          <- the candidate checkout at HEAD (name is load-bearing: "../skein-src")
  devflow.spool/      <- checked out at the deps.edn-pinned sha
  kanban.spool/       <- checked out at the deps.edn-pinned sha
```

`skein.spools.devflow-test` and `skein.spools.kanban-test` are run from inside `devflow.spool/` and `kanban.spool/`
respectively, so their `../skein-src` resolves to the candidate. The directory named `skein-src` is what makes the
relative roots work; the candidate's git checkout can live anywhere as long as a `skein-src` entry (real dir or
symlink) points at it.

## PROP-ssc-001.C2 — the CI job

A new blocking job `spool-suites` in `.github/workflows/quality.yml`, structured like the sibling Clojure jobs
(`clojure-test` `:10-38`): `ubuntu-latest`, `timeout-minutes: 20`, `actions/setup-java@v4` temurin 21,
`DeLaGuardo/setup-clojure@13.4`, and an `actions/cache@v4` block. It is **not** `continue-on-error` (contrast the
`security-report` job `:186`), so it blocks the merge queue like every other quality gate. Trigger is the file-level
`on:` (`quality.yml:3-7`): `pull_request` and `push` to `main` — both directions covered without per-job config.

**Candidate at HEAD (`actions/checkout`).** Because the default single checkout lands in `$GITHUB_WORKSPACE` and the
spools need a sibling named `skein-src`, the job checks the candidate out with an explicit `path: skein-src`
(default ref = the triggering sha, i.e. HEAD). The two spool repos check out beside it:

```yaml
- uses: actions/checkout@v4
  with: { path: skein-src }
- uses: actions/checkout@v4
  with: { repository: codethread/devflow.spool, ref: <extracted>, path: devflow.spool }
- uses: actions/checkout@v4
  with: { repository: codethread/kanban.spool,  ref: <extracted>, path: kanban.spool }
```

**Sha extraction — single source of truth (G2).** The two `ref:` values must come from `skein-src/deps.edn`, not be
retyped. A step reads them with a one-line Clojure/`clojure.edn` read against the checked-out `skein-src/deps.edn` and
writes them to `$GITHUB_OUTPUT`:

```
(let [d (clojure.edn/read-string (slurp "skein-src/deps.edn"))
      sha (fn [lib] (get-in d [:aliases :test :extra-deps lib :git/sha]))]
  (println (str "devflow=" (sha 'io.github.codethread/devflow.spool)))
  (println (str "kanban="  (sha 'io.github.codethread/kanban.spool))))
```

`actions/checkout` runs before deps are cached, so this step needs a JVM already on the runner (setup-java precedes
it); a `clojure -M -e '…'` reads the file with zero project deps. The extracted shas feed the `ref:` of the two spool
checkouts (a `checkout` cannot itself read a not-yet-checked-out file, so extraction is a step *after* the candidate
checkout and the two spool checkouts consume its outputs — checkout order: candidate, extract, spools). The pins are
never written in the YAML.

**Layout.** With all three `path:`d as siblings under `$GITHUB_WORKSPACE`, `../skein-src` already resolves from each
spool dir (C1); no extra arranging step is needed on CI. `spools/workflow` rides inside the `skein-src` checkout, so
kanban's `../skein-src/spools/workflow` root resolves for free.

**Run.** Two steps (or one loop), each `working-directory` the spool dir, each running
`clojure -M:test` — `skein.spools.devflow-test` and `skein.spools.kanban-test`. Prefer two named steps so a red X in
the GitHub UI already attributes the failing suite (G3).

**Cache.** Mirror the sibling jobs' `actions/cache@v4` over `~/.m2/repository`, `~/.gitlibs`, `~/.deps.clj`
(`quality.yml:23-31`). Key on `hashFiles('skein-src/deps.edn')` plus the spool checkout shas (via the extraction
step's output) so a pin bump invalidates cleanly, with a `restore-keys` prefix fallback like the others. The heavy
Maven deps (next.jdbc, sqlite-jdbc, data.json, …) come transitively through `io.skein/skein`'s `:local/root`, so this
cache pays off exactly as `clojure-test`'s does; the git-dep resolution of devflow lands in `~/.gitlibs`.

**Await scale.** `clojure-test` sets `SKEIN_TEST_AWAIT_SCALE: '3'` (`quality.yml:36-38`) because hosted runners have
~2 cores. The spool suites run the spools' own runners, not skein's `:test` runner, so whether they honor that knob
depends on the spools' test support. Set the env for parity; it is a no-op if the spool runner ignores it, and the
suites are small (~1–2 min) so timing pressure is far lower than skein's full suite.

**Slot.** Alongside `clojure-test`, `cli-go-test`, `smoke-test` as a peer blocking gate. No job depends on another in
this workflow (they run in parallel), so `spool-suites` simply joins the set.

## PROP-ssc-001.C3 — the local-reproduction surface (`make test-spool-suites`)

**Recommendation: add one make target that owns sha-extraction + sibling arrangement + running both suites, and reuse
it from CI, the land step, and manual runs.** This is the TEN-004-*minimal* choice precisely because the alternative
— open-coding the checkout-and-run three times (workflow YAML, land instruction, a docs snippet) — is more surface,
not less, and drifts. One target, three call sites, one place the layout logic lives.

Shape (mirroring the `test-warm` delegation to `scripts/` at `Makefile:97-98`):

- Read the two shas from `deps.edn` (the same `clojure.edn` read as C2).
- Create a scratch dir under `$TMPDIR`; place a `skein-src` entry that resolves to the *current* checkout (a symlink
  to the repo root works — `:local/root` resolves through it, and using the live worktree is what a pre-push local
  gate wants: it verifies the tree about to land, uncommitted edits included); `git clone` (or worktree-add) each
  spool as a sibling and `git checkout <sha>`.
- For each spool, `cd` into it and run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, echoing the spool
  name, its sha, and the exact invocation before each run (G3 failure UX).
- Exit non-zero on the first failing suite; clean the scratch dir on success.

CI (C2) can either open-code the checkout via `actions/checkout` (idiomatic, gives per-repo caching) **or** call
`make test-spool-suites`. Trade-off: the make target maximizes G2/G4 (zero pins and zero layout logic in the YAML —
the workflow shrinks to `- run: make test-spool-suites`), while `actions/checkout` gives cleaner GitHub-native
caching and shallow fetches. **Recommendation: CI uses `actions/checkout` + the extraction step for caching and
shallow clones, and the make target is the developer/land surface** — the two share the *extraction logic* (identical
`clojure.edn` read) but not a shell script, because the checkout mechanics legitimately differ (network `git clone`
locally vs. cached `actions/checkout` on CI). If the spec stage judges the duplication of the ~5-line extraction not
worth two homes, collapsing CI onto `make test-spool-suites` is the fallback and still satisfies every goal. This is
the one open trade-off for the spec stage (Q1).

## PROP-ssc-001.C4 — the land `merge-local-verify` decision

`merge-local-verify` (`.skein/workflows.clj:357-373`) is the coordinator's last gate before pushing main: it
squash-merges the branch into LOCAL main and runs the full local verification set — `clojure -M:test` under the
flock, `(cd cli && go test ./...)`, `make fmt-check lint reflect-check docs-check`, and `clojure -M:smoke` — with a
`git reset --hard origin/main` on any failure. Its whole purpose (per `land about`) is "Local main must pass the full
verification gate before main is pushed."

**Recommendation: `merge-local-verify` gains the spool-suite check, as one added command in its instruction text —
`make test-spool-suites` — not as new workflow structure.** The step already lists its gate commands in prose
(`workflows.clj:367-370`); adding one more line is consistent, load-bearing behavior stays in the `make` target
(prose guides, code decides — PHILOSOPHY), and it means the coordinator reproduces the exact CI gate locally before
push. The suites' ~1–2 min cost is negligible beside the full skein suite the step already runs.

**Cost if omitted.** Correctness is *not* lost by omission: the `spool-suites` job runs on `push` to main
(`quality.yml:5-6`), and the land step immediately after `merge-local-verify` — `push-main-ci-green`
(`workflows.clj:374-385`) — watches **all** main workflows to green before the land completes. So a spool break
omitted from `merge-local-verify` still fails the land, just one step later: after main is pushed, as a red main CI,
forcing a fix-and-re-land cycle rather than a pre-push local catch. Including it moves the signal earlier (pre-push,
local, cheap) at the cost of the coordinator's machine needing network access to clone the two spool repos during the
gate. Given the target already encapsulates that and the suites are tiny, the earlier signal wins.

**Scope note.** `.skein/workflows.clj` is out of scope for the docs-only proposal task; this feature's implementation
plan carries the one-line instruction edit, gated behind the make target existing first.

## PROP-ssc-001.C5 — failure UX (TEN-003, G3)

A red run must answer three questions without the developer reading YAML:

- **Which spool.** Two named CI steps (`devflow spool suite` / `kanban spool suite`) so the failing step is the red
  X; and the make target echoes the spool name before each run.
- **Which pin.** The extraction step and the make target both echo `devflow.spool @ <sha>` / `kanban.spool @ <sha>`
  before running, so the log shows exactly which sha broke — the same sha a reader finds in `deps.edn:16-26`.
- **How to reproduce.** Every failure path prints `Reproduce locally: make test-spool-suites`. The target is the
  literal command CI's intent maps to, so the reproduction is real, not approximate.

## PROP-ssc-001.R Risks

- **PROP-ssc-001.R1 — extraction drift from the pin's true home.** If the extraction reads the wrong `deps.edn` key
  (e.g. a future refactor moves the pins), the job would check out a stale sha and test the wrong thing silently.
  Mitigation: extract by the exact lib symbol (`io.github.codethread/devflow.spool`) under
  `[:aliases :test :extra-deps]`, and fail loudly (`nil` sha → non-zero exit with a clear message) rather than
  defaulting.
- **PROP-ssc-001.R2 — a spool changes its `:test` sibling contract.** The job hard-depends on
  `io.skein/skein {:local/root "../skein-src"}` (and kanban's `../skein-src/spools/workflow`). If a future pinned sha
  of a spool changes that convention, the layout breaks. Mitigation: the pin is controlled by skein-src (NG1 — pins
  bump deliberately); a bump that changes the sibling contract is caught the moment this gate runs against the new
  sha, which is the point. The proposal records the contract (C1) so a bump author knows what to preserve.
- **PROP-ssc-001.R3 — kanban's own devflow pin drifting from skein-src's.** kanban.spool pins devflow at `e9b28f5`,
  the same sha skein-src approves today. If skein-src bumps its devflow pin without kanban following, the kanban suite
  resolves a *different* devflow than skein-src expects. Mitigation: out of scope (NG1/NG2 — this gate does not manage
  pins), but the `config_test` sha-pairing check and the spools' own comments already flag the synchronization
  expectation; a divergence surfaces as a kanban-suite red here, which is the gate doing its job.
- **PROP-ssc-001.R4 — cache key staleness on pin bump.** If the cache key ignores the spool shas, a bumped pin could
  reuse a stale `~/.gitlibs`. Mitigation: fold the extracted shas into the cache key (C2), so a bump invalidates.
- **PROP-ssc-001.R5 — extra CI minutes.** A new blocking job adds ~1–2 min of wall time. Accepted: the brief
  sanctions blocking on that budget, and the caught class of bug (silent downstream break) is exactly what bit
  `unify-spool-classpath`.

## PROP-ssc-001.V Validation gates

- The `spool-suites` job is green on this feature's own PR, running both spool suites against this branch's HEAD.
- Deliberately red once (a scratch skein-src change that breaks a spool suite) to confirm the job fails, names the
  spool and sha, and prints the reproduction command — then reverted.
- `make test-spool-suites` reproduces a CI red locally and is green on a clean HEAD.
- No sha appears literally in `quality.yml`; `grep` for the two shas finds them only in `deps.edn` (and
  `.skein/spools.edn`, the pre-existing pairing).
- Existing gates unaffected: `clojure-test`, `cli-go-test`, `smoke-test`, fmt/lint/reflect/docs stay green;
  `git status --short` clean of generated artifacts.

## PROP-ssc-001.DW Done-when

- **DW1:** A blocking `spool-suites` job in `.github/workflows/quality.yml` checks out `devflow.spool` and
  `kanban.spool` at the `deps.edn`-extracted shas, arranges the sibling layout, and runs each spool's
  `clojure -M:test` on PR and push-to-main.
- **DW2:** The spool shas exist in exactly one place (`deps.edn:16-26`); the workflow reads them at job time.
- **DW3:** A red run names the failing spool, its sha, and `make test-spool-suites` as the reproduction.
- **DW4:** `make test-spool-suites` exists as the single local surface, reused by manual runs and referenced from the
  land `merge-local-verify` instruction (`.skein/workflows.clj:357-373`).
- **DW5:** No spool repo is modified; no pin-bumping automation is added.

## PROP-ssc-001.Q Design decisions (alternatives considered)

- **PROP-ssc-001.Q1 — CI checkout mechanism: `actions/checkout` + extraction, or `make test-spool-suites`?
  (leaning: `actions/checkout` on CI, make target for local/land; C3).** The make target maximizes single-source
  (the YAML carries zero pins and zero layout logic) but loses GitHub-native shallow-fetch caching; `actions/checkout`
  gives clean per-repo caching but re-expresses the layout in YAML. Both share the ~5-line extraction. Recommended
  split keeps CI idiomatic and the local surface DRY; collapsing CI onto the make target is the fallback if the spec
  stage judges the extraction not worth two homes. **The one open trade-off for the spec stage.**
- **PROP-ssc-001.Q2 — blocking vs. `continue-on-error`? (resolved: blocking, G1).** The `security-report` job is
  `continue-on-error` (`quality.yml:186`) because its findings are advisory. A broken downstream spool suite is a
  real regression the pins promise against, and the suites cost ~1–2 min, so it blocks like `clojure-test`.
- **PROP-ssc-001.Q3 — `merge-local-verify`: add the check, or lean on main CI? (resolved: add it as one instruction
  line, C4).** Omission keeps correctness (push-main-ci-green watches all main workflows) but moves the failure signal
  to a red main after push, forcing a re-land cycle. Adding one `make test-spool-suites` line to the step's existing
  command list moves the catch pre-push and local, cheaply. **Adopted: add it, as prose calling the make target, not
  new workflow structure.**
- **PROP-ssc-001.Q4 — extract the shas, or restate them in the workflow? (resolved: extract, G2).** Restating is one
  line shorter today but creates a second pin that silently drifts from `deps.edn` — the exact silent-inconsistency
  class this feature exists to close. **Adopted: read the shas from `deps.edn` at job time; fail loudly on a missing
  key (R1).**
- **PROP-ssc-001.Q5 — layout by symlink or by copy? (resolved: symlink/`path:` sibling, C1/C3).** Copying the
  candidate into a `skein-src` dir wastes IO and can desync from the live tree; a symlink (local) or a `path:`d
  checkout (CI) resolves `../skein-src` with no copy and, locally, tests the live worktree — which is what a pre-push
  gate should verify.
