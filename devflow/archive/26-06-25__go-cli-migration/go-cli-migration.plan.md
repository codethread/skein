# Go CLI Migration Plan

**Document ID:** `GOCLI-PLAN-001` **Feature:** `go-cli-migration` **Proposal:** [proposal.md](./proposal.md) **RFC:** [RFC-003 Fast JSON Socket CLI](./rfcs/2026-06-25-fast-json-socket-cli.md), [RFC-004 Go CLI Migration](./rfcs/2026-06-25-go-cli-migration.md) **Root specs:** [CLI Surface `SPEC-002`](../../specs/cli.md), [Daemon Runtime `SPEC-004`](../../specs/daemon-runtime.md), [Project Tenets](../../TENETS.md) **Feature specs:** [CLI delta `SPEC-002-D003`](./specs/cli.delta.md), [Daemon Runtime delta `SPEC-004-D002`](./specs/daemon-runtime.delta.md), [JSON Socket Protocol `GOCLI-PROTO-001`](./specs/json-socket-protocol.md), [Tenets delta `TEN-D001`](./specs/tenets.delta.md) **Status:** Shipped **Last Updated:** 2026-06-25

## GOCLI-PLAN-001.P1 Goal and scope

Migrate the scripted CLI from `clojure -M:todo ...` to a native Go `todo` executable that talks to the daemon over a local JSON Unix socket, while the Clojure daemon remains the owner of SQLite, query registry state, trusted config, and REPL workflows. The feature also promotes the JSON-thin-CLI/EDN-rich-daemon boundary into tenets and updates smoke/docs/specs to remove public CLI EDN.

## GOCLI-PLAN-001.P2 Approach

- **GOCLI-PLAN-001.A1:** Keep the daemon semantic API as the integration center. First freeze a small JSON protocol and Go-readable runtime metadata contract, then add a JSON socket transport in the Clojure daemon that decodes allowlisted operation requests, dispatches to existing daemon operations, normalizes results/errors, and publishes the socket path in runtime metadata.
- **GOCLI-PLAN-001.A2:** Introduce `cli/` as a separate Go module for the native CLI. Keep the Clojure engine in the current layout for this feature unless a later repo-layout task explicitly moves Clojure code; avoid mixing Go source into `src/`.
- **GOCLI-PLAN-001.A3:** Implement the Go CLI as parser/config/client/output layers: Cobra for command tree if it keeps parsing simple, standard library for Unix sockets/JSON/context/errors/tests, and a small XDG helper for config path discovery.
- **GOCLI-PLAN-001.A4:** Preserve command vocabulary for `init`, `add`, `update`, `show`, `list`, `ready`, and `daemon status|stop`; remove public CLI EDN and `--where EDN`; keep `daemon start --config` as trusted Clojure daemon config and use global `--client-config` only for low-privilege JSON client defaults.
- **GOCLI-PLAN-001.A5:** Use an explicit foreground launcher contract for `todo daemon start`: in dev, the Go CLI execs the Clojure daemon entrypoint as the long-lived foreground process, forwards `--db` and trusted `--config`, streams output, and fails before metadata publication when startup/config loading fails. Task/query/status/stop commands must connect through metadata and the JSON socket and must never shell out to the old Clojure CLI or open SQLite directly.
- **GOCLI-PLAN-001.A6:** Retain nREPL and Clojure client/REPL paths for development and rich runtime workflows. They may continue using EDN internally; only the public Go CLI surface becomes JSON/human.

## GOCLI-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| GOCLI-PLAN-001.AA1 | `src/todo/daemon` | Add Go-readable runtime metadata, JSON Unix socket runtime, allowlisted request dispatch, response/error envelopes, and shutdown cleanup. |
| GOCLI-PLAN-001.AA2 | `src/todo/client` / `src/todo/cli.clj` | Keep as daemon/REPL/dev support during migration; remove or demote public CLI use after Go command parity is proven. |
| GOCLI-PLAN-001.AA3 | `cli/` | New Go module containing command parsing, XDG JSON config, daemon socket client, output formatting, and Go tests. |
| GOCLI-PLAN-001.AA4 | `test/todo` and Go tests | Add daemon socket tests and Go CLI unit/integration coverage; update existing Clojure CLI tests to reflect retained/internal versus replaced behavior. |
| GOCLI-PLAN-001.AA5 | `dev/todo/smoke.clj`, `README.md`, `AGENTS.md`, `devflow/specs/*` | Switch smoke/docs/spec promotion to `todo` JSON/human CLI usage and keep EDN examples in REPL/config-only contexts. |

