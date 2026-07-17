# REPL API

**Document ID:** `SPEC-003` **Status:** Implemented **Last Updated:** 2026-07-17 **Code:** `src/skein/repl.clj`, `src/skein/api/*.alpha`, `src/skein/userland/alpha.clj`, `src/skein/test`

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

- **SPEC-003.C1:** `connect!` selects one active weaver connection by Skein workspace for explicit Clojure client/test workflows. It requires an explicit selected workspace and optional state metadata supplied directly by standalone Clojure/test helpers. It never accepts a database path and no longer silently falls back to an XDG global workspace. Default `mill weaver repl` does not call `connect!`.
- **SPEC-003.C2:** `mill weaver repl` requires a running `mill`, asks it to resolve the selected workspace, verify that workspace's weaver is running, and return nREPL metadata, then attaches the user's terminal to the selected weaver nREPL endpoint. Mill does not proxy nREPL. Any launched attach client is transport/UI only: user forms are evaluated in the weaver JVM, not in a separate local runtime or through the fixed API bridge.
- **SPEC-003.C3:** `mill weaver repl --stdin` attaches to the selected running weaver nREPL, reads and evaluates top-level stdin forms in the weaver JVM in order, prints one direct normal Clojure result per form, and exits non-zero on read, eval, or transport failure. Callers that want one machine-readable payload should wrap work in one top-level `do` or `let`.
- **SPEC-003.C4:** `skein.repl` helpers keep the human-friendly implicit selected-weaver surface: inside the active weaver JVM they use the active runtime, and explicit Clojure client/test workflows use `connect!` plus the fixed-form client bridge. The `skein.api.*.alpha` domain namespaces do not perform implicit runtime discovery or connected-client routing; trusted in-process callers pass a runtime explicitly. This is a deliberate TEN-000 in-place rethink to make runtime ownership visible. Weaver/transport failures surface loudly as Clojure exceptions.
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
- **SPEC-003.C16:** Blessed spool-workspace helpers live in explicit `skein.api.runtime.alpha`, not in the preloaded `skein.repl` helper namespace.
- **SPEC-003.C17:** `skein.api.runtime.alpha` exposes approved spool config helpers, approved-local-root sync helpers, resilient module activation with `use!`, weaver-lifetime sync/use introspection, a blessed runtime-local spool-state accessor for trusted spools, and a blessed runtime-scoped `now` read returning the runtime clock's current `java.time.Instant` (SPEC-004.C1a).
- **SPEC-003.C18:** `skein.api.runtime.alpha` helpers all take the target runtime as their first argument. Trusted startup/config/spool code that needs the active in-process runtime captures it with `(skein.api.current.alpha/runtime)` at an entry point and threads it explicitly. Connected-client workflows do not route through `skein.api.runtime.alpha`; humans use `skein.repl`, and trusted tests or tools use `skein.core.client` with explicit workspace arguments.
- **SPEC-003.C19:** `skein.api.runtime.alpha` is the documented spool-workspace path. Namespace tiers are contractual: `skein.api.*.alpha` promises accretion-based back-compat within each subnamespace; breaking rethinks move to a new subnamespace. `skein.core.*` promises nothing and is for engine internals; trusted users may require it or read raw SQLite when they accept compatibility cost. `skein.spools.*` is the authorable/reference spool layer. `skein.repl` is the interactive human surface. `skein.userland.alpha` is the userland-only terse ergonomics tier: strictly downstream, never required by any `skein.*` namespace (SPEC-003.C24–C27). `skein.*` source namespaces are reserved for code shipped by the Skein checkout; external/shared spool source namespaces use the author's org prefix instead (codethread spools use `ct.spools.<name>`), keeping them disjoint from every `skein.*` tier.
- **SPEC-003.C20:** `defpattern!` registers a simple pattern name, optional non-blank doc string, fully qualified function symbol, and input spec name in the active weaver's in-memory pattern registry. Supported arities are `(defpattern! name fn-sym input-spec)` and `(defpattern! name doc fn-sym input-spec)`. Duplicate registration replaces the prior entry. The same operations are available through the blessed `skein.api.patterns.alpha` namespace for trusted startup config and activated spools when callers pass the runtime explicitly.
- **SPEC-003.C21:** `patterns`, `pattern`, and `pattern-explain` inspect active weaver pattern state. Missing pattern lookup fails loudly. Explanations return serializable caller guidance based on the registered spec.
- **SPEC-003.C22:** `weave!` validates input against the registered spec, calls the registered function with `{:input input}`, requires the result to be a valid batch strand vector, and creates the batch atomically through the weaver.

