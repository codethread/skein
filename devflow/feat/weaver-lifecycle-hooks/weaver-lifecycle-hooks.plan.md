# Weaver Lifecycle Hooks Plan

**Document ID:** `WLH-PLAN-001`
**Feature:** `weaver-lifecycle-hooks`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** [RFC-008 Weaver Lifecycle Hooks](../../rfcs/2026-06-29-weaver-lifecycle-hooks.md)
**Root specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [CLI Surface](../../specs/cli.md), [Strand Model](../../specs/strand-model.md)
**Feature specs:** [Weaver Runtime delta](specs/daemon-runtime.delta.md), [REPL API delta](specs/repl-api.delta.md), [CLI Surface delta](specs/cli.delta.md)
**Status:** Reviewed
**Last Updated:** 2026-06-29

## WLH-PLAN-001.P1 Goal and scope

Deliver the reviewed Weaver Lifecycle Hooks contracts as a weaver-owned, trusted, synchronous hook system. The feature adds runtime hook registration and introspection, explicit attribute-normalization transforms, pre-commit mutation validation for blessed mutation APIs, received-payload gates for selected JSON socket operations, and preserved post-commit event behavior.

## WLH-PLAN-001.P2 Approach

- **WLH-PLAN-001.A1:** Build the hook system parallel to events but without an async worker: runtime owns a hook registry atom, `skein.weaver.api` owns validation/registration/invocation, and `skein.hooks.alpha` mirrors the existing `skein.events.alpha` direct-or-connected helper pattern.
- **WLH-PLAN-001.A2:** Implement a small hook runner before broad integration. It should resolve function symbols through the runtime library classloader, sort by `:order` then key `pr-str`, invoke validation hooks by ignored return value, invoke transform hooks by threading strict `{:hook/value ...}` wrappers, and wrap failures as `hook/failed` while preserving cause data.
- **WLH-PLAN-001.A3:** Integrate hooks at blessed weaver API entry points rather than in lower-level storage functions. Direct trusted calls to `skein.db` remain outside the hook guarantee.
- **WLH-PLAN-001.A4:** Normalize strand attributes before final candidate validation for add, update, transactional graph batch created/updated entries, and pattern-produced create-only batch entries. Edge attribute normalization is out of scope.
- **WLH-PLAN-001.A5:** Add pre-commit hooks where mutation failure can abort the transaction and prevent post-commit events: add, update including candidate edge operations, supersede, burn, apply-batch, and weave-created batches.
- **WLH-PLAN-001.A6:** Use an uncommitted-write seam rather than a dry-run planner for complex mutations. For supersede, graph batch, and create-only pattern batch, factor storage helpers so the weaver API can open the transaction, perform normal storage validation/mutation against that transaction, receive the uncommitted result/candidate context, invoke hooks before the transaction exits, and rely on transaction rollback if a hook throws. This keeps hook context aligned with committed result shapes without duplicating relation, ref, cycle, and JSON checks outside `skein.db`.
- **WLH-PLAN-001.A7:** Attribute normalization happens before storage helpers that consume attribute maps. Pre-commit validation for complex operations happens after storage helpers produce uncommitted candidate/result data and before commit.
- **WLH-PLAN-001.A8:** Add received-payload hooks in the JSON socket after existing protocol/identity/allowlist/argument validation and before dispatch, only for `add`, `update`, `supersede`, `burn`, `weave`, and `op`. Keep `init`, `status`, `stop`, and read-only operations ungated.
- **WLH-PLAN-001.A9:** Keep successful mutation event emission semantically unchanged; hook-rejected mutations emit no mutation events.

## WLH-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| -- | ---- | --------------- |
| WLH-PLAN-001.AA1 | `src/skein/weaver/runtime.clj` | Add hook registry runtime state and clear it during startup/reload lifecycle. |
| WLH-PLAN-001.AA2 | `src/skein/weaver/api.clj` | Add hook registration/introspection APIs, runner/error helpers, mutation integration, and reload clearing. |
| WLH-PLAN-001.AA3 | `src/skein/hooks/alpha.clj` | New blessed helper namespace for trusted hook workflows. |
| WLH-PLAN-001.AA4 | `src/skein/client.clj` | Add fixed nREPL operation mappings for hook helper routing. |
| WLH-PLAN-001.AA5 | `src/skein/weaver/socket.clj` | Invoke `:payload/received` hooks for selected JSON socket operations before dispatch. |
| WLH-PLAN-001.AA6 | `src/skein/db.clj` | Factor supersede, graph batch, and create-only pattern batch internals so the weaver API can run hooks after uncommitted mutation result construction and before transaction commit. |
| WLH-PLAN-001.AA7 | `test/skein/*` | Add focused unit/integration coverage for registry, helper routing, hook ordering, failure propagation, mutation aborts, event preservation, reload clearing, socket payload gates, and batch/pattern paths. |
| WLH-PLAN-001.AA8 | `devflow/specs/*`, `dev/skein/smoke.clj`, docs as needed | Promote shipped contracts and add smoke/doc coverage after implementation. |