## GOCLI-PLAN-001.P4 Contract and migration impact

- **GOCLI-PLAN-001.CM1:** `SPEC-002-D003` removes `--format edn` and `--where EDN` from the public CLI, changes the entrypoint to `todo`, adds `--client-config`, and preserves named query invocation through `--query name --param key=value`.
- **GOCLI-PLAN-001.CM2:** `SPEC-004-D002` adds a local JSON Unix socket transport and Go-readable socket metadata while preserving nREPL metadata needed by Clojure clients.
- **GOCLI-PLAN-001.CM3:** `TEN-D001` adds the durable design boundary: CLI is the thin JSON control surface; daemon/REPL is the rich semantic surface.
- **GOCLI-PLAN-001.CM4:** Root-level repository layout may grow `cli/` immediately. Moving current Clojure `src/`, `test/`, `dev/`, and `deps.edn` under an `atom/` engine folder is useful but not required for the first working migration; do it only as a distinct phase if it can be validated without obscuring the transport/CLI work.

## GOCLI-PLAN-001.P5 Implementation phases

### GOCLI-PLAN-001.PH1 Protocol, metadata, and launcher contracts

Outcome: The feature freezes a minimal JSON operation envelope, response/error envelope, operation allowlist, timeout semantics, Go-readable runtime metadata shape, status/stop semantics, and foreground `daemon start` launcher contract before daemon and Go implementation split.

### GOCLI-PLAN-001.PH2 JSON socket daemon path

Outcome: The Clojure daemon publishes Go-readable metadata with a local Unix socket endpoint plus retained nREPL endpoint data, accepts JSON operation requests only for the Go CLI allowlist, dispatches to daemon semantics, returns structured success/error envelopes, and cleans up socket artifacts on stop.

### GOCLI-PLAN-001.PH3 Go CLI skeleton, config, and build path

Outcome: A new `cli/` Go module provides a buildable `todo` binary, command tree, global `--db`, `--format human|json`, `--client-config`, XDG JSON defaults, usage/help text, and validation for unsupported EDN/`--where` options before wiring all operations.

### GOCLI-PLAN-001.PH4 Go task/query command parity

Outcome: The Go CLI implements `init`, `add`, `update`, `show`, `list`, and `ready` over the JSON socket with JSON/human formatting, repeated `--attr`/`--edge`/`--param` parsing, named query params, current row normalization expectations, and loud failure behavior.

### GOCLI-PLAN-001.PH5 Daemon lifecycle and end-to-end migration

Outcome: `todo daemon start [--config trusted.edn]` starts the Clojure daemon as the documented foreground long-lived process, `daemon status` performs metadata inspection plus socket health/identity checks, `daemon stop` stops the matched daemon over the socket, and smoke tests prove independent Go CLI invocations create, query, update, and stop task data.

### GOCLI-PLAN-001.PH6 Documentation, spec promotion, and cleanup

Outcome: User/agent docs, smoke flows, and root specs reflect `todo` as the public CLI, JSON as the only machine CLI format, and EDN as REPL/config-only; obsolete public Clojure CLI assumptions are removed or marked internal.

## GOCLI-PLAN-001.P6 Validation strategy

- **GOCLI-PLAN-001.V1:** Clojure unit tests cover Go-readable metadata publication, transitional nREPL metadata compatibility, local-only socket lifecycle, allowlisted JSON request dispatch, identity/mismatch failures, response error envelopes, and cleanup on daemon stop.
- **GOCLI-PLAN-001.V2:** Go tests cover command parsing, `--client-config` precedence, XDG config loading, JSON output formatting, socket client success/error handling, compatibility fixtures adapted from existing Clojure CLI tests, and explicit rejection of `--format edn`/`--where`.
- **GOCLI-PLAN-001.V3:** Integration/smoke validation starts a real daemon, invokes the Go `todo` binary from separate processes, exercises task creation/update/readiness/named query usage, verifies `daemon status` includes socket data, and stops the daemon.
- **GOCLI-PLAN-001.V4:** Project validation runs the Clojure test suite, Go test suite, smoke suite, and a manual tmux daemon verification before shipping.

