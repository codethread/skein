# REPL API

**Document ID:** `SPEC-003`
**Status:** Implemented
**Last Updated:** 2026-06-25
**Code:** `src/todo/repl.clj`

## SPEC-003.P1 Purpose

The REPL API gives coding agents and human developers a compact interactive Clojure interface over the stripped task surface and the selected daemon world.

## SPEC-003.P2 Interface

Helpers:

```clojure
connect!
init!
task!
update!
task
defquery!
load-queries!
queries
query
tasks
ready
```

## SPEC-003.P3 Contracts

- **SPEC-003.C1:** `connect!` selects one active daemon connection by daemon world. With no arguments it selects the default daemon world; with a config-dir argument it selects that explicit daemon world. It never accepts a database path.
- **SPEC-003.C2:** `todo daemon repl` preloads the REPL helper namespace, calls `connect!` for the selected daemon world, and presents the prompt.
- **SPEC-003.C3:** `todo daemon repl --stdin` uses the same preloaded, connected helper context, reads forms from stdin, evaluates them in order, prints one direct normal Clojure result per top-level form, and exits. Callers that want one machine-readable payload should wrap work in one top-level `do` or `let`.
- **SPEC-003.C4:** Helpers that need a daemon fail before connection with remediation that points to `todo daemon repl` or `connect!`; daemon/transport failures surface loudly as Clojure exceptions.
- **SPEC-003.C5:** `init!` initializes the active daemon store schema.
- **SPEC-003.C6:** `task!` creates a task and returns the created row. Supported arities are `(task! title)`, `(task! title attributes)`, and `(task! title status attributes)`.
- **SPEC-003.C7:** `update!` accepts a task id and patch map with optional `:title`, `:status`, `:attributes`, and `:edges`.
- **SPEC-003.C8:** `:edges` are maps with `:type`, `:to`, and optional `:attributes`; each edge is written from the updated task to `:to`.
- **SPEC-003.C9:** `defquery!` registers a named query expression or parameterized query map in the active daemon's in-memory query registry.
- **SPEC-003.C10:** `load-queries!` reads one EDN map of query names to query definitions and merges it into the active daemon's in-memory query registry.
- **SPEC-003.C11:** `queries` returns the active daemon's in-memory query registry.
- **SPEC-003.C12:** Query registry contents last only for the active daemon lifetime; reload trusted config or call `defquery!` / `load-queries!` again after daemon restart.
- **SPEC-003.C13:** `query` returns tasks matching an ad hoc query definition or daemon-registered query name, with optional runtime parameters.
- **SPEC-003.C14:** `task`, `tasks`, `query`, and `ready` return rows with JSON-bearing columns normalized to Clojure values.
- **SPEC-003.C15:** `ready` returns non-final tasks whose direct `depends-on` dependencies are all final and may be further filtered by an ad hoc or registered query.
- **SPEC-003.C16:** Blessed library-workspace helpers live in explicit `atom.libs.alpha`, not in the preloaded `todo.repl` helper namespace.
- **SPEC-003.C17:** `atom.libs.alpha` exposes approved library config helpers, approved-local-root sync helpers, resilient module activation with `use!`, and daemon-lifetime sync/use introspection.
- **SPEC-003.C18:** `atom.libs.alpha` helpers route to the selected daemon world when called from connected REPL clients. Direct `require` in a connected helper REPL remains local to the helper JVM.
- **SPEC-003.C19:** `atom.libs.alpha` is the documented library-workspace path, but trusted users may require lower-level namespaces or read raw SQLite when they accept compatibility cost.

## SPEC-003.P4 Runtime library workspace helpers

`atom.libs.alpha` is the blessed alpha namespace for trusted config and connected REPL library workspace workflows. It is explicit and is not preloaded into `todo.repl`.

Approved library config lives in `libs.edn` in the selected config-dir. The MVP config is an EDN map with exactly one top-level key, `:libs`. `:libs` is a map from symbol library coordinates to maps containing exactly one required key, `:local/root`, whose value is a non-blank string path. Unknown top-level keys, non-symbol coordinates, missing `:libs`, non-map entries, unknown per-lib keys, and missing/non-string `:local/root` fail loudly as structural config errors.

Relative `:local/root` values resolve against selected config-dir; absolute roots are accepted as explicit user-approved paths. Normalized approved config returns entries shaped as `{lib-symbol {:local/root original-path :root canonical-path}}`. Per-library missing or unreadable local roots are not structural config errors; `(libs/sync!)` records them as failed sync outcomes so optional module activation can skip without aborting daemon startup.

Helpers include:

- `(libs/approved)` returns normalized approved config.
- `(libs/sync!)` uses Clojure runtime dependency tooling to add approved local roots and returns structured results for loaded, already-available, and failed libraries.
- `(libs/syncs)` returns daemon-lifetime approved-library sync state.
- `(libs/use! key opts)` records one daemon-lifetime module-use attempt under keyword `key`; duplicate keys replace prior state for reload workflows.
- `(libs/uses)` and `(libs/use key)` expose daemon-lifetime module-use state.

`use!` options identify exactly one load target with `:ns` for daemon-side namespace loading or `:file` for selected-config-dir-relative daemon-side `load-file`; `:file` must be relative and must resolve within the selected config-dir. For `:ns`, the daemon first searches synced local-root classpath entries from each root's `deps.edn :paths` (defaulting to `["src"]`) and `load-file`s the namespace source using Clojure's hyphen-to-underscore path mapping; if no synced source exists it falls back to ordinary `require`. Options may include `:libs`, a vector or set of symbol library coordinate keys that must be approved and available before target loading; `:after`, a vector of prior loaded `use!` keys; `:call`, a fully qualified zero-arity function symbol to resolve and call after successful load; and `:required? true` for strict load/call failure behavior.

Malformed `use!` options always throw. Unmet `:libs` requirements record and return `{:status :skipped ...}` before target loading, with reasons including `:not-approved`, `:not-synced`, or `:sync-failed` when known. Unmet `:after` requirements record and return `{:status :skipped ...}` with reason `:missing-after`. Load or call exceptions record and return `{:status :failed ...}` by default; `:required? true` rethrows after recording. Raw `require` remains the strict fail-fast path for required config.

Maven/remote dependency coordinates, version-range matching, alternate approved-library config files, source fetching, and direct connected-helper-REPL `require` of newly synced daemon libraries are outside the MVP contract.

## SPEC-003.P5 Example library init

A selected config-dir `init.clj` may sync approved local roots and activate optional modules:

```clojure
(require '[atom.libs.alpha :as libs])

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

## SPEC-003.P6 Non-goals

The REPL API does not expose bespoke helpers such as `depends!`, `edge!`, `done!`, `by-attr`, `deps`, `blocking`, or `graph`. Those are either covered by `update!` or the generic query API.

The REPL API does not add CLI package authoring commands, package installation, source fetching, or plugin-directory loading.
