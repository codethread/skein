# Atom Todo Graph

Atom is a small daemon-backed task graph for coding agents and humans. It stores tasks locally in SQLite, exposes a thin `todo` CLI for scripts, and keeps richer customization in trusted daemon config and Clojure REPL workflows.

## What it is for

- Track local task graphs without a hosted service.
- Let coding agents create, update, query, and inspect structured task state.
- Model dependencies with `depends-on` edges so agents can ask what is ready next.
- Attach flexible JSON attributes to tasks and edges for userland workflows.
- Customize the live daemon through trusted startup config, named queries, and REPL helpers.

For contributor setup, validation, and debugging, see [CONTRIBUTING.md](./CONTRIBUTING.md). For canonical behavior contracts, see the [Devflow spec index](./devflow/README.md#root-specs).

## Bootstrap

Install the `todo` command from this checkout and point your default daemon world at it:

```sh
go install ./cli/cmd/todo
ATOM_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/skein"
mkdir -p "$ATOM_CONFIG"
printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$PWD" | jq . > "$ATOM_CONFIG/config.json"
```

This installs `todo` into your Go bin directory, which is commonly already on `PATH`. After that, day-to-day use goes through `todo`.

## Quickstart

Start the daemon in one terminal:

```sh
todo daemon start
```

Use it from another terminal:

```sh
todo init  # bootstraps missing config-dir files and git repo without overwriting existing files
todo add "Sketch model" --status done --attr priority=high
todo add "Write docs" --attr owner=agent
todo --format json list
todo --format json ready
todo daemon stop
```

Use `--config-dir <dir>` for an alternate daemon world. Its config lives in `<dir>/config.json`, runtime state in `<dir>/state`, and task data in `<dir>/data`.

Once the daemon is running, continue with [Getting started after the daemon is running](./docs/getting-started.md). If Clojure syntax is unfamiliar, keep [Tiny Clojure crash course for Atom users](./docs/clojure-crash-course.md) nearby.

## Core model

Atom stores:

- tasks with generated text ids, title, status, lifecycle timestamps, and JSON attributes;
- task edges with a type, direction, and JSON attributes;
- acyclic graph relationships, rejecting cycles loudly;
- `depends-on` edges used to calculate task readiness.

Final statuses are `done`, `failed`, and `cancelled`. Full semantics live in [Task Model](./devflow/specs/task-model.md).

## Customization model

Atom is daemon-core-first. Runtime customization belongs in trusted daemon startup config and connected REPL workflows; the CLI stays small and scriptable.

Named queries, daemon-memory views, and runtime libraries are loaded into the selected daemon world, then consumed by helpers or by small CLI commands such as `list --query <name>`. Fresh `todo init` startup config requires the built-in `atom.graph.alpha` and `atom.views.alpha` helper namespaces as an editable template. User/community libraries still belong under `libs.edn`; shipped `atom.*.alpha` namespaces are already on the Atom classpath. The CLI intentionally does not grow loaders for arbitrary query, view, or library files.

## Reference

- [Getting started](./docs/getting-started.md) — first CLI/REPL workflow after the daemon is running.
- [Tiny Clojure crash course](./docs/clojure-crash-course.md) — enough syntax and vocabulary for humans using the REPL.
- [CONTRIBUTING.md](./CONTRIBUTING.md) — local setup, validation, debugging, and implementation guidance.
- [CLI Surface](./devflow/specs/cli.md) — command vocabulary and output behavior.
- [REPL API](./devflow/specs/repl-api.md) — helper vocabulary.
- [Daemon Runtime](./devflow/specs/daemon-runtime.md) — daemon lifecycle and trusted startup config.
- [Task Model](./devflow/specs/task-model.md) — data semantics.
- [Devflow Philosophy](./devflow/PHILOSOPHY.md) — daemon-core design mental model.
