# Spike: Xerial SQLite In-Memory Lifecycle

**Spike ID:** `SPIKE-2026-06-26-001` **Status:** Open **Date:** 2026-06-26 **Related RFC:** [`RFC-005 Library Author Testing Support`](../rfcs/2026-06-26-library-author-testing.md)

## Question

Can Atom run a daemon-world test against real Xerial SQLite in-memory storage while preserving the existing `todo.db` schema, SQL behavior, transaction behavior, and fail-loud lifecycle semantics?

## Context

A quick REPL spike showed:

- `jdbc:sqlite::memory:` and `jdbc:sqlite:file:<name>?mode=memory&cache=shared` work with Xerial SQLite JDBC.
- Using the current `next.jdbc` datasource pattern can lose schema because each operation may open a new connection.
- Holding a single `java.sql.Connection` keeps the in-memory DB alive and allows existing `todo.db` operations to work.

## Scope

Investigate the smallest runtime change that supports real in-memory SQLite for tests.

Focus areas:

- held `java.sql.Connection` lifecycle
- compatibility with `todo.db/init!`, `execute!`, `execute-one!`, and query functions
- compatibility with `jdbc/with-transaction`
- concurrent daemon API calls against one held connection
- close behavior on `runtime/stop!`
- failure behavior if the connection dies

## Non-goals

- No fake persistence layer.
- No Clojure map-backed DB.
- No WASM SQLite.
- No public CLI flag.
- No package/library testing API design beyond proving runtime feasibility.

## Suggested experiment

1. Add a local/private constructor that creates a held in-memory SQLite connection.
2. Start a daemon runtime with the connection as its DB connectable.
3. Run existing DB and daemon operations against it.
4. Add a small concurrency probe: concurrent task add/update/query through daemon API.
5. Confirm stop closes the connection and subsequent use fails loudly.

## Acceptance evidence

- Existing DB operations pass against held in-memory connection.
- Existing daemon API operations pass against held in-memory connection.
- Transactions behave as expected or limitations are documented.
- Connection close is deterministic on runtime stop.
- Any concurrency limitations are explicit.

## Output

Write findings back into this file under `## Findings`, with a recommendation:

- `accept`: viable for `atom.test.alpha` follow-up
- `revise`: viable only with runtime/storage changes
- `reject`: too risky or divergent

## Findings

Recommendation: `revise` — Xerial in-memory SQLite is viable for `atom.test.alpha`, but only if runtime/storage owns a held `java.sql.Connection` and closes it deterministically on runtime stop. The current datasource-only runtime shape is not sufficient.

Concrete findings:

- Current `todo.db` functions already accept a `java.sql.Connection` as the connectable. `db/init!`, `db/add-task!`, `db/all-tasks`, and `jdbc/with-transaction` all worked against a held `DriverManager/getConnection "jdbc:sqlite::memory:"` connection without changing SQL or schema code.
- Transactions behaved as expected on a held connection: a task inserted inside `jdbc/with-transaction` was rolled back after an exception, while earlier committed rows remained visible.
- Closing the held connection is fail-loud: subsequent `todo.db` reads throw `java.sql.SQLException: database connection closed`.
- The existing datasource pattern is incompatible with in-memory SQLite lifecycle. A `next.jdbc/get-datasource` using either `jdbc:sqlite::memory:` or `jdbc:sqlite:file:<name>?mode=memory&cache=shared` failed during existing schema initialization/use because each operation may open a distinct connection and the in-memory schema disappears when no connection anchors it. Observed failure: `org.sqlite.SQLiteException [SQLITE_ERROR] SQL error or missing database (no such table: main.task_edges)`.
- A small daemon-shaped map with `:datasource` set to the held connection worked through `todo.daemon.api/init`, `add`, `list`, `update`, and `ready`.
- A basic concurrency probe using 20 concurrent `api/add` calls followed by 20 concurrent `api/update` calls against the same held connection completed successfully in this local experiment (`created=20`, `listed=20`, all updated to done). This is encouraging but should not be treated as a broad concurrency guarantee; a single shared JDBC connection serializes practical test workloads and is acceptable for daemon-world tests.
- Current `todo.daemon.runtime/stop!` stops socket/nREPL, clears metadata, and deletes runtime metadata, but does not close `:datasource`. File-backed datasource cleanup is currently implicit; a held in-memory connection requires explicit close ownership.

Suggested smallest follow-up design:

- Add a private/test runtime constructor path that creates and stores a held Xerial connection, e.g. `DriverManager/getConnection "jdbc:sqlite::memory:"`, and puts it in the runtime as the DB connectable used by existing daemon APIs.
- Add runtime-owned close behavior for closeable DB connectables on `runtime/stop!` (or introduce a small storage handle such as `{:connectable conn :close! #(.close conn)}`), so in-memory worlds are deterministically destroyed.
- Keep all schema and persistence functions in `todo.db`; do not add a fake persistence layer or alternate SQL path.
- Fail loudly if the held connection has been closed or dies; the native JDBC exception is acceptable unless a storage handle can attach clearer context without swallowing the root cause.

Acceptance status:

- Existing DB operations: viable against held connection.
- Existing daemon API operations: viable against held connection.
- Transactions: viable with `jdbc/with-transaction`.
- Stop/close lifecycle: requires runtime change.
- Concurrency: basic daemon API concurrency works for test-scale use, with the limitation that a single held connection should be considered serialized test storage rather than production-like pooled concurrency.
