# Table of contents
-  [`skein.spools.treadle`](#skein.spools.treadle)  - Bridge workflow subagent gates to shuttle agent runs.
    -  [`*runtime*`](#skein.spools.treadle/*runtime*) - Runtime captured for asynchronous treadle scans.
    -  [`gate-stalled?`](#skein.spools.treadle/gate-stalled?) - Return durable stall detail for a ready subagent gate view, or nil.
    -  [`install!`](#skein.spools.treadle/install!) - Install the treadle event handler and perform an initial scan.
    -  [`on-event`](#skein.spools.treadle/on-event) - Weaver event handler: graph changes may finish or unblock treadle work.
    -  [`scan!`](#skein.spools.treadle/scan!) - Deliver finished shuttle runs and spawn ready workflow subagent gates.

-----
# <a name="skein.spools.treadle">skein.spools.treadle</a>


Bridge workflow subagent gates to shuttle agent runs.

  The treadle watches workflow runs for ready `:subagent` gates, spawns a
  shuttle run for each gate, and delivers successful run results by completing
  the gate through `skein.spools.workflow/complete!`. It intentionally adds no
  CLI surface and keeps workflow and shuttle decoupled: this namespace is the
  only adapter that knows both vocabularies.




## <a name="skein.spools.treadle/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous treadle scans.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/treadle.clj#L29-L31">Source</a></sub></p>

## <a name="skein.spools.treadle/gate-stalled?">`gate-stalled?`</a>
``` clojure
(gate-stalled? gate-view)
```
Function.

Return durable stall detail for a ready subagent gate view, or nil.

  A gate is stalled when spawn failed onto `treadle/error`, or its stamped run is
  in shuttle phase `failed`/`exhausted`/`superseded`. `superseded` is included so
  a gate whose run was retired by `agent retry` (which supersedes the run without
  re-linking the fresh one) stays discoverable rather than silently pending until
  a coordinator clears the stamp. No wall-clock hang policy is applied.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/treadle.clj#L231-L247">Source</a></sub></p>

## <a name="skein.spools.treadle/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the treadle event handler and perform an initial scan.

  Fails loudly unless `skein.spools.shuttle/install!` has already registered
  the shuttle engine in this weaver runtime.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/treadle.clj#L249-L284">Source</a></sub></p>

## <a name="skein.spools.treadle/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: graph changes may finish or unblock treadle work.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/treadle.clj#L226-L229">Source</a></sub></p>

## <a name="skein.spools.treadle/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Deliver finished shuttle runs and spawn ready workflow subagent gates.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/shuttle/src/skein/spools/treadle.clj#L214-L224">Source</a></sub></p>