## SPEC-003.P4 Runtime transformation helpers

Skein ships blessed source-visible runtime transformation namespaces for trusted config, activated spools, and live in-weaver REPL forms. Except for client-side `skein.api.peers.alpha`, these namespaces take an explicit runtime first argument and never route through connected-client state:

- `skein.api.graph.alpha` exposes `(query-ids runtime query params)`, `(burn-by-id! runtime id)`, `(burn-by-ids! runtime ids)`, `(strands-by-ids runtime ids)`, `(ancestor-root-ids runtime seed-ids)`, `(ancestor-root-ids runtime seed-ids opts)`, `(subgraph runtime root-ids)`, `(subgraph runtime root-ids opts)`, `(incoming-edges runtime to-ids edge-type)`, `(outgoing-edges runtime from-ids edge-type)`, `(register-query! runtime query-name query-def)`, `(load-queries! runtime query-defs)`, `(queries runtime)`, `(resolve-query runtime query-name)`, `(query-metadata runtime)`, and `(query-explain runtime query-name)`. Traversal opts may include `:type` for the declared acyclic relation to walk and default to `"parent-of"`; annotation relations fail loudly. `ancestor-root-ids` also preserves `:where`/`:params` filtering. `incoming-edges`/`outgoing-edges` are lenient single-hop adjacency lookups (an absent id yields no rows rather than a missing-id error). The `register-query!`/`load-queries!` mutators validate and merge named query definitions into the runtime registry; `queries`, `resolve-query`, `query-metadata`, and `query-explain` read it back.
- `skein.api.views.alpha` exposes `(register-view! runtime name fn-sym)`, `(view! runtime name params)`, and `(views runtime)`. View registration accepts a simple view name and a fully qualified function symbol, not an arbitrary client-side function value.
- `skein.api.patterns.alpha` exposes `(register-pattern! runtime name fn-sym input-spec)`, `(register-pattern! runtime name doc fn-sym input-spec)`, `(patterns runtime)`, `(pattern runtime name)`, `(explain runtime name)`, and `(weave! runtime name input)`. Pattern functions are weaver-loadable function symbols called with `{:input input}` after input spec validation.
- `skein.api.events.alpha` exposes `(register! runtime key types fn-sym)`, `(register! runtime key types fn-sym metadata)`, `(unregister! runtime key)`, `(handlers runtime)`, `(recent-failures runtime)`, `(enqueue! runtime event)`, `(await-quiescent! runtime)`, and `(await-quiescent! runtime opts)`. `enqueue!` submits an event map to the runtime event system for asynchronous dispatch, validating the `:event/type`, `:event/id`, `:event/at`, and `:event/source` envelope. `await-quiescent!` blocks until the event lane settles (queue empty and no dispatch in flight), returns the runtime, or throws on timeout (default budget `skein.spools.test-support/await-budget-ms`, overridable via `:timeout-ms`); it is a lane-only settle and observes no off-lane completion (SPEC-004.C74b).
- `skein.api.hooks.alpha` exposes `(register! runtime key types fn-sym)`, `(register! runtime key types fn-sym opts)`, `(unregister! runtime key)`, and `(hooks runtime)`. Hook keys are stable keywords, symbols, or non-blank strings; hook type sets are non-empty keyword sets; function symbols are fully qualified and weaver-resolvable; `opts` may include `:order` and data-first metadata. Registration replaces by key, `hooks` returns deterministic data-first entries, validation hooks return normally or throw, and transform hooks return `{:hook/value replacement}`.
- `skein.api.peers.alpha` exposes `(peers)` (data-first sibling weaver metadata rows with staleness), `(peer name-or-workspace)` (resolve one running peer; bare tokens are logical names, explicitly path-like input matches workspaces; unknown/stale/ambiguous fail loudly), and `(call! peerish op args)` (invoke one allowlisted public JSON socket operation on a resolved peer). All three are client-side: they read published runtime metadata and speak to peer sockets from the calling process, behaving identically inside the live weaver JVM and in explicit connected client/test workflows. See the Weaver Runtime spec, SPEC-004.P10c.
- `skein.api.current.alpha` exposes `(runtime)`, the blessed facade for reading the active in-process weaver runtime. It fails loudly when no active runtime exists and never falls back to connected-client state. Its non-throwing sibling `(runtime-or-nil)` is the single sanctioned way to *probe* for an ambient runtime, returning nil rather than throwing so trusted resolvers can branch on absence instead of catching an exception as control flow. It also exposes `(with-runtime* runtime thunk)` and the `(with-runtime runtime & body)` macro, the blessed scoping twins that bind `runtime` as the thread-local ambient runtime for a dynamic extent so nested `(runtime)` reads and explicit-runtime callees agree without threading the runtime through every call.
- `skein.api.batch.alpha` exposes `(apply! runtime payload)` for transactional batch graph mutation payloads with `:refs`, `:strands`, `:edges`, and `:burn`. It returns normalized Clojure data from the weaver operation, including final refs, created rows, updated before/after rows, burned rows, and edge outcomes, without a JSON envelope.
- `skein.api.format.alpha` exposes `(fill block)` and `(reflow block)`, the pure `|`-margin doc-block helpers for publishing prose as data (op payloads, `about` surfaces, config rule descriptions). Unlike the rest of this list they take no runtime argument. Both fail loudly on a block with no barred lines (TEN-003) instead of returning empty output.

