(ns reviewers
  "This repository's declarative reviewer roster.

  This file is the source of truth for who reviews a change in this
  workspace: many small, cheap, single-concern reviewers instead of a couple
  of generalists. Each entry is one independent read-only review run with its
  own precise contract; a synthesizer fans all findings into one verdict.

  Run it over any strand (kanban card, plan root, task) from the worktree
  where the diff lives:

      strand agent review <target-id> --roster change-review --cwd <worktree>

  Discover the live registered roster with `strand agent rosters`. The
  registry is weaver-lifetime state: edits here need a weaver restart or
  config reload to take effect.

  Entry shape (validated loudly by skein.spools.agents/defroster!):
    :name     unique reviewer name; becomes the run's review focus
    :harness  harness alias from config.clj (resolved at review time)
    :contract the reviewer's single concern - precise, judgmental, actionable
    :scope    optional prompt-level confinement (phase 1: guidance text only;
              dynamic selection from git file changes is deliberately deferred
              to a future RFC)

  Routing note: keep entries on cheap tiers (explore = haiku, grunt = sonnet)
  and reserve frontier seats for judgment-heavy contracts. The synthesizer is
  the cross-vendor GPT seat so sign-off never comes from the model family
  that authored the work."
  (:require [skein.spools.agents :as agents]))

(def change-review
  "Roster fanned out over each reviewed change in this repository."
  {:reviewers
   [{:name "test-sleeps"
     :harness :grunt
     :contract (str "Hunt for sleeps and arbitrary timeouts in tests. They are nearly always "
                    "a hack: push the author toward event/condition-driven synchronization, "
                    "injected clocks, or deterministic scheduling, even when that means "
                    "rethinking the approach. A sleep is acceptable only when time itself is "
                    "a genuine component of the behavior under test - say so explicitly when "
                    "you accept one.")
     :scope "test files and test helpers in the change"}

    {:name "fail-loudly"
     :harness :explore
     :contract (str "Enforce TEN-003 (devflow/TENETS.md): unexpected input or state must fail "
                    "loudly, never fall back to 'sensible defaults', silent nil-punning, or "
                    "swallowed exceptions. Flag every new code path that guesses instead of "
                    "throwing, and every error whose message/data would leave an operator "
                    "without the failing value and the allowed alternatives.")}

    {:name "surface-minimalism"
     :harness :explore
     :contract (str "Enforce TEN-004 (devflow/TENETS.md): the change should expose the minimum "
                    "possible new public surface. Flag new API functions, CLI verbs, flags, or "
                    "attributes that userland composition of existing surface could replace, "
                    "and any new surface left undocumented or untested.")}

    {:name "spec-shapes"
     :harness :grunt
     :contract (str "Check that every public data shape the change introduces or reshapes - "
                    "registry inputs (defroster!/defharness!-style), weave pattern inputs, and "
                    "the input/output shapes of public seam functions - is defined by a "
                    "clojure.spec that validation actually consults, not prose alone. Manual "
                    "checks are acceptable only for what a spec cannot express (closed key "
                    "sets, cross-entry uniqueness); flag hand-rolled structural validation "
                    "where a spec should be the source of truth, and any public shape whose "
                    "spec exists but is unreferenced from its owning docstring or README.")}

    {:name "correctness"
     :harness :grunt
     :contract (str "Verify the change does what its strand contract and docs claim: trace the "
                    "main paths, check boundary and concurrency behavior against the actual "
                    "code, and flag regressions to adjacent behavior the diff touches. Report "
                    "only defects you can argue concretely with file:line references.")}

    {:name "docs-drift"
     :harness :explore
     :contract (str "Check documentation coherence: spool READMEs, devflow/specs root specs, "
                    "AGENTS.md/CLAUDE.md guidance, and ns docstrings must still describe the "
                    "code as changed. Flag every doc statement the diff falsifies and every "
                    "new behavior the docs should carry but do not.")}]

   :synthesizer {:harness :review-gpt}})

(defn install!
  "Register this repository's reviewer roster with the agents spool."
  []
  {:rosters [(agents/defroster! :change-review change-review)]})
