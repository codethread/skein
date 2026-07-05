# Roster Spool Spec

**Document ID:** `SPEC-RosterSpool-001`
**Status:** Implemented
**Last Updated:** 2026-07-05
**Related RFCs:** [RFC-014 Feature Tracking Registry](../../../rfcs/2026-07-02-feature-tracking-registry.md)
**Code:** `spools/src/skein/spools`, `src/skein/api/*.alpha`, `src/skein/core/weaver`

## SPEC-RosterSpool-001.P1 Purpose

The roster spool is the durable graph-native convention for answering "what work is active in this weaver?" It records active work entries as ordinary strands with a shared `roster/*` attribute vocabulary, exposes REPL and CLI helpers for explicit tracking, and makes quiet/stale states awaitable without replacing the workflow, devflow, kanban, shuttle, or branch-visibility surfaces.

## SPEC-RosterSpool-001.P2 Goals

- **SPEC-RosterSpool-001.G1:** Give agents and coordinators one reusable classpath-shipped spool, `skein.spools.roster`, for active-work visibility across repos.
- **SPEC-RosterSpool-001.G2:** Keep roster state inspectable as ordinary strands and attributes instead of runtime-only process memory.
- **SPEC-RosterSpool-001.G3:** Support explicit-runtime Clojure workflows through `track!`, `heartbeat!`, `finish!`, `roster`, and `await-quiet!`.
- **SPEC-RosterSpool-001.G4:** Support public automation through a declared-subcommand `strand roster` operation and a named `roster` query.
- **SPEC-RosterSpool-001.G5:** Surface stale entries loudly. Stale work remains visible and makes awaiters return `:stale`; it is never silently hidden or auto-burned.
- **SPEC-RosterSpool-001.G6:** Register workflow/devflow roots automatically where graph metadata is sufficient, while leaving AFK and ad hoc engines with an explicit `track!`/`heartbeat!`/`finish!` contract.

## SPEC-RosterSpool-001.P3 Non-goals

- **SPEC-RosterSpool-001.NG1:** Roster does not enforce locks, ownership, merge gates, or exclusivity.
- **SPEC-RosterSpool-001.NG2:** Roster does not replace card lanes, workflow run state, shuttle run state, devflow stages, or `strand branches`; it summarizes work roots for coordination.
- **SPEC-RosterSpool-001.NG3:** Roster does not aggregate across sibling weavers. Same-machine or remote aggregation belongs to future guild-facing composition over this spool's local answer.
- **SPEC-RosterSpool-001.NG4:** Roster does not auto-close, auto-burn, or auto-hide stale or finished entries. Cleanup and finishing are deliberate mutations.

## SPEC-RosterSpool-001.P4 Data model

A roster entry is a strand marked with `roster/entry` string `"true"`. Active entries use strand `state` `active`; `finish!` closes the strand and records final roster attributes. Implementations may create a new entry strand or restamp an explicitly supplied existing root strand, but the public views treat both shapes as roster entries.

| ID | Attribute | Meaning |
| --- | --- | --- |
| **SPEC-RosterSpool-001.A1** | `roster/entry` | String `"true"` marker for roster entries. |
| **SPEC-RosterSpool-001.A2** | `roster/feature` | Required non-blank feature/work slug used for scoped listing and await. |
| **SPEC-RosterSpool-001.A3** | `roster/owner` | Required non-blank driver identity, e.g. human, harness, or run id. |
| **SPEC-RosterSpool-001.A4** | `roster/status` | `active`, `finished`, or `abandoned`; active entries remain active strands, final statuses are closed strands. |
| **SPEC-RosterSpool-001.A5** | `roster/branch` | Optional branch name for merge/worktree visibility. |
| **SPEC-RosterSpool-001.A6** | `roster/worktree` | Optional absolute or caller-supplied worktree path. |
| **SPEC-RosterSpool-001.A7** | `roster/engine` | Optional engine/source label such as `workflow`, `devflow`, `afk`, `agent`, or `manual`. |
| **SPEC-RosterSpool-001.A8** | `roster/run-id` | Optional engine run identifier. |
| **SPEC-RosterSpool-001.A9** | `roster/source-id` | Optional source/root strand id when the entry mirrors an existing graph root. |
| **SPEC-RosterSpool-001.A10** | `roster/started-at` | Required ISO-8601 instant recorded by `track!` when missing. |
| **SPEC-RosterSpool-001.A11** | `roster/heartbeat-at` | Required ISO-8601 instant used for staleness decisions; graph-derived and explicit heartbeats update this value. |
| **SPEC-RosterSpool-001.A12** | `roster/finished-at` | ISO-8601 instant recorded by `finish!` for final entries. |
| **SPEC-RosterSpool-001.A13** | `roster/result` | Optional final reason/outcome string such as `done`, `abandoned`, or `failed`. |
| **SPEC-RosterSpool-001.A14** | `roster/body` | Optional human-readable context when the strand title is not enough. |

The unprefixed `feature`, `owner`, `branch`, and `worktree` attributes remain valid adjacent conventions for kanban and branch visibility. Roster helpers may stamp both prefixed and established unprefixed attributes on entries they create so existing queries remain useful, but `roster/*` is the contract for this spool.

## SPEC-RosterSpool-001.P5 Runtime API

All public Clojure functions take `runtime` as their first argument and never resolve ambient runtime themselves.

