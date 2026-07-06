# Skein Shuttle Spool — Cookbook

Composition recipes for `skein.spools.shuttle`: how to wire the engine seams a
workspace owner actually touches, and *why* each shape is the right one.

This is the **how/why** half of the shuttle docs. The other two halves are:

- [`shuttle/README.md`](./shuttle/README.md) — the **contract**: run lifecycle,
  the harness/backend registries, the claims model, crash reconciliation, and
  the `shuttle/*` attribute vocabulary. Read it for what the engine promises.
- [`shuttle.api.md`](./shuttle.api.md) — the **generated reference**: every
  public fn's signature, arities, and docstring, produced from the source.

Division of truth: signatures and argument lists live in the generated API doc;
narrative and composition live here and in the contract. This cookbook never
restates a fn signature or the attribute table — it links to them. When a recipe
needs an exact arity, follow the link.

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches
your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which primitives combine, and how.
3. **Snippet** — a complete, runnable form (assume
   `(require '[skein.spools.shuttle :as shuttle])`).
4. **Why this shape** — the reasoning: why these primitives, what the attribute
   conventions buy you, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — the shuttle source,
this repo's `.skein` config, or the test suite — so you can read the
load-bearing version.

The recipes lean on the shipped `sh` harness, whose "argv" is a plain shell and
whose prompt *is* the script it runs. That makes a run cheap and deterministic
(no real coding agent, no network) while still exercising the whole
readiness-driven spawn engine — the same path a `claude` or `pi` run takes. Swap
`:sh` for a real harness and the shapes are unchanged.

---

## Recipe: Bring your own agent — a harness plus an alias tier

**Situation.** You want to run a specific coding agent (Claude, Codex, an
in-house CLI) from strands, and you want a few named tiers over it — a cheap
model for search, a mid model for grunt work, a top model for building — without
teaching every caller the underlying flags.

**Composition.** One `defharness!` describes the concrete launcher as plain
data: the `:argv`, how output is parsed (`:parse`), and how the prompt reaches
the process (`:prompt-via`). Then `defalias!` layers named tiers over it —
`:alias-of` plus `:extra-args` that splice into the argv before the prompt.
Aliases flatten (an alias may point at another alias), so the tier names are the
only vocabulary a caller needs.

```clojure
(require '[skein.spools.shuttle :as shuttle])

;; A concrete harness. codex exec prints only its final message on stdout, so
;; :raw parses cleanly; the prompt is appended to argv (:arg is the default).
(shuttle/defharness! :codex
  {:argv ["codex" "exec" "--skip-git-repo-check" "--color" "never"
          "--dangerously-bypass-approvals-and-sandbox"
          "-c" "shell_environment_policy.inherit=all"]
   :parse :raw
   :doc "Codex CLI headless; final message on stdout."})

;; Named tiers over the shipped :claude harness — one line each.
(shuttle/defalias! :explore
  {:alias-of :claude :extra-args ["--model" "haiku"]
   :doc "Fast read-only exploration and fan-out search."})
(shuttle/defalias! :grunt
  {:alias-of :claude :extra-args ["--model" "sonnet"]
   :doc "Tests, mechanical edits, grunt work."})
(shuttle/defalias! :build
  {:alias-of :claude :extra-args ["--model" "opus"]
   :doc "Feature building, reviews, council seats."})

(shuttle/resolve-harness :build)   ; => the flattened :claude def + opus args
```

**Why this shape.**

- **A harness is data, so swapping a provider is one line.** Nothing about a run
  is compiled in — `defharness!`/`defalias!` register plain maps that the engine
  reads at launch. Re-point `:build` at a different base and every `:build` run
  in the workspace follows, no caller change. This is the exact seam the
  [agents spool](./agents/README.md) builds its cross-harness subagent surface
  on.
