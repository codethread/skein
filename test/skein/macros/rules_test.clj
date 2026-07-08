(ns skein.macros.rules-test
  "Tests for the skein.macros.rules defrule macro and its remember/install flow."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.macros.rules :as rules :refer [defrule]]
            [skein.spools.chime :as chime]
            [skein.spools.test-support :as test-support]))

;; The accessors resolve a namespace, and `macroexpand-1` resolves the macro
;; symbol, against the *current* `*ns*`; the test runner does not bind that to
;; this namespace, so the tests below name it explicitly rather than relying on
;; a no-arg/aliased default.
(def ^:private this-ns 'skein.macros.rules-test)

(defn- remembered
  "Return the ordered entries remembered under `ns-key` in the private registry."
  [ns-key]
  (get @@#'rules/rule-registry ns-key))

(defn- expand
  "macroexpand-1 `form`, surfacing the macro's own ex-info rather than the
  compiler's macro-syntax-check wrapper, so guard assertions can match it."
  [form]
  (try
    (macroexpand-1 form)
    (catch clojure.lang.Compiler$CompilerException e
      (throw (or (.getCause e) e)))))

(defn- with-chime
  "Run `f` against an isolated `:publish? false` runtime with chime installed."
  [f]
  (test-support/with-runtime
    {:prefix "skein-macros-rules"}
    (fn [rt config-dir]
      (chime/install!)
      (f rt config-dir))))

(defrule test-alpha
  "Notify when a strand is active."
  [{:keys [strand]}]
  (when (= "active" (:state strand))
    {:title "alpha" :body "alpha body"}))

(defrule test-beta
  "Never notify; a quiet rule for ordering coverage."
  [{:keys [strand]}]
  (when (= "closed" (:state strand))
    {:title "beta" :body "beta body"}))

(deftest defrule-defines-var-and-remembers-entry
  (testing "the macro expands to a real defn of the <name>-rule handler var"
    (is (var? #'test-alpha-rule))
    (is (= {:title "alpha" :body "alpha body"} (test-alpha-rule {:strand {:state "active"}})))
    (is (nil? (test-alpha-rule {:strand {:state "closed"}}))))
  (testing "remembered entries carry the chime key and fully-qualified handler symbol in author order"
    (is (= [{:key :test-alpha :fn 'skein.macros.rules-test/test-alpha-rule}
            {:key :test-beta :fn 'skein.macros.rules-test/test-beta-rule}]
           (remembered this-ns)))))

(deftest install-rules-registers-through-chime
  (with-chime
    (fn [_rt _dir]
      (let [result (rules/install-rules! this-ns)]
        (testing "install returns a vector of chime/defrule! returns in author order"
          (is (= [{:rule :test-alpha :fn 'skein.macros.rules-test/test-alpha-rule}
                  {:rule :test-beta :fn 'skein.macros.rules-test/test-beta-rule}]
                 result)))
        (testing "the rules are registered with the chime engine under their keys"
          (is (= #{:test-alpha :test-beta}
                 (set (map :name (chime/rules))))))))))

(deftest remember-rule-replaces-same-key-in-place
  (testing "re-remembering a key replaces its entry, preserving author order (reload-friendly)"
    (let [ns-key 'skein.macros.rules-test.dup]
      (rules/remember-rule! ns-key {:key :a :fn 'ns/a-rule})
      (rules/remember-rule! ns-key {:key :b :fn 'ns/b-rule})
      (rules/remember-rule! ns-key {:key :a :fn 'ns/a2-rule})
      (is (= [{:key :a :fn 'ns/a2-rule} {:key :b :fn 'ns/b-rule}]
             (remembered ns-key))))))

(deftest defrule-fails-loudly-on-bad-input
  (testing "a non-symbol name throws at macroexpansion"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name must be a symbol"
                          (expand '(skein.macros.rules/defrule "not-a-symbol" "doc" [{:keys [strand]}] nil)))))
  (testing "a missing or non-string docstring throws at macroexpansion, naming the rule"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a docstring"
                          (expand '(skein.macros.rules/defrule bad-doc :not-a-string [{:keys [strand]}] nil))))))
