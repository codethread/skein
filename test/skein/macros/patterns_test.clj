(ns skein.macros.patterns-test
  "Tests for defpattern and defp contribution declarations."
  (:require [clojure.test :refer [deftest is]]
            [skein.macros.patterns :as patterns]))

(deftest defpattern-and-defp-expand-to-collector-entries
  (is (:macro (meta #'patterns/defpattern)))
  (doseq [macro-sym ['skein.macros.patterns/defpattern 'skein.macros.patterns/defp]]
    (let [form (-> `(~macro-sym sample "Sample." {:input {:title string?}} [ctx] []) macroexpand-1 macroexpand-1)]
      (is (= 'do (first form)))
      (is (some #(and (seq? %) (= 'skein.core.weaver.module-refresh/collect-entry! (first %))) form)))))

(deftest defpattern-rejects-malformed-authoring-forms
  (is (thrown? clojure.lang.Compiler$CompilerException
               (macroexpand-1 '(skein.macros.patterns/defpattern "bad" "doc" {:input {}} [ctx] []))))
  (is (thrown? clojure.lang.Compiler$CompilerException
               (macroexpand-1 '(skein.macros.patterns/defpattern missing-doc nil {:input {}} [ctx] [])))))
