(ns skein.core.specs-test
  "Tests for shared Skein specs that define boundary data contracts."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.core.specs :as specs]))

(deftest attribute-storage-migration-result-spec-pins-cutover-shape
  (is (s/valid? ::specs/attribute-storage-migration-result
                {:status :migrated :strands 2 :attributes 5}))
  (is (s/valid? ::specs/attribute-storage-migration-result
                {:status :already-current :strands 2 :attributes 5}))
  (doseq [result [{:status :done :strands 2 :attributes 5}
                  {:status :migrated :strands -1 :attributes 5}
                  {:status :migrated :strands 2 :attribute-count 5}]]
    (is (not (s/valid? ::specs/attribute-storage-migration-result result)) (pr-str result))))

(deftest omitted-attribute-descriptor-discriminates-typed-descriptor
  (testing "the descriptor shape conforms"
    (is (specs/omitted-attribute-descriptor? {:skein/omitted true :bytes 1025})))
  (testing "plain attribute values never conform as descriptors"
    (doseq [value ["large string" 42 true false nil ["x"] {:bytes 1025} {:skein/omitted false :bytes 1025}]]
      (is (not (specs/omitted-attribute-descriptor? value)) (pr-str value)))))
