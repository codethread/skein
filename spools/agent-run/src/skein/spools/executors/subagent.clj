(ns skein.spools.executors.subagent
  "Bridge workflow subagent gates to agent-run runs.

  The subagent executor watches workflow runs for ready `:subagent` gates, spawns
  an agent-run run for each gate, and delivers successful run results by
  completing the gate through `skein.spools.workflow/complete!`. It intentionally
  adds no CLI surface and keeps workflow and agent-run decoupled: this namespace
  is the only adapter that knows both vocabularies."
  (:require [clojure.string :as str]
            [skein.spools.agent-run :as agent-run]
            [skein.spools.workflow :as workflow]
            [skein.spools.util :refer [fail! attr-get]]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as api]
            [skein.api.events.alpha :as events]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]))

(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded})

(def ^:private stalled-run-phases
  "Terminal agent-run phases that leave a delegated run dead: a `failed`/`exhausted`
  worker, or a `superseded` run `agent retry` retired. A gate whose delegated run
  is in one of these is stalled — both `gate-stalled?` and the `stalled-gates`
  query key off this list. Phase alone is not enough for lockstep: the query
  reaches phases through `delegates` edges, so `stamp-run-on-gate!` also repoints
  superseded runs out of the query (see there) so the two agree on membership."
  ["failed" "exhausted" "superseded"])

(def ^:dynamic *runtime*
  "Runtime captured for asynchronous subagent-executor scans."
  nil)

(defn- rt []
  (or *runtime* (current/runtime)))

(def ^:private state-version
  "Shape version for the subagent executor's runtime spool-state map. Bump whenever
  `new-state`'s key set changes: spool-state survives `reload!`, so a post-upgrade
  reload would otherwise reuse a preserved map missing the new key
  (docs/writing-shared-spools.md 'Versioned spool state', SPEC-004.C95). The
  `state-shape-matches-declared-version` test guards against silent drift."
  1)

(defn- new-state []
  {:scan-monitor (Object.)})

(defn- state []
  (runtime/spool-state (rt) ::state {:version state-version} new-state))

(defn- scan-monitor [] (:scan-monitor (state)))

(defn- attr
  "Read attribute `k` tolerating keyword- or string-keyed maps, via the shared
  spool-tier tolerant reader (`skein.spools.util/attr-get`)."
  [strand k]
  (attr-get strand k))

(defn- non-blank [s]
  (when (and (string? s) (not (str/blank? s))) s))

(defn- stamp! [id attributes]
  (api/update (rt) id {:attributes attributes}))

(defn- spawn-idempotency-run-for-gate
  "Return an existing live run for gate-id, so a crash between spawn and gate
  stamp re-stamps instead of double-spawning. Failed/exhausted/superseded runs
  are deliberately excluded so clearing a gate's `gate/run` requests a fresh
  run rather than re-adopting the dead one (a `superseded` run is one `agent
  retry` closed out); the trade-off is that a run that died inside that same
  crash window is orphaned, not re-adopted (see subagent.md)."
  [gate-id]
  (first (api/list (rt)
                   [:and [:= [:attr "gate/step"] gate-id]
                    [:missing [:attr "gate/delivered"]]
                    [:not [:= [:attr "agent-run/phase"] "failed"]]
                    [:not [:= [:attr "agent-run/phase"] "exhausted"]]
                    [:not [:= [:attr "agent-run/phase"] "superseded"]]]
                   {})))

