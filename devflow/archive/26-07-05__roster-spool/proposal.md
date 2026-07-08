# Roster Spool Proposal

**Document ID:** `PROP-RosterSpool-001` **Last Updated:** 2026-07-05 **Related RFCs:** [RFC-014 Feature Tracking Registry](../../rfcs/2026-07-02-feature-tracking-registry.md) **Related root specs:** [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [CLI Surface](../../specs/cli.md), [Strand Model](../../specs/strand-model.md)

## PROP-RosterSpool-001.P1 Problem

Skein has an interim branch-stamped work-root convention for answering what is active in a repository, but no durable shared spool owns the vocabulary, query shape, or await semantics described by `RFC-014.REC3`. Coordinators still need a single graph-native answer for active feature/session work across workflow/devflow, AFK loops, and ad hoc drivers without re-deriving state from worktree side effects.

## PROP-RosterSpool-001.P2 Goals

- **PROP-RosterSpool-001.G1:** Ship a reusable `skein.spools.roster` reference spool that defines consistent `roster/*` attributes for active work entries.
- **PROP-RosterSpool-001.G2:** Provide explicit-runtime REPL helpers for registering, heartbeating, finishing, listing, and awaiting roster entries.
- **PROP-RosterSpool-001.G3:** Provide a discoverable public `strand roster ...` surface suitable for agents and scripts.
- **PROP-RosterSpool-001.G4:** Surface stale entries loudly as stale instead of hiding or auto-cleaning them.
- **PROP-RosterSpool-001.G5:** Fold the heartbeat/staleness decision from card `w1t3o`: heartbeat thresholds are explicit, stale entries remain visible, and finished-entry cleanup is deliberate.
- **PROP-RosterSpool-001.G6:** Fold the workflow/devflow/AFK integration decision from card `hphfn`: roster presence should be automatic where existing graph roots provide enough context, and explicit for engines/ad hoc sessions that the graph cannot infer.

## PROP-RosterSpool-001.P3 Non-goals

- **PROP-RosterSpool-001.NG1:** The roster spool does not replace workflow, devflow, kanban, shuttle, or branch visibility; it summarizes active work across them.
- **PROP-RosterSpool-001.NG2:** The roster spool does not enforce exclusive locks or prevent untracked work; trusted users and agents may still work outside the convention.
- **PROP-RosterSpool-001.NG3:** Cross-weaver aggregation is not part of this feature. The spool should expose data a future guild-facing operation can serve.
- **PROP-RosterSpool-001.NG4:** Finished or stale entries are not auto-burned. Cleanup remains a deliberate action.

## PROP-RosterSpool-001.P4 Proposed scope

- **PROP-RosterSpool-001.S1:** Define a canonical roster entry vocabulary covering feature, owner, worktree, branch, engine, heartbeat, status, and lifecycle timestamps.
- **PROP-RosterSpool-001.S2:** Define `track!`, `heartbeat!`, `finish!`, `roster`, and `await-quiet!` behavior for explicit runtime consumers.
- **PROP-RosterSpool-001.S3:** Expose an agent-facing `strand roster` operation with `about`, `track`, `heartbeat`, `finish`, `list`, and `await-quiet` subcommands.
- **PROP-RosterSpool-001.S4:** Add a named roster query so existing `strand list`/`ready` query flows can project active roster entries.
- **PROP-RosterSpool-001.S5:** Integrate with workflow/devflow roots by deriving roster attributes from existing run/family/feature context when roots are created or updated.
- **PROP-RosterSpool-001.S6:** Document the AFK/ad hoc integration convention: engines that do not mutate graph state during a run must call `track!`, periodic `heartbeat!`, and `finish!` explicitly.

## PROP-RosterSpool-001.P5 Open questions

- **PROP-RosterSpool-001.Q1:** What default stale threshold should the shipped spool use, and how should callers override it per list/await call?
- **PROP-RosterSpool-001.Q2:** Should audit/history visibility for finished entries be exposed as an explicit non-default view?
- **PROP-RosterSpool-001.Q3:** Which workflow/devflow attributes are sufficient to auto-stamp useful entries without adding hard dependencies on downstream spools?
