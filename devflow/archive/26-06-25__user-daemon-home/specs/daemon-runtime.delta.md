# Daemon Runtime Delta — User Daemon Home

**Document ID:** `UDH-DELTA-001` **Status:** Merged into root spec **Date:** 2026-06-25 **Updates:** [Daemon Runtime](../../../specs/daemon-runtime.md)

## UDH-DELTA-001.P1 Summary

Replace database-path-keyed daemon runtime discovery with config-dir-keyed daemon worlds. A selected config-dir defines one daemon, one state directory, one default data directory, one fixed JSON socket, one metadata set, and one trusted startup file namespace.

## UDH-DELTA-001.P2 Contract changes

- **UDH-DELTA-001.C1:** A daemon world is selected by config-dir. The default config-dir is `$XDG_CONFIG_HOME/atom` or `~/.config/atom`; an explicit CLI daemon/client config-dir override selects a separate world.
- **UDH-DELTA-001.C2:** The default world uses `$XDG_STATE_HOME/atom` or `~/.local/state/atom` for runtime state and `$XDG_DATA_HOME/atom` or `~/.local/share/atom` for daemon-owned data.
- **UDH-DELTA-001.C3:** An explicit `--config-dir DIR` selects a self-contained experimental world: config files live in `DIR`, runtime state lives in `DIR/state`, and daemon data lives in `DIR/data`.
- **UDH-DELTA-001.C4:** The daemon JSON socket path is fixed within the selected state world as `daemon.sock`, instead of being hashed from a canonical database path.
- **UDH-DELTA-001.C5:** Runtime metadata is published beside the fixed socket as `daemon.json` for Go clients and `daemon.edn` for Clojure clients. Metadata identifies pid, daemon id, protocol version, selected config-dir, selected data path/database path for status/debugging, nREPL endpoint, socket path, and startup time.
- **UDH-DELTA-001.C6:** Clients discover the daemon by selected config-dir/state world, then verify daemon identity/protocol over the socket. They do not need a database path to locate metadata, and they do not send or compare database path as a request identity field.
- **UDH-DELTA-001.C7:** The daemon owns storage selection. By default it uses `tasks.sqlite` under the selected data world. This feature does not add a daemon startup hook for DB path customization.
- **UDH-DELTA-001.C8:** The daemon loads `init.clj` from the selected config-dir by default when present. Load errors fail startup loudly and publish no ready metadata. The prior explicit EDN `daemon start --config` startup path is removed from the blessed public contract by this feature.
- **UDH-DELTA-001.C9:** A selected config-dir may have at most one running daemon. Starting another daemon for the same selected config-dir fails loudly unless stale socket/metadata can be proven dead and cleaned.
- **UDH-DELTA-001.C10:** Multi-daemon use is explicit world selection: different config-dir overrides create separate daemon/socket/metadata/data/init worlds. No client command implicitly switches worlds based on cwd or database path.

## UDH-DELTA-001.P3 Removed contracts

- **UDH-DELTA-001.R1:** Remove daemon metadata keyed by stable hash of canonical database path.
- **UDH-DELTA-001.R2:** Remove the requirement that clients provide database path to connect to a daemon.
- **UDH-DELTA-001.R3:** Remove client-side database-path mismatch as the primary daemon identity check; replace it with config-dir/daemon-id/protocol checks while still reporting daemon-owned database path in status metadata.
- **UDH-DELTA-001.R4:** Remove `database_path` from the required JSON socket request envelope. Database path remains daemon-owned status/debugging metadata only.
