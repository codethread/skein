# Skein Agents Spool

`skein.spools.agents` is the cross-harness subagent surface. It composes the [shuttle run engine](../shuttle/README.md) and owns the whole agent-facing vocabulary: the `strand agent ...` verbs, the `agent-plan` weave pattern, delegation and recovery, and both the worker contract and the coordinator guidance.

---

## 1. User guide (for humans)

This is the human-facing part. If you are a delegated agent, read [§5 Worker contract](#5-worker-contract-and-coordinator-loop) and run `strand agent about`.

### What this gives you

One consistent way to run and coordinate coding-agent subagents, across every harness, over your strand graph:

- **Cross-harness.** A subagent can be Claude, pi, or any tool you registered as a harness. Switching a whole workspace to a new provider is one `defharness!`/`defalias!` line in trusted config — no prompts, scripts, or workflows change.
- **Everything is durable, observable, and awaitable.** Each subagent is a strand. You (or another agent) can list it, read its result, await it, read its notes, and see how it hangs off the plan — long after the session that spawned it ended. Nothing lives only in one harness's private memory.
- **The graph is the scheduler.** You describe work and its dependencies once as a plan; readiness starts each piece the moment its blockers close. There is no separate queue to babysit.
- **Human-in-the-loop tasks are plan nodes too.** `delegate --interactive` opens the agent in a live terminal-multiplexer session (tmux by default; your multiplexer is one `defbackend!` in trusted config) serving the task. You attach with the command `ps` hands you, pair with the agent, and when you agree the work is done the agent closes the task — the session is torn down and dependents unblock, exactly like any other task. This is how `hitl=true` tasks get delegated instead of stalling the plan.

### The vocabulary your agents already know

The surface is deliberately built from words coding agents are already trained on. Use these exact words when you prompt — they map one-to-one onto the tool:

- **task** vs **run** — a *task* is a unit of work with a contract; a *run* is one agent attempt at it. Verifying and closing the task is what matters, not any single run succeeding.
- **plan** — a feature strand with task children and `depends-on` edges between them.
- **delegate** — hand a ready task to an agent (`agent delegate`); **delegate the ready ones** fans a plan out (`agent delegate --ready`).
- **retry** — recover a failed task by superseding its dead run and respawning (`agent retry`).
- **status** — the coordinator dashboard (`agent status`): what's ready, running, failed, or awaiting your verification.
- **harness / alias** — which agent tool runs the work (`pi-main`, `explore`, `build`, …).
- **worker contract** — the standing rules every delegated agent is handed automatically.
- **notes** — durable, append-only memory attached to any strand.

### How to prompt effectively

- **Point your agent at the manual.** Tell it to run `strand agent about` and coordinate through it, rather than describing the surface yourself. It is the in-band, always-current source of truth.
- **Put contracts in strand bodies, not chat.** A task's body *is* the contract the worker reads (`strand show <task-id>`): scope, owned files, validation commands, commit policy. Chat scrollback is invisible to a delegated run; the strand body is not.
- **Ask for `agent status` when you want to see where things are.** It renders the whole delegation tree plus flat triage lists from data that already exists — no bespoke reporting needed.

### Harness-native subagents vs this surface

Your harness's own subagent/scout tools are cheap and good — **keep using them for private, throwaway exploration inside a single session** ("grep the codebase and tell me where X lives"). Their results, though, bypass the graph: nothing is awaitable by anyone else, nothing persists, and you can't see them from `agent status`.

Move work onto this surface whenever the result should be **durable, awaitable by others, cross-harness, or visible to you**. Concretely: anything another task depends on, anything a coordinator must fan in, anything you'll want to inspect later. Where you want this shared abstraction to be the default, it is legitimate to deliberately **downscale or strip a harness's native subagent tools** so agents reach for `strand agent` instead — the goal is one observable mechanism, not two parallel invisible ones.

---

## 2. Overview and loading

`agents` layers over shuttle, so shuttle must be installed first. A workspace opts in with `spools.edn` and trusted startup or REPL code:

```clojure
;; .skein/spools.edn
{:spools {skein.spools/shuttle {:local/root "../spools/shuttle"}
          skein.spools/agents  {:local/root "../spools/agents"}}}
```

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))
(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :shuttle
  {:ns 'skein.spools.shuttle
   :spools ['skein.spools/shuttle]
   :call 'skein.spools.shuttle/install!
   :required? true})
(runtime-alpha/use! runtime :agents
  {:ns 'skein.spools.agents
   :spools ['skein.spools/agents]
   :call 'skein.spools.agents/install!
   :required? true
   :after [:shuttle]})
```

`agents/install!` registers:

- the **`agent` CLI operation** (`strand agent ...`) — the full verb surface below;
- the **`agent-plan` weave pattern** (`strand weave --pattern agent-plan`);
- the **`agent-failures` named query** (`strand list --query agent-failures`) selecting active runs in phase `failed`/`exhausted`;
- the **worker contract** into shuttle's preamble-extension seam, so every delegated run is launched with it.

Loading shuttle without agents gives you the run engine but **no** `strand agent` surface.

---

## 3. Op surface

Every operational verb returns JSON; all verbs are flat under `strand agent <verb>`. `strand agent about` returns a structured JSON manual (concepts, verb summaries, the coordinator loop, the worker contract); the reference below is the expanded form.

### Concepts (read first)

- A **run** is a strand carrying `shuttle/*` attributes; a **task** is an ordinary work strand you delegate. Their ids look identical — each verb states which kind it takes.
- `depends-on` **readiness is the only scheduler**: a pending run starts the moment its blockers close.
- A successful run closes itself, carrying the worker's final message in `shuttle/result`. A **failed run stays active** (loud, visible) until you `retry` or `kill` it.
- Run phases: `pending → running → done | failed | exhausted | superseded`. The last four are terminal; only `failed`/`exhausted` leave the run active.
- An **interactive run** (`mode=interactive`) is a live multiplexer session: it completes when the strand it serves closes, not when a process exits. `ps` carries its `attach` command; a session that dies before its work completes fails the run loudly (no auto-respawn — `retry` opens a fresh session).
- **Run success never closes the task it served.** You verify, then close the task — and closing the task is what makes dependent tasks ready. Skip the close and the plan silently stalls.
- A task's **file scope** is the set of files its body names as owned. Every scope rule (disjoint siblings, one mutator per scope) refers to that owned set.

### Engine verbs

```
agent spawn --harness <name> --prompt "..." [--title t] [--depends-on <strand-id>]...
            [--for <strand-id>] [--spawned-by <run-id>] [--cwd <dir>] [--max-attempts n]
            [--interactive --backend <name> [--reap auto|manual]]
```
Raw run creation, no task contract. Async; the run starts when ready. `--for` = the strand this run serves (gets a `parent-of` edge); `--spawned-by` = *your* run id when you are an agent spawning a helper (provenance only). Helpers usually pass only `--spawned-by`. `--interactive` launches the harness into a multiplexer session via `--backend`: with `--for` the run completes when that strand closes, without it when the run strand itself is closed; `--reap manual` leaves the finished session open for the human (default `auto` tears it down, capturing scrollback first when the backend supports it).
→ `{"id","title","state","phase","harness", …}` (run summary)

```
agent ps [--active] [--for <strand-id>]
```
List run summaries. Listing doubles as an interactive liveness check: a dead session is failed here. Interactive summaries add `mode`, `backend`, `session`, and the `attach` command to hand the human.
→ `[{"id","title","state","phase","harness","for"?,"spawned-by"?,"attempt"?,"result"?,"error"?,"mode"?,"backend"?,"session"?,"attach"?}]`

```
agent await <run-id>... [--under <root-id>] [--timeout-secs n]
```
Block until every listed run is terminal (closed, failed, or exhausted); default timeout 300s. `--under <root-id>` instead awaits every **non-terminal** run (pending or running) in the delegation tree beneath root (a plan or task id). Run ids and `--under` are mutually exclusive; passing both fails loudly.
→ `{"timed-out":false,"runs":[<ps summary shape, including result/error>]}`
A finished helper's findings are in `result` right here — you rarely need logs for success cases.

```
agent logs <run-id> [--tail n]
```
The harness process's captured stdout/stderr (debugging, failure forensics). For a **running interactive** run, logs captures the session transcript fresh (harness `:capture` when configured, else backend scrollback) — a coordinator peek without attaching; finished interactive runs return the transcript persisted at teardown, and `err` is omitted.
→ `{"id","out":{"path","text"},"err":{"path","text"}?}`

```
agent kill <run-id>
```
Kill a **running** run's live process or interactive session and mark the run failed. Fails loudly on a run with no live process/session — for a run that already failed, use `retry`.
→ `{"killed":"<run-id>"}`

```
agent harnesses
```
→ `[{"name","kind":"harness|alias","alias-of"?,"argv"?,"doc"?}]`

```
agent backends
```
List configured interactive session backends (terminal multiplexers registered with `defbackend!` in trusted config; see the [shuttle backend registry](../shuttle/README.md#4-backend-registry-interactive-sessions)).
→ `[{"name","ops":["start","alive","stop",...],"doc"?}]`

### Delegation verbs (the task-contract layer)

```
agent delegate <task-id> [--harness h] [--cwd dir] [--prompt <extra>] [--spawned-by <run-id>]
                         [--interactive [--backend b] [--reap auto|manual]]
```
Delegate one **active** task strand: builds the worker prompt from the task's current title + body + `validation` attribute, injects the worker contract, and spawns a run attached `--for` the task.
- **Harness resolution:** `--harness` flag > task's `harness` attribute > fail loudly (no default).
- **cwd resolution:** `--cwd` flag > task's `cwd` attribute > workspace root.
- **`--interactive`** opens a live multiplexer session for the task instead of a headless run — this is how `hitl=true` tasks are delegated. Backend resolution: `--backend` flag > task's `backend` attribute > fail loudly. The prompt frames the agent as pairing *with* the user (the human is the authority on scope and completion), and the session is reaped when the task closes.
- Fails loudly when: task not active; task not **ready** (still has active `depends-on` blockers — delegation follows readiness); task has no body and no `--prompt`; no harness resolvable; task is `hitl=true` and `--interactive` was not passed; no backend resolvable with `--interactive`; task already has an **active** run (kill or await it first; a failed/exhausted one wants `retry`; a successful one must be verified and closed).
→ `{"task":"<task-id>","run":{"id","phase","harness","attach"?}}`

```
agent delegate --ready <plan-id> [--cwd dir]
```
Fan-out: delegate every **ready** task under the plan (all blockers closed) that has no active or successful run and is not `hitl`. Harness comes from **each task's** `harness` attribute (this is how mixed-harness fan-out works); fails loudly up front, delegating nothing, if any ready task lacks one. Idempotent: re-invoke after verifying + closing finished tasks to pick up newly-unblocked work. `--interactive` is deliberately rejected here — live sessions are delegated one task at a time so the human is never swamped.
→ `{"plan":"<plan-id>","delegated":[{"task","run":{"id","phase","harness"}}],"skipped":[{"task","reason":"hitl|has-active-run|failed-needs-retry|already-succeeded"}]}`

```
agent retry <task-or-run-id> [--harness h] [--cwd dir] [--prompt <extra>]
```
**The** recovery verb. Given a **task id**: finds its failed/exhausted run, marks that run `superseded` (closed with phase `superseded` — it stops blocking delegate-eligibility; its logs and notes remain), rebuilds the prompt from the task's **current** body, and spawns a fresh run. When the contract was the problem, fix the body **first** (`strand update <task-id> --attr body=:payload/<name> --payload <name>=<path>`). Given a **raw run id**: same supersede-and-respawn with the original prompt. A failed interactive run retries as a fresh session on the same backend. Fails loudly if the target has no failed/exhausted run to supersede.
→ `{"superseded":"<old-run-id>","task":"<task-id>"?,"run":{"id","phase","harness"}}`

```
agent status [root-id]
```
The coordinator dashboard. `root-id` is a plan or task id; no root = every active delegation in the workspace. Delegation tree (tasks → their runs → nested sub-spawns via `spawned-by`) plus flat triage lists.
→
```
{"tree":[{"id","title","kind":"task|run","phase"?,"status"?,"children":[...]}],
 "ready":["<task-id>"...],                  tasks delegable right now
 "running":["<run-id>"...],
 "failed":[{"task"?,"run","error"}],        needs retry or kill
 "awaiting_verification":["<task-id>"...],  worker set status=implemented; verify + close these
 "blocked":[{"task","blockers":["<id>"...]}]}
```

### Memory / review verbs

```
agent note <strand-id> "text" [--by <run-id>] [--round n]
```
Append an immutable note to any strand's memory (`--round` only matters inside councils). Notes are append-only memory, not mutation: a worker may note any strand, including parents, without violating its contract.
→ `{"id":"<note-id>","note-for":"<strand-id>"}`

```
agent notes <strand-id> [--round n]
```
→ `[{"id","note","at","by"?,"round"?}]`

```
agent review <target-id> [--members n] [--harness a,b] [--cwd dir] [--contract text] [--synthesize] [--spawned-by <run-id>]
```
Spawn independent read-only reviewers of the target strand **and its subtree** — reviewing a plan root reviews the whole feature. Each reviewer reads the strand contract(s) plus repository state at `--cwd` (default: workspace root; pass the worktree where the diff lives) and appends findings as notes on the target. `--members` defaults to 2; `--harness` is a comma-separated list cycled across reviewers (default `claude`); `--contract` overrides the workspace default review contract. `--synthesize` adds a synthesizer run that depends on all reviewers; its `result` is the verdict (await it), with raw findings in the target's notes.
→ `{"target","reviewers":["<run-id>"...],"synthesizer":"<run-id>"?}`

```
agent council --topic "..." [--members n] [--rounds n] [--harness name] [--spawned-by <run-id>]
```
Multi-round deliberation on one shared strand; await the synthesizer for the verdict.
→ `{"council":"<strand-id>","members":["<run-id>"...],"synthesizer":"<run-id>"}`

### Plan creation (weave pattern, not an agent verb)

```
printf '%s' '{"feature":"<slug>","title":"...","body":"...?","tasks":[
  {"key":"core","title":"...","body":"<full contract>","validation":["clojure -M:test"],"harness":"build"},
  {"key":"docs","title":"...","body":"...","depends_on":["core"],"harness":"pi-main"}]}' \
  | strand weave --pattern agent-plan
```
→ `{"plan":{"id","title"},"tasks":{"<your-key>":{"id","title"}}}`

Task fields: `key`, `title`, `body` (the full worker contract: scope, owned files, validation commands, commit policy), `depends_on?` (sibling **keys**, resolved to strand ids at weave time), `harness?` (set it here — `delegate --ready` requires it), `cwd?`, `validation?` (list of commands), `max-attempts?`, `hitl?` (true = human-in-the-loop work; headless `delegate` refuses it, `delegate --interactive` opens it as a live session), `kind?` (`task` or `review`). Harness and validation are independent axes: harness picks **who** does the work; validation lists the commands that **prove** it.

---

## 4. DAG conventions

Delegation shapes the strand graph by convention, so the whole tree is inspectable:

- **Task strands carry contracts.** The body is the complete, current worker contract; validation/cwd/harness live in the task's attributes. `agent delegate` reads them at delegate time, so editing the body before a `retry` changes what the next run is told.
- **Runs attach to their task with `--for`.** A delegated run gets a `parent-of` edge from the task it serves; that edge is what `agent status` and `runs --for` render as the run's `for` target.
- **Sub-spawns carry `--spawned-by`.** When a worker spawns a helper, it passes its own run id as `--spawned-by` (provenance only, distinct from `--for`). That is how `agent status` nests sub-spawns under the run that launched them.
- **Readiness is the scheduler.** A task/run with open `depends-on` blockers is not delegable/spawnable yet; it becomes ready the moment its blockers close. `delegate` refuses a not-ready task, so readiness is the only sequencer at every layer.
- **Verify-then-close unblocks dependents.** A run finishing does not advance the plan. You verify the task (re-run its validation, inspect the diff) and close it; closing is the single event that makes dependent tasks ready.

---

## 5. Worker contract and coordinator loop

Roles are lenses, not capabilities: there is one API. Any worker may promote itself to coordinator for its own sub-world using the same verbs. The two guidance sets below differ only in placement — the worker contract is injected automatically; the coordinator loop is opt-in via `agent about`.

### Worker contract

Injected automatically into every delegated run's preamble (shown here for reference):

- **Read your assigned strand AND its notes first:** `strand show <task-id>`; `agent notes <task-id>` — the body may be newer than your launch prompt, and a predecessor's notes may save you from repeating its mistakes.
- **Record progress as you go:** `strand update <task-id> --attr progress=...`.
- **Set `--attr status=implemented` only when your validation gate is green.**
- **Never close your assigned strand. Never mutate sibling or parent strands. Never commit unless your contract says so.**
- **Spawn read-only helpers freely:** `agent spawn --harness explore --prompt "..." --spawned-by <your-run-id>`; then `agent await <helper-run-id>` — the findings are in the returned `result`.
- **Leave durable findings** for the coordinator and successors: `agent note <task-id> "..." --by <your-run-id>`.
- **Keep delegation shallow;** never spawn a second mutator inside your own file scope.

### Coordinator loop (the whole job, in order)

1. **Provision working directories first.** Worktree management is deliberately not this tool's job — use your worktree tooling. Shared worktree = pass the same `cwd` to coupled tasks (readiness serializes the writes: the blocker task runs alone, then disjoint-file siblings run concurrently). Isolate siblings that are not compile-coupled.
2. **Weave an agent-plan.** Every body is a complete contract; set per-task `harness` (and `cwd` when not uniform).
3. **`agent delegate --ready <plan-id>`** — and read the `skipped` list, not just `delegated`: a task skipped as `hitl` or `has-active-run` stalls the plan until you act on it.
4. **`agent await --under <plan-id>`** (or await the run ids from step 3).
5. **Verify each task in status's `awaiting_verification` yourself.** `strand show <task-id>` re-fetches its contract; re-run its validation commands in its cwd and inspect the diff — do not trust `status=implemented` alone. Then close it: `strand update <task-id> --state closed`. Closing is what unblocks dependents.
6. **Anything in status's `failed`:** `agent logs <run-id>` → diagnose → fix the task body or environment → `agent retry <task-id>`.
7. Repeat 3–6 until status shows nothing ready, running, or failed. Fan-in — synthesis, commit, merge — is **yours**; workers never commit unless their contract says so. Finish by closing the plan root: `strand update <plan-id> --state closed`.

Policy: sibling tasks own disjoint files; never two mutators in one file scope; keep delegation shallow (workers spawn read-only helpers, not sub-plans, unless their contract says otherwise).

---

## 6. See also

- [shuttle/README.md](../shuttle/README.md) — the run engine this spool composes (harness registry, run lifecycle, preamble seam).
- [shuttle/treadle.md](../shuttle/treadle.md) — bridges workflow `:subagent` gates to shuttle runs.
- `test/skein/agents_test.clj` — executable coverage for the op surface, delegation, retry, status, and the weave pattern.
- [Weaver Runtime](../../devflow/specs/daemon-runtime.md) — CLI operation registry, pattern registry, and named queries.
