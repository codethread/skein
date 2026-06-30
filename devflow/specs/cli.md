# CLI Surface

**Document ID:** `SPEC-002`
**Status:** Implemented
**Last Updated:** 2026-06-30
**Related RFCs:** [RFC-002 Task Query DSL](../rfcs/2026-06-24-task-query-dsl.md), [RFC-003 Fast JSON Socket CLI](../archive/26-06-25__go-cli-migration/rfcs/2026-06-25-fast-json-socket-cli.md), [RFC-004 Go CLI Migration](../archive/26-06-25__go-cli-migration/rfcs/2026-06-25-go-cli-migration.md)
**Code:** `cli/`, `src/skein/weaver`

## SPEC-002.P1 Purpose

The CLI is the primary scripted interface for coding agents. It exposes a deliberately small strand surface: bootstrap repo config, create strands, update strands, burn strands, inspect strands, list strands, ask for ready work, and manage the local weaver runtime.

The public strand CLI is a thin Go executable named `strand`. It uses Cobra for command, subcommand, flag parsing, and help text; sends selected-world requests to the local `mill` router; emits JSON for public strand/weaver commands; and never opens SQLite or evaluates rich query definitions locally.

Skein also ships an alpha local router executable named `mill`. `mill start` creates the Skein XDG state root, publishes `mill.json` and `mill.sock`, and listens in the foreground for local requests; `mill status` performs a minimal health request against the active mill.

## SPEC-002.P2 Interface

Entrypoints:

```text
strand [--config-dir <dir>] <command> [args]
mill start
mill status
```

Commands:

```text
init
add <title> [--state active|closed] [--attr key=value ...] [--attr-file key=path ...] [--attr-stdin key] [--attributes-stdin] [--edge edge-type:to-id ...]
update <id> [--title title] [--state active|closed] [--attr key=value ...] [--edge edge-type:to-id ...]
show <id>
supersede <old-id> <replacement-id>
burn <id>
list [--state active|closed|replaced] [--query name] [--param key=value ...]
ready [--query name] [--param key=value ...]
weave --pattern <name>
pattern explain <name>
op <name> [args...]
weaver start
weaver repl [--stdin]
weaver stop
weaver status
```

## SPEC-002.P3 Contracts

