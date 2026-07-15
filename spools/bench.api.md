
-----
# <a name="skein.spools.bench">skein.spools.bench</a>


Trusted userland spool for deterministic, containerized benchmarking of
  coding-agent harnesses.

  A bench run is a strand graph: a run root, one entry strand per matrix cell,
  and a judge strand that depends on every entry. Each entry executes its agent
  inside a fresh container against a pristine checkout of a pinned repo+sha;
  when the container exits the engine deterministically extracts metrics and
  stamps them on the entry strand, then closes it. Closing every entry unblocks
  the judge — a decoupled fulfilment seam (an agent run by default, but
  fulfillable by any mechanism) that writes a comparative verdict.

  Setup and measurement are code (this namespace plus `skein.spools.bench.exec`);
  only judgment is a model. Two registries — agent definitions and suites — are
  weaver-lifetime trusted config validated loudly at registration. All public
  functions take `runtime` explicitly and keep state runtime-owned via
  `skein.api.runtime.alpha/spool-state` (shared-spool rules); the versioned
  state carries the bounded executor, the registries, and in-flight container
  tracking, and its `:close-fn` kills live containers on runtime stop.




## <a name="skein.spools.bench/abort!">`abort!`</a>
``` clojure
(abort! runtime run-id)
```
Function.

Abort a bench run: kill live containers, fail outstanding entries, and close
  the judge strand cleanly.

  Outstanding entries (phase pending/preparing/running) become `failed` with
  `bench/error "aborted"`; done entries are left closed. Best-effort kills
  every entry's container by name. The judge strand is closed with
  `bench/error "aborted"` (the same marking as an aborted entry, whether the
  judge is an agent run or an external seam); an agent-run judge additionally
  gets `agent-run/phase "superseded"` so the run engine treats it as retired.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L954-L994">Source</a></sub></p>

## <a name="skein.spools.bench/about">`about`</a>
``` clojure
(about)
```
Function.

Return the authored bench manual: purpose, determinism model, run lifecycle,
  attribute vocabulary, judge protocol summary, and artifact layout.

  Deliberately carries no argument shapes — `strand help bench` projects those
  from the declared `:subcommands`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L1191-L1291">Source</a></sub></p>

## <a name="skein.spools.bench/agents">`agents`</a>
``` clojure
(agents runtime)
```
Function.

Return registered agent definitions for `runtime`, sorted by key.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L324-L327">Source</a></sub></p>

## <a name="skein.spools.bench/bench-op">`bench-op`</a>
``` clojure
(bench-op #:op{:keys [args runtime]})
```
Function.

Dispatch parsed `strand bench ...` subcommands to the engine functions.

  Each verb is a thin JSON wrapper: the parser routes on `:subcommand` and
  supplies flags and positionals; rich data stays in trusted Clojure. A bare
  `strand bench` or an unknown verb fails during parser routing (the declared
  `:subcommands` machinery), never here.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L1357-L1378">Source</a></sub></p>

## <a name="skein.spools.bench/cross">`cross`</a>
``` clojure
(cross & axes)
```
Function.

Return the cross-product of axis maps as an explicit vector of entry cells.

  `(cross {:agent [:claude :codex]} {:prompt [:baseline :strict]})` expands to
  the four `{:agent .. :prompt ..}` cells. A convenience for authoring suites;
  the persisted suite always holds explicit entries.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L339-L352">Source</a></sub></p>

## <a name="skein.spools.bench/defagent!">`defagent!`</a>
``` clojure
(defagent! runtime k definition)
```
Function.

Register an agent definition under `k` for this `runtime`.

  Says how one tool runs inside a container: `:image` and `:argv` are required;
  `:prompt-via`, `:model-flag`, `:thinking-flag`, `:env`, `:auth`, `:metrics`,
  and `:doc` are optional. Fails loudly on unknown keys (TEN-003) or a shape the
  spec rejects. Returns the stored definition.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L191-L203">Source</a></sub></p>

## <a name="skein.spools.bench/defextractor!">`defextractor!`</a>
``` clojure
(defextractor! runtime k f)
```
Function.

