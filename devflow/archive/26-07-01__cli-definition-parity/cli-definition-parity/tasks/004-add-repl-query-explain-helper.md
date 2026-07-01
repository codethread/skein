# Add repl query explain helper

**Document ID:** `CDP-TASK-004`

## CDP-TASK-004.P1 Scope

Type: AFK

Add `(query-explain name)` to `skein.repl` beside `queries`, returning the same caller-guidance shape as CLI `query explain`, for parity with the existing `pattern-explain` helper.

References:

- [Plan](../cli-definition-parity.plan.md) `CDP-PLAN-001.PH4`
- [REPL API delta](../specs/repl-api.delta.md) `CDP-DELTA-002.CC1`–`CC3`

## CDP-TASK-004.P2 Implementation notes

- In `src/skein/repl.clj`, add `query-explain` following the existing `pattern-explain` helper's structure and connection routing: execute against `current-runtime` inside the live weaver JVM and route through connected-client dispatch after `connect!`, whichever mechanism `pattern-explain` uses.
- Connected routing goes through `skein.client`'s fixed-form `api-symbols` operation table, which allowlists operations; add a `:query-explain` entry beside the existing `:pattern-explain` entry so `connect!` workflows do not fail with "Unknown weaver API operation" (CDP-DELTA-002.CC4).
- Accept simple symbol, keyword, or string names; delegate resolution and shape to the task 1 `skein.weaver.api/query-explain` helper. Do not duplicate shape-building in `skein.repl`.
- Leave `queries` untouched — it keeps returning the raw registry map.

## CDP-TASK-004.P3 Done when

- REPL tests in `test/skein/repl_test.clj` beside the pattern helper tests cover: `query-explain` for a registered parameterized query returning `:name`, `:params`, `:referenced-params`, `:where`, `:definition`, `:where-form`, `:definition-form`, and `:summary`; a missing name failing loudly with available names; `queries` output unchanged.
- A connected-client test (following the existing connected `pattern-explain` or equivalent `connect!` coverage) proves `query-explain` works through the `skein.client` fixed-form path, not only inside the weaver JVM.
- Existing Clojure tests pass.

## CDP-TASK-004.P4 Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```
