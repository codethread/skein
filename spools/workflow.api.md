
-----
# <a name="skein.spools.workflow">skein.spools.workflow</a>


Alpha workflow spool for molecule and wisp-style strand graphs.

  A workflow definition is plain data. `compile` turns that data into a Skein
  batch payload, while `pour!` and `wisp!` materialize persistent molecules and
  ephemeral wisps through the public batch alpha surface. This namespace owns no
  privileged runtime state; it composes existing strand graph primitives.




## <a name="skein.spools.workflow/active-runs">`active-runs`</a>
``` clojure
(active-runs)
(active-runs family)
```
Function.

Return active workflow root strands, optionally filtered by family.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L987-L994">Source</a></sub></p>

## <a name="skein.spools.workflow/advance!">`advance!`</a>
``` clojure
(advance! run-id)
(advance! run-id opts)
```
Function.

Advance run-id by one ready step regardless of its kind, returning the
  `{:ready [step-view ...] :done boolean}` result shape.

  Resolves the ready step (honoring an optional `:step` selector). When it is a
  checkpoint, `opts` must carry `:choice` (fail loudly otherwise); `advance!`
  dispatches to `choose!` with that choice, its `:input` (default `{}`), and the
  pass-through `:by`/`:step` opts. When it is a plain step, `:choice` must be
  absent (fail loudly otherwise); `advance!` dispatches to `complete!` with the
  pass-through `:notes`/`:attributes`/`:step`/`:by` opts.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1841-L1869">Source</a></sub></p>

## <a name="skein.spools.workflow/archive-run!">`archive-run!`</a>
``` clojure
(archive-run! run-id)
(archive-run! run-id {:keys [title attributes]})
```
Function.

Squash a finished run's molecules into one closed digest strand and return it.

  Fails loudly (TEN-003) for an unknown run or one that still has an active root.
  Every molecule subgraph of the run is burned; the single digest is stamped
  `workflow/role "digest"`, `workflow/run-id`, `workflow/squashed-count`, and a
  compact JSON-safe `workflow/summary` of the history (stage titles + checkpoint
  outcomes). opts may override the digest `:title` and merge extra `:attributes`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1243-L1271">Source</a></sub></p>

## <a name="skein.spools.workflow/await!">`await!`</a>
``` clojure
(await! run-id)
(await! run-id opts)
(await! runtime run-id opts)
```
Function.

Block until workflow run-id is done, at a checkpoint, at a ready `:self`
  step, at a gate whose waiter has no registered executor, at an
  executor-owned gate whose stall predicate reports detail, or timed out.

  opts: `:timeout-secs` (default 1800) and `:poll-ms` (default 250, matching
  the agent-run await surface). Fails loudly for a non-negative-integer
  violation on either, agreeing with `skein.spools.roster/await-quiet!`'s
  `:timeout-ms`/`:poll-ms` validation.

  The three-arg `(runtime run-id opts)` arity threads the target runtime
  explicitly, agreeing with `skein.spools.roster/await-quiet!`; the shorter
  arities resolve `current/runtime` as the ergonomic default for trusted
  in-process callers.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1316-L1342">Source</a></sub></p>

## <a name="skein.spools.workflow/bond!">`bond!`</a>
``` clojure
(bond! left-id right-id)
```
Function.

Bond two materialized molecules: `right-id` depends on `left-id`.

  The `workflow/bond` edge attribute distinguishes a cross-molecule bond from
  the intra-molecule dependency edges `compile` emits.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L939-L947">Source</a></sub></p>

## <a name="skein.spools.workflow/burn!">`burn!`</a>
``` clojure
(burn! root-id)
```
Function.

Burn a materialized molecule or wisp subgraph rooted at `root-id`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L956-L960">Source</a></sub></p>

## <a name="skein.spools.workflow/call">`call`</a>
``` clojure
(call id procedure params & {:as opts})
```
Function.

Return a procedure-style workflow call.

  The callee workflow is expanded inline at compile time. Downstream parent
  steps depend on the call id, which represents completion of the expanded
  procedure's exit steps.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L673-L681">Source</a></sub></p>

## <a name="skein.spools.workflow/checkpoint">`checkpoint`</a>
``` clojure
(checkpoint id title & {:as opts})
```
Function.

Return a workflow checkpoint step definition.

  Checkpoints are ordinary strands with consistent workflow metadata for HITL,
  review, routing, or external wait points. `:choices` may be simple keywords or
  maps with `:key`, `:label`, `:description`, optional `:next` routing (a symbol
  or a registered workflow name — see `register-workflow!`), an optional
  `:revise {:params {...}}` directive (mutually exclusive with `:next`) that
  re-pours the run's own definition with authoritative param overrides, and an
  optional `:input` declaration (a vector of `{:key :required :description}` maps
  surfaced with the choice and enforced by `choose!`).

  A `:kind :human` checkpoint (the default) is the canonical human-in-the-loop
  signal: the builder auto-stamps `workflow/hitl "true"` so callers never set
  it by hand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L683-L713">Source</a></sub></p>

