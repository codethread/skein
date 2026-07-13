(ns harnesses
  "Repo-local harness seats and routing policy.

  Naming convention: aliases are pure model handles (`:sol-low`,
  `:terra-med`, `:opus`) — never role names, so a seat cannot be routed by
  connotation. A `-ro` suffix marks the read-only-sandbox variant of a seat.
  Role policy lives where it is applied: reviewer rosters pick seats in
  `.skein/reviewers.clj`, workflows take seats as parameters.

  Every seat doc leads with a scorecard a coordinator can scan:

    {:complexity N :code-taste N :resilience N :ui-design N
     :coordination N :cost N}

  - :complexity    hardest task it completes reliably (9 best)
  - :code-taste    repo-idiom fidelity, API design, test quality (9 best)
  - :resilience    recovery from broken/hostile environments (9 best)
  - :ui-design     interface/UX design taste (9 best)
  - :coordination  sub-supervision: seat routing, constraint tracking,
                   delegation judgment (9 best)
  - :cost          9 is cheapest per task
  An axis of X is untested; `-` means do not route that axis's work to the
  seat at all. Pick the best-scoring seat across the axes the task actually
  needs.

  Scores are provenanced claims, never vibes: each doc names its source
  bench (or marks the axis a prior/estimate), and scores are revised only
  from new bench evidence. Current source: pandora-task-002 bench
  2026-07-13, card nihrl — 28 entries, three environment conditions;
  ui-design axes are coordinator priors (the bench had no UI signal);
  coordination axes come from the 2026-07-13 three-seat comprehension test
  (opus/terra/sol asked to derive routing policy from this registry alone)
  plus coordinator judgment.

  Prompting note: gpt seats run well on goals and contracts alone — keep
  their prompts to objective, constraints, and Done-when. Reserve explicit
  'don't do X' guardrail lists for claude seats, which benefit from them;
  prohibition-heavy prompts on gpt seats dilute the objective (coordinator
  observation, 2026-07-13).

  Aliases are weaver-lifetime state, so startup config re-registers them
  like queries and ops. `strand agent harnesses` lists the live registry.
  pi seats are deprioritized pending a pi-vs-codex harness bench (codex
  currently wins on RAM/CPU under concurrent load); the pi harness itself
  stays registered via its spool."
  (:require [skein.api.format.alpha :as format-alpha]
            [skein.spools.delegation :as agents]
            [skein.spools.agent-run :as shuttle]))

;; gpt-5.6 rate cards, USD per 1M tokens, hand-pinned 2026-07-13 from
;; https://developers.openai.com/api/docs/pricing (gpt-5.x models are not in
;; public rate datasets). Re-pin when OpenAI changes list prices.
(def ^:private sol-rates {:input 5.0 :cache-read 0.5 :output 30.0})
(def ^:private terra-rates {:input 2.5 :cache-read 0.25 :output 15.0})
(def ^:private luna-rates {:input 1.0 :cache-read 0.1 :output 6.0})

