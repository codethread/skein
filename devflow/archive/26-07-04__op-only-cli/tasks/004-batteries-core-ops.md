# Task 4: Batteries spool core strand ops

**Document ID:** `TASK-Ooc-004`

## TASK-Ooc-004.P1 Scope

Type: AFK

Create `spools/src/skein/spools/batteries.clj` (classpath reference spool, ns docstring, runtime-owned state only) registering the core strand surface as parser-backed ops over `skein.api.*.alpha`, per SPEC-002-D004 and old SPEC-002.C6–C11 semantics. Ops are proven through the existing `strand op <name>` invocation path — the old builtin commands still exist and are untouched.

## TASK-Ooc-004.P2 Must implement exactly

- **TASK-Ooc-004.MI1:** An `activate!` (or `use!`-conventional) entry registering ops with `register-op!`, each with `:doc`, `:arg-spec`, and `:hook-class` (`:read` for show/list/ready/subgraph, `:mutating` for add/update/supersede/burn). Provenance must come out as `skein.spools.batteries`.
- **TASK-Ooc-004.MI2:** `add`: positional `title`; `--state` (`active|closed`, default active, reject others loudly); repeatable `--attr k=v` (map flag); `--attributes <ref>` accepting a payload reference with `:parse :json` for typed-JSON bulk attributes (replaces old `--attributes-stdin`); repeatable `--edge type:to-id`. Merge precedence: `--attr` over `--attributes` (old C6e); duplicate keys within `--attr` fail loudly. Values in `--attr` may be payload refs (parser resolves; replaces old `--attr-file`/`--attr-stdin`). Returns the created strand JSON-shaped like the old CLI (normalized attributes/state).
- **TASK-Ooc-004.MI3:** `update`: positional `id`; `--title`; `--state` (`active|closed`; cannot set replaced); `--attr` / `--edge` as in add; no `--attributes` (old C7 kept `--attributes-stdin` add-only).
- **TASK-Ooc-004.MI4:** `show <id>`, `supersede <old-id> <replacement-id>`, `burn <id>`, `list [--state active|closed|replaced] [--query name] [--param k=v...]`, `ready [--query name] [--param k=v...]`, `subgraph <root-id> [--relation type]` — each delegating to the same `skein.api.*.alpha` calls the socket dispatch cases use today (`src/skein/core/weaver/socket.clj` `dispatch` is the reference for exact API wiring, including named-query param handling and the list state overlay), returning the same JSON shapes.
- **TASK-Ooc-004.MI5:** Request-context: mutating ops pass a request context like today's `(request-context op)` so hooks/events see equivalent `:request/operation` data.
- **TASK-Ooc-004.MI6:** Tests (weaver-world tests per `docs/library-authoring.md` patterns): each op's happy path, attr merge precedence, payload-ref attr (add with `--attr body=:stdin` style via envelope), invalid state loud, unknown query loud, and JSON shape equivalence spot-checks against the old socket dispatch results for add/list/ready.

## TASK-Ooc-004.P3 Done when

- **TASK-Ooc-004.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green.
- **TASK-Ooc-004.DW2:** In a disposable `--workspace` world whose `init.clj` activates batteries, `strand op add 'x' --attr k=v`, `strand op list`, `strand op ready` behave equivalently to builtin `strand add/list/ready`.

## TASK-Ooc-004.P4 Out of scope

- **TASK-Ooc-004.OS1:** weave/query/pattern ops and the contract doc (task 5); default-workspace activation wiring (task 8's `mill init` template and task 9); removing the builtin commands (tasks 6–8).

## TASK-Ooc-004.P5 References

- **TASK-Ooc-004.REF1:** cli delta SPEC-002-D004 (esp. R2 flag-semantics mapping), plan A1/R4/TC1/TC2, old root spec `devflow/specs/cli.md` C6–C11 for the semantics being reproduced, `src/skein/core/weaver/socket.clj` dispatch for API wiring, `docs/writing-shared-spools.md` for spool conventions.
