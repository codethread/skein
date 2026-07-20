# Task 5: Publish new runtime API and test surface

**Document ID:** `TASK-Olr-005`

## TASK-Olr-005.P1 Scope

Type: AFK

Add the target `skein.api.runtime.alpha` story for `module!`, `refresh!`, `status`, and advanced `reload-code!`; update result specs and `skein.test.alpha` disposable-world support. Keep current lifecycle vars temporarily only so unconverted consumers remain testable until Task 16.

## TASK-Olr-005.P2 Must implement exactly

- **TASK-Olr-005.MI1:** Define public functions and specs exactly as `DELTA-OlrRepl-001`; keep explicit runtime first and public-first story shape within the API form limits.
- **TASK-Olr-005.MI2:** `module!` stages under startup collection and otherwise updates/refreshes one desired module. It does not offer targeted module deletion.
- **TASK-Olr-005.MI3:** `refresh!` exposes full and `{:only non-empty-keys}` arities; `status` is offline/read-only; `reload-code!` is code-only and reports ledger/residual outcomes without contribution/resource work.
- **TASK-Olr-005.MI4:** Preserve approval editor, release-marker, spool-state, and clock APIs. Mark the old lifecycle vars as temporary branch scaffolding in code comments only; do not generate target docs that present both paths as permanent.
- **TASK-Olr-005.MI5:** Extend `skein.test.alpha/with-weaver-world` fixtures to author module declarations and inspect refresh/status without touching canonical worlds.
- **TASK-Olr-005.MI6:** Add API shape/spec tests and generated-doc source mapping for the new public vars.

## TASK-Olr-005.P3 Done when

- **TASK-Olr-005.DW1:** Alpha API tests cover every arity, exact result/spec shape, malformed declarations/options, offline status, and code-only reload.
- **TASK-Olr-005.DW2:** A minimal disposable workspace starts and refreshes one module using only the new surface.
- **TASK-Olr-005.DW3:** `quality.api-form`, focused runtime/API/test-helper suites, format, lint, reflection, and a local API-doc generation pass are green.

## TASK-Olr-005.P4 Out of scope

- **TASK-Olr-005.OS1:** Do not remove old vars yet, convert spools, or update human docs beyond docstrings/generated-source plumbing.

## TASK-Olr-005.P5 References

- **TASK-Olr-005.REF1:** `DELTA-OlrRepl-001`, `SPEC-003.C19a`, and `PLAN-Olr-001.PH2`.
