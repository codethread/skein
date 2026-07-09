# Skein Delegation Spool — Cookbook

Composition recipes for `skein.spools.delegation`: the shapes real delegation takes as a loop — plan, delegate, await, verify, close, repeat — and *why* each shape holds up.

This is the **how/why** half of the delegation docs. The other two halves are:

- [`delegation/README.md`](./delegation/README.md) — the **contract**: the concept
  model, every `strand agent` verb's semantics, the DAG conventions, the worker
  contract, and the panel composition layer. Read it for what the surface
  promises.
- [`delegation.api.md`](./delegation.api.md) — the **generated reference**: every public
  fn's signature, arity, and docstring, produced from source.

Division of truth: verb shapes and fn signatures live in the README and the generated API doc; the coordination *shapes* live here. This cookbook never restates a verb's flags or a fn's arity — it links to them, and to `strand agent about`, the always-current in-band manual a delegated agent reads. When a recipe needs an exact flag, follow the link.

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which verbs combine, and in what order.
3. **Snippet** — a complete, runnable form. Delegation snippets use the built-in
   `sh` harness, which runs its prompt as a shell command, so you can rehearse a
   plan's shape without a real coding agent before pointing it at one.
4. **Why this shape** — the reasoning: what the readiness graph buys you, which
   guard you're leaning on, and what the sloppy version would cost.

Each recipe cites the honest source it was distilled from — the README contract, this repo's reviewer roster and config, or the executable coverage in ``delegation_test.clj`` — so you can read the load-bearing version.

---

## Recipe: The coordinator loop, end to end

**Situation.** You have a feature that decomposes into a few tasks with dependencies, and you want agents to do the work while you stay the one who decides it's actually done.

**Composition.** Weave an `agent-plan` whose task bodies *are* the worker contracts, `delegate --ready` the whole frontier, `await --under` the plan, then verify each finished task yourself and close it — closing is the only event that unblocks the next frontier. Loop until nothing is ready, running, or failed.

```sh
# 1. Weave the plan. Every body is the complete contract the worker will read;
#    set each task's harness here so --ready can fan out without guessing.
printf '%s' '{"feature":"parser","title":"Feature: streaming parser","tasks":[
  {"key":"core","title":"Implement the token reader",
   "body":"Own src/parser/reader.clj. Add a streaming token reader. Validation: clojure -M:test. Do not commit.",
   "validation":["clojure -M:test"],"harness":"sh"},
  {"key":"docs","title":"Document the reader","depends_on":["core"],
   "body":"Own docs/parser.md. Describe the reader once core lands. Do not commit.",
   "harness":"sh"}]}' \
  | strand weave --pattern agent-plan
# => {"plan":{"id":"<plan>"},"tasks":{"core":{"id":"<core>"},"docs":{"id":"<docs>"}}}

# 2. Delegate every ready task. Read the skipped list, not just delegated —
#    a task skipped hitl or has-active-run stalls the plan until you act.
strand agent delegate --ready <plan>
# => {"plan":"<plan>","delegated":[{"task":"<core>","run":{...}}],"skipped":[]}

# 3. Block until the tree goes quiet.
strand agent await --under <plan>

# 4. Verify, then close. status surfaces what claims to be done; you decide.
strand agent status <plan>          # read awaiting_verification
strand show <core>                  # re-fetch the contract
clojure -M:test                     # re-run the task's own validation
strand update <core> --state closed # closing is what makes :docs ready

# 5. Repeat 2–4. delegate --ready is idempotent — it picks up :docs now that
#    :core is closed — until status shows nothing ready, running, or failed.
```

**Why this shape.**

