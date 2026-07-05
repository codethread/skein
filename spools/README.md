# Shipped reference spools

Spools are trusted, authorable Clojure loaded into the weaver. The
`skein.spools.*` namespace family is reserved for exactly this kind of code
(see the [REPL API spec](../devflow/specs/repl-api.md)); the spools in
this directory ship with Skein as working references — use them directly,
copy them as starting points, or study them to author your own.

Because they ship on the weaver classpath, no `spools.edn` approval is
needed — `require` them from `init.clj`, an activated spool, or a live
`mill weaver repl`:

```clojure
(require '[skein.spools.workflow :as workflow])
```

Blessed alpha helpers such as `skein.api.peers.alpha` are also explicit-require
userland APIs for trusted config and REPL workflows. Use that namespace's
`peers`, `peer`, and `call!` helpers when a shipped spool or repo config needs
to discover and invoke same-machine sibling weavers.

## Index

| Spool | Contract doc | Purpose |
|---|---|---|
| `skein.spools.batteries` | [batteries.md](./batteries.md) | Shipped core strand command surface as registered ops: add/update/show/supersede/burn/list/ready/subgraph plus `weave` and the `query`/`pattern` registry-introspection reads, all parser-backed. |
| `skein.spools.workflow` | [workflow.md](./workflow.md) | Workflow engine: plain-data definitions compiled to strand batches, with loops, gates, checkpoints, routing, and rebindable tool bindings. |
| `skein.spools.ephemeral` | [ephemeral.md](./ephemeral.md) | Small helper for temporary, parent-owned strands marked and burned via a userland attribute. |
| `skein.spools.guild` | [guild.md](./guild.md) | Versioned public weaver op API declarations, `guild.describe` introspection, and loud structured deprecation for local peer coordination. |
| `skein.spools.bobbin` | [bobbin.md](./bobbin.md) | Context-pack assembler: one self-contained JSON bundle of a strand's blockers, dependents, provenance, notes, and workflow context, plus deterministic prompt-text rendering. |
| `skein.spools.selvage` | [selvage.md](./selvage.md) | Opt-in attribute vocabulary lint: data-first checks per attribute namespace, on-demand `check`/`check-all`, and post-hoc watch-mode violation recording. |
| `skein.spools.carder` | [carder.md](./carder.md) | Read-only graph hygiene reports: stale active work, orphaned strands, and work blocked behind failed agent runs. |
| `skein.spools.roster` | [roster.md](./roster.md) | Active-work registry: `roster/*` attribute vocabulary, explicit-runtime `track!`/`heartbeat!`/`finish!`/`roster`/`await-quiet!` helpers, a declared-subcommand `roster` op and named query, awaitable quiet/stale semantics, and automatic workflow/devflow root stamping. |

## External git-distributed spools

Some reference spools no longer ship on the weaver classpath and are consumed by
git coordinate instead — the worked example of publishing a spool for others
(RFC-017, [Writing shared spools](../docs/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution)).
Unlike the classpath spools above, these require explicit `spools.edn` approval
of a pinned commit before the weaver will fetch or activate them.

| Spool | Source | Contract doc | Purpose |
|---|---|---|---|
| `skein.spools.devflow` | [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool) | [devflow.md](https://github.com/codethread/devflow.spool/blob/6c0f8c7e20a7f6de4cf81c98f4d7a33388663592/devflow.md) | Reference devflow lifecycle built on the workflow engine: intake → proposal → spec/plan → tasks/implementation stages with HITL checkpoints. |

This repo consumes devflow via a sha-pinned `:git/url`+`:git/sha` coordinate in
`.skein/spools.edn`, activates it with `:required? true` in `.skein/init.clj`,
and pins the same sha as a tools.deps git dep for the test JVM; developers
override the coordinate with a gitignored `spools.local.edn` local root to work
against a checkout. See the devflow.spool repo contract doc for the full consumption
recipe.

## Approved local-root examples

These live beside this index in the repo-root [`spools/`](./) directory, **off**
the shipped classpath, and load only through the approved-local-root flow
(`spools.edn` → explicit-runtime `sync!` → explicit-runtime `use!`). The placement rule: pure graph vocabulary
that other code builds on ships on the classpath above; a spool that
**escalates capability** (the shuttle spawns harness processes with the
user's authority) or exists to exercise the userland distribution path sits
here, behind the workspace's explicit `spools.edn` consent. They also serve
as the worked example of authoring your own spool
([docs/skein.md](../docs/skein.md#authoring-your-own-spool-code)). For
publishing a spool for others by git coordinate, SHA-pinned approval,
README Dependency information / Activation snippets, Maven-only spool-root
dependencies, and local development overrides, see [Writing shared spools](../docs/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution).

| Spool | Contract doc | Purpose |
|---|---|---|
| `skein.spools.shuttle` | [shuttle/README.md](./shuttle/README.md) | Agent shuttle **engine**: readiness-driven headless coding-agent runs plus interactive multiplexer sessions (backend registry, claims-model reaping), harness aliases, crash reconciliation, append-only run memory, and the preamble seam. Registers no ops. |
| `skein.spools.agents` | [agents/README.md](./agents/README.md) | Cross-harness subagent surface over shuttle: the `strand agent` verbs, the `agent-plan` weave pattern, delegation/retry/status, and the worker + coordinator guidance. |
| `skein.spools.treadle` | [shuttle/treadle.md](./shuttle/treadle.md) | Workflow gate bridge: fulfills ready `:subagent` gates by spawning shuttle runs and delivering successful results through `workflow/complete!`. |
| `skein.spools.chime` | [chime/README.md](./chime/README.md) | Notification engine: watches graph mutations, evaluates user-registered rules, and sends matches through a user-bound local notifier command. |
| `skein.spools.kanban` | [kanban.md](./kanban.md) | User-facing kanban board: feature/epic cards, refinement/pending/claimed lanes, notes and handovers via `strand kanban`. |

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
