# Skein Agent-run Spool — Cookbook

Composition recipes for `skein.spools.agent-run`: how to wire the engine seams a workspace owner actually touches, and *why* each shape is the right one.

This is the **how/why** half of the agent-run docs. The other two halves are:

- [`agent-run/README.md`](./agent-run/README.md) — the **contract**: run lifecycle,
  the harness/backend registries, the claims model, crash reconciliation, and
  the `agent-run/*` attribute vocabulary. Read it for what the engine promises.
- [`agent-run.api.md`](./agent-run.api.md) — the **generated reference**: every
  public fn's signature, arities, and docstring, produced from the source.

Division of truth: signatures and argument lists live in the generated API doc; narrative and composition live here and in the contract. This cookbook never restates a fn signature or the attribute table — it links to them. When a recipe needs an exact arity, follow the link.

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which primitives combine, and how.
3. **Snippet** — a complete, runnable form (assume
   `(require '[skein.spools.agent-run :as agent-run])`).
4. **Why this shape** — the reasoning: why these primitives, what the attribute
   conventions buy you, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — the agent-run source, this repo's `.skein` config, or the test suite — so you can read the load-bearing version.

The recipes lean on the shipped `sh` harness, whose "argv" is a plain shell and whose prompt *is* the script it runs. That makes a run cheap and deterministic (no real coding agent, no network) while still exercising the whole readiness-driven spawn engine — the same path a `claude` or `pi` run takes. Swap `:sh` for a real harness and the shapes are unchanged.

---

## Recipe: Bring your own agent — a harness plus an alias tier

**Situation.** You want to run a specific coding agent (Claude, Codex, an in-house CLI) from strands, and you want a few named tiers over it — a cheap model for search, a mid model for grunt work, a top model for building — without teaching every caller the underlying flags.

**Composition.** One `register-harness!` describes the concrete launcher as plain data: the `:argv`, how output is parsed (`:parse`), and how the prompt reaches the process (`:prompt-via`). Then `register-alias!` registers seats in a separate alias registry — `:alias-of` plus `:extra-args` that splice into the argv before the prompt. Resolution checks aliases first, then harnesses, so a seat may intentionally share a tool's name without overwriting the tool. Aliases flatten (an alias may point at another alias), so the tier names are the only vocabulary a caller needs.

```clojure
(require '[skein.spools.agent-run :as agent-run])

;; A concrete harness. This agent prints only its final message on stdout, so
;; :raw parses cleanly; the prompt is appended to argv (:arg is the default).
(agent-run/register-harness! :my-agent
  {:argv ["my-agent" "run" "--headless"]     ; your launcher plus its flags
   :parse :raw
   :doc "In-house agent CLI; final message on stdout."})

;; Named tiers over the base harness — one line each.
(agent-run/register-alias! :cheap
  {:alias-of :my-agent :extra-args ["--tier" "small"]
   :doc "Fast read-only exploration and fan-out search."})
(agent-run/register-alias! :standard
  {:alias-of :my-agent :extra-args ["--tier" "mid"]
   :doc "Tests, mechanical edits, grunt work."})
(agent-run/register-alias! :deep
  {:alias-of :my-agent :extra-args ["--tier" "large"]
   :doc "Feature building, reviews, council seats."})

(agent-run/resolve-harness :deep)   ; => the flattened :my-agent def + large-tier args
```

**Why this shape.**

- **A harness is data, and seats are a separate naming layer.** Nothing about a
  run is compiled in — `register-harness!` registers concrete tools and `register-alias!`
  registers seats the engine reads at launch. Re-point `:build` at a different
  base and every `:build` run in the workspace follows, no caller change. This
  is the exact seam the [delegation spool](./delegation/README.md) builds its
  cross-harness subagent surface on.
