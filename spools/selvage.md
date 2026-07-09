# Skein Selvage Spool

> This is the **contract** doc: the vocabulary model, check forms, watch-mode
> semantics, and the violation shape. Its two companions are
> [`selvage.cookbook.md`](./selvage.cookbook.md) — worked composition recipes
> (how/why you grow and enforce a vocabulary) — and
> [`selvage.api.md`](./selvage.api.md) — generated fn signatures and docstrings.
> Reach for the cookbook when you want a runnable pattern, the API doc when you
> want an exact arity, and this doc for what the spool promises.

## 1. Overview

`skein.spools.selvage` is a reference spool for opt-in attribute vocabulary linting. Skein intentionally keeps strand attributes loose and userland-owned; Selvage lets a workspace register the attribute invariants it cares about and check them on demand or after mutations.

It is deliberately userland convention, not an engine schema. Vocabularies are weaver-lifetime data, checks are ordinary maps, and violations are returned as data. Invalid vocabulary specs fail loudly at registration time.

Watch mode is post-hoc **detection**, not rejection. It uses asynchronous `skein.api.events.alpha` handlers on `:strand/added` and `:strand/updated`, so a bad mutation has already committed by the time a violation is recorded. Skein also exposes synchronous lifecycle hooks, but this spool ships only the minimal lint/detection surface.

## 2. Usage

```clojure
(require '[skein.repl :as repl]
         '[skein.spools.selvage :as selvage])

(selvage/install!)

(selvage/defvocab! :agent-run
  {:checks [{:attr "agent-run/phase"
             :enum ["pending" "running" "done" "failed" "exhausted"]}
            {:attr "agent-run/run" :kind :string}
            {:attr "agent-run/max-attempts" :kind :int-string}]})

(def run (repl/strand! "Run agent" {:agent-run/phase "running"
                                    :agent-run/run "abc"
                                    :agent-run/max-attempts "3"}))

(selvage/check (:id run))
;; => []

(repl/update! (:id run) {:attributes {:agent-run/phase "bogus"}})
(selvage/check (:id run))
;; => [{:strand-id "..." :vocab :agent-run :attr "agent-run/phase" ...}]

(selvage/violations)
;; asynchronous watch results, in delivery order
```

## 3. Surface

| Fn | Behavior |
|---|---|
| `(defvocab! name spec)` | Register or replace one weaver-lifetime vocabulary. `name` must be a keyword, symbol, or string. `spec` supports `:checks` and optional `:doc`; unknown spec/check keys fail with allowed sets in `ex-data`. |
| `(vocabs)` | Return registered vocabulary metadata, sorted deterministically. |
| `(remove-vocab! name)` | Remove a vocabulary; absent names fail loudly. |
| `(check strand-or-id)` | Return violation maps for a strand map or id. Missing ids fail loudly. |
| `(check-all)` / `(check-all query-form)` | Check all active strands, or only active strands selected by a predicate DSL query form. |
| `(watch!)` | Register the asynchronous event handler for `:strand/added` and `:strand/updated`. Duplicate calls replace the same handler key. |
| `(violations)` | Return watch-mode violations recorded so far, in delivery order. |
| `(clear-violations!)` | Reset recorded watch-mode violations. |
| `(install!)` | Register watch mode and return installation metadata with public function symbols. |

Each violation has this shape:

```clojure
{:strand-id "..."
 :vocab :agent-run
 :attr "agent-run/phase"
 :check :enum
 :value "bogus"
 :message "Attribute agent-run/phase must be one of ..."}
```

## 4. Vocabulary checks

A vocabulary spec is pure data:

```clojure
{:doc "optional human note"
 :checks [{:attr "board/status" :enum ["todo" "doing" "done"]}
          {:attr "board/points" :kind :int-string}
          {:attr "board/owner" :required-with "board/status"}]}
```

Supported check forms:

| Check | Meaning |
|---|---|
| `{:attr s :enum [v ...]}` | If attribute `s` is present, its value must exactly equal one of the vector values. |
| `{:attr s :kind k}` | If attribute `s` is present, its value must match `k`, one of `:string`, `:number`, `:boolean`, `:map`, or `:int-string`. |
| `{:attr s :required-with t}` | If attribute `t` is present, attribute `s` must also be present. |

Attributes are addressed by string names like `"agent-run/phase"`, matching how JSON-backed attributes appear at the CLI boundary. In Clojure strand maps these are keyword keys such as `:agent-run/phase`.

## 5. See also

- [selvage.cookbook.md](./selvage.cookbook.md) — worked composition recipes:
  hardening an attribute table, pre-merge sweeps, watch mode, and evolving a
  vocabulary.
- [README.md](./README.md) — shipped spools index.
- `test/skein/spools/selvage_test.clj` — executable examples against a real
  weaver runtime.
- [Authoring your own spool code](../docs/skein.md#authoring-your-own-spool-code)
  — the loading/approval model for spools you write yourself.
