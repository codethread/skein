# Skein orientation

Skein is a local strand graph for agents and humans: a durable SQLite-backed graph of work, notes, dependencies, and workflow state, behind a small machine-readable command surface. `mill` is the local router/supervisor, the long-lived **weaver** owns storage and runtime state, and the `strand` CLI is a thin JSON control surface. Runtime customization lives in trusted config and REPL workflows.

Run `mill strand prime` for the task-tracking workflow (creating and driving strand plans).

## Skein source on disk

{{.Source}}

This is the resolved Skein source checkout for the current world (from `SKEIN_SOURCE`, the install-time source recorded by `make install`, or a canonical Skein checkout cwd). All the docs below live under it.

## Read these to go deeper

- `{{.Source}}/docs/skein.md` — canonical user reference: mental model,
  workspaces, weaver lifecycle, the CLI surface, the strand model, edges &
  readiness, attributes as the extension point, queries, the REPL, startup
  config, authoring your own spool, weave patterns, views, and events. Its
  spec index points at the exact contracts in `{{.Source}}/devflow/specs/`.
- `{{.Source}}/spools/README.md` — index of the shipped reference spools
  (workflow engine, ephemeral strands, guild, bobbin, selvage, carder) plus the
  approved local-root spools (agent-run, delegation, executors.subagent, chime, kanban) and the
  git-distributed devflow lifecycle. Each row links a contract doc.
- `{{.Source}}/AGENTS.md` — when working **inside a Skein-style repo**, read its
  "Repo coordination workspace (.skein)" section: the installed runtime surface,
  the `.skein` shared coordination world, the kanban board, the devflow
  lifecycle, delegation, and branch-work visibility.

## Extending the .skein config

Runtime behaviour is trusted Clojure loaded by the weaver. To add named queries, weave patterns, ops, views, event handlers, or activate a spool:

- Read `docs/skein.md` → "Startup config", "Authoring your own spool code",
  "Weave patterns", "Queries", and "Views and graph helpers".
- Explore live with `mill weaver repl` or `mill weaver repl --stdin`.
- `spools.edn` approves local/git spool roots; `runtime/sync!` makes them
  available; `runtime/use!` activates a module; `runtime/reload!`
  re-runs startup files without restarting the weaver.

## Live REPL hygiene for shared weaver sessions

`mill weaver repl` and `mill weaver repl --stdin` evaluate inside the live weaver JVM, usually in the shared `skein.repl` namespace. Exploratory requires and scratch defs mutate that namespace, so use names that are easy to identify and clean up.

- Prefer `:as` aliases over `:refer` in shared REPL work.
- Prefix aliases and scratch vars with an owner/session prefix, e.g. `ct-`,
  `agent-abc-`, or a task slug.
- Clean aliases with `ns-unalias` and scratch vars with `ns-unmap` when done.
- Avoid unprefixed scratch vars like `result`, `x`, `data`, or bare referred
  helpers in `skein.repl`.

```clojure
(require '[clojure.pprint :as ct-pprint])
(def ct-config-publics (keys (ns-publics 'config)))

(ct-pprint/pprint ct-config-publics)

(ns-unalias *ns* 'ct-pprint)
(ns-unmap *ns* 'ct-config-publics)
```

For stronger isolation, create an agent-local namespace and call Skein helpers through an alias:

```clojure
(create-ns 'agent.ct)
(in-ns 'agent.ct)
(clojure.core/refer 'clojure.core)
(require '[skein.repl :as repl]
         '[clojure.pprint :as ct-pprint])

(ct-pprint/pprint (repl/ready))

(remove-ns 'agent.ct)
```
