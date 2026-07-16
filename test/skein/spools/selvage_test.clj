(ns skein.spools.selvage-test
  "Tests for the Selvage attribute vocabulary lint spool."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.api.vocab.alpha :as vocab]
            [skein.repl :as repl]
            [skein.spools.selvage :as selvage]
            [skein.spools.test-support :refer [assert-state-shape with-runtime]]))

(defn- unique-checkset []
  (keyword (str "selvage-test-" (java.util.UUID/randomUUID))))

(defn- shuttle-spec []
  {:checks [{:attr "agent-run/phase" :enum ["pending" "running" "done"]}
            {:attr "agent-run/max-attempts" :type :int-string}]})

(defn- clear-checksets! []
  (doseq [{:keys [name]} (selvage/checksets)]
    (selvage/unregister-checkset! name)))

(deftest register-checkset-fails-loudly-on-invalid-specs
  (testing "unknown type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown"
                          (selvage/register-checkset! (unique-checkset)
                                                      {:checks [{:attr "x" :type :uuid}]}))))
  (testing "unknown spec key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                          (selvage/register-checkset! (unique-checkset)
                                                      {:checks [] :extra true}))))
  (testing "unknown check key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                          (selvage/register-checkset! (unique-checkset)
                                                      {:checks [{:attr "x" :type :string :extra true}]})))))

(deftest check-reports-clean-and-violating-strands
  (with-runtime
    (fn [_rt _]
      (clear-checksets!)
      (let [checkset (unique-checkset)]
        (selvage/register-checkset! checkset (shuttle-spec))
        (let [clean (repl/strand! "Clean" {:agent-run/phase "running"
                                           :agent-run/max-attempts "3"})
              bad (repl/strand! "Bad" {:agent-run/phase "bogus"
                                       :agent-run/max-attempts "three"})]
          (is (= [] (selvage/check (:id clean))))
          (is (= [{:strand-id (:id bad)
                   :checkset checkset
                   :attr "agent-run/phase"
                   :check :enum
                   :value "bogus"}
                  {:strand-id (:id bad)
                   :checkset checkset
                   :attr "agent-run/max-attempts"
                   :check :type
                   :value "three"}]
                 (mapv #(select-keys % [:strand-id :checkset :attr :check :value])
                       (selvage/check (:id bad))))))))))

(deftest check-fails-loudly-for-missing-strand-id
  (with-runtime
    (fn [_rt _]
      (clear-checksets!)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Strand not found"
                            (selvage/check "missing-strand"))))))

(deftest check-all-can-be-scoped-by-query-form
  (with-runtime
    (fn [_rt _]
      (clear-checksets!)
      (let [checkset (unique-checkset)]
        (selvage/register-checkset! checkset (shuttle-spec))
        (repl/strand! "Bad in scope" {:owner "agent" :agent-run/phase "bogus"})
        (repl/strand! "Bad out of scope" {:owner "human" :agent-run/phase "bogus"})
        (is (= ["agent"]
               (mapv #(get-in (repl/strand (:strand-id %)) [:attributes :owner])
                     (selvage/check-all [:= [:attr :owner] "agent"]))))))))

(deftest undeclared-checks-cross-references-the-ownership-registry
  (with-runtime
    (fn [rt _]
      (clear-checksets!)
      (vocab/declare! rt {:kind :attr-namespace :name "agent-run"
                          :owner :selvage-test/init :doc "agent-run attributes"})
      (let [checkset-name (unique-checkset)]
        ;; "agent-run/*" is now declared; "frobnicate/*" and bare "verify-note"
        ;; are owned by nobody, so their checks surface as undeclared.
        (selvage/register-checkset! checkset-name
                                    {:checks [{:attr "agent-run/phase" :enum ["pending" "done"]}
                                              {:attr "frobnicate/level" :type :int-string}
                                              {:attr "verify-note" :type :string}]})
        (is (= [{:checkset checkset-name :attr "frobnicate/level" :namespace "frobnicate"}
                {:checkset checkset-name :attr "verify-note" :namespace "verify-note"}]
               (mapv #(select-keys % [:checkset :attr :namespace])
                     (selvage/undeclared-checks))))
        (testing "declaring the missing namespace clears its checks"
          (vocab/declare! rt {:kind :attr-namespace :name "frobnicate"
                              :owner :selvage-test/init :doc "frobnicate attributes"})
          (is (= ["verify-note"]
                 (mapv :attr (selvage/undeclared-checks)))))))))

(deftest watch-records-and-clears-violations
  (with-runtime
    (fn [_rt _]
      (clear-checksets!)
      (let [checkset (unique-checkset)
            strand (repl/strand! "Watch target" {:agent-run/phase "pending"})]
        (selvage/register-checkset! checkset {:checks [{:attr "agent-run/phase" :enum ["pending"]}]})
        (selvage/clear-violations!)
        (selvage/install!)
        (repl/update! (:id strand) {:attributes {:agent-run/phase "failed"}})
        (Thread/sleep 250)
        (is (= [{:strand-id (:id strand)
                 :checkset checkset
                 :attr "agent-run/phase"
                 :check :enum
                 :value "failed"}]
               (mapv #(select-keys % [:strand-id :checkset :attr :check :value])
                     (selvage/violations))))
        (is (= {:cleared true} (selvage/clear-violations!)))
        (is (= [] (selvage/violations)))))))

(deftest register-checkset-replaces-existing-checkset
  (with-runtime
    (fn [_rt _]
      (clear-checksets!)
      (let [checkset (unique-checkset)
            strand (repl/strand! "Replace target" {:agent-run/phase "running"})]
        (selvage/register-checkset! checkset {:checks [{:attr "agent-run/phase" :enum ["pending"]}]})
        (is (= [:enum] (mapv :check (selvage/check (:id strand)))))
        (selvage/register-checkset! checkset {:checks [{:attr "agent-run/phase" :enum ["running"]}]})
        (is (= [] (selvage/check (:id strand))))
        (is (= {:unregistered checkset} (selvage/unregister-checkset! checkset)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not registered"
                              (selvage/unregister-checkset! checkset)))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for selvage's versioned spool-state: a key added to new-state
  ;; without a state-version bump would survive reload! as a stale map.
  (assert-state-shape #'selvage/new-state #{:checkset-registry :violation-log}))
