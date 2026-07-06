(ns skein.core.query-compile-test
  "Tests for structural query compilation invariants."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.core.query :as query]))

(defn compiled [expr]
  (query/compile-query expr {}))

(defn attr-exists-sql [predicate]
  (str "EXISTS (SELECT 1 FROM attributes AS a"
       " WHERE a.strand_id = t.id"
       " AND a.archived = 0"
       " AND a.key = ?"
       " AND " predicate
       ")"))

(defn attr-semi-join-sql [predicate]
  (str "t.id IN (SELECT a.strand_id FROM attributes AS a"
       " WHERE a.archived = 0"
       " AND a.key = ?"
       " AND " predicate
       ")"))

(deftest attr-keys-compile-to-row-backed-exists-predicates
  (testing "single predicate forms bind value path and top-level key"
    (doseq [[expr sql params]
            [[[:= [:attr :owner] "agent"] (attr-semi-join-sql "json_extract(a.value, ?) = ?") ["owner" "$" "agent"]]
             [[:!= [:attr :owner] "agent"] (attr-exists-sql "json_extract(a.value, ?) <> ?") ["owner" "$" "agent"]]
             [[:< [:attr :rank] 3] (attr-semi-join-sql "json_extract(a.value, ?) < ?") ["rank" "$" 3]]
             [[:<= [:attr :rank] 3] (attr-semi-join-sql "json_extract(a.value, ?) <= ?") ["rank" "$" 3]]
             [[:> [:attr :rank] 3] (attr-exists-sql "json_extract(a.value, ?) > ?") ["rank" "$" 3]]
             [[:>= [:attr :rank] 3] (attr-exists-sql "json_extract(a.value, ?) >= ?") ["rank" "$" 3]]
             [[:in [:attr :owner] ["agent" "human"]] (attr-semi-join-sql "json_extract(a.value, ?) IN (?, ?)") ["owner" "$" "agent" "human"]]
             [[:exists [:attr :owner]] (attr-exists-sql "json_extract(a.value, ?) IS NOT NULL") ["owner" "$"]]
             [[:missing [:attr :owner]] (str "NOT " (attr-exists-sql "json_extract(a.value, ?) IS NOT NULL")) ["owner" "$"]]]]
      (is (= {:sql sql :params params} (compiled expr)) (pr-str expr))))
  (testing "nested attributes use the remaining JSON path inside the stored value"
    (is (= {:sql (attr-semi-join-sql "json_extract(a.value, ?) = ?")
            :params ["owner" "$.\"name\"" "agent"]}
           (compiled [:= [:attr :owner :name] "agent"]))))
  (testing "logical composition preserves row-backed self-join predicates"
    (is (= {:sql (str "(" (attr-semi-join-sql "json_extract(a.value, ?) = ?")
                      " AND (NOT "
                      (attr-exists-sql "json_extract(a.value, ?) IS NOT NULL")
                      "))")
            :params ["owner" "$" "agent" "blocked" "$"]}
           (compiled [:and [:= [:attr :owner] "agent"] [:not [:exists [:attr :blocked]]]])))))

(deftest attr-keys-are-never-spliced-into-sql
  (let [{:keys [sql params]} (compiled [:= [:attr "owner\") OR 1 = 1 --"] "agent"])]
    (is (not (re-find #"owner|1 = 1" sql)))
    (is (= ["owner\") OR 1 = 1 --" "$" "agent"] params))))
