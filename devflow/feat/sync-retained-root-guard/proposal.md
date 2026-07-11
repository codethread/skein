# Sync retained-root guard: fail loudly when a session-retained spool root is deleted

**Document ID:** `PROP-srr-001` **Last Updated:** 2026-07-11 **Related brief:** [brief.md](./brief.md) (scope is
the contract) **Kanban:** card `pn7wh` (p2). **Extracted from:**
[`unify-spool-classpath`](../unify-spool-classpath/proposal.md) (`PROP-usc-001`), the "own the spool classpath"
concern — this card is the mitigation; the classloader redesign is that card's. **Related root specs:**
[Weaver Runtime](../../specs/daemon-runtime.md) (SPEC-004, the `spools.edn` allowlist and `sync!` semantics),
[Alpha Surface](../../specs/alpha-surface.md) (SPEC-005.C5, the `skein.api.runtime.alpha`
`approved`/`sync!`/`syncs` delegation to `spool-sync`). **Related sources:**
`src/skein/core/weaver/spool_sync.clj` (`sync-approved-spools`, `sync-approved-spool!`,
`add-root-paths-to-spool-loader!`), `src/skein/api/runtime/alpha.clj` (`sync!`, `reload!`),
`src/skein/core/weaver/runtime.clj` (`reload-config!`, `clear-reload-state!`),
`clojure/repl/deps.clj` (`add-libs`), `clojure/java/basis.clj` (`current-basis`),
`clojure/java/basis/impl.clj` (`the-basis`, `update-basis!`), `test/skein/runtime_deps_test.clj`
(`keep-add-libs-root!`).

**Reading context.** A *spool* is trusted authorable Clojure approved in `spools.edn` and loaded into the weaver
by `runtime/sync!`. `sync!` loads each approved `:local/root` by handing it to `clojure.repl.deps/add-libs`, which
mutates a *process-global* runtime basis that persists for the JVM lifetime — the "session" in this document is one
weaver JVM lifetime, and a "session-retained root" is a `:local/root` that any prior `add-libs` in that JVM merged
into that basis. This feature adds one preflight to `sync!` and one loud failure; it changes no loader, no
classloader, and no healthy-path behavior. Every point ID is a grepable anchor; source citations name a stable site
(a fn, a config key) and any `file:line` is secondary, verified at authoring in the `sync-retained-root-guard`
worktree.

## PROP-srr-001.P1 Problem

`skein.api.runtime.alpha/sync!` (`runtime/alpha.clj:20-23`) delegates to
`skein.core.weaver.spool-sync/sync-approved-spools` (`spool_sync.clj:484-493`), which resets skein's
`:approved-spool-sync-state` atom, then maps `sync-approved-spool!` over the approved allowlist. For each approved
local root, `sync-approved-spool!` (`spool_sync.clj:446-482`) calls, inside `with-spool-classloader`:

```clojure
;; spool_sync.clj:466-471
(repl-deps/add-libs {lib {:local/root (:root entry)}})
```

`add-libs` is not a per-lib operation. Its implementation (`clojure/repl/deps.clj:35-57`) reads the current runtime
basis and re-resolves against the **entire retained universe**:

- `clojure/repl/deps.clj:41` — `(basis/current-basis)` derefs the process-global runtime basis and binds its
  `:libs` (every lib added so far this JVM, plus the launch basis).
- `clojure/repl/deps.clj:48` — the tool call is
  `{:existing libs, :add lib-coords, :procurer procurer}` → `clojure.tools.deps/resolve-added-libs`. The **whole**
  retained `:libs` map is passed as `:existing`; `resolve-added-libs` re-canonicalizes it alongside the one new
  coordinate.
- `clojure/repl/deps.clj:53` — `(basis-impl/update-basis! update :libs merge added)` merges the newly-resolved libs
  back into the global basis. `update-basis!` (`clojure/java/basis/impl.clj:48-51`) `swap!`s
  `@the-basis`, a delay-wrapped process-global atom (`impl.clj:44-46`). This is the JVM-lifetime retention: nothing
  in skein or tools.deps evicts a `:local/root` once added.

**The failure.** When a session-retained local root is deleted from disk, the *next* `add-libs` — for any spool,
including an unrelated one — re-canonicalizes the deleted path while processing `:existing` and throws. The thrown
error names the stale retained lib and its old path, not the spool being synced. `sync-approved-spool!` catches it
into a `:runtime-add-failed` sync result (`spool_sync.clj:478-482`) attributed to the *new* lib, so the recorded
failure blames the wrong spool. Observed on the live canonical weaver: deleting the vendored `skein.spools/kanban`
root bricked `reload!`; the subsequent `codethread/kanban` sync failed with an error citing the deleted
`skein.spools/kanban` path. The remedy — recreate a stub directory at the deleted path so canonicalization succeeds
until the next restart clears `the-basis` — had to be reverse-engineered from the stack trace.

