# Task 12: Convert agent-run live registries and bindings

**Document ID:** `TASK-Olr-012`

## TASK-Olr-012.P1 Scope

Type: AFK

In a linked feature worktree for `/Users/ct/dev/projects/agent-harness.spool`, convert the `ct.spools/agent-run` root's harness, alias, backend, defaults, contracts, and engine declarations to owner-complete refresh against the Skein feature worktree. Preserve every active-run and recovery invariant.

## TASK-Olr-012.P2 Must implement exactly

- **TASK-Olr-012.MI1:** Partition harnesses, aliases, and backends by stable module owner with explicit default/system and workspace owners, complete deletion, authorized override, and joined introspection.
- **TASK-Olr-012.MI2:** Keep in-flight maps, process/session handles, executors, fan-out state, preamble conflicts, contracts, and close functions in versioned spool-state. Migration carries required atom/handle identities rather than replacing them with declaration partitions.
- **TASK-Olr-012.MI3:** Resolve harness/alias/backend for each new process or interactive-session launch, including retry and resume. Persist or retain the resolved launch operations needed by supervise, monitor, capture, stop, and finish so later registry refresh cannot redirect an already-launched process/session.
- **TASK-Olr-012.MI4:** Preserve deferred recovery for temporarily missing harnesses, alias-cycle failures, fan-out claims, supersession, usage capture, and default registration behavior.
- **TASK-Olr-012.MI5:** Replace install-only publication with contribution/reconcile entry points while keeping business API and frozen `strand agent` vocabulary unchanged.
- **TASK-Olr-012.MI6:** Before editing, record the post-Task-7 Skein commit supplied by the coordinator and test against a dedicated immutable Skein baseline worktree at that commit, not the concurrently moving feature worktree.

## TASK-Olr-012.P3 Done when

- **TASK-Olr-012.DW1:** Keystone test launches a long-running shell harness, refreshes the alias to another command, proves the first process and capture/stop path keep launch bindings, and proves a later launch uses the replacement.
- **TASK-Olr-012.DW2:** Tests cover owner deletion/override/restoration, defaults plus workspace entries, retry/resume binding, deferred recovery, interactive supervision, state migration identity, and no dropped in-flight run.
- **TASK-Olr-012.DW3:** `clojure -M:test ct.spools.agent-run-test ct.spools.subagent-test`, repository format/lint/API-doc gates, and state-shape checks pass against the recorded Skein baseline commit.

## TASK-Olr-012.P4 Out of scope

- **TASK-Olr-012.OS1:** Do not change durable agent-run attributes, frozen CLI vocabulary, process execution semantics, or publish/tag a release.

## TASK-Olr-012.P5 References

- **TASK-Olr-012.REF1:** `agent-run/src/ct/spools/agent_run.clj`, `DELTA-OlrDrt-001.CC8/CC10`, Opus notes `a22m2` F1/F2 and `kxhd4` R1.
