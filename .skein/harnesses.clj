(ns harnesses
  "Repo-local shuttle harness seats and routing policy.

  Every model seat delegation and review can route to is declared here, with
  the routing rationale beside the alias it applies to. `strand agent
  harnesses` lists the live registry; `.skein/reviewers.clj` declares which
  seats review a change. Aliases are weaver-lifetime state, so startup config
  re-registers them like queries and ops.

  Routing policy in brief: claude tiers mirror how we use agents (haiku
  explores, sonnet does grunt work, opus builds and reviews), GPT seats give
  cross-vendor validation so no model family signs off its own work, and the
  refactor/complex-patch flow inverts the docs routing — codex GPT seats
  author diff-shaped changes, Claude reviews them."
  (:require [skein.spools.agents :as agents]
            [skein.spools.shuttle :as shuttle]))

(defn- register-harness-aliases!
  "Register repo-local shuttle harness aliases."
  []
  [(shuttle/defalias! :pi-main
     {:alias-of :pi
      :extra-args ["--agent" "main"]
      :doc "pi main agent with scout subagents; preferred delegation harness."})
   ;; codex exec prints only the final message on stdout (activity log goes to
   ;; stderr), so :raw parses cleanly. Sessions persist to disk so `codex exec
   ;; resume <session-id>` can continue them; :resume splices that subcommand
   ;; ahead of the prompt (the global flags before it propagate into resume).
   ;; :raw does not capture a session id, so resume stays declared-but-inert
   ;; until a codex-json parse lands — persistence is never required
   ;; (PLAN-Pnl-001.R1/NG4), and a resume attempt fails loudly on the missing
   ;; id rather than starting cold. The bypass flag mirrors the shipped claude
   ;; harness: runs must reach the weaver socket outside any codex sandbox —
   ;; redefine with --sandbox workspace-write to tighten. Env inheritance must
   ;; be explicit: codex's default shell_environment_policy strips the PATH
   ;; entries that carry the strand/mill CLIs, leaving workers unable to reach
   ;; the coordination surface.
   (shuttle/defharness! :codex
     {:argv ["codex" "exec" "--skip-git-repo-check" "--color" "never"
             "--dangerously-bypass-approvals-and-sandbox"
             "-c" "shell_environment_policy.inherit=all"]
      :parse :raw
      :resume ["resume" :shuttle/session-id]
      :doc "Codex CLI (gpt-5.5) headless; final message on stdout."})
   ;; claude tiers mirror how we use agents: haiku explores, sonnet does
   ;; tests/grunt work, opus builds features and sits on councils
   (shuttle/defalias! :explore
     {:alias-of :claude
      :extra-args ["--model" "haiku"]
      :doc "Claude Haiku: fast read-only exploration and fan-out search."})
   (shuttle/defalias! :grunt
     {:alias-of :claude
      :extra-args ["--model" "sonnet"]
      :doc "Claude Sonnet: tests, mechanical edits, and grunt work."})
   (shuttle/defalias! :build
     {:alias-of :claude
      :extra-args ["--model" "opus"]
      :doc "Claude Opus: feature building, reviews, and council seats."})
   ;; Interactive TUI seat for `strand hitl` sessions. This cannot alias
   ;; :claude: the shipped harness is headless (`claude -p`, prompt on stdin)
   ;; and exits immediately inside a multiplexer pane — an interactive launch
   ;; requires the prompt as the initial argv message (the session owns stdin).
   (shuttle/defharness! :hitl-build
     {:argv ["claude" "--model" "opus" "--dangerously-skip-permissions"]
      :parse :raw
      :doc "Claude Opus interactive TUI for hitl multiplexer sessions; prompt rides as the initial argv message."})
   ;; oracle is deliberately scarce: single-case briefs with mandatory
   ;; incremental card notes — a context-overflowed Fable run that wrote
   ;; nothing costs an order of magnitude more than any other seat.
   (shuttle/defalias! :oracle
     {:alias-of :claude
      :extra-args ["--model" "claude-fable-5"]
      :doc "Claude Fable oracle: reserved for extreme diagnosis cases only — deep forensics and design diagnosis where cheaper seats have failed or the blast radius justifies it."})
   ;; GPT seats for cross-vendor validation. Routing policy: build (opus) is
   ;; favoured for anything prose/docs-heavy, but never signs off its own
   ;; work — sign-off review of opus-authored output always includes a GPT
   ;; harness. review-gpt is the standing reviewer seat; hard-gpt is for the
   ;; occasional difficult implementation task that wants a non-Claude model.
   (shuttle/defalias! :review-gpt
     {:alias-of :pi
      :extra-args ["--provider" "openai" "--model" "gpt-5.4" "--thinking" "high"]
      :doc "GPT-5.4 high reasoning via pi: independent review/validation seat; required sign-off reviewer for opus-authored docs work."})
   (shuttle/defalias! :hard-gpt
     {:alias-of :codex
      :extra-args ["-m" "gpt-5.5" "-c" "model_reasoning_effort=medium"]
      :doc "GPT-5.5 medium reasoning via codex exec: occasional difficult implementation tasks needing a second frontier model."})
   ;; The refactor/complex-patch flow: for patch-based work over existing code
   ;; (refactors, storage rewrites, mechanical-but-delicate migrations) codex
   ;; GPT seats author and Claude reviews — the inverse of the docs routing.
   ;; codex is heavily tuned for git-diff-shaped work; opus is stronger
   ;; greenfield, so it takes the review eye instead. patch-gpt (low) is the
   ;; default implementer; escalate the hardest slices to hard-gpt (medium);
   ;; review with the complex-patch-review roster (.skein/reviewers.clj:
   ;; opus design seat + gpt-5.4-high thorough seat).
   (shuttle/defalias! :patch-gpt
     {:alias-of :codex
      :extra-args ["-m" "gpt-5.5" "-c" "model_reasoning_effort=low"]
      :doc "GPT-5.5 low reasoning via codex exec: default implementer seat for the refactor/complex-patch flow — precise diff-based edits over existing code; escalate the hardest slices to hard-gpt."})
   ;; Cheap GPT-5.4-mini seats verified by the bench spool smoke run. Keep them
   ;; scoped to low-stakes, single-concern review/recon/validation work unless a
   ;; larger run proves they hold up on broader tasks.
   (shuttle/defalias! :mini-gpt-codex
     {:alias-of :codex
      :extra-args ["-m" "gpt-5.4-mini" "-c" "model_reasoning_effort=medium"]
      :doc "GPT-5.4-mini via codex exec: cheap seat for single-concern review, recon, and validation tasks."})
   (shuttle/defalias! :mini-gpt-pi
     {:alias-of :pi
      :extra-args ["--provider" "openai" "--model" "gpt-5.4-mini" "--thinking" "low"]
      :doc "GPT-5.4-mini via pi: cheap seat for single-concern review, recon, and validation tasks."})])

(defn install!
  "Register the repo's harness seats and the default review contract."
  []
  {:installed true
   :namespace 'harnesses
   :harnesses (register-harness-aliases!)
   ;; agent review consumes the one authoritative policy text by default; the
   ;; text itself ships from skein.spools.agents, set-default-review-contract!
   ;; still lives on the shuttle engine
   :review-contract (shuttle/set-default-review-contract! agents/review-contract)})
