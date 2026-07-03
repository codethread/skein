# Test Concurrency and Multi-Runtime JVM Support

**Document ID:** `RFC-016`
**Status:** Implemented
**Date:** 2026-07-03
**Related:** [Weaver Runtime](../specs/daemon-runtime.md), [REPL API](../specs/repl-api.md), [Library Author Testing RFC](2026-06-26-library-author-testing.md), [Library Author Testing feature](../feat/library-author-testing-support/proposal.md), [Shuttle spool](../../spools/shuttle/README.md), [Chime spool](../../spools/chime/README.md)

## RFC-016.P1 Problem

`clojure -M:test` runs 333 tests / 1796 assertions strictly sequentially
(~34s wall on the reference machine, ~131% CPU). Every weaver-backed test pays
full runtime start/stop serially. This slows all validation loops (agent
delegation gates included), and the underlying constraint — **one weaver
runtime per JVM** — also blocks `library-author-testing-support`: a
`skein.test.alpha/with-weaver-world` helper cannot be safely nested or
parallelized while `runtime/start!` throws when any runtime is already active.

Verified current state (2026-07-03):

- **RFC-016.P1.1:** `skein.core.weaver.runtime/current-runtime` is a process
  singleton. `start!` fails loudly if it is set; `stop!` clears it; `-main`
  spins on it.
- **RFC-016.P1.2:** The runtime itself is already a self-contained map — every
  registry (`:query-registry`, `:view-registry`, `:pattern-registry`,
  `:op-registry`, `:hook-registry`, event system, spool classloader,
  datasource) is per-runtime state. The `skein.api.*.alpha` layer already takes
  explicit `runtime` as first argument throughout. The singleton is a guard and
  an ambient selector, not a structural necessity.
- **RFC-016.P1.3:** Ambient consumers of the singleton:
  - `skein.api.current.alpha/runtime` (blessed facade for trusted in-process
    config/spool/REPL code, e.g. `init.clj` startup files).
  - `skein.core.client` nREPL eval forms, which deref
    `@skein.core.weaver.runtime/current-runtime` **inside the weaver process**
    to resolve the runtime serving that nREPL server.
  - `skein.repl` fallback routing and `skein.spools.shuttle`'s private
    `(rt)` helper (`*runtime*` binding, else `current/runtime`).
- **RFC-016.P1.4:** Several spools hold JVM-global mutable state independent of
  any runtime: shuttle (`harness-registry`, `in-flight`,
  `preamble-extension`, reconcile state), chime (`notifier-binding`,
  `rule-registry`, `seen-notifications`, `failure-log`, `scanned-batch-ids`),
  guild (`guild-ops`, `deprecated-ops`, `fallback-guild-name`), treadle
  (`scan-monitor`). Parallel tests would race on these even with independent
  runtimes.
- **RFC-016.P1.5:** `clojure.repl.deps/add-libs` (spool sync of approved local
  roots) mutates JVM-global classpath/basis state. `skein.test-runner` already
  documents a hard ordering dependency: `skein.shuttle-test` must precede other
  spool suites or later syncs are poisoned.
- **RFC-016.P1.6:** `skein.repl` keeps ambient connection state
  (`active-config-dir`, `active-state-dir`) by design — it is the human
  interactive surface. Four test namespaces use `with-redefs`
  (`repl-test`, `alpha-test`, `core.client-test`, `spools.workflow-test`),
  which is JVM-global.
- **RFC-016.P1.7:** `skein.test-runner/-main` is a single sequential
  `clojure.test/run-tests` call over all 22 namespaces.

## RFC-016.P2 Goals

- **RFC-016.G1:** Multiple independent weaver runtimes may run concurrently in
  one JVM, each fully isolated (storage, registries, transports, events).
- **RFC-016.G2:** Preserve current daemon semantics: a real weaver process
  still has exactly one published ambient runtime, `skein.api.current.alpha`
  and trusted startup config keep working unchanged, and publishing a second
  runtime in one process still fails loudly.
- **RFC-016.G3:** Spool state (shuttle, chime, guild, treadle) becomes
  runtime-owned so independent runtimes do not share or reset each other's
  registries.
- **RFC-016.G4:** `clojure -M:test` runs parallel-safe namespaces concurrently
  and keeps a documented serialized island for JVM-global concerns
  (`add-libs`, `with-redefs`, ambient REPL connection state), with correct
  aggregate failure reporting and non-interleaved output.
- **RFC-016.G5:** Directly unblock `skein.test.alpha` (LAT feature): weaver
  world fixtures become nestable and parallelizable.

## RFC-016.P3 Non-goals

- **RFC-016.NG1:** No change to the one-weaver-per-workspace model, mill
  supervision, metadata/socket discovery, or the CLI surface.
- **RFC-016.NG2:** No attempt to make `add-libs`-dependent tests parallel;
  they remain a serialized island by design.