## GOCLI-PLAN-001.P7 Risks and open questions

- **GOCLI-PLAN-001.R1:** Unix socket support and path lengths can vary by platform. Mitigation: keep sockets under the existing runtime metadata area with short hashed names and test cleanup/stale metadata paths.
- **GOCLI-PLAN-001.R2:** Response shape drift between Clojure and Go can create brittle clients. Mitigation: define small request/response envelopes and golden fixtures first, then test them from both sides.
- **GOCLI-PLAN-001.R3:** Daemon lifecycle from Go can accidentally become a shell-out wrapper around the old CLI. Mitigation: allow launching the Clojure daemon entrypoint only for foreground `daemon start`; all task/query/status/stop commands must use Go-readable metadata and JSON socket.
- **GOCLI-PLAN-001.R4:** Repository layout changes could obscure behavior changes. Mitigation: introduce `cli/` now, and defer any `atom/` engine move unless implemented as an isolated mechanical slice with full validation.
- **GOCLI-PLAN-001.R5:** Exposing the full daemon API over JSON would violate the low-privilege CLI boundary. Mitigation: JSON socket operation allowlist excludes query registry mutation/listing/inspection; rich registry management remains in REPL/config workflows.

## GOCLI-PLAN-001.P8 Task context

- **GOCLI-PLAN-001.TC1:** Treat `SPEC-002-D002` and `SPEC-004-D001` query-registry behavior as landed or stabilized for implementation: named queries are daemon-owned, CLI query mutation is out of scope, and CLI query access is by `--query name` plus string params.
- **GOCLI-PLAN-001.TC2:** Public CLI EDN is intentionally removed. Do not add EDN parsing libraries to the Go CLI and do not preserve `--format edn` as a compatibility flag.
- **GOCLI-PLAN-001.TC3:** Keep rich query authoring/debugging in trusted Clojure config and REPL helpers. Go tasks should not implement query inspection or mutation commands.
- **GOCLI-PLAN-001.TC4:** The new Go module should live under `cli/`; any larger repo split into `atom/` and `cli/` should be a separate explicit task, not incidental churn.
- **GOCLI-PLAN-001.TC5:** The initial development invocation is `go build -o ./cli/bin/todo ./cli/cmd/todo` (or `go test ./...` from `cli/` for tests). Smoke/docs may use that repo-local binary until packaging is defined.
- **GOCLI-PLAN-001.TC6:** JSON socket operations for this feature are allowlisted to public CLI behavior: `init`, `add`, `update`, `show`, `list`, `ready`, `list-query`, `ready-query`, `status`, and `stop`. Do not expose `register-query`, `load-queries`, `queries`, or `resolve-query` over the low-privilege JSON transport.
- **GOCLI-PLAN-001.TC7:** Runtime metadata consumed by Go must be JSON or another Go-readable format using only standard/focused Go dependencies; do not add EDN parsing to the Go CLI.

## GOCLI-PLAN-001.P9 Developer Notes

### GOCLI-PLAN-001.DN1 Plan authored — 2026-06-25

- Plan assumes the earlier daemon query registry work is effectively complete and uses the stable active delta IDs as dependency anchors. Deep review should check whether the task queue slices are safe to generate before marking this plan Reviewed.

### GOCLI-PLAN-001.DN2 Plan reviewed — 2026-06-25

- Deep review found no remaining issues after adding explicit protocol, Go-readable metadata, operation allowlist, foreground daemon start, status/stop, and build-path planning. Plan marked Reviewed and ready for task slicing.

### GOCLI-PLAN-001.DN3 Task 1 protocol freeze — 2026-06-25

