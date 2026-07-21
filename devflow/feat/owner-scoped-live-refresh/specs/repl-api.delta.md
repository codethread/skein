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
- **DELTA-OlrRepl-001.CC3:** `(runtime/module! runtime key opts)` declares one module under stable keyword `key`. `opts` is closed to a source target (`:ns` or workspace-relative `:file`), optional approved `:spools`, optional module-key `:after`, optional fully qualified `:contribute` symbol, optional fully qualified `:reconcile` symbol, and optional `:required?`. Exactly one source target is required. When `:contribute` is omitted, the module's contribution is the declaration data collected from the authoring forms (`defop`, `defquery`, `defpattern`, `defrule`, and registered domain-kind forms) evaluated in the module source — a plain file of authoring forms is a complete module with no explicit contribution function. The declaration is data and is introspectable without executing the module.
- **DELTA-OlrRepl-001.CC4:** During startup-file collection, `module!` stages declarations and performs no source load, registry publication, or reconcile effect. Outside collection, `module!` replaces the desired declaration for that key and refreshes that module plus affected dependents. Whole-module removal is expressed only by omitting it from a successfully collected full graph; targeted `refresh! {:only ...}` operates on known active declarations and is not a deletion API. Full-graph removal deletes logical owner contributions and records any retained resource or loaded-code residual.
- **DELTA-OlrRepl-001.CC5:** A contribution function (or the default collector) receives one context map containing at least `:runtime`, `:module/key`, and the validated module declaration. It returns a map of kind id to entry collections, open over every registered kind — the shipped core kinds `:ops`, `:queries`, `:patterns`, `:hooks`, `:events`, and `:vocab`, plus any domain kind declared through `skein.api.registry.alpha` — and closed against unregistered kinds, which fail loudly before publication. Entry shapes are the owning kind's declared spec plus optional explicit override intent. Contribution functions do not register entries themselves; the kernel validates and publishes every declarative partition, core and domain alike, inside the refresh publication guarantee.
- **DELTA-OlrRepl-001.CC6:** A reconcile function receives one context map containing at least `:runtime`, `:module/key`, `:module/declaration`, `:module/previous`, `:module/contribution`, and `:refresh/result`. Its return is JSON-compatible module status data. It owns resources and registration effects only — declarative entries, including domain-kind entries, publish through the contribution path, never through reconcile. Its arbitrary effects are outside the contribution publication guarantee.
- **DELTA-OlrRepl-001.CC7:** `(runtime/refresh! runtime)` re-reads selected startup files, collects the complete layered module graph, and applies the Weaver Runtime refresh contract. `(runtime/refresh! runtime {:only keys})` refreshes a non-empty set of known module keys and affected dependents against the active declaration graph without re-reading startup files. Unknown keys, malformed opts, and an empty `:only` fail loudly. Both return the joined refresh result.
- **DELTA-OlrRepl-001.CC8:** `(runtime/status runtime)` returns the offline, read-only joined runtime status defined by DELTA-OlrDrt-001.CC15. It replaces separate sync/use reads. It does not fetch, resolve Maven, fingerprint files, load source, execute contributions, or reconcile resources.
- **DELTA-OlrRepl-001.CC9:** `(runtime/reload-code! runtime root-lib)` is the advanced code-only seam. It makes the selected synced root's current source live in dependency order and records exact load-ledger entries, but performs no module contribution or resource reconciliation. It reports partial loads and residuals and is documented as a sharp REPL/development operation rather than the normal refresh path.
- **DELTA-OlrRepl-001.CC10:** Direct per-entry registration functions in the graph, patterns, events, hooks, and weaver APIs remain trusted sharp tools, but every write carries an explicit owner. A direct owner may replace its own keys; cross-owner replacement requires explicit authorization. Module contribution publication uses the same validators and effective registry shapes without calling the direct mutation functions entry by entry.
- **DELTA-OlrRepl-001.CC11:** Spool domains with their own declarative registries expose complete-owner replacement rather than requiring remember/forget/install shadow registries. The workspace `defop`, `defquery`, `defpattern`, and `defrule` forms emit declaration data into the current module contribution; their `remember-*`, `forget-*`, and `install-*` lifecycle functions are removed.
- **DELTA-OlrRepl-001.CC12:** Runtime status and refresh values are Clojure data-first maps with named specs in `skein.api.runtime.alpha`. Public bodies retain the SPEC-003.C19a story shape: `refresh!` visibly composes collection, classification, source loading, contribution publication, and resource reconciliation rather than delegating as a husk.
- **DELTA-OlrRepl-001.CC13:** `skein.api.registry.alpha` is the blessed kind-declaration and owner-partition primitive for spool domains. It declares kinds (id, entry spec, binding-moment datum, layer policy) — a declared kind becomes a valid contribution-map key published by the refresh kernel — and provides direct owner-partition operations: replace or remove one complete owner partition, immutable effective snapshots, and explanation of active, shadowed, and override entries. It has no generic resource/effect callbacks; domains perform baseline, durable-write, and lifecycle work through their own APIs around publication.
- **DELTA-OlrRepl-001.CC14:** `(runtime/plan runtime)` and `(runtime/plan runtime {:only keys})` are the dry-run counterparts of `refresh!`: they collect and diff without publication or reconcile effects and return a refresh-result-shaped map of intentions. The one honest caveat is stated in the result and the docstring: collection may load source code, and any such load is recorded in the ledger.

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

## DELTA-OlrRepl-001.P5 Amendment record

2026-07-21, pre-queue-release amendment round (user-approved at checkpoint review): CC3 made `:contribute` optional with a default authoring-form collector; CC5 opened the contribution map to registered kinds; CC6 narrowed reconcile to resources/effects; CC13 recast registry.alpha as the kind-declaration primitive; CC14 (`plan` dry-run) added. Rationale: DELTA-OlrDrt-001.D5 and the blind probe (run `hhqgk`, task `np7h2`).
