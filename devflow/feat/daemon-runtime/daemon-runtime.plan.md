# Daemon Runtime Plan

**Document ID:** `PLAN-001`
**Feature:** `daemon-runtime`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** [RFC-002 Task Query DSL](../../rfcs/2026-06-24-task-query-dsl.md)
**Root specs:** [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Task Model](../../specs/task-model.md)
**Feature specs:** [Daemon Runtime](./specs/daemon-runtime.md), [CLI delta](./specs/cli.delta.md), [REPL delta](./specs/repl-api.delta.md)
**Status:** Reviewed
**Last Updated:** 2026-06-24

## PLAN-001.P1 Goal and scope

Deliver the first daemon-backed runtime for the todo graph: one long-lived local Clojure process owns the SQLite datasource, and the CLI/REPL helper surface connects to that daemon for the existing stripped task operations. This feature intentionally defers the query DSL, durable saved queries, SCI/sandboxing, and broad config systems.

## PLAN-001.P2 Approach

- **PLAN-001.A1:** Add `nrepl/nrepl` and use nREPL as the initial private local transport because it reuses mature Clojure/JVM daemon and client tooling and supports direct advanced REPL access.
- **PLAN-001.A2:** Keep the semantic boundary in `todo.daemon.api`. CLI and REPL clients generate fixed calls to this namespace with user inputs passed as data.
- **PLAN-001.A3:** Keep persistence logic in `todo.db`; daemon API functions normalize arguments/results and delegate domain work to the existing database layer.
- **PLAN-001.A4:** Store runtime metadata as an atomically written EDN file under a deterministic runtime directory, keyed by a stable hash of the canonical database path.
- **PLAN-001.A5:** Start with plain Clojure/Java runtime metadata and `clojure.edn` for any minimal config shape. Do not add Aero until profiles/env interpolation/includes are required.
- **PLAN-001.A6:** Treat trusted `load-file`/user-code loading as a daemon startup capability after core client/server plumbing, not as a prerequisite for existing task commands.
- **PLAN-001.A7:** Fail loudly on absent daemons, stale metadata, database mismatch, nREPL timeout, daemon exceptions, and config/user-code load failures.

## PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-001.AA1 | `src/todo/daemon` | New daemon runtime, lifecycle, API, nREPL startup, runtime metadata. |
| PLAN-001.AA2 | `src/todo/client` | New client helpers for metadata discovery, nREPL calls, timeouts, and error normalization. |
| PLAN-001.AA3 | `src/todo/cli.clj` | Parse daemon lifecycle commands and route task commands through the client. |
| PLAN-001.AA4 | `src/todo/repl.clj` | Replace process-local datasource helpers with daemon client helpers. |
| PLAN-001.AA5 | `deps.edn` | Add nREPL dependency and any daemon/dev aliases needed for startup. |
| PLAN-001.AA6 | `test/todo` and smoke demo | Cover lifecycle parsing, client failure modes, daemon-backed commands, and REPL helper behavior. |
| PLAN-001.AA7 | `dev/user.clj` and `dev/todo/smoke.clj` | Update dev helpers and smoke workflow to use daemon-backed connections. |
| PLAN-001.AA8 | `README.md` and `AGENTS.md` | Update contributor/user examples for daemon start/connect flow. |

## PLAN-001.P4 Contract and migration impact

- **PLAN-001.CM1:** CLI `--db` changes from direct datasource selection to daemon/runtime identity for task commands.
- **PLAN-001.CM2:** REPL `open!` no longer means “open a datasource in this process”; it selects/connects to a daemon runtime.
- **PLAN-001.CM3:** Existing databases remain SQLite files with the same task schema; no task-model migration is required for daemon plumbing.
- **PLAN-001.CM4:** Backward compatibility with per-invocation direct SQLite task commands is not preserved unless explicitly reintroduced by a later decision.

## PLAN-001.P5 Implementation phases

### PLAN-001.PH1 Daemon lifecycle and API shell

Outcome: A daemon can start for one database, initialize/hold its datasource, publish runtime metadata, answer status, and stop cleanly. `todo.daemon.api` exposes existing task operations directly callable in-process.

### PLAN-001.PH2 nREPL client bridge

Outcome: A client namespace discovers runtime metadata, verifies daemon identity, calls fixed `todo.daemon.api` forms over nREPL, returns structured Clojure data, and converts daemon/transport failures into loud exceptions.

### PLAN-001.PH3 CLI daemon routing

Outcome: `todo daemon start|stop|status` works, and existing CLI task commands route through the client while preserving EDN/JSON/human output and non-zero failure behavior.

### PLAN-001.PH4 REPL helper routing

Outcome: `todo.repl` helpers connect to the daemon and preserve the compact helper API and normalized return values without opening local datasources.

### PLAN-001.PH5 Trusted startup extension hook

