# Contributing

This guide covers local setup, validation, debugging, and implementation notes for Atom contributors.

## Requirements

- Clojure CLI
- Java / OpenJDK
- Go
- SQLite, provided by the `org.xerial/sqlite-jdbc` dependency at runtime

On this system, Homebrew OpenJDK may need to be put on PATH:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

## Validation

Run the unit tests and smoke demo:

```sh
clojure -M:test
(cd cli && go test ./...)
clojure -M:smoke
```

The smoke path builds a temporary CLI while exercising the Go CLI against the daemon JSON socket.

Primary validation used by agents:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

After validation, `git status --short` should not show generated SQLite or runtime metadata artifacts.

## Build and run locally

Install the public CLI and create/edit the default XDG config world:

```sh
go install ./cli/cmd/todo
ATOM_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/skein"
mkdir -p "$ATOM_CONFIG"
printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$PWD" | jq . > "$ATOM_CONFIG/config.json"
```

Run in a dedicated terminal; daemon start stays in the foreground:

```sh
todo daemon start
```

Run from another terminal while the daemon is alive:

```sh
todo init  # bootstraps missing config.json, libs.edn, init.clj, and .git without overwriting existing files
todo --format json list
todo daemon status
todo daemon stop
```

`todo init` is safe to re-run: it creates missing workspace files and initializes a git repo if absent, but never overwrites existing `config.json`, `libs.edn`, `init.clj`, or `.git`.

Use `--config-dir <dir>` when you need a disposable or feature-local daemon world:

```sh
go install ./cli/cmd/todo
world=$(mktemp -d)
printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$PWD" | jq . > "$world/config.json"
todo --config-dir "$world" daemon start
```

From another terminal:

```sh
todo --config-dir "$world" init
design=$(todo --config-dir "$world" add "Sketch model" --status done --attr priority=high)
docs=$(todo --config-dir "$world" add "Write docs" --attr owner=agent)
todo --config-dir "$world" update "$docs" --edge depends-on:$design
todo --config-dir "$world" --format json ready
todo --config-dir "$world" daemon stop
```

## REPL workflows

Use `todo daemon repl` for interactive exploration when a daemon is already running for the selected config-dir world:

```sh
todo --config-dir "$world" daemon repl
```

```clojure
(init!)
(def design (:id (task! "Sketch model" "done" {:priority "high"})))
(def docs (:id (task! "Write docs" {:owner "agent"})))
(update! docs {:edges [{:type "depends-on" :to design}]})
(defquery! 'agent-owned '[:= [:attr :owner] "agent"])
(ready)
```

For non-interactive trusted forms, use stdin. It prints direct Clojure results without a CLI response envelope:

```sh
printf '(ready)\n' | todo --config-dir "$world" daemon repl --stdin
```

Named queries are daemon-lifetime runtime state: register or load them through trusted daemon config or REPL helpers (`defquery!`, `load-queries!`), inspect them with `queries`, then consume them from either REPL helpers or CLI commands such as `list --query agent-owned`.

## Runtime libraries and startup config

Trusted runtime customization belongs in the selected daemon world's `init.clj` and connected REPL workflows. The selected `--config-dir` is also a user-owned library workspace. Atom does not clone source, install packages, or expose plugin/package/library activation commands in the public CLI.

The recommended workspace is a normal Git repo. Keep the Atom checkout wherever `config.json` `source` points; keep user/community libraries under the config-dir by Git submodule, symlink, or manual copy. `libs.edn` approves local roots, `atom.libs.alpha/sync!` makes them available to the daemon, and `atom.libs.alpha/use!` activates optional modules. Built-in shipped namespaces such as `atom.graph.alpha` and `atom.views.alpha` are already on the Atom classpath; require them directly from trusted config or a connected REPL, and do not add them to `libs.edn` or load them with `libs/use!`.

A fresh default-world setup can look like this:

