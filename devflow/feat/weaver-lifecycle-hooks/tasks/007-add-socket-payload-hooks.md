# Task 7: Add socket payload hooks

**Document ID:** `WLH-TASK-007`

## WLH-TASK-007.P1 Scope

Type: AFK

Add validation-only `:payload/received` hooks to selected JSON socket operations while keeping setup, read-only, and administrative socket operations ungated.

## WLH-TASK-007.P2 Must implement exactly

- **WLH-TASK-007.MI1:** In `src/skein/weaver/socket.clj`, invoke `:payload/received` hooks after JSON decode, protocol validation, identity verification, allowlist resolution, and operation-specific argument shape validation, but before semantic dispatch.
- **WLH-TASK-007.MI2:** Run payload hooks only for `add`, `update`, `supersede`, `burn`, `weave`, and `op` socket requests.
- **WLH-TASK-007.MI3:** Do not run payload hooks for `init`, `status`, `stop`, `show`, `list`, `ready`, `list-query`, `ready-query`, or `pattern-explain`.
- **WLH-TASK-007.MI4:** Payload hook context must include `:hook/type :payload/received`, `:request/source :json-socket`, keyword `:request/operation`, `:request/id`, string-keyed decoded `:request/args` before dispatch reshaping, and decoded `:request/options`.
- **WLH-TASK-007.MI5:** Payload hooks may reject by throwing but must not transform operation names, request arguments, options, or allowlist membership.
- **WLH-TASK-007.MI6:** Hook rejection must return a JSON socket domain error with code `hook/failed` and structured hook/cause details.
- **WLH-TASK-007.MI7:** Protocol errors and malformed operation arguments must still fail before payload hooks run.

## WLH-TASK-007.P3 Done when

- **WLH-TASK-007.DW1:** Socket tests prove payload hooks run for each gated operation: `add`, `update`, `supersede`, `burn`, `weave`, and `op`.
- **WLH-TASK-007.DW2:** Socket tests prove payload hooks do not run for exempt operations, including at least `init`, `status`, `stop`, `show`, `list`, `ready`, and `pattern-explain`.
- **WLH-TASK-007.DW3:** Tests prove payload context contains pre-normalization string-keyed arguments for an `add` or `update` request with attributes.
- **WLH-TASK-007.DW4:** Tests prove a payload hook rejection prevents semantic dispatch and returns a `hook/failed` domain envelope.
- **WLH-TASK-007.DW5:** Existing socket protocol validation tests continue to pass.

## WLH-TASK-007.P4 Out of scope

- **WLH-TASK-007.OS1:** Do not add payload transformation.
- **WLH-TASK-007.OS2:** Do not add hook registration/listing to the JSON socket allowlist.
- **WLH-TASK-007.OS3:** Do not gate `init`, `status`, `stop`, or read-only operations.

## WLH-TASK-007.P5 References

- **WLH-TASK-007.REF1:** [Plan](../weaver-lifecycle-hooks.plan.md) `WLH-PLAN-001.PH4`.
- **WLH-TASK-007.REF2:** [Weaver Runtime delta](../specs/daemon-runtime.delta.md) `WLH-DELTA-001.CC11` through `WLH-DELTA-001.CC13`, `WLH-DELTA-001.CC23` through `WLH-DELTA-001.CC25`.
- **WLH-TASK-007.REF3:** [CLI delta](../specs/cli.delta.md) `WLH-DELTA-003.CC4` through `WLH-DELTA-003.CC8`.
- **WLH-TASK-007.REF4:** `src/skein/weaver/socket.clj` `validate-request`, `handle-request`, and `dispatch`.
