# Strand Model delta for batch graph upsert

**Document ID:** `BGU-DELTA-001` **Root spec:** [strand-model.md](../../specs/strand-model.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-28

## BGU-DELTA-001.P1 Summary

This delta adds a durable batch graph mutation contract to the strand model. The underlying strand, attribute, lifecycle, edge, readiness, and burn meanings remain unchanged; the new behavior is an atomic way to apply multiple creates, updates, burns, and supported edge operations with batch-local refs.

## BGU-DELTA-001.P2 Contract changes

- **BGU-DELTA-001.CC1:** Core storage exposes a transactional batch graph mutation primitive that applies a complete payload atomically: every valid mutation commits, or no strand/edge mutation commits.
- **BGU-DELTA-001.CC2:** A batch payload has a local ref table. Refs are unqualified, non-blank keywords. Top-level refs bind those local keywords to existing durable strand ids. Each ref resolves to exactly one strand, and each existing strand id may be bound by at most one ref in the payload.
- **BGU-DELTA-001.CC3:** Missing top-level ref target ids, duplicate id aliases, non-keyword or namespaced ref names, and references to unbound refs outside declared create entries fail loudly before mutation.
- **BGU-DELTA-001.CC4:** A strand entry addressed to a bound ref updates the existing strand. Supported patch fields are the existing core fields `:title`, `:active`, and `:attributes`; removed lifecycle fields remain invalid.
- **BGU-DELTA-001.CC5:** A strand entry addressed to an unbound ref creates a new strand, requires a non-blank `:title`, accepts optional `:active` and `:attributes`, and extends the local ref table with the generated durable id.
- **BGU-DELTA-001.CC6:** The same ref may not appear in multiple strand entries. A ref may not be both updated and burned in the same payload.
- **BGU-DELTA-001.CC7:** Burn entries address existing bound refs only. Burning a newly created ref, burning an unknown ref, or burning a ref that is also patched fails loudly. A successful burn physically deletes the strand and incident edges using the existing burn semantics.
- **BGU-DELTA-001.CC8:** Edge entries are explicit operations addressed by local refs. The initial supported operation is upsert of an edge with `:from`, `:to`, `:type`, and optional `:attributes`. Upsert inserts a missing edge and replaces attributes on an existing matching `(from, to, type)` edge.
- **BGU-DELTA-001.CC9:** Edge upsert validates endpoint refs, target existence after create/update resolution, allowed edge type, JSON object attributes, self-edge prohibition, and final graph acyclicity.
- **BGU-DELTA-001.CC10:** Edge operations that reference a ref burned in the same payload fail loudly. Batch edge outcomes therefore describe edges present in the committed graph, not transient edges later removed by burn.
- **BGU-DELTA-001.CC11:** Omitted edges do not imply deletion or replacement. Unsupported edge operations fail loudly and reserve space for future explicit edge delete or replace contracts.
- **BGU-DELTA-001.CC12:** A batch result includes the final ref table and normalized summaries of created strands, updated before/after rows, burned ids with pre-delete rows, and edge outcomes.

## BGU-DELTA-001.P3 Design decisions

### BGU-DELTA-001.D1 Local refs are first-class batch identities

- **Decision:** Batch payloads address strands by unqualified keyword local refs after an explicit top-level existing-id binding phase.
- **Rationale:** Readable names are easier for agents and humans to inspect than repeated durable ids, and a single parse/normalization phase gives core one place to enforce existence and one-ref-one-strand invariants. Restricting refs to one Clojure-native shape avoids string/keyword/symbol normalization ambiguity in the initial trusted REPL/config surface.
- **Rejected:** Allowing raw durable ids throughout the payload as interchangeable refs, or accepting several ref token types with implicit normalization; both obscure which ids were intentionally bound and make accidental duplicate mutation harder to catch.

### BGU-DELTA-001.D2 Edge absence is not deletion

- **Decision:** Batch v1 supports explicit edge upsert only; absence of an edge entry never deletes existing edges, and edge delete/replace is deferred to future explicit operation contracts.
- **Rationale:** Replacement and deletion semantics require policy about scope, edge type, and direction. Keeping v1 upsert-only avoids accidental graph loss while the operation-shaped entry leaves room for explicit future operations.
- **Rejected:** Treating the submitted edge list as the complete outgoing edge set for a strand, or adding edge delete before edge CRUD semantics are settled.

## BGU-DELTA-001.P4 Open questions

- **BGU-DELTA-001.Q1:** None.
