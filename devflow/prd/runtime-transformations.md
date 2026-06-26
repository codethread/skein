# Runtime Transformations PRD

**Document ID:** `PRD-001`
**Status:** Draft
**Last Updated:** 2026-06-25
**Related:** [Devflow Philosophy](../PHILOSOPHY.md), [Task Model](../specs/task-model.md), [CLI Surface](../specs/cli.md), [REPL API](../specs/repl-api.md), [Daemon Runtime](../specs/daemon-runtime.md), [Runtime Library Workspace archive](../archive/26-06-26__runtime-library-workspace/)

## PRD-001.P1 Vision

Atom should treat persisted tasks and edges as durable facts, and treat almost everything else as runtime transformation over those facts.

SQLite remains the long-term persistence layer for the task graph. The daemon is the live application core. Trusted Clojure config, runtime libraries, and REPL workflows define runtime behavior. The selected config-dir is now the user-owned runtime library workspace: `init.clj` boots trusted code, `libs.edn` approves local roots, and `atom.libs.alpha/sync!` / `use!` make libraries available and activate modules in daemon memory. The CLI remains a small convenience and low-privilege invocation surface for common operations and known daemon behavior. Runtime transformations require a running daemon; CLI examples in this document are daemon-mediated calls against the selected config-dir world, not direct SQLite access.

This should feel closer to Emacs than to a stateless command-line utility: start the daemon, load trusted runtime behavior from config-dir libraries, explore and refine in the REPL, and let constrained workers invoke named behavior without needing broad REPL access.

## PRD-001.P2 Problem

Users need increasingly rich ways to inspect and act on task graphs:

- tasks scoped to a repository, feature, owner, time window, or lifecycle state;
- ready work within a larger DAG;
- feature-level views that zoom from a currently active task out to the whole feature DAG;
- custom prioritization, scoring, grouping, or filtering;
- chained workflows where one query feeds graph expansion, which feeds more queries and final userland filtering.

A pure CLI/query-DSL surface would either become too constrained or grow into a second programming language. Raw SQL would be flexible but too low-level, too schema-coupled, and inappropriate for low-privilege workers. Persisting every derived query/view in SQLite would blur durable facts with runtime behavior.

The first runtime transformation model is read-only: named queries and views inspect, select, expand, rank, group, and shape task data. Mutating workflows may be explored later, but they are not part of this PRD's view model.

## PRD-001.P3 Product principles

- **PRD-001.G1:** Store durable facts only: tasks, attributes, and edges belong in SQLite; views, filters, scores, and workflows belong in daemon runtime/config/REPL.
- **PRD-001.G2:** Keep the core robust and small: persistence, query compilation, graph primitives, daemon state, and clear errors.
- **PRD-001.G3:** Let userland explore freely: trusted Clojure code can compose queries, graph traversals, filters, ranking, grouping, and output shapes.
- **PRD-001.G4:** Keep the CLI thin: it invokes common operations and known named daemon behavior, but does not become a runtime loading or extension system.
- **PRD-001.G5:** Prefer set-oriented primitives over row-at-a-time APIs so userland composition remains performant.
- **PRD-001.G6:** Keep data-first contracts where they matter: the EDN query DSL remains the portable, inspectable, SQL-compilable candidate-selection language.

## PRD-001.P4 Conceptual model

Runtime transformations have three layers:

1. **EDN query DSL: candidate selection**
   - SQL-backed.
   - Data-first.
   - Good for status, timestamps, attributes, ids, edge predicates, and other coarse filters.
   - Used to minimize the task set before richer Clojure logic runs.

2. **Graph and batch primitives: set expansion**
   - SQLite-backed where possible, including recursive CTEs for graph traversal.
   - Operate on sets of ids rather than one task at a time.
   - Good for parent/child traversal, ancestor/descendant expansion, subgraph extraction, edge lookup, and batch hydration.

3. **Trusted Clojure views: orchestration and shaping**
   - Daemon-memory runtime functions installed by trusted config-dir libraries or defined in the REPL.
   - Compose queries, graph primitives, and arbitrary Clojure filters.
   - Good for domain-specific views, scoring, grouping, ranking, custom output shapes, and chained workflows.
   - Expected to ship first as blessed `atom.graph.alpha` and `atom.views.alpha` libraries layered on the runtime library workspace, not as public CLI loaders or plugin-directory APIs.

The preferred flow is:

```text
query -> graph/batch expansion -> Clojure filter/shape -> optional further query/expansion -> final result
```

## PRD-001.P5 Example: ready work for one repo

A user tracks repository ownership in task attributes:

```clojure
{:repo "atom"}
```

Trusted config, a config-dir runtime library, or REPL registers a named query:

```clojure
(require '[todo.daemon.api :as todo])

(todo/register-query!
  'ready-for-repo
  {:params [:repo]
   :where [:= [:attr :repo] [:param :repo]]})
```

