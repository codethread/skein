# REPL API

**Document ID:** `SPEC-003`
**Status:** Implemented
**Last Updated:** 2026-07-01
**Code:** `src/skein/repl.clj`, `src/skein/batch/alpha.clj`

## SPEC-003.P1 Purpose

The REPL API gives coding agents and human developers a compact interactive Clojure interface over the stripped strand surface and the selected weaver workspace.

## SPEC-003.P2 Interface

Helpers in `skein.repl`:

```clojure
connect!
init!
strand!
update!
strand
supersede!
burn!
burn-by-ids!
defquery!
load-queries!
queries
query-explain
query
strands
ready
declare-acyclic-relation!
acyclic-relations
defpattern!
patterns
pattern
pattern-explain
weave!
```

## SPEC-003.P3 Contracts

- **SPEC-003.C1:** `connect!` selects one active weaver connection by Skein workspace for explicit Clojure client/test workflows. It requires an explicit selected workspace and optional state metadata supplied directly by standalone Clojure/test helpers. It never accepts a database path and no longer silently falls back to an XDG global workspace. Default `strand weaver repl` does not call `connect!`.
- **SPEC-003.C2:** `strand weaver repl` requires a running `mill`, asks it to resolve the selected workspace, verify that workspace's weaver is running, and return nREPL metadata, then attaches the user's terminal to the selected weaver nREPL endpoint. Mill does not proxy nREPL. Any launched attach client is transport/UI only: user forms are evaluated in the weaver JVM, not in a separate local runtime or through the fixed API bridge.
- **SPEC-003.C3:** `strand weaver repl --stdin` attaches to the selected running weaver nREPL, reads and evaluates top-level stdin forms in the weaver JVM in order, prints one direct normal Clojure result per form, and exits non-zero on read, eval, or transport failure. Callers that want one machine-readable payload should wrap work in one top-level `do` or `let`.
- **SPEC-003.C4:** Helpers that need a weaver use `@skein.weaver.runtime/current-runtime` when evaluated inside the active weaver JVM. Outside an active weaver process, they fail before connection with remediation that points to `strand weaver repl` or `connect!`; explicit connected helper/client workflows route through the selected workspace after `connect!`. Weaver/transport failures surface loudly as Clojure exceptions.
- **SPEC-003.C5:** `init!` is a trusted idempotent helper for explicit schema initialization/testing. Normal CLI setup does not require calling it because weaver startup prepares empty stores.
- **SPEC-003.C6:** `strand!` creates a strand and returns the created row. Supported arities include a title alone, title with attributes, and title with options containing optional `:state` and `:attributes`.
- **SPEC-003.C7:** `update!` accepts a strand id and patch map with optional `:title`, `:state`, `:attributes`, and `:edges`. Generic update accepts `active|closed`; `replaced` is reserved for supersession. Other lifecycle keys are not core strand fields.
- **SPEC-003.C8:** `:edges` are maps with `:type`, `:to`, and optional `:attributes`; each edge is written from the updated strand to `:to`. Edge `:type` values are open relation names matching `[a-z0-9][a-z0-9._/-]*`.
- **SPEC-003.C8a:** `supersede!` accepts `(supersede! old-id replacement-id)`, delegates to the weaver supersession operation, stores `replacement --supersedes--> old`, marks the old strand `replaced`, rewires incoming `depends-on` edges, and returns the normalized supersession result.
- **SPEC-003.C8b:** `burn!` and `burn-by-ids!` physically delete strands and incident edges through weaver burn primitives. Missing ids fail loudly.
- **SPEC-003.C9:** `defquery!` registers a named query expression or parameterized query map in the active weaver's in-memory query registry.
- **SPEC-003.C10:** `load-queries!` reads one EDN map of query names to query definitions and merges it into the active weaver's in-memory query registry.
- **SPEC-003.C11:** `queries` returns the active weaver's in-memory query registry. `query-explain` accepts a simple symbol, keyword, or string query name, resolves it against the active weaver's in-memory query registry, and returns serializable caller guidance with the same core fields as CLI `query explain`: canonical name, declared params, referenced params, the effective where expression, the normalized definition, exact EDN form strings, and a short invocation summary. Missing names fail loudly with the existing `query/not-found` behavior including available names. Explicit connected-client workflows route `query-explain` through the fixed-form client operation table.
- **SPEC-003.C12:** Query registry contents last only for the active weaver lifetime; reload trusted config or call `defquery!` / `load-queries!` again after weaver restart.
- **SPEC-003.C13:** `query` returns strands matching an ad hoc query definition or weaver-registered query name, with optional runtime parameters.
- **SPEC-003.C13a:** Query predicates include direct edge-existence forms `[:edge/out relation target-query]` and `[:edge/in relation source-query]`. `relation` is a valid relation-name string or a `[:param :name]` reference resolving to one. Endpoint queries are strand-local and fail loudly if they contain nested edge predicates.
- **SPEC-003.C14:** `strand`, `strands`, `query`, and `ready` return rows with JSON-bearing columns normalized to Clojure values and with the `state` lifecycle field.
- **SPEC-003.C15:** `ready` returns active strands whose direct `depends-on` dependencies are not active and may be further filtered by an ad hoc or registered query. This is equivalent to `[:and [:= :state "active"] [:not [:edge/out "depends-on" [:= :state "active"]]]]`.
- **SPEC-003.C15a:** `declare-acyclic-relation!` declares a valid relation name acyclic in durable storage and is idempotent. It fails loudly if edges of that relation already exist. `acyclic-relations` lists declared acyclic relation names.
- **SPEC-003.C16:** Blessed spool-workspace helpers live in explicit `skein.runtime.alpha`, not in the preloaded `skein.repl` helper namespace.
- **SPEC-003.C17:** `skein.runtime.alpha` exposes approved spool config helpers, approved-local-root sync helpers, resilient module activation with `use!`, and weaver-lifetime sync/use introspection.
- **SPEC-003.C18:** `skein.runtime.alpha` helpers execute against the active `current-runtime` when called inside the live weaver JVM. In explicit connected client/test workflows, they route to the selected weaver workspace after `connect!`.
- **SPEC-003.C19:** `skein.runtime.alpha` is the documented spool-workspace path, but trusted users may require lower-level namespaces or read raw SQLite when they accept compatibility cost.
- **SPEC-003.C20:** `defpattern!` registers a simple pattern name, optional non-blank doc string, fully qualified function symbol, and input spec name in the active weaver's in-memory pattern registry. Supported arities are `(defpattern! name fn-sym input-spec)` and `(defpattern! name doc fn-sym input-spec)`. Duplicate registration replaces the prior entry. The same operations are available through the blessed `skein.patterns.alpha` namespace for trusted startup config, activated spools, and connected REPL workflows.
- **SPEC-003.C21:** `patterns`, `pattern`, and `pattern-explain` inspect active weaver pattern state. Missing pattern lookup fails loudly. Explanations return serializable caller guidance based on the registered spec.
- **SPEC-003.C22:** `weave!` validates input against the registered spec, calls the registered function with `{:input input}`, requires the result to be a valid batch strand vector, and creates the batch atomically through the weaver.

