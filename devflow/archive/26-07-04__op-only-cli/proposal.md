# Op-only CLI Proposal

**Document ID:** `PROP-Ooc-001`
**Last Updated:** 2026-07-04
**Related RFCs:** [RFC-019 Op-only CLI](../../rfcs/2026-07-04-op-only-cli.md)
**Related root specs:** [CLI Surface](../../specs/cli.md) (SPEC-002), [Weaver Runtime](../../specs/daemon-runtime.md) (SPEC-004), [REPL API](../../specs/repl-api.md) (SPEC-003; affected because `register-op!`/`replace-op!`, op metadata, and the blessed parser are trusted REPL/config surfaces documented there)

## PROP-Ooc-001.P1 Problem

The strand surface has two parallel dispatch tiers. Shipped commands each pay a four-layer tax (Cobra command with hand-maintained flag contracts, socket allowlist entry, weaver `dispatch` case arm, SPEC-002 contract clause) while userland commands pay one (`register-op!` behind the `op <name>` prefix). The shipped surface bypasses the extension model it ships, discovery is split across three mechanisms (`op help`, `query explain`, `pattern explain`), and every shipped-command change is a Go binary release plus protocol accretion instead of a spool change. RFC-019 records the accepted direction and rejected alternatives.

## PROP-Ooc-001.P2 Goals

- **PROP-Ooc-001.G1:** One dispatch mechanism: every weaver-facing command is a registered op invoked at the CLI root (`strand add`, `strand agent delegate`); the `op` prefix is removed.
- **PROP-Ooc-001.G2:** `strand` is a pure dispatcher: context/workspace selection, named payloads in, NDJSON out, `--dry-run` envelope inspection; everything after the op name is opaque argv.
- **PROP-Ooc-001.G3:** Everything that must work without a running weaver moves to `mill` (`mill init`, `mill weaver start|stop|status|repl`), matching who already does the work.
- **PROP-Ooc-001.G4:** Shipped strand behavior lives in the classpath reference spool `skein.spools.batteries`, built on `skein.api.*.alpha` like any other spool, with its contract at `spools/batteries.md`.
- **PROP-Ooc-001.G5:** A blessed declarative argv parser (`skein.api.cli.alpha`) gives ops fail-loud flag parsing, payload reference resolution, and help rendering from one arg-spec.
- **PROP-Ooc-001.G6:** Discovery unifies on a core-registered `help` op projecting the registry with per-op metadata and provenance.
- **PROP-Ooc-001.G7:** The socket protocol collapses to one invoke envelope plus self-describing single-result-or-stream response frames.

## PROP-Ooc-001.P3 Non-goals

- **PROP-Ooc-001.NG1:** No semantic changes to the `skein.api.*.alpha` primitive tier; batteries consumes it as-is.
- **PROP-Ooc-001.NG2:** No stream-in or bidirectional session protocol (RFC-019.NG2).
- **PROP-Ooc-001.NG3:** No output shaping: no `--format`, `--quiet`, or pretty rendering; JSON/NDJSON only (RFC-019.NG3, TEN-001).
- **PROP-Ooc-001.NG4:** No hook bypass flags, ever — a permanent invariant, not feature scope (RFC-019.NG4).
- **PROP-Ooc-001.NG5:** No compatibility layer or aliases for the removed builtin commands and `op` prefix (RFC-019.NG5, TEN-000 hard cutover).

## PROP-Ooc-001.P4 Proposed scope

- **PROP-Ooc-001.S1:** The `strand` binary: dispatcher flags (`--workspace`, `--cwd`, `--worktree-root`, `--git-common-dir`, `--stdin`, `--payload`, `--timeout`, `--dry-run`, `--version`, `--help`), envelope assembly, NDJSON relay. All builtin subcommands removed.
- **PROP-Ooc-001.S2:** The `mill` binary: absorbs `init` and `weaver start|stop|status|repl [--stdin]` with `--workspace` selection and JSON output.
- **PROP-Ooc-001.S3:** Weaver socket protocol: invoke envelope replaces the per-command allowlist and dispatch case; response framing supports streams; `status`/`stop` become mill supervision concerns.
- **PROP-Ooc-001.S4:** Weaver op registry: metadata classes (arg-spec, stream, deadline class, hook-gating class), provenance recording, loud collision on `register-op!`, explicit `replace-op!`, core-registered `help`.
- **PROP-Ooc-001.S5:** New `skein.api.cli.alpha` blessed parser and new `skein.spools.batteries` reference spool registering the shipped command surface.
- **PROP-Ooc-001.S6:** Repo-wide `op` prefix removal: `.skein` config docs, CLAUDE.md, strand skill, spool docs, and devflow-spool-emitted instructions.
- **PROP-Ooc-001.S7:** Spec restructure: SPEC-002 rewritten to dispatcher + mill contract; per-command contracts move to `spools/batteries.md`; SPEC-004 accretes envelope, framing, and registry metadata.

## PROP-Ooc-001.P5 Open questions

- **PROP-Ooc-001.Q1:** None blocking; design decisions and rejected alternatives are recorded in RFC-019. Any implementation-discovered contract gaps route back through spec deltas in this feature folder.
