# Task 20: Publish peer breaking successor markers

**Document ID:** `TASK-Olr-020`

## TASK-Olr-020.P1 Scope

Type: HITL

Review and execute the irreversible peer release actions prepared by Task 19. The human confirms the explicit compatibility-rule exception, exact candidate commits, and successor markers before any tag or push.

## TASK-Olr-020.P2 Must implement exactly

- **TASK-Olr-020.MI1:** Present the three release packets, expected previous-marker alarm failures, unrelated-failure proof, immutable old pins, and no-shim impact for explicit approval.
- **TASK-Olr-020.MI2:** After approval, create annotated successor tags on the reviewed commits in agent-harness, kanban, and devflow; push tags using each repository's release convention.
- **TASK-Olr-020.MI3:** Resolve and record each tag's peeled commit SHA from the remote. Fail if it differs from the reviewed candidate.
- **TASK-Olr-020.MI4:** Add final release/tag/peeled-SHA notes to card `tsofs` and the plan Developer Notes. Do not change Skein pins yet.

## TASK-Olr-020.P3 Done when

- **TASK-Olr-020.DW1:** Human approval is recorded before publication.
- **TASK-Olr-020.DW2:** All three annotated tags exist remotely, peel to the reviewed commits, and have matching release exception records.
- **TASK-Olr-020.DW3:** Old tags/SHAs remain available and Skein's current `.skein/spools.edn` is unchanged.

## TASK-Olr-020.P4 Out of scope

- **TASK-Olr-020.OS1:** Do not update consumer pins, merge Skein, run `make install`, or restart the canonical weaver.

## TASK-Olr-020.P5 References

- **TASK-Olr-020.REF1:** Task 19 packets; `DELTA-OlrAlpha-001.D2`; peer release guidance; card `tsofs` user authorization.