Helpers execute weaver-side when called from `init.clj`, activated runtime spools, or the live weaver REPL after the caller passes an explicit runtime. Connected client users who want to register new view, pattern, event handler, or hook functions should place them in weaver-loadable config/spool code and register their symbols through `skein.repl` or trusted `skein.core.client` calls. View, pattern, event, and hook registrations are weaver-lifetime runtime state unless user config reloads them on startup.

User config may require `skein.api.graph.alpha`, `skein.api.patterns.alpha`, `skein.api.views.alpha`, `skein.api.events.alpha`, `skein.api.hooks.alpha`, `skein.api.batch.alpha`, and `skein.api.format.alpha` so users can inspect and extend the blessed path. These built-in namespaces come from the Skein checkout on the weaver classpath; they do not require `spools.edn` approval.

Hook functions receive one context map and run synchronously at the lifecycle gates specified by the Weaver Runtime contract. They may reject by throwing; only explicit transform hook families may replace values. Hook registration, unregistration, and introspection are trusted Clojure workflows and are not public JSON socket operations.

Event handlers receive one event map and may perform trusted side effects, including calling Skein APIs. They are dispatched asynchronously after successful mutation commits; handler return values are ignored. `(events/recent-failures)` returns bounded, data-first failure records for handler exceptions. Handler exceptions do not fail the already-committed mutation.

## SPEC-003.P4a Scheduler helpers

`skein.api.scheduler.alpha` is a blessed source-visible explicit-runtime namespace for the weaver-owned no-poller scheduler primitive (SPEC-004.P10d), usable from trusted config, activated spools, and live in-weaver REPL forms. Like the rest of the P4 helpers it takes the target runtime as its first argument for every operation, never performs connected-client routing or implicit ambient lookup, and expects callers to capture the runtime with `skein.api.current.alpha/runtime` only at trusted entry points.

