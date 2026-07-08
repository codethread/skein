# Agents Spool Plan

**Status:** Shipped **Last Updated:** 2026-07-03 **Proposal:** [proposal.md](./proposal.md) **RFC:** [RFC-015](./rfcs/2026-07-03-agents-spool.md) — the [op manual draft](./rfcs/2026-07-03-agents-spool.op-manual.md) is the authoritative surface contract for this build.

Deliberately lean: no `tasks/` queue or AFK loop. Coordination runs through shuttle-delegated agent runs from an `agent-plan` strand DAG (per RFC-015.OUT2), with the coordinator (owner session) verifying and closing task strands.

## Phases

1. **Engine seam (shuttle):** stop registering the `agent` op and `agent-failures` query in `install!`; move `council!`/`review!`/about/op-dispatch code out; add the preamble-extension hook and public prompt-building accessors (pinned command). Keep harness registry, spawn/scan/reconcile, await/kill/logs, `note!`/`notes`, run summaries.
2. **Agents spool:** new `spools/agents` local root depending on `../shuttle`; `install!` registers `strand op agent` with about/spawn/ps/await(+`--under`)/logs/kill/note/notes/harnesses/council/review + delegate/delegate `--ready`/retry/status, the `agent-plan` pattern, the `agent-failures` query, and fills the worker-contract preamble hook. `about` prints the dogfooded manual; behavior matches its documented semantics exactly (fail-loudly conditions, return shapes, supersede phase).
3. **Workspace migration:** `.skein/spools.edn` + `init.clj` load agents after shuttle; `config.clj` drops `agent-delegate`, `delegation-policy-text`, and the `agent-plan` pattern (keeps harness aliases incl. `:codex`, chime rules, named queries, devflow/workflow ops); strand skill section defers to `agent about`.
4. **Docs:** `spools/shuttle/README.md` reduced to engine contract (+ `superseded` phase row); new `spools/agents/README.md` (op surface, DAG conventions, worker/coordinator guidance, human user guide per PROP-AgentsSpool-001.G4); CLAUDE.md spool index; `daemon-runtime.md`/`repl-api.md` reference updates; treadle doc re-target check.
5. **Validation:** shuttle tests split (engine vs op surface, ops move to agents tests); `clojure -M:test`, `(cd cli && go test ./...)`, `clojure -M:smoke` green; `git status --short` clean of runtime artifacts.

## Validation gates

- `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`
- `(cd cli && go test ./...)`
- `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`

## Developer Notes

- 2026-07-03: Plan created after RFC-015 acceptance. Surface pre-validated (RFC-015.C7); implementation risk concentrates in the engine seam split and keeping the manual/behavior in lockstep.
- 2026-07-03: Shipped in merge commit `fa34573` / feature commit `66c2a4c`. Delivered `spools/agents` as the `strand op agent` owner, reduced shuttle to the run engine, migrated repo config/init/skill guidance, added the human user guide in `spools/agents/README.md`, and validated with `clojure -M:test`, `cli/go test ./...`, `clojure -M:smoke`, `git diff --check`, and Shuttle review. No cut scope.
