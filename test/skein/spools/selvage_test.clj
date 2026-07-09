(ns skein.spools.selvage-test
  "Tests for the Selvage attribute vocabulary lint spool."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.repl :as repl]
            [skein.spools.selvage :as selvage]
            [skein.spools.test-support :refer [assert-state-shape with-runtime]]))

(defn- unique-vocab []
  (keyword (str "selvage-test-" (java.util.UUID/randomUUID))))

(defn- shuttle-spec []
  {:checks [{:attr "agent-run/phase" :enum ["pending" "running" "done"]}
            {:attr "agent-run/max-attempts" :kind :int-string}]})

(defn- clear-vocabs! []
  (doseq [{:keys [name]} (selvage/vocabs)]
    (selvage/remove-vocab! name)))

(deftest defvocab-fails-loudly-on-invalid-specs
  (testing "unknown kind"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown"
                          (selvage/defvocab! (unique-vocab)
                            {:checks [{:attr "x" :kind :uuid}]}))))
  (testing "unknown spec key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                          (selvage/defvocab! (unique-vocab)
                            {:checks [] :extra true}))))
  (testing "unknown check key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                          (selvage/defvocab! (unique-vocab)
                            {:checks [{:attr "x" :kind :string :extra true}]})))))

(deftest check-reports-clean-and-violating-strands
  (with-runtime
    (fn [_rt _]
      (clear-vocabs!)
      (let [vocab (unique-vocab)]
        (selvage/defvocab! vocab (shuttle-spec))
        (let [clean (repl/strand! "Clean" {:agent-run/phase "running"
                                           :agent-run/max-attempts "3"})
              bad (repl/strand! "Bad" {:agent-run/phase "bogus"
                                       :agent-run/max-attempts "three"})]
          (is (= [] (selvage/check (:id clean))))
          (is (= [{:strand-id (:id bad)
                   :vocab vocab
                   :attr "agent-run/phase"
                   :check :enum
                   :value "bogus"}
                  {:strand-id (:id bad)
                   :vocab vocab
                   :attr "agent-run/max-attempts"
                   :check :kind
                   :value "three"}]
                 (mapv #(select-keys % [:strand-id :vocab :attr :check :value])
                       (selvage/check (:id bad))))))))))

(deftest check-fails-loudly-for-missing-strand-id
  (with-runtime
    (fn [_rt _]
      (clear-vocabs!)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Strand not found"
                            (selvage/check "missing-strand"))))))

(deftest check-all-can-be-scoped-by-query-form
  (with-runtime
    (fn [_rt _]
      (clear-vocabs!)
      (let [vocab (unique-vocab)]
        (selvage/defvocab! vocab (shuttle-spec))
        (repl/strand! "Bad in scope" {:owner "agent" :agent-run/phase "bogus"})
        (repl/strand! "Bad out of scope" {:owner "human" :agent-run/phase "bogus"})
        (is (= ["agent"]
               (mapv #(get-in (repl/strand (:strand-id %)) [:attributes :owner])
                     (selvage/check-all [:= [:attr :owner] "agent"]))))))))

(deftest watch-records-and-clears-violations
  (with-runtime
    (fn [_rt _]
      (clear-vocabs!)
      (let [vocab (unique-vocab)
            strand (repl/strand! "Watch target" {:agent-run/phase "pending"})]
        (selvage/defvocab! vocab {:checks [{:attr "agent-run/phase" :enum ["pending"]}]})
        (selvage/clear-violations!)
        (selvage/install!)
        (repl/update! (:id strand) {:attributes {:agent-run/phase "failed"}})
        (Thread/sleep 250)
        (is (= [{:strand-id (:id strand)
                 :vocab vocab
                 :attr "agent-run/phase"
                 :check :enum
                 :value "failed"}]
               (mapv #(select-keys % [:strand-id :vocab :attr :check :value])
                     (selvage/violations))))
        (is (= {:cleared true} (selvage/clear-violations!)))
        (is (= [] (selvage/violations)))))))

(deftest defvocab-replaces-existing-vocabulary
  (with-runtime
    (fn [_rt _]
      (clear-vocabs!)
      (let [vocab (unique-vocab)
            strand (repl/strand! "Replace target" {:agent-run/phase "running"})]
        (selvage/defvocab! vocab {:checks [{:attr "agent-run/phase" :enum ["pending"]}]})
        (is (= [:enum] (mapv :check (selvage/check (:id strand)))))
        (selvage/defvocab! vocab {:checks [{:attr "agent-run/phase" :enum ["running"]}]})
        (is (= [] (selvage/check (:id strand))))
        (is (= {:removed vocab} (selvage/remove-vocab! vocab)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not registered"
                              (selvage/remove-vocab! vocab)))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for selvage's versioned spool-state: a key added to new-state
  ;; without a state-version bump would survive reload! as a stale map.
  (assert-state-shape #'selvage/new-state #{:vocab-registry :violation-log}))
