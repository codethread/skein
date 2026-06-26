# CLI Surface

**Document ID:** `SPEC-002`
**Status:** Implemented
**Last Updated:** 2026-06-25
**Related RFCs:** [RFC-002 Task Query DSL](../rfcs/2026-06-24-task-query-dsl.md), [RFC-003 Fast JSON Socket CLI](../archive/26-06-25__go-cli-migration/rfcs/2026-06-25-fast-json-socket-cli.md), [RFC-004 Go CLI Migration](../archive/26-06-25__go-cli-migration/rfcs/2026-06-25-go-cli-migration.md)
**Code:** `cli/`, `src/todo/daemon`

## SPEC-002.P1 Purpose

The CLI is the primary scripted interface for coding agents. It exposes a deliberately small task surface: initialize storage, create tasks, update tasks, inspect tasks, list tasks, ask for ready work, and manage the local daemon runtime.

The public CLI is a thin Go executable named `todo`. It uses Cobra for command, subcommand, flag parsing, and help text; resolves the selected daemon world; sends JSON requests over the daemon's local Unix socket; formats human or JSON output; and never opens SQLite or evaluates rich query definitions locally.

## SPEC-002.P2 Interface

Entrypoint:

```text
todo [--config-dir <dir>] [--format human|json] <command> [args]
```

Commands:

```text
init
add <title> [--status todo|done|failed|cancelled] [--attr key=value ...]
update <id> [--title title] [--status todo|done|failed|cancelled] [--attr key=value ...] [--edge edge-type:to-id ...]
show <id>
list [--query name] [--param key=value ...]
ready [--query name] [--param key=value ...]
daemon start
daemon repl [--stdin]
daemon stop
daemon status
```

## SPEC-002.P3 Contracts

- **SPEC-002.C1:** `--config-dir` selects a daemon world. Without it, the CLI uses `$XDG_CONFIG_HOME/atom` or `~/.config/atom` as the default config directory. An explicit config-dir creates a self-contained alternate world with config in that directory, runtime state under `state`, and daemon data under `data`.
- **SPEC-002.C2:** Client JSON config lives at `config.json` inside the selected config-dir. It must declare `"configFormat":"alpha"`. The alpha config format supports low-privilege keys `configFormat`, `source`, and `format`; malformed config, missing/unsupported config format, unsupported keys, or wrong value types fail non-zero.
- **SPEC-002.C3:** `source`, when required, must resolve to an absolute path to the Atom source checkout root containing `deps.edn`. A leading `~` (or `~/`) is expanded to the user home directory before validation. Relative paths, missing directories, and directories without `deps.edn` fail before launching Clojure.
- **SPEC-002.C4:** `--format` accepts `human` or `json` and defaults to `human`, unless configured in `config.json`. EDN is not a public CLI output format.
- **SPEC-002.C5:** Normal task/query/status/stop commands connect to the fixed JSON socket for the selected daemon world and do not require `source` once the daemon is running. They fail loudly when no daemon is running, when metadata/socket state is stale or malformed, or when protocol/identity verification fails.
- **SPEC-002.C6:** `add` creates a task with generated id, first-class status, timestamps, and string-valued CLI attributes.
- **SPEC-002.C7:** `update` patches title, status, attributes, and task edges for one existing task.
- **SPEC-002.C8:** `--edge edge-type:to-id` creates or updates an outgoing edge from the updated task to the target task.
- **SPEC-002.C9:** `show`, `list`, and `ready` return task rows with normalized `attributes` in JSON output.
- **SPEC-002.C10:** `ready` returns non-final tasks whose direct `depends-on` dependencies are all final.
- **SPEC-002.C11:** `list` and `ready` accept an optional named query from daemon memory with `--query` and repeated string-valued `--param key=value` runtime parameters.
- **SPEC-002.C12:** `--where` and `--format edn` are not part of the public Go CLI. Rich EDN query authoring belongs in trusted daemon config and REPL workflows.
- **SPEC-002.C13:** The CLI has no query registry mutation/listing commands and does not accept `--query-file`; query loading is a trusted daemon config or REPL workflow, and registry contents last only for the daemon lifetime.
- **SPEC-002.C14:** Malformed options, invalid statuses, invalid edge targets, unknown commands, stale/missing metadata, socket transport/identity failures, malformed daemon responses, and database/domain errors fail non-zero. Task commands against a reachable but uninitialized daemon store fail clearly with instructions to run `todo init`.
- **SPEC-002.C14a:** `todo init` is also the selected config-dir bootstrap command. Before initializing task storage, it creates only missing alpha workspace files/directories: selected config-dir, `config.json`, `libs/`, `libs.edn`, `init.clj`, and a Git repository with `git init` when `.git` is missing. It never overwrites existing files. When it creates `config.json`, `source` is the current working directory and must be an Atom checkout containing `deps.edn`; created config uses `"configFormat":"alpha"` and default human format. If `config.json` already exists, `todo init` validates it but does not add missing keys or rewrite it.
- **SPEC-002.C15:** The Go CLI implementation uses Cobra rather than hand-rolled command dispatch or flag parsing. Root, command, subcommand, and flag help must clearly describe the supported command tree and accepted flags.
- **SPEC-002.C16:** `daemon start` resolves the selected config-dir, reads `config.json`, requires valid `source`, launches the Clojure daemon from that source in the foreground, and passes the selected config-dir into the daemon. The daemon owns storage selection and loads selected config-dir `init.clj` when present.
- **SPEC-002.C17:** `daemon repl` resolves the selected config-dir, reads `config.json`, requires valid `source`, verifies a reachable daemon for that world, and launches a local plain Clojure helper REPL from the source checkout already connected to the daemon.
- **SPEC-002.C18:** `daemon repl --stdin` reads Clojure forms from stdin, evaluates them in the same connected helper context as the interactive REPL, prints one direct normal Clojure result per top-level form, and exits non-zero on read/eval errors. It does not impose a JSON or EDN response envelope; callers that want one machine-readable payload should send one top-level `do` or `let` form.
- **SPEC-002.C19:** `daemon repl` and `daemon repl --stdin` are the public CLI paths for users and agents that need to run trusted library-workspace Clojure code against a running daemon world.
- **SPEC-002.C20:** `daemon status` validates metadata and socket identity and reports health, selected config/state/data paths, daemon-owned database path, pid, daemon identity, socket endpoint, and nREPL endpoint. `daemon stop` stops only the matched daemon over the socket and waits for runtime metadata/socket cleanup.
- **SPEC-002.C21:** Runtime library workspace operations happen through selected config-dir `init.clj`, `atom.libs.alpha`, and trusted REPL workflows, not through task/query CLI commands. Runtime library support does not change the JSON socket allowlist or add package/library activation commands.

## SPEC-002.P4 Deferred

`by-attr`, bespoke dependency inspection commands, `link`, `done`, `batch`, public CLI EDN query expressions, query registry mutation commands, and plugin/package commands are not part of the stripped public CLI.

The legacy `clojure -M:todo` entrypoint may remain available as an internal Clojure/dev support path, but it is not the public scripted CLI contract.
