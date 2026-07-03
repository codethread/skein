# Library Author Testing Support Proposal

**Document ID:** `LAT-PROP-001`
**Last Updated:** 2026-07-03
**Related RFCs:** [RFC-005 Library Author Testing Support](../../rfcs/2026-06-26-library-author-testing.md)
**Related root specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md)

## LAT-PROP-001.P1 Problem

Skein's runtime library model asks users and community authors to write normal trusted Clojure libraries, approve them through `spools.edn`, and activate them inside weaver worlds. Authors currently lack a first-class way to test those libraries against a selected Skein checkout without copying internal smoke helpers, misunderstanding test-JVM versus weaver-JVM classpaths, or accidentally touching the user's default weaver world.

The testing story also needs storage support designed correctly from the start. File-backed SQLite weaver worlds match the normal user path, while real Xerial SQLite in-memory weaver worlds offer clean isolated integration tests. If `skein.test.alpha` is designed around only filesystem database paths, later in-memory support may distort the public helper API.

## LAT-PROP-001.P2 Goals

- **LAT-PROP-001.G1:** Provide documented library-author testing guidance covering pure Clojure tests, Skein namespace tests, and weaver-world integration tests.
- **LAT-PROP-001.G2:** Add a blessed author-side `skein.test.alpha` helper namespace for idiomatic `clojure.test` weaver-world tests.
- **LAT-PROP-001.G3:** Refactor weaver storage enough to support both file-backed SQLite and real Xerial SQLite in-memory storage through one coherent runtime storage model.
- **LAT-PROP-001.G4:** Preserve the real runtime-spool workflow in tests: workspace dir, `spools.edn`, `init.clj`, `skein.api.runtime.alpha/sync!`, `skein.api.runtime.alpha/use!`, weaver-side execution, and cleanup.
- **LAT-PROP-001.G5:** Keep the public CLI thin; library testing is a docs/test-helper/weaver-runtime concern, not a new package command surface.
- **LAT-PROP-001.G6:** Dogfood the new helpers in Skein's own blessed library tests where they model author-visible behavior.

## LAT-PROP-001.P3 Non-goals

- **LAT-PROP-001.NG1:** No package registry, source installer, dependency solver, or lockfile.
- **LAT-PROP-001.NG2:** No sandboxing or support for untrusted library execution.
- **LAT-PROP-001.NG3:** No fake persistence layer, map-backed DB, alternate SQLite engine, or WASM SQLite.
- **LAT-PROP-001.NG4:** No Go CLI subprocess helper or CLI binary build/discovery API in `skein.test.alpha`.
- **LAT-PROP-001.NG5:** No public CLI command or flag for package/library testing.
- **LAT-PROP-001.NG6:** No requirement that every test start a weaver; pure Clojure tests remain ordinary Clojure tests.

## LAT-PROP-001.P4 Proposed scope

- **LAT-PROP-001.S1:** Define a weaver storage model that distinguishes `:sqlite-file` and `:sqlite-memory`, owns close lifecycle, and reports storage identity deliberately in metadata/status.
- **LAT-PROP-001.S2:** Add real Xerial SQLite in-memory weaver storage for trusted test/runtime construction using a held connection, while preserving existing file-backed behavior as the default user path.
- **LAT-PROP-001.S3:** Add `skein.test.alpha` with a small weaver-world helper API such as `with-weaver-world`, `weaver-world-fixture`, and `repl!`, with explicit storage selection.
- **LAT-PROP-001.S4:** Add `docs/library-authoring.md` with dependency/classpath guidance, test tiers, examples, and CI/pinning recommendations.
- **LAT-PROP-001.S5:** Update smoke/tests to preserve file-backed canonical coverage and add focused coverage for in-memory weaver worlds and the new test helper.
- **LAT-PROP-001.S6:** Update affected specs for weaver storage metadata, REPL/test helper surface, and CLI status reporting.

## LAT-PROP-001.P5 Open questions

- **LAT-PROP-001.Q1:** None for MVP. Storage metadata field names and the initial dogfood target are fixed in the feature deltas and plan.
