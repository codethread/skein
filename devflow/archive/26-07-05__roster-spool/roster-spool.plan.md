# Roster Spool Implementation Plan

**Document ID:** `PLAN-RosterSpool-001`
**Status:** Reviewed
**Last Updated:** 2026-07-05
**Proposal:** [Roster Spool Proposal](./proposal.md)
**RFC:** [RFC-014 Feature Tracking Registry](../../rfcs/2026-07-02-feature-tracking-registry.md)
**Feature spec:** [Roster Spool Spec](./specs/roster-spool.md)
**Root specs touched at finish:** [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md)

## PLAN-RosterSpool-001.P1 Goal and scope

Build the classpath-shipped `skein.spools.roster` reference spool described by `RFC-014.REC3`: a shared active-work registry vocabulary, explicit-runtime helpers, a public `strand roster` op, a named `roster` query, and awaitable quiet/stale semantics. The shipped scope includes the heartbeat/staleness decision from card `w1t3o` and the workflow/devflow/AFK integration decision from card `hphfn`.

## PLAN-RosterSpool-001.P2 Approach

- **PLAN-RosterSpool-001.A1:** Implement roster as ordinary strand mutations over `roster/*` attributes, not as runtime-only memory. This keeps entries queryable, visible in subgraphs, and available to future guild composition.
- **PLAN-RosterSpool-001.A2:** Put the public namespace under `spools/src/skein/spools/roster.clj` so it ships on the Skein classpath beside workflow/carder/batteries.
- **PLAN-RosterSpool-001.A3:** Keep every public Clojure helper explicit-runtime-first. Op handlers read runtime from `:op/runtime`; `install!` receives or resolves runtime only at the activation boundary used by existing shipped spools.
- **PLAN-RosterSpool-001.A4:** Register the `roster` named query and `roster` CLI op from `install!`. The op uses the blessed arg-spec subcommand path, with `prime` and `about` authored by the spool and generated help owned by the parser.
- **PLAN-RosterSpool-001.A5:** Use event handlers for automatic heartbeat/stamping where safe. Initial automatic integration is conservative but explicit: only active, non-plumbing workflow/devflow roots with a feature slug and owner are auto-stamped; roots missing either identity field require explicit `track!`.
- **PLAN-RosterSpool-001.A6:** Treat staleness as a visible derived status. Listing annotates stale entries; `await-quiet!` returns `:stale` before declaring quiet when selected entries exceed the threshold.

## PLAN-RosterSpool-001.P3 Affected areas

- **PLAN-RosterSpool-001.M1:** `spools/src/skein/spools` gains the roster implementation.
- **PLAN-RosterSpool-001.M2:** `spools/README.md` and a new `spools/roster.md` document the shipped spool contract and examples.
- **PLAN-RosterSpool-001.M3:** `.skein/init.clj` should activate roster for the repo coordination world after implementation review, if the coordinator wants it live in the canonical workspace. Do not change/reload canonical config in AFK tasks without explicit approval.
- **PLAN-RosterSpool-001.M4:** Tests under `test/skein` should cover explicit-runtime helpers, op parsing/results, query registration, stale/await semantics, and automatic integration behavior using disposable test worlds.
- **PLAN-RosterSpool-001.M5:** Finish/archive updates should promote the feature spec into root specs and update the shipped spools index.

## PLAN-RosterSpool-001.P4 Implementation phases

- **PLAN-RosterSpool-001.PH1 Model and helpers:** Implement validation, attribute normalization, `track!`, `heartbeat!`, `finish!`, `roster`, and stale derivation. Keep failure modes loud for malformed attrs, missing ids, non-roster ids, closed heartbeat targets, and invalid thresholds.
- **PLAN-RosterSpool-001.PH2 CLI/query install:** Add `install!`, named query registration, declared-subcommand `roster` op, `prime`, `about`, `track`, `heartbeat`, `finish`, `list`, and `await-quiet` handlers.
- **PLAN-RosterSpool-001.PH3 Await semantics:** Implement polling `await-quiet!` with timeout and stale short-circuit. Make the CLI op unbounded-deadline and return JSON-safe result maps.
- **PLAN-RosterSpool-001.PH4 Integration:** Add event-handler integration for active non-plumbing workflow/devflow roots with feature slug from `workflow/run-id`, `devflow/feature`, `feature`, or `roster/feature`, and owner from `owner`, `roster/owner`, or a workflow/devflow actor attribute. Refresh heartbeat on graph mutations touching the source root or discoverable `parent-of` descendants; test missing feature/owner as explicit-tracking negative cases.
- **PLAN-RosterSpool-001.PH5 Docs and smoke:** Add `spools/roster.md`, update `spools/README.md`, promote/update devflow specs at finish, and validate with the standard Clojure and Go suites.

## PLAN-RosterSpool-001.P5 Validation strategy

- **PLAN-RosterSpool-001.V1:** Unit-style Clojure tests use isolated weaver worlds; no canonical weaver restart or reload.
- **PLAN-RosterSpool-001.V2:** Helper tests cover happy path, input failures, finish lifecycle, default and overridden stale thresholds, scoped listings, and JSON-safe return shapes.
- **PLAN-RosterSpool-001.V3:** CLI tests invoke the registered op in a disposable workspace and cover generated help compatibility through subcommands plus `about`/`prime` payload shape.
- **PLAN-RosterSpool-001.V4:** Await tests use short stale/timeout windows and deterministic heartbeats to avoid sleeps longer than needed.
- **PLAN-RosterSpool-001.V5:** Integration tests cover auto-stamping positive cases, mutation-driven heartbeat refresh, and negative roots missing feature/owner metadata.
- **PLAN-RosterSpool-001.V6:** Final gate before marking implementation complete: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` and `(cd cli && go test ./...)`, plus `git status --short` showing only intended source/doc artifacts before commits.

## PLAN-RosterSpool-001.P6 Task context

Tasks should be sliced so each mutator has a narrow file scope and can validate locally. Suggested harnesses:

- **PLAN-RosterSpool-001.T1:** `grunt` for helper implementation and focused tests.
- **PLAN-RosterSpool-001.T2:** `grunt` for CLI/query install and operation tests.
- **PLAN-RosterSpool-001.T3:** `grunt` for await semantics tests because timing/sleeps need careful mechanical validation.
- **PLAN-RosterSpool-001.T4:** `build` for workflow/devflow integration because it requires cross-spool design judgment.
- **PLAN-RosterSpool-001.T5:** `build` or `pi-main` for docs/spec promotion and final validation/reporting.

Each task must avoid canonical weaver reload/restart. If a task needs live config activation, it must create a disposable `--workspace` world.

## PLAN-RosterSpool-001.P7 Developer Notes

- **2026-07-05:** Proposal approved by coordinator; feature-local spec committed in `1087907`.
- **2026-07-05:** Plan marks itself Reviewed because the scope is already RFC-backed and checkpointed through proposal sign-off; task sign-off remains the next human/coordinator decision before AFK execution.
