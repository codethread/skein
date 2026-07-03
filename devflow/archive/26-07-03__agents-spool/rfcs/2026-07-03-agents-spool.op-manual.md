# RFC-015 supporting material: dogfooded `strand op agent` manual draft

Companion to [RFC-015](./2026-07-03-agents-spool.md) (see RFC-015.C7 for the validation evidence). This is the candidate text `agent about` would print, refined over three rounds of cold-read agent testing. It seeds the `spools/agents` doc and op manual at implementation; it is not a shipped contract. Archive it with the RFC.

```
agent — spawn and coordinate coding-agent runs over the strand graph. Every operational verb returns JSON (`about` returns this manual). All verbs are flat under `strand op agent <verb>`.

CONCEPTS (read this first)
- A RUN is a strand carrying shuttle/* attributes; a TASK is an ordinary work strand you delegate. Their ids look identical — each verb below states which kind it takes.
- depends-on readiness is the only scheduler: a pending run starts the moment its blockers close.
- A successful run closes itself, carrying the worker's final message in `result`. A failed run stays ACTIVE (loud, visible) until you `retry` or `kill` it.
- Run phases: pending → running → done | failed | exhausted | superseded (the last four are terminal; only failed/exhausted leave the run active).
- Run success never closes the task it served: YOU verify, then close the task — and closing the task is what makes dependent tasks ready. Skip the close and the plan silently stalls.
- A task's FILE SCOPE is the set of files its body names as owned. Every scope rule below — disjoint siblings, one mutator per scope — refers to that owned set.

ENGINE VERBS
  agent spawn --harness <name> --prompt "..." [--title t] [--depends-on <strand-id>]... [--for <strand-id>] [--spawned-by <run-id>] [--cwd <dir>] [--max-attempts n]
      Raw run creation, no task contract. Async; the run starts when ready.
      --for = the strand this run serves (gets a parent-of edge). --spawned-by = YOUR run id when you are an agent spawning a helper (provenance only). Helpers usually pass only --spawned-by.
      → {"id":"<run-id>","title":"...","state":"active","phase":"pending","harness":"..."}
  agent ps [--active] [--for <strand-id>]
      List run summaries.
      → [{"id","title","state","phase","harness","for"?,"spawned-by"?,"attempt"?,"result"?,"error"?}]
  agent await <run-id>... [--under <root-id>] [--timeout-secs n]
      Block until every listed run is terminal (closed, failed, or exhausted); default timeout 300s. --under <root-id> instead awaits every NON-TERMINAL run (pending or running) in the delegation tree beneath root (a plan or task id). Run ids and --under are mutually exclusive; passing both fails loudly.
      → {"timed-out":false,"runs":[<same summary shape as ps, including result/error>]}
      A finished helper's findings are in `result` right here — you rarely need logs for success cases.
  agent logs <run-id> [--tail n]
      The harness process's captured output (debugging, failure forensics).
      → {"id","out":{"path","text"},"err":{"path","text"}}
  agent kill <run-id>
      Kill a RUNNING run's live process and mark the run failed. Fails loudly on a run with no live process — for a run that already failed, use `retry` (or leave it failed; it harms nothing except delegate-eligibility of its task).
      → {"killed":"<run-id>"}
  agent harnesses
      → [{"name","kind":"harness|alias","alias-of"?,"doc"?}]

DELEGATION VERBS (the task-contract layer)
  agent delegate <task-id> [--harness h] [--cwd dir] [--prompt <extra>] [--spawned-by <run-id>]
      Delegate one ACTIVE task strand: builds the worker prompt from the task's CURRENT title + body + validation attributes, injects the worker contract, spawns a run attached --for the task.
      Harness resolution: --harness flag > task's `harness` attribute > fail loudly (there is no default).
      cwd resolution: --cwd flag > task's `cwd` attribute > workspace root.
      Fails loudly when: task not active; task not READY (it still has active depends-on blockers — delegation follows readiness, and `delegate --ready` handles fan-out ordering); task has no body and no --prompt; no harness resolvable; task is marked hitl=true (coordinator/user must do it); task already has an ACTIVE run — a running one must be killed or awaited first, a failed one wants `retry`.
      → {"task":"<task-id>","run":{"id":"<run-id>","phase":"pending","harness":"..."}}
  agent delegate --ready <plan-id> [--cwd dir]
      Fan-out: delegate every READY task under the plan (all depends-on blockers closed) that has no active or successful run and is not hitl. Harness comes from EACH task's `harness` attribute (this is how mixed-harness fan-out works); fails loudly up front, delegating nothing, if any ready task lacks one. Idempotent: re-invoke after verifying + closing finished tasks to pick up newly-unblocked work.
      → {"plan":"<plan-id>","delegated":[{"task","run":{"id","harness"}}],"skipped":[{"task","reason":"hitl|has-active-run|already-succeeded"}]}
  agent retry <task-or-run-id> [--harness h] [--cwd dir] [--prompt <extra>]
      THE recovery verb. Given a task id: finds its failed/exhausted run, marks that run superseded (closed with phase "superseded" — it stops blocking delegate-eligibility; its logs and notes remain for archaeology), rebuilds the prompt from the task's CURRENT body, and spawns a fresh run. When the contract was the problem, fix the body FIRST — bodies are attributes: strand update <task-id> --attr-file body=<path> (or --attr-stdin body). --prompt appends extra text to the rebuilt prompt, same as delegate. Given a raw run id: same supersede-and-respawn with the original prompt. Fails loudly if the target has no failed/exhausted run to supersede.
      → {"superseded":"<old-run-id>","task":"<task-id>"?,"run":{"id":"<new-run-id>","phase":"pending","harness":"..."}}
  agent status [root-id]
      The coordinator dashboard. root-id is a plan or task id; no root = every active delegation in the workspace. Delegation tree (tasks → their runs → nested sub-spawns via spawned-by) plus flat triage lists.
      → {"tree":[{"id","title","kind":"task|run","phase"?,"status"?,"children":[...]}],
         "ready":["<task-id>"...],                     tasks delegable right now
         "running":["<run-id>"...],
         "failed":[{"task"?,"run","error"}],           needs retry or kill
         "awaiting_verification":["<task-id>"...],     worker set status=implemented; verify + close these
         "blocked":[{"task","blockers":["<id>"...]}]}

MEMORY / REVIEW VERBS
  agent note <strand-id> "text" [--by <run-id>]
      Append an immutable note to any strand's memory (--round exists but only matters inside councils). Notes are append-only memory, not mutation: workers may note any strand, including parents, without violating their contract.
      → {"id":"<note-id>","note-for":"<strand-id>"}
  agent notes <strand-id> [--round n]
      → [{"id","note","at","by"?,"round"?}]
  agent review <target-id> [--members n] [--harness a,b] [--cwd dir] [--synthesize]
      Spawn independent read-only reviewers of the target strand AND its subtree — reviewing a plan root reviews the whole feature. Each reviewer reads the strand contract(s) plus repository state at --cwd (default: workspace root; pass the worktree where the diff lives), and appends findings as notes on the target. --synthesize adds a synthesizer run that depends on all reviewers; the verdict is the synthesizer run's `result` (await it), with the raw findings in the target's notes.
      → {"target","reviewers":["<run-id>"...],"synthesizer":"<run-id>"?}
  agent council --topic "..." [--members n] [--rounds n] [--harness name]
      Multi-round deliberation on one shared strand; await the synthesizer for the verdict.
      → {"council":"<strand-id>","members":["<run-id>"...],"synthesizer":"<run-id>"}

PLAN CREATION (weave pattern — not an agent verb)
  printf '%s' '{"feature":"<slug>","title":"...","body":"...?","tasks":[
    {"key":"core","title":"...","body":"<full contract>","validation":["clojure -M:test"],"harness":"build"},
    {"key":"docs","title":"...","body":"...","depends_on":["core"],"harness":"pi-main"}]}' \
    | strand weave --pattern agent-plan
      → {"plan":{"id","title"},"tasks":{"<your-key>":{"id","title"}}}
  Task fields: key, title, body (the full worker contract: scope, owned files, validation commands, commit policy), depends_on? (sibling KEYS, resolved to strand ids at weave time), harness? (set it here — delegate --ready requires it), cwd?, validation? (list of commands), max-attempts?, hitl? (true = coordinator/user work; delegate refuses it).
  harness and validation are independent axes: harness picks WHO does the work; validation lists the commands that PROVE it, regardless of harness.

COORDINATOR LOOP (the whole job, in order)
  1. Provision working directories FIRST — worktree management is deliberately NOT this tool's job; use your worktree tooling. Shared worktree = pass the same cwd to coupled tasks (readiness serializes the writes: the blocker task runs alone, then disjoint-file siblings run concurrently). Isolate siblings that are not compile-coupled.
  2. Weave an agent-plan. Every body is a complete contract; set per-task harness (and cwd when not uniform).
  3. agent delegate --ready <plan-id> — and read the `skipped` list, not just `delegated`: a task skipped as hitl or has-active-run stalls the plan until you act on it.
  4. agent await --under <plan-id>   (or await the run ids from step 3)
  5. VERIFY each task in status's awaiting_verification yourself: strand show <task-id> re-fetches its contract (validation/cwd/harness live in its attributes); re-run its validation commands in its cwd and inspect the diff — do not trust status=implemented alone. Then close it: strand update <task-id> --state closed. Closing is what unblocks dependents.
  6. Anything in status's failed: agent logs <run-id> → diagnose → fix the task body or environment → agent retry <task-id>.
  7. Repeat 3–6 until status shows nothing ready, running, or failed. Fan-in — synthesis, commit, merge — is YOURS; workers never commit unless their contract says so. Finish by closing the plan root: strand update <plan-id> --state closed.
  Policy: sibling tasks own disjoint files; never two mutators in one file scope; keep delegation shallow (workers spawn read-only helpers, not sub-plans, unless their contract says otherwise).

WORKER CONTRACT (injected automatically into every delegated run's preamble; shown for reference)
  - Read your assigned strand AND its notes first: strand show <task-id>; agent notes <task-id> — the body may be newer than your launch prompt, and a predecessor's notes may save you from repeating its mistakes.
  - Record progress as you go: strand update <task-id> --attr progress=...
  - Set --attr status=implemented only when your validation gate is green.
  - Never close your assigned strand. Never mutate sibling or parent strands. Never commit unless your contract says so.
  - Spawn read-only helpers freely: agent spawn --harness explore --prompt "..." --spawned-by <your-run-id>; then agent await <helper-run-id> — the findings are in the returned `result`.
  - Leave durable findings for the coordinator and successors: agent note <task-id> "..." --by <your-run-id>.
  - Keep delegation shallow; never spawn a second mutator inside your own file scope.

```
