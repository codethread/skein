---
name: clojure
description: always use for authoring clojure code; also use for reviewing, auditing, or scanning Clojure code conformity
---

# Clojure Authoring Guide

## Knowledge

### Project posture

- Prefer small, data-oriented APIs and pure transformation functions.
- Keep side effects at system boundaries: DB, files, sockets, subprocesses, REPL/runtime state.
- Fail loudly on invalid input. Do not silently coerce, default, or tolerate malformed data unless the contract explicitly says so.
- Public behavior should be discoverable from namespace organization, docstrings, specs where useful, and tests.
- When auditing existing Clojure, evaluate it against this desired style even if the file predates the skill. Report concrete deviations; do not only summarize architecture or positives.

### Idiomatic specs

Use `clojure.spec.alpha` as a selective contract and generative-testing tool, not as mandatory ceremony for every non-trivial function.

Prefer specs for:

- Data shapes at boundaries: config maps, DB rows/entities, API payloads, wire JSON/EDN shapes, query DSL forms, batch operations.
- Public API functions where `s/fdef` clarifies a contract or enables useful generative tests.
- Pure transformations with meaningful invariants: graph operations, normalization, parsers/printers, encoders/decoders, round-trips, idempotent updates, topological/DAG operations.

Usually skip specs for:

- Thin wrappers and delegation functions.
- Local/private helpers whose contract is obvious from their caller.
- I/O orchestration where example/integration tests communicate behavior better.
- Functions where generators would be hard to make realistic or would only produce trivial cases.

A good default rule:

> Spec data contracts broadly; spec function contracts selectively where generated inputs or API documentation will catch real bugs.

### Property-based testing with spec

`clojure.spec.test.alpha/check` is most valuable when the `:fn` relation states a property between inputs and outputs, not merely when `:args` and `:ret` check shape.

Good property candidates:

- Encode/decode and parse/print round-trips preserve data.
- Normalization is idempotent.
- Upsert/merge is idempotent or monotonic as intended.
- Adding graph edges preserves declared acyclic relation invariants.
- Query evaluation agrees with simpler reference predicates.
- Sorting/traversal returns only valid members and respects dependency ordering.

Do not add property tests with weak generators just to claim coverage. A few concrete regression examples may be better than a shallow generator.

### Docstrings

Docstrings are expected for public API vars more often than specs are.

Use docstrings for:

- Every public `defn`, `defmacro`, protocol, record/type, and important public `def`.
- Namespaces with non-trivial purpose.
- Private functions only when intent, invariants, or domain behavior is not obvious.

For audits, treat a top-level var as public unless it has `^:private`, is defined with `defn-`, or the namespace clearly marks it as internal. Missing docstrings on public vars are reportable conformity issues, especially for API, DB, query, REPL, and namespace entry-point functions.

Docstring style:

- First line: concise standalone summary, preferably imperative: `Return ...`, `Create ...`, `Evaluate ...`.
- Add a blank line before details.
- Explain contracts, side effects, important invariants, and return shape when not obvious.
- Do not restate the function name or implementation mechanics.

Example:

```clojure
(defn ready
  "Return open strands whose blocking dependencies are closed.

  Traverses only declared structural dependency relations. Annotation edges do not
  affect readiness."
  [db]
  ...)
```

### Long strings and prose blocks

Hard limit: no docstring or string literal line may extend past column 180. Long lines break IDE viewports and diff review. This is a reportable conformity issue wherever it appears — source, tests, config.

When a value is prose (op payloads, `about` surfaces, rule descriptions, delegation bodies), do not build it from `(str ...)` fragments or one long literal. Author it as a `|`-margin block and reflow it with the shipped helpers:

- `skein.api.format.alpha` — the blessed surface for every tier, spools included: trusted config (`.skein/`), userland, core. (`skein.spools.format` is deleted; SPEC-005.C3.)

`(fill block)` returns a vector of item strings (bare `|` line separates items; indented-past-the-bar lines keep an item verbatim for command samples). `(reflow block)` soft-wraps one paragraph into one string. Contract: `docs/spools/writing-shared-spools.md`.

```clojure
(format-alpha/reflow
 "|One rule sentence that would otherwise be an unreadable
  |single source line, soft-wrapped at authoring time.")
```

