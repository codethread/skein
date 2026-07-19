(ns skein.api.spool-test
  "Tests for skein.api.spool.alpha: shared arg-spec, validation, attribute, and
  polling seams that other spools compose from."
  (:require [clojure.test :refer [deftest is]]
            [skein.api.spool.alpha :as util]))

(deftest fail!-throws-ex-info-with-contextual-data-and-optional-cause
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boundary rejected"
                                (util/fail! "boundary rejected" {:op :add})))]
    (is (= {:op :add} (ex-data e))))
  (let [cause (RuntimeException. "disk full")
        e (is (thrown? clojure.lang.ExceptionInfo
                       (util/fail! "write failed" {:path "x"} cause)))]
    (is (identical? cause (ex-cause e)))))

(deftest reject-unknown-keys!-returns-m-and-names-the-offending-surface
  (let [m {:feature "f" :branch "b"}]
    (is (identical? m (util/reject-unknown-keys! "claim" #{:feature :branch} m)))
    (is (= {} (util/reject-unknown-keys! "claim" #{:feature} {})))
    (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"claim received unknown keys"
                                  (util/reject-unknown-keys! "claim" #{:feature}
                                                             {:feature "f" :brnch "typo"})))]
      (is (= {:unknown [:brnch] :allowed [:feature]} (ex-data e))))))

(deftest require-valid!-returns-value-or-attaches-spec-explain-data
  (is (= 42 (util/require-valid! int? 42 "must be an int")))
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be an int"
                                (util/require-valid! int? "42" "must be an int")))]
    (is (= "42" (:value (ex-data e))))
    (is (some? (:explain (ex-data e))))))

(deftest attr-key->str-renders-the-wire-form
  (is (= "lane" (util/attr-key->str :lane)))
  (is (= "kanban/lane" (util/attr-key->str :kanban/lane)))
  (is (= "kanban/lane" (util/attr-key->str "kanban/lane"))))

(deftest attr-get-prefers-keyword-keys-and-tolerates-wire-string-keys
  (is (= "doing" (util/attr-get {:attributes {:status "doing"}} :status)))
  (is (= "doing" (util/attr-get {:attributes {"status" "doing"}} :status)))
  (is (= "doing" (util/attr-get {:attributes {"kanban/lane" "doing"}} :kanban/lane)))
  (is (= "doing" (util/attr-get {:attributes {:status "doing"}} "status")))
  (is (= "keyword-wins" (util/attr-get {:attributes {:status "keyword-wins"
                                                     "status" "string-loses"}}
                                       :status)))
  (is (nil? (util/attr-get {:attributes {:status "doing"}} :missing))))

(deftest attr-get-reads-falsey-keyword-values-instead-of-falling-through
  (is (false? (util/attr-get {:attributes {:hitl false "hitl" true}} :hitl)))
  (is (nil? (util/attr-get {:attributes {:hitl nil "hitl" true}} :hitl))))

(deftest entity-projection-is-exact-and-requires-every-canonical-field
  (let [strand {:id "abc123" :title "Work" :state "active"
                :attributes {:kind "task"} :created_at "discarded"}
        expected (dissoc strand :created_at)]
    (is (= expected (util/entity-projection strand)))
    (doseq [field (keys expected)]
      (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                    #"missing canonical entity fields"
                                    (util/entity-projection (dissoc strand field))))]
        (is (= [field] (:missing (ex-data e))))))))

(deftest poll-until-deadline!-polls-until-pred-result-then-stops
  (let [calls (atom 0)]
    (is (= :ready
           (util/poll-until-deadline!
            {:deadline (+ (System/currentTimeMillis) 60000)
             :poll-ms 1
             :check #(swap! calls inc)
             :pred->result (fn [n] (when (>= n 3) :ready))
             :on-timeout (constantly :timeout)})))
    (is (= 3 @calls))))

(deftest poll-until-deadline!-calls-on-timeout-once-the-deadline-has-passed
  (is (= {:reason :timeout :value :still-waiting}
         (util/poll-until-deadline!
          {:deadline (dec (System/currentTimeMillis))
           :poll-ms 1
           :check (constantly :still-waiting)
           :pred->result (constantly nil)
           :on-timeout (fn [v] {:reason :timeout :value v})}))))

(deftest poll-until-deadline!-fails-loudly-for-malformed-deadline-or-poll-ms
  (let [valid {:deadline (System/currentTimeMillis)
               :poll-ms 1
               :check (constantly true)
               :pred->result (constantly :done)
               :on-timeout (constantly :timeout)}]
    (doseq [bad [1.5 "5" nil]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":deadline must be a long"
                            (util/poll-until-deadline! (assoc valid :deadline bad)))))
    (doseq [bad [-1 1.5 "5" nil]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":poll-ms must be a non-negative integer"
                            (util/poll-until-deadline! (assoc valid :poll-ms bad)))))))

(deftest poll-until-deadline!-fails-loudly-for-missing-required-functions
  (let [valid {:deadline (System/currentTimeMillis)
               :poll-ms 1
               :check (constantly true)
               :pred->result (constantly :done)
               :on-timeout (constantly :timeout)}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":check must be a function"
                          (util/poll-until-deadline! (dissoc valid :check))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":pred->result must be a function"
                          (util/poll-until-deadline! (dissoc valid :pred->result))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":on-timeout must be a function"
                          (util/poll-until-deadline! (dissoc valid :on-timeout))))))

(deftest poll-until-deadline!-rejects-unknown-option-keys
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"poll-until-deadline! received unknown keys"
                                (util/poll-until-deadline!
                                 {:deadline (System/currentTimeMillis)
                                  :poll-ms 1
                                  :check (constantly true)
                                  :pred->result (constantly :done)
                                  :on-timeout (constantly :timeout)
                                  :timeout-secs 5})))]
    (is (= [:timeout-secs] (:unknown (ex-data e))))))

(deftest attr-get-rejects-omitted-attribute-descriptors
  (let [strand {:id "abc123"
                :attributes {:payload {:skein/omitted true :bytes 2048}}}
        e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"omitted from this lean read"
                                (util/attr-get strand :payload)))]
    (is (= {:key :payload
            :strand-id "abc123"
            :recovery "show abc123"}
           (ex-data e)))))
