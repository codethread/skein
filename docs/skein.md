# Skein user reference

Skein is a local strand graph for agents and humans. It gives you a durable SQLite-backed graph of work, notes, dependencies, and workflow state, while keeping the command-line surface small and machine-readable.

The short version:

- A **strand** is one record in your graph.
- A **weaver** is the long-lived local process that owns the database and runtime state.
- The **`strand` CLI** is a thin JSON control surface for scripts and agents.
- The **REPL** is the trusted, high-power surface for customization and exploration.
- Your workflow model lives mostly in custom **attributes** and your own config/library code.

This guide is written for Skein users and for agents working inside a user's Skein world. Maintainer-facing contracts live in [`devflow/specs/`](../devflow/specs/); see the [spec index](#spec-index) at the end.

## Mental model

Skein is daemon-core-first behind a small router. You start `mill` once, ask it to start a weaver for a selected world, then clients send requests through `mill` to that weaver.

```text
selected config-dir (normally repo .skein)
  config.json      -> local, gitignored Skein source checkout path
  init.clj         -> shared trusted startup code loaded by the weaver
  init.local.clj   -> personal startup overlay loaded after init.clj
  libs.edn         -> shared approved local library roots
  libs.local.edn   -> personal approved-library overlay
  libs/            -> optional local libraries

running weaver
  owns SQLite storage
  owns named queries
  owns weave pattern registrations
  owns view registrations
  owns event handler registrations
  owns synced library state

clients
  strand CLI       -> mill -> weaver JSON socket, small safe command set
  weaver REPL      -> mill resolution -> connected Clojure helper context
```

Different config dirs are different worlds. Use `--config-dir <dir>` when you want an isolated world for experiments, agent work, or tests.

## Worlds and config dirs

The ordinary world is repo-local. Without `--config-dir`, `mill` resolves the current Git worktree root and uses that repo's `.skein` directory as the selected config-dir. Outside Git, no-flag commands fail loudly. `strand init` creates or completes `.skein` at the Git root and fails loudly outside Git:

```sh
strand init
```

When installed with `make install` from a Skein checkout, `strand init` can write the source path automatically. If you installed another way, pass the checkout explicitly or set `SKEIN_SOURCE`:

```sh
strand init --source /path/to/skein-src
# or
SKEIN_SOURCE=/path/to/skein-src strand init
```

A world can also be selected explicitly with:

```sh
strand --config-dir /path/to/world ...
```

For explicit worlds, `/path/to/world` is the config workspace. Runtime state,
metadata, sockets, and data are owned by mill under Skein's XDG state root for
the selected config identity.

The important file is `config.json`:

```json
{
  "configFormat": "alpha",
  "source": "/absolute/path/to/skein/source"
}
```

`source` tells the CLI where to launch the Clojure weaver and REPL from. It should point at a Skein checkout containing `deps.edn`.

## Agent guidance files

From a Skein source checkout, `make install` installs the Go CLIs (`strand` and `mill`) and records the checkout as the default weaver source. After that, use the CLIs directly: `mill start`, `strand init`, and `strand weaver start`.

`strand init` is the normal repo bootstrap path. It creates or completes the repo `.skein` world, writes local `config.json` when source resolution succeeds, and leaves shared config files ready to commit. It does not run `git init` or initialize database storage; weaver startup prepares storage.

User-facing Skein documentation lives in the source checkout under `docs/`; the canonical user reference is `docs/skein.md`.

## Weaver

The weaver is the application core. It is a long-lived local Clojure process that owns:

- the SQLite database connection;
- strand creation, update, query, readiness, and burn operations;
- the in-memory named-query registry;
- the in-memory weave-pattern registry;
- the in-memory view registry;
- the in-memory event handler registry and async dispatch worker;
- the approved-library sync state;
- runtime module activation state.

Start mill once, then start the selected world's weaver:

```sh
mill start
strand --config-dir "$world" weaver start
```

