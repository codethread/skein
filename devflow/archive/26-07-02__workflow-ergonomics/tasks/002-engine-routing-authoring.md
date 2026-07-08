# Task 2: Engine routing and authoring

**Document ID:** `TASK-Werg-002`

## TASK-Werg-002.P1 Scope

Type: AFK

Implement DELTA-Werg-001 items D1.7–D1.8 in the engine and rewire `skein.spools.devflow` (D2.1–D2.3) to use them, removing the per-stage revision-wrapper boilerplate.

## TASK-Werg-002.P2 Must implement exactly

- **TASK-Werg-002.MI1 (D1.7):** weaver-lifetime named workflow registry in `skein.spools.workflow`: `(register-workflow! name constructor-sym)` (keyword name, fully qualified symbol; duplicate name replaces for reload workflows), `(workflow-definition name)` (fails loudly on unknown), `(registered-workflows)` introspection. A checkpoint choice `:next` may be a registered keyword name as well as a symbol; resolution happens at `choose!` time and fails loudly on an unregistered name.
- **TASK-Werg-002.MI2 (D1.8):** choice maps accept `:revise {:params {...}}` (mutually exclusive with `:next`, enforced loudly at build time): choosing it re-pours the current root's `workflow/definition` under the same run-id, with params `(merge context choice-input override-params)` where the `:revise` params are authoritative and persist as the new root's `workflow/context`. Same single-transaction semantics as `:next` routing. Fails loudly when the root has no resolvable `workflow/definition`.
- **TASK-Werg-002.MI3:** forward-routing loop-state hygiene: `:revise` override params are stage-local — implement engine support so params carried in `:revise` overrides are dropped when a later `:next`/named route leaves the stage (keep it simple: record override keys on the root, e.g. `workflow/stage-params`, and dissoc them from continuation params in `route-plan`). This replaces devflow's `enter-*` wrapper dissoc behavior; verify with the existing `:revision`-does-not-leak tests.
- **TASK-Werg-002.MI4 (D2.1–D2.3):** rewire devflow: register all stage constructors in `install!` (and on namespace load for REPL use) under the existing registry keys; forward `:next` targets become registered names; `:revise` choices replace `intake-revision-workflow`, `proposal-revision-workflow`, `spec-plan-revision-workflow`, `task-breakdown-revision-workflow`, `direct-implementation-revision-workflow`, and the `enter-*-workflow` wrappers — delete those fns; abort's required `{:reason}` becomes a declared `:input` on every abort choice (D1.2 from task 1). Devflow keeps identical observable stage behavior (existing devflow tests keep passing, updated only where they referenced deleted wrapper fns or old titles).

## TASK-Werg-002.P3 Done when

- **TASK-Werg-002.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- **TASK-Werg-002.DW2:** Tests cover: named `:next` resolution (and loud unknown-name failure), `:revise` loop skipping condition-gated steps, revise-then-approve not leaking stage-local params downstream, in-flight rename resilience (re-registering a name points existing runs' choices at the new constructor).
- **TASK-Werg-002.DW3:** workflow.md §5 documents named routing + `:revise` (and updates the “stringified symbol” durability warning); devflow.md §3/§5 rewritten for the wrapper-less model.

## TASK-Werg-002.P4 Out of scope

- **TASK-Werg-002.OS1:** describe/history/archive (task 3); `.skein` ops (task 4); commits.

## TASK-Werg-002.P5 References

- **TASK-Werg-002.REF1:** [DELTA-Werg-001](../specs/workflow-spool-contract.delta.md) D1.7–D1.8, D2.1–D2.3; [PLAN-Werg-001](../workflow-ergonomics.plan.md) PH2, R3.
- **TASK-Werg-002.REF2:** `route-plan`/`choose!` in `src/skein/spools/workflow.clj`; `fresh-stage-entry`, `enter-*`, `*-revision-workflow` fns and `workflow-registry` in `src/skein/spools/devflow.clj`; devflow tests pinning revision/leak semantics.
