# CLI Surface

**Document ID:** `SPEC-002`
**Status:** Implemented
**Last Updated:** 2026-06-24
**Related RFCs:** None
**Code:** `src/todo`
**Configuration identification:** `SPEC-002` is the second root spec in this repository. Every nested point ID is prefixed with `SPEC-002`.

## SPEC-002.P1 Purpose

The CLI surface is the primary scripted interface for coding agents. It exposes task-model operations as deterministic shell commands with explicit database selection, repeatable attribute input, and machine-readable output modes.

## SPEC-002.P2 Goals

- **SPEC-002.G1:** Let agents initialize, populate, update, and query a todo graph from shell commands.
- **SPEC-002.G2:** Provide stable command names and argument shapes that are easy for agents to memorize and compose.
- **SPEC-002.G3:** Support machine-readable EDN and JSON query output in addition to human-readable local output.
- **SPEC-002.G4:** Fail loudly when command input is malformed instead of silently choosing defaults that hide mistakes.

## SPEC-002.P3 Non-goals

- **SPEC-002.NG1:** The CLI is not a long-running daemon or network service.
- **SPEC-002.NG2:** The CLI does not implement shell completion or an interactive prompt.
- **SPEC-002.NG3:** The CLI does not expose every internal database helper; it exposes the stable operations agents need.
- **SPEC-002.NG4:** The CLI does not parse typed JSON attribute values from `--attr`; shell-provided attributes are strings.

## SPEC-002.P4 Domain concepts

- **SPEC-002.DC1:** CLI commands operate on the task model defined in [task-model.md](./task-model.md).
- **SPEC-002.DC2:** A query command is a command whose result should be printed in the requested output format.
- **SPEC-002.DC3:** A mutation command changes the database and prints output only when machine-readable formatting is explicitly requested.
- **SPEC-002.DC4:** CLI attributes are supplied as repeated `--attr key=value` pairs after the command's positional arguments.

## SPEC-002.P5 Interfaces and contracts

- **SPEC-002.IC1:** The CLI entrypoint is `clojure -M:todo [--db <path>] [--format human|edn|json] <command> [args]`.
- **SPEC-002.IC2:** `--db <path>` selects the SQLite database path; when omitted, the CLI uses `todo.sqlite`.
- **SPEC-002.IC3:** `--format <mode>` accepts `human`, `edn`, or `json`; invalid formats fail before command execution.
- **SPEC-002.IC4:** Global options must appear before the command name.
- **SPEC-002.IC5:** `--attr key=value` is repeatable for `add` and `link`; malformed attributes or misplaced attributes fail the command.
- **SPEC-002.IC6:** `init` takes no arguments and creates the schema in the selected database.
- **SPEC-002.IC7:** `add <id> <title> [--attr key=value ...]` creates or updates a task with string-valued CLI attributes.
- **SPEC-002.IC8:** `link <from-id> <to-id> <edge-type> [--attr key=value ...]` creates or updates an edge with string-valued CLI attributes.
- **SPEC-002.IC9:** `show <id>` returns one task row when the task exists; for a missing task it succeeds with `nil` in EDN/human output and `null` in JSON output.
- **SPEC-002.IC10:** `list` returns all tasks ordered by id.
- **SPEC-002.IC11:** `deps <id>` returns direct `depends-on` dependencies for the task.
- **SPEC-002.IC12:** `transitive-deps <id>` returns recursive `depends-on` dependencies for the task.
- **SPEC-002.IC13:** `blocking <id>` returns tasks directly blocked by the task.
- **SPEC-002.IC14:** `ready` returns incomplete tasks whose direct `depends-on` dependencies are all done.
- **SPEC-002.IC15:** `by-attr <key> <value>` returns tasks whose top-level JSON attribute matches the provided string value; the CLI does not currently parse numeric or boolean query values.
- **SPEC-002.IC16:** `done <id>` sets the conventional task `status` attribute to `done`.
- **SPEC-002.IC17:** Query output normalizes JSON-bearing columns such as `attributes` and `edge_attributes` into data structures before printing EDN or JSON.
- **SPEC-002.IC18:** Human output prints Clojure-readable rows for non-empty collection query results, `(no rows)` for empty collection query results, and `nil` for missing single-row results such as `show <missing-id>`.
- **SPEC-002.IC19:** EDN output prints one EDN value representing the result.
- **SPEC-002.IC20:** JSON output prints one JSON value representing the result.
- **SPEC-002.IC21:** Unknown commands, missing required arguments, invalid formats, malformed attributes, and database/domain exceptions exit non-zero with usage text on stderr.

## SPEC-002.P6 Design decisions

### SPEC-002.D1 Small hand-rolled command parser

- **Decision:** Keep CLI parsing in-project with a small parser.
- **Rationale:** The supported command vocabulary is small, and avoiding a CLI framework keeps the tool simple for agents and maintainers.
- **Rejected:** A heavyweight CLI framework is rejected until command complexity requires it.

### SPEC-002.D2 String attributes at the shell boundary

- **Decision:** Treat `--attr key=value` values as strings.
- **Rationale:** Shell syntax stays simple and predictable; richer typed attributes can still be produced through the REPL or lower-level Clojure API.
- **Rejected:** Parsing shell values as EDN or JSON is rejected for the MVP because it increases quoting complexity for agents.

### SPEC-002.D3 Machine-readable query output

- **Decision:** Support EDN and JSON output modes for query commands.
- **Rationale:** Coding agents can consume structured output directly instead of scraping human text.
- **Rejected:** Human-only CLI output is rejected because it is brittle for automation.

## SPEC-002.P7 Open questions

- **SPEC-002.Q1:** Whether future CLI versions should add typed attribute input or typed attribute queries such as `--attr-json` remains open.
- **SPEC-002.Q2:** Whether future CLI versions should expose a generic edge/graph query command remains open.
