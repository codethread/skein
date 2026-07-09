(ns skein.cron-test
  "Tests for the generic skein.spools.cron timer engine against a real weaver
  runtime: registration/status/deregistration, jittered scheduling bounds, loud
  failure recording that does not stop the cadence, and the versioned
  spool-state drift alarm.

  Job fires drive off a manual runtime clock and `skein.test.alpha/advance!`
  (DELTA-Dtt-001.CC3) rather than real executor-timer waits: cron's
  clock-consumer pump releases due jobs synchronously, so `advance!` returns
  only once a job it made due has already run."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.spools.cron :as cron]
            [skein.spools.test-support :as test-support]
            [skein.test.alpha :as test-alpha])
  (:import [java.time Duration Instant]
           [java.util Random]))

;; Job seams the engine resolves by fully-qualified symbol.
(defn fire-ok [_runtime] :ok)
(defn fire-throw [_runtime] (throw (ex-info "boom" {:why :test})))

(defn- with-cron [f]
  (test-support/with-runtime
    {:prefix "skein-cron"}
    (fn [rt _config-dir]
      (test-alpha/set-clock! rt (constantly (Instant/ofEpochSecond 0)))
      (cron/install!)
      (f rt))))

(deftest register-lists-and-deregisters
  (with-cron
    (fn [rt]
      ;; a one-hour interval keeps the first fire far out of the way
      (let [status (cron/register! rt {:id :slow
                                       :interval-ms (* 60 60 1000)
                                       :jitter-ms 0
                                       :run! 'skein.cron-test/fire-ok})]
        (is (= :slow (:id status)))
        (is (string? (:next-fire-at status)))
        (is (= 'skein.cron-test/fire-ok (:run! status)))
        (is (= [:slow] (mapv :id (cron/jobs rt))))
        (is (= {:deregistered :slow} (cron/deregister! rt :slow)))
        (is (= [] (cron/jobs rt)))
        (is (= {:deregistered nil} (cron/deregister! rt :slow)))))))

(deftest fires-and-records-last-outcome
  (with-cron
    (fn [rt]
      (cron/register! rt {:id :quick
                          :interval-ms 1000
                          :jitter-ms 0
                          :run! 'skein.cron-test/fire-ok})
      ;; the pump releases the due fire synchronously, so the outcome is
      ;; already recorded once advance! returns
      (test-alpha/advance! rt (Duration/ofSeconds 2))
      (let [job (first (cron/jobs rt))]
        (is (= :ok (:last-outcome job)))
        (is (string? (:last-fired-at job)))
        (is (nil? (:last-error job))))
      (cron/deregister! rt :quick))))

(deftest records-run-failure-without-stopping-cadence
  (with-cron
    (fn [rt]
      (cron/register! rt {:id :boom
                          :interval-ms 1000
                          :jitter-ms 0
                          :run! 'skein.cron-test/fire-throw})
      (test-alpha/advance! rt (Duration/ofSeconds 2))
      (let [failure (last (cron/failures rt))]
        (is (= :run (:kind failure)))
        (is (= :boom (:job failure)))
        (is (= "boom" (:message failure)))
        (is (string? (:at failure))))
      ;; the throw is recorded, not fatal: the job stays scheduled and carries
      ;; the error on its status
      (let [job (first (cron/jobs rt))]
        (is (= :boom (:id job)))
        (is (= "boom" (:last-error job))))
      (cron/deregister! rt :boom))))

(deftest jitter-offset-stays-in-bounds
  (let [rng (Random. 42)
        bound 1000]
    (dotimes [_ 1000]
      (let [offset (cron/jitter-offset-ms bound rng)]
        (is (<= (- bound) offset bound))))
    (testing "a zero or negative bound yields no jitter"
      (is (zero? (cron/jitter-offset-ms 0 (Random. 1))))
      (is (zero? (cron/jitter-offset-ms -5 (Random. 1)))))))

(deftest register-validates-inputs
  (with-cron
    (fn [rt]
      (is (thrown? Exception
                   (cron/register! rt {:id :bad :interval-ms 0
                                       :run! 'skein.cron-test/fire-ok})))
      (is (thrown? Exception
                   (cron/register! rt {:id :bad :interval-ms 1000 :jitter-ms -1
                                       :run! 'skein.cron-test/fire-ok})))
      (is (thrown? Exception
                   (cron/register! rt {:id :bad :interval-ms 1000
                                       :run! 'not-qualified}))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for cron's versioned spool-state: a key added to new-state
  ;; without a state-version bump would survive reload! as a stale map and
  ;; schedule against a nil executor.
  (test-support/assert-state-shape
   ;; white-box read of a private var: kondo flags cross-ns private access, but
   ;; #'ns/private is legal and intentional here.
   #_{:clj-kondo/ignore [:unresolved-var]}
   #'cron/new-state
   #{:executor :jobs :failure-log :rng :close-fn}))
