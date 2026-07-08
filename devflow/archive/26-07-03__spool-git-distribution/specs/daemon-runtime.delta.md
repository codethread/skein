# Weaver Runtime delta — spool git distribution

**Document ID:** `SGD-SPEC-DR-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (SPEC-004, amends C42/C43 area) **Status:** Merged **Last Updated:** 2026-07-03

Changes relative to SPEC-004 on ship. Decision history: [RFC-017](../../../rfcs/2026-07-03-spool-git-distribution.md).

## SGD-SPEC-DR-001.D1 Git coordinate materialization (new contract)

- Approved git coordinates materialize into a content-addressed cache at `<cache-base>/skein/spools/<sha>`, where `<cache-base>` is `XDG_CACHE_HOME` when set and non-blank, else `~/.cache`. The effective spool root is that path plus `:deps/root` when present.
- Cache hit (path exists, non-empty): no git invocation, no network, no tag verification — the sha is the content contract. Sync result carries `:fetch :cached`.
- Cache miss: the weaver shells out to system `git` (PATH-resolved), fetching the pinned sha into a temporary directory and atomically renaming into the cache path; a partially fetched tree is never observable at the final path. Materialized trees contain content only (no `.git`). Sync result carries `:fetch :fetched`.
- Any git failure — including a missing git binary — is a per-spool sync outcome (`:failed` / `:fetch-failed` with exit code and trimmed stderr), never a structural error and never an exception out of `sync!`.
- Sync outcome maps are kind-shaped: every result carries `:kind`, with `:local/root` only on local-kind results and `:git/url`/`:git/sha` (plus `:git/tag`/`:deps/root` when configured) only on git-kind results; no nil-stuffed cross-kind keys.

## SGD-SPEC-DR-001.D2 Tag verification (new contract)

When `:git/tag` is present and a fetch occurs, the tag (including the peeled `^{}` form for annotated tags) must resolve via `git ls-remote` to exactly the pinned sha before the tree is installed. Mismatch or absence → `:failed` / `:tag-mismatch` outcome with expected/actual data; nothing is cached. Cache hits skip verification by design.

## SGD-SPEC-DR-001.D3 Manifest processing and needs computation (new contract)

- `sync!` parses an optional `spool.edn` at each effective root (grammar in the repl-api delta). Malformed manifests fail that spool's sync (`:manifest-invalid`); `:coordinate` mismatch fails it (`:coordinate-mismatch`).
- After all entries sync, each loaded spool's declared `:needs` are checked against the approved set and sync results; violations are recorded as `:unmet-needs` data on that spool's result. The spool's own status remains `:loaded` — the gate is `use!` activation, and no fetch is ever triggered by a need (consent stays per-coordinate, RFC-017.NG2).

## SGD-SPEC-DR-001.D4 Activation gating (amends module-use contract)

`use!` skips (or fails, when `:required? true`) modules whose listed spools carry unmet needs or whose manifest-declared provided namespaces do not require cleanly in the spool classloader, with skip reasons `:unmet-needs` / `:provides-unloadable` and full ex-info data. Check order: approval/sync (existing) → unmet needs → provides → `:after` (existing).
