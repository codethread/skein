# Skein Ephemeral Spool

> This is the **contract** doc: the attribute convention, the surface, and the
> workspace-wide burn semantics. Its two companions are
> [`ephemeral.cookbook.md`](./ephemeral.cookbook.md) — worked composition recipes
> (when a throwaway strand earns its place, how to burn the right ones, and
> reaching the convention from the shell) — and
> [`ephemeral.api.md`](./ephemeral.api.md) — generated fn signatures and
> docstrings.

## 1. Overview

`skein.spools.ephemeral` is a small reference spool for temporary, parent-owned work strands: scratch notes, intermediate results, or throwaway sub-tasks an agent wants tracked while it works and cleaned up afterwards.

It is deliberately a userland convention, not a core lifecycle: an ephemeral strand is an ordinary persistent strand carrying the attribute `ephemeral "true"` and a `parent-of` edge from its owner. Nothing in the engine treats it specially — the spool composes only the documented `skein.repl` and `skein.api.graph.alpha` surfaces, which also makes it the smallest worked example of attribute-convention spool design.

## 2. Usage

```clojure
(require '[skein.repl :as repl]
         '[skein.spools.ephemeral :as ephemeral])

(def parent (repl/strand! "Implement feature"))

;; create a scratch strand owned by the parent
(ephemeral/ephemeral! (:id parent) "Scratch: API notes" {:owner "agent"})

;; list active ephemeral strand ids
(ephemeral/ephemeral-ids)
;; => ["s-..."]

;; burn every active ephemeral strand when done
(ephemeral/burn-ephemeral!)
;; => {:burned ["s-..."] :count 1}
```

## 3. Surface

| Fn / var | Behavior |
|---|---|
| `(ephemeral! parent-id title)` / `(ephemeral! parent-id title attributes)` | Create a strand with `ephemeral "true"` merged into `attributes` and add a `parent-of` edge from `parent-id`. Returns the created strand. |
| `ephemeral-query` | The query form selecting active ephemeral strands: `[:and [:= [:attr :ephemeral] "true"] [:= :state "active"]]`. Reusable in named queries or views. |
| `(ephemeral-ids)` | Ids of all active ephemeral strands. |
| `(burn-ephemeral!)` | Burn all active ephemeral strands; returns `{:burned [...] :count n}` (empty result when nothing is active). |
| `(install!)` | Installation metadata: the attribute convention plus creator/burner fns as a symbol map, for trusted registration by name. |

Burning is workspace-wide by design: `burn-ephemeral!` clears **all** active ephemeral strands, not one parent's. Scope it yourself (e.g. compose `ephemeral-query` with a parent filter) if you need finer granularity.

## 4. See also

- [README.md](./README.md) — shipped spools index.
- `test/skein/spools_test.clj` — the ephemeral cases drive this spool
  against a real weaver runtime.
- [Authoring your own spool code](../docs/reference.md#authoring-your-own-spool-code)
  — the loading/approval model for spools you write yourself.
