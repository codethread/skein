# Spools

Spools are trusted, authorable Clojure loaded into the weaver. The `skein.spools.*` namespace family is reserved for exactly this kind of code (see the [REPL API spec](../devflow/specs/repl-api.md)); the spools in this directory ship with Skein as working references — use them directly, copy them as starting points, or study them to author your own.

Every spool loads through one convention: an approved coordinate in `.skein/spools.edn`, synced into the weaver by explicit-runtime `sync!`, and activated by a `:spools`-guarded explicit-runtime `use!`. `batteries` is the single documented exception — see [Classpath exception: batteries](#classpath-exception-batteries) below.

Blessed alpha helpers such as `skein.api.peers.alpha` are also explicit-require userland APIs for trusted config and REPL workflows. Use that namespace's `peers`, `peer`, and `call!` helpers when a spool or repo config needs to discover and invoke same-machine sibling weavers.

## Doc triad

Each shipped spool's docs follow a three-file convention:

- **`<spool>.md`** — the **contract**: hand-authored guarantees, run lifecycle,
  and the attribute vocabulary. This is the load-bearing promise.
- **`<spool>.cookbook.md`** — authored **composition recipes**: how to shape
  real work out of the primitives, and *why* each shape is right. Present only
  where the value is in composition; [`workflow.cookbook.md`](./workflow.cookbook.md)
  is the template.
- **`<spool>.api.md`** — the **generated reference**: every public fn's
  signature, arity, and docstring, produced from source. Never hand-edit these;
  regenerate with `make api-docs`.

Signatures live only in the generated API doc; contracts and cookbooks link to them rather than restating them.

## Index

Each spool lives in its own root under `spools/<name>/src`, off the weaver's source classpath. A spool's `.skein/spools.edn` coordinate is what makes it reachable — `runtime/sync!` adds the approved root to the weaver runtime, and a `:spools`-guarded `runtime/use!` activates it. These spools also serve as the worked example of authoring your own ([docs/skein.md](../docs/skein.md#authoring-your-own-spool-code)); for publishing a spool for others by git coordinate, SHA-pinned approval, README dependency/activation snippets, Maven-only spool-root dependencies, and local development overrides, see [Writing shared spools](../docs/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution).

| Spool | Coordinate (`.skein/spools.edn`) | Contract doc | API reference | Purpose |
|---|---|---|---|---|
| `skein.spools.workflow` | `../spools/workflow` | [workflow.md](./workflow.md) | [workflow.api.md](./workflow.api.md) · [cookbook](./workflow.cookbook.md) | Workflow engine: plain-data definitions compiled to strand batches, with loops, gates, checkpoints, routing, and rebindable tool bindings. |
| `skein.spools.executors.shell` | `../spools/workflow` (folded into the workflow root) | [executors/shell.md](./executors/shell.md) | [executors/shell.api.md](./executors/shell.api.md) · [cookbook](./executors/shell.cookbook.md) | Workflow `:shell` gate executor: runs a ready gate's `shell/argv` command directly on a spool-owned worker pool, closes it with `complete!` on a zero exit, and stamps a loud `shell/error` (with exit code and bounded output) on failure. Registers the `:shell` executor and the `stalled-shell-gates` query. |
| `skein.spools.ephemeral` | `../spools/ephemeral` | [ephemeral.md](./ephemeral.md) | [ephemeral.api.md](./ephemeral.api.md) · [cookbook](./ephemeral.cookbook.md) | Small helper for temporary, parent-owned strands marked and burned via a userland attribute. |
| `skein.spools.roster` | `../spools/roster` | [roster.md](./roster.md) | [roster.api.md](./roster.api.md) · [cookbook](./roster.cookbook.md) | Active-work registry: `roster/*` attribute vocabulary, explicit-runtime `track!`/`heartbeat!`/`finish!`/`roster`/`await-quiet!` helpers, a declared-subcommand `roster` op and named query, awaitable quiet/stale semantics, and automatic workflow/devflow root stamping. |
| `skein.spools.loom` | `../spools/loom` | [loom.md](./loom.md) | [loom.api.md](./loom.api.md) · [cookbook](./loom.cookbook.md) | Read-only work-graph projections: active parent-of work DAGs with depends-on edges, per-branch progress views joined to a ready frontier, and workflow flow-status (history/frontier/gates/runs/stalls) with a Mermaid gate chain. Registers no ops. |
| `skein.spools.carder` | `../spools/carder` | [carder.md](./carder.md) | [carder.api.md](./carder.api.md) · [cookbook](./carder.cookbook.md) | Read-only graph hygiene reports: stale active work, orphaned strands, and work blocked behind failed agent runs. |
| `skein.spools.text-search` **(UNSAFE)** | `../spools/text-search` | [text-search.md](./text-search.md) | [text-search.api.md](./text-search.api.md) · [cookbook](./text-search.cookbook.md) | **UNSAFE reference spool** — requires `skein.core.db` and runs SQL against the physical tables to `LIKE`-search titles and attribute values, including archived rows the query language cannot see. Registers the `search` op. A maintained example of breaking the namespace-tier rules in the open, not a blessed path; read its [Unsafe declaration](./text-search.md#unsafe-declaration) before activating. |
| `skein.spools.bobbin` | *(none approved in this repo)* | [bobbin.md](./bobbin.md) | [bobbin.api.md](./bobbin.api.md) · [cookbook](./bobbin.cookbook.md) | Context-pack assembler: one self-contained JSON bundle of a strand's blockers, dependents, provenance, notes, and workflow context, plus deterministic prompt-text rendering. |
| `skein.spools.guild` | *(none approved in this repo)* | [guild.md](./guild.md) | [guild.api.md](./guild.api.md) · [cookbook](./guild.cookbook.md) | Versioned public weaver op API declarations, `guild.describe` introspection, and loud structured deprecation for local peer coordination. |
| `skein.spools.selvage` | *(none approved in this repo)* | [selvage.md](./selvage.md) | [selvage.api.md](./selvage.api.md) · [cookbook](./selvage.cookbook.md) | Opt-in attribute vocabulary lint: data-first checks per attribute namespace, on-demand `check`/`check-all`, and post-hoc watch-mode violation recording. |
| `skein.spools.agent-run` | `../spools/agent-run` | [agent-run/README.md](./agent-run/README.md) | [agent-run.api.md](./agent-run.api.md) · [cookbook](./agent-run.cookbook.md) | Agent-run **engine**: readiness-driven headless coding-agent runs plus interactive multiplexer sessions (backend registry, claims-model reaping), harness aliases, crash reconciliation, storage-enforced write-once run memory, and the preamble seam. Registers no ops. |
| `skein.spools.delegation` | `../spools/delegation` | [delegation/README.md](./delegation/README.md) | [delegation.api.md](./delegation.api.md) · [cookbook](./delegation.cookbook.md) | Cross-harness subagent surface over agent-run: the `strand agent` verbs, the `agent-plan` weave pattern, delegation/retry/status, and the worker + coordinator guidance. |
| `skein.spools.executors.subagent` | `../spools/agent-run` (folded into the agent-run root) | [executors/subagent.md](./executors/subagent.md) | [executors/subagent.api.md](./executors/subagent.api.md) · [cookbook](./executors/subagent.cookbook.md) | Workflow gate bridge: fulfills ready `:subagent` gates by spawning agent-run runs and delivering successful results through `workflow/complete!`. |
| `skein.spools.chime` | `../spools/chime` | [chime/README.md](./chime/README.md) | [chime.api.md](./chime.api.md) · [cookbook](./chime.cookbook.md) | Notification engine: watches graph mutations, evaluates user-registered rules, and sends matches through a user-bound local notifier command. |
| `skein.spools.kanban` | git, sha-pinned (see below) | [kanban.md](https://github.com/codethread/kanban.spool/blob/54eea43e3afc50c3f17335d297a74af8d6767704/kanban.md) | — | User-facing kanban board: feature/epic cards, refinement/pending/claimed/in_review lanes, notes and handovers via `strand kanban`. |
| `skein.spools.cron` | `../spools/cron` | [cron/README.md](./cron/README.md) | [cron.api.md](./cron.api.md) · [cookbook](./cron.cookbook.md) | Userland recurrence layer over durable scheduler wakes: registers named interval+jitter jobs, records last-outcome/failure status, and leaves next-fire timing to scheduler introspection. Ships no jobs. |
| `skein.spools.bench` | `../spools/bench` | [bench/README.md](./bench/README.md) | [bench.api.md](./bench.api.md) | Deterministic, containerized benchmarking of coding-agent harnesses: pinned repo/prompt/memory overlays, bench-owned entry execution, normalized metrics, and an agent-run served judge. |
| `skein.spools.devflow` | git, sha-pinned (see below) | [devflow.md](https://github.com/codethread/devflow.spool/blob/6c0f8c7e20a7f6de4cf81c98f4d7a33388663592/devflow.md) | — | Reference devflow lifecycle built on the workflow engine: intake → proposal → spec/plan → tasks/implementation stages with HITL checkpoints. |

`bobbin`, `guild`, and `selvage` are never-activated reference roots: this repo carries their source and tests but adds no `.skein/spools.edn` coordinate for them — a downstream user opts in by adding one.

`skein.spools.devflow` is consumed from [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool) by git coordinate rather than a local root — the worked example of publishing a spool for others (RFC-017, [Writing shared spools](../docs/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution)). This repo pins a sha-pinned `:git/url`+`:git/sha` coordinate in `.skein/spools.edn`, activates it with `:required? true` in `.skein/init.clj`, and pins the same sha as a tools.deps git dep for the test JVM; developers override the coordinate with a gitignored `spools.local.edn` local root to work against a checkout.

`skein.spools.kanban` is the second external spool: it lives in [`codethread/kanban.spool`](https://github.com/codethread/kanban.spool) and requires `skein.spools.devflow` (the `kanban card` devflow join), so `.skein/init.clj` activates it after devflow with both coordinates in its `:spools` guard. Like devflow, `.skein/spools.edn` and the test JVM (`deps.edn`) pin the same sha-pinned `:git/url`+`:git/sha` coordinate — config_test enforces the pairing — and developers override it with a gitignored `spools.local.edn` local root.

## Classpath exception: batteries

| Spool | Contract doc | API reference | Purpose |
|---|---|---|---|
| `skein.spools.batteries` | [batteries.md](./batteries.md) | [batteries.api.md](./batteries.api.md) · [cookbook](./batteries.cookbook.md) | Shipped core strand command surface as registered ops: add/update/show/supersede/burn/list/ready/subgraph plus `weave` and the `query`/`pattern`/`vocab` registry-introspection reads, all parser-backed. |

`batteries` is the base strand command surface every fresh `mill init` world needs at zero config — a fresh workspace seeds `spools.edn` as `{:spools {}}`, yet must still get add/update/show/supersede/burn/list/ready/subgraph plus the `weave`/`query`/`pattern`/`vocab` reads. It is non-escalating (a CRUD/query surface, not a capability escalation like the agent-run harness spawn), which is why it earns a classpath exception rather than an approved coordinate: bootstrap cannot write a source-relative `spools.edn` coordinate without persisting a machine-specific source-checkout path, which the weaver runtime spec forbids. `batteries` therefore ships on the weaver's source classpath (its own root, `spools/batteries/src`, on `deps.edn` `:paths`) and is loaded by an explicit `(require 'skein.spools.batteries)` placed above its `use!` in `init.clj` — an honest require, not a hidden loader fallback — rather than through `spools.edn` approval. Every other spool loads only through the approved `spools.edn` → `sync!` → `:spools`-guarded `use!` path.

## `util` and `format` left the spool family

`skein.spools.util` and `skein.spools.format` were never activatable spools — they registered no ops and no world `use!`d them; they were authoring libraries other spools built on. Both have left `skein.spools.*` for base-classpath `src/`: `format` is deleted in favor of the already-blessed `skein.api.format.alpha` (`fill`/`reflow`), and `util` is promoted to the blessed `skein.api.spool.alpha` (`fail!`, `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, `poll-until-deadline!`) — the accretion-compatible home for the spool-authoring helpers every reference spool leans on. After this move, `skein.spools.*` is exactly "activatable spools" and nothing else.

## Reference examples

- Each contract doc ends with worked examples (`workflow.md` §8,
  `devflow.md` §4).
- The test suites drive every documented behavior against a real weaver
  runtime and double as executable examples:
  [`test/skein/spools/workflow_test.clj`](../test/skein/spools/workflow_test.clj),
  the standalone devflow.spool test suite, and the ephemeral cases in
  [`test/skein/spools_test.clj`](../test/skein/spools_test.clj).

## Using and extending

- Strand **attributes are the extension surface**: `workflow.md` §7's
  attribute table is the workflow engine's extension API, and `devflow.md`
  §6 documents devflow's conventions on top of it. Build your own
  conventions the same way instead of waiting for engine fields.
- Workflow definitions accept pure-data **tool bindings** (`workflow.md`
  §3), so a consumer rebinds steps to their own tooling from trusted config
  without touching these namespaces.
- Some spools expose `install!` metadata (fns as symbol maps) for trusted
  registration by name; others use `install!` for side-effectful setup such as
  registering weaver ops. See each contract doc for exact behavior.
- To author and load your own spool from a workspace-local root, follow
  [Authoring your own spool code](../docs/skein.md#authoring-your-own-spool-code).
