
-----
# <a name="skein.spools.roster">skein.spools.roster</a>


Active-work registry: `roster/*` attribute vocabulary plus explicit-runtime
  `track!`/`heartbeat!`/`finish!`/`roster`/`await-quiet!` helpers over ordinary
  strands, installed as the declared-subcommand `roster` op and a named
  `roster` query.

  A roster entry is an active strand marked `roster/entry` "true" with a
  required `roster/feature` and `roster/owner`. `track!` either creates a new
  entry strand or restamps an explicitly supplied existing strand in place
  (preserving its other attributes), so callers can bring a workflow/devflow
  root under roster tracking without losing its identity. Every public model
  function here takes `runtime` as its first argument and never resolves
  ambient runtime itself (see SPEC-RosterSpool-001 P4/P5 and
  docs/spools/writing-shared-spools.md's explicit-runtime pattern), so this spool
  composes safely across published daemons, test runtimes, and side-by-side
  worlds; only `install!` resolves the active runtime, at the activation
  boundary used by other shipped spools, and CLI op handlers read `:op/runtime`
  from their invocation context rather than resolving it themselves.

  The public seam input shapes are declared as `clojure.spec` specs
  (`::track-attrs`, `::heartbeat-opts`, `::finish-opts`, `::roster-opts`,
  `::await-quiet-opts`, and
  their field predicates) as the discoverable/reusable source of truth,
  matching sibling spools; each fn layers manual checks for what s/keys cannot
  express (closed key sets and track!'s id-derivable feature/owner).




## <a name="skein.spools.roster/about">`about`</a>
``` clojure
(about)
```
Function.

Return the roster convention and installed helper surface.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L571-L613">Source</a></sub></p>

## <a name="skein.spools.roster/await-quiet!">`await-quiet!`</a>
``` clojure
(await-quiet! runtime opts)
```
Function.

Block until the selected scope has no active non-stale entries.

  `opts` accepts `:feature`, `:branch`, `:worktree`, `:timeout-ms` (default
  thirty minutes), `:stale-after-ms` (default fifteen minutes), and `:poll-ms`
  (default fifty). Returns `{:reason :quiet|:stale|:timeout :entries [...]}`:
  `:stale` short-circuits as soon as any selected entry exceeds the stale
  threshold (checked before waiting further), `:quiet` when the scope has no
  active entries, and `:timeout` when neither happens before the deadline.
  `:entries` is whatever `roster` returned for the scope at the decision
  point. Fails loudly for a malformed opts map, unknown keys, or a negative
  `:timeout-ms`/`:poll-ms`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L375-L403">Source</a></sub></p>

## <a name="skein.spools.roster/default-stale-after-ms">`default-stale-after-ms`</a>




Default staleness threshold for `roster`/`await-quiet!`: fifteen minutes.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L37-L39">Source</a></sub></p>

## <a name="skein.spools.roster/default-timeout-ms">`default-timeout-ms`</a>




Default `await-quiet!` timeout: thirty minutes, matching `workflow/await!`'s
  long-poll default.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L349-L352">Source</a></sub></p>

## <a name="skein.spools.roster/finish!">`finish!`</a>
``` clojure
(finish! runtime entry-id opts)
```
Function.

Close an active roster entry with a final `roster/status`.

  `opts` is a map: `:status` (`"finished"` default, or `"abandoned"`),
  optional `:result` outcome string, and `:now` override. Records
  `roster/status`, `roster/finished-at`, and optional `roster/result`, then
  closes the strand. Sends only that delta (not the whole attribute snapshot)
  so a concurrent auto-heartbeat cannot roll the final status back
  (SPEC-RosterSpool-001.C4). Fails loudly for a missing, closed, or non-roster
  entry id, an unrecognized status, or malformed opts.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L249-L273">Source</a></sub></p>

## <a name="skein.spools.roster/handle-mutation-event!">`handle-mutation-event!`</a>
``` clojure
(handle-mutation-event! runtime event)
```
Function.

Roster's async graph-integration handler (explicit-runtime core).

  For every strand add/update it either (a) restamps a sufficient, unstamped
  graph-root strand into a roster entry, or (b) refreshes the heartbeat of the
  active roster entry that roots the touched strand's `parent-of` ancestry, so
  graph-tracked workflow/devflow flows stay fresh without an explicit
  `heartbeat!`. Roster's own bookkeeping writes are ignored to avoid feedback
  loops. Exceptions are left to surface on the weaver event-failure surface.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L528-L546">Source</a></sub></p>

## <a name="skein.spools.roster/heartbeat!">`heartbeat!`</a>
``` clojure
(heartbeat! runtime entry-id & [opts])
```
Function.

Update `roster/heartbeat-at` on an active roster entry.

  Refuses loudly for a missing, closed/finished, or non-roster entry id: a
  finished entry is never re-heartbeated. `opts` accepts `:now` to override the
  recorded instant for deterministic callers/tests. Sends only the
  `roster/heartbeat-at` delta (not the whole attribute snapshot), so a heartbeat
  that commits just after a concurrent `finish!` can only advance the timestamp
  on the already-closed entry — it can never resurrect `roster/status` to
  "active" (SPEC-RosterSpool-001.C4). Fails loudly for non-map opts or
  unknown opt keys.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L183-L201">Source</a></sub></p>

## <a name="skein.spools.roster/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the roster op and named query into the active weaver.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L798-L817">Source</a></sub></p>

## <a name="skein.spools.roster/mutation-handler">`mutation-handler`</a>
``` clojure
(mutation-handler event)
```
Function.

Registered event-handler entry point: dispatches to
  `handle-mutation-event!` under the runtime the event worker bound for this
  delivery.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L548-L553">Source</a></sub></p>

## <a name="skein.spools.roster/prime">`prime`</a>
``` clojure
(prime)
```
Function.

Return the full agent-priming payload for using the roster.

  A superset of `about` — it reuses the same attribute/api/command surface and
  adds the working discipline an agent needs before tracking, heartbeating, or
  finishing roster entries.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L615-L658">Source</a></sub></p>

## <a name="skein.spools.roster/roster">`roster`</a>
``` clojure
(roster runtime opts)
```
Function.

Return active roster entries, optionally scoped by `:feature`, `:owner`,
  `:branch`, `:worktree`, or `:engine`.

  Each row is `{:strand <normalized strand> :stale? bool :age-ms long}`,
  derived against `opts`'s `:stale-after-ms` (default fifteen minutes; must
  be a positive integer when supplied). Sorted by strand id.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L325-L343">Source</a></sub></p>

## <a name="skein.spools.roster/roster-op">`roster-op`</a>
``` clojure
(roster-op ctx)
```
Function.

Dispatch parsed `strand roster ...` subcommands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L711-L721">Source</a></sub></p>

## <a name="skein.spools.roster/track!">`track!`</a>
``` clojure
(track! runtime attrs)
```
Function.

Create or restamp one roster entry.

  `attrs` requires non-blank `:feature` and `:owner` unless `:id` names an
  existing strand that already carries them (as `roster/feature`/`feature`
  and `roster/owner`/`owner`). Supplying `:id` restamps that strand into the
  roster entry in place, merging roster attributes onto whatever it already
  carries and forcing it active; omitting `:id` creates a new entry strand,
  optionally recording `:source-id` as a link back to another strand it
  mirrors. Other optional keys: `:title`, `:body`, `:branch`, `:worktree`,
  `:engine`, `:run-id`, and `:now` (an `Instant` override for deterministic
  callers/tests). Fails loudly for malformed attrs or a missing `:id` strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L113-L157">Source</a></sub></p>

## <a name="skein.spools.roster/watch!">`watch!`</a>
``` clojure
(watch! runtime)
```
Function.

Register roster's async workflow/devflow graph-integration handler on
  strand add/update events.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/roster/src/skein/spools/roster.clj#L558-L565">Source</a></sub></p>
