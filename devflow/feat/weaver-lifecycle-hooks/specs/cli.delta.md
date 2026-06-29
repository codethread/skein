# CLI Surface delta for weaver lifecycle hooks

**Document ID:** `WLH-DELTA-003`
**Root spec:** [cli.md](../../../specs/cli.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Reviewed
**Last Updated:** 2026-06-29

## WLH-DELTA-003.P1 Summary

This delta clarifies how the thin public CLI interacts with weaver lifecycle hooks. The CLI gains no hook authoring or schema surface. It continues to send JSON-shaped requests over the socket; the weaver may reject or normalize through trusted lifecycle hooks before mutation commit.

## WLH-DELTA-003.P2 Contract changes

- **WLH-DELTA-003.CC1:** The public `strand` CLI has no hook registration, listing, debugging, schema, coercion, or workflow-rule commands.
- **WLH-DELTA-003.CC2:** Existing CLI attribute input contracts remain unchanged. `--attr`, `--attr-file`, and `--attr-stdin` send string-valued attributes; `--attributes-stdin` preserves JSON value types from the submitted JSON object.
- **WLH-DELTA-003.CC3:** Trusted weaver-side attribute-normalization hooks may coerce or normalize attribute maps after the CLI request reaches the weaver. The Go CLI does not infer types from strings or user schemas.
- **WLH-DELTA-003.CC4:** After the JSON socket validates protocol, identity, allowlist membership, and operation argument shape, the weaver may run received-payload hooks before dispatching strand-graph mutation or userland-invoking public operations. Setup, read-only, and administrative operations such as `init`, `status`, `stop`, `show`, `list`, `ready`, and `pattern explain` are not gated by received-payload hooks.
- **WLH-DELTA-003.CC5:** CLI commands that would otherwise be syntactically valid may fail non-zero because a lifecycle hook rejects the request or candidate mutation. Such failures are domain errors with code `hook/failed` and structured details from the weaver, including any original hook-provided cause code.
- **WLH-DELTA-003.CC6:** Hook failures never cause the CLI to retry, locally repair, coerce, or partially apply a command.
- **WLH-DELTA-003.CC7:** Hook-approved successful CLI commands preserve existing JSON result shapes, except that normalized attributes may reflect weaver-side attribute-normalization hooks.
- **WLH-DELTA-003.CC8:** Non-allowlisted operations remain unavailable over the JSON socket even if a hook is registered. Hooks can reject public requests but cannot expand the CLI operation set.

## WLH-DELTA-003.P3 Design decisions

### WLH-DELTA-003.D1 The CLI remains a thin JSON control surface

- **Decision:** Lifecycle policy lives in trusted weaver config and REPL workflows, not in Go CLI flags or config.
- **Rationale:** The CLI should be scriptable by agents and low-privilege workers. Rich policy belongs in the daemon core where it can apply consistently across clients.
- **Rejected:** Adding CLI schema files, per-command coercion flags, or hook authoring commands.

### WLH-DELTA-003.D2 Hook failures surface as domain failures

- **Decision:** The CLI reports hook rejections as normal non-zero weaver domain errors using the socket error envelope.
- **Rationale:** Hook rejection is a business/policy failure from the selected weaver world, not a malformed command or transport failure.
- **Rejected:** Reporting hook rejection as protocol failure or hiding hook details behind a generic transport error.

## WLH-DELTA-003.P4 Open questions

- **WLH-DELTA-003.Q1:** None.