Stop it:

```sh
strand --config-dir "$world" weaver stop
```

Check it:

```sh
strand --config-dir "$world" weaver status
```

The weaver exposes two local transports:

- a Unix socket used by the Go `strand` CLI;
- an nREPL endpoint used by the connected helper REPL.

A selected config-dir may have one running weaver. Runtime registries are weaver-lifetime state, so named queries, weave patterns, views, and synced library state should be loaded from startup config if you want them to appear after every restart.

## CLI

The `strand` CLI is intentionally small. It is for scripts, low-friction agent use, and JSON automation. It does not evaluate rich Clojure forms or mutate runtime extension state.

Common commands:

```sh
strand --config-dir "$world" init
strand --config-dir "$world" add "Write docs" --attr owner=agent --attr area=docs
strand --config-dir "$world" update <id> --state closed
strand --config-dir "$world" update <id> --edge depends-on:<other-id>
strand --config-dir "$world" show <id>
strand --config-dir "$world" list
strand --config-dir "$world" ready
strand --config-dir "$world" burn <id>
strand --config-dir "$world" pattern explain <pattern-name>
printf '{"title":"New work"}\n' | strand --config-dir "$world" weave --pattern <pattern-name>
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
strand --config-dir "$world" update <id> --state closed --attr outcome=done
```

Burn only when you want deletion:

```sh
strand --config-dir "$world" burn <id>
```

## Edges and readiness

Edges connect strands with open relation names such as `depends-on`, `parent-of`, `supersedes`, or annotation relations like `references`.

A `depends-on` edge from `A` to `B` means: `A` is blocked while `B` is active.

```sh
strand --config-dir "$world" update "$docs" --edge depends-on:"$design"
```

`ready` returns active strands whose direct `depends-on` targets are inactive or absent:

```sh
strand --config-dir "$world" ready
```

Self-edges fail for every relation. Declared acyclic relations such as `depends-on`, `parent-of`, and `supersedes` reject relation-local cycles; annotation relations may form non-self cycles.

## Attributes are the extension point

Skein's core is deliberately small. Most workflow meaning hangs off `attributes`.

Examples:

```sh
strand --config-dir "$world" add "Draft release notes" \
  --attr owner=agent \
  --attr project=skein \
  --attr kind=doc \
  --attr priority=high
```

Your world can decide what attributes mean. For example:

- `owner=agent` can mean an agent should pick it up;
- `kind=feature` can identify feature roots;
- `outcome=done` can record completion reason after deactivation;
- `temporary=true` can identify rows your own tooling treats as temporary;
- `external.issue=123` can link to another system if your tooling understands it.

Skein stores attributes as JSON text. CLI input is simple string pairs; `--attr temporary=true` stores the string `"true"`, not a JSON boolean. Trusted Clojure workflows can write richer JSON-compatible values.

Because attributes are userland, your own config and libraries should define the conventions for your world. Prefer documenting those conventions in source-controlled docs or in your library docs. Attribute names and cleanup behavior are userland choices, not Skein core.

## Queries

Queries can be registered in weaver memory, then consumed by the REPL or CLI.

From the connected REPL, `defquery!` registers a query for the current weaver lifetime only:

```clojure
(defquery! 'agent-docs
  '[:and
    [:= [:attr :owner] "agent"]
    [:= [:attr :area] "docs"]])
```

Then from the CLI:

```sh
strand --config-dir "$world" list --query agent-docs
strand --config-dir "$world" ready --query agent-docs
```

Named query registries are not durable by themselves. If you want a query after every weaver restart, register it from startup-loaded code.

For a simple persistent query, put it directly in `init.clj`:

```clojure
(require '[skein.libs.alpha :as libs]
         '[skein.weaver.api :as api])

(libs/sync!)
(api/register-query! 'mine [:= [:attr :owner] "ct"])
```

