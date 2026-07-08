# Remove legacy Clojure CLI Plan

**Document ID:** `RCLC-PLAN-001` **Feature:** `remove-legacy-clojure-cli` **Proposal:** [proposal.md](./proposal.md) **Root specs:** [CLI Surface](../../specs/cli.md) **Status:** Shipped **Last Updated:** 2026-06-26

## Scope

Remove dead Clojure entrypoints and test coverage. The maintained CLI remains the Go `strand` binary; the maintained Clojure code remains the weaver, REPL, and runtime libraries. The `skein.cli` namespace stays only as the Go CLI's Clojure weaver-launch helper until that launcher is redesigned.

### Files to delete

- `src/skein/app.clj`
- `test/skein/cli_test.clj`

### Files to edit

- `deps.edn` — remove the `:skein` alias that points at `skein.cli` and the `:run` alias that points at `skein.app`
- `test/skein/test_runner.clj` — remove `skein.cli-test` from the test namespace list
- `devflow/specs/cli.md` — remove legacy Clojure CLI references that describe `clojure -M:skein` as supported surface
- `README.md` — remove any mention of `clojure -M:skein` if present
- `AGENTS.md` — remove any mention of `clojure -M:skein` if present
- `CONTRIBUTING.md` — remove any mention of `clojure -M:skein` if present

## Acceptance criteria

- `rg -n "skein\.cli|skein\.app|clojure -M:skein|clojure -M:run|:main-opts \[\"-m\" \"skein.cli\"\]|:main-opts \[\"-m\" \"skein.app\"\]" .` returns no live references outside archive/history if any remain.
- `clojure -M:test` passes without `skein.cli-test`.
- `go test ./...` under `cli/` passes.
- No new helper code is added solely to preserve dead CLI behavior.

## Notes

This is intentional YAGNI cleanup: no compatibility shim, no duplicate dead CLI implementation, no leftover “might be useful” helper surface.
