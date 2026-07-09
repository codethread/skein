(ns skein.executors.subagent-test
  "Tests for the subagent executor: the workflow-gate to agent-run adapter."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skein.spools.agent-run :as shuttle]
            [skein.spools.executors.subagent :as treadle]
            [skein.spools.delegation :as agents]
            [skein.spools.workflow :as workflow]
            [skein.spools.test-support :as test-support :refer [with-runtime]]
            [skein.core.db :as db]
            [skein.api.weaver.alpha :as api]
            [skein.api.events.alpha :as events]))

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

(defn- await-settled
  "Poll `pred` (a strand -> truthy fn) against strand `id` for a real
  off-lane subprocess completion (RFC-Dtt-001.REC7) that lane quiescence
  alone cannot observe. Once `pred` lands, settle the event lane so
  treadle's on-lane reaction to that transition (delivery, error,
  superseded-by stamps) has run before the caller reads dependent state."
  ([rt id pred] (await-settled rt id pred (test-support/await-budget-ms)))
  ([rt id pred timeout-ms]
   (await-eventually #(let [s (api/show rt id)]
                        (when (pred s)
                          (events/await-quiescent! rt)
                          (api/show rt id)))
                     timeout-ms)))

(defn- await-run-phase
  "Await run `id` reaching one of `phases` (a terminal `:agent-run/phase`),
  layered on `await-settled`."
  ([rt id phases] (await-run-phase rt id phases (test-support/await-budget-ms)))
  ([rt id phases timeout-ms]
   (await-settled rt id #(contains? (set phases) (attr % :agent-run/phase)) timeout-ms)))

(defn- await-delivered
  "Await run `id` stamping `:gate/delivered`, layered on `await-settled`."
  ([rt id] (await-delivered rt id (test-support/await-budget-ms)))
  ([rt id timeout-ms]
   (await-settled rt id #(some? (attr % :gate/delivered)) timeout-ms)))

(defn- workflow-with-gate [gate-attrs]
  (workflow/workflow
   "Treadle test"
   (workflow/step :first "First" :self)
   (workflow/gate :delegate "Delegate work" :subagent
                  :depends-on [:first]
                  :attributes gate-attrs)
   (workflow/step :after "After" :self :depends-on [:delegate])))

(defn- run-for-gate [rt gate-id]
  (first (api/list rt [:= [:attr "gate/step"] gate-id] {})))

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
      (workflow/start! "happy" (workflow-with-gate {"agent-run/harness" "sh-tail"
                                                    "agent-run/prompt" "echo gate-result"}) {})
      (let [[first-step] (workflow/next-steps "happy")]
        (workflow/complete! "happy" {:step (:id first-step)}))
      ;; capture gate-id before settling: a fast gate (no subprocess delay)
      ;; can already be delivered and gone from next-steps by the time the
      ;; event lane settles.
      (let [gate-id (:id (ready-subagent-gate "happy"))
            _ (events/await-quiescent! rt)
            run-id (:id (run-for-gate rt gate-id))
            run (await-delivered rt run-id)
            gate (api/show rt (attr run :gate/step))
            after (first (workflow/next-steps "happy"))]
        (is (= "true" (attr run :gate/delivered)))
        (is (= (:id run) (attr gate :workflow/outcome-by)))
        (is (= "gate-result" (attr gate :workflow/outcome-notes)))
        (is (= "happy" (attr run :gate/run-id)))
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
                                       :attributes {"agent-run/harness" "sh-tail"
                                                    "agent-run/prompt" "echo later"}))]
        (workflow/start! "blocked" definition {})
        (events/await-quiescent! rt)
        (let [gate-id (:id (first (api/list rt [:= :title "Delegate"] {})))]
          (is (some? gate-id))
          (is (nil? (run-for-gate rt gate-id)))
          (let [[blocker] (workflow/next-steps "blocked")]
            (workflow/complete! "blocked" {:step (:id blocker)}))
          (events/await-quiescent! rt)
          (is (some? (run-for-gate rt gate-id))))))))

(deftest missing-harness-stamps-error-and-does-not-retry
  (with-treadle
    (fn [rt]
      (workflow/start! "missing" (workflow/workflow
                                  "Missing"
                                  (workflow/gate :delegate "Delegate" :subagent
                                                 :attributes {"agent-run/prompt" "echo no"})) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-subagent-gate "missing"))
            gate (api/show rt gate-id)]
        (is (str/includes? (attr gate :gate/error) "agent-run/harness"))
        (is (nil? (run-for-gate rt gate-id)))
        (api/add rt {:title "unrelated"})
        (events/await-quiescent! rt)
        (is (nil? (run-for-gate rt gate-id)))))))

