# Repo-first Config Proposal

**Document ID:** `PROP-RepoFirstConfig-001` **Last Updated:** 2026-06-29 **Related RFCs:** None **Related root specs:** [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md)

## PROP-RepoFirstConfig-001.P1 Problem

Skein currently treats the selected config-dir like an Emacs/Vim-style personal home that can be overridden explicitly with `--config-dir`. That model is awkward for coding agents and repo-scoped work: a global personal world must somehow know which local repo definitions, queries, patterns, libraries, hooks, and operations apply to the current worktree. The more predictable model is repo-first: each repository owns a `.skein` world, and that world may pull in personal global workflow libraries through local overlay files.

The existing explicit config-dir model also makes ordinary CLI use noisy. Agents and humans should be able to run `strand ready` from anywhere inside a repository and have Skein select the nearest repo `.skein` world consistently. Personal control still matters: users need gitignored overlays for machine-specific source paths, personal libraries, private queries, and local automation without forcing colleagues to adopt the same setup.

## PROP-RepoFirstConfig-001.P2 Goals

- **PROP-RepoFirstConfig-001.G1:** Make repo-local `.skein` the default Skein world selection model for CLI commands.
- **PROP-RepoFirstConfig-001.G2:** Preserve `--config-dir` as an explicit override for tests, automation, and unusual worlds.
- **PROP-RepoFirstConfig-001.G3:** Support one layered config under `.skein`: shared `init.clj`/`libs.edn` plus personal `init.local.clj`/`libs.local.edn`.
- **PROP-RepoFirstConfig-001.G4:** Allow personal `libs.local.edn` entries to override shared `libs.edn` entries by library coordinate.
- **PROP-RepoFirstConfig-001.G5:** Keep library acquisition path-only for the MVP; Skein remains a runtime local-root loader, not a package manager.
- **PROP-RepoFirstConfig-001.G6:** Fail loudly outside a repo/config world instead of silently falling back to a global default.
- **PROP-RepoFirstConfig-001.G7:** Bootstrap repo worlds at the Git root when possible and install sensible ignore defaults for local/runtime artifacts.

## PROP-RepoFirstConfig-001.P3 Non-goals

- **PROP-RepoFirstConfig-001.NG1:** No package manager, source installer, dependency solver, lockfile, Maven version resolution, or remote fetching.
- **PROP-RepoFirstConfig-001.NG2:** No sandboxing or untrusted execution model for shared or local init code.
- **PROP-RepoFirstConfig-001.NG3:** No attempt to preserve implicit global default-world behavior for ordinary no-flag CLI commands.
- **PROP-RepoFirstConfig-001.NG4:** No per-command repo metadata passed through the JSON socket; the selected config-dir remains the world identity.
- **PROP-RepoFirstConfig-001.NG5:** No conflict prevention between shared and local library coordinates beyond deterministic local override semantics.

## PROP-RepoFirstConfig-001.P4 Proposed scope

- **PROP-RepoFirstConfig-001.S1:** Change default CLI world resolution so `--config-dir` wins; otherwise the CLI searches upward from cwd for the nearest `.skein` directory; if none exists, non-init commands fail loudly.
- **PROP-RepoFirstConfig-001.S2:** Change `strand init` without `--config-dir` to create or complete `.skein` at the nearest Git root; outside Git, create or complete `.skein` in cwd.
- **PROP-RepoFirstConfig-001.S3:** Treat `.skein/config.json` as local machine config and gitignore it by default because it contains the Skein source checkout path; `strand init` resolves that source from an explicit `--source`, `SKEIN_SOURCE`, or cwd when cwd is the Skein checkout, and otherwise fails with clear remediation.
- **PROP-RepoFirstConfig-001.S4:** Bootstrap shared files suitable for commit (`init.clj`, `libs.edn`, `.gitignore`) and local/runtime ignore rules for `config.json`, `init.local.clj`, `libs.local.edn`, `state/`, `data/`, and weaver artifacts.
- **PROP-RepoFirstConfig-001.S5:** Extend weaver startup and config reload to load `init.clj` then `init.local.clj` from the selected config-dir when present, with missing files accepted and present failing files aborting loudly.
- **PROP-RepoFirstConfig-001.S6:** Extend approved library config so effective approved libs are `libs.edn` overlaid by `libs.local.edn`, with local entries replacing shared entries by coordinate.
- **PROP-RepoFirstConfig-001.S7:** Update specs and tests for repo discovery, init bootstrap, layered config loading, library override behavior, status visibility, and retained explicit config-dir test workflows.

## PROP-RepoFirstConfig-001.P5 Open questions

- **PROP-RepoFirstConfig-001.Q1:** None blocking planning. The accepted direction is repo-first default discovery, no global fallback, Git-root init, local-overrides-shared library merging, and path-only library roots for the MVP.
