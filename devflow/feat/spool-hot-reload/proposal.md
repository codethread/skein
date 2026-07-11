# Spool hot-reload: a blessed verb for bumping an active spool's code

**Document ID:** `PROP-shr-001` **Last Updated:** 2026-07-11 **Related brief:** [brief.md](./brief.md) (scope is
the contract) **Kanban:** card `sae7i`. **Related root specs:** [REPL API](../../specs/repl-api.md)
(SPEC-003.C17/C18/C19 the `runtime.alpha` surface, `:112-121` the `sync!`/`reload!`/`use!` loader), [Alpha
Surface](../../specs/alpha-surface.md) (SPEC-005.C2 the blessed `skein.api.*.alpha` tier, C9 change discipline),
[Weaver Runtime](../../specs/daemon-runtime.md) (the spool classloader and `:local/root` sync). **Related sources:**
`src/skein/api/runtime/alpha.clj` (`sync!:20`, `reload!:30`, `use!:119`, `with-spool-classloader` use at `:135`),
`src/skein/core/weaver/spool_sync.clj` (`root-paths:168`, `synced-root-paths:525`, `ns-relative-path:519`,
`load-synced-namespace!:543`, `sync-approved-spool!:446`, `approved-spool-syncs:495`),
`src/skein/core/weaver/access.clj` (`with-spool-classloader:72`, `approved-spool-sync-state:57`),
`src/skein/core/weaver/runtime.clj` (`with-runtime-and-spool-classloader:314`, `reload-config!:273`),
`test/skein/spools_test.clj` (the sync/use! integration harness), `test/skein/spools/test_support.clj`
(`with-runtime`, `:publish? false`).

**Reading context.** This proposal assumes the vocabulary in `docs/skein.md` and `spools/README.md`. A *spool* is
trusted authorable Clojure loaded into the weaver under the `skein.spools.*` family; an *opt-in* spool is approved
in `spools.edn`, synced into the runtime's spool classloader by `runtime/sync!`, and activated by a
`:spools`-guarded `runtime/use!`. This feature adds one verb — `runtime/reload-spool!` — that makes an updated copy
of an already-synced-and-loaded spool's source *live*, the operation neither `reload!` nor `require :reload`
performs. The decision to add it is the card; this proposal designs its signature, failure modes, ordering, and
compose-with-`reload!` boundary. Every point ID is a grepable anchor; `file:line` citations were verified at
authoring in the `spool-hot-reload` worktree and are secondary to the named site.

## PROP-shr-001.P1 Problem

Once an opt-in spool is synced and its namespaces are loaded, nothing makes an updated copy of its source live
short of a weaver restart — which the hard rules forbid without user sign-off. The two runtime reloads both skip
loaded spool code, for different reasons:

- **`runtime/reload!` re-registers, but against stale code.** `reload!` (`alpha.clj:30` → `reload-config!`,
  `runtime.clj:273`) clears every weaver-lifetime registry and re-runs the startup files, so the spool's
  `activate!`/`install!` re-registers its ops, queries, and handlers. But it reloads *files*, not *namespaces*:
  "Reload does not unload already-loaded Clojure namespaces or vars" (`repl-api.md:114`). The spool's
  `skein.spools.<name>` namespace stays exactly as first loaded, so the re-registration binds to old code.
- **`require :reload` is classloader-blind to spool roots.** The CLAUDE.md pickup ladder's next rung is a targeted
  `(require 'the.ns :reload)`. That reloads a loaded namespace from the *base* classpath, but an opt-in spool's
  source lives only under the per-spool root that `sync!` adds to the runtime's spool classloader
  (`sync-approved-spool!` calls `repl-deps/add-libs` then `add-root-paths-to-spool-loader!`, `spool_sync.clj:446`).
  A bare `require :reload` runs on the calling thread's default loader, which has no such root, so for an opt-in
  spool it reloads nothing useful.

The observed workaround (kanban extraction 2026-07-11) was to reach into the internal core seam
`skein.core.weaver.access/with-spool-classloader` (`access.clj:72`) and hand-`load-file` each source path — done
twice during the `unify-spool-classpath` work. That leaks an internal loader boundary into an operator workflow,
with no consent check that the coordinate is actually approved-and-synced, no ordering guarantee across the spool's
several namespaces, and no data-first result to inspect.

