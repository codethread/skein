# Agent Contributor Guide

This file is for coding agents and contributors who are building, debugging, or extending the project.

Canonical shipped contracts live in root specs:

- [Task Model](./devflow/specs/task-model.md): task records, JSON attributes, edge semantics, and readiness rules.
- [CLI Surface](./devflow/specs/cli.md): command vocabulary, options, output modes, and failure behavior.
- [REPL API](./devflow/specs/repl-api.md): interactive helper vocabulary, datasource lifecycle, and return normalization.

## Project commands

Use Homebrew OpenJDK when Java is not on the default PATH:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

Common commands:

```sh
clojure -M:todo --db /tmp/todo-agent.sqlite daemon start
clojure -M:todo --db /tmp/todo-agent.sqlite init
clojure -M:todo --db /tmp/todo-agent.sqlite --format edn list
clojure -M:todo --db /tmp/todo-agent.sqlite daemon stop
clojure -M:test
clojure -M:repl
clojure -M:run
```

## Agent operation quick reference

Agents should prefer the CLI for scripted work. Pass `--db <path>` on every command when using disposable or feature-local databases.

```sh
DB=/tmp/todo-agent.sqlite
clojure -M:todo --db "$DB" daemon start
clojure -M:todo --db "$DB" init
design=$(clojure -M:todo --db "$DB" add "Sketch model" --status done --attr priority=high)
docs=$(clojure -M:todo --db "$DB" add "Write docs" --attr owner=agent)
clojure -M:todo --db "$DB" update "$docs" --edge depends-on:$design
clojure -M:todo --db "$DB" --format edn ready
clojure -M:todo --db "$DB" daemon stop
```

Use `todo.repl` for interactive exploration when a REPL is already available:

```clojure
(require '[todo.repl :refer :all])
(open! "agent.sqlite")
(init!)
(def design (:id (task! "Sketch model" {:status "done" :priority "high"})))
(def docs (:id (task! "Write docs" {:status "todo" :owner "agent"})))
(depends! docs design)
(ready)
(by-attr :owner "agent")
(done! docs)
```

For the full CLI and REPL contracts, read the root specs linked above instead of duplicating details here.

## Validation and smoke testing

Primary validation:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

The unit test suite covers parser and database behavior. The smoke demo recreates `smoke.sqlite`, exercises the CLI subprocess path, exercises REPL helpers, and prints JSON1/graph query examples.

After validation, `git status --short` should not show generated SQLite or cache artifacts.

## Debugging SQLite state

Useful inspection commands:

```sh
sqlite3 smoke.sqlite '.schema'
sqlite3 smoke.sqlite 'select id, title, attributes from tasks;'
sqlite3 smoke.sqlite 'select from_task_id, to_task_id, edge_type, attributes from task_edges;'
```

## Implementation boundaries

- Keep the CLI thin: parse command-line input, normalize output, and delegate domain behavior to `todo.db`. Prefer testing core logic at the parser/database boundaries instead of duplicating full CLI surface tests for every behavior; use CLI tests for parsing, wiring, and subprocess smoke coverage.
- Keep task attributes as JSON `TEXT`; do not introduce JSONB assumptions.
- Store task status in `attributes` for now; do not add a status column without a planned migration.
- Do not add schemas for userland attributes yet.
- Keep SQL and shared persistence behavior in `todo.db`.
- Keep shell automation in `todo.cli`.
- Keep interactive convenience wrappers in `todo.repl`.
- Fail loudly on invalid CLI input instead of silently falling back.
- Keep CLI query output machine-readable via EDN/JSON.
- When changing shipped behavior, update the relevant root spec in `devflow/specs/`.

## Devflow notes

Completed feature plans live under `devflow/archive/`. Active feature work, if any, lives under `devflow/feat/`.
