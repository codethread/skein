(require '[skein.api.runtime.alpha :as runtime-alpha])

(runtime-alpha/sync!)
(runtime-alpha/use! :skein/spools-ephemeral
  {:ns 'skein.spools.ephemeral
   :call 'skein.spools.ephemeral/install!})
(runtime-alpha/use! :skein/spools-workflow
  {:ns 'skein.spools.workflow
   :call 'skein.spools.workflow/install!})
(runtime-alpha/use! :skein/spools-devflow
  {:ns 'skein.spools.devflow
   :call 'skein.spools.devflow/install!})
(runtime-alpha/use! :macros/patterns
  {:ns 'skein.macros.patterns
   :spools ['skein.macros/macros]
   :required? true})
(runtime-alpha/use! :macros/demo
  {:ns 'skein.macros.demo
   :spools ['skein.macros/macros]
   :after [:macros/patterns]
   :call 'skein.macros.demo/install!
   :required? true})
(runtime-alpha/use! :skein/spools-shuttle
  {:ns 'skein.spools.shuttle
   :spools ['skein.spools/shuttle]
   :call 'skein.spools.shuttle/install!
   :required? true})
;; Chime is a vocabulary-agnostic notification engine: it installs bare here,
;; config.clj registers this repo's attention rules (HITL checkpoints, agent
;; failures, treadle errors), and each developer binds how they are notified
;; in gitignored init.local.clj (loaded after this file on startup and
;; reload). Unbound chime records loud notifier-missing failures.
(runtime-alpha/use! :skein/spools-chime
  {:ns 'skein.spools.chime
   :spools ['skein.spools/chime]
   :call 'skein.spools.chime/install!
   :required? true})
(runtime-alpha/use! :config
  {:file "config.clj"
   :after [:skein/spools-ephemeral :skein/spools-workflow :skein/spools-devflow
           :skein/spools-shuttle :skein/spools-chime]
   :call 'config/install!})
;; Treadle installs last: its install! runs an initial gate scan, so every
;; harness alias config.clj registers (e.g. pi-main) must already exist or a
;; durable ready gate would be stamped treadle/error on every cold start.
(runtime-alpha/use! :skein/spools-treadle
  {:ns 'skein.spools.treadle
   :spools ['skein.spools/shuttle]
   :after [:skein/spools-shuttle :skein/spools-workflow :config]
   :call 'skein.spools.treadle/install!
   :required? true})
