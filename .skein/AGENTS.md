Always read `docs/skein.md` from the repository root before changing this Skein config.

This repo's `.skein` world installs a blessed devflow coordination config from
`.skein/config.clj` via `.skein/init.clj`. It registers:

- patterns: `agent-plan`, `devflow-plan`
- queries: `feature-active`, `feature-work`, `feature-owner-work`,
  `feature-task-scope`, `devflow-active`, `current-dag-roots`
- ops: `current-dags`, `devflow-status`, `devflow-conventions`

Use feature-scoped ready queries when coordinating agents, for example:

```sh
strand ready --query feature-work --param feature=<feature>
strand ready --query feature-owner-work --param feature=<feature> --param owner=<agent>
strand ready --query feature-task-scope --param feature=<feature> --param task=<task-key-or-file>
```

Smoke test config changes in a separate disposable `--config-dir` world when
possible. After changes pass, reload the current weaver:

```sh
printf "(do (require '[skein.libs.alpha :as libs]) (libs/reload!))\n" | strand weaver repl --stdin
```
