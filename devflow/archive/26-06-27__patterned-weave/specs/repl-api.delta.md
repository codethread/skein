# REPL API delta for patterned weave

**Document ID:** `DELTA-002`
**Root spec:** [REPL API](../../../specs/repl-api.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged
**Last Updated:** 2026-06-27

## DELTA-002.P1 Summary

The REPL API gains trusted helpers for registering, explaining, and invoking named weave patterns. A pattern is a weaver-lifetime name pointing to a fully qualified function symbol plus a Clojure spec input contract. Invoking a pattern validates input data, then transforms it into an atomic batch strand creation.

## DELTA-002.P2 Contract changes

- **DELTA-002.CC1:** Add helpers to `skein.repl`: `defpattern!`, `patterns`, `pattern`, `pattern-explain`, and `weave!`.
- **DELTA-002.CC2:** `defpattern!` registers a simple unqualified pattern name, a fully qualified Clojure function symbol, and an input spec name in the active weaver's in-memory pattern registry. Duplicate registration replaces the prior entry for reload workflows.
- **DELTA-002.CC3:** `patterns` returns the active weaver's pattern registry as serializable entries, not function objects.
- **DELTA-002.CC4:** `pattern` returns one registered pattern entry by name and fails loudly or returns nil according to the surrounding helper convention chosen during implementation; missing invocation through `weave!` always fails loudly.
- **DELTA-002.CC5:** `pattern-explain` accepts a pattern name and returns a serializable explanation of its registered input spec for callers, leaning on existing spec/schema tooling rather than a custom Skein schema format.
- **DELTA-002.CC6:** `weave!` accepts a pattern name and input data, validates input data against the registered spec, invokes the pattern in the weaver JVM, validates that the return value is a batch strand vector, and creates strands through the existing atomic batch primitive.
- **DELTA-002.CC7:** Pattern functions receive one argument. The planned MVP argument is a context map containing at least `:input`; this allows future metadata without changing arity.
- **DELTA-002.CC8:** Pattern functions return the same vector shape accepted by `skein.db/add-strand-batch!`: strand maps with optional `:ref`, lifecycle flags, attributes, and `:edges` containing `:type`, `:to`, and optional `:attributes`.
- **DELTA-002.CC9:** Input spec failures, pattern function exceptions, malformed return values, and batch failures surface loudly. Batch writes remain all-or-nothing.
- **DELTA-002.CC10:** Pattern registry state is weaver-lifetime runtime state. Users reload trusted config or call `defpattern!` again after weaver restart or `libs/reload!`.

## DELTA-002.P3 Example

```clojure
(require '[clojure.spec.alpha :as s])

(defn dev-task-pattern [{:keys [input]}]
  [{:ref 'impl
    :title (:title input)
    :attributes {:kind "implementation"}}
   {:ref 'review
    :title (str "Review: " (:title input))
    :attributes {:kind "review"}
    :edges [{:type "depends-on" :to 'impl}]}])

(s/def ::title string?)
(s/def ::owner string?)
(s/def ::dev-task-input (s/keys :req-un [::title]
                                :opt-un [::owner]))

(defpattern! 'dev-task 'my.workflow/dev-task-pattern ::dev-task-input)
(weave! 'dev-task {:title "Remove format config"})
```

## DELTA-002.P4 Design decisions

### DELTA-002.D1 Function-symbol patterns

- **Decision:** Patterns point to fully qualified function symbols, not stored function values or CLI-uploaded code.
- **Rationale:** This matches view registration, preserves REPL/config reload workflows, and keeps executable behavior in trusted weaver-loadable code.

### DELTA-002.D2 Spec-backed caller contracts

- **Decision:** Patterns require an input spec name at registration time.
- **Rationale:** The same trusted contract supports both pre-invocation validation and lower-privilege caller explanation, and lets Skein reuse the wider Clojure spec ecosystem for forms, examples, JSON Schema, and validation errors.

## DELTA-002.P5 Open questions

- **DELTA-002.Q1:** Decide whether `pattern` should return nil or fail loudly for missing names during implementation, matching the nearest existing helper precedent.
- **DELTA-002.Q2:** Decide the exact output shape of `pattern-explain` after validating built-in `s/form` / `s/exercise` and `metosin/spec-tools` JSON Schema/error output for the supported spec forms.
