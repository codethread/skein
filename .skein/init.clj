(require '[skein.libs.alpha :as libs])

(libs/sync!)
(libs/use! :skein/libs-ephemeral
  {:ns 'skein.libs.ephemeral
   :call 'skein.libs.ephemeral/install!})
(libs/use! :config
  {:file "config.clj"
   :after [:skein/libs-ephemeral]
   :call 'config/install!})
