# Weaver Runtime delta for mill router runtime

**Document ID:** `DELTA-MillRouterRuntime-DaemonRuntime-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-30

## DELTA-MillRouterRuntime-DaemonRuntime-001.P1 Summary

The selected weaver world remains one Clojure runtime per repo/config identity, but runtime supervision and socket discovery move behind a Go `mill` router. Weaver state, metadata, sockets, and SQLite data live under Skein's XDG state root rather than inside repo `.skein`. Weaver startup initializes or validates storage schema; CLI database initialization is removed.

## DELTA-MillRouterRuntime-DaemonRuntime-001.P2 Contract changes

- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC1:** `mill` is a local Go router/supervisor. It owns process lifecycle and transport routing only; it does not own strand storage, query execution, runtime registries, trusted config evaluation, hook/event dispatch, or REPL semantics.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC2:** A selected world resolves to a canonical config identity. For implicit repo worlds, the config identity is the canonical repo `.skein` directory. For explicit `--config-dir`, the config identity is the canonical explicit directory.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC3:** `mill` derives a stable per-world runtime directory under `$XDG_STATE_HOME/skein/weavers/<hash>` or `~/.local/state/skein/weavers/<hash>`, where `<hash>` is derived from the canonical config identity and is safe for filesystem/socket paths.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC4:** Weaver runtime metadata and JSON socket files are published under the per-world XDG runtime directory, not under `<config-dir>/state`.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC5:** Weaver-owned SQLite data lives under the per-world XDG runtime/data directory by default, not under `<config-dir>/data`.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC6:** Repo `.skein` remains trusted config workspace only: `init.clj`, `init.local.clj`, `libs.edn`, `libs.local.edn`, and related source/config files. Runtime artifacts are not intentionally written there.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC7:** Weaver startup accepts explicit selected config-dir, state-dir, and data-dir values from `mill`; Clojure entry points fail loudly if required world/state/data inputs are missing.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC8:** A selected config identity may have at most one running weaver child under the active mill process. Starting another selected-world weaver fails loudly or returns already-running status after identity verification.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC9:** Weaver startup initializes or validates the SQLite schema before publishing ready metadata. Empty/missing databases are prepared automatically; malformed or incompatible storage fails startup loudly.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC10:** The JSON socket allowlist no longer includes public database `init` for CLI use. Trusted Clojure helpers may retain an idempotent schema-init helper for REPL/testing, but normal startup is responsible for store readiness.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC11:** `mill` forwards public operation payloads without semantic inspection. It may inspect only routing envelope fields, selected-world identity, operation name for allowed routing/lifecycle commands, and transport metadata needed for request/response correlation.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC12:** If `mill` terminates, child weavers it started are expected to terminate as well. After a mill restart, no in-memory child handles are assumed; users start desired weavers again with `strand weaver start`.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC13:** Runtime metadata still records pid, weaver id, protocol version, selected config-dir, selected state dir, selected data dir, database path, nREPL endpoint, JSON socket path, and startup time, but selected state/data dirs now point into XDG state.
- **DELTA-MillRouterRuntime-DaemonRuntime-001.CC14:** Startup config loading remains weaver-owned and ordered `init.clj` then `init.local.clj` from the selected config-dir. Mill never evaluates Clojure config.

## DELTA-MillRouterRuntime-DaemonRuntime-001.P3 Design decisions

### DELTA-MillRouterRuntime-DaemonRuntime-001.D1 Mill is a router, not a semantic daemon

- **Decision:** Mill supervises and routes; the weaver remains the semantic application core.
- **Rationale:** This preserves daemon-core-first runtime state while removing per-project manual process management.
- **Rejected:** Moving query execution, registry state, or SQLite access into Go.

### DELTA-MillRouterRuntime-DaemonRuntime-001.D2 XDG runtime/data by selected world hash

- **Decision:** State and data move to XDG paths keyed by a hash of canonical config identity.
- **Rationale:** This avoids repo pollution, avoids socket path length issues, and keeps agent-visible working trees free of SQLite/runtime artifacts.
- **Rejected:** Keeping `.skein/state` and `.skein/data` as normal runtime locations.

### DELTA-MillRouterRuntime-DaemonRuntime-001.D3 Weaver initializes its own store

- **Decision:** Store schema readiness is a weaver startup responsibility.
- **Rationale:** The weaver owns storage selection and schema semantics; requiring a separate CLI init call creates ordering friction.
- **Rejected:** Keeping `strand init` as a mixed config/bootstrap plus DB-init command.

## DELTA-MillRouterRuntime-DaemonRuntime-001.P4 Open questions

- **DELTA-MillRouterRuntime-DaemonRuntime-001.Q1:** Exact process-group termination mechanics are implementation-specific but must make `pkill mill` stop child weavers on supported development platforms.