Register a metrics extractor `f` under `k` for this `runtime`.

  `f` is `(fn [ctx] -> partial metrics map)`; `ctx` carries the entry dir paths
  and parsed stdout. Its return is validated against the closed §7 metrics schema
  before merging — nonconforming keys/values are dropped and recorded under
  `:extraction-warnings` rather than laundered onto `bench/*` attrs (the entry
  still completes). The shipped `:claude`/`:pi`/`:codex`/`:generic` extractors
  register through this registry; userland extends it. Fails loudly when `f` is
  not a function.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L291-L306">Source</a></sub></p>

## <a name="skein.spools.bench/defsuite!">`defsuite!`</a>
``` clojure
(defsuite! runtime k definition)
```
Function.

Register a benchmark suite under `k` for this `runtime`.

  A suite is the matrix plus its deterministic starting state. Validated loudly
  at registration (closed key set, spec, one-of `:sha`/`:rev` and
  `:prompts`/`:prompt`, unique slugs). Stores the raw definition; `run!`
  normalizes it. Returns the stored definition.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L278-L289">Source</a></sub></p>

## <a name="skein.spools.bench/engine">`engine`</a>
``` clojure
(engine runtime)
```
Function.

Return the resolved container engine argv prefix, or nil when none is set.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L319-L322">Source</a></sub></p>

## <a name="skein.spools.bench/extractors">`extractors`</a>
``` clojure
(extractors runtime)
```
Function.

Return the registered extractor keys for `runtime`, sorted.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L334-L337">Source</a></sub></p>

## <a name="skein.spools.bench/gc!">`gc!`</a>
``` clojure
(gc! runtime {:keys [run]})
```
Function.

Delete bench artifact directories, keeping strand-side metrics and verdicts.

  With `:run <id>` removes that run's dir; otherwise removes every run dir under
  the bench data root (the mirror cache is preserved). Returns the removed ids.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L996-L1018">Source</a></sub></p>

## <a name="skein.spools.bench/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Activate bench on the current runtime.

  Creates the runtime-owned state (bounded executor + registries + in-flight
  tracking), detects the container engine (docker then podman on PATH unless
  `set-engine!` already pinned one), registers the shipped
  `:generic`/`:claude`/`:pi`/`:codex` extractors (defaults; user registrations
  win), reconciles entries orphaned by a previous weaver lifetime, and registers
  the `bench` CLI op and the `bench-runs` named query. Registers no suites or
  agent definitions — those are trusted config. Called as a no-arg module
  `:call` at startup/reload.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L1422-L1455">Source</a></sub></p>

## <a name="skein.spools.bench/judge-spec">`judge-spec`</a>
``` clojure
(judge-spec runtime suite-name-or-inline {:keys [run-id entries sha]})
```
Function.

Return a bench run's judge fulfilment seam as plain data — the one prompt
  source every fulfilment mode shares:

      {:prompt <full judge prompt>
       :attrs  {"bench/judge" .. "bench/run" .. "bench/judge-prompt" .. "body" ..}
       :entry-ids [<entry strand id> ..]}

  `run-context` is `{:run-id <root id> :entries [{:id :slug :data-dir :agent?
  :model? :thinking? :prompt-slug?} ..] :sha?}`. `suite-name-or-inline` is a
  registered suite name or an inline suite value (validated identically); its
  `:judge :contract` is layered onto the built-in protocol, but the ground-truth
  invariant is baked into the builder and is not overridable.

  Suite prompts are resolved via the same `resolve-prompt-text` entries
  themselves go through (a `:path` prompt is slurped relative to the workspace
  root), so the judge is always shown the same text an entry received — never
  a raw `{:path ...}` value. The built prompt lists only the suite prompts
  `entries` actually reference via `:prompt-slug` (a subset `--entries` run
  omits unused prompts); single-prompt suites are unaffected, since there is
  only ever the one.

  This is the seam `run!` and workflow authors both consume. `run!` pours the
  judge strand and (in `:harness` mode) its serving agent run straight from
  this output — the strand's `bench/judge-prompt` and an agent run's
  `agent-run/prompt` come from this one builder, so they never drift. A workflow
  author calls `judge-spec` at pour time and maps it onto a `:subagent` gate
  exactly as roster review specs do (`skein.spools.delegation/roster-review-specs`):
  `:prompt` becomes the gate's `agent-run/prompt`, the author picks the gate's
  `agent-run/harness`, `:attrs` merge into the gate, and the gate depends on
  `:entry-ids`. Bench thus never requires or references the workflow spool.

  Fails loudly when the suite declares `:judge :none` (there is no judge to
  spec). A read over the suite registry, the workspace's suite-prompt files,
  and the passed run context.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L717-L777">Source</a></sub></p>

