# Table of contents
-  [`skein.spools.reed`](#skein.spools.reed)  - Fulfil workflow <code>:shell</code> gates by running their command off the event thread.
    -  [`*runtime*`](#skein.spools.reed/*runtime*) - Runtime captured for asynchronous reed worker threads.
    -  [`gate-stalled?`](#skein.spools.reed/gate-stalled?) - Return durable stall detail for a ready <code>:shell</code> gate view, or nil.
    -  [`install!`](#skein.spools.reed/install!) - Install the reed event handler, register the <code>:shell</code> executor and the <code>stalled-shell-gates</code> coordinator query, and perform an initial scan.
    -  [`on-event`](#skein.spools.reed/on-event) - Weaver event handler: graph changes may make a <code>:shell</code> gate ready.
    -  [`scan!`](#skein.spools.reed/scan!) - Dispatch every ready <code>:shell</code> gate not already claimed or errored.

-----
# <a name="skein.spools.reed">skein.spools.reed</a>


Fulfil workflow `:shell` gates by running their command off the event thread.

  A reed watches workflow runs for ready gates whose waiter is `:shell`, runs the
  gate's `shell/argv` directly (no implicit shell) on a spool-owned worker pool,
  and closes the gate through `skein.spools.workflow/complete!` on a zero exit. A
  non-zero exit, timeout, spawn error, or invalid argv stamps a loud, distinct
  `shell/error` and leaves the gate ready and stamped rather than masquerading as
  a completed run. It is a treadle sibling minus everything shuttle-specific: the
  failure detail lives on the gate itself, so there is no separate run strand, no
  `delegates` edge, and no session/harness vocabulary. This namespace is the only
  adapter that knows both the workflow gate contract and process execution.




## <a name="skein.spools.reed/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous reed worker threads.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/reed.clj#L44-L46">Source</a></sub></p>

## <a name="skein.spools.reed/gate-stalled?">`gate-stalled?`</a>
``` clojure
(gate-stalled? gate-view)
```
Function.

Return durable stall detail for a ready `:shell` gate view, or nil.

  The failure detail lives on the gate itself (`shell/error`), so — unlike
  treadle — there is no `delegates`-edge join back to a separate run row.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/reed.clj#L291-L299">Source</a></sub></p>

## <a name="skein.spools.reed/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the reed event handler, register the `:shell` executor and the
  `stalled-shell-gates` coordinator query, and perform an initial scan.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/reed.clj#L301-L319">Source</a></sub></p>

## <a name="skein.spools.reed/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: graph changes may make a `:shell` gate ready.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/reed.clj#L286-L289">Source</a></sub></p>

## <a name="skein.spools.reed/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Dispatch every ready `:shell` gate not already claimed or errored.

  Enumerates ready gates purely through the workflow surface and serializes on a
  runtime-owned monitor so concurrent scans cannot double-launch a gate.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/reed.clj#L267-L284">Source</a></sub></p>
