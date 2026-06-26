# Devflow

Active feature work lives under `devflow/feat/`. Root specs in `devflow/specs/` become canonical only when feature work ships.

Always study [TENETS](./TENETS.md) and [PHILOSOPHY](./PHILOSOPHY.md). No code, spec or idea can violate these unless explicitly stated and cited in an agreed RFC first.

## Root specs

Root specs are canonical for shipped behavior:

- [Strand Model](./specs/strand-model.md) — strand records, active/ephemeral lifecycle, JSON attributes, edge semantics, and readiness rules.
- [CLI Surface](./specs/cli.md) — scriptable command contract for agents, including the thin Go `strand` CLI and JSON socket boundary.
- [REPL API](./specs/repl-api.md) — interactive Clojure helper contract, including connected REPL and runtime library workspace helpers.
- [Weaver Runtime](./specs/daemon-runtime.md) — local long-lived weaver lifecycle, metadata, transports, trusted startup config, query registry, and runtime library workspace model.

## Active features

- `library-author-testing-support` — proposed library-author testing helpers and weaver storage support.

## Archived features

Archived feature folders preserve historical planning context. Current shipped contracts are the root specs above, even if older archive notes describe pre-spec documentation locations.

- `26-06-24__agent-tool-interface` — shipped agent-operable CLI/REPL interface for the todo graph MVP.
- `26-06-24__db-owned-task-ids` — shipped generated task ids and creation-time `--link` edges.
- `26-06-24__batch-task-refs` — shipped stdin EDN batch task creation with batch-local refs.
- `26-06-25__daemon-runtime` — shipped local daemon runtime with nREPL transport, daemon-backed CLI/REPL clients, and trusted startup config.
- `26-06-25__daemon-query-registry` — shipped in-memory daemon query registry managed through REPL/config workflows and consumed by CLI named queries.
- `26-06-25__go-cli-migration` — shipped native Go `todo` CLI over the daemon JSON Unix socket, with JSON-only machine output and Clojure REPL/config retained for rich workflows.
- `26-06-25__user-daemon-home` — shipped config-dir daemon worlds, fixed selected-world socket discovery, default daemon init, and connected REPL/stdin UX.
- `26-06-25__runtime-plugin-system` — shipped an earlier trusted local plugin/library MVP. Its public `load-plugin!` and plugin metadata surface has been superseded by the runtime library workspace model in the canonical root specs.
- `26-06-26__runtime-library-workspace` — shipped config-dir Clojure library workspaces with `libs.edn`, approved local roots, daemon-side `atom.libs.alpha/sync!` and `use!`, module-use introspection, and replacement of the plugin-directory public extension API.
- `26-06-26__runtime-transformation-primitives` — shipped built-in `atom.graph.alpha` / `atom.views.alpha` helpers for set-oriented graph/query composition and daemon-memory read-only views.
- `26-06-26__skein-rename` — shipped Skein/strand/weaver rename, strand model lifecycle/retention, `strand` CLI, and `skein.*` namespaces.
- `26-06-24__stripped-task-api` — shipped smaller CLI/REPL surface with first-class task lifecycle fields.
