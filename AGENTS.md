# Agent Contributor Guide

Always read `./devflow/TENETS.md` and `./devflow/PHILOSOPHY.md` before all work

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
# Install the public Go CLI and create/edit the default XDG config world.
go install ./cli/cmd/todo
ATOM_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/atom"
mkdir -p "$ATOM_CONFIG"
printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$PWD" | jq . > "$ATOM_CONFIG/config.json"

# Run in a dedicated terminal; daemon start stays in the foreground.
todo daemon start
# Optional trusted startup code lives at ~/.config/atom/init.clj.

# Run from another terminal while the daemon is alive.
todo init
todo --format json list
todo daemon status
todo daemon stop
clojure -M:test
(cd cli && go test ./...)
clojure -M:repl
clojure -M:run
```

## Agent operation quick reference

Agents should prefer the CLI for scripted work. Always use an explicit disposable `--config-dir <dir>` for agent testing, smoke reproduction, and feature work. Never use or mutate the user's default config/data/state worlds (`~/.config/atom`, `~/.local/share/atom`, `~/.local/state/atom`) unless the user explicitly asks you to operate on their real setup.

```sh
go install ./cli/cmd/todo
world=$(mktemp -d)
printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$PWD" | jq . > "$world/config.json"
# Run in a dedicated terminal; daemon start stays in the foreground.
todo --config-dir "$world" daemon start
# Optional trusted startup code lives at "$world/init.clj".

# Run from another terminal while the daemon is alive.
todo --config-dir "$world" init
design=$(todo --config-dir "$world" add "Sketch model" --status done --attr priority=high)
docs=$(todo --config-dir "$world" add "Write docs" --attr owner=agent)
todo --config-dir "$world" update "$docs" --edge depends-on:$design
todo --config-dir "$world" --format json ready
todo --config-dir "$world" daemon stop
```

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

Named queries are daemon-lifetime runtime state: register or load them through trusted daemon config or REPL helpers (`defquery!`, `load-queries!`), inspect them with `queries`, then consume them from either REPL helpers or CLI commands such as `list --query agent-owned`. They disappear when the daemon stops; the CLI does not accept `--query-file` because runtime customization belongs in daemon/REPL workflows rather than the low-privilege CLI.

For config-dir library workspace workflows (`libs.edn`, `atom.libs.alpha/sync!`, layered `use!`, and helper-REPL classpath boundaries), follow [CONTRIBUTING.md](./CONTRIBUTING.md#runtime-libraries-and-startup-config). There are intentionally no plugin/package CLI commands.

```sh
todo --config-dir "$world" daemon status
todo --config-dir "$world" daemon stop
```

For the full CLI and REPL contracts, read the root specs linked above instead of duplicating details here.

## Validation and smoke testing

Primary validation:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

The unit test suite covers parser, database, daemon, client, and REPL behavior. Go tests cover the native `todo` command, config, socket client, and integration paths. The smoke demo builds a temporary CLI, creates disposable `--config-dir` worlds with `config.json` pointing at this checkout, starts disposable daemon runtimes, exercises Go CLI subprocess commands and `daemon repl --stdin` through the selected world from outside the repo, exercises REPL helpers through a real daemon connection, then removes generated state, data, config, socket, and built CLI artifacts.

Agents must run `clojure -M:smoke` before signing off work unless explicitly told not to. If smoke cannot be run, report that clearly with the reason and do not imply full validation passed.

Keep [docs/getting-started.md](./docs/getting-started.md) and the smoke workflow in sync. User-facing bootstrap/getting-started claims should have a representative smoke path, including clean config-dir bootstrap, dirty/partially setup config-dir bootstrap, and live runtime-library reload behavior where applicable.

Tests and smoke workflows must isolate daemon worlds with temporary config dirs. Do not start test daemons through the implicit default world; pass an explicit world/config-dir so tests never read user `init.clj`, `libs.edn`, runtime metadata, or task data.

After validation, `git status --short` should not show generated SQLite or runtime metadata artifacts.

## Debugging SQLite state

Useful inspection commands:

```sh
# The default smoke run cleans these files after success; pass a custom smoke path
# or inspect during a stopped failure before cleanup.
sqlite3 smoke-cli.sqlite.config-dir/data/tasks.sqlite '.schema'
sqlite3 smoke-cli.sqlite.config-dir/data/tasks.sqlite 'select id, title, attributes from tasks;'
sqlite3 smoke-cli.sqlite.config-dir/data/tasks.sqlite 'select from_task_id, to_task_id, edge_type, attributes from task_edges;'
```

## Implementation boundaries

- Keep the CLI thin: parse command-line input, normalize output, and route task commands through the daemon client. Keep SQL and persistence behavior in `todo.db`; use CLI tests for parsing, daemon wiring, and subprocess smoke coverage.
- Keep task attributes as JSON `TEXT`; do not introduce JSONB assumptions.
- Do not add schemas for userland attributes yet.
- Keep SQL and shared persistence behavior in `todo.db`.
- Keep public CLI automation in `cli/` and daemon transport glue thin; keep legacy `todo.cli` internal/dev-only during migration.
- Keep interactive convenience wrappers in `todo.repl`.
- Fail loudly on invalid CLI input instead of silently falling back.
- Keep public CLI machine output JSON-only; EDN belongs to Clojure REPL/config/dev workflows.
- When changing shipped behavior, update the relevant root spec in `devflow/specs/`.

## Devflow notes

Completed feature plans live under `devflow/archive/`. Active feature work, if any, lives under `devflow/feat/`.
