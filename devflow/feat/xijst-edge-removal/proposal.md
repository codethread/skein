# Exact edge removal in the batch primitive

**Document ID:** `PROP-Xer-001`

**Last updated:** 2026-07-21

**Source card:** `xijst` (branch `codex/xijst-edge-removal`) **Decision card:** `nuu5y` (done) **Decision note:** `xh4nd` **Lifecycle task:** `79i5x` **Council synthesis:** `3773y` (panel-f8e735f2)

**Related root specs:** [Strand Model](../../specs/strand-model.md) (SPEC-001.P5 Edges, SPEC-001.P6 Batch graph mutation), [Alpha Surface](../../specs/alpha-surface.md) (SPEC-005.C2)

**Related source:** `src/skein/api/batch/alpha.clj`, `src/skein/core/db.clj` (`normalize-batch-payload!` `db.clj:1155`, `apply-batch-in-transaction!` `db.clj:1206`, `delete-edge!` `db.clj:761`, `add-edge!` `db.clj:736`)

## PROP-Xer-001.P1 Problem

Spools can create and replace edges but cannot remove one. `skein.api.batch.alpha/apply!` upserts edges (`{:op :upsert ...}`, `db.clj:1194`), and `delete-edge!` (`db.clj:761`) is core-private, so a worker that needs to retire a stale edge has no blessed path.

The gap is not cosmetic. When a stale edge survives it still participates in traversal, readiness, and queries, so filtering it becomes distributed userland policy. `xijst` was raised (note `6snea`) after run `9v0p0` left a stale `delegates` edge and worked around it with a `treadle/superseded-by` provenance marker. Card `4cdsu` later had to call core-private `delete-edge!` directly to retire three obsolete `depends-on` edges (task note `n746p`).

Provenance markers do not close the gap: the edge is still there. Physical removal is the only thing that stops a retired edge participating in the graph.

## PROP-Xer-001.P2 Goals

- **PROP-Xer-001.G1:** A blessed removal primitive that deletes an exact edge by identity, atomic with the rest of the batch.
- **PROP-Xer-001.G2:** One uniform edge outcome shape across upsert and remove, so a hook or caller reads every edge transition the same way.
- **PROP-Xer-001.G3:** No surface beyond the one batch op — the decision (`xh4nd`) and council synthesis (`3773y`) hold this to the narrowest accretion.

## PROP-Xer-001.P3 Non-goals

- **PROP-Xer-001.NG1:** No CLI verb. Edge removal is a rich trusted mutation and stays out of the thin JSON surface (TEN-006).
- **PROP-Xer-001.NG2:** No `:edge/removed` event, no standalone one-edge helper, no generic replace or rewire op, no nested batch stages, no variadic `apply!` arity, no graph projector, no edge tombstone, no durable audit or outbox promise.
- **PROP-Xer-001.NG3:** No new graph invariant. Core adds no connectivity, reachability, root, or readiness guard; those are caller and hook policy (SPEC-001.P7, and `3773y` §2).
- **PROP-Xer-001.NG4:** No change to `supersede!` rewiring semantics. Replacement stays caller-composed (PROP-Xer-001.C6).

## PROP-Xer-001.P4 Approach

The work is design clauses C1–C9. C1 adds the `:remove` op to the batch edge grammar; C2 fixes its identity and failure behavior; C3 keeps submitted-order execution and atomicity; C4 converts every edge outcome to a before/after transition; C5 routes those outcomes through the existing hook and event; C6 records that replacement stays caller-composed; C7 fixes the invariant boundary; C8 lists spec and doc deltas; C9 records what is deliberately not built. Each clause names the exact call site it changes.

## PROP-Xer-001.C1 — the `:remove` op

Extend the batch `:edges` grammar (`normalize-batch-payload!`, `db.clj:1190-1203`) with one closed op beside the shipped `:upsert`:

```clojure
{:op :remove :from <existing-ref> :to <existing-ref> :type <relation>}
```

