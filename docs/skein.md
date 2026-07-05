# Skein user reference

Skein is a local strand graph for agents and humans. It gives you a durable SQLite-backed graph of work, notes, dependencies, and workflow state, while keeping the command-line surface small and machine-readable.

The short version:

- A **strand** is one record in your graph.
- A **weaver** is the long-lived local process that owns the database and runtime state.
- The **`strand` CLI** is a thin JSON control surface for scripts and agents.
- The **REPL** is the trusted, high-power surface for customization and exploration.
- Your workflow model lives mostly in custom **attributes** and your own config/spool code.

This guide is written for Skein users and for agents working inside a user's Skein workspace. Maintainer-facing contracts live in [`devflow/specs/`](../devflow/specs/); see the [spec index](#spec-index) at the end.

## Mental model

Skein is daemon-core-first behind a small router. You start `mill` once, ask it to start a weaver for a selected workspace, then clients send requests through `mill` to that weaver.

```text
selected workspace (normally canonical repo .skein)
  config.json        -> shared alpha workspace config
  config.local.json  -> personal config overlay
  init.clj           -> shared trusted startup code loaded by the weaver
  init.local.clj     -> personal startup overlay loaded after init.clj
  spools.edn         -> shared approved local spool roots
  spools.local.edn   -> personal approved-spool overlay
  spools/            -> optional local spools

running weaver
  owns SQLite storage
  owns named queries
  owns weave pattern registrations
  owns view registrations
  owns event handler registrations
  owns synced spool state

clients
  strand CLI       -> mill -> weaver JSON socket, small safe command set
  weaver REPL      -> mill resolution -> direct live nREPL attach to the weaver JVM
```

Different workspaces are different workspaces. Use `--workspace <dir>` when you want an isolated workspace for experiments, agent work, or tests.

## Workspaces and workspaces

The ordinary workspace is repository-scoped. Without `--workspace`, `mill` resolves the canonical repository root and uses that repo's `.skein` directory as the selected workspace. Linked worktrees for the same repository share this default workspace. Outside supported Git layouts, no-flag commands fail loudly. `mill init` creates or completes `.skein` at the canonical Git root and fails loudly outside supported Git layouts:

```sh
mill init
```

Mill resolves the Skein source checkout used to launch the weaver from `SKEIN_SOURCE`, the install-time source recorded by `make install`, or a canonical Skein checkout cwd. `mill init` does not persist a source path in `.skein/config.json`.

A workspace can also be selected explicitly with:

```sh
strand --workspace /path/to/workspace ...
```

For explicit workspaces, `/path/to/workspace` is the config workspace. Runtime state,
metadata, sockets, and data are owned by mill under Skein's XDG state root for
the selected config identity.

The important file is `config.json`:

```json
{
  "configFormat": "alpha"
}
```

`config.json` is only the low-privilege alpha format marker. Source checkout paths are mill launch context, not config workspace state.

## Agent guidance files

From a Skein source checkout, `make install` installs the Go CLIs (`strand` and `mill`) and records the checkout as mill's default source for weaver launch and the thin nREPL attach client. After that, use the CLIs directly: `mill start`, `mill init`, and `mill weaver start`.

`mill init` is the normal repo bootstrap path. It creates or completes the canonical repo `.skein` workspace, writes shareable `config.json` with the alpha format marker when absent, and leaves shared config files ready to commit. It does not run `git init`, persist source, or initialize database storage; weaver startup prepares storage.

User-facing Skein documentation lives in the source checkout under `docs/`; the canonical user reference is `docs/skein.md`. Two harness-agnostic orientation commands surface this to agents at runtime, with no running weaver required: `mill skein prime` resolves the Skein source and prints the paths to the docs, the spool index, and the repo coordination guidance, plus how to extend `.skein` config; `mill strand prime` prints the strand planning/tracking workflow. In a repo-world bootstrap, `mill init` also seeds a `## Skein / strand` section in the repository-root `AGENTS.md`/`CLAUDE.md` that points new agents at these two commands.

When working in this repository, also read the "Repo coordination workspace (.skein)" section of the root [`AGENTS.md`](../AGENTS.md).
This repo-local guidance documents the installed runtime surface loaded from
`.skein/init.clj` and `.skein/config.clj`: the classpath `skein.spools.workflow`
spool, the git-distributed `skein.spools.devflow` spool (approved via a
`.skein/spools.edn` git coordinate), the `devflow-*` CLI ops that drive the
feature lifecycle, feature-scoped queries, and the `agent-plan` pattern for
lightweight work DAGs.

