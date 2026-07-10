
-----
# <a name="skein.spools.executors.shell">skein.spools.executors.shell</a>


Fulfil workflow `:shell` gates by running their command off the event thread.

  The shell executor watches workflow runs for ready gates whose waiter is
  `:shell`, runs the gate's `shell/argv` directly (no implicit shell) on a
  spool-owned worker pool, and closes the gate through
  `skein.spools.workflow/complete!` on a zero exit. A non-zero exit, timeout,
  spawn error, or invalid argv stamps a loud, distinct `shell/error` and leaves
  the gate ready and stamped rather than masquerading as a completed run. It is
  a subagent-executor sibling minus everything agent-run-specific: the failure
  detail lives on the gate itself, so there is no separate run strand, no
  `delegates` edge, and no session/harness vocabulary. This namespace is the
  only adapter that knows both the workflow gate contract and process
  execution.




## <a name="skein.spools.executors.shell/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous shell-executor worker threads.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/executors/shell.clj#L48-L50">Source</a></sub></p>

## <a name="skein.spools.executors.shell/gate-stalled?">`gate-stalled?`</a>
``` clojure
(gate-stalled? gate-view)
```
Function.

Return durable stall detail for a ready `:shell` gate view, or nil.

  The failure detail lives on the gate itself (`shell/error`), so — unlike
  the subagent executor — there is no `delegates`-edge join back to a separate
  run row.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/executors/shell.clj#L296-L305">Source</a></sub></p>

## <a name="skein.spools.executors.shell/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the shell executor: register its event handler, the `:shell`
  workflow executor, and the `stalled-shell-gates` coordinator query, then
  perform an initial scan.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/executors/shell.clj#L307-L326">Source</a></sub></p>

## <a name="skein.spools.executors.shell/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: graph changes may make a `:shell` gate ready.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/executors/shell.clj#L291-L294">Source</a></sub></p>

## <a name="skein.spools.executors.shell/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Dispatch every ready `:shell` gate not already claimed or errored.

  Enumerates ready gates purely through the workflow surface and serializes on a
  runtime-owned monitor so concurrent scans cannot double-launch a gate.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/executors/shell.clj#L272-L289">Source</a></sub></p>
