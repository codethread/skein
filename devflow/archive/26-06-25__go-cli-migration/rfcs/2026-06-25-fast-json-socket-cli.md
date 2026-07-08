# Fast JSON Socket CLI

**Document ID:** `RFC-003` **Status:** Accepted **Date:** 2026-06-25 **Related:** [Daemon Runtime feature](../../26-06-25__daemon-runtime/daemon-runtime.plan.md), [CLI Surface](../../../specs/cli.md), [REPL API](../../../specs/repl-api.md)

## RFC-003.P1 Problem

The daemon-runtime feature moved task execution into a long-lived Clojure daemon and made the existing CLI connect to that daemon through nREPL. This solves shared runtime state and enables future user-loaded functions, but it does not make command-line invocations fast because every `clojure -M:todo ...` command still starts a JVM client before connecting to the daemon.

For scripted agent workflows, the remaining JVM startup cost is now the dominant CLI latency. The nREPL transport should remain valuable for live Clojure development and daemon runtime modification, but the primary fast CLI path should use a lighter transport and client.

## RFC-003.P2 Goals

- **RFC-003.G1:** Provide a faster scripted CLI path that avoids starting a JVM for each command.
- **RFC-003.G2:** Keep the daemon as the owner of SQLite access and runtime state.
- **RFC-003.G3:** Preserve machine-readable request/response behavior and loud failures.
- **RFC-003.G4:** Keep nREPL available for Clojure-native live development and trusted runtime modification.
- **RFC-003.G5:** Prefer a simple implementation that is easy for agents to invoke and debug.

## RFC-003.P3 Non-goals

- **RFC-003.NG1:** This RFC does not remove nREPL from the daemon.
- **RFC-003.NG2:** This RFC does not define the full JSON operation schema yet.
- **RFC-003.NG3:** This RFC does not add remote, multi-user, or untrusted daemon access.
- **RFC-003.NG4:** This RFC does not decide saved query or query DSL semantics.

## RFC-003.P4 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-003.O1 | Keep nREPL as the only CLI transport. | Reuses current implementation; no new daemon endpoint; keeps one transport. | CLI remains slow because every command starts a JVM; nREPL eval is a poor fit for non-Clojure lightweight clients. |
| RFC-003.O2 | Add a TCP JSON transport and a lightweight CLI. | Simple protocol; easy for Go, Python, shell, or future native clients; avoids JVM startup. | TCP still needs port discovery and local-bind discipline; JSON loses some native Clojure data fidelity. |
| RFC-003.O3 | Add a Unix domain socket JSON transport and replace the CLI client with a small Go tool. | Local-only by construction; no public port; fast startup; straightforward JSON payloads; Go is simple to ship as a small CLI binary. | Requires a second daemon transport and a non-Clojure client implementation. |
| RFC-003.O4 | Build a Graal/native Clojure CLI client that still uses nREPL. | Keeps Clojure code sharing. | More build complexity; still couples fast CLI to nREPL eval semantics; less simple than a small JSON client. |

## RFC-003.P5 Recommendation

- **RFC-003.REC1:** Choose **RFC-003.O3**: keep nREPL for live Clojure access, and add a Unix domain socket transport that accepts JSON request payloads and returns JSON responses for the fast CLI path.
- **RFC-003.REC2:** Replace the current JVM-backed CLI command runner with a small Go CLI that discovers the daemon socket from runtime metadata, sends JSON operation requests, prints human/EDN/JSON-compatible results as required by the CLI contract, and exits quickly.
- **RFC-003.REC3:** Treat the JSON socket API as the scripted transport boundary. It should dispatch to the same daemon semantic operations as `todo.daemon.api` rather than duplicating persistence logic.

## RFC-003.P6 Consequences

- **RFC-003.C1:** Runtime metadata should grow from nREPL endpoint-only discovery to include the Unix socket path for the selected canonical database.
- **RFC-003.C2:** The daemon will run two local transports: nREPL for live Clojure development and a JSON Unix socket for fast scripted operations.
- **RFC-003.C3:** The future feature must define request and response envelopes, operation names, error shape, timeout behavior, and how CLI output formats map from JSON results.
- **RFC-003.C4:** The current Clojure CLI remains useful as a reference implementation until the Go CLI replaces it.
- **RFC-003.C5:** This does not change the task model or SQLite schema.

## RFC-003.P7 Outcome

- **RFC-003.OUT1:** Accepted on 2026-06-25 during daemon-runtime walkthrough. The limitation was discovered while proving the daemon: nREPL gives shared live runtime access, but the current CLI still pays per-command JVM startup. Future work should plan a Go-based fast CLI over a local JSON Unix socket while retaining nREPL for direct daemon REPL access.
