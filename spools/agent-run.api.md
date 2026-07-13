
-----
# <a name="skein.spools.agent-run">skein.spools.agent-run</a>


Userland spool that spawns coding agents in user-chosen harnesses.

  An agent run is a strand carrying `agent-run/*` attributes; creating the strand
  is the API. The engine listens for graph mutations, spawns each pending run
  the moment its strand becomes ready (`depends-on` readiness is the only
  scheduler), captures the harness process output back onto the run strand, and
  closes the strand so dependent runs unblock. Everything is asynchronous by
  default; `await` is the opt-in blocking convenience.

  Runs survive weaver crashes because the strands are durable: `reconcile!`
  respawns still-active running strands on install, bounded by
  `agent-run/max-attempts`. Run memory is note strands linked by the declared
  `notes` relation — the edge is the sole linkage — whose `note/text`/`note/at`
  content is storage-enforced write-once.

  Delegation semantics ride a `serves` edge (run → target): a serving run is a
  delegation of that target's own work. This is distinct from the `parent-of`
  edge, which only places a run in the graph — a serving run carries both
  (placement plus semantics), a read-only helper (recon spawn, reviewer, panel
  seat) carries `parent-of` alone. `spawn-run!` writes the `serves` edge; the
  read side (`run-summary` `:for`, `runs`, delegation guards) keys serving off
  it rather than inferring it from placement.

  A run may continue a predecessor's harness session: `spawn-run!` accepts
  `:resume <predecessor-run-id>`, and a harness def declares a `:resume` argv
  splice (keyword placeholders resolve from the predecessor's captured
  attributes) that the engine splices in before the prompt. Continuation is
  recorded on the graph (`agent-run/resumes` plus a `resumes` annotation edge);
  a lost session fails loudly, classed `agent-run/error-class "resume"`, so
  recovery deliberately branches to a fresh spawn rather than silently starting
  cold.

  A dead run is succeeded — never mutated in place — through the one primitive
  `supersede-and-respawn!`: it mints a fresh successor preserving the
  predecessor's `serves` target, `depends-on` edges, `spawned-by` provenance,
  and execution shape, closes the predecessor `agent-run/phase "superseded"`,
  and records lineage as a `supersedes` edge (successor → predecessor) plus an
  `agent-run/supersedes` attr. `runs-serving` resolves the current run for a
  target as the serving run with no incoming `supersedes` edge. Crash-respawn
  (`reconcile!`) and session-carrying resume are the same family read two ways:
  reconcile resets a strand in place so the run id stays stable, and
  `:continuity :resume` layers the resume link onto a supersession — `resumes`
  and `supersedes` stay distinct edges and the resolution rule keys on
  `supersedes` alone.

  Interactive runs are the second execution mode: instead of exec-and-wait, the
  engine launches the harness into a user-registered multiplexer backend
  (tmux by default) and supervises it through the graph — the run completes
  when its interactive completion target (`agent-run/for`) closes (claims
  model), not when a process exits. That interactive `agent-run/for` target is
  the interactive completion signal, separate from the headless `serves`
  delegation edge above and never folded into it.
  Backends are data-first argv definitions (`defbackend!`) whose `:start` op
  returns a durable handle stored as `agent-run/handle.*` attributes, so
  sessions survive weaver restarts and are adopted, never respawned.

  Harnesses (tools) and aliases (seats) live in two independent runtime
  registries: `defharness!` writes one entry per tool (claude, pi, codex, sh),
  `defalias!` writes named seats over them. Resolution is alias-first — an
  unvisited alias shadows a same-named harness, so a seat may carry a tool's own
  name and still terminate at the tool. Re-registration replaces within a
  registry (reload idempotency); across registries names are independent.

  The whole spool composes public surfaces (`skein.api.weaver.alpha` inside the
  weaver JVM) and owns no privileged runtime state. Higher-level spools, such as
  `skein.spools.delegation`, register CLI operations over this engine.




## <a name="skein.spools.agent-run/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous engine worker threads.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L91-L93">Source</a></sub></p>