Docstrings cannot use runtime helpers: wrap them by hand well short of the limit (match the surrounding namespace, usually ~80). Converted `skein.api.*` modules carry a hard 96-column bound on every source line, enforced by `quality.api-form` (SPEC-003.C19a).

### Audit red flags

When scanning Clojure conformity, actively look for and report these before listing positives:

- Any docstring or string literal line past column 180, or long prose built from `(str ...)` fragments instead of a `|`-margin block through `skein.api.format.alpha`.
- Public namespace lacks an `ns` docstring and has a non-trivial public role.
- Public `defn`, `defmacro`, protocol, record/type, or important public `def` lacks a useful docstring.
- Boundary data is validated only ad hoc when a reusable spec would clarify the shipped contract or enable generated checks.
- Pure invariant-heavy behavior lacks property/spec tests where generators would be meaningful.
- Invalid input silently becomes a no-op, default value, SQL three-valued-logic surprise, coercion, or misleading empty result instead of failing loudly.
- Public functions or `def` values appear unused, under-tested, or accidentally public. Constants, SQL fragments, dynamic compiler state, and implementation tables should normally be `^:private` unless they are intentional API and documented.
- Tests only cover examples when a broad invariant would be better captured by property testing.

### Public vs private helpers

Do not make a public var private just because it has lower-level mechanics. Classify visibility before changing it.

Keep a var public and add a docstring when:

- another namespace calls it directly;
- tests exercise it as a boundary;
- it is useful from REPL/dev workflows;
- it represents a smaller valid API than the highest-level wrapper;
- the name appears in docs, specs, examples, or user-facing guidance.

Make a var private when:

- only same-namespace internals call it;
- it exists to support recursion, dynamic binding context, SQL fragments, helper tables, or implementation state;
- callers should always use a higher-level public function;
- no tests, docs, specs, or adjacent namespaces imply direct use.

If uncertain, prefer documenting over privatizing and report the visibility question as a follow-up.

### Style and tooling

Follow common Clojure community style unless local code establishes a stronger pattern:

- 2-space indentation, no tabs.
- `kebab-case` vars/functions/namespaces.
- Predicate names end in `?`; mutating/side-effecting operations may use `!` when that communicates danger or state change.
- Prefer `let`, threading macros, and small named helpers over dense nested forms.
- Prefer maps and plain data over unnecessary records/classes.
- Prefer explicit requires with aliases; avoid broad `:refer :all` outside tests/REPL-oriented namespaces.
- Keep public functions near the top-level flow where practical; keep private helpers close enough to their use to aid reading.
- Use `clj-kondo`/editor diagnostics as the baseline style and correctness feedback.

## Decisions

Entry state: CLASSIFY_CHANGE

### CLASSIFY_CHANGE

- guard: auditing existing Clojure files → AUDIT_EXISTING
- guard: adding or changing public Clojure API → PUBLIC_API
- guard: adding or changing boundary data shape, DSL, config, wire payload, DB entity, graph operation, parser/printer, normalization, or round-trip behavior → SPEC_CANDIDATE
- guard: adding or changing tests → TESTING
- guard: private/internal implementation only → INTERNAL_CODE

### AUDIT_EXISTING

- action: inspect public vars, namespace purpose, boundary validation, spec/property-test candidates, fail-loud behavior, tests, and style.
- guard: concrete deviations exist → REPORT_DEVIATIONS
- guard: no concrete deviations found → REPORT_CONFORMS

### REPORT_DEVIATIONS

- action: report file path, symbol/line when possible, violated rule, and preferred fix.
- always → VALIDATE

### REPORT_CONFORMS

- action: briefly state that no conformity deviations were found, with only the strongest supporting evidence.
- always → VALIDATE

### PUBLIC_API

- action: add or update docstrings and consider `s/fdef` if the contract is non-obvious or useful for generated checks.
- always → TESTING

### SPEC_CANDIDATE

- action: define/update data specs and add targeted `s/fdef` only where properties or public contracts earn it.
- guard: meaningful generated properties exist → PROPERTY_TESTING
- guard: generated properties would be weak/noisy → EXAMPLE_TESTING

### PROPERTY_TESTING

- action: add `stest/check` coverage or generator-backed tests for the specific invariant.
- always → VALIDATE

### EXAMPLE_TESTING

- action: add focused example/regression tests for important cases and failures.
- always → VALIDATE

### TESTING

- action: choose property tests for broad invariants and example tests for concrete behavior/regressions.
- always → VALIDATE