- **SPEC-003.C58:** `(schedule! runtime wake)` persists or replaces one durable wake and arms it. `wake` is a map with a stable `:key`, an absolute `:wake-at` (`java.time.Instant`), a fully qualified `:handler` symbol resolvable in the weaver classloader, and an optional `:payload` (nil or a JSON-object-encodable map). Replacing an existing key resets its attempt count. Unknown keys, malformed values, unsupported payloads, and unresolvable/non-callable handlers fail loudly and persist nothing.
- **SPEC-003.C59a:** `(cancel! runtime key)` cancels a pending wake by stable key and returns data-first cancellation information; a missing key fails loudly. No separately named idempotent cancel helper ships.
- **SPEC-003.C59b:** Read helpers expose serializable scheduler state — `pending`, `recent-fires`, `recent-cancellations`, and `recent-failures` — returning data-first maps that never expose functions, executors, timer handles, or raw JDBC rows. The earliest pending wake is `(first (pending runtime))`, since `pending` is ordered by wake-at ascending; no dedicated public accessor is exposed.
- **SPEC-003.C59c:** Handler symbols resolve in the weaver JVM/runtime classloader, matching event/view/pattern conventions, and are persisted as a symbol/string representation that survives restart; closures and anonymous functions are rejected. Handlers receive one context map (SPEC-004.C103), run through the weaver's serialized async lane, have their return value ignored, and have exceptions captured in scheduler failure introspection.
- **SPEC-003.C59d:** The default timing recommendation remains pull-based `wake-at` strand attributes plus views when a poller exists. The scheduler API is documented as the no-poller proactive-wakeup escape hatch, and the first public surface is REPL/API-only — no mutating public `strand schedule` command is introduced; a thin read-only CLI may be added later if a concrete user needs it.

## SPEC-003.P5 Runtime spool workspace helpers

`skein.api.runtime.alpha` is the blessed alpha namespace for trusted config, activated spools, and live in-weaver REPL spool workspace workflows. Its functions take an explicit runtime first argument. `skein.api.current.alpha` exposes the blessed `(runtime)` facade for capturing the active in-process weaver runtime at trusted entry points, alongside its non-throwing `(runtime-or-nil)` probe sibling. It is explicit and is not preloaded into `skein.repl`. Loader/config helpers do not live under `skein.spools.*`; that namespace family is reserved for authorable spools and examples. Across the namespace map, `skein.api.*.alpha` is the accreting compatibility tier, `skein.core.*` is internal with no compatibility promise, `skein.spools.*` is authorable userland/reference code, and `skein.repl` is the interactive surface.

Approved spool config comes from `spools.edn` plus `spools.local.edn` in the selected workspace. Each
`spools.edn` key is a family symbol. A local family is `{:local/root path}`. A git family requires
`:git/url` and `:git/sha`, and may add `:git/tag`, `:roots`, `:requires`, and `:skein/min`. `:roots`
maps one or more root-lib symbols to relative paths within the pinned checkout; without it, the
family supplies one root at `"."`. `:requires` maps root libs to minimum positive `vN` markers.
`:git/tag` and `:skein/min` use the same marker grammar. Root libs must have unique owners across all
families. Unknown keys, mixed kinds, malformed pins or paths, duplicate root owners, and unsatisfied
release floors fail loudly before materialization.

`spools.local.edn` may replace a shared git family with `{:local/root path :claims "vN"}`. The local
coordinate applies to the whole family and inherits its root map and compatibility floors. The
claim states which release contract the local tree satisfies. Relative local roots resolve against
the selected workspace; absolute paths and home expansion remain explicit approvals. Normalized
approved config is keyed by root lib. Each value has `:kind`, kind-specific source fields, effective
`:root`, and source metadata. `sync!` adds `:lib`, owning `:family`, normalized family `:coordinate`,
provenance, and `:status`; failures also carry `:reason` and reason-specific diagnostics. Missing
roots, expected git/I/O acquisition failures, and tag mismatches are per-root outcomes. Unexpected
JVM throwables and atomic Maven-resolution failures escape loudly.

Spool metadata and spool prerequisites are documentation, not REPL API grammar. Shared spool authors document prerequisites, suggested pins, and activation order in their README using the convention described by `docs/spools/writing-shared-spools.md`. A `spool.edn` file, if present in a source tree for historical or local reasons, is ignored by the Skein spool contract and is not an authoritative manifest. This is a deliberate TEN-004-over-TEN-003 tradeoff scoped to manifest retirement: Skein does not read the file at all, so it cannot warn about it. The migration for authors is deleting the file.

Helpers include:

