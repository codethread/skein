# Skein Bench Spool

## 1. Overview

`ct.spools.bench` is a trusted userland spool for **deterministic, containerized benchmarking of coding-agent harnesses**. It answers one question reproducibly: *given this repository at this exact commit, this exact memory-file/skill surface, and this prompt — how do N agent configurations compare?*

A bench run is a strand graph: a **run root**, one **entry** strand per matrix cell, and a **judge** strand that depends on every entry. Each entry executes its agent inside a fresh container (docker or podman) against a pristine checkout of the pinned repo+sha; when the container exits, the engine deterministically extracts metrics (cost, tokens, turns, tool usage, diff stats, validation outcome) in-process and stamps them onto the entry strand. When all entries close, readiness unblocks the judge — a **decoupled fulfilment seam** (§8) that reads every entry's artifacts and writes a comparative verdict: shipped as an agent run on a **chosen approver harness/model**. A workflow, a human, or a custom bridge can fulfill the same seam.

Design lineage: this replaces the manual `bench.md` orchestration command (worktrees + hand-run scripts + jq) with a spool where **setup and measurement are code, and only judgment is a model**.

Division of labor with the [agent-run engine](../agent-run/README.md):

- **Entries are bench-owned, not agent runs.** A benchmark process must receive *exactly* the configured prompt: no injected preamble, no strand CLI, no workspace ambient state. Containers are hermetic by construction, so bench owns their execution on a spool-owned executor (the cron / executors.shell pattern) rather than contorting the agent-run harness model.
- **The judge is a fulfilment seam (§8), shipped as an agent run.** In the default `:harness` mode it runs on the host, needs a real harness/model seat, benefits from the agent-run lifecycle (logs, `agent ps`, retry), and is scheduled by ordinary `depends-on` readiness. It stays pending until every entry closes. The seam is decoupled: the same judge strand can instead be fulfilled by a workflow gate, a human, or a custom bridge, so bench never requires the workflow spool.

## 2. Loading

Approved local-root spool. Agent-run must be installed first (the default `:harness` judge is an agent run; `:external` judges need no agent-run).

```clojure
;; .skein/spools.edn
{:spools {ct.spools/agent-run {:local/root "../spools/agent-run"}
          ct.spools/bench   {:local/root "../spools/bench"}}}
```

```clojure
(runtime/use! runtime :bench
  {:ns 'ct.spools.bench
   :spools ['ct.spools/bench]
   :call 'ct.spools.bench/install!
   :required? true
   :after [:agent-run]})
```

`install!` creates the runtime-owned spool state (executor, registries, in-flight tracking — versioned per `docs/spools/writing-shared-spools.md` with a `:close-fn`), registers the `bench` CLI op and the `bench-runs` named query, detects the container engine (see §4), and reconciles orphaned entries from a previous weaver lifetime (see §9). It registers **no suites and no harness definitions** — those are trusted config, exactly like harness aliases.

All public functions take `runtime` as the first argument (shared-spool rules). State lives in `skein.api.runtime.alpha/spool-state`; no module-level atoms.

## 3. The DSL (trusted config)

Two registries, both weaver-lifetime state re-registered by startup config, both validated loudly against clojure.specs at registration (closed key sets; unknown keys fail — TEN-003).

### 3.1 Harness definitions — `register-harness!`

A bench harness definition says how one tool runs *inside a container*: image, argv, how matrix axes splice in, auth plumbing, and which metrics extractor understands its session artifacts.

> **Two registries, one word.** A bench harness is *not* an [agent-run](../agent-run/README.md) harness, and they are not interchangeable. A bench harness is a **container** definition (`:image`, `:auth`, `:model-flag`, `:thinking-flag`, `:extractor`) resolved by bench's own registry; an agent-run harness is a **host process** definition (`:parse`, `:resume`, `:preamble?`). A bench harness cannot be passed to `agent-run/spawn-run!`. Both appear in one suite map: an entry cell's `:harness` resolves *here*, while the suite's `:judge :harness` (§8) resolves *agent-run's* registry.

