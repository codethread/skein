# Runtime Transformations PRD delta for skein-rename

**Document ID:** `SR-DELTA-005` **Root document:** [runtime-transformations.md](../../../prd/runtime-transformations.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-26

## SR-DELTA-005.P1 Summary

Runtime transformation examples and terminology move from Atom/todo/task vocabulary to Skein/strand/weaver vocabulary. The PRD's architectural thesis remains unchanged: durable strand facts live in SQLite, and richer behavior lives in trusted runtime libraries, config, and REPL workflows.

## SR-DELTA-005.P2 Contract changes

- **SR-DELTA-005.CC1:** Replace product references from Atom to Skein and command examples from `todo` to `strand` / `strand weaver`.
- **SR-DELTA-005.CC2:** Replace task vocabulary in examples with strand vocabulary, including active/inactive semantics instead of final status semantics.
- **SR-DELTA-005.CC3:** Replace `todo.daemon.api` examples with `skein.weaver.api`.
- **SR-DELTA-005.CC4:** Replace blessed runtime namespace examples from `atom.libs.alpha`, `atom.graph.alpha`, and `atom.views.alpha` to `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`.
- **SR-DELTA-005.CC5:** Replace graph hydration helper examples from `tasks-by-ids` to `strands-by-ids`.
- **SR-DELTA-005.CC6:** Examples must not use core `:kind` or `:status` as if Skein owns those concepts. If examples need userland classification or outcomes, they should label them clearly as attributes chosen by that example world.
- **SR-DELTA-005.CC7:** The flagship runtime transformation shape remains query ids -> graph expansion -> batch hydration -> Clojure shaping -> optional named view invocation.

## SR-DELTA-005.P3 Design decisions

### SR-DELTA-005.D1 Rename examples without expanding transformation scope

- **Decision:** PRD updates should be vocabulary and lifecycle alignment only.
- **Rationale:** The runtime transformation model has already shipped as blessed graph/view primitives. The rename should not add CLI view invocation or new graph helpers.
- **Rejected:** Using the rename feature to broaden the runtime transformation surface.

## SR-DELTA-005.P4 Open questions

- **SR-DELTA-005.Q1:** None for MVP.