Require exactly `:op`, `:from`, `:to`, and `:type`. Reject `:attributes` and any unknown key (`require-no-unknown-keys!`, already applied at `db.clj:1193`). Both endpoints are top-level refs bound to existing strands, and neither may be burned in the same payload (the burned-ref check at `db.clj:1224-1229` already covers every edge op). Removal takes newly created refs as endpoints only if a same-batch upsert created the edge earlier in the vector.

Identity is `(from, to, type)` alone. Edge attributes are neither a selector nor a compare-and-set precondition — `:remove` names no attributes and removal never reads them.

## PROP-Xer-001.C2 — exact identity, fail loud on absence

At the op's submitted vector position, load the exact normalized edge row. If it is absent — including a wrong direction or a wrong relation type — fail loudly with structured `ex-data` naming the refs, resolved durable ids, and relation, and roll the transaction back (TEN-003).

Absence is strict, not idempotent. There is no ignore-missing flag. A stale retry that finds the edge already gone fails and forces the caller to reread and reconcile (PROP-Xer-001.Q1). Privileged cutover migrations that want idempotent deletion keep their own lower-level path and are out of this contract.

Removal applies no insertion-only check. Self-edge, DAG, and single-`serves` rules guard writes that add an edge; deleting a row only shrinks the edge set, so it cannot create a dangling endpoint, a self edge, a duplicate identity, a relation cycle, or a second `serves` target. Removing an edge that a privileged raw write left in an invalid state (a raw self-edge) is allowed and reduces the violation.

## PROP-Xer-001.C3 — submitted order and atomicity

Edge ops execute in submitted `:edges` order, each at its vector position, inside the one `jdbc/with-transaction` that already wraps the batch (`alpha.clj:38`, `apply-batch-in-transaction!` `db.clj:1206`). Any op failing — an absent remove, a rejected upsert, a burn conflict, or the pre-commit hook — rolls back every strand, edge, and burn mutation in the payload.

No hidden remove-first phase, and no blanket duplicate-identity rejection. The shipped vector already lets repeated upserts of one identity apply last-writer-wins; ordering `:remove` and `:upsert` of the same identity is a deterministic ordered program, and `:remove`/`:remove` of one identity fails at the second op by exact presence (C2). Phase splitting or duplicate rejection would change shipped edge-vector semantics and belongs to a separate batch-grammar redesign, not to this accretion (`3773y` §3, note `onqzu`).

## PROP-Xer-001.C4 — uniform ordered transition outcome

Every edge outcome becomes one transition shape carrying `:before` and `:after`, replacing the current upsert-only `{:op :upsert ... :edge <row>}` (`db.clj:1248-1258`):

```clojure
{:op <:upsert|:remove> :from <ref> :to <ref> :type <relation>
 :before <edge row or nil> :after <edge row or nil>}
```

- **Upsert:** `:before` is the old row or `nil` when the edge is new; `:after` is the new row.
- **Remove:** `:before` is the removed row; `:after` is `nil`.

Each row is the normalized edge — durable ids, relation, and the full attribute map — never storage JSON (TEN-007). Outcomes stay aligned to input order, so the outcome vector reads back against the submitted `:edges` vector one-to-one.

The same normalized outcomes reach all three boundaries: the `apply!` return value, the `:batch/apply-before-commit` hook context (`:batch/edge-ops`, `alpha.clj:89`), and the `:batch/applied` event (`:batch/edges`, `alpha.clj:100`). This is one shape change threaded through the existing plumbing, not a new channel.

## PROP-Xer-001.C5 — existing hook and event only

The `:batch/apply-before-commit` validation gate runs once after the whole candidate graph is realized (`alpha.clj:41-44`); it now sees remove before-images in its input-aligned `:batch/edge-ops`, and a rejection rolls everything back with no mutation and no event. After commit, `:batch/applied` carries the same input-aligned outcomes in `:batch/edges` (`alpha.clj:93-100`). An edge-only batch emits no per-strand fanout, as today.

