(ns skein.spools.loom-test
  "Tests for the read-only work-graph projection spool."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.repl :as repl]
            [skein.spools.loom :as loom]
            [skein.spools.test-support :refer [with-runtime]]))

(deftest work-dags-projects-parent-of-roots-with-dependency-edges
  (with-runtime
    (fn [rt _]
      (let [root (repl/strand! "Root")
            a (repl/strand! "Child A")
            b (repl/strand! "Child B")]
        (repl/update! (:id root) {:edges [{:type "parent-of" :to (:id a)}
                                          {:type "parent-of" :to (:id b)}]})
        (repl/update! (:id a) {:edges [{:type "depends-on" :to (:id b)}]})
        (let [{:keys [roots dags]} (loom/work-dags rt)
              dag (first (filter #(= (:id root) (get-in % [:root :id])) dags))]
          (is (contains? (set roots) (:id root)))
          (is (= #{(:id root) (:id a) (:id b)} (set (map :id (:strands dag)))))
          (is (= 2 (count (:parent_of_edges dag))))
          (is (= [[(:id a) (:id b)]]
                 (mapv (juxt :from_strand_id :to_strand_id) (:depends_on_edges dag)))))))))

(deftest branch-views-group-stamped-roots-and-join-ready-frontier
  (with-runtime
    (fn [rt _]
      (let [root (repl/strand! "Feature root" {"branch" "feat-x" "owner" "me"})
            child (repl/strand! "Child work")
            other (repl/strand! "Other branch" {"branch" "feat-y"})]
        (repl/update! (:id root) {:edges [{:type "parent-of" :to (:id child)}]})
        (let [views (loom/branch-views rt {:ready-query [:= :state "active"]})
              feat-x (first (filter #(= "feat-x" (:branch %)) views))
              root-view (first (:roots feat-x))]
          (is (= ["feat-x" "feat-y"] (mapv :branch views)))
          (is (= (:id root) (get-in root-view [:root :id])))
          (is (= [(:id child)] (mapv :id (:active_descendants root-view))))
          (is (= #{(:id root) (:id child)} (set (map :id (:ready root-view)))))
          (testing "a parent-of child stamped with a branch is not a branch root"
            (repl/update! (:id root) {:edges [{:type "parent-of" :to (:id other)}]})
            (repl/update! (:id other) {:attributes {"branch" "feat-x"}})
            (let [rescoped (loom/branch-views rt {:ready-query [:= :state "active"] :branch "feat-x"})]
              (is (= 1 (count (:roots (first rescoped)))))
              (is (= (:id root) (get-in (first rescoped) [:roots 0 :root :id])))))
          (testing "scoping to an unstamped branch yields no views"
            (is (empty? (loom/branch-views rt {:ready-query [:= :state "active"] :branch "feat-none"})))))))))

(deftest branch-views-require-a-ready-query
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a :ready-query"
                            (loom/branch-views rt {:branch-attr :branch}))))))

(deftest gate-chain-mermaid-marks-ready-stalled-and-closed
  (let [gates [{:id "g0" :title "Alpha" :state "active" :stalled? true}
               {:id "g1" :title "Beta" :state "active"}
               {:id "g2" :title "Gamma" :state "closed"}]
        chart (loom/gate-chain-mermaid gates #{"g1"})]
    (is (= (str "flowchart LR\n"
                "  G0[\"Alpha (stalled)\"]\n"
                "  G1[\"Beta (ready)\"]\n"
                "  G2[\"Gamma (closed)\"]\n"
                "  G0 --> G1\n"
                "  G1 --> G2")
           chart))))
