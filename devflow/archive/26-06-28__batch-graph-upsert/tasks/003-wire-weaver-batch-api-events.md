# Task 3: Wire weaver batch API events

**Document ID:** `BGU-TASK-003`

## BGU-TASK-003.P1 Scope

Type: AFK

Expose the core batch mutation primitive through the weaver API and implement the reviewed event behavior.

## BGU-TASK-003.P2 Must implement exactly

- **BGU-TASK-003.MI1:** Add a semantic weaver API operation in `src/skein/weaver/api.clj`, preferably `apply-batch`, that calls `db/apply-batch!` and returns normalized data.
- **BGU-TASK-003.MI2:** Generate one `:batch/id` for each successful batch mutation.
- **BGU-TASK-003.MI3:** Enqueue `:batch/applied` after successful commit. The event must include at least the standard event fields, `:batch/id`, final refs, created rows, updated before/after rows, burned ids/before rows, and edge outcomes.
- **BGU-TASK-003.MI4:** Enqueue compatibility fanout after `:batch/applied` in deterministic order: created strand events in result order, updated strand events in result order, and one aggregate `:strand/burned` event when burns occurred.
- **BGU-TASK-003.MI5:** Add only shared `:batch/id` to compatibility fanout events; do not duplicate the full final ref table on every per-strand event.
- **BGU-TASK-003.MI6:** Ensure edge-only batch effects are represented by `:batch/applied` only.
- **BGU-TASK-003.MI7:** Do not add the batch operation to the public JSON socket allowlist or CLI command surface.
- **BGU-TASK-003.MI8:** Keep existing `weave!` behavior and pattern return contract unchanged.

## BGU-TASK-003.P3 Done when

- **BGU-TASK-003.DW1:** A weaver API call can apply a valid batch payload and return normalized rows.
- **BGU-TASK-003.DW2:** The event queue receives `:batch/applied` before compatibility fanout with a shared `:batch/id`.
- **BGU-TASK-003.DW3:** No public CLI JSON socket operation is added.

## BGU-TASK-003.P4 Out of scope

- **BGU-TASK-003.OS1:** `skein.batch.alpha` helper namespace.
- **BGU-TASK-003.OS2:** CLI command implementation.
- **BGU-TASK-003.OS3:** Changing event queue capacity or post-commit failure policy.

## BGU-TASK-003.P5 References

- **BGU-TASK-003.REF1:** `devflow/feat/batch-graph-upsert/specs/daemon-runtime.delta.md`
- **BGU-TASK-003.REF2:** `src/skein/weaver/api.clj`
- **BGU-TASK-003.REF3:** `test/skein/weaver_test.clj`
- **BGU-TASK-003.REF4:** `devflow/specs/daemon-runtime.md`
