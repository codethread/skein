# Runtime Transformation Primitives Plan

**Document ID:** `RTP-PLAN-001`
**Status:** Draft
**Last Updated:** 2026-06-26
**Proposal:** [proposal.md](./proposal.md)
**PRD:** [Runtime Transformations PRD](../../prd/runtime-transformations.md)
**Spec deltas:** [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md), [cli.delta.md](./specs/cli.delta.md)
**Prerequisite shipped:** [Runtime Library Workspace](../../archive/26-06-26__runtime-library-workspace/)

## RTP-PLAN-001.P1 Goal and scope

Build the first blessed runtime transformation layer on top of the shipped runtime library workspace. The MVP should provide source-visible trusted Clojure helpers for set-oriented query and graph composition, plus a daemon-memory read-only view registry. It should also update fresh `todo init` config so users see and can edit the blessed runtime-helper workflow immediately.

The feature should prove the PRD flagship shape: query active seed ids, walk to feature roots through `parent-of`, expand feature DAGs, batch hydrate tasks, and shape results in Clojure.

## RTP-PLAN-001.P2 Approach

- **RTP-PLAN-001.A1:** Build inside the Atom repo as blessed alpha namespaces under `src/atom`, not as an external user library and not as a revival of the old plugin-directory API.
- **RTP-PLAN-001.A2:** Keep base daemon core focused on primitives that need storage/runtime access. Put ergonomic composition in `atom.*.alpha` libraries.
- **RTP-PLAN-001.A3:** Use a small two-namespace split: `atom.graph.alpha` for set-oriented query id, task hydration, and task/edge graph primitives; `atom.views.alpha` for daemon-memory view registry helpers. Do not add `atom.queries.alpha` in the MVP because the only query helper is an id-producing graph-pipeline primitive.
- **RTP-PLAN-001.A4:** Keep all APIs set-oriented. Avoid helper shapes that encourage row-at-a-time N+1 calls.
- **RTP-PLAN-001.A5:** Add daemon API operations only for stable primitive boundaries: query ids, tasks by ids, graph expansion, and view registry operations.
- **RTP-PLAN-001.A6:** Keep view functions read-only by convention and contract. They may call query/graph/hydration helpers and arbitrary Clojure shaping code, but this feature should not design mutating workflows.
- **RTP-PLAN-001.A7:** Register views by fully qualified function symbol, not by passing arbitrary function values through connected-client transports. This keeps registration serializable and daemon-resolvable from config-dir libraries.
- **RTP-PLAN-001.A8:** Update `todo init` generated `init.clj` so fresh worlds require the built-in transformation helper namespace(s) as an editable template. Existing files must not be overwritten. Do not use `atom.libs.alpha/use!` merely to load shipped namespaces unless an explicit install side effect is added.
- **RTP-PLAN-001.A9:** Adapt the useful install-style pattern from `/Users/ct/.config/atom/libs/ct-config/src/ct/atom/config.clj`, but do not ship user-specific queries such as owner `ct` defaults.

## RTP-PLAN-001.P3 Affected areas

| ID | Area | Impact |
| --- | --- | --- |
| RTP-PLAN-001.AA1 | `src/atom/*/alpha.clj` | Add blessed transformation helper namespace(s). |
| RTP-PLAN-001.AA2 | `src/todo/daemon/api.clj` | Add primitive daemon operations and view registry operations. |
| RTP-PLAN-001.AA3 | `src/todo/daemon/runtime.clj` | Add daemon-lifetime view registry state if needed. |
| RTP-PLAN-001.AA4 | `src/todo/db.clj` | Add SQL-backed id selection, batch hydration, and recursive graph/subgraph helpers where persistence belongs. |
| RTP-PLAN-001.AA5 | `src/todo/client.clj` | Add trusted nREPL/client operation routing for connected helper workflows. |
| RTP-PLAN-001.AA6 | Go CLI config/init code | Update generated `init.clj` defaults only; no new command surface. |
| RTP-PLAN-001.AA7 | Tests/smoke/docs | Cover daemon-side helpers, connected REPL routing, generated init files, and example workflows. |
| RTP-PLAN-001.AA8 | `devflow/specs/*` | Promote shipped contracts after validation. |

## RTP-PLAN-001.P4 API sketch

The MVP API surface is frozen for this feature as follows.