- **RFC-016.NG3:** ~~No process-level test sharding (separate JVMs); in-JVM
  concurrency is the target since JVM startup dominates the floor.~~
  *Amended 2026-07-03 by owner decision: concurrent subprocess JVM shards are
  the sanctioned mechanism for the add-libs island specifically. add-libs
  mutates JVM-global tools.deps state by design, so per-shard pristine bases
  are a better model than one serialized chain (ordering and basis-poisoning
  constraints become shard-local), and the boot cost is an accepted trade.
  In-JVM concurrency remains the mechanism for everything else.*
- **RFC-016.NG4:** No redesign of `skein.repl`'s ambient human-facing
  connection state; its tests stay in the serialized island.

## RFC-016.P4 Design

### RFC-016.D1 Publication-explicit runtime lifecycle (feature: multi-runtime-jvm)

Split "a runtime exists in this JVM" from "this process's ambient runtime":

- **RFC-016.D1.1:** `start!` gains a `:publish?` option (default `true`,
  preserving daemon and mill behavior). Publishing when another published
  runtime exists fails loudly exactly as today. `:publish? false` starts an
  unpublished runtime; any number may coexist.
- **RFC-016.D1.2:** `skein.api.current.alpha/runtime` resolves, in order: a
  dynamic per-thread runtime binding, then the published runtime, then fails
  loudly. `start!` binds the dynamic binding around startup-file loading and
  built-in op registration so trusted `init.clj` config works identically for
  unpublished runtimes.
- **RFC-016.D1.3:** nREPL runtime identity must not go through the global
  atom: each runtime's nREPL server resolves back to its own runtime (e.g. a
  server/port→runtime registry or injected middleware — implementer's choice,
  but `skein.core.client` eval forms must stop dereferencing
  `current-runtime` directly so client tests against unpublished runtimes are
  correct).
- **RFC-016.D1.4:** `stop!` unpublishes only if it owns publication;
  unpublished runtime stop is side-effect-free on ambient state.

### RFC-016.D2 Runtime-owned spool state (feature: runtime-owned-spool-state)

- **RFC-016.D2.1:** The runtime map grows a generic `:spool-state` slot
  (atom of spool-keyed state), with a small blessed accessor in
  `skein.api.runtime.alpha` so spools do not reach into runtime internals.
- **RFC-016.D2.2:** Shuttle, chime, guild, and treadle move their module-level
  atoms/monitors into runtime-owned state and take/resolve the runtime
  explicitly (shuttle's `*runtime*`/`(rt)` pattern generalizes via D1.2's
  binding).
- **RFC-016.D2.3:** Spool test suites drop cross-test `reset!` fixtures in
  favor of fresh per-test runtimes where cheap.

### RFC-016.D3 Grouped concurrent test runner (feature: parallel-test-runner)

- **RFC-016.D3.1:** `skein.test-runner` declares two explicit vectors:
  `parallel-namespaces` and `serial-namespaces`, with a comment stating why
  each serial member is serial. Initial serial island: `runtime-deps-test`,
  `shuttle-test`, `config-test` (add-libs, with documented shuttle-first
  ordering), `repl-test` (ambient connection + with-redefs), `alpha-test`,
  `core.client-test`, `spools.workflow-test` (with-redefs; may graduate to
  parallel if their with-redefs use is removed).
- **RFC-016.D3.2:** Parallel namespaces run one-per-thread over a bounded
  pool, each with its own `binding` of clojure.test report state and captured
  output, results aggregated into one summary; any failure/error exits
  non-zero. Serial island runs after (or before) the parallel batch, never
  overlapped.
- **RFC-016.D3.3:** Test hygiene in the same feature: namespace-level shared
  atoms in tests become per-test locals/fixtures; tests must not depend on
  cross-namespace ordering except inside the documented serial island.

## RFC-016.P5 Alternatives considered

- **RFC-016.A1: Process-level sharding (multiple JVMs).** Rejected: pays JVM
  startup per shard (~5s+ each), complicates failure aggregation, and does
  nothing for the LAT blocker (multi-runtime in one JVM).
- **RFC-016.A2: Parallelize `deftest`s within namespaces.** Rejected for now:
  most value is across namespaces; per-test parallelism multiplies hazard
  surface for little extra gain.
- **RFC-016.A3: Remove `current-runtime` entirely.** Rejected: the daemon
  process legitimately has one ambient runtime; trusted config, `skein.repl`
  fallback, and spool ergonomics rely on it. Publication-explicit keeps that
  contract while unblocking tests.

## RFC-016.P6 Staging

Three features, strictly sequenced:

1. `multi-runtime-jvm` (D1) — core unlock; updates `daemon-runtime` spec.
2. `runtime-owned-spool-state` (D2) — depends on D1's binding/`:spool-state`
   decisions; updates spool contract docs.
3. `parallel-test-runner` (D3) — depends on D1+D2; runner + test hygiene.

Success criteria: full suite green under the concurrent runner with a
meaningful wall-clock reduction from the 34s baseline; `-M:smoke` unchanged;
a follow-up LAT task can start two weaver worlds in one test JVM.
