# Update Docs and Smoke

**Document ID:** `TASK-005`
**Status:** Pending
**Plan:** [Weaver Event System Plan](../weaver-event-system.plan.md)

## TASK-005.P1 Scope

Type: AFK

Promote the shipped event-system contract into root specs/docs and add smoke coverage for the trusted helper path.

## TASK-005.P2 Required context

- Depends on `TASK-004`.
- Read feature-local spec deltas under `../specs/` and merge shipped behavior into root specs.
- Read `devflow/specs/daemon-runtime.md`, `devflow/specs/repl-api.md`, and existing smoke flows in `dev/skein/smoke.clj`.

## TASK-005.P3 Implementation notes

- Merge the daemon-runtime event-system delta into `devflow/specs/daemon-runtime.md`.
- Add/update a REPL API delta or root REPL API text for `skein.events.alpha` helper names, connected-REPL routing, weaver-lifetime registry semantics, reload clearing, and handler failure introspection.
- Update user-facing docs only where they describe trusted runtime helper namespaces.
- Add smoke coverage that registers a small event handler from trusted config or connected REPL workflow and observes an event-driven effect without adding JSON socket event commands.
- Preserve the JSON socket allowlist boundary in docs and tests.

## TASK-005.P4 Done when

- Root specs document the shipped event runtime and `skein.events.alpha` helper contract.
- Smoke exercises handler registration and asynchronous event effect in an isolated world.
- `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- `(cd cli && go test ./...)` passes.
- `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes.
