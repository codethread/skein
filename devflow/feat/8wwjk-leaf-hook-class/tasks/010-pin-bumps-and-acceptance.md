# Task 10: Pin bumps + full acceptance gates

**Document ID:** `TASK-Lhc-010`

## TASK-Lhc-010.P1 Scope

Type: AFK

Bump the three sibling spool pins to the Tasks 7–9 release tags and run the
whole acceptance gate set. This is the queue-acceptance slice: it owns the full
locked test suite.

## TASK-Lhc-010.P2 Must implement exactly

- **TASK-Lhc-010.MI1:** `.skein/spools.edn` pins: codethread/kanban → v7,
  ct.spools/agent-run → v12, codethread/devflow → v4 (tag + peeled SHA from the
  Task 7–9 worklogs), written through the repo's validated spool write path
  where usable from a checkout, plus any config/test mirrors of these pins
  (grep for the old tags/SHAs; update every hit or justify it in the worklog).
- **TASK-Lhc-010.MI2:** Acceptance gates, all green: full locked suite
  (`flock -w 3600 /tmp/skein-test.lock clojure -M:test`),
  `(cd cli && go test ./...)`, `clojure -M:smoke`, `make spool-suite-gate`,
  `make fmt-check lint reflect-check docs-check`, clean `git status --short`.

## TASK-Lhc-010.P3 Done when

- **TASK-Lhc-010.DW1:** Every MI2 gate green in one worklog note with command
  output summaries.

## TASK-Lhc-010.P4 Out of scope

- **TASK-Lhc-010.OS1:** Landing (coordinator-only `strand land` workflow);
  canonical-weaver refresh/restart (user-sanctioned, post-land).
