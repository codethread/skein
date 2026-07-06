# Weaver Runtime delta for eav-attr-storage

**Document ID:** `EAS-DELTA-003`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Draft
**Last Updated:** 2026-07-06

## EAS-DELTA-003.P1 Summary

This delta moves strand attribute storage from a document column to an `attributes` EAV table, adds trusted `archive!` / `unarchive!` primitives and one explicit migrate op, rewrites the query compiler to run over non-archived rows, and removes the L0b declared-indexed-attribute-key surface promoted at `5595fe7`. It touches the weaver storage model (SPEC-004.P3a) and the API boundary (SPEC-004.P4); it adds no public JSON socket operation. The full-fidelity in-process read guarantee and the L1 lean-tier guard are unchanged. No `skein.api.*.alpha` namespace membership changes, so the alpha-surface index (SPEC-005.C2) is unaffected — the new ops are accretion within `skein.api.weaver.alpha`, and the removed ops leave it.

## EAS-DELTA-003.P2 Contract changes — SPEC-004.P3a Weaver storage model

- **EAS-DELTA-003.CC1:** Storage initialization creates the `attributes` EAV table (`EAS-DELTA-001.CC6`) in place of the `strands.attributes` document column, with the `(key, value) WHERE archived = 0` partial index (`EAS-DELTA-001.CC7`). The single-storage-handle contract (SPEC-004.C91), scheduler-table initialization (SPEC-004.C91a), and the WAL/mmap/cache open pragmas are unchanged; `sqlite-memory` test storage runs the same schema and open path.
- **EAS-DELTA-003.CC2:** `ensure-current-schema!` fails loudly on the old document schema (a dropped `strands.attributes` column is an incompatible core layout) exactly as SPEC-004.C91b requires; there is no implicit migration (TEN-000). Unlike the additively-created `indexed_attr_keys` table this feature removes, the `attributes` table is part of the validated core schema, not an `IF NOT EXISTS` auxiliary table — an existing document-schema world does not silently gain it.
- **EAS-DELTA-003.CC3:** The `indexed_attr_keys` registry table and its per-key expression indexes are removed from storage initialization (`EAS-DELTA-001.CC9`). No auxiliary attribute-index table remains; the `acyclic_relations` and scheduler auxiliary tables are unaffected.

## EAS-DELTA-003.P3 Contract changes — SPEC-004.P4 API boundary

