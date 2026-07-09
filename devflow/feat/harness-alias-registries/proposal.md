# Harness/Alias Registry Split Proposal

**Document ID:** `PROP-HarnessAliasRegistries-001`
**Last Updated:** 2026-07-09
**Related RFCs:** None (direction settled with the user in-session; no unresolved alternatives)
**Related root specs:** None (spool-layer contract; strand-model / cli / repl-api / daemon-runtime untouched)
**Related contracts:** [Shuttle spool](../../../spools/shuttle/README.md) (§ harness registry), [Treadle spool](../../../spools/shuttle/treadle.md) (alias-registration ordering note), [Agents spool](../../../spools/agents/README.md) (harness/alias vocabulary)

## PROP-HarnessAliasRegistries-001.P1 Problem

`defharness!` and `defalias!` write into one shared registry, so tool
definitions and seat names compete for the same namespace and for an agent's
attention. Two concrete costs:

- A seat cannot carry a tool's natural name: `pi-main` exists only because
  `pi` is taken by the harness. Seat names drift toward awkward suffixes
  instead of what supervisors actually want to say.
- A same-named registration is a silent overwrite (`swap! assoc`): defining
  an alias `pi` would clobber the `pi` harness with no error, breaking every
  seat layered over it.

The two constructs have different concerns: a harness exists **once per
tool** (claude, pi, codex, sh) and is the maximum-control escape hatch; an
alias is a **seat** — the thing agents nearly always pick.

## PROP-HarnessAliasRegistries-001.P2 Goals

- **PROP-HarnessAliasRegistries-001.G1:** Harnesses and aliases live in
  separate registries; a seat may intentionally carry the same name as a
  tool without destroying it.
- **PROP-HarnessAliasRegistries-001.G2:** Spawn/resolution prefers the seat:
  alias registry first, then harness registry. A harness stays directly
  addressable whenever no seat shadows its name.
- **PROP-HarnessAliasRegistries-001.G3:** The repo roster reads as seats
  over tools: `pi-main` is replaced by seat `worker` (`pi --agent main`);
  the `pi` name goes back to meaning the tool.
- **PROP-HarnessAliasRegistries-001.G4:** A live weaver reload across this
  upgrade loses no registrations: the preserved mixed registry migrates by
  splitting entries on `:alias-of` presence.

## PROP-HarnessAliasRegistries-001.P3 Non-goals

- **PROP-HarnessAliasRegistries-001.NG1:** No declared per-harness argument
  schema constraining what aliases may pass. `:extra-args` stays free-form
  argv: alias authoring is trusted config, a schema would duplicate each
  CLI's argument surface and rot, and the CLI itself already fails loudly on
  bad arguments. (User left this fork open; settled as keep-argv.)
- **PROP-HarnessAliasRegistries-001.NG2:** No back-compat `pi-main` alias.
  The registry is workspace config re-registered on every reload; renames
  are a config-and-docs sweep, not a deprecation surface.
- **PROP-HarnessAliasRegistries-001.NG3:** No change to spawn, resume,
  review, or council semantics — only how a name resolves to an effective
  harness definition.

## PROP-HarnessAliasRegistries-001.P4 Proposed scope

- **PROP-HarnessAliasRegistries-001.S1:** Shuttle keeps two runtime
  registries: per-tool harnesses (`defharness!`) and seat aliases
  (`defalias!`). Registration within a registry replaces (reload
  idempotency); across registries names are independent.
- **PROP-HarnessAliasRegistries-001.S2:** Name resolution walks
  alias-first: an unvisited alias wins, otherwise the harness registry,
  otherwise fail loudly (cycle/missing). This makes `defalias! :pi
  {:alias-of :pi ...}` a lawful shadow that terminates at the tool.
- **PROP-HarnessAliasRegistries-001.S3:** The `harnesses` listing returns
  the union (kind already distinguishes), keeping the alias `:harness` /
  `:harness-doc` enrichment from `55b3e36`; same-named tool and seat both
  appear.
- **PROP-HarnessAliasRegistries-001.S4:** Registry state remains versioned
  spool-state: shape version bumps and the migrate hook splits a preserved
  mixed registry by `:alias-of` presence.
- **PROP-HarnessAliasRegistries-001.S5:** Repo roster and docs sweep:
  `worker` replaces `pi-main` across `.skein` config, treadle/agents docs,
  cookbook honest-source lines, and config tests; shuttle README documents
  the two-registry contract and resolution order.

## PROP-HarnessAliasRegistries-001.P5 Open questions

- **PROP-HarnessAliasRegistries-001.Q1:** None — resolution order, the
  argv fork (NG1), and the `worker` seat name were settled with the user
  in-session before this proposal.
