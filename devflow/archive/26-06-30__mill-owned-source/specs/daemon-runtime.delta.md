# Weaver Runtime delta for mill-owned source

**Document ID:** `DELTA-MOS-RUNTIME-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-30

## DELTA-MOS-RUNTIME-001.P1 Summary

This delta updates the runtime contract so mill owns launch source resolution while the weaver remains repository-scoped by default through canonical selected config identity. The weaver still loads trusted startup files from the selected config-dir and still receives explicit config/state/data dirs from mill.

## DELTA-MOS-RUNTIME-001.P2 Contract changes

- **DELTA-MOS-RUNTIME-001.CC1:** The default selected config-dir for a Git repository is canonical across linked worktrees. A repository's default weaver is therefore shared by all linked worktrees unless the caller explicitly selects another config-dir.
- **DELTA-MOS-RUNTIME-001.CC2:** Mill derives per-world runtime directories from the canonical selected config identity. Linked worktrees for the same repository default must hash to the same runtime/data/metadata identity.
- **DELTA-MOS-RUNTIME-001.CC3:** The selected config-dir remains the trusted config workspace root. The weaver loads `init.clj` and `init.local.clj` from that selected config-dir, which is repository-canonical by default rather than current-worktree-local.
- **DELTA-MOS-RUNTIME-001.CC4:** Mill owns the Skein source checkout path used as the Clojure process working directory for weaver startup and connected helper REPL startup. The weaver receives explicit `--config-dir`, `--state-dir`, and `--data-dir` values and does not read source from `config.json`.
- **DELTA-MOS-RUNTIME-001.CC5:** Runtime metadata and selected-world identity do not include source as a world identity field. Source resolution is launch context, not storage or weaver identity.
- **DELTA-MOS-RUNTIME-001.CC6:** Explicit `--config-dir` worlds remain independent weaver worlds and are the intended isolation mechanism for Skein contributors testing worktree-local config changes before merging.

## DELTA-MOS-RUNTIME-001.P3 Design decisions

### DELTA-MOS-RUNTIME-001.D1 One default weaver per repository

- **Decision:** Repository-canonical selected config identity, not worktree path, defines the default weaver world.
- **Rationale:** The strand graph is repository coordination state and should survive fan-out across implementation worktrees.
- **Rejected:** Treating every linked worktree as a separate default runtime world.

### DELTA-MOS-RUNTIME-001.D2 Keep startup config selected-world local

- **Decision:** The weaver still loads startup files from selected config-dir; this feature only changes how the default selected config-dir is found and how source is resolved for launch.
- **Rationale:** Trusted config remains the correct place for user/repo behavior, while source checkout paths are mill launch mechanics.
- **Rejected:** Loading `init.clj` from the caller's current worktree while routing to a repository-scoped weaver, because that would make runtime behavior depend on the latest client cwd rather than selected-world identity.

## DELTA-MOS-RUNTIME-001.P4 Open questions

- **DELTA-MOS-RUNTIME-001.Q1:** None for the runtime contract. Implementation details for Git canonical route discovery are owned by the feature plan/tasks.