For a world that already activates a local library with `libs/use!`, follow that existing pattern instead: add the `api/register-query!` call to the library's `install!` function so reload/startup installs everything from one place.

Defining a Clojure var that contains query data is not the same as registering a named query. A local var can be passed to graph helpers from your own code, but `strand list --query mine` only works after `mine` has been registered in the weaver's named-query registry.

`strand list --query mine` returns all matching strands unless you also pass a state filter. Use `strand list --query mine --state active` when you only want active matches. `strand ready --query mine` always applies readiness semantics, so returned strands are active and unblocked.

## REPL

The REPL is the trusted, high-power surface. Use it for richer inspection, custom query authoring, config reloads, and calling your own library code.

Open a connected REPL:

```sh
strand --config-dir "$world" weaver repl
```

Useful forms:

```clojure
(def id (:id (strand! "Explore workflow" {:owner "ct" :kind "spike"})))
(strand id)
(update! id {:state "closed" :attributes {:outcome "captured"}})
(strands)
(ready)
```

Script a connected helper REPL with stdin:

```sh
printf '(ready)\n' | strand --config-dir "$world" weaver repl --stdin
```

The REPL helper namespace includes common strand functions, but library-workspace helpers are explicit. Require them when needed:

```clojure
(require '[skein.libs.alpha :as libs])
(libs/reload!)
```

## Startup config

`strand init` bootstraps missing workspace files in the selected config-dir without overwriting existing files. It does not initialize database storage; weaver startup prepares storage for the selected world.

For the ordinary repo-local `.skein` world, it creates or ensures:

- `.skein/config.json` only if absent and source resolution succeeds;
- `.skein/libs/` directory;
- `.skein/libs.edn` only if absent, with `{:libs {}}`;
- `.skein/init.clj` only if absent, with the default below;
- `.skein/.gitignore` only if absent, ignoring local config overlays such as `config.json`, `init.local.clj`, and `libs.local.edn`.

Explicit `--config-dir` standalone worlds bootstrap the selected config directory directly. Existing `config.json`, `libs.edn`, `init.clj`, and `.gitignore` are preserved.

The generated `init.clj` is intentionally small:

```clojure
;; init.clj
(require '[skein.libs.alpha :as libs])

(libs/sync!)
```

The weaver loads startup files in order: `init.clj`, then `init.local.clj`. Missing files are skipped; present failing files fail loudly with file context. Use startup-loaded code to register queries, weave patterns, load approved libraries, register views, and install conventions for your world. Simple worlds can put shared registrations directly in `init.clj` and personal overlays in gitignored `init.local.clj`; reusable or larger worlds should keep `init.clj` minimal and install behavior from a local library.

A direct `init.clj` query registration can look like this:

```clojure
(require '[skein.libs.alpha :as libs]
         '[skein.weaver.api :as api])

(libs/sync!)
(api/register-query! 'mine [:= [:attr :owner] "ct"])
```

Use reload during development:

```clojure
(require '[skein.libs.alpha :as libs])
(libs/reload!)
```

Reload clears weaver-lifetime library sync state, module-use state, named queries, weave patterns, views, lifecycle hooks, event handlers, queued events, and recent event failures, then reloads `init.clj` followed by `init.local.clj`. Missing files are skipped; present failures fail loudly.

## Authoring your own library code

Skein treats runtime extensions as trusted Clojure code. A common layout is:

```text
world/
  config.json
  init.clj
  libs.edn
  libs/
    my-workflow/
      deps.edn
      src/my/workflow.clj
```

Approve the local library root in `libs.edn`:

```clojure
{:libs {my/workflow {:local/root "libs/my-workflow"}}}
```

Relative `:local/root` values resolve against the selected config-dir. Absolute paths are accepted as explicit user-approved paths, and `~` expands to your home directory.

Create a minimal `deps.edn` in the library root:

```clojure
{:paths ["src"]}
```

If `:paths` is omitted, Skein's namespace loading defaults to `["src"]`.

Implement the library:

