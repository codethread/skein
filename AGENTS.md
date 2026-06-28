# Agent Contributor Guide

Always read `./devflow/TENETS.md` and `./devflow/PHILOSOPHY.md` before all work.

Skein is daemon-core-first: the long-lived weaver owns storage and runtime state; the `strand` CLI stays a thin JSON control surface. Runtime customization belongs in trusted config and REPL workflows.

Canonical shipped contracts live in root specs:

- [Strand Model](./devflow/specs/strand-model.md)
- [CLI Surface](./devflow/specs/cli.md)
- [REPL API](./devflow/specs/repl-api.md)
- [Weaver Runtime](./devflow/specs/daemon-runtime.md)

## Project commands

Use Homebrew OpenJDK when Java is not on the default PATH:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

Common commands:

```sh
go install ./cli/cmd/strand
SKEIN_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/skein"
mkdir -p "$SKEIN_CONFIG"
printf '{"configFormat":"alpha","source":"%s"}\n' "$PWD" | jq . > "$SKEIN_CONFIG/config.json"

strand weaver start
strand init
strand list
strand weaver status
strand weaver stop
clojure -M:test
(cd cli && go test ./...)
clojure -M:repl
```

## Agent operation quick reference

Agents must prefer explicit disposable `--config-dir` worlds. Never use or mutate the user's default config/data/state worlds unless explicitly asked.

```sh
go install ./cli/cmd/strand
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

Use `strand weaver repl` for interactive trusted exploration:

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

For non-interactive trusted forms:

```sh
printf '(ready)\n' | strand --config-dir "$world" weaver repl --stdin
```

For config-dir library workspace workflows, use `libs.edn`, `skein.libs.alpha/sync!`, layered `use!`, and helper-REPL classpath boundaries. There are intentionally no plugin/package CLI commands.

## Validation and smoke testing

Primary validation:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

The smoke demo builds a temporary `strand` CLI, creates disposable `--config-dir` worlds, starts disposable weaver runtimes, exercises CLI subprocess commands and `weaver repl --stdin`, exercises REPL helpers through a real weaver connection, then removes generated state, data, config, socket, metadata, and built CLI artifacts.

Tests and smoke workflows must isolate weaver worlds with temporary config dirs. Do not start test weavers through the implicit default world.

After validation, `git status --short` should not show generated SQLite or runtime metadata artifacts.

## Debugging SQLite state

```sh
sqlite3 smoke-cli.sqlite.config-dir/data/skein.sqlite '.schema'
sqlite3 smoke-cli.sqlite.config-dir/data/skein.sqlite 'select id, title, attributes from strands;'
sqlite3 smoke-cli.sqlite.config-dir/data/skein.sqlite 'select from_strand_id, to_strand_id, edge_type, attributes from strand_edges;'
```

## Implementation boundaries

- Keep the CLI thin: parse command-line input, normalize output, and route strand commands through the weaver client.
- Keep SQL and shared persistence behavior in `skein.db`.
- Keep strand attributes as JSON `TEXT`; do not introduce JSONB assumptions.
- Keep public CLI automation in `cli/` and weaver transport glue thin.
- Keep interactive convenience wrappers in `skein.repl`.
- Fail loudly on invalid CLI input instead of silently falling back.
- Keep public CLI machine output JSON-only; EDN belongs to Clojure REPL/config/dev workflows.
- When changing shipped behavior, update the relevant root spec in `devflow/specs/`.
