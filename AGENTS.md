# Agent Contributor Guide

Always read `./devflow/TENETS.md` and `./devflow/PHILOSOPHY.md` before all work.

Run `mill strand prime` before planning or tracking multi-step work with `strand`, and `mill skein prime` for orientation on the Skein source, docs, and how to extend the `.skein/` config. These CLI commands are the harness-agnostic replacement for the former strand/skein skills.

Discovery convention (canonical doc: `docs/skein.md` "Discovery tiers"): **`prime`** is run-first orientation (`mill skein|strand prime`, `strand kanban prime`), **`about`** is an op's authored JSON manual (`strand kanban|agent about`), and **`help`** is generated from arg-spec data (`strand help [<op>]`, `strand <op> help|-h|--help` for subcommand ops) — never hand-write usage strings or subcommand dispatch; declare `:subcommands` instead (authoring rules: `docs/writing-shared-spools.md`).

Skein is daemon-core-first behind a small Go router: `mill` is the local entrypoint/supervisor, the long-lived weaver owns storage and runtime state, and the `strand` CLI stays a thin JSON control surface. Runtime customization belongs in trusted config and REPL workflows.

Canonical shipped contracts live in root specs:

- [Strand Model](./devflow/specs/strand-model.md)
- [CLI Surface](./devflow/specs/cli.md)
- [REPL API](./devflow/specs/repl-api.md)
- [Weaver Runtime](./devflow/specs/daemon-runtime.md)

Userland reference spools are indexed in [`spools/`](./spools/README.md), with classpath-shipped sources under `spools/src`, externally git-distributed spools consumed by coordinate, and contract docs beside them:

- [Batteries](./spools/batteries.md) (classpath-shipped spool: the shipped `strand <op>` command surface — `add`/`update`/`show`/`supersede`/`burn`/`list`/`ready`/`subgraph`/`weave`/`query`/`pattern`)
- [Workflow Engine](./spools/workflow.md)
- [Devflow Lifecycle](./spools/devflow.md) (external RFC-017 git-distributed spool: source in [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool), consumed via the `.skein/spools.edn` git coordinate)
- [Ephemeral Strands](./spools/ephemeral.md)
- [Weaver Guild](./spools/guild.md)
- [Bobbin Context Packs](./spools/bobbin.md)
- [Selvage Attribute Lint](./spools/selvage.md)
- [Carder Graph Hygiene](./spools/carder.md)
- [Agent Shuttle](./spools/shuttle/README.md) (approved local-root spool; run engine only)
- [Agents Spool](./spools/agents/README.md) (approved local-root spool; `strand agent` surface over shuttle)
- [Treadle Gate Bridge](./spools/shuttle/treadle.md) (approved local-root spool)
- [Chime Notifications](./spools/chime/README.md) (approved local-root spool)
- [Kanban Board](./spools/kanban.md) (approved local-root spool; user↔agent work board — feature/epic cards, notes, handovers)

Namespace tiers are intentional: `skein.api.*.alpha` is the blessed spool-facing API with accretion-based compatibility within each subnamespace, `skein.core.*` is internal and may change freely, `skein.spools.*` is the authorable/reference spool layer, `skein.repl` is the human interactive surface, and `skein.userland.alpha` is a userland-only terse ergonomics layer — a strict downstream consumer tier that no `skein.*` namespace may ever require.

Contributor and userland guidance docs live in [`docs/`](./docs):

- [Writing shared spools](./docs/writing-shared-spools.md) — composability-over-ergonomics rules for spools others will run, and the `skein.userland.alpha` layering pattern for your own config.
- [Library authoring](./docs/library-authoring.md) — testing spools/libraries against a selected Skein checkout: local-root test deps, testing tiers, `skein.test.alpha` weaver worlds, and the test-JVM vs weaver classpath boundary.

## Project commands

Use Homebrew OpenJDK when Java is not on the default PATH:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

Common commands:

