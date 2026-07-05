(ns skein.treadle-test
  "Tests for the treadle workflow-gate to shuttle-run adapter."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.spools.shuttle :as shuttle]
            [skein.spools.treadle :as treadle]
            [skein.spools.agents :as agents]
            [skein.spools.workflow :as workflow]
            [skein.spools.test-support :as test-support :refer [with-runtime]]
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

(defn- await-eventually
  ([pred] (await-eventually pred (test-support/await-budget-ms)))
  ([pred timeout-ms]
   (test-support/poll-until pred
                            {:timeout-ms timeout-ms
                             :on-timeout #(throw (ex-info "Timed out" {}))})))

(defn- attr [strand k]
  (get-in strand [:attributes k]))

(defn- workflow-with-gate [gate-attrs]
  (workflow/workflow
    "Treadle test"
    (workflow/step :first "First" :self)
    (workflow/gate :delegate "Delegate work" :subagent
                   :depends-on [:first]
                   :attributes gate-attrs)
    (workflow/step :after "After" :self :depends-on [:delegate])))

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
                                                      (api/show rt (:id r)))))))
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
                         (workflow/step :blocker "Blocker" :self)
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
          (is (some? (await-eventually #(run-for-gate rt gate-id)))))))))

(deftest missing-harness-stamps-error-and-does-not-retry
  (with-treadle
    (fn [rt]
      (workflow/start! "missing" (workflow/workflow
                                    "Missing"
                                    (workflow/gate :delegate "Delegate" :subagent
                                                   :attributes {"shuttle/prompt" "echo no"})) {})
      (let [gate-id (:id (ready-subagent-gate "missing"))
            gate (await-eventually #(let [g (api/show rt gate-id)] (when (attr g :treadle/error) g)))]
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
                                  (workflow/step :after "After" :self :depends-on [:delegate])) {})
      (let [gate-id (:id (ready-subagent-gate "retry"))
            failed (await-eventually #(when-let [r (run-for-gate rt gate-id)]
                                        (let [shown (api/show rt (:id r))]
                                          (when (= "failed" (attr shown :shuttle/phase)) shown))))]
        (is (= "active" (:state failed)))
        (is (= gate-id (attr failed :treadle/gate)))
        (is (= [gate-id] (mapv :id (filter #(= "subagent" (:gate %)) (workflow/next-steps "retry")))))
        (api/update rt gate-id {:attributes {"treadle/run" nil
                                             "shuttle/prompt" "echo recovered"}})
        (let [fresh (await-eventually #(some (fn [r]
                                                (when (not= (:id failed) (:id r)) r))
                                              (api/list rt [:= [:attr "treadle/gate"] gate-id] {})))]
          (is (= "recovered" (attr (await-eventually #(let [r (api/show rt (:id fresh))]
                                             (when (= "closed" (:state r)) r)))
                                  :shuttle/result))))))))

(deftest blank-result-gate-fails-loudly-stays-discoverable-and-recovers
  (with-treadle
    (fn [rt]
      ;; agents install registers the `agent-failures` query the failed run
      ;; must surface through.
      (agents/install!)
      (workflow/start! "blank" (workflow/workflow
                                 "Blank"
                                 (workflow/gate :delegate "Delegate" :subagent
                                                ;; exit 0 with empty stdout: a silently
                                                ;; dead worker that must not satisfy the gate.
                                                :attributes {"shuttle/harness" "sh-tail"
                                                             "shuttle/prompt" "true"})
                                 (workflow/step :after "After" :self :depends-on [:delegate])) {})
      (let [gate-id (:id (ready-subagent-gate "blank"))
            failed (await-eventually #(when-let [r (run-for-gate rt gate-id)]
                                        (let [shown (api/show rt (:id r))]
                                          (when (= "failed" (attr shown :shuttle/phase)) shown))))]
        ;; (a) the blank-result run is recorded failed, loud, and stays active
        (is (= "active" (:state failed)))
        (is (str/includes? (attr failed :shuttle/error) "empty result"))
        (is (= gate-id (attr failed :treadle/gate)))
        ;; (b) the gate is never delivered and its downstream step stays blocked
        (is (nil? (attr failed :treadle/delivered)))
        (is (nil? (attr (api/show rt gate-id) :workflow/notes)))
        (is (= [gate-id] (mapv :id (filter #(= "subagent" (:gate %)) (workflow/next-steps "blank")))))
        ;; (c) discoverable: the run through agent-failures, the gate through both
        ;; the stall predicate and the stalled-gates coordinator query.
        (is (some #(= (:id failed) (:id %)) (api/list-query rt 'agent-failures {})))
        (is (= "failed" (:phase (treadle/gate-stalled? (ready-subagent-gate "blank")))))
        (is (some #(= gate-id (:id %)) (api/list-query rt 'stalled-gates {})))
        ;; (d) recover by clearing the gate's run stamp: the treadle respawns a fresh run
        (api/update rt gate-id {:attributes {"treadle/run" nil
                                             "shuttle/prompt" "echo recovered"}})
        (let [fresh (await-eventually #(some (fn [r] (when (not= (:id failed) (:id r)) r))
                                             (api/list rt [:= [:attr "treadle/gate"] gate-id] {})))
              delivered (await-eventually #(let [r (api/show rt (:id fresh))]
                                             (when (attr r :treadle/delivered) r)))]
          (is (= "true" (attr delivered :treadle/delivered)))
          (is (= "recovered" (attr (api/show rt gate-id) :workflow/notes)))
          (is (= "After" (:title (first (workflow/next-steps "blank"))))))))))

(deftest recovery-respawn-keeps-stalled-gates-in-lockstep-with-predicate
  ;; A cleared-and-respawned gate leaves a stale `delegates` edge to its dead run.
  ;; The `stalled-gates` query follows that edge; `gate-stalled?` reads only the
  ;; current `treadle/run`. Repointing the dead run's provenance keeps the two in
  ;; lockstep: the gate stops surfacing the moment a healthy replacement is in flight.
  (with-treadle
    (fn [rt]
      (workflow/start! "lockstep" (workflow/workflow
                                    "Lockstep"
                                    (workflow/gate :delegate "Delegate" :subagent
                                                   ;; exit 0 with blank stdout -> a dead `failed` run
                                                   :attributes {"shuttle/harness" "sh-tail"
                                                                "shuttle/prompt" "true"})
                                    (workflow/step :after "After" :self :depends-on [:delegate])) {})
      (let [gate-id (:id (ready-subagent-gate "lockstep"))
            failed (await-eventually #(when-let [r (run-for-gate rt gate-id)]
                                        (let [shown (api/show rt (:id r))]
                                          (when (= "failed" (attr shown :shuttle/phase)) shown))))]
        ;; dead run: predicate and query agree the gate is stalled
        (is (= "failed" (:phase (treadle/gate-stalled? (ready-subagent-gate "lockstep")))))
        (is (some #(= gate-id (:id %)) (api/list-query rt 'stalled-gates {})))
        ;; recover by clearing the stamp; the treadle respawns a slow, healthy run
        (api/update rt gate-id {:attributes {"treadle/run" nil
                                             "shuttle/prompt" "sleep 3; echo recovered"}})
        (let [fresh (await-eventually #(some (fn [r] (when (not= (:id failed) (:id r)) r))
                                             (api/list rt [:= [:attr "treadle/gate"] gate-id] {})))]
          (await-eventually #(= (:id fresh) (attr (api/show rt gate-id) :treadle/run)))
          (await-eventually #(= "running" (attr (api/show rt (:id fresh)) :shuttle/phase)))
          ;; the dead run's provenance is repointed at the fresh run
          (is (= (:id fresh) (attr (api/show rt (:id failed)) :treadle/superseded-by)))
          (is (nil? (attr (api/show rt (:id fresh)) :treadle/superseded-by)))
          ;; lockstep while the replacement is healthy: neither surfaces the gate
          (is (nil? (treadle/gate-stalled? (ready-subagent-gate "lockstep"))))
          (is (empty? (filter #(= gate-id (:id %)) (api/list-query rt 'stalled-gates {}))))
          ;; let the healthy run finish so the gate delivers and teardown is clean
          (is (= "true" (attr (await-eventually #(let [r (api/show rt (:id fresh))]
                                                   (when (attr r :treadle/delivered) r))
                                                 (test-support/await-budget-ms 15000))
                              :treadle/delivered))))))))

(deftest agent-retry-of-blank-gate-run-supersedes-without-stale-delivery
  (with-treadle
    (fn [rt]
      (agents/install!)
      (workflow/start! "retry-blank" (workflow/workflow
                                       "Retry blank"
                                       (workflow/gate :delegate "Delegate" :subagent
                                                      :attributes {"shuttle/harness" "sh-tail"
                                                                   "shuttle/prompt" "true"})
                                       (workflow/step :after "After" :self :depends-on [:delegate])) {})
      (let [gate-id (:id (ready-subagent-gate "retry-blank"))
            failed (await-eventually #(when-let [r (run-for-gate rt gate-id)]
                                        (let [shown (api/show rt (:id r))]
                                          (when (= "failed" (attr shown :shuttle/phase)) shown))))
            retry (agents/agent-op {:op/argv ["retry" (:id failed)]})]
        ;; agent retry supersedes the dead run (closes it) and spawns a fresh run
        (is (= (:id failed) (:superseded retry)))
        (let [superseded (api/show rt (:id failed))]
          (is (= "superseded" (attr superseded :shuttle/phase)))
          (is (= "closed" (:state superseded))))
        ;; retry respawns with :parent = served-target, which for a treadle run
        ;; resolves to the gate (run-summary :for = treadle/gate). So the fresh
        ;; run gets a structural parent-of edge from the gate — but NOT a
        ;; treadle delegation (no delegates edge, no treadle/gate attr), and the
        ;; gate's treadle/run stamp is never rewritten. treadle keys its own
        ;; provenance off delegates / treadle/run, so it never adopts the fresh
        ;; run: this is exactly why retry is not the gate-recovery verb.
        (let [fresh-run-id (get-in retry [:run :id])]
          (is (some? (edge-row rt gate-id fresh-run-id "parent-of")))
          (is (nil? (edge-row rt gate-id fresh-run-id "delegates")))
          (is (nil? (attr (api/show rt fresh-run-id) :treadle/gate)))
          (is (= (:id failed) (attr (api/show rt gate-id) :treadle/run))))
        ;; a superseded run is closed but not `done`: its stale result must never
        ;; complete the gate, even when a delivery scan runs over it.
        (treadle/scan!)
        (is (nil? (attr (api/show rt (:id failed)) :treadle/delivered)))
        (is (nil? (attr (api/show rt gate-id) :workflow/notes)))
        ;; retry re-links no fresh run to the gate, so the gate must stay
        ;; discoverable as stalled rather than silently pending.
        (is (= "superseded" (:phase (treadle/gate-stalled? (ready-subagent-gate "retry-blank")))))))))

(deftest routed-away-gate-marks-run-gate-closed
  (with-treadle
    (fn [rt]
      (workflow/start! "routed" (workflow/workflow
                                   "Routed"
                                   (workflow/gate :delegate "Delegate" :subagent
                                                  :attributes {"shuttle/harness" "sh-tail"
                                                               "shuttle/prompt" "sleep 1; echo too-late"})) {})
      (let [gate-id (:id (ready-subagent-gate "routed"))
            run (await-eventually #(run-for-gate rt gate-id))]
        (api/update rt gate-id {:state "closed"})
        (let [done (await-eventually #(let [r (api/show rt (:id run))]
                             (when (attr r :treadle/delivered) r)))]
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
            run (await-eventually #(run-for-gate rt gate-id))
            hold (api/add rt {:title "Hold delivery"})]
        ;; un-ready the gate while its run is in flight: delivery must park
        ;; loudly (write-once treadle/delivery-blocked), not stamp terminal
        ;; state or spin
        (api/update rt gate-id {:edges [{:type "depends-on" :to (:id hold)}]})
        (let [blocked (await-eventually #(let [r (api/show rt (:id run))]
                                           (when (attr r :treadle/delivery-blocked) r)))]
          (is (= "closed" (:state blocked)))
          (is (nil? (attr blocked :treadle/delivered))))
        (api/update rt (:id hold) {:state "closed"})
        (let [delivered (await-eventually #(let [r (api/show rt (:id run))]
                                             (when (attr r :treadle/delivered) r)))]
          (is (= "true" (attr delivered :treadle/delivered)))
          (is (= "held-result" (attr (api/show rt gate-id) :workflow/notes))))))))

(deftest treadle-registers-executor-for-flow-await
  (with-treadle
    (fn [_]
      (workflow/start! "await-treadle" (workflow/workflow
                                          "Await treadle"
                                          (workflow/gate :delegate "Delegate" :subagent
                                                         :attributes {"shuttle/prompt" "echo no"})) {})
      (let [result (workflow/await! "await-treadle" {:timeout-secs 10})]
        (is (= :stalled (:reason result)))
        (is (str/includes? (get-in result [:detail :stall :error]) "shuttle/harness"))))))

(deftest stalled-gates-query-reports-spawn-errors-and-dead-runs
  (with-treadle
    (fn [rt]
      ;; (a) a gate whose delegated run is still running is not stalled
      (workflow/start! "query-running" (workflow/workflow
                                          "Query running"
                                          (workflow/gate :delegate "Running delegate" :subagent
                                                         :attributes {"shuttle/harness" "sh-tail"
                                                                      "shuttle/prompt" "sleep 1; echo ok"})) {})
      (let [running-gate-id (:id (ready-subagent-gate "query-running"))
            running-run-id (:id (await-eventually #(run-for-gate rt running-gate-id)))]
        (await-eventually #(= "running" (attr (api/show rt running-run-id) :shuttle/phase)))
        (is (empty? (filter #(= running-gate-id (:id %))
                            (api/list-query rt 'stalled-gates {}))))
        ;; (b) spawn-side error surfaces the gate
        (workflow/start! "query-error" (workflow/workflow
                                          "Query error"
                                          (workflow/gate :delegate "Broken delegate" :subagent
                                                         :attributes {"shuttle/prompt" "echo no"})) {})
        ;; (c) a gate whose delegated run died (blank result -> failed) also
        ;; surfaces: the query reaches the run's phase through the delegates edge,
        ;; not just the gate's own treadle/error.
        (workflow/start! "query-dead" (workflow/workflow
                                         "Query dead"
                                         (workflow/gate :delegate "Dead delegate" :subagent
                                                        :attributes {"shuttle/harness" "sh-tail"
                                                                     "shuttle/prompt" "true"})) {})
        (let [error-gate-id (:id (ready-subagent-gate "query-error"))
              dead-gate-id (:id (ready-subagent-gate "query-dead"))]
          (await-eventually #(attr (api/show rt error-gate-id) :treadle/error))
          (await-eventually #(when-let [r (run-for-gate rt dead-gate-id)]
                               (= "failed" (attr (api/show rt (:id r)) :shuttle/phase))))
          (is (= #{error-gate-id dead-gate-id}
                 (set (mapv :id (api/list-query rt 'stalled-gates {})))))
          (await-eventually #(= "closed" (:state (api/show rt running-run-id)))))))))

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

(deftest state-shape-matches-declared-version
  ;; Drift alarm for the treadle's versioned spool-state: a key added to
  ;; new-state without a state-version bump would survive reload! as a stale map.
  (test-support/assert-state-shape
   #'treadle/new-state
   #{:scan-monitor}))
