# TENETS delta for edge relation families

**Document ID:** `ERF-DELTA-005` **Root document:** [TENETS.md](../../TENETS.md) **Feature:** [proposal.md](./proposal.md) **Status:** Merged **Last Updated:** 2026-06-29

## ERF-DELTA-005.P1 Summary

This delta stages the TEN-005 rewrite required by edge relation families. The current tenet asserts a whole task graph DAG; the feature narrows that guarantee to declared structural relations (`depends-on`, `parent-of`, `supersedes`, and user-declared acyclic relations) so annotation edges may be cyclic.

## ERF-DELTA-005.P2 Proposed TEN-005 replacement

> **TEN-005: Declared structural relations are DAGs.** The engine guarantees each declared acyclic relation is independently acyclic, and every engine traversal walks exactly one such relation or is explicitly cycle-aware. Annotation edges carry no acyclicity guarantee and may form cycles; consumers must not assume whole-graph acyclicity.

## ERF-DELTA-005.P3 Contract changes

- **ERF-DELTA-005.CC1:** Replace the current global statement that task graphs are DAGs with a relation-scoped structural guarantee over declared acyclic relations, including the shipped `depends-on`, `parent-of`, and `supersedes` batteries.
- **ERF-DELTA-005.CC2:** Self-edges remain invalid for every relation. Non-self cycles fail only when the written relation is declared acyclic; annotation relation cycles are valid data.
- **ERF-DELTA-005.CC3:** Engine traversal code must either walk one declared acyclic relation or document explicit cycle-aware behavior.
- **ERF-DELTA-005.CC4:** Agents and userland code must not infer a whole-graph topological order from mixed relation edges.

## ERF-DELTA-005.P4 Design decision

### ERF-DELTA-005.D1 Preserve the agent-safe property at the relation boundary

- **Decision:** TEN-005 protects traversal over declared structural relations rather than every stored edge.
- **Rationale:** The original tenet's useful property is that planning and hierarchy traversals do not require cycle-specific reasoning. That property still holds when structural relations are DAGs independently, while annotation relations can model legitimate cycles.
- **Rejected:** Keeping the whole-graph DAG requirement or removing the DAG tenet entirely.

## ERF-DELTA-005.P5 Open questions

- **ERF-DELTA-005.Q1:** None for contract scope.