## <a name="skein.spools.workflow/choice-detail">`choice-detail`</a>
``` clojure
(choice-detail run-id choice)
(choice-detail run-id choice opts)
```
Function.

Return one choice explanation for run-id's current workflow checkpoint.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1415-L1426">Source</a></sub></p>

## <a name="skein.spools.workflow/choice-details">`choice-details`</a>
``` clojure
(choice-details run-id)
(choice-details run-id opts)
```
Function.

Return choice explanations for run-id's current workflow checkpoint, keyed by
  choice name with string-keyed detail maps (the same shape `choice-detail`
  returns for a single choice).

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1396-L1413">Source</a></sub></p>

## <a name="skein.spools.workflow/choose!">`choose!`</a>
``` clojure
(choose! run-id choice)
(choose! run-id choice input)
(choose! run-id choice input opts)
```
Function.

Record a checkpoint choice for run-id, optionally pour its continuation,
  and return the `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step` (materialized strand id) to select among multiple
  ready checkpoints; without it, exactly one checkpoint must be ready. opts may
  also include `:by`, recorded as "workflow/outcome-by" on the closed
  checkpoint alongside "workflow/outcome"/"workflow/outcome-input" to
  persist who made the choice (unenforced per TEN-002).

  When the chosen choice declares required `:input` keys, `choose!` fails loudly
  before any mutation if `input` omits them (see `validate-choice-input!`). A
  routed choice — one carrying `:next` (a symbol or registered name) or
  `:revise` (re-pour the run's own definition with override params) — closes out
  the current workflow's remaining steps and pours the continuation under the
  same run-id, all in one transactional `batch/apply!` (see `routed-batch`); a
  terminal choice that closes the last inner step beneath a `procedure` join
  closes the join in the same transaction. All validation happens before any
  mutation.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1815-L1839">Source</a></sub></p>

## <a name="skein.spools.workflow/compile">`compile`</a>
``` clojure
(compile workflow)
(compile workflow params)
(compile workflow params opts)
```
Function.

Return a batch payload for a workflow molecule or wisp.

  `workflow` accepts plain maps or values produced by the `workflow` builder.
  Each step requires `:id` and `:title`, and may include
  `:description`, `:attributes`, `:state`, `:depends-on`, `:condition`, or a
  simple `:loop` of `{:count n}` / `{:each xs}`. Dynamic names, titles,
  descriptions, and attribute values may be functions of the resolved params map.

  A `:depends-on` ref pointing at a `:condition`-excluded step is spliced onto
  that step's own deps, transitively, matching beads' behavior for conditional
  steps. A ref that matches neither an included nor an excluded step, or a step
  ref colliding with the root ref (`:molecule`, overridable via opts
  `:root-ref`), fails loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L748-L788">Source</a></sub></p>

## <a name="skein.spools.workflow/complete!">`complete!`</a>
``` clojure
(complete! run-id)
(complete! run-id opts)
```
Function.

Close the current ready non-checkpoint workflow step for run-id and return
  the `{:ready [step-view ...] :done boolean}` result shape.

  opts may include `:step` (materialized strand id) to select among multiple
  ready steps; without it, exactly one step must be ready. opts may also
  include `:notes` (string, stored as "workflow/outcome-notes") and `:attributes`
  (map merged onto the closed step). A non-blank `:by` is recorded as
  "workflow/outcome-by" on any step it is supplied for, but is only required
  when closing a gate step (one built with `gate`).

  When the closed step is the last active inner step beneath a `procedure`
  join, the join closes in the same transaction (see `cascade-join-ids`). All
  validation happens before any mutation.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1505-L1539">Source</a></sub></p>

## <a name="skein.spools.workflow/current-root">`current-root`</a>
``` clojure
(current-root run-id)
```
Function.

Return the single active workflow root for run-id, nil when absent, or fail if ambiguous.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1007-L1011">Source</a></sub></p>

## <a name="skein.spools.workflow/describe">`describe`</a>
``` clojure
(describe workflow)
(describe workflow params)
```
Function.

Return a compile-time projection of `workflow` without materializing any strand.

  `workflow` may be a workflow map, a constructor var, or a registered workflow
  keyword. Loop/call expansion and condition filtering apply exactly as
  `compile` runs them, so the description matches what would pour for `params`:
  excluded steps are absent, procedure joins appear as `:procedure` steps, and
  each checkpoint's choices carry their declared `:input` and their
  `:next`/`:revise` routing. The result is `{:name … :steps [{:id :title :kind
  :depends-on :condition :gate :choices [{:key :label :description :input
  :next|:revise} …]} …]}`.

  `(describe workflow)` resolves param defaults and fails loudly listing any
  required params without a default; pass `params` to describe a definition that
  needs them.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L870-L891">Source</a></sub></p>

## <a name="skein.spools.workflow/done?">`done?`</a>
``` clojure
(done? run-id)
```
Function.

Return true when run-id has no active workflow root, or its active root's
  step, checkpoint, and procedure strands are all closed.

  Fails loudly for a run-id that has never had a root strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1154-L1161">Source</a></sub></p>

## <a name="skein.spools.workflow/explain">`explain`</a>
``` clojure
(explain)
(explain topic)
```
Function.

Return self-documenting workflow spool input contracts.

  Agents can call this before constructing workflow data. It reports the stable
  public builders, valid step/checkpoint fields, and concrete examples without
  exposing batch payload internals.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L92-L194">Source</a></sub></p>

## <a name="skein.spools.workflow/gate">`gate`</a>
``` clojure
(gate id title waiter & {:as opts})
```
Function.

Return a workflow gate step definition — a step whose completion belongs to
  an external actor rather than the driving agent.

  A gate stays an ordinary step (role `"step"`, so done-semantics are
  untouched) stamped with `workflow/gate <waiter>`, a freeform actor hint such
  as `:ci`, `:human`, or `:subagent`. `step-view` surfaces it as `:gate`, and
  `complete!` refuses to close it without a `:by` recording who closed it. The
  driving agent should treat a ready gate as a poll/hand-off point, not work to
  do. `register-executor!` keys a stall predicate by this same waiter name, so
  `await!` can stay silent on a healthy executor-owned gate. Accepts the same
  opts as `step`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L573-L591">Source</a></sub></p>

## <a name="skein.spools.workflow/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Return installation metadata for this alpha workflow spool.

  Also seeds the `workflow/*` attribute namespace into the runtime vocabulary
  registry, owned by this spool's use-key, so the workflow attributes `compile`
  and the step/gate/checkpoint builders write are discoverable data.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1871-L1923">Source</a></sub></p>

## <a name="skein.spools.workflow/molecule-id">`molecule-id`</a>
``` clojure
(molecule-id result)
```
Function.

Return the materialized root molecule id from a `pour!` or `wisp!` result.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L931-L937">Source</a></sub></p>

## <a name="skein.spools.workflow/next-checkpoint">`next-checkpoint`</a>
``` clojure
(next-checkpoint run-id)
```
Function.

Return the single ready checkpoint view for run-id, nil if none, or fail if ambiguous.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1108-L1116">Source</a></sub></p>

## <a name="skein.spools.workflow/next-gates">`next-gates`</a>
``` clojure
(next-gates run-id)
(next-gates run-id waiter)
```
Function.

Return ready gate step views for run-id, optionally filtered by waiter.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1099-L1106">Source</a></sub></p>

## <a name="skein.spools.workflow/next-step">`next-step`</a>
``` clojure
(next-step run-id)
```
Function.

Return the single ready workflow step for run-id, or fail if ambiguous.

  The view carries `:run-id` (see `next-steps`).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1118-L1124">Source</a></sub></p>

## <a name="skein.spools.workflow/next-steps">`next-steps`</a>
``` clojure
(next-steps run-id)
(next-steps run-id selector)
```
Function.

Return agent-facing ready workflow steps for run-id.

  Each view carries `:run-id` so a stage cutover is visible in-band; a bare
  `step-view` on a strand without run context stays unchanged. An optional
  selector map filters by `:kind`, `:gate`, `:checkpoint`, or
  `:checkpoint-kind`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1086-L1097">Source</a></sub></p>

## <a name="skein.spools.workflow/param">`param`</a>
``` clojure
(param & {:as opts})
```
Function.

Return a workflow param definition.

  This is a Clojure-native replacement for Beads' TOML variable blocks.
  For example, pass `:required true` or `:default` values; the result is plain
  data that `compile` consumes.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L543-L551">Source</a></sub></p>

## <a name="skein.spools.workflow/pour!">`pour!`</a>
``` clojure
(pour! workflow)
(pour! workflow params)
(pour! workflow params opts)
```
Function.

Materialize `workflow` as a persistent molecule strand graph.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L897-L905">Source</a></sub></p>

## <a name="skein.spools.workflow/register-executor!">`register-executor!`</a>
``` clojure
(register-executor! waiter pred)
```
Function.

Register a stall predicate for gate waiter `waiter` (a keyword/symbol/string
  matching a `gate` waiter hint, e.g. `:subagent`).

  The predicate receives a ready gate step view and returns nil/false while the
  executor is still fulfilling the gate, or truthy detail when coordinator
  attention is needed. Registration is weaver-lifetime runtime state, mirroring
  `register-workflow!`. Returns the registered waiter as a keyword.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1609-L1624">Source</a></sub></p>

## <a name="skein.spools.workflow/register-workflow!">`register-workflow!`</a>
``` clojure
(register-workflow! name constructor-sym)
```
Function.

Register a workflow constructor under a stable keyword `name`.

  `name` is a keyword; `constructor-sym` is a fully qualified symbol resolving to
  a workflow constructor. Registration is weaver-lifetime in-memory state
  (mirroring named queries/patterns), re-established from startup config. A
  duplicate `name` replaces the prior entry, so reloading a workflow re-points
  existing in-flight runs' named `:next` routes at the new constructor. Returns
  `name`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1591-L1607">Source</a></sub></p>

## <a name="skein.spools.workflow/registered-executors">`registered-executors`</a>
``` clojure
(registered-executors)
```
Function.

Return the current registry map of gate waiter name (keyword) -> stall predicate.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1626-L1629">Source</a></sub></p>

## <a name="skein.spools.workflow/registered-workflows">`registered-workflows`</a>
``` clojure
(registered-workflows)
```
Function.

Return the current registry map of workflow name (keyword) -> constructor symbol.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1639-L1642">Source</a></sub></p>

## <a name="skein.spools.workflow/run-history">`run-history`</a>
``` clojure
(run-history run-id)
```
Function.

Return a read-only, creation-ordered projection of every molecule ever poured
  for run-id (any state) as a vector of
  `{:root {:id :title :stage :state :created_at} :events [{:type :id :title
  :outcome :by :input :notes :at} …]}` maps.

  `:type` is `:step-closed`, `:choice`, or `:gate-closed`; events are ordered by
  their strand's `updated_at`; `:stage` is present only when a molecule carries a
  `devflow/stage`. Writes nothing and fails loudly (TEN-003) for a run that never
  had a root strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1215-L1230">Source</a></sub></p>

## <a name="skein.spools.workflow/squash!">`squash!`</a>
``` clojure
(squash! root-id title)
(squash! root-id title attributes)
```
Function.

Replace a materialized wisp/molecule with one digest strand, then burn its graph.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L962-L976">Source</a></sub></p>

## <a name="skein.spools.workflow/start!">`start!`</a>
``` clojure
(start! run-id workflow params)
(start! run-id workflow params opts)
```
Function.

Start a workflow run and return the `{:ready [step-view ...] :done boolean}`
  result shape.

  `run-id` is the stable active workflow instance handle. `workflow` may be a
  pre-built workflow map, a constructor var, or a registered workflow keyword.
  Var/keyword starts derive `:definition`; when `:context` is absent, params are
  persisted as context after keyword values are stringified and non-JSON-safe
  values are rejected loudly. `opts` may include :family, :definition, :context,
  and :root-attributes. `:ready` is empty when the run has no ready workflow work
  (e.g. an empty workflow, which also reports `:done true`).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1355-L1380">Source</a></sub></p>

## <a name="skein.spools.workflow/step">`step`</a>
``` clojure
(step id title waiter & {:as opts})
```
Function.

Return a workflow step definition — a unit of work the driving agent does
  itself.

  `waiter` must be `:self`; there is never a named step owner. Any other value
  fails loudly, directing the caller to `gate` instead. A `:self` step carries
  no `workflow/gate` attribute, so its compiled output is identical to a bare
  step. The result is plain data and may be passed to `workflow` or
  transformed by user code before compilation.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L557-L571">Source</a></sub></p>

## <a name="skein.spools.workflow/step-view">`step-view`</a>
``` clojure
(step-view step)
```
Function.

Return the agent-facing view of a workflow step.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1058-L1074">Source</a></sub></p>

## <a name="skein.spools.workflow/wisp!">`wisp!`</a>
``` clojure
(wisp! workflow)
(wisp! workflow params)
(wisp! workflow params opts)
```
Function.

Materialize `workflow` as an ephemeral wisp strand graph.

  Wisps are normal Skein strands marked with workflow attributes so userland can
  burn or squash them explicitly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L911-L922">Source</a></sub></p>

## <a name="skein.spools.workflow/workflow">`workflow`</a>
``` clojure
(workflow name & body)
```
Function.

Return a Clojure-native workflow definition.

  The returned map is the same data shape accepted by `compile`, but avoids a
  separate TOML/JSON formula language.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L715-L726">Source</a></sub></p>

## <a name="skein.spools.workflow/workflow-definition">`workflow-definition`</a>
``` clojure
(workflow-definition name)
```
Function.

Return the constructor symbol registered under keyword `name`, failing loudly
  (TEN-003) when `name` is not registered.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/workflow/src/skein/spools/workflow.clj#L1631-L1637">Source</a></sub></p>
