# Shuttle Spool Proposal

**Document ID:** `SHU-PROP-001` **Status:** Shipped **Last Updated:** 2026-07-02 **Related RFCs:** None **Related root specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [Strand Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md)

## SHU-PROP-001.P1 Problem

Coding agents routinely spawn other agents: a driving session delegates a review, a research task, or an isolated implementation slice to a subagent. Today that capability is locked inside each harness's own proprietary tool (for example Claude Code's Agent tool), with the harness choice fixed, the orchestration imperative and invisible, and the run state lost when the driving session dies.

Skein already owns the durable pieces of that story: strands are durable typed records, `depends-on` readiness gives declarative sequencing, the event system reacts to mutations, and `strand op` gives low-trust CLI agents named handles to trusted weaver behavior. What is missing is a userland spool that composes them into agent spawning: create a strand describing an agent run, have a listener spawn the matching harness process, record results back onto the graph, and let one harness spawn another (Claude Code spawning `pi`, or the reverse) through nothing but strand creation.

This is also the next deep-module stress test. The workflow spool proved the structural primitives (batch, subgraph, ready, query). Shuttle targets the reactive and annotation primitives the spool programme has not yet exercised: event handlers that mutate the graph, respawn reconciliation from durable state, and append-only memory built from annotation edges. The spool must require zero new core surface; anywhere it forces a core addition is a recorded primitive gap, and every registry it never touches is a removal vote.

## SHU-PROP-001.P2 Goals

- **SHU-PROP-001.G1:** Spawn an agent in a user-chosen harness by creating a strand with agreed attributes. The shuttle listener spawns a run when its strand becomes ready, so a run strand created with unmet `depends-on` edges waits and an undepended one spawns immediately. Spawning is asynchronous by default — creation returns immediately and nothing blocks; callers observe progress by reading run strands through exposed helpers/ops, with an explicit `await`/`block-on` handle as the opt-in synchronous convenience.
- **SHU-PROP-001.G2:** Define harnesses and aliases in trusted userland config as data-first registry entries, so users layer their own named agent types (for example `reviewer` = claude with a fixed prompt prefix) without any core or spool change.
- **SHU-PROP-001.G3:** Make cross-harness and recursive spawning work by construction: a spawned agent holds the `strand` CLI, so it spawns further agents by creating strands, and `parent-of` records the spawn tree.
- **SHU-PROP-001.G4:** Lean into crash recovery instead of crash avoidance. The shuttle lives in the weaver and dies with it; the agent-run strands are durable, so a restarted weaver respawns still-active runs. Runs are framed as resumable-by-design: a review restarts from scratch, a coding task observes its predecessor's dirty worktree and continues.
- **SHU-PROP-001.G5:** Give runs durable, append-only memory as strands attached through annotation edges, with retrieval and append helpers surfaced to agents (op handles for CLI workers, functions for trusted REPL work), so a respawned successor can recover what its predecessor knew.
- **SHU-PROP-001.G6:** Record run results as attributes on the run strand itself; consumers filter `strand list` output downstream (for example with `jq`) rather than Skein owning slim projections.
- **SHU-PROP-001.G7:** Use `depends-on` readiness as the only concurrency primitive: the ready set is the spawnable set, fan-out is N undepended run strands spawning concurrently, and multi-agent pipelines are declarative graph data that compose with workflow-spool gates (`workflow/gate :subagent`) without either spool knowing about the other. Any spawn throttle is a spool config knob or userland convention, never core.
- **SHU-PROP-001.G7a:** Propagate results up subagent chains by caller pull and synthesis: a caller reads its direct children's result attributes (optionally via `await`) and synthesizes them into its own result attribute — its report to its own caller — while every descendant's full result stays on its strand and the spawn tree remains navigable through `parent-of`.
- **SHU-PROP-001.G8:** Ship Claude Code and `pi` as the two v1 harness definitions — two harnesses being the minimum to prove the abstraction is not shaped around one tool.
- **SHU-PROP-001.G9:** Require zero new core surface; document any primitive gap the spool exposes (and any core registry it demonstrates to be unnecessary) as programme findings.

## SHU-PROP-001.P3 Non-goals

