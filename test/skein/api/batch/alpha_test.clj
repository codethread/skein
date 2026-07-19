(ns skein.api.batch.alpha-test
  "Request-context arity and fail-loud payload coverage for the batch API.

  The broad batch behavior lock — event fanout, attribute normalization, and
  atomic rejection — lives in `skein.weaver-test`; this namespace pins the
  explicit caller-supplied request-context arity and the payload grammar's
  loud rejection at the public surface."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [skein.api.batch.alpha :as batch]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.db-test :as db-test]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.runtime :as weaver-runtime]))

(defn- test-world [config-dir]
  (weaver-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn- with-runtime [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-batch-alpha-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [rt (weaver-runtime/start! db-file {:world (test-world config-dir) :publish? false})]
      (try
        (f rt)
        (finally
          (weaver-runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file))))))

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
  (with-runtime
    (fn [rt]
      (weaver/init rt)
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
  (with-runtime
    (fn [rt]
      (weaver/init rt)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch payload must be a map"
                            (batch/apply! rt [:not-a-map])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown keys"
                            (batch/apply! rt {:strands [] :bogus []})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Batch strand entry requires :ref"
                            (batch/apply! rt {:strands [{:title "No ref"}]}))))))
