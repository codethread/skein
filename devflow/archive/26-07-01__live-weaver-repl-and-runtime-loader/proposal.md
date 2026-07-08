# Live Weaver REPL And Runtime Loader Proposal

**Document ID:** `LWRL-PROP-001` **Last Updated:** 2026-06-30 **Related RFCs:** None **Related root specs:** [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [CLI Surface](../../specs/cli.md)

## LWRL-PROP-001.P1 Problem

`strand weaver repl` currently launches a helper JVM that routes selected helper calls over nREPL API forms into the running weaver. That is useful for stable data-first operations, but it does not give users the core Clojure experience the system philosophy promises: attaching to the live weaver process, evaluating forms there, inspecting loaded namespaces, reading runtime atoms, debugging private state, and experimenting with trusted runtime code in place.

The runtime-library surface has a second naming problem. `skein.libs.alpha` is presented beside user/community libraries, but it is actually privileged core loader/config machinery for approved roots, config reload, and module-use state. That makes the extension story feel more userland than it is and obscures the real boundary between shipped runtime helpers and author-written libraries.

## LWRL-PROP-001.P2 Goals

- **LWRL-PROP-001.G1:** Make `strand weaver repl` attach to the selected running weaver nREPL as a live process REPL by default.
- **LWRL-PROP-001.G2:** Preserve a deliberate API-client Clojure path for non-interactive clients/tests that need stable weaver API calls without raw process authority.
- **LWRL-PROP-001.G3:** Preload or make available ergonomic helper namespaces inside the weaver process so direct REPL users keep the compact strand/query/config workflow.
- **LWRL-PROP-001.G4:** Rename or reframe privileged library/config loader helpers so they are explicitly core runtime loader/config APIs, not examples of ordinary userland libraries.
- **LWRL-PROP-001.G5:** Keep actual userland libraries demonstrably userland: normal trusted Clojure loaded through config, approved roots, or direct live REPL experimentation.
- **LWRL-PROP-001.G6:** Update specs and docs so the product story matches the implementation boundary.
- **LWRL-PROP-001.G7:** Refactor shipped `libs/` examples so they use the same authorable surface available to user/community libraries and do not proxy privileged loader behavior.

## LWRL-PROP-001.P3 Non-goals

- **LWRL-PROP-001.NG1:** No remote REPL authentication, authorization, sandboxing, or multi-user security model.
- **LWRL-PROP-001.NG2:** No package registry, dependency solver, source fetcher, or plugin command surface.
- **LWRL-PROP-001.NG3:** No removal of the public JSON CLI or mill router.
- **LWRL-PROP-001.NG4:** No guarantee that arbitrary direct REPL mutations preserve blessed API invariants; trusted users keep that responsibility.
- **LWRL-PROP-001.NG5:** No broad namespace reshuffle beyond the runtime loader/config helper boundary needed for this feature.

## LWRL-PROP-001.P4 Proposed scope

- **LWRL-PROP-001.S1:** Change `strand weaver repl` and `strand weaver repl --stdin` to evaluate directly in the selected weaver process through its nREPL endpoint.
- **LWRL-PROP-001.S2:** Keep `mill` responsible for selected-world resolution, liveness checks, thin attach-client source context, and returning nREPL metadata; mill does not proxy nREPL.
- **LWRL-PROP-001.S3:** Split direct live REPL behavior from API-client behavior in specs and code. The helper/client bridge remains available for Clojure clients and tests, but is not the default interactive REPL experience.
- **LWRL-PROP-001.S4:** Introduce `skein.runtime.alpha` as the blessed path replacing `skein.libs.alpha` for approved roots, sync, reload, and module-use introspection. Existing `skein.libs.alpha` remains temporarily as a compatibility alias during alpha migration.
- **LWRL-PROP-001.S5:** Update generated config templates, docs, and examples to use the new runtime loader/config namespace and describe shipped `skein.*.alpha` helpers as privileged built-in helpers, not user/community libraries.
- **LWRL-PROP-001.S6:** Move or rewrite special-behavior code currently presented as `libs` so core-owned loader/config behavior lives under core runtime namespaces, while `libs` contains only authorable examples or user/community library workspace content.
- **LWRL-PROP-001.S7:** Add tests covering direct live REPL evaluation, live introspection of `skein.weaver.runtime/current-runtime`, helper availability inside the weaver, stdin behavior, loader namespace compatibility, and one shipped library/example that does not call privileged loader internals.

## LWRL-PROP-001.P5 Open questions

- **LWRL-PROP-001.Q1:** Whether to expose the old helper-JVM behavior as an explicit CLI mode such as `strand weaver repl --client`, or leave it only as lower-level Clojure client/test APIs.