```sh
make install
mill start                    # run in a durable terminal
mill init                     # creates/completes canonical repo .skein

mill weaver start
strand list
mill weaver status
mill weaver stop
clojure -M:test
(cd cli && go test ./...)
clojure -M:repl
make dash                     # code-owner TUI over the live coordination world: agent runs/plans, kanban board, devflow (scripts/shuttle-dash/, Ink app polling the strand CLI)
```

## Agent operation quick reference

Agents must prefer explicit disposable `--workspace` workspaces. Never use or mutate the user's default config/data/state workspaces unless explicitly asked.

Kill processes by PID only. Never `pkill -f <pattern>`, `pkill clojure`, or any command-line/argv match to clean up a stuck process. The shipped `claude` and `pi` harnesses now deliver their worker prompt on stdin, so it no longer rides in argv — but other and custom harnesses may still pass the prompt as an argument (`:prompt-via :arg`, the default for any harness that doesn't opt into `:stdin`), and that prompt quotes the very commands you are trying to clean up (the test gate, a build step), so a pattern kill aimed at one stuck JVM can still strafe unrelated sibling agents whose argv happens to mention the same text. On 2026-07-05 a `pkill -f "clojure -M:test"` meant to clear stuck test JVMs killed two healthy delegated build runs within a second. Find the specific PID (`jps`, `ps`, or the run's recorded pid) and `kill <pid>`.

```sh
make install
workspace=$(mktemp -d)
xdg=$(mktemp -d)
export XDG_STATE_HOME="$xdg"
mill init --workspace "$workspace"

mill start &
mill_pid=$!
until mill status >/dev/null 2>&1; do sleep 0.1; done
mill weaver start --workspace "$workspace"
design=$(strand --workspace "$workspace" add "Sketch model" --state closed --attr priority=high)
docs=$(strand --workspace "$workspace" add "Write docs" --attr owner=agent)
strand --workspace "$workspace" update "$docs" --edge depends-on:$design
strand --workspace "$workspace" ready
mill weaver stop --workspace "$workspace"
kill "$mill_pid"
```

Use `mill weaver repl` for interactive trusted exploration:

```sh
mill weaver repl --workspace "$workspace"
```

```clojure
(init!)
(def design (:id (strand! "Sketch model" {:priority "high"} {:state "closed"})))
(def docs (:id (strand! "Write docs" {:owner "agent"})))
(update! docs {:edges [{:type "depends-on" :to design}]})
(defquery! 'agent-owned '[:= [:attr :owner] "agent"])
(ready)
```

For non-interactive trusted forms:

```sh
printf '(skein.api.current.alpha/runtime)\n' | mill weaver repl --stdin --workspace "$workspace"
```

For spool workspace workflows, use `spools.edn` (approving `:local/root` paths or sha-pinned `:git/url`+`:git/sha` coordinates), explicit-runtime `skein.api.runtime.alpha/sync!`, layered `runtime/use!` after `(skein.api.current.alpha/runtime)`, and live weaver REPL/config loading. Shared spools document prerequisite approvals and activation order in README Dependency information / Activation snippets; see docs/writing-shared-spools.md. There are intentionally no plugin/package CLI commands and no transitive spool fetching.

## Repo coordination workspace (.skein)

The canonical repo `.skein` workspace is the **shared coordination world**: the kanban board, devflow lifecycle runs, delegation, and cross-agent work tracking live there, and using it for coordination state is expected. The disposable `--workspace` rule above still governs everything else — dev experiments, config smoke tests, and test weavers never run against the canonical world.

Always read `docs/skein.md` from the repository root before changing the `.skein` config itself.

