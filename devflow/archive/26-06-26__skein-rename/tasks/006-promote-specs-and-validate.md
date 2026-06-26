# Promote specs and validate

**Document ID:** `TASK-006`

## TASK-006.P1 Scope

Type: AFK

Promote the reviewed feature-local deltas into canonical root specs/PRD after implementation and smoke are complete, then run final validation and prepare the feature for finish/archive.

## TASK-006.P2 Must implement exactly

- **TASK-006.MI1:** Merge [strand-model.delta.md](../specs/strand-model.delta.md) into the root task model by promoting `devflow/specs/task-model.md` to `devflow/specs/strand-model.md` with implemented Skein/strand terminology and active/ephemeral lifecycle contracts.
- **TASK-006.MI2:** Merge [cli.delta.md](../specs/cli.delta.md) into `devflow/specs/cli.md` with `strand`, `weaver`, active/ephemeral flags, Skein worlds, `weaver.*` metadata, and generated `skein.*.alpha` config.
- **TASK-006.MI3:** Merge [repl-api.delta.md](../specs/repl-api.delta.md) into `devflow/specs/repl-api.md` with `skein.repl`, strand helpers, and renamed blessed namespaces.
- **TASK-006.MI4:** Merge [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md) into `devflow/specs/daemon-runtime.md`, renaming the root spec title/framing to Weaver Runtime.
- **TASK-006.MI5:** Merge [runtime-transformations.delta.md](../specs/runtime-transformations.delta.md) into `devflow/prd/runtime-transformations.md`.
- **TASK-006.MI6:** Update `devflow/README.md` root spec index for `strand-model.md`, active feature status, and any renamed spec titles.
- **TASK-006.MI7:** Mark feature-local deltas `Merged` after promotion and update `skein-rename.plan.md` Developer Notes with validation outcomes and any cut/deferred scope.

## TASK-006.P3 Done when

- **TASK-006.DW1:** Root specs/PRD describe the shipped implementation and feature-local deltas are marked `Merged`.
- **TASK-006.DW2:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` pass.
- **TASK-006.DW3:** `git status --short` shows no generated SQLite, socket, metadata, smoke, or built CLI artifacts.
- **TASK-006.DW4:** The feature is ready for the devflow finish/archive procedure, including moving RFC-006 with the archived feature.

## TASK-006.P4 Out of scope

- **TASK-006.OS1:** Do not archive the feature folder unless the user explicitly invokes finish/archive after reviewing the completed implementation.
- **TASK-006.OS2:** Do not promote unimplemented behavior into root specs.
- **TASK-006.OS3:** Do not update public publishing handle claims.

## TASK-006.P5 References

- **TASK-006.REF1:** [Plan](../skein-rename.plan.md) `SR-PLAN-001.PH6`
- **TASK-006.REF2:** All feature-local deltas under [../specs](../specs)
- **TASK-006.REF3:** Root specs under `devflow/specs/` and [Runtime Transformations PRD](../../../prd/runtime-transformations.md)
