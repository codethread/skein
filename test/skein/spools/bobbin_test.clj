(ns skein.spools.bobbin-test
  "Contract tests for the bobbin context-pack reference spool."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skein.api.notes.alpha :as notes]
            [skein.repl :as repl]
            [skein.spools.bobbin :as bobbin]
            [skein.spools.test-support :refer [with-runtime]]))

(defn- ids [section]
  (set (map :id (:strands section))))

(defn- edge-triples [section]
  (set (map (juxt :from_strand_id :to_strand_id :edge_type) (:edges section))))

(deftest pack-projects-small-context-graph
  (with-runtime
    (fn [_rt _]
      (let [root (repl/strand! "Feature")
            target (repl/strand! "Implement" {:body "Do the full implementation.\nKeep it tested."})
            blocker (repl/strand! "Design")
            nested (repl/strand! "Research")
            dependent (repl/strand! "Review")
            child (repl/strand! "Subtask")
            note (repl/strand! "Note" {:note/text "remember this"
                                       :note/by "agent"
                                       :note/at "2026-07-02T00:00:00Z"})]
        (repl/update! (:id root) {:edges [{:type "parent-of" :to (:id target)}]})
        (repl/update! (:id target) {:edges [{:type "depends-on" :to (:id blocker)}
                                            {:type "parent-of" :to (:id child)}]})
        ;; note --notes--> target, the catalog's published direction
        (repl/update! (:id note) {:edges [{:type "notes" :to (:id target)}]})
        (repl/update! (:id blocker) {:edges [{:type "depends-on" :to (:id nested)}]})
        (repl/update! (:id dependent) {:edges [{:type "depends-on" :to (:id target)}]})
        (let [bundle (bobbin/pack (:id target))]
          (is (= (:id target) (get-in bundle [:strand :id])))
          (is (= #{(:id blocker) (:id nested)} (ids (:blockers bundle))))
          (is (contains? (edge-triples (:blockers bundle)) [(:id blocker) (:id nested) "depends-on"]))
          (is (= #{(:id dependent)} (ids (:dependents bundle))))
          (is (= #{(:id root)} (ids (:parents bundle))))
          (is (= #{(:id child)} (ids (:children bundle))))
          (is (= [(:id note)] (mapv :id (get-in bundle [:notes :strands])))))))))

(deftest notes-section-reads-the-notes-primitive
  (with-runtime
    (fn [rt _]
      (let [target (repl/strand! "Target")
            ;; note/at is write-once and stamped by note!, so the burst is written
            ;; through the primitive and its real timestamps drive the assertion.
            first-id (:id (notes/note! rt (:id target) "first" {:by "agent"}))
            second-id (:id (notes/note! rt (:id target) "second" {:by "agent"}))
            third-id (:id (notes/note! rt (:id target) "third" {:by "agent"}))
            packed (mapv :id (get-in (bobbin/pack (:id target)) [:notes :strands]))]
        ;; notes! writes note --notes--> target; a pack that walked the edge the
        ;; other way would see no notes at all.
        (is (= [first-id second-id third-id] packed))
        (is (= (mapv :id (notes/notes rt (:id target) {})) packed))))))

(deftest notes-section-orders-mixed-precision-timestamps-chronologically
  (with-runtime
    (fn [_rt _]
      (let [target (repl/strand! "Target")
            ;; Instant/toString omits a zero fraction, so '.' (46) meets 'Z' (90)
            ;; one char past the seconds: these two sort lexicographically in the
            ;; reverse of their chronological order.
            earlier (repl/strand! "Earlier" {:note/text "earlier" :note/at "2026-07-02T00:00:01Z"})
            later (repl/strand! "Later" {:note/text "later" :note/at "2026-07-02T00:00:01.5Z"})]
        (repl/update! (:id later) {:edges [{:type "notes" :to (:id target)}]})
        (repl/update! (:id earlier) {:edges [{:type "notes" :to (:id target)}]})
        (is (= [(:id earlier) (:id later)]
               (mapv :id (get-in (bobbin/pack (:id target)) [:notes :strands]))))))))

(deftest pack-section-edges-are-self-contained
  (with-runtime
    (fn [_rt _]
      (let [target (repl/strand! "Target")
            active-blocker (repl/strand! "Active blocker")
            closed-blocker (repl/strand! "Closed blocker" {} {:state "closed"})]
        (repl/update! (:id target) {:edges [{:type "depends-on" :to (:id active-blocker)}
                                            {:type "depends-on" :to (:id closed-blocker)}]})
        (let [blockers (:blockers (bobbin/pack (:id target)))
              packed-ids (ids blockers)]
          (is (= #{(:id active-blocker)} packed-ids))
          (is (every? #(and (contains? packed-ids (:from_strand_id %))
                            (contains? packed-ids (:to_strand_id %)))
                      (:edges blockers))))))))

(deftest include-selection-and-failure-are-explicit
  (with-runtime
    (fn [_rt _]
      (let [target (repl/strand! "Target")]
        (is (= #{:bobbin/version :include :strand :notes}
               (set (keys (bobbin/pack (:id target) {:include #{:strand :notes}})))))
        (try
          (bobbin/pack (:id target) {:include #{:strand :bogus}})
          (is false "expected unknown section failure")
          (catch clojure.lang.ExceptionInfo e
            (is (= [:bogus] (:unknown (ex-data e))))
            (is (contains? (:allowed (ex-data e)) :blockers))))))))

(deftest missing-strand-id-fails-loudly
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Bobbin target strand was not found"
                            (bobbin/pack "missing-strand"))))))

(deftest render-is-deterministic-and-includes-target-body
  (with-runtime
    (fn [_rt _]
      (let [target (repl/strand! "Target" {:body "Line one\nLine two" :owner "agent"})
            blocker (repl/strand! "Blocker")]
        (repl/update! (:id target) {:edges [{:type "depends-on" :to (:id blocker)}]})
        (let [bundle (bobbin/pack (:id target))
              rendered (bobbin/render bundle)]
          (is (= rendered (bobbin/render bundle)))
          (is (str/includes? rendered "Line one\nLine two"))
          (is (str/includes? rendered (:id blocker))))))))

(deftest workflow-section-appears-for-workflow-strands
  (with-runtime
    (fn [_rt _]
      (let [root (repl/strand! "Run" {"workflow/run-id" "run-1"
                                      "workflow/role" "root"})
            target (repl/strand! "Step" {"workflow/run-id" "run-1"
                                         "workflow/role" "step"})]
        (repl/update! (:id root) {:edges [{:type "parent-of" :to (:id target)}]})
        (let [workflow (:workflow (bobbin/pack (:id target)))]
          (is (= "run-1" (:run-id workflow)))
          (is (= "step" (:role workflow)))
          (is (= (:id root) (get-in workflow [:root :id]))))))))

(deftest workflow-section-resolves-a-closed-historical-root
  (with-runtime
    (fn [_rt _]
      ;; Packing a finished target to brief a reviewer is a first-class use, and
      ;; a finished run's root is closed — invisible to workflow/current-root.
      (let [root (repl/strand! "Run" {"workflow/run-id" "run-2"
                                      "workflow/role" "root"} {:state "closed"})
            target (repl/strand! "Step" {"workflow/run-id" "run-2"
                                         "workflow/role" "step"})]
        (repl/update! (:id root) {:edges [{:type "parent-of" :to (:id target)}]})
        (is (= (:id root) (get-in (bobbin/pack (:id target)) [:workflow :root :id])))))))

(deftest install-reports-public-surface
  (is (= {'pack 'skein.spools.bobbin/pack
          'render 'skein.spools.bobbin/render}
         (:fns (bobbin/install!)))))
