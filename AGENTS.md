# Agent Contributor Guide

Read `./devflow/TENETS.md` and `./devflow/PHILOSOPHY.md` before all work, then let the system orient you — this file holds only what the live surface cannot tell you.

## Discovery: ask the system, not this file

- `mill skein prime` — source/docs/config layout; `mill strand prime` — planning and tracking (run before multi-step work); `strand kanban prime` — board discipline (run before working the board).
- `about` manuals (`strand agent about`, `strand kanban about`, `strand land about`) and generated `strand help [<op>]` cover op semantics and invocation; the three-tier convention is documented in `docs/skein.md` "Discovery tiers".
- The installed surface is live: `strand devflow-conventions` (registered ops, queries, patterns), `strand agent harnesses` / `strand agent rosters` (seats and rosters; routing policy sits beside the definitions in `.skein/harnesses.clj` / `.skein/reviewers.clj`), `strand pattern explain <name>`.
- Shipped contracts: `devflow/specs/` — update the relevant root spec when changing shipped behavior; namespace tiers are contractual (SPEC-003.C19). Reference spools are indexed in `spools/README.md`; authoring rules in `docs/writing-shared-spools.md`; user reference in `docs/skein.md`.

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
make spool-suite-gate                   # pinned external spool suites vs this checkout — CI-blocking (GITLIBS=<dir> overrides the gitlibs cache)
make fmt-check lint reflect-check docs-check   # blocking CI quality gates, held at zero findings
make api-docs                           # regenerate *.api.md after touching any spool or skein.api.*.alpha docstring
```

`make lint` includes `lint-conventions` (ns docstrings everywhere; no locals named after clojure.core macros). New splint suppressions need written justification in `.splint.edn`. After validation, `git status --short` must not show generated SQLite or runtime metadata artifacts. Strand data is plain SQLite under a workspace's `data/skein.sqlite`.

## Hard rules

- **Agents never run `make install`** — it clobbers the user's global on-PATH binaries, which is the user's call. Use `make build` and the repo-local binaries.
- **Never restart a running weaver to pick up changes**; restarting the canonical weaver requires explicit user sign-off (it tears down live agent runs and registries other agents depend on). Pickup ladder: `make build` for Go CLI changes; `runtime/reload!` for config/startup files; a targeted `(require 'the.ns :reload)` first for already-loaded base-classpath namespaces; `runtime/reload-spool!` for already-loaded synced spools (a bare `:reload` is blind to spool classloaders); only JVM-level changes or a `sync!`-recorded pending generation justify a restart. Semantics and recipes: `docs/skein.md` "Startup config".
- **Kill by PID only**, never `pkill -f <pattern>` — delegated agents' prompts can quote the very command you match, so a pattern kill strafes healthy sibling runs.
- **Disposable workspaces for everything except coordination.** Tests, smoke runs, and config experiments use `--workspace` worlds from `mktemp -d`, never the user's default workspaces. Hold the path in your own shell variable and guard every expansion with `${ws:?}` — an empty variable must fail the command, not silently resolve to the canonical world.
- **Warm test output never satisfies a Done-when gate**, and the full suite is serialized across agents: hold the flock (bare `flock`, on PATH via nix), and run it only at queue acceptance and land merge-local-verify. `SKEIN_TEST_AWAIT_SCALE` multiplies await budgets on slow hosts (CI sets 3).

## Repo coordination workspace (.skein)

The repo's `.skein` workspace is the shared coordination world — kanban board, devflow runs, delegation, and cross-agent tracking live there; everything else follows the disposable-workspace rule. Config layout and change discipline live in the `.skein/init.clj` header; smoke-test config changes in a disposable world first (note: `spools.edn` local roots resolve relative to the config dir).

- Work under a claimed kanban card and note as you go — `strand kanban prime` is the source of truth for board, claiming, branch visibility, and notes.
- Features run the devflow lifecycle (`strand devflow-start <feature>`; ops in `strand devflow-conventions`); step views carry their own instructions.
- Delegate real work as tracked agent runs (`strand agent about`), not your harness's native subagents: an agent run is resumable, retryable, inspectable, and visible to other agents; native subagents are for cheap synchronous read-only recon only.
- Landing is coordinator-only (`strand land about`); worker agents stop at implemented+committed.
- Attention: `strand flow-await <run-id>` blocks until a run needs a coordinator (cap each await at ~50 minutes via `--timeout-secs` and re-issue, so provider prompt caches don't expire); `strand ready --query work` is the default ready view; failures surface via `strand list --query agent-failures` and `strand agent logs <run-id> --tail 80`.

## Implementation boundaries

- Keep SQL and shared persistence behavior in `skein.core.db`; strand attribute values stay JSON `TEXT` in the `attributes` table — no JSONB assumptions.
- Runtime publication: one ambient runtime per real weaver process (SPEC-004.C8a); tests and embedded runtimes start `:publish? false` and pass the runtime explicitly.
- Spool state is runtime-owned via `skein.api.runtime.alpha/spool-state`; no module-level atoms in spools.
