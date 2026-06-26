# REPL API Delta: Runtime Transformation Primitives

**Document ID:** `SPEC-003-D002`
**Status:** Merged into root spec
**Base Spec:** [REPL API](../../../specs/repl-api.md)
**Last Updated:** 2026-06-26

## SPEC-003-D002.P1 Changed contracts

- **SPEC-003-D002.C1:** Atom ships blessed source-visible runtime transformation namespaces `atom.graph.alpha` and `atom.views.alpha` for trusted config and connected REPL workflows.
- **SPEC-003-D002.C2:** `atom.graph.alpha` includes set-oriented query id selection, task hydration by ids, parent-of feature-root traversal, and parent-of DAG/subgraph expansion through `query-ids!`, `tasks-by-ids`, `ancestor-root-ids`, and `subgraph`.
- **SPEC-003-D002.C3:** `atom.views.alpha` includes read-only view registration, invocation, and introspection for daemon-memory views through `register-view!`, `view!`, and `views`. View registration accepts a simple view name and a fully qualified function symbol, not an arbitrary client-side function value.
- **SPEC-003-D002.C4:** Helpers route to the selected daemon world when called from connected REPL clients and execute daemon-side when called from `init.clj` or activated runtime libraries. Connected helper REPL users who want to register new view functions should place them in daemon-loadable config/library code and register their symbols.
- **SPEC-003-D002.C5:** Generated config-dir startup files may require the shipped transformation helper namespace(s) so fresh users can inspect and extend the blessed path immediately. Built-in `atom.*.alpha` namespaces are loaded from the Atom source checkout/classpath and do not require `libs.edn` approval.

## SPEC-003-D002.P2 Unchanged contracts

- **SPEC-003-D002.U1:** Base `todo.repl` remains compact and task-focused; richer transformation helpers live in explicit `atom.*.alpha` namespaces.
- **SPEC-003-D002.U2:** Runtime library workspace helpers remain in `atom.libs.alpha`.
- **SPEC-003-D002.U3:** Views and helper registrations are daemon-lifetime runtime state unless user config reloads them on startup.
