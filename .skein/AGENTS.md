Always read `docs/skein.md` from the repository root before changing this Skein config.

This repo's `.skein` world installs a blessed devflow coordination config from
`.skein/config.clj` via `.skein/init.clj`. It registers:

- patterns: `agent-plan`, `devflow-plan`
- queries: `feature-active`, `feature-work`, `feature-owner-work`,
  `feature-task-scope`, `devflow-active`, `current-dag-roots`
- ops: `current-dags`, `devflow-status`, `devflow-conventions`,
  `task-root`, `devflow-supersede`

Use feature-scoped ready queries when coordinating agents, for example:

```sh
strand ready --query feature-work --param feature=<feature>
strand ready --query feature-owner-work --param feature=<feature> --param owner=<agent>
strand ready --query feature-task-scope --param feature=<feature> --param task=<task-key-or-file>
```

Use explicit supersession when replacing a devflow task/review strand so core
Skein records the `supersedes` edge, marks the old strand `replaced`, and
rewires incoming `depends-on` edges to the replacement:

```sh
strand op devflow-supersede <old-id-or-task-key> <replacement-id-or-task-key>
```

Task keys must match exactly one active devflow task/review. Plan supersession
is intentionally unsupported because core supersession rewires dependencies, not
parent ownership.

Smoke test config changes in a separate disposable `--config-dir` world when
possible. After changes pass, reload the current weaver:

```sh
printf "(do (require '[skein.libs.alpha :as libs]) (libs/reload!))\n" | strand weaver repl --stdin
```