This repo's `.skein` world is thin glue over the reference spools. `.skein/init.clj` activates `skein.spools.batteries` (the shipped `strand <op>` command surface), `skein.spools.ephemeral`, and `skein.spools.workflow` from the weaver classpath, `skein.spools.devflow` from the approved `codethread/devflow` git coordinate (an RFC-017 git-distributed spool: `.skein/spools.edn` pins the `:git/sha`, a developer's gitignored `spools.local.edn` overrides it with a local root), plus `skein.spools.shuttle`, `skein.spools.agents`, and `skein.spools.treadle` from the approved `spools/shuttle` / `spools/agents` local roots, `skein.spools.chime` from the approved `spools/chime` local root, and `skein.spools.kanban` from the approved `spools/kanban` local root, and loads `.skein/reviewers.clj` — the declarative reviewer roster file (the source of truth for who reviews a change here). Together, the installed spools, `.skein/config.clj`, and `.skein/reviewers.clj` register:

- ops: `agent`, `kanban`, `branches`, `devflow-start`, `devflow-next`, `devflow-choices`, `devflow-choose`, `devflow-complete`, `devflow-advance`, `devflow-describe`, `devflow-history`, `devflow-archive`, `devflow-status`, `workflow-runs`, `current-dags`, `flow-await`, `flow-status`, `devflow-conventions`
- queries: `work`, `kanban-cards`, `kanban-unstarted`, `feature-active`, `feature-work`, `feature-owner-work`, `feature-run`, `workflow-runs`, `devflow-runs`, `agent-failures`
- patterns: `agent-plan`, `delegate-pipeline`, `kanban-batch`
- shuttle harness aliases: `pi-main` (delegation default), claude tiers matched to roles — `explore` (haiku: fan-out search/read-only recon), `grunt` (sonnet: tests and mechanical work), `build` (opus: feature building, reviews, councils), plus `oracle` (fable: reserved for extreme diagnosis — single-case briefs with mandatory incremental card notes; never routine work) — and GPT seats for cross-vendor validation — `review-gpt` (gpt-5.4 high reasoning via pi: standing reviewer seat) and `hard-gpt` (gpt-5.5 medium via codex: occasional difficult tasks wanting a second frontier model). These GPT seats also make **cross-vendor councils** possible: `strand agent council` seats identical members from the shell, while the trusted-Clojure `:seats` form (`skein.spools.agents/council!` / `panel!`) gives per-seat harnesses so a single deliberation spans vendors — harness now has no silent default, so a council with no resolvable harness fails loudly. Routing policy: `build` is favoured for prose/docs-heavy work but never signs off its own output — sign-off review of opus-authored work always includes a GPT seat. `strand agent harnesses` lists all. Use the spool-owned surface: run `strand agent about` for the live manual, delegate existing task strands with `strand agent delegate <task-id>`, fan out with `strand agent delegate --ready <plan-id>`, recover with `strand agent retry <task-or-run-id>` (shipped `claude`/`pi` harnesses capture their session id, so `retry` re-resumes it by default; `retry --fresh` severs a lost or resume-classed session and respawns cold on the full-brief prompt — see shuttle README §3.1), and inspect with `strand agent status [root-id]`. Review completed work with the declared roster: `strand agent review <target-id> --roster change-review --cwd <worktree> --commit-range <base>..HEAD` fans out the reviewers declared in `.skein/reviewers.clj` (one small single-concern run per entry — including the required test-sleeps pass — synthesized by the GPT seat) and appends findings as notes on the target strand; `strand agent rosters` shows the live roster, and ad hoc `--members`/`--harness` reviews remain for one-off passes the roster doesn't cover. Raw `strand agent spawn` remains the escape hatch for custom shuttle runs. Workflow `:subagent` gates are fulfilled automatically by the treadle (`spools/shuttle/treadle.md`).
- chime attention rules: `hitl-checkpoint-ready`, `agent-failure`, `treadle-error`, and `parked-run` (the silent-parking detector: a ready pending shuttle run left unclaimed past the threshold with no in-flight claim). Notification is already set up for everything a human needs to action; each developer only binds how they are told, in gitignored `.skein/init.local.clj`, e.g. `(require '[skein.spools.chime :as chime]) (chime/set-notifier! {:argv ["cc-notify"]})` — swap the argv for `osascript` or anything with the `cmd <title>` + body-on-stdin shape. Unbound chime records loud notifier-missing failures in `(chime/failures)`.

