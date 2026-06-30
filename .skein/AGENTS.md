Always read `docs/skein.md` from the repository root before changing this Skein config.

This repo's `.skein` world installs a blessed devflow coordination config from
`.skein/config.clj` via `.skein/init.clj`. It registers startup-loaded runtime
state for agent coordination:

- patterns: `devflow-plan`, `agent-plan`
- queries: `feature-active`, `feature-work`, `feature-owner-work`,
  `feature-task-scope`, `devflow-active`, `devflow-features`, `devflow-work`,
  `current-dag-roots`
- views: `devflow-dashboard`, `task-root`, `devflow-summaries`
- ops: `current-dags`, `devflow-status`, `devflow-conventions`, `task-root`,
  `devflow-assign`, `devflow-close-feature`, `devflow-supersede`,
  `devflow-summaries`

## Blessed devflow attributes

Devflow strands are ordinary Skein strands with repo-local attributes:

- `workflow`: `devflow` for feature work created by `devflow-plan`, or
  `agent-plan` for general agent work.
- `feature`: stable feature slug; use it to scope lists, ready work, dashboards,
  worktrees, and branch names.
- `kind`: `plan`, `task`, or `review`.
- `task_key`: pattern-local stable task key such as `impl`, `docs`, or `review`.
- `task_id`: optional external/devflow numeric task id, normalized to a string.
- `task_file`: optional path to a devflow task artifact.
- `owner`: optional agent/person assignment.
- `branch`: optional worktree/branch coordination hint.
- `validation`: optional vector of validation commands or expectations.
- `body`: issue-style context for delegated work. Include problem, scope,
  acceptance criteria, constraints, relevant files, and validation expectations.

For feature work, set `branch` to the branch/worktree the assignee should use,
for example `devflow-skein-coordination` or
`agent/<feature>/<task_key>`. Use `owner` plus `branch` together when assigning a
ready item so other agents can avoid duplicate work.

## Creating coordinated work

Prefer `devflow-plan` for repo devflow feature DAGs. It creates one
`kind=plan` feature root with `parent-of` edges to task/review children and
records coordination attributes for scoped ready queries.

```sh
strand pattern explain devflow-plan
printf '%s' '{
  "feature":"devflow-skein-coordination",
  "title":"Feature: Devflow Skein coordination",
  "body":"Coordinate implementation, docs, validation, and review.",
  "tasks":[
    {"key":"impl","title":"Implement config support","owner":"agent-a","branch":"devflow-skein-coordination","validation":["clojure -M:test"]},
    {"key":"docs","title":"Document blessed coordination conventions","owner":"agent-docs","branch":"devflow-skein-coordination","depends_on":["impl"]},
    {"key":"review","kind":"review","title":"Review feature","depends_on":["docs"]}
  ]
}' | strand weave --pattern devflow-plan
```

Use `agent-plan` for non-devflow agent DAGs that still need the same task keys,
owner, branch, validation, and scoped-ready conventions.

## Finding ready work

Use feature-scoped ready queries whenever multiple features, multiple agents, or
multiple linked worktrees share the same repo-local `.skein` world. Linked
worktrees resolve to the same canonical repo world by default, so unscoped
`strand ready` can return unrelated work.

```sh
strand ready --query feature-work --param feature=<feature>
strand ready --query feature-owner-work --param feature=<feature> --param owner=<agent>
strand ready --query feature-task-scope --param feature=<feature> --param task=<task-key-or-task-id-or-task-file>
strand ready --query devflow-work
```

Use `feature-active`/`devflow-active` with `strand list` when you want all active
matching strands, not readiness-filtered work:

```sh
strand list --query feature-active --param feature=<feature>
strand list --query devflow-features
```

For dashboards and root lookups, use trusted REPL/view workflows or the matching
ops:

```sh
strand op devflow-status <feature>
strand op current-dags
strand op task-root <strand-id-or-task-key>
printf "(do (require '[skein.views.alpha :as views]) (views/view! 'devflow-dashboard {:feature \"<feature>\"}))\n" | strand weaver repl --stdin
```

## Assignment, supersession, and closeout

Update coordination metadata for one task/review with atomic owner/branch
assignment:

```sh
strand op devflow-assign <feature> <task_key> <owner> <branch>
```

Use explicit supersession when replacing a devflow task/review strand so core
Skein records the `supersedes` edge, marks the old strand `replaced`, and
rewires incoming `depends-on` edges to the replacement:

```sh
strand op devflow-supersede <old-id-or-task-key> <replacement-id-or-task-key>
```

Close an active feature DAG by closing active workflow=devflow strands in the selected feature DAG atomically:

```sh
strand op devflow-close-feature <feature>
strand op devflow-summaries
```

Task keys must match exactly one active devflow task/review. Plan supersession
is intentionally unsupported because core supersession rewires dependencies, not
parent ownership.

Smoke test config changes in a separate disposable `--config-dir` world when
possible. Do not reload the main canonical weaver unless explicitly asked. For a
disposable or intentionally selected world, reload with:

```sh
printf "(do (require '[skein.libs.alpha :as libs]) (libs/reload!))\n" | strand --config-dir "$world" weaver repl --stdin
```
