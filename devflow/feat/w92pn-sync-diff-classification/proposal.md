# w92pn sync diff classification proposal

## Summary

`sync!` should treat the current weaver process as the generation boundary. New approved roots are additive and can be added to the live spool classloader. Non-additive changes are recorded as a pending generation and refused loudly so operators can restart with user sign-off instead of believing code was unloaded in-JVM.

## Behavior

- Additive: a coordinate that has not successfully synced in this generation continues through the existing stateless resolver and classloader-add path.
- Non-additive: removed roots, changed source roots for already-synced coordinates, and changed source for roots with already-loaded namespaces throw `:non-additive-sync-diff`.
- Pending generation: the runtime records an in-memory pending-generation map on the runtime, visible through `syncs` and later `sync!` returns.
- Retained instances: sync reports spool-state entries tagged with an older runtime generation as `:retained-spool-state` but does not refuse them.

## Deferred

Already-loaded Maven coordinate version changes are left for ypy3h. The classifier has a seam where that check can be added without changing the pending-generation record shape.
