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

(deftest install-patterns-unknown-ns-fails-loudly
  (testing "installing a namespace with no remembered patterns throws rather than silently installing nothing (TEN-003)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no remembered patterns"
                          (patterns/install-patterns! 'skein.macros.patterns-test.unknown))))
  (testing "a namespace forgotten down to nothing (all patterns removed from source) also throws"
    (let [ns-key 'skein.macros.patterns-test.emptied]
      (patterns/remember-pattern! ns-key {:name 'gone :fn 'ns/gone :input-spec ::g :doc "G"})
      (patterns/forget-patterns! ns-key)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no remembered patterns"
                            (patterns/install-patterns! ns-key))))))

(deftest forget-patterns-unknown-ns-is-a-no-op
  (testing "forget-patterns! tolerates an unknown namespace (first-load calls it before anything is remembered)"
    (is (nil? (patterns/forget-patterns! 'skein.macros.patterns-test.never-remembered)))))
