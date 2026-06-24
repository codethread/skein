# Daemon Runtime Proposal

**Document ID:** `PROP-001`
**Last Updated:** 2026-06-24
**Related RFCs:** [RFC-002 Task Query DSL](../../rfcs/2026-06-24-task-query-dsl.md)
**Related root specs:** [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Task Model](../../specs/task-model.md)

## PROP-001.P1 Problem

The task database is persistent, but the Clojure program is not. Each CLI invocation or REPL session owns its own process-local datasource and runtime state, so user-loaded configuration, helper functions, warm registries, and future saved query state disappear when that process exits.

## PROP-001.P2 Goals

- **PROP-001.G1:** Introduce a long-lived local daemon that owns the active task datasource and runtime state.
- **PROP-001.G2:** Make the CLI and REPL helpers connect to the daemon instead of independently opening SQLite datasources.
- **PROP-001.G3:** Use existing Clojure/JVM libraries where they reduce custom protocol or lifecycle code.
- **PROP-001.G4:** Preserve machine-readable CLI/REPL results and loud failures for daemon, transport, and domain errors.
- **PROP-001.G5:** Leave a clear path for user-loaded Clojure configuration/functions and future saved query registries.

## PROP-001.P3 Non-goals

- **PROP-001.NG1:** This feature does not define or implement the task query DSL from RFC-002.
- **PROP-001.NG2:** This feature does not implement durable saved query semantics before the query DSL exists.
- **PROP-001.NG3:** This feature does not provide sandboxed or untrusted plugin execution.
- **PROP-001.NG4:** This feature does not provide remote, multi-user, or network-exposed daemon operation.

## PROP-001.P4 Proposed scope

- **PROP-001.S1:** Add daemon lifecycle as a first-class local runtime concept for one active SQLite database.
- **PROP-001.S2:** Route the existing stripped task operations through the daemon while keeping the domain behavior owned by `todo.db`.
- **PROP-001.S3:** Define runtime discovery and identity rules so clients fail loudly when the daemon is absent, stale, or serving a different database.
- **PROP-001.S4:** Support trusted local Clojure extensibility as a daemon concern, staged after core daemon/client plumbing.

## PROP-001.P5 Open questions

- **PROP-001.Q1:** Should the first implementation expose only nREPL, or should the daemon API be transport-abstracted enough to swap in EDN-over-socket later? The plan resolves this by treating nREPL as the initial private transport and `todo.daemon.api` as the semantic boundary.
