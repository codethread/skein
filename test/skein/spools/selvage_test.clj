(ns skein.spools.selvage-test
  "Tests for the Selvage attribute vocabulary lint spool."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.repl :as repl]
            [skein.spools.selvage :as selvage]
            [skein.spools.test-support :refer [with-runtime]]))

(defn- unique-vocab []
  (keyword (str "selvage-test-" (java.util.UUID/randomUUID))))

(defn- shuttle-spec []
  {:checks [{:attr "shuttle/phase" :enum ["pending" "running" "done"]}
            {:attr "shuttle/max-attempts" :kind :int-string}]})

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
    (fn [rt _]
      (clear-vocabs!)
      (let [vocab (unique-vocab)]
        (selvage/defvocab! vocab (shuttle-spec))
        (let [clean (repl/strand! "Clean" {:shuttle/phase "running"
                                           :shuttle/max-attempts "3"})
              bad (repl/strand! "Bad" {:shuttle/phase "bogus"
                                       :shuttle/max-attempts "three"})]
          (is (= [] (selvage/check (:id clean))))
          (is (= [{:strand-id (:id bad)
                   :vocab vocab
                   :attr "shuttle/phase"
                   :check :enum
                   :value "bogus"}
                  {:strand-id (:id bad)
                   :vocab vocab
                   :attr "shuttle/max-attempts"
                   :check :kind
                   :value "three"}]
                 (mapv #(select-keys % [:strand-id :vocab :attr :check :value])
                       (selvage/check (:id bad))))))))))

(deftest check-fails-loudly-for-missing-strand-id
  (with-runtime
    (fn [rt _]
      (clear-vocabs!)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Strand not found"
                            (selvage/check "missing-strand"))))))

(deftest check-all-can-be-scoped-by-query-form
  (with-runtime
    (fn [rt _]
      (clear-vocabs!)
      (let [vocab (unique-vocab)]
        (selvage/defvocab! vocab (shuttle-spec))
        (repl/strand! "Bad in scope" {:owner "agent" :shuttle/phase "bogus"})
        (repl/strand! "Bad out of scope" {:owner "human" :shuttle/phase "bogus"})
        (is (= ["agent"]
               (mapv #(get-in (repl/strand (:strand-id %)) [:attributes :owner])
                     (selvage/check-all [:= [:attr :owner] "agent"]))))))))

(deftest watch-records-and-clears-violations
  (with-runtime
    (fn [rt _]
      (clear-vocabs!)
      (let [vocab (unique-vocab)
            strand (repl/strand! "Watch target" {:shuttle/phase "pending"})]
        (selvage/defvocab! vocab {:checks [{:attr "shuttle/phase" :enum ["pending"]}]})
        (selvage/clear-violations!)
        (selvage/install!)
        (repl/update! (:id strand) {:attributes {:shuttle/phase "failed"}})
        (Thread/sleep 250)
        (is (= [{:strand-id (:id strand)
                 :vocab vocab
                 :attr "shuttle/phase"
                 :check :enum
                 :value "failed"}]
               (mapv #(select-keys % [:strand-id :vocab :attr :check :value])
                     (selvage/violations))))
        (is (= {:cleared true} (selvage/clear-violations!)))
        (is (= [] (selvage/violations)))))))

(deftest defvocab-replaces-existing-vocabulary
  (with-runtime
    (fn [rt _]
      (clear-vocabs!)
      (let [vocab (unique-vocab)
            strand (repl/strand! "Replace target" {:shuttle/phase "running"})]
        (selvage/defvocab! vocab {:checks [{:attr "shuttle/phase" :enum ["pending"]}]})
        (is (= [:enum] (mapv :check (selvage/check (:id strand)))))
        (selvage/defvocab! vocab {:checks [{:attr "shuttle/phase" :enum ["running"]}]})
        (is (= [] (selvage/check (:id strand))))
        (is (= {:removed vocab} (selvage/remove-vocab! vocab)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not registered"
                              (selvage/remove-vocab! vocab)))))))
