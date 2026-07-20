# Task 8: Convert Chime and attention rules

**Document ID:** `TASK-Olr-008`

## TASK-Olr-008.P1 Scope

Type: AFK

Give `skein.spools.chime` complete-owner rule replacement with its existing baseline and deduplication semantics. Convert the repository attention module after the domain API exists; coordinate with Task 10 so the generic `defrule` authoring form emits contribution data.

## TASK-Olr-008.P2 Must implement exactly

- **TASK-Olr-008.MI1:** Partition Chime rule declarations by stable module owner and validate the full candidate set before changing the live effective rule view.
- **TASK-Olr-008.MI2:** Preserve Chime's registration barrier: baseline matching/seen state is reconciled under the same monitor before a new effective rule becomes visible.
- **TASK-Olr-008.MI3:** Owner omission removes rules and their owner-specific seen-notification entries. Override removal restores the displaced rule with a correct baseline rather than generating historical notifications.
- **TASK-Olr-008.MI4:** Keep notifier binding, failure history, scanned batch memory, event handler, hook, and other live state in versioned spool-state; rule partition publication cannot replace those identities.
- **TASK-Olr-008.MI5:** Convert `.skein/attention.clj` to one owner contribution with no top-level `forget-rules!` and preserve every current rule and parked-run detector behavior.

## TASK-Olr-008.P3 Done when

- **TASK-Olr-008.DW1:** Tests cover add/replace/delete/override/restore by owner, baseline correctness, no duplicate notification, mutation race serialization, and state identity across refresh.
- **TASK-Olr-008.DW2:** Deleting or renaming an attention rule removes it from both declaration and live Chime registry without global reload.
- **TASK-Olr-008.DW3:** `clojure -M:test skein.chime-test skein.macros.rules-test skein.config-test` and `make fmt-check lint` pass.

## TASK-Olr-008.P4 Out of scope

- **TASK-Olr-008.OS1:** Do not change notification policy, notifier process behavior, or event vocabulary.

## TASK-Olr-008.P5 References

- **TASK-Olr-008.REF1:** `.skein/attention.clj`, `skein.macros.rules`, `skein.spools.chime/register!`, and `DELTA-OlrDrt-001.CC4/CC7`.
