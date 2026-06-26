# Daemon Runtime delta for skein-rename

**Document ID:** `SR-DELTA-004`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-06-26

## SR-DELTA-004.P1 Summary

The daemon runtime becomes the weaver runtime. Runtime namespace, metadata, default world paths, socket artifacts, API boundary, and startup examples move from `todo`/`atom` vocabulary to `skein`/`weaver` vocabulary while preserving the daemon-core-first architecture.

## SR-DELTA-004.P2 Contract changes

- **SR-DELTA-004.CC1:** The long-lived runtime is called the weaver in public contracts. Root spec text should be promoted from Daemon Runtime to Weaver Runtime when the feature ships.
- **SR-DELTA-004.CC2:** The daemon API boundary namespace is renamed from `todo.daemon.api` to `skein.weaver.api`. Internal daemon namespaces move from `todo.daemon.*` to `skein.weaver.*`.
- **SR-DELTA-004.CC3:** The selected default world paths are `$XDG_CONFIG_HOME/skein`, `$XDG_STATE_HOME/skein`, and `$XDG_DATA_HOME/skein`.
- **SR-DELTA-004.CC4:** Runtime metadata and socket files are named `weaver.json`, `weaver.edn`, and `weaver.sock` in the selected state world.
- **SR-DELTA-004.CC5:** The default weaver-owned database is `skein.sqlite` under the selected data world.
- **SR-DELTA-004.CC6:** Runtime metadata/status reports strand storage identity and weaver identity using the renamed paths and filenames. Clients fail loudly on stale or malformed old `daemon.*` artifacts rather than silently falling back.
- **SR-DELTA-004.CC7:** Weaver API operations use strand row shapes with `active`, `ephemeral`, and `inactive_at`. Core operation names may remain generic (`add`, `update`, `show`, `list`, `ready`) where transport operation vocabulary is internal to trusted clients.
- **SR-DELTA-004.CC8:** The JSON socket allowlist remains limited to public CLI behavior under renamed row shapes. Query registry mutation/listing and view operations remain outside the JSON socket allowlist.
- **SR-DELTA-004.CC9:** Trusted startup config loads `init.clj` from the selected config-dir and may call `skein.weaver.api` and `skein.*.alpha` namespaces.
- **SR-DELTA-004.CC10:** Blessed built-in runtime namespaces are `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`; these are source-visible namespaces on the configured Skein checkout classpath.
- **SR-DELTA-004.CC11:** Weaver runtime and clients do not support compatibility lookup of old default `atom` worlds, `tasks.sqlite`, `daemon.*` metadata, or `todo.*`/`atom.*` namespaces.

## SR-DELTA-004.P3 Design decisions

### SR-DELTA-004.D1 Rename the runtime noun without changing the architecture

- **Decision:** The process remains daemon-core-first, but user-facing contracts call it the weaver.
- **Rationale:** Weaver matches the accepted Skein vocabulary while preserving the existing local runtime model: one selected world, one long-lived process, local transports, trusted startup code, and daemon-memory registries.
- **Rejected:** Turning the weaver into a stateless CLI helper or remote service as part of this rename.

### SR-DELTA-004.D2 Fail on old runtime artifacts

- **Decision:** Clients discover only `weaver.*` artifacts for selected Skein worlds.
- **Rationale:** Alpha rules allow dropping old names. Silent fallback to `daemon.*` or `atom` worlds would make failures confusing and risk mutating the wrong world.
- **Rejected:** Dual discovery during transition.

## SR-DELTA-004.P4 Open questions

- **SR-DELTA-004.Q1:** None for MVP.
