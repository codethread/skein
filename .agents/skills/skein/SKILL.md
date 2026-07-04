---
name: skein
description: >
  Explains how to extend the .skein/ config as a user. Use when asked add patterns, workflows, `strand` ops or general weaver commands
---

Read `<source-dir>/docs/skein.md` where `source-dir` is the Skein source checkout for the selected world.

Explore via `mill weaver repl` or `mill weaver repl --stdin`.

## Live REPL hygiene for shared weaver sessions

`mill weaver repl` and `mill weaver repl --stdin` evaluate inside the live weaver JVM, usually in the shared `skein.repl` namespace. Agent exploratory requires and scratch defs mutate that namespace, so use names that are easy to identify and clean up.

Conventions:

- Prefer `:as` aliases over `:refer` in shared REPL work.
- Prefix aliases and scratch vars with an owner/session prefix, e.g. `ct-`, `agent-abc-`, or a task slug.
- Clean aliases with `ns-unalias` and scratch vars with `ns-unmap` when done.
- Avoid unprefixed scratch vars like `result`, `x`, `data`, or bare referred helpers in `skein.repl`.

Example:

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
