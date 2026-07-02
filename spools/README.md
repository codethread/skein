# Shipped reference spools

Spools are trusted, authorable Clojure loaded into the weaver. The
`skein.spools.*` namespace family is reserved for exactly this kind of code
(see the [REPL API spec](../devflow/specs/repl-api.md)); the spools in
this directory ship with Skein as working references — use them directly,
copy them as starting points, or study them to author your own.

Because they ship on the weaver classpath, no `spools.edn` approval is
needed — `require` them from `init.clj`, an activated spool, or a live
`strand weaver repl`:

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
| `skein.spools.workflow` | [workflow.md](./workflow.md) | Workflow engine: plain-data definitions compiled to strand batches, with loops, gates, checkpoints, routing, and rebindable tool bindings. |
| `skein.spools.devflow` | [devflow.md](./devflow.md) | Reference devflow lifecycle built on the workflow engine: intake → proposal → spec/plan → tasks/implementation stages with HITL checkpoints. |
| `skein.spools.ephemeral` | [ephemeral.md](./ephemeral.md) | Small helper for temporary, parent-owned strands marked and burned via a userland attribute. |
| `skein.spools.guild` | [guild.md](./guild.md) | Versioned public weaver op API declarations, `guild.describe` introspection, and loud structured deprecation for local peer coordination. |

## Approved local-root examples

These live beside this index in the repo-root [`spools/`](./) directory, **off**
the shipped classpath, and load only through the approved-local-root flow
(`spools.edn` → `sync!` → `use!`). The placement rule: pure graph vocabulary
that other code builds on ships on the classpath above; a spool that
**escalates capability** (the shuttle spawns harness processes with the
user's authority) or exists to exercise the userland distribution path sits
here, behind the workspace's explicit `spools.edn` consent. They also serve
as the worked example of authoring your own spool
([docs/skein.md](../docs/skein.md#authoring-your-own-spool-code)).

| Spool | Contract doc | Purpose |
|---|---|---|
| `skein.spools.shuttle` | [shuttle/README.md](./shuttle/README.md) | Agent shuttle: readiness-driven headless coding-agent runs, harness aliases, crash reconciliation, append-only run memory, and `strand op agent`. |
| `skein.spools.treadle` | [shuttle/treadle.md](./shuttle/treadle.md) | Workflow gate bridge: fulfills ready `:subagent` gates by spawning shuttle runs and delivering successful results through `workflow/complete!`. |

## Reference examples

- Each contract doc ends with worked examples (`workflow.md` §8,
  `devflow.md` §4).
- The test suites drive every documented behavior against a real weaver
  runtime and double as executable examples:
  [`test/skein/spools/workflow_test.clj`](../test/skein/spools/workflow_test.clj),
  [`test/skein/spools/devflow_test.clj`](../test/skein/spools/devflow_test.clj),
  and the ephemeral cases in
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
