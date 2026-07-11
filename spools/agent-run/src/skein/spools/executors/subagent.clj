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
            [skein.api.spool.alpha :refer [fail! attr-get]]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.events.alpha :as events]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.vocab.alpha :as vocab]))

(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded})

(def ^:private stalled-run-phases
  "Terminal agent-run phases that leave a gate's current serving run dead: a
  `failed` or `exhausted` worker. A gate whose current delegated run is in one of
  these is stalled — both `gate-stalled?` and the `stalled-gates` query key off
  this list. `superseded` is deliberately absent: a run `agent retry` retired is
  not the gate's current server (its successor inherits the `serves` edge), so it
  never resolves as the current run in the first place, and the two membership
  rules agree by construction on \"the current serving run is dead.\""
  ["failed" "exhausted"])

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
  spool-tier tolerant reader (`skein.api.spool.alpha/attr-get`)."
  [strand k]
  (attr-get strand k))

(defn- non-blank [s]
  (when (and (string? s) (not (str/blank? s))) s))

(defn- stamp! [id attributes]
  (weaver/update (rt) id {:attributes attributes}))

(defn- current-serving-run
  "The gate's current delegated run, or nil: the single non-superseded run with a
  `serves` edge to the gate (`agent-run/runs-serving`, the C5 resolution rule).
  `agent retry` supersedes the dead run and its fresh successor inherits the
  `serves` edge, so this tracks the live successor with no separate re-link, and
  `gate-stalled?`, the `stalled-gates` query, and the spawn guard all read the
  same run."
  [gate-id]
  (first (agent-run/runs-serving gate-id)))

(defn- spawn-idempotency-run-for-gate
  "Return the gate's current serving run, if any, so a scan that re-observes a
  still-ready gate re-adopts the in-flight run instead of double-spawning. A
  `superseded` run (retired by `agent retry`) is not current, so recovery's fresh
  successor — which inherits the `serves` edge — is what this returns; a
  `failed`/`exhausted` run still counts as delegated, since recovery is retry, not
  auto-respawn."
  [gate-id]
  (current-serving-run gate-id))

(defn- ready-gate? [run-id gate-id]
  (some #(= gate-id (:id %)) (workflow/next-steps run-id)))

(defn- run-served-gate
  "The gate a run delegates: the target of its one outgoing `serves` edge, or nil."
  [run-id]
  (first (map :to_strand_id (graph/outgoing-edges (rt) [run-id] "serves"))))

(defn- deliver-run! [run]
  (let [run-id (:id run)
        gate-id (run-served-gate run-id)
        workflow-run-id (attr run :gate/run-id)]
    (try
      (let [gate (weaver/show (rt) gate-id)]
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
  ;; stale) result must never silently complete the gate; recovery supersedes it
  ;; with a fresh successor that inherits the `serves` edge.
  (weaver/list (rt)
            [:and [:= :state "closed"]
             [:= [:attr "agent-run/phase"] "done"]
             [:edge/out "serves" [:= [:attr "workflow/gate"] "subagent"]]
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

(defn- spawn-for-gate! [run-id gate-view]
  (let [gate (weaver/show (rt) (:id gate-view))]
    ;; Skip when the gate already has a run serving it (in flight, or dead and
    ;; awaiting `agent retry`) or a spawn error: the `serves` edge, written
    ;; atomically with the run, is the sole run↔gate link — there is no separate
    ;; stamp to lose in a crash between spawn and link.
    (when-not (or (spawn-idempotency-run-for-gate (:id gate)) (non-blank (attr gate :gate/error)))
      (try
        (let [harness (or (non-blank (attr gate :agent-run/harness))
                          (fail! "subagent gate requires agent-run/harness" {:gate (:id gate)}))
              prompt (or (gate-prompt gate)
                         (fail! "subagent gate requires agent-run/prompt or derivable instruction" {:gate (:id gate)}))]
          (agent-run/spawn-run! {:harness harness
                                 :cwd (attr gate :agent-run/cwd)
                                 :max-attempts (parse-max-attempts (attr gate :agent-run/max-attempts))
                                 :prompt (subagent-preamble {:gate gate :run-id run-id :prompt prompt})
                                 :title (str "Delegated: " (:title gate))
                                 :serves (:id gate)
                                 :attrs {"gate/run-id" run-id}}))
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

  A gate is stalled when spawn failed onto `gate/error`, or its current serving
  run — the non-superseded run with a `serves` edge to the gate — is dead in
  agent-run phase `failed`/`exhausted`. A run `agent retry` retired is superseded,
  so its fresh successor is the current server; the gate stays discoverable only
  while that server is itself dead, with no re-link step. No wall-clock hang
  policy is applied."
  [gate-view]
  (let [gate (weaver/show (rt) (:id gate-view))
        error (non-blank (attr gate :gate/error))
        run (current-serving-run (:id gate))]
    (cond
      error {:gate (:id gate) :error error}
      (contains? (set stalled-run-phases) (attr run :agent-run/phase))
      {:gate (:id gate) :run (:id run) :phase (attr run :agent-run/phase)
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
    ;; Own the `gate/*` attribute namespace — the treadle-era survivor this
    ;; executor still stamps (all other historical `treadle/*` keys were folded
    ;; into `gate/*`). The activation module owns it, not the file location: the
    ;; source sits in the agent-run package but `:skein/spools-treadle` is the
    ;; use-key. `:keys` is advisory (the keys `deliver-run!`/`spawn-for-gate!`
    ;; stamp), not enforced.
    (vocab/declare! runtime
                    {:kind :attr-namespace
                     :name "gate"
                     :owner :skein/spools-treadle
                     :keys ["gate/delivered" "gate/delivery-blocked" "gate/error" "gate/run-id"]
                     :doc "Subagent-gate delivery and spawn control attributes stamped by the treadle executor."})
    (events/register! runtime :gate/engine event-types
                      'skein.spools.executors.subagent/on-event
                      {:spool "subagent"})
    (workflow/register-executor! :subagent gate-stalled?)
    ;; The human attention surface for stuck gates: an active subagent gate whose
    ;; spawn errored, or whose current delegated run is dead in a terminal phase.
    ;; The gate's incoming `serves` edges reach each delegated run's
    ;; `agent-run/phase`; a superseded run carries `agent-run/phase "superseded"`
    ;; (never `failed`/`exhausted`), so matching a dead phase over `serves` selects
    ;; exactly the gates whose current serving run is dead — by construction in
    ;; lockstep with the `gate-stalled?` predicate, no `gate/superseded-by` bridge.
    (graph/register-query! runtime 'stalled-gates
                         [:and [:= :state "active"]
                          [:= [:attr "workflow/gate"] "subagent"]
                          [:or
                           [:and [:exists [:attr "gate/error"]]
                            [:not [:= [:attr "gate/error"] ""]]]
                           [:edge/in "serves"
                            [:in [:attr "agent-run/phase"] stalled-run-phases]]]])
    (graph/register-query! runtime 'blocked-deliveries
                         [:and [:= :state "closed"]
                          [:exists [:attr "gate/delivery-blocked"]]
                          [:missing [:attr "gate/delivered"]]])
    (scan!)
    {:installed true
     :namespace 'skein.spools.executors.subagent}))
