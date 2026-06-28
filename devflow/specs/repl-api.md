# REPL API

**Document ID:** `SPEC-003`
**Status:** Implemented
**Last Updated:** 2026-06-28
**Code:** `src/skein/repl.clj`, `src/skein/batch/alpha.clj`

## SPEC-003.P1 Purpose

The REPL API gives coding agents and human developers a compact interactive Clojure interface over the stripped strand surface and the selected weaver world.

## SPEC-003.P2 Interface

Helpers in `skein.repl`:

```clojure
connect!
init!
strand!
update!
strand
burn!
burn-by-ids!
defquery!
load-queries!
queries
query
strands
ready
defpattern!
patterns
pattern
pattern-explain
weave!
```

## SPEC-003.P3 Contracts

- **SPEC-003.C1:** `connect!` selects one active weaver connection by Skein world. With no arguments it selects the default weaver world; with a config-dir argument it selects that explicit weaver world. It never accepts a database path.
- **SPEC-003.C2:** `strand weaver repl` preloads `skein.repl`, calls `connect!` for the selected weaver world, and presents the prompt.
- **SPEC-003.C3:** `strand weaver repl --stdin` uses the same preloaded, connected helper context, reads forms from stdin, evaluates them in order, prints one direct normal Clojure result per top-level form, and exits. Callers that want one machine-readable payload should wrap work in one top-level `do` or `let`.
- **SPEC-003.C4:** Helpers that need a weaver fail before connection with remediation that points to `strand weaver repl` or `connect!`; weaver/transport failures surface loudly as Clojure exceptions.
- **SPEC-003.C5:** `init!` initializes the active weaver store schema.
- **SPEC-003.C6:** `strand!` creates a strand and returns the created row. Supported arities include a title alone, title with attributes, and title with options containing optional `:active` and `:attributes`.
- **SPEC-003.C7:** `update!` accepts a strand id and patch map with optional `:title`, `:active`, `:attributes`, and `:edges`. It does not accept removed core lifecycle keys such as `:status` or `:final_at`.
- **SPEC-003.C8:** `:edges` are maps with `:type`, `:to`, and optional `:attributes`; each edge is written from the updated strand to `:to`.
- **SPEC-003.C8a:** `burn!` and `burn-by-ids!` physically delete strands and incident edges through weaver burn primitives. Missing ids fail loudly.
- **SPEC-003.C9:** `defquery!` registers a named query expression or parameterized query map in the active weaver's in-memory query registry.
- **SPEC-003.C10:** `load-queries!` reads one EDN map of query names to query definitions and merges it into the active weaver's in-memory query registry.
- **SPEC-003.C11:** `queries` returns the active weaver's in-memory query registry.
- **SPEC-003.C12:** Query registry contents last only for the active weaver lifetime; reload trusted config or call `defquery!` / `load-queries!` again after weaver restart.
- **SPEC-003.C13:** `query` returns strands matching an ad hoc query definition or weaver-registered query name, with optional runtime parameters.
- **SPEC-003.C14:** `strand`, `strands`, `query`, and `ready` return rows with JSON-bearing columns normalized to Clojure values and with `active` and `inactive_at` lifecycle fields.
- **SPEC-003.C15:** `ready` returns active strands whose direct `depends-on` dependencies are not active and may be further filtered by an ad hoc or registered query.
- **SPEC-003.C16:** Blessed library-workspace helpers live in explicit `skein.libs.alpha`, not in the preloaded `skein.repl` helper namespace.
- **SPEC-003.C17:** `skein.libs.alpha` exposes approved library config helpers, approved-local-root sync helpers, resilient module activation with `use!`, and weaver-lifetime sync/use introspection.
- **SPEC-003.C18:** `skein.libs.alpha` helpers route to the selected weaver world when called from connected REPL clients. Direct `require` in a connected helper REPL remains local to the helper JVM.
- **SPEC-003.C19:** `skein.libs.alpha` is the documented library-workspace path, but trusted users may require lower-level namespaces or read raw SQLite when they accept compatibility cost.
- **SPEC-003.C20:** `defpattern!` registers a simple pattern name, optional non-blank doc string, fully qualified function symbol, and input spec name in the active weaver's in-memory pattern registry. Supported arities are `(defpattern! name fn-sym input-spec)` and `(defpattern! name doc fn-sym input-spec)`. Duplicate registration replaces the prior entry. The same operations are available through the blessed `skein.patterns.alpha` namespace for trusted startup config, activated libraries, and connected REPL workflows.
- **SPEC-003.C21:** `patterns`, `pattern`, and `pattern-explain` inspect active weaver pattern state. Missing pattern lookup fails loudly. Explanations return serializable caller guidance based on the registered spec.
- **SPEC-003.C22:** `weave!` validates input against the registered spec, calls the registered function with `{:input input}`, requires the result to be a valid batch strand vector, and creates the batch atomically through the weaver.