A hook that wants a projected view folds the before/after deltas over a committed predecessor slice it loads itself. Core does not ship a reusable graph projector: correctness depends on snapshot completeness and on the caller's view semantics (adjacency versus rooted subgraph versus a named query), and a removed edge may have connected data the hook never loaded. If repeated userland projection proves painful, a narrowly shaped relation-subgraph projector is a separate later design (note `to8k1`), not part of this contract.

No `:edge/removed` event is minted. Post-commit enqueue is bounded and not transactional: queue saturation can leave a committed deletion with a caller-visible enqueue failure, and a handler failure cannot roll the deletion back. Results and events support immediate observation, not a durable audit log; recovery and sign-off records belong to caller and domain workflows (PHILOSOPHY, "The work record is not the source of truth").

## PROP-Xer-001.C6 — replacement stays caller-composed

There is no generic replace or rewire op. A caller that wants `b --serves--> c` to become `x --serves--> c` submits `:remove b->c` then `:upsert x->c` in one batch; the two commit together or not at all.

`supersede!` is not graph substitution and is untouched here: it writes `replacement --supersedes--> old`, marks `old` replaced, and rewires only incoming `depends-on` edges to the replacement (`db.clj:816` and its region). It does not move `old`'s outgoing edges, so a caller that needs the old outgoing edge retired composes the exact `:remove` plus the new `:upsert` explicitly. Direction, relation, attributes, and any required reachability are the caller's choice.

## PROP-Xer-001.C7 — invariant boundary

Core promises, across blessed edge writes: both endpoint strands exist; edge identity is exactly `(from, to, type)` and schema-unique; relation names satisfy the open grammar; attributes are JSON object maps; self-edges are rejected for every relation; every declared acyclic relation is independently a DAG; and `serves` has at most one outgoing target per source (SPEC-001.P5).

Core does not promise whole-graph acyclicity, connectedness, one root, stable roots, reachability, minimum degree, parent cardinality, lineage completeness, preserved readiness, or durable audit (`3773y` §2). Exact removal may legally strand a node, create a root, hide a subtree from a selected rooted view, detach a note's sole linkage, erase a `serves` delegation meaning, leave a `supersedes` state without lineage, or make blocked work ready. None of those is engine corruption; they are domain policy owned by hooks and callers. A registered hook is the place to require approval before a `depends-on` removal can unblock work, or to require a replacement pair before a `serves` retire.

## PROP-Xer-001.C8 — spec and doc deltas

- **`devflow/specs/strand-model.md` SPEC-001.P6** (batch, ~L74-76): the supported edge ops become `{:op :upsert ...}` and `{:op :remove :from :to :type}`; state that `:remove` requires exact identity, forbids `:attributes` and unknown keys, and fails loudly when the edge is absent. Rewrite the batch-result edge sentence (L76) to the uniform before/after transition outcome (PROP-Xer-001.C4).
- **`devflow/specs/alpha-surface.md` SPEC-005.C2:** no new namespace — `batch` is already blessed and this accretes within its subnamespace. No edit unless the reviewer wants the outcome-shape change called out; the change is source-visible through the strand-model delta.
- **`src/skein/api/batch/alpha.clj`** docstring for `apply!` (`alpha.clj:21-31`): note the two edge ops and the transition outcome shape; regenerate `docs/api/batch.api.md` with `make api-docs`.
- **`docs/reference.md`** (batch region, ~L680-687) and **`docs/tutorial.md`** (batch example, ~L276): add a one-line `:remove` mention where each already shows `:op :upsert`.

## PROP-Xer-001.C9 — deliberately not built

CLI verb, `:edge/removed` event, standalone one-edge helper, generic replace/rewire op, nested batch stages, variadic `apply!` arity, graph projector, edge tombstone, durable audit or outbox, and any connectivity, reachability, root, or readiness invariant. Each was weighed and declined by the decision (`xh4nd`) and council synthesis (`3773y` §6); reopening one is a new decision, not an implementation choice.

## PROP-Xer-001.P5 Ordered-transition cases

