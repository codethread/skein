# Contributing to Skein

Skein is alpha software and follows the project tenets in `devflow/TENETS.md` and `devflow/PHILOSOPHY.md`.

## Local setup

```sh
make install
mill start
```

`make install` builds `strand` and `mill` and records this checkout as mill's install-time source for weaver launch and the thin nREPL attach client. `mill init` creates only the selected workspace's alpha config marker; do not write source paths into `config.json`.

Start the weaver in one terminal:

```sh
mill weaver start
```

Use it from another terminal:

```sh
mill init
strand list
mill weaver status
mill weaver stop
```

## Disposable workspaces for development

Use explicit workspaces for tests, smoke reproduction, examples, and agent work:

```sh
workspace=$(mktemp -d)
mill init --workspace "$workspace"
mill weaver start --workspace "$workspace"
design=$(strand --workspace "$workspace" add "Sketch model" --state closed --attr priority=high)
docs=$(strand --workspace "$workspace" add "Write docs" --attr owner=agent)
strand --workspace "$workspace" update "$docs" --edge depends-on:$design
strand --workspace "$workspace" ready
mill weaver stop --workspace "$workspace"
```

`mill init` bootstraps missing `config.json`, `spools.edn`, `init.clj`, `spools/`, and `.gitignore` files/directories without overwriting existing user files. `config.json` contains only `{"configFormat":"alpha"}`.

## REPL and runtime config

```sh
mill weaver repl --workspace "$workspace"
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
printf '(skein.api.current.alpha/runtime)\n' | mill weaver repl --stdin --workspace "$workspace"
```

Runtime spool startup config may use:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha]
         '[skein.api.graph.alpha :as graph]
         '[skein.api.views.alpha :as views])
(def runtime (current/runtime))
(runtime-alpha/sync! runtime)
```

Shipped `skein.api.*.alpha` namespaces are privileged built-in helpers. `spools.edn` approves user/community local roots; `skein.api.runtime.alpha/sync!` makes approved roots available; `runtime-alpha/use!` activates modules for the weaver lifetime.

## Work tracking: the kanban board

The user↔agent work board is strand-backed, not a separate app: `spools/kanban.md` is the contract and `strand kanban about` is the live manual. Everything you ask for becomes a `feature` card.

- `strand kanban board` — high-level overview. The `claimed` lane is work in progress, each card carrying its `owner`, `branch`, and latest handover; `needs-review` lists what awaits your attention and where (ready review work, per card and branch).
- `strand kanban card <id>` — one card's notes, latest handover, active work, and `related` (adjacent cards linked by `depends-on`).
- `strand branches [branch]` — what is happening inside a feature branch: the cards on it and their substrands.
- New ideas enter with `strand kanban add "..."`; use `--status refinement` for not-yet-actionable ideas and `strand kanban promote <id>` when one is ready to work.

Agents follow the fuller board contract in `AGENTS.md` (claim/notes/handover rules); this section is the human-side overview.

## Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

After validation, `git status --short` should not show generated SQLite, socket, metadata, smoke, or built CLI artifacts.

## Debugging SQLite state

```sh
# Smoke workspaces are created under a short /tmp/sk<pid>/ root while the suite runs.
sqlite3 /tmp/sk<pid>/smoke-cli.sqlite.workspace/data/skein.sqlite '.schema'
sqlite3 /tmp/sk<pid>/smoke-cli.sqlite.workspace/data/skein.sqlite 'select id, title, state, attributes from strands;'
sqlite3 /tmp/sk<pid>/smoke-cli.sqlite.workspace/data/skein.sqlite 'select from_strand_id, to_strand_id, edge_type, attributes from strand_edges;'
```

## Implementation boundaries

- Keep the CLI thin: parse command-line input, normalize output, and route strand commands through the weaver client.
- Keep SQL and persistence behavior in `skein.core.db`.
- Keep strand attributes as JSON `TEXT`; do not introduce JSONB assumptions.
- Do not add schemas for userland attributes yet.
- Keep public CLI automation in `cli/` and weaver transport glue thin.
- Keep interactive convenience wrappers in `skein.repl`.
- Fail loudly on invalid CLI input instead of silently falling back.
- Keep public CLI machine output JSON-only; EDN belongs to Clojure REPL/config/dev workflows.
- When changing shipped behavior, update the relevant root spec in `devflow/specs/`.
