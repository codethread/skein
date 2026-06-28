# CLI Surface

**Document ID:** `SPEC-002`
**Status:** Implemented
**Last Updated:** 2026-06-28
**Related RFCs:** [RFC-002 Task Query DSL](../rfcs/2026-06-24-task-query-dsl.md), [RFC-003 Fast JSON Socket CLI](../archive/26-06-25__go-cli-migration/rfcs/2026-06-25-fast-json-socket-cli.md), [RFC-004 Go CLI Migration](../archive/26-06-25__go-cli-migration/rfcs/2026-06-25-go-cli-migration.md)
**Code:** `cli/`, `src/skein/weaver`

## SPEC-002.P1 Purpose

The CLI is the primary scripted interface for coding agents. It exposes a deliberately small strand surface: initialize storage, create strands, update strands, burn strands, inspect strands, list strands, ask for ready work, and manage the local weaver runtime.

The public CLI is a thin Go executable named `strand`. It uses Cobra for command, subcommand, flag parsing, and help text; resolves the selected Skein world; sends JSON requests over the weaver's local Unix socket; emits JSON for public strand/weaver commands; and never opens SQLite or evaluates rich query definitions locally.

## SPEC-002.P2 Interface

Entrypoint:

```text
strand [--config-dir <dir>] <command> [args]
```

Commands:

```text
init
add <title> [--state active|closed] [--attr key=value ...] [--attr-file key=path ...] [--attr-stdin key] [--attributes-stdin]
update <id> [--title title] [--state active|closed] [--attr key=value ...] [--edge edge-type:to-id ...]
show <id>
supersede <old-id> <replacement-id>
burn <id>
list [--state active|closed|replaced] [--query name] [--param key=value ...]
ready [--query name] [--param key=value ...]
weave --pattern <name>
pattern explain <name>
weaver start
weaver repl [--stdin]
weaver stop
weaver status
```

## SPEC-002.P3 Contracts

