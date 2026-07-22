# Task 3: Promote contracts and run queue acceptance

**Document ID:** `TASK-Clp-003`

## TASK-Clp-003.P1 Scope

Type: AFK

Promote the implemented Clock and polling contracts into the current root specs, update authored guidance and generated API reference, then run the queue-acceptance gates once.

## TASK-Clp-003.P2 Must implement exactly

- **TASK-Clp-003.MI1:** Merge the durable outcomes from the three feature deltas into `devflow/specs/daemon-runtime.md`, `devflow/specs/repl-api.md`, and `devflow/specs/alpha-surface.md`; mark each delta Merged. Preserve existing clause IDs where amended and add only the smallest new clauses needed for Clock, runtime access, manual controls, and `poll-until!`.
- **TASK-Clp-003.MI2:** Update `docs/reference.md`, `docs/spools/writing-shared-spools.md`, `spools/README.md`, `spools/workflow.md`, and `spools/roster.md` for the new Clock dependency, relative timeout helper, and positive caller cadence. Keep prose plain and factual; do not repeat generated API reference.
- **TASK-Clp-003.MI3:** Run `make api-docs` after all public docstrings settle. Search active source/spec/docs for the removed helper and old zero-argument clock-function contract; only archived feature history may retain them as historical text.
- **TASK-Clp-003.MI4:** Run the cold focused suites named in Tasks 1 and 2, then `make fmt-check lint reflect-check docs-check`, `(cd cli && go test ./...)`, and `clojure -M:smoke`. At queue acceptance only, run `flock -w 3600 /tmp/skein-test.lock clojure -M:test` once.
- **TASK-Clp-003.MI5:** Set `PLAN-Clp-001` to Shipped only after implementation, contract promotion, generation, and validation succeed. Confirm `git status --short` has no SQLite or runtime metadata artifacts.

## TASK-Clp-003.P3 Done when

- **TASK-Clp-003.DW1:** Authored docs and all three root specs describe one runtime-owned Clock, manual deterministic sleep/pumps, `runtime/clock`, and required-Clock `poll-until!` without contradicting source.
- **TASK-Clp-003.DW2:** Generated API docs are current and every quality, Go, smoke, focused, and full locked Clojure gate in MI4 passes.
- **TASK-Clp-003.DW3:** `git diff --check` passes and the worktree contains only intended source, test, spec, plan/task, authored doc, and generated API changes.

## TASK-Clp-003.P4 Out of scope

- **TASK-Clp-003.OS1:** Do not rename weaver verbs, add peer software-version metadata, or widen Clock beyond `now` and `sleep!`.
- **TASK-Clp-003.OS2:** Do not run `make install`, restart the canonical weaver, or use the repo coordination workspace for tests or smoke experiments.

## TASK-Clp-003.P5 References

- **TASK-Clp-003.REF1:** [PLAN-Clp-001](../clock-aware-polling.plan.md) A6, PH3, V4, and Task context.
- **TASK-Clp-003.REF2:** [Alpha Surface delta](../specs/alpha-surface.delta.md), [REPL API delta](../specs/repl-api.delta.md), and [Weaver Runtime delta](../specs/daemon-runtime.delta.md).
- **TASK-Clp-003.REF3:** Repository `AGENTS.md` validation and workspace safety rules.