Outcome: The daemon can load an explicit trusted Clojure file or minimal EDN config at startup, failing loudly on errors. No runtime reload command or saved query semantics are implemented in this phase.

### PLAN-001.PH6 Dev smoke and documentation

Outcome: Dev helpers, smoke scripts, README, and AGENTS guidance demonstrate the daemon-backed workflow, including a tmux-held daemon and a separate client connection that shows actual task data.

## PLAN-001.P6 Validation strategy

- **PLAN-001.V1:** Unit-test daemon API delegation against disposable SQLite databases.
- **PLAN-001.V2:** Unit-test client behavior for missing metadata, stale metadata, database mismatch, connection timeout, and daemon-thrown domain errors.
- **PLAN-001.V3:** Exercise CLI subprocess smoke paths through a real daemon for `init`, `add`, `update`, `show`, `list`, and `ready`.
- **PLAN-001.V4:** Exercise REPL helpers against a real daemon in the smoke demo.
- **PLAN-001.V5:** Start the daemon in a named `agent-<task>` tmux session, connect from a separate CLI/REPL or `dev/` process through the new daemon connection path, create/read/update representative task data, capture tmux output showing the daemon remained live, and cleanly stop the daemon/session before completion.
- **PLAN-001.V6:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

## PLAN-001.P7 Risks and open questions

- **PLAN-001.R1:** nREPL eval can blur API and transport boundaries. Mitigation: generate only fixed daemon API forms from trusted client code and keep user values serialized as data.
- **PLAN-001.R2:** Runtime metadata can go stale after crashes. Mitigation: clients validate pid/endpoint/nonce/database identity and fail loudly with remediation guidance.
- **PLAN-001.R3:** JVM CLI clients still pay startup cost. Mitigation: this feature prioritizes shared long-lived runtime and extensibility; later work can add lighter launchers if command latency becomes the bottleneck.
- **PLAN-001.R4:** Trusted user code can crash or corrupt daemon state. Mitigation: make loading explicit, local, trusted, and loud; defer SCI/sandboxing until untrusted scripting is a real goal.
- **PLAN-001.R5:** Metadata path hashing can hide which database a file belongs to. Mitigation: include canonical db path inside the EDN metadata and expose it in `daemon status`.

## PLAN-001.P8 Task context

- **PLAN-001.TC1:** Council review supported nREPL only as a Clojure-native private transport, not as the semantic API; reviewers also recommended cutting saved queries/Aero/SCI from the first daemon feature.
- **PLAN-001.TC2:** RFC-002 remains open, so saved query storage/execution must not be specified beyond data-first alignment constraints.
- **PLAN-001.TC3:** The existing `todo.db` functions and SQLite schema remain the ground truth for task behavior.
- **PLAN-001.TC4:** Deep review required explicit tmux + `dev/` smoke verification, concrete `open!` semantics, deterministic metadata rules, and docs/dev helper updates before implementation is considered complete.

## PLAN-001.P9 Developer Notes

### PLAN-001.DN1 Planning review — 2026-06-24

- Council feedback was incorporated by narrowing scope to daemon lifecycle plus CLI/REPL routing, choosing nREPL as initial private transport, deferring Aero/SCI/saved queries, and adding explicit stale metadata and failure-mode requirements.

### PLAN-001.DN2 Deep review amendment — 2026-06-24

- Deep review found missing tmux/dev smoke validation, ambiguous `open!` semantics, public `--port` leakage, under-specified metadata discovery, and missing docs/dev helper scope. The plan/spec deltas were amended before task authoring.

### PLAN-001.DN3 TASK-001 implementation — 2026-06-24

- Added the daemon runtime/API foundation with nREPL bound to `127.0.0.1`, daemon-owned datasource state, and deterministic EDN metadata keyed by canonical database path hash.
- `todo.daemon.api` currently exposes in-process semantic operations only; nREPL client invocation and CLI/REPL routing remain in later slices.

### PLAN-001.DN4 TASK-002 implementation — 2026-06-24

- Added `todo.client` for metadata discovery, nREPL connection, identity verification, fixed daemon API invocation, timeout handling, and loud `ExceptionInfo` transport/domain failures.
- The daemon runtime now publishes the current runtime through `todo.daemon.runtime/current-runtime` for private nREPL bridge calls; CLI/REPL routing remains in later slices.

### PLAN-001.DN5 TASK-003 implementation — 2026-06-24

- Routed CLI task commands through `todo.client`; absent, stale, unreachable, and identity-mismatched daemons fail loudly with no direct SQLite fallback.
- Added `daemon start|status|stop` to `todo.cli` without exposing public port selection; status EDN/JSON includes health, canonical database path, pid, endpoint, and nonce identity.
- Updated the smoke CLI subsection, root CLI spec, and quickstart docs to start and stop a real daemon before exercising task commands; REPL helper migration remains in TASK-004.
- Tightened metadata staleness so dead daemon PIDs are treated as stale and a subsequent `daemon start` can replace stale metadata.
