# Task 5: Implement core supersession transaction

**Document ID:** `ERF-TASK-005`

## ERF-TASK-005.P1 Scope

Type: AFK

Implement supersession as a storage/weaver transaction that owns replacement lifecycle and dependency rewiring.

## ERF-TASK-005.P2 Must implement exactly

- **ERF-TASK-005.MI1:** Add a storage-level supersession function that accepts old and replacement strand ids inside one transaction.
- **ERF-TASK-005.MI2:** Validate both strands exist, ids are distinct, old strand is not already `state="replaced"`, and replacement strand is `state="active"`.
- **ERF-TASK-005.MI3:** Write or upsert `replacement --supersedes--> old` using normal declared-acyclic cycle checks.
- **ERF-TASK-005.MI4:** Set the old strand to `state="replaced"` through the same transaction.
- **ERF-TASK-005.MI5:** Rewire every incoming `depends-on` edge targeting old so it targets replacement instead, regardless of dependent strand state. Remove the old `depends-on old` edge as part of the transaction; this internal edge deletion does not create a public edge-delete API.
- **ERF-TASK-005.MI6:** Let normal `depends-on` cycle checks reject any rewired edge that would create a cycle; rollback the entire supersession on any failure.
- **ERF-TASK-005.MI7:** Return data-first results containing old before/after rows, replacement id, supersedes edge outcome, and dependency rewiring outcomes.
- **ERF-TASK-005.MI8:** Add a weaver API operation over the storage transaction and emit an explicit semantic supersession event after commit. Include enough event data for listeners to distinguish replacement from ordinary state updates.
- **ERF-TASK-005.MI9:** Add Clojure tests for happy path, no `replaced_by` column, existing dependency rewiring, already-replaced failure, replacement-not-active failure, self-supersession failure, lineage cycle failure, dependency cycle rollback, and event payload.

## ERF-TASK-005.P3 Done when

- **ERF-TASK-005.DW1:** Focused DB/weaver supersession tests pass.
- **ERF-TASK-005.DW2:** Replacement discovery works through incoming `supersedes` edges and indexed edge lookup, with no strand-level replacement pointer.
- **ERF-TASK-005.DW3:** A failed supersession leaves old state, supersedes edges, and dependency edges unchanged.

## ERF-TASK-005.P4 Out of scope

- **ERF-TASK-005.OS1:** Go CLI supersession command.
- **ERF-TASK-005.OS2:** Public edge-delete command or general edge deletion API.
- **ERF-TASK-005.OS3:** Copying old outgoing dependencies to replacement, freezing old strands, or single-successor policies beyond acyclic lineage.

## ERF-TASK-005.P5 References

- **ERF-TASK-005.REF1:** `devflow/feat/edge-relation-families/specs/strand-model.delta.md`
- **ERF-TASK-005.REF2:** `devflow/feat/edge-relation-families/specs/daemon-runtime.delta.md`
- **ERF-TASK-005.REF3:** `devflow/feat/edge-relation-families/edge-relation-families.plan.md`
