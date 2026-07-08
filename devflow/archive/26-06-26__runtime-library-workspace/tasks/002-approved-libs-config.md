# Approved Libs Config

**Document ID:** `RLW-TASK-002` **Status:** Pending **Plan:** [../runtime-library-workspace.plan.md](../runtime-library-workspace.plan.md) **Spec delta:** [../specs/repl-api.delta.md](../specs/repl-api.delta.md)

## RLW-TASK-002.P1 Scope

Type: AFK

Add the approved local-root config parser/normalizer for `libs.edn`, exposed through daemon-routed `atom.libs.alpha` helpers. This task owns config shape and validation only; it does not need to add roots to the classpath or implement `use!`.

## RLW-TASK-002.P2 Required work

- **RLW-TASK-002.W1:** Add `atom.libs.alpha` and daemon API support for reading approved library config from selected config-dir `libs.edn`.
- **RLW-TASK-002.W2:** Implement the exact MVP schema: `{:libs {lib-symbol {:local/root "path"}}}`.
- **RLW-TASK-002.W3:** Reject structural config errors loudly: unknown top-level keys, missing/non-map `:libs`, non-symbol coordinates, non-map entries, unknown per-lib keys, missing/non-string/blank `:local/root`, malformed EDN.
- **RLW-TASK-002.W4:** Normalize roots by resolving relative paths against selected config-dir, accepting absolute paths as explicit user-approved paths, and recording canonical root paths while preserving original `:local/root` values.
- **RLW-TASK-002.W5:** Add tests for relative roots, absolute roots, symlink/canonical behavior where practical, and structural failure cases.

## RLW-TASK-002.P3 Done when

- **RLW-TASK-002.D1:** `(atom.libs.alpha/approved)` returns normalized approved config from daemon context and connected helper context.
- **RLW-TASK-002.D2:** Structural config failures throw useful `ex-info` data and do not silently default.
- **RLW-TASK-002.D3:** Missing/unreadable local-root paths are not rejected by config normalization; they are left for sync classification in the next task.
- **RLW-TASK-002.D4:** Relevant Clojure tests pass.
