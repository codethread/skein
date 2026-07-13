# Skein Agent-run Spool

> This is the **contract** doc: run lifecycle, the harness/backend registries,
> the claims model, crash reconciliation, and the `agent-run/*` attribute
> vocabulary. Its two companions are
> [`agent-run.cookbook.md`](../agent-run.cookbook.md) — worked composition recipes
> (how/why you wire a harness tier, the preamble seam, readiness chains, or a
> backend) — and [`agent-run.api.md`](../agent-run.api.md) — generated fn signatures
> and docstrings. Reach for the cookbook when you want a runnable pattern, the
> API doc when you want an exact arity, and this doc for what the engine
> promises.

## 1. Overview

`skein.spools.agent-run` is a trusted userland spool for spawning coding-agent runs from ordinary Skein strands. It is a pure run **engine**: it registers no CLI operations of its own. The agent-facing verb surface (`strand agent ...`), delegation, and coordinator/worker guidance live in the [delegation spool](../delegation/README.md), which composes this engine.

A run is a strand carrying `agent-run/*` attributes. Creating the run strand is the API: the installed agent-run event handler watches graph mutations, asks Skein readiness which pending run strands are unblocked, launches the selected harness, records output back onto the run strand, and closes successful runs so downstream `depends-on` work can proceed.

