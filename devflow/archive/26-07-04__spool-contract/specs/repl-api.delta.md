# REPL API spool-contract delta

**Document ID:** `DELTA-repl-api-spool-contract-001` **Status:** Merged **Target root spec:** [REPL API](../../../specs/repl-api.md) (`SPEC-003`) **Feature:** spool-contract

## DELTA-repl-api-spool-contract-001.P1 Purpose

Stage the SPEC-003 REPL/runtime-spool helper contract changes from `SC-PROP-001`: remove `spool.edn` manifests from the durable contract and keep the public REPL-facing spool workflow centered on approved `spools.edn` / `spools.local.edn`, `sync!`, and explicit `use!` activation.

## DELTA-repl-api-spool-contract-001.P2 Changed clauses

- **SPEC-003.P5, paragraph beginning “A spool's effective root may carry an optional `spool.edn` manifest…”** is removed in full. This removes the manifest grammar and every REPL API contract for `:coordinate`, `:provides`, `:docs`, and `:needs`, including manifest shape validation, coordinate matching, needs reporting, and `use!` skip reasons derived from manifests.
- **SPEC-003.P5, helper bullet for `(runtime-alpha/sync! runtime)`** changes from returning “fetch, manifest, and unmet-needs data” to returning structured results for loaded, already-available, and failed approved spools, including fetch and runtime-add/dependency-policy failure data where applicable. Sync results are no longer shaped by `spool.edn`.
- **SPEC-003.P5, `use!` option paragraphs** keep the `:spools`, `:after`, `:call`, and `:required?` option grammar, but remove manifest-based activation gates. `use!` no longer checks manifest-declared needs or manifest-declared provided namespaces, and no longer records/throws `:unmet-needs` or `:provides-unloadable` for manifest state. The blessed early prerequisite check is the consumer-owned `:spools` gate strengthened by this feature: `:not-approved`, `:not-synced`, and `:sync-failed` remain the skip surface, and under `:required? true` each of those three skip reasons must throw as required skipped activation according to the runtime contract.
- **SPEC-003.P5, final non-goals sentence beginning “Maven/remote dependency coordinates…”** changes only for approved spool roots: Maven dependencies declared in an approved spool root's top-level `deps.edn :deps` are now part of the spool sync contract described by SPEC-004. Version ranges, alternate approved-spool config files, source fetching beyond approved spool coordinates, and direct explicit-client `require` of newly synced weaver spools remain outside the REPL API contract.

## DELTA-repl-api-spool-contract-001.P3 New documentation-only metadata clause

Spool metadata and spool prerequisites are documentation, not REPL API grammar. Shared spool authors document prerequisites, suggested pins, and activation order in their README using the convention described by `docs/writing-shared-spools.md`. A `spool.edn` file, if present in a source tree for historical or local reasons, is ignored by the Skein spool contract and is not an authoritative manifest. This is a deliberate TEN-004-over-TEN-003 tradeoff scoped to manifest retirement: Skein does not read the file at all, so it cannot warn about it. The migration for authors is deleting the file.

## DELTA-repl-api-spool-contract-001.P4 Deliberately unchanged

- The `spools.edn` / `spools.local.edn` coordinate grammar stays unchanged: local roots, sha-pinned git coordinates, optional verified `:git/tag`, optional git-only `:deps/root`, and local overlay semantics remain as specified in SPEC-003.P5.
- Git spool SHA pinning and exact-content consent remain unchanged.
- Every spool source coordinate still requires its own explicit approval; no spool can approve another spool by declaring it.
- Skein still performs no transitive spool fetching, registry lookup, package install, version solving, or source acquisition during activation.
