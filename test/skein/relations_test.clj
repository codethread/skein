(ns skein.relations-test
  "Tests for the skein.api.relations.alpha edge-relation catalog."
  (:require [clojure.test :refer [deftest is]]
            [skein.api.relations.alpha :as relations]))

(deftest catalog-shape-and-lookups
  (is (= #{"related-to" "duplicates" "references" "implements" "verifies" "tracks" "caused-by"}
         (set (map :relation (relations/annotation-relations)))))
  (is (= #{"depends-on" "parent-of" "supersedes" "serves"}
         (set (map :relation (relations/operational-relations)))))
  (is (= "replacement --supersedes--> replaced"
         (:direction (relations/relation "supersedes"))))
  (is (every? :declared-acyclic? (relations/operational-relations)))
  (is (every? (comp false? :declared-acyclic?) (relations/annotation-relations)))
  (is (every? #(and (:direction %) (seq (:help %))) relations/catalog))
  (is (nil? (relations/relation "agent.notes/v1"))))