The one existing namespace loader, `load-synced-namespace!` (`spool_sync.clj:543`), cannot be reused: it
deliberately short-circuits on `(find-ns ns-sym)` (`spool_sync.clj:550`) — a correct *load-once* primitive for
`use!` activation, and exactly the wrong behavior for reload. It is also keyed by namespace, where a spool is many
namespaces addressed by one coordinate.

## PROP-shr-001.G Goals

- **PROP-shr-001.G1:** One blessed verb, `skein.api.runtime.alpha/reload-spool!`, taking an explicit runtime
  (SPEC-003.C18) and a spool **coordinate symbol**, that `load-file`s the coordinate's synced-root namespace
  sources under the spool classloader so bumped code goes live in a running weaver.
- **PROP-shr-001.G2:** Fail loudly (TEN-003) and structurally on every unresolvable coordinate — unapproved,
  unsynced, sync `:failed`, missing root, or a root with no namespace sources — reusing the existing reason-keyword
  vocabulary rather than inventing parallel words.
- **PROP-shr-001.G3:** Correct reload order for cross-namespace macros: a namespace is reloaded after the
  namespaces (within the same root) whose macros it consumes.
- **PROP-shr-001.G4:** Minimal, focused surface (TEN-004): `reload-spool!` reloads *code* only and leaves
  registry re-registration to the caller; it does not compose a global `reload!`, does not touch `sync!`/`use!`
  semantics, and adds no CLI op.
- **PROP-shr-001.G5:** A contract that survives the owned-classloader redesign unchanged — the coordinate is the
  identity and "make its latest synced source live" is the promise; the load-file/classloader mechanics stay out of
  the contract.

## PROP-shr-001.NG Non-goals

- **PROP-shr-001.NG1:** No classloader ownership redesign (separate card). This rides the current tools.deps
  `add-libs` spool classloader; `reload-spool!` becomes that redesign's natural API, not its casualty.
- **PROP-shr-001.NG2:** No namespace *unload*. `reload-spool!` loads the current source set under the root; it does
  not track and remove namespaces a new revision deleted (no tools.namespace `refresh`-style dependency-tracked
  unload). A returning spool that renamed a namespace leaves the old one loaded until restart — acceptable for a
  dev-loop reload verb, and called out so no one expects otherwise.
- **PROP-shr-001.NG3:** No change to `sync!`, `reload!`, or `use!` semantics; `reload-spool!` is a new sibling.
  Re-syncing a git coordinate to a new sha is still `sync!`'s job; `reload-spool!` reloads whatever source the root
  currently holds.
