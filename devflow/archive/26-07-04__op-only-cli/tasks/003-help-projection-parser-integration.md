# Task 3: Parser-op integration and core help registry projection

**Document ID:** `TASK-Ooc-003`

## TASK-Ooc-003.P1 Scope

Type: AFK

Wire task 2's parser into op invocation and upgrade the core `help` op to a full registry projection per SPEC-004-D003.C6a/C7 and SPEC-002-D004.C12.

## TASK-Ooc-003.P2 Must implement exactly

- **TASK-Ooc-003.MI1:** In `op!` (`src/skein/api/weaver/alpha.clj`): when the resolved op entry carries `:arg-spec`, run `skein.api.cli.alpha` parsing + payload-ref resolution against `:op/argv` and the envelope before the handler; supply the result as `:op/args` in handler context. Parse failures throw the parser's structured error and never call the handler. Ops without `:arg-spec` get the raw context unchanged (raw-envelope ops), including the raw payloads map.
- **TASK-Ooc-003.MI2:** Replace `op-help-handler` with a registry projection registered as core `help` in `register-built-in-ops!` via the public `register-op!` path (metadata: `:hook-class :read`, `:doc` set): `strand op help` (current invocation path) with no argv returns all entries — name, doc, provenance, stream?, deadline-class, hook-class — sorted by name; with one positional op-name argv returns that op's full detail including the parser `explain` rendering of its arg-spec (or a raw-envelope marker when absent); unknown names reuse the existing loud not-found error carrying available names.
- **TASK-Ooc-003.MI3:** `help` itself declares an arg-spec (optional positional `op`), making it the first parser-consuming op and proving MI1 end-to-end.
- **TASK-Ooc-003.MI4:** Tests: parsed `:op/args` delivery, parse-failure short-circuit, raw-envelope passthrough (payloads visible), help listing shape, help detail shape for arg-spec and raw ops, help unknown-name failure.

## TASK-Ooc-003.P3 Done when

- **TASK-Ooc-003.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green.
- **TASK-Ooc-003.DW2:** In a disposable `--workspace` world with a locally built current-main CLI (old CLI still works at this point), `strand op help` and `strand op help help` return the new projection shapes.

## TASK-Ooc-003.P4 Out of scope

- **TASK-Ooc-003.OS1:** Batteries ops, socket/protocol changes, Go changes, replacing the `strand op` invocation path.

## TASK-Ooc-003.P5 References

- **TASK-Ooc-003.REF1:** SPEC-004-D003.C6a/C7 (daemon-runtime delta), SPEC-002-D004.C12 (cli delta), RFC-019.D6, plan A2/A3 and TC1 (disposable-workspace rules; never touch the canonical weaver).