- **`:prompt-via` is a safety decision, not a detail.** `:arg` appends the
  prompt as the final argv token (the default); `:stdin` pipes it to the
  process. The shipped `:claude`/`:pi` harnesses default to `:stdin` because
  `-p` mode reads the prompt there — which keeps the worker prompt out of `ps`
  and clear of any `pkill -f` pattern kill that would otherwise match quoted
  prompt text. Keep it declarative per harness: `sh -c` *must* stay `:arg`
  because its script is a required positional, so the engine cannot move it to
  stdin. A typo'd `:prompt-via` fails loudly at `register-harness!` rather than
  silently falling back to the less-safe argv delivery (contract
  [§3, "Harness and alias registries"](./agent-run/README.md#3-harness-and-alias-registries)).
- **Aliases flatten; layering is cheap.** `:extra-args` splice in before the
  prompt, and an alias-of-an-alias resolves the whole chain, so a
  `:fast-reviewer` over `:explore` over `:claude` composes without repetition.
  Cycles and missing bases fail loudly at `resolve-harness` time.

Honest source: the registry tests `harness-registry-validates-and-resolves-aliases` and `stdin-prompt-stays-off-argv` in ``test/skein/agent_run_test.clj``. This repo applies the same shape in its own harness roster ([`.skein/harnesses.clj`](../.skein/harnesses.clj), `register-harness-aliases!`) — one concrete harness plus named tiers over it.

---

## Recipe: Continue a session across turns with `:resume`

**Situation.** A follow-up run should *continue* the same agent conversation a previous run started — reuse its context window rather than start cold — when the harness persists sessions to disk (`claude --resume`, `codex exec resume`).

**Composition.** The harness declares a `:resume` argv splice: literal flag strings interleaved with placeholder keywords (currently `:agent-run/session-id`) drawn from a closed input set. A `:parse` strategy that captures a session id (`:claude-json`, `:pi-json`) records `agent-run/session-id` on each run. Spawning with `:resume <predecessor-run-id>` then continues that predecessor's session.

```clojure
(require '[skein.spools.agent-run :as agent-run])

;; A harness that captures a session id (via :claude-json) and knows how to
;; continue one (via the :resume splice).
(agent-run/register-harness! :agent
  {:argv ["agent" "-p"]
   :parse :claude-json                 ; captures agent-run/session-id from output
   :resume ["--resume" :agent-run/session-id]})

(def first-turn (agent-run/spawn-run! {:harness :agent :prompt "Draft the plan"}))
;; ... first-turn finishes and captures agent-run/session-id ...

;; Continue that exact session; the engine resolves the placeholder from the
;; predecessor and splices "--resume <session>" in ahead of the prompt.
(agent-run/spawn-run! {:harness :agent :prompt "Now tighten step 3"
                     :resume (:id first-turn)})
```

**Why this shape.**

- **The splice rides ahead of the prompt.** At launch the engine resolves each
  placeholder from the predecessor run's captured attributes and inserts the
  result *before* the prompt, so the session flag precedes the turn text. The
  new run is stamped `agent-run/resumes <predecessor-id>` with a `resumes`
  annotation edge, so the continuation is visible on the graph.
- **Resume fails loudly, never cold.** A lost or unresumable session (no
  captured id, a harness-name mismatch, a second concurrent continuation)
  fails and stamps `agent-run/error-class "resume"` rather than silently starting
  a fresh conversation that looks continuous but isn't. Recovery is the
  deliberate `--fresh` escape, not an automatic fallback (contract
  [§3.1, "Session continuation"](./agent-run/README.md#31-session-continuation-resume)).
- **Persistence is never required.** A harness with no `:resume` splice is
  first-class and simply cannot be resumed; a run that never passes `:resume`
  behaves byte-identically to a no-resume engine. This repo's `:codex` harness
  even declares a splice that stays inert until a session-capturing parse lands
  — declared but harmless.

Honest source: `register-harness-validates-resume-splice` and `resume-continues-a-captured-session` (with the `session-echo` fake harness) in ``test/skein/agent_run_test.clj``, and the `:codex` resume note in [`.skein/harnesses.clj`](../.skein/harnesses.clj).

---


## Recipe: Replace a failed serving run without losing lineage

**Situation.** A run that owns a task's work failed or exhausted. You need a new run for the same
task, with the same blockers and provenance, while keeping the failed run's logs and notes attached
to history.

**Composition.** Serving is an edge, not a boolean. Spawn serving work with both placement and
serving: `:parent` puts the run under the task, and `:serves` marks the run as the task's active
work. Helpers use placement only. When the serving run dies, call `supersede-and-respawn!` with the
replacement prompt and harness.

```clojure
(require '[skein.spools.agent-run :as agent-run])

;; The run is placed under task and also serves task. A helper would omit :serves.
(def run
  (agent-run/spawn-run! {:harness :worker
                       :prompt "Implement the task"
                       :parent (:id task)
                       :serves (:id task)
                       :depends-on [(:id gate)]}))

;; Later, run failed. Create the successor from the updated prompt.
(agent-run/supersede-and-respawn! (:id run)
  {:harness :worker
   :prompt "Implement the revised task body"
   :carry-attrs {"delegation/task" (:id task)}})
```

**Why this shape.**

- **Serving is explicit.** A serving run is a run with a `serves` edge to the target. `parent-of` is
  only placement, so a recon run, reviewer, or panel seat can hang under the same task without
  becoming the task's delegation. Interactive sessions still use `agent-run/completes-on` as their completion
  target; closing that strand is what reaps the session.
- **The successor preserves the engine-owned shape.** `supersede-and-respawn!` carries forward the old
  run's `serves` target, outgoing `depends-on` edges, `spawned-by` provenance, `parent-of`
  placement, execution shape, and `agent-run/max-attempts`. The caller supplies the new prompt and
  harness, may override `:cwd`, and may pass `:carry-attrs` for spool-owned structural attributes.
- **Lineage is queryable.** The old run closes with phase `superseded`. The new run records a
  `supersedes` edge to the old run and an `agent-run/supersedes` attr. The current run serving a
  task is the serving run with no incoming `supersedes` edge and no `superseded` phase.
- **Resume is lineage plus session continuity.** Passing `:continuity :resume` also records a
  `resumes` edge and `agent-run/resumes`; those are separate from `supersedes`. In-place crash-
  respawn is the other family member: recovery keeps the same run id and attempt path, so it has no
  supersession edge.

Honest source: `spawn-run!`, `runs-serving`, and `supersede-and-respawn!` in [`agent-
run.clj`](./agent-run/src/skein/spools/agent_run.clj), with coverage in
``test/skein/agent_run_test.clj``.

---## Recipe: Fan out then collect, driven only by readiness

**Situation.** You have several runs that can go in parallel and one run that must wait for all of them — a batch of workers feeding a collector, or a prepare/build/verify chain. You want the ordering to fall out of the graph, not a scheduler you have to run.

**Composition.** Spawn each run with `spawn-run!`; express ordering with `:depends-on`. A run with no active blockers spawns immediately; one with active blockers waits until graph mutations clear them. Depending on several ids fans in — the collector spawns only when the last blocker closes.

```clojure
(require '[skein.spools.agent-run :as agent-run]
         '[skein.api.weaver.alpha :as weaver]
         '[skein.api.current.alpha :as current])

(def rt (current/runtime))              ; the active weaver runtime

;; two workers that run in parallel (no edges between them)
(def a (agent-run/spawn-run! {:harness :sh :prompt "echo a"}))
(def b (agent-run/spawn-run! {:harness :sh :prompt "echo b"}))

;; an ordinary strand acting as an external gate the collector also waits on
(def gate (weaver/add rt {:title "external gate"}))

;; the collector waits for both workers AND the gate — it stays pending until
;; every one is closed, then spawns via the graph event
(def collector
  (agent-run/spawn-run! {:harness :sh :prompt "echo collected"
                       :depends-on [(:id a) (:id b) (:id gate)]}))

;; closing the last blocker is what triggers the collector's spawn
(weaver/update rt (:id gate) {:state "closed"})
;; => collector runs, agent-run/result "collected"
```

**Why this shape.**

- **Readiness is the only scheduler.** The engine has no timers and no run
  queue. Any graph mutation fires the event handler, which asks Skein readiness
  which pending runs are now unblocked and spawns them. So "wait for these three
  things" is just three `depends-on` edges — no polling loop, no orchestration
  code (contract [§5, "Run lifecycle"](./agent-run/README.md#5-run-lifecycle)).
- **Fan-in is edge-absence plus edge-presence.** Two runs with no edge between
  them are independently ready — that *is* the parallelism. Depending on both
  (plus an ordinary gate strand) is the fan-in. You never write "wait for N";
  you write N edges.
- **The blockers need not be runs.** `depends-on` targets any strand, so a
  agent-run run can wait on a human-closed task, a workflow step, or another run
  interchangeably — the collector above waits on a plain `weaver/add` strand
  alongside its two worker runs.

Honest source: `dependent-run-waits-for-blocker-and-fans-in` in ``test/skein/agent_run_test.clj`` (two `sh` workers plus an external gate strand, collector stays pending until the last closes).

---

## Recipe: A workspace-wide worker preamble

**Situation.** Every run you spawn needs the same standing contract — how to talk back to the graph, what a delegated worker may and may not close, where to leave notes — and you don't want to paste it into every prompt.

**Composition.** The engine already injects a minimal, role-blind preamble (run id, the pinned `strand` command, spawn/await/note one-liners) plus its own worker contract, which you cannot switch off. Layer your policy on from trusted startup config with two slots: `set-preamble-extension!` for text every preamble-carrying headless run gets, and `set-default-task-contract!` for text only runs serving a task get.

```clojure
(require '[skein.spools.agent-run :as agent-run])

;; run once from startup config (init.clj / a spool install!)
(agent-run/set-preamble-extension!
  "House rules for every run here:
   - Read ./AGENTS.md before touching code.
   - Never run the deploy scripts.")

;; only runs with a `serves` edge see this; <task-id> renders as the served id
(agent-run/set-default-task-contract!
  "You serve a task:
   - Read it and its notes before acting: strand show <task-id>.
   - Set status=implemented on it only when your validation gate is green.")
```

**Why this shape.**

- **The engine owns only what it couples to.** Its contract is the graph
  invariants (never close your assigned strand, PID-only kills, shallow
  delegation); workflow opinion — what "done" means, which docs to read —
  arrives through the slots, so the engine never grows a notion of your
  workflow (contract [§7, "Preamble and contract text"](./agent-run/README.md#7-preamble-and-contract-text)).
- **Task text follows the `serves` edge, not the spawn.** An ad-hoc recon spawn
  serves nothing, so telling it to report `progress=` on an assigned task would
  be a lie; the task slot is delivered only to runs that actually serve a
  strand, with `<task-id>` rendered as that strand's id.
- **The two slots fail differently, on purpose.** The extension is a shared
  seam, so a second registrant with *different* text is a real cross-spool
  clash: rather than throwing (which would abort `reload!` mid-startup and leave
  the world with no ops) it replaces the value and records the conflict durably
  in `preamble-extension-conflicts`. The task slot is workspace configuration
  with no claim to contest, so re-registration just replaces. Both are
  reload-tolerant, and both take an identical replay silently.
- **Interactive runs opt out by design.** Both slots carry the delegated-worker
  contract ("never close your assigned strand"), which is the opposite of an
  interactive session's contract, where closing the served strand is how the
  session ends. The interactive preamble variant deliberately excludes them.

Honest source: `preamble-composes-engine-contract-then-workspace-text`, `set-default-task-contract-validates-and-clears`, and `set-preamble-extension-tolerates-reload` / `set-preamble-extension-records-conflicts-durably` in ``test/skein/agent_run_test.clj``; this repo's own opt-in in [`.skein/harnesses.clj`](../.skein/harnesses.clj).

---

## Recipe: Surviving a weaver crash — reconciliation and the claims model

**Situation.** The weaver holding your in-flight runs dies. You want headless work to resume where it can and stop loudly where it can't, and you want a live human session to *not* be silently restarted underneath the person in it.

**Composition.** Nothing to wire — `reconcile!` runs on `install!` because runs are durable strands. The two execution modes recover differently, and the shape you choose at spawn time (`:max-attempts` for headless, `:parent` for an interactive claim) is what governs recovery.

```clojure
(require '[skein.spools.agent-run :as agent-run])

;; headless: bounded crash-recovery attempts (default 3). An orphan left
;; `running` by a dead weaver is reset to pending and respawned, up to the bound,
;; then marked `exhausted` (loud, still active) so dependents stay blocked.
(agent-run/spawn-run! {:harness :sh :prompt "echo work" :max-attempts 2})

;; interactive claim: agent-run/completes-on is the completion target. If this run also
;; owns the target's work, pass :serves too. A surviving session is adopted after
;; a crash, never respawned.
(agent-run/spawn-run! {:harness :sh :prompt "drive the session"
                     :mode :interactive :backend :tmux
                     :parent (:id target)
                     :serves (:id target)})
```

**Why this shape.**

- **Durable strands make recovery bookkeeping, not magic.** A run's whole state
  lives in `agent-run/*` attributes, so a fresh weaver can look at a `running`
  strand it holds no in-flight handle for and know a dead predecessor owned it.
  It kills the stale process only when it can verify identity (pid plus recorded
  OS start instant, so it never signals a recycled pid), then respawns or
  exhausts (contract [§5.2, "Crash reconciliation"](./agent-run/README.md#52-crash-reconciliation)).
- **Bounded attempts fail loud, not forever.** `:max-attempts` caps respawns; a
  run that spends them lands `exhausted` and *stays active*, so downstream
  `depends-on` work stays blocked and the failure is visible to `agent-failures`
  rather than silently dropped.
- **Interactive sessions are adopted, never respawned — that is the claims
  model.** A session outlives the weaver, so recovery probes its durable handle
  attributes: a live one keeps the run `running`; a dead one is reaped as `done`
  only if its served target already closed (completion wins races), else failed
  loudly. Auto-respawning would silently discard a human conversation, so the
  engine refuses to (contract [§5.1, "Interactive completion"](./agent-run/README.md#51-interactive-completion-the-claims-model)).

Honest source: `reconcile-respawns-orphans-and-exhausts-bounded-attempts` and `interactive-run-reaps-when-served-strand-closes` / `reconcile-adopts-live-sessions-and-fails-dead-ones` in ``test/skein/agent_run_test.clj``.

---

## Recipe: Registering an interactive backend

**Situation.** Interactive runs launch an agent into a terminal multiplexer for a live human session. You run tmux (the shipped default), or zellij, or wezterm, or a wrapper script — and you want agent-run to drive whichever one, through the graph.

**Composition.** `register-backend!` registers the multiplexer as three argv vectors — `:start`, `:alive`, `:stop` — plus optional `:capture` and a display-only `:attach` hint. Argv tokens are keyword **splice points**, not string templates: engine inputs like `:session`/`:cwd`/`:command`, and `:handle/<key>` lookups into whatever `:start` returned. `:start` must print one flat JSON object as its last stdout line — the run's durable *handle*.

```clojure
(require '[skein.spools.agent-run :as agent-run])

;; The shipped tmux backend, verbatim — the reference shape.
(agent-run/register-backend! :tmux
  {:start  ["tmux" "new-session" "-d" "-s" :session "-c" :cwd
            "-P" "-F" "{\"session\":\"#{session_name}\",\"pane\":\"#{pane_id}\"}"
            :command]                                   ; :command = launcher script path
   :alive  ["tmux" "has-session" "-t" :handle/session]  ; exit 0 = alive
   :stop   ["tmux" "kill-session" "-t" :handle/session]
   :capture ["tmux" "capture-pane" "-p" "-t" :handle/pane]
   :attach ["tmux" "attach" "-t" :handle/session]})     ; shown to the human, never executed

;; then launch an interactive run onto it (see the reconciliation recipe)
(agent-run/spawn-run! {:harness :hitl-build :prompt "pair on the bug"
                     :mode :interactive :backend :tmux :parent (:id target)})
```

**Why this shape.**

- **Splice points, not templates, so there's nothing to parse.** Keyword tokens
  are validated statically at `register-backend!` time — a `:handle/*` key referenced
  in `:start` (before any handle exists) or an input outside the allowed set
  fails loudly at registration. And bare-keyword splicing never clashes with
  tmux's own `#{...}` format syntax.
- **The handle contract is how a backend reports reality.** `:start` prints one
  flat JSON object of strings; the engine never interprets the keys, it flattens
  them onto the run as durable `agent-run/handle.<key>` attributes and splices them
  into later ops. So a backend that can't honor the suggested `:session` name
  (wezterm picks its own pane id; zellij generates names) reports what it
  actually got, and sessions survive weaver restarts from graph state alone
  (contract [§4, "Backend registry"](./agent-run/README.md#4-backend-registry-interactive-sessions)).
- **Anything with logic points its argv at a script.** A backend is pure argv,
  so a multiplexer needing real decisions (name collision handling, attach
  routing) wraps that behind a shell script and names it in the vectors —
  the engine stays a dumb, testable splicer.

Honest source: the shipped `:tmux` backend in [`agent-run/README.md`](./agent-run/README.md#4-backend-registry-interactive-sessions), and the `fake-mux` backend plus `backend-registry-validates-defs` / `interactive-run-reaps-when-served-strand-closes` in ``test/skein/agent_run_test.clj``.

---

## Recipe: Reading run spend

**Situation.** You want to see what completed agent runs cost, how many tokens they reported, and how long they ran.

**Composition.** Use the delegation spool's read surface:

```sh
strand agent spend
strand agent spend --harness worker --group-by day
strand agent spend --since 2026-07-10T00:00:00Z --until 2026-07-11T00:00:00Z
```

The report has `totals`, `groups`, and per-run `runs`. Each run includes `id`, `harness`, `phase`,
`cost-usd`, `tokens-total`, optional `tokens`, `duration-ms`, `started-at`, and `finished-at`.

**Why this shape.** `pi-json` folds per-message usage deltas into one run total. `claude-json` reads
Claude's final result object. `codex-json` takes the last (cumulative) `turn.completed` event — codex
reports tokens but no dollar cost, so a seat's `:cost-rates` card derives `cost-usd` from the token
split when one is declared. Raw harness output records no cost or token usage, but still reports
wall time because the spend reader derives `duration-ms` from the run timestamps. Missing cost or
token figures stay null and sums skip them; the engine never writes a fake zero.

**Cost from a rate card.** A token-only harness (`codex-json`) prices out only when its seat declares a
rate card:

```clojure
;; USD per 1M tokens; the seat that picks the model carries the card
(register-alias! :mini-gpt-codex
  {:alias-of :codex
   :extra-args ["-m" "gpt-5.4-mini"]
   :cost-rates {:input 0.25 :cache-read 0.025 :output 2.0}})
```

An alias-level card overrides the tool's own, so per-model pricing lives beside the model choice. With
no card, the run still records its tokens — cost just stays absent (a recorded token count without a
cost beats a guessed number). See [`agent-run/README.md` §3.2](./agent-run/README.md#32-cost-rate-cards-cost-rates).

Honest source: `skein.spools.agent-run/spend` and the `strand agent spend` subcommand in the
delegation spool.

---

## See also

- [`agent-run/README.md`](./agent-run/README.md) — the contract: run lifecycle, the
  harness/backend registries, the preamble seam, crash reconciliation, and the
  full `agent-run/*` attribute table.
- [`agent-run.api.md`](./agent-run.api.md) — generated signatures and docstrings for
  every public fn referenced above.
- [`delegation/README.md`](./delegation/README.md) — the `strand agent` verb surface,
  delegation, and coordinator/worker guidance layered over this engine.
- [`executors/subagent.cookbook.md`](./executors/subagent.cookbook.md) — composing agent-run runs behind
  workflow `:subagent` gates.
