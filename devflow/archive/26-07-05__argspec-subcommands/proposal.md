# Arg-spec subcommands Proposal

**Document ID:** `PROP-ArgspecSub-001` **Last Updated:** 2026-07-05 **Related RFCs:** None **Related root specs:** [SPEC-003.P5b Blessed op argv parser](../../specs/repl-api.md), [SPEC-002.C39 help op](../../specs/cli.md), [SPEC-004.C63a-c op registry](../../specs/daemon-runtime.md)

## PROP-ArgspecSub-001.P1 Problem

Multi-verb weaver ops cannot declare their subcommand structure in the blessed arg-spec DSL (`skein.api.cli.alpha`), which models only flags and positionals. Spool ops (`kanban`, `agent`) therefore register as raw-envelope ops and hand-roll subcommand dispatch inside their handlers; batteries `query`/`pattern` are parser-backed but fake the subcommand as a required string positional named `subcommand`, so the verb set (`list`/`explain`) lives only in prose docstrings. Consequences observed in real usage:

- `strand help kanban` renders only `{"raw-envelope": true, "doc": ...}` — subcommands are undiscoverable from the core help projection.
- Each spool reinvents (or forgets) missing/unknown-subcommand errors; the resulting domain errors embed hand-written usage strings instead of structured, renderable data.
- There is no data anywhere in the registry from which any client could render a subcommand tree.

## PROP-ArgspecSub-001.P2 Goals

- **PROP-ArgspecSub-001.G1:** A multi-verb op can declare its full subcommand surface as data in its registered arg-spec.
- **PROP-ArgspecSub-001.G2:** `strand help <op>` shows an op's subcommands (name, doc, per-subcommand args) whenever the op declares them, with no extra flags needed.
- **PROP-ArgspecSub-001.G3:** Missing/unknown subcommand failures are produced by the parser, uniformly, as structured loud errors carrying the available subcommand names (TEN-003).
- **PROP-ArgspecSub-001.G4:** At least one shipped multi-verb consumer is migrated to prove the shape end to end.

## PROP-ArgspecSub-001.P3 Non-goals

- **PROP-ArgspecSub-001.NG1:** No `<op> help`/`<op> -h`/bare-`<op>` invocation convention — that is the dependent follow-up feature (`op-help-convention`, kanban card `wcmae`).
- **PROP-ArgspecSub-001.NG2:** No CLI-side (Go dispatcher) changes; the dispatcher keeps shipping verbatim argv (SPEC-002.C30).
- **PROP-ArgspecSub-001.NG3:** No `strand help --flat`/`--subcommands` flags; default one-level rendering covers the need (TEN-004). Revisit only if depth demands it.
- **PROP-ArgspecSub-001.NG4:** No forced migration of every raw-envelope op; raw-envelope registration remains valid.

## PROP-ArgspecSub-001.P4 Proposed scope

- **PROP-ArgspecSub-001.S1:** Extend the arg-spec shape with a subcommand concept: named nested specs, each with its own doc, flags, and positionals.
- **PROP-ArgspecSub-001.S2:** Parsing a subcommand op routes on the first argv token, parses the remainder against the nested spec, and reports which subcommand matched; missing/unknown tokens fail loudly with available names and usage data.
- **PROP-ArgspecSub-001.S3:** The help projection (`explain` → `help <op>` detail) renders declared subcommands.
- **PROP-ArgspecSub-001.S4:** Migrate a shipped multi-verb consumer (batteries `query`/`pattern` and/or the kanban spool op) to declared subcommands as the reference usage.
- **PROP-ArgspecSub-001.S5:** Update root specs via feature-local deltas: SPEC-003.P5b (parser contract), SPEC-002.C39 / SPEC-004.C63c (help rendering), and SPEC-004.C63b (op invocation/parse contract).

## PROP-ArgspecSub-001.P5 Open questions

- **PROP-ArgspecSub-001.Q1:** One level of subcommands or arbitrary recursion? Leaning one level (all shipped consumers are one level; TEN-004) with the shape left open for accretion.
- **PROP-ArgspecSub-001.Q2:** Can a subcommand op also declare top-level flags/positionals alongside `:subcommands`? Leaning no (fail loudly at registration) to keep parse routing unambiguous.
- **PROP-ArgspecSub-001.Q3:** Which consumer(s) migrate in this feature: batteries `query`/`pattern` (replace the fake `subcommand` positional) and/or kanban? Minimum one; kanban is the motivating case but lives in a spool local root. `agent` is excluded here: bare `strand agent` intentionally returns its manual, and changing that interacts with the follow-up invocation-convention feature.
