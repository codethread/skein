(ns skein.api.spool-test
  "Tests for skein.api.spool.alpha: shared arg-spec, validation, attribute, and
  polling seams that other spools compose from."
  (:require [clojure.test :refer [deftest is]]
            [skein.api.spool.alpha :as util]))

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

(deftest arg-spec-fragments-pin-the-shared-declarations
  (is (= {:flags {:by {:doc "Attribution recorded with the note."}}
          :positionals [{:name :id :required? true :doc "Target id."}
                        {:name :text :required? true :doc "Note text."}]}
         util/note-surface))
  (is (= {:flags {:feature {:doc "Feature or work slug."}
                  :owner {:doc "Owner identity."}
                  :branch {:doc "Branch name."}
                  :worktree {:doc "Worktree path."}}}
         util/work-root))
  (is (= {:flags {:timeout-secs {:type :int
                                 :doc "Maximum seconds to wait."}}}
         util/timeout-secs))
  (is (= {:flags {:outcome {:doc "Outcome recorded when closing the entity."}}}
         util/outcome)))

(deftest arg-spec-fragments-compose-with-spool-owned-declarations
  (is (= {:flags {:feature {:doc "Feature or work slug."}
                  :owner {:doc "Owner identity."}
                  :branch {:doc "Branch name."}
                  :worktree {:doc "Worktree path."}
                  :engine {:doc "Spool-owned execution engine."}}
          :positionals [{:name :root-id
                         :required? true
                         :doc "Spool-owned root id."}]}
         (merge-with into
                     util/work-root
                     {:flags {:engine {:doc "Spool-owned execution engine."}}
                      :positionals [{:name :root-id
                                     :required? true
                                     :doc "Spool-owned root id."}]}))))

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

(deftest attr-get-rejects-omitted-attribute-descriptors
  (let [strand {:id "abc123"
                :attributes {:payload {:skein/omitted true :bytes 2048}}}
        e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"omitted from this lean read"
                                (util/attr-get strand :payload)))]
    (is (= {:key :payload
            :strand-id "abc123"
            :recovery "show abc123"}
           (ex-data e)))))
