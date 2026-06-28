(ns skein.batch.alpha
  (:require [skein.client :as client]
            [skein.repl :as repl]
            [skein.weaver.api :as api]
            [skein.weaver.runtime :as runtime]))

(defn apply!
  "Apply one transactional batch graph mutation payload through the selected weaver."
  [payload]
  (if-let [rt @runtime/current-runtime]
    (api/apply-batch rt payload)
    (client/call-world (repl/connected-config-dir) {} :apply-batch payload)))
