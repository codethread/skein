# REPL API delta for library author testing support

**Document ID:** `LAT-DELTA-002`
**Root spec:** [repl-api.md](../../../specs/repl-api.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Draft
**Last Updated:** 2026-06-26

## LAT-DELTA-002.P1 Summary

The REPL/helper surface gains a blessed author-side test namespace, tentatively `skein.test.alpha`, for isolated weaver-world tests. It is a dev/test helper namespace loaded in the author's test JVM, not a weaver runtime activation API and not a public CLI surface.

## LAT-DELTA-002.P2 Contract changes

- **LAT-DELTA-002.CC1:** `skein.test.alpha` provides a small `clojure.test`-friendly API for disposable weaver worlds. It ships from Skein's source-visible runtime paths, specifically `src/skein/test/alpha.clj`, so external library test JVMs can require it by adding the selected Skein checkout as a tools.deps `:local/root` test dependency. The initial durable helper vocabulary is expected to include `with-weaver-world`, `weaver-world-fixture`, and `repl!` or equivalent names.
- **LAT-DELTA-002.CC2:** `with-weaver-world` creates or uses an explicit isolated config-dir, writes requested `config.json`, `libs.edn`, `init.clj`, and fixture files, starts an in-process weaver runtime, binds a context map for the test body, and stops/cleans up in `finally`.
- **LAT-DELTA-002.CC3:** `weaver-world-fixture` provides the same lifecycle for `clojure.test` fixture usage, likely through a dynamic context var.
- **LAT-DELTA-002.CC4:** `repl!` evaluates weaver-routed forms against the test weaver and returns Clojure data or throws with weaver/client context. It does not wrap strand commands, library activation, or assertions.
- **LAT-DELTA-002.CC5:** The helper context includes orchestration facts such as config-dir, state-dir, data-dir, source checkout, runtime metadata, storage kind, and runtime handle. It intentionally does not include strand-specific wrappers or CLI subprocess helpers.
- **LAT-DELTA-002.CC6:** The helper accepts explicit storage selection. File-backed SQLite is the default; real Xerial SQLite in-memory is supported once weaver storage lifecycle and metadata contracts are implemented.
- **LAT-DELTA-002.CC7:** The helpers must never use the user's default config/data/state world unless a future helper names that opt-in explicitly. Generated test worlds are isolated by default.
- **LAT-DELTA-002.CC8:** Startup, init, REPL evaluation, weaver stop, and cleanup failures fail loudly. Cleanup runs in `finally`; failures should surface with useful context rather than being silently swallowed.

## LAT-DELTA-002.P3 Design decisions

### LAT-DELTA-002.D1 Author-side helper, weaver-side behavior

- **Decision:** `skein.test.alpha` runs in the author's test JVM but drives real weaver-side behavior through runtime startup and weaver-routed forms.
- **Rationale:** This keeps tests idiomatic Clojure while preserving the important classpath boundary: direct test-JVM `require` is distinct from weaver `libs/sync!` and `use!`.
- **Rejected:** Loading `skein.test.alpha` as weaver startup API or adding public CLI testing commands.

### LAT-DELTA-002.D2 Minimal orchestration, no strand DSL

- **Decision:** The helper owns world lifecycle and weaver-routed eval only.
- **Rationale:** Library authors should test real Skein APIs and their own library code, not a second strand/testing DSL that can drift from product behavior.
- **Rejected:** `strand!`, `ready`, assertion wrappers, package install helpers, Go CLI build/discovery, and rich fixture DSLs in the MVP.

## LAT-DELTA-002.P4 Open questions

- **LAT-DELTA-002.Q1:** Final helper names may be adjusted during implementation, but the public alpha surface should remain small and documented in `docs/library-authoring.md`.