```clojure
(require '[ct.spools.bench :as bench])

(bench/register-harness! runtime :claude
  {:image "ghcr.io/me/bench-claude:1.0"       ; digest refs recommended for strict pinning
   :argv ["claude" "-p" "--dangerously-skip-permissions" "--output-format" "json"]
   :prompt-via :stdin                          ; :stdin (bench default; agent-run defaults :arg) | :arg (appended last)
   :model-flag "--model"                       ; splices an entry's :model
   :thinking-flag "--effort"                   ; splices an entry's :thinking
   :env {"CLAUDE_CONFIG_DIR" "/bench/home/.claude"}
   :auth {:env ["ANTHROPIC_API_KEY"]           ; host env passed through at launch
          :mounts [{:host "~/.claude/.credentials.json"
                    :container "/bench/home/.claude/.credentials.json"}]} ; always read-only
   :extractor :claude                          ; extractor key, see §7; default :generic
   :doc "Claude Code headless in a container"})

(bench/register-harness! runtime :codex
  {:image "ghcr.io/me/bench-codex:1.0"
   :argv ["codex" "exec" "--dangerously-bypass-approvals-and-sandbox" "-"]
   :prompt-via :stdin
   :auth {:env ["OPENAI_API_KEY"]}
   :extractor :codex})

(bench/register-harness! runtime :pi
  {:image "ghcr.io/me/bench-pi:1.0"
   :argv ["pi" "--print" "--session" "/bench/home/session.jsonl"]
   :prompt-via :stdin
   :model-flag "--model"
   :thinking-flag "--thinking"
   :auth {:env ["PI_API_KEY"]}
   :extractor :pi})
```

Required: `:image`, `:argv`. `(bench/harnesses runtime)` lists registered definitions.

Images are userland-owned: build them with the agent CLI plus your project toolchain preinstalled. The spool never builds images; a missing image fails the entry loudly at launch with the engine's own error.

### 3.2 Suites — `register-suite!`

A suite is the benchmark matrix plus its deterministic starting state.

```clojure
(bench/register-suite! runtime :tmux-title-rewrite
  {:repo "https://github.com/me/proj.git"      ; git URL or absolute local path
   :sha  "0123456789abcdef0123456789abcdef01234567" ; 40-hex pin, or :rev "main" resolved at run time
   :prompts {:baseline "Rewrite the tmux-window-title extension in clean code style."
             :strict   "Rewrite the tmux-window-title extension. Hard rules: ..."}
   :setup ["pnpm" "install"]                   ; run in-container before the agent; non-zero fails the entry
   :validation ["pnpm" "check"]                ; run in-container after the agent; outcome recorded, never judged by code
   :files {"CLAUDE.md"  {:content "# House rules\n..."}   ; deterministic memory-file control
           "AGENTS.md"  {:path "bench-fixtures/agents.md"} ; copied from host at pour time
           ".claude/skills/clean-code" {:dir "bench-fixtures/skills/clean-code"}}
   :remove ["docs/CONTRIBUTING.md"]            ; delete from the checkout before the run
   :entries [{:harness :claude :model "opus"   :prompt :baseline}
             {:harness :claude :model "sonnet" :thinking "high" :prompt :baseline}
             {:harness :claude :model "sonnet" :thinking "high" :prompt :strict}
             {:harness :codex  :prompt :baseline}
             {:harness :pi     :model "gpt-5.4" :prompt :strict}]
   :parallel 2                                 ; max concurrent containers (default 2)
   :judge {:harness :build                     ; :harness spawns an agent run (or :external true → any fulfiller)
           :contract "Score each entry 1-10 on correctness, scope control, and code quality.
                      Verify claims against the diff. Pick a winner and justify it."}})
```

Rules:

- `:repo` + (`:sha` | `:rev`) — exactly one of `:sha`/`:rev`. A `:rev` is resolved to a sha at **run** time (`git rev-parse` against the mirror) and the resolved sha is stamped on the run root; re-running the printed sha reproduces the run. `:sha` must be 40 lowercase hex.
- `:prompts` — map of slug → prompt text (or `{:path ...}` read at pour time). A single-prompt suite may use `:prompt "..."` instead; entries then omit `:prompt`. With multiple prompts every entry must name one (no silent default — TEN-003).
- `:files` values: `{:content s}` inline, `{:path p}` host file, `{:dir p}` host directory copied recursively (how skills are pinned). Relative host paths resolve against the workspace root. Overlay is applied after checkout, so it *replaces* repo files of the same name; `:remove` deletes paths (e.g. strip the repo's own CLAUDE.md to test memoryless behavior). The applied overlay is recorded in the run manifest.
- `:entries` — each cell: required `:harness` (a bench harness, §3.1 — never an agent-run harness); optional `:model`, `:thinking`, `:prompt`, `:slug` (defaults to `<harness>[-<model>][-<thinking>][-<prompt>]`, uniquified; collisions fail loudly), `:env` (extra container env). `(bench/cross {:harness [:claude :codex]} {:prompt [:baseline :strict]})` is a helper returning the expanded cross-product cells — the persisted suite always holds explicit entries.
- `:judge` — the fulfilment seam (§8): exactly one of `:harness` (an agent-run harness/alias, validated against the agent-run registry at run time) or `:external true` (pour the seam strand and stop); optional `:contract` layered onto the built-in judge protocol; `:judge :none` runs a judgeless suite (metrics only). The `:judge` map is closed — unknown keys, or both/neither of `:harness`/`:external`, fail loudly.

`(bench/suites runtime)` lists registered suites. Suites are plain data; `run!` also accepts an **inline suite value** from trusted Clojure, validated identically (the CLI stays name-only, TEN-006).

## 4. Container engine

`install!` resolves the engine by probing `docker` then `podman` on PATH; trusted config may pin it with `(bench/set-engine! runtime ["podman"])` — any argv prefix speaking the `run`/`inspect` CLI dialect. **This is also the test seam**: tests inject a fake engine script and the whole lifecycle runs without a container runtime on the machine. No engine resolvable at `run!` time fails loudly.

Per entry, the engine executes (conceptually):

```
<engine> run --rm -i --name skein-bench-<run-id>-<slug> \
  -e HOME=/bench/home -e SKEIN_BENCH_RUN=<run-id> <agent env> <auth env> \
  -v <entry-dir>/home:/bench/home -v <entry-dir>/workspace:/bench/workspace \
  <auth mounts, ro> -w /bench/workspace <image> <compiled agent argv>
```

- Three sequential container invocations per entry against the same mounts: **setup** (when `:setup` present; logged to `setup.log`, non-zero → entry failed, agent never runs), **agent** (prompt on stdin or argv per `:prompt-via`; stdout/stderr captured), **validation** (when `:validation` present; exit + tail recorded as data).
- `HOME` inside the container is a mounted per-entry directory, so every harness's session artifacts (Claude `~/.claude/projects`, Codex `~/.codex/sessions`, the pinned pi session path) land on the host for extraction, and no user-global config (`~/.claude/CLAUDE.md`, global skills) can leak in. The only memory files and skills present are the repo's own at the pinned sha plus the suite's `:files` overlay.
- Auth mounts are always read-only; auth env is read from the weaver's host environment at launch and never persisted to strands or the manifest.
- Containers get default network access (agents must reach their APIs). Determinism here means **pinned starting state and measured outcomes**, not hermetic model behavior; the image digest is recorded (`<engine> image inspect`) on each entry at launch so drift is at least visible.
- Wall-clock timeout: suite `:timeout-secs` (default 3600) — expiry kills the container (`<engine> kill`) and fails the entry loudly.

### Host-engine smoke helper

`spools/bench/examples/host-engine` is a development helper that speaks the small docker/podman CLI subset bench emits, but runs the command directly on the host after rewriting mounted container paths. Use it when you want a quick bench smoke run without building images:

```clojure
(bench/set-engine! runtime ["/path/to/spools/bench/examples/host-engine"])
```

It keeps the pinned clone, per-entry `HOME`, metrics extraction, timeout handling, and kill path. It is not hermetic: the host `PATH`, installed tools, and platform leak into the run. Use it for local smoke tests and harness comparisons, not for archival benchmark results.

## 5. Workspace preparation (host-side, deterministic)

Under the bench data dir (`<weaver state dir>/bench/<run-id>/<slug>/`):

```
workspace/    # git clone of the mirror, checked out at the pinned sha, overlay applied
home/         # the container's HOME; harness session artifacts land here
stdout / stderr / setup.log / validation.log
metrics.json  # normalized metrics (§7)
diff.patch    # everything the agent changed, including untracked files
manifest.json # resolved sha, image digest, compiled argv (auth env redacted), overlay listing, prompt text
```

Repo mirrors are cached per URL (`git clone --mirror`, fetched on each run when a `:rev` needs resolving); each entry gets its own full clone from the mirror — entries never share working state. After checkout the `:files` overlay and `:remove` list are applied and the workspace is left **clean at the pinned sha from the agent's point of view** — overlay changes are committed as a single `bench: pinned overlay` commit so the agent's own diff is cleanly separable.

After the agent exits, the engine runs `git add -A` in the workspace and captures `git diff --cached` (patch + numstat) — the complete record of what the agent did, including new files.

Artifacts are kept until `strand bench gc [--run <id>]` removes them (metrics and verdicts live on strands and survive gc; `--keep` on `run` is unnecessary — keeping is the default).

## 6. Run lifecycle and graph shape

```
bench-run root  bench/run=true, bench/suite, bench/repo, bench/sha (resolved), bench/data-dir
├─ entry <slug>  bench/entry=true, bench/harness, bench/model?, bench/thinking?, bench/prompt-slug?,
│                bench/phase, bench/image-digest, bench/* metrics (§7)   [parent-of from root]
├─ entry ...
└─ judge         bench/judge=true, bench/judge-prompt, body, depends-on every entry  (parent-of)
                 ← a fulfilment seam (§8): a serving agent run (:harness) or a bare strand (:external)
```

`(run! runtime suite-name-or-inline opts)` / `strand bench run <suite>`:

1. Validate: suite exists/conforms, harnesses registered, judge harness resolvable (in `:harness` mode), engine resolvable. Resolve `:rev` → sha. Fail loudly on any of these **before** creating strands.
2. Pour the graph (root under an optional `--for` parent, e.g. a kanban card) and stamp attributes. Each entry writes its manifest when it launches.
3. Queue every entry on the spool executor bounded by `:parallel`; pour the judge seam strand (§8) `depends-on` every entry — in `:harness` mode as a serving agent run (`pending` until entries close), in `:external` mode as a bare strand nothing is spawned for.
4. Return `{:run <root-id> :entries {<slug> <id>} :judge <id>}` immediately — execution is async; `bench status` / `bench await` observe it.

Entry phases (`bench/phase`): `pending → preparing → running → done | failed`. A finished entry has metrics stamped and its strand **closed** (this is what unblocks the judge). A failed entry stays **active** with `bench/phase failed` and `bench/error` — the judge stays blocked, loudly visible, until `bench retry <entry-id>` (fresh workspace, same cell) or `bench abort <run-id>` (kills live containers, fails outstanding entries, and closes the judge strand with `bench/error "aborted"`).

## 7. Metrics (deterministic, code-owned)

Extraction runs in the weaver JVM after the container exits — Clojure JSON parsing over the mounted `home/` and captured stdout; no jq, no model involvement. Extractors are a registry keyed by the harness def's `:extractor`; shipped: `:claude`, `:codex`, `:pi`, `:generic`. Userland may register more with `(register-extractor! runtime k f)` where `f` is `(fn [ctx] -> partial metrics map)` and `ctx` carries the entry dir paths and parsed stdout.

Normalized schema (written to `metrics.json`, flattened onto the entry strand as `bench/*` attrs):

```clojure
{:exit 0  :duration-ms 84210
 :cost-usd 0.4213                         ; omitted when the provider reports none (codex) — absent, never 0
 :tokens {:input 12034 :output 8211 :cache-read 401223 :cache-write 10021}
 :turns 12
 :tools {:file-reads 10 :file-writes 3 :file-edits 5 :bash 7 :other 2 :total 27}
 :tool-errors 1
 :diff {:files 4 :insertions 120 :deletions 30}
 :validation {:exit 0 :cmd "pnpm check"}} ; omitted when the suite declares none
```

Source of truth per extractor:

- `:claude` — result JSON on stdout (`total_cost_usd`, `usage`, `num_turns`, `duration_ms`) plus the session JSONL under `home/.claude/projects/**` for the tool breakdown (tool_use names → the normalized taxonomy: Read→file-reads, Write→file-writes, Edit/NotebookEdit→file-edits, Bash→bash) and tool_result `is_error` counts, deduped by assistant message id.
- `:pi` — the pinned session JSONL (`--session /bench/home/session.jsonl`): per-assistant-message `usage.cost.total`, token fields, toolCall names, error tool results.
- `:codex` — rollout JSONL under `home/.codex/sessions/**`: token counts from the last `token_count` event's cumulative `total_token_usage`; shell-family calls (`shell`/`local_shell`, and `exec_command` on current Codex builds) → bash, `apply_patch` (a `function_call` or `custom_tool_call`) → file-edits. No cost reported → `:cost-usd` omitted.
- `:generic` — exit, duration, diff, validation only.

A malformed/missing session artifact never fabricates values: the extractor records what it can, and lists what it could not under `:extraction-warnings` in `metrics.json` (loud in `bench report`, non-fatal — the run's primary artifacts still exist).

Each extractor's return is validated against this schema (closed at the top level) before merging: an unknown key, or a key whose value fails its shape, is dropped from the merge and recorded under `:extraction-warnings` rather than laundered onto `bench/*` attrs — a buggy or third-party extractor can never silently pollute queryable metrics, and the entry still completes. `bench report` likewise flags a corrupt `metrics.json` distinctly from a missing one (`metrics.json unreadable: <msg>` in that entry's extraction warnings).

Attribute mapping (entry strand): `bench/cost-usd`, `bench/tokens-in`, `bench/tokens-out`, `bench/tokens-cache-read`, `bench/turns`, `bench/duration-ms`, `bench/tools-file-reads`, `bench/tools-file-writes`, `bench/tools-file-edits`, `bench/tools-bash`, `bench/tool-errors`, `bench/diff-files`, `bench/diff-insertions`, `bench/diff-deletions`, `bench/validation-exit`, `bench/exit-code` — all queryable with ordinary strand queries.

## 8. The judge (a fulfilment seam)

**The judge strand IS the seam.** Unless the suite says `:judge :none`, `run!` pours one judge strand that `depends-on` every entry and stamps onto it everything a fulfiller needs — so bench never has to be called at fulfilment time, and **bench never requires or references the workflow spool**:

| Attribute / field | Meaning |
|---|---|
| `bench/judge` = `true` | Marks the seam strand. |
| `bench/run-id` = `<root-id>` | The run this judges. |
| `bench/judge-prompt` | The complete built judge prompt (protocol + suite contract + entry strand ids + per-entry data-dir paths). Held as a strand attribute — the same convention delegated task/run strands use for large `body` text elsewhere in this repo. |
| `body` | A short, mechanism-agnostic fulfilment contract: read `bench/judge-prompt`, judge, write one note per entry on the entry strands, stamp `bench/verdict` on this strand, then close it. |
| `bench/verdict` | The canonical, durable verdict, stamped by whoever fulfils the strand. |

Stamping `bench/verdict` needs the batteries `update` op installed in the fulfiller's workspace. In a world without it the verdict is never stamped as an attribute, but in the agent-run-served `{:harness h}` mode it still lands durably as the judge run's `agent-run/result`, and `bench report`/`status` fall back to that (`verdict-source` reads `"run"` instead of `"attr"`).

The `bench/judge-prompt` protocol is: read each entry's `metrics.json`, `diff.patch`, and captured stdout (inspect the workspace when needed); **metrics are ground truth — never re-derive or dispute them; judge quality, not arithmetic** (this invariant is baked into the builder and is *not* overridable by `:contract`); frame per the varying axis (never flatten a two-axis run into one ranking); append one note per entry with scores and findings; stamp the verdict on the judge strand; and finish with the same verdict — a comparison table plus winner/pass-fail per the suite's `:judge :contract`.

### Fulfilment modes (the suite `:judge` value)

- `{:harness h, :contract? s}` — **shipped default.** Bench spawns an agent run serving the judge strand on `h` (any harness/alias — this is where "which model approves" is chosen). The run strand *is* the judge strand; its `agent-run/prompt` and the strand's `bench/judge-prompt` come from the same builder, so they never drift. A failed judge run recovers with the ordinary `strand agent retry`.
- `{:external true, :contract? s}` — bench pours the judge strand and **stops**; nothing is spawned. Any external mechanism (a workflow, a human, a custom bridge) fulfils the strand per its `body` contract. Mutually exclusive with `:harness` — declaring both, or neither, fails loudly.
- `:none` — a judgeless, metrics-only suite; no judge strand.

The verdict is resolved (in `bench report`/`status`) from `bench/verdict` first, else a serving agent run's `agent-run/result`, else absent. The report names the `verdict-source` (`attr` | `run` | `none`). The run is **complete when the judge strand closes** in both modes; only *who* closes it changes.

### Composition: `judge-spec` and workflow `:subagent` gates

`(ct.spools.bench/judge-spec runtime suite-name-or-inline {:run-id .. :entries [{:id :slug :data-dir ..} ..] :sha ..})` returns the seam as plain data — `{:prompt <full judge prompt> :attrs {bench/* incl. body} :entry-ids [..]}` — the one source `run!` itself pours from. This mirrors `ct.spools.delegation/roster-review-specs`: a workflow author calls `judge-spec` at pour time and maps it onto a `:subagent` gate exactly as roster review specs map onto gates — `:prompt` becomes the gate's `agent-run/prompt`, the author picks the gate's `agent-run/harness`, `:attrs` merge into the gate, and the gate `depends-on` `:entry-ids`. The [executors.subagent](../executors/subagent.md) spool then bridges that gate to an agent run and delivers its result back into the workflow. Bench supplies the judging *contract*; the workflow spool supplies the *fulfilment*, and the two never take a dependency on each other.

The judge is deliberately **not** asked to approve blind: validation exit codes and diffs are already extracted, so its job is the qualitative layer code cannot do.

## 9. Failure and recovery

- Fail-loud validation happens before any strand exists (§6). Setup failure, timeout, and engine errors mark the entry `failed` + `bench/error`, judge blocked. A **non-zero agent exit** still finalizes the entry `done` **when there is something to measure** — non-empty stdout *or* a non-empty diff (the exit code is recorded as `bench/exit-code`); with nothing to measure (empty stdout *and* empty diff) the entry is `failed` with `bench/error "agent exited <code> with no artifacts"`.
- Weaver crash mid-run: `install!` reconciliation marks any `bench/phase preparing|running` entry with no in-flight executor claim as `failed` (`bench/error "orphaned by weaver restart"`). Containers are `--rm` and named `skein-bench-<run-id>-<slug>`; reconcile best-effort `<engine> kill`s a still-live container by name. No auto-respawn — `bench retry` is deliberate.
- `bench retry <entry-id>`: only for `failed` entries; resets to `pending` with a fresh workspace, increments `bench/attempt`.
- Executor and registries are versioned spool state with `:close-fn`; runtime stop kills live containers loudly.

## 10. CLI op surface

`strand bench <verb>` — declared `:subcommands` arg-spec (generated `strand help bench`), JSON output only. Bare `strand bench` fails loudly listing verbs.

| Verb | Behavior |
|---|---|
| `run <suite> [--entries a,b] [--for <strand-id>]` | Pour + start a run (optionally only named cells; optionally rooted under a parent strand). → `{"run","entries":{slug:id},"judge"}` |
| `list [--suite s]` | List bench run roots with per-run entry phase counts. |
| `status <run-id>` | Entries with phase + headline metrics, judge strand state + verdict (`verdict-source`), blocking failures. |
| `report <run-id>` | The full comparison document: per-entry normalized metrics table, extraction warnings, judge verdict (with `verdict-source`: `attr` \| `run` \| `none`) + per-entry notes, artifact paths. |
| `retry <entry-id>` | Rerun one failed cell on a fresh workspace. |
| `abort <run-id>` | Kill live containers, fail outstanding entries, close the judge strand (`bench/error "aborted"`). |
| `suites` / `harnesses` | Registered suites / bench harness definitions (plain data). |
| `gc [--run <id>]` | Delete artifact directories (strand-side metrics/verdicts survive). |
| `about` | Authored JSON manual (concepts, determinism model, attribute vocabulary). |

Named query `bench-runs` (`strand list --query bench-runs`) selects active bench run roots.

## 11. Determinism model (what is and is not pinned)

Pinned and recorded: repo sha, memory files/skills (overlay manifest), prompt text, agent argv, container image (digest recorded; pin by digest for strictness), setup/validation commands, extractor code version (spool sha at run time recorded in the manifest).

Not pinned (recorded where visible): model behavior (nondeterministic by nature — that is what is being measured), network-fetched deps during `:setup` (pin via lockfiles in the repo sha), API-side model versions. Re-running a suite yields a *comparable* run, not a bit-identical one; the manifest makes any drift diagnosable.

## 12. See also

- [agent-run/README.md](../agent-run/README.md) — the run engine serving the default `:harness` judge; executor/state conventions this spool mirrors.
- [executors/subagent.md](../executors/subagent.md) — bridges workflow `:subagent` gates to agent runs; how a `judge-spec` gate (§8) is fulfilled inside a workflow.
- [delegation/README.md](../delegation/README.md) — `strand agent retry` for judge recovery; harness/alias registry the judge seat resolves against; `roster-review-specs`, the seam pattern `judge-spec` mirrors.
- `docs/spools/writing-shared-spools.md` — the shared-spool rules this spool is held to (explicit runtime, versioned spool state, fail loudly, subcommand arg-specs).
- `test/skein/bench_test.clj` — executable coverage via the injected fake engine (no container runtime needed).