CLI invokes known daemon behavior:

```sh
todo ready \
  --query ready-for-repo \
  --param repo=atom
```

The built-in `ready` command applies readiness semantics, and the named query narrows by repository. The CLI does not load the query definition; it only invokes a name already present in daemon memory.

## PRD-001.P6 Example: active feature DAGs for one repo

Assume the task graph has feature root tasks and `parent-of` edges from feature roots to child work. A user wants:

1. find active work in repo `X` since time `Y`;
2. walk up to owning feature roots;
3. fan back out to the full feature DAGs;
4. hydrate feature roots and included DAG tasks in batch;
5. shape the final result for an agent or human.

Trusted Clojure using the MVP alpha namespaces should look like:

```clojure
(require '[atom.graph.alpha :as graph]
         '[atom.views.alpha :as views]
         '[todo.daemon.api :as todo])

(todo/register-query!
  'active-work-in-repo
  {:params [:repo :since]
   :where [:and
           [:= [:attr :repo] [:param :repo]]
           [:> :updated_at [:param :since]]
           [:not [:in :status ["done" "failed" "cancelled"]]]]})

(defn active-feature-dags-view [{:keys [params]}]
  (let [seed-ids
        (graph/query-ids! 'active-work-in-repo params)

        feature-root-ids
        (graph/ancestor-root-ids seed-ids {:where [:= [:attr :kind] "feature"]})

        dag
        (graph/subgraph feature-root-ids)]
    {:features (graph/tasks-by-ids feature-root-ids)
     :dag dag}))

(views/register-view! 'active-feature-dags 'my.views/active-feature-dags-view)
```

The exact API is illustrative. The product requirement is the composition pattern: userland Clojure can chain SQL-backed queries, graph expansions, batch hydration, and arbitrary filtering without pulling the whole database into memory unless it explicitly chooses to.

## PRD-001.P7 First buildable slice

The runtime library workspace is already shipped. The first view-oriented slice should build on it rather than inventing another extension mechanism. Use blessed source-visible library namespaces `atom.graph.alpha` and `atom.views.alpha`, required from config-dir `init.clj` as normal shipped Atom namespaces. Use `atom.libs.alpha/use!` for user/community runtime libraries or explicit install side effects, not merely to load built-in namespaces already present on the Atom classpath. Thin daemon API primitives should sit underneath only where core access is required.

The first slice should prove the architecture without implementing every graph helper.

Minimum useful primitive set:

- **PRD-001.MVP1:** daemon-backed named query registration and invocation already established by the query registry work;
- **PRD-001.MVP2:** `query-ids!` for returning candidate ids from a query definition or registered query name;
- **PRD-001.MVP3:** `ancestor-root-ids` over `parent-of` for walking from seed work up to feature roots;
- **PRD-001.MVP4:** `subgraph` over `parent-of` for expanding from feature roots back down to full DAGs;
- **PRD-001.MVP5:** `tasks-by-ids` for batch hydration;
- **PRD-001.MVP6:** a trusted `register-view!` / `view!` path for read-only Clojure views, probably exposed through a blessed runtime library namespace rather than the base `todo.repl` helper surface;
- **PRD-001.MVP7:** module activation through existing `libs.edn`, `libs/sync!`, and `libs/use!` workflows;
- **PRD-001.MVP8:** no CLI view invocation until output contracts are designed, unless a feature explicitly scopes that work.

This slice is enough to validate the flagship shape: query active seed ids -> walk to feature roots -> expand feature DAGs -> hydrate -> Clojure shape. More graph helpers should be added only when a concrete view needs them.

## PRD-001.P8 Core primitive families

Future features should grow small primitives rather than one large view language.

Potential query primitives:

- **PRD-001.Q1:** `query!` — return task rows for a query definition or registered query name.
- **PRD-001.Q2:** `query-ids!` — return only ids for efficient chaining.
- **PRD-001.Q3:** `explain-query` — return SQL fragments/full SQL and bound params for debugging.

Potential batch primitives:

- **PRD-001.B1:** `tasks-by-ids` — hydrate many tasks in one call.
- **PRD-001.B2:** `edges-for` — fetch edges touching a set of ids.
- **PRD-001.B3:** `edges-between` — fetch edges inside a set/subgraph.

Potential graph primitives:

- **PRD-001.GR1:** `parents-of` / `children-of` for one-hop set traversal.
- **PRD-001.GR2:** `ancestors-of` / `descendants-of` for recursive traversal.
- **PRD-001.GR3:** `ancestor-root-ids` for finding feature roots or ownership roots.
- **PRD-001.GR4:** `subgraph` for expanding full DAGs from root ids, returning `{:root-ids [...] :tasks [...] :edges [...]}`.

Potential runtime primitives:

- **PRD-001.R1:** `register-query!` for EDN query definitions.
- **PRD-001.R2:** `register-view!` for trusted Clojure view functions.
- **PRD-001.R3:** `view!` for invoking registered views from trusted code.
- **PRD-001.R4:** library activation through existing `atom.libs.alpha/sync!` and `use!`, not a new plugin/package surface.
- **PRD-001.R5:** CLI view invocation for known registered views, if a future feature defines a stable output contract.

## PRD-001.P9 Performance model

Performance should come from pushing the right work to SQLite without forcing all behavior into SQL.

- **PRD-001.PERF1:** Push coarse selection to the EDN query DSL and SQL compiler.
- **PRD-001.PERF2:** Push graph traversal to SQLite recursive CTEs where practical.
- **PRD-001.PERF3:** Prefer id pipelines and batch hydration over passing full task maps through every stage.
- **PRD-001.PERF4:** Avoid N+1 helper APIs as the primary path; provide set-oriented equivalents.
- **PRD-001.PERF5:** Let userland opt into full scans when useful, but make efficient composition the easy path.

## PRD-001.P10 CLI model

The CLI should consume named daemon behavior rather than define it. All examples assume a matching daemon is already running for the selected config-dir world; task/view commands fail loudly if daemon metadata is missing, stale, or does not match.

Good CLI shape:

```sh
todo ready --query ready-for-repo --param repo=atom
```

Use `todo --config-dir <dir> ...` to select an explicit disposable or alternate world.

Potential future CLI shape, only after view output contracts are designed:

```sh
todo view active-feature-dags --param repo=atom --param since=2026-06-01
```

The CLI should not become the place users load views, define functions, submit raw SQL, or persist runtime behavior.

## PRD-001.P11 Runtime definition lifecycle

Runtime definitions live in trusted Clojure files, approved config-dir libraries, or active REPL sessions, not in SQLite. For repeatable automation, teams should keep canonical query/view definitions in versioned config-dir libraries loaded by daemon startup config. REPL definitions are for exploration and local session work.

A low-privilege worker invoking a named query/view should treat absence as a loud operational failure: either the daemon was started without the expected config, the wrong database/runtime was selected, or the runtime was restarted without reloading definitions.

Names should be stable, simple, and documented near the config or library module that registers them. The daemon registry is authoritative only for the current daemon lifetime.

## PRD-001.P12 Parameter contract

Current CLI params are string-valued `--param key=value` pairs. V1 runtime transformations should assume string params at the CLI boundary and perform any richer coercion inside trusted Clojure views or config-defined wrappers.

EDN query and view APIs used from REPL/config may accept richer Clojure values such as vectors for `:in` parameters. If low-privilege CLI callers need typed values later, that should be a separate feature with an explicit syntax and failure contract.

Examples using values such as `:since` are conceptual unless paired with trusted Clojure code that coerces or supplies the desired type.

## PRD-001.P13 Non-goals

- **PRD-001.NG1:** Do not persist views, Clojure functions, or runtime behavior in SQLite.
- **PRD-001.NG2:** Do not expose raw SQL as the public low-privilege CLI query surface.
- **PRD-001.NG3:** Do not grow the EDN query DSL into a full programming language.
- **PRD-001.NG4:** Do not require every useful transformation to become a durable schema concept.
- **PRD-001.NG5:** Do not optimize for untrusted plugin execution in the initial model.
- **PRD-001.NG6:** Do not revive the superseded plugin-directory public API; runtime transformations should use the shipped runtime library workspace model.

## PRD-001.P14 Open design questions

- **PRD-001.OQ1:** Should named views eventually be invoked through a separate CLI command such as `view`, or should existing commands grow view-aware options?
- **PRD-001.OQ2:** What output contracts should views support for EDN/JSON/human modes?
- **PRD-001.OQ3:** Which graph primitive should be first after the MVP slice proves one recursive expansion path?
- **PRD-001.OQ4:** How should view execution report explain/debug information for maintainers?
- **PRD-001.OQ5:** How should parameter values evolve beyond string-valued CLI params if views or queries need collections, timestamps, or typed values from low-privilege CLI callers?
- **PRD-001.OQ6:** If mutating runtime workflows are ever introduced, what separate safety, authorization, and audit boundaries do they require?

## PRD-001.P15 Success criteria

- **PRD-001.S1:** Users can define useful runtime behavior without changing the durable schema.
- **PRD-001.S2:** Low-privilege CLI workers can invoke known named behavior without REPL access.
- **PRD-001.S3:** Rich graph views can be built by composing small core primitives rather than adding bespoke commands.
- **PRD-001.S4:** Common view pipelines avoid unnecessary full-database scans and N+1 query patterns.
- **PRD-001.S5:** The core remains focused on durable facts, efficient primitive operations, clear contracts, and loud failures.
