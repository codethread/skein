# Agent Contributor Guide

Read `./devflow/TENETS.md` and `./devflow/PHILOSOPHY.md` before all work.

Skein is daemon-core-first behind a small Go router: `mill` is the local entrypoint/supervisor, the long-lived weaver owns storage and runtime state, and the `strand` CLI stays a thin JSON control surface. Runtime customization belongs in trusted config and REPL workflows.

## Discovery: ask the system, not this file

One convention, three tiers (canonical doc: `docs/skein.md` "Discovery tiers"):

- `prime` — run-first orientation: `mill skein prime` (source/docs/config layout), `mill strand prime` (planning and tracking; run before multi-step work), `strand kanban prime` (board discipline; run before working the board).
- `about` — an op's authored JSON manual: `strand agent about`, `strand kanban about`, `strand land about`.
- `help` — generated from arg-spec data: `strand help [<op>]`, `strand <op> help` for subcommand ops. Never hand-write usage strings or subcommand dispatch; declare `:subcommands` instead (authoring rules: `docs/writing-shared-spools.md`).

The installed surface is live and self-describing — prefer these over any static list:

- `strand devflow-conventions` — this repo's registered ops, queries, and patterns.
- `strand agent harnesses` / `strand agent rosters` — delegation seats and review rosters; routing policy lives beside the alias definitions in `.skein/harnesses.clj` and `.skein/reviewers.clj`.
- `strand pattern explain <name>` — weave pattern input shapes (`agent-plan`, `delegate-pipeline`, `kanban-batch`).

## Contracts and docs

- Shipped contracts: [`devflow/specs/`](./devflow/specs/) — strand model, CLI surface, REPL API, weaver runtime, and the alpha-surface index of in-contract vs internal. When changing shipped behavior, update the relevant root spec.
- Reference spools: indexed in [`spools/README.md`](./spools/README.md), each with its contract doc and cookbook beside it.
- Guidance: [writing shared spools](./docs/writing-shared-spools.md), [library authoring](./docs/library-authoring.md); user reference in [`docs/skein.md`](./docs/skein.md).

Namespace tiers are intentional: `skein.api.*.alpha` is the blessed spool-facing API (accretion-based compatibility within each subnamespace), `skein.core.*` is internal and may change freely, `skein.spools.*` is the authorable/reference spool layer, `skein.repl` is the human interactive surface, and `skein.userland.alpha` is a downstream-only ergonomics tier that no `skein.*` namespace may ever require.

## Commands

Use Homebrew OpenJDK when Java is not on the default PATH: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" <cmd>`.

```sh
make build                              # repo-local ./bin/strand, ./bin/mill — the agent path for CLI changes
mill start                              # supervisor, in a durable terminal
mill init [--workspace <dir>]           # create/complete a workspace
mill weaver start|status|stop|repl [--workspace <dir>]
make dash                               # code-owner TUI over the live coordination world

make test-warm NS="ns..."               # warm REPL to iterate a slice — never a Done-when gate
clojure -M:test <ns...>                 # cold focused run — the per-slice Done-when gate
flock -w 3600 /tmp/skein-test.lock clojure -M:test  # full locked suite — queue acceptance + land only; CI-blocking
(cd cli && go test ./...)               # primary validation, CI-blocking
clojure -M:smoke                        # primary validation, CI-blocking
make fmt-check lint reflect-check docs-check   # blocking CI quality gates, held at zero findings
make api-docs                           # regenerate spools/*.api.md after touching any spool docstring
```

`make security-report` (CI) and `make deps-report` (local-only, needs `CLJ_WATSON_NVD_API_KEY`) are non-blocking. New splint suppressions need written justification in `.splint.edn`. The codebase-wide formatting commit is listed in `.git-blame-ignore-revs`. After validation, `git status --short` must not show generated SQLite or runtime metadata artifacts.

