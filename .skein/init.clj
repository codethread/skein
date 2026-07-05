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
(runtime-alpha/use! runtime :config
  {:file "config.clj"
   :after [:skein/spools-ephemeral :skein/spools-workflow :skein/spools-devflow
           :skein/spools-shuttle :skein/spools-agents :skein/spools-chime]
   :call 'config/install!})
;; Treadle installs last: its install! runs an initial gate scan, so every
;; harness alias config.clj registers (e.g. pi-main) must already exist or a
;; durable ready gate would be stamped treadle/error on every cold start.
(runtime-alpha/use! runtime :skein/spools-treadle
  {:ns 'skein.spools.treadle
   :spools ['skein.spools/shuttle]
   :after [:skein/spools-shuttle :skein/spools-workflow :config]
   :call 'skein.spools.treadle/install!
   :required? true})
