# Task 3: Promote spec and finish user documentation

**Document ID:** `TASK-Xer-003`

## TASK-Xer-003.P1 Scope

Type: AFK

Promote the reviewed delta into the durable root spec and add the smallest user-facing edge-removal guidance.

## TASK-Xer-003.P2 Must implement exactly

- **TASK-Xer-003.MI1:** Merge both reviewed delta contract changes into `SPEC-001.P6` and mark the feature delta Merged.
- **TASK-Xer-003.MI2:** Add a concise `:remove` example or mention beside existing batch-upsert material in the reference and tutorial.
- **TASK-Xer-003.MI3:** Keep graph connectivity, rewiring, projection, CLI, and retry promises out of the shipped docs.

## TASK-Xer-003.P3 Done when

- **TASK-Xer-003.DW1:** `make docs-check` passes.
- **TASK-Xer-003.DW2:** The root spec, public API reference, reference guide, and tutorial agree on grammar and observable outcomes.

## TASK-Xer-003.P4 Out of scope

- **TASK-Xer-003.OS1:** Additional API surface, migration guidance, or changes to the separate create-path edge declaration outcome.

## TASK-Xer-003.P5 References

- **TASK-Xer-003.REF1:** [Strand-model delta](../specs/strand-model.delta.md), [plan](../xijst-edge-removal.plan.md), and `devflow/specs/strand-model.md`.
- **TASK-Xer-003.REF2:** `docs/reference.md` and `docs/tutorial.md`.
