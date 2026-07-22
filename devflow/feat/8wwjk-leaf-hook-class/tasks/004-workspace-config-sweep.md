# Task 4: Workspace config ops, workflows, defop macro, fixtures

**Document ID:** `TASK-Lhc-004`

## TASK-Lhc-004.P1 Scope

Type: AFK

Adopt per-leaf classes across the repo's `.skein` workspace config world and the
workspace macro spool: every op registered from `.skein/*.clj` (config.clj's
devflow wrappers, workflows.clj including `land`/`flow`/`hitl`, analytics.clj,
attention.clj, kanban_tracker.clj, module_adapters.clj, notifier_local.clj,
nvd_scan.clj as applicable), the `defop` macro in `.skein/spools/macros`, and
registry test fixtures. These edits are inert for the live weaver until merge —
never refresh or restart the canonical weaver.

## TASK-Lhc-004.P2 Must implement exactly

- **TASK-Lhc-004.MI1:** `skein.macros.ops/defop` (`.skein/spools/macros/src/
  skein/macros/ops.clj`) stops defaulting classes: it passes node classes
  through and (until Task 5) tolerates their absence exactly like
  `register-op!` — no macro-local defaults remain.
- **TASK-Lhc-004.MI2:** Every `.skein/*.clj` op declaration carries leaf classes
  matching current behavior (reads `:read`; blocking waits — `flow-await`,
  agent awaiting verbs' wrappers — `:unbounded` at the blocking leaf only).
- **TASK-Lhc-004.MI3:** Registry/publication test fixtures under `test/` that
  assemble op entries declare leaf classes (enumerate by grepping fixtures for
  `:hook-class` and entry constructors; name them in your worklog note).
- **TASK-Lhc-004.MI4:** Where a `.skein` op grammar fakes verbs with a
  positional action, record it in a migration-matrix note on the plan
  (Developer Notes) — fold to real nesting only when the op is repo-owned and
  the fold is mechanical; otherwise leave for a follow-up card.

## TASK-Lhc-004.P3 Done when

- **TASK-Lhc-004.DW1:** Cold `clojure -M:test` green on owned/affected test
  namespaces; config load proven in a **disposable** workspace
  (`ws=$(mktemp -d)`, every expansion `${ws:?}`) via `mill init --workspace` +
  a `strand --workspace` help/read call.
- **TASK-Lhc-004.DW2:** `make fmt-check lint reflect-check docs-check` green;
  clean `git status --short`.

## TASK-Lhc-004.P4 Out of scope / ownership

- **TASK-Lhc-004.OS1:** No edits to core mechanism files, batteries, guild,
  text-search, workflow/chime/cron, or smoke (Tasks 1–3); no enforcement
  (Task 5); no canonical-weaver refresh/restart, ever.
- Owns: `.skein/*.clj`, `.skein/spools/macros/`, and the registry fixture test
  files it names.
