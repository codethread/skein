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
   `{:attributes {:kanban/lane "claimed"}}` — and let `db/update-strand!`'s
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
8. **New names for new concepts; inherited names for inherited concepts.** A
   spool builds on a primitive when it invokes it *or* reproduces its concept —
   reimplementing a registry or lifecycle does not exempt its names. The
   primitive may be another spool, a blessed `skein.api.*.alpha` namespace, or
   a lower layer of your own spool that a preset wraps; in every case the
   surface speaks the primitive's vocabulary exactly as published. That means
   every name a consumer meets — function verbs, op subcommands, flag names,
   return keys, option-map and spec keys, pattern input fields, attribute
   keys, phase values, edge relation names — plus their defaults, types, and
   arities (diverge from any of these under an inherited name only with loud
   documentation at the key). The test for a genuinely new concept: describe
   your thing in the primitive's documented vocabulary; if no new noun or
   verb is needed, the name is inherited, and a synonym is a rebrand.
   Layering is decided by who invokes or reproduces whom — never by doc
   assertions or by which layer was written first. When the primitive itself
   publishes synonyms for one concept, converge on the deepest layer's word:
   a blessed `skein.api.*.alpha` name outranks a spool's, and a spool
   primitive's outranks its preset's. When the canonical name is already
   taken at your layer by a different shape, the concept keeps the canonical
   name and the colliding shape takes a derived one. Wrapping a primitive
   behind synonyms makes your spool a universe unto itself: nothing a reader
   learned elsewhere transfers in, and nothing they learn from you transfers
   out. An `acme/gate-sweeper` spool that drives workflow runs speaks
   `start`/`next`/`advance`, reads and writes `workflow/*` keys, and coins a
   name only for the sweeping policy the engine has no word for. Declare the
   namespaces you own with `vocab/declare!` (see Namespace claims); write
   inherited keys in the owner's namespace without declaring them. Bare
   (un-namespaced) keys such as `body` are pre-existing cross-spool
   convention, not a namespace to converge into: use them as found, and mint
   no new ones. A concept unrelated to another spool's that happens to share
   its noun is not inheritance — it is a reader trap; pick a different word.
   Before a `v1` promise, convergence may be a clean break under TEN-000:
   durable attributes on closed strands stay as written because they are
   memory, not authority. After `v1`, the corrected contract takes a new name;
   ship an explicit cutover for active rows when continuity needs it.

### Applying the vocabulary rule