```clojure
(require '[atom.graph.alpha :as graph]
         '[atom.views.alpha :as views])

(graph/query-ids! 'active-work-in-repo {:repo "atom" :since "2026-06-01"})
(graph/tasks-by-ids ["task-a" "task-b"])
(graph/ancestor-root-ids ["leaf-id"] {:where [:= [:attr :kind] "feature"]})
(graph/subgraph ["feature-root-id"])

;; In daemon-loadable config/library code:
(defn active-feature-dags [{:keys [params]}]
  ...)

(views/register-view! 'active-feature-dags 'my.views/active-feature-dags)
(views/view! 'active-feature-dags {:repo "atom"})
(views/views)
```

Default generated `init.clj` should stay an editable template, not hidden activation ceremony:

```clojure
(require '[atom.libs.alpha :as libs]
         '[atom.graph.alpha :as graph]
         '[atom.views.alpha :as views])

(libs/sync!)
```

## RTP-PLAN-001.P5 Implementation phases

### RTP-PLAN-001.PH1 API and namespace finalization

The MVP uses a two-namespace split: `atom.graph.alpha` for query id, hydration, and graph helpers; `atom.views.alpha` for view registry helpers. Freeze exact helper names, daemon op names, arities, and return shapes before broad implementation. Keep the first API small enough to explain in one getting-started section.

Minimum set primitive contracts to preserve:

- `query-ids!` returns a vector of ids ordered by the same stable task ordering as `list` query results.
- `tasks-by-ids` returns normalized task rows in first-occurrence input order, collapses duplicate ids, returns `[]` for empty input, and fails loudly for missing ids.

Minimum graph contracts to preserve:

- `ancestor-root-ids` traverses upward over `parent-of` edges where parent `from_task_id` points to child `to_task_id`.
- Empty seed input returns `[]`; any missing seed id fails loudly.
- Seed ids are depth-zero candidates.
- With `:where`, return topmost matching ancestors on every path; without `:where`, return graph roots with no `parent-of` parent.
- Results are deduplicated and stable-sorted by id.
- `subgraph` returns `{:root-ids [...] :tasks [...] :edges [...]}`; root ids preserve first-occurrence input order with duplicates collapsed, tasks are normalized and ordered by stable task id, and edges are internal `parent-of` edges ordered by `from_task_id`, `to_task_id`, then edge type. Empty root input returns an empty result map; any missing root id fails loudly.

Minimum view contracts to preserve:

- view names use the same simple-name canonicalization as query names;
- `register-view!` accepts a name and fully qualified function symbol and replaces duplicates;
- `view!` resolves and invokes the daemon-side function with `{:params params}`;
- `views` returns introspectable serializable entries, not function objects.

Final MVP helper and daemon operation signatures:

- `(graph/query-ids! query params)` -> daemon op `:query-ids`
- `(graph/tasks-by-ids ids)` -> daemon op `:tasks-by-ids`
- `(graph/ancestor-root-ids seed-ids opts)` -> daemon op `:ancestor-root-ids`
- `(graph/subgraph root-ids)` -> daemon op `:subgraph`
- `(views/register-view! name fn-sym)` -> daemon op `:register-view!`
- `(views/view! name params)` -> daemon op `:view!`
- `(views/views)` -> daemon op `:views`

Graph helpers are `parent-of` primitives in the MVP. There is no `:edge-type` option; future edge types require a later explicit contract.

### RTP-PLAN-001.PH2 DB-backed set primitives

Add persistence-layer helpers for id-oriented query execution, batch task hydration by ids, parent-of ancestor root traversal, and parent-of descendant/subgraph expansion. Use SQL/recursive CTEs where appropriate. Preserve normalized row behavior at daemon API boundaries.

### RTP-PLAN-001.PH3 Daemon and client operations

Expose the primitives through `todo.daemon.api` and trusted `todo.client` nREPL routing. Add explicit entries to the client operation allowlist for every new daemon op. Add daemon-lifetime read-only view registry state and operations. Keep JSON socket allowlist unchanged unless a later explicit decision adds CLI view invocation.

### RTP-PLAN-001.PH4 Blessed alpha libraries

Implement `atom.*.alpha` helper namespace(s) that work both daemon-side and from connected helper REPLs, following `atom.libs.alpha` routing style. Include install-style helpers only when they add clear default value.