```sh
go install ./cli/cmd/todo
ATOM_SOURCE="$PWD"
ATOM_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/skein"

mkdir -p "$ATOM_CONFIG/libs"
cd "$ATOM_CONFIG"
git init
printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$ATOM_SOURCE" | jq . > config.json
printf '{:libs {}}\n' > libs.edn
cat > init.clj <<'EOF'
(require '[atom.libs.alpha :as libs]
         '[atom.graph.alpha :as graph]
         '[atom.views.alpha :as views])
(libs/sync!)
EOF
git add config.json libs.edn init.clj
git commit -m "Initialize Atom config"
todo daemon start
```

Add a user/community library as local source. A submodule gives pinned source and easy review/update:

```sh
cd "$ATOM_CONFIG"
git submodule add <repo-url-or-local-path> libs/community-graph
git commit -m "Add community graph library"
```

Then approve and activate it:

```clojure
;; <config-dir>/libs.edn
{:libs {community/graph {:local/root "libs/community-graph"}
        my/config       {:local/root "/absolute/path/to/my-config-lib"}}}
```

```clojure
;; <config-dir>/init.clj
(require '[atom.libs.alpha :as libs])

(libs/sync!)

(libs/use! :graph
  {:ns 'community.graph.alpha
   :libs #{'community/graph}
   :call 'community.graph.alpha/install!})

(libs/use! :my/config
  {:ns 'my.config.alpha
   :libs #{'my/config}
   :after [:graph]
   :call 'my.config.alpha/install!})
```

Relative roots resolve against the selected config-dir; absolute roots are explicit user-approved paths. Roots are canonicalized, so symlinks and relative segments normalize before sync. Each local root should be a tools.deps project root, for example with its own `deps.edn` and `src` directory.

Inspect approved libraries, sync outcomes, and module-use state through the connected REPL or non-interactive stdin:

```sh
printf '(require '\''[atom.libs.alpha :as libs])\n(libs/approved)\n(libs/sync!)\n(libs/syncs)\n(libs/uses)\n' | todo daemon repl --stdin
```

REPL process boundaries matter: `libs/sync!` mutates the daemon JVM classpath, and `libs/use!` runs daemon-side activation. A direct `require` typed into a connected helper REPL uses that helper JVM's classpath, not newly synced daemon libraries.

## Debugging SQLite state

Useful inspection commands:

```sh
sqlite3 smoke-cli.sqlite.config-dir/data/skein.sqlite '.schema'
sqlite3 smoke-cli.sqlite.config-dir/data/skein.sqlite 'select id, title, attributes from tasks;'
sqlite3 smoke-cli.sqlite.config-dir/data/skein.sqlite 'select from_task_id, to_task_id, edge_type, attributes from task_edges;'
```

## Implementation boundaries

- Keep the CLI thin: parse command-line input, normalize output, and route task commands through the daemon client.
- Keep SQL and persistence behavior in `todo.db`.
- Keep task attributes as JSON `TEXT`; do not introduce JSONB assumptions.
- Do not add schemas for userland attributes yet.
- Keep public CLI automation in `cli/` and daemon transport glue thin; keep legacy `todo.cli` internal/dev-only during migration.
- Keep interactive convenience wrappers in `todo.repl`.
- Fail loudly on invalid CLI input instead of silently falling back.
- Keep public CLI machine output JSON-only; EDN belongs to Clojure REPL/config/dev workflows.
- When changing shipped behavior, update the relevant root spec in `devflow/specs/`.

## Reference

- [AGENTS.md](./AGENTS.md) for agent-specific guidance.
- [Devflow Tenets](./devflow/TENETS.md) and [Devflow Philosophy](./devflow/PHILOSOPHY.md) for design constraints.
- [Task Model](./devflow/specs/task-model.md) for data semantics.
- [CLI Surface](./devflow/specs/cli.md) for command vocabulary.
- [REPL API](./devflow/specs/repl-api.md) for helper vocabulary.
- [Daemon Runtime](./devflow/specs/daemon-runtime.md) for daemon lifecycle and startup config.
