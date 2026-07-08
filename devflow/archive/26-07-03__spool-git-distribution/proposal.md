# Spool git distribution Proposal

**Document ID:** `SGD-PROP-001` **Last Updated:** 2026-07-03 **Related RFCs:** [Registry-free git distribution for spools](../../rfcs/2026-07-03-spool-git-distribution.md) (RFC-017) **Related root specs:** [REPL API](../../specs/repl-api.md) (SPEC-003), [Weaver Runtime](../../specs/daemon-runtime.md) (SPEC-004)

## SGD-PROP-001.P1 Problem

Spools load only from locally present roots. Consuming a spool someone else publishes means hand-cloning and pointing `:local/root` at the checkout, and a published spool has no way to state which spools it builds on. Distribution and inter-spool requirements need first-class support without weakening the `spools.edn` consent model or adding infrastructure.

## SGD-PROP-001.P2 Goals

- **SGD-PROP-001.G1:** Approve a spool by git URL + pinned commit SHA in `spools.edn`; `sync!` materializes it and loads it like a local root.
- **SGD-PROP-001.G2:** Published spools can declare provided namespaces and needed coordinates in a `spool.edn` manifest; violations surface loudly at `sync!`/`use!`.
- **SGD-PROP-001.G3:** Everything stays declarative EDN + trusted config/REPL; zero new CLI verbs.

## SGD-PROP-001.P3 Non-goals

- **SGD-PROP-001.NG1:** No registry/index/discovery service (RFC-017.NG1).
- **SGD-PROP-001.NG2:** No transitive auto-fetch of unapproved code (RFC-017.NG2).
- **SGD-PROP-001.NG3:** No semver, version ranges, or integer contract levels (RFC-017.O2 rejected).
- **SGD-PROP-001.NG4:** No fetch-time code execution.

## SGD-PROP-001.P4 Proposed scope

- **SGD-PROP-001.S1:** Git coordinate kind in the approved-spool config grammar: `:git/url`, `:git/sha` (exact 40-hex pin), optional `:git/tag` (verified label), optional `:deps/root` (monorepo subpath).
- **SGD-PROP-001.S2:** Content-addressed fetch cache under the user cache directory, converged by `sync!`; per-spool fetch/tag-mismatch outcomes; cache hits touch no network.
- **SGD-PROP-001.S3:** Optional `spool.edn` manifest (`:coordinate`, `:provides`, `:needs` with `:suggest` hints, `:docs`) validated loudly, reported through sync results, and enforced as a `use!` activation gate.
- **SGD-PROP-001.S4:** Root spec deltas for SPEC-003/SPEC-004 and publishing guidance in `docs/writing-shared-spools.md`.

## SGD-PROP-001.P5 Open questions

- **SGD-PROP-001.Q1:** None blocking; RFC-017 resolved direction. Whether `:needs` may later name skein's own blessed namespaces (runtime-reported levels) is deferred follow-up scope.