## <a name="skein.spools.agent-run/await-runs">`await-runs`</a>
``` clojure
(await-runs ids)
(await-runs ids {:keys [timeout-secs], :or {timeout-secs 300}})
```
Function.

Block until every id is terminal (closed, failed, or exhausted) or
  `timeout-secs` (default 300) elapses. Returns run summaries plus :timed-out.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2269-L2286">Source</a></sub></p>

## <a name="skein.spools.agent-run/backends">`backends`</a>
``` clojure
(backends)
```
Function.

Return registered backend metadata ordered by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L623-L632">Source</a></sub></p>

## <a name="skein.spools.agent-run/capture!">`capture!`</a>
``` clojure
(capture! id)
```
Function.

Capture an interactive run's transcript right now, persist it as the run's
  `agent-run/log`, and return {:id :path :text}. Works on live runs (a
  coordinator peek without attaching) and, when the harness capture source
  outlives the session (hook-written logs), on finished runs too. Fails
  loudly when the run is not interactive or no capture op is configured.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2320-L2333">Source</a></sub></p>

## <a name="skein.spools.agent-run/defalias!">`defalias!`</a>
``` clojure
(defalias! name def)
```
Function.

Register `name` as an alias (seat) layered over another harness or alias.

  Alias defs take `:alias-of` (required), `:extra-args` appended to the base
  argv before the prompt, `:prompt-prefix` prepended to the run prompt, `:doc`,
  and `:cost-rates` — a seat-level rate card (same shape as `defharness!`) that
  overrides the tool's own card, since a seat is where the model is chosen.
  Aliases are how userland exposes its own named agent seats.

  Writes only the alias (seat) registry, independent of the harness registry, so
  a seat may intentionally carry a tool's name — `defalias! :pi {:alias-of :pi}`
  is a lawful shadow that resolves through the seat and terminates at the tool.
  The def shape is the `::alias-def` spec.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L426-L448">Source</a></sub></p>

## <a name="skein.spools.agent-run/default-review-contract-text">`default-review-contract-text`</a>
``` clojure
(default-review-contract-text)
```
Function.

Return the effective workspace review contract text.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2376-L2379">Source</a></sub></p>

## <a name="skein.spools.agent-run/defbackend!">`defbackend!`</a>
``` clojure
(defbackend! name def)
```
Function.

Register an interactive session backend under `name`.

  A backend def is plain data: required `:start`, `:alive`, and `:stop` argv
  vectors, optional `:capture` (scrollback forensics before teardown),
  `:attach` (display-only hint rendered for humans, never executed), and
  `:doc`. Argv tokens are literal strings, engine-input keywords (`:session`,
  `:cwd`, `:command`, `:run-id` — `:start` only, except `:run-id`), or
  `:handle/<key>` lookups into the handle `:start` returned. `:start` must
  print one flat JSON object of strings as its last stdout line (empty output
  means `{}`); that handle is stored durably on the run strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L590-L614">Source</a></sub></p>

## <a name="skein.spools.agent-run/defharness!">`defharness!`</a>
``` clojure
(defharness! name def)
```
Function.

Register a harness definition under `name`.

  A harness def is plain data: required `:argv` (vector of strings; the run
  prompt is appended per `:prompt-via`, default `:arg`), optional `:parse`
  strategy (:raw, :claude-json, :pi-json, :codex-json — default :raw),
  `:prompt-via` (:arg or :stdin), `:preamble?` (default true; when false the run
  preamble is not injected), `:env` map, `:cwd`, `:doc`, `:cost-rates` — a rate
  card `{:input :cache-read :output}` of USD per 1M tokens that derives
  `agent-run/cost-usd` from a token-only tally (a parser like :codex-json that
  reports tokens but no dollar cost); absent rates leave cost absent rather than
  guessed — `:resume` — an argv
  splice of literal strings and placeholder keywords (from the closed
  `resume-placeholder-inputs` set) that continues a predecessor's session, each
  placeholder resolving from that predecessor run's captured attributes at
  launch — and `:capture` — a
  spliced argv (interactive runs only) that prints this harness's best
  transcript text to stdout, overriding the backend's scrollback capture.
  Harness capture is the seam for harness-aware transcripts (session logs,
  user hook-written dialogue logs) without the engine knowing any harness's
  log format; correlate via the SKEIN_RUN_ID env var every session exports.

  Writes only the harness (tool) registry; a same-named seat may coexist in the
  alias registry and shadows this tool at resolution time. The def shape is the
  `::harness-def` spec; `:capture`/`:resume` splice semantics keep their
  dedicated validators.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L385-L424">Source</a></sub></p>

