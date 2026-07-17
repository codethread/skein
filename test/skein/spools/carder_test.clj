(ns skein.spools.carder-test
  "Tests for the read-only carder graph hygiene spool."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [skein.api.vocab.alpha :as vocab]
            [skein.repl :as repl]
            [skein.spools.carder :as carder]
            [skein.spools.test-support :refer [with-runtime]]))

(defn- set-updated-at! [rt id timestamp]
  (jdbc/execute! (:datasource rt)
                 ["UPDATE strands SET updated_at = ? WHERE id = ?" timestamp id]))

(deftest stale-detects-doctored-updated-at-and-validates-options
  (with-runtime
    (fn [rt _]
      (let [old (repl/strand! "Old work")
            fresh (repl/strand! "Fresh work")]
        (set-updated-at! rt (:id old) "2026-01-01 00:00:00")
        (is (= [(:id old)] (mapv :id (carder/stale {:days 1}))))
        (is (pos-int? (:days-stale (first (carder/stale {:days 1})))))
        (is (not-any? #{(:id fresh)} (map :id (carder/stale {:days 1}))))
        (doseq [bad [0 -1 1.5 "14" nil]]
          (testing (pr-str bad)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #":days must be a positive integer"
                                  (carder/stale {:days bad})))))))))

(deftest orphans-require-no-edges-and-no-workflow-attributes
  (with-runtime
    (fn [_rt _]
      (let [orphan (repl/strand! "Loose strand")
            parent (repl/strand! "Parent")
            child (repl/strand! "Child")
            workflow-carrier (repl/strand! "Workflow metadata only" {"workflow/role" "step"})]
        (repl/update! (:id parent) {:edges [{:type "references" :to (:id child)}]})
        (let [ids (set (map :id (carder/orphans)))]
          (is (contains? ids (:id orphan)))
          (is (not (contains? ids (:id parent))))
          (is (not (contains? ids (:id child))))
          (is (not (contains? ids (:id workflow-carrier)))))))))

(deftest blocked-by-failure-reports-failed-blocker-details
  (with-runtime
    (fn [_rt _]
      (let [blocked (repl/strand! "Blocked work")
            ok-blocked (repl/strand! "Blocked by nonfailed")
            failed (repl/strand! "Failed run" {"agent-run/run" "true" "agent-run/phase" "failed" "agent-run/error" "boom"})
            exhausted (repl/strand! "Exhausted run" {"agent-run/run" "true" "agent-run/phase" "exhausted"})
            running (repl/strand! "Running run" {"agent-run/run" "true" "agent-run/phase" "running"})
            phase-only (repl/strand! "Not a run record" {"agent-run/phase" "failed"})]
        (repl/update! (:id blocked) {:edges [{:type "depends-on" :to (:id failed)}
                                             {:type "depends-on" :to (:id exhausted)}]})
        (repl/update! (:id ok-blocked) {:edges [{:type "depends-on" :to (:id running)}
                                                {:type "depends-on" :to (:id phase-only)}]})
        (let [rows (carder/blocked-by-failure)
              row (first (filter #(= (:id blocked) (:id %)) rows))]
          (testing "a blocker counts only when it is an agent-run record"
            (is (= [(:id blocked)] (mapv :id rows))))
          (is (= #{(:id failed) (:id exhausted)} (set (map :id (:blockers row)))))
          (is (= "boom" (some #(when (= (:id failed) (:id %)) (:agent-run/error %)) (:blockers row)))))))))

(deftest report-aggregates-and-applies-default-exclusions
  (with-runtime
    (fn [rt _]
      (let [stale-work (repl/strand! "Stale work")
            workflow-root (repl/strand! "Workflow root" {"workflow/role" "root"})
            shuttle-run (repl/strand! "Run record" {"agent-run/run" "true"})]
        (set-updated-at! rt (:id stale-work) "2026-01-01 00:00:00")
        (set-updated-at! rt (:id workflow-root) "2026-01-01 00:00:00")
        (set-updated-at! rt (:id shuttle-run) "2026-01-01 00:00:00")
        (let [default-report (carder/report {:days 1})
              included-report (carder/report {:days 1 :include-plumbing? true})]
          (is (= 1 (get-in default-report [:stale :count])))
          (is (= [(:id stale-work)] (mapv :id (get-in default-report [:stale :rows]))))
          (is (= 3 (get-in included-report [:stale :count])))
          (is (= {:days 1 :include-plumbing? true} (:opts included-report)))
          (is (contains? default-report :orphans))
          (is (contains? default-report :blocked-by-failure)))))))

(deftest undeclared-flags-strays-by-namespace-not-exact-key
  (with-runtime
    (fn [rt _]
      (vocab/declare! rt {:kind :attr-namespace
                          :name "review"
                          :owner :carder-test
                          :doc "Declared for the false-positive-avoidance proof."})
      (let [clean (repl/strand! "New key under declared namespace" {"review/newfield" "ok"})
            bare (repl/strand! "Bare unowned key" {"verify-note" "stray"})
            unowned (repl/strand! "Unowned namespace" {"frobnicate/flag" "stray"})
            plain (repl/strand! "No namespaced attributes")
            by-id (into {} (map (juxt :id identity)) (carder/undeclared))]
        (testing "a fresh key under a declared namespace is clean (R3)"
          (is (not (contains? by-id (:id clean)))))
        (testing "a bare unowned attribute is flagged by its missing namespace"
          (is (= ["verify-note"] (:undeclared-attrs (by-id (:id bare))))))
        (testing "an unowned namespace is flagged"
          (is (= ["frobnicate/flag"] (:undeclared-attrs (by-id (:id unowned))))))
        (testing "a strand with no namespaced attributes is clean"
          (is (not (contains? by-id (:id plain)))))
        (testing "report carries the undeclared section without blocking a write"
          (let [section (:undeclared (carder/report))]
            (is (= #{(:id bare) (:id unowned)}
                   (set (map :id (:rows section)))))
            (is (= (count (:rows section)) (:count section)))))))))

(deftest report-rejects-unknown-options
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":report received unknown keys"
                            (carder/report {:days 1 :surprise true}))))))
