# REPL API Delta: Arg-spec subcommands

**Document ID:** `SPEC-003-D004` **Status:** Merged **Base Spec:** [REPL API](../../../specs/repl-api.md) **Feature:** [../proposal.md](../proposal.md) **Last Updated:** 2026-07-05

## SPEC-003-D004.P1 Changed contracts

- **SPEC-003-D004.C1:** The arg-spec shape (SPEC-003.C60) accretes an optional `:subcommands` entry: a map of subcommand name (string) to a nested arg-spec carrying its own `:doc`, `:flags`, and `:positionals`. Subcommands are one level deep: a nested spec declaring `:subcommands` fails loudly. An arg-spec declaring `:subcommands` may not also declare top-level `:flags` or `:positionals`; that combination fails loudly (routing stays unambiguous). One shared structural validator owns these rules: `parse` and `explain` consult it, and the op registry reuses it for registration-time failure (SPEC-004-D004.C3). Arg-specs without `:subcommands` are not newly validated anywhere.
- **SPEC-003-D004.C2:** Parsing a subcommand arg-spec routes on the first argv token: the token selects the nested spec, the remaining argv parses against it, and the parsed map is the nested result merged with `:subcommand` (the matched name). `:subcommand` is a reserved arg name; a nested spec declaring a flag or positional named `subcommand` fails loudly. A missing or unknown first token throws a loud structured error carrying the op name, the offending token, and the available subcommand names.
- **SPEC-003-D004.C3:** Payload reference resolution and `:parse` declarations (SPEC-003.C61) apply unchanged inside nested subcommand specs.
- **SPEC-003-D004.C4:** The `explain` projection renders declared subcommands (name, doc, flags, positionals per subcommand) as JSON-safe data, powering `help <op>` subcommand rendering (SPEC-002-D005, SPEC-004-D004).

## SPEC-003-D004.P2 Unchanged contracts

- **SPEC-003-D004.U1:** Flat arg-specs (flags + positionals, no `:subcommands`) parse exactly as before; this is a pure accretion within `skein.api.cli.alpha`.
- **SPEC-003-D004.U2:** Raw-envelope registration (no `:arg-spec`) remains valid; ops owning their argv handling are unchanged.

## SPEC-003-D004.P3 Design decisions

### SPEC-003-D004.D1 Merged parse result with reserved `:subcommand` key

- **Decision:** The parsed map is flat — nested args merged with `:subcommand` — rather than nesting under a per-subcommand key.
- **Rationale:** Handlers dispatch on `:subcommand` and destructure args directly, matching how existing hand-rolled handlers (kanban, agents) already work; collision is prevented loudly at registration.
- **Rejected:** Nested `{:subcommand s :args {...}}` shape — an extra level every handler would immediately unwrap.

### SPEC-003-D004.D2 One level of subcommands

- **Decision:** Only one subcommand level is supported; deeper nesting fails loudly at registration.
- **Rationale:** Every shipped consumer is one level; TEN-004 minimal surface. The shape (nested arg-specs) leaves room to accrete recursion later without breaking existing specs.
- **Rejected:** Arbitrary recursion now — speculative complexity with no consumer.
