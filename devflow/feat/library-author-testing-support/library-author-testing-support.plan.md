# Library Author Testing Support Plan

**Document ID:** `LAT-PLAN-001`
**Feature:** `library-author-testing-support`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** [RFC-005 Library Author Testing Support](../../rfcs/2026-06-26-library-author-testing.md)
**Root specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md)
**Feature specs:** [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md), [cli.delta.md](./specs/cli.delta.md)
**Status:** Reviewed
**Last Updated:** 2026-06-26

## LAT-PLAN-001.P1 Goal and scope

Deliver first-class library-author testing support by refactoring weaver storage to understand both file-backed and real Xerial SQLite in-memory worlds, exposing a small `skein.test.alpha` `clojure.test` helper API built on that storage model, documenting external library author workflows, and dogfooding the helper in Skein's own blessed-library tests.

## LAT-PLAN-001.P2 Approach

- **LAT-PLAN-001.A1:** Start with storage/runtime groundwork, not the public helper. Introduce an internal storage handle concept so runtime code no longer equates database identity with a filesystem path.
- **LAT-PLAN-001.A2:** Preserve file-backed SQLite as the default and canonical user/smoke path. Add in-memory SQLite only through trusted runtime/test construction using Xerial JDBC and a held connection.
- **LAT-PLAN-001.A3:** Update metadata/status at the same time as storage changes so `skein.test.alpha` does not inherit a path-only model. Storage kind and label become explicit; database path is file-only.
- **LAT-PLAN-001.A4:** Build `skein.test.alpha` after storage behavior is stable. The helper should orchestrate weaver worlds and weaver-routed forms only; it should not become a strand API, assertion library, or CLI wrapper.
- **LAT-PLAN-001.A5:** Documentation should show the conceptual layers clearly: pure tests, author test-JVM Skein namespace tests, and weaver-world integration tests that exercise `libs.edn`/`sync!`/`use!`.
- **LAT-PLAN-001.A6:** Dogfood by migrating a narrow set of existing weaver-world library tests, then leave lower-level storage/weaver tests on lower-level APIs where they provide better precision.

## LAT-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| -- | ---- | --------------- |
| LAT-PLAN-001.AA1 | `skein.db` | Add or expose storage construction support for file-backed datasource and held in-memory SQLite connection without changing schema/query functions. |
| LAT-PLAN-001.AA2 | `skein.weaver.runtime` | Start runtimes from a storage handle, own storage close lifecycle, and keep existing weaver API call sites using a next.jdbc-compatible connectable. |
| LAT-PLAN-001.AA3 | `skein.weaver.metadata` / socket status | Publish storage kind/label and file path only for file storage; validate metadata accordingly. |
| LAT-PLAN-001.AA4 | Go CLI client/status | Parse and validate new storage metadata while preserving current file-backed behavior. |
| LAT-PLAN-001.AA5 | `skein.test.alpha` | New blessed dev/test helper namespace for disposable weaver-world tests, shipped from `src/skein/test/alpha.clj` so external test JVMs can require it via Skein local-root dependency. |
| LAT-PLAN-001.AA6 | `docs` | Add `docs/library-authoring.md` with testing tiers, deps.edn examples, classpath boundaries, and CI guidance. |
| LAT-PLAN-001.AA7 | tests and smoke | Add storage/helper coverage, dogfood suitable library tests, keep canonical smoke file-backed. |

## LAT-PLAN-001.P4 Contract and migration impact

- **LAT-PLAN-001.CM1:** Weaver metadata/status changes are durable contract changes staged in `specs/daemon-runtime.delta.md` and `specs/cli.delta.md`: EDN uses `:storage-kind`, `:storage-label`, `:canonical-db-path`; JSON/status uses `database_kind`, `database_label`, `database_path`, with `database_path: null` for memory storage.
- **LAT-PLAN-001.CM2:** The REPL/helper surface gains `skein.test.alpha`, staged in `specs/repl-api.delta.md`.
- **LAT-PLAN-001.CM3:** Existing file-backed worlds and CLI strand commands should remain behaviorally unchanged, except status metadata may include additional storage fields.
- **LAT-PLAN-001.CM4:** Legacy db-file-oriented Clojure client helpers remain file-only; config-dir/world helpers are the path for non-file test worlds.
- **LAT-PLAN-001.CM5:** No user data migration is needed because in-memory storage is new test/runtime construction behavior and file storage remains default.

## LAT-PLAN-001.P5 Implementation phases

### LAT-PLAN-001.PH1 Storage handle and file-mode preservation

Outcome: Runtime startup uses an internal storage handle abstraction while preserving current file-backed behavior, metadata, tests, and smoke. No in-memory behavior exposed yet.

### LAT-PLAN-001.PH2 In-memory SQLite runtime mode

Outcome: Trusted runtime/test construction can start a weaver with real Xerial SQLite in-memory storage using a held connection. Stop closes the connection; DB and weaver API tests cover schema, transactions, basic concurrency, and fail-loud closed-connection behavior.

