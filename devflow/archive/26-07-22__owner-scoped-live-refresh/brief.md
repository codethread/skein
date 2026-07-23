# Brief: owner-scoped live refresh

Kanban: card `tsofs` (p2). Required incorporated issue: card `uxc5f`. User direction, 2026-07-20.

## Problem

Skein's runtime is a live image, but its public loading API exposes the implementation sequence needed to change that image: `sync!`, `reload-spool!`, `use!`, and `reload!`. Operators must choose and order those phases themselves. A full config reload clears unrelated registries before replaying every startup module, while a targeted code bump requires source reload followed by targeted activation or global reload.

Workspace code demonstrates the missing primitive. The `defop`, `defquery`, `defpattern`, and `defrule` helpers each maintain their own namespace-keyed `defonce` shadow registry, require top-level `forget-*` calls to remove renamed or deleted declarations, and later copy remembered values into the real runtime registry through `install-*`. This only becomes deletion-correct because global `reload!` clears the destination registries. The same omission problem exists in spool generation accounting: card `uxc5f` shows that shrinking `deps.edn :paths` or deleting a loaded source can make current-source fingerprints forget a namespace that remains loaded in the JVM.

The global replay model is especially wrong for Skein's primary workflow. Active DAGs of delegated agents must continue while runtime definitions change. A harness alias or routing policy may be changed underneath a running DAG so future runs use a different provider while in-flight runs retain their captured selection. Process replacement for ordinary config or source edits is untenable.

## Direction

Design a TEN-000@1-sanctioned rewrite around live, owner-scoped reconciliation:

- one intent-level refresh operation owns approval sync, dependency-ordered source reload, affected-module activation, and data-first reporting;
- every replaceable registry is partitioned by stable owner, so refreshing one module replaces its complete contribution, including omissions, without clearing unrelated owners;
- named definitions have explicit binding points: future work resolves the current definition while already-started work retains what it captured;
- runtime status distinguishes desired, available, active, loaded-ever, and residual state;
- loaded namespace ownership is cumulative, so removed files or source paths become loud orphaned residuals and can never be reported as a clean image;
- arbitrary trusted REPL mutation and live source redefinition remain available as sharp tools; no false transaction is promised around arbitrary effects or JVM class redefinition;
- process cutover remains a clean-image remedy for hard JVM/classpath conflicts, not the ordinary update path.

The rewrite may replace current blessed runtime APIs and internal registry shapes rather than preserve compatibility. It may also require coordinated rewrites of peer spools currently consumed only by this Skein checkout, including kanban and the agent/delegation/harness family. Published pre-v1 spool version rules may be broken deliberately where preserving them would retain the wrong model; every exception must be explicit in the proposal and all consumers must move atomically.

## Required design questions

1. What is the minimal owner-partitioned registry primitive, and how do core registries and spool-owned registries such as Chime rules, harness aliases, reviewer rosters, workflow definitions, and executor registrations use it?
2. How does module activation stage replaceable registrations while remaining honest about non-transactional resources, durable writes, external calls, and partial source reload?
3. What binding point does each dynamic definition family use, especially harness aliases, reviewers, executors, scheduled handlers, and CLI ops?
4. Which current `skein.api.runtime.alpha` functions stay blessed, which collapse behind refresh/status, and which remain only as advanced or internal seams?
5. How are source deletion, path shrinkage, renamed namespaces, retained Vars/classes, and classpath conflicts represented and repaired? The answer must fully resolve `uxc5f`.
6. Which first-party and peer spool repositories must change together, and how will the pre-v1 version-rule exception be contained and recorded?
7. How can the migration land without restarting or destabilizing the canonical coordination weaver during implementation?

## Acceptance for this planning feature

Produce a proposal, root-spec deltas, and a complete implementation task DAG covering core, API, workspace macros/config, first-party spools, peer spools, tests, docs, generated API references, and consumer cutover. Obtain independent tracked reviews from the configured `opus` seat and a separate `terra-med` fact checker, incorporate or disposition every finding, and stop before implementation begins.
