# Skein Batteries Spool

`skein.spools.batteries` registers the everyday `strand` command surface: add/update/show/supersede/burn/list/ready/subgraph, plus the `weave`, `query`, `pattern`, `vocab`, and `spool` ops.

## Loading

Batteries is the one classpath spool. It does not load through an approved `spools.edn` coordinate: its `src` root ships on the repository `deps.edn` `:paths`, and `init.clj` requires and declares it directly so a fresh `{:spools {}}` world gets the command surface at zero config. The rationale is in [the spool index](../README.md#classpath-exception-batteries) (SPEC-004.C50a). Every other spool loads through the approved `spools.edn` → `:spools`-guarded module path.

The `deps.edn` here declares the spool's own `src` root for tools and consumers that address the spool directory directly; the weaver classpath still comes from the repository `deps.edn`.

## Docs

Per-command behavior contract: [batteries.md](../batteries.md) · [API](../batteries.api.md) · [cookbook](../batteries.cookbook.md).
