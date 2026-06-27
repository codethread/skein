# Skein 🧶

Skein is a small weaver-backed strand graph for coding agents and humans. It stores strands locally in SQLite, exposes a thin JSON-only `strand` CLI for scripts, and keeps richer customization in trusted weaver config and Clojure REPL workflows.

Use it to:

- Track local strand graphs without a hosted service.
- Let coding agents create, update, query, and inspect structured strand state.
- Attach flexible JSON attributes to strands and edges for userland workflows.
- Keep custom queries, weave patterns, graph helpers, views, and runtime libraries in trusted Clojure config instead of the low-privilege CLI.

## Quick start

Install the `strand` command from this checkout and point your default Skein world at it:

```sh
go install ./cli/cmd/strand
SKEIN_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/skein"
mkdir -p "$SKEIN_CONFIG"
printf '{"configFormat":"alpha","source":"%s"}\n' "$PWD" | jq . > "$SKEIN_CONFIG/config.json"
```

Start the weaver in one terminal:

```sh
strand weaver start
```

Then use it from another terminal:

```sh
strand init
strand add "Sketch strand model" --active false --attr example_outcome=sketched
strand add "Write docs" --attr owner=agent
strand list
strand ready
```

The CLI emits JSON for all strand/weaver commands. The `repl` is where Skein really shines (see [docs](./docs/getting-started.md) for more):

```sh
strand weaver repl
```

When finished:

```sh
strand weaver stop
```

### Isolated weavers

For agent/testing work, prefer an explicit disposable world:

```sh
world=$(mktemp -d)
printf '{"configFormat":"alpha","source":"%s"}\n' "$PWD" | jq . > "$world/config.json"
```

Start the weaver in one terminal:

```sh
strand --config-dir "$world" weaver start
```

Then use it from another terminal:

```sh
strand --config-dir "$world" init
strand --config-dir "$world" add "Sketch strand model" --active false --attr example_outcome=sketched
```

Explicit `--config-dir <dir>` worlds keep config in `<dir>/config.json`, runtime state in `<dir>/state`, and strand data in `<dir>/data/skein.sqlite`.

## Data model

Skein stores:

- strands with generated text ids, titles, `active`, `inactive_at`, timestamps, and JSON attributes;
- strand edges with a type, direction, and JSON attributes;
- `depends-on` edges used to calculate readiness.

`active` is the only core lifecycle concept. Inactive strands are retained with `inactive_at`; destructive cleanup uses explicit `burn`. Outcomes, categories, temporary markers, or other workflow concepts are user attributes chosen by your world, not built-in fields.

## Runtime customization

Named queries, weave patterns, weaver-memory views, and runtime libraries are loaded into the selected Skein world, then consumed by helpers or by small CLI commands such as `list --query <name>` and `weave --pattern <name>`.

Fresh `strand init` startup config creates a small resilient `init.clj` that syncs approved libraries. Add your own config or library files when you need runtime queries, views, or workflow helpers.

Use `strand weaver repl` for trusted interactive work and `(skein.libs.alpha/reload!)` to hot-reload `init.clj`.

## Documentation

- [Skein user reference](./docs/skein.md)
- [Getting started](./docs/getting-started.md)
- [Clojure crash course](./docs/clojure-crash-course.md)
- [Devflow specs](./devflow/specs/)