- **Peers.** Spools with no invocation or reproduction relation between them
  are peers: neither's word binds the other, and a peer synonym is evidence
  of a shared miss, not precedent. A concept two peers share converges on the
  word of the spool whose core purpose it is (lifecycle state is agent-run's;
  board lanes are kanban's); when ownership is a wash, the surface every
  world loads outranks opt-in peers. Dependency direction is depth: the
  required spool's word wins over its requirer's. Two peers filling the same
  extension point name their surfaces in parallel — `stalled-shell-gates`
  beside `stalled-subagent-gates`, never one bare and one qualified.
- **One concept, one name — including within your own spool.** The rule binds
  a spool to itself: one concept carries one name across the whole surface
  (attribute key, return key, function verb, prose), and a projection's
  return key matches the attribute it projects. A second name for your own
  concept is a rebrand even though nothing external is shadowed.
- **The enumeration is illustrative, not exhaustive.** Ex-data keys,
  error-code namespaces, event-type keywords, registry key spaces, and
  public vars are all names a consumer meets. Private helpers are exempt
  until one shadows a published name with different semantics or argument
  order — the transfer argument protects the next author, not only the API
  consumer.
- **Inherit the bare verb.** Your Clojure namespace already carries the noun:
  `events/register!`, never `register-handler!`; and a member name never
  repeats its own namespace's noun. Mint keywords only into namespaces you
  own — a spool that writes no attributes still squats when it coins an
  event type or return value in someone else's namespace.
- **Loud documentation, defined.** A sanctioned divergence under an inherited
  name is loud when the docstring (or flag doc) names the primitive and
  states the delta — that reaches the generated API doc and the source
  reader at once.
- **Run the test token by token.** A surface can be a rebrand in its verb and
  novel in its payload nouns. A composition of inherited operations earns a
  coined verb when the composition is itself a concept consumers name
  (`pour!`); a pass-through with defaults does not. And generic nouns
  (`text`, `key`, `id`) shared across unrelated ops are traps only when the
  two readings are plausibly confusable in one context.
- **The free detection heuristic.** If your return keys, docstring, or
  contract doc must use the primitive's word to explain your name — an
  `activate!` that returns `{:installed true}` — the name is the thing
  that's wrong.

## Namespace claims

This section covers vocab and attribute namespaces, not Clojure source namespaces; see
[Namespace tiers](#namespace-tiers-why-this-split-exists) for source naming.

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
applies to shared-spool CLIs.

- Verbs follow role, and a role a primitive already names is never renamed. For
  entity lifecycles: `start`, `finish --outcome`, `abort` only for real
  teardown, `status <id>`, and `list`. For workflow steps: `start`, `next`,
  `complete`, `choose`, and `status`. For processes: `spawn`, `kill`, `retry`,
  `await`, `logs`, and `ps`. An op that fronts one of these behaviors takes the
  role's verb — a subcommand that reaches `workflow/advance!` is `next`, not a
  domain synonym.
- Use `--by` for attribution. Name attribute-stamping flags after the attribute:
  `--owner`, `--branch`, `--worktree`, and `--feature`. Prefer seconds-first,
  unit-suffixed durations such as `--timeout-secs`, and use `--outcome` for
  closing state.
- Prefer `list` for live, filterable entities; `ps` already owns the live
  process listing. Use a plural noun such as `harnesses`, `suites`, or
  `backends` for a fixed catalog.
- Prefer one op with declared subcommands for a cohesive multi-verb domain. Keep
  single-purpose projections and config-registered ops flat.
- Before a `v1` promise, a vocabulary correction may be a clear cut under
  TEN-000. After `v1`, keep the old contract and publish the correction under a
  new name as described in [Versioning and release](#versioning-and-release).

Every text-bearing flag or positional MUST use the declared arg-spec parser so
whole-value `:stdin` and `:payload/<name>` references resolve.

## Versioning and release

### Publishing a shared spool with git distribution

A spool repository is one release unit. Approve it once in `spools.edn`, at one commit, and map each
library root to its path in that checkout:

```clojure
{:spools
 {acme/priority-spool
  {:git/url "https://github.com/acme/priority.spool.git"
   :git/tag "v3"
   :git/sha "0123456789abcdef0123456789abcdef01234567"
   :roots {acme/priority "priority"
           acme/reports "reports"}
   :requires {skein.spools/workflow "v2"}
   :skein/min "v1"}}}
```

This family shape makes mixed generations of roots from one repository unrepresentable. `:git/sha`
is the consent boundary: it names the exact source a consumer agreed to run. `:git/tag` is an
ordered release marker of the form `v<int>`, with no SemVer range or resolver semantics. Releases
use annotated tags. An annotated tag has a tag-object sha and a peeled commit sha; `:git/sha` must
be the peeled commit reported as `refs/tags/vN^{}` by `git ls-remote`.

A work-in-progress repository is untagged and can only be sha-pinned. Floors cannot target it, and
the missing `:git/tag` in a consumer file is the visible nudge that no promise exists yet. Authors
may use labels such as `alpha-3` for humans, but those labels are mechanically inert. The marker
parser rejects them. `v0` is reserved and rejected.

`v1` is the smallest promise: from here, breaks take new names. It carries none of SemVer 1.0's
baggage. Later markers record release order, not degrees of compatibility.

### Accretion under a name

Keep every published name accretion-only. The classification rule is exact:

> rejecting input the published contract accepted is breaking even when it improves validation;
> rejecting what the contract declared invalid is a fix.

A new optional key, function, op, or root is accretion. Removing a case, changing a default, making
an optional field required, changing an accepted type, or giving an old name new behavior is a
break. Name contracts, not broad concepts: `capture-on!` says what changed more clearly than a
generic `capture-v2!`. When a whole model needs the same concept name and no contract-specific name
fits, use a numeric suffix such as `notebook2`; do not mix `next`, `new`, dates, and release-marker
suffixes for the same purpose.

The rename cost depends on the surface:

| Broken surface | What gets a new name | Cost to callers |
| --- | --- | --- |
| Function | A fresh function in the same namespace | Small: call sites opt into the new contract. |
| Registered op or CLI verb | A new op or subcommand; the old one stays registered | Scripts, help text, and automation must opt in. |
| Attribute vocabulary | A new namespaced key or value vocabulary | Highest: persisted rows, queries, views, and contributing spools need an explicit migration boundary. |

Escalate only as far as the break reaches:

1. For one function contract, add a function name. [`notebook.spool` v3](https://github.com/codethread/notebook.spool/tree/v3)
   leaves `capture!` alone and adds `capture-on!`. The
   [`breaking-wrong` branch](https://github.com/codethread/notebook.spool/tree/breaking-wrong)
   shows the rejected alternative.
2. When a namespace model changes, add a sibling root in the same repository. The
   [`notebook.spool` v4](https://github.com/codethread/notebook.spool/tree/v4) family adds
   `notebook2`; adding the root is accretion at family level.
3. When the whole concept changes, start a repository and family. This should be rare.

A sibling root is a complete world for its task. Consumers may load old and new roots while
migrating, but should not mix their requires to assemble one job. Give every public var in the new
root a fresh `defn` and its own docstring. Do not re-export vars with `(def f old/f)` or Potemkin:
arglists, docs, and source navigation must describe the new contract. Bodies may delegate.

Shared internal namespaces are a reasonable default while the compatibility alarm runs old tests
against the whole working tree. Copy the implementation when the old root should receive no churn
or the two roots will diverge. When the old contract can be expressed on the new implementation,
the clean end state is the tools.deps inversion: the new root owns the implementation and fresh
wrappers in the old root preserve the old contract.

Floor raises in `:requires` or `:skein/min` are not breaks. They constrain which release families
may be assembled; they do not change a published name's input contract. Raise a floor only with
evidence from tests at that floor.

### Compatibility alarm

Keep `bin/compat-alarm` in the spool repository. It takes a previous marker, extracts that release's
tests, and runs them against the working tree. The
[`notebook.spool` alarm](https://github.com/codethread/notebook.spool/blob/main/bin/compat-alarm)
is the reference implementation.

The alarm catches behavior covered by the old suite; it does not classify changes or prove
compatibility. Authors still apply the contract rule above. Core validation has a different job: it
refuses family pins below declared floors and roots the consumer has not approved. A helper may run
the alarm before writing a bump, but the loader stays offline and never selects or fetches a newer
release.

### Author tests

Test two different facts:

1. Classpath tests prove each declared floor. Pin required roots and Skein at exactly the markers in
   `:requires` and `:skein/min`, not at newer convenient releases. A floor raise and its test-pin
   bump belong in one commit. A small in-repo check may resolve markers with `git ls-remote` and
   verify that those pins match the declared floors; this is helper or repository policy, not core.
2. Runtime integration tests prove the consumer path. Keep a literal consumer-workspace fixture
   whose `spools.edn` loads the spool under test through `:local/root` plus `:claims "vN"`, pins its
   requirements as ordinary families, and syncs them in an embedded runtime with `:publish? false`.

For cross-repository work, a tools.deps `:sibling` alias should override the same dependency that a
gitignored `spools.local.edn` family override replaces. The local family entry carries `:claims
"vN"`; the alias uses `:override-deps`. This symmetry lets both test tiers exercise the same sibling
checkout without weakening the committed floors. The
[`standup.spool` test setup](https://github.com/codethread/standup.spool) demonstrates both tiers.

### Open attribute vocabularies

Composition across release skew works best through open, namespaced attributes. Declare the
namespace owner and document each key's contract. A renderer must render unknown contributors in
that vocabulary instead of rejecting a closed set of contributor names. A spool may then add
`notebook.section/standup` without a release of the notebook spool.

The live precedent is workflow and agent-run composing through the `:subagent` relation: each spool
owns its behavior while the shared graph vocabulary carries the connection. This is an extension
point, not permission to mint keys in another spool's namespace; coordinate the vocabulary contract
with its owner.

### Release and bump sequence

There is no simultaneous three-repository landing ceremony in a sha-pinned world:

1. In the producer, make the change accretive or give the broken surface a new name. Run current
   tests and `bin/compat-alarm` against the previous marker.
2. Update `spool.edn` floors and their test pins together when a floor changes. Commit, create the
   next annotated `v<int>` tag, push it, and obtain its peeled commit sha.
3. In each consumer, change `:git/tag` and `:git/sha` atomically. Add a new root mapping only when
   opting into that root. Validate the whole consumer file before loading or landing the bump.
4. Land downstream changes in dependency order. Every unchanged consumer remains on its old sha;
   no upstream push can alter it.

If a consumer uses a new name, its code and family-entry bump land together. Do not delete the old
name as cleanup: pinned consumers may still rely on it, and bump-time validation is the place to
refuse a release that no longer contains an approved root.

### Nested-spool prerequisites

A repository that contains several spool roots still gets one family entry. Point `:local/root` at
the repository checkout, or use one sha-pinned Git coordinate, then map each approved library to its
relative path with `:roots`. A requiring spool names the library root and floor in `:requires`; the
consumer adds or bumps the family that owns that root. Do not create a separate `:deps/root`
coordinate for each nested root.

### Advisory `spool.edn`

Authors may publish this manifest at the repository root:

```clojure
{:spool/format 1
 :skein/min "v1"
 :roots {acme/priority {:root "priority"}
         acme/reports {:root "reports"}}
 :requires {skein.spools/workflow "v2"}}
```

This follows the package.el split. Authoring helpers may read it to prepare a consumer family entry;
the core loader never reads it. The committed `spools.edn` remains the consumer's explicit consent
record and the only input to load-boundary validation. A README should still show the full family
entry and activation order, so a consumer can review what a helper would write. No prerequisite is
fetched transitively.

The layering is deliberate. Core refuses a bad consumer world. Authoring helpers, including planned
batteries support, make good entries easier to write. Userland may replace those helpers, but it
cannot replace the load-boundary checks.

If a prerequisite is a blessed `skein.api.*.alpha` namespace or `skein.spools.batteries`, document
the namespace and why it is required but do not invent a family coordinate for it. Both ship on the
selected Skein classpath. Every other source repository gets its own family entry.

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

(runtime/use! rt :acme/priority
  {:ns 'acme.priority.alpha
   :spools '[acme/priority-spool]
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
  another source root, document that root's repository as a family entry in
  `spools.edn`.
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
{:spools {acme/a-spool {:local/root "spools/a"
                        :roots {acme/a "."}}
          acme/b-spool {:local/root "spools/b"
                        :roots {acme/b "."}}}
 :mvn-overrides {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}
```

Overrides are overlaid shared-then-local like `:spools` and use the same
Maven-only policy as spool-root `:deps`: Maven coordinates only, no mutable
versions, and no source-bearing coordinate keys.

## Local development overrides

Use the same family coordinate in shared `spools.edn` and gitignored `spools.local.edn` to develop
against a checkout while other users stay pinned to the git sha. Local entries overlay shared
entries by coordinate and must claim the release contract they preserve.

Shared `spools.edn`:

```clojure
{:spools
 {acme/priority-spool
  {:git/url "https://github.com/acme/skein-priority-spool.git"
   :git/sha "0123456789abcdef0123456789abcdef01234567"
   :git/tag "v3"
   :roots {acme/priority "priority"
           acme/reports "reports"}}}}
```

Developer-only `spools.local.edn`:

```clojure
{:spools
 {acme/priority-spool
  {:local/root "~/dev/projects/skein-priority-spool"
   :claims "v3"}}}
```

The overlay inherits the base family's `:roots`, `:requires`, and `:skein/min`; it replaces the
source coordinate. A missing `:claims` fails loudly. Run the local checkout's compatibility alarm
against the claimed marker to check the claim. The Maven-only dependency policy still applies to
every local override root.

**Caution: `sync!` resolves the current approved Maven universe.** Each call
reads the roots approved at that moment, validates their `deps.edn` files, and
resolves their Maven dependencies as one stateless universe. A root removed from
`spools.edn` is simply absent from the next resolution; there is no retained
root set to stub out. If a root is still approved but its directory was deleted
or moved, that root reports a per-spool missing/unreadable failure until you
update or restore the approved entry.

## Test mechanics

The floor-pinned and consumer-workspace tiers above use ordinary Clojure test tooling. Put the
spool's roots in `:paths`, its tests in a `:test` alias, and each required root in `:extra-deps` at
the peeled sha for its declared floor. Give the test namespace a `-main` that exits non-zero on
failure so `clojure -M:test` works in CI.

Use `skein.test.alpha/with-weaver-world` for the consumer-workspace tier and take the runtime it
hands you explicitly. Reach for `skein.core.weaver.runtime/with-runtime-binding` only when a test
must exercise userland code that resolves the ambient runtime, never the shared spool's own
functions. The general fixture API and isolation rules live in [Testing your config and
spools](./testing.md).

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
- External/shared spool source namespaces use the author's org prefix; codethread
  spools use `ct.spools.<name>`. The `skein.*` prefix is reserved for source
  shipped by the Skein checkout. A source namespace is separate from the
  `.skein/spools.edn` coordinate symbol, such as `codethread/<name>`.

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
