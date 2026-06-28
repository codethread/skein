# Task 4: Add batch alpha helper

**Document ID:** `BGU-TASK-004`

## BGU-TASK-004.P1 Scope

Type: AFK

Add the trusted Clojure helper namespace `skein.batch.alpha` with the primary `(apply! payload)` operation.

## BGU-TASK-004.P2 Must implement exactly

- **BGU-TASK-004.MI1:** Create `src/skein/batch/alpha.clj` exposing `(apply! payload)`.
- **BGU-TASK-004.MI2:** When called inside the active weaver JVM, route directly to the weaver/current runtime operation consistent with other blessed alpha namespaces.
- **BGU-TASK-004.MI3:** When called from a connected helper REPL client, route to the selected weaver world through existing client/helper plumbing.
- **BGU-TASK-004.MI4:** Return the normalized Clojure data from the weaver operation without imposing a JSON envelope.
- **BGU-TASK-004.MI5:** Do not preload the helper into `skein.repl` unless needed to make connected routing work; explicit `(require '[skein.batch.alpha :as batch])` should be the documented path.
- **BGU-TASK-004.MI6:** Do not add any CLI command or JSON socket operation.

## BGU-TASK-004.P3 Done when

- **BGU-TASK-004.DW1:** `(require '[skein.batch.alpha :as batch])` succeeds from project/test classpath.
- **BGU-TASK-004.DW2:** `(batch/apply! payload)` works in the same routing modes as other blessed alpha runtime helpers.
- **BGU-TASK-004.DW3:** Existing `skein.repl` helper list remains small unless a minimal routing hook is required.

## BGU-TASK-004.P4 Out of scope

- **BGU-TASK-004.OS1:** Core storage behavior already covered by Tasks 1-2.
- **BGU-TASK-004.OS2:** Event behavior already covered by Task 3.
- **BGU-TASK-004.OS3:** Public CLI batch command.

## BGU-TASK-004.P5 References

- **BGU-TASK-004.REF1:** `devflow/feat/batch-graph-upsert/specs/repl-api.delta.md`
- **BGU-TASK-004.REF2:** `src/skein/graph/alpha.clj`
- **BGU-TASK-004.REF3:** `src/skein/views/alpha.clj`
- **BGU-TASK-004.REF4:** `src/skein/patterns/alpha.clj`
- **BGU-TASK-004.REF5:** `src/skein/repl.clj`