## <a name="skein.spools.agent-run/generic-review-contract">`generic-review-contract`</a>




Default contract text for independent agent-run reviews.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2358-L2364">Source</a></sub></p>

## <a name="skein.spools.agent-run/harnesses">`harnesses`</a>
``` clojure
(harnesses)
```
Function.

Return registered harness and alias metadata ordered by name.

  The result is the concatenation of both registries' entries sorted by name,
  never merged by name — a same-named tool and seat both appear, distinguished
  by `:kind`. Alias entries carry `:harness` (the resolved root harness name)
  and that root's doc as `:harness-doc` beside their own `:doc`, so one listing
  shows tool-level capabilities together with seat-level capabilities without
  callers re-walking alias chains. Root resolution is best-effort: a broken
  chain omits the `:harness`/`:harness-doc` keys rather than failing the
  listing.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L514-L543">Source</a></sub></p>

## <a name="skein.spools.agent-run/in-flight-run-ids">`in-flight-run-ids`</a>
``` clojure
(in-flight-run-ids)
```
Function.

Return the set of run ids the engine is currently tracking in-flight
  (claimed, running, or awaiting recovery).

  Attention detectors use this to tell a genuinely parked ready run — one that
  scan! should have launched but did not — from one already in flight.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L227-L234">Source</a></sub></p>

## <a name="skein.spools.agent-run/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the agent-run engine into the active weaver: default harnesses, the graph
  event listener, crash reconciliation, and a first scan, and declare the
  `agent-run/*` attribute-namespace vocabulary this spool owns.

  The declaration includes the usage attributes written when parsed runs finish:
  `agent-run/cost-usd`, `agent-run/tokens-total`, `agent-run/tokens`, and
  `agent-run/usage-source`. Pi JSON folds per-message usage deltas; Claude JSON
  reads the final result object's usage fields; raw output records no cost or
  token attributes.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2384-L2414">Source</a></sub></p>

## <a name="skein.spools.agent-run/kill!">`kill!`</a>
``` clojure
(kill! id)
```
Function.

Kill a run's harness process (or interactive session) and mark it failed.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2288-L2318">Source</a></sub></p>

## <a name="skein.spools.agent-run/note!">`note!`</a>
``` clojure
(note! target-id text)
(note! target-id text opts)
```
Function.

Append a note strand to `target-id`'s memory via the blessed
  `skein.api.notes.alpha/note!`, threading this spool's runtime.

  The note is born closed (memory, not work), linked to the target by a `notes`
  edge alone — no `note/for` attribute — and carries optional `note/by`/`note/round`.
  Its `note/text`/`note/at` content is storage-enforced write-once; the strand
  stays open to decorating attrs.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2335-L2344">Source</a></sub></p>

## <a name="skein.spools.agent-run/notes">`notes`</a>
``` clojure
(notes target-id)
(notes target-id opts)
```
Function.

Return `target-id`'s notes in `note/at` order, optionally one `:round`, via
  the blessed `skein.api.notes.alpha/notes` threading this spool's runtime.

  Walks the incoming `notes` edges to the target, so it reads every writer's
  notes regardless of decorating attrs.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2346-L2353">Source</a></sub></p>

## <a name="skein.spools.agent-run/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: any graph mutation may unblock a pending run or
  complete the strand an interactive session serves.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L1713-L1718">Source</a></sub></p>

