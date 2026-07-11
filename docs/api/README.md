# Alpha API index

`skein.api.*.alpha` is the blessed spool-facing API tier: the namespaces trusted config, spools, and REPL workflows build on. Each subnamespace evolves by accretion, so code written against it keeps working within the alpha line. The behavior contracts live in the root specs — [REPL API](../../devflow/specs/repl-api.md), [Weaver Runtime](../../devflow/specs/daemon-runtime.md), and the [alpha surface index](../../devflow/specs/alpha-surface.md) that draws the in-contract line — while the per-namespace pages beside this index are generated per-fn reference from source docstrings (`make api-docs`, never hand-edited).

Two conventions hold across the tier:

- **The runtime is explicit.** Every operational function takes the target weaver runtime as its first argument. Capture it once at a trusted entry point with `skein.api.current.alpha/runtime` and thread it through; nothing else reads ambient state.
- **Registries are weaver-lifetime.** Queries, patterns, views, events, and hooks registered at a REPL vanish on restart. Register from startup-loaded code (`init.clj` or an installed spool) when they should survive.

## Which namespace owns your concern

### Getting a runtime

| Namespace | Reach for it when |
| --- | --- |
| [`current`](./current.api.md) | You are at a trusted in-process entry point and need to capture the active runtime (or probe for one without fabricating it). |
| [`runtime`](./runtime.api.md) | Loader/config workflows: `sync!` approved spool roots, `use!` a module, `reload!` startup config, read `spool-state`, read the runtime clock. |

### Strand data

| Namespace | Reach for it when |
| --- | --- |
| [`weaver`](./weaver.api.md) | Single-strand lifecycle — add, update, show, list, ready, supersede, archive — plus schema init, acyclic-relation declaration, and the CLI op registry. |
| [`graph`](./graph.api.md) | Query-driven reads: the named-query registry, ad hoc and registered query selection, hydration by ids, traversal, adjacency, and burn. |
| [`batch`](./batch.api.md) | Many mutations that must land atomically — the trusted create/update/burn/edge batch engine behind `weave`. |
| [`notes`](./notes.api.md) | Writing or reading strand memory: the cross-spool `note!`/`notes` primitive every writer and reader shares. |

### Reacting and scheduling

| Namespace | Reach for it when |
| --- | --- |
| [`events`](./events.api.md) | Running code *after* a mutation commits: async handler registration, submission, and failure inspection. |
| [`hooks`](./hooks.api.md) | Running code *before* a mutation commits: synchronous lifecycle hooks that can normalize or veto. |
| [`scheduler`](./scheduler.api.md) | Something must happen at instant `T` with no client polling — durable, at-least-once wakes. Prefer a `wake-at` attribute plus a query when a poller already exists. |

### Publishing a surface

| Namespace | Reach for it when |
| --- | --- |
| [`patterns`](./patterns.api.md) | Agents should submit intent and your config decides the graph shape: spec-checked, create-only weave patterns invokable from the CLI. |
| [`views`](./views.api.md) | Registering named read-only projections callable from trusted Clojure. |
| [`cli`](./cli.api.md) | Declaring an op's argv shape as data — the parser and help renderer behind every registered op; never hand-write usage strings. |

### Vocabulary and prose

| Namespace | Reach for it when |
| --- | --- |
| [`vocab`](./vocab.api.md) | Declaring the attribute namespaces and edge types your module owns, so the graph's vocabulary stays discoverable data. |
| [`relations`](./relations.api.md) | Looking up the shipped relation catalog — advisory data, not a storage allowlist. |
| [`format`](./format.api.md) | Authoring long prose as `|`-margin blocks (`fill`, `reflow`) instead of unreadable string literals. |

### Talking to other weavers

| Namespace | Reach for it when |
| --- | --- |
| [`peers`](./peers.api.md) | Discovering and calling local sibling weavers from mill-published runtime metadata. |

Two blessed namespaces live outside `skein.api.*` but inside the same contract tier: `skein.test.alpha` (deterministic-test seams: weaver-world fixtures and clock control) and `skein.userland.alpha` (downstream-only ergonomics that no `skein.*` namespace may require). See [SPEC-005.C2](../../devflow/specs/alpha-surface.md) for the full tier membership.
