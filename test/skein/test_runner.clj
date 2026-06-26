(ns skein.test-runner
  (:require [clojure.test :as test]
            [skein.alpha-test]
            [skein.cli-test]
            [skein.client-test]
            [skein.weaver-test]
            [skein.db-test]
            [skein.libs-test]
            [skein.plugin-test]
            [skein.repl-test]
            [skein.runtime-deps-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'skein.alpha-test 'skein.cli-test 'skein.client-test 'skein.weaver-test 'skein.db-test 'skein.libs-test 'skein.plugin-test 'skein.repl-test 'skein.runtime-deps-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
