# REPL API delta for mill router runtime

**Document ID:** `DELTA-MillRouterRuntime-ReplApi-001` **Root spec:** [repl-api.md](../../../specs/repl-api.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-30

## DELTA-MillRouterRuntime-ReplApi-001.P1 Summary

Connected REPL launch uses `mill` for world resolution and weaver liveness, then attaches directly to the selected weaver's nREPL endpoint using mill-provided runtime metadata. The REPL remains a Clojure helper surface over one selected weaver; `mill` does not proxy interactive REPL traffic or evaluate Clojure.

## DELTA-MillRouterRuntime-ReplApi-001.P2 Contract changes

- **DELTA-MillRouterRuntime-ReplApi-001.CC1:** `strand weaver repl` and `strand weaver repl --stdin` require a running `mill` and ask it to resolve the selected world and verify that world's weaver is running before launching the helper JVM.
- **DELTA-MillRouterRuntime-ReplApi-001.CC2:** The Go CLI launches `skein.repl` with enough selected-world runtime information to find the XDG-hosted weaver metadata. This may be a config-dir plus state-dir argument or an equivalent explicit metadata reference.
- **DELTA-MillRouterRuntime-ReplApi-001.CC3:** `skein.repl/connect!` continues to select exactly one active weaver connection and fail loudly when no reachable selected-world weaver exists.
- **DELTA-MillRouterRuntime-ReplApi-001.CC4:** Connected helper clients validate weaver identity using metadata supplied or referenced by `mill`, including selected config identity and weaver nonce/protocol.
- **DELTA-MillRouterRuntime-ReplApi-001.CC5:** Mill does not tunnel or proxy nREPL messages for MVP. Interactive and stdin REPL sessions connect directly from the helper JVM to the weaver's nREPL endpoint after mill resolution.
- **DELTA-MillRouterRuntime-ReplApi-001.CC6:** `init!` remains available as a trusted idempotent helper for explicit schema initialization/testing, but normal user CLI startup no longer requires calling it because weaver startup prepares empty stores.
- **DELTA-MillRouterRuntime-ReplApi-001.CC7:** Library workspace helpers still read selected config-dir files (`init.clj`, `init.local.clj`, `libs.edn`, `libs.local.edn`) from the repo config workspace, not from XDG state.

## DELTA-MillRouterRuntime-ReplApi-001.P3 Design decisions

### DELTA-MillRouterRuntime-ReplApi-001.D1 Do not proxy REPL through Go

- **Decision:** Mill resolves/ensures the target and returns attachment metadata; the Clojure helper connects directly to nREPL.
- **Rationale:** This keeps Go free of nREPL protocol/session semantics and preserves the existing helper REPL model.
- **Rejected:** Byte-tunneling or interpreting nREPL traffic through `mill`.

### DELTA-MillRouterRuntime-ReplApi-001.D2 Keep init! as trusted helper

- **Decision:** Remove CLI DB init but retain REPL `init!` as an idempotent explicit storage helper.
- **Rationale:** Tests and trusted exploration may still need a direct schema preparation form, but everyday startup should not require it.
- **Rejected:** Removing all public helper access to schema initialization immediately.

## DELTA-MillRouterRuntime-ReplApi-001.P4 Open questions

- **DELTA-MillRouterRuntime-ReplApi-001.Q1:** Exact helper CLI arity for `skein.repl` can be chosen during implementation; it must make XDG metadata attachment explicit and testable.