```clojure
(ns my.workflow
  (:require [skein.weaver.api :as api]))

(defn install! []
  (api/register-query! 'mine [:= [:attr :owner] "ct"])
  {:my.workflow/installed true})
```

Activate it from `init.clj`:

```clojure
(require '[skein.libs.alpha :as libs])

(libs/sync!)
(libs/use! :my/workflow
  {:ns 'my.workflow
   :libs #{'my/workflow}
   :call 'my.workflow/install!})
```

Key points:

- `libs.edn` is approval. It says which local roots the weaver may load.
- `libs/sync!` makes approved roots available to the weaver.
- `libs/use!` activates one module and records whether it loaded, skipped, or failed.
- `:call` must name a fully qualified zero-argument function.
- Direct `require` from `strand weaver repl` is not the supported activation path for newly synced weaver libraries. The connected helper REPL is a separate client process; use `libs/use!` or `libs/reload!` so loading and installation happen inside the weaver runtime.
- Extension code runs with weaver authority. Only load trusted code.
- There is no per-module isolation or unload guarantee. Restart the weaver for a clean runtime if needed.

## Weave patterns

Weave patterns are trusted owner-defined transformations that turn a JSON-like input payload into an atomic batch of new strands and edges. They are useful when agents should submit intent and your world should decide the graph shape.

Pattern registration lives in trusted Clojure config or libraries, not in the public CLI. A pattern has a simple name, a fully qualified weaver-loadable function symbol, and a `clojure.spec` input contract.

```clojure
(ns my.workflow
  (:require [clojure.spec.alpha :as s]
            [skein.patterns.alpha :as patterns]))

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
  (patterns/register-pattern! 'task 'my.workflow/task-pattern ::task-input))
```

CLI callers can inspect the input contract and invoke the pattern with exactly one JSON value on stdin:

```sh
strand --config-dir "$world" pattern explain task
printf '{"title":"Implement review flow"}\n' | strand --config-dir "$world" weave --pattern task
```

The pattern function runs inside the weaver and receives `{:input input}`. Its return value must be the same batch vector shape accepted by Skein's batch primitive: strand maps with optional `:ref` and `:edges`. Symbolic refs are transient to the batch and are never durable ids. Input spec failure, malformed batch output, missing refs, invalid durable targets, cycles, and database errors fail loudly and leave no partial batch writes.

Like queries and views, patterns are weaver-lifetime runtime state. Register them from startup config if they should always exist after restart or reload.

## Views and graph helpers

Skein ships built-in alpha namespaces for trusted runtime transformations:

```clojure
(require '[skein.graph.alpha :as graph]
         '[skein.views.alpha :as views])
```

Graph helpers include operations such as query id selection, strand hydration by ids, ancestor-root traversal, subgraph expansion, and burn-by-id helpers.

Views let you register named read-only transformations backed by weaver-loadable function symbols. A view name is a simple unqualified name; the function symbol must be fully qualified and loadable in the weaver runtime.

```clojure
(ns my.workflow
  (:require [skein.graph.alpha :as graph]
            [skein.views.alpha :as views]
            [skein.weaver.api :as api]))

(defn owned-view [{:keys [params]}]
  (let [ids (graph/query-ids! 'owned params)]
    {:ids ids
     :strands (graph/strands-by-ids ids)}))

(defn install! []
  (api/register-query! 'owned [:= [:attr :owner] "ct"])
  (views/register-view! 'owned-view 'my.workflow/owned-view)
  {:installed true})
```

Call a registered view from trusted Clojure, usually a connected REPL:

```clojure
(require '[skein.views.alpha :as views])
(views/view! 'owned-view {})
```

For scripts, use `weaver repl --stdin`:

```sh
printf "(do (require '[skein.views.alpha :as views]) (views/view! 'owned-view {}))\n" \
  | strand --config-dir "$world" weaver repl --stdin
```

