(ns reviewers
  "This repository's declarative reviewer roster.

  This file is the source of truth for who reviews a change in this
  workspace: many small, cheap, single-concern reviewers instead of a couple
  of generalists. Each entry is one independent read-only review run with its
  own precise contract; a synthesizer fans all findings into one verdict.

  Findings append as notes on the review target, so run it over the feature's
  task strand — never the kanban card, whose notes stay lean for handover;
  `agent review` rejects card targets outright — from the worktree where the
  diff lives, and name the diff surface so reviewers stop re-deriving it:

      strand agent review <task-id> --roster change-review --cwd <worktree> \\
        --commit-range <base>..HEAD

  The commit range is expanded to its changed files and injected into every
  reviewer prompt as the authoritative diff surface. Discover the live
  registered roster with `strand agent rosters`. The registry is
  weaver-lifetime state: edits here need a weaver restart or config reload to
  take effect.

  Entry shape (validated loudly by skein.spools.delegation/defroster!):
    :name     unique reviewer name; becomes the run's review focus
    :harness  harness alias from harnesses.clj (resolved at review time)
    :contract the reviewer's single concern - precise, judgmental, actionable
    :scope    optional prompt-level confinement (guidance text only). The
              changed-file list and commit range are supplied separately as
              the review's change context (--commit-range); only dynamic
              reviewer *selection* from git changes stays deferred to an RFC.

  Routing note: pick seats by scanning the scorecards in
  .skein/harnesses.clj ({:complexity :code-taste :resilience :ui-design
  :cost}, X = untested), matching the axes the contract actually needs -
  route by waste-type, not call count. In practice (review-sweeps bench
  2026-07-13, card vw8pf): mechanical single-target contracts over a
  small diff take the cheapest seat (:luna-low, cost 9) - it matched
  :terra-med's recall on every change-review lens at ~28% of the price,
  and its occasional cite typo is cheap for the synthesizer to merge;
  contracts that must sweep for coherence beyond the changed-file list
  (docs-drift's generated api.md and README index checks) take
  :terra-med; judgment-heavy contracts take :sol-med or :opus. Boundary
  caveat: benched on a single-commit three-file diff - re-probe before
  trusting :luna-low on large multi-file changes. Note
  reviewers must run on read/write seats for now: findings append via the
  strand CLI over the weaver socket, which the -ro sandbox blocks (see
  :codex-ro in harnesses.clj) - read-only stays prompt-discipline. Two
  sign-off invariants: the synthesizer is never a seat that reviewed, and at
  least one reviewing seat comes from outside the authoring model family."
  (:require [skein.spools.delegation :as agents]))

(def change-review
  "Roster fanned out over each reviewed change in this repository."
  {:reviewers
   [{:name "test-sleeps"
     :harness :luna-low
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
     :harness :luna-low
     :contract (str "Enforce TEN-003 (devflow/TENETS.md): unexpected input or state must fail "
                    "loudly, never fall back to 'sensible defaults', silent nil-punning, or "
                    "swallowed exceptions. Flag every new code path that guesses instead of "
                    "throwing, and every error whose message/data would leave an operator "
                    "without the failing value and the allowed alternatives. Read the changed "
                    "files by range around the diff, not whole namespaces. This lens overlaps "
                    "spec-shapes and correctness on shared defects: state your fail-loud angle "
                    "concisely and let the synthesizer merge by root cause. Budget ~12-15 calls.")}

    {:name "surface-minimalism"
     :harness :luna-low
     :contract (str "Enforce TEN-004 (devflow/TENETS.md): the change should expose the minimum "
                    "possible new public surface. For EVERY new op, public fn, CLI verb, flag, "
                    "or attribute, answer explicitly in your findings: could an existing op, a "
                    "new flag on an existing op, or userland composition of existing surface "
                    "serve instead? State the answer even when it is no - the enumeration is "
                    "the deliverable, not an afterthought behind doc/test coverage. Also flag "
                    "any new surface left undocumented or untested. For every new or changed "
                    "op, verb, or flag, compare its name and shape with the CLI style section "
                    "of docs/spools/writing-shared-spools.md#cli-style and note whether it uses "
                    "the applicable skein.api.spool.alpha arg-spec fragment (note-surface, "
                    "work-root, timeout-secs, or outcome). Style or fragment divergence is an "
                    "ADVISORY finding for the synthesizer to weigh, never a gate. The sole "
                    "must-fix class is a text-bearing flag or positional declared outside the "
                    "declared arg-spec parser: it loses whole-value :stdin and :payload/<name> "
                    "resolution, so that is correctness rather than style. Work from the "
                    "changed-file list with targeted diff reads and one bounded guide read; do "
                    "not read whole namespaces or re-read a file in slices after a whole read. "
                    "Budget ~15-18 calls.")}

    {:name "spec-shapes"
     :harness :luna-low
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
     :harness :luna-low
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
     :harness :terra-med
     :contract (str "Check documentation and metadata coherence only - prose, not "
                    "implementation. Confine your review to spool READMEs, devflow/specs root "
                    "specs, AGENTS.md/CLAUDE.md guidance, ns docstrings, and metadata such as "
                    "'Last Updated' dates: flag every doc or metadata statement the diff "
                    "falsifies and every new behavior the docs should carry but do not. When "
                    "the diff should carry new guidance, enforce placement (PHILOSOPHY.md "
                    "'Prose guides, code decides'): workflow discipline belongs in the "
                    "authored prime/about manuals, shipped contracts in devflow/specs, "
                    "mechanically checkable rules in an automated gate (lint config or a "
                    "CI make target); AGENTS.md accepts only "
                    "undiscoverable content - build/validation commands, safety rules, "
                    "run-first pointers. Flag any AGENTS.md addition that restates what a "
                    "prime/about manual, spec, or TENETS.md already owns: name the owning "
                    "surface and recommend moving the prose there, never keeping both. Do not "
                    "re-verify the implementation or re-run tests - correctness owns that lane; "
                    "a doc claim is drift only when the prose contradicts the changed code. "
                    "Read prose files and the diff, not whole source namespaces. Budget ~15 calls.")
     :scope "documentation, specs, and metadata files - never source implementation"}]

   :synthesizer {:harness :sol-med}})

(def complex-patch-review
  "Roster for GPT-authored implementation changes — the refactor/complex-patch
  flow (:sol-low / :sol-high author). Two deep seats instead of the
  single-concern fan-out: :opus for holistic architecture/idiom judgment
  (cross-vendor - it never authored the patch, so no self-sign-off), and
  :sol-med for the exhaustive line-level pass (same family as the author;
  the opus seat is the independent eye, and sol-med benched the strongest
  line-level codex quality). Synthesized by :terra-med so synthesis comes
  from neither reviewing seat. Compose with change-review when a change also
  wants the cheap single-concern sweeps (test-sleeps, docs-drift, ...)."
  {:reviewers
   [{:name "opus-design"
     :harness :opus
     :contract (str "Deep architecture and idiom review of a GPT-authored patch. Judge: does "
                    "the change respect the repo's namespace tiers, runtime-publication "
                    "discipline, and implementation boundaries (CLAUDE.md); is complexity "
                    "placed in the right module (deep-module: core absorbs storage/semantics "
                    "burden, contracts stay thin); do abstractions, naming, and comment "
                    "density match the surrounding code; which decisions will age badly. "
                    "Read the diff first, then only the surrounding context it touches. "
                    "Judgment over nitpicks - style detail belongs to the fmt/lint gates.")}
    {:name "gpt-thorough"
     :harness :sol-med
     :contract (str "Exhaustive line-level correctness pass over the diff: logic errors, "
                    "boundary conditions, transaction and locking mistakes, error-path gaps "
                    "(TEN-003 fail-loudly), test assertions that do not test what they claim, "
                    "and divergence between the change and the spec/plan clauses it cites. "
                    "Work the diff hunk by hunk; verify claims against source with ranged "
                    "reads, never whole-namespace reads.")}]
   :synthesizer {:harness :terra-med}})

(def docs-review
  "Roster fanned out over human-facing prose changes: READMEs, docs/, spool
  contract docs and cookbooks, release notes. Distilled from the five-seat
  panel that reviewed the 2026-07 docs refresh and spool-cookbook batches
  (passes panel-0461821b, panel-1e14b735), whose seat mix repeatedly caught
  disjoint defect classes; seats remapped to the 2026-07-13 scorecard roster.
  reader-skeptic deliberately exercises :sol-high (scorecard X/untested) as
  its trial lane - downgrade to :sol-med if early runs disappoint. Generated
  `spools/*.api.md` files mirror docstrings and are out of scope."
  {:reviewers
   [{:name "docs-fact-check"
     :harness :sol-med
     :contract (str "Verify every command, flag, filename, path, and behavioral claim in the "
                    "changed prose against the repo's sources of truth: the contract docs, "
                    "generated api docs, devflow/specs, source, and tests. Spot-run the "
                    "highest-risk snippets where practical — `make build`, then a disposable "
                    "workspace from your own `ws=$(mktemp -d)` shell variable with every "
                    "--workspace expansion guarded as \"${ws:?}\"; NEVER the canonical .skein "
                    "world. Flag invented surface, stale claims the diff should have updated, "
                    "and citations that do not support the prose. Accuracy findings are "
                    "must-fix. Budget ~15-20 calls plus the snippet runs you choose.")}

    {:name "docs-tone"
     :harness :sol-med
     :contract (str "Police the house voice: warm, plain, confident. The prose is usually "
                    "opus-authored, which trends over-familiar and hyperbolic, so flag "
                    "overselling, forced enthusiasm, chumminess, empty hedging, and 'why' "
                    "prose that asserts instead of reasons — and equally lifeless corporate "
                    "flatness. Give a replacement sentence for every flag. Read only the "
                    "changed prose files. Budget ~10-12 calls.")}

    {:name "llm-tells"
     :harness :terra-med
     :contract (str "Sweep the changed prose with the docs-style checklist skill at "
                    ".claude/skills/docs-style/SKILL.md (in this worktree): grep its word "
                    "tells, contrast reframes ('it's not X, it's Y'), rule-of-three padding, "
                    "em-dash overuse, and bold-term-colon bullet walls, then do its cadence "
                    "read. Report each hit with file, section, offending text, and a plain "
                    "rewrite. Skip generated *.api.md files. Budget ~10-12 calls.")}

    {:name "reader-newbie"
     :harness :luna-low
     :contract (str "PERSONA: a developer meeting this material for the first time — no "
                    "Clojure, no prior Skein vocabulary beyond getting-started. Read the "
                    "changed docs top to bottom, in order, taking every instruction "
                    "literally. Report only comprehension breaks: terms used before they are "
                    "explained, steps that assume unstated knowledge, snippets that do not "
                    "say where they run (shell vs REPL vs config), and points where you "
                    "cannot tell what to do next. One linear read per file; no source "
                    "spelunking. Budget ~8-10 calls.")}

    {:name "reader-skeptic"
     :harness :sol-high
     :contract (str "PERSONA: a tired tech lead deciding whether this documentation helps or "
                    "rots. For each changed doc: does it solve problems a real team hits, or "
                    "is it filler demonstrating API calls? Does its reasoning give the cost "
                    "of the alternative or just vibes? Are cited sources real, durable, and "
                    "load-bearing — never session anecdotes or hardcoded incidental detail "
                    "that will silently rot? Name anything you would cut. Budget ~12-15 calls.")}]

   :synthesizer {:harness :sol-med}})

(defn install!
  "Register this repository's reviewer rosters with the delegation spool."
  []
  {:rosters [(agents/defroster! :change-review change-review)
             (agents/defroster! :complex-patch-review complex-patch-review)
             (agents/defroster! :docs-review docs-review)]})
