# CLI Attribute Inputs Plan

**Document ID:** `PLAN-002` **Status:** Shipped **Last Updated:** 2026-06-28 **Proposal:** [proposal.md](./proposal.md) **Related RFCs:** None **Feature specs:** [CLI delta](./specs/cli.delta.md)

## PLAN-002.P1 Goal and scope

Implement the planned `strand add` attribute input sources so large Markdown/text attributes and JSON attribute templates can be used without shell escaping.

## PLAN-002.P2 Approach

Add CLI-side parsing helpers in the Go command package that build the final attribute map before the existing socket `add` call. Keep the weaver protocol unchanged: `strand add` still sends `{"title", "active", "attributes"}` to operation `add`.

## PLAN-002.P3 Affected areas

- **PLAN-002.A1:** `cli/internal/command` flag registration and payload construction for `add`.
- **PLAN-002.A2:** Command tests for payload merging, stdin validation, file reads, and help text.
- **PLAN-002.A3:** Root CLI spec promotion after shipping.

## PLAN-002.P4 Implementation phases

- **PLAN-002.I1:** Add feature-local proposal/spec delta/plan artifacts.
- **PLAN-002.I2:** Implement `--attr-file`, `--attr-stdin`, and `--attributes-stdin` parsing and merge precedence for `add`.
- **PLAN-002.I3:** Add focused Go command tests for success and fail-loud cases.
- **PLAN-002.I4:** Run relevant validation and report any unpromoted spec delta.

## PLAN-002.P5 Validation strategy

Run `(cd cli && go test ./...)`. If Clojure-facing contracts are untouched, full Clojure validation is not required for this focused CLI change.

## PLAN-002.P6 Task context

No AFK task queue is needed; this is a small direct implementation with a reviewed plan.

## PLAN-002.P7 Developer Notes

- 2026-06-28: Plan created as reviewed because the API shape was agreed in-session and implementation scope is narrow.
- 2026-06-28: Implemented add attribute input flags, promoted CLI spec delta into the root CLI spec, and added focused command tests.
- 2026-06-28: Shipped full planned scope. Validation passed with `go test ./cli/...` and smoke passed from short temp checkout `/tmp/skein-smoke-src` due Unix socket path-length limits in the long worktree path.