There is no public `strand view` CLI command; view registration and invocation are trusted config/REPL workflows. A view returns whatever serializable Clojure data your function returns. The `{:ids ... :strands ...}` shape above is a convention, not a required schema.

Like queries, views are weaver-lifetime runtime state. Register them from startup config if they should always exist after restart or reload. View functions should be read-only; mutating workflows such as updates, burns, or cleanup helpers should be ordinary trusted functions, not views.

## Events

Skein ships `skein.events.alpha` for trusted config and connected REPL workflows that need to react to strand mutations. There are no public JSON socket or `strand` CLI commands for event registration.

Register handlers from startup-loaded code or weaver-loadable libraries:

```clojure
(ns my.workflow
  (:require [skein.events.alpha :as events]))

(defn cleanup-temporary! [event]
  ;; Handler receives one event map and can call trusted Skein helpers/APIs.
  (when (= :strand/updated (:event/type event))
    ;; your world-specific cleanup here
    nil))

(defn install! []
  (events/register! :my/cleanup-temporary
                    #{:strand/updated}
                    'my.workflow/cleanup-temporary!
                    {:purpose :cleanup}))
```

Handlers are selected by explicit event-type filters such as `:strand/added`, `:strand/updated`, and `:strand/burned`. Registration uses a stable key and a fully qualified function symbol resolvable in the weaver JVM; duplicate keys replace prior handlers for reload workflows.

Event dispatch is asynchronous after successful mutations. Handler exceptions do not roll back the mutation; inspect bounded failure state from trusted Clojure:

```clojure
(require '[skein.events.alpha :as events])
(events/handlers)
(events/recent-failures)
```

Event handler state is weaver-lifetime runtime state. Register handlers from `init.clj` or an installed library if they should exist after startup or reload.

## Fail loudly

Skein intentionally fails loudly instead of guessing. Expect errors for malformed config, unsupported fields, missing weavers, stale metadata, invalid edge targets, cycles, unknown queries, missing libraries, and bad runtime code.

This is by design: the system is flexible because attributes and user code are open-ended, so surprising states should be visible and fixable rather than silently papered over.

## Practical bootstrap

Install from a checkout, start mill, and create a repo-local world:

```sh
make install
mill start
strand init
strand weaver start
```

For experiments, use a disposable world:

```sh
world=$(mktemp -d)
strand --config-dir "$world" init
strand --config-dir "$world" weaver start
```

In another terminal:

```sh
strand --config-dir "$world" add "Sketch workflow" --attr owner=agent
strand --config-dir "$world" ready
```

Stop when finished:

```sh
strand --config-dir "$world" weaver stop
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
- config-dir selection;
- `config.json` format;
- JSON-only public output;
- CLI failure behavior;
- `strand init` bootstrap behavior;
- weaver lifecycle commands;
- what the CLI intentionally does not support.

Read this when scripting `strand`, debugging CLI behavior, or deciding whether a workflow belongs in the CLI versus config/REPL code.

### REPL API

Spec: [`devflow/specs/repl-api.md`](../devflow/specs/repl-api.md)

Covers:

- connected helper REPL functions;
- `strand weaver repl --stdin` behavior;
- query registration and execution;
- `skein.libs.alpha` helpers;
- graph, view, event, and explicit batch helper namespaces;
- runtime library workspace activation.

Read this when writing trusted Clojure forms, config code, local libraries, or custom query/view workflows.

### Weaver runtime

Spec: [`devflow/specs/daemon-runtime.md`](../devflow/specs/daemon-runtime.md)

Covers:

- weaver process model;
- config/state/data world selection;
- runtime metadata and socket discovery;
- JSON socket and nREPL transports;
- weaver API boundaries;
- startup config loading;
- named query registry behavior;
- runtime library workspace model;
- graph/view runtime primitives;
- trusted event handler runtime and helper contracts.

Read this when debugging weaver startup, metadata, transports, runtime state, library loading, or multi-world behavior.
