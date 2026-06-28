# Task 1: Convert core lifecycle to state

**Document ID:** `ERF-TASK-001`

## ERF-TASK-001.P1 Scope

Type: AFK

Replace the Clojure storage/query/weaver lifecycle model with `state`, without adding migration or compatibility aliases.

## ERF-TASK-001.P2 Must implement exactly

- **ERF-TASK-001.MI1:** In `src/skein/db.clj`, change the `strands` schema from `legacy boolean lifecycle columns` to required `state TEXT` with valid values `active`, `closed`, and `replaced`; default creates to `active`.
- **ERF-TASK-001.MI2:** Make existing old-schema databases fail loudly through schema compatibility checks. Do not add migration code, alias columns, data backfill, or fallback reads.
- **ERF-TASK-001.MI3:** Replace Clojure storage inputs and patch validation so generic add/update/batch/pattern paths accept `state` values `active` or `closed` only. Reserve `replaced` for the supersession transaction in later tasks.
- **ERF-TASK-001.MI4:** Update readiness SQL to use `t.state = 'active'` and active dependency targets as `dep.state = 'active'`.
- **ERF-TASK-001.MI5:** Update `src/skein/specs.clj` and `src/skein/query.clj` to expose `:state` and remove legacy boolean lifecycle query field/`:legacy inactive timestamp column` query fields.
- **ERF-TASK-001.MI6:** Update `src/skein/weaver/api.clj`, `src/skein/repl.clj`, and Clojure client-facing normalization so rows and patches use `state` only.
- **ERF-TASK-001.MI7:** Update Clojure tests for the touched core/weaver paths, including assertions that old lifecycle fields are rejected.

## ERF-TASK-001.P3 Done when

- **ERF-TASK-001.DW1:** Focused Clojure tests for DB/query/weaver lifecycle behavior pass.
- **ERF-TASK-001.DW2:** `rg 'legacy boolean lifecycle query field|legacy inactive timestamp column|\[:= legacy boolean lifecycle query field|active true|active false' src test` shows no old lifecycle schema references except intentional unrelated words or comments added by this task.
- **ERF-TASK-001.DW3:** No code accepts `state="replaced"` through generic create/update paths.

## ERF-TASK-001.P4 Out of scope

- **ERF-TASK-001.OS1:** Go CLI flag changes.
- **ERF-TASK-001.OS2:** Relation declaration tables, edge predicates, traversal changes, and supersession operation.
- **ERF-TASK-001.OS3:** Root spec/docs promotion.

## ERF-TASK-001.P5 References

- **ERF-TASK-001.REF1:** `devflow/feat/edge-relation-families/proposal.md`
- **ERF-TASK-001.REF2:** `devflow/feat/edge-relation-families/specs/strand-model.delta.md`
- **ERF-TASK-001.REF3:** `devflow/feat/edge-relation-families/edge-relation-families.plan.md`
