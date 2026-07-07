# Table of contents
-  [`skein.spools.shuttle`](#skein.spools.shuttle)  - Userland spool that spawns coding agents in user-chosen harnesses.
    -  [`*runtime*`](#skein.spools.shuttle/*runtime*) - Runtime captured for asynchronous shuttle worker threads.
    -  [`await-runs`](#skein.spools.shuttle/await-runs) - Block until every id is terminal (closed, failed, or exhausted) or <code>timeout-secs</code> (default 300) elapses.
    -  [`backends`](#skein.spools.shuttle/backends) - Return registered backend metadata ordered by name.
    -  [`capture!`](#skein.spools.shuttle/capture!) - Capture an interactive run's transcript right now, persist it as the run's <code>shuttle/log</code>, and return {:id :path :text}.
    -  [`defalias!`](#skein.spools.shuttle/defalias!) - Register <code>name</code> as an alias layered over another harness or alias.
    -  [`default-review-contract-text`](#skein.spools.shuttle/default-review-contract-text) - Return the effective workspace review contract text.
    -  [`defbackend!`](#skein.spools.shuttle/defbackend!) - Register an interactive session backend under <code>name</code>.
    -  [`defharness!`](#skein.spools.shuttle/defharness!) - Register a harness definition under <code>name</code>.
    -  [`generic-review-contract`](#skein.spools.shuttle/generic-review-contract) - Default contract text for independent shuttle reviews.
    -  [`harnesses`](#skein.spools.shuttle/harnesses) - Return registered harness and alias metadata ordered by name.
    -  [`in-flight-run-ids`](#skein.spools.shuttle/in-flight-run-ids) - Return the set of run ids the shuttle is currently tracking in-flight (claimed, running, or awaiting recovery).
    -  [`install!`](#skein.spools.shuttle/install!) - Install the shuttle into the active weaver: default harnesses, the graph event listener, crash reconciliation, and a first scan.
    -  [`kill!`](#skein.spools.shuttle/kill!) - Kill a run's harness process (or interactive session) and mark it failed.
    -  [`note!`](#skein.spools.shuttle/note!) - Append an immutable note strand to <code>target-id</code>'s memory.
    -  [`notes`](#skein.spools.shuttle/notes) - Return <code>target-id</code>'s notes in creation order, optionally one <code>:round</code>.
    -  [`on-event`](#skein.spools.shuttle/on-event) - Weaver event handler: any graph mutation may unblock a pending run or complete the strand an interactive session serves.
    -  [`pinned-strand-command`](#skein.spools.shuttle/pinned-strand-command) - Return the fully pinned strand invocation prefix for spawned agents.
    -  [`preamble-extension-conflicts`](#skein.spools.shuttle/preamble-extension-conflicts) - Return the durable record of genuine set-preamble-extension! conflicts.
    -  [`reconcile!`](#skein.spools.shuttle/reconcile!) - Recover running runs whose owning weaver died.
    -  [`register-default-backends!`](#skein.spools.shuttle/register-default-backends!) - Register the shipped tmux backend, keeping any existing entries.
    -  [`register-default-harnesses!`](#skein.spools.shuttle/register-default-harnesses!) - Register the shipped harness definitions, keeping any existing entries.
    -  [`resolve-backend`](#skein.spools.shuttle/resolve-backend) - Return the backend definition registered under <code>name</code>; fails loudly.
    -  [`resolve-harness`](#skein.spools.shuttle/resolve-harness) - Return the effective harness definition for <code>name</code>, flattening alias layers.
    -  [`run-query`](#skein.spools.shuttle/run-query) - Query form selecting all shuttle run strands.
    -  [`run-summary`](#skein.spools.shuttle/run-summary) - Project a run strand into the compact summary shape the op surface returns.
    -  [`runs`](#skein.spools.shuttle/runs) - Return summaries of shuttle runs; opts may filter to <code>:active</code> or <code>:for</code>.
    -  [`scan!`](#skein.spools.shuttle/scan!) - Spawn every ready pending run not already claimed.
    -  [`set-default-review-contract!`](#skein.spools.shuttle/set-default-review-contract!) - Set the workspace default review contract text; nil restores the generic one.
    -  [`set-preamble-extension!`](#skein.spools.shuttle/set-preamble-extension!) - Register additional preamble text appended after shuttle's engine contract.
    -  [`spawn-run!`](#skein.spools.shuttle/spawn-run!) - Create one agent-run strand; the engine spawns it when it becomes ready.
    -  [`supervise!`](#skein.spools.shuttle/supervise!) - Advance every interactive run in phase running: reap completed ones, fail dead sessions.

-----
# <a name="skein.spools.shuttle">skein.spools.shuttle</a>


Userland spool that spawns coding agents in user-chosen harnesses.

  An agent run is a strand carrying `shuttle/*` attributes; creating the strand
  is the API. The shuttle listens for graph mutations, spawns each pending run
  the moment its strand becomes ready (`depends-on` readiness is the only
  scheduler), captures the harness process output back onto the run strand, and
  closes the strand so dependent runs unblock. Everything is asynchronous by
  default; `await` is the opt-in blocking convenience.

  Runs survive weaver crashes because the strands are durable: `reconcile!`
  respawns still-active running strands on install, bounded by
  `shuttle/max-attempts`. Run memory is append-only note strands linked by
  `notes` annotation edges plus `shuttle/note-for` attributes.

  A run may continue a predecessor's harness session: `spawn-run!` accepts
  `:resume <predecessor-run-id>`, and a harness def declares a `:resume` argv
  splice (keyword placeholders resolve from the predecessor's captured
  attributes) that the engine splices in before the prompt. Continuation is
  recorded on the graph (`shuttle/resumes` plus a `resumes` annotation edge);
  a lost session fails loudly, classed `shuttle/error-class "resume"`, so
  recovery deliberately branches to a fresh spawn rather than silently starting
  cold.

  Interactive runs are the second execution mode: instead of exec-and-wait, the
  engine launches the harness into a user-registered multiplexer backend
  (tmux by default) and supervises it through the graph — the run completes
  when the strand it serves closes (claims model), not when a process exits.
  Backends are data-first argv definitions (`defbackend!`) whose `:start` op
  returns a durable handle stored as `shuttle/handle.*` attributes, so
  sessions survive weaver restarts and are adopted, never respawned.

  The whole spool composes public surfaces (`skein.api.weaver.alpha` inside the
  weaver JVM) and owns no privileged runtime state. Higher-level spools, such as
  `skein.spools.agents`, register CLI operations over this engine.




## <a name="skein.spools.shuttle/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous shuttle worker threads.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L54-L56">Source</a></sub></p>

## <a name="skein.spools.shuttle/await-runs">`await-runs`</a>
``` clojure
(await-runs ids)
(await-runs ids {:keys [timeout-secs], :or {timeout-secs 300}})
```
Function.

Block until every id is terminal (closed, failed, or exhausted) or
  `timeout-secs` (default 300) elapses. Returns run summaries plus :timed-out.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1556-L1573">Source</a></sub></p>

## <a name="skein.spools.shuttle/backends">`backends`</a>
``` clojure
(backends)
```
Function.

Return registered backend metadata ordered by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L425-L434">Source</a></sub></p>

## <a name="skein.spools.shuttle/capture!">`capture!`</a>
``` clojure
(capture! id)
```
Function.

Capture an interactive run's transcript right now, persist it as the run's
  `shuttle/log`, and return {:id :path :text}. Works on live runs (a
  coordinator peek without attaching) and, when the harness capture source
  outlives the session (hook-written logs), on finished runs too. Fails
  loudly when the run is not interactive or no capture op is configured.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1607-L1620">Source</a></sub></p>

## <a name="skein.spools.shuttle/defalias!">`defalias!`</a>
``` clojure
(defalias! name def)
```
Function.

Register `name` as an alias layered over another harness or alias.

  Alias defs take `:alias-of` (required), `:extra-args` appended to the base
  argv before the prompt, `:prompt-prefix` prepended to the run prompt, and
  `:doc`. Aliases are how userland exposes its own named agent types.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L312-L327">Source</a></sub></p>

## <a name="skein.spools.shuttle/default-review-contract-text">`default-review-contract-text`</a>
``` clojure
(default-review-contract-text)
```
Function.

Return the effective workspace review contract text.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1680-L1683">Source</a></sub></p>

## <a name="skein.spools.shuttle/defbackend!">`defbackend!`</a>
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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L392-L416">Source</a></sub></p>

## <a name="skein.spools.shuttle/defharness!">`defharness!`</a>
``` clojure
(defharness! name def)
```
Function.

Register a harness definition under `name`.

  A harness def is plain data: required `:argv` (vector of strings; the run
  prompt is appended per `:prompt-via`, default `:arg`), optional `:parse`
  strategy (:raw, :claude-json, :pi-json — default :raw), `:prompt-via`
  (:arg or :stdin), `:preamble?` (default true; when false the shuttle run
  preamble is not injected), `:env` map, `:cwd`, `:doc`, `:resume` — an argv
  splice of literal strings and placeholder keywords (from the closed
  `resume-placeholder-inputs` set) that continues a predecessor's session, each
  placeholder resolving from that predecessor run's captured attributes at
  launch — and `:capture` — a
  spliced argv (interactive runs only) that prints this harness's best
  transcript text to stdout, overriding the backend's scrollback capture.
  Harness capture is the seam for harness-aware transcripts (session logs,
  user hook-written dialogue logs) without the engine knowing any harness's
  log format; correlate via the SKEIN_RUN_ID env var every session exports.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L274-L310">Source</a></sub></p>

## <a name="skein.spools.shuttle/generic-review-contract">`generic-review-contract`</a>




Default contract text for independent shuttle reviews.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1666-L1668">Source</a></sub></p>

## <a name="skein.spools.shuttle/harnesses">`harnesses`</a>
``` clojure
(harnesses)
```
Function.

Return registered harness and alias metadata ordered by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L352-L361">Source</a></sub></p>

## <a name="skein.spools.shuttle/in-flight-run-ids">`in-flight-run-ids`</a>
``` clojure
(in-flight-run-ids)
```
Function.

Return the set of run ids the shuttle is currently tracking in-flight
  (claimed, running, or awaiting recovery).

  Attention detectors use this to tell a genuinely parked ready run — one that
  scan! should have launched but did not — from one already in flight.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L160-L167">Source</a></sub></p>

## <a name="skein.spools.shuttle/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the shuttle into the active weaver: default harnesses, the graph
  event listener, crash reconciliation, and a first scan.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1688-L1705">Source</a></sub></p>

## <a name="skein.spools.shuttle/kill!">`kill!`</a>
``` clojure
(kill! id)
```
Function.

Kill a run's harness process (or interactive session) and mark it failed.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1575-L1605">Source</a></sub></p>

## <a name="skein.spools.shuttle/note!">`note!`</a>
``` clojure
(note! target-id text)
(note! target-id text {:keys [by round]})
```
Function.

Append an immutable note strand to `target-id`'s memory.

  The note is born closed (it is memory, not work), carries
  `shuttle/note-for`, optional `shuttle/note-by` and `shuttle/round`
  attributes, and a `notes` annotation edge to the target.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1622-L1645">Source</a></sub></p>

## <a name="skein.spools.shuttle/notes">`notes`</a>
``` clojure
(notes target-id)
(notes target-id {:keys [round]})
```
Function.

Return `target-id`'s notes in creation order, optionally one `:round`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1647-L1661">Source</a></sub></p>

## <a name="skein.spools.shuttle/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: any graph mutation may unblock a pending run or
  complete the strand an interactive session serves.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1248-L1253">Source</a></sub></p>

## <a name="skein.spools.shuttle/pinned-strand-command">`pinned-strand-command`</a>
``` clojure
(pinned-strand-command)
```
Function.

Return the fully pinned strand invocation prefix for spawned agents.

  Harness shells may re-source user dotfiles and override ambient env, so the
  state root that selects the mill/weaver must ride inside the command text,
  not the inherited environment.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L552-L559">Source</a></sub></p>

## <a name="skein.spools.shuttle/preamble-extension-conflicts">`preamble-extension-conflicts`</a>
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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L591-L601">Source</a></sub></p>

## <a name="skein.spools.shuttle/reconcile!">`reconcile!`</a>
``` clojure
(reconcile!)
```
Function.

Recover running runs whose owning weaver died.

  Headless: any active `running` run this weaver has no in-flight handle for
  was owned by a dead predecessor: its stale process is killed when its
  identity can be verified (pid plus recorded start instant), then the run is
  either reset to `pending` for respawn or marked `exhausted` (loudly, still
  active so dependents stay blocked) when `shuttle/max-attempts` is spent.

  Interactive: sessions survive the weaver by design, so orphans are adopted,
  never respawned — a live session keeps its run `running` from durable
  handle attributes; a dead one is reaped as done when its target already
  closed (completion wins), otherwise failed loudly regardless of attempts
  (auto-respawn would silently discard a human conversation).

  Returns a summary of respawned/exhausted/adopted/reaped/failed run ids.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1273-L1342">Source</a></sub></p>

## <a name="skein.spools.shuttle/register-default-backends!">`register-default-backends!`</a>
``` clojure
(register-default-backends!)
```
Function.

Register the shipped tmux backend, keeping any existing entries.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L436-L451">Source</a></sub></p>

## <a name="skein.spools.shuttle/register-default-harnesses!">`register-default-harnesses!`</a>
``` clojure
(register-default-harnesses!)
```
Function.

Register the shipped harness definitions, keeping any existing entries.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L363-L384">Source</a></sub></p>

## <a name="skein.spools.shuttle/resolve-backend">`resolve-backend`</a>
``` clojure
(resolve-backend name)
```
Function.

Return the backend definition registered under `name`; fails loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L418-L423">Source</a></sub></p>

## <a name="skein.spools.shuttle/resolve-harness">`resolve-harness`</a>
``` clojure
(resolve-harness name)
```
Function.

Return the effective harness definition for `name`, flattening alias layers.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L329-L350">Source</a></sub></p>

## <a name="skein.spools.shuttle/run-query">`run-query`</a>




Query form selecting all shuttle run strands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L515-L517">Source</a></sub></p>

## <a name="skein.spools.shuttle/run-summary">`run-summary`</a>
``` clojure
(run-summary run)
(run-summary run parents)
```
Function.

Project a run strand into the compact summary shape the op surface returns.

  Pass `parents` (the run's parent-of source ids) to reuse a bulk fetch; when
  omitted a single indexed lookup resolves them.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1489-L1519">Source</a></sub></p>

## <a name="skein.spools.shuttle/runs">`runs`</a>
``` clojure
(runs)
(runs {:keys [active for]})
```
Function.

Return summaries of shuttle runs; opts may filter to `:active` or `:for`.
  Listing doubles as an interactive liveness checkpoint (there is no
  background poller): dead sessions are failed here, best-effort.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1521-L1548">Source</a></sub></p>

## <a name="skein.spools.shuttle/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Spawn every ready pending run not already claimed. Returns claimed run ids.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1237-L1246">Source</a></sub></p>

## <a name="skein.spools.shuttle/set-default-review-contract!">`set-default-review-contract!`</a>
``` clojure
(set-default-review-contract! text)
```
Function.

Set the workspace default review contract text; nil restores the generic one.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1672-L1678">Source</a></sub></p>

## <a name="skein.spools.shuttle/set-preamble-extension!">`set-preamble-extension!`</a>
``` clojure
(set-preamble-extension! text)
```
Function.

Register additional preamble text appended after shuttle's engine contract.

  Reload-tolerant, but it distinguishes the two cases the previous fail-loud path
  could not tell apart:

  - Replay (same spool re-running its own registration on `reload!`): identical
    text is a silent no-op (`:replaced false`).
  - Conflict (a second, distinct registrant clashing on the worker contract):
    different text replaces the value AND is recorded durably in the shuttle's
    `:preamble-conflicts` state (see `preamble-extension-conflicts`), plus a
    stderr warning. It deliberately does not fail: a hard error here would abort a
    `reload!` mid-startup and leave the world with zero ops (the original
    incident), which is worse than a recorded clash. The durable record is the
    fail-loud substitute — an operator/detector can see the conflict long after
    the stderr line has scrolled away, unlike the prior stderr-only signal.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L563-L589">Source</a></sub></p>

## <a name="skein.spools.shuttle/spawn-run!">`spawn-run!`</a>
``` clojure
(spawn-run! {:keys [harness prompt title depends-on parent spawned-by cwd max-attempts attrs mode backend reap resume]})
```
Function.

Create one agent-run strand; the engine spawns it when it becomes ready.

  Opts: `:harness` and `:prompt` required; optional `:title`, `:depends-on`
  (vector of strand ids), `:parent` and `:spawned-by` (each gets a parent-of
  edge to the run), `:cwd`, `:max-attempts`, and extra `:attrs`. Interactive
  sessions pass `:mode :interactive` with a required `:backend`, and
  optionally `:reap` (`auto` tears the session down on completion, `manual`
  leaves it to the human; default auto). An interactive run with a `:parent`
  completes when that strand closes (claim); without one it completes when
  its own run strand is closed (manual-close).

  `:resume <predecessor-run-id>` continues the predecessor's harness session:
  the run is stamped `shuttle/resumes` with a `resumes` annotation edge, and at
  launch the harness `:resume` splice resolves from the predecessor's captured
  attributes ahead of the prompt (see `validate-resume!` for the loud rules).
  Asynchronous: returns the created run strand immediately.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1384-L1447">Source</a></sub></p>

## <a name="skein.spools.shuttle/supervise!">`supervise!`</a>
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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/shuttle.clj#L1075-L1117">Source</a></sub></p>
