# Task 14: Convert kanban and peering registrations

**Document ID:** `TASK-Olr-014`

## TASK-Olr-014.P1 Scope

Type: AFK

In a linked feature worktree for `/Users/ct/dev/projects/kanban.spool`, convert board and peering declarations to stable owner contributions against the Skein feature worktree. Preserve kanban business behavior, tracker state, and peering wire contracts.

## TASK-Olr-014.P2 Must implement exactly

- **TASK-Olr-014.MI1:** Contribute the `kanban`/`kanban-export` ops, `kanban-batch` pattern, board queries, vocab, and peering ops as complete owner sets.
- **TASK-Olr-014.MI2:** Remove manual register-or-replace probing used only for reload safety. Owner replacement handles add/change/delete/override deterministically.
- **TASK-Olr-014.MI3:** Keep tracker binding in versioned spool-state. Tracker symbols resolve per projection call; replacing tracker declaration affects later projections without changing stored cards or active devflow runs.
- **TASK-Olr-014.MI4:** Preserve guild peering preconditions, `kanban.send.v1` wire shape, dynamic test seams, note behavior, return declarations, CLI vocabulary, attributes, and board semantics.
- **TASK-Olr-014.MI5:** Add complete-owner deletion and reload tests for board and peering declarations; this peer worktree owns its contract docs, README, docstrings, and generated API files for release preparation.
- **TASK-Olr-014.MI6:** Record and test against the same dedicated immutable post-Task-7 Skein baseline commit used by other peer conversions; never follow a moving Skein worktree during the task.

## TASK-Olr-014.P3 Done when

- **TASK-Olr-014.DW1:** Board and peering install/reload safety tests use owner contribution; deleting one op/query/pattern no longer leaves it live.
- **TASK-Olr-014.DW2:** Tracker replacement, guild ordering, peering send/receive, exact op returns, state-shape, epic/task/note/board tests all pass against the Skein feature worktree.
- **TASK-Olr-014.DW3:** `clojure -M:test`, TypeScript export checks where applicable, repository format/lint/API-doc gates, and state/return meta-tests pass against the recorded Skein baseline; no v5 tag is created.

## TASK-Olr-014.P4 Out of scope

- **TASK-Olr-014.OS1:** Do not change kanban vocabulary, attribute keys, peering wire version, business state machine, or publish v5.

## TASK-Olr-014.P5 References

- **TASK-Olr-014.REF1:** `src/ct/spools/kanban.clj`, `src/ct/spools/kanban/peering.clj`, `kanban.md`, and `PLAN-Olr-001.AA9`.
