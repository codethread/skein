(ns skein.api.batch.alpha-test
  "Request-context arity and fail-loud payload coverage for the batch API.

  The broad batch behavior lock — event fanout, attribute normalization, and
  atomic rejection — lives in `skein.weaver-test`; this namespace pins the
  explicit caller-supplied request-context arity and the payload grammar's
  loud rejection at the public surface."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [skein.api.batch.alpha :as batch]
            [skein.api.hooks.alpha :as hooks]
            [skein.test.alpha :as t]))

;; Namespace-level on purpose: hooks are registered by symbol and resolved to
;; top-level vars, so capture state cannot be a per-test local.
(def captured-contexts (atom []))

(use-fixtures :each (fn [f] (reset! captured-contexts []) (f)))

(defn capture-hook
  "Validation hook that records its context and approves the batch."
  [ctx]
  (swap! captured-contexts conj ctx)
  :ok)

(deftest apply-threads-a-caller-request-context-into-the-validation-gate
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (hooks/register-hook! rt :capture #{:batch/apply-before-commit}
                            'skein.api.batch.alpha-test/capture-hook {})
      (let [payload {:strands [{:ref :created
                                :title "Created"
                                :attributes {:owner "client"}}]}
            result (batch/apply! rt payload {:request/source :nrepl
                                             :request/operation :apply-batch})
            context (last @captured-contexts)]
        (is (= 1 (count (:created result))))
        (is (= :nrepl (:request/source context)))
        (is (= :apply-batch (:request/operation context)))
        (is (= :batch/apply (:mutation/operation context)))
        (is (= :apply (:batch/source context)))
        (is (= payload (:batch/payload context)))))))

(deftest apply-rejects-malformed-payloads-loudly
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch payload must be a map"
                            (batch/apply! rt [:not-a-map])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown keys"
                            (batch/apply! rt {:strands [] :bogus []})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch strand entry requires :ref"
                            (batch/apply! rt {:strands [{:title "No ref"}]}))))))

(deftest apply-rejects-malformed-remove-ops-loudly
  ;; PROP-Xer-001.PO2 shape matrix at the public surface: the closed `:remove`
  ;; grammar rejects an extra key, `:attributes`, and a missing endpoint before
  ;; the transaction opens, so a malformed remove never reaches storage.
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          refs {:a "strand-a" :b "strand-b"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown keys"
                            (batch/apply! rt {:refs refs
                                              :edges [{:op :remove :from :a :to :b
                                                       :type "depends-on" :extra 1}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown keys"
                            (batch/apply! rt {:refs refs
                                              :edges [{:op :remove :from :a :to :b
                                                       :type "depends-on" :attributes {}}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"endpoint is required"
                            (batch/apply! rt {:refs refs
                                              :edges [{:op :remove :to :b :type "depends-on"}]}))))))
