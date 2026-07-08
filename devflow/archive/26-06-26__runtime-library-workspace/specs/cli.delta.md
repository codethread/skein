# CLI Surface Delta: Runtime Library Workspace

**Document ID:** `RLW-DELTA-003` **Status:** Merged into root spec **Last Updated:** 2026-06-25 **Root spec:** [CLI Surface](../../../specs/cli.md) **Proposal:** [../proposal.md](../proposal.md)

## RLW-DELTA-003.P1 Summary

This feature keeps the public CLI thin. Runtime library workspace operations are trusted Clojure config/REPL workflows, not new public task/query CLI commands.

## RLW-DELTA-003.P2 Contract changes

- **RLW-DELTA-003.C1:** `daemon start` continues to load selected config-dir `init.clj`; that init file may use `atom.libs.alpha` to sync approved local roots and activate optional modules.
- **RLW-DELTA-003.C2:** `daemon repl` and `daemon repl --stdin` remain the public CLI paths for invoking trusted daemon-routed runtime library helpers against a running daemon world.
- **RLW-DELTA-003.C3:** The CLI does not add package install, dependency approval, source sync, Git submodule, scaffold, plugin, or library activation commands in this feature.
- **RLW-DELTA-003.C4:** The JSON socket operation allowlist remains limited to task/query/status/stop behavior and does not expose library sync, `use!`, or plugin operations.
- **RLW-DELTA-003.C5:** Existing CLI spec wording that names plugin loading as a supported runtime path should be replaced with trusted config/REPL `atom.libs.alpha` workflows when this feature ships.
