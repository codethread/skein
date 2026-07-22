(ns skein.macros.queries-test
  "Tests for defquery's module-contribution declaration shape."
  (:require [clojure.test :refer [deftest is]]
            [skein.macros.queries :as queries]))

(deftest defquery-expands-to-a-real-var-and-collector-entry
  (is (:macro (meta #'queries/defquery)))
  (let [form (macroexpand-1 '(skein.macros.queries/defquery sample-query "Sample."
                               {:usage "strand list --query sample"} [:= :state "active"]))]
    (is (= 'do (first form)))
    (is (some #(and (seq? %) (= 'skein.core.weaver.module-refresh/collect-entry! (first %))) form))))

(deftest defquery-rejects-malformed-authoring-forms
  (is (thrown? clojure.lang.Compiler$CompilerException
               (macroexpand-1 '(skein.macros.queries/defquery "bad" "doc" {:usage "u"} []))))
  (is (thrown? clojure.lang.Compiler$CompilerException
               (macroexpand-1 '(skein.macros.queries/defquery missing-usage "doc" {} [])))))
