# CLI Surface delta for mill router runtime

**Document ID:** `DELTA-MillRouterRuntime-Cli-001` **Root spec:** [cli.md](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-30

## DELTA-MillRouterRuntime-Cli-001.P1 Summary

The public `strand` CLI changes from directly discovering and connecting to per-world weaver sockets to using a user-started local `mill` router. `strand init` becomes repo config bootstrap only and no longer initializes storage through a weaver. Runtime state and SQLite data move from repo `.skein/state` and `.skein/data` to XDG state directories selected by `mill`.

## DELTA-MillRouterRuntime-Cli-001.P2 Contract changes

- **DELTA-MillRouterRuntime-Cli-001.CC1:** `make install` installs both `strand` and `mill` Go commands.
- **DELTA-MillRouterRuntime-Cli-001.CC2:** Public `strand` commands require a reachable local `mill` entrypoint. If no mill is running or its metadata/socket is stale, `strand` fails non-zero with remediation to run `mill start`.
- **DELTA-MillRouterRuntime-Cli-001.CC3:** `mill start` creates the Skein XDG state root, publishes `mill.json` and `mill.sock`, and listens for local `strand` requests. The fallback state root is `~/.local/state/skein` when `XDG_STATE_HOME` is unset.
- **DELTA-MillRouterRuntime-Cli-001.CC4:** `strand` sends `mill` the caller cwd and optional `--config-dir` value as world-resolution inputs. These inputs are not forwarded inside public strand operation payloads to the weaver.
- **DELTA-MillRouterRuntime-Cli-001.CC5:** `--config-dir <dir>` remains accepted as the highest-precedence explicit world selector for code churn and test/disposable workflows, but under `mill` it is a world reference input rather than a direct state/socket path.
- **DELTA-MillRouterRuntime-Cli-001.CC6:** Without `--config-dir`, implicit repo selection requires cwd to be inside a Git worktree. The selected repo root is the Git toplevel; outside Git, `strand init` and ordinary no-flag commands fail loudly instead of creating cwd `.skein` worlds.
- **DELTA-MillRouterRuntime-Cli-001.CC7:** `strand init` asks `mill` to create or complete the selected repo's `.skein` config directory and files. It never contacts or requires a running weaver and never initializes SQLite storage.
- **DELTA-MillRouterRuntime-Cli-001.CC8:** `strand init` creates missing repo config files only: `.skein/init.clj`, `.skein/libs.edn`, `.skein/.gitignore`, and local source config as needed. It never runs `git init` for explicit or implicit worlds.
- **DELTA-MillRouterRuntime-Cli-001.CC9:** Repo `.skein/.gitignore` ignores local overlays and accidental local artifacts, but runtime state, sockets, metadata, and SQLite data are not intentionally stored under `.skein`.
- **DELTA-MillRouterRuntime-Cli-001.CC10:** `strand weaver start` asks `mill` to start the selected world's weaver as a mill child process. It returns JSON status for the selected world and does not run the weaver in the foreground terminal.
- **DELTA-MillRouterRuntime-Cli-001.CC11:** Ordinary strand commands such as `add`, `update`, `show`, `list`, `ready`, `weave`, `pattern explain`, and `op` are routed through `mill` to an already running selected-world weaver. If no weaver is running for the selected world, they fail non-zero with remediation to run `strand weaver start`.
- **DELTA-MillRouterRuntime-Cli-001.CC12:** `strand weaver stop` asks `mill` to stop the selected world's weaver child and clean that world's runtime metadata/socket.
- **DELTA-MillRouterRuntime-Cli-001.CC13:** `strand weaver status` asks `mill` for selected-world status and reports the selected repo/config identity, XDG state/data paths, database path, pid, weaver identity, and endpoint information when running.
- **DELTA-MillRouterRuntime-Cli-001.CC14:** Public strand command output remains JSON-only. Mill transport errors, world-resolution errors, missing-weaver errors, and weaver domain errors preserve non-zero exit behavior and useful structured messages.
- **DELTA-MillRouterRuntime-Cli-001.CC15:** The public CLI no longer exposes or calls a socket `init` operation for database initialization. The `init` command name is repo config bootstrap only.

## DELTA-MillRouterRuntime-Cli-001.P3 Design decisions

### DELTA-MillRouterRuntime-Cli-001.D1 Mill is mandatory for strand

- **Decision:** `strand` talks to `mill` for all public commands rather than preserving a direct fallback to per-world weaver sockets.
- **Rationale:** A single entrypoint keeps the user model simple and avoids two subtly different lifecycle paths during alpha.
- **Rejected:** Supporting both direct and mill-routed modes for compatibility.

### DELTA-MillRouterRuntime-Cli-001.D2 No ordinary-command autostart

- **Decision:** `mill` does not start weavers for ordinary `strand add/list/...` requests; users run `strand weaver start` per repo when they want that repo's runtime active.
- **Rationale:** Starting a weaver executes trusted repo config. Making that side effect explicit is clearer and fail-loud.
- **Rejected:** Automatically starting a weaver on first mutation or read command.

### DELTA-MillRouterRuntime-Cli-001.D3 Git-root implicit worlds only

- **Decision:** No-flag repo worlds require Git and resolve to the Git toplevel.
- **Rationale:** Skein is primarily a coding-agent tool; binding implicit worlds to repositories avoids accidental cwd scratch worlds.
- **Rejected:** Creating `.skein` in arbitrary non-Git directories.

## DELTA-MillRouterRuntime-Cli-001.P4 Open questions

- **DELTA-MillRouterRuntime-Cli-001.Q1:** None blocking. Exact mill request envelope field names can be chosen during implementation if they preserve the contracts above.
