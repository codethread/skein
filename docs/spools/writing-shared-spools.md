# Writing shared spools

This guide is for authors of spools that **other people** will run — reusable, distributable spools, not the throwaway glue in your own workspace. Its one rule:

> **Composability over ergonomics.** A shared spool must work in any weaver
> runtime, including unpublished runtimes that coexist with others in a single
> JVM (tests, embedded tooling, `:publish? false`). It earns that by taking the
> runtime **explicitly** and never reaching for ambient/singleton state.

If you are only writing your own workspace `init.clj` or local helpers, you do not need this
discipline — layer the terse `skein.userland.alpha` ergonomics module on top ([customising your
workspace](./customisation.md)). This guide is about the code you ship to others.

## Why explicit runtime

RFC-016 made the weaver runtime an explicit first argument throughout `skein.api.*.alpha`, and split
"a runtime exists" from "this process's published ambient runtime". Multiple independent runtimes
can now run in one JVM, each with its own storage, registries, transports, and events. A shared
spool that reads the published singleton (`skein.api.current.alpha/runtime` with no scope, or the
raw `skein.core.weaver.runtime/current-runtime` atom) silently breaks the moment it runs inside an
unpublished runtime or alongside a second runtime: it mutates the wrong world or throws.

## The rules for shared spools

1. **Take `runtime` as the first argument** of every public function. Do not
   resolve it internally. Callers own runtime selection; you thread what you are
   given.
