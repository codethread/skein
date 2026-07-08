# Runtime Transformation Primitives Proposal

**Document ID:** `RTP-PROP-001` **Status:** Draft **Date:** 2026-06-26 **Related PRD:** [Runtime Transformations PRD](../../prd/runtime-transformations.md) **Relevant root specs:** [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Daemon Runtime](../../specs/daemon-runtime.md), [Task Model](../../specs/strand-model.md) **Prerequisite shipped:** [Runtime Library Workspace](../../archive/26-06-26__runtime-library-workspace/) — config-dir libraries, `libs.edn`, daemon-side `atom.libs.alpha/sync!` and `use!`

## RTP-PROP-001.P1 Problem

Atom now has the right extension substrate: selected config-dir worlds, trusted `init.clj`, approved local roots, daemon-side library sync/use, named query registry, and a thin Go CLI that consumes daemon behavior. The next gap is a small blessed transformation layer that lets trusted Clojure code efficiently compose task queries, graph expansion, batch hydration, and read-only views without growing the CLI or forcing users into raw SQL.

The Runtime Transformations PRD defines the desired architecture: durable task facts in SQLite; SQL-backed EDN query DSL for candidate selection; set-oriented graph/batch primitives for efficient expansion; and trusted Clojure views for orchestration and shaping. That model now needs a first buildable slice.

## RTP-PROP-001.P2 Goals

- **RTP-PROP-001.G1:** Add blessed source-visible alpha namespace(s) for runtime transformation primitives inside the Atom repo.
- **RTP-PROP-001.G2:** Provide set-oriented helpers for query id selection, feature-root traversal, DAG expansion, and batch hydration.
- **RTP-PROP-001.G3:** Provide a daemon-memory read-only view registry for trusted Clojure views.
- **RTP-PROP-001.G4:** Make fresh `todo init` worlds expose the blessed transformation helpers through generated startup config while preserving user editability.
- **RTP-PROP-001.G5:** Keep the public CLI thin; no new query authoring, library loading, or view invocation command unless explicitly scoped later.
- **RTP-PROP-001.G6:** Use the shipped runtime library workspace model rather than reviving plugin-directory APIs or adding another extension mechanism.

## RTP-PROP-001.P3 Non-goals

- **RTP-PROP-001.NG1:** Do not add public CLI view invocation in this feature.
- **RTP-PROP-001.NG2:** Do not persist views, functions, graph caches, or derived data in SQLite.
- **RTP-PROP-001.NG3:** Do not expose raw SQL as a public CLI surface.
- **RTP-PROP-001.NG4:** Do not build a full graph/query language beyond the existing EDN query DSL plus small Clojure helpers.
- **RTP-PROP-001.NG5:** Do not add package/source fetching, plugin-directory loading, or untrusted code execution.
- **RTP-PROP-001.NG6:** Do not solve typed CLI params beyond the current string-valued `--param key=value` boundary.

## RTP-PROP-001.P4 Proposed scope

- **RTP-PROP-001.S1:** Add blessed alpha runtime namespaces `atom.graph.alpha` and `atom.views.alpha` with the MVP split frozen in the feature plan.
- **RTP-PROP-001.S2:** Add query primitives for returning stably ordered ids from an ad hoc query definition or daemon-registered query name.
- **RTP-PROP-001.S3:** Add batch hydration by ids with first-occurrence input ordering, duplicate collapse, empty input support, and loud failure for missing ids.
- **RTP-PROP-001.S4:** Add `parent-of` graph primitives sufficient for the flagship PRD flow: active seed ids -> feature root ids -> feature DAG/subgraph.
- **RTP-PROP-001.S5:** Add a read-only daemon-memory view registry for named, fully qualified Clojure function symbols, plus trusted Clojure helpers such as `register-view!`, `view!`, and introspection.
- **RTP-PROP-001.S6:** Update generated config-dir startup files from `todo init` so new users get an editable template requiring the blessed transformation helper namespace(s). Built-in namespaces are already on the Atom classpath; do not use `atom.libs.alpha/use!` merely to load shipped namespaces unless an explicit install side effect is introduced.
- **RTP-PROP-001.S7:** Adapt useful patterns from the existing local helper library where appropriate, especially install-style helper registration; keep personal owner-specific behavior out of shipped defaults.

## RTP-PROP-001.P5 Open questions

- **RTP-PROP-001.Q1:** Should a later feature add CLI view invocation after EDN/JSON/human output contracts are designed?
