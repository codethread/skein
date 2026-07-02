# Skein Shuttle Spool

## 1. Overview

`skein.spools.shuttle` is a trusted userland spool for spawning headless coding-agent runs from ordinary Skein strands.

A run is a strand carrying `shuttle/*` attributes. Creating the run strand is the API: the installed shuttle event handler watches graph mutations, asks Skein readiness which pending run strands are unblocked, launches the selected harness, records output back onto the run strand, and closes successful runs so downstream `depends-on` work can proceed.

Shuttle is intentionally not core scheduler infrastructure. It composes existing primitives: strand attributes, `depends-on` readiness, `parent-of` provenance, annotation edges, event handlers, runtime spool loading, and `strand op`.

## 2. Loading

Shuttle is shipped as an approved-local-root spool example under `spools/shuttle`. A workspace opts in with `spools.edn` and trusted startup or REPL code:

```clojure
;; .skein/spools.edn
{:spools {skein.spools/shuttle {:local/root "../spools/shuttle"}}}
```

```clojure
(require '[skein.runtime.alpha :as runtime-alpha])

(runtime-alpha/sync!)
(runtime-alpha/use! :shuttle
  {:ns 'skein.spools.shuttle
   :spools ['skein.spools/shuttle]
   :call 'skein.spools.shuttle/install!
   :required? true})
```

`install!` registers the default harnesses, a graph-mutation event handler, and the `agent` CLI operation. The companion [treadle adapter](./treadle.md) can be loaded after shuttle to fulfill workflow `:subagent` gates with shuttle runs.

## 3. Harness registry

Harnesses are data-first launcher definitions registered in trusted Clojure:

| Fn | Behavior |
|---|---|
| `(defharness! name def)` | Register a concrete harness. `def` requires `:argv` and may include `:parse`, `:prompt-via`, `:preamble?`, `:env`, `:cwd`, and `:doc`. |
| `(defalias! name def)` | Register an alias over a harness or another alias. Alias defs require `:alias-of` and may add `:extra-args`, `:prompt-prefix`, and `:doc`. |
| `(resolve-harness name)` | Return the effective harness after flattening aliases; alias cycles fail loudly. |
| `(harnesses)` | Return registered harness and alias metadata ordered by name. |
| `(register-default-harnesses!)` | Register shipped `claude`, `pi`, and `sh` harnesses without replacing existing entries. |

Default parse strategies are `:raw`, `:claude-json`, and `:pi-json`. The `sh` harness is intended for tests and plumbing.

## 4. Run lifecycle

| Fn / op | Behavior |
|---|---|
| `(spawn-run! opts)` / `strand op agent spawn ...` | Create one run strand. Required: `:harness`, `:prompt`. Optional: `:title`, `:depends-on`, `:parent`, `:spawned-by`, `:cwd`, `:max-attempts`, `:attrs`. Returns immediately. |
| `(scan!)` | Spawn every ready pending run not already claimed in this weaver lifetime. Usually called by events and install/reconcile. |
| `(runs)` / `strand op agent ps` | Return summaries of shuttle runs; `{:active true}` or `--active` filters active runs. |
| `(await-runs ids opts)` / `strand op agent await ...` | Block until all runs are terminal or timeout. |
| `(kill! id)` / `strand op agent kill <id>` | Destroy a live harness process when known and mark the run failed. |
| `(reconcile!)` | On install, recover active running runs left by a previous weaver lifetime. |

Successful runs set `shuttle/phase "done"`, record `shuttle/result`, and close the run strand. Failed runs remain `active` with `shuttle/phase "failed"` and `shuttle/error`, so dependents stay blocked loudly. Exhausted crash-recovery runs remain active with `shuttle/phase "exhausted"`.

Readiness is the only scheduling primitive: a pending run with no active `depends-on` blockers can spawn; a pending run with active blockers waits until graph mutations make it ready.

## 5. Memory and councils

| Fn / op | Behavior |
|---|---|
| `(note! target-id text opts)` / `strand op agent note ...` | Append an immutable closed note strand linked to `target-id` by a `notes` annotation edge. |
| `(notes target-id opts)` / `strand op agent notes ...` | Return notes in creation order, optionally filtered by round. |
| `(council! topic opts)` / `strand op agent council ...` | Spawn multiple member runs plus a synthesizer run sharing one council strand as memory. |

Notes carry `shuttle/note-for`, `shuttle/note`, `shuttle/at`, and optional `shuttle/note-by` / `shuttle/round` attributes.

## 6. Attribute vocabulary

| Attribute | Meaning |
|---|---|
| `shuttle/run` | String `"true"` marks a strand as a shuttle run. |
| `shuttle/harness` | Harness or alias name. |
| `shuttle/prompt` | Prompt/script sent to the harness. |
| `shuttle/phase` | `pending`, `running`, `done`, `failed`, or `exhausted`. |
| `shuttle/attempt` | Crash-recovery launch attempt count. |
| `shuttle/max-attempts` | Optional maximum attempts before exhaustion; defaults to `3`. |
| `shuttle/result` | Final captured agent result on success. |
| `shuttle/error` | Failure detail when phase is `failed` or `exhausted`. |
| `shuttle/session-id` | Harness session id when parsed from harness output. |
| `shuttle/log` | Path to captured stdout log under the weaver state dir. |
| `shuttle/pid` | Live process pid recorded after launch. |
| `shuttle/pid-started-at` | OS process start instant used to avoid signalling recycled pids. |
| `shuttle/started-at` / `shuttle/finished-at` | Run timing metadata. |
| `shuttle/spawned-by` | Parent run id for provenance. |
| `shuttle/cwd` | Optional working directory override. |
| `shuttle/note-for` | Target strand id for a note strand. |
| `shuttle/note`, `shuttle/note-by`, `shuttle/round`, `shuttle/at` | Note payload and ordering metadata. |
| `shuttle/council`, `shuttle/topic`, `shuttle/members`, `shuttle/rounds`, `shuttle/role` | Council orchestration metadata. |

Run parents are connected to children with `parent-of` edges. Notes use the undeclared annotation relation `notes`.

## 7. CLI operation

`install!` registers `strand op agent`. `strand op agent about` returns the complete in-band manual, including subcommand usage and vocabulary. The CLI operation is deliberately a named trusted op rather than a first-class core command.

## 8. See also

- [treadle.md](./treadle.md) — shipped adapter that bridges workflow `:subagent` gates to shuttle runs.
- `test/skein/shuttle_test.clj` — executable coverage for harnesses, readiness, failures, notes, reconciliation, ops, and councils.
- [Runtime spool workspace helpers](../../devflow/specs/repl-api.md#spec-003p5-runtime-spool-workspace-helpers) — approved local-root loading contract.
- [Weaver Runtime](../../devflow/specs/daemon-runtime.md) — event handlers, CLI operation registry, JSON socket transport, and runtime reload behavior.

## Coordination attention helpers

`strand op agent ps --for <strand-id>` filters run summaries by their delegated target. Summaries include `:for` as `treadle/gate` when present, else a non-`spawned-by` `parent-of` source; provenance remains separately visible as `:spawned-by`.

`strand op agent logs <run-id> [--tail n]` reads the run's `shuttle/log` `.out` file and `.err` sibling from the weaver side, failing loudly if disposable state files are gone.

`install!` registers the `agent-failures` named query for active runs whose `shuttle/phase` is `failed` or `exhausted`.
