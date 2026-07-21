(ns skein.core.query-compile-test
  "Tests for structural query compilation invariants."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.core.db :as db]
            [skein.core.db-test :as db-test]
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

(deftest attr-keys-compile-to-row-backed-exists-predicates
  (testing "single predicate forms bind value path and top-level key"
    (doseq [[expr sql params]
            [[[:= [:attr :owner] "agent"] (attr-exists-sql "json_extract(a.value, ?) = ?") ["owner" "$" "agent"]]
             [[:!= [:attr :owner] "agent"] (attr-exists-sql "json_extract(a.value, ?) <> ?") ["owner" "$" "agent"]]
             [[:< [:attr :rank] 3] (attr-exists-sql "json_extract(a.value, ?) < ?") ["rank" "$" 3]]
             [[:<= [:attr :rank] 3] (attr-exists-sql "json_extract(a.value, ?) <= ?") ["rank" "$" 3]]
             [[:> [:attr :rank] 3] (attr-exists-sql "json_extract(a.value, ?) > ?") ["rank" "$" 3]]
             [[:>= [:attr :rank] 3] (attr-exists-sql "json_extract(a.value, ?) >= ?") ["rank" "$" 3]]
             [[:in [:attr :owner] ["agent" "human"]] (attr-exists-sql "json_extract(a.value, ?) IN (?, ?)") ["owner" "$" "agent" "human"]]
             [[:exists [:attr :owner]] (attr-exists-sql "json_extract(a.value, ?) IS NOT NULL") ["owner" "$"]]
             [[:missing [:attr :owner]] (str "NOT " (attr-exists-sql "json_extract(a.value, ?) IS NOT NULL")) ["owner" "$"]]]]
      (is (= {:sql sql :params params} (compiled expr)) (pr-str expr))))
  (testing "nested attributes use the remaining JSON path inside the stored value"
    (is (= {:sql (attr-exists-sql "json_extract(a.value, ?) = ?")
            :params ["owner" "$.\"name\"" "agent"]}
           (compiled [:= [:attr :owner :name] "agent"]))))
  (testing "logical composition preserves row-backed self-join predicates"
    (is (= {:sql (str "(" (attr-exists-sql "json_extract(a.value, ?) = ?")
                      " AND (NOT "
                      (attr-exists-sql "json_extract(a.value, ?) IS NOT NULL")
                      "))")
            :params ["owner" "$" "agent" "blocked" "$"]}
           (compiled [:and [:= [:attr :owner] "agent"] [:not [:exists [:attr :blocked]]]])))))

(deftest negated-attr-comparisons-compile-through-present-rows
  (testing "negated attr predicates keep missing and archived keys out of the result"
    (doseq [[expr sql params]
            [[[:not [:= [:attr :owner] "agent"]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) = ?)")
              ["owner" "$" "agent"]]
             [[:not [:< [:attr :rank] 3]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) < ?)")
              ["rank" "$" 3]]
             [[:not [:!= [:attr :owner] "agent"]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) <> ?)")
              ["owner" "$" "agent"]]
             [[:not [:> [:attr :rank] 3]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) > ?)")
              ["rank" "$" 3]]
             [[:not [:>= [:attr :rank] 3]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) >= ?)")
              ["rank" "$" 3]]
             [[:not [:in [:attr :owner] ["agent" "human"]]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) IN (?, ?))")
              ["owner" "$" "agent" "human"]]]]
      (is (= {:sql sql :params params} (compiled expr)) (pr-str expr)))))

(defn- query-titles [ds expr]
  (set (map :title (db/query-strands ds expr))))

(deftest composed-negation-preserves-attr-null-semantics
  (db-test/with-db
    (fn [ds]
      (let [a-matching-b-absent
            (:id (db/add-strand! ds {:title "a matching, b absent"
                                     :attributes {:a 1}}))
            a-nonmatching-b-absent
            (:id (db/add-strand! ds {:title "a nonmatching, b absent"
                                     :attributes {:a 0}}))
            both-matching
            (:id (db/add-strand! ds {:title "both matching"
                                     :attributes {:a 1 :b 2}}))
            both-nonmatching
            (:id (db/add-strand! ds {:title "both nonmatching"
                                     :attributes {:a 0 :b 3}}))
            b-present-nonmatching
            (:id (db/add-strand! ds {:title "b present nonmatching"
                                     :attributes {:a 1 :b 3}}))
            b-archived
            (:id (db/add-strand! ds {:title "b archived"
                                     :attributes {:a 1 :b 3}}))
            ids [a-matching-b-absent
                 a-nonmatching-b-absent
                 both-matching
                 both-nonmatching
                 b-present-nonmatching
                 b-archived]
            attr-and [:and [:= [:attr :a] 1] [:= [:attr :b] 2]]
            attr-or [:or [:= [:attr :a] 1] [:= [:attr :b] 2]]]
        (db/archive-attributes! ds b-archived [:b])

        (testing ":not over :and excludes missing and archived-only keys"
          (is (= #{"a nonmatching, b absent"
                   "both nonmatching"
                   "b present nonmatching"}
                 (query-titles ds [:and [:in :id ids] [:not attr-and]]))))

        (testing ":not over :or requires every negated attribute leaf to be present"
          (is (= #{"both nonmatching"}
                 (query-titles ds [:and [:in :id ids] [:not attr-or]]))))

        (testing "nested :not cancels compositionally"
          (is (= #{"both matching"}
                 (query-titles ds [:and [:in :id ids] [:not [:not attr-and]]]))))))))

