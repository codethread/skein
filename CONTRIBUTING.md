# Contributing to Skein

Skein is alpha software and follows the project tenets in `devflow/TENETS.md` and `devflow/PHILOSOPHY.md`.

## Local setup

```sh
go install ./cli/cmd/strand
SKEIN_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/skein"
mkdir -p "$SKEIN_CONFIG"
printf '{"configFormat":"alpha","source":"%s"}\n' "$PWD" | jq . > "$SKEIN_CONFIG/config.json"
```

Start the weaver in one terminal:

```sh
strand weaver start
```

Use it from another terminal:

```sh
strand init
strand list
strand weaver status
strand weaver stop
```

## Disposable worlds for development

Use explicit config dirs for tests, smoke reproduction, examples, and agent work:

```sh
world=$(mktemp -d)
printf '{"configFormat":"alpha","source":"%s"}\n' "$PWD" | jq . > "$world/config.json"
strand --config-dir "$world" weaver start
strand --config-dir "$world" init
design=$(strand --config-dir "$world" add "Sketch model" --state closed --attr priority=high)
docs=$(strand --config-dir "$world" add "Write docs" --attr owner=agent)
strand --config-dir "$world" update "$docs" --edge depends-on:$design
strand --config-dir "$world" ready
strand --config-dir "$world" weaver stop
```

`strand init` bootstraps missing `config.json`, `libs.edn`, `init.clj`, `libs/`, and `.git` files/directories without overwriting existing user files.

## REPL and runtime config

```sh
strand --config-dir "$world" weaver repl
```

```clojure
(init!)
(def design (:id (strand! "Sketch model" {:priority "high"} {:state "closed"})))
(def docs (:id (strand! "Write docs" {:owner "agent"})))
(update! docs {:edges [{:type "depends-on" :to design}]})
(defquery! 'agent-owned '[:= [:attr :owner] "agent"])
(ready)
```

Non-interactive trusted forms:

```sh
printf '(ready)\n' | strand --config-dir "$world" weaver repl --stdin
```

Runtime library startup config may use:

```clojure
(require '[skein.libs.alpha :as libs]
         '[skein.graph.alpha :as graph]
         '[skein.views.alpha :as views])
(libs/sync!)
```

`libs.edn` approves local roots under the selected config-dir; `skein.libs.alpha/sync!` makes approved roots available to the weaver; `libs/use!` activates modules for the weaver lifetime.

## Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

After validation, `git status --short` should not show generated SQLite, socket, metadata, smoke, or built CLI artifacts.

## Debugging SQLite state

```sh
# Smoke worlds are created under a short /tmp/sk<pid>/ root while the suite runs.
sqlite3 /tmp/sk<pid>/smoke-cli.sqlite.config-dir/data/skein.sqlite '.schema'
sqlite3 /tmp/sk<pid>/smoke-cli.sqlite.config-dir/data/skein.sqlite 'select id, title, state, attributes from strands;'
sqlite3 /tmp/sk<pid>/smoke-cli.sqlite.config-dir/data/skein.sqlite 'select from_strand_id, to_strand_id, edge_type, attributes from strand_edges;'
```

## Implementation boundaries

- Keep the CLI thin: parse command-line input, normalize output, and route strand commands through the weaver client.
- Keep SQL and persistence behavior in `skein.db`.
- Keep strand attributes as JSON `TEXT`; do not introduce JSONB assumptions.
- Do not add schemas for userland attributes yet.
- Keep public CLI automation in `cli/` and weaver transport glue thin.
- Keep interactive convenience wrappers in `skein.repl`.
- Fail loudly on invalid CLI input instead of silently falling back.
- Keep public CLI machine output JSON-only; EDN belongs to Clojure REPL/config/dev workflows.
- When changing shipped behavior, update the relevant root spec in `devflow/specs/`.
