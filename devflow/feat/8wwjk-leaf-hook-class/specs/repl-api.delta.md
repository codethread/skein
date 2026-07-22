# repl-api delta for 8wwjk-leaf-hook-class

**Document ID:** `DELTA-Lhc-001`
**Root spec:** [repl-api.md](../../../specs/repl-api.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Draft
**Last Updated:** 2026-07-22

## DELTA-Lhc-001.P1 Summary

Subcommand arg-specs nest to arbitrary depth with one uniform fractal node shape,
and `:hook-class`/`:deadline-class` become mandatory leaf-node metadata declared in
the arg-spec, replacing the op-wide classes. Breaking under TEN-000@1; loud at
registration under TEN-003.

## DELTA-Lhc-001.P2 Contract changes

- **DELTA-Lhc-001.CC1 (supersedes SPEC-003.C64 one-level cap):** An arg-spec node
  declaring `:subcommands` maps subcommand names to nested nodes of the **same
  shape**, at any depth. A node declares either `:subcommands` (interior node) or
  an invocation surface (`:flags`/`:positionals` — leaf node), never both; an
  empty `:subcommands {}` is invalid (every op must reach at least one invocable
  leaf). The shared structural validator enforces these rules recursively, and
  reserved names (`help`, `-h`, `--help`, the `subcommand` arg name) are rejected
  at **every** level.
- **DELTA-Lhc-001.CC2 (leaf classes; amends SPEC-003.C64):** Every leaf node
  declares `:hook-class` (`:read`|`:mutating`) and `:deadline-class`
  (`:standard`|`:unbounded`) as mandatory node metadata, peers of `:doc`/`:flags`/
  `:positionals`/`:annotations`. Interior nodes may not declare either (loud
  structural failure): interior nodes are never invocable, so a class there is
  dead metadata. A leaf of a stream-class op must declare
  `:deadline-class :unbounded` — streams stay explicitly unbounded and no
  streaming-timeout machinery is introduced.
- **DELTA-Lhc-001.CC3 (amends SPEC-003.C65 routing):** Parsing a subcommand
  arg-spec routes on the first argv token **recursively**: each token selects a
  child node until a leaf is reached, and the remaining argv parses against the
  leaf. The parsed map merges the leaf result with `:subcommand` bound to the
  **full path as a vector of name strings** (e.g. `["task" "add"]`), always a
  vector at every depth. Missing or unknown tokens at any level throw loud
  structured errors carrying the op name, the node path walked so far, the
  offending token (or its absence), and the available child names. Payload
  references and `:parse` declarations apply unchanged inside every nested level.
- **DELTA-Lhc-001.CC4 (amends SPEC-003.C60b returns routing):** A subcommand op's
  `:returns` mirrors the arg-spec tree: an interior return node is
  `{:subcommands {<name> <return-node> ...}}` and a leaf return node is a return
  case, with names matching the arg-spec **exactly at every level**. The
  return-shape validator, `explain`, and `check!` recurse accordingly; `check!`'s
  selection context takes the full subcommand path.
- **DELTA-Lhc-001.CC5 (amends SPEC-003.C66/C67 projection):** `hook-class` and
  `deadline-class` leave the envelope's op-wide facts and become **node keys**
  with defined semantics for every node kind: populated on invocable leaf nodes
  (a flat or raw-envelope op's root node **is** its leaf), `null` on interior
  nodes and on subcommand-op roots. Catalog summary nodes follow the same rule
  (populated only when the summary node is itself the leaf). Per-node closed
  `:annotations` sub-maps are honored at every depth, and the unconditional
  glossary-ref existence check walks **all** depths — a deep `failure-modes`
  name must not evade registration validation.
- **DELTA-Lhc-001.CC6 (rewrites SPEC-003.C68):** Help paths are live to the
  arg-spec's declared depth: `strand help <op> <verb> [<verb> ...]` slices the
  envelope to that node, same fractal shape, failing loudly with the available
  children on a bad path. Arbitrary-depth recursion is now proven by live ops,
  not only the synthetic renderer test.
- **DELTA-Lhc-001.CC7 (amends SPEC-003.C28):** `check-op-return!`'s subcommand
  context is the full path (vector), aligned with CC3/CC4.

## DELTA-Lhc-001.P3 Design decisions

### DELTA-Lhc-001.D1 Path values are vectors, never joined strings

- **Decision:** `:subcommand`, error-context paths, and `check-op-return!`
  contexts carry the path as a vector of name strings at every depth.
- **Rationale:** TEN-001 — raw structured data over display strings; consumers
  join for display (the dispatch label joins with spaces per SPEC-004.C63b).
- **Rejected:** Joined strings (lossy split on names containing spaces is
  impossible to rule out structurally); scalar-at-depth-1 with vectors deeper
  (two shapes for one concept).

### DELTA-Lhc-001.D2 Leaf-only, mandatory, no derivation

- **Decision:** Classes exist only on leaves; nothing is derived onto interior
  nodes, op roots, or the envelope.
- **Rationale:** Nothing invocable resolves to a non-leaf, so any non-leaf class
  is either dead metadata or a silently-wrong summary; mandatory declaration
  forces the read/write audit the old default let registrants skip (TEN-003).
- **Rejected:** Op-level fallback with leaf override (decision note mnl04 —
  superseded: keeps the misclassification hazard); derived op-wide superclass
  (compat crutch for consumers we own and are sweeping anyway).

## DELTA-Lhc-001.P4 Open questions

- None. (The path-shape question from PROP-Lhc-001.Q1 is settled by D1; the
  design thin-slice may reopen it only with evidence from the applied seam.)
