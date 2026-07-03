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

## Publishing a shared spool with git distribution

A shared spool can be published as an ordinary git repository and consumed from a
workspace `spools.edn` by explicit approval of a pinned commit. The approving
workspace chooses the coordinate symbol; that symbol is the consent handle used
by manifests, unmet-need reports, and local overrides.

> **Worked example.** Skein's own devflow lifecycle is distributed exactly this
> way: its source lives in [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool),
> this repo approves it with a sha-pinned `:git/url`+`:git/sha` coordinate in
> `.skein/spools.edn`, activates it (`:required? true`) from `.skein/init.clj`,
> pins the same sha as a tools.deps git dep for the test JVM (`deps.edn`), and
> developers override it with a gitignored `spools.local.edn` local root. See
> [`spools/devflow.md`](../spools/devflow.md) for the consumer side.

A consumer pins the exact content they consent to run:

```clojure
{:spools
 {acme/priority
  {:git/url "https://github.com/acme/skein-priority-spool.git"
   :git/sha "0123456789abcdef0123456789abcdef01234567"
   :git/tag "v0.1.0"}}}
```

- `:git/sha` is the behavior contract: it must be exactly 40 lowercase hex
  characters, and the weaver caches the fetched tree by sha.
- `:git/tag` is an optional human-readable label. When a fetch occurs, the tag
  must resolve to the same sha or sync records a `:tag-mismatch` failure. Cache
  hits trust the sha and do not re-check the tag.
- `:git/url` is passed to system `git` as-is, so users can choose HTTPS, SSH, or
  `file://` transports that match their own credentials and trust model.
- `:deps/root` is available for monorepos. It is a relative path inside the
  checkout, with no leading `/`, `~`, or `..` segment:

  ```clojure
  {:spools
   {acme/priority
    {:git/url "https://github.com/acme/tooling.git"
     :git/sha "0123456789abcdef0123456789abcdef01234567"
     :git/tag "priority-v0.1.0"
     :deps/root "spools/priority"}}}
  ```

After editing `spools.edn`, users run `sync!` and then activate modules with
`use!` from trusted config or the REPL. Git coordinates and local roots share the
same activation path once synced.

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def rt (current/runtime))
(runtime/sync! rt)
(runtime/use! rt :acme/priority
  {:ns 'acme.priority.alpha
   :spools '[acme/priority]})
```

### Authoring `spool.edn`

A shared spool root may include `spool.edn`. It is optional, but it gives agents
and humans machine-readable facts without expanding the CLI surface:

```clojure
{:coordinate acme/priority
 :provides [acme.priority.alpha]
 :needs {acme/graph {:suggest {:git/url "https://github.com/acme/skein-graph-spool.git"}}
         acme/audit nil}
 :docs {acme.priority.alpha "Priority promotion helpers. Call public functions with runtime first."}}
```

The manifest grammar is deliberately small:

- `:coordinate`, when present, must equal the approving coordinate. A mismatch
  fails that spool's sync loudly.
- `:provides` is a vector or set of namespace symbols. During `use!`, the weaver
  verifies that each declared namespace can be required from the synced spool
  classloader. A miss skips activation with `:provides-unloadable` (or throws for
  `:required? true`).
- `:needs` maps coordinate symbols to either `nil` or `{:suggest {:git/url
  "..."}}`. A suggestion is a hint for an agent or human, not a resolver.
- `:docs` is either one string or a map of namespace symbol to string.

Unknown keys or malformed values make sync fail with `:manifest-invalid`; do not
use the manifest as an unstructured metadata dump. Keep richer narrative docs in
your README and use `spool.edn` only for data that helps composition.

### Consent loop for needs

Needs are intentionally not transitive package resolution. If `acme/priority`
declares a need for `acme/graph`, the weaver never fetches `acme/graph` on its
own. Instead:

1. `runtime/sync!` records `:unmet-needs` on `acme/priority` when `acme/graph`
   is not approved, or when it is approved but failed to sync.
2. An agent or human reads the unmet need and any `:suggest` URL, proposes a
   `spools.edn` addition with an explicit `:git/sha`, and asks the user to
   approve it.
3. The user approves by editing `spools.edn`.
4. The user reruns `runtime/sync!`, then `runtime/use!`.

Until the need is resolved, `runtime/use!` enforces it: activating a module
whose spools carry unmet needs is skipped with `:reason :unmet-needs` (and
throws under `:required? true`).

That loop preserves the core rule: each coordinate is user-approved at a pinned
sha before code is fetched or activated.

### Local development overrides

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

The manifest is read from whichever effective root wins the overlay. In the
example above, the developer's local checkout provides `spool.edn`; consumers
without the override read the manifest from the cached git tree. `:deps/root` is
git-only because a local root can already point directly at any subdirectory you
want to use.

Local roots approved from shared `spools.edn` follow the same dependency-consent
rule as git roots: their `deps.edn` must not declare tools.deps `:deps`. Shared
workspace config may approve source roots, but it cannot consent to pulling
transitive Maven/git dependencies into another developer's weaver. Declare spool
dependencies in `spool.edn :needs`, or put trusted checkout-only tools.deps
dependencies behind a developer-owned, gitignored `spools.local.edn` override.

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

### A user's local config layers the ergonomics module on top

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
covers repo-owned `skein.*` and shipped-spool sources. Local and third-party shared spools are held to the
same rule by review and this guide. If abuse of the ergonomics layer by
distributed spools ever shows up in practice, the sanctioned next step is a
lint over approved spool roots at `skein.api.runtime.alpha/sync!` time that
rejects a spool whose source requires `skein.userland.alpha`.
