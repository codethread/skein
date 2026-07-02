(require '[skein.runtime.alpha :as runtime-alpha])

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
(runtime-alpha/use! :config
  {:file "config.clj"
   :after [:skein/spools-ephemeral :skein/spools-workflow :skein/spools-devflow]
   :call 'config/install!})
