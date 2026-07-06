# CLI Surface delta for eav-attr-storage

**Document ID:** `EAS-DELTA-002`
**Root spec:** [cli.md](../../../specs/cli.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-07-06

## EAS-DELTA-002.P1 Summary

The CLI surface is contract-unchanged by the storage move (TEN-007): the attribute-map wire shape, the lean-by-default read tier, and the dispatcher relay contracts all behave exactly as today because the change lives below `skein.core.*`. This delta records two consequences: no public command is added for the new trusted archive/migrate primitives, and the removal of the declared-indexed-attribute-key concept lets a now-stale CLI clause retire. It also owns the `CLAUDE.md` SQLite-debug docs rewrite that the dropped `strands.attributes` column forces.

## EAS-DELTA-002.P2 Contract changes

- **EAS-DELTA-002.CC1:** No `strand` command and no dispatcher flag is added for `archive!` / `unarchive!` or the migrate op. They are trusted Clojure config/REPL surface only (`EAS-DELTA-003.CC4`, TEN-006), mirroring the tier at which the removed indexed-attr-key declaration lived and at which acyclic-relation declaration still lives.
- **EAS-DELTA-002.CC2:** The lean-by-default read contract (SPEC-002.P1 second paragraph — `list`/`ready`/query-backed listing return the omission descriptor above the fixed byte floor; `show` returns the full row; no hydration flag; leanness is a batteries read-surface transform relayed verbatim as NDJSON) is unchanged. It operates over the assembled attribute map (`EAS-DELTA-001.CC3`), so the storage move is invisible to it.
- **EAS-DELTA-002.CC2a:** Archive visibility is decided below the lean transform. `show` relays the full point-read map including archived values; `list`/`ready`/query-backed listing receive hot-path maps with archived values already excluded (`EAS-DELTA-001.CC4`). The omission descriptor still only means "large value omitted from this read surface", never "archived".
- **EAS-DELTA-002.CC3:** SPEC-002.C21's "storage hot-key declaration" and "no public command declares or lists indexed attribute keys" language becomes moot on merge: the indexed-attribute-key concept is removed entirely (`EAS-DELTA-001.CC9`), so the retired clause language drops rather than being reworded. No CLI behavior changes — the concept was already command-less; the surface it described no longer exists.
- **EAS-DELTA-002.CC4:** The dispatcher's verbatim-argv and NDJSON relay contracts (SPEC-002.C30/C36) are unchanged. The attribute-map wire format (`show` full-fidelity JSON object, list-style lean projection) is byte-identical to today for full-fidelity reads (`PROP-EavAttrStorage-001` acceptance invariant), with lexicographic key order the only newly-guaranteed property (`EAS-DELTA-001.CC5`) — and JSON object key order was never a CLI contract.

## EAS-DELTA-002.P3 Documentation impact

- **EAS-DELTA-002.DI1:** The repository `CLAUDE.md` "Debugging SQLite state" section (which today shows `select id, title, attributes from strands`) is rewritten in this feature to query the new `attributes` table, e.g. `select strand_id, key, value, archived from attributes`, since the old query dies with the dropped document column. Leaving the stale snippet would misdirect every future contributor; the update is owned here, not deferred (`PROP-EavAttrStorage-001.D5`).

## EAS-DELTA-002.P4 Design decisions

### EAS-DELTA-002.D1 Storage-tier primitives stay off the CLI

- **Decision:** Keep `archive!` / `unarchive!` / migrate as trusted Clojure surface; add no `strand` command or flag.
- **Rationale:** These mutate storage-tier state and, in the migrate case, perform a one-time world cutover — decisions for a trusted operator at the REPL/config tier, not scriptable low-privilege CLI worker input (TEN-006). The CLI stays a thin JSON control surface over the map contract.
- **Rejected:** A public `strand archive`/`strand migrate` command surface.

## EAS-DELTA-002.P5 Open questions

- **EAS-DELTA-002.Q1:** None for CLI contract scope. The exact `CLAUDE.md` debug snippet wording is a docs mechanic finalized at implementation.