- **SPEC-002.C1:** Public `strand` commands require a reachable local `mill` entrypoint. If no mill is running or its metadata/socket is stale, commands fail non-zero with remediation to run `mill start`.
- **SPEC-002.C1a:** `--config-dir` selects a Skein world and is the highest-precedence override. Without it, implicit repo selection requires cwd to be inside a Git worktree; the selected config-dir is the Git root's `.skein` directory. Outside Git, no-flag commands fail loudly rather than creating cwd worlds. Runtime state, sockets, metadata, and SQLite data live under mill-owned XDG state paths, not inside the selected config-dir.
- **SPEC-002.C2:** Client JSON config lives at `config.json` inside the selected config-dir. It must declare `"configFormat":"alpha"`. The alpha config format supports low-privilege keys `configFormat` and `source`; malformed config, missing/unsupported config format, unsupported keys, or wrong value types fail non-zero.
- **SPEC-002.C3:** `source`, when required, must resolve to an absolute path to the Skein source checkout root containing `deps.edn`. A leading `~` (or `~/`) is expanded to the user home directory before validation. Relative paths, missing directories, and directories without `deps.edn` fail before launching Clojure.
- **SPEC-002.C4:** Public strand/weaver commands emit JSON. `--format` and config-file output format settings are not supported.
- **SPEC-002.C5:** Normal strand/query/status/stop commands send requests to `mill`, which routes them to the selected world's already-running weaver. They do not require `source` once the weaver is running. They fail loudly when mill is unavailable, no selected-world weaver is running, runtime metadata/socket state is stale or malformed, or protocol/identity verification fails.
- **SPEC-002.C6:** `add` creates a strand with generated id, lifecycle state, timestamps, and CLI attributes. `--state` defaults to `active` and accepts `active|closed`; `replaced` is reserved for supersession.
- **SPEC-002.C6a:** `--attr key=value` writes one string-valued attribute and may be repeated.
- **SPEC-002.C6b:** `--attr-file key=path` reads the exact file contents and writes that string as attribute `key`; it may be repeated.
- **SPEC-002.C6c:** `--attr-stdin key` reads all stdin and writes that string as attribute `key`; it may appear at most once.
- **SPEC-002.C6d:** `--attributes-stdin` reads exactly one JSON object from stdin and merges its properties into the attributes map, preserving JSON value types from that object.
- **SPEC-002.C6e:** `--attr-stdin` and `--attributes-stdin` are mutually exclusive because both consume stdin. Attribute merge precedence is `--attr` highest, then `--attr-file` / `--attr-stdin`, then `--attributes-stdin` lowest. Cross-priority duplicate keys are allowed and resolved by precedence; duplicate keys within one priority fail loudly.
- **SPEC-002.C7:** `update` patches title, lifecycle state, attributes, and strand edges for one existing strand. Generic update accepts `active|closed`; it cannot set `replaced`.
- **SPEC-002.C8:** `--edge edge-type:to-id` on `add` or `update` creates or updates an outgoing edge from the new/updated strand to the target strand.
- **SPEC-002.C9:** `add`, `update`, `supersede`, `show`, `list`, and `ready` return JSON from the weaver with normalized `attributes` and `state`; they do not emit old lifecycle fields `active` or `inactive_at`.
- **SPEC-002.C9a:** `supersede <old-id> <replacement-id>` delegates to the weaver supersession transaction, stores `replacement --supersedes--> old`, marks the old strand `replaced`, rewires incoming `depends-on` edges, and returns the normalized supersession result.
- **SPEC-002.C9b:** `burn <id>` physically deletes one strand and its incident edges, returning a JSON summary of burned ids and count.
- **SPEC-002.C10:** `ready` returns strands with `state="active"` and no direct `depends-on` target whose `state` is `active`.
- **SPEC-002.C11:** `list` and `ready` accept an optional named query from weaver memory with `--query` and repeated string-valued `--param key=value` runtime parameters. `list` also accepts optional `--state active|closed|replaced`; callers that care about lifecycle should pass it explicitly.
- **SPEC-002.C12:** `--where`, `--status`, and `--format` are not part of the public Go CLI. Rich EDN query authoring belongs in trusted weaver config and REPL workflows.
- **SPEC-002.C13:** The CLI has no query registry mutation/listing commands and does not accept `--query-file`; query loading is a trusted weaver config or REPL workflow, and registry contents last only for the weaver lifetime.
- **SPEC-002.C13a:** `weave --pattern <name>` reads exactly one JSON value from stdin, sends it to an already registered weaver-side pattern, and returns the pattern-created batch result as JSON with `created` rows and `refs`. Empty stdin, malformed JSON, trailing JSON values, missing/blank pattern names, and positional args fail before mutation.
- **SPEC-002.C13b:** `pattern explain <name>` sends only the pattern name to the weaver and returns JSON caller guidance for the registered input spec, including pattern name, optional doc string, function symbol, input spec name, spec form, a short summary, and expanded required/optional key specs when the input spec is a `clojure.spec.alpha/keys` form. Pattern registration is not exposed through the public CLI.
- **SPEC-002.C13c:** `op <name> [args...]` sends a non-blank registered operation name and raw string argv to the weaver-side operation registry. The Go CLI does not parse userland flags after `op`; operation registration and handler code are trusted config/REPL workflows. `op help` is a built-in registered operation that returns JSON guidance for custom invocation usage and currently registered operations.
- **SPEC-002.C14:** Malformed options, malformed attribute key/value flags, blank attribute keys, invalid booleans, removed lifecycle fields, invalid edge targets, unreadable attribute files, malformed/trailing/non-object attribute JSON stdin, incompatible stdin-consuming flags, missing/blank operation names, unknown commands, stale/missing metadata, socket transport/identity failures, malformed weaver responses, database/domain errors, and lifecycle hook rejections fail non-zero. Weaver startup initializes or validates the selected SQLite store before publishing ready metadata, so normal strand commands do not depend on a public database init operation. Lifecycle hook failures use the `hook/failed` domain error code and carry structured hook details, including original hook cause code when present. Pattern input validation failures use the `pattern/input-invalid` code and include a human-readable message plus structured contract/problem details.
- **SPEC-002.C14a:** `strand init` is the selected config-dir bootstrap command only. It asks `mill` to create or complete missing alpha workspace files/directories and never contacts a weaver, initializes SQLite storage, or runs `git init`. Without `--config-dir`, it creates or completes `.skein` at the Git repository root and fails loudly outside Git. With explicit `--config-dir`, it bootstraps that directory directly. Bootstrap creates only missing config workspace files/directories: selected config-dir, `config.json` when source can be resolved, `libs/`, `libs.edn`, `init.clj`, and `.gitignore`; it never overwrites existing files. Created config uses `"configFormat":"alpha"` and resolves `source` by `--source`, `SKEIN_SOURCE`, then cwd when cwd validates as a Skein checkout; otherwise init fails with remediation. Repo `.skein/.gitignore` ignores local overlays and accidental runtime artifacts including `config.json`, `init.local.clj`, `libs.local.edn`, `state/`, `data/`, `weaver.*`, and SQLite artifacts. Generated `init.clj` requires `skein.libs.alpha` and calls `(libs/sync!)`.
- **SPEC-002.C15:** The Go CLI implementation uses Cobra rather than hand-rolled command dispatch or flag parsing. Root, command, subcommand, and flag help must clearly describe the supported command tree and accepted flags.
- **SPEC-002.C16:** `weaver start` asks `mill` to resolve the selected world and start that world's Clojure weaver as a mill child process using mill-derived XDG state/data dirs. It returns selected-world JSON status and does not run the weaver in the foreground terminal. A selected config-dir without local source config fails with remediation to run `strand init --source <skein-source>` or set `SKEIN_SOURCE`. The weaver owns storage selection/preparation and loads selected config-dir startup files in order: `init.clj`, then `init.local.clj`; missing files are skipped and present failing files fail startup loudly.
- **SPEC-002.C17:** `weaver repl` asks `mill` to resolve the selected world and verify that world's weaver is running, then launches a local plain Clojure helper REPL from the configured source checkout already connected directly to the weaver nREPL endpoint. It passes explicit selected config/state metadata to the helper process.
- **SPEC-002.C18:** `weaver repl --stdin` reads Clojure forms from stdin, evaluates them in the same connected helper context as the interactive REPL, prints one direct normal Clojure result per top-level form, and exits non-zero on read/eval errors. It does not impose a JSON or EDN response envelope.
- **SPEC-002.C19:** `weaver repl` and `weaver repl --stdin` are the public CLI paths for users and agents that need to run trusted library-workspace Clojure code against a running weaver world.
- **SPEC-002.C20:** `weaver status` asks `mill` for selected-world status and reports health, selected config/data paths, weaver-owned database path, pid, weaver identity, socket endpoint, and nREPL endpoint when running. `weaver stop` asks `mill` to stop only the selected-world weaver child and clean that world's runtime metadata/socket.
- **SPEC-002.C21:** Runtime library workspace, runtime transformation, and lifecycle hook operations happen through selected config-dir `init.clj`, blessed `skein.*.alpha` namespaces, and trusted REPL workflows, not through strand/query CLI commands. Runtime library, view, custom operation, lifecycle hook, and batch graph mutation support do not add package/library/view/hook/batch activation commands.
- **SPEC-002.C22:** No `strand batch`, arbitrary JSON graph patch, or public batch mutation command is part of the current CLI surface. Users and agents that need transactional batch graph mutation use `strand weaver repl`, `strand weaver repl --stdin`, trusted config, activated libraries, or existing pattern-backed `weave` creation workflows.

