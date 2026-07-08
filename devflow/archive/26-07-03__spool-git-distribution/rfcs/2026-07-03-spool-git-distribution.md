# Registry-free git distribution for spools

**Document ID:** `RFC-017` **Status:** Accepted **Date:** 2026-07-03 **Related:** [REPL API](../specs/repl-api.md) (SPEC-003), [Weaver Runtime](../specs/daemon-runtime.md) (SPEC-004.C41–C43), [Writing shared spools](../../docs/writing-shared-spools.md), feature folder [spool-git-distribution](../feat/spool-git-distribution/)

## RFC-017.P1 Problem

Spools can only be loaded from local roots approved in `spools.edn`. There is no way to consume a spool someone else publishes without manually cloning it and pointing a `:local/root` at the checkout, and no way for a published spool to state that it builds on another spool. We need a distribution and compatibility story that preserves the existing consent model (`spools.edn` approval = code runs with the user's authority) and the tenets: fail loudly (TEN-003), less is more (TEN-004), agent-first (TEN-001), thin CLI (TEN-006).

## RFC-017.P2 Goals

- **RFC-017.G1:** A user/agent can approve and load a spool published as a git repository, choosing the URL/transport themselves.
- **RFC-017.G2:** Approval remains exact-content consent; nothing runs that was not explicitly approved.
- **RFC-017.G3:** A published spool can declare the spools it builds on, loudly surfacing unmet requirements.
- **RFC-017.G4:** No new hosted infrastructure and no imperative package-management CLI surface.

## RFC-017.P3 Non-goals

- **RFC-017.NG1:** No registry, index, or discovery service; discovery stays userland.
- **RFC-017.NG2:** No transitive auto-fetch; dependencies are fulfilled by explicit approval only.
- **RFC-017.NG3:** No install hooks or code execution at fetch time; approved code runs only via `use!`/require.
- **RFC-017.NG4:** No version-range resolution machinery of any kind.

## RFC-017.P4 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-017.O1 | Registry + semver (npm-shaped): hosted index, version ranges, resolver, install CLI | Familiar UX; discovery built in | Hosted infra; resolver complexity; ranges break exact-content consent; violates TEN-004/TEN-006 |
| RFC-017.O2 | Git + SHA pin, manifest with monotonic integer accretion levels per provided namespace (`:provides {ns 4}`, `:needs {ns 2}`) | No resolver (max level always satisfies — accretion dissolves the diamond problem); mirrors `skein.api.*.alpha` accretion discipline | Levels are self-asserted and behavior-blind: silent semantic drift, forgotten bumps, lying provides; a compatibility number nobody verifies is fail-quietly trust-bait (TEN-003 violation) |
| RFC-017.O3 | Git + SHA pin, manifest with `:provides` as a plain namespace set (verified loadable), `:needs` as coordinate set with optional `:suggest` hints, no integers | SHA is the real behavior contract (any change moves the hash, forcing re-approval); every claim in the manifest is mechanically verifiable; smallest surface | No in-band "how far has this API accreted" signal; consumers needing newer surface discover it at require/call time (loudly) |

## RFC-017.P5 Recommendation

- **RFC-017.REC1:** Option O3. Distribution: `spools.edn` grows a git coordinate kind (`:git/url` + `:git/sha`, optional readability `:git/tag` verified against the sha, optional `:deps/root` subpath for monorepos), fetched by `sync!` into a content-addressed cache and then treated exactly like a local root. Compatibility: an optional `spool.edn` manifest at the spool root declaring `:coordinate`, `:provides` (namespace set), `:needs` (coordinate → optional `:suggest {:git/url …}` hint), `:docs`. `sync!` reports unmet needs as loud per-spool outcomes; `use!` refuses activation on unmet needs or unloadable provides. Integer accretion levels (O2) are rejected for now; the manifest grammar can accrete a level map later without breaking existing manifests if real demand appears.
- **RFC-017.REC2:** Decision process: the O2/O3 tradeoff was adversarially reviewed by two independent agent debaters (claude-opus and pi) over two rounds; both converged on "the SHA pin is the behavioral contract; ship shape-checking honest about being shape-checking" and on rejecting unverified integers. Upgrades are SHA edits proposed by agents and signed off by the user.

## RFC-017.P6 Consequences

- **RFC-017.C1:** SPEC-003 (REPL API) gains the git coordinate grammar and the `spool.edn` manifest contract; SPEC-004 (Weaver Runtime) gains fetch/cache, tag-verification, needs/provides sync outcomes, and `use!` gating contracts.
- **RFC-017.C2:** `sync!` becomes the single fetch/converge point; fetch failures are per-spool sync outcomes, never structural errors, and no network is touched on cache hits.
- **RFC-017.C3:** The CLI surface is unchanged; add/update/remove remains editing EDN plus `sync!` from trusted config/REPL.
- **RFC-017.C4:** `docs/writing-shared-spools.md` grows publishing guidance (manifest authoring, git distribution, needs/suggest discipline).

## RFC-017.P7 Outcome

- **RFC-017.OUT1:** Accepted 2026-07-03 by the repository owner after the two-agent adversarial review. Implementation proceeds in feature folder [spool-git-distribution](../feat/spool-git-distribution/); durable contracts staged as spec deltas there.
