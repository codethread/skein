# CLI Surface delta for attr-scaling-ship-now

**Document ID:** `ASSN-DELTA-002`
**Root spec:** [cli.md](../../../specs/cli.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Draft
**Last Updated:** 2026-07-06

## ASSN-DELTA-002.P1 Summary

This delta makes CLI/agent list-style reads **lean by default**: large attribute values are replaced with the typed omission descriptor (`ASSN-DELTA-001.CC2`) instead of dumped into agent context. Point reads stay full-fidelity, and no new hydration flag is added — full fidelity is reached by composing the existing surface. The concrete per-op wording lands in the `skein.spools.batteries` contract (`spools/batteries.md`), since `list`/`ready`/`show` are batteries ops (SPEC-002.P1); this delta states the cross-cutting read-surface contract the dispatcher and batteries ops honor.

## ASSN-DELTA-002.P2 Contract changes

- **ASSN-DELTA-002.CC1:** The list-style read ops (`list`, `ready`, and query-backed listing) return the **lean** tier by default: each returned strand's attribute values above the fixed byte floor (`ASSN-DELTA-001.CC7`) are the omission descriptor `{"skein/omitted": true, "bytes": N}` in JSON; values at or below the floor are returned verbatim. Ids, `state`, `title`, timestamps, and all small metadata keys are unaffected.
- **ASSN-DELTA-002.CC2:** The point-read op (`show`) returns the **full** tier by default: every attribute value verbatim, unchanged from today. An agent that needs a payload lists to find the id, then `show <id>` returns the whole row.
- **ASSN-DELTA-002.CC3:** No `--hydrate` flag (and no other hydration lever) is added to the lean ops. Lean-then-point-read composition already serves the agent workflow, so per TEN-004 the surface stays as-is rather than growing a switch (`ASSN-DELTA-001.D3`).
- **ASSN-DELTA-002.CC4:** The lean projection is a read-surface transform applied at the CLI/agent op boundary only. It never alters stored data and never reaches the trusted in-process API that spools call (`ASSN-DELTA-001.CC5`), so the dispatcher's verbatim-argv and NDJSON relay contracts (SPEC-002.C30/C36) are unchanged — the change is in what the batteries op emits, not in how `strand` relays it.
- **ASSN-DELTA-002.CC5:** Declaring a hot filter key for indexing (`ASSN-DELTA-001.CC9`) is a trusted daemon/REPL/config surface, not a public `strand` command (TEN-006). No new CLI command is added for declaration or for listing declared keys.

## ASSN-DELTA-002.P3 Design decisions

### ASSN-DELTA-002.D1 Lean output is the agent-context fix, shipped independent of storage

- **Decision:** Change the default output shape of list-style reads to omit large values; leave storage untouched.
- **Rationale:** The current full-blob-in-every-list is itself the agent-context bug (TEN-001) and is the cheapest, safest, highest-value fix — independent of any storage-offload decision. Making it the default (with point reads full) directly cuts context waste without a flag an agent must remember to set.
- **Rejected:** Keeping full blobs the default and gating leanness behind an opt-in flag (the wrong default for the primary consumer).

## ASSN-DELTA-002.P4 Open questions

- **ASSN-DELTA-002.Q1:** None for contract scope. The exact per-op batteries wording lands in `spools/batteries.md` at implementation.