2. **Keep state runtime-owned.** No module-level `atom`/`def` mutable state. Use
   [`skein.api.runtime.alpha/spool-state`](../../devflow/specs/repl-api.md) to
   store per-runtime state keyed by a symbol you own, initialised once:

   ```clojure
   (require '[skein.api.runtime.alpha :as runtime])

   (defn- state [runtime]
     (runtime/spool-state runtime ::registry #(atom {})))
   ```

   Two runtimes then get two independent registries; nothing resets or races
   across runtimes.

   **Versioned spool state.** Spool-state entries deliberately survive `reload!`
   (unlike the registries a reload clears), so a preserved value outlives the
   code that built it. If your state map's *shape* changes between deploys — a
   new key, a swapped resource — a post-upgrade reload would otherwise reuse the
   stale map, and code reaching for the new key silently gets `nil` (this is a
   real incident: an agent-run reload once reused a map predating its executor keys
   and parked every run). Declare a `state-version` next to the builder and pass
   it, so a version mismatch reinits deliberately instead of reusing a
   shape-mismatched value:

   ```clojure
   (def ^:private state-version
     "Bump whenever new-state's key set changes."
     1)

   (defn- new-state []
     {:registry (atom {})})

   (defn- state [runtime]
     (runtime/spool-state runtime ::state {:version state-version} new-state))
   ```

   Any state holding a live resource (executor, scheduler, socket) must also
   store a no-arg `:close-fn` in its map so the runtime releases it on stop and
   on version-mismatch reinit; supply a `:migrate-fn` when a version bump must
   carry durable sub-state across (it then owns the old value's resources). See
   `skein.api.runtime.alpha/spool-state` and SPEC-004.C95 for the full contract.
   Pin the current key set with a drift-alarm test using
   `skein.spools.test-support/assert-state-shape`, which fails loudly if
   `new-state` and `state-version` drift apart.
3. **Register behaviour by symbol, not by closure.** Views, patterns, event
   handlers, and hooks register a fully qualified function *symbol* the weaver
   resolves. This keeps registration serialisable and runtime-portable.
4. **Fail loudly (TEN-003).** On unexpected input or missing state, throw with
   data. Do not paper over it with a "sensible default" or a fallback to the
   published runtime. Reach for `skein.api.spool.alpha` (`fail!`,
   `reject-unknown-keys!`, `require-valid!`, `attr-key->str`) instead of
   re-deriving these seams per spool.
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
7. **Write attribute deltas, not read-merged maps.** To change a strand's
   attributes, pass `weaver/update` **only the keys you are changing** —
   `{:attributes {:kanban/status "claimed"}}` — and let `db/update-strand!`'s
   `json_patch` merge fold them into the stored map. Never read the strand, merge
   your changes into its full `:attributes`, and write the whole map back: two
   concurrent updates each start from a possibly-stale read and the later write
   silently drops the earlier one (a lost-update race). `weaver/update` returns the
   full merged strand, so a delta write loses no result fidelity. For reads, use
   the shared tolerant reader `skein.api.spool.alpha/attr-get` (keyword key, bare
   string fallback) and `attr-key->str` for wire-key coercion rather than
   re-deriving a per-file attribute accessor. This delta write rides SQLite's
   `json_patch`, whose merge semantics treat an explicit `nil` value as a
   deletion instruction, not a stored `null` — `json_patch` drops that key from
   the map entirely. Omit a key you don't want to touch; only set it to `nil`
   when you deliberately mean "remove this attribute".

## Namespace claims

A shared spool declares each namespace it owns from its `install!` path with `vocab/declare!`. Qualify those namespaces with a project prefix, such as
`acme/priority`, so they do not collide with Skein core or with another author's spool. The prefix is an authoring convention, not a parser rule. The registry
backs it with the duplicate-owner check: if two owners claim the same namespace, install fails loudly instead of choosing one.

## Shared helper namespaces

Every reference spool builds on two small blessed helper namespaces, `skein.api.spool.alpha` and
`skein.api.format.alpha`. Both are source-visible on the Skein checkout/classpath — require them
directly, no `spools.edn` approval needed. They are part of the spool-authoring contract only where
this guide documents them; prefer them over local copies when writing a shared spool.

### `skein.api.spool.alpha`

Require it from spool code when you need fail-loud validation, attribute-key normalisation, or a caller-owned polling loop:

```clojure
(require '[skein.api.spool.alpha :as spool])
```

- `(fail! message data)` and `(fail! message data cause)` throw `ex-info` with
  the supplied message, data map, and optional cause. Use this for TEN-003
  boundary failures so callers receive structured context.
- `(reject-unknown-keys! context allowed m)` returns `m` after checking that all
  its keys are in the `allowed` set. Unknown keys throw with `:unknown` and
  `:allowed` data; use this on option maps rather than ignoring typos.
- `(require-valid! spec value message)` returns `value` when it satisfies the
  `clojure.spec` and throws with `:value` plus `:explain` data when it does not.
- `(attr-key->str k)` converts a strand attribute key to its string wire key.
  Keywords lose the leading colon and preserve namespaces; strings pass through.
  Use it when writing attribute maps whose keys may have been authored as
  keywords.
- `(attr-get strand k)` reads `k` from `(:attributes strand)` whether the map is
  keyword-keyed on the native path or string-keyed after a JSON round trip. It
  tests presence with `contains?`, so explicit falsey values are preserved, and
  it fails loudly if the selected value is a lean-read omission descriptor.
- `(poll-until-deadline! {:keys [deadline poll-ms check pred->result on-timeout]})`
  calls the zero-arg `check`, passes each value to `pred->result`, and returns
  the first non-nil result. If the epoch-millis `deadline` has passed, it calls
  `on-timeout` with the last checked value and returns that. `deadline` and
  `poll-ms` are required; the helper does not invent timeout or cadence defaults.
  It validates all five entries before polling, so bad inputs fail at the seam
  instead of surfacing later as a bare null or sleep error.

### `skein.api.format.alpha`

Require it when a spool needs to publish prose as data, such as `about` payloads or long rule descriptions:

```clojure
(require '[skein.api.format.alpha :as format])
```

Both helpers read `|`-margin strings. The first `|` on each source line marks column 0, so the surrounding Clojure form may be indented freely.

- `(fill block)` returns a vector of item strings. A bare `|` line separates
  items. Flush-left prose lines inside an item are trimmed and joined with
  spaces; an item with any indentation after the bar is preserved verbatim so
  command samples keep their layout.
- `(reflow block)` returns one string for a single prose value. It ignores blank
  barred lines, trims each remaining barred line, and joins them with spaces.

Example:

```clojure
(format/fill
  "|First prose item
   |continues on the next source line.
   |
   |  strand list --query work")
;; => ["First prose item continues on the next source line."
;;     "  strand list --query work"]
```

## The discovery surface your spool ships

Skein's discovery convention has three tiers — generated `help`, authored `about`, run-first `prime`
— described in [`docs/reference.md`](../reference.md) ("Discovery tiers"). For a spool op this
means:

1. **Declare your verbs as `:subcommands` arg-spec data; never hand-roll dispatch or usage errors.**
   Declaring subcommands buys the whole `help` tier for free: `strand help <op>` renders your verbs,
   `strand <op> help|-h|--help` aliases to it, and missing/unknown-verb failures become structured
   parser errors carrying the available names. `help`, `-h`, `--help`, and the arg name `subcommand`
   are reserved and rejected at registration. Bare `<op>` stays a loud non-zero error — never exit-0
   help.
2. **Ship `about` when your op has semantics beyond its argument shapes.** Return one structured JSON
   document (purpose, conventions, attribute contracts, usage examples) from an `about` subcommand. Do
   not duplicate arg shapes in it — that is `help`'s job and it never drifts.
3. **Ship `prime` when your spool carries working discipline.** If an agent must load conventions
   before acting (board lanes, handover contracts, workflow rules), expose a `prime` subcommand that
   prints them, generated from the same definitions the spool installs so the discipline can never
   drift from the installed surface.

## CLI style

The authoritative [discovery-tier contract](../reference.md#discovery-tiers-help-about-prime)
applies to shared-spool CLIs. The naming guidance below is advisory. Apply it
fix-on-touch when a surface changes for another reason. Do not rename a working
surface only for consistency or add compatibility aliases for the old name.

- Choose verbs by role. For entity lifecycles, prefer `start`, `finish
  --outcome`, `abort` only for real teardown, `status <id>`, and `list`. For
  workflow steps, prefer `start`, `next`, `complete`, `choose`, and `status`.
  For processes, prefer `spawn`, `kill`, `retry`, `await`, `logs`, and `ps`.
- Use `--by` for attribution. Name attribute-stamping flags after the attribute:
  `--owner`, `--branch`, `--worktree`, and `--feature`. Prefer seconds-first,
  unit-suffixed durations such as `--timeout-secs`, and use `--outcome` for
  closing state.
- Prefer `list` for live, filterable work. Use a plural noun such as `harnesses`,
  `suites`, or `backends` for a fixed catalog.
- Prefer one op with declared subcommands for a cohesive multi-verb domain. Keep
  single-purpose projections and config-registered ops flat.

One rule is mandatory, and it is the sole MUST in this section: every
text-bearing flag or positional MUST use the declared arg-spec parser so
whole-value `:stdin` and `:payload/<name>` references resolve.

## Publishing a shared spool with git distribution

A shared spool can be published as an ordinary git repository and consumed from a workspace
`spools.edn` by explicit approval of a pinned commit. The approving workspace chooses the coordinate
symbol; that symbol is the consent handle used by `sync!`, `use!`, and local overrides.

> **Worked example.** Skein's own devflow lifecycle is distributed exactly this
> way: its source lives in [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool),
> this repo approves it with a sha-pinned `:git/url`+`:git/sha` coordinate in
> `.skein/spools.edn`, activates it (`:required? true`) from `.skein/init.clj`,
> pins the same sha as a tools.deps git dep for the test JVM (`deps.edn`), and
> developers override it with a gitignored `spools.local.edn` local root. See
> [`spools/devflow.md`](../../spools/devflow.md) for the consumer side.

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

Do not put composition metadata in machine-readable spool files. A shared spool's metadata,
prerequisites, suggested pins, and activation order belong in its README, where an agent or human
can copy the complete approval and activation recipe and still make an explicit consent decision for
every source root.

Include a **Dependency information** section with a complete `spools.edn` snippet for this spool and
every spool prerequisite. Use author-suggested URLs and pins inline; consumers may choose newer
pins, local overrides, or no approval at all. No prerequisite is fetched transitively.

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

If a prerequisite is a blessed `skein.api.*.alpha` namespace or `skein.spools.batteries` (Skein's
one classpath-shipped spool, see [Classpath exception:
batteries](../../spools/README.md#classpath-exception-batteries)), document the namespace and why it
is required, but do not invent a coordinate for it — both are already trusted as part of the
selected Skein checkout. Every other reference spool is never a shipped-classpath prerequisite: if
your spool depends on one, list it as an ordinary `spools.edn` coordinate above, the same as any
other spool prerequisite.

### README activation snippet

Include an **Activation** section with the complete trusted `init.clj` snippet. The consumer owns
the runtime, calls `sync!`, and activates modules explicitly with `use!`. Use `:spools` guards for
every approved spool whose sync state is a prerequisite and `:after` when one activation depends on
another.

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

`use!` is the blessed early prerequisite check. Under `:required? true`, missing, unsynced, or
failed spool approvals throw for the surviving `:spools` skip reasons. Namespace load and `:call`
failures also fail loudly through the normal activation path.

### Maven dependencies in a spool root

A spool root may declare ordinary JVM library dependencies in its top-level `deps.edn :deps`. Those
dependencies are loaded into the live weaver during `sync!` with the same runtime dependency path
used for spool roots. Runtime loading is weaver-wide: there is no per-spool dependency isolation and
no unload semantics.

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

`sync!` resolves all approved spool Maven deps as one universe. If two roots
declare the same Maven lib with different coordinates, the whole sync fails and
names the lib, roots, and coordinates. Pin that lib with a top-level
`:mvn-overrides` map in `spools.edn` or `spools.local.edn`:

```clojure
{:spools {acme/a {:local/root "spools/a"}
          acme/b {:local/root "spools/b"}}
 :mvn-overrides {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}
```

Overrides are overlaid shared-then-local like `:spools` and use the same
Maven-only policy as spool-root `:deps`: Maven coordinates only, no mutable
versions, and no source-bearing coordinate keys.

## Local development overrides

Use the same coordinate in shared `spools.edn` and gitignored `spools.local.edn` to develop against
a checkout while other users stay pinned to the git sha. Local entries overlay shared entries by
coordinate.

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

The effective root is whichever entry wins the overlay. `:deps/root` is git-only because a local
root can already point directly at any subdirectory you want to use. The Maven-only dependency
policy still applies to local override roots.

**Caution: `sync!` resolves the current approved Maven universe.** Each call
reads the roots approved at that moment, validates their `deps.edn` files, and
resolves their Maven dependencies as one stateless universe. A root removed from
`spools.edn` is simply absent from the next resolution; there is no retained
root set to stub out. If a root is still approved but its directory was deleted
or moved, that root reports a per-spool missing/unreadable failure until you
update or restore the approved entry.

## Testing your spool against a Skein checkout

A shared spool repo carries its own standalone test suite and runs it against a
Skein checkout — the reference layout is siblings on disk (`your-spool/` beside
`skein-src/`), which is also how Skein's own CI can run your suite against a
candidate checkout. The pieces, worked in both `devflow.spool` and
`kanban.spool`:

```clojure
;; deps.edn
{:paths ["src"]
 :aliases
 {:test {:extra-paths ["test"]
         ;; io.skein/skein exposes only Skein's base classpath (src/,
         ;; batteries). Any reference spool your code requires — most
         ;; commonly the workflow engine — lives in a spool root off that
         ;; classpath and must join the test JVM as its own dep.
         :extra-deps {io.skein/skein {:local/root "../skein-src"}
                      io.skein/workflow-spool {:local/root "../skein-src/spools/workflow"}}
         :jvm-opts ["--enable-native-access=ALL-UNNAMED"]
         :main-opts ["-m" "acme.priority-test"]}}}
```

Keep every skein-supplied root on the **same checkout** — mixing a sibling
`io.skein/skein` with a sha-pinned spool root would test your spool against an
engine version the rest of the classpath never shipped with. Fixture-wise, use
the public author helper rather than repo-local scaffolding:
`skein.test.alpha/with-weaver-world` for a disposable weaver world, and take the
runtime it hands you explicitly — your shared spool never resolves it internally.
Reach for `skein.core.weaver.runtime/with-runtime-binding` only when a test must
exercise *userland* code that resolves the ambient runtime, never the shared
spool's own functions. Give the test namespace a `-main` that exits non-zero on
failure so `clojure -M:test` is CI-usable. The devflow.spool and kanban.spool
test suites are the worked examples of all of this.

## The pattern pair

### A shared spool exposes explicit-runtime functions

```clojure
(ns acme.priority.alpha
  "Shared spool: promote/inspect strand priority. Runtime is always explicit."
  (:require [skein.api.runtime.alpha :as runtime]
            [skein.api.weaver.alpha :as weaver]))

(defn- promotions [runtime]
  ;; Runtime-owned state, created once per runtime; no module-level atom.
  (runtime/spool-state runtime ::promotions #(atom 0)))

(defn promote!
  "Raise `id`'s priority attribute in `runtime` and return the updated strand."
  [runtime id]
  (when-not (weaver/show runtime id)
    (throw (ex-info "No such strand to promote" {:id id})))     ; fail loudly
  (swap! (promotions runtime) inc)
  (weaver/update runtime id {:attributes {:priority "high"}}))

(defn promotion-count
  "Return how many promotions this `runtime` has performed."
  [runtime]
  @(promotions runtime))
```

Everything takes `runtime`. It runs correctly in a published daemon, an unpublished test runtime, or two runtimes side by side — no cross-talk.

### Layering ergonomics in your own config

The consumer's side of this pattern — binding the runtime once with `skein.userland.alpha` for terse
daily calls while your spool stays explicit — is workspace customisation, and lives on [that
page](./customisation.md). The rule that matters here: the ergonomics stay entirely on the user's
side of the boundary. A shared spool never learns that `skein.userland.alpha` exists; users may
trade explicitness for terseness in their own config, shared code may not.

## Namespace tiers (why this split exists)

See [AGENTS.md](../../AGENTS.md) and [SPEC-003](../../devflow/specs/repl-api.md).

- `skein.api.*.alpha` — blessed, accreting, explicit-runtime API. **Build shared
  spools on this.**
- `skein.core.*` — engine internals, no compatibility promise.
- `skein.spools.*` — the authorable/reference spool layer.
- `skein.repl` — the interactive human surface (connection-aware).
- `skein.userland.alpha` — userland-only terse ergonomics; a strict *downstream*
  consumer tier. No `skein.*` namespace may require it, and neither may a shared
  spool.

## Enforcement

The invariant "no `skein.*` (engine, blessed API, REPL, or shipped spool) source requires
`skein.userland.alpha`" is guarded by a test
(`skein.userland-test/no-skein-source-requires-the-userland-module`). That test covers repo-owned
`skein.*` and shipped-spool sources. Local and third-party shared spools are held to the same rule
by review and this guide. If abuse of the ergonomics layer by distributed spools ever shows up in
practice, the sanctioned next step is a lint over approved spool roots at
`skein.api.runtime.alpha/sync!` time that rejects a spool whose source requires
`skein.userland.alpha`.

## Unsafe spools

Every rule above says: build on `skein.api.*.alpha`, never on `skein.core.*`. Sometimes a genuinely
useful capability lives on the wrong side of that line — the blessed surface deliberately doesn't
expose it, and won't. When you reach past the contract anyway, do it in the open, like a Rust
`unsafe` block: the capability stays available, the danger stays visible, and the next reader knows
exactly what they're trusting.

The worked reference is [`skein.spools.text-search`](../../spools/text-search.md): it requires
`skein.core.db` and runs SQL against the physical tables to search titles and attribute values,
including archived rows the query language cannot see. It is a maintained example of rule-breaking,
not a blessed path. If you must write one, follow the same three markers so the break is never
silent:

1. **`UNSAFE:` docstring prefix.** The namespace docstring's first line begins
   with `UNSAFE:` and names the internal namespaces it requires. A reader
   opening the source sees the bargain before the code.
2. **A README/contract unsafe-declaration section.** The contract doc opens with
   an **Unsafe declaration**: the exact internal namespaces required; why the
   blessed `api.*` surface cannot serve this; and the breakage contract —
   `skein.core.*` changes freely (TEN-000), so the spool may break on any
   upgrade and is maintained *in-repo, in lockstep* with the storage it reads.
3. **In-repo lockstep maintenance.** An unsafe spool ships in this repo, beside
   the internals it couples to, so a `skein.core.*` change and the spool's fix
   land together. An external spool that copies the pattern pins itself to
   internals that will move and owns its own breakage — say so, and don't
   distribute one.

This convention is enforced by review today. The enforcement direction — a `register!`-level
`:unsafe` flag and a lint spool that surfaces `skein.core.*`-requiring spool sources — is a tracked
follow-up.