## <a name="skein.spools.agent-run/pinned-strand-command">`pinned-strand-command`</a>
``` clojure
(pinned-strand-command)
```
Function.

Return the fully pinned strand invocation prefix for spawned agents.

  Harness shells may re-source user dotfiles and override ambient env, so the
  state root that selects the mill/weaver must ride inside the command text,
  not the inherited environment.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L757-L764">Source</a></sub></p>

## <a name="skein.spools.agent-run/preamble-extension-conflicts">`preamble-extension-conflicts`</a>
``` clojure
(preamble-extension-conflicts)
```
Function.

Return the durable record of genuine set-preamble-extension! conflicts.

  Each entry is `{:at <iso-instant> :previous <text> :replacement <text>}` for a
  re-registration that replaced an existing, non-identical preamble extension —
  a cross-spool worker-contract clash. Identical replays are not recorded. The
  record survives for the weaver lifetime (and across `reload!`, carried through
  `migrate-state`) so a conflict stays visible to operators and attention
  detectors after the stderr warning has scrolled off.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L796-L806">Source</a></sub></p>

## <a name="skein.spools.agent-run/reconcile!">`reconcile!`</a>
``` clojure
(reconcile!)
```
Function.

Recover running runs whose owning weaver died.

  Headless: any active `running` run this weaver has no in-flight handle for
  was owned by a dead predecessor: its stale process is killed when its
  identity can be verified (pid plus recorded start instant), then the run is
  either reset to `pending` for respawn or marked `exhausted` (loudly, still
  active so dependents stay blocked) when `agent-run/max-attempts` is spent.

  Interactive: sessions survive the weaver by design, so orphans are adopted,
  never respawned — a live session keeps its run `running` from durable
  handle attributes; a dead one is reaped as done when its target already
  closed (completion wins), otherwise failed loudly regardless of attempts
  (auto-respawn would silently discard a human conversation).

  Returns a summary of respawned/exhausted/adopted/reaped/failed run ids.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L1738-L1807">Source</a></sub></p>

## <a name="skein.spools.agent-run/register-default-backends!">`register-default-backends!`</a>
``` clojure
(register-default-backends!)
```
Function.

Register the shipped tmux backend, keeping any existing entries.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L634-L649">Source</a></sub></p>

## <a name="skein.spools.agent-run/register-default-harnesses!">`register-default-harnesses!`</a>
``` clojure
(register-default-harnesses!)
```
Function.

Register the shipped harness definitions, keeping any existing entries.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L545-L582">Source</a></sub></p>

## <a name="skein.spools.agent-run/resolve-backend">`resolve-backend`</a>
``` clojure
(resolve-backend name)
```
Function.

Return the backend definition registered under `name`; fails loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L616-L621">Source</a></sub></p>

## <a name="skein.spools.agent-run/resolve-harness">`resolve-harness`</a>
``` clojure
(resolve-harness name)
```
Function.

Return the effective harness definition for `name`, flattening alias layers.

  Resolution is alias-first: at each hop an unvisited alias shadows a same-named
  harness, so `defalias! :pi {:alias-of :pi}` resolves through the seat and
  terminates at the `pi` tool; otherwise the harness registry answers, otherwise
  the name is missing. A missing name fails `:error-class "harness-not-found"`
  and lists both registries' available names (recovery deferral keys off that
  class). A genuine alias cycle fails with a distinct `:error-class
  "alias-cycle"` so a real configuration bug never masquerades as the
  transient not-found reload race.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L450-L497">Source</a></sub></p>

## <a name="skein.spools.agent-run/run-query">`run-query`</a>




Query form selecting all agent run strands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L713-L715">Source</a></sub></p>

## <a name="skein.spools.agent-run/run-summary">`run-summary`</a>
``` clojure
(run-summary run)
(run-summary run parents)
(run-summary run parents served-target)
```
Function.

