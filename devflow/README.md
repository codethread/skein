# Devflow

Active feature work is tracked on the kanban board and as devflow workflow runs in the repo `.skein` coordination world. Root specs in `devflow/specs/` become canonical only when feature work ships.

Always study [TENETS](./TENETS.md) and [PHILOSOPHY](./PHILOSOPHY.md). No code, spec or idea can violate these unless explicitly stated and cited in an agreed RFC first.

## Root specs

Root specs are canonical for shipped behavior:

- [Strand Model](./specs/strand-model.md) — strand records, state lifecycle, burn deletion, JSON attributes, relation semantics, and readiness rules.
- [CLI Surface](./specs/cli.md) — the op-only public CLI: the `strand` invoke-envelope dispatcher (zero builtin subcommands), the `mill` router/bootstrap/lifecycle surface, and NDJSON single/stream response relay; per-command behavior lives in `spools/batteries.md`.
- [REPL API](./specs/repl-api.md) — interactive Clojure helper contract, including connected REPL, runtime spool workspace helpers, lifecycle hook helpers, and the `skein.test.alpha` author-side weaver-world test helpers.
- [Weaver Runtime](./specs/daemon-runtime.md) — local long-lived weaver lifecycle, storage model, metadata, transports, trusted startup config, query registry, runtime spool workspace model, and synchronous lifecycle hooks.
- [Alpha Surface](./specs/alpha-surface.md) — the contract index drawing the line around shipped alpha surface: which tiers are in-contract (root specs, blessed API namespaces, opt-in reference spool docs) and which surface is explicitly internal (mill socket protocol, unenumerated error codes, `skein.core.*`).

## Active features

_No active features currently._

## Archived features

Archived feature folders preserve historical planning context. Current shipped contracts are the root specs above, even if older archive notes describe pre-spec documentation locations.

