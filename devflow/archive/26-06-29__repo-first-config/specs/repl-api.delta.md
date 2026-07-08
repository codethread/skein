# REPL API delta for repo-first config

**Document ID:** `DELTA-ReplApi-001` **Root spec:** [repl-api.md](../../../specs/repl-api.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-29

## DELTA-ReplApi-001.P1 Summary

The REPL/helper surface keeps the same explicit helper namespaces, but selected-world helpers now operate against repo-first layered config. `skein.libs.alpha` reads effective approved libraries from `libs.edn` overlaid by `libs.local.edn`, and `libs/reload!` reloads both shared and local init files in the same order as weaver startup.

## DELTA-ReplApi-001.P2 Contract changes

- **DELTA-ReplApi-001.CC1:** `skein.libs.alpha/approved` returns the effective approved library config after applying `libs.local.edn` over `libs.edn`.
- **DELTA-ReplApi-001.CC2:** Effective approved entries may include source metadata indicating whether the winning entry came from `libs.edn` or `libs.local.edn`.
- **DELTA-ReplApi-001.CC3:** `skein.libs.alpha/sync!`, `syncs`, `use!`, `uses`, and `use` operate over the effective approved library config, so local overrides affect sync and module activation without additional calls.
- **DELTA-ReplApi-001.CC4:** `skein.libs.alpha/reload!` reloads selected config-dir startup files in startup order: `init.clj`, then `init.local.clj`. Missing files are skipped; present failing files throw. Reload keeps event dispatch semantics consistent with the Weaver Runtime delta by clearing event state before layered reload and only exposing the fully reloaded configuration afterward.
- **DELTA-ReplApi-001.CC5:** `strand weaver repl` and `strand weaver repl --stdin` pass the selected config-dir into `skein.repl` whether it came from explicit `--config-dir` or repo discovery. Direct `connect!` with no argument fails loudly without a selected-world context; explicit config-dir is the standalone way to choose a world.
- **DELTA-ReplApi-001.CC6:** Direct helper-REPL `require` remains local to the helper JVM; approved library sync and activation still happen in the weaver runtime.

## DELTA-ReplApi-001.P3 Design decisions

### DELTA-ReplApi-001.D1 One effective library config

- **Decision:** REPL users see and operate on one effective approved library config, not two separate shared/local APIs.
- **Rationale:** The layered files represent one selected config-dir world. Separate sync commands for shared and local libraries would expose avoidable mechanics and create reload drift.
- **Rejected:** Adding `sync-local!`, `approved-local`, or separate local module-use registries.

### DELTA-ReplApi-001.D2 Reload mirrors startup

- **Decision:** `libs/reload!` must reload both shared and local init files in startup order.
- **Rationale:** Cold start and hot reload should have the same config semantics.
- **Rejected:** Reloading only `init.clj` while startup loads both files.

## DELTA-ReplApi-001.P4 Open questions

- **DELTA-ReplApi-001.Q1:** None for MVP. Exact returned source metadata keys can be chosen during implementation and reflected before promotion.
