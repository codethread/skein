# CLI Surface delta for Go CLI Migration

**Document ID:** `SPEC-002-D003` **Root spec:** [cli.md](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-25

## SPEC-002-D003.P1 Summary

Replace the JVM-backed scripted CLI entrypoint with a small Go executable named `todo`. The command surface remains thin and daemon-backed; the CLI parses flags, resolves client defaults, sends JSON requests to the selected daemon over a Unix domain socket, formats responses, and fails loudly on configuration, transport, identity, or domain errors.

## SPEC-002-D003.P2 Contract changes

- **SPEC-002-D003.CC1:** The CLI entrypoint becomes `todo [--db <path>] [--client-config <path>] [--format human|json] <command> [args]` instead of `clojure -M:todo ...`. `todo daemon start --config <path>` remains the trusted daemon startup config flag.
- **SPEC-002-D003.CC2:** Explicit CLI flags override client config defaults; absent flags are resolved from the XDG client config, then documented built-in defaults.
- **SPEC-002-D003.CC3:** `--db` continues to select the daemon/database runtime identity. Task and query commands require a matching reachable daemon and must not open SQLite directly.
- **SPEC-002-D003.CC4:** Task/query commands use the daemon's JSON Unix socket transport. The Go CLI must not evaluate query expressions or named query registry entries locally; it passes user input as request data to daemon operations.
- **SPEC-002-D003.CC5:** `init`, `add`, `update`, `show`, `list`, and `ready` preserve their existing command vocabulary, option names, status values, edge syntax, JSON/human output modes, and non-zero failure behavior unless a later delta explicitly changes a command.
- **SPEC-002-D003.CC6:** `list` and `ready` continue to accept the daemon query registry `--query name` and string-valued `--param key=value` behavior defined by `SPEC-002-D002.C1`, `SPEC-002-D002.C3`, and `SPEC-002-D002.U2` once that active delta is promoted.
- **SPEC-002-D003.CC7:** `--where EDN` is removed from the public Go CLI surface. Ad hoc rich query expressions belong in REPL/config workflows; the CLI invokes daemon query behavior via `--query name` and simple params.
- **SPEC-002-D003.CC8:** Client config follows XDG conventions. By default the CLI looks under `$XDG_CONFIG_HOME/todo/` or `~/.config/todo/` for a documented JSON config file.
- **SPEC-002-D003.CC9:** Client config may provide low-privilege client defaults such as database path and default output format. It must not load executable code, replace trusted daemon startup config, or redirect daemon runtime metadata discovery unless a later daemon-runtime delta defines that shared behavior.
- **SPEC-002-D003.CC10:** Malformed client config, unsupported config keys, stale runtime metadata, socket connection failures, daemon identity mismatch, malformed daemon responses, and daemon/domain errors fail non-zero with useful messages.
- **SPEC-002-D003.CC11:** `daemon status` reports the Unix socket endpoint in addition to the existing daemon health, canonical database path, pid, endpoint/transport, and identity information.
- **SPEC-002-D003.CC12:** Go implementation dependencies should remain narrow and conventional: Cobra may own command/subcommand parsing and help text; the standard library should own Unix socket dialing, JSON encoding/decoding, contexts, errors, and baseline tests; XDG discovery may use a small focused helper such as `adrg/xdg`.
- **SPEC-002-D003.CC13:** EDN is removed from the public Go CLI surface. `--format edn`, `--where EDN`, and EDN-shaped CLI output are not supported by the migrated CLI; EDN remains available in the Clojure REPL and trusted daemon config layers.
- **SPEC-002-D003.CC14:** The daemon/engine may translate between JSON wire data and Clojure/EDN-native internal data, but that translation is an engine concern hidden behind the JSON socket API.

## SPEC-002-D003.P3 Design decisions

### SPEC-002-D003.D1 Native thin client

- **Decision:** The public scripted CLI becomes a Go binary, but remains only a parser/transport/formatter around daemon operations.
- **Rationale:** This removes per-command JVM startup while preserving daemon-core ownership of storage, query registry state, and runtime customization.
- **Rejected:** Reimplementing task persistence or query execution in Go, because that would split domain semantics across two implementations.

### SPEC-002-D003.D2 XDG client config is low privilege

- **Decision:** Client config is limited to defaults and discovery settings.
- **Rationale:** Runtime customization belongs in trusted daemon config and REPL workflows; the CLI should remain safe for common scripted operation.
- **Rejected:** Loading user code or query definitions from client config.

### SPEC-002-D003.D3 Small idiomatic Go dependency set

- **Decision:** Prefer standard library packages for protocol, transport, timeouts, errors, and tests; use Cobra only for CLI command ergonomics and a small XDG helper only for path convention correctness.
- **Rationale:** The CLI is intentionally thin, so broad frameworks would add more surface area than value. Cobra is idiomatic for non-trivial Go CLIs with subcommands, while `net`, `encoding/json`, and `context` are the idiomatic choices for the daemon client itself.
- **Rejected:** Large all-in-one config/framework stacks by default, including adopting Viper merely because Cobra is used.

## SPEC-002-D003.P4 Open questions

- **SPEC-002-D003.Q1:** Resolved by `GOCLI-PROTO-001.L5-L7`: in development, foreground `todo daemon start` execs the Clojure daemon entrypoint as the long-lived process, forwards `--db` and trusted `--config`, streams process I/O, and surfaces startup/config failures loudly before metadata publication.
