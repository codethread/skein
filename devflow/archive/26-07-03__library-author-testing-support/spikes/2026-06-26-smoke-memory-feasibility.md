# Spike: In-Memory Smoke Feasibility

**Spike ID:** `SPIKE-2026-06-26-005`
**Status:** Open
**Date:** 2026-06-26
**Related RFC:** [`RFC-005 Library Author Testing Support`](../rfcs/2026-06-26-library-author-testing.md)

## Question

If real Xerial SQLite in-memory daemon storage becomes available, which parts of Atom's smoke workflow could run in memory, and which should remain file-backed to validate the normal user-facing world layout?

## Context

The current smoke suite in `dev/todo/smoke.clj` is file-backed. It validates:

- Go CLI build and command help
- config-dir bootstrap
- daemon lifecycle
- JSON socket CLI task commands
- connected daemon REPL
- runtime library sync/use workflows
- metadata/status reporting
- cleanup of runtime and SQLite artifacts

The Go CLI never opens SQLite directly; it talks to the daemon. Therefore, CLI task flows could theoretically work with an in-memory daemon DB if daemon startup supports it. However, file-backed smoke also validates selected-world data paths and cleanup behavior.

## Scope

Classify smoke coverage by whether it requires file-backed SQLite or only requires a daemon with task storage.

Categories:

- must remain file-backed
- can run against in-memory storage
- should run in both modes
- unrelated to storage mode

## Non-goals

- No implementation of in-memory runtime.
- No replacement of canonical smoke unless evidence supports it.
- No CLI flag design.
- No package/library helper API design.

## Suggested experiment

1. Read `dev/todo/smoke.clj` and list each smoke section.
2. Identify assertions that rely on filesystem DB paths or SQLite artifact cleanup.
3. Identify daemon/CLI flows that only require live task storage.
4. Propose a split between canonical file-backed smoke and optional in-memory smoke.
5. Estimate whether in-memory would materially reduce runtime or mostly simplify cleanup.

## Acceptance evidence

- Table of smoke sections and storage requirements.
- Recommendation on whether full smoke should remain file-backed.
- Recommendation on whether to add an in-memory smoke variant later.
- List of assertions that would need metadata/storage changes.

## Output

Write findings back into this file under `## Findings`, with a recommended smoke strategy.

## Findings

### Smoke section classification

| Smoke section / helper | Storage requirement | Evidence |
| --- | --- | --- |
| `build-cli!`, `smoke-cli-help!` | Unrelated to storage mode | Only builds `cli/bin/todo` and checks Cobra help text. No daemon or DB path is used. |
| Config-dir cleanup helpers (`clean-runtime-artifacts!`, `delete-runtime-metadata!`, `delete-tree!`) | Must remain in file-backed canonical smoke | They explicitly remove `data/tasks.sqlite` plus SQLite sidecars and daemon metadata/socket files. This validates smoke leaves no generated SQLite/runtime artifacts behind. |
| `smoke-bootstrap-clean-config!` | Should remain file-backed in canonical smoke; task sub-flow can run in memory | The bootstrap assertions are about real config-dir files: `config.json`, `libs.edn`, `init.clj`, `libs/`, `.git`. Its post-init task add/show only needs a live daemon storage backend. |
| `smoke-bootstrap-dirty-config!` | Should remain file-backed in canonical smoke; task/query sub-flow can run in memory | The important coverage is preserving existing config files and startup query loading from `init.clj`. The `Dirty owned task` query check only needs live daemon storage. |
| `smoke-startup-transformations!` | Can run against in-memory storage, but still needs a real config-dir filesystem | It writes `init.clj` and uses startup-registered query/view functions. Task creation/query/view hydration only require daemon storage. No SQLite file assertion. |
| `smoke-live-library-reload!` | Can run against in-memory storage, but still needs real config-dir/library files | It edits `libs.edn`, creates local library roots, writes/reads marker files, and verifies daemon health after library failures. Task storage is only used for `Live owned task` after a query is registered. |
| Main CLI task graph flow in `smoke-cli!` | Should run in both modes if in-memory daemon startup exists | `add`, `update --edge`, `ready`, `show`, and `list --query` exercise the JSON socket CLI against daemon-owned task storage. They do not require persisted SQLite files. File-backed mode should keep validating selected-world `data/tasks.sqlite`. |
| CLI `daemon status` assertions | Needs metadata changes before a pure in-memory variant | Current smoke asserts `:socket_path` equals `metadata/socket-file` for the world. Status also reports `database_path` from `:canonical-db-path`; metadata requires it to be a string. In-memory storage needs an explicit metadata representation that the Go client can echo/validate. |
| CLI `daemon repl --stdin` flow | Can run against in-memory storage | It only depends on a connected daemon, live task storage, runtime query state, and library sync/use state. It does not need DB files. |
| `smoke-repl!` | Should run in both modes | It starts `runtime/start!` directly, connects helpers, mutates tasks/edges, and registers a daemon-lifetime query. The current call passes a file path, but the behavior under test is storage-backed daemon semantics rather than file persistence. |

### Assertions tied to file-backed SQLite or metadata paths

- `smoke-world-db` hard-codes the selected-world DB as `<config-dir>/data/tasks.sqlite`.
- `clean-runtime-artifacts!` deletes SQLite families for both the legacy smoke db name and selected-world `data/tasks.sqlite`, then deletes config-dir trees.
- `runtime/start!` currently chooses `(:db-path world)` when no `db-file` is supplied and canonicalizes every `db-file` through `metadata/canonical-db-path`.
- `todo.db/datasource` calls `io/make-parents` and builds `jdbc:sqlite:<db-file>`, which is file-path oriented today.
- Daemon metadata (`daemon.edn`, `daemon.json`, socket status) always includes `:canonical-db-path` / `database_path`; `metadata/stale-or-missing?` requires it to be a string.
- Go socket client status validation compares status `database_path`, `daemon_id`, `socket_path`, `config_dir`, and `data_dir` against on-disk metadata, so any in-memory mode still needs stable metadata semantics.

### Recommendation

Keep full `clojure -M:smoke` file-backed. It is the only smoke path that validates the normal user-facing world layout: config-dir bootstrap, `state/daemon.*`, `state/daemon.sock`, `data/tasks.sqlite`, local runtime libraries, and cleanup of SQLite/runtime artifacts.

If Xerial in-memory daemon storage is added, add a smaller optional smoke variant rather than replacing canonical smoke. The useful in-memory variant should cover daemon startup, CLI task graph commands, `daemon repl --stdin`, runtime query/view state, and direct `todo.repl` helpers. It should still use a disposable config-dir for `init.clj`, `libs.edn`, sockets, and library files unless a separate no-files runtime mode is intentionally designed.

Expected runtime savings are likely modest: the smoke suite spends meaningful time on Go build, daemon process startup, Clojure startup, nREPL/socket setup, config-dir bootstrap, and library sync/reload. In-memory storage would mostly simplify DB artifact cleanup and make task-flow smoke independent of file persistence, not eliminate the heaviest process/classpath costs.
