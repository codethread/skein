# Go CLI Migration

**Document ID:** `RFC-004` **Status:** Accepted **Date:** 2026-06-25 **Related:** [RFC-003 Fast JSON Socket CLI](./2026-06-25-fast-json-socket-cli.md), [CLI Surface `SPEC-002`](../../../specs/cli.md), [Daemon Runtime `SPEC-004`](../../../specs/daemon-runtime.md), active query registry deltas `SPEC-002-D002` and `SPEC-004-D001`

## RFC-004.P1 Problem

The current `clojure -M:todo ...` CLI is intentionally thin and now routes task operations through the daemon, but it still pays JVM startup cost for every scripted invocation. RFC-003 accepted the direction of a fast JSON Unix socket CLI. The remaining decision is how to frame the migration so the CLI becomes a small native client without weakening the daemon-core architecture or depending on query registry contracts currently staged as active deltas `SPEC-002-D002` and `SPEC-004-D001`.

## RFC-004.P2 Goals

- **RFC-004.G1:** Make common CLI invocations fast by replacing the JVM-backed command runner with a small Go executable.
- **RFC-004.G2:** Preserve the daemon as the only owner of SQLite access, task semantics, query registry execution, and trusted runtime state.
- **RFC-004.G3:** Keep the public CLI command surface stable for agents while simplifying machine data exchange to JSON; EDN remains for Clojure REPL and trusted daemon config workflows.
- **RFC-004.G4:** Discover the daemon through deterministic local metadata and an XDG JSON user config file for low-privilege client defaults.
- **RFC-004.G5:** Keep migration planning independent of old code paths until active query registry deltas `SPEC-002-D002` and `SPEC-004-D001` have been promoted or otherwise stabilized.

## RFC-004.P3 Non-goals

- **RFC-004.NG1:** This RFC does not remove nREPL or Clojure REPL workflows from the daemon.
- **RFC-004.NG2:** This RFC does not move task persistence, query evaluation, or runtime customization into Go.
- **RFC-004.NG3:** This RFC does not define a remote or multi-user daemon protocol.
- **RFC-004.NG4:** This RFC does not require durable saved-query storage beyond the daemon query registry contracts staged in `SPEC-002-D002` and `SPEC-004-D001`.

## RFC-004.P4 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-004.O1 | Keep the Clojure CLI and optimize around JVM startup. | Least migration work; maximal Clojure code reuse. | Does not solve the dominant per-command latency; keeps scripts tied to JVM availability. |
| RFC-004.O2 | Add a Go wrapper that shells out to the Clojure CLI. | Simple packaging improvement; can stage UX changes gradually. | Still pays JVM startup and duplicates error handling without improving architecture. |
| RFC-004.O3 | Replace task command execution with a Go JSON Unix socket client while keeping daemon lifecycle and semantics in Clojure. | Fast startup; thin client; local-only transport; preserves daemon-core ownership; aligns with RFC-003. | Requires a JSON operation envelope, socket lifecycle, config discovery, and output formatting in Go. |
| RFC-004.O4 | Reimplement the full CLI and storage path in Go. | Could remove JVM from scripted workflows entirely. | Violates daemon-core design by duplicating persistence and runtime behavior; would split semantics across implementations. |

## RFC-004.P5 Recommendation

- **RFC-004.REC1:** Choose **RFC-004.O3**. Build a Go CLI that parses the existing CLI surface, discovers the selected daemon, sends JSON requests over a Unix domain socket, formats human or JSON responses, and exits without starting a JVM.
- **RFC-004.REC2:** Keep task and query semantics behind daemon operations. The Go CLI must not open SQLite or evaluate query definitions locally.
- **RFC-004.REC3:** Drop EDN from the public CLI. The migrated CLI supports JSON for machine-readable output and JSON-shaped wire/config data; the Clojure engine may translate internally to EDN/Clojure data for REPL and config code.
- **RFC-004.REC4:** Use idiomatic Go defaults where they are enough: `net` for Unix socket dialing, `encoding/json` for protocol payloads, `context` for timeouts/cancellation, `errors`/`fmt` for explicit error wrapping, and Go's standard testing package for most tests.
- **RFC-004.REC5:** Use focused third-party libraries where they materially reduce CLI/config boilerplate: Cobra is acceptable for command/subcommand parsing and help output; `adrg/xdg` or an equivalent small XDG helper is acceptable for config path discovery. Avoid broad framework stacks unless the implementation plan proves they earn their weight.
- **RFC-004.REC6:** Start detailed implementation planning only after active query registry deltas `SPEC-002-D002` and `SPEC-004-D001` have been promoted or otherwise stabilized, so the plan can target final daemon API and query behavior rather than pre-landing paths.

## RFC-004.P6 Consequences

- **RFC-004.C1:** The CLI spec should change its entrypoint from `clojure -M:todo` to a native `todo` executable while preserving command contracts where practical and removing public EDN output/input modes.
- **RFC-004.C2:** The daemon runtime spec should require runtime metadata to include the Unix socket endpoint for the selected canonical database.
- **RFC-004.C3:** The daemon must expose a JSON request/response transport that dispatches to the same semantic daemon operations used by Clojure clients.
- **RFC-004.C4:** Client config must follow XDG conventions and remain low privilege: it may select client defaults such as database path and output format, but trusted Clojure code loading remains daemon startup configuration.
- **RFC-004.C5:** Feature planning is intentionally deferred until query registry contracts `SPEC-002-D002` and `SPEC-004-D001` are promoted or otherwise stabilized; the current feature folder should hold proposal and spec deltas only.
- **RFC-004.C6:** Library choices should bias toward small, conventional Go dependencies: standard library for transport/protocol, Cobra only if subcommand ergonomics beat hand-rolled parsing, and a small XDG helper instead of a large config framework.

## RFC-004.P7 Outcome

- **RFC-004.OUT1:** Accepted on 2026-06-25 for upcoming `go-cli-migration` feature preparation. Follow-up artifacts are the feature proposal and CLI/daemon-runtime/tenets deltas; the implementation plan will be written after active query registry deltas `SPEC-002-D002` and `SPEC-004-D001` are promoted or otherwise stabilized.
