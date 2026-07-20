# Task 1: Build owner-partitioned registry kernel

**Document ID:** `TASK-Olr-001`

## TASK-Olr-001.P1 Scope

Type: AFK

Implement the internal, domain-neutral owner registry used by later core and spool migrations. Own `src/skein/core/weaver/owner_registry.clj` (new) and `test/skein/core/weaver/owner_registry_test.clj` (new). Keep it pure except for the one atom that publishes registry state.

## TASK-Olr-001.P2 Must implement exactly

- **TASK-Olr-001.MI1:** Represent complete partitions by stable owner and derive a deterministic effective key map plus owner/provenance projection.
- **TASK-Olr-001.MI2:** Validate a complete candidate before one atomic publication. Readers must receive immutable snapshots and observe either the prior or next effective view.
- **TASK-Olr-001.MI3:** Refuse unauthorized cross-owner duplicates with structured owner/key diagnostics.
- **TASK-Olr-001.MI4:** Support explicit override of one displaced owner/key. Keep the displaced partition stored and visible; refreshing it updates the shadowed value; removing the override restores it; removing the displaced entry leaves the override effective.
- **TASK-Olr-001.MI5:** Provide complete-owner replacement and owner removal. Omitted keys disappear from that partition.
- **TASK-Olr-001.MI6:** Add specs for owner keys, partitions, override declarations, effective entries, snapshots, and mutation results. Invalid state fails before the registry atom changes.

## TASK-Olr-001.P3 Done when

- **TASK-Olr-001.DW1:** Tests cover replacement, deletion, stable-owner rename independence, collision refusal, every override transition, deterministic ordering, malformed candidates, and snapshot concurrency.
- **TASK-Olr-001.DW2:** Property or generative coverage proves normalization is deterministic/idempotent and successful replacement changes only the selected owner partition.
- **TASK-Olr-001.DW3:** `clojure -M:test skein.core.weaver.owner-registry-test` and `make fmt-check lint` pass.

## TASK-Olr-001.P4 Out of scope

- **TASK-Olr-001.OS1:** Do not migrate runtime registries, model modules, load source, or manage resource state.

## TASK-Olr-001.P5 References

- **TASK-Olr-001.REF1:** `DELTA-OlrDrt-001.CC2–CC4`, `PLAN-Olr-001.A1`, and Opus review note `a22m2` F2/F4.
- **TASK-Olr-001.REF2:** `skein.api.vocab.alpha` is precedent for explicit owner collision diagnostics, but its registry contract is not copied wholesale.
