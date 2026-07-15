(ns skein.api.return-shape.alpha-test
  "Tests for finite return declarations and captured-value checks."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [skein.api.return-shape.alpha :as return-shape]))

(def scalar-cases
  [[:string "text"]
   [:integer 42]
   [:number 3.5]
   [:boolean false]
   [:null nil]
   [:json {:nested ["value" 1 true nil]}]])

(deftest scalar-and-nullable-shapes
  (doseq [[shape value] scalar-cases]
    (is (= shape (return-shape/validate! shape)))
    (is (= value (return-shape/check! shape value))))
  (doseq [scalar [:string :integer :number :boolean]]
    (is (nil? (return-shape/check! [:nullable scalar] nil))))
  (is (= "present" (return-shape/check! [:nullable :string] "present")))
  (is (thrown? clojure.lang.ExceptionInfo
               (return-shape/check! :integer 1.5)))
  (is (thrown? clojure.lang.ExceptionInfo
               (return-shape/check! :json {:bad-key 1 'symbol-key 2}))))

(deftest map-and-collection-shapes
  (let [shape {:type :map
               :required {:id :string}
               :optional {:score [:nullable :number]}
               :extra {:type :collection :items :boolean}}]
    (is (= {:id "a" :score nil :flags [true false]}
           (return-shape/check! shape {:id "a" :score nil :flags [true false]})))
    (is (= {:id "a"} (return-shape/check! shape {:id "a"})))
    (let [missing (is (thrown? clojure.lang.ExceptionInfo
                               (return-shape/check! shape {})))
          nested (is (thrown? clojure.lang.ExceptionInfo
                              (return-shape/check! shape {:id "a" :flags [true "no"]})))]
      (is (= [:id] (:path (ex-data missing))))
      (is (= :string (:expected (ex-data missing))))
      (is (= [:flags 1] (:path (ex-data nested))))
      (is (= :boolean (:expected (ex-data nested))))
      (is (= "no" (:actual (ex-data nested))))))
  (let [closed {:type :map :optional {:name :string}}
        mismatch (is (thrown? clojure.lang.ExceptionInfo
                              (return-shape/check! closed {:other 1})))]
    (is (= [:other] (:path (ex-data mismatch))))
    (is (= :undeclared-key (:reason (ex-data mismatch)))))
  (is (= '(1 2 3)
         (return-shape/check! {:type :collection :items :integer} '(1 2 3))))
  (is (thrown? clojure.lang.ExceptionInfo
               (return-shape/check! {:type :collection :items :integer} #{1 2}))))

(deftest malformed-declarations-fail-loudly
  (doseq [declaration [:keyword
                       'named-reference
                       string?
                       [:nullable :json]
                       [:nullable :null]
                       [:nullable [:nullable :string]]
                       [:or :string :integer]
                       {:type :map :required {:id :string} :optional {:id :string}}
                       {:type :map :required {"id" :string}}
                       {:type :map :coerce true}
                       {:type :map :default {}}
                       {:type :collection}
                       {:type :collection :items :string :recursive :self}
                       {:stream {:emits :string}}
                       {:subcommands {:run :string}}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (return-shape/validate! declaration))
        (pr-str declaration))))

(deftest routed-declarations-and-json-safe-explanation
  (let [declaration
        {:subcommands
         {"list" {:type :collection :items :string}
          "watch" {:stream {:emits {:type :map :required {:id :integer}}
                            :result [:nullable :boolean]}}}}
        explained (return-shape/explain declaration)]
    (is (= declaration (return-shape/validate! declaration)))
    (is (= {:subcommands
            {"list" {:type "collection" :items "string"}
             "watch" {:stream
                      {:emits {:type "map"
                               :required {"id" "integer"}
                               :optional {}}
                       :result ["nullable" "boolean"]}}}}
           explained))
    (is (string? (json/write-str explained)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"selected concrete return shape"
                          (return-shape/check! declaration {})))))
