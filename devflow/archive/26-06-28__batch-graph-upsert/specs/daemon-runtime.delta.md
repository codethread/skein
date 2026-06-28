# Weaver Runtime delta for batch graph upsert

**Document ID:** `BGU-DELTA-002`
**Root spec:** [daemon-runtime.md](../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-06-28

## BGU-DELTA-002.P1 Summary

This delta adds a weaver semantic operation for applying batch graph mutation payloads and makes the operation available to trusted Clojure workflows. The public JSON CLI allowlist remains unchanged for the initial scope.

## BGU-DELTA-002.P2 Contract changes

- **BGU-DELTA-002.CC1:** The weaver API exposes a semantic batch graph mutation operation that delegates validation and persistence to the core batch graph mutation primitive.
- **BGU-DELTA-002.CC2:** The weaver operation accepts Clojure/EDN-shaped data containing top-level refs, strand entries, edge operation entries, and burn refs, and returns normalized Clojure data with JSON-bearing columns decoded.
- **BGU-DELTA-002.CC3:** The weaver operation emits no partial results on failed validation or failed persistence; failures preserve domain error data well enough for trusted clients to diagnose the rejected payload.
- **BGU-DELTA-002.CC4:** Successful batch mutation emits a coherent batch-level semantic event after commit with `:event/type :batch/applied`. The event records final refs, created rows, updated before/after rows, burned ids/before rows, and edge outcomes. Handler failures retain existing asynchronous event failure behavior.
- **BGU-DELTA-002.CC4a:** Successful batch mutation also emits compatibility fanout through the existing per-strand mutation event types after the batch-level event, with only a shared `:batch/id` value added for correlation. Existing event handlers therefore continue to observe strand mutations performed through the batch path; handlers that need transaction-level context should subscribe to `:batch/applied`.
- **BGU-DELTA-002.CC4b:** Batch event fanout is enqueued in deterministic order: `:batch/applied` first, then created strand events in result order, updated strand events in result order, and one aggregate `:strand/burned` event when the batch burns any strands. Edge-only batch effects are represented by `:batch/applied` only.
- **BGU-DELTA-002.CC5:** The initial JSON Unix socket public operation allowlist is not expanded for arbitrary batch graph mutation. Public CLI exposure is deferred until a JSON contract is intentionally designed.
- **BGU-DELTA-002.CC6:** Pattern invocation may continue to use the existing create-only batch creation path and public pattern return contract in v1, but the batch graph mutation operation becomes the blessed weaver-side primitive for userland transformations that need update, burn, or edge mutation in one transaction.
- **BGU-DELTA-002.CC7:** Runtime library, config, and connected REPL code may call the batch graph mutation helper through a blessed alpha namespace without requiring additional library approval.

## BGU-DELTA-002.P3 Design decisions

### BGU-DELTA-002.D1 Trusted Clojure first, CLI later

- **Decision:** Batch graph mutation is initially exposed through weaver API and trusted Clojure helpers, not as a public JSON socket CLI command.
- **Rationale:** The payload is rich graph-shaped data with refs, operation entries, and future extension points. Keeping initial exposure in trusted Clojure avoids prematurely freezing a JSON CLI surface while still unblocking userland flexibility.
- **Rejected:** Adding `strand batch` immediately to the public CLI allowlist.

### BGU-DELTA-002.D2 Batch events represent transaction context

- **Decision:** The weaver emits `:batch/applied` after a successful batch mutation commit and then fans out existing per-strand mutation event types with the same `:batch/id` and no full ref table duplication.
- **Rationale:** Event handlers need the transaction context to understand multi-strand rewrites, while existing handlers should not silently miss created, updated, or burned strands produced by the new blessed path. Keeping the rich ref table on `:batch/applied` avoids bloating compatibility fanout events.
- **Rejected:** Emitting only independent single-strand events for each effect, emitting only a batch event that bypasses current per-strand subscriptions, or duplicating the full batch result on every fanout event.

## BGU-DELTA-002.P4 Open questions

- **BGU-DELTA-002.Q1:** None.