- **EAS-DELTA-003.CC4:** The weaver API gains three trusted semantic operations, all trusted Clojure config/REPL surface with **no** public JSON socket operation and **no** `strand` command (same tier as SPEC-004.C16/C16a's trusted registry helpers, TEN-006):
  - `(archive! runtime strand-id)` archives all attributes for one strand; `(archive! runtime strand-id keys)` archives only the explicit key set. `(unarchive! runtime strand-id)` and `(unarchive! runtime strand-id keys)` are symmetric Clojure verbs so callers do not pass a direction flag with two legal values. `strand-id` is a non-blank string id; `keys` is a non-empty collection of attribute keys. Input/output are spec-backed in `skein.core.specs` (`::attribute-key-set`, `::attribute-archive-result`, and function fdefs or equivalent call-site conformance). Return shape is a map at least `{:strand-id <id> :keys [<key> ...] :archived? true|false :changed <n>}` with keys returned in lexicographic order; `:changed` counts rows whose flag actually transitioned, so repeating the same archive/unarchive is idempotent and returns `0`. Multi-key calls validate every requested key before writing and commit atomically in one transaction. Unknown strand ids, malformed ids, malformed key collections, empty key sets, keys not present on the strand, and any write-count mismatch after prevalidation fail loudly with ex-data carrying `:strand-id`, `:keys`, and a stable reason keyword. They mutate the `archived` flag only; they never change a value or the map contract. Archive **policy** stays userland (`PROP-EavAttrStorage-001.NG4`).
  - `(migrate-attribute-storage! runtime)` converts an existing world's `strands.attributes` documents into `attributes` rows, so existing worlds — including the canonical coordination world — cut over explicitly and once (`EAS-DELTA-001.CC8`). Its result is spec-backed in `skein.core.specs` (`::attribute-storage-migration-result`) and has shape at least `{:status :migrated|:already-current :strands <n> :attributes <n>}`. It validates each legacy document as JSON and against `::specs/attributes`, writes rows, and verifies row/document parity before commit. It fails loudly on malformed JSON, a shape-invalid source document, a partially migrated mixed schema, any post-write row/document parity mismatch, or any storage schema that is neither the old document layout nor the current row layout, with ex-data carrying enough counts/ids to diagnose the failure. Re-running on an already-migrated world returns `:already-current`.
- **EAS-DELTA-003.CC5:** The trusted declare/list **indexed-attribute-key** operations (the SPEC-004.C16 "declare/list durable indexed attribute keys" surface, and clauses SPEC-004.C16a/C16b) are **removed**. Every non-archived key is uniformly index-usable by construction, so there is nothing to declare (`EAS-DELTA-001.CC9`). SPEC-004.C16's operation inventory drops the indexed-attr-key pair and gains `archive!`/`unarchive!`/migrate on merge.
- **EAS-DELTA-003.CC6:** The query compiler compiles a `[:attr k]` predicate to an `EXISTS`/join over non-archived attribute rows, with cross-key predicates as self-joins (`EAS-DELTA-001.CC12`/`CC13`) and result semantics identical to today's document form for every key. The literal-JSON-path emission for declared keys and its `::specs/indexed-attr-key` re-validation (former SPEC-004.C16b) are removed; the compiler binds keys and values as parameters and never splices a key into SQL text (`EAS-DELTA-001.CC14`).
- **EAS-DELTA-003.CC7:** Weaver API return-value normalization (SPEC-004.C17) is unchanged above the storage seam, but archived visibility follows the read tier from `EAS-DELTA-001.CC4`: full point/trusted lookup reads (`show`, `strands-by-ids`, and `subgraph` rows loaded by id) decode assembled maps including archived values; hot list/query reads (`list`, `ready`, and query execution) assemble maps from non-archived rows only and predicates never match archived rows. The lean tier stays a CLI/agent read-surface transform layered above these assembled maps, so it never controls archive visibility.
- **EAS-DELTA-003.CC8:** The lean-tier omission descriptor (`::specs/omitted-attribute-descriptor`) and the `attr-get` fail-loud guard (SPEC-004.C17a) are unchanged; they operate over the assembled attribute map exactly as they did over the document, with the same ex-data contract (`{:key <attribute-key> :strand-id <strand-id> :recovery "show <strand-id>"}`).
- **EAS-DELTA-003.CC9:** Mutation event and view payloads (SPEC-004.P10a, SPEC-004.C71–C74; SPEC-004.C56–C59) that embed a strand carry the **same assembled attribute map shape** they carry today. Archive visibility follows the producer's read tier (`EAS-DELTA-003.CC7`); the L1 lean projection still applies only for read-surface consumers. Their shape does not change; the storage change is invisible to them (`PROP-EavAttrStorage-001.D4`).

## EAS-DELTA-003.P4 Design decisions

### EAS-DELTA-003.D1 `archive!` / `unarchive!` are trusted-only primitives, policy stays userland

- **Decision:** Ship `archive!` / `unarchive!` as trusted Clojure config/REPL ops operating per strand and per explicit key set, with no socket op and no `strand` command. Core ships no archive policy.
- **Rationale:** They mutate storage-tier state that must not be reachable from the low-privilege CLI worker surface (TEN-006), mirroring the tier at which `declare-acyclic-relation!` and the removed indexed-key ops lived. The two verbs are explicit at the call site and avoid a direction enum whose wrong value would need another fail-loud branch. What to archive and when (e.g. a userland gleaner over closed strands, which hold ~99% of payload bytes) is a userland decision, not a core one (TEN-004, `PROP-EavAttrStorage-001.NG4`).
- **Rejected:** A public `strand archive` command; one `set-attributes-archived!` operation with a direction flag; a shipped archive policy or scheduled sweeper in core.

### EAS-DELTA-003.D2 One explicit migrate op, no silent cutover

- **Decision:** Ship exactly one trusted migrate op, `migrate-attribute-storage!`, for the document→rows cutover; `ensure-current-schema!` fails loud on the old schema and never migrates implicitly.
- **Rationale:** TEN-000/TEN-003: an incompatible schema is a loud failure with an explicit, operator-invoked remedy, not a silent rewrite of a world carrying real coordination data. One op keeps the cutover a single deliberate act the operator can verify, rather than a startup side effect.
- **Rejected:** Auto-migration on open; re-init-only cutover with no data-preserving path for the canonical world.

### EAS-DELTA-003.D3 Removing L0b is honest cleanup, not a compatibility break to preserve

- **Decision:** Remove the indexed-attr-key table, spec, ops, and literal-path compilation rather than leave them as inert surface.
- **Rationale:** They guard a JSON-document-only problem that EAV dissolves; leaving them would be dead surface claiming a capability the storage no longer needs (TEN-004). Their coverage folds into the uniform-capability gates (`EAS-DELTA-001.D5`). TEN-000 lets us drop the just-shipped machinery without a migration plan when a better representation lands.
- **Rejected:** Keeping the declared-key ops and literal-path fast path alongside EAV.

## EAS-DELTA-003.P5 Open questions

- **EAS-DELTA-003.Q1:** The exact namespace placement and internal helper names for storage-private functions below `archive!`, `unarchive!`, and `migrate-attribute-storage!` are implementation mechanics. Their trusted tier, semantic args, spec-backed input/output shapes, atomicity/idempotency rules, and fail-loud cases are specified in `EAS-DELTA-003.CC4`; no contract-scope questions remain.
