(ns skein.test-runner
  "Explicit test entrypoint: requires every project test namespace and runs
  them via clojure.test, exiting non-zero on any failure or error."
  (:require [clojure.test :as test]
            [skein.alpha-test]
            [skein.client-test]
            [skein.config-test]
            [skein.weaver-test]
            [skein.db-test]
            [skein.spools-test]
            [skein.spools.devflow-test]
            [skein.spools.workflow-test]
            [skein.plugin-test]
            [skein.relations-test]
            [skein.repl-test]
            [skein.runtime-deps-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'skein.alpha-test 'skein.client-test 'skein.config-test 'skein.weaver-test 'skein.db-test 'skein.spools-test 'skein.spools.devflow-test 'skein.spools.workflow-test 'skein.plugin-test 'skein.relations-test 'skein.repl-test 'skein.runtime-deps-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
