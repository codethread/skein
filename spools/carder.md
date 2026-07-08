# Skein Carder Spool

> This is the **contract** doc: what each report means, the runtime it needs, the
> exclusion rules, and the option vocabulary. Its two companions are
> [`carder.cookbook.md`](./carder.cookbook.md) — worked composition recipes
> (how/why you build a hygiene loop) — and [`carder.api.md`](./carder.api.md) —
> generated fn signatures and docstrings. Reach for the cookbook when you want a
> runnable pattern, the API doc when you want an exact arity, and this doc for
> what the spool promises.

## 1. Overview

`skein.spools.carder` is a read-only reference spool for graph hygiene and triage. Long-lived strand graphs can accumulate stale active work, unconnected active strands, and work blocked behind failed agent runs. Carder reports those conditions as JSON-compatible data and mutates nothing.

The name follows the textile metaphor: carding untangles fibers before spinning.

`orphans`, `blocked-by-failure`, and `report` inspect `strand_edges` directly through the active runtime's datasource, because the public graph helpers expose relation-scoped traversal rather than a workspace-wide edge listing. They therefore require an **in-process weaver runtime** — trusted startup config, the weaver's own nREPL, or an in-process test runtime — and fail loudly with `ex-info` when none is active. `stale` composes only the public strand-listing surface.

## 2. Usage

```clojure
(require '[skein.spools.carder :as carder])

;; active strands not updated in at least 14 days
(carder/stale)

;; use a custom age threshold
(carder/stale {:days 30})

;; active strands with no incident edges and no workflow/* attributes
(carder/orphans)

;; active strands with active failed/exhausted depends-on blockers
(carder/blocked-by-failure)

;; aggregate all sections for a registered CLI op wrapper
(carder/report {:days 7})
```

## 3. Surface

| Fn / var | Behavior |
|---|---|
| `default-days` | Default stale threshold: `14`. |
| `(stale)` / `(stale opts)` | Active strands whose `updated_at` is at least `:days` days old. Rows carry compact strand fields plus `:days-stale`. `:days` must be a positive integer. |
| `(orphans)` / `(orphans opts)` | Active strands with zero incoming or outgoing edges in any relation in `strand_edges`, and no attribute whose key is in the `workflow/*` namespace. |
| `(blocked-by-failure)` / `(blocked-by-failure opts)` | Active strands with at least one active `depends-on` blocker whose `shuttle/phase` string is `"failed"` or `"exhausted"`. Rows include `:blockers` with compact blocker details and any `shuttle/phase` / `shuttle/error` values. |
| `(report)` / `(report opts)` | Aggregate map with `:opts` plus `:stale`, `:orphans`, and `:blocked-by-failure` sections, each carrying `:count` and `:rows`. |
| `(install!)` | Installation metadata: function symbols, default threshold, and `:read-only true` for trusted registration by name. |

Options accepted by all report functions:

- `:days` — positive integer stale threshold. Used by `stale` and reported by
  `report`; default is `14`.
- `:include-plumbing?` — when true, include workflow plumbing and shuttle run
  records. By default all sections exclude strands with `workflow/role` in
  `"molecule"`, `"procedure"`, or `"digest"`, and strands with
  `shuttle/run "true"`.

Malformed options fail loudly with `ex-info`; unknown keys are not ignored.

## 4. See also

- [carder.cookbook.md](./carder.cookbook.md) — worked composition recipes: stale
  triage, orphan sweeps, failure-blocked retry, and wiring a hygiene report into
  a routine.
- [README.md](./README.md) — shipped spools index.
- `test/skein/spools/carder_test.clj` — executable contract examples against a
  real weaver runtime.
- [Authoring your own spool code](../docs/skein.md#authoring-your-own-spool-code)
  — the loading/approval model for spools you write yourself.
