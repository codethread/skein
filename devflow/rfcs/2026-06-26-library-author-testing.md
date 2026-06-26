# Library Author Testing Support

**Document ID:** `RFC-005`
**Status:** Open
**Date:** 2026-06-26
**Related:** [Daemon Runtime](../specs/daemon-runtime.md), [REPL API](../specs/repl-api.md), [CLI Surface](../specs/cli.md), [Runtime Library Workspace](../archive/26-06-26__runtime-library-workspace/)

## RFC-005.P1 Problem

Atom's extension model is now normal trusted Clojure libraries approved by `libs.edn`, synced into the daemon, and activated from `init.clj` or a connected REPL. That is a good foundation for runtime customization, but library authors need first-class support for proving their code works against a specific Atom checkout and CLI without copying Atom's own smoke-test machinery or accidentally testing against their personal daemon world.

The current guidance is mostly conceptual: write pure Clojure tests where possible, add Atom as a local/root dev dependency when requiring Atom namespaces directly, and use disposable `--config-dir` daemon worlds for full integration tests. This leaves several frictions:

- **RFC-005.P1.1:** Non-Clojure-expert authors may not know how classpaths, tools.deps aliases, helper REPL process boundaries, and daemon JVM classpaths interact.
- **RFC-005.P1.2:** Authors may write tests that pass in-process but fail when loaded through the daemon's real `libs.edn`/`sync!`/`use!` path.
- **RFC-005.P1.3:** Authors may accidentally mutate `~/.config/atom` or rely on user-specific startup state.
- **RFC-005.P1.4:** Atom's own blessed libraries should dogfood the same authoring and test mechanisms that community libraries use wherever possible, instead of relying on privileged test-only paths that hide integration problems.

## RFC-005.P2 Goals

- **RFC-005.G1:** Provide a documented, first-class library-author testing experience for pure Clojure, Atom-namespace, and daemon-world integration tests.
- **RFC-005.G2:** Add a blessed `atom.*.alpha` testing library that makes isolated disposable daemon-world tests easy to write with idiomatic `clojure.test`.
- **RFC-005.G3:** Make it straightforward for a library author to test against a specific Atom source checkout and installed/built CLI version.
- **RFC-005.G4:** Ensure daemon integration tests exercise the real config-dir workflow: `config.json`, `libs.edn`, `init.clj`, `libs/sync!`, `libs/use!`, daemon start, CLI/REPL calls, daemon stop, and cleanup.
- **RFC-005.G5:** Dogfood the same testing primitives for Atom's own blessed alpha libraries where practical.
- **RFC-005.G6:** Keep Atom core and CLI thin; testing helpers may orchestrate real worlds, but should not become a parallel runtime surface.
- **RFC-005.G7:** Preserve fail-loud behavior and test isolation by default.

## RFC-005.P3 Non-goals

- **RFC-005.NG1:** Do not build a package registry, installer, dependency solver, or lockfile system.
- **RFC-005.NG2:** Do not sandbox library code or make untrusted execution safe.
- **RFC-005.NG3:** Do not require every library test to start a daemon; pure tests should remain ordinary Clojure tests.
- **RFC-005.NG4:** Do not make an in-memory SQL or in-memory CLI replacement part of the initial contract.
- **RFC-005.NG5:** Do not hide classpath boundaries; documentation and helpers should make them explicit.
- **RFC-005.NG6:** Do not add public CLI commands for package/library testing in the MVP.

## RFC-005.P4 Options

