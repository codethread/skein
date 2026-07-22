# Op-only CLI: root-level op dispatch, batteries spool, invoke envelope

**Document ID:** `RFC-019` **Status:** Open **Date:** 2026-07-04 **Related:** [CLI Surface](../specs/cli.md) (SPEC-002), [REPL API](../specs/repl-api.md), [Weaver Runtime](../specs/daemon-runtime.md), [TENETS](../TENETS.md) (TEN-000@1/001/003/004/006), [PHILOSOPHY](../PHILOSOPHY.md), `src/skein/core/weaver/socket.clj` (allowlist + dispatch case), `src/skein/api/weaver/alpha.clj` (`register-op!`, `register-built-in-ops!`)

## RFC-019.P1 Problem

The strand surface has two parallel dispatch tiers. Shipped commands (`add`, `update`, `show`, `supersede`, `burn`, `list`, `ready`, `graph subgraph`, `weave`, `query list/explain`, `pattern list/explain`) each pay a four-layer tax: a Cobra command with hand-maintained flag contracts (SPEC-002.C6a–C6e and friends), a socket allowlist entry, a `dispatch` case arm in `skein.core.weaver.socket`, and a SPEC-002 contract clause. Userland commands pay one layer: `register-op!`, reached through the `op <name>` prefix.

The shipped surface therefore bypasses the extension model it ships, and every new capability is a Go binary release plus protocol accretion rather than a spool change. PHILOSOPHY.md claims the Emacs shape — tiny core, everything else loadable — but the command surface does not have it. Discovery is likewise split three ways (`op help`, `query explain`, `pattern explain`) with three metadata shapes.

TEN-000@1 applies: no migration or compatibility shims are in scope.

## RFC-019.P2 Goals

- **RFC-019.G1:** One dispatch mechanism. Every weaver-facing command is a registered op invoked at the CLI root: `strand add`, `strand agent delegate`, `strand devflow-start`. The `op` subcommand prefix is removed.
- **RFC-019.G2:** `strand` becomes a pure dispatcher: workspace/context selection, named payloads in, NDJSON out. It parses no per-command flags; everything after the op name is opaque argv.
- **RFC-019.G3:** Everything that must work without a running weaver moves to `mill`: workspace bootstrap (`mill init`), weaver lifecycle (`mill weaver start|stop|status`), and trusted nREPL attach (`mill weaver repl`). Mill already does the work behind these commands (SPEC-002.C14a/C16/C17); this relabels the front door to match.
- **RFC-019.G4:** Shipped strand behavior lives in a classpath-shipped reference spool, `skein.spools.batteries` (under `spools/src`, contract doc at `spools/batteries.md`), built on the blessed `skein.api.*.alpha` tier like any other spool.
- **RFC-019.G5:** A blessed declarative argv parser ships in the api tier (working name `skein.api.cli.alpha`): arg-spec in, parsed map or loud structured error out. The same arg-spec powers `help` rendering. Users may layer clojure.spec/malli on top for richer validation.
- **RFC-019.G6:** Discovery unifies on the op registry: a core-registered `help` op enumerates ops with metadata and provenance, replacing `op help` / `query explain` / `pattern explain` as separate mechanisms.
- **RFC-019.G7:** The wire protocol collapses to essentially one operation kind — the invoke envelope — plus response frames that may be a single result or a stream.

## RFC-019.P3 Non-goals

- **RFC-019.NG1:** No changes to the `skein.api.*.alpha` primitive tier's semantics. Batteries consumes `api/add`, `api/list`, etc. as-is; what is removed is the parallel wire surface, not the API tier.
- **RFC-019.NG2:** No stream-in / bidirectional session protocol. Requests stay one bounded envelope (RFC-019.REC4).
- **RFC-019.NG3:** No output shaping: no `--format`, `--quiet`, or pretty rendering. JSON/NDJSON only (TEN-001, SPEC-002.C4 carried forward).
- **RFC-019.NG4:** No hook bypass flags, ever. Received-payload hooks remain the trust surface.
- **RFC-019.NG5:** No compatibility layer for the removed builtin commands or the `op` prefix (TEN-000@1).

## RFC-019.P4 Design

### RFC-019.D1 Command split

- `mill`: `init`, `weaver start|stop|status|repl [--stdin]`, plus existing `start`, `status`, `weaver list`. Mill grows `--workspace` selection and meets the same JSON output bar as strand. `weaver stop`/`status` are driven by mill supervision directly; the socket protocol's `stop` operation is removed and `status` shrinks to a minimal health/identity check.
- `strand`: `strand [dispatcher-flags] <op-name> [opaque argv...]`. Zero builtin subcommands. Dispatcher flag parsing stops at the first non-flag token; everything after the op name ships verbatim as argv.

