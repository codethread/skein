(ns skein.core.query-compile-test
  "Tests for structural query compilation invariants."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.core.query :as query]
            [skein.core.specs :as specs]))

(defn compiled [expr]
  (query/compile-query expr {}))

(deftest undeclared-attr-keys-keep-bound-parameter-sql
  (testing "single predicate forms are byte-identical to the historical bound path"
    (doseq [[expr sql params]
            [[[:= [:attr :owner] "agent"] "json_extract(t.attributes, ?) = ?" ["$.\"owner\"" "agent"]]
             [[:!= [:attr :owner] "agent"] "json_extract(t.attributes, ?) <> ?" ["$.\"owner\"" "agent"]]
             [[:< [:attr :rank] 3] "json_extract(t.attributes, ?) < ?" ["$.\"rank\"" 3]]
             [[:<= [:attr :rank] 3] "json_extract(t.attributes, ?) <= ?" ["$.\"rank\"" 3]]
             [[:> [:attr :rank] 3] "json_extract(t.attributes, ?) > ?" ["$.\"rank\"" 3]]
             [[:>= [:attr :rank] 3] "json_extract(t.attributes, ?) >= ?" ["$.\"rank\"" 3]]
             [[:in [:attr :owner] ["agent" "human"]] "json_extract(t.attributes, ?) IN (?, ?)" ["$.\"owner\"" "agent" "human"]]
             [[:exists [:attr :owner]] "json_extract(t.attributes, ?) IS NOT NULL" ["$.\"owner\""]]
             [[:missing [:attr :owner]] "json_extract(t.attributes, ?) IS NULL" ["$.\"owner\""]]]]
      (is (= {:sql sql :params params} (compiled expr)) (pr-str expr))))
  (testing "logical composition preserves bound-path fields"
    (is (= {:sql "(json_extract(t.attributes, ?) = ? AND (NOT json_extract(t.attributes, ?) IS NOT NULL))"
            :params ["$.\"owner\"" "agent" "$.\"blocked\""]}
           (compiled [:and [:= [:attr :owner] "agent"] [:not [:exists [:attr :blocked]]]])))))

(deftest declared-attr-keys-compile-to-literal-json-paths
  (binding [query/*indexed-attr-key?* #{"owner"}]
    (is (= {:sql "json_extract(t.attributes, '$.\"owner\"') = ?"
            :params ["agent"]}
           (compiled [:= [:attr :owner] "agent"])))
    (is (= {:sql "json_extract(t.attributes, ?) = ?"
            :params ["$.\"owner\".\"name\"" "agent"]}
           (compiled [:= [:attr :owner :name] "agent"])))))

(deftest declared-attr-key-literal-emission-revalidates-key
  (binding [query/*indexed-attr-key?* (constantly true)]
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (compiled [:= [:attr "bad key"] "x"])))]
      (is (= {:key "bad key"
              :spec ::specs/indexed-attr-key
              :allowed-pattern specs/indexed-attr-key-pattern-source}
             (ex-data e))))))
