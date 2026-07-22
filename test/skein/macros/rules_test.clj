(ns skein.macros.rules-test
  "Tests for defrule's module-contribution declaration shape."
  (:require [clojure.test :refer [deftest is]]
            [skein.macros.rules :as rules]))

(deftest defrule-expands-to-a-real-handler-and-collector-entry
  (is (:macro (meta #'rules/defrule)))
  (let [form (macroexpand-1 '(skein.macros.rules/defrule sample "Sample." [ctx] nil))]
    (is (= 'do (first form)))
    (is (some #(and (seq? %) (= 'skein.core.weaver.module-refresh/collect-entry! (first %))) form))))

(deftest defrule-rejects-malformed-authoring-forms
  (is (thrown? clojure.lang.Compiler$CompilerException
               (macroexpand-1 '(skein.macros.rules/defrule "bad" "doc" [ctx] nil))))
  (is (thrown? clojure.lang.Compiler$CompilerException
               (macroexpand-1 '(skein.macros.rules/defrule missing-doc nil [ctx] nil)))))
