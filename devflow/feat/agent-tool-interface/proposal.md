# Agent Tool Interface Proposal

**Document ID:** `PROP-001`
**Last Updated:** 2026-06-24
**Related RFCs:** None
**Related root specs:** None yet
**Configuration identification:** `PROP-001` is the first proposal in this repository. Every nested point ID is prefixed with `PROP-001`.

## PROP-001.P1 Problem

The current todo graph MVP proves SQLite JSON1 task attributes and edge-table relationships, but its primary interface is a human TUI plus ad hoc REPL calls. Coding agents need a stable, scriptable command surface for automation and a small REPL namespace for interactive exploration without learning internal implementation details.

## PROP-001.P2 Goals

- **PROP-001.G1:** Provide an agent-friendly CLI for creating, linking, querying, and updating task graph records.
- **PROP-001.G2:** Provide a compact REPL API with obvious function names for interactive exploration.
- **PROP-001.G3:** Preserve JSON1-backed open-ended attributes while establishing a few conventional fields and edge types for interoperability.
- **PROP-001.G4:** Make smoke validation demonstrate both command-oriented and REPL-oriented workflows.

## PROP-001.P3 Non-goals

- **PROP-001.NG1:** Do not build schemas or validation for userland attributes beyond valid JSON in this feature.
- **PROP-001.NG2:** Do not build a network service, MCP server, or long-running daemon in this feature.
- **PROP-001.NG3:** Do not replace SQLite with a different storage engine.
- **PROP-001.NG4:** Do not make the TUI the primary agent interface.

## PROP-001.P4 Proposed scope

- **PROP-001.S1:** Agents can initialize a database, add tasks, link edges, mark task status, and query useful graph/task views from the shell.
- **PROP-001.S2:** Agents can load a REPL namespace and use a small stable set of helper functions against an opened database.
- **PROP-001.S3:** Outputs intended for automation are available in machine-readable formats, with human-readable output retained for local inspection.
- **PROP-001.S4:** The project documentation tells agents the small command/function vocabulary they need to operate the tool.

## PROP-001.P5 Open questions

- **PROP-001.Q1:** None blocking MVP task generation; richer schemas, MCP integration, and advanced graph algorithms can be considered after the CLI/REPL MVP exists.