### RFC-019.D2 Dispatcher flags

Selection & context:

| Flag | Default | Notes |
| --- | --- | --- |
| `--workspace <dir>` | git-derived | Explicit workspace selection, highest precedence (SPEC-002.C1a semantics carried forward). Needed independent of git flags: disposable workspaces are not git repos. |
| `--cwd <dir>` | process cwd | Envelope cwd for op-side relative-path semantics. |
| `--worktree-root <dir>` | derived from cwd | Both git values ship explicitly: worktrees are load-bearing (delegation, `delegate-cwd`). |
| `--git-common-dir <dir>` | derived from cwd | Drives implicit workspace identity (linked worktrees select the same workspace). |

Precedence: `--workspace` wins outright; otherwise explicit git flags win over derivation; derivation runs from `--cwd` when given, else process cwd. Overriding `--cwd` re-derives git context from the new cwd unless git flags pin it; failed derivation with nothing pinned fails loudly.

Input:

| Flag | Notes |
| --- | --- |
| `--stdin` | Reads stdin verbatim to EOF into payload slot `stdin`. Opt-in only; the bin never auto-consumes a pipe. |
| `--payload name=path` | Repeated. Client-side file read into a named payload slot (preserves old `--attr-file` client-side-read semantics; the weaver never reads client-named paths). |

Transport:

