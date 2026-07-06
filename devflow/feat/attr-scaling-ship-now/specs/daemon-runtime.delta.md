# Weaver Runtime delta for attr-scaling-ship-now

**Document ID:** `ASSN-DELTA-003`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Draft
**Last Updated:** 2026-07-06

## ASSN-DELTA-003.P1 Summary

This delta adds storage-open pragmas, a durable indexed-attribute-key declaration registry with trusted declare/list operations, literal-path query compilation for declared keys, and the full-fidelity guarantee (plus fail-loud guard) for trusted in-process reads. It touches the weaver storage model (SPEC-004.P3a) and the API boundary (SPEC-004.P4); it adds no public JSON socket operation.

## ASSN-DELTA-003.P2 Contract changes — SPEC-004.P3a Weaver storage model

- **ASSN-DELTA-003.CC1:** The weaver datasource opens each SQLite database with `journal_mode=WAL`, a non-zero `mmap_size`, and an enlarged `cache_size`, applied on open for every world (`ASSN-DELTA-001.CC8`). Storage identity, the single-storage-handle contract (SPEC-004.C91), and the incompatible-schema fail-loud (SPEC-004.C91b) are unchanged. `sqlite-memory` test storage runs the same open path.
- **ASSN-DELTA-003.CC2:** Storage initialization additively creates the durable `indexed_attr_keys` registry table (`ASSN-DELTA-001.CC9`) with `CREATE TABLE IF NOT EXISTS`, alongside `acyclic_relations`. An existing world gains the table without triggering the incompatible-schema path; `ensure-current-schema!` continues to validate only the `strands`/`strand_edges` core tables.

## ASSN-DELTA-003.P3 Contract changes — SPEC-004.P4 API boundary

- **ASSN-DELTA-003.CC3:** The weaver API gains two trusted semantic operations paralleling declare/list acyclic relations (SPEC-004.C16): declare one indexed attribute key, and list declared indexed attribute keys. Declaration validates key syntax, is idempotent for an already-declared key, and — unlike acyclic-relation declaration — carries **no** late-declaration guard, because declaring a key hot is result-equivalent and index creation over existing rows is additive (`ASSN-DELTA-001.D4`). Declaring a key installs (or ensures, `CREATE INDEX IF NOT EXISTS`) its expression index (`ASSN-DELTA-001.CC10`).
- **ASSN-DELTA-003.CC4:** These operations are trusted Clojure config/REPL surface. They have **no** public JSON socket operation and **no** `strand` command (SPEC-004.C27, TEN-006); they mirror the tier at which `declare-acyclic-relation!` already lives.
- **ASSN-DELTA-003.CC5:** The query compiler emits a literal JSON path for a predicate over a declared key and the existing bound-parameter form for an undeclared key (`ASSN-DELTA-001.CC12`/`CC13`). Compilation consults the durable registry as the source of truth; result semantics are identical for both forms.
- **ASSN-DELTA-003.CC6:** Weaver API return-value normalization (SPEC-004.C17) is unchanged for the trusted in-process path: `skein.api.weaver.alpha` reads (`get`, `list`, `ready`, `strands-by-ids`, `subgraph`, and the query-execution ops as consumed **in process**) decode attributes to full-fidelity Clojure maps. The lean tier is a CLI/agent read-surface transform layered above these ops (`ASSN-DELTA-002.CC4`), so the trusted path a spool calls never applies it.
- **ASSN-DELTA-003.CC7:** The lean-tier omission descriptor is the shared `::specs/omitted-attribute-descriptor` contract (`ASSN-DELTA-001.CC3`). The canonical trusted spool reader `attr-get` fails loudly when handed a value conforming to that spec where a raw value is expected (`ASSN-DELTA-001.CC6`), so a misrouted lean-projected strand surfaces immediately rather than delivering a descriptor to a payload consumer (TEN-003).

## ASSN-DELTA-003.P4 Design decisions

### ASSN-DELTA-003.D1 Declaration is durable storage, consistent with acyclic relations

- **Decision:** The indexed-key set is durable storage read by the query compiler, not a weaver-lifetime registry rebuilt from config.
- **Rationale:** Query compilation must mean the same thing across weaver restart and config reload; a runtime-only registry would let identical durable rows compile differently depending on which config happened to load. This is the same reasoning that put acyclic-relation declarations in storage (`ERF-DELTA-001.D3`).
- **Rejected:** A `skein.api.runtime.alpha/spool-state` registry or `init.clj`-held set as the source of truth for compilation.

### ASSN-DELTA-003.D2 Storage pragmas are open-time, world-wide, and contract-invisible

- **Decision:** Apply WAL/mmap/cache pragmas in the datasource open path for every world, changing no contract.
- **Rationale:** They are free wins (3–5× faster writes, −25–50% on scans in the evidence base) with near-zero downside and no read-shape or schema effect, so they need no per-world knob and no API surface.
- **Rejected:** Per-world pragma configuration; `page_size` changes (evidence ruled these out).

## ASSN-DELTA-003.P5 Open questions

- **ASSN-DELTA-003.Q1:** None for contract scope. The trusted operation names, the `mmap_size`/`cache_size` values, and whether any keys are declared by shipped config at startup are implementation choices for the plan, preserving the behavior above.
