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
