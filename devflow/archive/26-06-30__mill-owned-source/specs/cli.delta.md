# CLI Surface delta for mill-owned source

**Document ID:** `DELTA-MOS-CLI-001` **Root spec:** [cli.md](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-30

## DELTA-MOS-CLI-001.P1 Summary

This delta changes the public CLI world-selection contract from current-worktree-local `.skein` discovery plus persisted `config.json` source to repository-canonical default world selection plus mill-owned source resolution. The default `strand` path should keep one weaver/strand store for a repository across all linked Git worktrees. Explicit `--config-dir` remains the opt-in isolation path.

## DELTA-MOS-CLI-001.P2 Contract changes

- **DELTA-MOS-CLI-001.CC1:** Without `--config-dir`, public `strand` commands select the Git repository's canonical `.skein` config workspace, not the current linked worktree's root `.skein` directory. Linked worktrees for one repository must therefore route to the same default selected-world identity.
- **DELTA-MOS-CLI-001.CC2:** `--config-dir` remains the highest-precedence world selector and intentionally creates/selects an independent world. Worktree-local Skein development and isolated tests use explicit `--config-dir` rather than relying on default discovery.
- **DELTA-MOS-CLI-001.CC3:** `config.json` remains the low-privilege selected config marker and must declare `"configFormat":"alpha"`; it no longer persists or authoritatively supplies the Skein source checkout path.
- **DELTA-MOS-CLI-001.CC4:** `strand init`, without `--config-dir`, creates/completes config workspace files at the repository-canonical default location. It does not require, write, or repair a persisted `source` field in `config.json`.
- **DELTA-MOS-CLI-001.CC5:** `strand weaver start` asks mill to resolve source for the selected world and launch the weaver. Source resolution failures are non-zero, fail-loud errors with remediation to set `SKEIN_SOURCE`, reinstall with a valid embedded source, or run from a canonical Skein checkout when applicable.
- **DELTA-MOS-CLI-001.CC6:** `strand weaver repl` asks mill for selected-world status and source resolution needed to launch the connected helper process. The `strand` CLI does not read source from `config.json`.
- **DELTA-MOS-CLI-001.CC7:** Public strand/weaver JSON status continues to report selected config/data/state paths and weaver metadata. It must not require callers to know or compare the source checkout path as selected-world identity.

## DELTA-MOS-CLI-001.P3 Design decisions

### DELTA-MOS-CLI-001.D1 Repository-canonical default world

- **Decision:** Default world selection is repository-scoped across linked worktrees.
- **Rationale:** Skein's value is a single repository task graph that agents can work from many worktrees and merge back into. Current-worktree-local default discovery fragments that graph.
- **Rejected:** Default per-worktree `.skein` discovery, because it makes accidental isolated weavers the normal path.

### DELTA-MOS-CLI-001.D2 Source is launch state, not config workspace state

- **Decision:** Mill owns source checkout resolution for process launch; `config.json` does not persist source.
- **Rationale:** Source is machine-local launch knowledge. Persisting it in repo/user config creates stale pointers and cross-worktree confusion.
- **Rejected:** Keeping `source` in `config.json` as the default authority.

## DELTA-MOS-CLI-001.P4 Open questions

- **DELTA-MOS-CLI-001.Q1:** The implementation plan defines the exact Git canonical route and fallback errors for unusual Git layouts; no durable CLI contract is blocked beyond requiring linked worktrees for one repository to converge on one default world.