## Weaver

The weaver is the application core. It is a long-lived local Clojure process that owns:

- the SQLite database connection;
- strand creation, update, query, readiness, and burn operations;
- the in-memory named-query registry;
- the in-memory weave-pattern registry;
- the in-memory view registry;
- the in-memory event handler registry and async dispatch worker;
- the approved-spool sync state;
- runtime module activation state.

Start mill once, then start the selected workspace's weaver:

```sh
mill start
mill weaver start --workspace "$workspace"
```

Stop it:

```sh
mill weaver stop --workspace "$workspace"
```

Check it:

```sh
mill weaver status --workspace "$workspace"
```

The weaver exposes two local transports:

- a Unix socket used by the Go `strand` CLI;
- an nREPL endpoint used by the live weaver REPL.

A selected workspace may have one running weaver. Runtime registries are weaver-lifetime state, so named queries, weave patterns, views, and synced spool state should be loaded from startup config if you want them to appear after every restart.

## CLI

The `strand` CLI is intentionally small. It is for scripts, low-friction agent use, and JSON automation. It does not evaluate rich Clojure forms or mutate runtime extension state.

Common commands:

```sh
mill init --workspace "$workspace"
strand --workspace "$workspace" add "Write docs" --attr owner=agent --attr area=docs
strand --workspace "$workspace" update <id> --state closed
strand --workspace "$workspace" update <id> --edge depends-on:<other-id>
strand --workspace "$workspace" show <id>
strand --workspace "$workspace" list
strand --workspace "$workspace" ready
strand --workspace "$workspace" burn <id>
strand --workspace "$workspace" query list
strand --workspace "$workspace" query explain <query-name>
strand --workspace "$workspace" pattern list
strand --workspace "$workspace" pattern explain <pattern-name>
printf '{"title":"New work"}\n' | strand --workspace "$workspace" --stdin weave --pattern <pattern-name> --input :stdin

```

The public strand/weaver commands emit JSON. CLI attributes are string-valued `key=value` pairs; richer Clojure data belongs in config or REPL workflows.

Use the CLI for:

- creating and updating ordinary strands;
- attaching simple attributes;
- adding edges;
- asking what is ready;
- consuming named queries registered by trusted config;
- invoking weave patterns registered by trusted config;
- starting, stopping, and checking the weaver.

Do not expect the CLI to be a package manager, query authoring surface, plugin host, or Clojure evaluator. Those belong to the weaver config and REPL.

## Discovery tiers: help, about, prime

Skein has one deliberate convention for "how do I find out?", with three tiers. They compose as an escalation path — `prime` orients you, `about` explains an op you are about to lean on, `help` answers exact invocation questions — and each tier has a different source of truth:

| Tier | Source | Question it answers | Examples |
| --- | --- | --- | --- |
| `help` | **Generated** from registered arg-spec data | "What can I type?" — verbs, flags, positionals, types | `strand help`, `strand help <op>`, `strand <op> help\|-h\|--help` |
| `about` | **Authored** per-op JSON manual | "What does this op mean?" — semantics, contracts, attribute conventions | `strand kanban about`, `strand agent about` |
| `prime` | **Authored** prose orientation | "How do we work here?" — run **before** starting work | `mill skein prime`, `mill strand prime`, `strand kanban prime` |

**`help` is never hand-written.** `strand help` lists every registered op; `strand help <op>` renders one op's detail from its arg-spec, including declared subcommands. Ops that declare `:subcommands` also answer `strand <op> help`, `strand <op> -h`, and `strand <op> --help` (sole token, no payloads) with the same detail at exit 0, and their missing/unknown-subcommand failures are structured parser errors carrying the available names — no spool ever writes its own usage strings or dispatch errors. Contracts: SPEC-002.C39, SPEC-003.C64/C65, SPEC-004.C63c–e.

**`about` is the op's manual.** A spool op whose meaning goes beyond its argument shapes ships an `about` subcommand returning a structured JSON document: purpose, conventions, attribute contracts, and usage examples. Think man page, machine-readable. Purely structural ops (batteries `add`/`list`/...) do not need one — their arg-spec already says everything.

