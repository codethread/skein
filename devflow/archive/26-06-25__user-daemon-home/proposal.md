# User Daemon Home Proposal

**Document ID:** `UDH-PROP-001`
**Status:** Draft
**Date:** 2026-06-25
**Related RFCs:** None
**Relevant root specs:** [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Daemon Runtime](../../specs/daemon-runtime.md)

## UDH-PROP-001.P1 Problem

The current daemon UX leaks implementation identity into every client workflow: users and agents must know the database path to find the daemon, and Clojure REPL access assumes the caller is already operating from the source tree or has manually supplied enough project context. This violates the daemon-core philosophy by making clients coordinate daemon-owned storage identity instead of connecting to the user's live application core.

## UDH-PROP-001.P2 Goals

- **UDH-PROP-001.G1:** Make the daemon discoverable from anywhere on the user's system through config-dir/state-dir identity rather than database-path identity.
- **UDH-PROP-001.G2:** Remove database paths from the public client config and normal CLI/REPL connection model.
- **UDH-PROP-001.G3:** Let `todo daemon repl` drop users into a connected Clojure helper REPL from any working directory on a dev machine where Clojure is on `PATH`, the daemon is running, and client config points at the local source checkout.
- **UDH-PROP-001.G4:** Treat daemon and config-dir as one world: multiple daemon worlds require an explicit config-dir override and do not share state, socket, data, or user startup files.
- **UDH-PROP-001.G5:** Load trusted user daemon initialization from the selected config-dir by default, preserving rich runtime customization in daemon/REPL workflows.
- **UDH-PROP-001.G6:** Keep the Go CLI thin: normal task/query/status/stop commands connect to the known JSON socket and do not need Clojure source or database paths.
- **UDH-PROP-001.G7:** Give agents a non-TTY trusted REPL path through `todo daemon repl --stdin`, where they can pipe Clojure forms and choose their own final output shape.

## UDH-PROP-001.P3 Non-goals

- **UDH-PROP-001.NG1:** Do not design end-user packaging that removes the need for a source checkout; this feature may require `config.json` to point at the Clojure source root.
- **UDH-PROP-001.NG2:** Do not add database selection flags to public task/query commands as a compatibility path.
- **UDH-PROP-001.NG3:** Do not make JSON client config an executable or EDN-rich extension system.
- **UDH-PROP-001.NG4:** Do not expose query registry mutation or arbitrary daemon eval through the low-privilege JSON CLI surface.
- **UDH-PROP-001.NG5:** Do not support concurrently running multiple daemons for one config-dir.

## UDH-PROP-001.P4 Proposed scope

- **UDH-PROP-001.S1:** Redefine daemon discovery around selected config-dir and XDG state/data locations, with a fixed socket and metadata under the selected state world.
- **UDH-PROP-001.S2:** Replace client config `db` with a low-privilege absolute `source` path used only by commands that spawn Clojure (`daemon start`, `daemon repl`) plus simple client defaults such as output format.
- **UDH-PROP-001.S3:** Give the daemon a deterministic default database path in the selected data world, owned by daemon startup rather than clients.
- **UDH-PROP-001.S4:** Load `$CONFIG_DIR/init.clj` as the blessed trusted daemon startup customization by default when present, with explicit failure on load errors.
- **UDH-PROP-001.S5:** Remove the previous public `daemon start --config <trusted.edn>` path instead of composing it with auto-loaded `init.clj`.
- **UDH-PROP-001.S6:** Add `todo daemon repl` to launch a local plain Clojure helper REPL from the configured source checkout and auto-connect it to the daemon world.
- **UDH-PROP-001.S7:** Add `todo daemon repl --stdin` to read Clojure forms from stdin, evaluate them in the same connected helper context, print form results without extra protocol wrappers, and exit non-zero on exceptions.
- **UDH-PROP-001.S8:** Update CLI, REPL, daemon runtime, smoke, and docs/specs to remove database-path-first examples and contracts.

## UDH-PROP-001.P5 Open questions

- **UDH-PROP-001.Q1:** None. Use `--config-dir` only for alternate worlds; defer profile aliases and richer REPL polish until demanded.