## SPEC-003.P4 Runtime transformation helpers

Skein ships blessed source-visible runtime transformation namespaces for trusted config, live weaver REPL, and explicit connected client workflows:

- `skein.graph.alpha` exposes `(query-ids! query params)`, `(burn-by-id! id)`, `(burn-by-ids! ids)`, `(strands-by-ids ids)`, `(ancestor-root-ids seed-ids)`, `(ancestor-root-ids seed-ids opts)`, `(subgraph root-ids)`, and `(subgraph root-ids opts)`. Traversal opts may include `:type` for the declared acyclic relation to walk and default to `"parent-of"`; annotation relations fail loudly. `ancestor-root-ids` also preserves `:where`/`:params` filtering.
- `skein.views.alpha` exposes `(register-view! name fn-sym)`, `(view! name params)`, and `(views)`. View registration accepts a simple view name and a fully qualified function symbol, not an arbitrary client-side function value.
- `skein.patterns.alpha` exposes `(register-pattern! name fn-sym input-spec)`, `(register-pattern! name doc fn-sym input-spec)`, `(patterns)`, `(pattern name)`, `(explain name)`, and `(weave! name input)`. Pattern functions are weaver-loadable function symbols called with `{:input input}` after input spec validation.
- `skein.events.alpha` exposes `(register! key types fn-sym)`, `(register! key types fn-sym metadata)`, `(unregister! key)`, `(handlers)`, and `(recent-failures)`.
- `skein.hooks.alpha` exposes `(register! key types fn-sym)`, `(register! key types fn-sym opts)`, `(unregister! key)`, and `(hooks)`. Hook keys are stable keywords, symbols, or non-blank strings; hook type sets are non-empty keyword sets; function symbols are fully qualified and weaver-resolvable; `opts` may include `:order` and data-first metadata. Registration replaces by key, `hooks` returns deterministic data-first entries, validation hooks return normally or throw, and transform hooks return `{:hook/value replacement}`.
- `skein.batch.alpha` exposes `(apply! payload)` for transactional batch graph mutation payloads with `:refs`, `:strands`, `:edges`, and `:burn`. It returns normalized Clojure data from the weaver operation, including final refs, created rows, updated before/after rows, burned rows, and edge outcomes, without a JSON envelope.

Helpers execute weaver-side when called from `init.clj`, activated runtime spools, or the live weaver REPL. Explicit connected client users who want to register new view, pattern, event handler, or hook functions should place them in weaver-loadable config/spool code and register their symbols. View, pattern, event, and hook registrations are weaver-lifetime runtime state unless user config reloads them on startup.

User config may require `skein.graph.alpha`, `skein.patterns.alpha`, `skein.views.alpha`, `skein.events.alpha`, `skein.hooks.alpha`, and `skein.batch.alpha` so users can inspect and extend the blessed path. These built-in namespaces come from the Skein checkout on the weaver classpath; they do not require `spools.edn` approval.

Hook functions receive one context map and run synchronously at the lifecycle gates specified by the Weaver Runtime contract. They may reject by throwing; only explicit transform hook families may replace values. Hook registration, unregistration, and introspection are trusted Clojure workflows and are not public JSON socket operations.