- **SPEC-002.C1:** `--config-dir` selects a Skein world. Without it, the CLI uses `$XDG_CONFIG_HOME/skein` or `~/.config/skein` as the default config directory. An explicit config-dir creates a self-contained alternate world with config in that directory, runtime state under `state`, and weaver data under `data`.
- **SPEC-002.C2:** Client JSON config lives at `config.json` inside the selected config-dir. It must declare `"configFormat":"alpha"`. The alpha config format supports low-privilege keys `configFormat` and `source`; malformed config, missing/unsupported config format, unsupported keys, or wrong value types fail non-zero.
- **SPEC-002.C3:** `source`, when required, must resolve to an absolute path to the Skein source checkout root containing `deps.edn`. A leading `~` (or `~/`) is expanded to the user home directory before validation. Relative paths, missing directories, and directories without `deps.edn` fail before launching Clojure.
- **SPEC-002.C4:** Public strand/weaver commands emit JSON. `--format` and config-file output format settings are not supported.
- **SPEC-002.C5:** Normal strand/query/status/stop commands connect to `weaver.sock` for the selected world and do not require `source` once the weaver is running. They fail loudly when no weaver is running, when `weaver.json`/socket state is stale or malformed, or when protocol/identity verification fails.
- **SPEC-002.C6:** `add` creates a strand with generated id, lifecycle state, timestamps, and CLI attributes. `--state` defaults to `active` and accepts `active|closed`; `replaced` is reserved for supersession.
- **SPEC-002.C6a:** `--attr key=value` writes one string-valued attribute and may be repeated.
- **SPEC-002.C6b:** `--attr-file key=path` reads the exact file contents and writes that string as attribute `key`; it may be repeated.
- **SPEC-002.C6c:** `--attr-stdin key` reads all stdin and writes that string as attribute `key`; it may appear at most once.
- **SPEC-002.C6d:** `--attributes-stdin` reads exactly one JSON object from stdin and merges its properties into the attributes map, preserving JSON value types from that object.
- **SPEC-002.C6e:** `--attr-stdin` and `--attributes-stdin` are mutually exclusive because both consume stdin. Attribute merge precedence is `--attr` highest, then `--attr-file` / `--attr-stdin`, then `--attributes-stdin` lowest. Cross-priority duplicate keys are allowed and resolved by precedence; duplicate keys within one priority fail loudly.
- **SPEC-002.C7:** `update` patches title, lifecycle state, attributes, and strand edges for one existing strand. Generic update accepts `active|closed`; it cannot set `replaced`.
- **SPEC-002.C8:** `--edge edge-type:to-id` creates or updates an outgoing edge from the updated strand to the target strand.
- **SPEC-002.C9:** `add`, `update`, `supersede`, `show`, `list`, and `ready` return JSON from the weaver with normalized `attributes` and `state`; they do not emit old lifecycle fields `active` or `inactive_at`.
- **SPEC-002.C9a:** `supersede <old-id> <replacement-id>` delegates to the weaver supersession transaction, stores `replacement --supersedes--> old`, marks the old strand `replaced`, rewires incoming `depends-on` edges, and returns the normalized supersession result.
- **SPEC-002.C9b:** `burn <id>` physically deletes one strand and its incident edges, returning a JSON summary of burned ids and count.
- **SPEC-002.C10:** `ready` returns strands with `state="active"` and no direct `depends-on` target whose `state` is `active`.
- **SPEC-002.C11:** `list` and `ready` accept an optional named query from weaver memory with `--query` and repeated string-valued `--param key=value` runtime parameters. `list` also accepts optional `--state active|closed|replaced`; callers that care about lifecycle should pass it explicitly.
- **SPEC-002.C12:** `--where`, `--status`, and `--format` are not part of the public Go CLI. Rich EDN query authoring belongs in trusted weaver config and REPL workflows.
- **SPEC-002.C13:** The CLI has no query registry mutation/listing commands and does not accept `--query-file`; query loading is a trusted weaver config or REPL workflow, and registry contents last only for the weaver lifetime.
- **SPEC-002.C13a:** `weave --pattern <name>` reads exactly one JSON value from stdin, sends it to an already registered weaver-side pattern, and returns the pattern-created batch result as JSON with `created` rows and `refs`. Empty stdin, malformed JSON, trailing JSON values, missing/blank pattern names, and positional args fail before mutation.
- **SPEC-002.C13b:** `pattern explain <name>` sends only the pattern name to the weaver and returns JSON caller guidance for the registered input spec, including pattern name, optional doc string, function symbol, input spec name, spec form, a short summary, and expanded required/optional key specs when the input spec is a `clojure.spec.alpha/keys` form. Pattern registration is not exposed through the public CLI.
- **SPEC-002.C14:** Malformed options, malformed attribute key/value flags, blank attribute keys, invalid booleans, removed lifecycle fields, invalid edge targets, unreadable attribute files, malformed/trailing/non-object attribute JSON stdin, incompatible stdin-consuming flags, unknown commands, stale/missing metadata, socket transport/identity failures, malformed weaver responses, and database/domain errors fail non-zero. Strand commands against a reachable but uninitialized weaver store fail clearly with instructions to run `strand init`. Pattern input validation failures use the `pattern/input-invalid` code and include a human-readable message plus structured contract/problem details.
- **SPEC-002.C14a:** `strand init` is also the selected config-dir bootstrap command. Before initializing strand storage, it creates only missing alpha workspace files/directories: selected config-dir, `config.json`, `libs/`, `libs.edn`, `init.clj`, and a Git repository with `git init` when `.git` is missing. It never overwrites existing files. Created config uses `"configFormat":"alpha"` and `source` as the current Skein checkout. Generated `init.clj` requires `skein.libs.alpha` and calls `(libs/sync!)`.
- **SPEC-002.C15:** The Go CLI implementation uses Cobra rather than hand-rolled command dispatch or flag parsing. Root, command, subcommand, and flag help must clearly describe the supported command tree and accepted flags.
- **SPEC-002.C16:** `weaver start` resolves the selected config-dir, reads `config.json`, requires valid `source`, launches the Clojure weaver from that source in the foreground, and passes the selected config-dir into the weaver. The weaver owns storage selection and loads selected config-dir `init.clj` when present.
- **SPEC-002.C17:** `weaver repl` resolves the selected config-dir, reads `config.json`, requires valid `source`, verifies a reachable weaver for that world, and launches a local plain Clojure helper REPL from the source checkout already connected to the weaver.
- **SPEC-002.C18:** `weaver repl --stdin` reads Clojure forms from stdin, evaluates them in the same connected helper context as the interactive REPL, prints one direct normal Clojure result per top-level form, and exits non-zero on read/eval errors. It does not impose a JSON or EDN response envelope.
- **SPEC-002.C19:** `weaver repl` and `weaver repl --stdin` are the public CLI paths for users and agents that need to run trusted library-workspace Clojure code against a running weaver world.
- **SPEC-002.C20:** `weaver status` validates metadata and socket identity and reports health, selected config/data paths, weaver-owned database path, pid, weaver identity, socket endpoint, and nREPL endpoint. `weaver stop` stops only the matched weaver over the socket and waits for `weaver.edn`, `weaver.json`, and `weaver.sock` cleanup.
- **SPEC-002.C21:** Runtime library workspace and runtime transformation operations happen through selected config-dir `init.clj`, blessed `skein.*.alpha` namespaces, and trusted REPL workflows, not through strand/query CLI commands. Runtime library, view, and batch graph mutation support do not change the JSON socket allowlist or add package/library/view/batch activation commands.
- **SPEC-002.C22:** No `strand batch`, arbitrary JSON graph patch, or public batch mutation command is part of the current CLI surface. Users and agents that need transactional batch graph mutation use `strand weaver repl`, `strand weaver repl --stdin`, trusted config, activated libraries, or existing pattern-backed `weave` creation workflows.

## SPEC-002.P4 Deferred

`by-attr`, bespoke dependency inspection commands, `link`, `done`, `batch`, arbitrary JSON graph patch commands, public CLI EDN query expressions, query registry mutation commands, pattern registry mutation commands, view commands, plugin/package commands, compatibility `todo` binaries, legacy Clojure CLI entrypoints, and fallback discovery of old `atom` worlds or `daemon.*` artifacts are not part of the public CLI.
