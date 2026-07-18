(ns skein.relations-test
  "Tests for the skein.api.relations.alpha edge-relation catalog."
  (:require [clojure.test :refer [deftest is]]
            [skein.api.relations.alpha :as relations]))

(deftest catalog-shape
  (is (= "replacement --supersedes--> replaced"
         (:direction (some #(when (= "supersedes" (:relation %)) %) relations/catalog))))
  (is (every? #(and (:direction %) (seq (:help %))) relations/catalog)))