- **SPEC-002.C23:** The public CLI has no hook registration, listing, debugging, schema, coercion, or workflow-rule commands. Existing attribute input contracts are unchanged: string-oriented attribute flags send strings, while `--attributes-stdin` preserves JSON object value types. Trusted weaver-side lifecycle hooks may normalize attributes or reject decoded requests/candidate mutations after they reach the weaver. The Go CLI does not infer types, retry, locally repair, coerce, or partially apply hook-rejected commands.
- **SPEC-002.C24:** Received-payload hooks may gate public JSON socket operations `add`, `update`, `supersede`, `burn`, `weave`, and `op` after protocol, identity, allowlist, and argument-shape validation. Setup, administrative, and read-only commands such as `init`, `weaver status`, `weaver stop`, `show`, `list`, `ready`, and `pattern explain` are not gated by received-payload hooks. Non-allowlisted operations remain unavailable regardless of registered hooks.
- **SPEC-002.C25:** Hook-approved successful CLI commands preserve existing JSON result shapes, except normalized strand attributes may reflect weaver-side `:attributes/normalize` hooks.

## SPEC-002.P4 Deferred

`by-attr`, bespoke dependency inspection commands, `link`, `done`, `batch`, arbitrary JSON graph patch commands, public CLI EDN query expressions, query registry mutation commands, pattern registry mutation commands, view commands, plugin/package commands, compatibility `todo` binaries, legacy Clojure CLI entrypoints, and fallback discovery of old `atom` worlds or `daemon.*` artifacts are not part of the public CLI.
