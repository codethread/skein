# Weaver Runtime delta for patterned weave

**Document ID:** `DELTA-003`
**Root spec:** [Weaver Runtime](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Draft
**Last Updated:** 2026-06-27

## DELTA-003.P1 Summary

The weaver runtime gains an in-memory pattern registry and a semantic weave operation. Patterns are trusted runtime transformation hooks that turn input data into atomic batch strand creation using the existing database primitive. Each pattern also has a registered Clojure spec input contract so the weaver can validate caller payloads and expose a caller-oriented explanation over the thin JSON CLI.

## DELTA-003.P2 Contract changes

- **DELTA-003.CC1:** A weaver owns one in-memory pattern registry for its lifetime alongside the query, view, approved-library sync, and module-use registries.
- **DELTA-003.CC2:** Weaver API operations include registering one pattern, listing patterns, resolving one pattern, and invoking a pattern to weave strands.
- **DELTA-003.CC3:** Pattern names are simple unqualified names. Symbol and keyword forms of the same name resolve to one registry entry, following named query/view conventions where practical.
- **DELTA-003.CC4:** Pattern registry entries point to fully qualified function symbols resolvable in the weaver JVM and to a Clojure spec name for validating input payloads.
- **DELTA-003.CC5:** Weave invocation resolves the registered input spec, validates the JSON-decoded payload as `:input`, resolves the registered function symbol, calls it with a context map containing at least `:input`, requires the return value to satisfy the existing batch input contract, and calls `skein.db/add-strand-batch!`.
- **DELTA-003.CC6:** Weave returns normalized created strand rows and the ref map produced by the batch primitive.
- **DELTA-003.CC7:** Pattern invocation and batch creation happen in the weaver process. Pattern function errors, invalid return shape, missing target refs, missing durable ids, cycles, and database errors fail loudly and leave no partial batch writes.
- **DELTA-003.CC8:** The JSON socket operation allowlist includes `weave` for invoking already-registered patterns and `pattern-explain` for read-only caller guidance. Pattern registration and full registry listing remain trusted config/REPL workflows and are excluded from the public JSON socket allowlist.
- **DELTA-003.CC9:** `pattern-explain` returns a JSON-serializable map derived from the registered input spec using existing Clojure spec ecosystem tools where practical, including enough structure for a caller to build a candidate payload and the raw spec name for richer REPL follow-up.
- **DELTA-003.CC10:** `libs/reload!` clears pattern registry state along with other weaver-lifetime config state before reloading `init.clj`.

## DELTA-003.P3 Design decisions

### DELTA-003.D1 Owner policy layer above batch refs

- **Decision:** Pattern functions sit above `add-strand-batch!` rather than replacing it.
- **Rationale:** The existing primitive already owns id generation, ephemeral refs, edge resolution, transactionality, and validation. Patterns add owner-controlled transformation and workflow policy.

### DELTA-003.D2 Create-only MVP

- **Decision:** The MVP weave operation creates new strands and edges via the batch primitive; true upsert identity semantics are deferred.
- **Rationale:** Upsert requires durable identity policy that differs by user/workflow. Trusted patterns may still query existing strands and reference durable ids as string edge targets when needed.

### DELTA-003.D3 Spec-backed input contracts

- **Decision:** Pattern registration requires an input spec name in addition to the function symbol.
- **Rationale:** The weaver can fail before arbitrary pattern code runs when the payload is malformed, and the same contract can be transformed into caller guidance for `pattern-explain` using existing spec/schema tooling.

## DELTA-003.P4 Open questions

- **DELTA-003.Q1:** Decide whether to ship `metosin/spec-tools` for JSON Schema/error conversion, start with built-in `s/form` + generated examples, or consider Malli as a future schema source for richer descriptions. Avoid inventing a Skein-owned schema AST unless ecosystem tooling proves insufficient.
