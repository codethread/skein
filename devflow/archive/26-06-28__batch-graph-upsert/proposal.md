# Batch Graph Upsert Proposal

**Document ID:** `BGU-PROP-001` **Status:** Reviewed **Related RFCs:** None **Related Specs:** [Strand Model](../../specs/strand-model.md), [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [CLI Surface](../../specs/cli.md) **Feature Deltas:** [Strand Model delta](specs/strand-model.delta.md), [Weaver Runtime delta](specs/daemon-runtime.delta.md), [REPL API delta](specs/repl-api.delta.md), [CLI Surface delta](specs/cli.delta.md)

## BGU-PROP-001.P1 Problem

Skein users can create strand batches through weave patterns and read selected sets through query/graph helpers, but durable update, burn, and edge mutation workflows remain one strand at a time or require lower-level trusted code. This makes common userland transformations verbose and error-prone: agents must coordinate ids manually, issue multiple mutations, and accept partial progress unless they write their own transaction wrapper.

The missing primitive is an atomic, data-first graph mutation payload that can create, update, burn, and connect strands as one transaction while preserving Skein's small core model and fail-loud behavior.

## BGU-PROP-001.P2 Goals

- **BGU-PROP-001.G1:** Provide one blessed batch mutation primitive for trusted REPL/config/library workflows.
- **BGU-PROP-001.G2:** Let callers bind readable local refs to existing strand ids once, then use those refs throughout the payload.
- **BGU-PROP-001.G3:** Treat bound refs as existing strands for update/burn and unbound strand refs as future created strands.
- **BGU-PROP-001.G4:** Apply creates, updates, burns, and supported edge operations atomically in one transaction.
- **BGU-PROP-001.G5:** Preserve graph invariants, including existing strand existence, one-ref-one-strand mapping, valid edge types, and acyclicity.
- **BGU-PROP-001.G6:** Return normalized data-first results suitable for agents, including final refs, created rows, updated before/after rows, burned ids, and edge outcomes.

## BGU-PROP-001.P3 Non-goals

- **BGU-PROP-001.NG1:** Do not add public CLI batch mutation commands in the initial scope.
- **BGU-PROP-001.NG2:** Do not add new core lifecycle fields, statuses, task kinds, or workflow semantics.
- **BGU-PROP-001.NG3:** Do not add query-driven mutation directly to the batch payload; callers can compose query-id selection before invoking batch mutation.
- **BGU-PROP-001.NG4:** Do not make edge absence imply deletion or replacement.
- **BGU-PROP-001.NG5:** Do not add remote package, plugin, or untrusted execution behavior.
- **BGU-PROP-001.NG6:** Do not introduce durable audit/tombstone storage for burns.

## BGU-PROP-001.P4 Proposed scope

- **BGU-PROP-001.S1:** Add a core transactional batch graph mutation contract shaped around a top-level local ref table, strand entries, edge operation entries, and burn refs.
- **BGU-PROP-001.S2:** Require top-level `:refs` to bind simple local ref names to existing durable strand ids, with one ref per id and fail-loud validation for missing ids or duplicate id aliases.
- **BGU-PROP-001.S3:** Require every strand entry to name one `:ref`; entries whose ref is already bound update that existing strand, and entries whose ref is unbound create a new strand and extend the local ref table.
- **BGU-PROP-001.S4:** Support strand creation and patch fields already present in the core model: `:title`, `:active`, and `:attributes`.
- **BGU-PROP-001.S5:** Support burn by local ref for existing bound refs, rejecting burn of newly created refs and rejecting combined burn/update of the same ref.
- **BGU-PROP-001.S6:** Support edge upsert operations addressed by local refs, with explicit operation shape such as `{:op :upsert :from :a :to :b :type "depends-on" :attributes {}}`; matching existing edges have attributes replaced.
- **BGU-PROP-001.S7:** Defer edge delete/replace from v1 while reserving room for future explicit edge operations; unsupported edge ops fail loudly.
- **BGU-PROP-001.S8:** Expose the primitive through weaver API and a blessed `skein.batch.alpha/apply!` helper for trusted runtime workflows; update REPL/spec docs to describe the supported contract.
- **BGU-PROP-001.S9:** Emit `:batch/applied` as the authoritative rich batch event, followed by compatibility fanout of existing per-strand mutation events with shared `:batch/id` only.
- **BGU-PROP-001.S10:** Keep the existing create-only weave/pattern batch path valid for v1 while treating batch graph upsert as the blessed path for transformations that need update, burn, or edge mutation. A later implementation may migrate pattern internals without changing the public pattern contract.

## BGU-PROP-001.P5 Draft payload sketch

```clojure
{:refs {:old-doc "ie32h"
        :old-design "id8we"}

 :strands [{:ref :old-doc
            :active false
            :attributes {:outcome "superseded"}}

           {:ref :new-design
            :title "Redesign batch API"
            :attributes {:kind "design"}}

           {:ref :new-doc
            :title "Document batch API"
            :attributes {:kind "doc"}}]

 :edges [{:op :upsert
          :from :new-doc
          :to :new-design
          :type "depends-on"}

         {:op :upsert
          :from :old-doc
          :to :new-doc
          :type "supersedes"}]

 :burn [:old-design]}
```

The normalized result should expose generated ids through the final ref table and report all durable effects as data, including pre-delete rows for burned strands.

## BGU-PROP-001.P6 Open questions

- **BGU-PROP-001.Q1:** Should a future public CLI batch command expose the same payload directly, or should it invoke named daemon-owned batch transforms instead?