### RTP-PLAN-001.PH5 Init/bootstrap defaults

Update `todo init` generated config-dir files so fresh worlds require the shipped transformation helper namespace(s) through normal trusted startup code. Ensure `todo init` remains non-overwriting and existing user config is untouched. Do not add `use!` calls for built-in shipped namespaces unless an install function with explicit side effects is part of this feature.

### RTP-PLAN-001.PH6 Docs, examples, and validation

Update README/getting-started/CONTRIBUTING as needed to show the new workflow. Add smoke coverage for a disposable world using generated or equivalent init code, registering a view/query, and invoking helpers from `todo daemon repl --stdin`.

## RTP-PLAN-001.P6 Validation strategy

- **RTP-PLAN-001.V1:** Clojure unit tests for query id selection and batch hydration, including empty id lists and missing ids.
- **RTP-PLAN-001.V2:** Clojure unit tests for `parent-of` ancestor root traversal and descendant/subgraph expansion on representative DAGs.
- **RTP-PLAN-001.V3:** Clojure unit tests for read-only view registry registration, replacement/reload behavior, invocation, missing names, and connected helper routing.
- **RTP-PLAN-001.V4:** Go CLI tests for generated `todo init` startup files and no new public command surface.
- **RTP-PLAN-001.V5:** Smoke test with a disposable config-dir world proving generated/init activated helpers can be used through `todo daemon repl --stdin`.
- **RTP-PLAN-001.V6:** Full validation: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

## RTP-PLAN-001.P7 Risks and open questions

- **RTP-PLAN-001.R1:** Namespace split may be premature. Mitigation: decide in PH1 and prefer fewer namespaces if unclear.
- **RTP-PLAN-001.R2:** Graph traversal semantics can become domain-heavy. Mitigation: MVP only targets `parent-of` feature DAG traversal and leaves richer graph policies to later libraries.
- **RTP-PLAN-001.R3:** View registry could tempt broad CLI invocation before output contracts exist. Mitigation: keep JSON socket allowlist unchanged in this feature.
- **RTP-PLAN-001.R4:** Generated init changes could surprise existing users if overwritten. Mitigation: `todo init` remains create-only; docs explain how to copy the defaults into existing config.
- **RTP-PLAN-001.R5:** User helper prototypes may contain personal policy. Mitigation: adapt patterns, not owner-specific defaults.

## RTP-PLAN-001.P8 Task context

- **RTP-PLAN-001.TC1:** Runtime library workspace is shipped and canonical. Do not create a parallel plugin or package model.
- **RTP-PLAN-001.TC2:** Public CLI stays thin and JSON-only. New transformation authoring/debugging belongs in Clojure config/REPL/libraries.
- **RTP-PLAN-001.TC3:** The first useful product flow is feature-DAG inspection, not general graph analytics.
- **RTP-PLAN-001.TC4:** Use `/Users/ct/.config/atom/libs/ct-config/src/ct/atom/config.clj` as a local prototype reference only; do not make tests depend on that path.

## RTP-PLAN-001.P9 Developer Notes

### RTP-PLAN-001.DN1 Initial planning — 2026-06-26

Started from the Runtime Transformations PRD after runtime library workspace shipped. User clarified these helpers should be built inside the Atom repo as blessed alpha helpers, exposed to fresh users through generated `todo init` config, and informed by an existing personal helper library without shipping personal defaults.

### RTP-PLAN-001.DN2 TASK-001 API finalization — 2026-06-26

Finalized the MVP alpha split as two namespaces: `atom.graph.alpha` owns `query-ids!`, `tasks-by-ids`, `ancestor-root-ids`, and `subgraph`; `atom.views.alpha` owns `register-view!`, `view!`, and `views`. The implementation should follow the existing `atom.libs.alpha` daemon-routing style so helpers work daemon-side during trusted startup and from connected helper REPLs. The daemon operation names are frozen as `:query-ids`, `:tasks-by-ids`, `:ancestor-root-ids`, `:subgraph`, `:register-view!`, `:view!`, and `:views`. No `atom.queries.alpha` namespace is planned for the MVP; `query-ids!` remains in `atom.graph.alpha` because it is primarily an id pipeline primitive for graph expansion. `ancestor-root-ids` and `subgraph` are parent-of primitives with no `:edge-type` option; future edge types require a later explicit contract.
