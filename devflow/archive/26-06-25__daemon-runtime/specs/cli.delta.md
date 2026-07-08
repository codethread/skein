# CLI Surface Delta: Daemon Runtime

**Document ID:** `SPEC-002-D001` **Status:** Merged **Last Updated:** 2026-06-25 **Root spec:** [CLI Surface](../../../specs/cli.md) **Feature spec:** [Daemon Runtime](./daemon-runtime.md)

## SPEC-002-D001.P1 Changed purpose

- **SPEC-002-D001.C1:** The CLI remains the primary scripted interface for coding agents, but command execution is routed through the local daemon instead of opening a per-invocation SQLite datasource.

## SPEC-002-D001.P2 Interface changes

- **SPEC-002-D001.C2:** Add daemon lifecycle commands:

```text
[--db <path>] daemon start [--config <path>]
[--db <path>] daemon stop
[--db <path>] [--format human|edn|json] daemon status
```

- **SPEC-002-D001.C3:** Existing task commands keep their stripped surface:

```text
init
add <title> [--status todo|done|failed|cancelled] [--attr key=value ...]
update <id> [--title title] [--status todo|done|failed|cancelled] [--attr key=value ...] [--edge edge-type:to-id ...]
show <id>
list
ready
```

## SPEC-002-D001.P3 Contract changes

- **SPEC-002-D001.C4:** `--db` identifies the daemon/database runtime to connect to. It no longer authorizes task commands to silently open SQLite directly.
- **SPEC-002-D001.C5:** Task commands fail non-zero when no matching daemon is reachable.
- **SPEC-002-D001.C6:** Task commands fail non-zero when runtime metadata points to a stale process, an unreachable endpoint, or a daemon serving a different canonical database path.
- **SPEC-002-D001.C7:** `daemon start` initializes the daemon runtime for the selected database and writes runtime metadata only after the endpoint is ready.
- **SPEC-002-D001.C8:** `daemon status` reports structured daemon identity and health in EDN/JSON formats using the existing global `--format` option.
- **SPEC-002-D001.C9:** Output contracts for `add`, `show`, `list`, and `ready` remain machine-readable via existing `--format` behavior after daemon result normalization.
- **SPEC-002-D001.C10:** Transport port selection is not part of the public CLI contract. Tests and dev workflows may use internal config or environment wiring when deterministic endpoint selection is required.
- **SPEC-002-D001.C11:** `daemon start --config <path>` reads a trusted startup EDN map. The initial config shape supports only `{:load-files ["path/to/trusted.clj"]}`; relative load paths resolve from the config file directory. Missing config files, malformed EDN, unsupported keys, missing load files, and load-time read/compile/runtime failures fail startup loudly before runtime metadata is published.