- `26-06-24__agent-tool-interface` — shipped agent-operable CLI/REPL interface for the todo graph MVP.
- `26-06-24__db-owned-task-ids` — shipped generated task ids and creation-time `--link` edges.
- `26-06-24__batch-task-refs` — shipped stdin EDN batch task creation with batch-local refs.
- `26-06-25__daemon-runtime` — shipped local daemon runtime with nREPL transport, daemon-backed CLI/REPL clients, and trusted startup config.
- `26-06-25__daemon-query-registry` — shipped in-memory daemon query registry managed through REPL/config workflows and consumed by CLI named queries.
- `26-06-25__go-cli-migration` — shipped native Go `todo` CLI over the daemon JSON Unix socket, with JSON-only machine output and Clojure REPL/config retained for rich workflows.
- `26-06-25__user-daemon-home` — shipped config-dir daemon worlds, fixed selected-world socket discovery, default daemon init, and connected REPL/stdin UX.
- `26-06-25__runtime-plugin-system` — shipped an earlier trusted local plugin/library MVP. Its public `load-plugin!` and plugin metadata surface has been superseded by the runtime spool workspace model in the canonical root specs.
- `26-06-26__runtime-library-workspace` — shipped config-dir Clojure library workspaces with `libs.edn`, approved local roots, daemon-side `atom.libs.alpha/sync!` and `use!`, module-use introspection, and replacement of the plugin-directory public extension API.
- `26-06-26__runtime-transformation-primitives` — shipped built-in `atom.graph.alpha` / `atom.views.alpha` helpers for set-oriented graph/query composition and daemon-memory read-only views.
- `26-06-26__remove-legacy-clojure-cli` — shipped removal of the legacy `skein.cli` Clojure CLI entrypoint, its tests, and stale spec references, leaving the Go `strand` binary as the sole scripted CLI.
- `26-06-26__skein-rename` — shipped Skein/strand/weaver rename, strand model lifecycle/retention, `strand` CLI, and `skein.*` namespaces.
- `26-06-24__stripped-task-api` — shipped smaller CLI/REPL surface with first-class task lifecycle fields.
- `26-06-27__patterned-weave` — shipped named weaver-side patterns for owner-controlled strand DAG creation from JSON CLI input.
- `26-06-27__weaver-event-system` — shipped weaver-owned semantic strand mutation events with trusted async handlers.
- `26-06-28__cli-attribute-inputs` — shipped file, stdin, and bulk JSON attribute input sources for `strand add`.
- `26-06-28__batch-graph-upsert` — shipped transactional trusted Clojure batch graph mutation primitive with local refs, create/update/burn/edge upsert support, weaver events, and `skein.api.batch.alpha/apply!`.
- `26-06-29__edge-relation-families` — shipped state lifecycle model, declared acyclic relation families, core supersession, edge predicates, relation-scoped traversal, and annotation catalog.
- `26-07-03__library-author-testing-support` — shipped weaver storage handles with real in-memory SQLite for trusted tests, explicit storage metadata/status, the `skein.test.alpha` author-side weaver-world helpers, and `docs/spools/testing.md`.
- `26-06-29__repo-first-config` — shipped repo-local `.skein` world selection, layered shared/local config, local extension overrides, and fail-loud no-global default behavior.
- `26-06-29__weaver-lifecycle-hooks` — shipped synchronous trusted lifecycle hooks for payload gating, attribute normalization, and pre-commit mutation policy.
- `26-06-30__mill-router-runtime` — shipped local Go `mill` router/supervisor, mill-routed `strand` commands, Git-root repo bootstrap, XDG runtime/data worlds, startup storage initialization, and connected REPL attachment through mill metadata.
- `26-06-30__mill-owned-source` — shipped mill-owned source resolution, marker-only config, and repository-canonical default weavers shared across linked Git worktrees.
- `26-07-01__live-weaver-repl-and-runtime-loader` — shipped direct live weaver nREPL attachment, `skein.api.runtime.alpha` loader/config helper namespace, friendly weaver discovery, and Neovim/Conjure integration.
- `26-07-01__library-to-spool` — shipped rename of the runtime approved-code-unit surface from library/libs to spool/spools, including `spools.edn`, `:spools`, `skein.spools.*`, and legacy `libs.edn` rejection.
- `26-07-01__cli-definition-parity` — shipped read-only CLI/socket query introspection (`query list`/`query explain`), `skein.repl/query-explain`, and explicit query/pattern discovery plus weave/batch framing.
- `26-07-02__workflow-engine-review` — shipped the `skein.spools.workflow` engine (plain-data definitions, loop-by-routing, gates, checkpoints, forge-agnostic step bindings) and the `skein.spools.devflow` lifecycle spool built on it; folder holds the review findings and step-bindings plan records.
- `26-07-02__workflow-ergonomics` — shipped workflow/devflow ergonomics: run mutation result maps, choice input declarations, stage-aware ready views, `advance!`, procedure join auto-close, named route/revise support, describe/history/archive helpers, and repo-local `.skein` ops/query guidance.
- `26-07-02__shuttle-spool` — shipped approved-local-root Shuttle spool for readiness-driven headless coding-agent runs, harness aliases, crash reconciliation, append-only run memory, and `strand op agent`.
- `26-07-02__treadle` — shipped the Shuttle-backed treadle adapter for `workflow/gate "subagent"` fulfillment.
- `26-07-02__agent-delegate` — shipped the repo-local `strand op agent-delegate` helper for delegating ready task strands through Shuttle.
- `26-07-02__afk-gates` — shipped delegated Devflow AFK task gates with human acceptance checkpoint.
- `26-07-02__ns-tiers` — shipped namespace tiering and the root `spools/` home.
- `26-07-02__attention-surface` — shipped coordinator attention/read surfaces for workflow and Shuttle/Treadle runs.
- `26-07-02__delegation-composition` — shipped shared delegation policy, review recipe, attribute-driven delegation defaults, and delegated pipeline pattern.
- `26-07-02__docs-pass-review` — archived approved documentation review notes.
- `26-07-02__weaver-guild` — shipped local weaver peering: portable config-declared weaver names, `skein.api.peers.alpha` discovery/`call!` client, and the `skein.spools.guild` op-declaration spool.
- `26-07-03__agents-spool` — shipped the `skein.spools.agents` spool owning the full `strand agent` delegation surface (delegate/retry/status/plan) over the pure shuttle engine, with repo `.skein/config.clj` shrunk to genuine workspace tuning.
- `26-07-03__spool-git-distribution` — shipped registry-free git spool distribution (RFC-017): sha-pinned `:git` coordinates in `spools.edn`, content-addressed fetch cache with verified tag labels, and explicit `use!` activation. Its original optional metadata-file gating was superseded by the Maven-only spool contract.
- `26-07-04__op-only-cli` — shipped RFC-019: removed all builtin strand commands and the `op` prefix; `strand` became a pure invoke-envelope dispatcher with named payloads and stream relay, `mill` absorbed init/weaver lifecycle/repl, the shipped command surface moved to the `skein.spools.batteries` spool over the new blessed `skein.api.cli.alpha` parser, and the socket collapsed to `invoke` + minimal `status` with op-metadata deadlines and hook gating.
- `26-07-04__spool-contract` — shipped the minimal spool contract: retired the `spool.edn` manifest (README Dependency information / Activation snippets replace it; RFC-018 rejected as mooted), strengthened required `use!` to throw on `:not-approved`/`:not-synced`/`:sync-failed`, and allowed uniform Maven-only spool `deps.edn :deps` resolved via sync-time `add-libs`.
- `26-07-05__review-fanout` — shipped the declarative reviewer roster: spec-defined roster data (`.skein/reviewers.clj`), `defroster!`/`rosters`, `agent review --roster` fan-out with per-reviewer contracts/scopes, review-pass tags, and the public `roster-review-specs` composition seam for workflow authors.
- `26-07-05__op-help-convention` — shipped the op help invocation convention: `<op> help|-h|--help` alias for subcommand-declaring ops resolved before hook gating, reserved help tokens, byte-faithful CLI JSON (stdout + stderr details), and the kanban op migrated to declared `:subcommands`.
- `26-07-05__argspec-subcommands` — shipped declarative `:subcommands` in the blessed arg-spec DSL: parser-owned routing with loud missing/unknown errors, registration-time structural validation, `strand help <op>` subcommand rendering, and batteries `query`/`pattern` migrated off the fake subcommand positional.
- `26-07-05__agent-panels` — shipped shuttle session continuation (`:resume` harness splice over captured session ids, `retry --fresh`, persistence-friendly harness defaults that never require persistence) and the panel primitive (seats × blackboard × turn wiring × synthesis compiled from spec'd data; turn-as-run barriers), with `review!`/`council!` as presets — cross-vendor councils, per-seat continuity, poll-loop prompts deleted.
- `26-07-05__roster-spool` — shipped the `skein.spools.roster` reference spool: canonical `roster/*` active-work vocabulary, register/heartbeat/finish/list/await helpers, a `strand roster` surface, and loud stale-entry surfacing that never auto-burns entries.
- `26-07-05__weaver-scheduler` — shipped the weaver-owned scheduler primitive: durable `wake-at` records in dedicated SQLite tables, startup/reload re-arming, fully-qualified-symbol handler resolution, at-least-once serialized async dispatch, and data-first introspection.
- `26-07-09__deterministic-test-time` — shipped two test-time control seams — a runtime-owned clock component (`skein.api.runtime.alpha/now` read, `skein.test.alpha/set-clock!`/`advance!` controls, and a clock-pump registry `advance!` drives) and an `skein.api.events.alpha/await-quiescent!` event-lane settle primitive — collapsed the scheduler onto the shared runtime clock, migrated the timer/event serial suites onto the seams, and graduated them from the serial island to the parallel batch.