- `(runtime/approved runtime)` returns normalized approved config.
- `(runtime/sync! runtime)` materializes approved git coordinates into the content-addressed cache, uses Clojure runtime dependency tooling to add each entry's effective root, and returns structured results for loaded, already-available, and failed approved spools, including fetch and runtime-add/dependency-policy failure data where applicable.
- `(runtime/syncs runtime)` returns weaver-lifetime approved-spool sync state.
- `(runtime/reload! runtime)` clears weaver-lifetime approved-spool sync state, module-use state, named queries, views, patterns, lifecycle hooks, event handlers, queued events, and recent event failures, then reloads selected workspace startup files in order (`init.clj`, then `init.local.clj`) inside the active weaver and returns loaded file metadata plus final return values. Missing startup files are skipped; present failing files throw with file context. Event dispatch resumes after the fully layered config loads. Reload does not unload already-loaded Clojure namespaces or vars.
- `(runtime/use! runtime key opts)` records one weaver-lifetime module-use attempt under keyword `key`; duplicate keys replace prior state for reload workflows.
- `(runtime/uses runtime)` and `(runtime/use runtime key)` expose weaver-lifetime module-use state.
- `(runtime/now runtime)` returns the runtime clock's current `java.time.Instant` — the blessed, runtime-scoped time source trusted spools read instead of `(Instant/now)` (SPEC-004.C1a). It defaults to the real wall clock; deterministic tests install and step an advanceable clock with `skein.test.alpha/set-clock!`/`advance!` (SPEC-003.C28a). It is data-first (an `Instant`, not the clock fn) and, like every `runtime.alpha` helper, takes the runtime first (SPEC-003.C18).

`use!` options identify exactly one load target with `:ns` for weaver-side namespace loading or `:file` for selected workspace-relative weaver-side `load-file`; `:file` must be relative and must resolve within the selected workspace. For `:ns`, the weaver searches synced local-root classpath entries from each root's `deps.edn :paths` (defaulting to `["src"]`) and `load-file`s the located namespace source using Clojure's hyphen-to-underscore path mapping; when no synced root holds the namespace it fails loudly with a "Could not locate namespace source in synced spool roots" error naming the searched roots. Code resident on the mill-resolved source classpath — blessed `skein.api.*.alpha` namespaces and the single `batteries` spool exception — is loaded by an explicit top-level `require`, not through this `:ns` search; `require`'s already-loaded short-circuit means an explicit require placed above a matching `use!` lets that `use!` still record module state and run its `:call` without reaching the loader. Options may include `:spools`, a vector or set of symbol spool coordinate keys that must be approved and available before target loading; `:after`, a vector of prior loaded `use!` keys; `:call`, a fully qualified zero-arity function symbol to resolve and call after successful load; and `:required? true` for strict load/call failure behavior.

Malformed `use!` options always throw. Unmet `:spools` requirements record and return `{:status :skipped ...}` before target loading, with reasons including `:not-approved`, `:not-synced`, or `:sync-failed` when known; under `:required? true`, each of those three skip reasons throws as a required skipped activation. Unmet `:after` requirements record and return `{:status :skipped ...}` with reason `:missing-after`. Load or call exceptions record and return `{:status :failed ...}` by default; `:required? true` rethrows after recording. Raw `require` remains the strict fail-fast path for required config.

Maven dependencies declared in an approved spool root's top-level `deps.edn :deps` are part of the spool sync contract described by SPEC-004. Version ranges, alternate approved-spool config files, source fetching beyond approved spool coordinates, and direct explicit-client `require` of newly synced weaver spools remain outside the REPL API contract.

## SPEC-003.P5b Blessed op argv parser