**`prime` is run-first context priming for agents.** A `prime` command prints the working discipline for an area — the conventions an agent must load *before* acting, with pointers to deeper docs. `mill skein prime` (source/docs orientation) and `mill strand prime` (the strand workflow) ship embedded in the `mill` binary and need no running weaver; spool-level primes like `strand kanban prime` are spool-generated so they can never drift from the installed surface. Repo-world `mill init` seeds a marker-guarded section into `AGENTS.md`/`CLAUDE.md` pointing fresh agents at the prime commands (see "Agent guidance files").

Spool authors: the authoring rules for this surface live in [`docs/writing-shared-spools.md`](./writing-shared-spools.md).

## Strand model

A strand has:

- `id` — generated text id;
- `title` — human-readable title;
- `state` — core lifecycle state: `active`, `closed`, or `replaced`;
- `created_at` and `updated_at`;
- `attributes` — userland JSON object.

`state` is the only built-in lifecycle field. Concepts like `status`, `kind`, `type`, `category`, `outcome`, `owner`, `priority`, `project`, `estimate`, or `retention` are your attributes, not core fields.

| Concept | Where it belongs |
| --- | --- |
| done/not done for readiness | core `state`, where `active` participates in readiness |
| completion time | custom attribute if your workflow needs it |
| status like `todo`, `doing`, `blocked`, `done` | custom attribute |
| outcome like `done`, `cancelled`, `abandoned` | custom attribute |
| owner, priority, project, estimate | custom attributes |
| local workflow marker | custom attribute plus your own helper code |

Close work when it is no longer active. There is no special `done` command; use `update --state closed` and optionally record your own outcome attribute:

```sh
strand --workspace "$workspace" update <id> --state closed --attr outcome=done
```

Burn only when you want deletion:

```sh
strand --workspace "$workspace" burn <id>
```

## Edges and readiness

Edges connect strands with open relation names such as `depends-on`, `parent-of`, `supersedes`, or annotation relations like `references`.

A `depends-on` edge from `A` to `B` means: `A` is blocked while `B` is active.

```sh
strand --workspace "$workspace" update "$docs" --edge depends-on:"$design"
```

`ready` returns active strands whose direct `depends-on` targets are inactive or absent:

```sh
strand --workspace "$workspace" ready
```

Self-edges fail for every relation. Declared acyclic relations such as `depends-on`, `parent-of`, and `supersedes` reject relation-local cycles; annotation relations may form non-self cycles.

## Attributes are the extension point

Skein's core is deliberately small. Most workflow meaning hangs off `attributes`.

Examples:

```sh
strand --workspace "$workspace" add "Draft release notes" \
  --attr owner=agent \
  --attr project=skein \
  --attr kind=doc \
  --attr priority=high
```

Your workspace can decide what attributes mean. For example:

- `owner=agent` can mean an agent should pick it up;
- `kind=feature` can identify feature roots;
- `outcome=done` can record completion reason after deactivation;
- `temporary=true` can identify rows your own tooling treats as temporary;
- `external.issue=123` can link to another system if your tooling understands it.

Skein stores attributes as JSON text. CLI input is simple string pairs; `--attr temporary=true` stores the string `"true"`, not a JSON boolean. Trusted Clojure workflows can write richer JSON-compatible values.

Because attributes are userland, your own config and spools should define the conventions for your workspace. Prefer documenting those conventions in source-controlled docs or in your spool docs. Attribute names and cleanup behavior are userland choices, not Skein core.

## Queries

Queries can be registered in weaver memory, then consumed by the REPL or CLI.

From the live weaver REPL, `defquery!` registers a query for the current weaver lifetime only:

```clojure
(defquery! 'agent-docs
  '[:and
    [:= [:attr :owner] "agent"]
    [:= [:attr :area] "docs"]])
```

Discover and consume it from the CLI:

```sh
strand --workspace "$workspace" query list
strand --workspace "$workspace" query explain agent-docs
strand --workspace "$workspace" list --query agent-docs
strand --workspace "$workspace" ready --query agent-docs

```

`query list` and `query explain <name>` are the read-only discovery pair for named query definitions. Application stays on the read commands: `list --query <name>` and `ready --query <name>` with repeated `--param key=value` when the query declares runtime params.

Named query registries are not durable by themselves. If you want a query after every weaver restart, register it from startup-loaded code.

