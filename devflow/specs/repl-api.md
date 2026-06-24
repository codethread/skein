# REPL API

**Document ID:** `SPEC-003`
**Status:** Implemented
**Last Updated:** 2026-06-24
**Related RFCs:** None
**Code:** `src/todo`
**Configuration identification:** `SPEC-003` is the third root spec in this repository. Every nested point ID is prefixed with `SPEC-003`.

## SPEC-003.P1 Purpose

The REPL API gives coding agents and human developers a compact interactive Clojure interface for exploring and editing the local todo graph without calling lower-level database functions directly.

## SPEC-003.P2 Goals

- **SPEC-003.G1:** Let a REPL user open a database and perform common task graph operations with a small helper vocabulary.
- **SPEC-003.G2:** Return Clojure data with JSON-bearing database columns normalized into maps or vectors where practical.
- **SPEC-003.G3:** Make the active database explicit enough that helper calls fail clearly before `open!`.
- **SPEC-003.G4:** Mirror the stable task-model operations exposed by the CLI while allowing Clojure-native attribute values.

## SPEC-003.P3 Non-goals

- **SPEC-003.NG1:** The REPL API is not an nREPL server, editor integration, or daemon.
- **SPEC-003.NG2:** The REPL API does not parse CLI-style strings; it accepts Clojure values.
- **SPEC-003.NG3:** The REPL API does not replace `todo.db` as the lower-level storage/query module for implementation work.
- **SPEC-003.NG4:** The REPL API does not manage multiple simultaneous named database handles; it keeps one active datasource for interactive convenience.

## SPEC-003.P4 Domain concepts

- **SPEC-003.DC1:** The REPL API operates on the task model defined in [task-model.md](./task-model.md).
- **SPEC-003.DC2:** The active datasource is the SQLite datasource last selected by `open!`.
- **SPEC-003.DC3:** Helper functions are convenience wrappers around shared database operations.
- **SPEC-003.DC4:** JSON normalization means row fields such as `attributes`, `edge_attributes`, and `blockers` are read into Clojure values when returned by helpers.

## SPEC-003.P5 Interfaces and contracts

- **SPEC-003.IC1:** Callers load the API with `(require '[todo.repl :refer :all])` or an equivalent namespace require.
- **SPEC-003.IC2:** `open!` accepts a database file path, creates a datasource for it, stores it as the active datasource, and returns that datasource.
- **SPEC-003.IC3:** Any helper that requires a datasource must throw a clear exception if called before `open!`.
- **SPEC-003.IC4:** `init!` initializes the schema in the active database.
- **SPEC-003.IC5:** `task!` creates or updates a task; it supports `(task! id title)` and `(task! id title attributes)`, where supported `attributes` values are nil or Clojure maps that encode to JSON objects. Nil attributes are stored as `{}`.
- **SPEC-003.IC6:** `edge!` creates or updates an edge; it supports `(edge! from to type)` and `(edge! from to type attributes)`, where supported `attributes` values are nil or Clojure maps that encode to JSON objects. Nil attributes are stored as `{}`.
- **SPEC-003.IC7:** `depends!` creates or updates a `depends-on` edge; it supports `(depends! from to)` and `(depends! from to attributes)`, where supported `attributes` values are nil or Clojure maps that encode to JSON objects. Nil attributes are stored as `{}`.
- **SPEC-003.IC8:** `done!` sets the conventional task `status` attribute to `done`.
- **SPEC-003.IC9:** `tasks` returns all tasks ordered by id.
- **SPEC-003.IC10:** `task` returns one task by id.
- **SPEC-003.IC11:** `deps` returns direct `depends-on` dependencies for a task.
- **SPEC-003.IC12:** `transitive-deps` returns recursive `depends-on` dependencies for a task.
- **SPEC-003.IC13:** `blocking` returns tasks directly blocked by a task.
- **SPEC-003.IC14:** `ready` returns incomplete tasks whose direct `depends-on` dependencies are all done.
- **SPEC-003.IC15:** `by-attr` returns tasks whose top-level JSON attribute matches the provided key and non-nil scalar value; `nil`/JSON `null` and compound map/vector attribute lookup are outside the current REPL API contract.
- **SPEC-003.IC16:** `graph` returns all edges where the task is either the source or destination.
- **SPEC-003.IC17:** Returned task rows include `:id`, `:title`, and normalized `:attributes` when those fields are available.
- **SPEC-003.IC18:** Dependency-oriented edge rows from `deps` and `blocking` expose normalized edge metadata as `:edge_attributes`.
- **SPEC-003.IC19:** Graph rows from `graph` expose normalized edge metadata as `:attributes` alongside relationship fields; callers should treat those `:attributes` as edge attributes for graph results.

## SPEC-003.P6 Design decisions

### SPEC-003.D1 One active datasource for interactive use

- **Decision:** Store one active datasource selected by `open!`.
- **Rationale:** Agents at a REPL can work concisely without threading a datasource through every exploratory call.
- **Rejected:** Implicitly falling back to a default database is rejected because it can cause writes to the wrong file.

### SPEC-003.D2 Fail before open

- **Decision:** Helper calls that require a database fail loudly if `open!` has not been called.
- **Rationale:** A clear failure is safer than quietly creating or selecting an unintended database.
- **Rejected:** Silent default initialization is rejected because it obscures state.

### SPEC-003.D3 Normalize JSON-bearing results

- **Decision:** REPL helpers convert known JSON string columns to Clojure data before returning results.
- **Rationale:** Interactive callers should work with maps and vectors rather than knowing which SQL columns are JSON-encoded strings.
- **Rejected:** Returning raw JSON strings from REPL helpers is rejected for common helper paths because it leaks storage encoding into exploration.

## SPEC-003.P7 Open questions

- **SPEC-003.Q1:** Whether future versions should support multiple named active datasources remains open.
- **SPEC-003.Q2:** Whether future versions should expose lower-level transaction helpers remains open.
