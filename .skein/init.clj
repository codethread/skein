;; Startup entrypoint for the repo's canonical coordination world. Repo policy
;; is split one file per concern, each activated below in dependency order:
;;   config.clj     — named queries and the CLI op surface
;;   workflows.clj  — hand-authored workflows (land, delegate-pipeline)
;;   harnesses.clj  — harness seats and routing policy
;;   reviewers.clj  — reviewer rosters
;;   attention.clj  — chime attention rules
;;   nvd_scan.clj   — NVD scan cron job
;;   analytics.clj  — agent-run cost/usage rollups (`strand feature-costs`)
;; Gitignored init.local.clj binds each developer's notifier. Read docs/reference.md
;; before changing this config; smoke-test changes in a disposable world first.
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/sync! runtime)
;; batteries is the one classpath spool: its source ships on :paths rather than
;; through a synced spool root, so it is required explicitly before its use!.
;; The require loads the namespace, so use!'s load-synced-namespace! short-circuits
;; at its find-ns guard and needs no :spools guard (documented exception).
(require 'skein.spools.batteries)
(runtime/use! runtime :skein/spools-batteries
              {:ns 'skein.spools.batteries
               :call 'skein.spools.batteries/install!})
(runtime/use! runtime :skein/spools-workflow
              {:ns 'skein.spools.workflow
               :spools ['skein.spools/workflow]
               :call 'skein.spools.workflow/install!})
(runtime/use! runtime :skein/spools-roster
              {:ns 'skein.spools.roster
               :spools ['skein.spools/roster]
               :call 'skein.spools.roster/install!})
;; The shell executor ships in the workflow spool root and fulfils :shell workflow
;; gates by running the gate command directly. Its install! runs an initial
;; scan, so it is ordered after workflow (which owns the executor registry it
;; registers into).
(runtime/use! runtime :skein/spools-shell
              {:ns 'skein.spools.executors.shell
               :spools ['skein.spools/workflow]
               :after [:skein/spools-workflow]
               :call 'skein.spools.executors.shell/install!})
;; UNSAFE spool: text-search reaches past the blessed api.* contract into
;; skein.core.db to LIKE-search titles and attribute values, including archived
;; rows the query language cannot see. It is a maintained, in-the-open example
;; of rule-breaking (see spools/text-search.md), not a blessed path — activated
;; here so the reference stays exercised.
(runtime/use! runtime :skein/spools-text-search
              {:ns 'skein.spools.text-search
               :spools ['skein.spools/text-search]
               :call 'skein.spools.text-search/install!})
;; devflow is an external git-distributed spool: activation is gated on the
;; approved codethread/devflow coordinate (spools.edn pin or a developer's
;; spools.local.edn checkout), never on an incidental classpath copy.
(runtime/use! runtime :skein/spools-devflow
              {:ns 'ct.spools.devflow
               :spools ['codethread/devflow]
               :call 'ct.spools.devflow/install!
               :required? true})
(runtime/use! runtime :macros/patterns
              {:ns 'skein.macros.patterns
               :spools ['skein.macros/macros]
               :required? true})
(runtime/use! runtime :macros/demo
              {:ns 'skein.macros.demo
               :spools ['skein.macros/macros]
               :after [:macros/patterns]
               :call 'skein.macros.demo/install!
               :required? true})
(runtime/use! runtime :skein/spools-shuttle
              {:ns 'ct.spools.agent-run
               :spools ['ct.spools/agent-run]
               :call 'ct.spools.agent-run/install!
               :required? true})
(runtime/use! runtime :skein/spools-delegation
              {:ns 'ct.spools.delegation
               :spools ['ct.spools/delegation]
               :after [:skein/spools-shuttle]
               :call 'ct.spools.delegation/install!
               :required? true})
(runtime/use! runtime :skein/spools-bench
              {:ns 'ct.spools.bench
               :spools ['ct.spools/bench]
               :after [:skein/spools-shuttle]
               :call 'ct.spools.bench/install!
               :required? true})
;; Repo policy modules, split by use case (each file is one concern):
;; harnesses.clj — model seats + routing policy; workflows.clj — hand-authored
;; land workflow + delegate-pipeline pattern; attention.clj — chime rules;
;; nvd_scan.clj — the scheduled NVD deep-scan cron job; reviewers.clj — the
;; declarative reviewer roster; analytics.clj — agent-run cost/usage rollups;
;; config.clj — named queries + the CLI op surface.
(runtime/use! runtime :harnesses
              {:file "harnesses.clj"
               :spools ['ct.spools/delegation 'ct.spools/agent-run]
               :after [:skein/spools-delegation]
               :call 'harnesses/install!
               :required? true})
;; The declarative reviewer roster lives in its own file so the "who reviews
;; a change here" policy stays a small git-reviewable data document. Roster
;; harness aliases resolve at review time, not registration time, so load
;; order relative to harnesses.clj is not load-bearing.
(runtime/use! runtime :reviewers
              {:file "reviewers.clj"
               :spools ['ct.spools/delegation]
               :after [:skein/spools-delegation]
               :call 'reviewers/install!
               :required? true})
;; Chime is a vocabulary-agnostic notification engine: it installs bare here,
;; attention.clj registers this repo's attention rules (HITL checkpoints, agent
;; failures, gate errors, kanban lifecycle, parked runs), and each developer
;; binds how they are notified in gitignored init.local.clj (loaded after this
;; file on startup and reload). Unbound chime records loud notifier-missing
;; failures.
(runtime/use! runtime :skein/spools-chime
              {:ns 'skein.spools.chime
               :spools ['skein.spools/chime]
               :call 'skein.spools.chime/install!
               :required? true})
(runtime/use! runtime :attention
              {:file "attention.clj"
               :spools ['skein.macros/macros 'ct.spools/agent-run]
               :after [:skein/spools-chime :skein/spools-shuttle :macros/patterns]
               :call 'attention/install!
               :required? true})
;; kanban is an external git-distributed spool (codethread/kanban.spool). The
;; board loads independently; the repo-specific tracker binding below joins it
;; to devflow after both spools are active.
(runtime/use! runtime :skein/spools-kanban
              {:ns 'ct.spools.kanban
               :spools ['codethread/kanban]
               :call 'ct.spools.kanban/install!
               :required? true})
(runtime/use! runtime :kanban/tracker
              {:file "kanban_tracker.clj"
               :spools ['codethread/kanban 'codethread/devflow]
               :after [:skein/spools-kanban :skein/spools-devflow]
               :call 'kanban-tracker/install!
               :required? true})
;; Cron is a generic weaver timer engine; install! only creates its scheduled
;; executor. nvd_scan.clj requires it (for the job's seed/jitter fns), so it
;; must be synced before that module loads.
(runtime/use! runtime :skein/spools-cron
              {:ns 'skein.spools.cron
               :spools ['skein.spools/cron]
               :call 'skein.spools.cron/install!
               :required? true})
(runtime/use! runtime :config
              {:file "config.clj"
               :spools ['skein.spools/workflow 'ct.spools/agent-run
                        'codethread/devflow 'skein.macros/macros]
               :after [:skein/spools-workflow :skein/spools-devflow
                       :skein/spools-shuttle :macros/patterns]
               :call 'config/install!
               :required? true})
;; Analytics is a read-only rollup surface over agent-run usage stamps; it
;; only needs the defop macro (macros spool) and the shuttle vocabulary the
;; runs were stamped with.
(runtime/use! runtime :analytics
              {:file "analytics.clj"
               :spools ['skein.macros/macros]
               :after [:skein/spools-shuttle :macros/patterns]
               :call 'analytics/install!
               :required? true})
;; workflows.clj reuses config.clj's public CLI-tail helpers, so it loads after
;; the :config module.
(runtime/use! runtime :workflows
              {:file "workflows.clj"
               :spools ['skein.spools/workflow 'ct.spools/delegation]
               :after [:skein/spools-workflow :skein/spools-delegation :config]
               :call 'workflows/install!
               :required? true})
;; The NVD scan job is its own module (not part of config.clj) so config_test's
;; direct config.clj load never registers the job or seeds against real gh.
(runtime/use! runtime :nvd-scan
              {:file "nvd_scan.clj"
               :spools ['skein.spools/cron]
               :after [:skein/spools-cron :skein/spools-kanban]
               :call 'nvd-scan/install!
               :required? true})
;; The subagent gate executor installs last: its install! runs an initial gate
;; scan, so every harness alias harnesses.clj registers (e.g. sol-low) must
;; already exist or a durable ready gate would be stamped gate/error on every
;; cold start.
(runtime/use! runtime :skein/spools-treadle
              {:ns 'ct.spools.executors.subagent
               :spools ['ct.spools/agent-run]
               :after [:skein/spools-shuttle :skein/spools-workflow :harnesses :config :workflows]
               :call 'ct.spools.executors.subagent/install!
               :required? true})
