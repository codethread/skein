Always read `docs/skein.md` from the repository root before changing this Skein config.

This repo's `.skein` world is thin glue over the shipped reference spools.
`.skein/init.clj` activates `skein.spools.ephemeral`, `skein.spools.workflow`,
and `skein.spools.devflow` from the weaver classpath, then loads
`.skein/config.clj`, which registers:

- ops: `devflow-start`, `devflow-next`, `devflow-choices`, `devflow-choose`,
  `devflow-complete`, `devflow-advance`, `devflow-describe`,
  `devflow-history`, `devflow-archive`, `devflow-status`, `workflow-runs`,
  `current-dags`, `devflow-conventions`
- queries: `work`, `feature-active`, `feature-work`, `feature-owner-work`,
  `feature-run`, `workflow-runs`, `devflow-runs`
- patterns: `agent-plan`

Contracts for the underlying spools live beside their code:
[`src/skein/spools/workflow.md`](../src/skein/spools/workflow.md) (engine) and
[`src/skein/spools/devflow.md`](../src/skein/spools/devflow.md) (lifecycle).
Run `strand op devflow-conventions` for the live installed surface.

## Driving the devflow lifecycle

The feature name is the workflow run-id. Each stage pours a molecule of
ordinary strands; checkpoints route between stages (see devflow.md §2 for the
stage map).

```sh
strand op devflow-start <feature> [required|already-in-worktree-ok]
strand op devflow-next <feature>
strand op devflow-choices <feature>
strand op devflow-choose <feature> <choice> ['{"key":"value"}'] [step=<id>]
strand op devflow-complete <feature> ['notes'] [step=<id>]
strand op devflow-advance <feature> [choice] ['{"key":"value"}'] ['notes'] [step=<id>]
strand op devflow-describe [stage-key]
strand op devflow-history <feature>
strand op devflow-archive <feature>
strand op devflow-status <feature>
```

Rules of the road:

- Step views tell you what to do: act on `instruction`/`action-ref`/`artifact`,
  then `devflow-complete` or the unified `devflow-advance`. Record what
  happened in `notes`.
- A `checkpoint` step view is decided with `devflow-choose` or
  `devflow-advance <feature> <choice>`, never `devflow-complete`. Checkpoints
  marked `workflow/hitl` are human decisions: stop and ask the user, do not
  choose for them.
- Aborting requires a reason: `strand op devflow-choose <feature> abort '{"reason":"..."}'`.
- A routed choice closes out the current stage's remaining steps — it is a hard
  cutover, not a pause (workflow.md §5).
- The same commands are available in the trusted REPL via
  `skein.spools.devflow` (`start!`, `next-steps`, `choose!`, `complete!`, ...),
  which also exposes composition (`devflow-cycle`, stage constructors) that the
  CLI intentionally does not.

Discover active runs and actionable work:

```sh
strand ready --query work
strand list --query work --state active
strand list --query devflow-runs
strand op workflow-runs devflow
strand list --query feature-run --param feature=<feature>
```

`work` is the default repo-local ready query for agents: it keeps normal tasks,
workflow steps, and checkpoints visible, but hides bookkeeping strands whose
`workflow/role` is `molecule`, `procedure`, or `digest`.

## Custom workflows

Author ad-hoc workflow molecules from the REPL with `skein.spools.workflow`
(`workflow`, `step`, `gate`, `checkpoint`, `call`, `pour!`, `start!`). Call
`(skein.spools.workflow/explain)` for machine-readable builder contracts before
constructing definitions.

## Lightweight plans (agent-plan)

For small non-lifecycle work DAGs, use the `agent-plan` pattern instead of raw
`add`/`update` commands:

```sh
strand pattern explain agent-plan
printf '%s' '{
  "feature":"<slug>",
  "title":"Feature: <name>",
  "tasks":[
    {"key":"impl","title":"Implement <outcome>","validation":["clojure -M:test"]},
    {"key":"review","kind":"review","title":"Review <outcome>","depends_on":["impl"]}
  ]
}' | strand weave --pattern agent-plan
strand ready --query feature-work --param feature=<slug>
```

Any strand delegated to another agent must include a descriptive `body`
attribute. Use `owner` plus `branch` together when assigning ready work so
other agents avoid duplicating it. Delegated agents must read their assigned
strand, append `progress` attributes while working, set `status=implemented`
when their scoped work is ready for coordinator verification, never close their
own assigned strand, and never mutate sibling or parent strands unless the
assignment explicitly says so.

## Config changes

Smoke test config changes in a disposable `--workspace` world when possible; do
not reload the main canonical weaver unless explicitly asked. Reload a selected
world with:

```sh
printf "(do (require '[skein.runtime.alpha :as runtime-alpha]) (runtime-alpha/reload!))\n" | strand --workspace "$world" weaver repl --stdin
```