## <a name="skein.spools.bench/reconcile!">`reconcile!`</a>
``` clojure
(reconcile! runtime)
```
Function.

Fail entries orphaned by a weaver restart and best-effort kill their
  containers.

  An in-flight executor claim is weaver-lifetime state, so after a restart any
  `preparing`/`running` entry with no claim is orphaned: it becomes `failed`
  with `bench/error "orphaned by weaver restart"` and its container is killed
  by name. Returns the reconciled entry ids.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L1400-L1420">Source</a></sub></p>

## <a name="skein.spools.bench/report">`report`</a>
``` clojure
(report runtime run-id)
```
Function.

Return the full comparison document for a bench run (§10): per-entry
  normalized metrics, extraction warnings, artifact paths, and per-entry judge
  notes, plus the judge verdict resolved per §8 (the judge strand's
  `bench/verdict` attr, else a serving run's `agent-run/result`) with its
  `:verdict-source` (attr|run|none). A pure read.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L1153-L1186">Source</a></sub></p>

## <a name="skein.spools.bench/retry!">`retry!`</a>
``` clojure
(retry! runtime entry-id)
```
Function.

Re-run one failed entry on a fresh workspace, incrementing `bench/attempt`.

  Only a `bench/phase failed` entry is retryable (TEN-003). Resets it to
  `pending`, clears `bench/error`, and re-queues it on the executor.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L922-L946">Source</a></sub></p>

## <a name="skein.spools.bench/run!">`run!`</a>
``` clojure
(run! runtime suite-name-or-inline opts)
```
Function.

Pour and start a bench run for `suite-name-or-inline` on `runtime`.

  `opts` may carry `:entries` (a subset of slugs to run) and `:for` (a parent
  strand id the run root hangs beneath). Validates everything — suite conforms,
  agents registered, judge harness (in `:harness` mode) and engine resolvable —
  and resolves a `:rev` to a concrete sha before creating any strand (TEN-003).
  Pours the run root, one entry per matrix cell, and (unless `:judge :none`) the
  judge strand depending on every entry — a serving agent run in `:harness`
  mode, a bare fulfilment-seam strand in `:external` mode — queues entries on the
  bounded executor, and returns `{:run root-id :entries {slug id} :judge
  judge-id}` immediately; execution is async.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L820-L900">Source</a></sub></p>

## <a name="skein.spools.bench/runs">`runs`</a>
``` clojure
(runs runtime {:keys [suite]})
```
Function.

Return bench run roots with per-run entry phase counts.

  `opts` may carry `:suite` to scope the listing to one suite. A pure read.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L1090-L1106">Source</a></sub></p>

## <a name="skein.spools.bench/set-engine!">`set-engine!`</a>
``` clojure
(set-engine! runtime argv)
```
Function.

Override the detected container engine with `argv` (a prefix vector speaking
  the docker/podman `run`/`inspect`/`kill` dialect), e.g. `["podman"]`.

  Trusted config pins the engine; tests inject a fake-engine script this way.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L308-L317">Source</a></sub></p>

## <a name="skein.spools.bench/status">`status`</a>
``` clojure
(status runtime run-id)
```
Function.

Return a bench run's entries with phase and headline metrics, judge run
  state, and the slugs of blocking (failed) entries. A pure read (§10).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L1108-L1128">Source</a></sub></p>

## <a name="skein.spools.bench/suites">`suites`</a>
``` clojure
(suites runtime)
```
Function.

Return registered suite definitions for `runtime`, sorted by key.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bench/src/skein/spools/bench.clj#L329-L332">Source</a></sub></p>