Contracts for the underlying spools live in the repo-root spools area: [`spools/workflow.md`](./spools/workflow.md) (engine) and [`spools/devflow.md`](./spools/devflow.md) (lifecycle). Run `strand devflow-conventions` for the live installed surface.

### Driving the devflow lifecycle

The feature name is the workflow run-id. Each stage pours a molecule of ordinary strands; checkpoints route between stages (see devflow.md §2 for the stage map).

```sh
strand devflow-start <feature> [required|already-in-worktree-ok]
strand devflow-next <feature>
strand devflow-choices <feature>
strand devflow-choose <feature> <choice> ['{"key":"value"}'] [step=<id>]
strand devflow-complete <feature> ['notes'] [step=<id>]
strand devflow-advance <feature> [choice] ['{"key":"value"}'] ['notes'] [step=<id>]
strand devflow-describe [stage-key]
strand devflow-history <feature>
strand devflow-archive <feature>
strand devflow-status <feature>
```

Rules of the road:

- Step views tell you what to do: act on `instruction`/`action-ref`/`artifact`, then `devflow-complete` or the unified `devflow-advance`. Record what happened in `notes`.
- A `checkpoint` step view is decided with `devflow-choose` or `devflow-advance <feature> <choice>`, never `devflow-complete`. Checkpoints marked `workflow/hitl` are human decisions: stop and ask the user, do not choose for them.
- Aborting requires a reason: `strand devflow-choose <feature> abort '{"reason":"..."}'`.
- To delegate the AFK loop through treadle, approve task sign-off with task data: `strand devflow-choose <feature> approved '{"tasks":[...],"delegate-harness":"pi-main","delegate-cwd":"/path/to/worktree"}'`.
- A routed choice closes out the current stage's remaining steps — it is a hard cutover, not a pause (workflow.md §5).
- The same commands are available in the trusted REPL via `skein.spools.devflow` (`start!`, `next-steps`, `choose!`, `complete!`, ...), which also exposes composition (`devflow-cycle`, stage constructors) that the CLI intentionally does not.

Discover active runs and actionable work:

```sh
strand ready --query work
strand list --query work --state active
strand list --query devflow-runs
strand workflow-runs devflow
strand list --query feature-run --param feature=<feature>
```

`work` is the default repo-local ready query for agents: it keeps normal tasks, workflow steps, and checkpoints visible, but hides bookkeeping strands whose `workflow/role` is `molecule`, `procedure`, or `digest`.

### Kanban board convention

The kanban board (`spools/kanban.md`) is the user↔agent work surface, held entirely in strands: anything the user asks for is a `feature` card, and **every agent working directly with the user works under a claimed card**. It complements devflow, agent plans, and delegation, which hang beneath cards via `parent-of`.

Run **`strand kanban prime`** before working the board — it is the live, spool-generated source of truth for the full working discipline (lanes, the pick-up-next flow, `kanban-batch` bulk authoring, the notes/handover contract, `needs-review`, and adjacent-work awareness). `strand kanban about` is the terse command manual.

### Branch work visibility

Every piece of work happening on a branch has exactly one **active work root strand** stamped with `branch` (plus `owner`, and `worktree` when one exists), with all execution strands hanging beneath it via `parent-of`. `kanban claim` stamps card roots; for non-card work (ad hoc `agent-plan` roots, coordination strands), stamp the root yourself: `strand update <root-id> --attr branch=<branch> --attr owner=<name>`. Children do not need their own `branch` attr — they are reachable from the root.

`strand branches` answers "what is going on inside each feature branch": it groups active branch-stamped roots by branch and joins each root to its active descendants and ready frontier. `strand branches <branch>` scopes to one branch and fails loudly if nothing is stamped with it. This is the interim convention from RFC-014 (`REC1`); the durable roster spool (`RFC-014.O3`) is a separate card on the board.

