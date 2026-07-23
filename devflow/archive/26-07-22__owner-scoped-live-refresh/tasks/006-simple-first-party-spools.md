# Task 6: Convert batteries roster and text-search

**Document ID:** `TASK-Olr-006`

## TASK-Olr-006.P1 Scope

Type: AFK

Convert the simpler first-party registration publishers to module contribution/reconcile entry points: `spools/batteries`, `spools/roster`, and `spools/text-search`, with their owner suites and API docs.

## TASK-Olr-006.P2 Must implement exactly

- **TASK-Olr-006.MI1:** Batteries contributes its complete op surface under its stable module owner while preserving the explicit classpath exception and built-in help replacement rules.
- **TASK-Olr-006.MI2:** Roster contributes its op/query/event declaration set completely; its integration watcher behavior remains resource/domain reconciliation and does not leak a stale handler when omitted.
- **TASK-Olr-006.MI3:** Text-search contributes its complete search op with explicit owner and retains its documented unsafe core-DB dependency unchanged.
- **TASK-Olr-006.MI4:** Replace install-only tests with contribution publication, owner deletion, refresh replacement, collision, and status-provenance tests. Keep temporary install adapters only if required before Task 16.
- **TASK-Olr-006.MI5:** Update spool docstrings and generated API source definitions, but leave cross-repo/human workflow docs to Task 17.
- **TASK-Olr-006.MI6:** Convert `spools/guild` op/deprecation declarations to a registered kind under its stable owner, and contribute core vocab declarations through the `:vocab` kind (DELTA-OlrDrt-001.CC4).
- **TASK-Olr-006.MI7:** Version the batteries `::read-limit` and `::git-client` spool-state slots so the version-mismatch machinery manages them; no unversioned spool-state slot remains in the converted spools.

## TASK-Olr-006.P3 Done when

- **TASK-Olr-006.DW1:** Omitting each spool's op/query/handler from a refreshed complete contribution removes it logically without affecting another owner.
- **TASK-Olr-006.DW2:** Roster watcher refresh does not duplicate handlers or lose heartbeat behavior.
- **TASK-Olr-006.DW3:** `clojure -M:test skein.spools.batteries-test skein.roster-test skein.spools.text-search-test skein.config-test` and `make fmt-check lint reflect-check` pass.

## TASK-Olr-006.P4 Out of scope

- **TASK-Olr-006.OS1:** Do not change command vocabulary, roster semantics, text-search safety classification, or bootstrap config.

## TASK-Olr-006.P5 References

- **TASK-Olr-006.REF1:** `PROP-Olr-001.S3/S9`, `PLAN-Olr-001.AA5`, terra-med note `zt2r1` finding 2.
