# Skein Reed Spool — Cookbook

Composition recipes for `skein.spools.reed`: how to let a workflow run a shell
check as a gate and get pass / fail back on the gate itself, and *why* the
executor is shaped the way it is.

This is the **how/why** half of the reed docs. The other two halves are:

- [`reed.md`](./reed.md) — the **contract**: the `shell/*` gate-attribute
  vocabulary, pass / fail semantics, recovery, and the attention surface. Read it
  for what the executor promises.
- [`reed.api.md`](./reed.api.md) — the **generated reference**: every public fn's
  signature and docstring, produced from the source.

Division of truth: signatures and argument lists live in the generated API doc;
narrative and composition live here and in the contract. This cookbook never
restates a fn signature or the attribute table — it links to them.

Reed sits between two spools that know nothing of process execution: the
[workflow engine](./workflow.md), which models an external wait point as a
`gate`, and the operating system, which runs commands. Reed is the only namespace
that speaks both. Load order matters — **workflow first, then reed** (its
`install!` registers the `:shell` executor and runs an initial gate scan) — see
[`reed.md`, "Loading"](./reed.md#loading).

## How to read a recipe

Every recipe has the same four parts:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which primitives combine, and how.
3. **Snippet** — a complete, runnable form (assume
   `(require '[skein.spools.workflow :as workflow])`).
4. **Why this shape** — the reasoning: why these primitives, what the attribute
   conventions buy you, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — the reed source or
its test suite.

---

## Recipe: An artifact gate — `test -s`

**Situation.** A stage produced a file, and the next stage must not start unless
that file exists and is non-empty. You want the workflow to block on a real
filesystem check, not a self-reported "done".

**Composition.** Model the check as an ordinary `workflow/gate` with waiter
`:shell`, carrying the command as `shell/argv`. Reed watches for the gate to
become ready, runs `test -s <path>` directly, and — on exit 0 — closes the gate
through `workflow/complete!`. A non-zero exit stamps `shell/error` and leaves the
gate ready.

```clojure
(require '[skein.spools.workflow :as workflow])

(def release
  (workflow/workflow
    "Release"
    (workflow/step :build "Build the jar" :self)
    (workflow/gate :verify "Jar exists and is non-empty" :shell   ; not :self — the OS checks it
                   :depends-on [:build]
                   :attributes {"shell/argv" ["test" "-s" "target/app.jar"]
                                "shell/cwd"  "/path/to/worktree"})
    (workflow/step :ship "Ship it" :self
                   :depends-on [:verify])))

(workflow/start! "release-1" release {})
(workflow/complete! "release-1")            ; finish :build; :verify becomes ready
;; reed runs `test -s target/app.jar`; on exit 0 it stamps the gate
;; workflow/outcome-by = "shell", workflow/notes = "shell command exited 0",
;; and :ship becomes ready. A missing/empty jar stamps shell/error instead.
```

**Why this shape.**

- **The workflow stays executor-agnostic.** The author writes a plain gate with
  a command; reed runs a plain process. Neither the engine nor the OS grows a
  dependency on the other — reed is the small adapter that knows both, so a gate
  is a pure-data check request (contract
  [`reed.md`, "Overview"](./reed.md#overview)).
- **Readiness drives it, so nothing polls.** Reed is an event handler over the
  same graph the engine watches. The check runs the moment the gate becomes
  ready — after `:build` closes, not before — because `depends-on` readiness is
  the only trigger.
- **The result is a gate outcome, not a side channel.** Exit 0 closes the gate
  through `workflow/complete!` with `workflow/outcome-by = "shell"`; the exit code
  and a bounded output tail land on the gate as `shell/exit-code` /
  `shell/output`. The check is part of the workflow's own audit trail.

Honest source: the worked example in [`reed.md`](./reed.md#worked-example), and
the happy-path gate test in
[`test/skein/spools/reed_test.clj`](../test/skein/spools/reed_test.clj).

---

## Recipe: A multi-file check — explicit `["sh" "-c" …]`

**Situation.** Your check needs a shell feature — a pipe, a glob, `&&`, a
`for` loop over several files. A bare `argv` executes a single program with no
shell, so none of that works directly.

**Composition.** Reed runs `shell/argv` directly with **no** implicit shell, by
design. When you genuinely want shell semantics, ask for them explicitly: make
`sh` the program and pass the script as `-c`. The gate is otherwise identical.

```clojure
(require '[skein.spools.workflow :as workflow])

(workflow/gate :lint-outputs "Every generated doc is non-empty" :shell
               :depends-on [:generate]
               :attributes {"shell/argv"
                            ["sh" "-c"
                             "for f in docs/*.api.md; do test -s \"$f\" || exit 1; done"]
                            "shell/cwd"          "/path/to/worktree"
                            "shell/timeout-secs" 60})
```

**Why this shape.**

- **No implicit shell means no accidental shell.** Passing `argv` straight to the
  process avoids a whole class of quoting and word-splitting surprises: a path
  with a space in a bare-`argv` gate is one argument, not two. The default is the
  safe one.
- **Shell features stay opt-in and visible.** When you write `["sh" "-c" …]`, the
  reader sees exactly that this gate wants a shell and reads the script inline.
  There is no hidden layer deciding whether your command "looks shell-y" — you
  asked for `sh`, so you got `sh` (contract
  [`reed.md`, "Gate request attributes"](./reed.md#gate-request-attributes)).
- **A non-zero exit is a real failure.** The `for` loop `exit 1`s on the first
  empty file; reed stamps `shell command exited 1` onto `shell/error` with the
  captured output tail, and the gate stays ready and discoverable rather than
  advancing the workflow.

Honest source: the argv contract in
[`reed.md`](./reed.md#gate-request-attributes), and the `sh -c` command tests in
[`test/skein/spools/reed_test.clj`](../test/skein/spools/reed_test.clj).

---

## Recipe: A shell check after a subagent gate

**Situation.** A step is delegated to an agent (a `:subagent` gate the treadle
fulfils), and once that agent is done you want a *deterministic* machine check —
run the tests, confirm the artifact — before the workflow moves on. You want the
agent's work and the objective check to be two separate, composable gates.

**Composition.** No new primitive: chain the two gates with `depends-on`. Author
the subagent gate as usual, then a `:shell` gate that `:depends-on` it. The
subagent gate closes when the treadle delivers the run result; only then does the
`:shell` gate become ready, and reed runs the deterministic check.

```clojure
(require '[skein.spools.workflow :as workflow])

(def implement-and-verify
  (workflow/workflow
    "Implement and verify"
    (workflow/gate :implement "Agent implements the feature" :subagent
                   :attributes {"shuttle/harness" "build"
                                "shuttle/prompt"  "Implement per specs/feature.md"
                                "shuttle/cwd"     "/path/to/worktree"})
    (workflow/gate :verify "Tests pass" :shell
                   :depends-on [:implement]                      ; only after the agent delivers
                   :attributes {"shell/argv"          ["clojure" "-M:test"]
                                "shell/cwd"           "/path/to/worktree"
                                "shell/timeout-secs"  600})
    (workflow/step :done "Mark complete" :self
                   :depends-on [:verify])))
```

**Why this shape.**

- **Two executors, one graph, zero coupling.** The treadle owns `:subagent`, reed
  owns `:shell`; each is registered for its own waiter and neither knows the
  other exists. The workflow composes them with the same `depends-on` edge it uses
  for any two steps — the objective check is not baked into the agent gate, it is
  a peer gate that runs after it.
- **The check runs once the agent's work is real.** `depends-on` readiness means
  reed does not run the tests until the subagent gate has actually closed with a
  delivered result. You get "agent did the work, *then* the machine confirms it"
  for free, without the workflow author sequencing anything by hand.
- **This is the composition, not a new gate type.** An earlier design considered a
  single gate that both delegates and self-verifies; expressing it as a `:shell`
  gate downstream of a `:subagent` gate keeps each executor single-purpose and
  the verification independently inspectable and recoverable.

Honest source: the `depends-on` gate chaining in
[`workflow.md`, "Gates"](./workflow.md#3-definition-layer), the treadle
`:subagent` contract in [`treadle.md`](./shuttle/treadle.md), and the dependent
`:shell` gate test in
[`test/skein/spools/reed_test.clj`](../test/skein/spools/reed_test.clj).

---

## Recipe: Recovering a stalled `shell/error` gate

**Situation.** A shell check failed — the command exited non-zero, timed out, or
the argv was malformed. The gate is stuck with `shell/error` and reed is skipping
it. You've fixed the underlying problem and want the check to run again.

**Composition.** Discovery is the `stalled-shell-gates` named query (or the
`gate-stalled?` predicate on a gate view). Recovery is a single mutation: **clear
the gate's `shell/error` attribute** (optionally rewriting `shell/argv` or
`shell/cwd`). The next scan finds a ready, un-errored, un-claimed `:shell` gate
and re-runs the deterministic check.

```clojure
(require '[skein.api.weaver.alpha :as api]
         '[skein.api.current.alpha :as current])

(def rt (current/runtime))              ; the active weaver runtime

;; find stalled shell gates (any gate carrying shell/error)
(api/list-query rt 'stalled-shell-gates {})

;; recover: fix the underlying problem, then clear the error.
;; Clearing shell/error is what lets the next scan re-run the check.
(api/update rt gate-id {:attributes {"shell/error" nil
                                     "shell/argv"  ["clojure" "-M:test"]}})
;; next scan re-runs the check and closes the gate on exit 0.
```

**Why this shape.**

- **Clearing the error, not retrying, is the recovery verb.** Because the check is
  deterministic and idempotent, a gate carrying `shell/error` (or a live
  `shell/running` claim) is skipped on every later scan — so an expensive
  `clojure -M:test` runs once per deliberate request, not on every graph mutation.
  Blanking `shell/error` is the explicit "run it again" signal (contract
  [`reed.md`, "Failure and recovery"](./reed.md#failure-and-recovery)).
- **Recovery is strictly simpler than treadle's.** There is no separate run strand
  to reconcile — the failure detail lives on the gate itself. A weaver crash
  between claim and outcome leaves `shell/running` with no live process; clearing
  it re-runs the same idempotent check. `shell/running` and `shell/error` are
  distinct markers, so a crashed-mid-run gate is never confused with a failed one.
- **The failure is loud and local, never lost.** A failed check does not advance
  the workflow and does not masquerade as a completed step: it stamps `shell/error`
  with the exit code and a bounded output tail and leaves the gate ready, so it
  surfaces through `stalled-shell-gates` and through `await!` (which reads the
  registered `:shell` executor) — a graph fact, not a dropped result.

Honest source: the recovery and attention sections in
[`reed.md`](./reed.md#failure-and-recovery), and the failure / recovery tests in
[`test/skein/spools/reed_test.clj`](../test/skein/spools/reed_test.clj).

---

## See also

- [`reed.md`](./reed.md) — the contract: the `shell/*` vocabulary, pass / fail
  semantics, recovery, and the attention surface.
- [`reed.api.md`](./reed.api.md) — generated signatures and docstrings for every
  public fn referenced above.
- [`workflow.cookbook.md`](./workflow.cookbook.md) — the gate and fan-out recipes
  that author the gates reed fulfils.
- [`treadle.md`](./shuttle/treadle.md) — the `:subagent` sibling reed composes
  with.
