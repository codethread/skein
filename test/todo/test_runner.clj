(ns todo.test-runner
  (:require [clojure.test :as test]
            [todo.cli-test]
            [todo.client-test]
            [todo.daemon-test]
            [todo.db-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'todo.cli-test 'todo.client-test 'todo.daemon-test 'todo.db-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