Strand data is plain SQLite under a workspace's `data/skein.sqlite` — `sqlite3 <path> '.schema'` when you need to look inside.

## Hard rules

- **Agents never run `make install`** — it clobbers the user's global on-PATH binaries, which is the user's call. Use `make build` and the repo-local `./bin/strand` / `./bin/mill` for all CLI validation.
- **Never restart a running weaver to pick up changes**; restarting the canonical weaver requires explicit user sign-off (it tears down live agent runs and registries other agents depend on). Pickup ladder: Go CLI changes need only `make build`; config/startup-file changes need `runtime-alpha/reload!`; already-loaded Clojure namespaces need a targeted `(require 'the.ns :reload)` first (reload! alone skips loaded namespaces); only JVM-level changes (deps.edn, transport/socket, unhealthy JVM) justify a restart. Reload a selected world:

  ```sh
  printf "(do (require '[skein.api.current.alpha :as current] '[skein.api.runtime.alpha :as runtime-alpha]) (runtime-alpha/reload! (current/runtime)))\n" | mill weaver repl --stdin --workspace "$world"
  ```

- **Kill by PID only** (`jps`/`ps`/the run's recorded pid, then `kill <pid>`) — never `pkill -f <pattern>`: delegated agents' prompts can quote the very command you match, so a pattern kill strafes healthy sibling runs.
- **Disposable workspaces for everything except coordination.** Tests, smoke runs, and config experiments use `--workspace` worlds from `mktemp -d`, never the user's default workspaces and never implicit repo discovery. Hold the path in your own shell variable (a shared scratch file under /tmp gets clobbered by sibling agents) and guard every expansion with `${ws:?}` — an empty variable must fail the command, not silently resolve to the canonical world.
- **Test validation runs in three tiers, and warm is never a gate.** Iterate a slice with `make test-warm NS="ns1 ns2"`, a per-worktree warm REPL that gives sub-second focused runs; its output never satisfies a Done-when block. Gate each slice on the cold focused run `clojure -M:test <ns...>` naming the namespaces the slice touched. Run the full suite `flock -w 3600 /tmp/skein-test.lock clojure -M:test` only at queue acceptance and at land `merge-local-verify`. **Serialize the full suite across agents**: sibling full suites starve each other's timing budgets and flake, so hold the lock (bare `flock`, on PATH via nix — never a vendored absolute `flock` path). `SKEIN_TEST_AWAIT_SCALE` multiplies await budgets on slow hosts (CI sets 3); the lock is deliberately not baked into the `:test` alias. Warm and cold run the same in-process code path, the warm runtime files (`.test-repl-port`, `.test-repl.pid`) are gitignored, and no gate — CI or local — depends on warm state.

## Coordination: the canonical .skein world

The repo's `.skein` workspace is the shared coordination world — the kanban board, devflow runs, delegation, and cross-agent tracking live there, and using it for coordination is expected. Everything else follows the disposable-workspace rule. The world is thin glue over the reference spools, with repo policy split into one file per concern: activation order in `.skein/init.clj`, named queries and the CLI op surface in `config.clj`, hand-authored workflows (land, delegate-pipeline) in `workflows.clj`, harness seats and routing policy in `harnesses.clj`, chime attention rules in `attention.clj`, the NVD scan cron job in `nvd_scan.clj`, and reviewer rosters in `reviewers.clj`. Read `docs/skein.md` before changing the `.skein` config itself, and smoke-test config changes in a disposable world first.

Working discipline:

- **Kanban** — every agent working directly with the user works under a claimed card; `strand kanban prime` is the source of truth for lanes, claiming, notes, and handovers.
- **Devflow** — features run the devflow lifecycle; the feature name is the workflow run-id (`strand devflow-start <feature>`, then `devflow-next`/`devflow-advance`). Step views carry their own instructions: act, record what happened in notes, advance. Checkpoints are decided with `devflow-choose`/`devflow-advance <choice>`, never completed; checkpoints marked `workflow/hitl` are human decisions — stop and ask, never choose for the user. A routed choice is a hard cutover that closes the stage's remaining steps, and aborting requires a reason. Contract: [`spools/devflow.md`](./spools/devflow.md); the trusted REPL surface (`skein.spools.devflow`) adds composition the CLI intentionally lacks.
- **Delegation** — `strand agent delegate <task-id>` (fan out with `--ready <plan-id>`, recover with `strand agent retry`); any delegated strand needs a descriptive `body` attribute, plus `owner` and `branch` so siblings don't duplicate it. Prefer agent-run delegation over your harness's own subagent tools for any real work: an agent run is a tracked strand — resumable, retryable, inspectable with `agent logs`, and visible to other agents — where a native subagent is invisible to the coordination world and dies with your session. Native subagents are fine only for focused synchronous read-only calls (exploration, quick recon) whose loss costs nothing. Workflow `:subagent` gates are fulfilled automatically by the subagent executor. Everything else — spawn, councils, hitl sessions, status, logs — is in `strand agent about`.
- **Review** — completed work gets the declared roster: `strand agent review <target-id> --roster change-review --cwd <worktree> --commit-range <base>..HEAD`. Rosters live in `.skein/reviewers.clj`.
- **Landing** — coordinator-only; worker agents stop at implemented+committed. `strand land about` is the discipline manual.
- **Branch visibility** — every branch has exactly one active work root stamped `branch` + `owner` (plus `worktree` when one exists); `kanban claim` stamps cards, stamp ad hoc roots yourself. Children hang beneath via `parent-of` and need no stamp of their own. `strand branches [branch]` is the projection.
- **Attention** — `strand flow-await <run-id>` blocks until a run needs a coordinator; `strand flow-status <run-id>` is the read-only join for renderers. Cap any single blocking await at ~50 minutes (`--timeout-secs`) and re-issue it, so provider prompt caches don't expire while you sit idle. Failures: `strand list --query agent-failures`, `strand ready --query stalled-gates`, `strand agent logs <run-id> --tail 80`. `strand ready --query work` is the default agent ready view (it hides workflow plumbing and agent-run records). Chime attention rules already cover everything a human must action; each developer binds the notifier in gitignored `.skein/init.local.clj`.

## Implementation boundaries

- Keep the CLI thin: parse input, normalize output, route through the weaver client; keep `cli/` automation and weaver transport glue thin. Public CLI machine output is JSON-only; EDN belongs to REPL/config/dev workflows.
- Keep SQL and shared persistence behavior in `skein.core.db`; strand attribute values stay JSON `TEXT` in the `attributes` table — no JSONB assumptions.
- Keep interactive convenience wrappers in `skein.repl`.
- Fail loudly on invalid CLI input instead of silently falling back.
- Every Clojure `ns` gets a docstring describing its purpose.
- Runtime publication discipline: a real weaver process publishes exactly one ambient runtime (atomic, double-publish fails loudly). Tests, fixtures, and embedded/tooling runtimes start with `:publish? false` and pass the runtime explicitly or run under `skein.core.weaver.runtime/with-runtime-binding`; never reach for the published singleton outside daemon startup/REPL ergonomics paths.
- Spool state is runtime-owned via `skein.api.runtime.alpha/spool-state`; no module-level atoms in spools.
- Never `:keys`-destructure `:fn` (or any clojure.core macro name) into a local — a local named `fn` shadows the macro and turns later thunks into eager calls. Rename on destructure: `{fn-sym :fn}`.

<!-- mill:skein-prime -->
## Skein / strand

This repo uses Skein strands to track work. Orientation ships in the `mill` CLI:

- `mill skein prime` — where the Skein source and docs live, and how to extend this repo's `.skein/` config.
- `mill strand prime` — the strand planning/tracking workflow; run it before multi-step work.
<!-- /mill:skein-prime -->
