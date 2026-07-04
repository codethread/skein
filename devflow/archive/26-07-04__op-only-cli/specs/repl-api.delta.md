# REPL API Delta: Op-only CLI

**Document ID:** `SPEC-003-D003`
**Status:** Merged
**Base Spec:** [REPL API](../../../specs/repl-api.md)
**Related RFC:** [RFC-019 Op-only CLI](../../../rfcs/2026-07-04-op-only-cli.md)
**Last Updated:** 2026-07-04

## SPEC-003-D003.P1 Changed contracts

- **SPEC-003-D003.C1:** A new blessed namespace `skein.api.cli.alpha` ships the declarative op argv parser: an arg-spec (flags, positionals, types, payload-parse declarations such as parse-as-JSON/JSONL) parses envelope argv into a data map or throws a loud structured error. The same arg-spec renders `help <op>` detail. Spool authors may layer clojure.spec/malli on the parsed map; the parser itself stays data-first.
- **SPEC-003-D003.C2:** Payload reference resolution is a parser contract: a whole argv value of `:stdin` or `:payload/<name>` resolves to the named envelope payload string; no substring interpolation. A reference without a matching payload fails loudly; an attached payload nothing references fails loudly. Ops registered without an arg-spec receive the raw envelope and own their argv/payload handling.
- **SPEC-003-D003.C3:** `skein.api.weaver.alpha/register-op!` accretes the op metadata map (doc, arg-spec, stream?, deadline class, hook-gating class) and fails loudly on name collision; `replace-op!` is the explicit override path. The registry records provenance (registering namespace/spool) on each entry. (Registry semantics in SPEC-004-D003.C6.)
- **SPEC-003-D003.C4:** The shipped reference spool `skein.spools.batteries` (classpath, under the shipped `skein.spools.*` tier) registers the public strand command surface as ops through the blessed parser; its behavior contract lives at `spools/batteries.md`, not in root specs. Workspaces may mask or replace batteries; a workspace without it retains core `help` discovery and loud unknown-op errors.

## SPEC-003-D003.P2 Unchanged contracts

- **SPEC-003-D003.U1:** Namespace tiers and their meanings are unchanged: `skein.api.cli.alpha` joins the blessed accretion-based tier; `skein.spools.batteries` joins the authorable/reference tier; nothing new is exposed to `skein.userland.alpha` consumers by contract.
- **SPEC-003-D003.U2:** Interactive `skein.repl` helpers, spool workspace helpers (`sync!`, `use!`, `reload!`), and test helpers are unchanged. (An earlier draft also listed the spool manifest grammar; the minimal-spool-contract feature removed manifest machinery from the root specs while this feature was in flight, so this delta deliberately says nothing about manifests.)
