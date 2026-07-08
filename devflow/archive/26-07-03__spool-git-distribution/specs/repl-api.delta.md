# REPL API delta — spool git distribution

**Document ID:** `SGD-SPEC-RA-001` **Root spec:** [repl-api.md](../../../specs/repl-api.md) (SPEC-003) **Status:** Merged **Last Updated:** 2026-07-03

Changes relative to SPEC-003 on ship. Decision history: [RFC-017](../../../rfcs/2026-07-03-spool-git-distribution.md).

## SGD-SPEC-RA-001.D1 Approved-spool config grammar (amends the `spools.edn` MVP grammar)

The per-coordinate entry map accepts exactly one coordinate kind:

- **Local kind** (unchanged): exactly `{:local/root <non-blank string>}`.
- **Git kind** (new): required `:git/url` (non-blank string, passed to git verbatim — user chooses ssh/https/file transport), required `:git/sha` (exactly 40 lowercase hex characters), optional `:git/tag` (non-blank string; human-readable label verified against the sha at fetch time), optional `:deps/root` (non-blank relative path, no leading `/`, no `~`, no `..` segments; selects a subdirectory of the checkout as the spool root).

Mixing kind keys, unknown keys, or malformed values remain loud structural config errors. `spools.local.edn` overlay semantics are unchanged and apply to both kinds.

## SGD-SPEC-RA-001.D2 Spool manifest contract (new)

A spool root may contain an optional `spool.edn` manifest: a map with only these optional keys —

- `:coordinate` symbol; when present must equal the approving `spools.edn` coordinate.
- `:provides` set or vector of namespace symbols the spool exposes.
- `:needs` map of coordinate symbol → `nil` or `{:suggest {:git/url <non-blank string>}}`; a machine-readable fulfillment hint, never resolution.
- `:docs` string, or map of namespace symbol → string.

Unknown keys or malformed shapes fail that spool's sync loudly (`:manifest-invalid`); the root is not loaded. There is no version field: the pinned sha is the content/behavior contract, and compatibility claims are limited to mechanically verifiable namespace presence (RFC-017.REC1).

## SGD-SPEC-RA-001.D3 Sync and activation semantics (amends runtime spool workspace helpers)

- `sync!` materializes git coordinates (see the daemon-runtime delta for cache/fetch contracts) and then treats their effective roots identically to local roots.
- Successful sync results for a spool with a manifest include the normalized manifest under `:manifest`, and — after all entries sync — `:unmet-needs [{:lib <coord> :reason :not-approved|:sync-failed :suggest <map-or-nil>}]` when declared needs are not satisfied by approved, successfully synced coordinates. Unmet needs never trigger fetching.
- `use!` gains skip reasons: `:unmet-needs` (any listed spool's latest sync carries unmet needs) and `:provides-unloadable` (a declared provided namespace fails to require in the spool classloader). With `:required? true` these fail activation loudly, matching existing required-module semantics.
