# Mandatory per-leaf hook-class + arity-N fractal subcommands Proposal

**Document ID:** `PROP-Lhc-001`
**Last Updated:** 2026-07-22
**Related RFCs:** None (design settled in the 2026-07-22 session; decision notes `mnl04` and superseding `77xab` on card 8wwjk)
**Related root specs:** [`repl-api.md`](../../specs/repl-api.md) (SPEC-003.C64/C65/C66/C68), [`daemon-runtime.md`](../../specs/daemon-runtime.md) (SPEC-004.C63a/C80), [`cli.md`](../../specs/cli.md) (SPEC-002.C44)

## PROP-Lhc-001.P1 Problem

`:hook-class` and `:deadline-class` are declared per **op**, so mixed multi-verb ops
are classified wholesale: `kanban` registers `:mutating` while `board`/`about`/`card`
are pure reads (mutation-gating read-only and offline projections); `agent` registers
an unbounded deadline for one awaiting verb; `spool` exiles its reads to a separate
`spool-status` op solely to escape the op-wide class. The registration-time
`:mutating` default silently misclassifies read ops (`vocab`, `query`, `pattern`) —
a sensible-default violation of TEN-003. Independently, SPEC-003.C64 caps
subcommands at one level; the cap was a v1 scope cut with no technical constraint
behind it (2026-07-22 audit: a single validator guard; parser routing, returns
routing, and the help node schema all generalize mechanically; the Go CLI forwards
argv untouched), and it forces nested verb grammars into positional-action hacks
(`kanban task <action> …`).

## PROP-Lhc-001.P2 Goals

- **PROP-Lhc-001.G1:** Every invocable leaf (subcommand leaf; flat or raw-envelope
  op) carries an explicit, mandatory `:hook-class` and `:deadline-class`; missing or
  misplaced declarations fail loudly at registration (TEN-003).
- **PROP-Lhc-001.G2:** No op-wide class exists for subcommand ops — not declared,
  not derived; consumers (payload-hook gate, help, deadline resolution) resolve from
  the invoked leaf only.
- **PROP-Lhc-001.G3:** Subcommands nest to arbitrary depth with one uniform fractal
  node shape at every level, so verb blocks compose as plain data (single form, flat
  `def`s, or reused patterns) with no new DSL.
- **PROP-Lhc-001.G4:** A missing or unknown verb at any depth fails loudly before
  payload hooks run, extending the SPEC-004.C80 unknown-op precedent.
- **PROP-Lhc-001.G5:** The whole owned surface adopts in one queue: in-repo ops,
  the `help` builtin, `.skein` config ops, and all sibling spools (kanban,
  delegation, bench, agent-harness, workflow) with their pin-bump ceremony.

## PROP-Lhc-001.P3 Non-goals

- **PROP-Lhc-001.NG1:** No compatibility shims, migrations, derivation fallbacks, or
  defaults — breaking under TEN-000@1; we own every consumer.
- **PROP-Lhc-001.NG2:** No richer semantic-class taxonomy (create/transition/
  host-effect …) beyond `:hook-class` `:read|:mutating` and `:deadline-class`
  `:standard|:unbounded`; a finer taxonomy is future accretion.
- **PROP-Lhc-001.NG3:** No Go CLI changes; `strand` stays a pure argv forwarder.
- **PROP-Lhc-001.NG4:** No lint-only enforcement: the contract is registration-time
  structural validation. `lint-conventions` may additionally catch it pre-load, but
  the runtime check is the contract.

## PROP-Lhc-001.P4 Proposed scope

- **PROP-Lhc-001.S1:** Arg-spec nodes gain `:hook-class`/`:deadline-class` as
  leaf-only, mandatory metadata; interior nodes and subcommand-op registration opts
  reject them loudly. Flat/raw-envelope registration requires both explicitly; the
  `:mutating`/implicit defaults are deleted.
- **PROP-Lhc-001.S2:** The one-level `:subcommands` cap is lifted: validation,
  parse routing (first-token recursion, reserved names per level), returns
  `:subcommands` mirror-routing, help verb-path slicing, and error contexts all
  become depth-uniform; the reserved `:subcommand` result key carries the full path.
- **PROP-Lhc-001.S3:** The socket payload-hook gate and deadline resolution resolve
  from the invoked leaf; unresolvable verbs fail loudly pre-hook.
- **PROP-Lhc-001.S4:** Help renders classes per leaf node; op-wide `hook-class`/
  `deadline-class` leave the envelope's operation facts (help `schema-version`
  bumps).
- **PROP-Lhc-001.S5:** Root specs SPEC-003.C64/C65/C66/C68, SPEC-004.C63a/C80, and
  SPEC-002.C44 are updated via feature-local deltas; the owned op surface and all
  sibling spools adopt in the same queue, folding split read ops (`spool-status`)
  back in as verbs where the surface reads better.

## PROP-Lhc-001.P5 Open questions

- **PROP-Lhc-001.Q1:** Exact shape of the `:subcommand` path value (vector of
  tokens vs joined string) and of path-carrying error contexts — owned by the
  design thin-slice (first implementation task), not by this proposal.