- **PROP-shr-001.NG4:** No CLI op. Hot-reload is a trusted runtime/REPL workflow (TEN-006; PHILOSOPHY "runtime
  customization belongs in trusted startup files and REPL workflows"), driven from `mill weaver repl`, not the
  JSON control surface.

## PROP-shr-001.C1 — signature and placement (design question 1)

**`(skein.api.runtime.alpha/reload-spool! runtime coord)`** — explicit runtime first (SPEC-003.C18), `coord` the
`spools.edn` coordinate symbol (e.g. `skein.spools/kanban`, `demo/lib`). It returns a data-first map:

```clojure
{:coord      coord
 :root       "/abs/canonical/spool/root"
 :namespaces [skein.spools.a skein.spools.b]     ; reload order, dependencies first
 :reloaded   [{:ns skein.spools.a :file "…/a.clj"}
              {:ns skein.spools.b :file "…/b.clj"}]}
```

The blessed fn stays thin (TEN-006 "keep the CLI thin", and the same discipline for the alpha tier over core): it
validates that `coord` is a symbol and delegates the mechanics to a new **`skein.core.weaver.spool-sync/reload-
synced-spool!`**, exactly as `sync!`→`sync-approved-spools` (`alpha.clj:20`) and `reload!`→`reload-config!`
(`alpha.clj:30`) delegate today. Placement follows SPEC-003.C17: `runtime.alpha` is where blessed spool-workspace
loader/config helpers live, and this is one. The docstring names the gap it fills (neither `reload!` nor
`require :reload` reloads synced spool code) so `make api-docs` publishes the distinction.

**`coord` is the coordinate, not a namespace**, because a spool is many namespaces and sync state
(`approved-spool-sync-state`, `access.clj:57`) is keyed by coordinate. This is why `reload-spool!` cannot reuse the
`use!` `:ns` loader shape (`load-synced-namespace!`) and needs its own root-scoped, multi-namespace path.

## PROP-shr-001.C2 — resolving the synced root and the failure modes (design question 2)

`reload-synced-spool!` resolves the root from *sync state*, not from the approved config alone, because a coordinate
can be approved yet unsynced or sync-failed. It reads `@(approved-spool-sync-state runtime)` (the same atom
`synced-root-paths` reads, `spool_sync.clj:525`) and the approved allowlist `(approved-spools runtime)`, and fails
loudly at the first unmet precondition. **The ex-info reasons reuse the vocabulary already in the code** —
`use-spool-skip` emits `:not-approved`/`:not-synced`/`:sync-failed` (`alpha.clj:88`) and `sync-failed` emits
`:missing-root` (`spool_sync.clj:162`) — so operators and tests see one consistent reason set:

| precondition | check | reason | ex-info data |
| --- | --- | --- | --- |
| `coord` is a symbol | `(symbol? coord)` | — | `{:coord coord}` msg "reload-spool! coordinate must be a symbol" |
| approved | `coord` ∈ `(:spools (approved-spools runtime))` | `:not-approved` | `{:coord coord :approved (sorted keys)}` |
| synced | `coord` ∈ `@sync-state` | `:not-synced` | `{:coord coord :synced (sorted keys)}` |
| sync succeeded | entry `:status` ∈ `#{:loaded :already-available}` | `:sync-failed` | `{:coord coord :status … :sync entry}` |
| root on disk | `(.isDirectory (io/file (:root entry)))` | `:missing-root` | `{:coord coord :root …}` |
| has sources | `(seq namespace-decls)` | `:no-namespaces` | `{:coord coord :root … :searched-roots …}` |

Every throw is `(ex-info "<message>" (assoc data :reason <kw>))`, mirroring `sync-failed`'s
`{:status :failed :reason …}` shape so a caller can `(:reason (ex-data e))`-dispatch. The `:status` gate uses the
*same* `#{:loaded :already-available}` set `synced-root-paths` (`spool_sync.clj:527`) already treats as "root is on
the classpath" — reusing that predicate keeps reload's notion of "synced" identical to the loader's, so a root
`reload-spool!` accepts is exactly a root whose namespaces `use!` could have loaded.

**The `:no-namespaces` case fails loudly rather than returning an empty reload.** A synced spool root with zero
`.clj` under its `:paths` is almost certainly a misconfigured root or a `:deps/root` typo, not a legitimate no-op;
TEN-003 says surface it, not swallow it. (This is the one reason keyword with no prior twin; it is named for the new
condition the multi-namespace discovery introduces.)

## PROP-shr-001.C3 — namespace discovery and reload order (design question 3)

**Discovery.** The root's classpath dirs come from `root-paths` (`spool_sync.clj:168`) — the same fn `sync` and the
`use!` loader use, which reads the root's `deps.edn :paths` (defaulting to `["src"]`) and guards every entry against
`..`/symlink escape. Every `.clj`/`.cljc` under those dirs is a namespace source of this spool. This reuses the
approved-root path resolution wholesale, so reload's file set is exactly the consented classpath, no wider.

**Order matters, and arbitrary order is wrong.** Clojure macros expand at *compile* time. If root namespace `b`
defines a macro that namespace `a` uses (`a` `:require`s `b`), reloading `a` before `b` recompiles `a` against the
*old* macro — `require` inside `a`'s reloaded `ns` form is a no-op because `b` is already loaded — and then
reloading `b` cannot retroactively fix `a`'s expansion. So `a` must be reloaded *after* `b`: dependency order,
dependencies first. Alphabetical order is a coin-flip on this; "any order + let `require` drive it" is the same
coin-flip because `require` never re-runs an already-loaded dependency.

**Recommendation: order by an intra-root dependency graph via `org.clojure/tools.namespace`.** Use its
`clojure.tools.namespace.find`/`parse` to read each source's `ns` declaration and its `clojure.tools.namespace.
dependency`/`track` to topologically sort *the namespaces within this root only* (external requires — `clojure.*`,
blessed `skein.api.*`, other spools — are edges out of the set and are not reloaded), then `load-file` in that
order. We drive `load-file` ourselves under the spool classloader; we do **not** use tools.namespace `refresh`,
because `refresh` tracks the whole classpath against file mtimes on the base loader and is blind to spool-
classloader roots — the exact failure mode of `require :reload` (C1/P1). tools.namespace is the battle-tested `ns`-
form parser (prefix lists, `:require`/`:use`, reader conditionals, string-vs-symbol libspecs); re-implementing that
parser in core is more surface we own and must make robust, against TEN-004. It is a single well-established
`org.clojure` dependency added to the weaver's own `deps.edn` (not a spool dep), proportionate to the parsing it
saves. See Q3 for the hand-rolled alternative and its cost.

**Load-file, not `require :reload`.** Each ordered namespace is loaded with `load-file` on its located source path,
inside `skein.core.weaver.access/with-spool-classloader` (`access.clj:72` → `with-runtime-and-spool-classloader`,
`runtime.clj:314`, which installs the spool classloader as context loader *and* rebinds `Compiler/LOADER` so
require/load see synced sources). This matches `load-synced-namespace!`'s existing `load-file` mechanism
(`spool_sync.clj:555`) and the startup-file loader (`runtime.clj:245`), and pins the exact source path rather than
trusting classpath resource resolution to pick the spool root over any shadow. `require :reload-all` is rejected in
Q3: it reloads transitive non-spool dependencies too.

## PROP-shr-001.C4 — compose with `reload!`, or leave re-registration to the caller? (design question 4)

**Recommendation: `reload-spool!` reloads code only; the caller re-registers.** It does not call `reload!`, and
`reload!` does not call it. The two are *complementary halves* of a hot bump:

- `reload-spool!` makes the spool's namespace **code** live — the half `reload!` (`repl-api.md:114`) skips.
- `reload!` re-runs the startup files so `activate!`/`install!` re-registers ops/queries/handlers — the half
  `reload-spool!` deliberately does not touch, since it holds no opinion about which registrations a spool owns.

The blessed sequence for a code bump is therefore `reload-spool! coord` *then* `reload!` (or, for a spool whose
activation is one `use!` `:call`, re-run that `use!`) — the caller composes them at their own granularity. This
mirrors the CLAUDE.md pickup ladder, which already sequences a targeted `require :reload` *before* `reload!` for the
same reason; `reload-spool!` is the correct rung to replace that ineffective `require :reload` for opt-in spools.

**Cost of the rejected alternative (auto-compose `reload!` inside `reload-spool!`):** it turns a surgical "bump this
one spool's code" into a **global** operation — `reload-config!` clears *every* registry and re-runs *every* spool's
activation and the whole `init.clj`, so bumping one spool tears down and rebuilds unrelated userland ops, queries,
and event handlers, and re-fires the scheduler rearm. That is precisely the blast-radius the pickup ladder tells
operators to avoid, and it violates TEN-004 (a focused verb should not force a global side effect). It would also
make `reload-spool!` un-composable: a caller wanting *only* fresh code (e.g. to inspect a redefined var before
re-registering) could not get it. Keeping the halves separate is strictly more flexible and matches the established
ladder.

## PROP-shr-001.C5 — forward-compatibility with the owned-classloader redesign (design question 5)

The card requires this verb to stay compatible with a future owned-classloader redesign (separate refinement card).
The contract that guarantees it: **the coordinate is the identity and "make its latest synced source live" is the
promise** — the load-file-under-`with-spool-classloader` mechanism is an implementation detail of
`reload-synced-spool!` in core, never named in the `runtime.alpha` signature, docstring behavior, or the return
map's *meaning* (the return names namespaces and files, which any loader can report). When the owned-classloader
work lands, `reload-synced-spool!`'s body swaps to "reload the coordinate's namespaces in its owned loader"; the
blessed `reload-spool!` signature, failure vocabulary (C2), and dependency-ordered result (C3) are unchanged. This
is the same core-owns-mechanics / alpha-owns-contract split (TEN-007) that lets `sync!` change its tools.deps
internals without moving the `runtime/sync!` contract. Building `reload-spool!` now, on the current mechanism, is
therefore not throwaway: it is the redesign's API surface, delivered early.

## PROP-shr-001.C6 — spec and doc deltas (design question 6)

- **`devflow/specs/repl-api.md` (the owning root spec, SPEC-005.C9).** Add a `runtime.alpha` bullet beside the
  `sync!`/`reload!`/`use!` list (`:112-115`) specifying `(runtime/reload-spool! runtime coord)`: resolves the
  coordinate's synced root, `load-file`s its namespace sources under the spool classloader in dependency order, and
  fails loudly with `:not-approved`/`:not-synced`/`:sync-failed`/`:missing-root`/`:no-namespaces`. Extend the
  SPEC-003.C17 enumeration sentence (`:60`) to include spool-code hot-reload among the `runtime.alpha` helpers. Add
  a sentence to the `:114` reload paragraph clarifying that `reload!` still does not unload namespaces and that
  `reload-spool!` is the code-reload companion for synced spools.
- **`devflow/specs/alpha-surface.md`.** SPEC-005.C2 (`:12`) already lists `runtime` in the blessed tier; adding a fn
  is accretion *within* the subnamespace, so C2's text needs no change. Per SPEC-005.C9 the delta lands in the
  owning root spec (repl-api.md), and this index is touched only if tier membership moves — it does not. Note this
  explicitly in the plan so no one adds a spurious C2 edit.
- **`devflow/specs/daemon-runtime.md`.** Reaffirmed, not changed: `reload-spool!` leans on the existing spool
  classloader and `:local/root`/git sync (the C41/C42/C91 machinery) without altering them. The plan records that no
  daemon-runtime clause changes; a one-line cross-reference that the spool classloader also backs `reload-spool!` is
  optional, a spec-plan call.
- **`docs/skein.md` / `CLAUDE.md` pickup ladder.** The ladder's "already-loaded Clojure namespaces need a targeted
  `(require 'the.ns :reload)`" rung is wrong for opt-in spools (P1); the implementation feature updates it to name
  `runtime/reload-spool!` for synced spools. Guidance-doc change, run through the docs-style gate.
- **`make api-docs`.** Regenerate `docs/api/*.api.md` after the `runtime.alpha` docstring lands (blocking `make
  docs-check`).

## PROP-shr-001.V Validation gates

- **Cold-focused (per-slice Done-when):** `clojure -M:test skein.spools-test` — the home of the `sync!`/`use!`
  integration tests, which already carries the disposable-runtime harness (`with-runtime`, `write-local-lib!`,
  `write-spools!`, `write-spool-ns!`). If the graph-ordering logic warrants isolation, a focused
  `skein.runtime-reload-test` sibling; the spec-plan decides. Add `skein.api.spool-test`-style coverage only if the
  alpha fn grows validation beyond the symbol check.
- **Test approach (`:publish? false` discipline).** `with-runtime` (`test_support.clj`) yields a fresh runtime
  thread-bound but **unpublished** (`:publish? false` default), and `reload-spool!` takes that runtime explicitly —
  no ambient singleton is read (matching the runtime-publication discipline). Core cases:
  1. **Reload picks up bumped code (the keystone).** `write-local-lib!` a spool whose fn returns `:v1`;
     `write-spools!` a coordinate; `runtime/sync!`; `runtime/use!` to load it; assert the fn returns `:v1`. Rewrite
     the source to return `:v2`. Assert `runtime/reload!` and a bare `(require ns :reload)` still see `:v1` (the gap,
     as a regression guard). Then `runtime/reload-spool! coord`, assert the fn returns `:v2` and the result's
     `:reloaded` names the namespace.
  2. **Dependency order.** Two namespaces in one root, `a` using a macro from `b`; bump `b`'s macro, `reload-spool!`,
     assert `a` reflects the new expansion — proving `b` reloads before `a` and that arbitrary order would fail.
  3. **Failure modes.** `thrown-with-msg?` / `ex-data :reason` for each of `:not-approved` (unapproved coord),
     `:not-synced` (approved, not synced), `:sync-failed` (coordinate whose root fails to sync), `:missing-root`
     (synced then root deleted), `:no-namespaces` (root with empty `:paths` dir).
- **Suite/quality gates:** full locked suite `flock -w 3600 /tmp/skein-test.lock clojure -M:test` at queue
  acceptance and land; `(cd cli && go test ./...)` and `clojure -M:smoke` (no CLI/bootstrap change expected, so these
  are regression insurance); `make fmt-check lint reflect-check docs-check` at zero findings; `make api-docs` clean
  regen with `git status --short` showing only expected `*.api.md` changes.

## PROP-shr-001.DW Done-when

- **DW1:** `skein.api.runtime.alpha/reload-spool!` exists, takes `(runtime coord)`, and delegates to
  `skein.core.weaver.spool-sync/reload-synced-spool!`.
- **DW2:** It `load-file`s the coordinate's synced-root namespace sources under `with-spool-classloader` in
  dependency order, and its reload picks up bumped spool code that `reload!` and `require :reload` do not (proven by
  the keystone test).
- **DW3:** Every unresolvable-coordinate case fails loudly with the reused reason vocabulary
  (`:not-approved`/`:not-synced`/`:sync-failed`/`:missing-root`/`:no-namespaces`).
- **DW4:** `repl-api.md` documents the verb and its accretion status; `alpha-surface.md`/`daemon-runtime.md`
  confirmed unchanged-beyond-reaffirmation; the pickup-ladder guidance updated.
- **DW5:** Cold-focused, full suite, Go, smoke, and fmt/lint/reflect/docs gates green; `make api-docs` regenerated
  clean.

## PROP-shr-001.Q Design decisions (alternatives considered)

- **PROP-shr-001.Q1 — coordinate symbol or namespace symbol? (resolved: coordinate, C1).** A namespace argument
  would let `reload-spool!` reuse the `use!` `:ns` loader shape, but a spool is many namespaces and its identity in
  sync state is the coordinate. **Adopted: the coordinate**, reloading all of the root's namespaces as one unit;
  taking a namespace would push the operator to enumerate a spool's namespaces by hand — the very tedium this verb
  removes.
- **PROP-shr-001.Q2 — compose `reload!`, or leave re-registration to the caller? (resolved: leave it, C4).**
  Auto-composing `reload!` would make one call both reload code and re-register, but at the cost of a global
  registry teardown for a one-spool intent (TEN-004) and an un-composable verb. **Adopted: code-only reload**, with
  `reload!` (or a targeted re-`use!`) as the caller's complementary second step, matching the pickup ladder.
- **PROP-shr-001.Q3 — order by tools.namespace, hand-rolled parse, or `require :reload-all`? (resolved:
  tools.namespace, C3).** `require :reload-all` reloads transitive *non-spool* dependencies (clojure.core-adjacent
  libs, blessed api) — over-broad and slow. A hand-rolled `ns`-form parser avoids a new dependency but re-implements
  prefix-list/reader-conditional/`:use` parsing we must then make robust (TEN-003), more owned surface than TEN-004
  wants. **Adopted: `org.clojure/tools.namespace` parse+dependency for the intra-root topo-sort, driving our own
  `load-file`** — never its classloader-blind `refresh`. One established `org.clojure` weaver dependency, buying a
  battle-tested parser.
- **PROP-shr-001.Q4 — `load-file` or `require :reload` per namespace? (resolved: `load-file`, C3).** A per-namespace
  `(require ns :reload)` under `with-spool-classloader` might resolve the spool root via classloader resource
  lookup, but load order still matters (Q3) and resource resolution could pick a shadow over the intended root.
  **Adopted: `load-file` on the located source path**, matching `load-synced-namespace!` and the startup-file loader,
  which pins the exact file.
- **PROP-shr-001.Q5 — unload deleted namespaces? (resolved: no, NG2).** tools.namespace `refresh` tracks and unloads
  removed namespaces, but that requires whole-classpath mtime tracking on the base loader (blind to spool roots) and
  a dependency-tracked unload this dev-loop verb does not need. **Adopted: load the current source set only**; a
  renamed/removed namespace lingers until restart, called out as a non-goal.