- **The graph is the scheduler; closing is the only clock.** A run finishing does
  *not* advance the plan — `depends-on` readiness does, and a task only becomes
  ready when its blocker is **closed**. Verify-then-close is therefore the single
  load-bearing step: skip it and the plan silently stalls with a "finished" run
  and no ready frontier (README [§4](./delegation/README.md#4-dag-conventions)).
- **The body is the contract, not the chat.** A delegated run cannot see your
  scrollback; it reads `strand show <task-id>`. Put scope, owned files,
  validation, and commit policy in the body, and `delegate` rebuilds the prompt
  from the task's *current* body every time — which is what makes step 4's
  editing-then-retrying work.
- **`--ready` is idempotent and up-front loud.** It classifies every ready task
  exactly once, delegates the ones with no active serving run, and fails before
  spawning anything if a ready task lacks a harness — so a re-invoke after each
  close is safe and never double-delegates.
- **Verification is yours because trust isn't transitive.** `status=implemented`
  is the worker's claim; re-running validation in the task's cwd is your proof.
  The two are different events on purpose.

Honest source: the coordinator loop in [`delegation/README.md` §5](./delegation/README.md#5-worker-contract-and-coordinator-loop), the `agent-plan` weave pattern (§3), and the delegate/await/status coverage in ``delegation_test.clj``.

---

## Recipe: Recover a failed task by fixing the contract first

**Situation.** A delegated run failed. The reflex is to re-run it. Usually the run didn't fail because the agent was unlucky — it failed because the task body told it to do the wrong thing, or left out a constraint it needed.

**Composition.** Read the failure, diagnose from the logs, **edit the task body first**, then `retry` — which rebuilds the prompt from the *current* body and supersedes the dead run. Reach for `retry --fresh` only when the failure was a lost session, not a bad contract.

```sh
strand agent status <plan>          # failed:[{task:"<core>",run:"<run>",error:"..."}]
strand agent logs <run> --tail 80   # diagnose: what did it actually do?

# Fix the CONTRACT, not the run. A payload keeps a long body out of argv.
strand update <core> --attr body=:payload/contract --payload contract=./new-body.md

# retry supersedes the dead run (logs and notes survive) and spawns a fresh run
# built from the body you just edited.
strand agent retry <core>
# => {"superseded":"<run>","task":"<core>","run":{"id":"<new>","phase":"pending"}}
```

**Why this shape.**

- **`retry` reads the body at retry time, so fixing it first is the whole point.**
  If you retry before editing, you respawn the same instructions and get the same
  failure. The supersede-then-rebuild order is exactly why the body is the tuning
  knob: change the contract, and the next attempt is told the new thing.
- **Supersede keeps the evidence.** The dead run is closed as `superseded`, not
  deleted — its logs and notes stay queryable, and it stops blocking
  delegate-eligibility so the fresh run is unobstructed. A failed *helper* (a
  recon spawn or reviewer, stamped `agent-run/serves=false`) never shadows the real
  delegation failure, so `retry <task-id>` always finds the serving run.
- **`--fresh` is for a dead session, not a bad brief.** A run that continued a
  predecessor's session re-resumes it by default; when the session itself was
  lost (`agent-run/error-class "resume"`), a plain retry fails loudly telling you to
  pass `--fresh`, which severs the linkage and cold-starts from the full brief.
  Use `--fresh` when the session is gone; fix the body when the *instructions*
  were the problem. They are different failures.

Honest source: the retry semantics in [`delegation/README.md` §3](./delegation/README.md#delegation-verbs-the-task-contract-layer) and the retry/supersede coverage in ``delegation_test.clj``.

---

## Recipe: One-concern review fan-out — roster vs. ad hoc pass

**Situation.** A change needs reviewing. Two generalist reviewers reading the whole diff miss things and repeat each other; you want many small reviewers, each hunting one class of defect, fanned in to a single verdict.

**Composition.** For the review policy your workspace runs on every change, use a declared **roster** — one authoritative document naming the reviewers, their harnesses, and their single-concern contracts. For a one-off concern the roster doesn't cover, use an ad hoc `--members`/`--harness` pass instead.

```sh
# The workspace roster: one run per declared reviewer, always synthesized.
# --commit-range names the diff surface so reviewers stop re-deriving it.
strand agent review <target> --roster change-review \
  --cwd /path/to/worktree --commit-range main..HEAD
# => {"target":"<target>","reviewers":["<r1>","<r2>",...],"synthesizer":"<syn>"}

# A one-off pass the roster doesn't cover — two ad hoc reviewers, one synthesis.
strand agent review <target> --members 2 --harness claude,review-gpt --synthesize \
  --cwd /path/to/worktree --commit-range main..HEAD
```

**Why this shape.**

- **The roster is the single source of truth, so it fails loudly on drift.**
  `--roster` owns the reviewer count, harnesses, and contracts, so combining it
  with `--members`, `--harness`, or `--contract` is rejected — you can't half-
  override a policy. That's what makes the roster file a *reviewable* artifact:
  changing who reviews a change is a diff to one document
  ([`.skein/reviewers.clj`](../.skein/reviewers.clj)), not scattered flags.
- **Single-concern beats generalist, and routing is by waste-type.** Each roster
  entry hunts one defect class with a per-concern call budget; `grunt` (sonnet)
  is the default read-through seat, `explore` (haiku) is reserved for trivially
  greppable single-file concerns, and the synthesizer is a cross-vendor GPT seat
  so sign-off never comes from the model family that authored the work.
- **Reviewers never gate delegation.** Reviewer and synthesizer runs are non-
  serving helpers (`agent-run/serves=false`): they hang under the target but never
  trip its `delegate` guard, so you can review a task before *or* after
  delegating it.
- **`--commit-range` injects the diff, so reviewers read it instead of guessing
  it.** The range's changed files are expanded via `git diff --name-only` and
  handed to every reviewer as the authoritative diff surface — which also means a
  range against a **stale branch** hands reviewers a misleading surface: it can
  expand to the wrong file set, so the reviewers hunt a diff you didn't mean.
  Prefer the merge-base, or another range whose changed files match the review
  surface you mean.

Honest source: this repo's [`.skein/reviewers.clj`](../.skein/reviewers.clj) `change-review` roster (six single-concern reviewers, `review-gpt` synthesizer), and the review verb and roster semantics in [`delegation/README.md` §3](./delegation/README.md#reviewer-rosters).

---

## Recipe: Cross-vendor deliberation with a per-seat panel

**Situation.** A hard call — an architecture choice, a contentious review — wants more than one frontier model weighing in, so no single vendor's blind spot decides it. The shell verbs seat *identical* agents; you need *different* harnesses per seat.

**Composition.** Drop to trusted Clojure. `council!` (and the underlying `panel!`) take a `:seats` vector where each seat names its own harness, so one deliberation spans vendors. The CLI stays scalar-only on purpose — rich per-seat data doesn't ride the control surface.

```clojure
(require '[skein.spools.delegation :as agents])

;; A cross-vendor council: an Anthropic seat and two GPT seats deliberate over
;; two rounds on a shared board, then a GPT synthesizer weighs the whole thing.
(agents/council! "Should the parser own its own buffer pool?"
                 {:seats [{:name "builder"  :harness :build}       ; opus
                          {:name "skeptic"  :harness :review-gpt}  ; gpt, standing reviewer
                          {:name "second"   :harness :hard-gpt}]   ; gpt, second frontier
                  :rounds 2
                  :synthesizer :review-gpt
                  :cwd "/path/to/worktree"})
;; => {:council "<board>" :turns [["<r1s1>" ...] ["<r2s1>" ...]] :synthesizer "<syn>"}
;; await the synthesizer run for the verdict; raw turns are notes on the board.
```

**Why this shape.**

- **The CLI is scalar-only by contract, so per-seat harnesses live in Clojure.**
  `strand agent council --members n --harness one` seats N *identical* agents;
  the moment seats need different harnesses it's structured data, and structured
  data does not ride argv (TEN-006). `:seats` is that seam.
- **Cross-vendor seating is deliberate.** In this repo the extra seat and the
  synthesizer come from a different model family, so sign-off does not rest on the
  family that authored the work. It's the same discipline the reviewer roster
  encodes with its `review-gpt` synthesizer, applied to live deliberation.
- **Rounds are barriers, not a poll loop.** Each turn row `depends-on` every
  seat's previous-turn run, so a round completes before the next opens and the
  deliberation structure is queryable straight from run attributes
  (`agent-run/panel-seat`, `agent-run/panel-turn`). You compose the deliberation; the
  panel compiler owns the choreography.
- **The top-level input map is conventional, not spec-backed.** The `:seats`
  vector inside it is validated (the panel spec checks seat shape and name
  uniqueness), but `council!`'s outer option keys are checked ad hoc inside the
  function — a typoed key fails at runtime, not against a named spec. Copy the
  shape from the API doc rather than from memory.

Honest source: the panel composition layer in [`delegation/README.md` §6](./delegation/README.md#6-panels-presets-and-the-composition-layer), `council!` in [`delegation.api.md`](./delegation.api.md), this repo's cross-vendor GPT seats declared in [`.skein/harnesses.clj`](../.skein/harnesses.clj) (`review-gpt`, `hard-gpt`), and the same synthesizer-must-be-cross-vendor rule live in [`.skein/reviewers.clj`](../.skein/reviewers.clj).

---

## Recipe: Read-only recon that never gates the work

**Situation.** You're a worker mid-task and you need to know where something lives before you touch it. You want a cheap fan-out helper — but you must not let it count as "the task already has a run" and block your own later delegation, and you must not spawn a second thing that writes your files.

**Composition.** `spawn` a read-only helper on a cheap harness, tagged with your own run id via `--spawned-by`, and `await` its result. Its findings come back in `result`; you rarely need logs for a success.

```sh
# From inside a running worker (a1fx9 is *your* run id):
helper=$(strand agent spawn --harness explore \
  --prompt "Locate every caller of parse-token; return file:line list" \
  --spawned-by a1fx9 | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

strand agent await "$helper"   # => {"runs":[{"result":"...file:line list..."}]}
```

**Why this shape.**

- **`spawn` runs are non-serving, so a recon helper never gates delegation.**
  Raw `spawn` marks its runs `agent-run/serves=false`, and the delegate guards and
  `delegate --ready` skip classification only count *serving* runs — so recon-ing
  or reviewing a task never blocks delegating its real work later. That's the
  whole reason recon uses `spawn`, not `delegate`.
- **`--spawned-by` is provenance, not scheduling.** It's your run id, so
  `agent status` nests the helper under you in the delegation tree. It's distinct
  from `--for` (which attaches a run to the strand it serves) precisely because a
  helper serves *you*, not the task.
- **Helpers read; they don't mutate — keep it that way.** The worker contract lets
  you spawn read-only helpers freely, but forbids a second mutator inside your own
  file scope. Recon fans out; writing stays single-owner. Delegation stays
  shallow.

Honest source: the `spawn` verb and serving/non-serving model in [`delegation/README.md` §3](./delegation/README.md#engine-verbs), the worker contract's "spawn read-only helpers freely" rule (§5), and the spawn coverage in ``delegation_test.clj``.

---

## Recipe: A human-in-the-loop task as a plan node

**Situation.** One task in the plan genuinely needs a person — a design call, a pairing session, a judgment only the user can make. A headless run can't do it, and you don't want it to silently stall the plan either.

**Composition.** Mark the task `hitl` in the plan. Headless `delegate` refuses it; `delegate --interactive` opens it as a live multiplexer session instead. The agent pairs *with* the user, and when they agree it's done the agent records the outcome and **closes the tracking strand itself** — which tears the session down and unblocks dependents, exactly like any other task.

```sh
# The task was woven with "hitl":true, so headless delegate refuses it:
strand agent delegate <task>
# => fails loudly: task is hitl=true and --interactive was not passed

# Open it as a live session the human attaches to:
strand agent delegate <task> --interactive --backend tmux
# => {"task":"<task>","run":{"id":"<run>","attach":"tmux attach -t ..."}}
strand agent ps --for <task>    # carries the attach command to hand the user
```

**Why this shape.**

- **An interactive run completes when the strand closes, not when a process
  exits.** The session serves the task; closing the task is what reaps it. So the
  self-termination contract — pair, record the decision as notes and an `outcome`
  attr, then close the tracking strand — is the same verify-then-close event that
  drives every other node, just performed by the paired agent with the human's
  agreement.
- **`hitl` is a first-class plan state, not an exception.** Because `delegate`
  refuses a `hitl` task headlessly and `delegate --ready` skips it (reporting it
  in `skipped`, so it's visible, not silently dropped), a human task can't be
  accidentally handed to a headless run — you must consciously open it
  interactively.
- **`--ready` never opens interactive sessions.** Fan-out deliberately rejects
  `--interactive`: live sessions are delegated one at a time so the human is
  paired with, not swamped by, a wall of terminals.

Honest source: the interactive delegation model in [`delegation/README.md` §1 and §3](./delegation/README.md#delegation-verbs-the-task-contract-layer), and this repo's `strand hitl` coordinator convention (a tracking strand under the parent, an interactive `hitl-build` session, and the self-terminating record-outcome-then-close contract) documented in the root `CLAUDE.md`.

---

## See also

- [`delegation/README.md`](./delegation/README.md) — the contract: concept model, every
  verb's semantics and failure modes, DAG conventions, the worker contract, and
  the panel composition layer.
- [`delegation.api.md`](./delegation.api.md) — generated signatures and docstrings for
  `council!`, `panel!`, `review!`, `defroster!`, and the rest referenced above.
- `strand agent about` — the always-current in-band manual a delegated agent
  reads; point your workers at it rather than describing the surface yourself.
- [`agent-run/README.md`](./agent-run/README.md) — the run engine this spool composes:
  harness registry, run lifecycle, and the interactive backend registry.
- [`workflow.cookbook.md`](./workflow.cookbook.md) — the sibling cookbook for
  composing the workflow engine these delegation gates plug into.
