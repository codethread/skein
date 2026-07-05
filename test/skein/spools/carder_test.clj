(ns skein.spools.carder-test
  "Tests for the read-only carder graph hygiene spool."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
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
            failed (repl/strand! "Failed run" {"shuttle/phase" "failed" "shuttle/error" "boom"})
            exhausted (repl/strand! "Exhausted run" {"shuttle/phase" "exhausted"})
            running (repl/strand! "Running run" {"shuttle/phase" "running"})]
        (repl/update! (:id blocked) {:edges [{:type "depends-on" :to (:id failed)}
                                             {:type "depends-on" :to (:id exhausted)}]})
        (repl/update! (:id ok-blocked) {:edges [{:type "depends-on" :to (:id running)}]})
        (let [rows (carder/blocked-by-failure)
              row (first (filter #(= (:id blocked) (:id %)) rows))]
          (is (= [(:id blocked)] (mapv :id rows)))
          (is (= #{(:id failed) (:id exhausted)} (set (map :id (:blockers row)))))
          (is (= "boom" (some #(when (= (:id failed) (:id %)) (:shuttle/error %)) (:blockers row)))))))))

(deftest report-aggregates-and-applies-default-exclusions
  (with-runtime
    (fn [rt _]
      (let [stale-work (repl/strand! "Stale work")
            workflow-root (repl/strand! "Workflow root" {"workflow/role" "molecule"})
            shuttle-run (repl/strand! "Run record" {"shuttle/run" "true"})]
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

(deftest report-rejects-unknown-options
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown carder option keys"
                            (carder/report {:days 1 :surprise true}))))))
