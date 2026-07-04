# Task 2: Blessed parser skein.api.cli.alpha with payload refs

**Document ID:** `TASK-Ooc-002`

## TASK-Ooc-002.P1 Scope

Type: AFK

Create the new blessed namespace `src/skein/api/cli/alpha.clj` (with ns docstring): a declarative argv parser for weaver ops per SPEC-003-D003.C1/C2. Standalone pure-data namespace; no registry or socket coupling (integration is task 3).

## TASK-Ooc-002.P2 Must implement exactly

- **TASK-Ooc-002.MI1:** A data-first arg-spec grammar (design the exact shape; keep it minimal EDN data, no functions) covering: named flags (`--flag value`, types `:string`/`:int`/`:boolean` with `--flag` presence form, `:repeat?` for repeatable flags, `:required?`), `key=value` map-accumulating flags (for `--attr k=v` style), positional args (ordered, `:required?`, optional trailing variadic), and per-arg `:doc`. Parsing returns a map of keyword arg names to parsed values.
- **TASK-Ooc-002.MI2:** Fail loudly (`ex-info` with structured data naming the offending token/flag and the op's spec) on: unknown flags, missing required args, type violations, duplicate non-repeat flags, malformed `key=value` tokens, trailing unconsumed tokens.
- **TASK-Ooc-002.MI3:** Payload references (SPEC-003-D003.C2): after parse, any **whole** string value equal to `:stdin` or `:payload/<name>` resolves to the matching entry in the envelope payloads map. No substring interpolation. Loud rule 1: a reference with no matching payload throws. Loud rule 2: any attached payload that no reference consumed throws (message states which slots went unused). Resolution runs across flag values, map-flag values, and positionals.
- **TASK-Ooc-002.MI4:** Payload parse declarations: an arg (or payload-consuming value) may declare `:parse :json` or `:parse :jsonl`; the resolved payload string is parsed accordingly (jsonl → vector of values), malformed input throws with line context. No other coercions.
- **TASK-Ooc-002.MI5:** `explain` (or similarly named) function: given an arg-spec, return a JSON-safe data rendering (args, types, docs, required, payload-parse declarations) suitable for the `help <op>` projection in task 3.
- **TASK-Ooc-002.MI6:** Thorough unit tests: happy paths for every arg kind, every loud failure in MI2, both payload loud rules, whole-value-only matching (a value containing `:stdin` as a substring is untouched), json/jsonl parse success and failure, explain output shape.

## TASK-Ooc-002.P3 Done when

- **TASK-Ooc-002.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green.
- **TASK-Ooc-002.DW2:** Namespace has a docstring; no module-level atoms; pure functions only.

## TASK-Ooc-002.P4 Out of scope

- **TASK-Ooc-002.OS1:** Registry/`op!` integration, help op, batteries arg-specs, Go code, clojure.spec/malli integration (userland layers those on the parsed map).

## TASK-Ooc-002.P5 References

- **TASK-Ooc-002.REF1:** `devflow/feat/op-only-cli/specs/repl-api.delta.md` SPEC-003-D003.C1/C2; RFC-019.D4; plan A3.
