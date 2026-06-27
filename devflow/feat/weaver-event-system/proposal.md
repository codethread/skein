# Weaver Event System Proposal

**Document ID:** `PROP-001`
**Status:** Draft
**Last Updated:** 2026-06-27
**Related RFCs:** None
**Related Specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [Strand Model](../../specs/strand-model.md)

## PROP-001.P1 Problem

Skein now owns the mutation boundary for strand creation, updates, burns, and graph changes, but trusted userland workflows that want to react to those mutations must either poll, wrap every operation themselves, or use external scheduled jobs.

This makes workflows like “burn userland ephemeral children when their parent becomes inactive” possible but awkward: the behavior belongs in trusted weaver config, yet there is no core event primitive for composing it cleanly.

## PROP-001.P2 Goals

- **PROP-001.G1:** Provide a small core event system owned by the weaver runtime, not by SQLite triggers or external polling.
- **PROP-001.G2:** Emit semantic mutation events for Skein-owned CRUD/burn operations after the underlying mutation succeeds.
- **PROP-001.G3:** Let trusted user config/runtime libraries register event handlers during weaver startup or connected REPL workflows.
- **PROP-001.G4:** Dispatch handlers asynchronously so normal CLI/REPL mutations are not slowed by userland handler work.
- **PROP-001.G5:** Keep event payloads data-first and agent-readable.

## PROP-001.P3 Non-goals

- **PROP-001.NG1:** No public CLI commands for registering handlers.
- **PROP-001.NG2:** No durable event log, replay, delivery guarantees, or cross-process event subscription in the MVP.
- **PROP-001.NG3:** No SQLite trigger/update-hook based API.
- **PROP-001.NG4:** No sandboxing or permission model for handlers; handlers are trusted config code like existing runtime libraries.
- **PROP-001.NG5:** No broad plugin framework beyond the existing library workspace and REPL/config surfaces.

## PROP-001.P4 Proposed scope

- **PROP-001.S1:** Add weaver-lifetime event registry state, similar in spirit to query/view/module-use registries.
- **PROP-001.S2:** Add blessed runtime helpers for registering, listing, and unregistering handlers from trusted Clojure code, with explicit event-type filters so handlers do not receive unrelated events by default.
- **PROP-001.S3:** Emit semantic events from the weaver mutation boundary for current public/blessed mutations: add, update, and burn. Batch events are deferred until Skein exposes a blessed weaver batch mutation API.
- **PROP-001.S4:** Dispatch events to handlers on a background worker path so event handlers do not run inline with the mutating operation.
- **PROP-001.S5:** Surface handler failures as bounded weaver-visible runtime state/loggable data without failing the original mutation after commit.

## PROP-001.P5 Open questions

- **PROP-001.Q1:** Whether the first MVP should expose a single sequential handler worker or a bounded pool. The plan should prefer deterministic sequential dispatch unless implementation review proves it too limiting.
- **PROP-001.Q2:** Whether event helpers belong in a new `skein.events.alpha` namespace or existing `skein.weaver.api`/`skein.libs.alpha` helpers. The plan should prefer a new small blessed namespace for user-facing composition.
- **PROP-001.Q3:** Whether the event queue should be bounded in the MVP. The plan should explicitly choose queue behavior and bound recent failure retention to avoid silent memory growth.
