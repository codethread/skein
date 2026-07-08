# Weaver Runtime delta for repo-first config

**Document ID:** `DELTA-DaemonRuntime-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-29

## DELTA-DaemonRuntime-001.P1 Summary

The selected config-dir remains the weaver world identity, but the normal selected config-dir is now repo-local `.skein`. Weaver startup and reload treat shared and local files as one layered config: approved libraries come from `libs.edn` overlaid by `libs.local.edn`, and startup code loads `init.clj` then `init.local.clj` when present.

## DELTA-DaemonRuntime-001.P2 Contract changes

- **DELTA-DaemonRuntime-001.CC1:** A selected config-dir may be a repo-local `.skein` directory. The weaver still derives state, data, socket, metadata, and default SQLite storage from that selected config-dir.
- **DELTA-DaemonRuntime-001.CC2:** Weaver startup loads selected config-dir startup files in this order: `init.clj`, then `init.local.clj`. Missing files are skipped; a present file that fails to read or evaluate aborts startup loudly and publishes no ready metadata.
- **DELTA-DaemonRuntime-001.CC3:** Runtime config reload mirrors startup layering exactly. It clears the same weaver-lifetime registries as today, including queries, views, patterns, CLI operations, module-use state, hooks, event handlers, queued events, and recent event failures; reinstalls built-ins; reloads `init.clj` and `init.local.clj` in order when present; then leaves event dispatch running for the fully reloaded config.
- **DELTA-DaemonRuntime-001.CC4:** The selected config-dir's approved library config is the deterministic overlay of `libs.edn` and `libs.local.edn`. Missing files contribute no libraries; malformed present files fail loudly as structural config errors.
- **DELTA-DaemonRuntime-001.CC5:** Library overlay semantics are shallow by coordinate: effective `:libs` is `(merge shared-libs local-libs)`, so a coordinate in `libs.local.edn` replaces the same coordinate from `libs.edn`.
- **DELTA-DaemonRuntime-001.CC6:** Normalized approved library entries include enough metadata to identify whether the effective entry came from shared `libs.edn` or local `libs.local.edn` for introspection and debugging.
- **DELTA-DaemonRuntime-001.CC7:** `libs.local.edn` uses the same alpha config grammar as `libs.edn`: top-level `:libs`, symbol coordinates, and entry maps with required non-blank string `:local/root`.
- **DELTA-DaemonRuntime-001.CC8:** Relative `:local/root` values in both files resolve against the selected config-dir; absolute paths and `~` expansion keep existing behavior.
- **DELTA-DaemonRuntime-001.CC9:** Local library overrides are intentional user control. The weaver does not warn, block, or try to solve version conflicts when `libs.local.edn` replaces a shared coordinate.
- **DELTA-DaemonRuntime-001.CC10:** Runtime source acquisition remains outside weaver boot. Skein does not clone, fetch, install, or update library source as part of this feature.

## DELTA-DaemonRuntime-001.P3 Design decisions

### DELTA-DaemonRuntime-001.D1 Layering belongs to the weaver

- **Decision:** The weaver owns `init.clj`/`init.local.clj` and `libs.edn`/`libs.local.edn` layering for every selected config-dir.
- **Rationale:** Startup and reload must behave the same way, and users should not need to hand-roll local overlay loading in every shared init file.
- **Rejected:** Requiring each `init.clj` to conditionally load `init.local.clj` itself.

### DELTA-DaemonRuntime-001.D2 Local libs may override shared libs

- **Decision:** `libs.local.edn` entries replace shared entries with the same coordinate.
- **Rationale:** Personal workflow control is an explicit goal. A user may intentionally point a coordinate at a newer local checkout or fork without requiring organization-wide alignment.
- **Rejected:** Duplicate-coordinate failure or shared-file priority.

### DELTA-DaemonRuntime-001.D3 Path-only library roots for MVP

- **Decision:** Approved libraries continue to use local-root paths only.
- **Rationale:** The desired personal workflow only needs a pointer to an existing local common-workflows repo. Package management can be designed later without coupling it to repo-first config.
- **Rejected:** Maven coordinates, package install commands, lockfiles, source fetching, or registry semantics in this feature.

## DELTA-DaemonRuntime-001.P4 Open questions

- **DELTA-DaemonRuntime-001.Q1:** None for MVP. Implementation should choose exact introspection field names for shared/local source metadata while preserving the effective approved-lib shape needed by existing helpers.