- **SPEC-003.C60:** `skein.api.cli.alpha` is the blessed declarative argv parser for weaver ops: a data-first arg-spec (named typed flags, including value-consuming `:string`, `:int`, `:boolean-token`, presence `:boolean`, `key=value` `:map`, repeatable flags, positionals, per-arg docs, payload-parse declarations) parses envelope argv into a data map or throws a loud structured error naming the offending token and the op's spec. The same arg-spec powers `help <op>` rendering via a JSON-safe `explain` projection. `skein.api.return-shape.alpha` is the blessed pure return-shape companion shared by registry validation, help, author tests, and real spool-to-spool consumption seams; it exposes `validate!`, `explain`, and `check!` without runtime, registry, socket, or CLI parser state. Spool authors may layer clojure.spec/malli on the parsed input map; both declaration surfaces stay data-first.
- **SPEC-003.C60a:** A return shape is a finite inline EDN tree. Scalar leaves are `:string`, `:integer`, `:number`, `:boolean`, `:null`, and `:json`; `:json` accepts any JSON-compatible value. `[:nullable <scalar>]` accepts nil or `:string`, `:integer`, `:number`, or `:boolean`; it cannot wrap maps, collections, `:json`, `:null`, or another nullable form. Maps declare `{:type :map :required {<keyword> <shape> ...} :optional {<keyword> <shape> ...} :extra <shape>}`. Required and optional default to empty, may not overlap, and undeclared keys fail checking unless `:extra` supplies their common value shape. Homogeneous sequential collections declare `{:type :collection :items <shape>}`. The language has no functions, arbitrary predicates, coercions, defaults, general unions, named references, or recursive declarations.
- **SPEC-003.C60b:** A flat non-stream op uses a shape directly. A subcommand op declares `{:subcommands {<name-string> <return-case> ...}}` with exactly the arg-spec's subcommand names. A streaming return case declares `{:stream {:emits <shape> :result <shape>}}`; a non-stream case is a shape. `validate!` returns valid authored data unchanged or fails loudly, `explain` returns the declaration as JSON-safe data, and `check!` returns a conforming value unchanged or throws structured mismatch data carrying at least the failing path, expected shape, and actual value.
- **SPEC-003.C61:** Payload reference resolution is a parser contract: a **whole** argv value of `:stdin` or `:payload/<name>` resolves to the named envelope payload string; no substring interpolation. A reference without a matching payload fails loudly; an attached payload nothing references fails loudly. An arg may declare `:parse :json`/`:jsonl` to parse the resolved payload, failing loudly with context on malformed input. Ops registered without an arg-spec receive the raw envelope and own their argv/payload handling.
- **SPEC-003.C62:** Op registration/override from trusted config and REPL uses `skein.api.weaver.alpha/register-op!` (loud on name collision) and `replace-op!` (explicit override); entries carry op metadata, including optional `:returns`, and registry-recorded provenance (SPEC-004.C63a–d). Workspace `defop` and direct spool registrations pass `:returns` through the same metadata route; there is no second macro or registry.
- **SPEC-003.C63:** The shipped `skein.spools.batteries` reference spool (classpath, `skein.spools.*` tier) registers the public strand command surface as parser-backed ops; its behavior contract lives at `spools/batteries.md`. Workspaces may mask or replace batteries; a workspace without it retains core `help` discovery and loud unknown-op errors.
- **SPEC-003.C63f:** Approved opt-in reference spools may layer their own explicit-runtime helpers and parser-backed ops on this surface. `skein.spools.roster` is the worked example — an approved opt-in spool (`spools.edn` coordinate → `runtime/sync!` → `:spools`-guarded `runtime/use!`) — it exposes explicit-runtime `track!`/`heartbeat!`/`finish!`/`roster`/`await-quiet!` helpers that each take the runtime first (SPEC-003.C18 pattern; SPEC-RosterSpool-001.P5), and its `install!` registers a declared-subcommand `roster` op (SPEC-003.C64/C65) plus a `roster` named query and an async graph-integration event handler (SPEC-003.P4 events). Its full behavior contract lives at `spools/roster.md` (SPEC-RosterSpool-001.P6), not in this spec.
- **SPEC-003.C64:** An arg-spec may declare `:subcommands`: a map of subcommand name (string) to a nested arg-spec carrying its own `:doc`, `:flags`, and `:positionals`. Subcommands are one level deep — a nested spec declaring `:subcommands` fails loudly — and an arg-spec declaring `:subcommands` may not also declare top-level `:flags` or `:positionals` (loud failure keeps routing unambiguous). One shared structural validator owns these rules: `parse` and `explain` consult it, and the op registry reuses it for registration-time failure (SPEC-004.C63d). Flat arg-specs are also structurally validated at registration time, including flag/positional container shape, keyword names, and supported `:type` values.
- **SPEC-003.C65:** Parsing a subcommand arg-spec routes on the first argv token: the token selects the nested spec, the remaining argv parses against it, and the parsed map is the nested result merged with `:subcommand` (the matched name). `:subcommand` is a reserved arg name; a nested spec declaring a flag or positional named `subcommand` fails loudly. A missing or unknown first token throws a loud structured error carrying the op name, the offending token, and the available subcommand names. Payload references and `:parse` declarations (SPEC-003.C61) apply unchanged inside nested specs, and `explain` renders declared subcommands (name, doc, per-subcommand flags/positionals) as JSON-safe data. `help`, `-h`, and `--help` are reserved subcommand names: the shared structural validator rejects declaring them at every seam (parse, explain, registration), keeping them free for the invocation help alias (SPEC-004.C63e).