Project a run strand into the compact summary shape the op surface returns.

  `:for` resolves serving first: a serving run reads its `serves`-edge target; a
  helper (`parent-of` only) falls back to its structural parent, so `spawn --for
  X` still shows the helper "for X". Pass `parents` (the run's parent-of source
  ids) and `served-target` (its `serves` target) to reuse bulk fetches; when
  omitted single indexed lookups resolve them.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2060-L2094">Source</a></sub></p>

## <a name="skein.spools.agent-run/runs">`runs`</a>
``` clojure
(runs)
(runs {:keys [active], for-target :for})
```
Function.

Return summaries of agent-run runs; opts may filter to `:active` or `:for`.
  Listing doubles as an interactive liveness checkpoint (there is no
  background poller): dead sessions are failed here, best-effort.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2123-L2129">Source</a></sub></p>

## <a name="skein.spools.agent-run/runs-serving">`runs-serving`</a>
``` clojure
(runs-serving target-id)
```
Function.

Runs currently serving strand `target-id`: those with a `serves` edge to it
  that have not been superseded. This is the C5 resolution rule — its unique
  element is the current run serving the target. A run is superseded when it
  carries an incoming `supersedes` edge, equivalently `agent-run/phase
  "superseded"`; `supersede-and-respawn!` writes edge and phase together so the
  two criteria stay in lockstep. Read-only helpers carry `parent-of` placement
  with no `serves` edge, so they never appear here.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L1957-L1971">Source</a></sub></p>

## <a name="skein.spools.agent-run/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Spawn every ready pending run not already claimed. Returns claimed run ids.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L1702-L1711">Source</a></sub></p>

## <a name="skein.spools.agent-run/set-default-review-contract!">`set-default-review-contract!`</a>
``` clojure
(set-default-review-contract! text)
```
Function.

Set the workspace default review contract text; nil restores the generic one.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2368-L2374">Source</a></sub></p>

## <a name="skein.spools.agent-run/set-preamble-extension!">`set-preamble-extension!`</a>
``` clojure
(set-preamble-extension! text)
```
Function.

Register additional preamble text appended after the engine's worker contract.

  Reload-tolerant, but it distinguishes the two cases the previous fail-loud path
  could not tell apart:

  - Replay (same spool re-running its own registration on `reload!`): identical
    text is a silent no-op (`:replaced false`).
  - Conflict (a second, distinct registrant clashing on the worker contract):
    different text replaces the value AND is recorded durably in the engine's
    `:preamble-conflicts` state (see `preamble-extension-conflicts`), plus a
    stderr warning. It deliberately does not fail: a hard error here would abort a
    `reload!` mid-startup and leave the world with zero ops (the original
    incident), which is worse than a recorded clash. The durable record is the
    fail-loud substitute — an operator/detector can see the conflict long after
    the stderr line has scrolled away, unlike the prior stderr-only signal.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L768-L794">Source</a></sub></p>

## <a name="skein.spools.agent-run/spawn-run!">`spawn-run!`</a>
``` clojure
(spawn-run!
 {:keys [harness prompt title depends-on parent spawned-by cwd max-attempts attrs mode backend reap resume serves]})
```
Function.

