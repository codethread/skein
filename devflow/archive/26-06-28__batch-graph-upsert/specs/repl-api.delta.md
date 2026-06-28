# REPL API delta for batch graph upsert

**Document ID:** `BGU-DELTA-003`
**Root spec:** [repl-api.md](../../specs/repl-api.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-06-28

## BGU-DELTA-003.P1 Summary

This delta adds a blessed trusted Clojure helper surface for transactional batch graph mutation. The helper exposes the core batch payload directly enough for userland flexibility while preserving fail-loud validation at the weaver boundary.

## BGU-DELTA-003.P2 Contract changes

- **BGU-DELTA-003.CC1:** Skein exposes a blessed source-visible namespace for batch graph mutation workflows, `skein.batch.alpha`.
- **BGU-DELTA-003.CC2:** The batch namespace exposes one primary helper, `(apply! payload)`, that routes to the selected weaver when called from connected REPL clients and executes weaver-side when called from trusted config or activated runtime libraries.
- **BGU-DELTA-003.CC3:** The helper accepts the batch payload contract described by the Strand Model delta: `:refs`, `:strands`, `:edges`, and `:burn` with unqualified keyword local refs, create/update classification, explicit edge upsert operations, and fail-loud validation.
- **BGU-DELTA-003.CC4:** The helper returns normalized Clojure data including final refs, created rows, updated before/after rows, burned ids/before rows, and edge outcomes.
- **BGU-DELTA-003.CC5:** The helper is not preloaded into `skein.repl` in the initial scope unless implementation review finds this improves ergonomics without expanding the default helper surface too much. Users can explicitly require the alpha namespace.
- **BGU-DELTA-003.CC6:** Existing helpers such as `strand!`, `update!`, `burn!`, `burn-by-ids!`, `query`, `ready`, graph helpers, views, patterns, and events remain valid; batch graph mutation is an additional composition primitive, not a replacement for narrow helpers.
- **BGU-DELTA-003.CC7:** The batch helper does not accept query definitions directly in the mutation payload. Users compose selection with existing query/graph helpers, then construct a payload over local refs.

## BGU-DELTA-003.P3 Design decisions

### BGU-DELTA-003.D1 A single apply helper over many narrow batch helpers

- **Decision:** The trusted Clojure surface centers on one operation that applies a declarative payload, rather than separate public helpers for batch-create, batch-update, batch-burn, and batch-edge-upsert.
- **Rationale:** A single graph patch keeps transaction boundaries obvious and allows mixed transformations to be expressed atomically.
- **Rejected:** Adding independent helpers whose sequential use would reintroduce partial-progress risks.

### BGU-DELTA-003.D2 Explicit require keeps REPL surface small

- **Decision:** The batch alpha namespace is explicit by default rather than automatically adding another helper to the core preloaded REPL list.
- **Rationale:** Batch mutation is powerful and less common than single-strand inspection/update. Explicit require keeps the small default helper surface while preserving trusted access.
- **Rejected:** Making the helper globally available without namespace qualification in every connected REPL session.

## BGU-DELTA-003.P4 Open questions

- **BGU-DELTA-003.Q1:** None.