## SPEC-003.P5a Userland ergonomics module

`skein.userland.alpha` is a blessed but **userland-only** terse ergonomics layer over the explicit-runtime API. It lets trusted userland config (`init.clj`), local glue, and tooling hold one weaver runtime and make terse calls (`(ready)`, `(strand! "title")`) instead of threading the runtime through every `skein.api.*.alpha` call. Ergonomics at the cost of hidden ambient resolution is a trade users are allowed to make; skein namespaces are not.

- **SPEC-003.C24:** `skein.userland.alpha` is a strict downstream consumer tier.
  No `skein.*` namespace — engine, blessed API, shipped spool, or `skein.repl` —
  may require it. This invariant is guarded by a test that fails if any
  repo-owned `skein.*` or shipped-spool source requires the module. Shared/distributed
  spools are held to the same rule by review and the shared-spool guidance doc.
- **SPEC-003.C25:** It holds one runtime and resolves every terse call in order:
  the innermost `with-runtime` scope, then the `bind!` default, then
  `skein.api.current.alpha/runtime` (active startup binding or published
  runtime), else a loud failure with userland remediation (TEN-003). It works
  with unpublished runtimes and performs no connected-client routing.
- **SPEC-003.C26:** `bind!`/`unbind!`/`bound` manage a module-local default
  runtime; `bind!` does not publish a process ambient runtime and affects only
  this module's terse calls. `(with-runtime runtime & body)` (macro) and
  `(with-runtime* runtime thunk)` (fn) additionally bind the shared ambient
  runtime for their dynamic extent (via `skein.api.current.alpha`), so nested
  reads and shared code see the same runtime; independent scopes do not
  cross-talk. `(runtime)` returns the resolved runtime as an escape hatch for
  the explicit-runtime surface this module does not wrap (graph, views, events,
  hooks).
- **SPEC-003.C27:** The terse wrappers mirror `skein.repl`'s vocabulary bound to
  the resolved runtime: `init!`, `strand!`, `strand`, `update!`, `supersede!`,
  `burn!`, `strands`, `query`, `ready`, `defquery!`, `load-queries!`, `queries`,
  `query-explain`, `declare-acyclic-relation!`, `acyclic-relations`,
  `defpattern!`, `patterns`, `pattern`, `pattern-explain`, `weave!`, and `apply!`
  (batch). One deliberate signature divergence: `load-queries!` takes an
  already-parsed EDN map of named query definitions, not the file path
  `skein.repl/load-queries!` reads from disk — trusted in-process code owns its
  own I/O. Positioning: `skein.repl` is the interactive human, connection-aware
  surface; `skein.userland.alpha` is the trusted userland-code surface holding an
  explicit in-process runtime. Same terse vocabulary, different runtime
  ownership model.

## SPEC-003.P6 Example spool init

