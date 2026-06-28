# Runtime transformations

**Document ID:** `PRD-001`
**Related:** [Devflow Philosophy](../PHILOSOPHY.md), [Strand Model](../specs/strand-model.md), [CLI Surface](../specs/cli.md), [REPL API](../specs/repl-api.md), [Weaver Runtime](../specs/daemon-runtime.md)

Skein treats persisted strands and edges as durable facts, and treats richer behavior as runtime transformation over those facts.

SQLite remains the long-term persistence layer for the strand graph. The weaver is the live application core. Trusted Clojure config, runtime libraries, and REPL workflows define runtime behavior. The selected config-dir is the user-owned runtime library workspace: `init.clj` boots trusted code, `libs.edn` approves local roots, and helper namespaces expose blessed workflows.

## Goals

- Store durable facts only: strands, attributes, and edges belong in SQLite; views, filters, scores, and workflows belong in weaver runtime/config/REPL.
- Expose set-oriented graph helpers for query-id selection, batch hydration, ancestor-root traversal, and subgraph expansion.
- Expose read-only weaver-memory views by name for trusted Clojure workflows.
- Keep the CLI thin: it may consume named weaver behavior, but it does not author views or load libraries.

## Runtime helper namespaces

Skein ships source-visible helper namespaces:

- `skein.libs.alpha` for approved local-root sync and module activation.
- `skein.graph.alpha` for graph/query helpers such as `query-ids!`, `strands-by-ids`, `ancestor-root-ids`, and `subgraph`.
- `skein.views.alpha` for `register-view!`, `view!`, and `views`.

These helpers run in trusted startup config or connected REPL workflows.

## Example: repository-owned ready strands

A user tracks repository ownership in strand attributes:

```clojure
(require '[skein.weaver.api :as api])

(api/register-query!
 'ready-for-repo
 '{:where [:and
           [:= [:attr :repo] [:param :repo]]
           [:= :state "active"]]})
```

The CLI can consume the registered query while the same weaver is running:

```sh
strand ready --query ready-for-repo --param repo=skein
```

## Example: feature view

Assume a world uses user attributes such as `example_category="feature"` and `parent-of` edges from feature roots to child work. Startup config can register a view:

```clojure
(ns my.skein.views
  (:require [skein.graph.alpha :as graph]
            [skein.views.alpha :as views]
            [skein.weaver.api :as api]))

(api/register-query!
 'active-owned
 '{:where [:and
           [:= [:attr :repo] [:param :repo]]
           [:= :state "active"]]})

(defn active-feature-dags [{:keys [params]}]
  (let [seed-ids (graph/query-ids! 'active-owned params)
        feature-root-ids (graph/ancestor-root-ids seed-ids {:where [:= [:attr :example_category] "feature"]})
        graph-data (graph/subgraph feature-root-ids)]
    {:features (graph/strands-by-ids feature-root-ids)
     :graph graph-data}))

(views/register-view! 'active-feature-dags 'my.skein.views/active-feature-dags)
```

The flagship shape remains: query ids -> graph expansion -> batch hydration -> Clojure shaping -> optional named view invocation.

## Non-goals

- No CLI package manager or view-authoring commands.
- No untrusted plugin sandbox.
- No durable storage for view definitions in this PRD.
- No core category, outcome, or workflow taxonomy; worlds store those concepts in attributes when they need them.
