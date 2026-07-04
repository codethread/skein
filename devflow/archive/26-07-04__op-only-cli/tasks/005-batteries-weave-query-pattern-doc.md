# Task 5: Batteries weave, query, pattern ops and contract doc

**Document ID:** `TASK-Ooc-005`

## TASK-Ooc-005.P1 Scope

Type: AFK

Complete the batteries surface in `spools/src/skein/spools/batteries.clj`: `weave`, `query`, and `pattern` ops; then write the contract doc `spools/batteries.md` absorbing the per-command clauses of the old CLI spec.

## TASK-Ooc-005.P2 Must implement exactly

- **TASK-Ooc-005.MI1:** `weave`: `--pattern <name>` plus required `--input <ref>` payload reference with `:parse :json` (replaces reading raw stdin; callers use `strand --stdin weave --pattern x --input :stdin`). Exactly one JSON value; delegates to the same `weave!` API call as today's socket dispatch with request context; `:hook-class :mutating`.
- **TASK-Ooc-005.MI2:** `query`: positional subcommand `list` (no args) and `explain <name>`, `:hook-class :read`, returning today's `query-metadata` / `query-explain` payloads (SPEC-004.C36a/C36b shapes, JSON-safe). `pattern`: `list` / `explain <name>` likewise over today's `patterns` / `pattern-explain`. Loud on unknown subcommands and missing names (mirror old CLI usage errors).
- **TASK-Ooc-005.MI3:** `spools/batteries.md`: contract doc in the style of the other spool docs (`spools/workflow.md`), with stable IDs, covering every batteries op's argv contract, payload-ref conventions (the `--attr k=:payload/x` replacement for old `--attr-file`, `--attributes :stdin` replacement for `--attributes-stdin`), hook classes, and JSON result shapes. Write it against old `devflow/specs/cli.md` C6–C13 so behavior equivalences and deliberate differences are explicit.
- **TASK-Ooc-005.MI4:** Add `spools/README.md` index row for batteries.
- **TASK-Ooc-005.MI5:** Tests: weave happy/malformed-JSON/trailing-value loud paths, query/pattern list+explain shapes, unknown pattern/query loud errors.

## TASK-Ooc-005.P3 Done when

- **TASK-Ooc-005.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green.
- **TASK-Ooc-005.DW2:** Live check proves `weave` by driving `op!` with an envelope carrying a payload (REPL/`weaver repl --stdin` acceptable since the Go `--stdin` flag does not exist yet). Safety rail: any live weaver used here runs against a disposable `--workspace` dir with a temp `XDG_STATE_HOME` — never the user's canonical `.skein` weaver or default state dirs (plan TC1).
- **TASK-Ooc-005.DW3:** `spools/batteries.md` exists, indexed, and covers every registered batteries op.

## TASK-Ooc-005.P4 Out of scope

- **TASK-Ooc-005.OS1:** Socket/Go changes; removing builtin `weave`/`query`/`pattern` commands; CLAUDE.md/skill updates (task 10).

## TASK-Ooc-005.P5 References

- **TASK-Ooc-005.REF1:** cli delta SPEC-002-D004.C12/R1/R2; old `devflow/specs/cli.md` C13a/C13aa/C13ab/C13b; `src/skein/core/weaver/socket.clj` dispatch cases `weave`/`query-list`/`query-explain`/`pattern-list`/`pattern-explain`; plan PH2.
