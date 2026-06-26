# Skein

Skein is a small weaver-backed strand graph for coding agents and humans. It stores strands locally in SQLite, exposes a thin `strand` CLI for scripts, and keeps richer customization in trusted weaver config and Clojure REPL workflows.

Use it to:

- Track local strand graphs without a hosted service.
- Let coding agents create, update, query, and inspect structured strand state.
- Attach flexible JSON attributes to strands and edges for userland workflows.
- Keep custom queries, graph helpers, views, and runtime libraries in trusted Clojure config instead of the low-privilege CLI.

## Quick start

Install the `strand` command from this checkout and point your default Skein world at it:

```sh
go install ./cli/cmd/strand
SKEIN_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/skein"
mkdir -p "$SKEIN_CONFIG"
printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$PWD" | jq . > "$SKEIN_CONFIG/config.json"
```

For agent/testing work, prefer an explicit disposable world:

```sh
world=$(mktemp -d)
printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$PWD" | jq . > "$world/config.json"
```

Start the weaver in one terminal:

```sh
strand --config-dir "$world" weaver start
```

Then use it from another terminal:

```sh
strand --config-dir "$world" init
strand --config-dir "$world" add "Sketch strand model" --active false --attr example_outcome=sketched
strand --config-dir "$world" add "Write docs" --attr owner=agent
strand --config-dir "$world" --format json list
strand --config-dir "$world" --format json ready
strand --config-dir "$world" weaver stop
```

Explicit `--config-dir <dir>` worlds keep config in `<dir>/config.json`, runtime state in `<dir>/state`, and strand data in `<dir>/data/skein.sqlite`.

## Data model

Skein stores:

- strands with generated text ids, titles, `active`, `ephemeral`, `inactive_at`, timestamps, and JSON attributes;
- strand edges with a type, direction, and JSON attributes;
- `depends-on` edges used to calculate readiness.

`active` is the only core lifecycle concept. Inactive persistent strands are retained with `inactive_at`; ephemeral strands are deleted when deactivated. Outcomes or categories are user attributes chosen by your world, not built-in fields.

## Runtime customization

Named queries, weaver-memory views, and runtime libraries are loaded into the selected Skein world, then consumed by helpers or by small CLI commands such as `list --query <name>`. Fresh `strand init` startup config requires the built-in `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha` helper namespaces as editable examples.

Use `strand weaver repl` for trusted interactive work.

## Documentation

- [Getting started](./docs/getting-started.md)
- [Clojure crash course](./docs/clojure-crash-course.md)
- [Devflow specs](./devflow/specs/)
