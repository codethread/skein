# Todo Graph MVP

A small Clojure todo graph tool using `next.jdbc` + SQLite.

It exists to give coding agents and humans a lightweight local task graph:

- tasks are stored in SQLite;
- tasks have first-class `status`, `created_at`, `updated_at`, and `final_at` lifecycle fields;
- open-ended task attributes are JSON stored as `TEXT` and queried with SQLite JSON1;
- `task_edges` stores acyclic graph relationships used by task updates, including `depends-on` for readiness;
- agents can use a stripped scriptable CLI or compact REPL API.

For contributor, debugging, and implementation guidance, see [AGENTS.md](./AGENTS.md). For durable behavior contracts, see the [Devflow spec index](./devflow/README.md#root-specs); for the daemon/REPL/CLI design mental model, see [Devflow Philosophy](./devflow/PHILOSOPHY.md).

## Requirements

- Clojure CLI
- Java / OpenJDK
- SQLite, provided by the `org.xerial/sqlite-jdbc` dependency at runtime

On this system, Homebrew OpenJDK may need to be put on PATH:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

## Quickstart

Run the unit tests and smoke demo:

```sh
clojure -M:test
clojure -M:smoke
```

Use the daemon-backed agent CLI. Start the daemon in one terminal; it stays in the foreground:

```sh
DB=/tmp/todo-agent.sqlite
clojure -M:todo --db "$DB" daemon start
```

Run task commands from another terminal:

```sh
DB=/tmp/todo-agent.sqlite
clojure -M:todo --db "$DB" daemon status
clojure -M:todo --db "$DB" init
design=$(clojure -M:todo --db "$DB" add "Sketch model" --status done --attr priority=high)
docs=$(clojure -M:todo --db "$DB" add "Write docs" --attr owner=agent)
clojure -M:todo --db "$DB" update "$docs" --edge depends-on:$design
clojure -M:todo --db "$DB" --format edn ready
clojure -M:todo --db "$DB" daemon stop
```

Task commands connect to the matching daemon selected by `--db`; start the daemon first and stop it when finished.

Use the REPL helpers against a running daemon (started in another terminal):

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

Named queries live in daemon memory for the current daemon lifetime. Load them from trusted daemon config or REPL helpers such as `defquery!` / `load-queries!`, then consume them from either REPL helpers or the small CLI surface:

```sh
clojure -M:todo --db agent.sqlite --format edn list --query agent-owned
clojure -M:todo --db agent.sqlite daemon stop
```

The registry is not saved to SQLite; restart the daemon and reload trusted config/REPL query definitions when needed. The CLI intentionally has no `--query-file` loader so runtime customization stays daemon/REPL-owned, matching the daemon-core design described in [Devflow Philosophy](./devflow/PHILOSOPHY.md).

## Data model

The durable data contract is specified in [Task Model](./devflow/specs/task-model.md). At a high level:

- tasks have a generated unique text id, title, status, lifecycle timestamps, and open-ended JSON object attributes;
- final statuses are `done`, `failed`, and `cancelled`, and set `final_at`;
- task edges connect two tasks with a canonical edge type and open-ended JSON object attributes;
- edge writes reject unsupported types and directed cycles;
- `depends-on` edges define readiness semantics.

## Development

See:

- [AGENTS.md](./AGENTS.md) for contributor/debug/build guidance;
- [Task Model](./devflow/specs/task-model.md) for data semantics;
- [CLI Surface](./devflow/specs/cli.md) for full command vocabulary;
- [REPL API](./devflow/specs/repl-api.md) for full helper vocabulary.