Runs come in two execution modes. **Headless** runs (the default) exec the harness and capture its exit/stdout. **Interactive** runs launch the harness into a user-registered terminal multiplexer [backend](#4-backend-registry-interactive-sessions) (tmux by default) and are supervised through the graph: the run completes when the strand it serves closes (the claims model — the agent in the session marks the work done, the engine reaps the session), not when a process exits.

Agent-run is intentionally not core scheduler infrastructure. It composes existing primitives: strand attributes, `depends-on` readiness, `parent-of` provenance, annotation edges, event handlers, runtime spool loading, and the CLI op registry.

## 2. Loading

Agent-run is shipped as an approved-local-root spool example under `spools/agent-run`. A workspace opts in with `spools.edn` and trusted startup or REPL code:

```clojure
;; .skein/spools.edn
{:spools {skein.spools/agent-run {:local/root "../spools/agent-run"}}}
```

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/sync! runtime)
(runtime/use! runtime :agent-run
  {:ns 'skein.spools.agent-run
   :spools ['skein.spools/agent-run]
   :call 'skein.spools.agent-run/install!
   :required? true})
```

`install!` registers the default harnesses and backends, a graph-mutation event handler, and runs crash reconciliation with a first scan. Harnesses, backends, live in-flight process ownership, deferred-recovery scheduling, preamble extensions, and default review contract text are runtime-local weaver-lifetime state, isolated from other runtimes in the same JVM. The deferred-recovery scheduler is owned by runtime spool state and is shut down during runtime stop before storage closes. This state is registered with a declared shape **version** (`skein.api.runtime.alpha/spool-state`, SPEC-004.C95): spool state survives `reload!`, so after a deploy that adds a new state key a reload deliberately reinits through a migrate hook that carries the durable registries and in-flight tracking over onto fresh executors, rather than silently reusing a preserved map missing the new executor keys (which previously turned scan!'s launch into `(.execute nil ..)` and parked every new run forever). The executor and scheduler accessors fail loudly when their spool-state entry is missing rather than parking runs on a nil executor (TEN-003). It does **not** register any CLI operations. Load the [delegation spool](../delegation/README.md) after agent-run for the `strand agent` surface, and the companion [subagent executor](../executors/subagent.md) to fulfill workflow `:subagent` gates with agent-run runs.

## 3. Harness and alias registries

Harnesses are data-first launcher definitions registered in trusted Clojure; aliases are the seat names layered over them in a registry of their own:

| Fn | Behavior |
|---|---|
| `(defharness! name def)` | Register a concrete harness. `def` requires `:argv` and may include `:parse`, `:prompt-via`, `:preamble?`, `:env`, `:cwd`, `:doc`, `:cost-rates` (rate card — see [§3.2](#32-cost-rate-cards-cost-rates)), `:resume` (session continuation splice — see [§3.1](#31-session-continuation-resume)), and `:capture` (interactive transcript capture — see [§4.1](#41-transcript-capture)). |
| `(defalias! name def)` | Register an alias over a harness or another alias. Alias defs require `:alias-of` and may add `:extra-args`, `:prompt-prefix`, `:doc`, and `:cost-rates` (a seat-level rate card that overrides the tool's). |
| `(resolve-harness name)` | Return the effective harness after flattening aliases; alias cycles fail loudly. |
| `(harnesses)` | Return registered harness and alias metadata ordered by name. |
| `(register-default-harnesses!)` | Register shipped `claude`, `pi`, and `sh` harnesses without replacing existing entries. |

Default parse strategies are `:raw`, `:claude-json`, `:pi-json`, and `:codex-json`. The `sh` harness is intended for tests and plumbing.

`:codex-json` parses `codex exec --json` (a JSONL event stream): the result is the last `agent_message` item, the session id is the started thread id, and usage comes from the **last** `turn.completed` event. codex's `turn.completed` usage is the *cumulative* session total, not a per-turn delta (openai/codex#17539), so the run total is the last event, never a sum — the opposite fold from `:pi-json`'s per-turn deltas. `cached_input_tokens` is split out of `input_tokens` into the `:cache-read` dimension, and `reasoning_output_tokens` is recorded beside `:output` (a subset of it, never added on top). codex reports no dollar cost (subscription auth), so `:codex-json` records tokens only; a `:cost-rates` card is what turns them into cost.

Harnesses and aliases live in separate runtime registries. A harness names one
concrete tool (`claude`, `pi`, `sh`); an alias names a seat over a tool
(`worker`, `explore`, `build`) and may carry model/provider flags, prompt
prefixes, or seat-specific docs. Name resolution is alias-first, then harness:
a seat can intentionally shadow a tool name without replacing the tool, and a
harness remains directly addressable whenever no alias with that name exists.
Same-name shadowing is therefore lawful, while alias cycles and missing roots
still fail loudly. On reload from the prior mixed registry shape, agent-run
migrates entries by shape: definitions with `:alias-of` become aliases,
definitions with `:argv` become harnesses, and malformed entries fail the
migration rather than guessing.

`:prompt-via` controls how a headless run's worker prompt reaches the process — `:stdin` (piped to the process's standard input) or `:arg` (appended as the final argv token, the default). **The shipped `:claude` and `:pi` harnesses default to `:stdin`** because `-p` mode on both reads the prompt from stdin: an argv-delivered prompt is exposed in `ps` and, worse, lands in the blast radius of any `pkill -f` pattern kill that happens to match quoted prompt text (a pattern kill matching prompt text once strafed sibling agent processes). Keep the argv shape declarative per harness — a harness whose CLI cannot read the prompt from stdin (e.g. `sh -c`, whose script is a required positional argument) stays `:arg`. Interactive runs always use `:arg`: stdin belongs to the live session, so a `:stdin` harness is rejected on the interactive path. `defharness!` rejects any `:prompt-via` other than `:arg` or `:stdin` at registration — a typo fails loudly rather than silently falling back to the less-safe argv delivery.

Because a harness is plain data, swapping the underlying provider for a whole workspace is a single `defharness!`/`defalias!` line — this is the seam the delegation spool builds its cross-harness subagent surface on.

### 3.1 Session continuation (`:resume`)

A harness may declare a `:resume` splice: a non-empty vector of literal argv strings interleaved with placeholder keywords drawn from a closed input set (currently `:agent-run/session-id`). A typo'd placeholder fails loudly at `defharness!` time rather than resolving to `nil`. Spawning a run with `:resume <predecessor-run-id>` continues that predecessor's harness session — the engine stamps `agent-run/resumes <predecessor-id>` on the new run plus a `resumes` annotation edge, and at launch resolves each placeholder from the predecessor's captured attributes and splices the result into the argv **before** the prompt (so the session flag precedes the turn text). The shipped `:claude` and `:pi` defs declare a resume splice and capture `agent-run/session-id` by default (via `:claude-json` / `:pi-json`); a harness with no `:resume` splice stays first-class and simply cannot be resumed.

Resume fails loudly (TEN-003), never silently, and every resume-classed failure stamps `agent-run/error-class "resume"` so recovery can branch deliberately:

- **no splice** — the spawning harness declares no `:resume`;
- **missing session** — the predecessor never captured a `agent-run/session-id`;
- **harness mismatch** — resume requires the *exact same* harness/alias name as the predecessor (aliases swap model/provider/agent via `:extra-args`, so a base-root match is too weak; the error carries both names);
- **concurrent continuation** — at most one active run may carry `agent-run/resumes <p>` at a time (one live continuation per session);
- **interactive** — interactive runs reject `:resume`; the live session is their own continuity.

These invariants are enforced both at `spawn-run!` time and again at the launch seam, because a run strand can be hand-built directly via `weaver/add` — a handmade `agent-run/resumes` run never bypasses them.

**Persistence is host-local and never required.** Harness session stores are host-local, non-skein-owned state: Skein records only the `agent-run/session-id` it parsed and never manages the store. Nothing consumes a session unless a caller passes `:resume` — a run without it behaves byte-identically to a no-resume engine. A lost or unresumable session fails loudly rather than auto-falling back to a cold start; the recovery path is the named `--fresh` escape (see [agents `retry`](../delegation/README.md#3-op-surface)), which severs the linkage and respawns on the full-brief prompt.

### 3.2 Cost rate cards (`:cost-rates`)

Some harnesses report token usage but no dollar cost — `:codex-json` is the case in point (codex runs on subscription auth and never emits a price). For these, a harness or alias may declare a **rate card** `:cost-rates {:input <usd-per-1M-uncached> :cache-read <usd-per-1M-cached> :output <usd-per-1M-output>}`. At completion, when a parser yields usage without cost and the seat declares a card, the engine derives `agent-run/cost-usd` from the token split (USD per 1M tokens over the run's `:input`/`:cache-read`/`:output` dimensions). The key set is closed, so a typo'd rate fails at `defharness!`/`defalias!` time rather than pricing that dimension at zero.

The card is optional on purpose: **absence is contract.** When no rates are declared, cost stays absent — recorded tokens without cost beat a guessed number, and the sums in `strand agent spend` skip the absent figure rather than reading a fake zero. A parser that reports its own dollar cost (`:claude-json`, `:pi-json`) is never overridden by a card. The `usage-source` is left as the parser set it (`"codex-json"`), so a card-derived cost stays distinguishable from a provider-reported one. Because the *seat* is where a model is chosen (`-m` / `--model` in `:extra-args`), an alias-level card overrides the tool's own card — per-model pricing lives on the alias.

## 4. Backend registry (interactive sessions)

Backends are the pluggable multiplexer seam for interactive runs, registered in trusted Clojure exactly like harnesses. One user runs tmux, another zellij or wezterm; anything needing real logic points its argv at a wrapper script.

| Fn | Behavior |
|---|---|
| `(defbackend! name def)` | Register a backend. `def` requires `:start`, `:alive`, and `:stop` argv vectors; optional `:capture` (scrollback forensics before teardown), `:attach` (display-only human hint, never executed), and `:doc`. |
| `(resolve-backend name)` | Return the registered backend def; missing backends fail loudly. |
| `(backends)` | Return registered backend metadata ordered by name. |
| `(register-default-backends!)` | Register the shipped `tmux` backend without replacing existing entries. |

Argv tokens are keyword **splice points**, not string templates — zero parsing, no clash with tmux's own `#{...}` format syntax, and validated statically at `defbackend!` time. Bare keywords are engine inputs (`:session` the suggested session name, `:cwd`, `:command` the launcher script path, `:run-id`; `:start` only, except `:run-id`); `:handle/<key>` keywords look up the run's stored handle. The shipped default:

```clojure
(defbackend! :tmux
  {:start  ["tmux" "new-session" "-d" "-s" :session "-c" :cwd
            "-P" "-F" "{\"session\":\"#{session_name}\",\"pane\":\"#{pane_id}\"}"
            :command]
   :alive  ["tmux" "has-session" "-t" :handle/session]   ; exit 0 = alive
   :stop   ["tmux" "kill-session" "-t" :handle/session]
   :capture ["tmux" "capture-pane" "-p" "-t" :handle/pane]
   :attach ["tmux" "attach" "-t" :handle/session]})
```

**Handle contract.** `:start` must print one flat JSON object of strings as its last stdout line (empty output means `{}`) — the run's *handle*. Backends that cannot honor the suggested session name (wezterm returns a pane id it picked; zellij generates names) report reality here. The engine never interprets handle keys; it flattens them onto the run strand as durable `agent-run/handle.<key>` attributes and splices them into later ops, so sessions survive weaver restarts from graph state alone. A referenced handle key the backend never returned fails loudly at first use.

Backends **should** honor the suggested `:session` name whenever the multiplexer allows it: the suggestion is written durably *before* `:start` runs and is the only recovery identity for a weaver crash in the gap between `:start` succeeding and the handle attrs landing. A backend whose ops need only `:handle/session` recovers cleanly from that gap; one that depends on other handle keys accepts that a crash there orphans its session — the engine then fails the run loudly and the human cleans the session up by its name.

**Launcher script.** The engine writes a `0700` script under the weaver state dir (deleted on teardown) containing harness env, pinned engine env (`SKEIN_RUN_ID`, `XDG_STATE_HOME`), the cwd, and the exec of the harness argv with the prompt — so prompts stay out of multiplexer argv and process listings. `:command` is that script's path. Suggested session names are workspace-namespaced (`skein-<workspace-hash>-<run-id>`): multiplexer sessions live in a server-global namespace and two workspaces must not collide or cross-adopt.

### 4.1 Transcript capture

Capture is a resolvable seam, not a fixed behavior: **a harness `:capture` op wins over the backend's `:capture`** (tmux scrollback, the shipped default). Both are the same spliced argv contract with inputs `:run-id`, `:cwd`, `:session`, and `:handle/*`; the op prints the best available transcript text to stdout, which the engine persists as `<run-id>.capture` and records in `agent-run/log`. The engine never parses any harness's log format — that knowledge stays in userland argv, which is deliberate: harness transcript schemas are internal and drift across releases, so an adapter layer that parses them (the pandoras-box Spawner approach) rots; a user-owned capture command does not.

Harness capture is how harness-aware transcript sources plug in. Correlation is the engine's job and already done: every session's launcher exports `SKEIN_RUN_ID`, so session-start hooks can key their logs by run id. Example for a hook-written dialogue log:

```clojure
(defharness! :claude-hitl
  {:argv ["claude" "--dangerously-skip-permissions"]
   ;; a SessionStart hook symlinks its dialogue log to <state>/claude-dialogue/by-run/$SKEIN_RUN_ID.jsonl
   :capture ["sh" "-c" "cat \"${XDG_STATE_HOME:-$HOME/.local/state}/claude-dialogue/by-run/$1.jsonl\"" "capture" :run-id]})
```

Capture runs at two points: best-effort before teardown (never a completion blocker), and on demand via `(capture! id)` / `agent logs <run-id>` against a **live** session — a coordinator peek without attaching. When the capture source outlives the session (hook logs do; scrollback does not), `capture!` also works after the run finished.

## 5. Run lifecycle

| Fn | Behavior |
|---|---|
| `(spawn-run! opts)` | Create one run strand. Required: `:harness`, `:prompt`. Optional: `:title`, `:depends-on`, `:parent`, `:spawned-by`, `:serves`, `:cwd`, `:max-attempts`, `:attrs`, and `:resume`. `:parent` and `:spawned-by` add `parent-of`; `:serves` adds `serves`. Interactive runs add `:mode`, `:backend`, and `:reap`. |
| `(scan!)` | Spawn every ready pending run not already claimed in this weaver lifetime. Usually called by events and install/reconcile. |
| `(supervise!)` | Advance every interactive run in phase `running`: reap completed ones, fail dead sessions. Called by events, `runs`, `await-runs`, and reconcile — the weaver deliberately has no timers, so there is no background poller. |
| `(runs opts)` | Return summaries of agent-run runs; `{:active true}` filters active runs, `{:for <strand-id>}` filters by delegated target. `:for` is the served target (`serves`) or, for helpers, the non-`spawned-by` `parent-of` source. `:spawned-by` remains separate. Interactive summaries add session fields. |
| `(await-runs ids opts)` | Block until all runs are terminal or `:timeout-secs` (default 300) elapses; interactive runs are liveness-probed every ~2s while awaited. |
| `(kill! id)` | Destroy a live harness process or backend session and mark the run failed; fails loudly when the run has no live process/session. |
| `(capture! id)` | Capture an interactive run's transcript now (harness `:capture` > backend scrollback), persist it as `agent-run/log`, and return `{:id :path :text}`. |
| `(reconcile!)` | On install, recover active running runs left by a previous weaver lifetime. |
| `(in-flight-run-ids)` | Return the set of run ids the agent-run is currently tracking in-flight — every run in phase `:claimed`, `:running`, or `:deferred-recovery`. Attention detectors (the repo's parked-run chime rule) use it to tell a genuinely parked ready run — one `scan!` should have launched but did not — from one already in flight. |

Successful runs set `agent-run/phase "done"`, record a non-blank `agent-run/result`, and close the run strand. A headless run that exits 0 but produces a blank result is **not** success: the result is the worker's report, and a silent harness/transport death mid-turn writes nothing yet still exits 0, so such a run is recorded `failed` (loud and retryable) rather than a hollow `done` that would dodge `agent-failures` and both recovery paths. The same holds when the harness's own output reports the turn failed despite a clean exit: a `:pi-json` event stream whose final assistant message carries `stopReason "error"`/`errorMessage` (provider usage limit, auth, transport — pi still exits 0) is recorded `failed` with that message in `agent-run/error`. Failed runs remain `active` with `agent-run/phase "failed"` and `agent-run/error`, so dependents stay blocked loudly. Exhausted crash-recovery runs remain active with `agent-run/phase "exhausted"`.

Every headless outcome that observes the process exit records that code on `agent-run/exit-code` — the `done` close, the exit-0 failures (blank result, harness-reported turn error), and the non-zero-exit failure all stamp it. Failures with no process exit code to report (interactive session death, launch exceptions, a killed run) deliberately omit the attribute; its presence means "a headless process reported this code", not "the run failed".

Readiness is the only scheduling primitive: a pending run with no active `depends-on` blockers can spawn; a pending run with active blockers waits until graph mutations make it ready.

### 5.1 Interactive completion (the claims model)

An interactive run's completion signal is graph state, not process exit:

- **claim** (`:parent` given, recorded as `agent-run/for`): the run serves that strand. The injected preamble instructs the agent to note, say goodbye, then close the served strand as its literal last action; the engine reaps the session when that strand closes. Completion wins races — supervision checks graph completion before and after the liveness probe, so an agent that closes its target and exits in the same instant is `done`, not `failed`.
- **manual-close** (no `:parent`): the session ends when its own run strand is closed.
- **reap policy**: `auto` (default) captures the transcript (see [§4.1](#41-transcript-capture); path recorded in `agent-run/log`) and stops the session on completion; `manual` marks the run done but leaves the session to the human. Teardown failure is recorded loudly in `agent-run/teardown-error` but never blocks completion.
- **dead sessions**: a session that dies before its work completes (user closes the pane, agent crashes) marks the run `failed` loudly. There is no auto-respawn — restarting an interactive session would silently discard a human conversation; recovery is a deliberate retry.

If you want the human notified as soon as a session is ready to attach, keep that in userland: chime can register a rule matching `agent-run/mode=interactive` and `agent-run/phase=running` and include the same attach hint shown by `strand agent ps`. See [Chime cookbook: Notify when an interactive session is waiting](../chime.cookbook.md#recipe-notify-when-an-interactive-session-is-waiting).

### 5.2 Crash reconciliation

`reconcile!` runs on `install!`. **Headless:** any active `running` run this weaver holds no in-flight handle for was owned by a dead predecessor: its stale process is killed when its identity can be verified (pid plus recorded OS start instant), then the run is reset to `pending` for respawn or marked `exhausted` (loudly, still active) once `agent-run/max-attempts` (default `3`) is spent. Recovered runs are stamped with `agent-run/recovered-at`; if a recovery-origin respawn references a harness alias that is not registered yet, the run is returned to `pending` with a loud `agent-run/error` and `agent-run/recovery-deferred-until`, and scans skip it until that quiet retry timestamp passes rather than immediately self-looping. The retry wakeup is runtime-owned spool state and is cancelled/joined on runtime stop; after a restart, ordinary scans still enforce the persisted `agent-run/recovery-deferred-until` timestamp. That deferral is bounded by a recovery window (currently 30 seconds from `agent-run/recovered-at`): transient startup/config alias races can heal, but a genuinely missing alias becomes a normal `failed` run and is visible to failure queries. User-created spawns and handmade pending runs with unknown harnesses still fail loudly. **Interactive:** sessions survive the weaver by design, so orphans are *adopted*, never respawned — a live session (probed via its durable handle attrs) keeps its run `running`; a dead one is reaped as done when its target already closed, otherwise failed loudly regardless of attempts. Runs survive weaver crashes because the strands are durable.

### 5.3 Serving runs and lineage

A serving run is a run with a `serves` edge from the run to the target strand. Helpers omit that
edge. `parent-of` is only graph placement and provenance; no reader infers serving from it. This
keeps a recon run, reviewer, or panel seat under a task without making it the task's active
delegation.

Interactive completion is separate. A claim-completion interactive run still records its completion
target in `agent-run/for` and completes when that strand closes. A run may also carry a `serves`
edge when the caller wants it to be the current serving run for that target, but `agent-run/for`
remains the session-reap signal.

`supersede-and-respawn!` is the engine's succession primitive. It creates a fresh successor and
preserves the predecessor's `serves` target, outgoing `depends-on` edges, `spawned-by` provenance,
`parent-of` placement, execution shape, `agent-run/max-attempts`, and caller-supplied `:carry-
attrs`. The caller supplies the new prompt and harness; `:cwd` comes from the caller or the
predecessor. The successor starts at `agent-run/phase "pending"` with no process residue.

The same call records lineage. The predecessor is closed with `agent-run/phase "superseded"`. The
successor gets a `supersedes` edge to the predecessor and an `agent-run/supersedes` attribute naming
it. `runs-serving` resolves the current run serving a target as the serving run with no incoming
`supersedes` edge and no `superseded` phase. Helpers never enter that set because they have no
`serves` edge.

The family has three members. Deliberate supersession uses a fresh successor. `:resume` supersession
also creates a successor, but carries a `resumes` edge and `agent-run/resumes` so the harness can
continue the predecessor's session; `resumes` and `supersedes` stay separate. In-place crash-respawn
is recovery of the same run id and has no supersession edge; it increments the attempt path for the
orphaned run.
## 6. Run memory (notes)

| Fn | Behavior |
|---|---|
| `(note! target-id text opts)` | Append a closed note strand linked to `target-id` by the declared `notes` relation; its `note/text`/`note/at` content is storage-enforced write-once. `opts` may set `:by` (author run id) and `:round`. |
| `(notes target-id opts)` | Return notes in creation order, optionally filtered by `:round`. |

Notes are append-only memory: a note-content rewrite throws, and burn is the only escape hatch. Each note carries `note/text` and `note/at` (storage-enforced write-once), plus optional `note/by` / `note/round`; the linkage is the `notes` edge, never a `note/for` attribute.

## 7. Preamble seam

Every spawned run (unless its harness sets `:preamble? false`) is launched with an injected preamble the engine owns. The preamble is deliberately minimal and role-blind — it carries only run identity and the mechanics a worker needs to talk back to the graph:

- the run's `run-id`;
- the fully pinned `strand` invocation (`env XDG_STATE_HOME=… strand --workspace …`) that must prefix every strand command, because harness shells re-source dotfiles and cannot be trusted to inherit ambient env;
- spawn/await/note one-liners and the pointer to `strand agent about`.

Higher-level policy is layered on through a single seam so the engine stays free of workflow opinion:

| Fn | Behavior |
|---|---|
| `(set-preamble-extension! text)` | Register additional preamble text appended after the engine contract. Reload-tolerant, distinguishing replay from conflict: re-registering the **same** text is a silent no-op, and **different** text (a genuine cross-spool clash) replaces the prior value (returned as `:replaced true`) rather than failing. A hard failure here would abort `reload!` mid-startup — the config re-runs this on every reload — and a preamble-text change between deploys must not leave the world with no ops. Instead of a stderr-only warning that scrolls off a long-lived daemon, a conflict is recorded durably in agent-run state (see `preamble-extension-conflicts`) in addition to the warning. The delegation spool fills this with its worker contract. |
| `(preamble-extension-conflicts)` | Return the durable record of genuine `set-preamble-extension!` conflicts — a vector of `{:at <iso-instant> :previous <text> :replacement <text>}`, one per re-registration that replaced an existing non-identical value. Identical replays are not recorded. The record survives for the weaver lifetime and across `reload!` (carried through the state migrate hook), so a cross-spool worker-contract clash stays visible to operators and attention detectors after the stderr warning has scrolled away. |
| `(pinned-strand-command)` | Return the fully pinned `strand` invocation prefix. Public accessor the delegation spool consumes to build worker/review prompts. |
| `(set-default-review-contract! text)` / `(default-review-contract-text)` | Hold the workspace-default reviewer contract text as weaver-lifetime state (re-set by startup config like harness aliases); `nil` restores the generic default. The delegation spool's `review` verb consumes this when no explicit contract is passed. |

Interactive runs get their own preamble variant carrying the completion contract (note → goodbye → close the served strand, in that order). It deliberately **excludes** the preamble extension: that seam carries the delegated-worker contract ("never close your assigned strand"), which is the opposite of the interactive contract where closing the served strand is how the session ends.

## 8. Attribute vocabulary

| Attribute | Meaning |
|---|---|
| `agent-run/run` | String `"true"` marks a strand as an agent-run run. |
| `agent-run/harness` | Harness or alias name. |
| `agent-run/prompt` | Prompt/script sent to the harness. |
| `agent-run/phase` | `pending`, `running`, `done`, `failed`, `exhausted`, or `superseded`. |
| `agent-run/attempt` | Crash-recovery launch attempt count. |
| `agent-run/max-attempts` | Optional maximum attempts before exhaustion; defaults to `3`. |
| `agent-run/result` | Final captured agent result on success; always non-blank (a blank result is failed, not done). |
| `agent-run/error` | Failure detail when phase is `failed` or `exhausted`. A headless exit 0 with an empty result reads `harness exited 0 with an empty result`; a harness-reported turn error reads `harness exited 0 but the final turn errored: <message>`. Both append a stderr tail when one exists. |
| `agent-run/exit-code` | Process exit code of a headless run, recorded on every outcome that observed one: `done`, the exit-0 failures (blank result, harness-reported turn error), and non-zero-exit failure. Absent when no process reported a code (interactive death, launch exception, kill). |
| `agent-run/session-id` | Harness session id when parsed from harness output. |
| `agent-run/usage-source` | Which parser produced the usage tally: `claude-json`, `pi-json`, or `codex-json`. A `codex-json` cost is derived from a `:cost-rates` card, not reported by the provider. |
| `agent-run/cost-usd` | Run cost in USD, when the parser reported one or a `:cost-rates` card derived it. Absent when neither applies — never a stored 0. |
| `agent-run/tokens-total` | Harness's own total token count for the run. Absent when the harness reported none. |
| `agent-run/tokens` | Token breakdown map (`:input`, `:output`, `:cache-read`, `:cache-write`, `:reasoning`) carrying only the dimensions the run actually spent; a reported zero is dropped, never stored. |
| `agent-run/resumes` | Predecessor run id whose harness session this run continues (also carried as a `resumes` annotation edge). |
| `agent-run/error-class` | `resume` on a failure that resolving/continuing a session caused, so recovery can branch to a fresh spawn instead of retrying against a lost session. |
| `agent-run/recovered-at` | Timestamp set when crash reconciliation returned a headless orphan to `pending`; missing harness aliases on these recovery-origin retries defer back to `pending` only inside the bounded recovery window. |
| `agent-run/recovery-deferred-until` | Timestamp for the next quiet retry after a recovered run hit an unregistered harness alias. |
| `agent-run/log` | Path to captured stdout log under the weaver state dir. |
| `agent-run/pid` | Live process pid recorded after launch. |
| `agent-run/pid-started-at` | OS process start instant used to avoid signalling recycled pids. |
| `agent-run/started-at` / `agent-run/finished-at` | Run timing metadata. |
| `agent-run/spawned-by` | Parent run id for provenance. |
| `agent-run/supersedes` | Predecessor run id superseded by this successor; mirrored by a `supersedes` edge from successor to predecessor. |
| `agent-run/cwd` | Optional working directory override. |
| `agent-run/mode` | `interactive` marks a session run; absent means headless. |
| `agent-run/backend` | Backend name for an interactive run. |
| `agent-run/completion` | `claim` (completes when `agent-run/for` closes) or `manual-close` (completes when the run strand is closed). |
| `agent-run/for` | The strand a claim-completion interactive run serves. |
| `agent-run/reap` | `auto` (default) or `manual` session teardown policy on completion. |
| `agent-run/session` | Workspace-namespaced suggested session name; the probe/cleanup anchor when a crash predates the handle write. |
| `agent-run/handle.<key>` | Durable backend handle entries returned by `:start` (e.g. `handle.session`, `handle.pane`). |
| `agent-run/teardown-error` | Recorded when backend stop failed after completion; never blocks the close. |
| `note/text`, `note/at` | Note body and sub-second timestamp; storage-enforced write-once. Linkage to the target is the `notes` edge, not a `note/for` attribute. |
| `note/by`, `note/round` | Optional author run id and council round on a note. |

The `pending → running → done | failed | exhausted` transitions are written by the engine. The terminal `superseded` phase is written by `supersede-and-respawn!`;
a superseded run's logs and notes remain for archaeology.

Run parents are connected to children with `parent-of` edges. Serving runs connect to their targets with `serves` edges. Lineage uses `supersedes` edges.
Notes use the declared `notes` relation.

## 9. See also

- [delegation/README.md](../delegation/README.md) — the `strand agent` verb surface, delegation, and coordinator/worker guidance layered over this engine.
- [executors/subagent.md](../executors/subagent.md) — shipped adapter that bridges workflow `:subagent` gates to agent-run runs.
- `test/skein/agent_run_test.clj` — executable coverage for harnesses, readiness, failures, notes, and reconciliation.
- [Runtime spool workspace helpers](../../devflow/specs/repl-api.md#spec-003p5-runtime-spool-workspace-helpers) — approved local-root loading contract.
- [Weaver Runtime](../../devflow/specs/daemon-runtime.md) — event handlers, CLI operation registry, JSON socket transport, and runtime reload behavior.
