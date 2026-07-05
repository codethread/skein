(ns reviewers
  "This repository's declarative reviewer roster.

  This file is the source of truth for who reviews a change in this
  workspace: many small, cheap, single-concern reviewers instead of a couple
  of generalists. Each entry is one independent read-only review run with its
  own precise contract; a synthesizer fans all findings into one verdict.

  Run it over any strand (kanban card, plan root, task) from the worktree
  where the diff lives, and name the diff surface so reviewers stop
  re-deriving it:

      strand agent review <target-id> --roster change-review --cwd <worktree> \\
        --commit-range <base>..HEAD

  The commit range is expanded to its changed files and injected into every
  reviewer prompt as the authoritative diff surface. Discover the live
  registered roster with `strand agent rosters`. The registry is
  weaver-lifetime state: edits here need a weaver restart or config reload to
  take effect.

  Entry shape (validated loudly by skein.spools.agents/defroster!):
    :name     unique reviewer name; becomes the run's review focus
    :harness  harness alias from config.clj (resolved at review time)
    :contract the reviewer's single concern - precise, judgmental, actionable
    :scope    optional prompt-level confinement (guidance text only). The
              changed-file list and commit range are supplied separately as
              the review's change context (--commit-range); only dynamic
              reviewer *selection* from git changes stays deferred to an RFC.

  Routing note: route by waste-type, not call count. grunt (sonnet) is the
  default for read-through review seats - its instinct is targeted git-diff
  and ranged file reads, so it does not thrash whole namespaces for a small
  window. Reserve explore (haiku) for trivially greppable single-file concerns
  whose contract tells it to do one global diff sweep - haiku is ~0.25-0.3x
  sonnet's per-call cost, so a few extra greps there are still net-cheaper.
  Reserve frontier seats for judgment-heavy contracts. The synthesizer is the
  cross-vendor GPT seat so sign-off never comes from the model family that
  authored the work, and it de-duplicates overlapping findings by root cause."
  (:require [skein.spools.agents :as agents]))

(def change-review
  "Roster fanned out over each reviewed change in this repository."
  {:reviewers
   [{:name "test-sleeps"
     :harness :explore
     :contract (str "Hunt for sleeps and arbitrary timeouts in tests. They are nearly always "
                    "a hack: push the author toward event/condition-driven synchronization, "
                    "injected clocks, or deterministic scheduling, even when that means "
                    "rethinking the approach. A sleep is acceptable only when time itself is "
                    "a genuine component of the behavior under test - say so explicitly when "
                    "you accept one. Budget: one global `git diff` over the changed test files "
                    "in your change context, then grep that for sleep/Thread/timeout - do not "
                    "read files whole, re-grep per commit, or spelunk history; a handful of "
                    "tool calls suffices.")
     :scope "test files and test helpers in the change"}

    {:name "fail-loudly"
     :harness :grunt
     :contract (str "Enforce TEN-003 (devflow/TENETS.md): unexpected input or state must fail "
                    "loudly, never fall back to 'sensible defaults', silent nil-punning, or "
                    "swallowed exceptions. Flag every new code path that guesses instead of "
                    "throwing, and every error whose message/data would leave an operator "
                    "without the failing value and the allowed alternatives. Read the changed "
                    "files by range around the diff, not whole namespaces. This lens overlaps "
                    "spec-shapes and correctness on shared defects: state your fail-loud angle "
                    "concisely and let the synthesizer merge by root cause. Budget ~12-15 calls.")}

    {:name "surface-minimalism"
     :harness :grunt
     :contract (str "Enforce TEN-004 (devflow/TENETS.md): the change should expose the minimum "
                    "possible new public surface. Flag new API functions, CLI verbs, flags, or "
                    "attributes that userland composition of existing surface could replace, "
                    "and any new surface left undocumented or untested. Work from the "
                    "changed-file list with targeted diff reads; do not read whole namespaces "
                    "or re-read a file in slices after a whole read. Budget ~12-15 calls.")}

    {:name "spec-shapes"
     :harness :grunt
     :contract (str "Check that every public data shape the change introduces or reshapes - "
                    "registry inputs (defroster!/defharness!-style), weave pattern inputs, and "
                    "the input/output shapes of public seam functions - is defined by a "
                    "clojure.spec that validation actually consults, not prose alone. Manual "
                    "checks are acceptable only for what a spec cannot express (closed key "
                    "sets, cross-entry uniqueness); flag hand-rolled structural validation "
                    "where a spec should be the source of truth, and any public shape whose "
                    "spec exists but is unreferenced from its owning docstring or README. Use "
                    "ranged reads around the changed definitions, not whole-namespace reads. "
                    "This lens overlaps fail-loudly and correctness on validation defects: "
                    "state your spec-coverage angle concisely and let the synthesizer merge by "
                    "root cause. Budget ~15 calls.")}

    {:name "correctness"
     :harness :grunt
     :contract (str "Verify the change does what its strand contract and docs claim: trace the "
                    "main paths, check boundary and concurrency behavior against the actual "
                    "code, and flag regressions to adjacent behavior the diff touches. Report "
                    "only defects you can argue concretely with file:line references. Trust the "
                    "changed-file list in your change context as the diff surface; do not re-run "
                    "full test suites to chase a flake or spelunk unrelated strands. This lens "
                    "overlaps fail-loudly and spec-shapes on shared defects: state your "
                    "correctness angle concisely and let the synthesizer merge by root cause. "
                    "Budget ~15-20 calls.")}

    {:name "docs-drift"
     :harness :grunt
     :contract (str "Check documentation and metadata coherence only - prose, not "
                    "implementation. Confine your review to spool READMEs, devflow/specs root "
                    "specs, AGENTS.md/CLAUDE.md guidance, ns docstrings, and metadata such as "
                    "'Last Updated' dates: flag every doc or metadata statement the diff "
                    "falsifies and every new behavior the docs should carry but do not. Do not "
                    "re-verify the implementation or re-run tests - correctness owns that lane; "
                    "a doc claim is drift only when the prose contradicts the changed code. "
                    "Read prose files and the diff, not whole source namespaces. Budget ~12 calls.")
     :scope "documentation, specs, and metadata files - never source implementation"}]

   :synthesizer {:harness :review-gpt}})

(defn install!
  "Register this repository's reviewer roster with the agents spool."
  []
  {:rosters [(agents/defroster! :change-review change-review)]})
