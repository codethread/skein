# Skein Rename Proposal

**Document ID:** `SR-PROP-001` **Last Updated:** 2026-06-26 **Related RFCs:** [RFC-006 Rename to Skein](./rfcs/2026-06-26-skein-rename.md) **Related root specs:** [Strand Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Runtime Transformations PRD](../../prd/runtime-transformations.md)

## SR-PROP-001.P1 Problem

The accepted Skein rename RFC resolves the product direction, but the repository still exposes a mixed identity: public commands are `todo`, internal namespaces are `todo.*`, blessed runtime libraries are `atom.*.alpha`, durable storage is named around `tasks`, and default daemon worlds live under `atom` XDG paths. That split makes new specs, examples, and runtime libraries harder to understand and hardens vocabulary that no longer matches the product: a neutral attributed DAG core whose durable unit should get meaning from userland attributes.

The rename also exposes leftover domain assumptions in the core model: first-class lifecycle statuses (`todo`, `done`, `failed`, `cancelled`) still encode task-like outcomes, and every strand currently persists after completion. The core only needs to know whether a strand is **active** for filtering and dependency readiness, plus whether it is **ephemeral** so deactivating it deletes it instead of retaining history. Outcome subtypes such as done, failed, cancelled, note, idea, or page belong in user attributes when a world wants them.

The feature needs to turn RFC-006 into one shipped contract without leaving partial aliases, stale examples, or unresolved naming decisions for implementers to guess at.

## SR-PROP-001.P2 Goals

- **SR-PROP-001.G1:** Ship one coherent identity: product **Skein**, namespace root `skein.*`, CLI binary `strand`, stored unit **strand**, and daemon/runtime noun **weaver**.
- **SR-PROP-001.G2:** Rename public contracts, code namespaces, storage identifiers, default world paths, generated config, docs, tests, and smoke workflows consistently.
- **SR-PROP-001.G3:** Preserve existing behavior while renaming it except for deliberate lifecycle/retention simplifications: status enum to core `active` boolean, and optional ephemeral strands that disappear when deactivated. Edge types, readiness rules, JSON attributes, query registry semantics, runtime library workflow, and view/graph primitives keep their current meaning.
- **SR-PROP-001.G4:** Drop old names outright under TEN-000; no compatibility aliases, fallback lookup paths, dual binaries, or data migration.
- **SR-PROP-001.G5:** Keep the CLI thin. The rename should not add query authoring, package management, view invocation, or richer runtime behavior.

## SR-PROP-001.P3 Non-goals

- **SR-PROP-001.NG1:** No migration from existing `atom` config dirs, `todo` binaries, `tasks.sqlite` files, or `todo.*`/`atom.*` namespaces.
- **SR-PROP-001.NG2:** No semantic change to the DAG shape, allowed edge types, readiness rule intent, query DSL, daemon transports, or trusted runtime-library model beyond replacing the task-oriented status enum with core active/inactive state and adding core ephemeral retention.
- **SR-PROP-001.NG3:** No `bond`, `molecule`, connected-subgraph, or broader textile metaphor feature beyond the accepted `skein`/`strand`/`weaver` vocabulary.
- **SR-PROP-001.NG4:** No standalone `weaver` executable. `weaver` is the subcommand group under the public `strand` binary and the semantic name for the long-lived runtime.
- **SR-PROP-001.NG5:** No public website, GitHub organization, or external release-handle claim in this feature. The code/spec rename may use `skein.*`; public publishing handles require owner confirmation outside this implementation.

## SR-PROP-001.P4 Proposed scope

- **SR-PROP-001.S1:** Rename product and documentation language from Atom/Todo/task graph to Skein/strand graph wherever it describes current shipped behavior.
- **SR-PROP-001.S2:** Move Clojure code and references from `todo.*` to `skein.*`, including `todo.daemon.api` to `skein.weaver.api`, daemon internals to `skein.weaver.*`, and REPL helpers to `skein.repl`.
- **SR-PROP-001.S3:** Rename blessed runtime libraries from `atom.libs.alpha`, `atom.graph.alpha`, and `atom.views.alpha` to `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`; generated `init.clj` templates should require the new namespaces only.
- **SR-PROP-001.S4:** Rename the durable model vocabulary from task to strand in specs, API/helper names, code identifiers, and storage: `tasks` to `strands`, `task_edges` to `strand_edges`, task-id edge columns to strand-id edge columns, and default `tasks.sqlite` to `skein.sqlite`. Replace first-class `status` and `final_at` with core `active`, `inactive_at`, and `ephemeral`: active strands are live and participate in readiness; inactive persistent strands are retained with `inactive_at`; ephemeral strands are deleted with their incident edges when deactivated; any subtype, outcome, or reason belongs in JSON attributes.
- **SR-PROP-001.S5:** Rename the public CLI binary to `strand`. The command tree becomes `strand init`, `strand add/update/show/list/ready`, and `strand weaver start|repl|stop|status`; edge creation remains `--edge edge-type:to-id`; lifecycle input uses explicit boolean active state rather than status values; creation/update accepts explicit ephemeral retention.
- **SR-PROP-001.S6:** Rename default runtime worlds from `atom` to `skein`: `$XDG_CONFIG_HOME/skein`, `$XDG_STATE_HOME/skein`, and `$XDG_DATA_HOME/skein`. Explicit `--config-dir` worlds remain self-contained under the selected directory.
- **SR-PROP-001.S7:** Resolve the daemon artifact naming open item by using `weaver.sock`, `weaver.json`, and `weaver.edn` in the selected state world. Clients must discover only the new names and fail loudly on stale or missing runtime metadata.
- **SR-PROP-001.S8:** Rename REPL/user-facing helper vocabulary from task to strand where helpers name the stored unit, for example `task!`/`task`/`tasks` become `strand!`/`strand`/`strands`. Generic operations such as `init!`, `update!`, `ready`, `query`, and registry helpers keep their names when the verb is still accurate.
- **SR-PROP-001.S9:** Update root specs via feature-local deltas, including renaming `task-model.md` to `strand-model.md`, reframing CLI daemon sections as weaver runtime sections, and refreshing PRD-001 examples to the new namespaces and command names.
- **SR-PROP-001.S10:** Update tests, smoke workflow, README/getting-started docs, and agent contributor guidance to use disposable `skein`/`strand` worlds and to avoid mutating any old default `atom` world.
- **SR-PROP-001.S11:** Treat external publishing handles as deliberately out of scope for this rename. Local checks show `skein.dev`, `getskein.dev`, `github.com/skein`, and `github.com/skein-dev` are occupied; shipped docs should not claim them. If the owner controls a handle, release/publish docs can be updated in a later explicit release feature.
- **SR-PROP-001.S12:** Define lifecycle and retention semantics explicitly: deactivating a persistent strand sets `active=false` and `inactive_at`; reactivating it sets `active=true` and clears `inactive_at`; deactivating an ephemeral strand deletes the strand and all incident edges instead of recording an inactive row. Ephemeral strands are still persisted while active. Inactive ephemeral rows are invalid: create/update requests that would make lifecycle and retention ordering ambiguous fail loudly.

## SR-PROP-001.P5 Open questions

- **SR-PROP-001.Q1:** None for proposal-to-plan. RFC-006's implementation open items are resolved here: use `strand weaver ...` rather than a standalone daemon binary, rename runtime metadata/socket files to `weaver.*`, and exclude public domain/GitHub handle claims from this implementation scope.
