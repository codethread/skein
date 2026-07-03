(ns skein.test-runner
  "Explicit test entrypoint: requires every project test namespace and runs
  them via clojure.test, exiting non-zero on any failure or error."
  (:require [clojure.test :as test]
            [skein.alpha-test]
            [skein.core.client-test]
            [skein.config-test]
            [skein.weaver-test]
            [skein.core.db-test]
            [skein.spools-test]
            [skein.spools.bobbin-test]
            [skein.spools.carder-test]
            [skein.spools.devflow-test]
            [skein.spools.selvage-test]
            [skein.spools.workflow-test]
            [skein.guild-test]
            [skein.peers-test]
            [skein.plugin-test]
            [skein.relations-test]
            [skein.repl-test]
            [skein.runtime-deps-test]
            [skein.shuttle-test]
            [skein.treadle-test]
            [skein.chime-test]))

(defn -main [& _]
  ;; skein.shuttle-test must precede the other spool suites: its sync! test
  ;; add-libs a repo-local root, and tools.deps add-libs state is JVM-global —
  ;; later suites register throwaway roots that would poison a later sync.
  (let [{:keys [fail error]} (test/run-tests 'skein.alpha-test 'skein.core.client-test 'skein.config-test 'skein.weaver-test 'skein.core.db-test 'skein.shuttle-test 'skein.treadle-test 'skein.chime-test 'skein.spools-test 'skein.spools.bobbin-test 'skein.spools.carder-test 'skein.spools.devflow-test 'skein.spools.selvage-test 'skein.spools.workflow-test 'skein.guild-test 'skein.peers-test 'skein.plugin-test 'skein.relations-test 'skein.repl-test 'skein.runtime-deps-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
