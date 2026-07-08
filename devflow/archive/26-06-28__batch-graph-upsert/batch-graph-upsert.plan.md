# Batch Graph Upsert Plan

**Document ID:** `BGU-PLAN-001` **Feature:** `batch-graph-upsert` **Proposal:** [proposal.md](./proposal.md) **RFC:** None **Root specs:** [Strand Model](../../specs/strand-model.md), [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md) **Feature specs:** [Strand Model delta](./specs/strand-model.delta.md), [Weaver Runtime delta](./specs/daemon-runtime.delta.md), [REPL API delta](./specs/repl-api.delta.md), [CLI Surface delta](./specs/cli.delta.md) **Status:** Shipped **Last Updated:** 2026-06-28

## BGU-PLAN-001.P1 Goal and scope

Deliver the reviewed batch graph upsert contract as a trusted Clojure workflow: one atomic payload can bind existing refs, create new strands, update existing strands, burn existing strands, and upsert edges by local refs. The initial scope is core storage, weaver API/events, `skein.batch.alpha/apply!`, tests, smoke coverage, and spec promotion; it intentionally does not add a public CLI batch command.

## BGU-PLAN-001.P2 Approach

- **BGU-PLAN-001.A1:** Implement the batch payload parser/validator in `skein.db` at the storage boundary, normalizing top-level refs, classifying strand entries, validating burns and edge ops, and applying the complete mutation inside one transaction.
- **BGU-PLAN-001.A2:** Reuse existing core primitives where they already encode invariants: `add-strand!`, `update-strand!`, `add-edge!`, `strands-by-ids`, and burn deletion behavior. Avoid duplicating SQL invariants outside the transaction path.
- **BGU-PLAN-001.A3:** Keep the existing pattern/weave create-only batch contract working in v1. The new batch primitive is additive and becomes the blessed path for mixed graph transformations.
- **BGU-PLAN-001.A4:** Add a weaver API operation that normalizes results and emits `:batch/applied` followed by compatibility fanout events with shared `:batch/id`.
- **BGU-PLAN-001.A5:** Add `skein.batch.alpha/apply!` as the trusted public helper namespace, routing through the selected weaver for connected REPL clients and executing directly inside the weaver for config/library code.
- **BGU-PLAN-001.A6:** Validate with focused Clojure tests for payload parsing, atomicity, events, helper routing, existing weave compatibility, and final smoke coverage.

## BGU-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| BGU-PLAN-001.AA1 | `src/skein/db.clj` | Add core transactional batch graph mutation primitive and validation helpers. |
| BGU-PLAN-001.AA2 | `src/skein/weaver` | Expose weaver semantic operation and batch event fanout. |
| BGU-PLAN-001.AA3 | `src/skein/batch` | Add blessed alpha helper namespace for trusted workflows. |
| BGU-PLAN-001.AA4 | `src/skein/repl.clj` / connected helper plumbing | Ensure batch helper can route to the selected weaver without preloading broad convenience unless needed. |
| BGU-PLAN-001.AA5 | `test/skein` and `dev/skein/smoke.clj` | Add focused regression, integration, and smoke coverage. |
| BGU-PLAN-001.AA6 | `devflow/specs` | Promote reviewed feature deltas into canonical root specs when shipped. |

## BGU-PLAN-001.P4 Contract and migration impact

- **BGU-PLAN-001.CM1:** Durable strand/edge schema does not change.
- **BGU-PLAN-001.CM2:** Public CLI command surface and JSON socket allowlist do not change.
- **BGU-PLAN-001.CM3:** Existing pattern/weave batch creation payloads remain valid.
- **BGU-PLAN-001.CM4:** New trusted Clojure contract is added via weaver API and `skein.batch.alpha/apply!`.
- **BGU-PLAN-001.CM5:** New event type `:batch/applied` is added; existing per-strand event types are preserved through compatibility fanout.

## BGU-PLAN-001.P5 Implementation phases

### BGU-PLAN-001.PH1 Core batch graph mutation

Outcome: `skein.db` accepts the reviewed payload shape, validates refs/strands/burns/edges fail-loudly, applies all mutations atomically, and returns normalized-enough storage data for the weaver to expose.

### BGU-PLAN-001.PH2 Weaver API and events

Outcome: the weaver exposes the semantic operation, normalizes result data, emits `:batch/applied`, and fans out existing per-strand events in the reviewed order.

### BGU-PLAN-001.PH3 Trusted helper namespace

Outcome: `skein.batch.alpha/apply!` works from connected REPL clients and weaver-side config/library code, without adding public CLI commands.

### BGU-PLAN-001.PH4 Integration, smoke, and docs promotion

