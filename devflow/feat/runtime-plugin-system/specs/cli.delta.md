# CLI Surface Delta — Runtime Plugin System

**Document ID:** `RPS-DELTA-003`
**Status:** Draft
**Date:** 2026-06-25
**Blocked by:** `user-daemon-home` spec promotion
**Updates:** [CLI Surface](../../../specs/cli.md)

## RPS-DELTA-003.P1 Summary

The public CLI remains a thin daemon client. It does not become the plugin loader, package manager, or extension authoring environment in this feature.

## RPS-DELTA-003.P2 Contract clarifications

- **RPS-DELTA-003.C1:** Plugin loading happens through daemon startup `init.clj`, `atom.plugin.alpha/load-plugin!`, and trusted REPL workflows, not through task/query CLI commands.
- **RPS-DELTA-003.C2:** The JSON socket allowlist is unchanged by this feature.
- **RPS-DELTA-003.C3:** `todo daemon repl --stdin` is the recommended non-TTY path for agents that need to run trusted plugin/library code.
- **RPS-DELTA-003.C4:** Future CLI or REPL helpers for git-backed plugin fetch/pin/freeze/thaw are deferred to a separate feature.
