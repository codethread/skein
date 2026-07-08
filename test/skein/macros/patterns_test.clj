(ns skein.macros.patterns-test
  "Tests for skein.macros.patterns reload correctness via forget-patterns!.

  The defpattern macro's remember/install flow is exercised end-to-end through
  the workspace's macros demo (config_test loads it against a live runtime); this
  namespace pins the reload-correctness contract the other macro namespaces share:
  a forget-then-reload registers exactly the current source, never a stale entry."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.macros.patterns :as patterns]))

(defn- remembered
  "Return the name->entry map remembered under `ns-key` in the private registry."
  [ns-key]
  (get @@#'patterns/pattern-registry ns-key))

(deftest forget-patterns-drops-stale-entries-across-reload
  (testing "forget-patterns! clears the namespace so a reload remembers only current source (TEN-003)"
    (let [ns-key 'skein.macros.patterns-test.reload]
      ;; first load: source defined A and B
      (patterns/remember-pattern! ns-key {:name 'stale-a :fn 'ns/stale-a :input-spec ::a :doc "A"})
      (patterns/remember-pattern! ns-key {:name 'stale-b :fn 'ns/stale-b :input-spec ::b :doc "B"})
      ;; reload where B was deleted from source: forget, then re-remember only A
      (patterns/forget-patterns! ns-key)
      (patterns/remember-pattern! ns-key {:name 'stale-a :fn 'ns/stale-a :input-spec ::a :doc "A"})
      (is (= ['stale-a] (keys (remembered ns-key)))
          "only the surviving pattern remains remembered; the deleted one is gone"))))
