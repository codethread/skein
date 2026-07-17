# Roster spool

> This is the **contract** doc: the `roster/*` vocabulary, staleness/await
> semantics, the automatic workflow/devflow integration, and the CLI op. Its two
> companions are [`roster.cookbook.md`](./roster.cookbook.md) — worked
> composition recipes (how/why you track a job, await quiet before landing, or
> let workflow/devflow roots track themselves) — and
> [`roster.api.md`](./roster.api.md) — generated fn signatures and docstrings.
> Reach for the cookbook when you want a runnable pattern, the API doc when you
> want an exact arity, and this doc for what the spool promises.

The roster spool is the durable, graph-native answer to "what work is active in this weaver?" It records active work as ordinary strands under a shared `roster/*` attribute vocabulary, exposes explicit-runtime helpers and a `strand roster` op for tracking, and makes quiet/stale states awaitable — without replacing workflow runs, devflow stages, kanban lanes, agent-run run state, or `strand branches` (SPEC-RosterSpool-001.P1). It summarizes work roots for coordination and enforces no locks, ownership, merge gates, or exclusivity (SPEC-RosterSpool-001.NG1).

`skein.spools.roster` is an ordinary reference spool: a workspace approves the local-root coordinate `skein.spools/roster` in its `spools.edn` (see [`spools/README.md`](./README.md)) and activates it (see [Activation](#activation)). It does not ship on the weaver classpath.

The durable contract of record is this document plus the feature spec `SPEC-RosterSpool-001`; section ids below cross-reference it.

## Model

A roster entry is one strand marked with the `roster/entry` attribute string `"true"` (SPEC-RosterSpool-001.P4). Active entries are ordinary active strands; `finish!` closes the strand and records the final roster attributes, so finished/abandoned entries remain inspectable through normal `show`/graph tools but drop out of the active roster surface. An entry is either a purpose-built strand created by `start!`, or an existing root strand restamped in place — the public views treat both shapes identically.

An entry's identity lives in the cross-spool `feature`, `owner`, `branch`, `worktree`, and `body` convention keys — the same keys kanban and branch visibility already read. Roster writes them as found and owns no prefixed twin of them. Roster state the graph has no word for lives under the `roster/*` topic:

| ID | Attribute | Meaning |
| --- | --- | --- |
| **A1** | `roster/entry` | String `"true"` marker for roster entries. |
| **A2** | `feature` | Required non-blank feature/work slug used for scoped listing and await. |
| **A3** | `owner` | Required non-blank driver identity: human, harness, or run id. |
| **A4** | `roster/phase` | `active`, `finished`, or `abandoned`; active entries are active strands, final phases are closed strands. |
| **A5** | `branch` | Optional branch name for merge/worktree visibility. |
| **A6** | `worktree` | Optional worktree path. |
| **A7** | `roster/engine` | Optional engine/source label: `workflow`, `devflow`, `afk`, `agent`, or `manual`. |
| **A8** | `roster/run-id` | Optional engine run identifier. |
| **A9** | `roster/source-id` | Optional source/root strand id when the entry mirrors an existing graph root. |
| **A10** | `roster/started-at` | ISO-8601 instant recorded by `start!` when missing. |
| **A11** | `roster/heartbeat-at` | ISO-8601 instant used for staleness decisions; graph-derived and explicit heartbeats update it. |
| **A12** | `roster/finished-at` | ISO-8601 instant recorded by `finish!`. |
| **A13** | `roster/outcome` | Optional final outcome string, e.g. `done`, `abandoned`, or `failed`. |
| **A14** | `body` | Optional human-readable context when the title is not enough. |

(Attribute ids above are the `SPEC-RosterSpool-001.A*` series.)

## Staleness and awaiting

Staleness is a **visible derived status**, never a silent cleanup (SPEC-RosterSpool-001.G5/NG4). The default stale threshold is fifteen minutes (SPEC-RosterSpool-001.C6); callers override it per call with `:stale-after-ms`, and a non-positive threshold fails loudly.

- `list`/`roster list` annotate every row with `:stale?` and `:age-ms` computed from `roster/heartbeat-at`.
- `await-quiet!`/`roster await-quiet` return `{:reason :stale ...}` as soon as any selected entry exceeds the threshold — stale work short-circuits ahead of declaring quiet.

Stale and finished work is never hidden or auto-burned; cleanup and finishing are deliberate mutations (SPEC-RosterSpool-001.NG4).

## Automatic workflow/devflow integration

Roster registers one async graph-integration event handler during `install!` (SPEC-RosterSpool-001.C11). It rides the shipped event-helper API (`skein.api.events.alpha`, `:strand/added`/`:strand/updated`; SPEC-004.C64–C74) and reads only public strand attributes written by workflow/devflow roots — coupling is one-directional, so no lower-tier core namespace ever depends on roster (SPEC-RosterSpool-001.C12).

For every strand add/update the handler either:

- **Auto-stamps** a sufficient, unstamped graph root into a roster entry, or
- **Refreshes the heartbeat** of the active roster entry that roots the touched strand's `parent-of` ancestry (including the strand itself), so graph-tracked flows stay fresh without an explicit `heartbeat!` (SPEC-RosterSpool-001.C14).

A root is **sufficient** for auto-stamping when it is active, is not workflow plumbing (`workflow/role` absent or not `root`/`procedure`/`digest`), is a graph root (no incoming `parent-of` edge), and carries both a feature slug and an owner (SPEC-RosterSpool-001.C13):

- feature slug, in priority order: `workflow/run-id`, `devflow/feature`, `feature`.
- owner, in priority order: `owner`, `workflow/actor`, `devflow/actor`, `actor`.

A root that already carries `branch`/`worktree` (as work-tree roots do per the repo's Branch work visibility convention) keeps them on the entry, so branch-scoped `roster list`/`await-quiet!` find auto-tracked workflow/devflow roots.

`roster/engine` is derived `devflow` when `devflow/feature` is present, else `workflow` when `workflow/run-id` is present. Roster's own bookkeeping writes (fresh/just-restamped entries and heartbeat-only refreshes) are ignored so the async loop never feeds itself. Because the auto-heartbeat runs on the async event worker independent of any direct caller, `start!`/`heartbeat!`/`finish!` send only their own attribute delta (not the whole entry snapshot), so a heartbeat committing just after a concurrent `finish!` can never roll `roster/phase` back to `active` (SPEC-RosterSpool-001.C4); a heartbeat that loses the race against a finish is a benign no-op.

Workflow/devflow roots **missing** a feature slug or owner are the negative case: the spool never invents identity, and the driver must call `start!` explicitly. AFK loops, ad hoc sessions, and any engine whose roots cannot be inferred or that does not mutate the graph during a run own the explicit `start!` → `heartbeat!` → `finish!` contract (SPEC-RosterSpool-001.C15/G6).

## Runtime API

Every public helper takes `runtime` as its first argument and never resolves the ambient runtime itself, so the spool composes across published daemons, test runtimes, and side-by-side worlds (SPEC-RosterSpool-001.P5). Malformed attrs, missing ids, non-roster ids, closed heartbeat/finish targets, and invalid thresholds all fail loudly. Timestamps fail loudly at the seam too: a non-`Instant` `:now` override is rejected at `start!`/`heartbeat!`/`finish!`, and a missing or unparseable `roster/heartbeat-at` (e.g. from a hand-stamped `roster/entry`) fails staleness derivation with a contextual error naming the strand rather than a bare parse/NPE. The public seam input shapes (`::start-attrs`, `::finish-opts`, `::list-opts`, `::await-quiet-opts`) are declared as `clojure.spec` specs matching sibling spools, with manual checks layered for closed key sets and `start!`'s id-derivable feature/owner.

| Fn | Behavior |
| --- | --- |
| `(start! runtime attrs)` | **C1** — Create or restamp one roster entry. `attrs` requires non-blank `:feature` and `:owner` unless `:id` names an existing strand that already carries them. `:id` restamps that strand into a roster entry in place (merging roster attributes, forcing it active); omitting `:id` creates a new entry, optionally recording `:source-id`. Other optional keys: `:title`, `:body`, `:branch`, `:worktree`, `:engine`, `:run-id`, and `:now` (an `Instant` override for deterministic callers/tests). |
| `(heartbeat! runtime entry-id & [opts])` | **C2** — Update `roster/heartbeat-at` on an active entry. Refuses loudly for a missing, closed/finished, or non-roster id (a finished entry is never re-heartbeated) and sends only the timestamp delta. `opts` accepts `:now`. |
| `(finish! runtime entry-id opts)` | **C3** — Record `roster/phase` (`"finished"` default or `"abandoned"`), `roster/finished-at`, and optional `roster/outcome`, then close the entry. `opts` also accepts `:now`. An unrecognized phase, non-roster/closed ids, or malformed opts fail loudly. |
| `(list runtime opts)` | **C4** — Active entries as `{:strand <normalized> :stale? bool :age-ms long}`, sorted by strand id, optionally scoped by `:feature`, `:owner`, `:branch`, `:worktree`, or `:engine`, derived against `:stale-after-ms`. |
| `(await-quiet! runtime opts)` | **C5** — Block until the selected scope has no active non-stale entries. Returns `{:reason :quiet\|:stale\|:timeout :entries [...]}`. Scopes on `:feature`, `:branch`, `:worktree`; `:timeout-secs` (default thirty minutes), `:stale-after-ms` (default fifteen minutes), and `:poll-ms` (default fifty) tune the wait. `:stale` short-circuits ahead of `:quiet`. |

The default stale threshold is `skein.spools.roster/default-stale-after-ms` (fifteen minutes) and the default await timeout is `default-timeout-secs` (1800, matching `workflow/await!`'s `:timeout-secs` default).

## CLI op

`install!` registers one declared-subcommand `roster` op (SPEC-RosterSpool-001.P6). `strand help roster` shows the machine-readable verb/flag surface, and `strand roster help`/`-h`/`--help` return that same detail; bare `strand roster` and unknown verbs fail loudly with the available subcommand names. `await-quiet` registers the unbounded deadline class so it can block for arbitrarily long coordination waits (SPEC-RosterSpool-001.C10).

```sh
strand roster prime
strand roster about
strand roster start --feature <slug> --owner <owner> [--branch <branch>] [--worktree <path>] [--engine <engine>] [--run-id <id>] [--source-id <strand-id>] [--body <text>]
strand roster heartbeat <entry-id>
strand roster finish <entry-id> [--phase finished|abandoned] [--outcome <text>]
strand roster list [--feature <slug>] [--owner <owner>] [--branch <branch>] [--worktree <path>] [--engine <engine>] [--stale-after-secs <n>]
strand roster await-quiet [--feature <slug>] [--branch <branch>] [--worktree <path>] [--timeout-secs <n>] [--stale-after-secs <n>]
```

- `prime` is the agent onboarding surface: a superset of `about` that adds the working discipline — when automatic tracking applies, when explicit `start!` is required, how to heartbeat, and how to finish (SPEC-RosterSpool-001.C7).
- `about` is the terse authored manual: purpose, `roster/*` vocabulary, API summary, and the command surface, without duplicating parser-generated usage (SPEC-RosterSpool-001.C8).
- `list` and the named query default to **active** entries. Finished entries are not part of the initial public list/query surface; `finish!` leaves closed strands inspectable through ordinary tools, and any richer finished-entry audit view is future work (SPEC-RosterSpool-001.C9/Q2).

## Query

`install!` also registers the `roster` named query over active roster entries:

```sh
strand list --query roster
strand ready --query roster
```

It resolves to `[:and [:= :state "active"] [:= [:attr "roster/entry"] "true"]]`.

## Activation

A workspace approves the spool root in its `spools.edn`:

```clojure
{:spools {skein.spools/roster {:local/root "../spools/roster"}}}
```

and activates it from `init.clj`:

```clojure
(runtime/use! runtime :skein/spools-roster
              {:ns 'skein.spools.roster
               :spools ['skein.spools/roster]
               :call 'skein.spools.roster/install!})
```

`install!` resolves the active runtime at the activation boundary (as other shipped spools do), registers the async integration event handler, the `roster` op, and the `roster` named query, and returns installation metadata (`:installed`, `:namespace`, `:watcher`, `:ops`, `:queries`). The explicit-runtime helpers above are the composition-friendly surface for trusted Clojure that already holds a runtime.

## Examples

The worked, compositional examples live in [`roster.cookbook.md`](./roster.cookbook.md): tracking a long-running job with `start!`/`heartbeat!`/`finish!`, using `await-quiet!` as a fan-in barrier before landing, and letting workflow/devflow roots track themselves. Each recipe pairs a runnable snippet with the reasoning behind its shape.

The spool's behavior is driven end-to-end against a real weaver runtime by [`test/skein/roster_test.clj`](../test/skein/roster_test.clj), which doubles as an executable reference for every documented behavior.
