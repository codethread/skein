# Agent Contributor Guide

This file is for coding agents and contributors who are building, debugging, or extending the project.

Design philosophy: this tool is daemon-core-first, closer to Emacs than a stateless CLI. Runtime customization belongs in trusted daemon config and REPL workflows; the CLI stays small for common/scripted operations and low-privilege workers. See [Devflow Philosophy](./devflow/PHILOSOPHY.md).

Canonical shipped contracts live in root specs:

- [Task Model](./devflow/specs/task-model.md): task records, JSON attributes, edge semantics, and readiness rules.
- [CLI Surface](./devflow/specs/cli.md): command vocabulary, options, output modes, and failure behavior.
- [REPL API](./devflow/specs/repl-api.md): interactive helper vocabulary, daemon lifecycle, and return normalization.
- [Daemon Runtime](./devflow/specs/daemon-runtime.md): local daemon lifecycle, runtime metadata, nREPL transport, and trusted startup config.

## Project commands

Use Homebrew OpenJDK when Java is not on the default PATH:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

Common commands:

```sh
# Run in a dedicated terminal; daemon start stays in the foreground.
clojure -M:todo --db /tmp/todo-agent.sqlite daemon start
# Optional trusted startup config:
# clojure -M:todo --db /tmp/todo-agent.sqlite daemon start --config /path/to/daemon.edn

# Run from another terminal while the daemon is alive.
clojure -M:todo --db /tmp/todo-agent.sqlite init
clojure -M:todo --db /tmp/todo-agent.sqlite --format edn list
clojure -M:todo --db /tmp/todo-agent.sqlite daemon status
clojure -M:todo --db /tmp/todo-agent.sqlite daemon stop
clojure -M:test
clojure -M:repl
clojure -M:run
```

## Agent operation quick reference

Agents should prefer the CLI for scripted work. Pass `--db <path>` on every command when using disposable or feature-local databases.

```sh
DB=/tmp/todo-agent.sqlite
# Run in a dedicated terminal; daemon start stays in the foreground.
clojure -M:todo --db "$DB" daemon start
# Optional: daemon.edn may contain {:load-files ["trusted.clj"]}

# Run from another terminal while the daemon is alive.
clojure -M:todo --db "$DB" init
design=$(clojure -M:todo --db "$DB" add "Sketch model" --status done --attr priority=high)
docs=$(clojure -M:todo --db "$DB" add "Write docs" --attr owner=agent)
clojure -M:todo --db "$DB" update "$docs" --edge depends-on:$design
clojure -M:todo --db "$DB" --format edn ready
clojure -M:todo --db "$DB" daemon stop
```

Use `todo.repl` for interactive exploration when a daemon is already running for the same database in another terminal:

```sh
clojure -M:todo --db agent.sqlite daemon start
```

```clojure
(require '[todo.repl :refer :all])
(open! "agent.sqlite")
(init!)
(def design (:id (task! "Sketch model" "done" {:priority "high"})))
(def docs (:id (task! "Write docs" {:owner "agent"})))
(update! docs {:edges [{:type "depends-on" :to design}]})
(defquery! 'agent-owned '[:= [:attr :owner] "agent"])
(ready)
```

Named queries are daemon-lifetime runtime state: register or load them through trusted daemon config or REPL helpers (`defquery!`, `load-queries!`), inspect them with `queries`, then consume them from either REPL helpers or CLI commands such as `list --query agent-owned`. They disappear when the daemon stops; the CLI does not accept `--query-file` because runtime customization belongs in daemon/REPL workflows rather than the low-privilege CLI.

```sh
clojure -M:todo --db agent.sqlite daemon status
clojure -M:todo --db agent.sqlite daemon stop
```

For the full CLI and REPL contracts, read the root specs linked above instead of duplicating details here.

## Validation and smoke testing

Primary validation:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

The unit test suite covers parser, database, daemon, client, and REPL behavior. The smoke demo starts disposable daemon runtimes, exercises CLI subprocess commands through a real daemon, exercises REPL helpers through a real daemon connection, then removes generated SQLite and runtime metadata artifacts.

For this daemon-runtime feature, also perform the manual tmux verification in the feature plan: hold a daemon open in a named `agent-<task>` tmux session, connect from a separate CLI/REPL or `dev/` process, create/read/update representative task data, capture that the daemon remained live, then stop the daemon and session.

After validation, `git status --short` should not show generated SQLite or runtime metadata artifacts.

## Debugging SQLite state

Useful inspection commands:

```sh
# The default smoke run cleans these files after success; pass a custom smoke path
# or inspect during a stopped failure before cleanup.
sqlite3 smoke-cli.sqlite '.schema'
sqlite3 smoke-cli.sqlite 'select id, title, attributes from tasks;'
sqlite3 smoke-cli.sqlite 'select from_task_id, to_task_id, edge_type, attributes from task_edges;'
```

## Implementation boundaries

- Keep the CLI thin: parse command-line input, normalize output, and route task commands through the daemon client. Keep SQL and persistence behavior in `todo.db`; use CLI tests for parsing, daemon wiring, and subprocess smoke coverage.
- Keep task attributes as JSON `TEXT`; do not introduce JSONB assumptions.
- Do not add schemas for userland attributes yet.
- Keep SQL and shared persistence behavior in `todo.db`.
- Keep shell automation in `todo.cli`.
- Keep interactive convenience wrappers in `todo.repl`.
- Fail loudly on invalid CLI input instead of silently falling back.
- Keep CLI query output machine-readable via EDN/JSON.
- When changing shipped behavior, update the relevant root spec in `devflow/specs/`.

## Devflow notes

Completed feature plans live under `devflow/archive/`. Active feature work, if any, lives under `devflow/feat/`.
