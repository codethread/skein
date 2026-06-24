# Task Model

**Document ID:** `SPEC-001`
**Status:** Implemented
**Last Updated:** 2026-06-24
**Related RFCs:** None
**Code:** `src/todo`
**Configuration identification:** `SPEC-001` is the first root spec in this repository. Every nested point ID is prefixed with `SPEC-001`.

## SPEC-001.P1 Purpose

The task model defines the durable local data contract for the todo graph: task records, open-ended JSON attributes, typed task-to-task edges, and dependency/readiness semantics shared by the CLI, REPL API, TUI, and smoke workflows.

## SPEC-001.P2 Goals

- **SPEC-001.G1:** Store tasks and graph relationships in a local SQLite database that can be used by coding agents and humans without a service process.
- **SPEC-001.G2:** Allow userland task and edge metadata at runtime without requiring a schema migration for every new field.
- **SPEC-001.G3:** Provide dependency semantics that let callers distinguish ready work from work blocked by incomplete direct dependencies.
- **SPEC-001.G4:** Keep the model small enough for agents to understand before reading implementation code.

## SPEC-001.P3 Non-goals

- **SPEC-001.NG1:** The model does not validate userland attribute schemas beyond requiring valid JSON.
- **SPEC-001.NG2:** The model does not enforce a fixed status enum beyond the conventional meaning of `done`.
- **SPEC-001.NG3:** The model does not maintain an edge-type registry; edge types are strings with documented conventions.
- **SPEC-001.NG4:** The model does not currently enforce acyclic dependency graphs.
- **SPEC-001.NG5:** The model does not provide multi-user sync, remote access, or authorization.

## SPEC-001.P4 Domain concepts

- **SPEC-001.DC1:** A task is identified by a unique text `id`, has a required `title`, and has an `attributes` JSON object for runtime/userland metadata.
- **SPEC-001.DC2:** Task attributes are stored as SQLite `TEXT`, must be valid JSON according to JSON1, and are an open-ended object. Write paths reject non-object JSON roots; omitted or nil attributes are normalized to `{}`.
- **SPEC-001.DC3:** Conventional task attributes include `status`, `priority`, `due-date`, and `owner`; callers may add other keys.
- **SPEC-001.DC4:** A task is complete when its `status` attribute is the string `done`; any missing or non-`done` status is considered incomplete for readiness checks.
- **SPEC-001.DC5:** A task edge connects `from_task_id` to `to_task_id`, has an `edge_type` string, and has attributes shaped as an open-ended JSON object; omitted or nil edge attributes are normalized to `{}`.
- **SPEC-001.DC6:** A `depends-on` edge from task `A` to task `B` means `A` is blocked by `B` until `B` is done; conversely, `B` is blocking `A`.
- **SPEC-001.DC7:** A `mentions` edge is a loose relationship with no readiness semantics.
- **SPEC-001.DC8:** A ready task is incomplete and has no direct `depends-on` dependency whose task is incomplete.

## SPEC-001.P5 Interfaces and contracts

- **SPEC-001.IC1:** The `tasks` table has columns `id TEXT PRIMARY KEY`, `title TEXT NOT NULL`, and `attributes TEXT NOT NULL DEFAULT '{}'` with `CHECK (json_valid(attributes))`.
- **SPEC-001.IC2:** The `task_edges` table has `from_task_id`, `to_task_id`, `edge_type`, and `attributes`, with a primary key of `(from_task_id, to_task_id, edge_type)` and JSON validity on `attributes`.
- **SPEC-001.IC3:** The schema declares foreign-key relationships from edge endpoints to tasks with `ON DELETE CASCADE`; reliable enforcement depends on SQLite foreign-key enforcement being enabled for the connection executing the write.
- **SPEC-001.IC4:** Creating a task with an existing id updates its title and attributes rather than creating a duplicate row.
- **SPEC-001.IC5:** Creating an existing edge updates its attributes rather than creating a duplicate row.
- **SPEC-001.IC6:** Attribute lookup operates on top-level task attributes and compares the JSON value exposed by SQLite JSON1 with the caller-provided value; JSON `null` matching is outside the current contract, and interface-specific specs may further narrow accepted value types.
- **SPEC-001.IC7:** Direct dependencies list the `to_task_id` tasks reachable through `depends-on` edges from the queried task.
- **SPEC-001.IC8:** Blocking tasks list the `from_task_id` tasks that directly depend on the queried task through `depends-on` edges.
- **SPEC-001.IC9:** Transitive dependencies recursively follow `depends-on` edges from the queried task and return dependency tasks, excluding the queried task from the result.
- **SPEC-001.IC10:** Readiness is based on direct `depends-on` dependencies only; transitive dependency traversal is available for inspection, not required for the ready predicate because an incomplete indirect blocker implies an incomplete direct dependency elsewhere in the chain.

## SPEC-001.P6 Design decisions

### SPEC-001.D1 JSON TEXT attributes

- **Decision:** Store task and edge attributes as JSON text in SQLite `TEXT` columns and query them with JSON1.
- **Rationale:** Agents can add runtime metadata such as `priority`, `owner`, `due-date`, or estimates without changing the relational schema.
- **Rejected:** JSONB assumptions and fixed attribute columns are rejected for this MVP because SQLite JSON1 text is sufficient and more flexible.

### SPEC-001.D2 Status lives in attributes

- **Decision:** Store task completion state in the conventional `status` task attribute.
- **Rationale:** Status is useful immediately, but keeping it in attributes avoids schema churn before the task model stabilizes.
- **Rejected:** A dedicated status column is deferred until a future migration justifies stricter status behavior.

### SPEC-001.D3 Dependency direction follows the blocked task

- **Decision:** A `depends-on` edge points from the blocked task to the task it needs.
- **Rationale:** Queries for a task's dependencies and transitive dependencies can follow outgoing `depends-on` edges naturally.
- **Rejected:** Reversing the edge direction would make dependency traversal less direct and force callers to mentally invert the relationship.

## SPEC-001.P7 Open questions

- **SPEC-001.Q1:** Whether future versions should add schema validation for userland attributes remains open.
- **SPEC-001.Q2:** Whether future versions should enforce dependency acyclicity remains open.
- **SPEC-001.Q3:** Whether datasource creation should guarantee SQLite foreign-key enforcement for every connection remains open.
