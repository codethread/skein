(ns skein.treadle-test
  "Tests for the treadle workflow-gate to shuttle-run adapter."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.spools.shuttle :as shuttle]
            [skein.spools.treadle :as treadle]
            [skein.spools.workflow :as workflow]
            [skein.spools.test-support :refer [with-runtime]]
            [skein.core.db :as db]
            [skein.api.weaver.alpha :as api]))

(defn- with-treadle [f]
  (with-runtime
    (fn [rt _]
      (shuttle/install!)
      (shuttle/defharness! :sh-tail {:argv ["sh" "-c" "tail -n 1 | sh"]
                                      :prompt-via :stdin
                                      :parse :raw
                                      :preamble? false
                                      :doc "Test harness that executes only the final prompt line."})
      (treadle/install!)
      (f rt))))

(defn- await-eventually [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (pred)]
        (cond
          v v
          (> (System/currentTimeMillis) deadline) (throw (ex-info "Timed out" {}))
          :else (do (Thread/sleep 50) (recur)))))))

(defn- attr [strand k]
  (get-in strand [:attributes k]))

(defn- workflow-with-gate [gate-attrs]
  (workflow/workflow
    "Treadle test"
    (workflow/step :first "First")
    (workflow/gate :delegate "Delegate work" :subagent
                   :depends-on [:first]
                   :attributes gate-attrs)
    (workflow/step :after "After" :depends-on [:delegate])))

(defn- run-for-gate [rt gate-id]
  (first (api/list rt [:= [:attr "treadle/gate"] gate-id] {})))

