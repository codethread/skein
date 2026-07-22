(ns skein.macros.ops-test
  "Tests for defop's module-contribution declaration shape."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.core.weaver.module-graph :as module-graph]
            [skein.macros.ops :as ops]))

(deftest defop-expands-to-a-real-handler-and-collector-entry
  (is (:macro (meta #'ops/defop)))
  (let [form (macroexpand-1 '(skein.macros.ops/defop sample "Sample."
                               {:arg-spec {:op "sample" :doc "Sample."}}
                               [ctx] ctx))]
    (is (= 'do (first form)))
    (is (some #(and (seq? %)
                    (= 'skein.core.weaver.module-refresh/collect-entry! (first %)))
              form))))

(deftest collection-replaces-same-key-and-allows-an-empty-owner
  (let [context {:module/key :test/ops
                 :source/file (.getCanonicalPath (java.io.File. *file*))
                 :source/namespace (ns-name *ns*)}
        result (module-graph/with-contribution-collection
                 context
                 #(do (module-graph/collect-entry! :ops "sample" {:name "sample" :version 1})
                      (module-graph/collect-entry! :ops "sample" {:name "sample" :version 2})))]
    (is (= {:name "sample" :version 2}
           (get-in result [:contribution :ops :entries "sample"])))
    (is (= {} (:contribution
               (module-graph/with-contribution-collection context (constantly nil)))))))

(deftest defop-rejects-malformed-authoring-forms
  (testing "macro-time shape checks fail loudly"
    (is (thrown? clojure.lang.Compiler$CompilerException
                 (macroexpand-1
                  '(skein.macros.ops/defop "bad" "doc" {:arg-spec {}} [ctx] ctx))))
    (is (thrown? clojure.lang.Compiler$CompilerException
                 (macroexpand-1
                  '(skein.macros.ops/defop missing-doc nil {:arg-spec {}} [ctx] ctx))))))