## WLH-PLAN-001.P4 Contract and migration impact

- **WLH-PLAN-001.CM1:** No SQLite migration or persisted hook state is introduced. Hook registrations are weaver-lifetime runtime state reinstalled by trusted config or REPL workflows.
- **WLH-PLAN-001.CM2:** The public Go CLI surface gains no new commands or flags. Existing commands may fail with a `hook/failed` domain error when selected-world policy rejects them.
- **WLH-PLAN-001.CM3:** `skein.hooks.alpha` becomes a new blessed source-visible namespace, explicit like `skein.events.alpha` and not preloaded into `skein.repl`.
- **WLH-PLAN-001.CM4:** Hook-approved successful mutation result shapes and post-commit events remain compatible with current contracts, except normalized strand attributes may reflect weaver-side transforms.

## WLH-PLAN-001.P5 Implementation phases

### WLH-PLAN-001.PH1 Registry and helper surface

Outcome: Hook registry state, API operations, helper namespace, connected-helper routing, deterministic introspection, and reload clearing exist without yet changing mutation behavior.

### WLH-PLAN-001.PH2 Core hook runner and simple strand mutation gates

Outcome: The hook runner enforces ordering, transform wrappers, and `hook/failed` errors, then gates add/update/burn paths with attribute normalization and pre-commit validation while preserving event semantics.

### WLH-PLAN-001.PH3 Complex mutation seams and gates

Outcome: Supersede, transactional graph batches, and pattern-created batches expose uncommitted result/candidate seams, run the reviewed hooks before commit, and reject atomically when hooks fail.

### WLH-PLAN-001.PH4 JSON socket payload gates

Outcome: Selected public socket operations run validation-only received-payload hooks before dispatch; setup, read-only, and administrative operations remain ungated.

### WLH-PLAN-001.PH5 Final integration, docs, and promotion

Outcome: Helper, CLI, smoke, and root spec documentation are aligned with shipped behavior and the standard project validation suite passes.

## WLH-PLAN-001.P6 Validation strategy

- **WLH-PLAN-001.V1:** Clojure tests prove registry validation, replacement, ordering, helper routing, reload clearing, classloader resolution, and no callable values in introspection.
- **WLH-PLAN-001.V2:** Mutation tests prove transform hooks thread values, no-op transforms must return `{:hook/value ...}`, invalid transforms fail loudly, validation hook failures abort storage writes, and hook-approved mutations still enqueue post-commit events.
- **WLH-PLAN-001.V3:** Batch/pattern tests prove common batch context shape, per-strand normalization, all-or-nothing rejection, and single policy pass for `weave`.
- **WLH-PLAN-001.V4:** Socket tests prove payload hooks run only for `add`, `update`, `supersede`, `burn`, `weave`, and `op`, see string-keyed pre-normalization args, and do not gate `init`, `status`, `stop`, `show`, `list`, `ready`, or `pattern-explain`.
- **WLH-PLAN-001.V5:** Standard validation remains `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

## WLH-PLAN-001.P7 Risks and open questions

- **WLH-PLAN-001.R1:** Complex mutation hooks may require deeper factoring of `skein.db` than the simpler add/update/burn paths. Mitigation: use uncommitted-write seams for supersede, graph batch, and create-only pattern batch, and require tests that compare hook context to committed result shapes.
- **WLH-PLAN-001.R2:** Hook contexts can become too broad or inconsistent across entry points. Mitigation: use the feature deltas as the contract source and make common shape assertions in tests.
- **WLH-PLAN-001.R3:** Wrapping all hook failures as `hook/failed` could hide user policy details. Mitigation: require `:hook/cause-code` and original `ex-info` data in error details.
- **WLH-PLAN-001.Q1:** None blocking task generation.

## WLH-PLAN-001.P8 Task context

- **WLH-PLAN-001.TC1:** The reviewed contract lives in `proposal.md` and the three feature spec deltas. Do not re-open RFC alternatives while implementing tasks unless a task discovers a concrete contradiction.
- **WLH-PLAN-001.TC2:** Existing event code is the closest registry/helper pattern, but hook failures propagate synchronously and must not use the event worker or recent-failure queue.
- **WLH-PLAN-001.TC3:** Keep the CLI thin. Hook registration, listing, and debugging stay in `skein.hooks.alpha` / trusted REPL workflows, not Go commands.
- **WLH-PLAN-001.TC4:** Direct `skein.db` use by trusted code bypasses hooks by design. The blessed weaver API and alpha helpers are the policy-gated path.
- **WLH-PLAN-001.TC5:** Prefer explicit failure over fallback behavior. If a hook context cannot be constructed with required data, fail the operation loudly rather than silently skipping the hook.

## WLH-PLAN-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### WLH-PLAN-001.DN1 Task queue authoring — 2026-06-29

- Created eight AFK tasks covering registry/helper surface, hook runner and normalization, simple mutation gates, supersession seam, graph batch gate, pattern weave gate, socket payload hooks, and final spec promotion/validation.
- Review found no remaining blockers after context-key amendments to Tasks 3-6.
