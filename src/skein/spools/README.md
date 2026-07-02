# Shipped reference spools

Spools are trusted, authorable Clojure loaded into the weaver. The
`skein.spools.*` namespace family is reserved for exactly this kind of code
(see the [REPL API spec](../../../devflow/specs/repl-api.md)); the spools in
this directory ship with Skein as working references — use them directly,
copy them as starting points, or study them to author your own.

Because they ship on the weaver classpath, no `spools.edn` approval is
needed — `require` them from `init.clj`, an activated spool, or a live
`strand weaver repl`:

```clojure
(require '[skein.spools.workflow :as workflow])
```

## Index

| Spool | Contract doc | Purpose |
|---|---|---|
| `skein.spools.workflow` | [workflow.md](./workflow.md) | Workflow engine: plain-data definitions compiled to strand batches, with loops, gates, checkpoints, routing, and rebindable tool bindings. |
| `skein.spools.devflow` | [devflow.md](./devflow.md) | Reference devflow lifecycle built on the workflow engine: intake → proposal → spec/plan → tasks/implementation stages with HITL checkpoints. |
| `skein.spools.ephemeral` | [ephemeral.md](./ephemeral.md) | Small helper for temporary, parent-owned strands marked and burned via a userland attribute. |

## Reference examples

- Each contract doc ends with worked examples (`workflow.md` §8,
  `devflow.md` §4).
- The test suites drive every documented behavior against a real weaver
  runtime and double as executable examples:
  [`test/skein/spools/workflow_test.clj`](../../../test/skein/spools/workflow_test.clj),
  [`test/skein/spools/devflow_test.clj`](../../../test/skein/spools/devflow_test.clj),
  and the ephemeral cases in
  [`test/skein/spools_test.clj`](../../../test/skein/spools_test.clj).

## Using and extending

- Strand **attributes are the extension surface**: `workflow.md` §7's
  attribute table is the workflow engine's extension API, and `devflow.md`
  §6 documents devflow's conventions on top of it. Build your own
  conventions the same way instead of waiting for engine fields.
- Workflow definitions accept pure-data **tool bindings** (`workflow.md`
  §3), so a consumer rebinds steps to their own tooling from trusted config
  without touching these namespaces.
- Every spool exposes `install!` metadata (fns as symbol maps) for trusted
  registration by name.
- To author and load your own spool from a workspace-local root, follow
  [Authoring your own spool code](../../../docs/skein.md#authoring-your-own-spool-code).
