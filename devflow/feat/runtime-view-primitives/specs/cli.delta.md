# CLI Surface Delta — Runtime View Primitives

**Document ID:** `RVP-DELTA-003`
**Status:** Draft
**Date:** 2026-06-25
**Blocked by:** `user-daemon-home` spec promotion
**Updates:** [CLI Surface](../../../specs/cli.md)

## RVP-DELTA-003.P1 Summary

This feature intentionally does not add a public CLI view command. Runtime view definition and invocation remain trusted Clojure daemon/REPL workflows for this slice.

## RVP-DELTA-003.P2 Contract clarifications

- **RVP-DELTA-003.C1:** The public JSON CLI allowlist is unchanged by this feature.
- **RVP-DELTA-003.C2:** `todo view`, `list --view`, `ready --view`, and CLI view output contracts remain deferred.
- **RVP-DELTA-003.C3:** Low-privilege CLI workers may continue to consume registered named queries through existing query-aware commands, but they cannot register or invoke arbitrary views through the JSON socket in this feature.
- **RVP-DELTA-003.C4:** Agents needing trusted view execution should use `todo daemon repl --stdin` after `user-daemon-home` ships, and choose their own printed output shape in Clojure.
