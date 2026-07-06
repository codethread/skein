# Skein Bobbin Spool

> This is the **contract** doc: the bundle sections, the self-contained-edges
> guarantee, and the render rules. Its two companions are
> [`bobbin.cookbook.md`](./bobbin.cookbook.md) — worked composition recipes
> (briefing a delegated agent or reviewer, trimming a pack, feeding it to a tool)
> — and [`bobbin.api.md`](./bobbin.api.md) — generated fn signatures and
> docstrings.

## 1. Overview

`skein.spools.bobbin` assembles a compact context bundle for one strand:
the target summary, nearby blocking and ownership graph, attached shuttle notes,
and workflow metadata when present. The bundle is JSON-compatible and
self-contained: every edge emitted by a section references strands summarized in
that section.

Bobbin is deliberately a classpath reference spool. It owns no engine behavior
and composes only the documented `skein.repl` and `skein.api.graph.alpha`
surfaces.

## 2. Usage

```clojure
(require '[skein.spools.bobbin :as bobbin])

(def bundle (bobbin/pack "abc12"))

;; Select a smaller bundle when a prompt only needs certain sections.
(bobbin/pack "abc12" {:include #{:strand :blockers :notes}})

;; Deterministic plain text suitable for a delegated-agent prompt prefix.
(println (bobbin/render bundle))
```

The target strand's `body` attribute is included in full by `render`; other
related strands render as one compact line each.

## 3. Surface

| Fn | Behavior |
|---|---|
| `(pack strand-id)` | Return the full bobbin bundle for `strand-id`: `:strand`, `:blockers`, `:dependents`, `:parents`, `:children`, `:notes`, and optional `:workflow`. Missing ids fail loudly with `ex-info`. |
| `(pack strand-id opts)` | Supports `{:include #{...}}` where allowed sections are `:strand`, `:blockers`, `:dependents`, `:parents`, `:children`, `:notes`, and `:workflow`. Unknown sections fail loudly with `:allowed` in ex-data. |
| `(render bundle)` | Return deterministic prompt text using stable section order and strand ordering. Related strands are one line each; the target `body` attribute is included in full when present. |
| `(install!)` | Installation metadata: attribute/edge conventions plus public fns as a symbol map, for trusted registration by name. |

Section meanings:

- `:strand` — target summary: id, title, state, attributes, and timestamps.
- `:blockers` — active transitive `depends-on` blockers plus internal edges.
- `:dependents` — direct active strands with `depends-on` edges to the target.
- `:parents` — active `parent-of` ancestry.
- `:children` — direct active `parent-of` children.
- `:notes` — strands attached by the `notes` edge, ordered by `shuttle/at`,
  creation time, then id.
- `:workflow` — present only for targets carrying `workflow/*` attributes;
  includes at least run id, role, workflow attributes, and the molecule root
  summary when resolvable.

## 4. See also

- [README.md](./README.md) — shipped spools index.
- `test/skein/spools/bobbin_test.clj` — executable contract examples against a real weaver runtime.
- [Authoring your own spool code](../docs/skein.md#authoring-your-own-spool-code).
