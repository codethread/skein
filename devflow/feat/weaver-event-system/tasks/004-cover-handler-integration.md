# Cover Handler Integration

**Document ID:** `TASK-004`
**Status:** Pending
**Plan:** [Weaver Event System Plan](../weaver-event-system.plan.md)

## TASK-004.P1 Scope

Type: AFK

Add an end-to-end integration test proving a trusted handler can react to strand events and perform useful cleanup asynchronously.

## TASK-004.P2 Required context

- Depends on `TASK-002` and `TASK-003`.
- Use existing weaver/runtime test helpers in `test/skein/weaver_test.clj` or adjacent test namespaces.

## TASK-004.P3 Implementation notes

- Create a test handler namespace/function available to the weaver JVM.
- Register a handler filtered to `:strand/updated` that listens for a parent strand becoming inactive and burns child strands tagged with userland attribute `{:ephemeral "true"}` or equivalent JSON-normalized attributes.
- Trigger the handler via a normal `update` operation.
- Account for asynchronous dispatch with bounded polling/waiting in tests rather than sleeps with no assertion loop.
- Use deterministic coordination such as promises/latches/atoms in slow-handler tests: prove the mutation returns while the handler is blocked, then release the handler and assert final effects. Also prove unrelated event types do not invoke the filtered handler.
- Cover bounded queue behavior and reload semantics with focused tests: queue-full enqueue fails loudly, and reload discards pending queued events before reinstalling handlers.

## TASK-004.P4 Done when

- Integration test proves registered handler reacts to update events and burns expected userland ephemeral children.
- Test proves unrelated children remain.
- Test proves handler failure or slowness does not fail the original mutation without relying on wall-clock thresholds.
- No default-world config/data/state is touched.
- `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