For a simple persistent query, put it directly in `init.clj`:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha]
         '[skein.api.weaver.alpha :as api])

(def runtime (current/runtime))

(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :call 'skein.spools.batteries/activate!})
(api/register-query! runtime 'mine [:= [:attr :owner] "ct"])
```

For a workspace that already activates a local spool with `runtime-alpha/use!`, follow that existing pattern instead: add the `api/register-query!` call to the spool's `install!` function so reload/startup installs everything from one place.

Defining a Clojure var that contains query data is not the same as registering a named query. A local var can be passed to graph helpers from your own code, but `strand list --query mine` only works after `mine` has been registered in the weaver's named-query registry.

`strand list --query mine` returns all matching strands unless you also pass a state filter. Use `strand list --query mine --state active` when you only want active matches. `strand ready --query mine` always applies readiness semantics, so returned strands are active and unblocked.

## REPL

The REPL is the trusted, high-power surface. `mill weaver repl` attaches directly to the selected running weaver nREPL, so forms evaluate in the weaver JVM with weaver process authority. Use it for richer inspection, custom query authoring, config reloads, and calling your own spool code. Prefer blessed helper/API paths for operations that should preserve validation, hooks, events, and normalized return shapes.

Open a live weaver REPL:

```sh
mill weaver repl --workspace "$workspace"
```

Useful forms:

```clojure
(def id (:id (strand! "Explore workflow" {:owner "ct" :kind "spike"})))
(strand id)
(update! id {:state "closed" :attributes {:outcome "captured"}})
(strands)
(ready)
```

Script the live weaver REPL with stdin:

```sh
printf '(skein.api.current.alpha/runtime)\n' | mill weaver repl --stdin --workspace "$workspace"
```

The REPL helper namespace includes common strand functions. Privileged runtime loader/config helpers are explicit built-in namespaces, not ordinary user spools; require them when needed:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])
(runtime-alpha/reload! (current/runtime))
```

## Startup config

`mill init` bootstraps missing workspace files in the selected workspace without overwriting existing files. It does not initialize database storage; weaver startup prepares storage for the selected workspace.

For the ordinary repo-local `.skein` workspace, it creates or ensures:

- `.skein/config.json` only if absent, with the alpha format marker;
- `.skein/spools/` directory;
- `.skein/spools.edn` only if absent, with `{:spools {}}`;
- `.skein/init.clj` only if absent, with the default below;
- `.skein/.gitignore` only if absent, ignoring local config overlays such as `config.local.json`, `init.local.clj`, and `spools.local.edn`.

It also seeds a `## Skein / strand` orientation section, bounded by `<!-- mill:skein-prime -->` and `<!-- /mill:skein-prime -->` markers, into the repository-root `AGENTS.md`/`CLAUDE.md` (appending to whichever exist, creating `AGENTS.md` when neither does), pointing new agents at `mill skein prime` and `mill strand prime`. Injection is idempotent and append-only; it never rewrites existing prose. Explicit `--workspace` bootstrap does not touch repo guidance files.

Explicit `--workspace` standalone workspaces bootstrap the selected workspace directory directly. Existing `config.json`, `spools.edn`, `init.clj`, and `.gitignore` are preserved.

The generated `init.clj` is intentionally small:

```clojure
;; init.clj
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))

(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :call 'skein.spools.batteries/activate!})
```

The weaver loads startup files in order: `init.clj`, then `init.local.clj`. Missing files are skipped; present failing files fail loudly with file context. Use startup-loaded code to register queries, weave patterns, load approved spools, register views, and install conventions for your workspace. Simple workspaces can put shared registrations directly in `init.clj` and personal overlays in gitignored `init.local.clj`; reusable or larger workspaces should keep `init.clj` minimal and install behavior from a local spool.

A direct `init.clj` query registration can look like this:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha]
         '[skein.api.weaver.alpha :as api])

(def runtime (current/runtime))

(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :call 'skein.spools.batteries/activate!})
(api/register-query! runtime 'mine [:= [:attr :owner] "ct"])
```

Use reload during development:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])
(runtime-alpha/reload! (current/runtime))
```

`skein.api.runtime.alpha` is a privileged built-in runtime loader/config helper namespace shipped with Skein. It is not an ordinary user/community spool, and loader/config helpers do not live under `skein.spools.*`.

Reload clears weaver-lifetime spool sync state, module-use state, named queries, weave patterns, views, custom ops, lifecycle hooks, event handlers, queued events, and recent event failures, then reloads `init.clj` followed by `init.local.clj`. Missing files are skipped; present failures fail loudly.

## Authoring your own spool code

Skein treats runtime extensions as trusted Clojure code. Before writing your
own, see the [shipped reference spools](../spools/README.md) — a
workflow engine and an ephemeral-strand helper (plus the external,
git-distributed devflow lifecycle) that double as worked examples of spool
design. A common layout is:

```text
workspace/
  config.json
  init.clj
  spools.edn
  spools/
    my-workflow/
      deps.edn
      src/my/workflow.clj
```

Approve the local spool root in `spools.edn`:

```clojure
{:spools {my/workflow {:local/root "spools/my-workflow"}}}
```

Relative `:local/root` values resolve against the selected workspace. Absolute paths are accepted as explicit user-approved paths, and `~` expands to your home directory.

Create a minimal `deps.edn` in the spool root:

```clojure
{:paths ["src"]}
```

If `:paths` is omitted, Skein's namespace loading defaults to `["src"]`.

Implement the spool:

```clojure
(ns my.workflow
  (:require [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as api]))

(defn install! []
  (api/register-query! (current/runtime) 'mine [:= [:attr :owner] "ct"])
  {:my.workflow/installed true})
```

Activate it from `init.clj`:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))

(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :call 'skein.spools.batteries/activate!})
(runtime-alpha/use! runtime :my/workflow
  {:ns 'my.workflow
   :spools #{'my/workflow}
   :call 'my.workflow/install!})
```

Key points:

- `spools.edn` is approval. It says which local roots the weaver may load.
- `runtime-alpha/sync!` makes approved roots available to the weaver.
- `runtime-alpha/use!` activates one module and records whether it loaded, skipped, or failed.
- `:call` must name a fully qualified zero-argument function.
- Direct `require` from `mill weaver repl` evaluates in the weaver JVM and is useful for trusted experimentation. For repeatable module activation and reload introspection, use `runtime-alpha/use!` or `runtime-alpha/reload!` from startup config or the live REPL.
- Extension code runs with weaver authority. Only load trusted code.
- There is no per-module isolation or unload guarantee. Restart the weaver for a clean runtime if needed.

## Weave patterns

Weave patterns are trusted owner-defined transformations that turn a JSON-like input payload into an atomic batch of new strands and edges. They are useful when agents should submit intent and your workspace should decide the graph shape.

Pattern registration lives in trusted Clojure config or spools, not in the public CLI. A pattern has a simple name, a fully qualified weaver-loadable function symbol, and a `clojure.spec` input contract.

```clojure
(ns my.workflow
  (:require [clojure.spec.alpha :as s]
            [skein.api.current.alpha :as current]
            [skein.api.patterns.alpha :as patterns]))

(s/def ::title string?)
(s/def ::task-input (s/keys :req-un [::title]))

(defn task-pattern [{:keys [input]}]
  [{:ref 'impl
    :title (:title input)
    :attributes {:owner "agent"}}
   {:ref 'review
    :title (str "Review: " (:title input))
    :attributes {:kind "review"}
    :edges [{:type "depends-on" :to 'impl}]}])

(defn install! []
  (patterns/register-pattern! (current/runtime) 'task 'my.workflow/task-pattern ::task-input))
```

CLI callers can discover registered patterns, inspect the input contract, and invoke the pattern with exactly one JSON value on stdin:

```sh
strand --workspace "$workspace" pattern list
strand --workspace "$workspace" pattern explain task
printf '{"title":"Implement review flow"}\n' | strand --workspace "$workspace" weave --pattern task

```

`pattern list` and `pattern explain <name>` are the write-definition discovery pair, parallel to `query list` / `query explain <name>` for read definitions. Application stays on `weave --pattern <name>`.

The pattern function runs inside the weaver and receives `{:input input}`. Its return value must be the same batch vector shape accepted by Skein's batch primitive: strand maps with optional `:ref` and `:edges`. Symbolic refs are transient to the batch and are never durable ids. Input spec failure, malformed batch output, missing refs, invalid durable targets, cycles, and database errors fail loudly and leave no partial batch writes.

`weave --pattern` is the CLI-safe, named, spec-checked, create-only front door over the same transactional batch engine as REPL-only `skein.api.batch.alpha/apply!`. Raw batch is the trusted loading-dock door: it can create, update, burn, and upsert edges, so it remains a Clojure config/REPL workflow instead of a public CLI command.

Like queries and views, patterns are weaver-lifetime runtime state. Register them from startup config if they should always exist after restart or reload.

## Views and graph helpers

Skein ships built-in privileged alpha namespaces for trusted runtime transformations. They are source-visible helper namespaces from the Skein checkout/classpath, not user/community spools that need `spools.edn` approval:

```clojure
(require '[skein.api.graph.alpha :as graph]
         '[skein.api.views.alpha :as views])
```

Graph helpers include operations such as query id selection, strand hydration by ids, ancestor-root traversal, subgraph expansion, and burn-by-id helpers.

Views let you register named read-only transformations backed by weaver-loadable function symbols. A view name is a simple unqualified name; the function symbol must be fully qualified and loadable in the weaver runtime.

```clojure
(ns my.workflow
  (:require [skein.api.graph.alpha :as graph]
            [skein.api.views.alpha :as views]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as api]))

(defn owned-view [{:keys [params]}]
  (let [rt (current/runtime)
        ids (graph/query-ids! rt 'owned params)]
    {:ids ids
     :strands (graph/strands-by-ids rt ids)}))

(defn install! []
  (let [rt (current/runtime)]
    (api/register-query! rt 'owned [:= [:attr :owner] "ct"])
    (views/register-view! rt 'owned-view 'my.workflow/owned-view)
    {:installed true}))
```

Call a registered view from trusted Clojure, usually the live weaver REPL:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.views.alpha :as views])
(views/view! (current/runtime) 'owned-view {})
```

For scripts, use `mill weaver repl --stdin`:

```sh
printf "(do (require '[skein.api.current.alpha :as current] '[skein.api.views.alpha :as views]) (views/view! (current/runtime) 'owned-view {}))\n" \
  | mill weaver repl --stdin --workspace "$workspace"
```

There is no public `strand view` CLI command; view registration and invocation are trusted config/REPL workflows. A view returns whatever serializable Clojure data your function returns. The `{:ids ... :strands ...}` shape above is a convention, not a required schema.

Like queries, views are weaver-lifetime runtime state. Register them from startup config if they should always exist after restart or reload. View functions should be read-only; mutating workflows such as updates, burns, or cleanup helpers should be ordinary trusted functions, not views.

## Events

Skein ships `skein.api.events.alpha` for trusted config and live REPL workflows that need to react to strand mutations. There are no public JSON socket or `strand` CLI commands for event registration.

Register handlers from startup-loaded code or weaver-loadable spools:

```clojure
(ns my.workflow
  (:require [skein.api.current.alpha :as current]
            [skein.api.events.alpha :as events]))

(defn cleanup-temporary! [event]
  ;; Handler receives one event map and can call trusted Skein helpers/APIs.
  (when (= :strand/updated (:event/type event))
    ;; your workspace-specific cleanup here
    nil))

(defn install! []
  (events/register! (current/runtime)
                    :my/cleanup-temporary
                    #{:strand/updated}
                    'my.workflow/cleanup-temporary!
                    {:purpose :cleanup}))
```

Handlers are selected by explicit event-type filters such as `:strand/added`, `:strand/updated`, and `:strand/burned`. Registration uses a stable key and a fully qualified function symbol resolvable in the weaver JVM; duplicate keys replace prior handlers for reload workflows.

Event dispatch is asynchronous after successful mutations. Handler exceptions do not roll back the mutation; inspect bounded failure state from trusted Clojure:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.events.alpha :as events])
(events/handlers (current/runtime))
(events/recent-failures (current/runtime))
```

Event handler state is weaver-lifetime runtime state. Register handlers from `init.clj` or an installed spool if they should exist after startup or reload.

## Scheduler (no-poller wakeups)

The default answer for time-based work is still pull: stamp a `wake-at` attribute on a strand and let a view or query surface it to whatever already polls the graph. That keeps timing in ordinary, inspectable strand data. Reach for the scheduler only for the **no-poller** case — when something must proactively happen at instant `T` and there is no client polling to trigger it.

`skein.api.scheduler.alpha` is a blessed explicit-runtime namespace for that case. A wake is keyed by a stable caller key, an absolute `java.time.Instant`, a fully qualified handler symbol, and an optional JSON-encodable payload; the weaver persists it in dedicated weaver-owned tables (never as a strand), re-arms pending wakes across startup and trusted reload, and dispatches due handlers through the same serialized async lane as post-commit events. Delivery is at-least-once, so handlers must be idempotent. There is no mutating `strand schedule` CLI: scheduling is a trusted REPL/config/API surface only.

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.scheduler.alpha :as scheduler])

(defn remind! [{:keys [runtime key payload]}]
  ;; Handler receives one context map and runs on the shared mutation lane.
  ;; It may call trusted Skein APIs; its return value is ignored.
  nil)

(let [rt (current/runtime)]
  (scheduler/schedule! rt {:key "nightly-sweep"
                           :wake-at (.plusSeconds (java.time.Instant/now) 3600)
                           :handler 'my.workflow/remind!
                           :payload {:scope "temporary"}})
  (scheduler/pending rt)          ; => data-first pending wakes, earliest first
  (first (scheduler/pending rt))  ; => the earliest pending wake, or nil
  (scheduler/recent-fires rt)
  (scheduler/recent-failures rt)
  (scheduler/cancel! rt "nightly-sweep"))
```

Core stays minimal: no cron, recurrence, retry/backoff, jitter, or DST policy. A handler that wants to run again schedules its own next wake. See the Weaver Runtime spec (`SPEC-004.P10d`) and REPL API spec (`SPEC-003.P4a`) for the full contract.

## Fail loudly

Skein intentionally fails loudly instead of guessing. Expect errors for malformed config, unsupported fields, missing weavers, stale metadata, invalid edge targets, cycles, unknown queries, missing spools, and bad runtime code.

This is by design: the system is flexible because attributes and user code are open-ended, so surprising states should be visible and fixable rather than silently papered over.

## Practical bootstrap

Install from a checkout, start mill, and create a repo-local workspace:

```sh
make install
mill start
mill init
mill weaver start
```

For experiments, use a disposable workspace:

```sh
workspace=$(mktemp -d)
mill init --workspace "$workspace"
mill weaver start --workspace "$workspace"
```

In another terminal:

```sh
strand --workspace "$workspace" add "Sketch workflow" --attr owner=agent
strand --workspace "$workspace" ready
```

Stop when finished:

```sh
mill weaver stop --workspace "$workspace"
```

## Spec index

Use this guide for orientation. Use the specs when you need exact behavior, contracts, or implementation boundaries.

### Strand model

Spec: [`devflow/specs/strand-model.md`](../devflow/specs/strand-model.md)

Covers:

- durable strand fields;
- `state` lifecycle values;
- burn deletion;
- JSON attributes;
- relation names and declared acyclic relations;
- readiness semantics;
- queryable fields.

Read this when you need to know what data exists, how lifecycle state works, how `depends-on` works, or what belongs in attributes instead of core fields.

### CLI surface

Spec: [`devflow/specs/cli.md`](../devflow/specs/cli.md)

Covers:

- supported `strand` commands and flags;
- workspace selection;
- `config.json` format;
- JSON-only public output;
- CLI failure behavior;
- `mill init` bootstrap behavior;
- weaver lifecycle commands;
- what the CLI intentionally does not support.

Read this when scripting `strand`, debugging CLI behavior, or deciding whether a workflow belongs in the CLI versus config/REPL code.

### REPL API

Spec: [`devflow/specs/repl-api.md`](../devflow/specs/repl-api.md)

Covers:

- live weaver REPL functions;
- `mill weaver repl --stdin` behavior;
- query registration and execution;
- `skein.api.runtime.alpha` loader/config helpers;
- graph, view, event, and explicit batch helper namespaces;
- runtime spool workspace activation.

Read this when writing trusted Clojure forms, config code, local spools, or custom query/view workflows.

### Weaver runtime

Spec: [`devflow/specs/daemon-runtime.md`](../devflow/specs/daemon-runtime.md)

Covers:

- weaver process model;
- config/state/data workspace selection;
- runtime metadata and socket discovery;
- JSON socket and nREPL transports;
- weaver API boundaries;
- startup config loading;
- named query registry behavior;
- runtime spool workspace model;
- graph/view runtime primitives;
- trusted event handler runtime and helper contracts.

Read this when debugging weaver startup, metadata, transports, runtime state, spool loading, or multi-workspace behavior.
