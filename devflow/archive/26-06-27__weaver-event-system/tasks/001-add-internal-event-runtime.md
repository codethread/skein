# Add Internal Event Runtime

**Document ID:** `TASK-001` **Status:** Pending **Plan:** [Weaver Event System Plan](../weaver-event-system.plan.md)

## TASK-001.P1 Scope

Type: AFK

Add internal weaver event-system primitives without yet emitting strand mutation events.

## TASK-001.P2 Required context

- Read `devflow/feat/weaver-event-system/weaver-event-system.plan.md`.
- Read `devflow/feat/weaver-event-system/specs/daemon-runtime.delta.md`.
- Inspect `src/skein/weaver/runtime.clj` and existing runtime registry patterns in `src/skein/weaver/api.clj`.

## TASK-001.P3 Implementation notes

- Add weaver-lifetime state for event handler registry, bounded dispatch queue/worker, and bounded recent handler failures.
- Define the registry entry shape in this task: stable key, non-empty event type set, fully qualified handler function symbol, validated resolved callable, and data-first metadata for introspection.
- Start the worker with the runtime and stop it during runtime shutdown.
- Provide internal functions for registering/replacing handlers by key, unregistering, listing handlers, enqueueing an event, and recording handler failures.
- Use a single sequential bounded worker queue for MVP determinism and daemon stability. Recent failures must be bounded to the latest 100 entries.
- Do not add public CLI commands.

## TASK-001.P4 Done when

- Internal event runtime can register, replace, unregister, and list handler entries by key.
- Registration fails loudly for malformed keys, malformed/empty event type sets, non-qualified symbols, unresolved symbols, or non-callable resolved values.
- Introspection returns data-first handler entries with keys, event type sets, metadata, and function symbols, not function objects or worker internals.
- Enqueued events are delivered asynchronously only to registered handlers whose event type set contains the event's `:event/type`; enqueue attempts fail loudly when the queue is full.
- Handler exceptions are captured in bounded recent failure state with handler key, handler symbol, event id/type, exception message, and timestamp, and do not kill the worker.
- Runtime shutdown stops the worker cleanly and discards pending queued events that have not started handling.
- Focused Clojure tests cover the above behavior.
- `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
