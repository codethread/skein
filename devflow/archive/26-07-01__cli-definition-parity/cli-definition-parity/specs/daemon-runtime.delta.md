# Weaver Runtime delta for CLI definition parity

**Document ID:** `CDP-DELTA-003`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-07-01

## CDP-DELTA-003.P1 Summary

The weaver gains read-only query introspection API helpers and two JSON socket operations, `query-list` and `query-explain`, matching the shipped `pattern-list` / `pattern-explain` precedent. The SPEC-004.C27 registry exclusion narrows for queries: mutation/authoring stays excluded from the JSON socket; read-only introspection joins the allowlist. The shared transactional batch engine behind `weave` and `apply-batch` is documented explicitly.

## CDP-DELTA-003.P2 Contract changes

- **CDP-DELTA-003.CC1:** SPEC-004.C16 weaver API operations gain query introspection helpers used by both socket dispatch and REPL helpers: list registered query metadata (`query-metadata`) and explain one registered query (`query-explain`). `queries` remains the raw trusted registry listing.
- **CDP-DELTA-003.CC2:** Query metadata listing returns a vector of serializable entries ordered by canonical name. Each entry contains the canonical `:name`, declared `:params` (the allowlist from map query definitions; empty for vector definitions), and `:referenced-params` (parameter references discovered by walking the effective `:where` expression for `[:param kw]` forms, including relation positions in `[:edge/out ...]` / `[:edge/in ...]` predicates). Entries do not embed full definitions.
- **CDP-DELTA-003.CC3:** Query explanation returns one query's serializable caller guidance: the listing fields plus `:where` (effective where expression as a JSON-safe projection), `:definition` (normalized registered definition as a JSON-safe projection), `:where-form` / `:definition-form` (exact `pr-str` EDN strings for trusted users and docs), and a short `:summary` stating that invocation happens through `list --query` / `ready --query` with runtime params. Unknown names fail loudly with the existing `query/not-found` domain error including available names.
- **CDP-DELTA-003.CC4:** SPEC-004.C26 JSON socket allowlist gains `query-list` and `query-explain`. `query-list` accepts an empty arguments object; `query-explain` accepts exactly `{"query": <string>}`, with the name passing through the same trim/leading-colon normalization used by `list-query` / `ready-query`, so blank names fail as domain errors at normalization, matching existing named-definition transport behavior. Both dispatch to the weaver API introspection helpers, not to transport-local registry reads.
- **CDP-DELTA-003.CC5:** SPEC-004.C27 is narrowed for queries: query registry mutation and authoring remain excluded from the JSON socket; read-only query introspection is allowlisted. Pattern registry mutation, view registry operations, CLI operation registry mutation/listing, event handler operations, and lifecycle hook operations remain excluded unchanged.
- **CDP-DELTA-003.CC6:** `query-list` and `query-explain` are read-only operations and are not added to the SPEC-004.C80 `:payload/received` hook operation set.
- **CDP-DELTA-003.CC7:** SPEC-004.P10 gains framing language for the shared write engine: pattern-created batches (`weave`, SPEC-004.C61) and trusted transactional graph batch mutation (`apply-batch`, SPEC-004.C59a) are two doors into one transactional batch engine at different trust tiers — `weave` is the named, spec-checked, create-only CLI-safe door; raw batch remains trusted Clojure because it can create, update, burn, and upsert edges.

## CDP-DELTA-003.P3 Design decisions

### CDP-DELTA-003.D1 Distinct socket operations instead of exposing the `queries` helper

- **Decision:** Public socket introspection uses new `query-list` / `query-explain` operations with an ordered, metadata-oriented result shape; the raw registry map returned by `queries` is not the wire contract.
- **Rationale:** CLI callers need stable ordered JSON metadata; the raw registry shape is a trusted Clojure convenience and would leak EDN-oriented structure into the public wire contract (CDP-PROP-001.S2).
- **Rejected:** Reusing the trusted `queries` return shape as the socket operation result.

### CDP-DELTA-003.D2 Referenced-param discovery is part of the introspection contract

- **Decision:** Introspection reports both the declared `:params` allowlist and the `[:param ...]` references actually used by the effective where expression.
- **Rationale:** Declared params are what the socket accepts (unknown params are rejected); referenced params are what a successful invocation must provide. Vector definitions can carry relation-position param references that pass registration validation but are uninvocable through the CLI — introspection should make that visible rather than hide it (TEN-003, CDP-PROP-001.S4).
- **Rejected:** Reporting only the declared allowlist; schema inference or result previews (CDP-PROP-001.NG8).

## CDP-DELTA-003.P4 Open questions

- **CDP-DELTA-003.Q1:** None. JSON wire keys follow the existing Clojure-origin kebab style (`referenced-params`, `where-form`, `definition-form`) already used by pattern explanation transport.
