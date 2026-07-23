# Skein Batteries spool

`skein.spools.batteries` registers the everyday `strand` command surface: add/update/show/supersede/burn/list/ready/subgraph, plus the `weave`, `query`, `pattern`, `vocab`, and `spool` ops.

## Loading

Batteries is an ordinary approved spool. `mill init` seeds `skein.spools/batteries {:skein/source-root "spools/batteries"}` in `spools.edn` and a `:spools ['skein.spools/batteries]` guarded module in `init.clj`. The relative coordinate resolves against the mill-selected Skein checkout without persisting an absolute path. Delete the seeded entry to opt out; a workspace without it has no batteries ops. See [the spool index](../README.md#shipped-source-root-batteries) (SPEC-004.C50a/C50b).

The `deps.edn` here declares the spool's own `src` root for tools and consumers that address the spool directory directly. Production weaver loading goes through the approved source-root coordinate.

## Docs

Per-command behavior contract: [batteries.md](../batteries.md) · [API](../batteries.api.md) · [cookbook](../batteries.cookbook.md).
