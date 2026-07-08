# CLI Surface delta for repo-first config

**Document ID:** `DELTA-Cli-001` **Root spec:** [cli.md](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-29

## DELTA-Cli-001.P1 Summary

The CLI changes from default global-world selection to repo-first world selection. `--config-dir` remains the explicit override. Without `--config-dir`, ordinary commands search upward from cwd for the nearest `.skein` directory and use it as the selected config-dir. If no `.skein` is found, non-init commands fail loudly instead of falling back to `$XDG_CONFIG_HOME/skein`.

## DELTA-Cli-001.P2 Contract changes

- **DELTA-Cli-001.CC1:** `--config-dir <dir>` remains the highest-precedence world selector for all commands and retains explicit-world behavior for tests and automation.
- **DELTA-Cli-001.CC2:** When `--config-dir` is absent, the CLI searches from cwd upward to filesystem root for the nearest directory named `.skein` containing or eligible to contain a Skein config world.
- **DELTA-Cli-001.CC3:** If repo discovery finds `.skein`, that directory is the selected config-dir; runtime state and data for that world live under `.skein/state` and `.skein/data`.
- **DELTA-Cli-001.CC4:** If no `.skein` is found, all commands except `strand init` fail non-zero with remediation to run `strand init` or pass `--config-dir`.
- **DELTA-Cli-001.CC5:** `strand init` without `--config-dir` creates or completes `.skein` at the nearest Git repository root. Outside a Git repository, it creates or completes `.skein` in cwd.
- **DELTA-Cli-001.CC6:** `strand init --config-dir <dir>` keeps explicit bootstrap behavior for the selected directory and does not perform repo-root discovery.
- **DELTA-Cli-001.CC7:** Repo bootstrap creates missing shared config files `init.clj`, `libs.edn`, and `.gitignore`, creates local `config.json` for the selected machine's Skein source checkout when source resolution succeeds, and creates supporting directories as needed. It never overwrites existing files.
- **DELTA-Cli-001.CC7a:** `strand init` source resolution uses this precedence: an explicit `--source <path>` init flag, `SKEIN_SOURCE`, cwd when cwd is the Skein source checkout, then failure with remediation to pass `--source` or set `SKEIN_SOURCE`. The resolved source must satisfy the existing source validation contract.
- **DELTA-Cli-001.CC7b:** A discovered `.skein` without local `config.json` is an incomplete local world for commands that require source or a running weaver. Errors should name the selected config-dir and tell the user to run `strand init --source <skein-source>` or set `SKEIN_SOURCE`.
- **DELTA-Cli-001.CC8:** Repo bootstrap `.skein/.gitignore` ignores `config.json`, `init.local.clj`, `libs.local.edn`, `state/`, `data/`, `weaver.*`, and SQLite/runtime artifacts by default.
- **DELTA-Cli-001.CC9:** `.skein/config.json` remains the client config file for source checkout resolution and must declare `"configFormat":"alpha"`; it is treated as local machine config and is ignored by repo bootstrap defaults.
- **DELTA-Cli-001.CC10:** Public CLI requests still send only the selected world/socket operation data to the weaver. Repo path, Git root, and cwd are not added to strand operation JSON payloads.
- **DELTA-Cli-001.CC11:** `strand weaver status` continues to report the selected config-dir, state dir, data dir, database path, socket, pid, and identity so callers can see which repo world was selected.
- **DELTA-Cli-001.CC12:** `strand weaver start` and `strand weaver repl` pass the resolved selected config-dir to the launched Clojure process even when selection came from repo discovery rather than an explicit `--config-dir` flag.

## DELTA-Cli-001.P3 Design decisions

### DELTA-Cli-001.D1 Repo-first replaces global fallback

- **Decision:** No-flag CLI world selection becomes nearest-parent `.skein` discovery, and absence of `.skein` fails loudly.
- **Rationale:** Repo-local config is easier for agents to reason about than a personal global config that must infer repo scope. Failing outside a repo avoids accidental mutation of an unrelated global world.
- **Rejected:** Keeping `$XDG_CONFIG_HOME/skein` as an implicit fallback for ordinary commands.

### DELTA-Cli-001.D2 Explicit config-dir stays

- **Decision:** `--config-dir` remains supported and highest precedence.
- **Rationale:** Tests, smoke workflows, disposable worlds, and advanced automation still need exact control over the selected world.
- **Rejected:** Removing explicit worlds in favor of mandatory repo discovery.

### DELTA-Cli-001.D3 Git-root init

- **Decision:** `strand init` creates `.skein` at the nearest Git root when possible.
- **Rationale:** The product model is repo-first, so initialization should attach Skein config to the repository rather than an arbitrary subdirectory.
- **Rejected:** Always creating `.skein` in cwd when inside a Git worktree.

## DELTA-Cli-001.P4 Open questions

- **DELTA-Cli-001.Q1:** None for MVP. Existing specs and tests must be updated to remove implicit default-world fallback from ordinary command behavior.
