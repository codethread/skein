
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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L55-L57">Source</a></sub></p>

## <a name="skein.spools.executors.subagent/gate-stalled?">`gate-stalled?`</a>
``` clojure
(gate-stalled? gate-view)
```
Function.

Return durable stall detail for a ready subagent gate view, or nil.

  A gate is stalled when spawn failed onto `gate/error`, or its current serving
  run — the non-superseded run with a `serves` edge to the gate — is dead in
  agent-run phase `failed`/`exhausted`. A run `agent retry` retired is superseded,
  so its fresh successor is the current server; the gate stays discoverable only
  while that server is itself dead, with no re-link step. No wall-clock hang
  policy is applied.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L240-L257">Source</a></sub></p>

## <a name="skein.spools.executors.subagent/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the subagent executor's event handler and perform an initial scan.

  Fails loudly unless `skein.spools.agent-run/install!` has already registered
  the agent-run engine in this weaver runtime.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L259-L298">Source</a></sub></p>

## <a name="skein.spools.executors.subagent/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: graph changes may finish or unblock subagent executor work.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L235-L238">Source</a></sub></p>

## <a name="skein.spools.executors.subagent/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Deliver finished agent-run runs and spawn ready workflow subagent gates.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L223-L233">Source</a></sub></p>

## <a name="skein.spools.executors.subagent/stalled-gates-query">`stalled-gates-query`</a>




Query definition behind the registered `stalled-subagent-gates` query: an
  active subagent gate whose spawn errored (non-blank `gate/error`), or whose
  current serving run is dead in a terminal phase. The gate's incoming `serves`
  edges reach each delegated run's `agent-run/phase`; a superseded run carries
  `agent-run/phase "superseded"` (never `failed`/`exhausted`), so matching a
  dead phase over `serves` selects exactly the gates whose current serving run
  is dead — by construction in lockstep with the `gate-stalled?` predicate, no
  `gate/superseded-by` bridge. `install!` registers it under the query name;
  readers composing on the rule (loom's `flow-status`) list with this definition
  directly, so membership cannot drift even on a runtime where the executor is
  not installed.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agent-run/src/skein/spools/executors/subagent.clj#L35-L53">Source</a></sub></p>
