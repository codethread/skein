# Weaver Lifecycle Hooks Proposal

**Document ID:** `WLH-PROP-001`
**Status:** Reviewed
**Last Updated:** 2026-06-29
**Related RFCs:** [RFC-008 Weaver Lifecycle Hooks](../../rfcs/2026-06-29-weaver-lifecycle-hooks.md)
**Related Specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md), [Strand Model](../../specs/strand-model.md)
**Feature Deltas:** [Weaver Runtime delta](specs/daemon-runtime.delta.md), [REPL API delta](specs/repl-api.delta.md), [CLI Surface delta](specs/cli.delta.md)

## WLH-PROP-001.P1 Problem

Skein's event system is intentionally asynchronous and post-commit. That makes it suitable for notifications, indexing, logging, and follow-on automation, but unsuitable for policy that must prevent an invalid mutation from committing.

Trusted users need weaver-side lifecycle gates for workflow rules such as attribute normalization, schema validation, and state-transition policy. A CLI worker should still send simple JSON-shaped data, but trusted config loaded into the weaver should be able to coerce configured attributes and reject invalid candidate graph changes before storage commits. Those failures must propagate to the original CLI, REPL, pattern, or batch caller.

## WLH-PROP-001.P2 Goals

- **WLH-PROP-001.G1:** Add a weaver-lifetime lifecycle hook registry for trusted synchronous Clojure policy.
- **WLH-PROP-001.G2:** Keep post-commit events as asynchronous notification machinery, not rollback or validation machinery.
- **WLH-PROP-001.G3:** Let hooks reject decoded JSON socket requests for strand-graph mutation or userland-invoking operations before semantic dispatch without expanding the public CLI schema surface.
- **WLH-PROP-001.G4:** Let hooks normalize strand attribute maps only at explicit transform hook points before validation and candidate construction finish.
- **WLH-PROP-001.G5:** Let hooks validate pre-commit mutation candidates for add, update, supersede, burn, batch graph mutation, and pattern-created batches.
- **WLH-PROP-001.G6:** Preserve deterministic ordering, reload-friendly replacement, data-first introspection, and loud structured failure propagation.
- **WLH-PROP-001.G7:** Ensure public JSON socket mutation paths, connected REPL helpers, trusted alpha namespaces, patterns, and batch mutation paths use the same weaver-owned policy gates where they enter blessed weaver APIs.

## WLH-PROP-001.P3 Non-goals

- **WLH-PROP-001.NG1:** Do not add CLI schema, coercion, workflow, or hook-registration commands.
- **WLH-PROP-001.NG2:** Do not sandbox hook code, make it safe for untrusted users, or restrict trusted code from using lower-level namespaces when it accepts compatibility cost.
- **WLH-PROP-001.NG3:** Do not persist hook registrations or hook results in SQLite.
- **WLH-PROP-001.NG4:** Do not make async event handlers block, retry, or roll back committed mutations.
- **WLH-PROP-001.NG5:** Do not introduce a plugin/package manager or a second extension-loading model.
- **WLH-PROP-001.NG6:** Do not make arbitrary hook return values meaningful; only explicitly transformable hook families may replace values.

## WLH-PROP-001.P4 Proposed scope

- **WLH-PROP-001.S1:** Add `skein.hooks.alpha` as the blessed source-visible helper namespace for trusted config, activated libraries, in-weaver code, and connected REPL workflows.
- **WLH-PROP-001.S2:** Add hook registration, unregistration, and registry introspection operations to the weaver API. Registry entries are keyed, typed, function-symbol based, metadata-bearing, and ordered.
- **WLH-PROP-001.S3:** Add validation-only received-payload hooks for strand-graph mutation or userland-invoking JSON socket requests after request decode, protocol validation, identity verification, and allowlist checks, but before semantic dispatch. Setup, read-only, and administrative socket operations such as `init`, `status`, and `stop` stay ungated for debuggability.
- **WLH-PROP-001.S4:** Add explicit per-strand attribute-normalization transform hooks that thread replacement attribute maps through ordered handlers. A no-op transform still returns `{:hook/value current-attributes}`; returning `nil`, a non-wrapper value, or an invalid shape fails loudly.
- **WLH-PROP-001.S5:** Add pre-commit mutation hooks that receive normalized before/after candidate context and reject invalid graph or strand changes by throwing.
- **WLH-PROP-001.S6:** Invoke lifecycle hooks through the runtime library classloader, matching existing views, patterns, operations, and events.
- **WLH-PROP-001.S7:** Clear hook registry state during config reload with the other weaver-lifetime registries, then let `init.clj` reinstall hooks.
- **WLH-PROP-001.S8:** Wrap hook invocation failures in a predictable `hook/failed` domain error while preserving hook key, type, function symbol, original message, original `ex-info` data, and original `:code` as hook cause data for callers.
- **WLH-PROP-001.S9:** Keep event emission after successful commits unchanged: successful mutations still enqueue post-commit events; hook-rejected mutations emit no mutation events.
- **WLH-PROP-001.S10:** Document that direct trusted use of lower-level persistence namespaces is outside the blessed policy-gated API path.

## WLH-PROP-001.P5 Open questions

- **WLH-PROP-001.Q1:** None for proposal scope. Implementation planning should give batch candidate planning explicit attention because it is the highest-risk integration point.
