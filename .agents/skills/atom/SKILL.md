---
name: atom
description: Query and operate the Atom todo task database from agents, scripts, and the REPL.
---

# Atom task workflows

Atom is daemon-core-first. The public `todo` CLI is a thin Go control surface over a running daemon; rich query/config/library work belongs in trusted `init.clj` and `todo daemon repl` workflows.

Always use an explicit disposable `--config-dir <dir>` for agent testing unless the user asks for their real default world.

## Daemon world setup

```sh
world=$(mktemp -d)
printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$PWD" > "$world/config.json"
# In a dedicated terminal; stays in foreground.
todo --config-dir "$world" daemon start
# From another terminal after the daemon is running.
todo --config-dir "$world" init
```

`todo init` bootstraps missing config-dir workspace files without overwriting existing files: `config.json`, `libs/`, `libs.edn`, `init.clj`, and `.git`, then initializes task storage through the selected daemon. `config.json` must use `"configFormat":"alpha"`; daemon start also requires `source` to point at an Atom checkout containing `deps.edn`.

Useful lifecycle commands:

```sh
todo --config-dir "$world" daemon status
todo --config-dir "$world" daemon stop
```

## CLI task operations

```sh
todo --config-dir "$world" --format json add "Sketch model" --status todo --attr owner=agent
todo --config-dir "$world" --format json update "$id" --status done
todo --config-dir "$world" --format json update "$docs" --edge depends-on:"$design"
todo --config-dir "$world" --format json show "$id"
todo --config-dir "$world" --format json list
todo --config-dir "$world" --format json ready
```

Notes:

- `add <title>` generates the task id; in human mode it prints the id.
- CLI `--attr` and `--param` values are strings.
- `--format` is `human` or `json` only; EDN is for Clojure REPL/config workflows.
- The Go CLI does not support `--where`, `--query-file`, raw SQL, or query registry mutation. Use daemon-loaded named queries.

## Named queries from the CLI

The CLI consumes queries already registered in daemon memory:

```sh
todo --config-dir "$world" --format json ready \
  --query owned-open \
  --param owner=agent
```

Query registry contents are daemon-lifetime runtime state, not durable storage. Reload them from `init.clj`, trusted libraries, or the REPL after daemon restart. Query names are simple unqualified names; symbol and keyword forms resolve to the same registry entry.

## Connected REPL

```sh
todo --config-dir "$world" daemon repl
```

Core helpers:

```clojure
(init!)
(def design (:id (task! "Sketch model" "done" {:priority "high"})))
(def docs (:id (task! "Write docs" {:owner "agent"})))
(update! docs {:edges [{:type "depends-on" :to design}]})
(task docs)
(tasks)
(ready)
```

Connection helpers:

```clojure
(connect!)                  ; default world
(connect! "/tmp/atom-world") ; explicit config-dir world, not a db path
```

Non-interactive trusted forms via stdin print one direct Clojure result per top-level form:

```sh
printf '(ready)\n' | todo --config-dir "$world" daemon repl --stdin
```

Wrap work in one top-level `do` or `let` when a caller wants one machine-readable result.

## Defining and loading queries

```clojure
(defquery! 'high-priority [:= [:attr :priority] "high"])
(query 'high-priority)
(ready 'high-priority)

(load-queries! "queries.edn")
(queries)
(query 'owned-open {:owner "agent"})
(ready 'owned-open {:owner "agent"})
```

A query file is one EDN map of names to query expressions or parameterized query maps:

```clojure
{owned-open
 {:params [:owner]
  :where [:and
          [:= :status "todo"]
          [:= [:attr :owner] [:param :owner]]]}}
```

## Query forms

Queries are EDN vectors. Supported fields:

- `:id`
- `:title`
- `:status`
- `:created_at`
- `:updated_at`
- `:final_at`
- `[:attr :key]`
- `[:attr :nested :key]`

Supported operators:

```clojure
[:= field value]
[:!= field value]
[:< field value]
[:<= field value]
[:> field value]
[:>= field value]
[:in field [value ...]]
[:exists field]
[:missing field]
[:and query ...]
[:or query ...]
[:not query]
```

Use `[:param :name]` inside named queries to accept runtime values. CLI params are strings; REPL params may be Clojure values SQLite JSON comparisons can handle.

## Runtime library workspace

Trusted runtime customization lives in the selected config-dir workspace:

- `init.clj` loads at daemon startup.
- `libs.edn` approves local roots under `{:libs {my/lib {:local/root "libs/my-lib"}}}`.
- `atom.libs.alpha` provides daemon-side library sync and module activation helpers.

In `todo daemon repl`, `atom.libs.alpha` is required as `libs`:

```clojure
(libs/approved)
(libs/sync!)
(libs/syncs)
(libs/use! :my/module {:ns 'my.module.alpha :libs #{'my/lib}})
(libs/uses)
(libs/use :my/module)
```

A typical `init.clj`:

```clojure
(require '[atom.libs.alpha :as libs])
(libs/sync!)
```
