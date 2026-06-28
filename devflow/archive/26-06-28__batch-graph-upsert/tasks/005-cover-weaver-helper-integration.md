# Task 5: Cover weaver helper integration

**Document ID:** `BGU-TASK-005`

## BGU-TASK-005.P1 Scope

Type: AFK

Add integration tests for the weaver operation, event behavior, `skein.batch.alpha/apply!`, and unchanged weave compatibility.

## BGU-TASK-005.P2 Must implement exactly

- **BGU-TASK-005.MI1:** Add weaver-level tests that call the batch operation through `skein.weaver.api` against an initialized runtime and assert normalized result shape.
- **BGU-TASK-005.MI2:** Test event behavior: `:batch/applied` is emitted first; created fanout order matches created result order; updated fanout order matches updated result order; compatibility fanout includes shared `:batch/id`; edge-only batches produce no per-strand fanout; and aggregate burn fanout matches existing burn event shape.
- **BGU-TASK-005.MI3:** Add tests for `skein.batch.alpha/apply!` in the direct/weaver-side mode used by other alpha helper tests.
- **BGU-TASK-005.MI4:** Add connected helper routing coverage proving `skein.batch.alpha/apply!` routes from a connected helper/client context to the selected weaver world. Use the existing alpha/repl test patterns rather than treating this mode as optional.
- **BGU-TASK-005.MI5:** Add a regression test proving existing pattern `weave!` create-only batch behavior still works with its existing public return contract.
- **BGU-TASK-005.MI6:** Keep tests isolated with temporary config/data/runtime worlds and avoid the user's default weaver world.

## BGU-TASK-005.P3 Done when

- **BGU-TASK-005.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes or any unrelated failure is clearly documented.
- **BGU-TASK-005.DW2:** Test coverage proves the trusted helper path and event semantics, not only storage behavior.

## BGU-TASK-005.P4 Out of scope

- **BGU-TASK-005.OS1:** Smoke demo edits.
- **BGU-TASK-005.OS2:** Public CLI batch behavior.
- **BGU-TASK-005.OS3:** Rewriting pattern internals to use the new primitive.

## BGU-TASK-005.P5 References

- **BGU-TASK-005.REF1:** `devflow/feat/batch-graph-upsert/specs/daemon-runtime.delta.md`
- **BGU-TASK-005.REF2:** `devflow/feat/batch-graph-upsert/specs/repl-api.delta.md`
- **BGU-TASK-005.REF3:** `test/skein/weaver_test.clj`
- **BGU-TASK-005.REF4:** `test/skein/repl_test.clj`
- **BGU-TASK-005.REF5:** `test/skein/alpha_test.clj`
- **BGU-TASK-005.REF6:** `test/skein/plugin_test.clj`