- **PROP-Xer-001.T1 — `serves` swap.** `:remove old-serves` then `:upsert new-serves` in one batch. The single-`serves` rule (SPEC-001.P5) rejects a second outgoing target, so the remove must precede the upsert; the reverse order fails the cardinality check and rolls back with the committed prestate byte-identical.
- **PROP-Xer-001.T2 — DAG reversal.** `:remove a->b` then `:upsert b->a` on a declared acyclic relation. The remove must precede the upsert or the acyclicity check rejects the transient cycle. Submitted order is what makes the legal program commit and the illegal order roll back.

## PROP-Xer-001.P6 Proof obligations

- **PROP-Xer-001.PO1 — monotone graph:** one present exact removal yields the graph minus only that row, leaves strands and other edges unchanged, and preserves endpoint existence, no-self, relation-local DAG, identity uniqueness, and `serves` cardinality.
- **PROP-Xer-001.PO2 — exactness and shape matrix:** absent edge, wrong direction, wrong type, malformed shape, extra keys, `:attributes` present, and an unknown/new/burned ref each fail atomically with structured `ex-data`; removing a raw self-edge is allowed.
- **PROP-Xer-001.PO3 — ordered transitions:** T1 and T2 succeed in the legal order and fail in the reversed order with an unchanged committed prestate.
- **PROP-Xer-001.PO4 — repeated identity:** upsert/upsert keeps shipped last-writer behavior; remove/remove fails at the second op by exact presence and rolls back; remove/upsert and upsert/remove of one identity are deterministic ordered outcomes.
- **PROP-Xer-001.PO5 — atomic multi-op:** removing three obsolete `depends-on` edges is all-or-none (the `4cdsu` case); remove-old plus upsert-distinct-replacement commits together; a later failure restores the whole graph.
- **PROP-Xer-001.PO6 — uniform outcome:** upsert and remove both return the before/after transition, aligned to input order, at the return value, the `:batch/apply-before-commit` context, and the `:batch/applied` event; the hook can veto a removal with no mutation and no event.
- **PROP-Xer-001.PO7 — semantic locks:** a `depends-on` removal that makes work ready and a `parent-of` removal that creates a root or hides a subtree both commit without core rejection.

## PROP-Xer-001.P7 Validation gates

- `make build`
- `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (focused batch namespaces green cold first)
- `(cd cli && go test ./...)`
- `clojure -M:smoke`
- `make fmt-check lint reflect-check docs-check` (held at zero findings)
- `make api-docs` — clean regen; `git status --short` shows only the expected `docs/api/batch.api.md` change
- `git status --short` clean of generated SQLite and runtime metadata artifacts

## PROP-Xer-001.P8 Done-when

- **PROP-Xer-001.DW1:** `{:op :remove :from :to :type}` is a supported batch edge op, exact-identity, closed-shape, fail-loud on absence (C1, C2).
- **PROP-Xer-001.DW2:** every edge outcome is the before/after transition (C4), reaching the return value, the pre-commit hook, and the `:batch/applied` event identically (C5).
- **PROP-Xer-001.DW3:** no surface beyond the one op ships (C9); the strand-model spec and batch docstring reflect the grammar and outcome change (C8).
- **PROP-Xer-001.DW4:** the P6 proof obligations pass and the P7 gates are green.

## PROP-Xer-001.P9 Open questions

- **PROP-Xer-001.Q1 (contract, resolved by decision) — absent-edge behavior.** Strict fail-loud, not idempotent (C2, TEN-003). Implementation confirms the retry story: a stale remover that finds the edge gone fails and rereads; there is no ignore-missing flag in v1.
- **PROP-Xer-001.Q2 (implementation, open) — contention versus absence.** SQLite `IMMEDIATE` serialization means a later remover normally observes absence, but bounded lock waiting may instead surface contention. Implementation must decide whether a contention timeout reports a distinct error from a clean absence; the contract does not promise "absent" under unbounded contention.
- **PROP-Xer-001.Q3 (implementation, open) — outcome key placement.** C4 fixes the transition shape (`:before`/`:after`); implementation confirms the exact key set on the normalized row and that `:batch/edge-ops` (hook) and `:batch/edges` (event) both carry it, replacing the current `:edge` key without a compatibility alias (TEN-000@1).