### LAT-PLAN-001.PH3 Storage metadata/status contract

Outcome: Runtime metadata, JSON socket status, Clojure client metadata validation, Go metadata parsing, and CLI status validation/reporting support explicit storage kind/label and file-only database path semantics.

### LAT-PLAN-001.PH4 `skein.test.alpha` helper API

Outcome: Add `src/skein/test/alpha.clj` with `with-weaver-world`, `weaver-world-fixture`, and `repl!` or equivalent minimal names. It supports `:storage :sqlite-file` and `:storage :sqlite-memory`, writes config fixtures, starts/stops isolated worlds, and fails loudly on startup/eval/cleanup errors.

### LAT-PLAN-001.PH5 Documentation and external author workflow

Outcome: Add `docs/library-authoring.md` with library repo shape, deps.edn local-root alias, classpath boundary explanation, pure/Skein/weaver test tiers, `skein.test.alpha` examples, short temp path guidance, and CI checkout/pinning notes.

### LAT-PLAN-001.PH6 Dogfood and smoke alignment

Outcome: Migrate the weaver-world library-author-style cases `weaver-init-runs-with-library-classloader-after-sync` and `connected-client-use-executes-in-weaver-runtime` from `test/skein/libs_test.clj` to `skein.test.alpha`, add focused in-memory coverage, and keep canonical `clojure -M:smoke` file-backed while optionally adding a smaller in-memory storage test path.

## LAT-PLAN-001.P6 Validation strategy

- **LAT-PLAN-001.V1:** Run Clojure unit tests covering file-backed storage unchanged and new in-memory storage behavior.
- **LAT-PLAN-001.V2:** Run Go CLI tests to validate metadata/status parsing and ensure strand command behavior remains unchanged.
- **LAT-PLAN-001.V3:** Run or add focused tests for `skein.test.alpha` file-backed and in-memory weaver-world helpers.
- **LAT-PLAN-001.V4:** Validate an external-style fixture library can test pure code, require Skein namespaces via local-root checkout, and load through weaver `libs.edn`/`sync!`/`use!`.
- **LAT-PLAN-001.V5:** Run `clojure -M:smoke` before sign-off; canonical smoke remains file-backed.
- **LAT-PLAN-001.V6:** Verify `git status --short` after validation shows no generated SQLite/runtime artifacts.

## LAT-PLAN-001.P7 Risks and open questions

- **LAT-PLAN-001.R1:** Metadata changes can ripple through Go/Clojure clients. Mitigation: update metadata/status validation in one phase and preserve file-backed field values for current flows.
- **LAT-PLAN-001.R2:** A single held in-memory SQLite connection may serialize test workloads and differ from file-backed datasource behavior under concurrency. Mitigation: document as test storage, cover basic concurrent weaver API calls, and keep file-backed tests/smoke.
- **LAT-PLAN-001.R3:** `skein.test.alpha` could grow into a parallel runtime API. Mitigation: restrict it to lifecycle and weaver-routed eval; reject task/assertion/package wrappers in this feature.
- **LAT-PLAN-001.R4:** Unix domain socket paths can exceed platform limits with long temp dirs. Mitigation: helper-generated config dirs should use short temp roots/names and produce clear diagnostics.
- **LAT-PLAN-001.Q1:** None blocking task generation. Metadata field names and dogfood targets are fixed above.

## LAT-PLAN-001.P8 Task context

- **LAT-PLAN-001.TC1:** RFC and spike context lives in `devflow/rfcs/2026-06-26-library-author-testing.md` and `devflow/spikes/2026-06-26-*.md`.
- **LAT-PLAN-001.TC2:** The storage lifecycle spike found that held `java.sql.Connection` works; datasource-only in-memory fails because each connection sees a fresh DB.
- **LAT-PLAN-001.TC3:** The metadata spike recommends explicit storage kind/label and file-only database path.
- **LAT-PLAN-001.TC4:** The API spike recommends minimal `skein.test.alpha`: `with-weaver-world`, `weaver-world-fixture`, `repl!`, no strand wrappers, no CLI helpers. The helper must ship from `src/skein/test/alpha.clj`.
- **LAT-PLAN-001.TC5:** The classpath spike validated an external-style fixture and found long temp paths can break Unix socket creation.
- **LAT-PLAN-001.TC6:** The smoke spike recommends canonical smoke stays file-backed; in-memory can supplement but should not replace it.

## LAT-PLAN-001.P9 Developer Notes

### LAT-PLAN-001.DN1 Plan creation — 2026-06-26

- Created from RFC-005 and five concurrent spike findings. Status remains Draft until reviewed.

### LAT-PLAN-001.DN2 Review completed — 2026-06-26

- Review found metadata field names, helper source location, and dogfood targets needed to be fixed before tasking. Updated deltas and plan; re-review returned no findings. Plan marked Reviewed for task breakdown.
