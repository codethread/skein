# Task 1: Reserved help tokens and pre-hook help alias

**Document ID:** `TASK-OpHelp-001`

## TASK-OpHelp-001.P1 Scope

Type: AFK

Implement the help invocation alias per SPEC-004-D005 and the reserved tokens per SPEC-003-D005 (devflow/feat/op-help-convention/specs/).

## TASK-OpHelp-001.P2 Must implement exactly

- **TASK-OpHelp-001.MI1:** `skein.api.cli.alpha`: `validate-subcommands!` rejects subcommand names `help`, `-h`, `--help` with a structured `fail!` (reason + op + offending name). Enforced at all existing seams for free (parse/explain/registration).
- **TASK-OpHelp-001.MI2:** Help alias resolved **before lifecycle hook gating**: when the resolved op entry's arg-spec declares `:subcommands`, argv is exactly one of `["help"]`, `["-h"]`, `["--help"]`, and the envelope has no payloads, return the op's `op-detail` projection as a normal successful result; the handler never runs and the op's hooks never fire (SPEC-004-D005.C1/C1a). Find the seam where hook-class gating happens on the socket invoke path (`src/skein/core/weaver/socket.clj` ~168-179, 260-267 — resolved entry, argv, and payloads are all in scope there); implement the alias check with a small pure helper in `skein.api.weaver.alpha` so `op!`-direct callers (tests, embedded) can share it.
- **TASK-OpHelp-001.MI3:** Tests (`test/skein/weaver_test.clj` or the socket test namespace as appropriate): alias hit for each of the three tokens on a `:subcommands` op (result equals `help <op>` detail); alias miss → normal loud parse errors for: extra argv after the token, payloads attached, flat-arg-spec op, raw-envelope op; missing/unknown subcommand still loud; a mutating-class hook does NOT fire on an alias invocation but DOES fire on a real subcommand invocation; registering a spec with subcommand name "help" fails loudly.

## TASK-OpHelp-001.P3 Done when

- **TASK-OpHelp-001.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green.

## TASK-OpHelp-001.P4 Out of scope

- **TASK-OpHelp-001.OS1:** Go changes (task 2), kanban migration (task 3), root spec merges (finish).

## TASK-OpHelp-001.P5 References

- **TASK-OpHelp-001.REF1:** specs/daemon-runtime.delta.md, specs/repl-api.delta.md, plan A1/A2, shipped SPEC-003.C64/C65 + SPEC-004.C63d in devflow/specs/.
