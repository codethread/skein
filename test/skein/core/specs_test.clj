(ns skein.core.specs-test
  "Tests for shared Skein specs that define boundary data contracts."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.core.specs :as specs]))

(deftest indexed-attr-key-spec-pins-literal-safe-character-class
  (doseq [key ["owner" "devflow/role" "agent.notes/v1" "a_b-c.d/0"]]
    (is (s/valid? ::specs/indexed-attr-key key) key))
  (doseq [key ["" "Owner" ".hidden" "has space" "has\"quote" "has'quote" "has\\slash" "line\nbreak" 42 nil]]
    (is (not (s/valid? ::specs/indexed-attr-key key)) (pr-str key))))

(deftest omitted-attribute-descriptor-discriminates-typed-descriptor
  (testing "the descriptor shape conforms"
    (is (specs/omitted-attribute-descriptor? {:skein/omitted true :bytes 1025})))
  (testing "plain attribute values never conform as descriptors"
    (doseq [value ["large string" 42 true false nil ["x"] {:bytes 1025} {:skein/omitted false :bytes 1025}]]
      (is (not (specs/omitted-attribute-descriptor? value)) (pr-str value)))))
