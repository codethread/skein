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
            [skein.api.weaver.alpha :as weaver]
            [skein.api.events.alpha :as events]
            [skein.api.vocab.alpha :as vocab]))

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
  treadle's on-lane reaction to that transition (delivery, spawn error)
  has run before the caller reads dependent state."
  ([rt id pred] (await-settled rt id pred (test-support/await-budget-ms)))
  ([rt id pred timeout-ms]
   (await-eventually #(let [s (weaver/show rt id)]
                        (when (pred s)
                          (events/await-quiescent! rt)
                          (weaver/show rt id)))
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
  (first (weaver/list rt [:edge/out "serves" [:= :id gate-id]] {})))

(defn- other-run-for-gate
  "The gate's serving run whose id is not `exclude-id` — the fresh successor after
  a retry supersedes the original run."
  [rt gate-id exclude-id]
  (first (remove #(= exclude-id (:id %))
                 (weaver/list rt [:edge/out "serves" [:= :id gate-id]] {}))))

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
            gate (weaver/show rt gate-id)
            after (first (workflow/next-steps "happy"))]
        (is (= "true" (attr run :gate/delivered)))
        (is (= (:id run) (attr gate :workflow/outcome-by)))
        (is (= "gate-result" (attr gate :workflow/outcome-notes)))
        (is (= "happy" (attr run :gate/run-id)))
        (is (= "After" (:title after)))
        ;; the run↔gate link is the run's own outgoing `serves` edge — placement
        ;; is orthogonal, so the gate has no `parent-of` edge to the run.
        (is (= {:from_strand_id (:id run)
                :to_strand_id (:id gate)
                :edge_type "serves"}
               (edge-row rt (:id run) (:id gate) "serves")))
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
        (let [gate-id (:id (first (weaver/list rt [:= :title "Delegate"] {})))]
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
            gate (weaver/show rt gate-id)]
        (is (str/includes? (attr gate :gate/error) "agent-run/harness"))
        (is (nil? (run-for-gate rt gate-id)))
        (weaver/add rt {:title "unrelated"})
        (events/await-quiescent! rt)
        (is (nil? (run-for-gate rt gate-id)))))))

