# REPL API delta for owner-scoped live refresh

**Document ID:** `DELTA-OlrRepl-001`
**Root spec:** [repl-api.md](../../../specs/repl-api.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Reviewed
**Last Updated:** 2026-07-20

## DELTA-OlrRepl-001.P1 Summary

The blessed runtime API changes from exposing separate synchronization, activation, source-reload, and global-replay phases to declaring stable modules and refreshing the live image through one deep boundary. Approval editing, runtime services, and an advanced code-only reload seam remain available.

## DELTA-OlrRepl-001.P2 Contract changes

- **DELTA-OlrRepl-001.CC1:** `skein.api.runtime.alpha` retains `approved`, `declared`, `release-marker`, `upsert-spool-entry!`, `remove-spool-entry!`, `spool-state`, and `now` with their existing purposes.
- **DELTA-OlrRepl-001.CC2:** The normal lifecycle removes `sync!`, `syncs`, `reload!`, `reload-spool!`, `use!`, `uses`, and `use-entry`. Their acquisition, classification, module-ordering, activation, and introspection behavior is owned by `module!`, `refresh!`, `status`, and the renamed advanced `reload-code!` seam; no compatibility aliases or deprecated forwarding vars remain.
- **DELTA-OlrRepl-001.CC3:** `(runtime/module! runtime key opts)` declares one module under stable keyword `key`. `opts` is closed to a source target (`:ns` or workspace-relative `:file`), optional approved `:spools`, optional module-key `:after`, required fully qualified `:contribute` symbol, optional fully qualified `:reconcile` symbol, and optional `:required?`. Exactly one source target is required. The declaration is data and is introspectable without executing the module.
- **DELTA-OlrRepl-001.CC4:** During startup-file collection, `module!` stages declarations and performs no source load, registry publication, or reconcile effect. Outside collection, `module!` replaces the desired declaration for that key and refreshes that module plus affected dependents. Whole-module removal is expressed only by omitting it from a successfully collected full graph; targeted `refresh! {:only ...}` operates on known active declarations and is not a deletion API. Full-graph removal deletes logical owner contributions and records any retained resource or loaded-code residual.
- **DELTA-OlrRepl-001.CC5:** A contribution function receives one context map containing at least `:runtime`, `:module/key`, and the validated module declaration. It returns a closed map of declarative core entries: optional `:ops`, `:queries`, `:patterns`, `:hooks`, and `:events` collections. Entry shapes are the corresponding registry API contracts plus optional explicit cross-owner replacement authorization. Contribution functions do not register entries themselves.
- **DELTA-OlrRepl-001.CC6:** A reconcile function receives one context map containing at least `:runtime`, `:module/key`, `:module/declaration`, `:module/previous`, `:module/contribution`, and `:refresh/result`. Its return is JSON-compatible module status data. It may call domain APIs for owner-complete replacement and resource reconciliation; its arbitrary effects are outside the contribution publication guarantee.
- **DELTA-OlrRepl-001.CC7:** `(runtime/refresh! runtime)` re-reads selected startup files, collects the complete layered module graph, and applies the Weaver Runtime refresh contract. `(runtime/refresh! runtime {:only keys})` refreshes a non-empty set of known module keys and affected dependents against the active declaration graph without re-reading startup files. Unknown keys, malformed opts, and an empty `:only` fail loudly. Both return the joined refresh result.
- **DELTA-OlrRepl-001.CC8:** `(runtime/status runtime)` returns the offline, read-only joined runtime status defined by DELTA-OlrDrt-001.CC15. It replaces separate sync/use reads. It does not fetch, resolve Maven, fingerprint files, load source, execute contributions, or reconcile resources.
- **DELTA-OlrRepl-001.CC9:** `(runtime/reload-code! runtime root-lib)` is the advanced code-only seam. It makes the selected synced root's current source live in dependency order and records exact load-ledger entries, but performs no module contribution or resource reconciliation. It reports partial loads and residuals and is documented as a sharp REPL/development operation rather than the normal refresh path.
- **DELTA-OlrRepl-001.CC10:** Direct per-entry registration functions in the graph, patterns, events, hooks, and weaver APIs remain trusted sharp tools, but every write carries an explicit owner. A direct owner may replace its own keys; cross-owner replacement requires explicit authorization. Module contribution publication uses the same validators and effective registry shapes without calling the direct mutation functions entry by entry.
- **DELTA-OlrRepl-001.CC11:** Spool domains with their own declarative registries expose complete-owner replacement rather than requiring remember/forget/install shadow registries. The workspace `defop`, `defquery`, `defpattern`, and `defrule` forms emit declaration data into the current module contribution; their `remember-*`, `forget-*`, and `install-*` lifecycle functions are removed.
- **DELTA-OlrRepl-001.CC12:** Runtime status and refresh values are Clojure data-first maps with named specs in `skein.api.runtime.alpha`. Public bodies retain the SPEC-003.C19a story shape: `refresh!` visibly composes collection, classification, source loading, contribution publication, and resource reconciliation rather than delegating as a husk.
- **DELTA-OlrRepl-001.CC13:** `skein.api.registry.alpha` is the blessed reusable owner-partition primitive for spool domains. It creates runtime-owned registries with domain validators, replaces or removes complete owner partitions, returns immutable effective snapshots, and explains active, shadowed, and override entries. It has no generic resource/effect callbacks; domains perform baseline, durable-write, and lifecycle work through their own APIs around publication.

## DELTA-OlrRepl-001.P3 Design decisions

### DELTA-OlrRepl-001.D1 One normal refresh, one advanced code seam

- **Decision:** Callers normally use `refresh!`; `reload-code!` remains separately available for code-only experimentation and diagnosis.
- **Rationale:** A deep normal interface removes manual choreography, while the live-image workflow still needs to redefine code without repeating non-idempotent activation effects.
- **Rejected:** Making `refresh!` the only possible source reload operation or retaining every current phase as a peer blessed workflow.

### DELTA-OlrRepl-001.D2 Stable module key owns declarations

- **Decision:** The module keyword, not a namespace, source file, or root lib, owns contributed declarations.
- **Rationale:** Source may move or rename while its logical workspace role remains; ownership must survive exactly the changes omission accounting is meant to handle.
- **Rejected:** Deriving ownership from the handler symbol namespace or current file path.

## DELTA-OlrRepl-001.P4 Open questions

None.
