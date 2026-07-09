# Table of contents
-  [`skein.spools.executors.subagent`](#skein.spools.executors.subagent)  - Bridge workflow subagent gates to agent-run runs.
    -  [`*runtime*`](#skein.spools.executors.subagent/*runtime*) - Runtime captured for asynchronous subagent-executor scans.
    -  [`gate-stalled?`](#skein.spools.executors.subagent/gate-stalled?) - Return durable stall detail for a ready subagent gate view, or nil.
    -  [`install!`](#skein.spools.executors.subagent/install!) - Install the subagent executor's event handler and perform an initial scan.
    -  [`on-event`](#skein.spools.executors.subagent/on-event) - Weaver event handler: graph changes may finish or unblock subagent executor work.
    -  [`scan!`](#skein.spools.executors.subagent/scan!) - Deliver finished agent-run runs and spawn ready workflow subagent gates.

-----
# <a name="skein.spools.executors.subagent">skein.spools.executors.subagent</a>


Bridge workflow subagent gates to agent-run runs.

  The subagent executor watches workflow runs for ready `:subagent` gates, spawns
  an agent-run run for each gate, and delivers successful run results by
  completing the gate through `skein.spools.workflow/complete!`. It intentionally
  adds no CLI surface and keeps workflow and agent-run decoupled: this namespace
  is the only adapter that knows both vocabularies.




## <a name="skein.spools.executors.subagent/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous subagent-executor scans.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L31-L33">Source</a></sub></p>

## <a name="skein.spools.executors.subagent/gate-stalled?">`gate-stalled?`</a>
``` clojure
(gate-stalled? gate-view)
```
Function.

Return durable stall detail for a ready subagent gate view, or nil.

  A gate is stalled when spawn failed onto `gate/error`, or its stamped run is
  in agent-run phase `failed`/`exhausted`/`superseded`. `superseded` is included so
  a gate whose run was retired by `agent retry` (which supersedes the run without
  re-linking the fresh one) stays discoverable rather than silently pending until
  a coordinator clears the stamp. No wall-clock hang policy is applied.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L233-L250">Source</a></sub></p>

## <a name="skein.spools.executors.subagent/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the subagent executor's event handler and perform an initial scan.

  Fails loudly unless `skein.spools.agent-run/install!` has already registered
  the agent-run engine in this weaver runtime.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L252-L288">Source</a></sub></p>

## <a name="skein.spools.executors.subagent/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: graph changes may finish or unblock subagent executor work.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L228-L231">Source</a></sub></p>

## <a name="skein.spools.executors.subagent/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Deliver finished agent-run runs and spawn ready workflow subagent gates.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L216-L226">Source</a></sub></p>