| Flag | Notes |
| --- | --- |
| `--timeout <dur>` | Carried in the envelope so the weaver can cancel/refuse, not merely a client-side give-up. Op metadata supplies the default deadline class (replacing SPEC-002.C13c's fixed short/long split); the flag is the override. Stream ops default to no timeout. |

Bin-only: `--version` (bin + protocol version; the version-skew detection story now that the command surface lives weaver-side), `--help` (static; see D6), `--dry-run` (print the assembled envelope without sending; requires no weaver).

Explicitly absent: `--format`/`--quiet`, stream toggles (responses self-describe, D5), retry/reconnect flags, hook-related flags, and all per-command flags (`--attr`, `--query`, `--param`, ... — argv now, owned by ops and the blessed parser).

### RFC-019.D3 Invoke envelope

One request shape: `{name, argv, payloads, cwd, worktree_root, git_common_dir, workspace, timeout?, request_id, protocol_version, client: {pid, version}}`. Automatic fields (`request_id`, versions, pid) are not flags. The socket's current `"op"` operation becomes this envelope, plausibly renamed `invoke`; the per-command allowlist and `dispatch` case in `skein.core.weaver.socket` are deleted.

### RFC-019.D4 Payload references

The bin is dumb transport: it attaches payloads and never interprets argv. Substitution happens weaver-side in the blessed parser:

- A **whole argv value** of `:stdin` or `:payload/<name>` resolves to the named payload string. No substring interpolation, ever.
- Loud rule 1: a reference with no matching payload attached → error (catches accidental literals).
- Loud rule 2: an attached payload nothing references → error (catches silently dropped input).
- The escape hatch for a genuine literal `:stdin` value is to send it via stdin.
- Typed JSON input (old `--attributes-stdin`) becomes an arg-spec concern: the op declares "parse this payload as JSON/JSONL," and validation fails loudly there.

Ops that bypass the blessed parser receive the raw envelope and own their argv/payload handling.

### RFC-019.D5 Response framing (stream-out)

Responses are NDJSON: a single result is a one-line degenerate stream; a streaming op's response is a header frame, NDJSON lines, and a terminator frame carrying done/error status. The bin relays frames until terminator or interrupt and never needs to know in advance whether an op streams. `:stream true` in op metadata exists for discovery and for the no-timeout default, not dispatch mechanics.

Stream-in is rejected (NG2): bounded bulk (JSONL import) is a payload parsed and applied op-side in **one transaction** — matching the existing `weave --pattern` one-JSON-one-transaction precedent and keeping TEN-003 clean (no partial application). Unbounded live ingestion is spool-owned design inside the weaver (TEN-006), or repeated calls; it must not become a dispatcher flag.

### RFC-019.D6 Discovery and `help`

- `strand --help` / bare `strand`: bin-owned, static, zero-weaver — dispatcher flags, envelope contract, pointer to `strand help` and `mill start` remediation.
- `strand help`: a **core-registered** op (registered at runtime init through the public `register-op!` path, as today's built-in `help` already is) projecting the registry: name, doc, metadata classes, provenance.
- `strand help <op>`: full detail — the blessed parser's arg-spec renders here. This is the unification point for the old `query explain` / `pattern explain` / `op help` trio.
- Discovery lives in **core, not batteries**: `help` is a projection of registry state core already owns, must survive batteries failing to activate (else no surface exactly when debugging activation), and must work in curated workspaces where batteries is masked/replaced.
- Registry entries carry **provenance** (registering spool/namespace) — with batteries and userland sharing one flat root namespace, "where did this command come from" is the first question; this is Emacs `describe-function` naming the defining file.
- Collision policy: `register-op!` on an existing name fails loudly (TEN-003; accidental shadowing of `add` must be loud). Deliberate override goes through an explicit `replace-op!` (or `:replace true`). `help` is replaceable through that same door and maskable through generic spool mechanics; unknown-op errors carry the available-names list, so removing `help` buys no secrecy and needs no special-case protection (TEN-002).

### RFC-019.D7 Op metadata classes

Op registration accretes a metadata map designed once, alongside the parser arg-spec: `doc`, `arg-spec`, `stream?`, deadline class (default timeout), hook-gating class (read vs mutating — the SPEC-002.C24 distinction becomes per-op declaration), provenance (recorded by the registry, not self-asserted).

### RFC-019.D8 Batteries spool

`skein.spools.batteries` registers the shipped strand surface as ops: `add`, `update`, `show`, `supersede`, `burn`, `list`, `ready`, `subgraph`, `weave`, `query`/`pattern` discovery equivalents (largely subsumed by `help`), etc., each via the blessed parser. Contract doc at `spools/batteries.md` absorbs the per-command clauses of SPEC-002 (C6–C13). Activation: generated `init.clj` from `mill init` activates batteries explicitly — visible in the same file that activates every other spool, one mechanism, inspectable, and maskable/replaceable for curated low-privilege workspaces (which strictly improves the PHILOSOPHY.md low-privilege story over today's all-or-nothing allowlist). A workspace that drops batteries has no `add` and fails loudly with discovery still available (D6).

## RFC-019.P5 Options considered

| ID | Summary | Outcome |
| --- | --- | --- |
| RFC-019.O1 | Status quo: builtin tier + `op` prefix | Rejected: four-layer tax per shipped command; shipped surface bypasses the extension model; three discovery mechanisms (P1). |
| RFC-019.O2 | Root op dispatch, builtins kept in Go for "core" commands | Rejected: keeps both dispatch tiers and the reserved-name problem; the mill split (G3) removes the last reason for any strand builtin. |
| RFC-019.O3 | Root op dispatch + batteries spool + mill split (this RFC) | Adopted. |
| RFC-019.O4 | Auto-read stdin when not a tty instead of `--stdin` | Rejected: hung scripts and accidentally consumed pipes; opt-in stays. |
| RFC-019.O5 | Single `--stdin` slot only, add file flags later if needed | Rejected in favor of named payloads now: multiple heavy inputs (body + plan + notes) are real; one substitution rule and two flags cover everything old C6a–C6e did, avoiding a second mechanism later. |
| RFC-019.O6 | `--stream-in` JSONL request streaming | Rejected (NG2): bidirectional sessions need input framing, backpressure, and partial-application semantics that erode fail-loudly into fail-partially; bounded bulk is already covered, unbounded ingestion is spool territory. |
| RFC-019.O7 | `help` registered by batteries | Rejected: discovery must survive batteries failing/being masked; it is a projection of core-owned registry state (D6). |
| RFC-019.O8 | Substring interpolation of payload refs in argv | Rejected: whole-value match only; interpolation makes the sentinel unsafe and unparseable. |

## RFC-019.P6 Consequences

- **RFC-019.C1:** SPEC-002 is rewritten: it shrinks to the dispatcher contract (flags, envelope, framing, precedence) plus a new/expanded mill spec section for `init` and `weaver *`. Per-command contracts move to `spools/batteries.md`. SPEC-004 (Weaver Runtime) accretes the invoke envelope, response framing, op metadata classes, `replace-op!`, and registry provenance.
- **RFC-019.C2:** `cli/` deletes the strand Cobra command tree in favor of dispatcher flag parsing + envelope assembly; `skein.core.weaver.socket` deletes the allowlist and `dispatch` case.
- **RFC-019.C3:** Repo config and docs drop the `op` prefix everywhere: `strand op agent delegate` → `strand agent delegate`, `strand op devflow-start` → `strand devflow-start`, `strand op backlog add` → `strand backlog add`. CLAUDE.md, `.agents/skills/strand/SKILL.md`, spool docs, and the devflow spool's emitted instructions all update (TEN-000@1: hard cutover, no aliases).
- **RFC-019.C4:** Received-payload hooks gate on op name + envelope; the read/mutating distinction becomes op-metadata-declared (D7) rather than a fixed protocol list (SPEC-002.C24 superseded).
- **RFC-019.C5:** Version skew between Go bin and weaver reduces to envelope protocol version; command-surface evolution no longer requires binary releases.
