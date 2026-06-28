# Task 8: Sweep docs smoke and final validation

**Document ID:** `ERF-TASK-008`

## ERF-TASK-008.P1 Scope

Type: AFK

Finish the feature by updating current specs/docs/smoke examples, running full validation, and proving old lifecycle schema references are gone from current code/docs.

## ERF-TASK-008.P2 Must implement exactly

- **ERF-TASK-008.MI1:** Update canonical root specs in `devflow/specs/` for the shipped model: `state`, operational relation declarations, supersession, edge predicates, relation traversal, CLI flags, REPL helpers, and runtime API/socket behavior.
- **ERF-TASK-008.MI2:** Update `devflow/TENETS.md` with the staged TEN-005 replacement when implementation behavior matches it.
- **ERF-TASK-008.MI3:** Update public docs and contributor/agent guides: `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `docs/getting-started.md`, `docs/skein.md`, `docs/clojure-crash-course.md`, and `dev/user.clj` examples.
- **ERF-TASK-008.MI4:** Update `dev/skein/smoke.clj` to exercise `--state`, state-based readiness, relation predicates if practical, and the supersession command/helper through disposable config-dir worlds.
- **ERF-TASK-008.MI5:** Remove old lifecycle schema from current code/docs. No compatibility examples for `active`, `inactive_at`, `--active`, or `:active` should remain in source, public docs, canonical specs, or active feature docs after Task 9 has adapted batch graph upsert.
- **ERF-TASK-008.MI6:** Do not edit `devflow/archive`. Grep checks for removed lifecycle schema must exclude archived historical devflow folders only.
- **ERF-TASK-008.MI7:** Run the full validation suite and clean generated artifacts.

## ERF-TASK-008.P3 Done when

- **ERF-TASK-008.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- **ERF-TASK-008.DW2:** `(cd cli && go test ./...)` passes.
- **ERF-TASK-008.DW3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes.
- **ERF-TASK-008.DW4:** Grep checks for removed lifecycle schema tokens pass across code/docs/current devflow files after Task 9, excluding `devflow/archive`, allowing only non-schema English uses of the word "active" such as "active feature" or "active weaver".
- **ERF-TASK-008.DW5:** `git status --short` shows no generated SQLite/runtime metadata artifacts.

## ERF-TASK-008.P4 Out of scope

- **ERF-TASK-008.OS1:** Implementing additional annotation workflow semantics beyond the shipped supersession operation.
- **ERF-TASK-008.OS2:** Live migration support for old databases.
- **ERF-TASK-008.OS3:** Adding `replaced_by` or compatibility lifecycle aliases.
- **ERF-TASK-008.OS4:** Editing `devflow/archive` historical files.

## ERF-TASK-008.P5 References

- **ERF-TASK-008.REF1:** `devflow/feat/edge-relation-families/edge-relation-families.plan.md`
- **ERF-TASK-008.REF2:** `devflow/feat/edge-relation-families/specs/`
- **ERF-TASK-008.REF3:** `devflow/rfcs/2026-06-28-edge-relation-families.md`
- **ERF-TASK-008.REF4:** `AGENTS.md`
