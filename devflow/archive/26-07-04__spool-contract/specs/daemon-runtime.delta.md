# Weaver Runtime spool-contract delta

**Document ID:** `DELTA-daemon-runtime-spool-contract-001` **Status:** Merged **Target root spec:** [Weaver Runtime](../../../specs/daemon-runtime.md) (`SPEC-004`) **Feature:** spool-contract

## DELTA-daemon-runtime-spool-contract-001.P1 Purpose

Stage the SPEC-004 runtime changes from `SC-PROP-001`: remove manifest-shaped sync/use behavior and replace the current `deps.edn :deps` consent asymmetry with one Maven-only dependency rule for every approved spool root.

This delta intentionally preserves SPEC-004's existing duplicate clause numbering around the storage C91-C94 block and runtime spool C91-C94 block. It names only the affected runtime spool clauses and does not renumber unrelated clauses.

## DELTA-daemon-runtime-spool-contract-001.P2 Changed clauses

- **SPEC-004.C44** changes only in the shape of sync outcomes: `sync!` still materializes approved git coordinates and adds each effective root to the weaver runtime, but outcome maps no longer include manifest-derived `:manifest`, `:unmet-needs`, `:manifest-invalid`, or `:coordinate-mismatch` data. Per-spool failures still report fetch, root, and runtime-add failures loudly in the sync result.
- **SPEC-004.C48@2** keeps the source acquisition rule and removes only the manifest-needs reference. Runtime source acquisition still happens only at `sync!` for explicitly approved sha-pinned git coordinates; Skein must not silently clone repositories, add Git submodules, fetch source as part of module activation, or fetch anything a coordinate's own approval did not pin. No declared prerequisite ever triggers acquisition.
- **SPEC-004.C49@2** is narrowed: approved local roots and approved sha-pinned git coordinates remain the only source kinds, and package registries, version ranges, dependency solving, lockfiles beyond approved git SHA pins, fetch-time code execution, and CLI package commands remain outside the contract. Maven artifact downloads for approved spool-root `deps.edn :deps` entries are now allowed only under the C94a replacement rule below.
- **SPEC-004.C93 (runtime spool block)** is removed/replaced. `sync!` no longer parses `spool.edn`, validates manifest shape, compares manifest `:coordinate`, reports `:manifest-invalid` / `:coordinate-mismatch`, or computes `:unmet-needs`. Spool prerequisites are documentation and consumer-owned approval/activation choices, not sync-time manifest data.
- **SPEC-004.C94a** is replaced by the Maven-only `:deps` rule in P3.
- **SPEC-004.C94 (runtime spool block)** is replaced for manifest behavior. `use!` no longer refuses activation because of manifest-declared needs or manifest-declared provides, and no longer has manifest skip reasons `:unmet-needs` or `:provides-unloadable`. The existing `use!` gates for `:after` ordering, target load, and `:call` remain otherwise unchanged. The `:spools` approval/sync-state gate is strengthened: under `:required? true`, skip reasons `:not-approved`, `:not-synced`, and `:sync-failed` must throw as required skipped activation. `:spools` approval gating plus this expanded `:required?` strictness are now the blessed early prerequisite check.

## DELTA-daemon-runtime-spool-contract-001.P3 Replacement C94a: approved spool Maven dependencies

- **SPEC-004.C94a@spool-contract:** A spool root's `deps.edn` top-level `:deps` is allowed for every approved spool kind (`:git` and `:local`) and for entries sourced from either `spools.edn` or `spools.local.edn`.
- **SPEC-004.C94a.1@spool-contract:** Every `:deps` entry must be a Maven coordinate map containing `:mvn/version`. Source-bearing tools.deps coordinates and paths, including `:git/url`, `:git/sha`, and `:local/root`, are not allowed inside a spool root's `deps.edn :deps`. Standard Maven coordinate refinement keys `:exclusions`, `:classifier`, and `:extension` are allowed alongside `:mvn/version`.
- **SPEC-004.C94a.2@spool-contract:** `sync!` validates this policy before calling `add-libs`. Any non-Maven `:deps` entry fails that spool's sync loudly as a per-spool runtime-add/dependency-policy failure before dependency resolution or classpath mutation for that entry. Mutable Maven versions are also forbidden: `:mvn/version` must not end in `-SNAPSHOT` and must not be the `RELEASE` or `LATEST` meta-version; violations use the same loud per-spool dependency-policy sync failure. A spool root `deps.edn` must not contain top-level `:mvn/repos` or `:mvn/local-repo`; repo redirection is not honored and fails that spool's sync loudly. Resolution uses only the weaver process's ambient Maven repository configuration.
- **SPEC-004.C94a.3@spool-contract:** Allowed Maven dependencies are resolved into the live weaver runtime during `sync!` through the same `clojure.repl.deps/add-libs` runtime dependency path used for spool roots. Transitive dependency semantics are plain tools.deps/add-libs Maven expansion of the declared coordinates. A spool root `deps.edn`'s `:aliases` and any other top-level key outside `:paths` and `:deps` — except the keys C94a.2 rejects loudly (`:mvn/repos`, `:mvn/local-repo`) — are ignored by `sync!`; no alias activation participates in the contract. Runtime dependency loading remains weaver-wide: there is no per-spool version isolation or unloading guarantee.

## DELTA-daemon-runtime-spool-contract-001.P4 Deliberately unchanged

- Git spool SHA pinning and exact-content consent remain unchanged.
- Per-coordinate approval in `spools.edn` / `spools.local.edn` remains unchanged.
- Optional `:git/tag` verification, optional git-only `:deps/root`, and local overlay precedence remain unchanged. The local overlay `:deps` consent path is deliberately tightened by C94a: `spools.local.edn` local roots no longer retain a special ability to declare arbitrary tools.deps `:deps`, including `:local/root` or `:git/url`.
- Skein still performs no transitive spool fetching: one approved spool's metadata, docs, or dependencies cannot approve or fetch another spool source.
