# CLI Surface Delta: Runtime Transformation Primitives

**Document ID:** `SPEC-002-D003` **Status:** Merged into root spec **Base Spec:** [CLI Surface](../../../specs/cli.md) **Last Updated:** 2026-06-26

## SPEC-002-D003.P1 Changed contracts

- **SPEC-002-D003.C1:** `todo init` generated startup files may require the shipped runtime transformation helper namespaces `atom.graph.alpha` and `atom.views.alpha` through normal trusted config-dir `init.clj` code as an editable template. Built-in shipped namespaces do not require `libs.edn` approval or `atom.libs.alpha/use!` activation unless a feature defines explicit install side effects.
- **SPEC-002-D003.C2:** The generated files remain user-owned and editable; `todo init` must not overwrite existing user files.

## SPEC-002-D003.P2 Unchanged contracts

- **SPEC-002-D003.U1:** No public CLI view command is added in this feature.
- **SPEC-002-D003.U2:** The CLI does not load view/query/library files and does not expose runtime transformation registry mutation/listing commands.
- **SPEC-002-D003.U3:** Existing `list --query` and `ready --query` named-query consumption remains the public CLI query path.
- **SPEC-002-D003.U4:** Public CLI machine output remains JSON-only.