### INTERNAL_CODE

- action: keep code small, clear, data-oriented, and fail-loud; add private docstrings/specs only when they clarify subtle invariants.
- always → VALIDATE

### VALIDATE

- action: run relevant Clojure tests/lint where available.
- terminal state.

## Procedures

### Authoring Clojure code

1. Read the surrounding namespace and tests before editing; match local idioms unless they conflict with this skill or project rules.
2. Shape the code around plain data and small pure functions.
3. Put validation at boundaries and fail loudly with useful errors.
4. Add public docstrings for public vars introduced or materially changed.
5. Add specs for boundary data and selected public/pure functions where contracts or generated tests are valuable.
6. Add tests at the right level:
   - property/spec tests for invariants over many inputs;
   - example tests for workflows, edge cases, regressions, and I/O behavior.
7. Run relevant validation before reporting completion.

### Fixing Clojure conformity in one file

1. Apply all high-confidence mechanical fixes in the file, not just the first category found.
2. Add or improve the namespace docstring when the namespace has a public role.
3. Add docstrings to every intentional public `defn`/`defmacro`/protocol/record/type/important `def`, including functions declared earlier with `declare` and defined later.
4. Mark accidental public implementation vars `^:private`, especially dynamic compiler state, SQL fragments, schema constants, helper tables, and internal defaults.
5. Leave intentionally public vars public, but document them.
6. Do not introduce broad specs or property tests in a drive-by conformity fix unless the user asked for behavior/test work; report those as follow-up candidates.
7. Run relevant validation before reporting completion.

### Auditing Clojure conformity

1. List public top-level vars in the audited files; flag missing or unhelpful docstrings for public API vars.
2. Classify each public var as intentional API or accidental exposure; flag implementation constants, SQL strings, state vars, helper tables, and internal utilities that should be `^:private`.
3. Identify boundary data shapes and pure invariant-heavy functions; flag missing specs/property tests only when they would provide real contract or generated-test value.
4. Check fail-loud behavior: explicit invalid input should throw useful `ex-info` or equivalent, not silently no-op, coerce, default, or produce misleading results.
5. Check tests: broad invariants should have property/spec coverage when practical; concrete regressions and I/O workflows should have example tests.
6. Check style: namespace docstring, requires, naming, side-effect boundaries, dense forms, dead public functions, and linter-obvious issues.
7. Report findings as deviations with severity when useful. Avoid long positive inventories unless no deviations exist.

### Writing specs

1. Prefer namespaced spec names that mirror the domain concept, e.g. `::strand`, `::edge`, `::query`.
2. Keep data specs composable: define leaf specs, then compose map/vector/tuple specs.
3. For `s/fdef`, include `:args` and `:ret`; include `:fn` when a meaningful input/output relation exists.
4. Add custom generators only when the default generators are unrealistic or too weak.
5. Avoid making specs stricter than the shipped contract unless intentionally tightening behavior.

### Writing docstrings

1. Start with a one-line summary that stands alone in REPL `doc` output.
2. Add details only when they clarify arguments, return values, side effects, invariants, or failure modes.
3. Mention important domain semantics, not implementation trivia.
4. Keep private helper docstrings optional and purposeful.

## Constraints

- Do not spec every function by default.
- Do not let any docstring or string literal line pass column 180; author long prose as `|`-margin blocks via `skein.api.format.alpha`.
- Do not omit public docstrings for public Clojure API without a good local reason.
- Do not leave implementation vars public by accident; make them private or document them as supported API.
- Do not add weak property tests that only exercise trivial/generated happy paths.
- Do not silently fall back to defaults for invalid data; fail loudly.
- Do not introduce broad compatibility shims or deprecated paths unless explicitly requested.
- Do not let specs replace tests; specs support tests and documentation, but concrete regressions still deserve direct tests.

## Validation

Before reporting success on Clojure code changes, verify as applicable:

- [ ] Public vars added or changed have useful docstrings.
- [ ] No docstring or string literal line passes column 180; prose values use the `|`-margin format helpers.
- [ ] Boundary data shapes have specs when useful.
- [ ] `s/fdef` is used selectively for public contracts or property-testable pure functions.
- [ ] Property tests assert real invariants, especially via `:fn` or explicit property assertions.
- [ ] Relevant Clojure tests pass.
- [ ] Linter/editor diagnostics do not report obvious namespace, arity, or unused-var issues.
