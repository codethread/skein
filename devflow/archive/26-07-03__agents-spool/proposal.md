# Agents Spool Proposal

**Document ID:** `PROP-AgentsSpool-001` **Last Updated:** 2026-07-03 **Related RFCs:** [RFC-015](./rfcs/2026-07-03-agents-spool.md) (Implemented), companion [op manual draft](./rfcs/2026-07-03-agents-spool.op-manual.md), [RFC-010](../../rfcs/2026-07-02-shuttle-backed-coordination.md) (Implemented) **Related root specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md)

## PROP-AgentsSpool-001.P1 Problem

RFC-015 is accepted: the agent-facing delegation surface must move out of the shuttle engine and out of this repo's private `.skein/config.clj` into a shipped `skein.spools.agents` spool, so any Skein workspace gets the full orchestration vocabulary (delegate, retry, status, plan) in-band, not just the run engine. The surface itself is already designed and empirically validated (RFC-015.C7); what remains is the re-layering, the migration of repo config to genuine tuning, and documentation — including a human-facing user guide RFC-015.OUT2 requires.

## PROP-AgentsSpool-001.P2 Goals

- **PROP-AgentsSpool-001.G1:** `skein.spools.agents` owns the complete `strand op agent` surface with the RFC-015.REC1 verb set and the dogfooded manual as its `about` text.
- **PROP-AgentsSpool-001.G2:** Shuttle becomes a pure engine per RFC-015.REC7: registers no ops, exposes the preamble-extension hook and prompt-building accessors, nothing else new.
- **PROP-AgentsSpool-001.G3:** Repo `.skein/config.clj` shrinks to genuine workspace tuning per RFC-015.REC4; the strand skill defers to in-band manuals per RFC-015.C3.
- **PROP-AgentsSpool-001.G4:** The spool doc carries both agent guidance sets (RFC-015.REC3) and a human user guide: high-level concepts, the load-bearing vocabulary agents already understand, how to prompt agents so they use the surface effectively, and when to move harness-native subagent usage into this shared abstraction (cross-provider portability with minimal `defharness!` maintenance).
- **PROP-AgentsSpool-001.G5:** Affected root specs and spool docs point at the new ownership per RFC-015.C1/C4.

## PROP-AgentsSpool-001.P3 Non-goals

- **PROP-AgentsSpool-001.NG1:** RFC-015.NG1–NG4 apply unchanged (no scheduler, no native-subagent ban, no worktree management, no delegation mandate).
- **PROP-AgentsSpool-001.NG2:** No compatibility shim for the moved op registration (RFC-015.C2, TEN-000@1).

## PROP-AgentsSpool-001.P4 Proposed scope

- **PROP-AgentsSpool-001.S1:** New approved local-root spool `spools/agents` (namespace `skein.spools.agents`) composing the shuttle engine.
- **PROP-AgentsSpool-001.S2:** Shuttle engine seam changes and doc split (engine contract only in `spools/shuttle/README.md`).
- **PROP-AgentsSpool-001.S3:** Workspace migration: `spools.edn`, `init.clj` use-chain, `config.clj` shrink, strand skill shrink.
- **PROP-AgentsSpool-001.S4:** `spools/agents/README.md` with op surface, DAG conventions, both agent guidance sets, and the human user guide.
- **PROP-AgentsSpool-001.S5:** Test coverage moves/extends with the surface; smoke stays green.

## PROP-AgentsSpool-001.P5 Open questions

- None. Direction, surface, and semantics were resolved by RFC-015 and its validation evidence.
