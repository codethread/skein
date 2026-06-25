# CLI Surface Delta — User Daemon Home

**Document ID:** `UDH-DELTA-002`
**Status:** Merged into root spec
**Date:** 2026-06-25
**Updates:** [CLI Surface](../../../specs/cli.md)

## UDH-DELTA-002.P1 Summary

Make the public `todo` CLI connect to the selected user daemon world from anywhere without database-path configuration. Keep JSON config low-privilege and use it only for client defaults and source checkout discovery needed by Clojure-spawning lifecycle commands.

## UDH-DELTA-002.P2 Contract changes

- **UDH-DELTA-002.C1:** Remove public `--config-path <path>` and replace it with `--config-dir <dir>` for selecting a daemon world. The default world uses the XDG atom config directory.
- **UDH-DELTA-002.C2:** Client JSON config lives inside the selected config-dir as `config.json` and may contain only supported low-privilege keys such as `source` and `format`.
- **UDH-DELTA-002.C3:** Remove `db` from supported client JSON config. Database path is daemon-owned and not required by task/query/status/stop clients.
- **UDH-DELTA-002.C4:** Task and query commands connect to the fixed JSON socket for the selected daemon world. They fail loudly when no daemon is running, when metadata/socket is stale or malformed, or when protocol/identity verification fails.
- **UDH-DELTA-002.C5:** `source` must be an absolute path to the Atom source checkout root containing `deps.edn`. Relative paths, missing directories, and directories without `deps.edn` fail before launching Clojure.
- **UDH-DELTA-002.C6:** `daemon start` resolves the selected config-dir, reads `config.json`, requires valid `source`, and launches the Clojure daemon from that source in the foreground.
- **UDH-DELTA-002.C7:** `daemon start` passes the selected config-dir to the Clojure daemon. The daemon then loads default trusted `init.clj` from that config-dir when present. The prior `daemon start --config <trusted.edn>` public startup option is removed rather than composed with `init.clj`.
- **UDH-DELTA-002.C8:** Add `daemon repl`. It resolves selected config-dir, reads `config.json`, requires valid `source`, verifies a reachable daemon for that world, and launches a local plain Clojure helper REPL from the source checkout already connected to the daemon.
- **UDH-DELTA-002.C9:** Add `daemon repl --stdin` for non-TTY agent use. It reads Clojure forms from stdin, evaluates them in the same connected helper context as the interactive REPL, prints one direct normal Clojure result per top-level form, and exits non-zero on read/eval errors.
- **UDH-DELTA-002.C10:** `daemon repl --stdin` does not impose a JSON or EDN response envelope. Agents choose output shape in the forms they run; callers that want one machine-readable payload should wrap work in one top-level `do` or `let` and make that form return or print the desired final value.
- **UDH-DELTA-002.C11:** Normal task/query/status/stop commands do not require `source`; only Clojure-spawning lifecycle commands (`daemon start`, `daemon repl`) require it.
- **UDH-DELTA-002.C12:** Human help and failure messages should describe the selected config-dir world and the remediation path, for example setting `source` or starting the daemon.

## UDH-DELTA-002.P3 Non-goals retained

- **UDH-DELTA-002.N1:** The CLI remains a thin JSON control surface and does not parse EDN query definitions or mutate rich daemon runtime state.
- **UDH-DELTA-002.N2:** `daemon repl` is a convenience launcher for the rich Clojure workflow, not a new JSON command surface for arbitrary daemon behavior.
