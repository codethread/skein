# Add Trusted Event Helpers

**Document ID:** `TASK-003` **Status:** Pending **Plan:** [Weaver Event System Plan](../weaver-event-system.plan.md)

## TASK-003.P1 Scope

Type: AFK

Expose the event system to trusted Clojure config and connected REPL workflows through a blessed helper namespace.

## TASK-003.P2 Required context

- Depends on `TASK-001`.
- Read `../specs/repl-api.delta.md`.
- Inspect helper patterns in `src/skein/graph/alpha.clj`, `src/skein/views/alpha.clj`, `src/skein/libs/alpha.clj`, `src/skein/client.clj`, and `src/skein/repl.clj`.

## TASK-003.P3 Implementation notes

- Add `src/skein/events/alpha.clj` with helpers for register, unregister, list, and recent handler failures.
- Register handlers by stable key, non-empty event type set, and fully qualified function symbol.
- Ensure re-registering the same key replaces the prior handler for reload workflows.
- Update `skein.weaver.api/reload-config!` so dispatch stops, pending queued events are discarded, event handlers and recent failures are cleared, dispatch restarts, and only then `init.clj` is re-run. Document that reload clears registry state but does not unload already-loaded Clojure code.
- Route helpers directly when called in the weaver JVM and through fixed client/weaver operations when called from a connected helper REPL.
- Keep event operations out of the JSON socket public CLI allowlist.

## TASK-003.P4 Done when

- Trusted helper tests can register with event type filters, replace, unregister, list handlers, and inspect failures as data-first maps.
- `libs/reload!`/runtime reload clears removed handler registrations, discards pending queued events, and clears recent failures before reinstalling config, while preserving the existing no-unload runtime-classpath limitation.
- Connected helper REPL routing works without requiring direct local classpath access to user libraries.
- A connected-helper route can register a handler symbol absent from the helper JVM but resolvable in the weaver JVM under the runtime library classloader.
- JSON socket allowlist test still excludes event registration operations.
- `(cd cli && go test ./...)` passes.
- `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
