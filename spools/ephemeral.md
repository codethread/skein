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

An ephemeral strand is an ordinary persistent strand carrying the attribute `ephemeral/entry "true"` and a `parent-of` edge from its owner. Nothing in the engine treats it specially — the spool composes only the documented `skein.api.weaver.alpha`, `skein.api.graph.alpha`, and `skein.api.current.alpha` surfaces, which also makes it the smallest worked example of attribute-convention spool design. `install!` declares the `ephemeral` attribute namespace it owns.

The marker key was the bare `ephemeral` until this reset; it is now `ephemeral/entry`, and the change is a clean break. Strands still carrying the bare `ephemeral "true"` attribute are invisible to `query`, `ids`, and `burn-all!` — burn them by id, or restamp them with `ephemeral/entry`.

## 2. Usage

```clojure
(require '[skein.repl :as repl]
         '[skein.spools.ephemeral :as ephemeral])

(def parent (repl/strand! "Implement feature"))

;; create a scratch strand owned by the parent
(ephemeral/add (:id parent) "Scratch: API notes" {:owner "agent"})

;; list active ephemeral strand ids
(ephemeral/ids)
;; => ["s-..."]

;; burn every active ephemeral strand when done
(ephemeral/burn-all!)
;; => {:burned ["s-..."] :count 1}
```

## 3. Surface

| Fn / var | Behavior |
|---|---|
| `(add parent-id title)` / `(add parent-id title attributes)` | Create a strand with `ephemeral/entry "true"` merged into `attributes` and add a `parent-of` edge from `parent-id`. Returns the created strand. |
| `query` | The query form selecting active ephemeral strands: `[:and [:= [:attr "ephemeral/entry"] "true"] [:= :state "active"]]`. Reusable in named queries or views. |
| `(ids)` | Ids of all active ephemeral strands. |
| `(burn-all!)` | Burn all active ephemeral strands; returns `{:burned [...] :count n}` (empty result when nothing is active). |
| `(install!)` | Declare the `ephemeral` attribute namespace and return installation metadata: the attribute convention plus the `add`/`burn` fns as a symbol map, for trusted registration by name. |

Burning is workspace-wide by design: `burn-all!` clears **all** active ephemeral strands, not one parent's. Scope it yourself (e.g. compose `query` with a parent filter) if you need finer granularity.

## 4. See also

- [README.md](./README.md) — shipped spools index.
- `test/skein/spools_test.clj` — the ephemeral cases drive this spool
  against a real weaver runtime.
- [Authoring your own spool code](../docs/spools/customisation.md#promoting-config-to-a-local-spool)
  — the loading/approval model for spools you write yourself.
