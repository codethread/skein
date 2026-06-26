# Init Docs and Smoke Workflow

**Document ID:** `TASK-005`

## TASK-005.P1 Scope

Type: AFK

Update fresh `todo init` generated config, docs, and smoke coverage so users can discover and use the blessed runtime transformation helpers.

## TASK-005.P2 References

- **TASK-005.R1:** [Feature plan](../runtime-transformation-primitives.plan.md)
- **TASK-005.R2:** [CLI delta](../specs/cli.delta.md)
- **TASK-005.R3:** `cli/internal/command/command.go`, `cli/internal/command/command_test.go`, `cli/integration_test.go`, `dev/todo/smoke.clj`, `README.md`, `docs/getting-started.md`, `CONTRIBUTING.md`, `AGENTS.md`

## TASK-005.P3 Implementation notes

- **TASK-005.I1:** Update generated `init.clj` for fresh worlds to require the chosen transformation helper namespace(s) as an editable template. Keep `(libs/sync!)` and do not overwrite existing user files.
- **TASK-005.I2:** Do not use `atom.libs.alpha/use!` merely to load built-in shipped namespaces unless Task 4 introduced an explicit install side effect and documented it.
- **TASK-005.I3:** Update Go tests around `todo init` generated files.
- **TASK-005.I4:** Update user docs to show the intended workflow: generated helper imports, defining a named query/view in config or REPL, using graph helpers from `todo daemon repl`, and continued CLI thinness.
- **TASK-005.I5:** Add smoke coverage for a disposable config-dir world proving generated or equivalent init code loads, registers at least one query or view in daemon runtime state, and invokes that registered behavior through `todo daemon repl --stdin`.

## TASK-005.P4 Done when

- **TASK-005.D1:** Fresh `todo init` config includes the helper template without overwriting existing config.
- **TASK-005.D2:** Docs explain built-in shipped namespaces versus user/community libraries under `libs.edn`.
- **TASK-005.D3:** Smoke exercises startup-loaded runtime registration and at least one helper through a real daemon connected REPL/stdin path.
- **TASK-005.D4:** Relevant Go/Clojure tests and smoke checks pass for touched areas.
