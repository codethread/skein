# Implement strand lifecycle model

**Document ID:** `TASK-001`

## TASK-001.P1 Scope

Type: AFK

Build the core strand row shape and lifecycle behavior while the code may still live under pre-rename namespaces. This is the tracer bullet for the behavioral change from task statuses to `active` / `inactive_at` / `ephemeral`.

## TASK-001.P2 Must implement exactly

- **TASK-001.MI1:** Update the Clojure data model in `src/todo/db.clj`, `src/todo/query.clj`, and `src/todo/specs.clj` or their renamed equivalents so fresh schemas use `strands`, `strand_edges`, `active`, `inactive_at`, and `ephemeral` instead of `tasks`, `task_edges`, `status`, and `final_at`.
- **TASK-001.MI2:** Replace final-status readiness semantics with active-readiness semantics: ready strands are active and have no direct `depends-on` target that is active.
- **TASK-001.MI3:** Implement persistent lifecycle transitions: deactivating a persistent strand sets `active=false` and `inactive_at`; reactivating it sets `active=true` and clears `inactive_at`.
- **TASK-001.MI4:** Implement ephemeral retention: active ephemeral strands persist; deactivating an already-ephemeral active strand deletes the strand and incident edges.
- **TASK-001.MI5:** Fail loudly for invalid or ambiguous lifecycle/retention requests: create with `active=false` and `ephemeral=true`, update patches that change both `active` and `ephemeral`, status fields, invalid boolean values, or inactive ephemeral persisted rows.
- **TASK-001.MI6:** Update Clojure DB/query tests that exercise schema creation, add/update/show/list, readiness, query fields, reactivation, and ephemeral deletion. Do not preserve old status behavior.

## TASK-001.P3 Done when

- **TASK-001.DW1:** A fresh store initializes the strand schema and no tests expect the old task/status/final_at schema.
- **TASK-001.DW2:** Query DSL fields include `:active`, `:ephemeral`, and `:inactive_at` and reject/remove `:status` and `:final_at` from current contract tests.
- **TASK-001.DW3:** Clojure tests cover active readiness, persistent reactivation, invalid inactive-ephemeral inputs, and destructive ephemeral deactivation.
- **TASK-001.DW4:** Relevant Clojure tests pass.

## TASK-001.P4 Out of scope

- **TASK-001.OS1:** Do not rename all namespaces or the Go CLI in this task unless required by local compilation after the model change.
- **TASK-001.OS2:** Do not add compatibility migrations for old SQLite worlds.
- **TASK-001.OS3:** Do not introduce core `kind`, `status`, `outcome`, or reason fields.

## TASK-001.P5 References

- **TASK-001.REF1:** [Strand model delta](../specs/strand-model.delta.md)
- **TASK-001.REF2:** [Plan](../skein-rename.plan.md) `SR-PLAN-001.PH1`
- **TASK-001.REF3:** Current model code anchors from scout: `src/todo/db.clj`, `src/todo/query.clj`, `src/todo/specs.clj`, and `test/todo/db_test.clj`.
