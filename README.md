# Skein 🧶 Give your agents a lisp

> skein: A continuous length of thread or wool wound into a loose, long twist so it doesn't tangle

Skein is a small, local graph for coding agents and the humans working with them — and, under the thin CLI, a live Lisp runtime you can reshape while it runs. Strands live on your machine in SQLite, and everyday `strand` commands emit JSON for easy scripting. But the real power is the **weaver**: a long-lived Clojure image you attach a REPL to, introspect, and extend without ever restarting it.

A few terms you'll see throughout:

- **strand** — one record in your graph: a title, a lifecycle `state`, and an open-ended map of JSON `attributes`.
- **weaver** — the long-lived local process that owns the database and runtime state.
- **mill** — the local supervisor you start once; it routes each command to the right weaver.
- **`strand` CLI** — a thin, JSON-only control surface for scripts and agents.
- **REPL** — a live, trusted Clojure connection to the weaver for customization and exploration.
- **workspace** — one isolated Skein setup, chosen by workspace directory (a repo's `.skein`, or an explicit `--workspace`).

Most agent tools ship a fixed schema and someone else's workflow. Skein ships primitives and a running Lisp, so you build the workflow you actually want:

- Track work as a local strand graph — no hosted service, no server to babysit.
- Let agents create, update, query, and inspect structured state through a safe JSON CLI.
- Model your own concepts — status, priority, ownership, "done", board columns — as open JSON attributes, instead of waiting for a schema to grow.
- Rebuild whatever you need, in trusted Clojure: a beads-style issue tracker, a kanban board, or your own Claude Code workflow — from named queries, weave patterns, views, event handlers, and custom `strand op` commands.
- Introspect and reshape the running system live: attach a REPL, inspect runtime state, redefine a function and watch the next call pick it up, or hot-reload your whole config — all without restarting or losing your strands.

## Quick start

Install from this checkout, then start `mill` once:

```sh
make install
mill start
```

In the Git repo you want to work in, create a workspace and start its weaver:

```sh
mkdir -p ~/learn-skein
cd ~/learn-skein
git init
strand init          # create this repo's .skein config
strand weaver start  # boot the weaver for this workspace
```

Add a few strands, including one that depends on another:

```sh
strand add "initial"
ID=$(strand add "hello" | jq -r '.id')      # capture the new strand's id
strand add "world" --edge depends-on:${ID}  # "world" depends on "hello"
```

Inspect them — these commands emit JSON:

```sh
strand list   # every strand
strand ready  # only strands with nothing blocking them
```

Open a live REPL or stop the weaver when you're done:

```sh
strand weaver repl  # live Clojure REPL (optional)
strand weaver stop
```

By default (no `--workspace`), `strand` finds the canonical Git repository root and uses that repo as its workspace, so linked worktrees of the same repository share one workspace. Outside a supported Git layout, no-flag commands fail loudly rather than guess — they won't silently create a workspace from your current directory or fall back to a global default. See [Getting started](./docs/getting-started.md) for the full walkthrough.

### Isolated weavers

For agent or testing work, prefer an explicit disposable workspace instead of a repo's default one:

```sh
workspace=$(mktemp -d)
```

Pass `--workspace "$workspace"` on **every** command that should target it — the flag is not remembered between commands. With `mill` running:

```sh
strand --workspace "$workspace" init
strand --workspace "$workspace" weaver start
```

Then use it from another terminal:

```sh
strand --workspace "$workspace" add "Sketch strand model" --state closed --attr example_outcome=sketched
```

An explicit `--workspace <dir>` workspace keeps trusted config in that directory. Runtime metadata, sockets, and SQLite data live under mill-owned XDG state paths, keyed to the selected config.

## Data model

Skein stores:

- **strands** — a generated text id, title, lifecycle `state`, timestamps, and JSON attributes;
- **edges** — an open relation name, a direction, and JSON attributes;
- three **declared acyclic relations** for structure: `depends-on`, `parent-of`, and `supersedes`.

`state` is the only core lifecycle field. Active strands participate in readiness; closed and replaced strands are retained; deleting is explicit, via `burn`.

Superseding is a first-class move: `strand supersede <old-id> <replacement-id>` records `replacement --supersedes--> old`, marks the old strand `replaced`, and rewires its direct dependents onto the replacement.

Everything else — outcomes, categories, temporary markers, priorities — lives in attributes your workspace chooses, not in built-in fields.

## Runtime customization

The CLI stays thin on purpose; the power lives in the weaver. It's a real Clojure image — the full language, macros and all — so your customizations can be as expressive as you want, and you can introspect or redefine any of them from a live REPL without a restart.

Richer behavior — named queries, weave patterns, weaver-memory views, event handlers, custom `strand op` commands, and trusted runtime spools — is loaded into your workspace, discovered through small read-only CLI groups such as `query list` / `query explain <name>` and `pattern list` / `pattern explain <name>`, then consumed by semantic commands such as `list --query <name>` and `weave --pattern <name>`.


Two kinds of code can extend the weaver:

- **Built-in `skein.api.*.alpha` namespaces** — privileged helpers shipped with Skein.
- **Your own trusted spools** — Clojure loaded through config, approved local roots, or live REPL work.

Reference spools ship with Skein in [`spools/`](./spools/README.md), with sources under `spools/src` — a workflow engine, a devflow lifecycle, and an ephemeral-strand helper — each documented beside its code and driven end-to-end by its tests.

Fresh `strand init` creates a repo's config files: `.skein/config.json`, `.skein/init.clj`, `.skein/spools.edn`, and `.skein/.gitignore`. Commit the shared files (`config.json`, `init.clj`, `spools.edn`) for behavior the whole repo gets; keep personal overlays in gitignored `config.local.json`, `init.local.clj`, and `spools.local.edn`.

`strand init` does not persist a source path. Mill resolves the Skein source for weaver/REPL launch from `SKEIN_SOURCE`, the install-time source, or a canonical Skein checkout as the working directory.

Use `strand weaver repl` to attach directly to the running weaver's nREPL for trusted interactive work, and `(skein.api.runtime.alpha/reload!)` to hot-reload `init.clj` followed by `init.local.clj`.

## Documentation

- [Skein user reference](./docs/skein.md)
- [Getting started](./docs/getting-started.md)
- [Shipped reference spools](./spools/README.md)
- [Clojure crash course](./docs/clojure-crash-course.md)
- [Devflow specs](./devflow/specs/)
