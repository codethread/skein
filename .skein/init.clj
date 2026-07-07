(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))

(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :skein/spools-batteries
                    {:ns 'skein.spools.batteries
                     :call 'skein.spools.batteries/activate!})
(runtime-alpha/use! runtime :skein/spools-ephemeral
                    {:ns 'skein.spools.ephemeral
                     :call 'skein.spools.ephemeral/install!})
(runtime-alpha/use! runtime :skein/spools-workflow
                    {:ns 'skein.spools.workflow
                     :call 'skein.spools.workflow/install!})
(runtime-alpha/use! runtime :skein/spools-roster
                    {:ns 'skein.spools.roster
                     :call 'skein.spools.roster/install!})
;; loom is a read-only work-graph projection library (registers no ops);
;; config.clj's current-dags/branches/flow-status ops are thin wrappers over it.
(runtime-alpha/use! runtime :skein/spools-loom
                    {:ns 'skein.spools.loom
                     :call 'skein.spools.loom/install!})
;; brief is the classpath-shipped brief/guide primitive: it installs the
;; fetch-only `brief` op and owns the clause-block/guide registries. config.clj
;; registers this repo's worker-contract clause block and renders
;; pipeline-task-prompt through it, so brief must sync before config loads.
(runtime-alpha/use! runtime :skein/spools-brief
                    {:ns 'skein.spools.brief
                     :call 'skein.spools.brief/install!})
;; UNSAFE spool: text-search reaches past the blessed api.* contract into
;; skein.core.db to LIKE-search titles and attribute values, including archived
;; rows the query language cannot see. It is a maintained, in-the-open example
;; of rule-breaking (see spools/text-search.md), not a blessed path — activated
;; here so the reference stays exercised.
(runtime-alpha/use! runtime :skein/spools-text-search
                    {:ns 'skein.spools.text-search
                     :call 'skein.spools.text-search/install!})
;; devflow is an external git-distributed spool: activation is gated on the
;; approved codethread/devflow coordinate (spools.edn pin or a developer's
;; spools.local.edn checkout), never on an incidental classpath copy.
(runtime-alpha/use! runtime :skein/spools-devflow
                    {:ns 'skein.spools.devflow
                     :spools ['codethread/devflow]
                     :call 'skein.spools.devflow/install!
                     :required? true})
(runtime-alpha/use! runtime :macros/patterns
                    {:ns 'skein.macros.patterns
                     :spools ['skein.macros/macros]
                     :required? true})
(runtime-alpha/use! runtime :macros/demo
                    {:ns 'skein.macros.demo
                     :spools ['skein.macros/macros]
                     :after [:macros/patterns]
                     :call 'skein.macros.demo/install!
                     :required? true})
(runtime-alpha/use! runtime :skein/spools-shuttle
                    {:ns 'skein.spools.shuttle
                     :spools ['skein.spools/shuttle]
                     :call 'skein.spools.shuttle/install!
                     :required? true})
(runtime-alpha/use! runtime :skein/spools-agents
                    {:ns 'skein.spools.agents
                     :spools ['skein.spools/agents]
                     :after [:skein/spools-shuttle]
                     :call 'skein.spools.agents/install!
                     :required? true})
;; The declarative reviewer roster lives in its own file so the "who reviews
;; a change here" policy stays a small git-reviewable data document. Roster
;; harness aliases resolve at review time, not registration time, so loading
;; before config.clj registers the aliases is safe.
(runtime-alpha/use! runtime :reviewers
                    {:file "reviewers.clj"
                     :after [:skein/spools-agents]
                     :call 'reviewers/install!
                     :required? true})
;; Chime is a vocabulary-agnostic notification engine: it installs bare here,
;; config.clj registers this repo's attention rules (HITL checkpoints, agent
;; failures, treadle errors), and each developer binds how they are notified
;; in gitignored init.local.clj (loaded after this file on startup and
;; reload). Unbound chime records loud notifier-missing failures.
(runtime-alpha/use! runtime :skein/spools-chime
                    {:ns 'skein.spools.chime
                     :spools ['skein.spools/chime]
                     :call 'skein.spools.chime/install!
                     :required? true})
(runtime-alpha/use! runtime :skein/spools-kanban
                    {:ns 'skein.spools.kanban
                     :spools ['skein.spools/kanban]
                     :call 'skein.spools.kanban/install!
                     :required? true})
;; Cron is a generic weaver timer engine; install! only creates its scheduled
;; executor. config.clj requires it (for the nvd-scan job's seed/jitter fns),
;; so it must be synced before config loads.
(runtime-alpha/use! runtime :skein/spools-cron
                    {:ns 'skein.spools.cron
                     :spools ['skein.spools/cron]
                     :call 'skein.spools.cron/install!
                     :required? true})
(runtime-alpha/use! runtime :config
                    {:file "config.clj"
                     :after [:skein/spools-ephemeral :skein/spools-workflow :skein/spools-devflow
                             :skein/spools-loom :skein/spools-brief :skein/spools-shuttle :skein/spools-agents
                             :skein/spools-chime :skein/spools-cron]
                     :call 'config/install!})
;; Register the scheduled NVD deep-scan cron job here rather than in
;; config/install! so config_test (which loads config.clj and calls install!
;; directly) never triggers the job's startup gh seed against the real repo.
;; The job's run!/seed fns live in config.clj beside the other repo policy;
;; re-run on reload it re-seeds and re-registers idempotently.
((requiring-resolve 'config/register-nvd-scan-job!))
;; Treadle installs last: its install! runs an initial gate scan, so every
;; harness alias config.clj registers (e.g. pi-main) must already exist or a
;; durable ready gate would be stamped treadle/error on every cold start.
(runtime-alpha/use! runtime :skein/spools-treadle
                    {:ns 'skein.spools.treadle
                     :spools ['skein.spools/shuttle]
                     :after [:skein/spools-shuttle :skein/spools-workflow :config]
                     :call 'skein.spools.treadle/install!
                     :required? true})
