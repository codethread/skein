# Brief: agent-layer vocabulary rename

Kanban: card `26o9g`, feature 1 of epic `kaans` (agent-layer redesign). Source: coordinator design session 2026-07-09.

## Problem

The shuttle/agents/treadle cluster grew three vocabularies with owner-encoded, non-descriptive names riding durable data and worker prompts. Cold readers (delegated agents hitting `strand show` mid-task) must guess what `shuttle/phase` or `treadle/run` means; the `agents` spool colonizes `shuttle/*` (review/panel attrs, the `superseded` phase); `workflow/notes` collides with the note concept while being a gate-outcome string. The redesign (epic `kaans`) fixes behavior in later features; this feature is the **purely mechanical rename** that precedes it, so behavioral diffs review clean.

## Naming rule (institutionalized by this feature)

Attribute namespaces name **concepts, not owners**. Names riding durable data or prompts must be self-describing compound nouns; contributor-internalized names (namespaces, dirs) may be short. Ownership is registered, not encoded; third-party spools qualify theirs with a project prefix.

## Rename table (the contract)

| Today | New |
|---|---|
| `skein.spools.shuttle` | `skein.spools.agent-run` |
| `shuttle/*` run attrs (phase, harness, prompt, result, error, exit-code, session-id, resumes, log, pid, pid-started-at, started-at, finished-at, spawned-by, attempt, max-attempts, recovered-at, recovery-deferred-until, cwd, mode, backend, completion, for, reap, session, handle.*, teardown-error, error-class) | `agent-run/*` same suffixes |
| `shuttle/run` boolean marker | **dropped** — `agent-run/phase` presence is the marker |
| `skein.spools.agents` | `skein.spools.delegation` |
| `strand agent ...` CLI verbs, `agent-plan` pattern, `agent-failures` query | **unchanged** (trained-vocabulary surface is frozen) |
| `skein.spools.treadle` | `skein.spools.executors.subagent` |
| `skein.spools.reed` | `skein.spools.executors.shell` |
| `:subagent` waiter value | **unchanged** (devflow.spool pin; blast radius bounded) |
| `shuttle/review-target`, `review-pass`, `review-roster`, `review-focus`, `review-synthesis` | `review/*` same suffixes |
| `shuttle/panel-seat`, `panel-turn`, `shuttle/fresh-prompt` | `panel/seat`, `panel/turn`, `panel/fresh-prompt` |
| `shuttle/serves` | `agent-run/serves` (boolean survives until F2's `serves` edge) |
| `treadle/error` | `gate/error` (on gate) |
| `treadle/delivered`, `treadle/delivery-blocked` | `gate/delivered`, `gate/delivery-blocked` (on run) |
| `treadle/run`, `treadle/gate`, `treadle/run-id`, `treadle/superseded-by` | `gate/run`, `gate/gate-of`→ keep as `gate/step`, `gate/run-id`, `gate/superseded-by` (deleted entirely in F2) |
| `shuttle/note-for`, `note`, `note-by`, `round`, `at` | `note/for`, `note/text`, `note/by`, `note/round`, `note/at` |
| `workflow/notes` | `workflow/outcome-notes` (gate-outcome string; kills the notes collision) |

Spool dir moves: `spools/shuttle` → `spools/agent-run` (treadle source joins `spools/executors/`), doc triads follow. Untouched: `skein`/`strand`/`weaver`/`mill`, harness/alias/backend terms, seat names, kanban/roster/devflow vocabularies.

## Constraints

- **No behavior change.** Same tests pass modulo renamed symbols/attrs; any behavioral fix discovered is carded, not folded in.
- **Atomic landing**: spool sources + tests + `.skein` config (init/config/workflows/harnesses/attention/reviewers/nvd_scan) + bench + chime recipes + docs (`spools/*`, `docs/`, `devflow/specs` alpha-surface) + `make api-docs` regen in one landing.
- **Cutover is part of Done-when**: one-shot rewrite script for **active** strands' attrs, rehearsed against a *copy* of the canonical world's SQLite in a disposable world; documented cutover plan (quiet board → script → weaver restart → smoke via `agent status`/`stalled-gates`/`kanban board`). The canonical weaver restart itself requires explicit user sign-off — hard stop.
- No dual-read compat shims (TEN-000).
- Blocked for implementation by the in-flight tiered-validation-v2 queue and `vk8aa` (shared doc/test files); design stages may proceed.

## Validation

`make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test`; `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check`; `make api-docs`; `git status --short` clean of runtime artifacts.