| ID | Summary | Pros | Cons |
| -- | ------- | ---- | ---- |
| RFC-005.O1 | Documentation only: add `docs/library-authoring.md` with deps.edn examples, pure tests, local-root dev dependency guidance, and shell snippets for disposable daemon worlds. | Lowest implementation cost; teaches Clojure fundamentals; no new API to maintain. | Still leaves non-Clojure authors writing brittle process orchestration; harder for Atom to dogfood consistently; repeated boilerplate across libraries. |
| RFC-005.O2 | Add `atom.test.alpha` with helper functions/macros for temporary config-dir worlds and real daemon/CLI integration tests, plus documentation. | First-class support; tests real user workflow; easier dogfooding; can centralize cleanup, isolation, process handling, and diagnostics. | Adds a blessed API surface to maintain; must be careful not to over-abstract daemon behavior or duplicate smoke tests. |
| RFC-005.O3 | Build an in-memory DB/daemon/CLI test harness as the main library-author path. | Fast tests; simpler process lifecycle; useful for many core unit tests. | Risks diverging from real runtime behavior; substantial design/implementation cost; does not prove config-dir/classpath/CLI integration; premature for alpha. |
| RFC-005.O4 | Treat library testing as external project responsibility only. | Zero Atom maintenance burden. | Poor author experience; encourages unsafe default-world testing; undermines the runtime-library strategy and dogfooding goal. |

## RFC-005.P5 Recommendation

- **RFC-005.REC1:** Choose **RFC-005.O2**: create documentation plus a small blessed testing library, tentatively `atom.test.alpha`.
- **RFC-005.REC2:** The testing library should focus on orchestration of real disposable daemon worlds rather than mocking core behavior. Its initial contract should include helpers to create a temporary config-dir, write `config.json`/`libs.edn`/`init.clj`, start and stop a daemon, run CLI commands against that world, run daemon REPL stdin forms, and guarantee cleanup.
- **RFC-005.REC3:** Provide idiomatic `clojure.test` integration. A likely shape is a macro such as `with-daemon-world` or a fixture factory that binds a world map containing `:config-dir`, `:source`, `:cli`, and helpers like `cli!`, `repl!`, and `stop!`.
- **RFC-005.REC4:** Keep pure Atom namespace tests ordinary Clojure. Documentation should show tools.deps aliases that add Atom as a `:local/root` dev dependency, but the blessed test API does not need to wrap normal `require`/`clojure.test` usage.
- **RFC-005.REC5:** Defer in-memory SQL/CLI simulation. It may become an optimization for inner-loop tests later, but real daemon-world tests should remain the conformance path for library authors and for Atom's own blessed runtime libraries.
- **RFC-005.REC6:** Dogfood requirement: new or refactored blessed `atom.*.alpha` libraries should use `atom.test.alpha` for daemon-world behavior unless they are purely functional or have a specific lower-level reason to test directly.

## RFC-005.P6 Consequences

- **RFC-005.C1:** Add `docs/library-authoring.md` covering library repo shape, deps.edn aliases, testing tiers, classpath/process boundaries, disposable daemon-world integration tests, CLI version/source checkout expectations, and publishing/tagging guidance.
- **RFC-005.C2:** Add a new blessed alpha namespace such as `src/atom/test/alpha.clj` with tests under `test/` and examples in docs.
- **RFC-005.C3:** Existing smoke-test code may inform implementation, but the library-author API should be smaller, documented, and suitable outside the Atom repo.
- **RFC-005.C4:** Atom's own tests should migrate suitable daemon integration cases onto the same helpers to validate the helpers under real maintenance pressure.
- **RFC-005.C5:** The helpers must always use explicit disposable config dirs and must never read or mutate the user's default config/data/state worlds unless a caller explicitly opts into a path.
- **RFC-005.C6:** The helpers should expose diagnostics on daemon start failure, init failure, CLI failure, and cleanup failure clearly enough for library authors to debug classpath and activation problems.
- **RFC-005.C7:** The durable contract will likely require updates to the REPL API and Daemon Runtime specs once accepted, plus a feature proposal/plan for implementation.

## RFC-005.P7 Outcome

- **RFC-005.OUT1:** Open for review. Proposed follow-up feature: `library-author-testing-support`, including `docs/library-authoring.md`, `atom.test.alpha`, and dogfooding updates to Atom's own blessed library tests.