- **SPEC-RosterSpool-001.C1:** `(track! runtime attrs)` creates or restamps one roster entry. `attrs` must include non-blank `:feature` and `:owner` unless an existing source strand supplies them. Optional keys include `:id`, `:title`, `:body`, `:branch`, `:worktree`, `:engine`, `:run-id`, `:source-id`, and `:now` (which must be an `Instant`). Invalid input fails loudly. Mutations send only the roster attribute delta, not the whole entry snapshot, so a restamp never reverts a concurrent write. The public seam input shapes are declared as `clojure.spec` specs matching sibling spools; manual checks cover closed key sets and the id-derivable feature/owner requirement.
- **SPEC-RosterSpool-001.C2:** `(heartbeat! runtime entry-id & [opts])` updates `roster/heartbeat-at` on an active roster entry, sending only that delta. Missing, closed/finished, or non-roster ids fail loudly; a finished entry is never re-heartbeated. A concurrent auto-heartbeat that loses the race against a `finish!` is a benign no-op and can never resurrect a closed entry to `active`.
- **SPEC-RosterSpool-001.C3:** `(finish! runtime entry-id opts)` records `roster/status`, `roster/finished-at`, and optional `roster/result` as a minimal delta, then closes the entry. Missing or non-roster ids fail loudly. Because it sends only its own keys, a concurrent heartbeat cannot roll its final status back.
- **SPEC-RosterSpool-001.C4:** `(roster runtime opts)` returns active roster entries, optionally scoped by `:feature`, `:owner`, `:branch`, `:worktree`, or `:engine`. Each row includes the underlying strand and derived `:stale?`/`:age-ms` data using the caller's `:stale-after-ms` or the spool default. A missing or unparseable `roster/heartbeat-at` (e.g. from a hand-stamped entry that bypassed `track!`) fails loudly with an error naming the offending strand rather than a bare parse/NPE.
- **SPEC-RosterSpool-001.C5:** `(await-quiet! runtime opts)` blocks until the selected scope has no active non-stale entries, returns immediately with stale entries when any selected entry exceeds the stale threshold, or returns on timeout. The result shape is `{:reason :quiet|:stale|:timeout :entries [...]}`.
- **SPEC-RosterSpool-001.C6:** The default stale threshold is fifteen minutes. Callers may override it per `roster` or `await-quiet!` call with `:stale-after-ms`; non-positive thresholds fail loudly.

## SPEC-RosterSpool-001.P6 CLI and discovery

The spool installs one declared-subcommand operation named `roster` and one named query named `roster`.

```text
strand roster prime
strand roster about
strand roster track --feature <slug> --owner <owner> [--branch <branch>] [--worktree <path>] [--engine <engine>] [--run-id <id>] [--source-id <strand-id>] [--body <text>]
strand roster heartbeat <entry-id>
strand roster finish <entry-id> [--status finished|abandoned] [--result <text>]
strand roster list [--feature <slug>] [--owner <owner>] [--branch <branch>] [--worktree <path>] [--engine <engine>] [--stale-after-ms <n>]
strand roster await-quiet [--feature <slug>] [--branch <branch>] [--worktree <path>] [--timeout-ms <n>] [--stale-after-ms <n>]
```

- **SPEC-RosterSpool-001.C7:** `prime` returns working discipline for agents: when automatic tracking applies, when explicit tracking is required, how to heartbeat, and how to finish.
- **SPEC-RosterSpool-001.C8:** `about` returns the authored manual: purpose, attribute vocabulary, API summary, and examples. It does not duplicate parser-generated usage.
- **SPEC-RosterSpool-001.C9:** `list` and the named query default to active entries. Finished entries are not part of the initial public list/query surface; `finish!` leaves closed strands inspectable by ordinary `show`/graph tools, and any richer audit view is future work.
- **SPEC-RosterSpool-001.C10:** `await-quiet` uses an unbounded op deadline class and returns one JSON object with the same `:reason` semantics as the REPL helper.

## SPEC-RosterSpool-001.P7 Integration semantics

- **SPEC-RosterSpool-001.C11:** The shipped spool registers event handlers or hooks by symbol during `install!` so graph roots from workflow/devflow can be roster-stamped when they carry a sufficient metadata set.
- **SPEC-RosterSpool-001.C12:** Automatic stamping must not add hard dependencies from lower-tier core namespaces to downstream spools. Integration lives in `skein.spools.roster` and uses public strand attributes from workflow/devflow roots.
- **SPEC-RosterSpool-001.C13:** A root is sufficient for automatic roster stamping when it is active, not workflow plumbing (`workflow/role` is absent or not `molecule`, `procedure`, or `digest`), and has a feature slug from `workflow/run-id`, `devflow/feature`, `feature`, or `roster/feature`, plus an owner from `owner`, `roster/owner`, or a workflow/devflow actor attribute if present. When the root already exposes a branch or worktree (plain `branch`/`worktree` or prefixed `roster/branch`/`roster/worktree`), auto-stamping copies it into `roster/branch`/`roster/worktree` so branch-scoped `roster`/`await-quiet!` find the auto-tracked root. Workflow/devflow roots without a feature slug or owner are a negative case: the spool must not invent identity and the driver must call `track!` explicitly.
- **SPEC-RosterSpool-001.C14:** Any graph mutation event touching an auto-tracked source root, or a descendant of that root over `parent-of` when the source root is discoverable, must refresh the matching active roster entry's `roster/heartbeat-at`. This is required for graph-tracked flows; explicit `heartbeat!` is only for engines that do not mutate the graph during a run or whose roots cannot be inferred.
- **SPEC-RosterSpool-001.C15:** AFK loops and ad hoc sessions should call `track!` at start, `heartbeat!` once per visible unit of progress, and `finish!` in normal completion/abandon/failure cleanup.

## SPEC-RosterSpool-001.P8 Open questions

- **SPEC-RosterSpool-001.Q1:** Whether `track!` should always create a separate roster entry or prefer restamping supplied root strands for automatic workflow/devflow integration.
- **SPEC-RosterSpool-001.Q2:** Whether a future finished-entry audit view is worth adding after the active roster surface ships.
