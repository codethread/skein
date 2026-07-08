# CLI Surface Delta: Live Weaver REPL And Runtime Loader

**Document ID:** `LWRL-DELTA-CLI-001` **Status:** Merged **Last Updated:** 2026-07-01 **Updates:** [CLI Surface](../../../specs/cli.md) **Feature:** [live-weaver-repl-and-runtime-loader](../proposal.md)

## LWRL-DELTA-CLI-001.P1 Summary

This delta changes `strand weaver repl` from launching a connected helper JVM to attaching to the selected running weaver nREPL. It also updates generated config language to use the renamed privileged runtime loader/config helper namespace.

## LWRL-DELTA-CLI-001.P2 Contract changes

- **LWRL-DELTA-CLI-001.C1:** Replace `SPEC-002.C17`: `weaver repl` asks `mill` to resolve the selected world, verify that world's weaver is running, and return enough nREPL metadata to attach directly to the weaver process. The default path may launch a thin nREPL attach client process, but it must evaluate user forms only in the selected weaver JVM and must not use the fixed API bridge as the user's REPL.
- **LWRL-DELTA-CLI-001.C2:** Replace `SPEC-002.C18`: `weaver repl --stdin` sends stdin forms to the selected weaver nREPL for direct evaluation in the weaver JVM, prints one direct Clojure result per top-level form, and exits non-zero on read/eval/transport failure. Read/eval state belongs to the weaver session, not a helper JVM namespace.
- **LWRL-DELTA-CLI-001.C3:** `mill` does not proxy nREPL traffic. It only resolves the selected world, verifies liveness/identity, resolves source for a thin attach client when needed, and returns connection context. The CLI attach client connects directly to the weaver endpoint.
- **LWRL-DELTA-CLI-001.C4:** If an explicit helper/client mode is retained, it must be opt-in and named to avoid confusing it with live process attachment. The default `strand weaver repl` behavior is direct live attach.
- **LWRL-DELTA-CLI-001.C5:** Update `SPEC-002.C14a` generated `init.clj` contract to require the renamed privileged loader namespace, expected as `(require '[skein.runtime.alpha :as runtime])` followed by `(runtime/sync!)`, while allowing existing config using `skein.libs.alpha` to continue during alpha compatibility.
- **LWRL-DELTA-CLI-001.C6:** Update CLI help/docs copy so runtime loader/config helpers are not described as userland libraries or package commands. The public CLI still has no package/library activation commands.

## LWRL-DELTA-CLI-001.P3 Non-goals

- **LWRL-DELTA-CLI-001.NG1:** This delta does not add arbitrary EDN query parsing to public strand commands.
- **LWRL-DELTA-CLI-001.NG2:** This delta does not add CLI commands for registering hooks, views, patterns, or runtime modules.
