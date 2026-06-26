# Refresh smoke and docs

**Document ID:** `TASK-005`

## TASK-005.P1 Scope

Type: AFK

Refresh end-to-end smoke coverage and user/agent-facing documentation so the completed implementation teaches only the Skein/strand/weaver contract.

## TASK-005.P2 Must implement exactly

- **TASK-005.MI1:** Update the smoke workflow under `dev/todo/smoke.clj` or its renamed path to build/invoke `strand`, start/stop `strand weaver`, use disposable Skein config dirs, expect `skein.sqlite` and `weaver.*`, and exercise CLI plus REPL flows with `active`, `ephemeral`, and `inactive_at` row shapes.
- **TASK-005.MI2:** Smoke must cover generated `init.clj` requiring `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`, plus runtime library sync/use and graph/view helper flows under renamed namespaces.
- **TASK-005.MI3:** Update `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `docs/getting-started.md`, and relevant non-archive docs to use `strand`, `weaver`, Skein worlds, strand helpers, and active/ephemeral vocabulary.
- **TASK-005.MI4:** Update examples to avoid core `:kind`, `:status`, `done`, `failed`, or `cancelled` as built-in concepts. If an example needs an outcome/category, label it as a user attribute chosen by the example.
- **TASK-005.MI5:** Keep docs and smoke explicit about using disposable `--config-dir` worlds for agent/testing work.
- **TASK-005.MI6:** Run grep checks for old public-surface strings and either update them or confirm they appear only in historical/archived/RFC context.

## TASK-005.P3 Done when

- **TASK-005.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes.
- **TASK-005.DW2:** User-facing docs contain current Skein examples and no stale public instructions for `todo`, `todo daemon`, `atom.*.alpha`, `task!`, `tasks.sqlite`, `daemon.*`, or status flags.
- **TASK-005.DW3:** Smoke cleanup leaves no generated SQLite, socket, metadata, or built CLI artifacts in `git status --short`.

## TASK-005.P4 Out of scope

- **TASK-005.OS1:** Do not rewrite archived feature folders except as part of final spec/RFC archiving later.
- **TASK-005.OS2:** Do not claim public GitHub/domain/Clojars publishing handles.
- **TASK-005.OS3:** Do not add new smoke scenarios unrelated to rename/lifecycle confidence.

## TASK-005.P5 References

- **TASK-005.REF1:** [Plan](../skein-rename.plan.md) `SR-PLAN-001.PH5`
- **TASK-005.REF2:** [CLI delta](../specs/cli.delta.md), [REPL API delta](../specs/repl-api.delta.md), and [Runtime transformations delta](../specs/runtime-transformations.delta.md)
- **TASK-005.REF3:** Current anchors from scout: `dev/todo/smoke.clj`, `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `docs/getting-started.md`, and `docs/clojure-crash-course.md`.