Outcome: tests and smoke prove the core paths, existing weave compatibility remains intact, and reviewed spec deltas are ready to merge into root specs when the implementation ships.

## BGU-PLAN-001.P6 Validation strategy

- **BGU-PLAN-001.V1:** Run focused Clojure tests covering happy path, validation failures, transaction rollback, edge cycles, burn constraints, event fanout, helper routing, and existing weave compatibility.
- **BGU-PLAN-001.V2:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` before completion.
- **BGU-PLAN-001.V3:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` after integration changes.
- **BGU-PLAN-001.V4:** Confirm `(cd cli && go test ./...)` still passes because CLI behavior should remain unchanged.

## BGU-PLAN-001.P7 Risks and open questions

- **BGU-PLAN-001.R1:** Event enqueue after commit already has partial notification risks when queues are full; batch fanout increases the number of enqueues. Mitigation: keep `:batch/applied` authoritative and document deterministic fanout without changing the existing event failure model in this feature.
- **BGU-PLAN-001.R2:** Ref validation and operation ordering can become tangled if implemented ad hoc. Mitigation: parse/normalize the whole payload before mutation, then apply in a small deterministic order inside one transaction.
- **BGU-PLAN-001.R3:** Existing create-only batch/weave code may diverge from the new primitive. Mitigation: keep compatibility tests and do not migrate the public pattern contract in this feature.
- **BGU-PLAN-001.Q1:** Future CLI exposure remains intentionally unresolved and out of implementation scope.

## BGU-PLAN-001.P8 Task context

- **BGU-PLAN-001.TC1:** Implement the reviewed spec deltas exactly. The MVP is trusted Clojure only: no `strand batch`, no JSON socket allowlist change, no edge delete/replace, no query-driven mutation inside the payload.
- **BGU-PLAN-001.TC2:** Refs are unqualified non-blank keywords. Raw durable ids appear only in top-level `:refs` values.
- **BGU-PLAN-001.TC3:** Edge upsert replaces attributes on matching `(from, to, type)` edges. Edge operations touching burned refs fail loudly. Edge-only batches produce only `:batch/applied` events.

## BGU-PLAN-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

- Task 1: Added storage-level `skein.db/apply-batch!` for refs/creates/updates/edge upserts/burns. Smoke validation was run from a short `/tmp/skein-copy` checkout because the repo path makes Unix-domain socket paths exceed the platform limit during smoke startup.
- Task 2: Added storage-level `apply-batch!` coverage for successful mixed graph mutation, fail-loud validation without mutation, edge replacement/cycle behavior, and transaction rollback. Root-path smoke still hits the known socket path limit, so smoke was validated from a short `/tmp` checkout.
- Task 3: Added `skein.weaver.api/apply-batch` with `:batch/applied` followed by compatibility fanout carrying only shared `:batch/id`; edge-only batches emit only the batch event. Root-path smoke still hits the known socket path limit, so smoke was validated from a short `/tmp` checkout. Review follow-up strengthened tests for multi-row deterministic fanout order and exact fanout `:batch/*` keys; top-level batch edge outcomes remain represented on `:batch/applied` rather than duplicated into compatibility strand patches.
- Task 4: Added explicit `skein.batch.alpha/apply!` routing through direct weaver runtime calls or connected helper REPL client plumbing, and updated root/user docs after review noted canonical spec drift. Root-path smoke still hits the known socket path limit, so smoke was validated from a short `/tmp` checkout.
- Task 5: Added weaver/helper integration coverage for normalized batch result shape, deterministic batch event fanout, direct and connected `skein.batch.alpha/apply!` routing, and create-only `weave!` compatibility. Root-path smoke still hits the known socket path limit, so smoke was validated from a short `/tmp` checkout.
- Task 6: Extended CLI smoke to call `skein.batch.alpha/apply!` through `strand weaver repl --stdin`, covering bound refs, a created ref, an existing update, an edge upsert, and an existing burn while checking result shape plus final graph observations through REPL and CLI reads. Root-path smoke still hits the known socket path limit; the same smoke suite passes from a short `/tmp` checkout.
- Task 7: Promoted shipped batch graph upsert contracts into root strand model, weaver runtime, REPL API, and CLI specs; marked feature-local deltas merged; set feature status to Shipped. Validation run: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`; root-path `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` still fails with Unix-domain socket path length, and the same smoke suite passes from `/tmp/skein-smoke-short`. Deferred scope remains future public CLI batch commands and explicit edge delete/replace operations.
- Finish archive: Archived the shipped feature under `devflow/archive/26-06-28__batch-graph-upsert/` and updated the devflow README index. No RFCs were linked or archived. Cut/deferred scope remains future public CLI batch commands and explicit edge delete/replace operations.