(deftest failed-run-stays-ready-and-clearing-stamp-spawns-fresh-run
  (with-treadle
    (fn [rt]
      (workflow/start! "retry" (workflow/workflow
                                "Retry"
                                (workflow/gate :delegate "Delegate" :subagent
                                               :attributes {"agent-run/harness" "sh-tail"
                                                            "agent-run/prompt" "exit 7"})
                                (workflow/step :after "After" :self :depends-on [:delegate])) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-subagent-gate "retry"))
            run-id (:id (run-for-gate rt gate-id))
            failed (await-run-phase rt run-id #{"failed"})]
        (is (= "active" (:state failed)))
        (is (= gate-id (attr failed :gate/step)))
        (is (= [gate-id] (mapv :id (filter #(= "subagent" (:gate %)) (workflow/next-steps "retry")))))
        (api/update rt gate-id {:attributes {"gate/run" ""
                                             "agent-run/prompt" "echo recovered"}})
        (events/await-quiescent! rt)
        (let [fresh-id (:id (some (fn [r]
                                    (when (not= (:id failed) (:id r)) r))
                                  (api/list rt [:= [:attr "gate/step"] gate-id] {})))
              done (await-run-phase rt fresh-id #{"done"})]
          (is (= "recovered" (attr done :agent-run/result))))))))

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
                                               :attributes {"agent-run/harness" "sh-tail"
                                                            "agent-run/prompt" "true"})
                                (workflow/step :after "After" :self :depends-on [:delegate])) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-subagent-gate "blank"))
            run-id (:id (run-for-gate rt gate-id))
            failed (await-run-phase rt run-id #{"failed"})]
        ;; (a) the blank-result run is recorded failed, loud, and stays active
        (is (= "active" (:state failed)))
        (is (str/includes? (attr failed :agent-run/error) "empty result"))
        (is (= gate-id (attr failed :gate/step)))
        ;; (b) the gate is never delivered and its downstream step stays blocked
        (is (nil? (attr failed :gate/delivered)))
        (is (nil? (attr (api/show rt gate-id) :workflow/outcome-notes)))
        (is (= [gate-id] (mapv :id (filter #(= "subagent" (:gate %)) (workflow/next-steps "blank")))))
        ;; (c) discoverable: the run through agent-failures, the gate through both
        ;; the stall predicate and the stalled-gates coordinator query.
        (is (some #(= (:id failed) (:id %)) (api/list-query rt 'agent-failures {})))
        (is (= "failed" (:phase (treadle/gate-stalled? (ready-subagent-gate "blank")))))
        (is (some #(= gate-id (:id %)) (api/list-query rt 'stalled-gates {})))
        ;; (d) recover by clearing the gate's run stamp: the treadle respawns a fresh run
        (api/update rt gate-id {:attributes {"gate/run" ""
                                             "agent-run/prompt" "echo recovered"}})
        (events/await-quiescent! rt)
        (let [fresh-id (:id (some (fn [r] (when (not= (:id failed) (:id r)) r))
                                  (api/list rt [:= [:attr "gate/step"] gate-id] {})))
              delivered (await-delivered rt fresh-id)]
          (is (= "true" (attr delivered :gate/delivered)))
          (is (= "recovered" (attr (api/show rt gate-id) :workflow/outcome-notes)))
          (is (= "After" (:title (first (workflow/next-steps "blank"))))))))))

(deftest recovery-respawn-keeps-stalled-gates-in-lockstep-with-predicate
  ;; A cleared-and-respawned gate leaves a stale `delegates` edge to its dead run.
  ;; The `stalled-gates` query follows that edge; `gate-stalled?` reads only the
  ;; current `gate/run`. Repointing the dead run's provenance keeps the two in
  ;; lockstep: the gate stops surfacing the moment a healthy replacement is in flight.
  (with-treadle
    (fn [rt]
      (workflow/start! "lockstep" (workflow/workflow
                                   "Lockstep"
                                   (workflow/gate :delegate "Delegate" :subagent
                                                   ;; exit 0 with blank stdout -> a dead `failed` run
                                                  :attributes {"agent-run/harness" "sh-tail"
                                                               "agent-run/prompt" "true"})
                                   (workflow/step :after "After" :self :depends-on [:delegate])) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-subagent-gate "lockstep"))
            run-id (:id (run-for-gate rt gate-id))
            failed (await-run-phase rt run-id #{"failed"})]
        ;; dead run: predicate and query agree the gate is stalled
        (is (= "failed" (:phase (treadle/gate-stalled? (ready-subagent-gate "lockstep")))))
        (is (some #(= gate-id (:id %)) (api/list-query rt 'stalled-gates {})))
        ;; recover by clearing the stamp; the treadle respawns a slow, healthy run
        (api/update rt gate-id {:attributes {"gate/run" nil
                                             "agent-run/prompt" "sleep 3; echo recovered"}})
        (events/await-quiescent! rt)
        (let [fresh-id (:id (some (fn [r] (when (not= (:id failed) (:id r)) r))
                                  (api/list rt [:= [:attr "gate/step"] gate-id] {})))]
          ;; the respawn stamps the gate's gate/run in the same on-lane scan
          ;; that created the fresh run, so it is already in lockstep here
          (is (= fresh-id (attr (api/show rt gate-id) :gate/run)))
          (await-run-phase rt fresh-id #{"running"})
          ;; the dead run's provenance is repointed at the fresh run
          (is (= fresh-id (attr (api/show rt (:id failed)) :gate/superseded-by)))
          (is (nil? (attr (api/show rt fresh-id) :gate/superseded-by)))
          ;; lockstep while the replacement is healthy: neither surfaces the gate
          (is (nil? (treadle/gate-stalled? (ready-subagent-gate "lockstep"))))
          (is (empty? (filter #(= gate-id (:id %)) (api/list-query rt 'stalled-gates {}))))
          ;; let the healthy run finish so the gate delivers and teardown is clean
          (is (= "true" (attr (await-delivered rt fresh-id (test-support/await-budget-ms 15000))
                              :gate/delivered))))))))

(deftest agent-retry-of-blank-gate-run-supersedes-without-stale-delivery
  (with-treadle
    (fn [rt]
      (agents/install!)
      (workflow/start! "retry-blank" (workflow/workflow
                                      "Retry blank"
                                      (workflow/gate :delegate "Delegate" :subagent
                                                     :attributes {"agent-run/harness" "sh-tail"
                                                                  "agent-run/prompt" "true"})
                                      (workflow/step :after "After" :self :depends-on [:delegate])) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-subagent-gate "retry-blank"))
            run-id (:id (run-for-gate rt gate-id))
            failed (await-run-phase rt run-id #{"failed"})
            retry (agents/agent-op {:op/argv ["retry" (:id failed)]})]
        ;; agent retry supersedes the dead run (closes it) and spawns a fresh run
        (is (= (:id failed) (:superseded retry)))
        (let [superseded (api/show rt (:id failed))]
          (is (= "superseded" (attr superseded :agent-run/phase)))
          (is (= "closed" (:state superseded))))
        ;; retry respawns with :parent = served-target, which resolves from the
        ;; failed run's serves edge. A treadle-delegated run carries no serves
        ;; edge (treadle stamps gate/step + a delegates edge, not serves), so the
        ;; served-target is nil and the fresh run is unparented: no parent-of and
        ;; no delegates edge from the gate, no gate/step attr, and the gate's
        ;; gate/run stamp is never rewritten. This is exactly why retry is not the
        ;; gate-recovery verb — the fresh run is never adopted by the gate.
        (let [fresh-run-id (get-in retry [:run :id])]
          (is (nil? (edge-row rt gate-id fresh-run-id "parent-of")))
          (is (nil? (edge-row rt gate-id fresh-run-id "delegates")))
          (is (nil? (attr (api/show rt fresh-run-id) :gate/step)))
          (is (= (:id failed) (attr (api/show rt gate-id) :gate/run))))
        ;; a superseded run is closed but not `done`: its stale result must never
        ;; complete the gate, even when a delivery scan runs over it.
        (treadle/scan!)
        (is (nil? (attr (api/show rt (:id failed)) :gate/delivered)))
        (is (nil? (attr (api/show rt gate-id) :workflow/outcome-notes)))
        ;; retry re-links no fresh run to the gate, so the gate must stay
        ;; discoverable as stalled rather than silently pending.
        (is (= "superseded" (:phase (treadle/gate-stalled? (ready-subagent-gate "retry-blank")))))))))

(deftest routed-away-gate-marks-run-gate-closed
  (with-treadle
    (fn [rt]
      (workflow/start! "routed" (workflow/workflow
                                 "Routed"
                                 (workflow/gate :delegate "Delegate" :subagent
                                                :attributes {"agent-run/harness" "sh-tail"
                                                             "agent-run/prompt" "sleep 1; echo too-late"})) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-subagent-gate "routed"))
            run-id (:id (run-for-gate rt gate-id))]
        (api/update rt gate-id {:state "closed"})
        (let [done (await-delivered rt run-id)]
          (is (= "gate-closed" (attr done :gate/delivered))))))))

(deftest unready-gate-blocks-delivery-until-ready-again
  (with-treadle
    (fn [rt]
      (workflow/start! "held" (workflow/workflow
                               "Held"
                               (workflow/gate :delegate "Delegate" :subagent
                                              :attributes {"agent-run/harness" "sh-tail"
                                                           "agent-run/prompt" "sleep 1; echo held-result"})) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-subagent-gate "held"))
            run-id (:id (run-for-gate rt gate-id))
            hold (api/add rt {:title "Hold delivery"})]
        ;; un-ready the gate while its run is in flight: delivery must park
        ;; loudly (write-once gate/delivery-blocked), not stamp terminal
        ;; state or spin
        (api/update rt gate-id {:edges [{:type "depends-on" :to (:id hold)}]})
        (let [blocked (await-settled rt run-id #(attr % :gate/delivery-blocked))]
          (is (= "closed" (:state blocked)))
          (is (nil? (attr blocked :gate/delivered))))
        (api/update rt (:id hold) {:state "closed"})
        (let [delivered (await-delivered rt run-id)]
          (is (= "true" (attr delivered :gate/delivered)))
          (is (= "held-result" (attr (api/show rt gate-id) :workflow/outcome-notes))))))))

(deftest treadle-registers-executor-for-flow-await
  (with-treadle
    (fn [_]
      (workflow/start! "await-treadle" (workflow/workflow
                                        "Await treadle"
                                        (workflow/gate :delegate "Delegate" :subagent
                                                       :attributes {"agent-run/prompt" "echo no"})) {})
      (let [result (workflow/await! "await-treadle" {:timeout-secs 10})]
        (is (= :stalled (:reason result)))
        (is (str/includes? (get-in result [:detail :stall :error]) "agent-run/harness"))))))

(deftest stalled-gates-query-reports-spawn-errors-and-dead-runs
  (with-treadle
    (fn [rt]
      ;; (a) a gate whose delegated run is still running is not stalled
      (workflow/start! "query-running" (workflow/workflow
                                        "Query running"
                                        (workflow/gate :delegate "Running delegate" :subagent
                                                       :attributes {"agent-run/harness" "sh-tail"
                                                                    "agent-run/prompt" "sleep 1; echo ok"})) {})
      (events/await-quiescent! rt)
      (let [running-gate-id (:id (ready-subagent-gate "query-running"))
            running-run-id (:id (run-for-gate rt running-gate-id))]
        (await-run-phase rt running-run-id #{"running"})
        (is (empty? (filter #(= running-gate-id (:id %))
                            (api/list-query rt 'stalled-gates {}))))
        ;; (b) spawn-side error surfaces the gate
        (workflow/start! "query-error" (workflow/workflow
                                        "Query error"
                                        (workflow/gate :delegate "Broken delegate" :subagent
                                                       :attributes {"agent-run/prompt" "echo no"})) {})
        ;; (c) a gate whose delegated run died (blank result -> failed) also
        ;; surfaces: the query reaches the run's phase through the delegates edge,
        ;; not just the gate's own gate/error.
        (workflow/start! "query-dead" (workflow/workflow
                                       "Query dead"
                                       (workflow/gate :delegate "Dead delegate" :subagent
                                                      :attributes {"agent-run/harness" "sh-tail"
                                                                   "agent-run/prompt" "true"})) {})
        (events/await-quiescent! rt)
        (let [error-gate-id (:id (ready-subagent-gate "query-error"))
              dead-gate-id (:id (ready-subagent-gate "query-dead"))
              dead-run-id (:id (run-for-gate rt dead-gate-id))]
          (await-run-phase rt dead-run-id #{"failed"})
          (is (= #{error-gate-id dead-gate-id}
                 (set (mapv :id (api/list-query rt 'stalled-gates {})))))
          (await-run-phase rt running-run-id #{"done"}))))))

(deftest non-subagent-gates-are-ignored
  (with-treadle
    (fn [rt]
      (workflow/start! "ci" (workflow/workflow
                             "CI"
                             (workflow/gate :ci "Wait for CI" :ci
                                            :attributes {"agent-run/harness" "sh-tail"
                                                         "agent-run/prompt" "echo ignored"})) {})
      (let [gate-id (:id (first (workflow/next-steps "ci")))]
        (events/await-quiescent! rt)
        (is (nil? (run-for-gate rt gate-id)))
        (is (nil? (attr (api/show rt gate-id) :gate/run)))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for the treadle's versioned spool-state: a key added to
  ;; new-state without a state-version bump would survive reload! as a stale map.
  (test-support/assert-state-shape
   ;; white-box read of a private var: kondo flags cross-ns private access, but
   ;; #'ns/private is legal and intentional here.
   #_{:clj-kondo/ignore [:unresolved-var]}
   #'treadle/new-state
   #{:scan-monitor}))
