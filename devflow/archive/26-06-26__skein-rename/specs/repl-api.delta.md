# REPL API delta for skein-rename

**Document ID:** `SR-DELTA-003` **Root spec:** [repl-api.md](../../../specs/repl-api.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-26

## SR-DELTA-003.P1 Summary

The REPL API moves from task vocabulary to strand vocabulary and from `todo.*` / `atom.*.alpha` namespaces to `skein.*` namespaces. The helper surface continues to be the rich trusted Clojure workflow over a selected weaver world.

## SR-DELTA-003.P2 Contract changes

- **SR-DELTA-003.CC1:** The connected helper namespace is `skein.repl`; `todo.repl` is removed from the public helper contract.
- **SR-DELTA-003.CC2:** `strand weaver repl` preloads `skein.repl`, connects to the selected weaver world, and presents the renamed helper prompt.
- **SR-DELTA-003.CC3:** Task-named helpers are renamed: `task!` becomes `strand!`, `task` becomes `strand`, and `tasks` becomes `strands`. The old helper names are not compatibility aliases.
- **SR-DELTA-003.CC4:** Generic helper names remain when still accurate: `connect!`, `init!`, `update!`, `defquery!`, `load-queries!`, `queries`, `query`, and `ready`.
- **SR-DELTA-003.CC5:** `strand!` creates a strand with title, optional attributes, optional `active`, and optional `ephemeral`. It returns the created strand row with normalized JSON attributes.
- **SR-DELTA-003.CC6:** `update!` accepts patch keys for `:title`, `:active`, `:ephemeral`, `:attributes`, and `:edges`. It no longer accepts `:status` as a core lifecycle key.
- **SR-DELTA-003.CC6a:** REPL/helper calls follow the strand model invariant: create with `{:active false :ephemeral true}` fails loudly, and patches that change both `:active` and `:ephemeral` fail loudly. Delete-on-deactivate requires an active strand that was already ephemeral before the deactivation patch.
- **SR-DELTA-003.CC7:** `strand`, `strands`, `query`, and `ready` return strand-shaped rows containing `active`, `ephemeral`, and `inactive_at`, not `status` or `final_at`.
- **SR-DELTA-003.CC8:** Blessed runtime namespaces are renamed to `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`.
- **SR-DELTA-003.CC9:** `skein.graph.alpha/tasks-by-ids` is renamed to `skein.graph.alpha/strands-by-ids`; other graph/view helper names remain when their vocabulary is still accurate (`query-ids!`, `ancestor-root-ids`, `subgraph`, `register-view!`, `view!`, `views`).
- **SR-DELTA-003.CC10:** Runtime library workspace helpers retain their operation names under `skein.libs.alpha`: `approved`, `sync!`, `syncs`, `use!`, `uses`, and `use`.
- **SR-DELTA-003.CC11:** Examples and generated startup config use `skein.weaver.api` for daemon-side trusted API calls.

## SR-DELTA-003.P3 Design decisions

### SR-DELTA-003.D1 Strand vocabulary at user-facing helper boundaries

- **Decision:** Rename task-specific helpers instead of keeping old helper names over new row shapes.
- **Rationale:** REPL helpers are a primary semantic surface for trusted users and agents. Mixed vocabulary would make examples and library code harder to read.
- **Rejected:** Keeping `task!` / `tasks` as aliases because the project is alpha and this feature intentionally drops old names.

### SR-DELTA-003.D2 Keep generic runtime helper names

- **Decision:** Keep helper names such as `ready`, `query`, `defquery!`, and `use!` when they do not encode task/todo/atom identity.
- **Rationale:** These names describe generic operations and remain accurate under Skein.
- **Rejected:** Renaming every helper for novelty or textile metaphor consistency.

## SR-DELTA-003.P4 Open questions

- **SR-DELTA-003.Q1:** None for MVP.
