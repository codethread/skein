# REPL API Delta — User Daemon Home

**Document ID:** `UDH-DELTA-003` **Status:** Merged into root spec **Date:** 2026-06-25 **Updates:** [REPL API](../../../specs/repl-api.md)

## UDH-DELTA-003.P1 Summary

Change REPL connection ergonomics from explicit database selection to implicit selected daemon-world connection. `todo daemon repl` should start a helper REPL with task/query helpers already connected to the running daemon for the selected config-dir.

## UDH-DELTA-003.P2 Contract changes

- **UDH-DELTA-003.C1:** Add `connect!` as the public helper connection operation. With no arguments it selects the default daemon world; with a config-dir argument it selects that explicit daemon world. It never accepts a database path.
- **UDH-DELTA-003.C2:** `todo daemon repl` preloads the REPL helper namespace, calls `connect!` for the selected daemon world, and presents the prompt.
- **UDH-DELTA-003.C3:** `todo daemon repl --stdin` uses the same preloaded, connected helper context, reads forms from stdin, evaluates them in order, prints one direct normal Clojure result per top-level form, and exits. Callers that want one machine-readable payload should wrap work in one top-level `do` or `let`.
- **UDH-DELTA-003.C4:** Existing task/query helpers operate after implicit connection without requiring `(open! "path/to.sqlite")`.
- **UDH-DELTA-003.C5:** Calling helpers before connection still fails loudly with remediation that points to `todo daemon repl` or `connect!`, not to database-path `open!`.
- **UDH-DELTA-003.C6:** Database-path `open!` is removed from the public REPL API. Internal tests may use lower-level client helpers for fixture setup, but user-facing docs/specs should not present `open!` as the blessed connection path.
- **UDH-DELTA-003.C7:** Query registry mutation/inspection remains a REPL/trusted-config capability. This feature changes connection discovery, not the semantic split between CLI and REPL.

## UDH-DELTA-003.P3 Compatibility position

- **UDH-DELTA-003.A1:** Per alpha tenets, database-path `open!` is removed as a supported public workflow rather than retained as a compatibility mode.