(defn- ready-gate? [run-id gate-id]
  (some #(= gate-id (:id %)) (workflow/next-steps run-id)))

(defn- deliver-run! [run]
  (let [run-id (:id run)
        gate-id (attr run :gate/step)
        workflow-run-id (attr run :gate/run-id)]
    (try
      (let [gate (api/show (rt) gate-id)]
        (cond
          (nil? gate)
          (stamp! run-id {"gate/delivered" "error: gate not found"})

          (= "closed" (:state gate))
          (stamp! run-id {"gate/delivered" "gate-closed"})

          (ready-gate? workflow-run-id gate-id)
          (do
            (workflow/complete! workflow-run-id
                                (cond-> {:step gate-id :by run-id}
                                  (non-blank (attr run :agent-run/result))
                                  (assoc :notes (attr run :agent-run/result))))
            (stamp! run-id {"gate/delivered" "true"}))

          :else
          ;; Gate is active but not currently ready (e.g. userland added a
          ;; dependency after spawn). Leave the run undelivered so a later
          ;; scan retries once the gate is ready again, but stamp a durable
          ;; signal — write-once, or the stamp's own update event would
          ;; re-trigger this branch in a loop.
          (when-not (attr run :gate/delivery-blocked)
            (stamp! run-id {"gate/delivery-blocked"
                            (str "gate " gate-id " is active but not ready")}))))
      (catch Throwable t
        (stamp! run-id {"gate/delivered" (str "error: " (ex-message t)
                                                 (some->> (ex-data t) (str " ")))})))))

(defn- finished-undelivered-runs []
  ;; Only a genuinely successful run delivers a gate. A run's result is the
  ;; worker's report, so `agent-run/phase "done"` (which the engine records only for a
  ;; non-blank result) is the delivery gate. Any other closed phase — notably a
  ;; `superseded` run left by `agent retry` — is a dead worker whose (blank or
  ;; stale) result must never silently complete the gate; recovery re-spawns a
  ;; fresh run instead.
  (api/list (rt)
            [:and [:= :state "closed"]
             [:= [:attr "agent-run/phase"] "done"]
             [:exists [:attr "gate/step"]]
             [:missing [:attr "gate/delivered"]]]
            {}))

(defn- gate-prompt [gate]
  (or (non-blank (attr gate :agent-run/prompt))
      (non-blank (attr gate :workflow/instruction))
      (non-blank (attr gate :description))
      (non-blank (:title gate))))

(defn- subagent-preamble [{:keys [gate run-id prompt]}]
  (str "This run fulfills workflow gate " (:id gate) " (" (:title gate) ") "
       "in workflow run " run-id ".\n"
       "Your final message is captured as the gate's completion record.\n"
       "Do not close or mutate workflow strands yourself; the subagent executor closes the gate after this run succeeds.\n\n"
       prompt))

(defn- parse-max-attempts [v]
  (cond
    (nil? v) nil
    (integer? v) v
    (string? v) (try
                  (Long/parseLong v)
                  (catch NumberFormatException _
                    (fail! "agent-run/max-attempts must be an integer" {:value v})))
    :else (fail! "agent-run/max-attempts must be an integer" {:value v})))

(defn- stamp-run-on-gate!
  "Stamp gate-id with its current delegated run: the `gate/run` attribute and a
  `delegates` edge, repointing every prior delegated run's provenance to this one.

  `stalled-gates` reaches a run's `agent-run/phase` through the gate's `delegates`
  edges because the query DSL has no attr->id join back to `gate/run`. A
  cleared-and-respawned gate keeps its old `delegates` edge to the dead run, so
  without repointing the query would keep surfacing a healthy gate while
  `gate-stalled?` (which reads only the current `gate/run`) reports nil. Marking
  each superseded run `gate/superseded-by` excludes its stale edge from the
  query, keeping the query's membership rule — the current delegated run is dead —
  in lockstep with the predicate."
  [gate-id run-id]
  (doseq [prior (api/list (rt)
                          [:and [:= [:attr "gate/step"] gate-id]
                           [:!= :id run-id]
                           [:missing [:attr "gate/superseded-by"]]]
                          {})]
    (stamp! (:id prior) {"gate/superseded-by" run-id}))
  (api/update (rt) gate-id {:attributes {"gate/run" run-id}
                            :edges [{:type "delegates" :to run-id}]}))

(defn- ensure-run-stamp! [gate]
  (when-let [run (spawn-idempotency-run-for-gate (:id gate))]
    (stamp-run-on-gate! (:id gate) (:id run))
    (:id run)))

(defn- spawn-for-gate! [run-id gate-view]
  (let [gate (api/show (rt) (:id gate-view))]
    (when-not (or (non-blank (attr gate :gate/run)) (non-blank (attr gate :gate/error)))
      (try
        (if (ensure-run-stamp! gate)
          nil
          (let [harness (or (non-blank (attr gate :agent-run/harness))
                            (fail! "subagent gate requires agent-run/harness" {:gate (:id gate)}))
                prompt (or (gate-prompt gate)
                           (fail! "subagent gate requires agent-run/prompt or derivable instruction" {:gate (:id gate)}))
                run (agent-run/spawn-run! {:harness harness
                                           :cwd (attr gate :agent-run/cwd)
                                           :max-attempts (parse-max-attempts (attr gate :agent-run/max-attempts))
                                           :prompt (subagent-preamble {:gate gate :run-id run-id :prompt prompt})
                                           :title (str "Delegated: " (:title gate))
                                           :attrs {"gate/step" (:id gate)
                                                   "gate/run-id" run-id}})]
            (stamp-run-on-gate! (:id gate) (:id run))))
        (catch Throwable t
          (stamp! (:id gate) {"gate/error" (str (ex-message t)
                                                   (some->> (ex-data t) (str " ")))}))))))

(defn- spawn-ready-gates! []
  (doseq [root (workflow/active-runs)
          :let [run-id (attr root :workflow/run-id)]
          step (workflow/next-steps run-id)
          :when (= "subagent" (:gate step))]
    (try
      (spawn-for-gate! run-id step)
      (catch Throwable t
        (stamp! (:id step) {"gate/error" (str (ex-message t)
                                                 (some->> (ex-data t) (str " ")))})))))

(defn scan!
  "Deliver finished agent-run runs and spawn ready workflow subagent gates."
  []
  (let [runtime (rt)]
    (binding [*runtime* runtime
              agent-run/*runtime* runtime]
      (locking (scan-monitor)
        (doseq [run (finished-undelivered-runs)]
          (deliver-run! run))
        (spawn-ready-gates!)
        {:scanned true}))))

(defn on-event
  "Weaver event handler: graph changes may finish or unblock subagent executor work."
  [_event]
  (scan!))

(defn gate-stalled?
  "Return durable stall detail for a ready subagent gate view, or nil.

  A gate is stalled when spawn failed onto `gate/error`, or its stamped run is
  in agent-run phase `failed`/`exhausted`/`superseded`. `superseded` is included so
  a gate whose run was retired by `agent retry` (which supersedes the run without
  re-linking the fresh one) stays discoverable rather than silently pending until
  a coordinator clears the stamp. No wall-clock hang policy is applied."
  [gate-view]
  (let [gate (api/show (rt) (:id gate-view))
        run-id (non-blank (attr gate :gate/run))
        error (non-blank (attr gate :gate/error))
        run (when run-id (api/show (rt) run-id))]
    (cond
      error {:gate (:id gate) :error error}
      (contains? (set stalled-run-phases) (attr run :agent-run/phase))
      {:gate (:id gate) :run run-id :phase (attr run :agent-run/phase)
       :error (attr run :agent-run/error)})))

(defn install!
  "Install the subagent executor's event handler and perform an initial scan.

  Fails loudly unless `skein.spools.agent-run/install!` has already registered
  the agent-run engine in this weaver runtime."
  []
  (let [runtime (rt)
        handlers (set (map :key (events/handlers runtime)))]
    (when-not (contains? handlers :agent-run/engine)
      (fail! "Subagent executor requires the agent-run engine to be installed first" {:handlers handlers}))
    (events/register! runtime :gate/engine event-types
                      'skein.spools.executors.subagent/on-event
                      {:spool "subagent"})
    (workflow/register-executor! :subagent gate-stalled?)
    ;; The human attention surface for stuck gates: an active subagent gate whose
    ;; spawn errored, or whose current delegated run is dead in a terminal phase.
    ;; The `delegates` edge (added beside every `gate/run` stamp) lets the query
    ;; reach the run's `agent-run/phase` — no per-attr join back to `gate/run`
    ;; exists. `stamp-run-on-gate!` marks superseded runs `gate/superseded-by`,
    ;; so excluding those keeps the edge-scoped query in lockstep with the
    ;; current-stamp `gate-stalled?` predicate through the clear-and-respawn flow.
    (graph/register-query! runtime 'stalled-gates
                         [:and [:= :state "active"]
                          [:= [:attr "workflow/gate"] "subagent"]
                          [:or
                           [:and [:exists [:attr "gate/error"]]
                            [:not [:= [:attr "gate/error"] ""]]]
                           [:edge/out "delegates"
                            [:and [:missing [:attr "gate/superseded-by"]]
                             [:in [:attr "agent-run/phase"] stalled-run-phases]]]]])
    (graph/register-query! runtime 'blocked-deliveries
                         [:and [:= :state "closed"]
                          [:exists [:attr "gate/delivery-blocked"]]
                          [:missing [:attr "gate/delivered"]]])
    (scan!)
    {:installed true
     :namespace 'skein.spools.executors.subagent}))