Event handlers receive one event map and may perform trusted side effects, including calling Skein APIs. They are dispatched asynchronously after successful mutation commits; handler return values are ignored. `(events/recent-failures)` returns bounded, data-first failure records for handler exceptions. Handler exceptions do not fail the already-committed mutation.

## SPEC-003.P5 Runtime spool workspace helpers

`skein.runtime.alpha` is the blessed alpha namespace for trusted config, live weaver REPL, and explicit connected client spool workspace workflows. It is explicit and is not preloaded into `skein.repl`. Loader/config helpers do not live under `skein.spools.*`; that namespace family is reserved for authorable spools and examples.

Approved spool config is the effective overlay of `spools.edn` and `spools.local.edn` in the selected workspace. Both files use the same MVP EDN grammar: exactly one top-level key, `:spools`, whose value is a map from symbol spool coordinates to maps containing exactly one required key, `:local/root`, a non-blank string path. Unknown top-level keys, non-symbol coordinates, missing `:spools` in a present file, non-map entries, unknown per-lib keys, and missing/non-string `:local/root` fail loudly as structural config errors. Missing files contribute no spools. When both files define the same coordinate, the `spools.local.edn` entry replaces the `spools.edn` entry.

Relative `:local/root` values resolve against selected workspace; absolute roots are accepted as explicit user-approved paths; leading `~` and `~/` expand to the user home directory. Normalized approved config returns entries shaped as `{lib-symbol {:local/root original-path :root canonical-path :source {:kind :shared|:local :file path}}}`. Per-spool missing or unreadable local roots are not structural config errors; `(runtime-alpha/sync!)` records them as failed sync outcomes so optional module activation can skip without aborting weaver startup.

Helpers include:

- `(runtime-alpha/approved)` returns normalized approved config.
- `(runtime-alpha/sync!)` uses Clojure runtime dependency tooling to add approved local roots and returns structured results for loaded, already-available, and failed spools.
- `(runtime-alpha/syncs)` returns weaver-lifetime approved-spool sync state.
- `(runtime-alpha/reload!)` clears weaver-lifetime approved-spool sync state, module-use state, named queries, views, patterns, lifecycle hooks, event handlers, queued events, and recent event failures, then reloads selected workspace startup files in order (`init.clj`, then `init.local.clj`) inside the active weaver and returns loaded file metadata plus final return values. Missing startup files are skipped; present failing files throw with file context. Event dispatch resumes after the fully layered config loads. Reload does not unload already-loaded Clojure namespaces or vars.
- `(runtime-alpha/use! key opts)` records one weaver-lifetime module-use attempt under keyword `key`; duplicate keys replace prior state for reload workflows.
- `(runtime-alpha/uses)` and `(runtime-alpha/use key)` expose weaver-lifetime module-use state.

`use!` options identify exactly one load target with `:ns` for weaver-side namespace loading or `:file` for selected workspace-relative weaver-side `load-file`; `:file` must be relative and must resolve within the selected workspace. For `:ns`, the weaver first searches synced local-root classpath entries from each root's `deps.edn :paths` (defaulting to `["src"]`) and `load-file`s the namespace source using Clojure's hyphen-to-underscore path mapping; if no synced source exists it falls back to ordinary `require`. Options may include `:spools`, a vector or set of symbol spool coordinate keys that must be approved and available before target loading; `:after`, a vector of prior loaded `use!` keys; `:call`, a fully qualified zero-arity function symbol to resolve and call after successful load; and `:required? true` for strict load/call failure behavior.

Malformed `use!` options always throw. Unmet `:spools` requirements record and return `{:status :skipped ...}` before target loading, with reasons including `:not-approved`, `:not-synced`, or `:sync-failed` when known. Unmet `:after` requirements record and return `{:status :skipped ...}` with reason `:missing-after`. Load or call exceptions record and return `{:status :failed ...}` by default; `:required? true` rethrows after recording. Raw `require` remains the strict fail-fast path for required config.

Maven/remote dependency coordinates, version-range matching, alternate approved-spool config files, source fetching, and direct explicit-client `require` of newly synced weaver spools are outside the MVP contract.

## SPEC-003.P6 Example spool init

Selected workspace startup files (`init.clj`, then `init.local.clj`) may sync approved local roots and activate optional modules:

```clojure
(require '[skein.runtime.alpha :as runtime-alpha])

(runtime-alpha/sync!)
(runtime-alpha/use! :my/module
  {:ns 'my.module.alpha
   :spools #{'my/module}
   :call 'my.module.alpha/install!})
```

A selected workspace `spools.edn` approves local roots:

```clojure
{:spools {my/module {:local/root "spools/my-module"}}}
```

## SPEC-003.P7 Non-goals

The REPL API does not expose old helper names such as `task!`, `task`, or `tasks`, or bespoke helpers such as `depends!`, `edge!`, `done!`, `by-attr`, `deps`, `blocking`, or `graph`. Those are either covered by `update!` or the generic query/API helpers.

The REPL API does not add CLI package authoring commands, package installation, source fetching, or plugin-directory loading.