**Why skein cannot already see this.** `reload!` (`runtime/alpha.clj:30-33`) → `reload-config!`
(`runtime.clj:273-303`) → `clear-reload-state!` (`runtime.clj:262-271`) resets skein's
`:approved-spool-sync-state` atom to `{}` on every reload, but does **not** touch tools.deps' `the-basis`. So after
a reload skein's own sync state is empty while the retained universe is intact: the deleted-root fact lives only in
`(:libs (clojure.java.basis/current-basis))`, which `sync!` never inspects before calling `add-libs`. The existing
per-entry existence check (`spool_sync.clj:454`, `(not (.exists root-file))` → `:missing-root`) guards only the
root of the entry *being synced*, never the retained universe that poisons the `add-libs` call.

This retention hazard is already known in-repo: `test/skein/runtime_deps_test.clj` carries `keep-add-libs-root!`
whose comment states "tools.deps keeps add-libs local roots in JVM-global basis state. Retaining temp spool roots
prevents later add-libs calls in this shard from failing while canonicalizing an earlier, now-deleted local/root
coordinate." The test works *around* the hazard; this feature makes it a diagnosable failure.

## PROP-srr-001.G Goals

- **PROP-srr-001.G1:** A preflight in `sync-approved-spools` that, before any `add-libs` runs, detects
  session-retained `:local/root` libs whose path is missing from disk.
- **PROP-srr-001.G2 (fail loudly, TEN-003):** On detection, throw an `ex-info` whose message and data name (a) the
  retained lib coordinate, (b) the deleted path, and (c) both remedies — stub directory (until next restart) or
  weaver restart — so the operator gets the failing value and the allowed alternatives without reading a stack
  trace.
- **PROP-srr-001.G3 (TEN-004, minimal surface):** One preflight, one failure. No new sync-state field, no new
  config key, no change to `add-libs` call sites, `use!`, the classloader, or the per-entry `:missing-root` path.
- **PROP-srr-001.G4:** Healthy syncs — every retained root still present on disk — behave exactly as today.

## PROP-srr-001.NG Non-goals

- **PROP-srr-001.NG1 — no classloader / retained-universe redesign.** The root cause is that `add-libs`
  re-canonicalizes the whole retained `:libs` on every add and never evicts a `:local/root`. Fixing that (an
  isolated spool classloader that skein controls, or per-sync basis scoping) is the "own the spool classpath"
  refinement card's, not this one (brief "Deliberately not built").
- **PROP-srr-001.NG2 — no auto-repair.** The preflight does not create the stub directory, prune the retained
  basis, or restart the weaver. TEN-002/TEN-003: it names the failing value and the allowed remedies and stops; the
  operator chooses.
- **PROP-srr-001.NG3 — no change to healthy-root behavior or to the per-entry `:missing-root` result**
  (`spool_sync.clj:454`). That check guards the entry being synced; the preflight guards the disjoint retained
  universe. Neither absorbs the other.
- **PROP-srr-001.NG4 — no eviction of the retained basis.** This feature reads `the-basis`; it never mutates it.
  Mutating a process-global tools.deps atom to "unretain" a root is exactly the redesign NG1 defers.

## PROP-srr-001.C1 — detection point and what "session-retained root" means concretely

**"Session-retained root" is a lib in `(:libs (clojure.java.basis/current-basis))` carrying a `:local/root`.**
Skein adds exactly these at `spool_sync.clj:471` (`{lib {:local/root (:root entry)}}`); after resolution the
basis `:libs` entry for that lib retains its `:local/root` value (the canonical path skein passed) plus resolved
`:paths`. The universe also includes any local root from the launch basis (`clojure.basis` system property) and any
Maven-resolved deps, which carry no `:local/root` and are therefore out of scope. The detection predicate is
narrow: **a `:libs` entry that has a `:local/root` whose `io/file` no longer `.exists`.**

**Detection point: the top of `sync-approved-spools` (`spool_sync.clj:484-493`), once per sync, before the
`sync-approved-spool!` map.** This is the right seam for three reasons:

- It runs before *any* `add-libs` (`spool_sync.clj:466-471`), so the loud skein failure precedes the misleading
  tools.deps canonicalization error. A per-entry preflight would fire N times and still race the first `add-libs`.
- It is sync-scoped, not entry-scoped: the retained universe is a property of the JVM, not of any one approved
  entry, so it is checked once per `sync!` regardless of how many roots the allowlist holds.
- `sync-approved-spools` already owns the sync lifecycle (it resets `:approved-spool-sync-state` at
  `spool_sync.clj:487`); the preflight is a sibling step, keeping the retained-root concern in the one namespace
  that drives `add-libs`.

