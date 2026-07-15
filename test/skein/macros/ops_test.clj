(ns skein.macros.ops-test
  "Tests for the skein.macros.ops defop macro and its remember/install flow."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.api.weaver.alpha :as weaver]
            [skein.macros.ops :as ops :refer [defop]]
            [skein.spools.test-support :refer [with-runtime]]))

;; The accessors resolve a namespace, and `macroexpand-1` resolves the macro
;; symbol, against the *current* `*ns*`; the test runner does not bind that to
;; this namespace, so the tests below name it explicitly rather than relying on
;; a no-arg/aliased default.
(def ^:private this-ns 'skein.macros.ops-test)

(defn- expand
  "macroexpand-1 `form`, surfacing the macro's own ex-info rather than the
  compiler's macro-syntax-check wrapper, so guard assertions can match it."
  [form]
  (try
    (macroexpand-1 form)
    (catch clojure.lang.Compiler$CompilerException e
      (throw (or (.getCause e) e)))))

;; Named-arg-spec form: the op carries an existing arg-spec var plus an extra
;; :convention field beyond the mechanically-derived {:name :help}.
(def ^:private test-alpha-arg-spec
  {:op "test-alpha"
   :doc "Alpha test op."
   :positionals [{:name :x :type :string :doc "An x value."}]})

(defop test-alpha
  "Alpha op handler."
  {:arg-spec test-alpha-arg-spec
   :returns {:type :map
             :required {:operation :string
                        :x :string}}
   :convention {:manual "strand test-alpha about"}}
  [ctx]
  {:operation "test-alpha" :x (:x (:op/args ctx))})

;; Inline-arg-spec form: the op declares its arg-spec inline and passes an extra
;; op-metadata key (:deadline-class) straight through to registration.
(defop test-beta
  "Beta op handler."
  {:arg-spec {:op "test-beta"
              :doc "Beta test op."
              :positionals [{:name :run-id :type :string :required? true :doc "A run id."}]}
   :deadline-class :unbounded
   :returns {:type :map
             :required {:operation :string}}}
  [_ctx]
  {:operation "test-beta"})

(deftest defop-defines-handler-and-remembers-conventions
  (testing "the macro expands to a real, callable defn of the <name>-op handler var"
    (is (var? #'test-alpha-op))
    (is (fn? test-alpha-op))
    (is (= {:operation "test-alpha" :x "v"} (test-alpha-op {:op/args {:x "v"}}))))
  (testing "remembered conventions carry the derived {:name :help} plus authored fields, in author order"
    (is (= [{:name "test-alpha" :help "strand help test-alpha" :manual "strand test-alpha about"}
            {:name "test-beta" :help "strand help test-beta"}]
           (#'ops/remembered-ops this-ns)))))

(deftest install-ops-registers-into-runtime
  (with-runtime
    (fn [rt _]
      (let [result (ops/install-ops! this-ns)]
        (testing "install returns a register-op! entry vector in author order, matching the install! :ops shape"
          (is (= 2 (count result)))
          (is (= ["test-alpha" "test-beta"] (mapv :name result))))
        (testing "the named-arg-spec op registers with its fully-qualified handler symbol and arg-spec-derived doc"
          (let [entry (weaver/resolve-op rt 'test-alpha)]
            (is (= 'skein.macros.ops-test/test-alpha-op (:fn entry)))
            (is (= "Alpha test op." (:doc entry)))
            (is (= "test-alpha" (:op (:arg-spec entry))))
            (is (= {:type :map :required {:operation :string :x :string}}
                   (:returns entry)))))
        (testing "the inline-arg-spec op registers and its extra :deadline-class metadata survives to registration"
          (let [entry (weaver/resolve-op rt 'test-beta)]
            (is (= 'skein.macros.ops-test/test-beta-op (:fn entry)))
            (is (= "test-beta" (:op (:arg-spec entry))))
            (is (= {:type :map :required {:operation :string}}
                   (:returns entry)))
            (is (= :unbounded (:deadline-class entry)))))))))

(deftest install-ops-double-registration-fails-loudly
  (with-runtime
    (fn [_rt _]
      (ops/install-ops! this-ns)
      (testing "a second install of the same names trips register-op!'s loud-collision contract"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already registered"
                              (ops/install-ops! this-ns)))))))

(deftest remember-op-replaces-same-name-in-place
  (testing "re-remembering a name replaces its entry, preserving author order (reload-friendly)"
    (let [ns-key 'skein.macros.ops-test.dup]
      (ops/remember-op! ns-key {:name 'a :fn 'x/a-op :arg-spec {} :metadata {} :convention {:name "a" :help "strand help a"}})
      (ops/remember-op! ns-key {:name 'b :fn 'x/b-op :arg-spec {} :metadata {} :convention {:name "b" :help "strand help b"}})
      (ops/remember-op! ns-key {:name 'a :fn 'x/a-op :arg-spec {} :metadata {} :convention {:name "a" :help "strand help a" :purpose "second"}})
      (is (= [{:name "a" :help "strand help a" :purpose "second"} {:name "b" :help "strand help b"}]
             (#'ops/remembered-ops ns-key))))))

(deftest forget-ops-drops-stale-entries-across-reload
  (testing "forget-ops! clears the namespace so a reload registers only current source (TEN-003)"
    (let [ns-key 'skein.macros.ops-test.reload
          arg-spec {:op "stale-a" :doc "d"}]
      ;; first load: source defined A and B
      (ops/remember-op! ns-key {:name 'stale-a :fn 'skein.macros.ops-test/test-alpha-op
                                :arg-spec arg-spec :metadata {} :convention {:name "stale-a"}})
      (ops/remember-op! ns-key {:name 'stale-b :fn 'skein.macros.ops-test/test-beta-op
                                :arg-spec {:op "stale-b" :doc "d"} :metadata {} :convention {:name "stale-b"}})
      ;; reload where B was deleted from source: forget, then re-remember only A
      (ops/forget-ops! ns-key)
      (ops/remember-op! ns-key {:name 'stale-a :fn 'skein.macros.ops-test/test-alpha-op
                                :arg-spec arg-spec :metadata {} :convention {:name "stale-a"}})
      (is (= [{:name "stale-a"}] (#'ops/remembered-ops ns-key))
          "only the surviving op remains remembered")
      (with-runtime
        (fn [rt _]
          (let [result (ops/install-ops! ns-key)]
            (is (= ["stale-a"] (mapv :name result)) "install registers only the surviving op")
            (is (= 'skein.macros.ops-test/test-alpha-op (:fn (weaver/resolve-op rt 'stale-a))))
            (testing "the forgotten op never reaches the runtime and resolving it fails loudly"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Operation not found"
                                    (weaver/resolve-op rt 'stale-b))))))))))

(deftest install-ops-unknown-ns-fails-loudly
  (testing "installing a namespace with no remembered ops throws rather than silently installing nothing (TEN-003)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no remembered ops"
                          (ops/install-ops! 'skein.macros.ops-test.unknown))))
  (testing "a namespace forgotten down to nothing (all ops removed from source) also throws"
    (let [ns-key 'skein.macros.ops-test.emptied]
      (ops/remember-op! ns-key {:name 'gone :fn 'x/gone-op :arg-spec {} :metadata {} :convention {:name "gone"}})
      (ops/forget-ops! ns-key)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no remembered ops"
                            (ops/install-ops! ns-key))))))

(deftest forget-ops-unknown-ns-is-a-no-op
  (testing "forget-ops! tolerates an unknown namespace (first-load calls it before anything is remembered)"
    (is (nil? (ops/forget-ops! 'skein.macros.ops-test.never-remembered)))))

(deftest defop-fails-loudly-on-bad-input
  (testing "a non-symbol name throws at macroexpansion"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name must be a symbol"
                          (expand '(skein.macros.ops/defop "not-a-symbol" "doc" {:arg-spec {:op "x" :doc "d"}} [ctx] nil)))))
  (testing "a missing or non-string docstring throws at macroexpansion"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a docstring"
                          (expand '(skein.macros.ops/defop bad-doc :not-a-string {:arg-spec {:op "x" :doc "d"}} [ctx] nil)))))
  (testing "a missing :arg-spec throws at macroexpansion"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require an :arg-spec"
                          (expand '(skein.macros.ops/defop no-spec "doc" {} [ctx] nil)))))
  (testing "invalid :returns survives expansion and fails during installation"
    (let [ns-key 'skein.macros.ops-test.bad-returns]
      (ops/remember-op! ns-key {:name 'bad-returns
                                :fn 'skein.macros.ops-test/test-alpha-op
                                :arg-spec {:op "bad-returns" :doc "d"}
                                :metadata {:returns :not-a-shape}
                                :convention {:name "bad-returns"}})
      (with-runtime
        (fn [_rt _]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #":returns declaration is invalid"
                                (ops/install-ops! ns-key))))))))
