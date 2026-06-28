# Task 7: Promote specs and finish feature

**Document ID:** `BGU-TASK-007`

## BGU-TASK-007.P1 Scope

Type: AFK

After implementation and validation pass, merge the reviewed feature spec deltas into root specs and prepare the feature for archive.

## BGU-TASK-007.P2 Must implement exactly

- **BGU-TASK-007.MI1:** Read the final implementation and each feature delta under `devflow/feat/batch-graph-upsert/specs/`.
- **BGU-TASK-007.MI2:** Merge shipped durable contracts into the matching root specs in `devflow/specs/`: strand model, weaver runtime, REPL API, and CLI surface.
- **BGU-TASK-007.MI3:** Mark each feature-local delta `Status: Merged` after its durable content is reflected in the root spec.
- **BGU-TASK-007.MI4:** Update root spec `Last Updated` values and any relevant root spec interface/helper lists.
- **BGU-TASK-007.MI5:** Update `devflow/README.md` only if the root spec index or active/archive feature listing needs adjustment at this point.
- **BGU-TASK-007.MI6:** Update `devflow/feat/batch-graph-upsert/batch-graph-upsert.plan.md` status to `Shipped`, append final Developer Notes summarizing implementation, validation commands, and any cut/deferred scope.
- **BGU-TASK-007.MI7:** Do not archive the feature folder unless explicitly running the full devflow finish/archive procedure in the same session with clean validation and user intent.

## BGU-TASK-007.P3 Done when

- **BGU-TASK-007.DW1:** Root specs describe the shipped batch graph upsert behavior accurately.
- **BGU-TASK-007.DW2:** Feature deltas are marked merged.
- **BGU-TASK-007.DW3:** The feature plan records shipped status, validation, and deferred CLI/edge-delete scope.

## BGU-TASK-007.P4 Out of scope

- **BGU-TASK-007.OS1:** Implementing code behavior not completed by prior tasks.
- **BGU-TASK-007.OS2:** Adding future CLI batch commands or edge delete operations.
- **BGU-TASK-007.OS3:** Archiving without explicit finish/archive intent.

## BGU-TASK-007.P5 References

- **BGU-TASK-007.REF1:** `devflow/feat/batch-graph-upsert/specs/*.delta.md`
- **BGU-TASK-007.REF2:** `devflow/specs/strand-model.md`
- **BGU-TASK-007.REF3:** `devflow/specs/daemon-runtime.md`
- **BGU-TASK-007.REF4:** `devflow/specs/repl-api.md`
- **BGU-TASK-007.REF5:** `devflow/specs/cli.md`
- **BGU-TASK-007.REF6:** `devflow/feat/batch-graph-upsert/batch-graph-upsert.plan.md`
