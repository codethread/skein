# REPL API delta for CLI definition parity

**Document ID:** `CDP-DELTA-002` **Root spec:** [repl-api.md](../../../specs/repl-api.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-01

## CDP-DELTA-002.P1 Summary

`skein.repl` gains `(query-explain name)` beside `queries` for parity with `pattern-explain`. Raw registry access through `queries` is unchanged; the new helper returns the same caller-guidance shape the CLI receives.

## CDP-DELTA-002.P2 Contract changes

- **CDP-DELTA-002.CC1:** The SPEC-003.P2 helper list gains `query-explain`, listed beside `queries`.
- **CDP-DELTA-002.CC2:** `(query-explain name)` accepts a simple symbol, keyword, or string query name, resolves it against the active weaver's in-memory query registry, and returns serializable caller guidance with the same core fields as CLI `query explain`: canonical name, declared params, referenced params, the effective where expression, the normalized definition, exact EDN form strings, and a short invocation summary. Missing names fail loudly with the existing `query/not-found` behavior including available names.
- **CDP-DELTA-002.CC3:** `queries` keeps returning the raw in-memory registry map (SPEC-003.C11). The metadata/explain helpers are additive; no existing REPL return shape changes.
- **CDP-DELTA-002.CC4:** The connected-client fixed-form operation table (`skein.client`) gains a `query-explain` mapping so explicit `connect!` workflows reach the weaver API helper, matching the existing `pattern-explain` routing.

## CDP-DELTA-002.P3 Design decisions

### CDP-DELTA-002.D1 Add explain beside raw registry access instead of changing `queries`

- **Decision:** Keep `queries` as raw trusted registry listing and add a distinct caller-guidance helper.
- **Rationale:** Trusted REPL users already rely on the raw map for direct definition access; the CLI-oriented metadata shape is a different consumer contract (CDP-PROP-001.S3).
- **Rejected:** Reshaping `queries` output to the ordered metadata vector used by the JSON socket.

## CDP-DELTA-002.P4 Open questions

- **CDP-DELTA-002.Q1:** None.
