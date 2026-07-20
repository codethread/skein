# Task 10: Replace workspace remember forget install macros

**Document ID:** `TASK-Olr-010`

## TASK-Olr-010.P1 Scope

Type: AFK

Rewrite `.skein/spools/macros` so `defop`, `defquery`, `defpattern`/`defp`, and `defrule` emit current module contribution data directly. Remove all namespace-global remember/forget/install registries and update workspace config/analytics/demo authoring sites.

## TASK-Olr-010.P2 Must implement exactly

- **TASK-Olr-010.MI1:** Preserve concise real Var/function definitions, docstrings, specs, arg specs, return declarations, and conventions metadata while collecting declaration data for the module contribution.
- **TASK-Olr-010.MI2:** Delete `remember-*`, `forget-*`, and `install-*` atoms/functions and every top-level forget call. No replacement module-level mutable registry is allowed.
- **TASK-Olr-010.MI3:** Convert `.skein/config.clj`, `.skein/analytics.clj`, and macros demo to contribution entry points under their stable init module keys. Coordinate attention rule conversion with Task 8.
- **TASK-Olr-010.MI4:** Ensure deleting/renaming one macro declaration is represented by omission from the next complete contribution for both file-backed and namespace-backed modules.
- **TASK-Olr-010.MI5:** Keep conventions derivation and author order deterministic without re-reading source or preserving a global shadow registry.

## TASK-Olr-010.P3 Done when

- **TASK-Olr-010.DW1:** Macro tests cover expansion, declaration return shape, order, same-key replacement within one source evaluation, deletion across refresh, empty owner contribution, and malformed forms.
- **TASK-Olr-010.DW2:** Repo config tests prove removed ops/queries/patterns/rules disappear without global reload or targeted `require :reload` choreography.
- **TASK-Olr-010.DW3:** `rg 'forget-(ops|queries|patterns|rules)!|install-(ops|queries|patterns|rules)!|remember-(op|query|pattern|rule)!' .skein` finds no production references.
- **TASK-Olr-010.DW4:** `clojure -M:test skein.macros.ops-test skein.macros.queries-test skein.macros.patterns-test skein.macros.rules-test skein.config-test` and `make fmt-check lint reflect-check` pass.

## TASK-Olr-010.P4 Out of scope

- **TASK-Olr-010.OS1:** Do not change op/query/pattern/rule business definitions or generalize macros beyond current consumers.

## TASK-Olr-010.P5 References

- **TASK-Olr-010.REF1:** `PROP-Olr-001.P1/S7`, `DELTA-OlrRepl-001.CC11`, and orientation note `vn5he` on kanban task `2g1th`.
