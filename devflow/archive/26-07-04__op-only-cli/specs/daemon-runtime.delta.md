# Weaver Runtime Delta: Op-only CLI

**Document ID:** `SPEC-004-D003`
**Status:** Merged
**Base Spec:** [Weaver Runtime](../../../specs/daemon-runtime.md)
**Related RFC:** [RFC-019 Op-only CLI](../../../rfcs/2026-07-04-op-only-cli.md)
**Last Updated:** 2026-07-04

## SPEC-004-D003.P1 Changed contracts

### JSON socket transport (SPEC-004.P6)

- **SPEC-004-D003.C1:** The socket operation set collapses to `invoke` plus a minimal `status` health/identity operation. `invoke` carries the envelope `{name, argv, payloads, cwd, worktree_root, git_common_dir, workspace, timeout?, client{pid, version}}` inside the existing request frame (client identity is socket-level diagnostic data: visible to payload hooks in `:request/args`, not threaded into op handler context) (protocol version, request id, weaver id retained per SPEC-004.C23) and dispatches to the op registry. The per-command allowlist and per-command argument-shape validation (SPEC-004.C26) are removed; argument validation belongs to ops via the blessed parser.
- **SPEC-004-D003.C2:** Responses self-describe as single or stream. A single result is the existing one-frame response (SPEC-004.C24 retained). A stream response is a header frame, NDJSON result lines, and a terminator frame carrying done/error status; transport and domain error envelopes are unchanged in shape. One request per connection is retained; a streaming response holds its connection open without starving others (SPEC-004.C23a retained).
- **SPEC-004-D003.C3:** The socket `stop` operation is removed; weaver shutdown is mill supervision (signal), and the weaver shuts down cleanly on termination signals, removing `weaver.edn`/`weaver.json`/`weaver.sock` artifacts as today (SPEC-004.C28 stop semantics move to signal handling).
- **SPEC-004-D003.C4:** Received-payload hooks gate `invoke` after protocol/identity validation and before dispatch. The op's registered hook-gating class (`:read` or `:mutating`) replaces SPEC-004.C80's fixed gated-operation list, which this clause explicitly supersedes: hook context keeps `:hook/type`, `:request/source :json-socket`, `:request/id`, and `:request/options`, with `:request/operation :invoke`, plus `:op/name` and the decoded envelope (argv, payloads, context fields) as `:request/args`. Hooks may still reject but not transform. Read-class ops are not gated, preserving the old read-only exemption; pre-commit mutation and batch hook families (SPEC-004.C81–C83) are unchanged.
- **SPEC-004-D003.C5:** Request deadlines apply to single-result responses: the op's registered deadline class supplies the default (`:standard` = 10s, `:unbounded` = none); an envelope `timeout` (milliseconds) overrides it. On expiry the connection returns a `operation/deadline-exceeded` domain error and the handler future is cancelled with interruption — no orphan success frame follows a reported timeout (interruption is cooperative; a mutation already mid-commit is not rolled back). Stream-class ops run unbounded on the connection thread and are never server-deadline-bounded, including when an envelope timeout is present; cancellation is client disconnect, which aborts the emitting handler on its next write.

### Op registry (SPEC-004.P4 / C16)

- **SPEC-004-D003.C6:** Op registration accretes a metadata map: `doc`, `arg-spec` (blessed parser spec, optional for raw-envelope ops), `stream?`, deadline class, hook-gating class, and registry-recorded provenance (registering namespace/spool — recorded by the registry, never self-asserted). `register-op!` on an existing name fails loudly; deliberate override is explicit `replace-op!` (which preserves loud behavior for accidental collisions).
- **SPEC-004-D003.C6a:** Op handler context accretes envelope fields alongside the existing `:op/name`/`:op/argv`/`:op/runtime`/`:op/runtime-metadata` keys: `:op/payloads`, `:op/cwd`, `:op/worktree-root`, `:op/git-common-dir`, and `:op/timeout`. When the op declares an arg-spec, the blessed parser (SPEC-003-D003) runs before the handler and supplies the parsed argument map; parse failures are loud domain errors and the handler is not called. Raw-envelope ops receive the unparsed context and own their argv/payload handling.
- **SPEC-004-D003.C7:** Core registers exactly one built-in op at runtime init: `help`, projecting the registry (names, docs, metadata classes, provenance; per-op detail renders the arg-spec). `help` is registered through the public path and is replaceable via `replace-op!` and maskable like any op; unknown-op errors carry available op names, so masking `help` yields no secrecy.
- **SPEC-004-D003.C8:** The weaver API operations for the old fixed command surface (add/update/supersede/show/burn/list/ready/list-query/ready-query/weave/subgraph and query/pattern introspection in SPEC-004.C16) remain blessed `skein.api.*.alpha` functions but are no longer socket operations; the socket reaches them only through registered ops (batteries).

### Peering (SPEC-004.P10 / C85–C90)

- **SPEC-004-D003.C9:** `skein.api.peers.alpha/call!` drops its client-side mirror of the removed allowlist (SPEC-004.C88's "allowlisted public JSON socket operation" clause): it invokes a named op on the resolved peer via the `invoke` envelope (or the minimal `status` operation), and rejection is receiving-side — unknown-op domain errors and the peer's `:payload/received` hooks remain the policy gate. Peering still adds no socket operations or protocol fields, and the receiving weaver still cannot distinguish a peer from any other local client. Peer `call!` supports single-result responses; consuming a peer's stream-class op through `call!` is out of scope for this feature and fails loudly rather than truncating.

## SPEC-004-D003.P2 Removed contracts

- **SPEC-004-D003.R1:** SPEC-004.C26 (socket operation allowlist) and C36c (query-list/query-explain socket argument shapes): superseded by `invoke` + op dispatch.
- **SPEC-004-D003.R2:** SPEC-004.C28 socket `status`/`stop` reporting duties beyond the minimal health/identity check; full status reporting is mill's (SPEC-002-D004.C10).

## SPEC-004-D003.P3 Unchanged contracts

- **SPEC-004-D003.U1:** Runtime model, storage model, metadata/discovery, nREPL transport, startup config loading, query/pattern/view/hook/event registries, and spool workspace model (SPEC-004.P2/P3/P3a/P5/P7–P9) are unchanged; only their socket exposure route changes.
- **SPEC-004-D003.U2:** Registry mutation stays REPL/trusted-config-only (SPEC-004.C27): no socket operation registers ops, queries, patterns, views, or hooks.
- **SPEC-004-D003.U3:** JSON socket remains local-only, one request per connection, with the existing error-envelope taxonomy.
