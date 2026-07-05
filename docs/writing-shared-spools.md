# Writing shared spools

This guide is for authors of spools that **other people** will run — reusable,
distributable spools, not the throwaway glue in your own workspace. Its one rule:

> **Composability over ergonomics.** A shared spool must work in any weaver
> runtime, including unpublished runtimes that coexist with others in a single
> JVM (tests, embedded tooling, `:publish? false`). It earns that by taking the
> runtime **explicitly** and never reaching for ambient/singleton state.

If you are only writing your own workspace `init.clj` or local helpers, you do
not need this discipline — layer the terse
[`skein.userland.alpha`](#layering-ergonomics-in-your-own-config) ergonomics
module on top and enjoy. This guide is about the code you ship to others.

## Why explicit runtime

RFC-016 made the weaver runtime an explicit first argument throughout
`skein.api.*.alpha`, and split "a runtime exists" from "this process's published
ambient runtime". Multiple independent runtimes can now run in one JVM, each with
its own storage, registries, transports, and events. A shared spool that reads
the published singleton (`skein.api.current.alpha/runtime` with no scope, or the
raw `skein.core.weaver.runtime/current-runtime` atom) silently breaks the moment
it runs inside an unpublished runtime or alongside a second runtime: it mutates
the wrong world or throws.

## The rules for shared spools

1. **Take `runtime` as the first argument** of every public function. Do not
   resolve it internally. Callers own runtime selection; you thread what you are
   given.
2. **Keep state runtime-owned.** No module-level `atom`/`def` mutable state. Use
   [`skein.api.runtime.alpha/spool-state`](../devflow/specs/repl-api.md) to
   store per-runtime state keyed by a symbol you own, initialised once:

   ```clojure
   (require '[skein.api.runtime.alpha :as runtime-alpha])

   (defn- state [runtime]
     (runtime-alpha/spool-state runtime ::registry #(atom {})))
   ```

   Two runtimes then get two independent registries; nothing resets or races
   across runtimes.
3. **Register behaviour by symbol, not by closure.** Views, patterns, event
   handlers, and hooks register a fully qualified function *symbol* the weaver
   resolves. This keeps registration serialisable and runtime-portable.
4. **Fail loudly (TEN-003).** On unexpected input or missing state, throw with
   data. Do not paper over it with a "sensible default" or a fallback to the
   published runtime.
5. **Never depend on the ergonomics layer.** A shared spool must **not** require
   `skein.userland.alpha`, must not call `bind!`, and must not read the published
   singleton for its own operation. `skein.userland.alpha` is userland-only,
   forever, and holds a process-local runtime binding that is meaningless — and
   actively wrong — inside a reusable spool.
6. **Default to pull-based timing.** When your spool needs time-based work, prefer
   a `wake-at` strand attribute surfaced by a view or query to whatever already
   polls the graph; reach for `skein.api.scheduler.alpha` only for the no-poller
   case where something must proactively fire at instant `T` with nothing polling
   to trigger it. Scheduler delivery is at-least-once, so any handler you register
   must be idempotent.

## The discovery surface your spool ships

Skein's discovery convention has three tiers — generated `help`, authored `about`, run-first `prime` — described in [`docs/skein.md`](./skein.md) ("Discovery tiers"). For a spool op this means:

1. **Declare your verbs as `:subcommands` arg-spec data; never hand-roll dispatch or usage errors.** Declaring subcommands buys the whole `help` tier for free: `strand help <op>` renders your verbs, `strand <op> help|-h|--help` aliases to it, and missing/unknown-verb failures become structured parser errors carrying the available names. `help`, `-h`, `--help`, and the arg name `subcommand` are reserved and rejected at registration. Bare `<op>` stays a loud non-zero error — never exit-0 help.
2. **Ship `about` when your op has semantics beyond its argument shapes.** Return one structured JSON document (purpose, conventions, attribute contracts, usage examples) from an `about` subcommand. Do not duplicate arg shapes in it — that is `help`'s job and it never drifts.
3. **Ship `prime` when your spool carries working discipline.** If an agent must load conventions before acting (board lanes, handover contracts, workflow rules), expose a `prime` subcommand that prints them, generated from the same definitions the spool installs so the discipline can never drift from the installed surface.

## Publishing a shared spool with git distribution

A shared spool can be published as an ordinary git repository and consumed from a
workspace `spools.edn` by explicit approval of a pinned commit. The approving
workspace chooses the coordinate symbol; that symbol is the consent handle used
by `sync!`, `use!`, and local overrides.

> **Worked example.** Skein's own devflow lifecycle is distributed exactly this
> way: its source lives in [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool),
> this repo approves it with a sha-pinned `:git/url`+`:git/sha` coordinate in
> `.skein/spools.edn`, activates it (`:required? true`) from `.skein/init.clj`,
> pins the same sha as a tools.deps git dep for the test JVM (`deps.edn`), and
> developers override it with a gitignored `spools.local.edn` local root. See
> [`spools/devflow.md`](../spools/devflow.md) for the consumer side.

A consumer pins the exact source content they consent to run:

```clojure
{:spools
 {acme/priority
  {:git/url "https://github.com/acme/skein-priority-spool.git"
   :git/sha "0123456789abcdef0123456789abcdef01234567"
   :git/tag "v0.1.0"}}}
```

- `:git/sha` is the behavior contract: it must be exactly 40 lowercase hex
  characters, and the weaver caches the fetched tree by sha. On a cache miss,
  the weaver fetches the pinned sha directly; if the remote does not satisfy
  that direct request, it performs one bounded refetch of advertised tags and
  branches from the same remote before failing loudly with the remote URL and
  cache path.
- `:git/tag` is an optional human-readable label. When a fetch occurs, the tag
  must resolve to the same sha or sync records a `:tag-mismatch` failure. Cache
  hits trust the sha and do not re-check the tag.
- `:git/url` is passed to system `git` as-is, so users can choose HTTPS, SSH, or
  `file://` transports that match their own credentials and trust model.
- `:deps/root` is available for monorepos. It is a relative path inside the
  checkout, with no leading `/`, `~`, or `..` segment.

### README dependency information

Do not put composition metadata in machine-readable spool files. A shared spool's
metadata, prerequisites, suggested pins, and activation order belong in its
README, where an agent or human can copy the complete approval and activation
recipe and still make an explicit consent decision for every source root.

Include a **Dependency information** section with a complete `spools.edn` snippet
for this spool and every spool prerequisite. Use author-suggested URLs and pins
inline; consumers may choose newer pins, local overrides, or no approval at all.
No prerequisite is fetched transitively.

```clojure
;; spools.edn
{:spools
 {acme/graph
  {:git/url "https://github.com/acme/skein-graph-spool.git"
   :git/sha "89abcdef0123456789abcdef0123456789abcdef"
   :git/tag "v0.2.0"}

  acme/priority
  {:git/url "https://github.com/acme/skein-priority-spool.git"
   :git/sha "0123456789abcdef0123456789abcdef01234567"
   :git/tag "v0.1.0"}}}
```

If a prerequisite is shipped on Skein's own classpath, document the namespace and
why it is required, but do not invent a coordinate for it. Shipped namespaces are
already trusted as part of the selected Skein checkout.

### README activation snippet

Include an **Activation** section with the complete trusted `init.clj` snippet.
The consumer owns the runtime, calls `sync!`, and activates modules explicitly
with `use!`. Use `:spools` guards for every approved spool whose sync state is a
prerequisite and `:after` when one activation depends on another.

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def rt (current/runtime))
(runtime/sync! rt)

