# Task 16: Remove old lifecycle and integrate local peer roots

**Document ID:** `TASK-Olr-016`

## TASK-Olr-016.P1 Scope

Type: AFK

Assemble the Skein and three peer feature worktrees through local approved roots, remove every temporary adapter and old blessed lifecycle function, and make the whole source/config test surface use only module/refresh/status/reload-code.

## TASK-Olr-016.P2 Must implement exactly

- **TASK-Olr-016.MI1:** Remove `sync!`, `syncs`, `reload!`, `reload-spool!`, `use!`, `uses`, and `use-entry` from `skein.api.runtime.alpha`, their specs/generated entries, and all in-tree callers. `reload-code!` is the sole renamed advanced code-only successor.
- **TASK-Olr-016.MI2:** Remove core clear-and-replay paths, module-use state, branch-only adapters, install-only activation paths, and dead shadow-registry plumbing. Keep only internals required by refresh acquisition/loading.
- **TASK-Olr-016.MI3:** Configure a disposable repo world with local roots for agent-harness, kanban, and devflow feature worktrees and the new module declarations. Do not edit canonical local overlays.
- **TASK-Olr-016.MI4:** Update every test fixture, script, pickup-ladder instruction source, bootstrap assertion, spool-status/status consumer, and quality inventory reference that names the old lifecycle.
- **TASK-Olr-016.MI5:** Ensure full module removal, owner restoration, residual reporting, and resource reconciliation work across core plus all local peers with no permanent compatibility path.

## TASK-Olr-016.P3 Done when

- **TASK-Olr-016.DW1:** `rg 'runtime/(sync!|syncs|reload!|reload-spool!|use!|uses|use-entry)' src spools test dev scripts .skein cli README.md AGENTS.md docs devflow --glob '!devflow/archive/**' --glob '!devflow/feat/**'` returns no production/current-guidance references; feature and archive history are the only exclusions.
- **TASK-Olr-016.DW2:** The integrated disposable repo world starts, supports board/devflow/agent/help operations, performs full and targeted refresh, and retains active-resource status using all local feature roots.
- **TASK-Olr-016.DW3:** `clojure -M:test skein.spools-test skein.weaver-test skein.config-test`, each peer repository's full test alias against local roots, `(cd cli && go test ./...)`, `clojure -M:smoke`, and `make fmt-check lint reflect-check` pass.

## TASK-Olr-016.P4 Out of scope

- **TASK-Olr-016.OS1:** Do not tag peers, update git pins, merge branches, or restart the canonical weaver.

## TASK-Olr-016.P5 References

- **TASK-Olr-016.REF1:** `DELTA-OlrRepl-001.CC1–CC11`, `DELTA-OlrAlpha-001.CC1–CC3`, `PLAN-Olr-001.PH3–PH4`.