## SPEC-003.P4 Runtime transformation helpers

Skein ships blessed source-visible runtime transformation namespaces for trusted config and connected REPL workflows:

- `skein.graph.alpha` exposes `(query-ids! query params)`, `(burn-by-id! id)`, `(burn-by-ids! ids)`, `(strands-by-ids ids)`, `(ancestor-root-ids seed-ids opts)`, and `(subgraph root-ids)`. These helpers route to weaver operations for set-oriented query id selection, raw deletion, strand hydration by ids, parent-of feature-root traversal, and parent-of DAG/subgraph expansion.
- `skein.views.alpha` exposes `(register-view! name fn-sym)`, `(view! name params)`, and `(views)`. View registration accepts a simple view name and a fully qualified function symbol, not an arbitrary client-side function value.
- `skein.patterns.alpha` exposes `(register-pattern! name fn-sym input-spec)`, `(register-pattern! name doc fn-sym input-spec)`, `(patterns)`, `(pattern name)`, `(explain name)`, and `(weave! name input)`. Pattern functions are weaver-loadable function symbols called with `{:input input}` after input spec validation.
- `skein.events.alpha` exposes `(register! key types fn-sym)`, `(register! key types fn-sym metadata)`, `(unregister! key)`, `(handlers)`, and `(recent-failures)`. Event registration accepts a stable handler key, a non-empty set of event type keywords, and a fully qualified function symbol resolvable in the weaver JVM.
- `skein.batch.alpha` exposes `(apply! payload)` for transactional batch graph mutation payloads with `:refs`, `:strands`, `:edges`, and `:burn`. It returns normalized Clojure data from the weaver operation, including final refs, created rows, updated before/after rows, burned rows, and edge outcomes, without a JSON envelope.

Helpers execute weaver-side when called from `init.clj` or activated runtime libraries, and route to the selected weaver world when called from connected REPL clients. Connected helper REPL users who want to register new view, pattern, or event handler functions should place them in weaver-loadable config/library code and register their symbols. View, pattern, and event registrations are weaver-lifetime runtime state unless user config reloads them on startup.

User config may require `skein.graph.alpha`, `skein.patterns.alpha`, `skein.views.alpha`, `skein.events.alpha`, and `skein.batch.alpha` so users can inspect and extend the blessed path. These built-in namespaces come from the Skein checkout on the weaver classpath; they do not require `libs.edn` approval.

Event handlers receive one event map and may perform trusted side effects, including calling Skein APIs. They are dispatched asynchronously after successful mutation commits; handler return values are ignored. `(events/recent-failures)` returns bounded, data-first failure records for handler exceptions. Handler exceptions do not fail the already-committed mutation.

## SPEC-003.P5 Runtime library workspace helpers

`skein.libs.alpha` is the blessed alpha namespace for trusted config and connected REPL library workspace workflows. It is explicit and is not preloaded into `skein.repl`.