(deftest negated-attr-predicates-require-present-hot-keys
  (db-test/with-db
    (fn [ds]
      (let [eq-nonmatching (:id (db/add-strand! ds {:title "eq nonmatching"
                                                    :attributes {:eq-owner "human"}}))
            eq-matching (:id (db/add-strand! ds {:title "eq matching"
                                                 :attributes {:eq-owner "agent"}}))
            eq-absent (:id (db/add-strand! ds {:title "eq absent"}))
            eq-archived (:id (db/add-strand! ds {:title "eq archived"
                                                 :attributes {:eq-owner "human"}}))
            lt-nonmatching (:id (db/add-strand! ds {:title "lt nonmatching"
                                                    :attributes {:lt-rank 5}}))
            lt-matching (:id (db/add-strand! ds {:title "lt matching"
                                                 :attributes {:lt-rank 2}}))
            lt-absent (:id (db/add-strand! ds {:title "lt absent"}))
            lt-archived (:id (db/add-strand! ds {:title "lt archived"
                                                 :attributes {:lt-rank 5}}))
            in-nonmatching (:id (db/add-strand! ds {:title "in nonmatching"
                                                    :attributes {:in-owner "human"}}))
            in-matching (:id (db/add-strand! ds {:title "in matching"
                                                 :attributes {:in-owner "agent"}}))
            in-absent (:id (db/add-strand! ds {:title "in absent"}))
            in-archived (:id (db/add-strand! ds {:title "in archived"
                                                 :attributes {:in-owner "human"}}))
            neq-nonmatching (:id (db/add-strand! ds {:title "neq nonmatching"
                                                     :attributes {:neq-owner "agent"}}))
            neq-matching (:id (db/add-strand! ds {:title "neq matching"
                                                  :attributes {:neq-owner "human"}}))
            neq-absent (:id (db/add-strand! ds {:title "neq absent"}))
            neq-archived (:id (db/add-strand! ds {:title "neq archived"
                                                  :attributes {:neq-owner "agent"}}))
            gt-nonmatching (:id (db/add-strand! ds {:title "gt nonmatching"
                                                    :attributes {:gt-rank 2}}))
            gt-matching (:id (db/add-strand! ds {:title "gt matching"
                                                 :attributes {:gt-rank 5}}))
            gt-absent (:id (db/add-strand! ds {:title "gt absent"}))
            gt-archived (:id (db/add-strand! ds {:title "gt archived"
                                                 :attributes {:gt-rank 2}}))
            gte-nonmatching (:id (db/add-strand! ds {:title "gte nonmatching"
                                                     :attributes {:gte-rank 2}}))
            gte-matching (:id (db/add-strand! ds {:title "gte matching"
                                                  :attributes {:gte-rank 3}}))
            gte-absent (:id (db/add-strand! ds {:title "gte absent"}))
            gte-archived (:id (db/add-strand! ds {:title "gte archived"
                                                  :attributes {:gte-rank 2}}))]
        (db/archive-attributes! ds eq-archived [:eq-owner])
        (db/archive-attributes! ds lt-archived [:lt-rank])
        (db/archive-attributes! ds in-archived [:in-owner])
        (db/archive-attributes! ds neq-archived [:neq-owner])
        (db/archive-attributes! ds gt-archived [:gt-rank])
        (db/archive-attributes! ds gte-archived [:gte-rank])

        (testing ":not over :="
          (is (= #{"eq nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [eq-nonmatching eq-matching eq-absent eq-archived]]
                                   [:not [:= [:attr :eq-owner] "agent"]]]))))

        (testing ":not over :<"
          (is (= #{"lt nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [lt-nonmatching lt-matching lt-absent lt-archived]]
                                   [:not [:< [:attr :lt-rank] 3]]]))))

        (testing ":not over :in"
          (is (= #{"in nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [in-nonmatching in-matching in-absent in-archived]]
                                   [:not [:in [:attr :in-owner] ["agent"]]]]))))

        (testing ":not over :!="
          (is (= #{"neq nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [neq-nonmatching neq-matching neq-absent neq-archived]]
                                   [:not [:!= [:attr :neq-owner] "agent"]]]))))

        (testing ":not over :>"
          (is (= #{"gt nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [gt-nonmatching gt-matching gt-absent gt-archived]]
                                   [:not [:> [:attr :gt-rank] 3]]]))))

        (testing ":not over :>="
          (is (= #{"gte nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [gte-nonmatching gte-matching gte-absent gte-archived]]
                                   [:not [:>= [:attr :gte-rank] 3]]]))))))))

(deftest attr-keys-are-never-spliced-into-sql
  (let [{:keys [sql params]} (compiled [:= [:attr "owner\") OR 1 = 1 --"] "agent"])]
    (is (not (re-find #"owner|1 = 1" sql)))
    (is (= ["owner\") OR 1 = 1 --" "$" "agent"] params))))