### Custom workflows

Author ad-hoc workflow molecules from the REPL with `skein.spools.workflow` (`workflow`, `step`, `gate`, `checkpoint`, `call`, `pour!`, `start!`). Call `(skein.spools.workflow/explain)` for machine-readable builder contracts before constructing definitions.

### Lightweight plans (agent-plan)

For small non-lifecycle work DAGs, use the `agent-plan` pattern instead of raw `add`/`update` commands:

```sh
strand pattern explain agent-plan
printf '%s' '{
  "feature":"<slug>",
  "title":"Feature: <name>",
  "tasks":[
    {"key":"impl","title":"Implement <outcome>","validation":["clojure -M:test"]},
    {"key":"review","kind":"review","title":"Review <outcome>","depends_on":["impl"]}
  ]
}' | strand weave --pattern agent-plan
strand ready --query feature-work --param feature=<slug>
```

Any strand delegated to another agent must include a descriptive `body` attribute. Use `owner` plus `branch` together when assigning ready work so other agents avoid duplicating it. Prefer the spool-owned delegation op:

```sh
strand agent delegate <task-id> --prompt "Extra implementation constraints if needed"
```

Delegated agents receive the spool-owned worker contract. `agent delegate` resolves routing as flags > task attributes for `harness` (no default), and `--cwd` > task `cwd` > workspace root for cwd. `agent-plan` may set optional `harness`, `cwd`, `validation`, and `max-attempts` keys on each task.

For sequential delegated workflows, use `delegate-pipeline`:

```sh
printf '%s' '{"run_id":"pipe-1","harness":"pi-main","accept":true,"tasks":[{"id":"a","title":"Do A","body":"..."}]}' | strand weave --pattern delegate-pipeline
```

### Config changes

Smoke test config changes in a disposable `--workspace` world when possible; do not reload the main canonical weaver unless explicitly asked. Reload a selected world with:

```sh
printf "(do (require '[skein.api.current.alpha :as current] '[skein.api.runtime.alpha :as runtime-alpha]) (runtime-alpha/reload! (current/runtime)))\n" | mill weaver repl --stdin --workspace "$world"
```

### Weaver reload vs restart

Never restart a running weaver (`mill weaver stop`/`start`) just to pick up code or config changes — a restart tears down live shuttle runs and weaver-lifetime registries other agents depend on. Restarting the canonical weaver requires explicit user sign-off. Match the change to the lightest pickup path:

- Go CLI changes (`cli/`): `make install` only; no weaver action at all.
- Selected-workspace config/startup file changes: `runtime-alpha/reload!` (above) — it clears registries, reinstalls built-ins, and re-runs startup files. Spool state under `skein.api.runtime.alpha/spool-state` (including shuttle run supervision) survives reload.
- Changes to already-loaded Clojure namespaces (core `src/skein/**` or spool sources): `reload!` alone is NOT enough — startup files re-run but `require`/sync skip namespaces already loaded in the JVM. Send a targeted `(require 'the.changed.ns :reload)` via `mill weaver repl --stdin` first, then `reload!` if the namespace registers ops/queries/patterns.
- Restart is genuinely needed only when the JVM itself must change: classpath/deps.edn edits, weaver launch/transport/socket changes, or an unhealthy JVM.

### Coordination attention surface

Use `strand flow-await <workflow-run-id> [--timeout-secs n]` to block until a workflow is done, reaches any checkpoint, reaches an unattended gate, or a treadle-managed subagent gate stalls. Use `strand flow-status <workflow-run-id>` for a read-only JSON status payload that joins workflow run history, the ready frontier, subagent gates, their treadle/shuttle run state, stalled gates, agent failures, and done-ness for renderer consumption. Shuttle run records are excluded from `strand ready --query work`; inspect failed delegation records with `strand list --query agent-failures`, `strand ready --query stalled-gates`, and `strand agent logs <run-id> --tail 80`.

