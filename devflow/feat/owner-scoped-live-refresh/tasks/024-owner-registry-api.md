# Task 24: Publish owner registry API for spool domains

**Document ID:** `TASK-Olr-024`

## TASK-Olr-024.P1 Scope

Type: AFK

Expose the Task 1 owner-partition kernel as a blessed reusable Clojure primitive for first-party and external spool domains. Own new `src/skein/api/registry/alpha.clj`, its internal plumbing/specs, tests, root-spec delta amendments, and generated API source registration.

## TASK-Olr-024.P2 Must implement exactly

- **TASK-Olr-024.MI1:** Provide a small explicit API to declare a kind (id, entry spec, binding-moment datum, layer policy), replace/remove one complete owner partition, inspect an immutable snapshot/effective view, and explain shadowed/override state. A declared kind becomes a valid contribution-map key published by the refresh kernel (DELTA-OlrRepl-001.CC5/CC13).
- **TASK-Olr-024.MI2:** Preserve Task 1 semantics exactly: stable owner, omission deletion, pre-publication validation, unauthorized collision refusal, explicit displaced owner/key, shadowed partition retention, deterministic restoration, and snapshot reads.
- **TASK-Olr-024.MI3:** Keep domain effects outside the primitive. Chime baseline seeding, cron wake changes, workflow state, agent process handles, and other lifecycle behavior remain domain calls around registry publication.
- **TASK-Olr-024.MI4:** Make registry handles runtime-owned and compatible with versioned spool-state; no module-level mutable singleton or raw `skein.core.*` dependency is required by shared spools.
- **TASK-Olr-024.MI5:** Add `registry` to the blessed Alpha Surface delta and document the API in the REPL delta before task completion.

## TASK-Olr-024.P3 Done when

- **TASK-Olr-024.DW1:** External-style tests build two independent registries in two unpublished runtimes and prove no cross-talk, all override/deletion transitions, malformed validator failures, and snapshot concurrency. A test kind declared through the API is accepted as a contribution-map key and published by the kernel; an undeclared kind is refused loudly.
- **TASK-Olr-024.DW2:** `clojure -M:test skein.api.registry.alpha-test skein.core.weaver.owner-registry-test` passes.
- **TASK-Olr-024.DW3:** `make fmt-check lint reflect-check` and `make api-docs` pass with only expected generated changes.

## TASK-Olr-024.P4 Out of scope

- **TASK-Olr-024.OS1:** Do not convert any domain registry or add a generic effects/transaction protocol.

## TASK-Olr-024.P5 References

- **TASK-Olr-024.REF1:** `PLAN-Olr-001.A6`, `DELTA-OlrDrt-001.CC4`, and task-DAG review note `t9itj` H1.
