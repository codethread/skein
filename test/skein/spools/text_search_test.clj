(ns skein.spools.text-search-test
  "Tests for the UNSAFE substring-search spool: title and attribute-value hits,
  archived-row visibility, literal metacharacter matching, and the loud
  blank-pattern and overflow failures."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.weaver.alpha :as weaver]
            [skein.repl :as repl]
            [skein.spools.test-support :refer [with-runtime]]
            [skein.spools.text-search :as text-search]))

(deftest search-hits-titles-and-attribute-values
  (with-runtime
    (fn [rt _]
      (let [design (repl/strand! "Design the payments flow" {"topic" "billing"})]
        (repl/strand! "Unrelated work" {"topic" "shipping"})
        (testing "a title substring hit carries no attribute key"
          (let [rows (text-search/search rt {:text "payments"})]
            (is (= [{:id (:id design) :key nil}]
                   (mapv #(select-keys % [:id :key]) rows)))))
        (testing "an attribute-value hit carries the matching key"
          (let [rows (text-search/search rt {:text "billing"})]
            (is (= 1 (count rows)))
            (is (= (:id design) (:id (first rows))))
            (is (= "topic" (:key (first rows))))
            (is (str/includes? (:snippet (first rows)) "billing"))))))))

(deftest key-scope-searches-only-that-attribute-and-skips-titles
  (with-runtime
    (fn [rt _]
      (let [a (repl/strand! "shared token here" {"owner" "shared token"})]
        (repl/strand! "other" {"note" "shared token"})
        (testing "--key restricts to one attribute key and drops the title branch"
          (let [rows (text-search/search rt {:text "shared token" :key "owner"})]
            (is (= [{:id (:id a) :key "owner"}]
                   (mapv #(select-keys % [:id :key]) rows)))))))))

(deftest archived-rows-are-invisible-by-default-and-visible-with-archived
  (with-runtime
    (fn [rt _]
      (let [strand (repl/strand! "Old session" {"transcript" "secretword"})]
        (weaver/archive! rt (:id strand) ["transcript"])
        (testing "an archived attribute value is invisible to the query language and to a default search"
          (is (empty? (text-search/search rt {:text "secretword"}))))
        (testing "--archived opts the cold row back in"
          (let [rows (text-search/search rt {:text "secretword" :archived? true})]
            (is (= [{:id (:id strand) :key "transcript"}]
                   (mapv #(select-keys % [:id :key]) rows)))))))))

(deftest patterns-match-literally
  (with-runtime
    (fn [rt _]
      (let [pct (repl/strand! "Rollout 50% done")]
        (repl/strand! "Rollout 50X done")
        (testing "a LIKE metacharacter in the pattern matches literally, not as a wildcard"
          (is (= [(:id pct)]
                 (mapv :id (text-search/search rt {:text "50%"})))))))))

(deftest blank-pattern-fails-loudly
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                            (text-search/search rt {:text "   "})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                            (text-search/search rt {:text nil}))))))

(deftest malformed-options-fail-loudly-before-changing-search-mode
  (with-runtime
    (fn [rt _]
      (testing ":archived? must be a boolean, not a truthy string"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                              (text-search/search rt {:text "needle" :archived? "false"}))))
      (testing ":key must be a non-blank string"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                              (text-search/search rt {:text "needle" :key ""})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                              (text-search/search rt {:text "needle" :key :owner})))))))

(deftest overflow-fails-loudly-and-caps-rather-than-truncates
  (with-runtime
    (fn [rt _]
      (dotimes [n 3]
        (repl/strand! (str "widget number " n)))
      (testing "matches within the limit return"
        (is (= 3 (count (text-search/search rt {:text "widget" :limit 3})))))
      (testing "more matches than the limit fail loudly naming --limit"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"more than the --limit"
                              (text-search/search rt {:text "widget" :limit 2}))))
      (testing "a non-positive limit fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                              (text-search/search rt {:text "widget" :limit 0})))))))

(deftest op-handler-threads-args-and-coerces-the-archived-flag
  (with-runtime
    (fn [rt _]
      (let [strand (repl/strand! "Session log" {"transcript" "coldvalue"})]
        (weaver/archive! rt (:id strand) ["transcript"])
        (testing "absent --archived reads as false"
          (is (empty? (text-search/search-op {:op/runtime rt :op/args {:text "coldvalue"}}))))
        (testing "present --archived reads as true"
          (is (= [(:id strand)]
                 (mapv :id (text-search/search-op {:op/runtime rt :op/args {:text "coldvalue" :archived true}})))))))))