- **`:prompt-via` is a safety decision, not a detail.** `:arg` appends the
  prompt as the final argv token (the default); `:stdin` pipes it to the
  process. The shipped `:claude`/`:pi` harnesses default to `:stdin` because
  `-p` mode reads the prompt there — which keeps the worker prompt out of `ps`
  and clear of any `pkill -f` pattern kill that would otherwise match quoted
  prompt text. Keep it declarative per harness: `sh -c` *must* stay `:arg`
  because its script is a required positional, so the engine cannot move it to
  stdin. A typo'd `:prompt-via` fails loudly at `defharness!` rather than
  silently falling back to the less-safe argv delivery (contract
  [§3, "Harness registry"](./shuttle/README.md#3-harness-registry)).
- **Aliases flatten; layering is cheap.** `:extra-args` splice in before the
  prompt, and an alias-of-an-alias resolves the whole chain, so a
  `:fast-reviewer` over `:explore` over `:claude` composes without repetition.
  Cycles and missing bases fail loudly at `resolve-harness` time.

Honest source: this repo's harness roster in
[`.skein/config.clj`](../.skein/config.clj) (`register-harness-aliases!` —
`:codex`, `:explore`/`:grunt`/`:build`, the GPT seats), and the registry tests
`harness-registry-validates-and-resolves-aliases` and
`stdin-prompt-stays-off-argv` in
[`test/skein/shuttle_test.clj`](../test/skein/shuttle_test.clj).

---

## Recipe: Continue a session across turns with `:resume`

**Situation.** A follow-up run should *continue* the same agent conversation a
previous run started — reuse its context window rather than start cold — when the
harness persists sessions to disk (`claude --resume`, `codex exec resume`).

**Composition.** The harness declares a `:resume` argv splice: literal flag
strings interleaved with placeholder keywords (currently `:shuttle/session-id`)
drawn from a closed input set. A `:parse` strategy that captures a session id
(`:claude-json`, `:pi-json`) records `shuttle/session-id` on each run. Spawning
with `:resume <predecessor-run-id>` then continues that predecessor's session.

```clojure
(require '[skein.spools.shuttle :as shuttle])

;; A harness that captures a session id (via :claude-json) and knows how to
;; continue one (via the :resume splice).
(shuttle/defharness! :agent
  {:argv ["agent" "-p"]
   :parse :claude-json                 ; captures shuttle/session-id from output
   :resume ["--resume" :shuttle/session-id]})

(def first-turn (shuttle/spawn-run! {:harness :agent :prompt "Draft the plan"}))
;; ... first-turn finishes and captures shuttle/session-id ...

;; Continue that exact session; the engine resolves the placeholder from the
;; predecessor and splices "--resume <session>" in ahead of the prompt.
(shuttle/spawn-run! {:harness :agent :prompt "Now tighten step 3"
                     :resume (:id first-turn)})
```

**Why this shape.**

- **The splice rides ahead of the prompt.** At launch the engine resolves each
  placeholder from the predecessor run's captured attributes and inserts the
  result *before* the prompt, so the session flag precedes the turn text. The
  new run is stamped `shuttle/resumes <predecessor-id>` with a `resumes`
  annotation edge, so the continuation is visible on the graph.
- **Resume fails loudly, never cold.** A lost or unresumable session (no
  captured id, a harness-name mismatch, a second concurrent continuation)
  fails and stamps `shuttle/error-class "resume"` rather than silently starting
  a fresh conversation that looks continuous but isn't. Recovery is the
  deliberate `--fresh` escape, not an automatic fallback (contract
  [§3.1, "Session continuation"](./shuttle/README.md#31-session-continuation-resume)).
- **Persistence is never required.** A harness with no `:resume` splice is
  first-class and simply cannot be resumed; a run that never passes `:resume`
  behaves byte-identically to a no-resume engine. The shipped `:codex` harness
  even declares a splice that stays inert until a session-capturing parse lands
  — declared but harmless.

Honest source: `defharness-validates-resume-splice` and
`resume-continues-a-captured-session` (with the `session-echo` fake harness) in
[`test/skein/shuttle_test.clj`](../test/skein/shuttle_test.clj), and the
`:codex` resume note in [`.skein/config.clj`](../.skein/config.clj).

---

## Recipe: Fan out then collect, driven only by readiness

**Situation.** You have several runs that can go in parallel and one run that
must wait for all of them — a batch of workers feeding a collector, or a
prepare/build/verify chain. You want the ordering to fall out of the graph, not
a scheduler you have to run.

**Composition.** Spawn each run with `spawn-run!`; express ordering with
`:depends-on`. A run with no active blockers spawns immediately; one with active
blockers waits until graph mutations clear them. Depending on several ids fans
in — the collector spawns only when the last blocker closes.

```clojure
(require '[skein.spools.shuttle :as shuttle]
         '[skein.api.weaver.alpha :as api])

;; two workers that run in parallel (no edges between them)
(def a (shuttle/spawn-run! {:harness :sh :prompt "echo a"}))
(def b (shuttle/spawn-run! {:harness :sh :prompt "echo b"}))

;; an ordinary strand acting as an external gate the collector also waits on
(def gate (api/add rt {:title "external gate"}))

;; the collector waits for both workers AND the gate — it stays pending until
;; every one is closed, then spawns via the graph event
(def collector
  (shuttle/spawn-run! {:harness :sh :prompt "echo collected"
                       :depends-on [(:id a) (:id b) (:id gate)]}))

;; closing the last blocker is what triggers the collector's spawn
(api/update rt (:id gate) {:state "closed"})
;; => collector runs, shuttle/result "collected"
```

**Why this shape.**

- **Readiness is the only scheduler.** The engine has no timers and no run
  queue. Any graph mutation fires the event handler, which asks Skein readiness
  which pending runs are now unblocked and spawns them. So "wait for these three
  things" is just three `depends-on` edges — no polling loop, no orchestration
  code (contract [§5, "Run lifecycle"](./shuttle/README.md#5-run-lifecycle)).
- **Fan-in is edge-absence plus edge-presence.** Two runs with no edge between
  them are independently ready — that *is* the parallelism. Depending on both
  (plus an ordinary gate strand) is the fan-in. You never write "wait for N";
  you write N edges.
- **The blockers need not be runs.** `depends-on` targets any strand, so a
  shuttle run can wait on a human-closed task, a workflow step, or another run
  interchangeably — the collector above waits on a plain `api/add` strand
  alongside its two worker runs.

Honest source: `dependent-run-waits-for-blocker-and-fans-in` in
[`test/skein/shuttle_test.clj`](../test/skein/shuttle_test.clj) (two `sh` workers
plus an external gate strand, collector stays pending until the last closes).

---

## Recipe: A workspace-wide worker preamble

**Situation.** Every run you spawn needs the same standing contract — how to talk
back to the graph, what a delegated worker may and may not close, where to leave
notes — and you don't want to paste it into every prompt.

**Composition.** The engine already injects a minimal, role-blind preamble
(run id, the pinned `strand` command, spawn/await/note one-liners). Layer your
policy on with `set-preamble-extension!` from trusted startup config: its text
is appended after the engine contract on every spawned run.

```clojure
(require '[skein.spools.shuttle :as shuttle])

;; run once from startup config (init.clj / a spool install!)
(shuttle/set-preamble-extension!
  "You are a delegated worker.
   - Read your assigned strand and its notes before acting.
   - Set status=implemented only when your validation gate is green.
   - Never close your assigned strand; leave findings as notes.")
```

**Why this shape.**

- **One seam keeps the engine free of workflow opinion.** The engine's own
  preamble carries only run identity and the mechanics of talking to the graph.
  Higher-level policy — the worker contract — arrives through this single
  extension point, so the engine never grows a notion of "delegated worker."
  The agents spool fills exactly this seam with its worker contract on
  `install!` (contract [§7, "Preamble seam"](./shuttle/README.md#7-preamble-seam)).
- **Reload-tolerant: replay is silent, a genuine clash is recorded.** Startup
  config re-runs on every `reload!`, so re-registering the *same* text is a
  no-op. A second registrant with *different* text is a real cross-spool clash;
  rather than throwing (which would abort `reload!` mid-startup and leave the
  world with no ops) it replaces the value and records the conflict durably in
  `preamble-extension-conflicts`, so an operator sees it long after the stderr
  warning scrolled away.
- **Interactive runs opt out by design.** The extension carries the
  delegated-worker contract ("never close your assigned strand"), which is the
  opposite of an interactive session's contract, where closing the served
  strand is how the session ends. The interactive preamble variant deliberately
  excludes it.

Honest source: `skein.spools.agents/install!` calling
`set-preamble-extension!` with its worker contract, and
`set-preamble-extension-tolerates-reload` /
`set-preamble-extension-records-conflicts-durably` in
[`test/skein/shuttle_test.clj`](../test/skein/shuttle_test.clj).

---

## Recipe: Surviving a weaver crash — reconciliation and the claims model

**Situation.** The weaver holding your in-flight runs dies. You want headless
work to resume where it can and stop loudly where it can't, and you want a live
human session to *not* be silently restarted underneath the person in it.

**Composition.** Nothing to wire — `reconcile!` runs on `install!` because runs
are durable strands. The two execution modes recover differently, and the shape
you choose at spawn time (`:max-attempts` for headless, `:parent` for an
interactive claim) is what governs recovery.

```clojure
(require '[skein.spools.shuttle :as shuttle])

;; headless: bounded crash-recovery attempts (default 3). An orphan left
;; `running` by a dead weaver is reset to pending and respawned, up to the bound,
;; then marked `exhausted` (loud, still active) so dependents stay blocked.
(shuttle/spawn-run! {:harness :sh :prompt "echo work" :max-attempts 2})

;; interactive claim: the run serves `target` and completes when target closes.
;; A surviving session is adopted after a crash, never respawned.
(shuttle/spawn-run! {:harness :sh :prompt "drive the session"
                     :mode :interactive :backend :tmux
                     :parent (:id target)})
```

**Why this shape.**

- **Durable strands make recovery bookkeeping, not magic.** A run's whole state
  lives in `shuttle/*` attributes, so a fresh weaver can look at a `running`
  strand it holds no in-flight handle for and know a dead predecessor owned it.
  It kills the stale process only when it can verify identity (pid plus recorded
  OS start instant, so it never signals a recycled pid), then respawns or
  exhausts (contract [§5.2, "Crash reconciliation"](./shuttle/README.md#52-crash-reconciliation)).
- **Bounded attempts fail loud, not forever.** `:max-attempts` caps respawns; a
  run that spends them lands `exhausted` and *stays active*, so downstream
  `depends-on` work stays blocked and the failure is visible to `agent-failures`
  rather than silently dropped.
- **Interactive sessions are adopted, never respawned — that is the claims
  model.** A session outlives the weaver, so recovery probes its durable handle
  attributes: a live one keeps the run `running`; a dead one is reaped as `done`
  only if its served target already closed (completion wins races), else failed
  loudly. Auto-respawning would silently discard a human conversation, so the
  engine refuses to (contract [§5.1, "Interactive completion"](./shuttle/README.md#51-interactive-completion-the-claims-model)).

Honest source: `reconcile-respawns-orphans-and-exhausts-bounded-attempts` and
`interactive-run-reaps-when-served-strand-closes` /
`reconcile-adopts-live-sessions-and-fails-dead-ones` in
[`test/skein/shuttle_test.clj`](../test/skein/shuttle_test.clj).

---

## Recipe: Registering an interactive backend

**Situation.** Interactive runs launch an agent into a terminal multiplexer for a
live human session. You run tmux (the shipped default), or zellij, or wezterm, or
a wrapper script — and you want shuttle to drive whichever one, through the
graph.

**Composition.** `defbackend!` registers the multiplexer as three argv vectors —
`:start`, `:alive`, `:stop` — plus optional `:capture` and a display-only
`:attach` hint. Argv tokens are keyword **splice points**, not string templates:
engine inputs like `:session`/`:cwd`/`:command`, and `:handle/<key>` lookups
into whatever `:start` returned. `:start` must print one flat JSON object as its
last stdout line — the run's durable *handle*.

```clojure
(require '[skein.spools.shuttle :as shuttle])

;; The shipped tmux backend, verbatim — the reference shape.
(shuttle/defbackend! :tmux
  {:start  ["tmux" "new-session" "-d" "-s" :session "-c" :cwd
            "-P" "-F" "{\"session\":\"#{session_name}\",\"pane\":\"#{pane_id}\"}"
            :command]                                   ; :command = launcher script path
   :alive  ["tmux" "has-session" "-t" :handle/session]  ; exit 0 = alive
   :stop   ["tmux" "kill-session" "-t" :handle/session]
   :capture ["tmux" "capture-pane" "-p" "-t" :handle/pane]
   :attach ["tmux" "attach" "-t" :handle/session]})     ; shown to the human, never executed

;; then launch an interactive run onto it (see the reconciliation recipe)
(shuttle/spawn-run! {:harness :hitl-build :prompt "pair on the bug"
                     :mode :interactive :backend :tmux :parent (:id target)})
```

**Why this shape.**

- **Splice points, not templates, so there's nothing to parse.** Keyword tokens
  are validated statically at `defbackend!` time — a `:handle/*` key referenced
  in `:start` (before any handle exists) or an input outside the allowed set
  fails loudly at registration. And bare-keyword splicing never clashes with
  tmux's own `#{...}` format syntax.
- **The handle contract is how a backend reports reality.** `:start` prints one
  flat JSON object of strings; the engine never interprets the keys, it flattens
  them onto the run as durable `shuttle/handle.<key>` attributes and splices them
  into later ops. So a backend that can't honor the suggested `:session` name
  (wezterm picks its own pane id; zellij generates names) reports what it
  actually got, and sessions survive weaver restarts from graph state alone
  (contract [§4, "Backend registry"](./shuttle/README.md#4-backend-registry-interactive-sessions)).
- **Anything with logic points its argv at a script.** A backend is pure argv,
  so a multiplexer needing real decisions (name collision handling, attach
  routing) wraps that behind a shell script and names it in the vectors —
  the engine stays a dumb, testable splicer.

Honest source: the shipped `:tmux` backend in
[`shuttle/README.md`](./shuttle/README.md#4-backend-registry-interactive-sessions),
and the `fake-mux` backend plus `backend-registry-validates-defs` /
`interactive-run-reaps-when-served-strand-closes` in
[`test/skein/shuttle_test.clj`](../test/skein/shuttle_test.clj).

---

## See also

- [`shuttle/README.md`](./shuttle/README.md) — the contract: run lifecycle, the
  harness/backend registries, the preamble seam, crash reconciliation, and the
  full `shuttle/*` attribute table.
- [`shuttle.api.md`](./shuttle.api.md) — generated signatures and docstrings for
  every public fn referenced above.
- [`agents/README.md`](./agents/README.md) — the `strand agent` verb surface,
  delegation, and coordinator/worker guidance layered over this engine.
- [`treadle.cookbook.md`](./treadle.cookbook.md) — composing shuttle runs behind
  workflow `:subagent` gates.
