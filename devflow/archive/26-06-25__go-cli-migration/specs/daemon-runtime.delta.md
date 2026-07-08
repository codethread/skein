# Daemon Runtime delta for Go CLI Migration

**Document ID:** `SPEC-004-D002` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-25

## SPEC-004-D002.P1 Summary

Extend the daemon runtime with a local JSON Unix domain socket transport for the Go CLI while retaining nREPL for live Clojure development and trusted runtime modification. Runtime metadata grows to advertise the socket endpoint for the selected canonical database. The exact feature-local wire contract is frozen in [`GOCLI-PROTO-001`](./json-socket-protocol.md).

## SPEC-004-D002.P2 Contract changes

- **SPEC-004-D002.CC1:** A running daemon exposes a local Unix domain socket for JSON request/response CLI operations.
- **SPEC-004-D002.CC2:** Runtime metadata for the selected canonical database includes the Unix socket path, daemon pid, canonical database path, daemon identity/nonce, and any remaining nREPL endpoint needed by Clojure clients.
- **SPEC-004-D002.CC3:** Socket discovery remains keyed by canonical database path and must fail loudly on missing, stale, mismatched, or unreachable metadata.
- **SPEC-004-D002.CC4:** The JSON transport dispatches to daemon semantic operations rather than duplicating SQL or query logic in transport handlers.
- **SPEC-004-D002.CC5:** JSON request envelopes include an operation name, operation arguments, selected output-relevant options where needed, and daemon identity information sufficient for the daemon/client to reject mismatched runtimes.
- **SPEC-004-D002.CC6:** JSON response envelopes distinguish successful results from domain/transport errors and preserve enough structured error information for the CLI to exit non-zero with useful messages.
- **SPEC-004-D002.CC7:** The transport remains local-only. Remote TCP access, authentication, and multi-user authorization are outside this feature.
- **SPEC-004-D002.CC8:** nREPL remains available for REPL workflows and trusted daemon runtime modification.
- **SPEC-004-D002.CC9:** The daemon query registry defined by active delta `SPEC-004-D001.C1-C6` is consumed through daemon operations over the JSON socket once promoted; query registry state is not persisted or evaluated by the Go client.

## SPEC-004-D002.P3 Design decisions

### SPEC-004-D002.D1 Unix socket alongside nREPL

- **Decision:** Add a JSON Unix socket transport for fast CLI calls while keeping nREPL as a separate Clojure-native transport.
- **Rationale:** Unix sockets are local-only by construction and fit a small native CLI; nREPL remains the better tool for live Clojure development.
- **Rejected:** Replacing nREPL entirely or using TCP for the fast CLI transport.

### SPEC-004-D002.D2 Semantic API remains daemon-owned

- **Decision:** The JSON socket is a transport boundary, not a new domain API with independent behavior.
- **Rationale:** The daemon already owns task storage, query execution, and runtime state. Transport handlers should call the same semantic operations used by other clients.
- **Rejected:** Implementing special-case SQL or query code for Go CLI requests.

## SPEC-004-D002.P4 Open questions

- **SPEC-004-D002.Q1:** Resolved by `GOCLI-PROTO-001`: request/response field names, timeout behavior, framing, operation allowlist, and error shape are defined for implementation.
- **SPEC-004-D002.Q2:** Resolved by `GOCLI-PROTO-001.M1`: Go-readable JSON metadata is written atomically under the deterministic runtime metadata directory keyed by canonical database path; the socket path is advertised in that JSON metadata.
