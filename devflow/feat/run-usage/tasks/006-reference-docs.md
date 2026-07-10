# Task 6: reference docs — usage attrs, per-format capture, spend verb

**Document ID:** `TASK-Ru-006`
**Slice:** `PLAN-Ru-001.S6`  **Harness:** worker  **Type:** AFK
**Depends on:** TASK-Ru-005

## TASK-Ru-006.P1 Scope

Type: AFK

Document the new `agent-run/*` usage attributes, the per-format capture behavior, and the
`strand agent spend` verb in the userland reference docs (`PROP-Ru-001.C8`). Doc-only, and it describes
landed code — depends on Tasks 1–5 for accuracy (`PLAN-Ru-001.S6`). `make api-docs` regen is **deferred to
Task 7**; describe the code as it is.

**Owned files (disjoint):**
- `spools/agent-run.cookbook.md`
- `spools/delegation/README.md`
- The agent-run/delegation docstrings the api-docs are generated from: `install!`, `parse-pi-json`,
  `parse-claude-json` in `spools/agent-run/src/skein/spools/agent_run.clj`, and the `agent` op `spend`
  subcommand in `spools/delegation/src/skein/spools/delegation.clj`.

## TASK-Ru-006.P2 Must implement exactly

Per `PROP-Ru-001.C8`:

- **TASK-Ru-006.MI1:** Document the four `agent-run/*` usage attributes and the per-format capture
  (pi-json delta fold, claude-json result object, `:raw` records none) in the agent-run docstrings
  (`install!`, `parse-pi-json`, `parse-claude-json`).
- **TASK-Ru-006.MI2:** Add a short "reading run spend" entry to `spools/agent-run.cookbook.md`.
- **TASK-Ru-006.MI3:** Document the `strand agent spend` subcommand — its four flags and JSON output shape
  — in `spools/delegation/README.md` and the `strand agent about` manual docstring.
- **TASK-Ru-006.MI4:** Prose passes the docs-style gate: plain voice, no LLM tells, no prose line past
  column 180.

## TASK-Ru-006.P3 Done when

- **TASK-Ru-006.DW1:** The usage attrs, per-format capture, and the spend verb are documented in the
  userland reference docs (`PROP-Ru-001.C8`).
- **TASK-Ru-006.DW2:** `make docs-check` at zero findings. `make api-docs` regen is deferred to Task 7 —
  do not run it or commit `*.api.md` here.

## TASK-Ru-006.P4 Out of scope

- **TASK-Ru-006.OS1:** Any code behavior change — this task edits docstrings and markdown only.
- **TASK-Ru-006.OS2:** `make api-docs` / `spools/*.api.md` regen (Task 7 owns the atomic regen).
- **TASK-Ru-006.OS3:** Root-spec edits — all four F-Ru deltas are no-change (`PLAN-Ru-001.CM2`), so there
  is no spec-application slice.

## TASK-Ru-006.P5 Commit

- Atomic single commit, devflow message (why-focused), **no push**.

## TASK-Ru-006.P6 References

- **TASK-Ru-006.REF1:** `PLAN-Ru-001.S6`, `AA8`; `PROP-Ru-001.C8`.
- **TASK-Ru-006.REF2:** The landed Tasks 1–5 — describe the usage attrs, per-format capture, and spend
  subcommand as they are.