## Validation and smoke testing

Primary validation:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

The smoke demo builds temporary `strand` and `mill` CLIs, `mill init`s a disposable `--workspace` workspace, starts a disposable mill and weaver, exercises the strand dispatcher's batteries ops (`add`/`update`/`list`/`ready`/`show`/`weave`, payload-ref flags, `--dry-run`, `strand help`, unknown-op failure, and a streaming op) plus direct live `mill weaver repl --stdin`, exercises REPL helpers against a real weaver, then removes generated state, data, config, socket, metadata, and built CLI artifacts.

### Cooperative swarm validation gate

When several agents run the full suite concurrently on one machine (delegated swarms, parallel worktrees), the suite's fixed timing budgets execute under CPU and fork-pressure starvation that a solo run never sees — three concurrent suites put ~40 runnable threads and a dozen JVMs on a 10-core box — and the flakes that follow are load artefacts, not real failures. In agent/swarm contexts, serialize the suite behind a machine-wide advisory lock so only one full run holds the CPU at a time:

```sh
flock -w 3600 /tmp/skein-test.lock clojure -M:test
```

This is a cooperative queue at the gate, not code-level locking, and it is deliberately **not** baked into the `:test` alias: a solo interactive run must not pay the mutex cost, and the lock only means something when siblings honour the same convention. Reach for it whenever you know other agents may be testing at the same time. The queue also protects unrelated interactive sessions on the machine, since the fork-pressure starvation it prevents is machine-global.

`flock` is a util-linux tool and is not shipped with macOS — on this host install it with `brew install util-linux` (it lands at `/opt/homebrew/opt/util-linux/bin/flock`, so add that to `PATH` or symlink it). Any equivalent whole-run mutex on the same well-known lock path serves the same purpose.

Tests and smoke workflows must isolate weaver workspaces with temporary workspaces. Do not start test weavers through implicit repo discovery or any user-owned workspace.

After validation, `git status --short` should not show generated SQLite or runtime metadata artifacts.

## Debugging SQLite state

```sh
sqlite3 smoke-cli.sqlite.workspace/data/skein.sqlite '.schema'
sqlite3 smoke-cli.sqlite.workspace/data/skein.sqlite 'select id, title, attributes from strands;'
sqlite3 smoke-cli.sqlite.workspace/data/skein.sqlite 'select from_strand_id, to_strand_id, edge_type, attributes from strand_edges;'
```

## Implementation boundaries

- Keep the CLI thin: parse command-line input, normalize output, and route strand commands through the weaver client.
- Keep SQL and shared persistence behavior in `skein.core.db`.
- Keep strand attributes as JSON `TEXT`; do not introduce JSONB assumptions.
- Keep public CLI automation in `cli/` and weaver transport glue thin.
- Keep interactive convenience wrappers in `skein.repl`.
- Fail loudly on invalid CLI input instead of silently falling back.
- Keep public CLI machine output JSON-only; EDN belongs to Clojure REPL/config/dev workflows.
- When changing shipped behavior, update the relevant root spec in `devflow/specs/`.
- Every Clojure `ns` gets a docstring (string right after the ns symbol) describing its purpose, for `clojure.repl/doc` and editor/cljdoc discovery.
- Runtime publication discipline: a real weaver process publishes exactly one ambient runtime (atomic, double-publish fails loudly). Everything else — tests, fixtures, embedded/tooling runtimes — starts with `:publish? false` and passes the runtime explicitly or runs under `skein.core.weaver.runtime/with-runtime-binding`. Never reach for the published singleton outside daemon startup/REPL ergonomics paths.
- Spool state is runtime-owned via `skein.api.runtime.alpha/spool-state`; no module-level atoms in spools.
- Never `:keys`-destructure `:fn` (or any clojure.core macro name) into a local: a local named `fn` silently shadows the `fn` macro and turns later thunks into eager calls. Rename on destructure instead: `{fn-sym :fn}`.
