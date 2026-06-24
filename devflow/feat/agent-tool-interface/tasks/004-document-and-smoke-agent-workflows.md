# Task 4: Document and smoke agent workflows

**Document ID:** `TASK-004`
**Configuration identification:** `TASK-004` is the fourth task in `agent-tool-interface`. Every nested point ID is prefixed with `TASK-004`.

## TASK-004.P1 Scope

Type: AFK

## TASK-004.P2 Must implement exactly

- **TASK-004.MI1:** Update `README.md` with an agent quickstart that prioritizes CLI commands and REPL helpers over the TUI.
- **TASK-004.MI2:** Document the supported command vocabulary, the supported REPL helper vocabulary, and the conventional task attributes/edge types established for the MVP.
- **TASK-004.MI3:** Add or update smoke coverage so a single command demonstrates loading tasks, linking dependencies, setting statuses, and querying both JSON1 attributes and graph relationships through the agent-facing interfaces.
- **TASK-004.MI4:** Keep the original TUI instructions present but secondary.
- **TASK-004.MI5:** Ensure temporary smoke databases remain ignored by git.

## TASK-004.P3 Done when

- **TASK-004.DW1:** A future coding agent can skim `README.md` and identify the minimal commands/functions needed to use the tool.
- **TASK-004.DW2:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes and demonstrates the updated agent workflow.
- **TASK-004.DW3:** Representative CLI commands from the README run successfully against a disposable database.
- **TASK-004.DW4:** `git status --short` shows no generated SQLite/cache artifacts as untracked files after validation.

## TASK-004.P4 Out of scope

- **TASK-004.OS1:** Do not add durable root specs unless the implementation reveals a stable contract that must be promoted during finish/archive.
- **TASK-004.OS2:** Do not document speculative future integrations as supported behavior.
- **TASK-004.OS3:** Do not implement additional product features beyond docs and smoke validation fixes needed for the agent workflow.

## TASK-004.P5 References

- **TASK-004.REF1:** Proposal: `devflow/feat/agent-tool-interface/proposal.md`.
- **TASK-004.REF2:** Plan: `devflow/feat/agent-tool-interface/agent-tool-interface.plan.md`, especially `PLAN-001.PH4`.
- **TASK-004.REF3:** README: `README.md`.
- **TASK-004.REF4:** Smoke demo: `dev/todo/smoke.clj`.
- **TASK-004.REF5:** CLI and REPL namespaces produced by Tasks 2 and 3.
