# Daemon Query Registry Plan

**Document ID:** `PLAN-001`
**Status:** Reviewed
**Last Updated:** 2026-06-25
**Proposal:** [proposal.md](./proposal.md)
**Specs:** [CLI delta](./specs/cli.delta.md), [REPL delta](./specs/repl-api.delta.md), [Daemon delta](./specs/daemon-runtime.delta.md)
**RFC:** [RFC-002 Task Query DSL](../../rfcs/2026-06-24-task-query-dsl.md)
**Philosophy:** [Devflow Philosophy](../../PHILOSOPHY.md)

## PLAN-001.P1 Goal and scope

- **PLAN-001.G1:** Add a daemon-owned in-memory named-query registry that REPL/config workflows can mutate and CLI/REPL clients can read during the daemon lifetime.
- **PLAN-001.G2:** Keep durable persistence out of scope; users reload runtime behavior through trusted config or REPL workflows after daemon restart.
- **PLAN-001.G3:** Preserve ad hoc `--where` and direct REPL query-definition behavior.
- **PLAN-001.G4:** Remove CLI `--query-file` instead of maintaining a parallel CLI query-loading path.

## PLAN-001.P2 Approach

- **PLAN-001.A1:** Store the registry in daemon runtime state, not in SQLite. This matches the user's explicit “in memory” target and avoids schema/persistence work.
- **PLAN-001.A2:** Expose registry operations through `todo.daemon.api`, with client wrappers in `todo.client` so CLI and REPL stay daemon-backed.
- **PLAN-001.A3:** Move REPL named-query behavior from a local atom to daemon calls. Local REPL state should only track the active DB path.
- **PLAN-001.A4:** Do not add CLI registry commands. Single-query registration is handled by REPL `defquery!`; file loading is handled by REPL `load-queries!` or trusted daemon startup config.
- **PLAN-001.A5:** Allow `list --query name` and `ready --query name`; the daemon resolves the name from memory.
- **PLAN-001.A6:** Remove CLI `--query-file` so the CLI remains a small consumer of daemon state rather than a parallel runtime-loading surface.
- **PLAN-001.A7:** Canonicalize registry lookup for simple symbol/keyword names so CLI and REPL can use the same unqualified query name without caring about EDN representation.
- **PLAN-001.A8:** Defer fuzzy “did you mean” suggestions unless a tiny standard-library implementation is sufficient. A clear missing-name error with available names is enough for MVP.

## PLAN-001.P3 Affected areas

- **PLAN-001.AR1:** `todo.daemon.runtime` for lifetime state shape.
- **PLAN-001.AR2:** `todo.daemon.api` for registry operations and named-query list/ready execution.
- **PLAN-001.AR3:** `todo.client` for fixed-form wrappers around registry operations.
- **PLAN-001.AR4:** `todo.cli` for removing `--query-file` and routing `--query` names through daemon memory.
- **PLAN-001.AR5:** `todo.repl` for daemon-backed `defquery!`, `load-queries!`, `queries`, and named query execution.
- **PLAN-001.AR6:** `todo.query` for shared validation, strict EDN file reading, and possibly clearer missing-name errors.
- **PLAN-001.AR7:** CLI, daemon/client, and REPL tests for cross-surface behavior.

## PLAN-001.P4 Implementation phases

- **PLAN-001.PH1:** Add daemon runtime registry state and daemon/client API operations for add/load/list/resolve named queries.
- **PLAN-001.PH2:** Remove CLI `--query-file` and route `list`/`ready --query name` through daemon memory.
- **PLAN-001.PH3:** Wire REPL helpers to daemon memory and remove the process-local query registry as product behavior.
- **PLAN-001.PH4:** Add integration tests and docs/spec updates covering CLI-to-REPL and REPL-to-CLI reuse through one daemon.

## PLAN-001.P5 Validation strategy

- **PLAN-001.V1:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`.
- **PLAN-001.V2:** Add tests proving a query registered or loaded from REPL/client can be used from CLI `list --query name`.
- **PLAN-001.V3:** Add tests proving CLI `--query-file` is no longer accepted.
- **PLAN-001.V4:** Add tests for missing query names and invalid query definitions failing clearly.

## PLAN-001.P6 Task context

- **PLAN-001.T1:** The core behavior should be daemon-memory reuse, not durable DB persistence.
- **PLAN-001.T2:** Query definitions must remain EDN data passed as arguments to fixed daemon API calls.
- **PLAN-001.T3:** Keep fuzzy suggestions optional; avoid new dependencies unless there is an obvious, tiny, well-established Clojure core/library choice already present.

## PLAN-001.P7 Developer Notes

- **PLAN-001.DN1:** Created feature plan from user clarification: CLI and REPL should add/load EDN query definitions into daemon memory, and both should later use a loaded name without a query file.
- **PLAN-001.DN2:** User first refined scope to drop CLI `query add`; REPL owns single-query creation via `defquery!`.
- **PLAN-001.DN3:** User further refined scope to drop CLI `query load`, `query list`, and `--query-file`. Mental model: daemon is the app core; runtime behavior comes from trusted config or REPL, while CLI is a small common-ops/low-privilege consumption surface.
- **PLAN-001.DN4:** TASK-001 stores daemon query registry keys as canonical unqualified strings while accepting simple symbols/keywords at API boundaries; client wrappers preserve daemon domain errors for missing and invalid query names.
- **PLAN-001.DN5:** TASK-002 routes CLI `list --query` / `ready --query` through daemon API calls that resolve names at execution time; `--query-file` is now an unknown CLI option and CLI error output surfaces daemon domain messages such as missing query names.
- **PLAN-001.DN6:** TASK-003 removes the REPL-local query registry from product behavior. REPL registry helpers now call the daemon client, named `query`/`tasks`/`ready` dispatch through daemon named-query operations, and REPL-facing daemon domain errors are rethrown with the daemon message for clear interactive failures. `load-queries!` checks for an active daemon before file I/O and query EDN files now fail loudly when they contain trailing forms.
- **PLAN-001.DN7:** TASK-004 smoke validation now proves a query registered through trusted startup config and a query registered through `todo.repl/defquery!` are consumed by separate CLI subprocesses during the same daemon lifetime. Runtime startup now creates daemon memory state before loading trusted config but still publishes metadata only after config succeeds, preserving fail-loud startup behavior; trusted files can use `todo.daemon.api/register-query!` / `load-queries!` startup helpers instead of reaching into runtime internals. Root docs/specs explicitly state that named queries are in-memory daemon state, must be loaded through trusted config or REPL helpers, and disappear when the daemon stops; CLI `--query-file` remains rejected by parser coverage from TASK-002.
