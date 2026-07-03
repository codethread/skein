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
(runtime-alpha/use! :skein/spools-shuttle
  {:ns 'skein.spools.shuttle
   :spools ['skein.spools/shuttle]
   :call 'skein.spools.shuttle/install!
   :required? true})
;; Chime installs before config: config.clj binds the cc-notify notifier, and
;; the binding must land after install! so a reload cannot leave rules armed
;; without their notifier.
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