Selected workspace startup files (`init.clj`, then `init.local.clj`) may sync approved local roots and activate optional modules:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/sync! runtime)
(runtime/use! runtime :my/module
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

## SPEC-003.P8 Author-side test helpers

- **SPEC-003.C28:** `skein.test.alpha` is the blessed author-side test namespace for disposable weaver worlds. It ships from `src/skein/test/alpha.clj` so external library test JVMs can require it by adding a selected Skein checkout as a tools.deps `:local/root` test dependency. It is a dev/test helper loaded in the author's test JVM, not a weaver runtime activation API and not a public CLI surface. The durable vocabulary is `with-weaver-world`, `weaver-world-fixture` (binding `*weaver-world*` for `clojure.test` fixtures), `repl!`, `spool-checkout-root`, `check-op-return!`, and the deterministic-clock controls `set-clock!`/`advance!` (SPEC-003.C28a). `check-op-return!` accepts an explicit runtime, operation, optional subcommand or stream-channel context, and a captured value; it resolves the registered declaration, delegates to `skein.api.return-shape.alpha/check!`, returns the value on success, and throws mismatch data naming the operation, selected declaration, failing path, and actual value.
- **SPEC-003.C28a:** `skein.test.alpha` ships the manual-clock control pair for deterministic tests: `(set-clock! runtime clock-fn)` installs a zero-arg clock fn (returning a `java.time.Instant`) as the runtime clock, and `(advance! runtime duration)` moves that clock forward by a `java.time.Duration` and then runs every clock-consuming subsystem's now-due work synchronously before returning (SPEC-004.C1a), returning the new `Instant`. `advance!` fails loudly (TEN-003) on a non-positive or backwards duration — a manual clock never runs backwards. Both take an explicit `runtime` first argument, so in-process serial suites driving `skein.spools.test-support/with-runtime` call them directly rather than through the nREPL `repl!` path. They live in this author-side test namespace — not a production alpha tier — because installing and stepping a manual clock is a test-only control; the blessed production-scoped time read is `skein.api.runtime.alpha/now` (SPEC-003.C17, SPEC-004.C1a).
- **SPEC-003.C29:** `with-weaver-world` creates an isolated generated workspace (or uses an explicit `:root`), writes requested `config.json`, `spools.edn`, `init.clj`, and workspace-relative fixture files, starts an unpublished in-process weaver runtime with explicit storage selection (`:sqlite-file` default, `:sqlite-memory` supported), binds an orchestration context map for the body, and stops the weaver and cleans up deterministically afterwards. Worlds nest and run concurrently. Startup, fixture, eval, stop, and cleanup failures fail loudly; cleanup failures during body failure attach as suppressed exceptions rather than masking the body error. Helpers never touch the user's default config/data/state workspaces, and generated worlds use short temp roots to stay inside Unix socket path limits.
- **SPEC-003.C30:** `repl!` evaluates weaver-routed forms (quoted forms rendered with pr-str, or source strings) against the world's weaver over its real nREPL transport with the runtime ambiently bound, so `skein.api.current.alpha/runtime` resolves to the test weaver. Results must be EDN-readable data; weaver-side and transport failures throw ExceptionInfo with weaver/client context. The namespace deliberately provides no general assertion DSL, per-command wrappers, spool activation wrappers, or CLI subprocess helpers. `check-op-return!` is its sole output-contract helper: it checks values already captured by an op-owner test and does not invoke an op. Each owner suite derives its production operations and required flat, subcommand, and stream leaves from registry provenance and declarations, requires `:returns` on every owned op, and records at least one `check-op-return!` call per leaf; parallel hand-maintained op or expected-leaf lists do not define coverage.
- **SPEC-003.C31:** The context map exposes orchestration facts only: `:config-dir`, `:state-dir`, `:data-dir`, `:db-path` (file storage only), `:storage`, `:source` (the Skein checkout on the test classpath), `:runtime`, `:metadata`, and `:timeout-ms`.
- **SPEC-003.C32:** `spool-checkout-root` resolves the directory checkout backing a spool source resource on the author's test classpath so tests can approve that checkout as a generated `spools.edn` `:local/root`. It takes a classpath-relative source path, requires a file-backed classpath resource, derives the supplying classpath entry from the resource location, and returns the nearest ancestor whose `deps.edn` declares that exact entry in `:paths`. The one-argument form uses `clojure.java.io/resource`; the two-argument form accepts a resource-loader function from resource path string to `java.net.URL` or nil. Missing resources, jar-backed resources, and directory resources without the expected `deps.edn`/`:paths` checkout layout fail loudly with ExceptionInfo.