(deftest failed-run-stays-ready-and-agent-retry-recovers-the-gate
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
        ;; the failed run serves the gate; the gate stays ready and does not respawn
        (is (= gate-id (:to_strand_id (edge-row rt (:id failed) gate-id "serves"))))
        (is (= [gate-id] (mapv :id (filter #(= "subagent" (:gate %)) (workflow/next-steps "retry")))))
        ;; recover with `agent retry`: supersede the dead run with a fresh
        ;; successor that inherits the serves edge — no re-link step
        (let [fresh (shuttle/supersede-and-respawn! (:id failed)
                                                    {:harness "sh-tail"
                                                     :prompt "echo recovered"
                                                     :carry-attrs {"gate/run-id" (attr failed :gate/run-id)}})
              delivered (await-delivered rt (:id fresh))]
          (is (= "recovered" (attr delivered :agent-run/result)))
          (is (= "true" (attr delivered :gate/delivered)))
          (is (= gate-id (:to_strand_id (edge-row rt (:id fresh) gate-id "serves"))))
          (is (= "superseded" (attr (weaver/show rt (:id failed)) :agent-run/phase)))
          (is (= "recovered" (attr (weaver/show rt gate-id) :workflow/outcome-notes))))))))

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
        (is (= gate-id (:to_strand_id (edge-row rt (:id failed) gate-id "serves"))))
        ;; (b) the gate is never delivered and its downstream step stays blocked
        (is (nil? (attr failed :gate/delivered)))
        (is (nil? (attr (weaver/show rt gate-id) :workflow/outcome-notes)))
        (is (= [gate-id] (mapv :id (filter #(= "subagent" (:gate %)) (workflow/next-steps "blank")))))
        ;; (c) discoverable: the run through agent-failures, the gate through both
        ;; the stall predicate and the stalled-gates coordinator query.
        (is (some #(= (:id failed) (:id %)) (weaver/list-query rt 'agent-failures {})))
        (is (= "failed" (:phase (treadle/gate-stalled? (ready-subagent-gate "blank")))))
        (is (some #(= gate-id (:id %)) (weaver/list-query rt 'stalled-gates {})))
        ;; (d) recover with `agent retry`: the successor inherits the serves edge
        ;; and the treadle delivers it once it succeeds
        (let [fresh (shuttle/supersede-and-respawn! (:id failed)
                                                    {:harness "sh-tail"
                                                     :prompt "echo recovered"
                                                     :carry-attrs {"gate/run-id" (attr failed :gate/run-id)}})
              delivered (await-delivered rt (:id fresh))]
          (is (= "true" (attr delivered :gate/delivered)))
          (is (= "recovered" (attr (weaver/show rt gate-id) :workflow/outcome-notes)))
          (is (= "After" (:title (first (workflow/next-steps "blank"))))))))))

(deftest recovery-respawn-keeps-stalled-gates-in-lockstep-with-predicate
  ;; The `stalled-gates` query and the `gate-stalled?` predicate both resolve the
  ;; gate's current serving run from `serves` + lineage, so they agree by
  ;; construction: the moment `agent retry` supersedes the dead run and a healthy
  ;; successor inherits the serves edge, neither surfaces the gate.
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
        (is (some #(= gate-id (:id %)) (weaver/list-query rt 'stalled-gates {})))
        ;; recover with agent retry; the successor is slow but healthy
        (let [fresh (shuttle/supersede-and-respawn! (:id failed)
                                                    {:harness "sh-tail"
                                                     :prompt "sleep 3; echo recovered"
                                                     :carry-attrs {"gate/run-id" (attr failed :gate/run-id)}})]
          ;; the fresh successor is the gate's current server; the old run is superseded
          (is (= (:id fresh) (:id (other-run-for-gate rt gate-id (:id failed)))))
          (is (= "superseded" (attr (weaver/show rt (:id failed)) :agent-run/phase)))
          (await-run-phase rt (:id fresh) #{"running"})
          ;; lockstep while the replacement is healthy: neither surfaces the gate
          (is (nil? (treadle/gate-stalled? (ready-subagent-gate "lockstep"))))
          (is (empty? (filter #(= gate-id (:id %)) (weaver/list-query rt 'stalled-gates {}))))
          ;; let the healthy run finish so the gate delivers and teardown is clean
          (is (= "true" (attr (await-delivered rt (:id fresh) (test-support/await-budget-ms 15000))
                              :gate/delivered))))))))

(deftest superseded-run-stale-result-never-delivers
  ;; C14/NG2: only a `done` run with a non-blank result delivers a gate. A run
  ;; `agent retry` supersedes is closed `superseded`, never `done`, so its stale
  ;; result must never complete the gate — even when a delivery scan runs over it.
  ;; The fresh successor inherits the serves edge and delivers on success instead.
  (with-treadle
    (fn [rt]
      (workflow/start! "stale" (workflow/workflow
                                "Stale"
                                (workflow/gate :delegate "Delegate" :subagent
                                               :attributes {"agent-run/harness" "sh-tail"
                                                            "agent-run/prompt" "true"})
                                (workflow/step :after "After" :self :depends-on [:delegate])) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-subagent-gate "stale"))
            run-id (:id (run-for-gate rt gate-id))
            failed (await-run-phase rt run-id #{"failed"})
            ;; supersede with a slow successor so it has not delivered yet
            fresh (shuttle/supersede-and-respawn! (:id failed)
                                                  {:harness "sh-tail"
                                                   :prompt "sleep 3; echo recovered"
                                                   :carry-attrs {"gate/run-id" (attr failed :gate/run-id)}})]
        (let [superseded (weaver/show rt (:id failed))]
          (is (= "superseded" (attr superseded :agent-run/phase)))
          (is (= "closed" (:state superseded))))
        ;; a scan over the superseded run must not deliver its stale blank result
        (treadle/scan!)
        (is (nil? (attr (weaver/show rt (:id failed)) :gate/delivered)))
        (is (nil? (attr (weaver/show rt gate-id) :workflow/outcome-notes)))
        ;; the healthy successor still serves the gate and delivers on success
        (let [delivered (await-delivered rt (:id fresh) (test-support/await-budget-ms 15000))]
          (is (= "true" (attr delivered :gate/delivered)))
          (is (= "recovered" (attr (weaver/show rt gate-id) :workflow/outcome-notes))))))))

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
        (weaver/update rt gate-id {:state "closed"})
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
            hold (weaver/add rt {:title "Hold delivery"})]
        ;; un-ready the gate while its run is in flight: delivery must park
        ;; loudly (write-once gate/delivery-blocked), not stamp terminal
        ;; state or spin
        (weaver/update rt gate-id {:edges [{:type "depends-on" :to (:id hold)}]})
        (let [blocked (await-settled rt run-id #(attr % :gate/delivery-blocked))]
          (is (= "closed" (:state blocked)))
          (is (nil? (attr blocked :gate/delivered))))
        (weaver/update rt (:id hold) {:state "closed"})
        (let [delivered (await-delivered rt run-id)]
          (is (= "true" (attr delivered :gate/delivered)))
          (is (= "held-result" (attr (weaver/show rt gate-id) :workflow/outcome-notes))))))))

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
                            (weaver/list-query rt 'stalled-gates {}))))
        ;; (b) spawn-side error surfaces the gate
        (workflow/start! "query-error" (workflow/workflow
                                        "Query error"
                                        (workflow/gate :delegate "Broken delegate" :subagent
                                                       :attributes {"agent-run/prompt" "echo no"})) {})
        ;; (c) a gate whose delegated run died (blank result -> failed) also
        ;; surfaces: the query reaches the run's phase through the incoming serves
        ;; edge, not just the gate's own gate/error.
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
                 (set (mapv :id (weaver/list-query rt 'stalled-gates {})))))
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
        (is (nil? (run-for-gate rt gate-id)))))))

(deftest install-declares-gate-vocabulary
  ;; The treadle's install! owns the `gate/*` namespace — the sole treadle-era
  ;; durable survivor — under the single use-key :skein/spools-treadle.
  (with-treadle
    (fn [rt]
      (let [decl (vocab/declaration rt :attr-namespace "gate")]
        (is (= :attr-namespace (:kind decl)))
        (is (= "gate" (:name decl)))
        (is (= :skein/spools-treadle (:owner decl)))
        (is (contains? (set (:keys decl)) "gate/delivered"))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for the treadle's versioned spool-state: a key added to
  ;; new-state without a state-version bump would survive reload! as a stale map.
  (test-support/assert-state-shape
   ;; white-box read of a private var: kondo flags cross-ns private access, but
   ;; #'ns/private is legal and intentional here.
   #_{:clj-kondo/ignore [:unresolved-var]}
   #'treadle/new-state
   #{:scan-monitor}))
