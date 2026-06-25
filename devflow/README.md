# Devflow

Active feature work lives under `devflow/feat/`. Root specs in `devflow/specs/` become canonical only when feature work ships.

Always study [TENETS](./TENETS.md) and [PHILOSOPHY](./PHILOSOPHY.md). No code, spec or idea can violate these unless explicitly stated and cited in an agreed RFC first.

## Root specs

Root specs are canonical for shipped behavior:

- [Task Model](./specs/task-model.md) — task records, JSON attributes, edge semantics, and readiness rules.
- [CLI Surface](./specs/cli.md) — scriptable command contract for agents.
- [REPL API](./specs/repl-api.md) — interactive Clojure helper contract.
- [Daemon Runtime](./specs/daemon-runtime.md) — local long-lived daemon lifecycle, metadata, transport, and trusted startup config.

## Active features

- `runtime-library-workspace` — reviewed plan for config-dir Clojure library workspaces, approved local roots, daemon-side `use!` activation, and layered resilient startup.

## Archived features

Archived feature folders preserve historical planning context. Current shipped contracts are the root specs above, even if older archive notes describe pre-spec documentation locations.

- `26-06-24__agent-tool-interface` — shipped agent-operable CLI/REPL interface for the todo graph MVP.
- `26-06-24__db-owned-task-ids` — shipped generated task ids and creation-time `--link` edges.
- `26-06-24__batch-task-refs` — shipped stdin EDN batch task creation with batch-local refs.
- `26-06-25__daemon-runtime` — shipped local daemon runtime with nREPL transport, daemon-backed CLI/REPL clients, and trusted startup config.
- `26-06-25__daemon-query-registry` — shipped in-memory daemon query registry managed through REPL/config workflows and consumed by CLI named queries.
- `26-06-25__go-cli-migration` — shipped native Go `todo` CLI over the daemon JSON Unix socket, with JSON-only machine output and Clojure REPL/config retained for rich workflows.
- `26-06-25__user-daemon-home` — shipped config-dir daemon worlds, fixed selected-world socket discovery, default daemon init, and connected REPL/stdin UX.
- `26-06-25__runtime-plugin-system` — shipped trusted local runtime plugin/library MVP with blessed `atom.*.alpha` namespaces, local `load-plugin!`, daemon-lifetime plugin metadata, bootstrap/prelude ergonomics, and CLI plugin non-goals.
- `26-06-24__stripped-task-api` — shipped smaller CLI/REPL surface with first-class task lifecycle fields.
