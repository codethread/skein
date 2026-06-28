# Task 6: Add smoke coverage and verify CLI unchanged

**Document ID:** `BGU-TASK-006`

## BGU-TASK-006.P1 Scope

Type: AFK

Extend smoke/integration validation to exercise the trusted batch helper through a disposable weaver world and verify the public CLI remains unchanged.

## BGU-TASK-006.P2 Must implement exactly

- **BGU-TASK-006.MI1:** Update `dev/skein/smoke.clj` to exercise `skein.batch.alpha/apply!` through `strand weaver repl --stdin` or the existing smoke helper mechanism against a disposable `--config-dir` world.
- **BGU-TASK-006.MI2:** Smoke payload should bind at least one existing ref, create one new ref, update one strand, upsert one edge, and burn one existing strand.
- **BGU-TASK-006.MI3:** Smoke assertions should verify final refs, created/updated/burned result shape, and observable final graph state through existing CLI/REPL reads.
- **BGU-TASK-006.MI4:** Do not add `strand batch`, JSON socket batch operation, or any new public CLI command.
- **BGU-TASK-006.MI5:** Run CLI tests to confirm unchanged public CLI behavior.
- **BGU-TASK-006.MI6:** Ensure smoke cleanup leaves no generated SQLite/runtime metadata artifacts in git status.

## BGU-TASK-006.P3 Done when

- **BGU-TASK-006.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes.
- **BGU-TASK-006.DW2:** `(cd cli && go test ./...)` passes.
- **BGU-TASK-006.DW3:** `git status --short` shows only intentional source/devflow changes, not generated config/data/state artifacts.

## BGU-TASK-006.P4 Out of scope

- **BGU-TASK-006.OS1:** Adding public CLI batch command or JSON socket allowlist entries.
- **BGU-TASK-006.OS2:** Additional feature work beyond proving the trusted Clojure batch path.

## BGU-TASK-006.P5 References

- **BGU-TASK-006.REF1:** `devflow/feat/batch-graph-upsert/specs/cli.delta.md`
- **BGU-TASK-006.REF2:** `dev/skein/smoke.clj`
- **BGU-TASK-006.REF3:** `cli/`
- **BGU-TASK-006.REF4:** `AGENTS.md` validation guidance
