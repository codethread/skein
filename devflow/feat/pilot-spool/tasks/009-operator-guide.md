# Task 9: Operator guide

**Document ID:** `TASK-Pilot-009`

## TASK-Pilot-009.P1 Scope

Type: AFK

Write the pilot operator section in `docs/skein.md`: how the code owner launches a
pilot run, the four human-only points, how auto-land is granted, and how to read
escalations and the train queue. This is human-facing prose — apply the docs-style
skill (`.claude/skills/docs-style/SKILL.md`) and the column-180 limit.

Owned files: `docs/skein.md` (new pilot section). Worker discipline: record `progress`,
`status=implemented` only on a green gate, one atomic commit of owned files (no push),
never close your strand or touch siblings, kill only by PID.

## TASK-Pilot-009.P2 Must implement exactly

- **TASK-Pilot-009.MI1 (launch):** document how to launch a pilot run — the pilot family
  and its run-id (from task 1), and where the run appears in the coordination world.
- **TASK-Pilot-009.MI2 (human-only points):** name the four load-bearing human-only
  points as load-bearing — brief capture, RFC/scope acceptance or abort, `break-lock`,
  and the endpoint of every bounded escalation. State plainly that seats never close a
  `workflow/hitl` checkpoint and never abort a feature.
- **TASK-Pilot-009.MI3 (auto-land):** document that auto-land is an explicit per-run
  grant recorded at scope acceptance (`pilot/auto-land`), default off in v1, and what
  turning it on means for the train.
- **TASK-Pilot-009.MI4 (reading the run):** point to the pilot named queries and ops
  from task 3 — the attention frontier, the train queue, and pilot failures — and to
  `strand pilot about` / `strand pilot prime` for the live surface. Prefer pointing at
  the self-describing ops over restating them.
- **TASK-Pilot-009.MI5 (style):** run the docs-style sweep. No line over column 180;
  match the wrapping of the surrounding `docs/skein.md` prose.

## TASK-Pilot-009.P3 Done when

- **TASK-Pilot-009.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make docs-check`
  passes (and `make fmt-check lint` stays at zero if any code fence is linted).
- **TASK-Pilot-009.DW2:** No line in `docs/skein.md` exceeds column 180; the docs-style
  word/structure/formatting tells are swept (see the skill's tell catalogue).
- **TASK-Pilot-009.DW3:** The section names all four human-only points and states that
  seats never close a `workflow/hitl` checkpoint.
- **TASK-Pilot-009.DW4:** One atomic commit of `docs/skein.md` (no push).

## TASK-Pilot-009.P4 Out of scope

- **TASK-Pilot-009.OS1:** Promotion to a reference spool and its cookbook (deferred to
  Q6, PROP-Pilot-001.P6); the acceptance drills (tasks 10–11). This slice is the
  operator guide only.

## TASK-Pilot-009.P5 References

- **TASK-Pilot-009.REF1:** [PROP-Pilot-001](../proposal.md) P1 (human-only points), S6,
  P6; [PLAN-Pilot-001](../pilot-spool.plan.md) PH9;
  [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md) REC3, REC7.
- **TASK-Pilot-009.REF2:** `docs/skein.md` (surrounding prose and wrapping),
  `.claude/skills/docs-style/SKILL.md` (the sweep).
