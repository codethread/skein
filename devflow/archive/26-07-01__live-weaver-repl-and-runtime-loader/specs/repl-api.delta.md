# REPL API Delta: Live Weaver REPL And Runtime Loader

**Document ID:** `LWRL-DELTA-REPL-001` **Status:** Merged **Last Updated:** 2026-07-01 **Updates:** [REPL API](../../../specs/repl-api.md) **Feature:** [live-weaver-repl-and-runtime-loader](../proposal.md)

## LWRL-DELTA-REPL-001.P1 Summary

This delta changes the primary `strand weaver repl` contract from a connected helper JVM to a direct live nREPL session inside the selected running weaver process, and reframes privileged loader/config helpers away from the ordinary userland library story.

## LWRL-DELTA-REPL-001.P2 Contract changes

- **LWRL-DELTA-REPL-001.C1:** Preserve the `SPEC-003.C1` `connect!` contract for explicit Clojure client/test workflows. `connect!` continues to select one active weaver connection by selected config-dir and optional state metadata, never by database path.
- **LWRL-DELTA-REPL-001.C2:** Replace `SPEC-003.C2` with a direct-live REPL contract: `strand weaver repl` requires a running `mill`, asks it to resolve the selected world and verify that world's weaver is running, then attaches the user's terminal to the selected weaver nREPL endpoint. User forms are evaluated in the weaver JVM. The CLI may launch a thin attach client process to speak nREPL, but that process must not evaluate user forms locally or route them through the fixed API bridge.
- **LWRL-DELTA-REPL-001.C3:** The live REPL starts in a useful weaver-side namespace, expected to make `skein.repl` helpers available without hiding that users are inside the weaver process. Users may inspect `skein.weaver.runtime/current-runtime`, switch namespaces, require weaver-loaded code, and evaluate arbitrary trusted forms with normal Clojure semantics.
- **LWRL-DELTA-REPL-001.C4:** Replace `SPEC-003.C3` with direct stdin semantics: `strand weaver repl --stdin` evaluates top-level forms in the selected weaver process, prints one direct Clojure result per top-level form, and exits non-zero on read/eval/transport failure.
- **LWRL-DELTA-REPL-001.C5:** `skein.repl/connect!`, `connected-config-dir`, and connected helper routing remain available only for explicit API-client/test workflows. They are no longer described as the default interactive REPL path.
- **LWRL-DELTA-REPL-001.C6:** `skein.repl` helper functions must work when evaluated directly inside the active weaver JVM by dispatching through `@skein.weaver.runtime/current-runtime` without requiring a connected helper client.
- **LWRL-DELTA-REPL-001.C7:** Replace `SPEC-003.C16` through `SPEC-003.C19` and `SPEC-003.P5` naming with `skein.runtime.alpha` as the privileged runtime loader/config helper namespace. It exposes approved-root config inspection, approved-root sync, config reload, module activation, and module-use introspection. It is a built-in core helper namespace, not an ordinary user/community library.
- **LWRL-DELTA-REPL-001.C8:** `skein.libs.alpha` remains as an alpha compatibility alias for the `skein.runtime.alpha` loader/config functions, but new generated config, docs, and examples use `skein.runtime.alpha`.
- **LWRL-DELTA-REPL-001.C9:** Built-in helper namespaces such as `skein.graph.alpha`, `skein.views.alpha`, `skein.patterns.alpha`, `skein.events.alpha`, `skein.hooks.alpha`, `skein.batch.alpha`, and `skein.runtime.alpha` are documented as privileged shipped helpers. User/community libraries are code loaded through trusted config, approved roots, or direct live REPL work.
- **LWRL-DELTA-REPL-001.C10:** Namespaces presented as libraries or examples must be authorable by users through the same trusted Clojure surface. They may depend on documented built-in helpers, but must not be wrappers around privileged loader/config operations unless they are explicitly marked deprecated compatibility shims.

## LWRL-DELTA-REPL-001.P3 Non-goals

- **LWRL-DELTA-REPL-001.NG1:** This delta does not add sandboxing or restrict arbitrary direct REPL evaluation.
- **LWRL-DELTA-REPL-001.NG2:** This delta does not remove lower-level Clojure client APIs used by tests or tools.
- **LWRL-DELTA-REPL-001.NG3:** This delta does not add package installation commands or dependency fetching.