**Reading the basis.** The preflight reads `(:libs (clojure.java.basis/current-basis))` — the same accessor
`add-libs` itself reads (`clojure/repl/deps.clj:41`), so it sees precisely the universe `resolve-added-libs` is
about to re-canonicalize. `current-basis` (`clojure/java/basis.clj:43-47`) derefs `@@the-basis`; when the process
was not launched by the CLI the basis may be nil/empty, in which case there are no retained local roots and the
preflight is a no-op (G4). No dependency is added: `clojure.java.basis` and `clojure.repl.deps` are already on the
runtime classpath (skein calls `repl-deps/add-libs` today).

**Scope guard vs. the per-entry check.** The existing `(not (.exists root-file))` at `spool_sync.clj:454` inspects
`(:root entry)` for the entry being synced and records a soft `:missing-root` sync result. The preflight is
complementary and disjoint: it inspects *other* retained roots — possibly libs no longer in `spools.edn` at all
(e.g. the deleted `skein.spools/kanban` after the allowlist moved to `codethread/kanban`) — and fails the whole
sync loudly, because such a root breaks the next `add-libs` for every entry, not just its own.

## PROP-srr-001.C2 — the error shape (TEN-003)

On finding one or more retained-but-deleted local roots, the preflight throws before the map. The `ex-info` gives
the operator the failing value and the allowed alternatives (TEN-003 "operator gets failing value and allowed
alternatives"):

- **Message:** states that a session-retained spool root was deleted from disk and that `sync!` cannot proceed
  because tools.deps will re-canonicalize the retained root on the next `add-libs`. It names the retained lib and
  the deleted path in prose so the stack-trace-free reader sees the cause.
- **Data (`ex-data`):**
  - `:missing-roots` — a vector of `{:lib <retained lib symbol> :local/root <deleted path string>}`, one per
    retained root found missing. A vector, not a single value, because more than one retained root can be gone; the
    operator gets all of them at once rather than failing, fixing one, and re-failing.
  - `:remedy` — the allowed alternatives, named, not applied (NG2):
    - `:stub-dir` — recreate a directory at each deleted path (a bare directory suffices for canonicalization);
      effective until the next weaver restart clears the retained basis.
    - `:restart` — restart the weaver JVM, which discards `the-basis` and its retained roots; the durable fix when
      the root is gone for good and its lib has left the allowlist.
  - `:retained-universe-source` — the accessor the operator (or a future refinement) can inspect,
    `clojure.java.basis/current-basis` `:libs`, so the diagnostic is self-describing.

The exception is thrown from `sync-approved-spools`, so it propagates through `sync!` and, on the reload path,
through `reload-config!` (`runtime.clj:282`) exactly like any other startup-file failure — `reload-config!`
already leaves partially-loaded state in place and rethrows loudly (`runtime.clj:294-303`), so no reload-path change
is needed. Unlike a `:runtime-add-failed` sync result (`spool_sync.clj:478-482`), the preflight failure is
attributed to the actually-broken retained lib, not to whichever entry `add-libs` happened to be processing.

## PROP-srr-001.C3 — implementation sketch

A private helper in `spool-sync` plus a two-line call in `sync-approved-spools`. Shape (spec-plan owns the final
form):

```clojure
(defn- retained-missing-local-roots
  "Session-retained :local/root libs whose path no longer exists on disk."
  []
  (into []
        (keep (fn [[lib coord]]
                (when-let [root (:local/root coord)]
                  (when-not (.exists (io/file root))
                    {:lib lib :local/root root}))))
        (:libs (clojure.java.basis/current-basis))))
```

`sync-approved-spools` gains, before the map at `spool_sync.clj:489`:

```clojure
(when-let [missing (seq (retained-missing-local-roots))]
  (throw (ex-info "<names lib + path + remedy>"
                  {:missing-roots (vec missing)
                   :remedy {:stub-dir "..." :restart "..."}
                   :retained-universe-source 'clojure.java.basis/current-basis})))
```

`clojure.java.basis` is added to the `spool-sync` `ns` `:require`. No other call site changes.

## PROP-srr-001.C4 — test approach (cold focused)

Namespaces touched: `src/skein/core/weaver/spool_sync.clj` (implementation) and
`test/skein/runtime_deps_test.clj` (tests — it already owns add-libs-against-a-live-weaver coverage and documents
this retention hazard in `keep-add-libs-root!`). Cold focused gate:

```sh
clojure -M:test skein.runtime-deps-test
```

Cases:

- **Retained-but-deleted fails loudly.** Sync an approved local root into a live test weaver (established pattern in
  `runtime_deps_test.clj`: `write-hot-lib!` + a `spools.edn`), delete the root directory from disk, then run
  `sync!`/`sync-approved-spools` again and assert `thrown-with-msg?` `ex-info` whose `ex-data` `:missing-roots`
  names the retained lib and the deleted `:local/root`, and whose `:remedy` names `:stub-dir` and `:restart`.
- **Healthy retained roots are a no-op (G4).** With every retained root present, `sync!` runs unchanged and records
  the normal `:loaded`/`:already-available` sync results — the preflight adds no failure.
- **Multiple missing roots are all reported.** Two retained roots deleted → `:missing-roots` has both entries, so
  the operator is not forced into fix-one-re-fail iteration.
- **Stub-dir remedy unblocks (documents the remedy).** After the failure, recreate a bare directory at the deleted
  path and assert the preflight passes again — pinning the `:stub-dir` remedy the error advertises. (This exercise
  runs in an isolated worktree/temp world so a deleted-then-stubbed retained root does not leak into a sibling test
  shard's global basis, consistent with `keep-add-libs-root!`'s existing shard-isolation caution.)

Per the full-suite discipline, the cold focused run above is the slice gate; `flock … clojure -M:test` runs only at
queue acceptance and land. No Go, smoke, or api-docs surface changes (docs-only tenets aside): the CLI surface,
generated docs, and bootstrap are untouched.

## PROP-srr-001.V Validation gates

- `make build`, then `clojure -M:test skein.runtime-deps-test` green (C4).
- `make fmt-check lint reflect-check` at zero findings.
- No Go/smoke/api-docs deltas: no CLI, bootstrap, or spool-classpath change (C3/NG3).
- `git status --short` clean of generated SQLite and runtime-metadata artifacts.

## PROP-srr-001.DW Done-when

- **DW1:** `sync-approved-spools` runs a retained-root preflight reading `(:libs (basis/current-basis))` before any
  `add-libs`, and a session-retained `:local/root` missing from disk fails `sync!`/`reload!` loudly.
- **DW2:** The failure `ex-info` names the retained lib, the deleted path, and both remedies (stub-dir, restart) in
  message and `ex-data` (C2).
- **DW3:** Healthy syncs and the per-entry `:missing-root` path are unchanged (G4/NG3); no classloader, `use!`, or
  config surface changed (NG1).
- **DW4:** Cold focused tests in `test/skein/runtime_deps_test.clj` cover deleted-root failure, healthy no-op,
  multi-root reporting, and the stub-dir remedy; fmt/lint/reflect green.

## PROP-srr-001.Q Design decisions (alternatives considered)

- **PROP-srr-001.Q1 — preflight vs. wrapping the `add-libs` failure? (resolved: preflight, C1).** Catching the
  tools.deps canonicalization error inside `sync-approved-spool!` and rewriting it would still fire *after*
  `add-libs`, would depend on parsing tools.deps' error shape (brittle across versions), and would attribute the
  failure to whichever entry was mid-sync — the exact mis-attribution the brief flags. **Adopted: a preflight that
  reads the same basis `add-libs` reads and fails first, naming the real culprit.**
- **PROP-srr-001.Q2 — where to read retained roots: skein's sync-state or tools.deps' basis? (resolved: the basis,
  C1).** Skein's `:approved-spool-sync-state` records `:root` per synced lib (`sync-result-base`,
  `spool_sync.clj:155-160`), but
  `clear-reload-state!` (`runtime.clj:262-271`) empties it every reload while the retained universe survives — so
  sync-state is blind to exactly the cross-reload case that bricked the live weaver. **Adopted:
  `(:libs (clojure.java.basis/current-basis))`, the authoritative retained universe and the same value
  `resolve-added-libs` re-canonicalizes.**
- **PROP-srr-001.Q3 — fail the whole sync or record a per-lib sync result? (resolved: fail loudly, C2).** A soft
  `:failed` sync result (like `:runtime-add-failed`) would let the poisoned sync limp on, and the next `add-libs`
  would still throw the misleading tools.deps error. A retained-but-deleted root breaks the *whole* `add-libs`
  universe, not one entry, so degrading it to a per-entry status understates the blast radius. **Adopted: throw
  from `sync-approved-spools` (TEN-003), aborting the sync with a named cause and named remedies.**
- **PROP-srr-001.Q4 — auto-stub or prune the retained basis? (resolved: report only, NG2/NG4).** The preflight
  *could* `mkdir` the deleted path or `update-basis!` the stale lib out. Both are the redesign this card explicitly
  defers, and both mutate global/on-disk state on the operator's behalf without sign-off. **Adopted: name the
  remedy, do not apply it** — the stub-vs-restart choice is the operator's, and the durable fix (classloader
  ownership) is the refinement card's.
