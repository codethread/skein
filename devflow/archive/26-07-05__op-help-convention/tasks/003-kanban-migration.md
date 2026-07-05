# Task 3: Migrate kanban op to declared subcommands

**Document ID:** `TASK-OpHelp-003`

## TASK-OpHelp-003.P1 Scope

Type: AFK

Migrate the kanban op from raw-envelope hand-rolled dispatch to a declared `:subcommands` arg-spec (plan A4/PH2). Diff `spools/kanban/**` at start — the surface recently gained `prime` and board changes; the live code is the source of truth, not this task's verb list.

## TASK-OpHelp-003.P2 Must implement exactly

- **TASK-OpHelp-003.MI1:** In `spools/kanban/src/skein/spools/kanban.clj`, build a `:subcommands` arg-spec covering every current verb (at time of writing: about, add, board, card, next, promote, claim, note, finish, prime) with flags/positionals translated from the existing `parse-op-argv` calls: `add` variadic required title + `--body/--source/--status/--type/--epic`; `board`/`next`/`about`/`prime` zero-arg; `card`/`promote` one required id; `claim` id + required-by-handler `--owner/--branch` + `--worktree`; `note` id + variadic text + `--author` + boolean `--handover`; `finish` id + `--outcome`. Register with the arg-spec (keep `:hook-class` and doc; doc should mention `about`).
- **TASK-OpHelp-003.MI2:** `kanban-op` dispatches on `:subcommand` from `:op/args` and reads parsed args instead of `parse-op-argv`; delete the hand-rolled usage-error branch and any now-dead `parse-op-argv` plumbing for this op. Handler-level semantic validation (e.g. claim requiring `--owner`/`--branch`) stays handler-owned and loud.
- **TASK-OpHelp-003.MI3:** Update `test/skein/kanban_test.clj`: hand-rolled usage-error assertions become parser-phase structured-error assertions (available names present); add assertions that `kanban help` (via the task-1 alias, through `op!`/dispatch) returns the detail projection and that `strand help kanban`-equivalent detail lists the verbs.
- **TASK-OpHelp-003.MI4:** Update `spools/kanban.md` where it documents the command surface or its errors.

## TASK-OpHelp-003.P3 Done when

- **TASK-OpHelp-003.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green.
- **TASK-OpHelp-003.DW2:** No behavior change to any valid kanban invocation (same JSON shapes).

## TASK-OpHelp-003.P4 Out of scope

- **TASK-OpHelp-003.OS1:** agents/treadle/chime op migrations; canonical weaver reload; root spec merges.

## TASK-OpHelp-003.P5 References

- **TASK-OpHelp-003.REF1:** plan A4/R1/R3, specs/repl-api.delta.md (reserved names), shipped SPEC-003.C64/C65.
