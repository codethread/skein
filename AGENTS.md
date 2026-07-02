# Agent Contributor Guide

Always read `./devflow/TENETS.md` and `./devflow/PHILOSOPHY.md` before all work.

Always load the local strand skill at `./.agents/skills/strand/SKILL.md` when planning or tracking multi-step work with `strand`.

Skein is daemon-core-first behind a small Go router: `mill` is the local entrypoint/supervisor, the long-lived weaver owns storage and runtime state, and the `strand` CLI stays a thin JSON control surface. Runtime customization belongs in trusted config and REPL workflows.

Canonical shipped contracts live in root specs:

- [Strand Model](./devflow/specs/strand-model.md)
- [CLI Surface](./devflow/specs/cli.md)
- [REPL API](./devflow/specs/repl-api.md)
- [Weaver Runtime](./devflow/specs/daemon-runtime.md)

Userland reference spools are indexed in [`spools/`](./spools/README.md), with shipped sources under `spools/src` and contract docs beside them:

- [Workflow Engine](./spools/workflow.md)
- [Devflow Lifecycle](./spools/devflow.md)
- [Ephemeral Strands](./spools/ephemeral.md)
- [Weaver Guild](./spools/guild.md)
- [Agent Shuttle](./spools/shuttle/README.md) (approved local-root spool)
- [Treadle Gate Bridge](./spools/shuttle/treadle.md) (approved local-root spool)

Namespace tiers are intentional: `skein.api.*.alpha` is the blessed spool-facing API with accretion-based compatibility within each subnamespace, `skein.core.*` is internal and may change freely, `skein.spools.*` is the authorable/reference spool layer, and `skein.repl` is the human interactive surface.

## Project commands

Use Homebrew OpenJDK when Java is not on the default PATH:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

Common commands:

```sh
make install
mill start                    # run in a durable terminal
strand init                   # creates/completes canonical repo .skein

strand weaver start
strand list
strand weaver status
strand weaver stop
clojure -M:test
(cd cli && go test ./...)
clojure -M:repl
```

## Agent operation quick reference

Agents must prefer explicit disposable `--workspace` workspaces. Never use or mutate the user's default config/data/state workspaces unless explicitly asked.

```sh
make install
workspace=$(mktemp -d)
xdg=$(mktemp -d)
export XDG_STATE_HOME="$xdg"
strand --workspace "$workspace" init

mill start &
mill_pid=$!
until mill status >/dev/null 2>&1; do sleep 0.1; done
strand --workspace "$workspace" weaver start
design=$(strand --workspace "$workspace" add "Sketch model" --state closed --attr priority=high)
docs=$(strand --workspace "$workspace" add "Write docs" --attr owner=agent)
strand --workspace "$workspace" update "$docs" --edge depends-on:$design
strand --workspace "$workspace" ready
strand --workspace "$workspace" weaver stop
kill "$mill_pid"
```

Use `strand weaver repl` for interactive trusted exploration:

```sh
strand --workspace "$workspace" weaver repl
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
printf '(skein.api.runtime.alpha/current-runtime)\n' | strand --workspace "$workspace" weaver repl --stdin
```

For spool workspace workflows, use `spools.edn`, privileged `skein.api.runtime.alpha/sync!`, layered `runtime/use!`, and live weaver REPL/config loading. There are intentionally no plugin/package CLI commands.

## Validation and smoke testing

Primary validation:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

The smoke demo builds a temporary `strand` CLI, creates disposable `--workspace` workspaces, starts disposable weaver runtimes, exercises CLI subprocess commands and direct live `weaver repl --stdin`, exercises REPL helpers against a real weaver, then removes generated state, data, config, socket, metadata, and built CLI artifacts.

Tests and smoke workflows must isolate weaver workspaces with temporary workspaces. Do not start test weavers through implicit repo discovery or any user-owned workspace.

After validation, `git status --short` should not show generated SQLite or runtime metadata artifacts.

## Debugging SQLite state

```sh
sqlite3 smoke-cli.sqlite.workspace/data/skein.sqlite '.schema'
sqlite3 smoke-cli.sqlite.workspace/data/skein.sqlite 'select id, title, attributes from strands;'
sqlite3 smoke-cli.sqlite.workspace/data/skein.sqlite 'select from_strand_id, to_strand_id, edge_type, attributes from strand_edges;'
```

## Implementation boundaries

- Keep the CLI thin: parse command-line input, normalize output, and route strand commands through the weaver client.
- Keep SQL and shared persistence behavior in `skein.core.db`.
- Keep strand attributes as JSON `TEXT`; do not introduce JSONB assumptions.
- Keep public CLI automation in `cli/` and weaver transport glue thin.
- Keep interactive convenience wrappers in `skein.repl`.
- Fail loudly on invalid CLI input instead of silently falling back.
- Keep public CLI machine output JSON-only; EDN belongs to Clojure REPL/config/dev workflows.
- When changing shipped behavior, update the relevant root spec in `devflow/specs/`.
- Every Clojure `ns` gets a docstring (string right after the ns symbol) describing its purpose, for `clojure.repl/doc` and editor/cljdoc discovery.
