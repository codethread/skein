# REPL API Delta — Runtime View Primitives

**Document ID:** `RVP-DELTA-002`
**Status:** Draft
**Date:** 2026-06-25
**Blocked by:** `user-daemon-home` spec promotion
**Updates:** [REPL API](../../../specs/repl-api.md)

## RVP-DELTA-002.P1 Summary

Expose the runtime transformation MVP through connected REPL helpers so trusted Clojure config, human REPL sessions, and `todo daemon repl --stdin` agent workflows can compose named queries, graph traversal, batch hydration, and read-only views.

## RVP-DELTA-002.P2 Contract changes

- **RVP-DELTA-002.C1:** Add `query-ids!` as the id-only counterpart to `query`.
- **RVP-DELTA-002.C2:** Add `tasks-by-ids` for batch task hydration from ids.
- **RVP-DELTA-002.C3:** Add `ancestor-root-ids` for reverse `parent-of` traversal from seed ids to selected root ids.
- **RVP-DELTA-002.C4:** Add either `descendant-ids` or `subgraph` for downward `parent-of` expansion from root ids, matching the daemon runtime primitive selected by this feature.
- **RVP-DELTA-002.C5:** Add `defview!` or `register-view!` as the public REPL helper for registering a named read-only Clojure view function.
- **RVP-DELTA-002.C6:** Add `view` for invoking a registered view by name with optional params.
- **RVP-DELTA-002.C7:** New helpers require an active daemon connection and fail loudly before connection, using the post-`user-daemon-home` remediation language that points to `todo daemon repl` or `connect!`.
- **RVP-DELTA-002.C8:** Helper return values are Clojure data with normalized task rows where tasks are returned. The REPL API does not impose JSON output contracts.

## RVP-DELTA-002.P3 Example shape

Trusted config or a connected REPL can register a view that:

1. uses `query-ids!` to find active seed work for a repo;
2. uses `ancestor-root-ids` to find owning feature roots;
3. uses the downward expansion primitive to get full feature work;
4. uses `tasks-by-ids` to hydrate results;
5. returns a Clojure map shaped for the caller.

## RVP-DELTA-002.P4 Non-goals retained

- **RVP-DELTA-002.N1:** Do not add public CLI `view` invocation.
- **RVP-DELTA-002.N2:** Do not make the REPL helper API a second query language; rich transformation logic remains ordinary trusted Clojure.
