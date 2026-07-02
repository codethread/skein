# RFC-012 Implementation Review

Validation run: `PATH=/opt/homebrew/opt/openjdk/bin:$PATH clojure -M:test`

Result: PASS — 275 tests, 1599 assertions, 0 failures, 0 errors. The run printed one background shuttle thread `ExceptionInfo: Shuttle requires an in-process weaver runtime`, but the Clojure test runner still completed green. `git status --short` after validation showed only the intended source/test/doc changes plus this review file; no generated SQLite/runtime artifacts were present.

## Findings

### should-fix — default context accepts JSON-unsafe numeric values

- `src/skein/spools/workflow.clj:786-795`

`json-safe-context-value` treats every Clojure `number?` as JSON-safe. That admits non-finite floating values such as `##NaN`, `##Inf`, and `##-Inf`, which are not portable JSON values for durable `workflow/context`. RFC-012.Q2 and TEN-003 call for loud rejection of non-JSON-safe context defaults. Please reject non-finite doubles/floats (or otherwise prove the persistence JSON encoder rejects them loudly before storing) and add a regression test beside `workflow-start-accepts-var-and-defaults-durable-context`.

### should-fix — no regression coverage for unknown registered workflow keyword at start time

- `test/skein/spools/workflow_test.clj:556-567`
- `src/skein/spools/workflow.clj:802-812`
- `src/skein/spools/workflow.clj:1430-1436`

The implementation path appears to fail loudly through `workflow-definition`, but the requested RFC-012 behavior is specifically a start!/describe-time failure for unknown registry keywords. Current tests only cover successful keyword start and keyword describe. Add a negative test for `(workflow/start! "..." :missing {})` (and ideally `describe`) asserting the `Unknown registered workflow` error. This is an important contract guard for REC2.

## Verdict

request-changes — The core RFC-012 semantics are implemented coherently: chained loops preserve expansion-0 declared deps, chain later expansions to the previous expansion, and keep base-id fan-in as all expanded ids; devflow uses the new loop form while preserving task ids and attributes; docs and `explain` were updated for loops, start/describe, selectors, and registry. However, the JSON-safe context contract still has an observable hole for non-finite numbers, and the start-time unknown-keyword failure lacks direct regression coverage despite being one of the RFC's main fail-loud guarantees.

## Fixes applied

- Fixed should-fix: default context now rejects non-finite `Double`/`Float` values (`##NaN`, `##Inf`, `##-Inf`) before storing defaulted `workflow/context`, with regression coverage for NaN and infinity in `workflow-start-accepts-var-and-defaults-durable-context`.
- Fixed should-fix: added `workflow-start-and-describe-reject-unknown-registered-keyword`, covering loud `Unknown registered workflow` failures for both keyword `start!` and `describe`.
- Validation: `PATH=/opt/homebrew/opt/openjdk/bin:$PATH clojure -M:test` passed — 276 tests, 1603 assertions, 0 failures, 0 errors. The existing background shuttle thread printed `ExceptionInfo: Shuttle requires an in-process weaver runtime`; the test runner still completed green.
- Artifact check: `git status --short` showed only source/test/doc/review changes and no generated SQLite/runtime artifacts.