Approved library config lives in `libs.edn` in the selected config-dir. The MVP config is an EDN map with exactly one top-level key, `:libs`. `:libs` is a map from symbol library coordinates to maps containing exactly one required key, `:local/root`, whose value is a non-blank string path. Unknown top-level keys, non-symbol coordinates, missing `:libs`, non-map entries, unknown per-lib keys, and missing/non-string `:local/root` fail loudly as structural config errors.

Relative `:local/root` values resolve against selected config-dir; absolute roots are accepted as explicit user-approved paths; leading `~` and `~/` expand to the user home directory. Normalized approved config returns entries shaped as `{lib-symbol {:local/root original-path :root canonical-path}}`. Per-library missing or unreadable local roots are not structural config errors; `(libs/sync!)` records them as failed sync outcomes so optional module activation can skip without aborting weaver startup.

Helpers include:

- `(libs/approved)` returns normalized approved config.
- `(libs/sync!)` uses Clojure runtime dependency tooling to add approved local roots and returns structured results for loaded, already-available, and failed libraries.
- `(libs/syncs)` returns weaver-lifetime approved-library sync state.
- `(libs/reload!)` clears weaver-lifetime approved-library sync state, module-use state, named queries, views, patterns, event handlers, queued events, and recent event failures, then reloads selected config-dir `init.clj` inside the active weaver and returns its file, status, and final form result. Missing `init.clj` fails loudly. Reload does not unload already-loaded Clojure namespaces or vars.
- `(libs/use! key opts)` records one weaver-lifetime module-use attempt under keyword `key`; duplicate keys replace prior state for reload workflows.
- `(libs/uses)` and `(libs/use key)` expose weaver-lifetime module-use state.

`use!` options identify exactly one load target with `:ns` for weaver-side namespace loading or `:file` for selected-config-dir-relative weaver-side `load-file`; `:file` must be relative and must resolve within the selected config-dir. For `:ns`, the weaver first searches synced local-root classpath entries from each root's `deps.edn :paths` (defaulting to `["src"]`) and `load-file`s the namespace source using Clojure's hyphen-to-underscore path mapping; if no synced source exists it falls back to ordinary `require`. Options may include `:libs`, a vector or set of symbol library coordinate keys that must be approved and available before target loading; `:after`, a vector of prior loaded `use!` keys; `:call`, a fully qualified zero-arity function symbol to resolve and call after successful load; and `:required? true` for strict load/call failure behavior.

Malformed `use!` options always throw. Unmet `:libs` requirements record and return `{:status :skipped ...}` before target loading, with reasons including `:not-approved`, `:not-synced`, or `:sync-failed` when known. Unmet `:after` requirements record and return `{:status :skipped ...}` with reason `:missing-after`. Load or call exceptions record and return `{:status :failed ...}` by default; `:required? true` rethrows after recording. Raw `require` remains the strict fail-fast path for required config.

Maven/remote dependency coordinates, version-range matching, alternate approved-library config files, source fetching, and direct connected-helper-REPL `require` of newly synced weaver libraries are outside the MVP contract.

## SPEC-003.P6 Example library init

A selected config-dir `init.clj` may sync approved local roots and activate optional modules:

```clojure
(require '[skein.libs.alpha :as libs])

(libs/sync!)
(libs/use! :my/module
  {:ns 'my.module.alpha
   :libs #{'my/module}
   :call 'my.module.alpha/install!})
```

A selected config-dir `libs.edn` approves local roots:

```clojure
{:libs {my/module {:local/root "libs/my-module"}}}
```

## SPEC-003.P7 Non-goals

The REPL API does not expose old helper names such as `task!`, `task`, or `tasks`, or bespoke helpers such as `depends!`, `edge!`, `done!`, `by-attr`, `deps`, `blocking`, or `graph`. Those are either covered by `update!` or the generic query/API helpers.

The REPL API does not add CLI package authoring commands, package installation, source fetching, or plugin-directory loading.
