# Task 10: Smoke suite rewrite and docs cutover

**Document ID:** `TASK-Ooc-010`

## TASK-Ooc-010.P1 Scope

Type: AFK

Rewrite the smoke suite to the new surface and finish the documentation cutover (CLAUDE.md, strand skill, docs/).

## TASK-Ooc-010.P2 Must implement exactly

- **TASK-Ooc-010.MI1:** Rewrite the `clojure -M:smoke` demo to the new surface end-to-end: build temporary binaries; `mill init` a disposable workspace; `mill start`; `mill weaver start`; batteries ops through the dispatcher (`add`/`update`/`list`/`ready`/`show`/`weave` with `--stdin` payload); payload-ref forms (`--attr body=:stdin`, `--payload`); `--dry-run`; `strand help` + `strand help add`; unknown-op failure; the stream op through the full chain (load the pinned fixture `test/fixtures/stream-op-init.clj` from the smoke workspace's `init.clj`, invoke `strand test-stream`); `mill weaver stop`; full artifact cleanup assertions (per existing smoke conventions, including `git status --short` cleanliness).
- **TASK-Ooc-010.MI2:** Update `CLAUDE.md`: Project commands, Agent operation quick reference, coordination sections — every `strand op X` → `strand X`, `strand init` → `mill init`, `strand weaver *` → `mill weaver *`, attribute-input examples to payload forms, and the spool index gains batteries. Keep edits surgical; do not restructure the document.
- **TASK-Ooc-010.MI3:** Update `.agents/skills/strand/SKILL.md` the same way (note: it also mentions `--config-dir` in stale examples — align those with current `--workspace` reality while there).
- **TASK-Ooc-010.MI4:** Update `docs/skein.md`, `docs/writing-shared-spools.md`, `docs/library-authoring.md`, and `spools/*.md` prose for the new invocation surface (sweep with the task-9 grep patterns over `docs/` and `spools/*.md`).
- **TASK-Ooc-010.MI5:** Root spec merge is **not** this task (finish stage owns delta promotion); do not edit `devflow/specs/*`.

## TASK-Ooc-010.P3 Done when

- **TASK-Ooc-010.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` green; `clojure -M:test` and `(cd cli && go test ./...)` still green.
- **TASK-Ooc-010.DW2:** Task-9 grep sweep over `CLAUDE.md`, `.agents/`, `docs/`, `spools/*.md` returns zero live old-surface hits.
- **TASK-Ooc-010.DW3:** `git status --short` clean of runtime artifacts after smoke.

## TASK-Ooc-010.P4 Out of scope

- **TASK-Ooc-010.OS1:** Root spec promotion, archive, external devflow.spool (task 11), BACKLOG.md state.

## TASK-Ooc-010.P5 References

- **TASK-Ooc-010.REF1:** plan A6/PH5/V2; cli delta SPEC-002-D004; `spools/batteries.md` (task 5) for correct new-form examples; existing smoke entry under the `:smoke` alias in `deps.edn`.