(runtime/use! rt :acme/graph
  {:ns 'acme.graph.alpha
   :spools '[acme/graph]
   :required? true})

(runtime/use! rt :acme/priority
  {:ns 'acme.priority.alpha
   :spools '[acme/priority acme/graph]
   :after [:acme/graph]
   :required? true})
```

`use!` is the blessed early prerequisite check. Under `:required? true`, missing,
unsynced, or failed spool approvals throw for the surviving `:spools` skip
reasons. Namespace load and `:call` failures also fail loudly through the normal
activation path.

### Maven dependencies in a spool root

A spool root may declare ordinary JVM library dependencies in its top-level
`deps.edn :deps`. Those dependencies are loaded into the live weaver during
`sync!` with the same runtime dependency path used for spool roots. Runtime
loading is weaver-wide: there is no per-spool dependency isolation and no unload
semantics.

The policy is intentionally narrow:

- The rule applies to every approved spool root: git or local, shared
  `spools.edn` or gitignored `spools.local.edn`.
- Every `:deps` entry must be a Maven coordinate map containing `:mvn/version`.
- Source-bearing coordinates are rejected in spool-root `deps.edn :deps`,
  including `:git/url`, `:git/sha`, and `:local/root`. If a spool composes with
  another source root, document that root as its own `spools.edn` entry.
- Mutable Maven versions are rejected: no `-SNAPSHOT`, `RELEASE`, or `LATEST`.
- Repo redirection is rejected: no top-level `:mvn/repos` or `:mvn/local-repo`
  in the spool root.
- Standard Maven refinement keys such as `:exclusions`, `:classifier`, and
  `:extension` are allowed alongside `:mvn/version`.
- Aliases and other non-rejected top-level keys are ignored by `sync!`; no alias
  activation participates in the spool contract.

Example:

```clojure
;; deps.edn inside the spool root
{:paths ["src"]
 :deps {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}
```

## Local development overrides

Use the same coordinate in shared `spools.edn` and gitignored
`spools.local.edn` to develop against a checkout while other users stay pinned
to the git sha. Local entries overlay shared entries by coordinate.

Shared `spools.edn`:

```clojure
{:spools
 {acme/priority
  {:git/url "https://github.com/acme/skein-priority-spool.git"
   :git/sha "0123456789abcdef0123456789abcdef01234567"
   :git/tag "v0.1.0"}}}
```

Developer-only `spools.local.edn`:

```clojure
{:spools
 {acme/priority
  {:local/root "~/dev/projects/skein-priority-spool"}}}
```

The effective root is whichever entry wins the overlay. `:deps/root` is git-only
because a local root can already point directly at any subdirectory you want to
use. The Maven-only dependency policy still applies to local override roots.

## The pattern pair

### A shared spool exposes explicit-runtime functions

```clojure
(ns acme.priority.alpha
  "Shared spool: promote/inspect strand priority. Runtime is always explicit."
  (:require [skein.api.runtime.alpha :as runtime-alpha]
            [skein.api.weaver.alpha :as api]))

(defn- promotions [runtime]
  ;; Runtime-owned state, created once per runtime; no module-level atom.
  (runtime-alpha/spool-state runtime ::promotions #(atom 0)))

(defn promote!
  "Raise `id`'s priority attribute in `runtime` and return the updated strand."
  [runtime id]
  (when-not (api/show runtime id)
    (throw (ex-info "No such strand to promote" {:id id})))     ; fail loudly
  (swap! (promotions runtime) inc)
  (api/update runtime id {:attributes {:priority "high"}}))

(defn promotion-count
  "Return how many promotions this `runtime` has performed."
  [runtime]
  @(promotions runtime))
```

Everything takes `runtime`. It runs correctly in a published daemon, an
unpublished test runtime, or two runtimes side by side — no cross-talk.

### Layering ergonomics in your own config

In your **own** workspace `init.clj`, capture the runtime once and hold it, then
call both the blessed API and the shared spool through terse wrappers:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.userland.alpha :as u]
         '[acme.priority.alpha :as priority])

;; Bind the active in-process runtime once for terse daily calls.
(u/bind! (current/runtime))

;; Terse core calls — the runtime is resolved for you.
(u/strand! "Ship the release" {:owner "me"})
(u/ready)

;; The shared spool still takes the runtime explicitly; hand it (u/runtime).
(priority/promote! (u/runtime) some-id)
```

The ergonomics live entirely on **your** side of the boundary. The shared spool
never learns that `skein.userland.alpha` exists. That asymmetry is the whole
point: users may trade explicitness for terseness in their own config; shared
code may not.

## Namespace tiers (why this split exists)

See [AGENTS.md](../AGENTS.md) and [SPEC-003](../devflow/specs/repl-api.md#spec-003c19).

- `skein.api.*.alpha` — blessed, accreting, explicit-runtime API. **Build shared
  spools on this.**
- `skein.core.*` — engine internals, no compatibility promise.
- `skein.spools.*` — the authorable/reference spool layer.
- `skein.repl` — the interactive human surface (connection-aware).
- `skein.userland.alpha` — userland-only terse ergonomics; a strict *downstream*
  consumer tier. No `skein.*` namespace may require it, and neither may a shared
  spool.

## Enforcement

The invariant "no `skein.*` (engine, blessed API, REPL, or shipped spool) source
requires `skein.userland.alpha`" is guarded by a test
(`skein.userland-test/no-skein-source-requires-the-userland-module`). That test
covers repo-owned `skein.*` and shipped-spool sources. Local and third-party
shared spools are held to the same rule by review and this guide. If abuse of
the ergonomics layer by distributed spools ever shows up in practice, the
sanctioned next step is a lint over approved spool roots at
`skein.api.runtime.alpha/sync!` time that rejects a spool whose source requires
`skein.userland.alpha`.
