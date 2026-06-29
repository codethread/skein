# Task 8: Promote specs and validate

**Document ID:** `WLH-TASK-008`

## WLH-TASK-008.P1 Scope

Type: AFK

Finish the feature after implementation by aligning documentation/specs, adding smoke coverage if needed, and running the standard validation suite.

## WLH-TASK-008.P2 Must implement exactly

- **WLH-TASK-008.MI1:** Merge shipped behavior from feature deltas into root specs: `devflow/specs/daemon-runtime.md`, `devflow/specs/repl-api.md`, and `devflow/specs/cli.md`.
- **WLH-TASK-008.MI2:** Mark feature-local deltas in `devflow/feat/weaver-lifecycle-hooks/specs/` as `Merged` only after their durable content is reflected in root specs.
- **WLH-TASK-008.MI3:** Update `devflow/README.md` active feature/archive notes only if feature status or spec index text needs adjustment for the shipped state.
- **WLH-TASK-008.MI4:** Add or update smoke/demo coverage where useful to prove a disposable `--config-dir` world can install a hook through trusted config/REPL workflow and observe a hook-approved or hook-rejected CLI mutation. Keep smoke worlds isolated.
- **WLH-TASK-008.MI5:** Run project validation: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.
- **WLH-TASK-008.MI6:** Ensure `git status --short` shows no generated SQLite/runtime metadata artifacts after validation.
- **WLH-TASK-008.MI7:** Add a final Developer Notes entry to `weaver-lifecycle-hooks.plan.md` summarizing shipped scope, validation results, and any cut/deferred scope.

## WLH-TASK-008.P3 Done when

- **WLH-TASK-008.DW1:** Root specs describe lifecycle hooks as the canonical current contract and no longer rely on feature-local deltas for shipped behavior.
- **WLH-TASK-008.DW2:** Feature deltas are marked `Merged`.
- **WLH-TASK-008.DW3:** Standard validation commands pass, or any failure is fixed before task completion.
- **WLH-TASK-008.DW4:** No generated weaver state/data/socket/metadata artifacts remain in the working tree.
- **WLH-TASK-008.DW5:** The feature plan Developer Notes record final validation and shipped/cut scope.

## WLH-TASK-008.P4 Out of scope

- **WLH-TASK-008.OS1:** Do not archive the feature folder unless explicitly running the devflow finish/archive procedure after shipped implementation review.
- **WLH-TASK-008.OS2:** Do not add new hook families or CLI commands during final docs promotion.
- **WLH-TASK-008.OS3:** Do not promote unimplemented behavior into root specs.

## WLH-TASK-008.P5 References

- **WLH-TASK-008.REF1:** [Plan](../weaver-lifecycle-hooks.plan.md) `WLH-PLAN-001.PH5` and `WLH-PLAN-001.V5`.
- **WLH-TASK-008.REF2:** Feature deltas in `devflow/feat/weaver-lifecycle-hooks/specs/`.
- **WLH-TASK-008.REF3:** Root specs in `devflow/specs/` and workspace index `devflow/README.md`.
- **WLH-TASK-008.REF4:** Project validation commands in `AGENTS.md`.
