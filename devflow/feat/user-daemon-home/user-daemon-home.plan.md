# User Daemon Home Plan

**Document ID:** `UDH-PLAN-001`
**Status:** Reviewed
**Last Updated:** 2026-06-25
**Proposal:** [proposal.md](./proposal.md)
**Spec deltas:** [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [cli.delta.md](./specs/cli.delta.md), [repl-api.delta.md](./specs/repl-api.delta.md)
**Related RFCs:** None

## UDH-PLAN-001.P1 Goal and scope

Deliver the daemon-core UX pivot described in `UDH-PROP-001`: a selected config-dir defines the daemon world, clients discover a fixed socket/state location without knowing a database path, and `todo daemon repl` launches an already-connected Clojure helper REPL from any working directory on a dev machine where Clojure is on `PATH`, a daemon is running, and `config.json` points at the source checkout. Agents can use `todo daemon repl --stdin` for non-TTY trusted Clojure workflows.

## UDH-PLAN-001.P2 Approach

- **UDH-PLAN-001.A1:** Treat config-dir as the user-visible daemon identity. Root all discovery around a resolved config-dir, not cwd and not SQLite path.
- **UDH-PLAN-001.A2:** Introduce small shared world resolution in both Go and Clojure: the default world uses atom XDG config/state/data dirs; explicit `--config-dir DIR` selects a self-contained experimental world with config in `DIR`, state in `DIR/state`, and data in `DIR/data`.
- **UDH-PLAN-001.A3:** Move JSON socket and metadata publication to fixed filenames in the selected state world: `daemon.sock`, `daemon.json`, and `daemon.edn`. Keep metadata for status/debugging/identity, but no longer use DB-path hashes for discovery.
- **UDH-PLAN-001.A4:** Remove `db` from Go client config, command resolution, and the JSON socket request envelope. The daemon runtime chooses `tasks.sqlite` under the selected data world and reports it in status only.
- **UDH-PLAN-001.A5:** Keep JSON config low privilege. It supplies an absolute `source` checkout path for Clojure-spawning commands and simple client defaults; trusted runtime behavior goes in selected config-dir `init.clj`.
- **UDH-PLAN-001.A6:** Add a Clojure REPL launcher path that preloads `todo.repl`, connects by selected config-dir, and starts a plain `clojure.main/repl` helper REPL.
- **UDH-PLAN-001.A7:** Add `daemon repl --stdin` as the one non-interactive REPL execution path for agents. It reads forms from stdin and prints direct Clojure results without a CLI-defined response envelope, so callers can choose EDN, JSON, or side-effecting output in their own forms.
- **UDH-PLAN-001.A8:** Update docs/specs/smoke examples to present `todo daemon start`, task commands, `todo daemon repl`, and `todo daemon repl --stdin` as the blessed paths, with config-dir override documented as an explicit alternate world.

## UDH-PLAN-001.P3 Affected areas

| ID | Area | Impact |
| --- | --- | --- |
| UDH-PLAN-001.AA1 | `cli/internal/config` | Replace `db`/config-path assumptions with config-dir resolution, `config.json` loading, `source`, and fixed state/data paths. |
| UDH-PLAN-001.AA2 | `cli/internal/client` | Connect to the selected world's fixed socket/metadata instead of DB-hashed metadata. |
| UDH-PLAN-001.AA3 | `cli/internal/command` | Add `--config-dir`, remove public `--config-path`, remove DB from resolved options, update daemon lifecycle launch, add `daemon repl`. |
| UDH-PLAN-001.AA4 | `src/todo/daemon` | Resolve world dirs, publish fixed metadata/socket, default DB under data dir, load config-dir `init.clj`. |
| UDH-PLAN-001.AA5 | `src/todo/client` and `src/todo/repl.clj` | Connect by daemon world instead of database path; expose implicit helper connection for REPL launcher. |
| UDH-PLAN-001.AA6 | `deps.edn` / runtime entrypoints | Add or adapt aliases/main namespaces needed for daemon start and connected helper REPL from source checkout. |
| UDH-PLAN-001.AA7 | Tests/smoke/docs/specs | Replace DB-keyed assumptions and prove from-anywhere CLI/REPL connection behavior. |

## UDH-PLAN-001.P4 Contract and migration impact

- **UDH-PLAN-001.CM1:** This intentionally breaks the current client config contract requiring `db`; alpha tenets favor dropping the bad abstraction rather than preserving it.
- **UDH-PLAN-001.CM2:** Existing task databases are not migrated by this feature. If users need existing data, they can move/copy it to the selected world's default `tasks.sqlite` path before starting the daemon. This feature does not add DB path customization hooks.
- **UDH-PLAN-001.CM3:** Old DB-hashed runtime metadata/socket files may become stale artifacts. The new client path should ignore them; cleanup can be best-effort during development but not part of the new identity model.
- **UDH-PLAN-001.CM4:** `--config-dir DIR` creates a separate daemon world with config in `DIR`, state in `DIR/state`, data in `DIR/data`, and trusted startup file `DIR/init.clj`; it is primarily for tests and experiments, not routine per-project use.
- **UDH-PLAN-001.CM5:** The public `daemon start --config <trusted.edn>` path is removed in favor of default config-dir `init.clj`.

## UDH-PLAN-001.P5 Implementation phases

### UDH-PLAN-001.PH1 World directory and config contract

Define Go and Clojure resolution for config/state/data dirs, JSON config shape, source validation, and default daemon database path. `source` must be an absolute existing directory containing `deps.edn`; failures occur before launching Clojure. Update specs/tests around config loading and failure messages.

### UDH-PLAN-001.PH2 Fixed daemon socket and metadata

Change daemon metadata/socket publication and Go/Clojure client discovery to selected-world fixed paths. Remove `database_path` from required JSON requests. Preserve daemon-id/protocol validation and status reporting, and make stale/missing/malformed state fail loudly with useful remediation.

### UDH-PLAN-001.PH3 Daemon startup config and source launcher

Update `todo daemon start` to launch from configured `source` regardless of cwd, pass selected config-dir into Clojure, use daemon-owned default DB, load selected config-dir `init.clj` by default when present, and remove the public EDN `--config` startup path.

### UDH-PLAN-001.PH4 Connected REPL command

Add `todo daemon repl` plus Clojure entrypoint/helper changes so users land in a connected helper REPL with no database argument. Add `todo daemon repl --stdin` so agents can pipe forms such as `(tasks)` and `(ready)` in non-TTY contexts and receive direct printed results. Ensure task/query helpers fail loudly if used without connection.

### UDH-PLAN-001.PH5 Smoke, docs, and spec promotion prep

Revise README/AGENTS/spec examples and smoke validation to exercise normal task commands and `todo daemon repl` from outside the repo with a disposable config-dir world.

## UDH-PLAN-001.P6 Validation strategy

- **UDH-PLAN-001.V1:** Go tests cover config-dir/default XDG resolution, config JSON supported keys, source validation for lifecycle commands, fixed socket client discovery, and no-DB task command options.
- **UDH-PLAN-001.V2:** Clojure tests cover daemon world resolution, fixed metadata/socket publication, default data DB path, trusted `init.clj` load success/failure, and REPL helper connection by config-dir.
- **UDH-PLAN-001.V3:** Integration tests start a daemon for a disposable config-dir, run Go task/query/status commands from a different cwd, verify metadata/status, and stop the daemon.
- **UDH-PLAN-001.V4:** Smoke validation builds `todo`, writes disposable `config.json` with `source` pointing to this checkout, starts daemon, runs `todo daemon repl --stdin` with forms such as `(tasks)` and `(ready)`, verifies output is direct Clojure printing without an extra protocol envelope, and cleans generated state/data/config artifacts.
- **UDH-PLAN-001.V5:** Full validation remains `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

## UDH-PLAN-001.P7 Risks and open questions

- **UDH-PLAN-001.R1:** Fixed socket path can hide stale daemon artifacts more visibly than hashed DB metadata. Mitigation: explicit pid/protocol/daemon-id validation and startup cleanup only when staleness is proven.
- **UDH-PLAN-001.R2:** Source checkout config is a temporary development packaging story. Mitigation: isolate it to lifecycle commands and keep normal socket clients source-free.
- **UDH-PLAN-001.R3:** Explicit `--config-dir DIR` worlds place generated state/data under `DIR/state` and `DIR/data`, which may surprise users who expect pure config content. Mitigation: document that `--config-dir` is an experimental world selector and use it primarily for tests.
- **UDH-PLAN-001.R4:** Auto-loading `init.clj` by default increases trusted startup power. Mitigation: align with daemon-core philosophy, fail loudly on errors, and keep CLI JSON config non-executable.
- **UDH-PLAN-001.R5:** Fixed socket metadata can misclassify rare PID reuse as live stale state. Mitigation: accept as alpha risk for now while preserving daemon-id/protocol verification on reachable sockets.
- **UDH-PLAN-001.Q1:** None. Use `--config-dir` only and plain Clojure REPL for this feature; defer profile aliases and Rebel polish.

## UDH-PLAN-001.P8 Task context

- **UDH-PLAN-001.TC1:** Do not preserve DB-path daemon discovery as a compatibility mode unless council/user explicitly reverses direction.
- **UDH-PLAN-001.TC2:** Keep JSON client config small and reject unsupported keys; do not add EDN parsing to Go.
- **UDH-PLAN-001.TC3:** Treat config-dir as the sole world selector. Remove `--config-path` rather than keeping it as an alias. Avoid cwd-sensitive behavior except launching Clojure from configured `source`.
- **UDH-PLAN-001.TC4:** Normal task/query/status/stop commands must not require Clojure or source checkout once the daemon is running.
- **UDH-PLAN-001.TC5:** `daemon repl --stdin` is the only non-interactive REPL execution API in this feature; do not add a separate `--eval` flag. It prints one result per top-level form, so agents wanting one payload should send one top-level `do`/`let` form.
- **UDH-PLAN-001.TC6:** Use disposable `--config-dir` worlds in tests and smoke to avoid touching the developer's real atom config/state/data.

## UDH-PLAN-001.P9 Developer Notes

### UDH-PLAN-001.DN1 Initial draft — 2026-06-25

Authored proposal, feature-local spec deltas, and draft plan from user direction plus project philosophy/tenets.

### UDH-PLAN-001.DN2 Council review revisions — 2026-06-25

Council supported the config-dir daemon world direction but blocked task slicing until several decisions were explicit. Revised the plan/deltas to: remove `database_path` from the JSON request envelope, define explicit `--config-dir DIR` as a self-contained world with `DIR/state` and `DIR/data`, remove public `daemon start --config <trusted.edn>` in favor of auto-loaded `init.clj`, require absolute `source` with `deps.edn`, use fixed `daemon.sock`/`daemon.json`/`daemon.edn` names, choose plain Clojure REPL, and defer DB customization hooks.

### UDH-PLAN-001.DN3 Agent non-TTY REPL path — 2026-06-25

Added `todo daemon repl --stdin` as the single scripted REPL execution path for agents. It reads forms from stdin, evaluates them in the connected helper context, and prints one direct Clojure result per top-level form without an extra CLI response envelope so agents can choose EDN, JSON, or custom output in their forms. Agents wanting one payload should send one top-level `do`/`let`. Explicitly did not add a separate `--eval` flag.

### UDH-PLAN-001.DN4 Final review revisions — 2026-06-25

Final review asked for explicit removal of `--config-path`, a named public REPL connection helper, and sharper `--stdin` output semantics. Updated deltas/plan to remove public `--config-path`, add `connect!` as the config-dir/world connection helper, remove database-path `open!` from public REPL API, and state one printed result per top-level stdin form.

### UDH-PLAN-001.DN5 Task queue generated — 2026-06-25

Marked the plan Reviewed and generated AFK task slices. Task sequencing is vertical: establish world/config resolution first, then fixed socket protocol, daemon startup/init, task command migration, connected REPL/stdin, validation/docs, and spec promotion/archive prep.

### UDH-PLAN-001.DN6 Task 1 implementation notes — 2026-06-25

Implemented the first-slice world/config contract while keeping socket discovery DB-keyed until the fixed-socket slice. The Go CLI now resolves `--config-dir` worlds and rejects old `db` client config; Clojure has matching world path helpers and the internal CLI resolves `--config-dir` through the same `config.json` `source`/`format` shape for transition. Deep review found and we fixed relative `--config-dir` normalization before launching Clojure. Root spec promotion remains deferred to Task 7 as planned.
