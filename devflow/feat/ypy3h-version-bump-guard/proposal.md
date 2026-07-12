# ypy3h proposal: Maven version-bump guard

## Summary

Add Maven version-bump detection to the existing `sync!` non-additive diff classifier. After materialization and before classloader mutation, Skein keeps using the w92pn pending-generation path for any resolved Maven coordinate version that changed after its jar URLs were added to the current weaver generation's spool classloader.

## Bookkeeping

Use runtime-owned in-memory state beside the existing generation sync state:

- `:approved-spool-generation-state` remains the previous successful root state.
- `:approved-spool-generation-fingerprints` remains the source redefinition baseline.
- `:approved-spool-generation-maven` records `{coordinate resolved-version}` for coordinates returned by the resolver as added jars in a successful sync.

This defines "already loaded" as a coordinate whose resolved jar URLs were added by a previous successful sync in this weaver generation. Coordinates newly appearing in the current resolution have no previous entry and remain additive-safe.

## Behavior

`sync!` preserves the existing structural non-additive check before Maven resolution. If the structural diff is additive-safe, it resolves the Maven universe, compares resolved versions against `:approved-spool-generation-maven`, and refuses any changed version before adding jars or roots. The exception uses `:reason :non-additive-sync-diff`, records `:diff {:maven-version-bumps [...]}`, and includes the existing pending-generation remedy.
