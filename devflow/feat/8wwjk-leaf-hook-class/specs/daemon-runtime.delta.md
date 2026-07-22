# daemon-runtime delta for 8wwjk-leaf-hook-class

**Document ID:** `DELTA-Lhc-002`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Draft
**Last Updated:** 2026-07-22

## DELTA-Lhc-002.P1 Summary

Registration requires explicit leaf classes on every route into the registry, the
payload-hook gate and deadline resolution resolve from the invoked leaf, and
unresolvable verbs fail loudly before hooks. Breaking under TEN-000@1.

## DELTA-Lhc-002.P2 Contract changes

- **DELTA-Lhc-002.CC1 (amends SPEC-004.C63a):** The op metadata map loses its
  `:hook-class`/`:deadline-class` defaults. A flat or raw-envelope op **must**
  declare both at registration (it is its own leaf); an op whose arg-spec declares
  `:subcommands` **must not** declare either at the op level — classes live on the
  arg-spec's leaf nodes (DELTA-Lhc-001.CC2). Violations fail loudly at
  `register-op!`/`replace-op!` naming the op and, for node-level violations, the
  node path.
- **DELTA-Lhc-002.CC2 (amends SPEC-004.C63d, closes the publication seam):** The
  same leaf-class and recursion rules are enforced on **every registration
  route**: `register-op!`/`replace-op!`, and the module-publication entry seam,
  whose entry validation gains the shared structural checks (today it accepts any
  map). Hand-assembled entry constructors — batteries' contribution path, guild,
  text-search, workspace `workflows.clj`, the workspace `defop` macro, and test
  fixtures — conform to the same contract; none may re-create a class default.
- **DELTA-Lhc-002.CC3 (amends SPEC-004.C80):** `:payload/received` hooks gate
  socket `invoke` by the **invoked leaf's** `:hook-class`: the gate walks the
  envelope argv tokens through the op's arg-spec to the leaf before running
  hooks. `:read` leaves skip payload hooks; `:mutating` leaves run them. A
  missing or unknown verb token at any depth fails loudly **before any hook
  runs** — the same pre-hook policy as an unknown op name — carrying the op, the
  path walked, and the available children. Flat and raw-envelope ops gate on
  their own (mandatory) class; nothing defaults.
- **DELTA-Lhc-002.CC4 (amends SPEC-004.C26b):** The single-result deadline
  default comes from the **invoked leaf's** `:deadline-class`; envelope `timeout`
  still overrides. Stream-class ops remain unbounded, consistent with the
  leaf-level `:unbounded` requirement (DELTA-Lhc-001.CC2).
- **DELTA-Lhc-002.CC5 (amends SPEC-004.C63b):** The dispatch-owned `:operation`
  label amendment derives from the parsed `:subcommand` **path vector** joined
  with spaces. The "parsed nested `:action`" special-case is retired: grammars
  that encoded verbs as positionals migrate to real nested subcommands.
- **DELTA-Lhc-002.CC6 (amends SPEC-004.C108):** The trailing `--help`/`-h` flag
  rewrite applies after any verb path (`strand <op> <verb> ... --help` rewrites
  to `help <op> <verb> ...`), still resolving before hook gating as a read-class
  registry projection. The retired-sugar redirect and its declared-subcommand
  exemption evaluate against the first-level child names as today; reserved-name
  validation at every depth (DELTA-Lhc-001.CC1) keeps `help` undeclarable
  anywhere.

## DELTA-Lhc-002.P3 Design decisions

### DELTA-Lhc-002.D1 Pre-hook loud failure instead of a fallback class

- **Decision:** No class exists for "the op as a whole"; unresolvable invocations
  fail loudly before payload hooks.
- **Rationale:** Nothing invocable resolves to a non-leaf (a verb-less invocation
  of a subcommand op is already a loud parse error), and SPEC-004.C80 already
  fails unknown ops pre-hook — this extends that policy down the tree rather
  than inventing a conservative gating class that would never gate a successful
  invocation.
- **Rejected:** Gating unresolvable invocations with a derived superclass (dead
  path — the invocation fails at parse regardless; running mutation hooks for a
  doomed request only adds noise).

## DELTA-Lhc-002.P4 Open questions

- None.