(defn- ready-subagent-gate [run-id]
  (first (filter #(= "subagent" (:gate %)) (workflow/next-steps run-id))))

(defn- edge-row [rt from-id to-id type]
  (db/execute-one! (:datasource rt)
                   ["SELECT from_strand_id, to_strand_id, edge_type
                       FROM strand_edges
                      WHERE from_strand_id = ?
                        AND to_strand_id = ?
                        AND edge_type = ?"
                    from-id to-id type]))

(deftest happy-path-spawns-delivers-and-unblocks-next-step
  (with-treadle
    (fn [rt]
      (workflow/start! "happy" (workflow-with-gate {"shuttle/harness" "sh-tail"
                                                     "shuttle/prompt" "echo gate-result"}) {})
      (let [[first-step] (workflow/next-steps "happy")]
        (workflow/complete! "happy" {:step (:id first-step)}))
      (let [gate-id (:id (ready-subagent-gate "happy"))
            run (await-eventually #(some-> (run-for-gate rt gate-id)
                                           ((fn [r] (when (= "closed" (:state (api/show rt (:id r))))
                                                      (api/show rt (:id r))))))
                                  10000)
            gate (api/show rt (attr run :treadle/gate))
            after (first (workflow/next-steps "happy"))]
        (is (= "true" (attr (api/show rt (:id run)) :treadle/delivered)))
        (is (= (:id run) (attr gate :workflow/outcome-by)))
        (is (= "gate-result" (attr gate :workflow/notes)))
        (is (= "happy" (attr run :treadle/run-id)))
        (is (= "After" (:title after)))
        (is (= {:from_strand_id (:id gate)
                :to_strand_id (:id run)
                :edge_type "delegates"}
               (edge-row rt (:id gate) (:id run) "delegates")))
        (is (nil? (edge-row rt (:id gate) (:id run) "parent-of")))))))

(deftest blocked-gate-spawns-only-after-blocker-closes
  (with-treadle
    (fn [rt]
      (let [definition (workflow/workflow
                         "Blocked"
                         (workflow/step :blocker "Blocker")
                         (workflow/gate :delegate "Delegate" :subagent
                                        :depends-on [:blocker]
                                        :attributes {"shuttle/harness" "sh-tail"
                                                     "shuttle/prompt" "echo later"}))]
        (workflow/start! "blocked" definition {})
        (Thread/sleep 250)
        (let [gate-id (:id (first (api/list rt [:= :title "Delegate"] {})))]
          (is (some? gate-id))
          (is (nil? (run-for-gate rt gate-id)))
          (let [[blocker] (workflow/next-steps "blocked")]
            (workflow/complete! "blocked" {:step (:id blocker)}))
          (is (some? (await-eventually #(run-for-gate rt gate-id) 10000))))))))

(deftest missing-harness-stamps-error-and-does-not-retry
  (with-treadle
    (fn [rt]
      (workflow/start! "missing" (workflow/workflow
                                    "Missing"
                                    (workflow/gate :delegate "Delegate" :subagent
                                                   :attributes {"shuttle/prompt" "echo no"})) {})
      (let [gate-id (:id (ready-subagent-gate "missing"))
            gate (await-eventually #(let [g (api/show rt gate-id)] (when (attr g :treadle/error) g)) 10000)]
        (is (str/includes? (attr gate :treadle/error) "shuttle/harness"))
        (is (nil? (run-for-gate rt gate-id)))
        (api/add rt {:title "unrelated"})
        (Thread/sleep 250)
        (is (nil? (run-for-gate rt gate-id)))))))

(deftest failed-run-stays-ready-and-clearing-stamp-spawns-fresh-run
  (with-treadle
    (fn [rt]
      (workflow/start! "retry" (workflow/workflow
                                  "Retry"
                                  (workflow/gate :delegate "Delegate" :subagent
                                                 :attributes {"shuttle/harness" "sh-tail"
                                                              "shuttle/prompt" "exit 7"})
                                  (workflow/step :after "After" :depends-on [:delegate])) {})
      (let [gate-id (:id (ready-subagent-gate "retry"))
            failed (await-eventually #(when-let [r (run-for-gate rt gate-id)]
                                        (let [shown (api/show rt (:id r))]
                                          (when (= "failed" (attr shown :shuttle/phase)) shown))) 10000)]
        (is (= "active" (:state failed)))
        (is (= gate-id (attr failed :treadle/gate)))
        (is (= [gate-id] (mapv :id (filter #(= "subagent" (:gate %)) (workflow/next-steps "retry")))))
        (api/update rt gate-id {:attributes {"treadle/run" nil
                                             "shuttle/prompt" "echo recovered"}})
        (let [fresh (await-eventually #(some (fn [r]
                                                (when (not= (:id failed) (:id r)) r))
                                              (api/list rt [:= [:attr "treadle/gate"] gate-id] {}))
                                      10000)]
          (is (= "recovered" (attr (await-eventually #(let [r (api/show rt (:id fresh))]
                                             (when (= "closed" (:state r)) r)) 10000)
                                  :shuttle/result))))))))

(deftest routed-away-gate-marks-run-gate-closed
  (with-treadle
    (fn [rt]
      (workflow/start! "routed" (workflow/workflow
                                   "Routed"
                                   (workflow/gate :delegate "Delegate" :subagent
                                                  :attributes {"shuttle/harness" "sh-tail"
                                                               "shuttle/prompt" "sleep 1; echo too-late"})) {})
      (let [gate-id (:id (ready-subagent-gate "routed"))
            run (await-eventually #(run-for-gate rt gate-id) 10000)]
        (api/update rt gate-id {:state "closed"})
        (let [done (await-eventually #(let [r (api/show rt (:id run))]
                             (when (attr r :treadle/delivered) r)) 10000)]
          (is (= "gate-closed" (attr done :treadle/delivered))))))))

(deftest unready-gate-blocks-delivery-until-ready-again
  (with-treadle
    (fn [rt]
      (workflow/start! "held" (workflow/workflow
                                "Held"
                                (workflow/gate :delegate "Delegate" :subagent
                                               :attributes {"shuttle/harness" "sh-tail"
                                                            "shuttle/prompt" "sleep 1; echo held-result"})) {})
      (let [gate-id (:id (ready-subagent-gate "held"))
            run (await-eventually #(run-for-gate rt gate-id) 10000)
            hold (api/add rt {:title "Hold delivery"})]
        ;; un-ready the gate while its run is in flight: delivery must park
        ;; loudly (write-once treadle/delivery-blocked), not stamp terminal
        ;; state or spin
        (api/update rt gate-id {:edges [{:type "depends-on" :to (:id hold)}]})
        (let [blocked (await-eventually #(let [r (api/show rt (:id run))]
                                           (when (attr r :treadle/delivery-blocked) r)) 10000)]
          (is (= "closed" (:state blocked)))
          (is (nil? (attr blocked :treadle/delivered))))
        (api/update rt (:id hold) {:state "closed"})
        (let [delivered (await-eventually #(let [r (api/show rt (:id run))]
                                             (when (attr r :treadle/delivered) r)) 10000)]
          (is (= "true" (attr delivered :treadle/delivered)))
          (is (= "held-result" (attr (api/show rt gate-id) :workflow/notes))))))))

(deftest treadle-registers-stall-predicate-for-flow-await
  (with-treadle
    (fn [_]
      (workflow/start! "await-treadle" (workflow/workflow
                                          "Await treadle"
                                          (workflow/gate :delegate "Delegate" :subagent
                                                         :attributes {"shuttle/prompt" "echo no"})) {})
      (let [result (workflow/await! "await-treadle" {:timeout-secs 10
                                                     :stall-predicate :treadle})]
        (is (= :stalled (:reason result)))
        (is (str/includes? (get-in result [:detail :stall :error]) "shuttle/harness"))))))

(deftest stalled-gates-query-reports-only-spawn-errors
  (with-treadle
    (fn [rt]
      (workflow/start! "query-running" (workflow/workflow
                                          "Query running"
                                          (workflow/gate :delegate "Running delegate" :subagent
                                                         :attributes {"shuttle/harness" "sh-tail"
                                                                      "shuttle/prompt" "sleep 1; echo ok"})) {})
      (let [running-gate-id (:id (ready-subagent-gate "query-running"))
            running-run-id (:id (await-eventually #(run-for-gate rt running-gate-id) 10000))]
        (await-eventually #(= "running" (attr (api/show rt running-run-id) :shuttle/phase)) 10000)
        (is (empty? (filter #(= running-gate-id (:id %))
                            (api/list-query rt 'stalled-gates {}))))
        (workflow/start! "query-error" (workflow/workflow
                                          "Query error"
                                          (workflow/gate :delegate "Broken delegate" :subagent
                                                         :attributes {"shuttle/prompt" "echo no"})) {})
        (let [error-gate-id (:id (ready-subagent-gate "query-error"))]
          (await-eventually #(attr (api/show rt error-gate-id) :treadle/error) 10000)
          (is (= [error-gate-id]
                 (mapv :id (api/list-query rt 'stalled-gates {}))))
          (await-eventually #(= "closed" (:state (api/show rt running-run-id))) 10000))))))

(deftest non-subagent-gates-are-ignored
  (with-treadle
    (fn [rt]
      (workflow/start! "ci" (workflow/workflow
                               "CI"
                               (workflow/gate :ci "Wait for CI" :ci
                                              :attributes {"shuttle/harness" "sh-tail"
                                                           "shuttle/prompt" "echo ignored"})) {})
      (let [gate-id (:id (first (workflow/next-steps "ci")))]
        (Thread/sleep 300)
        (is (nil? (run-for-gate rt gate-id)))
        (is (nil? (attr (api/show rt gate-id) :treadle/run)))))))
