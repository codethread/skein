# Weaver Runtime Delta: Live Weaver REPL And Runtime Loader

**Document ID:** `LWRL-DELTA-RUNTIME-001` **Status:** Merged **Last Updated:** 2026-07-01 **Updates:** [Weaver Runtime](../../../specs/daemon-runtime.md) **Feature:** [live-weaver-repl-and-runtime-loader](../proposal.md)

## LWRL-DELTA-RUNTIME-001.P1 Summary

This delta clarifies that the weaver nREPL is the primary trusted live process interface, not merely a transport for fixed client API forms, and separates privileged built-in runtime helpers from ordinary userland libraries.

## LWRL-DELTA-RUNTIME-001.P2 Contract changes

- **LWRL-DELTA-RUNTIME-001.C1:** Update `SPEC-004.C2`, `SPEC-004.C19`, and `SPEC-004.C21` to state that nREPL serves two trusted local purposes: direct live weaver REPL sessions and fixed-form Clojure client/API calls. Direct live sessions may evaluate arbitrary trusted code inside the weaver JVM.
- **LWRL-DELTA-RUNTIME-001.C2:** Fixed-form API calls remain the correct path for Clojure client libraries, tests, and automation that want structured operations and stable error translation. They are not the default interactive `strand weaver repl` implementation.
- **LWRL-DELTA-RUNTIME-001.C3:** The weaver should make the compact helper surface usable in-process. Helpers called inside the weaver use the active `current-runtime`; helpers called from explicit client/test contexts may continue to route through `skein.client/call-world`.
- **LWRL-DELTA-RUNTIME-001.C4:** Update `SPEC-004.C6` to say mill owns Skein source checkout resolution for launching the weaver and for launching any thin attach client process used by the CLI to speak nREPL. Direct `strand weaver repl` uses published nREPL metadata for evaluation in the weaver JVM; any attach client is transport/UI only and must not become a second semantic runtime.
- **LWRL-DELTA-RUNTIME-001.C5:** Replace `SPEC-004.C39`, `SPEC-004.C44`, `SPEC-004.C46`, and related text that names `skein.libs.alpha` as a runtime library with `skein.runtime.alpha` as a privileged built-in runtime loader/config helper. It owns approved-root sync, config reload, module activation, and loader/module introspection.
- **LWRL-DELTA-RUNTIME-001.C6:** Keep `libs.edn`, `libs.local.edn`, and selected config-dir `libs/` as user/community library workspace concepts. The configuration file names need not change in this feature; only the privileged helper namespace is renamed/reframed.
- **LWRL-DELTA-RUNTIME-001.C7:** Update `SPEC-004.C40` and `SPEC-004.C50` language so shipped `skein.*.alpha` namespaces are described as built-in privileged helpers loaded from the Skein checkout/classpath. They are source-visible and trusted, but not proof that ordinary user libraries can implement equivalent loader/runtime behavior.
- **LWRL-DELTA-RUNTIME-001.C8:** Direct REPL users may call lower-level namespaces or mutate runtime state. Such usage is trusted and powerful; blessed API paths remain the documented invariant-preserving route for operations that should trigger hooks, events, validation, and normalized return shapes.
- **LWRL-DELTA-RUNTIME-001.C9:** Code under library/example naming must not own privileged runtime loader behavior. Core-owned loader behavior belongs to `skein.runtime.alpha` and lower `skein.weaver.*` implementation namespaces; authorable libraries may call documented helpers and weaver APIs, but should not be the home for special runtime machinery.

## LWRL-DELTA-RUNTIME-001.P3 Non-goals

- **LWRL-DELTA-RUNTIME-001.NG1:** This delta does not make nREPL remote-safe or authenticated.
- **LWRL-DELTA-RUNTIME-001.NG2:** This delta does not remove JSON socket routing for the public CLI.
- **LWRL-DELTA-RUNTIME-001.NG3:** This delta does not rename `libs.edn` or the selected config-dir `libs/` directory.
