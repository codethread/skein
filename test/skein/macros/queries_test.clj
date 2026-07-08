(ns skein.macros.queries-test
  "Tests for the skein.macros.queries defquery macro and its remember/install flow."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.api.weaver.alpha :as api]
            [skein.macros.queries :as queries :refer [defquery]]
            [skein.spools.test-support :refer [with-runtime]]))

;; The accessors resolve a namespace, and `macroexpand-1` resolves the macro
;; symbol, against the *current* `*ns*`; the test runner does not bind that to
;; this namespace, so the tests below name it explicitly rather than relying on
;; a no-arg/aliased default.
(def ^:private this-ns 'skein.macros.queries-test)

(defn- expand
  "macroexpand-1 `form`, surfacing the macro's own ex-info rather than the
  compiler's macro-syntax-check wrapper, so guard assertions can match it."
  [form]
  (try
    (macroexpand-1 form)
    (catch clojure.lang.Compiler$CompilerException e
      (throw (or (.getCause e) e)))))

(defquery test-alpha-query
  "Active work test query."
  {:usage "strand ready --query test-alpha"}
  [:= :state "active"])

(defquery test-beta-query
  "Parameterized feature test query."
  {:usage "strand list --query test-beta --param feature=<feature>"}
  {:params [:feature]
   :where [:= [:attr :feature] [:param :feature]]})

(deftest defquery-defines-var-and-remembers-entry
  (testing "the macro expands to a real def of the query var"
    (is (= [:= :state "active"] test-alpha-query))
    (is (var? #'test-alpha-query)))
  (testing "remembered entries carry the registered name (with -query stripped) and usage in author order"
    (is (= [{:name 'test-alpha :usage "strand ready --query test-alpha"}
            {:name 'test-beta :usage "strand list --query test-beta --param feature=<feature>"}]
           (queries/remembered-queries this-ns)))))

(deftest install-queries-registers-into-runtime-and-round-trips
  (with-runtime
    (fn [rt _]
      (let [result (queries/install-queries! this-ns)]
        (testing "install returns registered-name -> register-query! canonical map, matching register-query-map!"
          (is (= {'test-alpha {"test-alpha" [:= :state "active"]}
                  'test-beta {"test-beta" {:params [:feature]
                                           :where [:= [:attr :feature] [:param :feature]]}}}
                 result)))
        (testing "the registered query definitions round-trip through the runtime registry"
          (is (= [:= :state "active"] (api/resolve-query rt 'test-alpha)))
          (is (= {:params [:feature] :where [:= [:attr :feature] [:param :feature]]}
                 (api/resolve-query rt 'test-beta))))))))

(deftest remember-query-replaces-same-name-in-place
  (testing "re-remembering a name replaces its entry, preserving author order (reload-friendly)"
    (let [ns-key 'skein.macros.queries-test.dup]
      (queries/remember-query! ns-key {:name 'a :query [:= :state "active"] :usage "usage-a"})
      (queries/remember-query! ns-key {:name 'b :query [:= :state "closed"] :usage "usage-b"})
      (queries/remember-query! ns-key {:name 'a :query [:= :state "blocked"] :usage "usage-a2"})
      (is (= [{:name 'a :usage "usage-a2"} {:name 'b :usage "usage-b"}]
             (queries/remembered-queries ns-key))))))

(deftest defquery-fails-loudly-on-bad-input
  (testing "a non-symbol name throws at macroexpansion"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name must be a symbol"
                          (expand '(skein.macros.queries/defquery "not-a-symbol" "doc" {:usage "u"} [:= :state "active"])))))
  (testing "a missing or non-string docstring throws at macroexpansion"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a docstring"
                          (expand '(skein.macros.queries/defquery bad-doc-query :not-a-string {:usage "u"} [:= :state "active"])))))
  (testing "a missing :usage throws at macroexpansion"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require a :usage"
                          (expand '(skein.macros.queries/defquery no-usage-query "doc" {} [:= :state "active"]))))))
