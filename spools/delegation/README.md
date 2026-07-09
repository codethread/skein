# Skein Delegation Spool

`skein.spools.delegation` is the cross-harness subagent surface. It composes the [agent-run run engine](../agent-run/README.md) and owns the whole agent-facing vocabulary: the `strand agent ...` verbs, the `agent-plan` weave pattern, delegation and recovery, and both the worker contract and the coordinator guidance.

This README is the contract — what each verb promises. For the *shapes* real coordination takes — the plan/delegate/await/verify/close loop, contract-first recovery, roster review, cross-vendor panels — see the composition recipes in [`delegation.cookbook.md`](../delegation.cookbook.md).

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
- **harness / alias** — which agent tool runs the work (`worker`, `explore`, `build`, …).
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

`delegation` layers over agent-run, so agent-run must be installed first. A workspace opts in with `spools.edn` and trusted startup or REPL code:

```clojure
;; .skein/spools.edn
{:spools {skein.spools/agent-run {:local/root "../spools/agent-run"}
          skein.spools/delegation  {:local/root "../spools/delegation"}}}
```

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))
(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :agent-run
  {:ns 'skein.spools.agent-run
   :spools ['skein.spools/agent-run]
   :call 'skein.spools.agent-run/install!
   :required? true})
(runtime-alpha/use! runtime :delegation
  {:ns 'skein.spools.delegation
   :spools ['skein.spools/delegation]
   :call 'skein.spools.delegation/install!
   :required? true
   :after [:agent-run]})
```

`agents/install!` registers:

- the **`agent` CLI operation** (`strand agent ...`) — the full verb surface below;
- the **`agent-plan` weave pattern** (`strand weave --pattern agent-plan`);
- the **`agent-failures` named query** (`strand list --query agent-failures`) selecting active runs in phase `failed`/`exhausted`;
- the **worker contract** into agent-run's preamble-extension seam, so every delegated run is launched with it.

Loading agent-run without delegation gives you the run engine but **no** `strand agent` surface.

---

## 3. Op surface

Every operational verb returns JSON; all verbs are flat under `strand agent <verb>`. `strand help agent` is generated from the declared arg-spec and is the command-shape reference. `strand agent about` returns the authored structured JSON manual (concepts, verb semantics, the coordinator loop, the worker contract). Bare `strand agent` fails loudly with the available subcommands so discovery stays aligned with the help/about convention.

### Concepts (read first)

- A **run** is a strand carrying `agent-run/*` attributes; a **task** is an ordinary work strand you delegate. Their ids look identical — each verb states which kind it takes.
- `depends-on` **readiness is the only scheduler**: a pending run starts the moment its blockers close.
- A successful run closes itself, carrying the worker's final message in `agent-run/result`. A **failed run stays active** (loud, visible) until you `retry` or `kill` it.
- Run phases: `pending → running → done | failed | exhausted | superseded`. The last four are terminal; only `failed`/`exhausted` leave the run active.
- An **interactive run** (`mode=interactive`) is a live multiplexer session: it completes when the strand it serves closes, not when a process exits. `ps` carries its `attach` command; a session that dies before its work completes fails the run loudly (no auto-respawn — `retry` opens a fresh session).
- **Run success never closes the task it served.** You verify, then close the task — and closing the task is what makes dependent tasks ready. Skip the close and the plan silently stalls.
- A **serving run** delegates a task's own work and gates that task's delegation; a **non-serving helper** (recon `spawn`, reviewers, panel/council seats) only reads or reviews and is stamped `agent-run/serves=false`. A run serves its `--for` target by default; only the helper-spawning paths mark it non-serving. Delegation guards and `delegate --ready` skip classification count only serving, non-superseded runs, so reviewing or reconning a task never blocks delegating it later.
- A task's **file scope** is the set of files its body names as owned. Every scope rule (disjoint siblings, one mutator per scope) refers to that owned set.

### Engine verbs

```
agent spawn --harness <name> --prompt "..." [--title t] [--depends-on <strand-id>]...
            [--for <strand-id>] [--spawned-by <run-id>] [--cwd <dir>] [--max-attempts n]
            [--interactive --backend <name> [--reap auto|manual]]
```
Raw run creation, no task contract. Async; the run starts when ready. `--for` attaches the run under a strand (a `parent-of` edge). **`spawn` is the raw helper verb: its runs are non-serving** (`agent-run/serves=false`), so a `spawn --for` a task is a recon/one-off helper that never gates that task's later `delegate` — delegating a task's own work is `delegate`'s job. `--spawned-by` = *your* run id when you are an agent spawning a helper (provenance only). Helpers usually pass only `--spawned-by`. `--interactive` launches the harness into a multiplexer session via `--backend`: with `--for` the run completes when that strand closes, without it when the run strand itself is closed; `--reap manual` leaves the finished session open for the human (default `auto` tears it down, capturing scrollback first when the backend supports it). → `{"id","title","state","phase","harness", …}` (run summary)

```
agent ps [--active] [--for <strand-id>]
```
List run summaries. Listing doubles as an interactive liveness check: a dead session is failed here. Interactive summaries add `mode`, `backend`, `session`, and the `attach` command to hand the human. → `[{"id","title","state","phase","harness","for"?,"spawned-by"?,"attempt"?,"result"?,"error"?,"mode"?,"backend"?,"session"?,"attach"?}]`

```
agent await <run-id>... [--under <root-id>] [--timeout-secs n]
```
Block until every listed run is terminal (closed, failed, or exhausted); default timeout 300s. `--under <root-id>` instead awaits every **non-terminal** run (pending or running) in the delegation tree beneath root (a plan or task id). Run ids and `--under` are mutually exclusive; passing both fails loudly. → `{"timed-out":false,"runs":[<ps summary shape, including result/error>]}` A finished helper's findings are in `result` right here — you rarely need logs for success cases.

```
agent logs <run-id> [--tail n]
```
The harness process's captured stdout/stderr (debugging, failure forensics). For a **running interactive** run, logs captures the session transcript fresh (harness `:capture` when configured, else backend scrollback) — a coordinator peek without attaching; finished interactive runs return the transcript persisted at teardown, and `err` is omitted. → `{"id","out":{"path","text"},"err":{"path","text"}?}`

```
agent kill <run-id>
```
Kill a **running** run's live process or interactive session and mark the run failed. Fails loudly on a run with no live process/session — for a run that already failed, use `retry`. → `{"killed":"<run-id>"}`

```
agent harnesses
```
→ `[{"name","kind":"harness|alias","alias-of"?,"argv"?,"doc"?}]`

```
agent backends
```
List configured interactive session backends (terminal multiplexers registered with `defbackend!` in trusted config; see the [agent-run backend registry](../agent-run/README.md#4-backend-registry-interactive-sessions)). → `[{"name","ops":["start","alive","stop",...],"doc"?}]`

### Delegation verbs (the task-contract layer)

```
agent delegate <task-id> [--harness h] [--cwd dir] [--prompt <extra>] [--spawned-by <run-id>]
                         [--interactive [--backend b] [--reap auto|manual]]
```
Delegate one **active** task strand: builds the worker prompt from the task's current title + body + `validation` attribute, injects the worker contract, and spawns a run attached `--for` the task.
- **Harness resolution:** `--harness` flag > task's `harness` attribute > fail loudly (no default).
- **cwd resolution:** `--cwd` flag > task's `cwd` attribute > workspace root.
- **`--interactive`** opens a live multiplexer session for the task instead of a headless run — this is how `hitl=true` tasks are delegated. Backend resolution: `--backend` flag > task's `backend` attribute > fail loudly. The prompt frames the agent as pairing *with* the user (the human is the authority on scope and completion), and the session is reaped when the task closes.
- Fails loudly when: task not active; task not **ready** (still has active `depends-on` blockers — delegation follows readiness); task has no body and no `--prompt`; no harness resolvable; task is `hitl=true` and `--interactive` was not passed; no backend resolvable with `--interactive`; task already has an **active serving** run (kill or await it first; a failed/exhausted one wants `retry`; a successful one must be verified and closed). Non-serving helper runs (recon spawns, reviewers) never trip these guards.
→ `{"task":"<task-id>","run":{"id","phase","harness","attach"?}}`

```
agent delegate --ready <plan-id> [--cwd dir]
```
Fan-out: delegate every **ready** task under the plan (all blockers closed) that has no active or successful **serving** run and is not `hitl` — non-serving helper runs (recon/review) are ignored, so a reconned or reviewed task is still delegated. Every task is classified exactly once against pre-spawn state, so a task delegated this pass is never also reported in `skipped`. Harness comes from **each task's** `harness` attribute (this is how mixed-harness fan-out works); fails loudly up front, delegating nothing, if any ready task lacks one. Idempotent: re-invoke after verifying + closing finished tasks to pick up newly-unblocked work. `--interactive` is deliberately rejected here — live sessions are delegated one task at a time so the human is never swamped. → `{"plan":"<plan-id>","delegated":[{"task","run":{"id","phase","harness"}}],"skipped":[{"task","reason":"hitl|has-active-run|failed-needs-retry|already-succeeded"}]}`

```
agent retry <task-or-run-id> [--fresh] [--harness h] [--cwd dir] [--prompt <extra>]
```
**The** recovery verb. Given a **task id**: finds its failed/exhausted **serving** run (read-only helpers — recon `spawn`s, reviewers, seats stamped `agent-run/serves=false` — are ignored, so a failed helper never shadows the real delegation failure; retry a failed helper by passing its run id directly), marks that run `superseded` (closed with phase `superseded` — it stops blocking delegate-eligibility; its logs and notes remain), rebuilds the prompt from the task's **current** body, and spawns a fresh run. If a task somehow has more than one failed serving run, retry fails loudly listing the candidate run ids rather than picking one. When the contract was the problem, fix the body **first** (`strand update <task-id> --attr body=:payload/<name> --payload <name>=<path>`). Given a **raw run id**: same supersede-and-respawn, preserving the served target, spawned-by provenance, `depends-on` edges, cwd, max-attempts, and the panel/review structural attrs (`agent-run/panel-seat`, `agent-run/panel-turn`, `agent-run/review-*`, and the `agent-run/serves` non-serving marker) so a recovered seat stays queryable from run attrs and a retried helper stays non-serving. A failed interactive run retries as a fresh session on the same backend. Fails loudly if the target has no failed/exhausted run to supersede.
- **`--fresh` and session continuity.** A **resumed** run (one continuing a predecessor's session, see [§6](#6-panels-presets-and-the-composition-layer)) re-resumes that same session by default. `--fresh` severs the linkage and respawns cold on the run's full-brief prompt — a fresh process can never take the short continuation form. A plain retry of a run whose failure is **resume-classed** (`agent-run/error-class "resume"`, i.e. the session was lost) fails loudly instructing `--fresh`, so recovery never loops against a dead session.
→ `{"superseded":"<old-run-id>","task":"<task-id>"?,"run":{"id","phase","harness"}}`

```
agent status [root-id]
```
The coordinator dashboard. `root-id` is a plan or task id; no root = every active delegation in the workspace. Delegation tree (active tasks → their runs → nested sub-spawns via `spawned-by`) plus flat triage lists. →
```
{"tree":[{"id","title","kind":"task|run","phase"?,"status"?,"children":[...]}],
 "ready":["<task-id>"...],                  active tasks delegable right now
 "running":["<run-id>"...],
 "failed":[{"task"?,"run","error"}],        needs retry or kill
 "awaiting_verification":["<task-id>"...],  active tasks with status=implemented; verify + close these
 "blocked":[{"task","blockers":["<id>"...]}]}
```

### Memory / review verbs

```
agent note <strand-id> "text" [--by <run-id>] [--round n]
```
Append an immutable note to any strand's memory (`--round` only matters inside councils). Notes are append-only memory, not mutation: a worker may note any strand, including parents, without violating its contract. → `{"id":"<note-id>","note-for":"<strand-id>"}`

```
agent notes <strand-id> [--round n]
```
→ `[{"id","note","at","by"?,"round"?}]`

```
agent review <target-id> [--roster name | --members n --harness a,b --contract text]
                         [--cwd dir] [--commit-range range] [--changed-files a,b]
                         [--synthesize] [--spawned-by <run-id>]
```
Spawn independent read-only reviewers of the target strand **and its subtree** — reviewing a plan root reviews the whole feature. Reviewer and synthesizer runs are **non-serving helpers** (`agent-run/serves=false`): they hang under the target but never gate a later `delegate` of it, so a target can be reviewed before or after it is delegated. Each reviewer reads the strand contract(s) plus repository state at `--cwd` (default: workspace root; pass the worktree where the diff lives) and appends findings as notes on the target. `--members` defaults to 2; `--harness` is a comma-separated list cycled across reviewers (default `claude`); `--contract` overrides the workspace default review contract. `--synthesize` adds a synthesizer run that depends on all reviewers; its `result` is the verdict (await it), with raw findings in the target's notes. `--commit-range <range>` (e.g. `main..HEAD`) names the **diff surface**: its changed files are expanded via `git -C <cwd> diff --name-only <range>` and injected into every reviewer prompt as the authoritative diff surface, so reviewers stop re-deriving the diff. `--changed-files a,b` overrides the file list explicitly (csv). A `--commit-range` with no `--cwd` to expand it, or one git cannot expand at `--cwd`, fails loudly — a range is never injected without its resolved file list. The synthesizer never receives the change context — it weighs notes, not the diff. `--roster <name>` fans out a **named declarative roster** instead (see [Reviewer rosters](#reviewer-rosters) below): one run per declared entry with its own precise contract and scope, always synthesized. The roster is the one authoritative source of reviewer count, harnesses, and contracts, so combining `--roster` with `--members`, `--harness`, or `--contract` fails loudly; unknown roster names fail loudly with the available names. → `{"target","reviewers":["<run-id>"...],"synthesizer":"<run-id>"?}`

```
agent rosters
```
List the reviewer rosters registered by trusted config as full plain data. → `[{"name","reviewers":[{"name","harness","contract","scope"?}],"synthesizer":{"harness"}?}]`

#### Reviewer rosters

A roster turns "who reviews a change in this workspace" into declarative, git-reviewable data: many small, cheap, single-concern reviewers instead of a couple of generalists. Trusted config registers rosters (weaver-lifetime state, re-registered on startup like harness aliases):

```clojure
(require '[skein.spools.delegation :as agents])

(agents/defroster! :change-review
  {:reviewers [{:name "test-sleeps" :harness :explore
                :contract "Hunt for sleeps and arbitrary timeouts in tests..."
                :scope "test files and test helpers in the change"}
               {:name "docs-drift" :harness :grunt
                :contract "Check documentation and metadata coherence only..."}]
   :synthesizer {:harness :review-gpt}})
```

Route reviewers by **waste-type, not call count**: `grunt` (sonnet) is the default for read-through seats — it does targeted git-diff and ranged reads rather than thrashing a whole namespace for a small window — while `explore` (haiku) is reserved for trivially greppable single-file concerns whose contract tells it to do one global diff sweep (haiku is ~0.25–0.3× sonnet's per-call cost, so a few extra greps there stay net-cheaper). Give each contract a per-concern **call budget** and, on lenses that overlap (fail-loudly / spec-shapes / correctness on the same defect), tell each to state its angle concisely; the synthesizer de-duplicates overlapping findings by root cause.

Data shape, validated loudly at registration against the **`:skein.spools.delegation/roster`** clojure.spec (inspect it with `s/form`; the seam output below is likewise specced as `:skein.spools.delegation/review-specs`): each entry requires a unique `:name` (doubles as the run's review focus), a `:harness` (resolved against the agent-run registry at **review time**, not registration time, so roster files may load before config registers aliases), and a `:contract` — the reviewer's precise single-concern mandate, layered onto the workspace base review contract in the prompt. `:scope` is optional prompt-level confinement text. `:synthesizer` optionally overrides the synthesis run's harness (default: first reviewer's). Unknown keys fail loudly to catch typos (closed key sets and name uniqueness are checked beyond the spec, which is structurally open).

`agent review --roster <name>` spawns one read-only run per entry (stamped `agent-run/review-roster` for attribution) plus the synthesizer, which receives the base review contract — synthesis weighs findings roster-independently. In this repository the roster lives in `.skein/reviewers.clj`; keep yours near the root of your workspace config so the review policy is one obvious document.

**Composing rosters into workflows.** The verb is agent-run-native, but the prompt building is not verb-private: `skein.spools.delegation/roster-review-specs` returns the whole fan-out as plain, fully-built run specs, and `review!` itself spawns from them. A workflow author decorating a [workflow](../workflow.md) with roster review maps each spec onto a `:subagent` gate — `:harness`/`:prompt` onto the gate's `agent-run/harness`/`agent-run/prompt`, `:attrs` merged into the gate's attributes, and a synthesizer gate depending on every reviewer gate (the synthesis prompt is deliberately buildable before any run exists). Both paths share one prompt source, so roster contracts cannot drift between the verb and a composed workflow:

```clojure
(agents/roster-review-specs :change-review {:target plan-id})
;; => {:roster :change-review :target "..." :review-pass "change-review-1a2b3c4d"
;;     :reviewers [{:name "test-sleeps" :harness :explore :prompt "..." :attrs {...}} ...]
;;     :synthesizer {:name "synthesis" :harness :review-gpt :prompt "..." :attrs {...}}}
```

**Change context.** `roster-review-specs` (and `review!`'s `:change-context`) take an optional `:skein.spools.delegation/change-context` value — `{:commit-range "main..HEAD" :files ["a.clj" "b.clj"] :windows [{:path "a.clj" :lines "40-90"}]}` — that is injected into every reviewer prompt as the authoritative diff surface so reviewers read the changed files instead of re-deriving the diff. It is validated against its spec and fails loudly when malformed; the synthesizer never carries it. The `agent review` CLI builds this from `--commit-range`/`--changed-files`; trusted Clojure can also pass cheap code windows.

Each specs call mints a `:review-pass` tag (override with `:review-id`): reviewers prefix their notes with it, the synthesizer filters on it, and every run/gate carries it as `agent-run/review-pass` — so repeated rounds on one target stay separable without run ids, which don't exist at workflow-definition time. The single-prompt-source property is test-enforced; the gate→treadle mapping itself is deliberately untested here in phase 1 (treadle owns gate consumption and fails loudly on a missing `agent-run/harness`/`agent-run/prompt`).

**Parameterised and one-off rosters.** `roster-review-specs` (and `review!`'s `:roster`) accept an **inline roster value** — any map conforming to `:skein.spools.delegation/roster`, validated identically to `defroster!` input — labelled `:inline` in specs and run attributes. Because rosters are plain data, pour-time code can filter, augment, or construct one (e.g. guarantee at least one cross-vendor reviewer given the authoring harness) and hand it straight to the seam; register a computed variant under a name with `defroster!` when durable attribution matters. The CLI stays name-only (TEN-006: rich data does not ride the control surface).

```
agent council --topic "..." [--members n] [--rounds n] [--harness name] [--synthesizer name]
                            [--cwd dir] [--spawned-by <run-id>]
```
Convene a fresh-blackboard **panel** (see [§6](#6-panels-presets-and-the-composition-layer)): `--members n` seats N identical agents on `--harness`, they deliberate over one shared council strand across `--rounds` turn-as-run barrier rows (default 2), then a synthesizer weighs the whole deliberation — await it for the verdict. Harness has **no default**: a council with no resolvable harness fails loudly, mirroring `delegate`. The synthesizer runs `--synthesizer` or the first seat's harness. The CLI is scalar-only; per-seat harness/brief (the `:seats` vector — e.g. a cross-vendor panel) is trusted-Clojure `council!`/`panel!` territory (TEN-006: rich data does not ride the control surface). → `{"council":"<strand-id>","turns":[["<run-id>"...]...],"synthesizer":"<run-id>"}`

### Plan creation (weave pattern, not an agent verb)

```
printf '%s' '{"feature":"<slug>","title":"...","body":"...?","tasks":[
  {"key":"core","title":"...","body":"<full contract>","validation":["clojure -M:test"],"harness":"build"},
  {"key":"docs","title":"...","body":"...","depends_on":["core"],"harness":"worker"}]}' \
  | strand weave --pattern agent-plan
```
→ `{"plan":{"id","title"},"tasks":{"<your-key>":{"id","title"}}}`

Task fields: `key`, `title`, `body` (the full worker contract: scope, owned files, validation commands, commit policy), `depends_on?` (sibling **keys**, resolved to strand ids at weave time), `harness?` (set it here — `delegate --ready` requires it), `cwd?`, `validation?` (list of commands), `max-attempts?`, `hitl?` (true = human-in-the-loop work; headless `delegate` refuses it, `delegate --interactive` opens it as a live session), `kind?` (`task` or `review`). Harness and validation are independent axes: harness picks **who** does the work; validation lists the commands that **prove** it.

---

## 4. DAG conventions

Delegation shapes the strand graph by convention, so the whole tree is inspectable:

- **Task strands carry contracts.** The body is the complete, current worker contract; validation/cwd/harness live in the task's attributes. `agent delegate` reads them at delegate time, so editing the body before a `retry` changes what the next run is told.
- **Runs attach to their task with `--for`.** A delegated run gets a `parent-of` edge from the task it serves; that edge is what `agent status` and `runs --for` render as the run's `for` target. A run serves its `--for` target by default; read-only helpers (recon `spawn`, reviewers, panel/council seats) attach the same way but are stamped `agent-run/serves=false` so they never gate the target's delegation.
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
- **Kill processes by PID only — never `pkill -f`/pattern kills.** The shipped `claude`/`pi` harnesses now deliver the worker prompt on stdin, but other/custom harnesses may still pass it in argv (`:prompt-via :arg`, the default), so a pattern kill aimed at a stuck JVM can still strafe sibling agents whose prompt quotes the same text (a `pkill -f "clojure -M:test"` murdered two delegated runs on 2026-07-05). Find the pid and kill it.
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

## 6. Panels, presets, and the composition layer

`review` and `council` are the shipped presets; underneath them sits one internal primitive — the **panel** — that trusted Clojure can compose directly. There is deliberately **no `panel` verb**: panels are rich structured data, so they live in trusted Clojure and inline values, not shell argv.

### The panel shape

A panel is plain data, validated loudly against the **`:skein.spools.delegation/panel`** clojure.spec (closed key sets and seat-name uniqueness are checked beyond the structurally-open spec):

```clojure
{:seats [{:name "skeptic" :harness :review-gpt :brief "…"
          :scope? "…"                 ; optional prompt-level confinement
          :continuity? :fresh|:resume}] ; default :fresh
 :turns? {:rounds n}                   ; default {:rounds 1}
 :blackboard? :target|:fresh           ; default :target
 :synthesis? {:harness … :brief? …} | :none}
```

`panel-specs` compiles a panel into fully-built run specs (output specced as **`:skein.spools.delegation/panel-specs`**); `panel!` spawns from them. Both apply the defaults above. `panel!` validates its option map against **`:skein.spools.delegation/panel-input`**.

- **Turn-as-run.** Seat *s* on turn *r* is one run, stamped `agent-run/panel-seat`, `agent-run/panel-turn`, `agent-run/review-target`, and `agent-run/review-pass`, so the deliberation structure is queryable from *run* attributes (notes keep the existing `{:by :round}` + tag-in-text convention — no new note facets).
- **Barriers.** Turn row *r* `depends-on` every seat's turn *r−1* run, so a round completes before the next opens.
- **Blackboards.** `:target` (default) reads the supplied target strand via `show` — the review shape, single-round only (a target board hosts no peer posts, so `:rounds > 1` on `:target` fails loudly; use `:fresh`). `:fresh` mints a new shared board strand (role `panel`) that seats post to and read via `notes` — the deliberation shape.
- **Continuity and both prompt forms.** Every turn *r>1* spec carries **both** a full-brief prompt (the whole brief, for a fresh process) and a short continuation `resume-prompt` (only the coordinates a resumed session can't infer). A seat defaults to `:continuity :fresh` — each turn is a fresh process on the full brief. `:continuity :resume` threads turn *r>1* onto the seat's previous-turn run via agent-run `:resume` (requiring a resume-declaring harness); because a session can't be resumed before it exists, `panel!` awaits the previous row to completion before spawning a resuming row, while `:fresh`-continuity rounds spawn upfront behind their barriers. A resuming run stamps its full-brief form as `agent-run/fresh-prompt`, which is exactly what `agent retry --fresh` cold-starts from.
- **Pass tags.** Each compile mints a `:review-pass` tag (override with `:review-id`), stamped `agent-run/review-pass` on every run, so repeated passes on one board stay separable without run ids — which don't exist at compile/pour time.

### The presets over the panel

- **`review!` / rosters.** A single-round `:target` panel *is* the independent review shape; `roster->panel` converts a roster into exactly that panel. `review!` keeps its own spawn path (`roster-review-specs`, §3) so its established prompts and run attributes stay contractually frozen (the compatibility floor), while sharing the same internal blackboard-protocol fragments as the panel compiler — so the two cannot drift.
- **`council!`.** Re-shipped as a `:fresh`-blackboard panel preset. `:members n` expands to N identical seats on a council-wide `:harness`; `:seats [{:name :harness? :brief?}]` gives per-seat harness/perspective (a cross-vendor council) and is mutually exclusive with `:members`. Harness has **no silent default** — a seat with neither its own nor a council-wide harness fails loudly, mirroring `delegate`. `:rounds` become turn-as-run barrier rows; the old poll-loop prompt choreography is gone (the panel compiler owns it). The option map is validated against **`:skein.spools.delegation/council-input`**.

### Subagent / workflow boundary

A **rounds=1** panel — i.e. a roster review — maps onto `:subagent` gates exactly as [rosters document](#reviewer-rosters): one gate per reviewer spec, a synthesizer gate depending on them, no treadle changes. **Multi-round gate mapping is deliberately deferred:** a resumed turn's gate run would need the seat's *previous gate run* id, which is unknowable at pour time (the workflow is poured before any run exists — the same class of problem as adaptive round counts). It is solvable later via a moderator/coordinator pattern; it is documented here as a known boundary rather than discovered as a surprise.

### Parameterised and inline panels

Like rosters, `panel-specs`/`panel!` take an **inline panel value** — any map conforming to `:skein.spools.delegation/panel`, validated identically to preset input. Because panels are plain data, pour-time Clojure can construct, filter, or augment one (e.g. guarantee a cross-vendor seat given the authoring harness) and hand it straight to the compiler or spawner. There is **no panel registry** in phase 1: panels are inline values or preset-derived, and the roster registry remains the only naming layer. Register a durable named review policy with `defroster!` when attribution matters; reach for a panel when you need multi-round deliberation or per-seat continuity.

---

## 7. See also

- [agent-run/README.md](../agent-run/README.md) — the run engine this spool composes (harness registry, run lifecycle, preamble seam).
- [executors/subagent.md](../executors/subagent.md) — bridges workflow `:subagent` gates to agent-run runs.
- `test/skein/agents_test.clj` — executable coverage for the op surface, delegation, retry, status, and the weave pattern.
- [Weaver Runtime](../../devflow/specs/daemon-runtime.md) — CLI operation registry, pattern registry, and named queries.