- **SHU-PROP-001.NG1:** No external shuttle worker process, mill supervision changes, or new core watch/subscribe transport in v1; the listener is in-weaver via the existing event system.
- **SHU-PROP-001.NG2:** No conditional-update or claim/lease core primitive; the single in-weaver executor makes claim races out of scope.
- **SHU-PROP-001.NG3:** No sandboxing, permission enforcement, or resource quotas for spawned agents; per TEN-002 spawned agents are trusted, and guardrails are userland conventions.
- **SHU-PROP-001.NG4:** No interactive sessions, PTY bridging, or live output streaming in v1; runs are headless batch-style processes with durable results and memory. The harness registry keeps the launcher an explicit dimension so a future tmux launcher (observable, attachable, surviving weaver death) can be added without changing the run contract.
- **SHU-PROP-001.NG5:** No remote or cross-machine execution; harness processes run on the local machine beside the weaver.
- **SHU-PROP-001.NG6:** No scheduler features beyond readiness (no priorities, fairness, or concurrency pools) in v1.
- **SHU-PROP-001.NG7:** No general job-queue product; the scope is agent harness spawning, even though the shape resembles a queue.

## SHU-PROP-001.P4 Proposed scope

- **SHU-PROP-001.S1:** A `shuttle` spool (trusted userland Clojure, activated through the standard spool workspace flow) owning a data-first harness registry with alias layering, loaded from workspace config.
- **SHU-PROP-001.S2:** A documented agent-run attribute vocabulary (harness, prompt, phase, isolation, session identity, result, spawn provenance) that constitutes the spool's public contract; the strand is the API.
- **SHU-PROP-001.S3:** Readiness-driven spawning through registered event handlers (readiness recomputed from mutation events, since readiness is derived state), plus startup reconciliation that respawns still-active runs and surfaces stale runs loudly after a weaver crash.
- **SHU-PROP-001.S3a:** A launcher dimension in the harness registry with one v1 implementation: headless in-weaver subprocess execution.
- **SHU-PROP-001.S4:** A `strand op` surface exposing named handles for low-trust CLI agents: spawn, list runs, await, kill, and memory append/retrieve.
- **SHU-PROP-001.S5:** Append-only run memory as strands linked with annotation edges, with append and retrieval helpers for both op and REPL consumers.
- **SHU-PROP-001.S6:** Claude Code and `pi` harness definitions shipped as the reference registry entries, including result capture back onto the run strand.
- **SHU-PROP-001.S7:** Attribute-vocabulary alignment with the workflow spool so a `workflow/gate :subagent` step can be fulfilled by a shuttle run, with the closing harness recorded.
- **SHU-PROP-001.S8:** Tests and docs following the library-author testing conventions, plus a findings note recording primitive gaps and unused-core observations for the spool programme.

## SHU-PROP-001.P5 Open questions

- **SHU-PROP-001.Q1:** Crash-loop guard: should respawn-on-restart carry a bounded attempt count recorded on the run strand, and what happens (fail-loud attribute? close?) when it is exhausted?
- **SHU-PROP-001.Q2:** Session continuation in v1: do the Claude Code and `pi` harness definitions resume a recorded session id on respawn, or is v1 always a fresh spawn that relies on graph memory and worktree state?
- **SHU-PROP-001.Q3:** Where does the spool live: a directory in this repo consumed via `spools.edn` local roots (like the workflow worktree's approach), or a separate repo under `~/dev/projects`? Related: whether `skein.spools.ephemeral` should move out alongside it.
- **SHU-PROP-001.Q4:** Memory shape details: one memory strand per entry versus a per-run journal strand, and the annotation edge relation name — to be fixed in the plan after prototyping retrieval ergonomics.
- **SHU-PROP-001.Q5:** Does v1 need any spawn throttle knob (for example max concurrent runs per workspace), or is an unbounded ready set acceptable until real usage says otherwise?

## SHU-PROP-001.P6 Shipped outcome

Shipped in `3e3e8b1 feat: add shuttle agent spool`.

- The shuttle lives as an approved-local-root spool under `spools/shuttle`, activated through `spools.edn`, `skein.runtime.alpha/sync!`, and `runtime/use!`.
- The v1 public surface is the `skein.spools.shuttle` namespace plus `strand op agent`, whose in-band `about` response is the agent-facing manual.
- Runs are ordinary strands with `shuttle/*` attributes. Pending runs spawn from readiness, successful runs close with `shuttle/result`, failed/exhausted runs stay active and block dependents loudly.
- Default harnesses include `claude`, `pi`, and `sh`; the `sh` harness is for deterministic tests and plumbing.
- Memory shipped as one closed note strand per entry, linked by a `notes` annotation edge and `shuttle/note-for` attributes.
- Crash recovery shipped with bounded `shuttle/max-attempts` and pid start-instant verification before killing stale processes.
- No spawn throttle shipped; fan-out remains the ready set until real usage requires a userland throttle knob.
- Core runtime changes were limited to enabling primitives: SQLite write-lock waiting, concurrent JSON socket connection handling, long `op` deadlines, and namespaced JSON attribute key preservation.
- Deferred/cut scope: interactive PTY/tmux launchers, live output streaming, remote execution, resource quotas, and session continuation on respawn.
