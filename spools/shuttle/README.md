# Skein Shuttle Spool

## 1. Overview

`skein.spools.shuttle` is a trusted userland spool for spawning headless coding-agent runs from ordinary Skein strands. It is a pure run **engine**: it registers no CLI operations of its own. The agent-facing verb surface (`strand op agent ...`), delegation, and coordinator/worker guidance live in the [agents spool](../agents/README.md), which composes this engine.

A run is a strand carrying `shuttle/*` attributes. Creating the run strand is the API: the installed shuttle event handler watches graph mutations, asks Skein readiness which pending run strands are unblocked, launches the selected harness, records output back onto the run strand, and closes successful runs so downstream `depends-on` work can proceed.

Shuttle is intentionally not core scheduler infrastructure. It composes existing primitives: strand attributes, `depends-on` readiness, `parent-of` provenance, annotation edges, event handlers, runtime spool loading, and `strand op`.

## 2. Loading

Shuttle is shipped as an approved-local-root spool example under `spools/shuttle`. A workspace opts in with `spools.edn` and trusted startup or REPL code:

```clojure
;; .skein/spools.edn
{:spools {skein.spools/shuttle {:local/root "../spools/shuttle"}}}
```

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))
(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :shuttle
  {:ns 'skein.spools.shuttle
   :spools ['skein.spools/shuttle]
   :call 'skein.spools.shuttle/install!
   :required? true})
```

`install!` registers the default harnesses, a graph-mutation event handler, and runs crash reconciliation with a first scan. Harnesses, live in-flight process ownership, preamble extensions, and default review contract text are runtime-local weaver-lifetime state, isolated from other runtimes in the same JVM. It does **not** register any `strand op` verbs. Load the [agents spool](../agents/README.md) after shuttle for the `strand op agent` surface, and the companion [treadle adapter](./treadle.md) to fulfill workflow `:subagent` gates with shuttle runs.

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

Because a harness is plain data, swapping the underlying provider for a whole workspace is a single `defharness!`/`defalias!` line — this is the seam the agents spool builds its cross-harness subagent surface on.

## 4. Run lifecycle

| Fn | Behavior |
|---|---|
| `(spawn-run! opts)` | Create one run strand. Required: `:harness`, `:prompt`. Optional: `:title`, `:depends-on`, `:parent`, `:spawned-by`, `:cwd`, `:max-attempts`, `:attrs`. `:parent` and `:spawned-by` each add a `parent-of` edge to the run. Returns immediately. |
| `(scan!)` | Spawn every ready pending run not already claimed in this weaver lifetime. Usually called by events and install/reconcile. |
| `(runs opts)` | Return summaries of shuttle runs; `{:active true}` filters active runs, `{:for <strand-id>}` filters by delegated target. Summaries carry `:for` (the delegated target: `treadle/gate` when present, else a non-`spawned-by` `parent-of` source) with spawning provenance separately visible as `:spawned-by`. |
| `(await-runs ids opts)` | Block until all runs are terminal or `:timeout-secs` (default 300) elapses. |
| `(kill! id)` | Destroy a live harness process when known and mark the run failed; fails loudly when the run has no live process. |
| `(reconcile!)` | On install, recover active running runs left by a previous weaver lifetime. |

Successful runs set `shuttle/phase "done"`, record `shuttle/result`, and close the run strand. Failed runs remain `active` with `shuttle/phase "failed"` and `shuttle/error`, so dependents stay blocked loudly. Exhausted crash-recovery runs remain active with `shuttle/phase "exhausted"`.

Readiness is the only scheduling primitive: a pending run with no active `depends-on` blockers can spawn; a pending run with active blockers waits until graph mutations make it ready.

### 4.1 Crash reconciliation

`reconcile!` runs on `install!`. Any active `running` run this weaver holds no in-flight handle for was owned by a dead predecessor: its stale process is killed when its identity can be verified (pid plus recorded OS start instant), then the run is reset to `pending` for respawn or marked `exhausted` (loudly, still active) once `shuttle/max-attempts` (default `3`) is spent. Runs survive weaver crashes because the strands are durable.

## 5. Run memory (notes)

| Fn | Behavior |
|---|---|
| `(note! target-id text opts)` | Append an immutable closed note strand linked to `target-id` by a `notes` annotation edge. `opts` may set `:by` (author run id) and `:round`. |
| `(notes target-id opts)` | Return notes in creation order, optionally filtered by `:round`. |

Notes are append-only memory, not mutation. They carry `shuttle/note-for`, `shuttle/note`, `shuttle/at`, and optional `shuttle/note-by` / `shuttle/round` attributes.

## 6. Preamble seam

Every spawned run (unless its harness sets `:preamble? false`) is launched with an injected preamble the engine owns. The preamble is deliberately minimal and role-blind — it carries only run identity and the mechanics a worker needs to talk back to the graph:

- the run's `run-id`;
- the fully pinned `strand` invocation (`env XDG_STATE_HOME=… strand --workspace …`) that must prefix every strand command, because harness shells re-source dotfiles and cannot be trusted to inherit ambient env;
- spawn/await/note one-liners and the pointer to `strand op agent about`.

Higher-level policy is layered on through a single seam so the engine stays free of workflow opinion:

| Fn | Behavior |
|---|---|
| `(set-preamble-extension! text)` | Register additional preamble text appended after the engine contract. A second registration with **different** text fails loudly (composed spools cannot silently clobber each other's worker contract); re-registering the same text is idempotent for config reloads. The agents spool fills this with its worker contract. |
| `(pinned-strand-command)` | Return the fully pinned `strand` invocation prefix. Public accessor the agents spool consumes to build worker/review prompts. |
| `(set-default-review-contract! text)` / `(default-review-contract-text)` | Hold the workspace-default reviewer contract text as weaver-lifetime state (re-set by startup config like harness aliases); `nil` restores the generic default. The agents spool's `review` verb consumes this when no explicit contract is passed. |

## 7. Attribute vocabulary

| Attribute | Meaning |
|---|---|
| `shuttle/run` | String `"true"` marks a strand as a shuttle run. |
| `shuttle/harness` | Harness or alias name. |
| `shuttle/prompt` | Prompt/script sent to the harness. |
| `shuttle/phase` | `pending`, `running`, `done`, `failed`, `exhausted`, or `superseded`. |
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

The `pending → running → done | failed | exhausted` transitions are written by the engine. The terminal `superseded` phase is written by the agents spool's `retry` verb (the engine already treats any closed run as terminal, so it needs no code to honor it); a superseded run's logs and notes remain for archaeology.

Run parents are connected to children with `parent-of` edges. Notes use the undeclared annotation relation `notes`.

## 8. See also

- [agents/README.md](../agents/README.md) — the `strand op agent` verb surface, delegation, and coordinator/worker guidance layered over this engine.
- [treadle.md](./treadle.md) — shipped adapter that bridges workflow `:subagent` gates to shuttle runs.
- `test/skein/shuttle_test.clj` — executable coverage for harnesses, readiness, failures, notes, and reconciliation.
- [Runtime spool workspace helpers](../../devflow/specs/repl-api.md#spec-003p5-runtime-spool-workspace-helpers) — approved local-root loading contract.
- [Weaver Runtime](../../devflow/specs/daemon-runtime.md) — event handlers, CLI operation registry, JSON socket transport, and runtime reload behavior.
