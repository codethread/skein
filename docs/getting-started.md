# Getting started after the daemon is running

This guide assumes you have already bootstrapped an Atom config-dir, started the daemon, and can run:

```sh
todo daemon status
```

Atom is primarily a tool for coding agents, so the core interface is structured and composable. The CLI is intentionally small. The REPL is where the system becomes powerful for humans and agents.

If Clojure syntax is unfamiliar, keep [Tiny Clojure crash course for Atom users](./clojure-crash-course.md) open beside this guide.

## 1. Start with the CLI

Initialize task storage once:

```sh
todo init
```

Add a task:

```sh
todo add "Write getting-started docs"
```

List tasks:

```sh
todo list
```

Ask for ready tasks:

```sh
todo ready
```

Add attributes:

```sh
todo add "Review docs" --attr owner=ct --attr area=docs
```

Use JSON when scripting:

```sh
todo --format json list
```

The CLI is useful, but it will feel intentionally underpowered. It does not author rich queries, load runtime libraries, or expose every workflow as a command. That power lives in trusted config and the REPL.

## 2. Open the connected REPL

```sh
todo daemon repl
```

You should see:

```text
todo=>
```

The REPL starts with the base task helpers loaded. It also preloads the `libs` alias for runtime-library workspace helpers. Fresh `todo init` config also shows the blessed transformation helper namespaces `atom.graph.alpha` and `atom.views.alpha` as editable startup imports.

Check your runtime library state:

```clojure
(libs/approved)
(libs/syncs)
(libs/uses)
```

A fresh config usually returns empty structures.

## 3. Work with tasks in the REPL

List all tasks:

```clojure
(tasks)
```

List ready tasks:

```clojure
(ready)
```

Create a task and keep its id in a REPL var:

```clojure
(def t (:id (task! "My first REPL task")))
```

Fetch it:

```clojure
(task t)
```

Mark it done:

```clojure
(update! t {:status "done"})
```

Create a task with attributes:

```clojure
(task! "Owned by ct" {:owner "ct"})
```

## 4. Define a reusable query

Define a named query for your tasks:

```clojure
(defquery! 'mine [:= [:attr :owner] "ct"])
```

Run it in the REPL:

```clojure
(query 'mine)
```

Filter the result further with normal Clojure:

```clojure
(->> (query 'mine)
     (remove #(= "done" (:status %)))
     vec)
```

That means: get my tasks, remove completed ones, return a vector.

## 5. Reuse named queries from the CLI

Named queries live in daemon memory. Once registered in the daemon, the CLI can use them by name:

```sh
todo list --query mine
```

Ready tasks matching the named query:

```sh
todo ready --query mine
```

This is the intended split:

- use the REPL or `init.clj` to define rich runtime behavior;
- use the CLI to invoke already-loaded daemon behavior from scripts and lower-privilege agent workflows.

Named queries disappear when the daemon stops. Put important ones in your config-dir `init.clj` or another local library that your `init.clj` loads.

## 6. Make startup reusable

Your config-dir `init.clj` is trusted startup code. A minimal generated one looks like:

```clojure
(require '[atom.libs.alpha :as libs]
         '[atom.graph.alpha :as graph]
         '[atom.views.alpha :as views])
(libs/sync!)
```

`atom.graph.alpha` and `atom.views.alpha` are built into Atom. They come from the Atom checkout on the daemon classpath; they do not need `libs.edn` approval and should not be loaded with `libs/use!`.

You can add named queries and daemon-memory views there:

```clojure
(ns my.atom.init
  (:require [atom.libs.alpha :as libs]
            [atom.graph.alpha :as graph]
            [atom.views.alpha :as views]
            [skein.weaver.api :as api]))

(libs/sync!)
(api/register-query! 'mine [:= [:attr :owner] "ct"])

(defn mine-view [{:keys [params]}]
  (let [ids (graph/query-ids! 'mine {})]
    {:params params
     :ids ids
     :tasks (graph/tasks-by-ids ids)}))

(views/register-view! 'mine-view 'my.atom.init/mine-view)
```

From a connected REPL, inspect and invoke the registered view:

```clojure
(require '[atom.graph.alpha :as graph]
         '[atom.views.alpha :as views])

(graph/query-ids! 'mine {})
(views/views)
(views/view! 'mine-view {:format "summary"})
```

Restart the daemon after changing `init.clj`:

```sh
todo daemon stop
todo daemon start
```

Then the CLI can immediately use:

```sh
todo list --query mine
```

## 7. Where runtime libraries fit

When your config grows, move user/community code into local Clojure libraries under your config-dir, commonly as Git submodules:

```text
<config-dir>/
├── init.clj
├── libs.edn
└── libs/
    └── my-workflows/
        ├── deps.edn
        └── src/...
```

Approve user/community library roots in `libs.edn`:

```clojure
{:libs {my/workflows {:local/root "libs/my-workflows"}}}
```

Activate user/community libraries from `init.clj`:

```clojure
(require '[atom.libs.alpha :as libs])

(libs/sync!)
(libs/use! :my/workflows
  {:ns 'my.workflows.alpha
   :libs #{'my/workflows}
   :call 'my.workflows.alpha/install!})
```

Built-in `atom.*.alpha` namespaces are different from user/community libraries: they ship with Atom and are required directly from `init.clj` or the REPL, not approved in `libs.edn`.

`use!` records loaded, skipped, and failed modules so you can fix forward:

```clojure
(libs/syncs)
(libs/uses)
```

## 8. Useful REPL helpers

```clojure
(init!)
(task! "Title")
(task! "Title" {:owner "ct"})
(update! task-id {:status "done"})
(task task-id)
(tasks)
(ready)
(defquery! 'mine [:= [:attr :owner] "ct"])
(query 'mine)
(queries)
```

And runtime-library helpers:

```clojure
(libs/approved)
(libs/sync!)
(libs/syncs)
(libs/use! :key {...})
(libs/uses)
(libs/use :key)
```

Transformation helpers:

```clojure
(require '[atom.graph.alpha :as graph]
         '[atom.views.alpha :as views])

(graph/query-ids! 'mine {})
(graph/tasks-by-ids ["task-id"])
(graph/ancestor-root-ids ["leaf-id"] {:where [:= [:attr :kind] "feature"]})
(graph/subgraph ["feature-root-id"])
(views/register-view! 'name 'my.ns/view-fn)
(views/view! 'name {:key "value"})
(views/views)
```