(defn- register-harness-aliases!
  "Register repo-local harness aliases."
  []
  [;; Sessions persist to disk, so `codex exec resume <session-id>` can
   ;; continue them once a codex-json parse captures session ids; :resume
   ;; splices that subcommand ahead of the prompt (the global flags before it
   ;; propagate into resume). Persistence is never required
   ;; (PLAN-Pnl-001.R1/NG4).
   ;; :codex reports tokens but never dollar cost (subscription auth), so cost
   ;; rides a hand-authored rate card. The base card prices the CLI's default
   ;; model (gpt-5.5, post-5.6-launch cut, pinned 2026-07-11); every 5.6 seat
   ;; overrides with its tier card above.
   (shuttle/defharness! :codex
     {:argv ["codex" "exec" "--json" "--skip-git-repo-check" "--color" "never"
             "--dangerously-bypass-approvals-and-sandbox"
             "-c" "shell_environment_policy.inherit=all"]
      :parse :codex-json
      :resume ["resume" :agent-run/session-id]
      :cost-rates {:input 1.25 :cache-read 0.125 :output 10.0}
      :doc (format-alpha/reflow
            "|Codex CLI headless, read/write: agentic coding harness with the
             |strongest general-purpose web search of the roster. Seats pin models
             |with -m and effort with -c model_reasoning_effort. Final message
             |prints on stdout as a JSONL event stream (activity log on stderr);
             |:codex-json captures the agent_message result, thread session id, and
             |cumulative token usage, and the :cost-rates card derives cost-usd
             |(codex reports no dollar cost). Runs bypass the codex sandbox so
             |workers reach the weaver socket, and env inheritance is explicit
             |because codex's default shell_environment_policy strips the PATH
             |entries carrying the strand/mill CLIs. :resume continues the captured
             |session id.")})
   ;; Enforced read-only variant behind the -ro seats: codex's own sandbox
   ;; denies writes instead of relying on prompt discipline. KNOWN LIMIT
   ;; (verified, codex 0.144.2): the seatbelt also blocks AF_UNIX connects,
   ;; and the shipped review/worker contracts drive the strand CLI over the
   ;; weaver socket (agent note / strand show) — so -ro seats suit contracts
   ;; whose findings ride the run result, not standard note-appending roster
   ;; reviews, until a `-c permissions...` unix-socket grant for the weaver
   ;; state dir is proven in a disposable world. `-a never` keeps sandboxed
   ;; runs headless instead of hanging on an approval prompt. `codex exec
   ;; review` (headless --json review over --base/--commit/--uncommitted) is
   ;; a further candidate harness once ranges can ride argv.
   (shuttle/defharness! :codex-ro
     {:argv ["codex" "exec" "--json" "--skip-git-repo-check" "--color" "never"
             "--sandbox" "read-only" "-a" "never"
             "-c" "shell_environment_policy.inherit=all"]
      :parse :codex-json
      :resume ["resume" :agent-run/session-id]
      :cost-rates {:input 1.25 :cache-read 0.125 :output 10.0}
      :doc (format-alpha/reflow
            "|Codex CLI headless with the enforced read-only sandbox: identical
             |event stream, parse, resume, and rate-card behavior to :codex; the
             |sandbox is the only difference. A seat needing even one write — or
             |the strand CLI (weaver socket) — belongs on :codex instead.")})
   ;; --- gpt-5.6 tier seats -------------------------------------------------
   (shuttle/defalias! :luna-low
     {:alias-of :codex
      :extra-args ["-m" "gpt-5.6-luna" "-c" "model_reasoning_effort=low"]
      :cost-rates luna-rates
      :doc (format-alpha/reflow
            "|{:complexity 3 :code-taste 4 :resilience 1 :ui-design 2
             | :coordination - :cost 9}
             |gpt-5.6-luna low via codex. Concrete, well-scoped recon and fan-out
             |search at the lowest cost of the roster (benched $0.12-0.36/task);
             |quits at the first sign of environment friction and benched lowest
             |on authored-code quality. Scores: pandora-task-002 bench (card
             |nihrl); ui-design is a prior.")})
   (shuttle/defalias! :luna-low-ro
     {:alias-of :codex-ro
      :extra-args ["-m" "gpt-5.6-luna" "-c" "model_reasoning_effort=low"]
      :cost-rates luna-rates
      :doc (format-alpha/reflow
            "|Read-only-sandbox variant of :luna-low (same scorecard). For
             |contracts whose findings ride the run result — no strand CLI
             |mid-run (see :codex-ro socket limit).")})
   (shuttle/defalias! :terra-med
     {:alias-of :codex
      :extra-args ["-m" "gpt-5.6-terra" "-c" "model_reasoning_effort=medium"]
      :cost-rates terra-rates
      :doc (format-alpha/reflow
            "|{:complexity 5 :code-taste 7 :resilience 2 :ui-design 4
             | :coordination 7 :cost 7}
             |gpt-5.6-terra medium via codex. Well-defined single-concern review
             |and validation on clean checkouts — benched the cleanest
             |test-writing of the codex tiers at ~40% of sol's price; missed
             |cross-package fallout when it could not run tests and gives up on
             |broken toolchains. Scores: pandora-task-002 bench (card nihrl);
             |ui-design is a prior.")})
   (shuttle/defalias! :terra-med-ro
     {:alias-of :codex-ro
      :extra-args ["-m" "gpt-5.6-terra" "-c" "model_reasoning_effort=medium"]
      :cost-rates terra-rates
      :doc (format-alpha/reflow
            "|Read-only-sandbox variant of :terra-med (same scorecard). For
             |contracts whose findings ride the run result — no strand CLI
             |mid-run (see :codex-ro socket limit).")})
   (shuttle/defalias! :sol-low
     {:alias-of :codex
      :extra-args ["-m" "gpt-5.6-sol" "-c" "model_reasoning_effort=low"]
      :cost-rates sol-rates
      :doc (format-alpha/reflow
            "|{:complexity 6 :code-taste 6 :resilience 9 :ui-design 5
             | :coordination X :cost 5}
             |gpt-5.6-sol low via codex. General build and default delegation
             |seat, including diff-shaped refactor work: the only benched model
             |that shipped a passing gate under every environment condition —
             |hostile, deceptive, and working toolchains — recovering the
             |environment itself when needed (nodejs.org download; volta-image
             |discovery). Scores: pandora-task-002 bench (card nihrl); ui-design
             |is a prior.")})
   (shuttle/defalias! :sol-med
     {:alias-of :codex
      :extra-args ["-m" "gpt-5.6-sol" "-c" "model_reasoning_effort=medium"]
      :cost-rates sol-rates
      :doc (format-alpha/reflow
            "|{:complexity 7 :code-taste 8 :resilience 9 :ui-design 5
             | :coordination 8 :cost 4}
             |gpt-5.6-sol medium via codex. Best benched codex quality (8.5/10
             |known-work arm) with sol's full environment resilience — the
             |step-up seat when quality matters more than ~2x :sol-low's cost,
             |the standing cross-vendor sign-off reviewer for claude-authored
             |work, and the delegated sub-supervisor seat when a run must route
             |and track other runs. Scores: pandora-task-002 bench (card nihrl);
             |ui-design is a prior; coordination from the 2026-07-13 three-seat
             |comprehension test.")})
   (shuttle/defalias! :sol-med-ro
     {:alias-of :codex-ro
      :extra-args ["-m" "gpt-5.6-sol" "-c" "model_reasoning_effort=medium"]
      :cost-rates sol-rates
      :doc (format-alpha/reflow
            "|Read-only-sandbox variant of :sol-med (same scorecard). For
             |contracts whose findings ride the run result — no strand CLI
             |mid-run (see :codex-ro socket limit).")})
   (shuttle/defalias! :sol-high
     {:alias-of :codex
      :extra-args ["-m" "gpt-5.6-sol" "-c" "model_reasoning_effort=high"]
      :cost-rates sol-rates
      :doc (format-alpha/reflow
            "|{:complexity X :code-taste X :resilience X :ui-design X
             | :coordination X :cost 3}
             |gpt-5.6-sol high via codex. The most complex implementation tasks
             |before an :oracle escalation. High effort is untested in our bench
             |(low and medium verified; sol-medium scored 7/8/9) — treat early
             |runs as trials, note outcomes on the card, and score from the next
             |bench.")})
   ;; --- claude seats -------------------------------------------------------
   (shuttle/defalias! :opus
     {:alias-of :claude
      :extra-args ["--model" "opus"]
      :doc (format-alpha/reflow
            "|{:complexity 8 :code-taste 9 :resilience X :ui-design 9
             | :coordination 6 :cost 2}
             |Claude Opus. Greenfield feature building, API design, and critical
             |seams: archaeology-first — reuses existing repo seams and cites
             |precedent where gpt tiers invent parallel abstractions — and won
             |the bench's known-work quality arm outright. Keep cross-vendor
             |sign-off: a GPT seat reviews opus-authored changes. Scores:
             |pandora-task-002 bench (card nihrl); resilience untested (the
             |claude harness inherits host env, so it never faced the handicap);
             |ui-design is a coordinator prior. Sonnet holds no seat: it benched costlier
             |than opus and ranked below it.")})
   ;; Interactive TUI seat for `strand hitl` sessions. This cannot alias
   ;; :claude: the shipped harness is headless (`claude -p`, prompt on stdin)
   ;; and exits immediately inside a multiplexer pane — an interactive launch
   ;; requires the prompt as the initial argv message (the session owns stdin).
   (shuttle/defharness! :hitl-fable
     {:argv ["claude" "--model" "claude-fable-5" "--dangerously-skip-permissions"]
      :parse :raw
      :doc "Claude Fable interactive TUI: the goto hitl seat at the top of the graph; prompt rides as the initial argv message."})
   (shuttle/defharness! :hitl-opus
     {:argv ["claude" "--model" "opus" "--dangerously-skip-permissions"]
      :parse :raw
      :doc "Claude Opus interactive TUI for hitl multiplexer sessions when fable-level depth is not warranted."})
   (shuttle/defalias! :oracle
     {:alias-of :claude
      :extra-args ["--model" "claude-fable-5"]
      :doc (format-alpha/reflow
            "|{:complexity 9 :code-taste 9 :resilience X :ui-design 8
             | :coordination 9 :cost 1}
             |Claude Fable. Deepest reasoning seat, reserved for extreme
             |diagnosis cases only — forensics and design diagnosis where cheaper
             |seats have failed or the blast radius justifies it. Brief one case
             |per run and require incremental card notes: a context-overflowed
             |run that wrote nothing costs an order of magnitude more than any
             |other seat. Also the top-of-graph coordination seat: the goto
             |hitl agent (see :hitl-fable) and the highest-trust supervisor
             |when one run must own the whole graph. Scores: coordinator priors
             |plus bench-judge duty; resilience untested, ui-design a prior.")})
   ;; --- cheap validation and fallback seats ---------------------------------
   (shuttle/defalias! :gpt-mini
     {:alias-of :codex
      :extra-args ["-m" "gpt-5.4-mini" "-c" "model_reasoning_effort=medium"]
      ;; overrides the :codex base card: this seat runs gpt-5.4-mini, so it
      ;; prices on the mini tier. USD per 1M tokens, hand-pinned from
      ;; https://openai.com/api/pricing/ (pinned 2026-07-11).
      :cost-rates {:input 0.25 :cache-read 0.025 :output 2.0}
      :doc (format-alpha/reflow
            "|{:complexity 2 :code-taste 3 :resilience X :ui-design X
             | :coordination - :cost 9}
             |gpt-5.4-mini via codex. Low-stakes single-concern review, recon,
             |and validation. Scores estimated from a bench-spool smoke run only
             |— don't route broader work here until a larger run proves it holds
             |up.")})
   (shuttle/defalias! :deepseek
     {:alias-of :pi
      :extra-args ["--agent" "main" "--model" "deepseek/deepseek-v4-pro:high"]
      :doc (format-alpha/reflow
            "|UNSCORED (unbenched; :coordination -). DeepSeek v4 Pro (high
             |thinking) via pi:
             |fallback seat for when codex and anthropic provider usage runs
             |out. Route reviews of primarily Claude-authored code here, not
             |frontier design or broad implementation work.")})])

(defn install!
  "Register the repo's harness seats and the default review contract."
  []
  {:installed true
   :namespace 'harnesses
   :harnesses (register-harness-aliases!)
   ;; agent review consumes the one authoritative policy text by default; the
   ;; text itself ships from skein.spools.delegation, set-default-review-contract!
   ;; still lives on the agent-run engine
   :review-contract (shuttle/set-default-review-contract! agents/review-contract)})