Create one agent-run strand; the engine spawns it when it becomes ready.

  Opts: `:harness` and `:prompt` required; optional `:title`, `:depends-on`
  (vector of strand ids), `:parent` and `:spawned-by` (each gets a parent-of
  edge placing the run in the graph), `:serves <target-id>` (writes one `serves`
  edge run → target, marking the run a delegation of that target's own work),
  `:cwd`, `:max-attempts`, and extra `:attrs`. Placement and serving are
  orthogonal: a serving run carries both `parent-of` and `serves`, a helper
  carries `parent-of` alone. Interactive sessions pass `:mode :interactive` with
  a required `:backend`, and optionally `:reap` (`auto` tears the session down on
  completion, `manual` leaves it to the human; default auto). An interactive run
  with a `:parent` completes when that strand closes (claim) — its interactive
  completion target `agent-run/for`, distinct from the headless `serves` edge;
  without one it completes when its own run strand is closed (manual-close).

  `:resume <predecessor-run-id>` continues the predecessor's harness session:
  the run is stamped `agent-run/resumes` with a `resumes` annotation edge, and at
  launch the harness `:resume` splice resolves from the predecessor's captured
  attributes ahead of the prompt (see `validate-resume!` for the loud rules).
  Asynchronous: returns the created run strand immediately.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L1849-L1919">Source</a></sub></p>

## <a name="skein.spools.agent-run/spend">`spend`</a>
``` clojure
(spend)
(spend opts)
```
Function.

Aggregate recorded agent-run spend into the C7 read shape (PROP-Ru-001.C7):
  `{:operation "agent-spend", :filters, :totals, :groups, :runs}`. Optional
  opts filter and shape the report:

    :harness   restrict to one harness/alias name
    :since     lower ISO-8601 instant bound on the run's started-at (inclusive)
    :until     upper ISO-8601 instant bound on the run's started-at (inclusive)
    :group-by  :harness (default) or :day (buckets by the started-at date)

  A malformed :since/:until fails loudly naming the value rather than silently
  yielding an empty or skewed window.

  Every run contributes its count and its timestamp-derived duration for every
  format including `:raw`; a run that recorded no cost/tokens contributes null for
  those, and every sum skips nils so a missing figure is never inflated to 0. The
  read costs one bulk query for many runs, never one per run (PROP-Ru-001.R4).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L2243-L2261">Source</a></sub></p>

## <a name="skein.spools.agent-run/supersede-and-respawn!">`supersede-and-respawn!`</a>
``` clojure
(supersede-and-respawn! old-run-id {:keys [prompt harness cwd carry-attrs continuity], :or {continuity :fresh}})
```
Function.

Succeed a dead run `old-run-id` with a fresh successor — the sole succession
  path in the engine (PROP-Aep-001.C4). Preserved from the predecessor, engine
  owned: its `serves` target (the successor now serves it), its structural
  `parent-of` placement (the served target for serving runs, the `--for`
  placement for helpers — a serving run carries both edges, C1), its
  `depends-on` edges, its `spawned-by` provenance (the parent-of placement plus
  the `agent-run/spawned-by` attr), and its execution shape (`agent-run/mode`/
  `backend`/`reap` and the interactive completion target for interactive runs,
  `agent-run/max-attempts` always). `:prompt` and `:harness` come from the
  caller, `:cwd` too (else the predecessor's). `:carry-attrs` layers spool-owned
  structural attrs on top — the primitive stays ignorant of the delegation
  vocabulary.

  The successor is fresh: a new run id and strand, `agent-run/phase "pending"`,
  no execution residue. `:continuity` (default `:fresh`) severs any session;
  `:resume` continues the predecessor's session by stamping `:resume` on the
  spawn (a `resumes` edge plus `agent-run/resumes`, validated by the resume
  machinery at launch), so `resumes` and `supersedes` stay distinct edges.

  Lineage is recorded in the same call: the predecessor is closed
  `agent-run/phase "superseded"`, and the successor gains a `supersedes` edge
  to it (successor --supersedes--> predecessor, the catalog direction) plus an
  `agent-run/supersedes` attr naming it. `runs-serving` keys on the `serves`
  edge and the absence of an incoming `supersedes` edge, which the `superseded`
  phase mirrors because this call writes edge and phase together. Returns the
  successor run strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L1982-L2043">Source</a></sub></p>

## <a name="skein.spools.agent-run/supervise!">`supervise!`</a>
``` clojure
(supervise!)
```
Function.

Advance every interactive run in phase running: reap completed ones, fail
  dead sessions. Completion wins races — graph completion is checked before
  and after the liveness probe, so an agent that closes its target and exits
  in the same instant is reaped as done, not failed. This runs on graph
  events and inspection calls; the weaver deliberately has no timers, so
  there is no background poller. Returns {:reaped [..] :failed [..]}.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/agent_run.clj#L1523-L1565">Source</a></sub></p>