- Added feature-local protocol note `specs/json-socket-protocol.md` with stable IDs for JSON runtime metadata, one-request-per-connection newline framing, request/response/error envelopes, timeout behavior, operation allowlist/exclusions, status/stop identity checks, and foreground `daemon start` launcher semantics.
- Added golden JSON fixtures under `specs/fixtures/` for one success response and one domain error response so later Clojure and Go tests can consume the same contract examples.

### GOCLI-PLAN-001.DN4 Task 2 daemon socket runtime — 2026-06-25

- Added Clojure JSON Unix socket transport alongside nREPL, with JSON metadata publication retaining EDN/nREPL compatibility.
- Socket filenames use a shortened database hash under the runtime directory because Unix domain socket path length limits reject the full metadata hash path on macOS temp directories; JSON metadata is the authoritative advertised socket path.
- The transport dispatches only the Go CLI allowlist to `todo.daemon.api`; registry mutation/listing/inspection operations remain unavailable over JSON.

### GOCLI-PLAN-001.DN5 Task 3 Go CLI skeleton — 2026-06-25

- Added `cli/` as a standalone Go module plus root `go.work` so the planned root build command works before packaging is defined.
- Current Go commands validate flags/config/help and then intentionally fail at daemon socket stubs for task/query/status/stop operations; future wiring tasks should replace only the socket client layer and command argument payload construction.
- Client config is JSON-only with supported keys `db` and `format`; unsupported keys and malformed config fail before command execution.

### GOCLI-PLAN-001.DN6 Task 4 Go task commands — 2026-06-25

- Wired Go `init`, `add`, `update`, and `show` through JSON runtime metadata and one-request-per-connection Unix socket calls; task paths no longer use the Clojure CLI or SQLite.
- Go canonical database path handling resolves symlinked parent directories so `/tmp/...` paths match the Clojure daemon's canonical `/private/tmp/...` metadata on macOS.
- Query and daemon lifecycle commands remain intentionally unwired for later slices.

### GOCLI-PLAN-001.DN7 Task 5 Go query commands — 2026-06-25

- Wired Go `list`, `ready`, `list-query`, and `ready-query` over the JSON socket with string-valued `--param` maps and loud `--where` rejection.
- The JSON socket now converts wire query names/params into the daemon's existing symbol/keyword query API, keeping registry mutation APIs unchanged and unexposed.
- Added a Go integration test that starts a real Clojure daemon with a trusted config-loaded query and consumes it via `go run ./cmd/todo list --query ...`.

### GOCLI-PLAN-001.DN8 Task 6 daemon lifecycle commands — 2026-06-25

- Wired Go `daemon start` as a foreground Clojure launcher that forwards `--db` and trusted `--config`, preserving daemon stdio and exit status.
- Wired `daemon status` and `daemon stop` over the JSON socket; stop now waits for metadata/socket cleanup before returning.
- Added Go integration coverage for lifecycle status/stop, trusted config loading through the Go launcher, and startup config failure leaving no published metadata.

### GOCLI-PLAN-001.DN9 Task 7 smoke and validation — 2026-06-25

- Updated `dev/todo/smoke.clj` to build `./cli/bin/todo`, exercise independent Go CLI subprocesses over the JSON socket, assert only JSON machine output, and clean generated SQLite/runtime/socket/build artifacts.
- Kept EDN-rich query authoring/debugging in the REPL smoke path through `todo.repl` instead of public CLI EDN.
- Validation run: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `cd cli && go test ./...`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` all pass.

### GOCLI-PLAN-001.DN10 Final alignment review and promotion — 2026-06-25

- Final self-review inspected the Go command/client implementation, daemon JSON socket/metadata path, feature deltas, root docs/specs, and smoke/test coverage. Alignment fixes made before shipping: root CLI and daemon runtime specs now describe the public Go CLI and JSON socket transport; project tenets include the thin JSON CLI / rich daemon-REPL boundary; README/AGENTS command examples now use `./cli/bin/todo`; protocol docs now match the shortened socket filename implementation.
- Task 8 manual verification was reported complete by the user; task 9 spec/docs promotion is complete. Feature-local deltas are marked `Merged` and the plan is marked `Shipped`.
- Final validation during finish: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` and `cd cli && go test ./...` passed. Smoke validation was already recorded in DN9 for Task 7.
