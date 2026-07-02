(ns skein.spools.treadle
  "Bridge workflow subagent gates to shuttle agent runs.

  The treadle watches workflow runs for ready `:subagent` gates, spawns a
  shuttle run for each gate, and delivers successful run results by completing
  the gate through `skein.spools.workflow/complete!`. It intentionally adds no
  CLI surface and keeps workflow and shuttle decoupled: this namespace is the
  only adapter that knows both vocabularies."
  (:require [clojure.string :as str]
            [skein.spools.shuttle :as shuttle]
            [skein.spools.workflow :as workflow]
            [skein.weaver.api :as api]
            [skein.weaver.runtime :as runtime]))

(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded})

(defonce ^:private scan-monitor (Object.))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- rt []
  (or @runtime/current-runtime
      (fail! "Treadle requires an in-process weaver runtime" {})))

(defn- attr
  "Read attribute `k` tolerating keyword- or string-keyed maps (mirrors
  skein.spools.workflow's tolerant reader for JSON round-tripped strands)."
  [strand k]
  (let [attrs (:attributes strand)]
    (or (get attrs k) (get attrs (subs (str k) 1)))))

(defn- non-blank [s]
  (when (and (string? s) (not (str/blank? s))) s))

(defn- stamp! [id attributes]
  (api/update (rt) id {:attributes attributes}))

(defn- spawn-idempotency-run-for-gate
  "Return an existing live run for gate-id, so a crash between spawn and gate
  stamp re-stamps instead of double-spawning. Failed/exhausted runs are
  deliberately excluded so clearing a gate's `treadle/run` requests a fresh run
  rather than re-adopting the dead one; the trade-off is that a run that failed
  inside that same crash window is orphaned, not re-adopted (treadle.md)."
  [gate-id]
  (first (api/list (rt)
                   [:and [:= [:attr "treadle/gate"] gate-id]
                    [:missing [:attr "treadle/delivered"]]
                    [:not [:= [:attr "shuttle/phase"] "failed"]]
                    [:not [:= [:attr "shuttle/phase"] "exhausted"]]]
                   {})))

(defn- ready-gate? [run-id gate-id]
  (some #(= gate-id (:id %)) (workflow/next-steps run-id)))

(defn- deliver-run! [run]
  (let [run-id (:id run)
        gate-id (attr run :treadle/gate)
        workflow-run-id (attr run :treadle/run-id)]
    (try
      (let [gate (api/show (rt) gate-id)]
        (cond
          (nil? gate)
          (stamp! run-id {"treadle/delivered" "error: gate not found"})

          (= "closed" (:state gate))
          (stamp! run-id {"treadle/delivered" "gate-closed"})

          (ready-gate? workflow-run-id gate-id)
          (do
            (workflow/complete! workflow-run-id
                                (cond-> {:step gate-id :by run-id}
                                  (non-blank (attr run :shuttle/result))
                                  (assoc :notes (attr run :shuttle/result))))
            (stamp! run-id {"treadle/delivered" "true"}))

          :else
          ;; Gate is active but not currently ready (e.g. userland added a
          ;; dependency after spawn). Leave the run undelivered so a later
          ;; scan retries once the gate is ready again, but stamp a durable
          ;; signal — write-once, or the stamp's own update event would
          ;; re-trigger this branch in a loop.
          (when-not (attr run :treadle/delivery-blocked)
            (stamp! run-id {"treadle/delivery-blocked"
                            (str "gate " gate-id " is active but not ready")}))))
      (catch Throwable t
        (stamp! run-id {"treadle/delivered" (str "error: " (ex-message t)
                                                 (some->> (ex-data t) (str " ")))})))))

(defn- finished-undelivered-runs []
  (api/list (rt)
            [:and [:= :state "closed"]
             [:exists [:attr "treadle/gate"]]
             [:missing [:attr "treadle/delivered"]]]
            {}))

(defn- gate-prompt [gate]
  (or (non-blank (attr gate :shuttle/prompt))
      (non-blank (attr gate :workflow/instruction))
      (non-blank (attr gate :description))
      (non-blank (:title gate))))

(defn- treadle-preamble [{:keys [gate run-id prompt]}]
  (str "This run fulfills workflow gate " (:id gate) " (" (:title gate) ") "
       "in workflow run " run-id ".\n"
       "Your final message is captured as the gate's completion record.\n"
       "Do not close or mutate workflow strands yourself; the treadle closes the gate after this run succeeds.\n\n"
       prompt))

(defn- parse-max-attempts [v]
  (cond
    (nil? v) nil
    (integer? v) v
    (string? v) (try
                  (Long/parseLong v)
                  (catch NumberFormatException _
                    (fail! "shuttle/max-attempts must be an integer" {:value v})))
    :else (fail! "shuttle/max-attempts must be an integer" {:value v})))

(defn- ensure-run-stamp! [gate]
  (when-let [run (spawn-idempotency-run-for-gate (:id gate))]
    (api/update (rt) (:id gate) {:attributes {"treadle/run" (:id run)}
                                 :edges [{:type "delegates" :to (:id run)}]})
    (:id run)))

(defn- spawn-for-gate! [run-id gate-view]
  (let [gate (api/show (rt) (:id gate-view))]
    (when-not (or (attr gate :treadle/run) (attr gate :treadle/error))
      (try
        (if (ensure-run-stamp! gate)
          nil
          (let [harness (or (non-blank (attr gate :shuttle/harness))
                            (fail! "subagent gate requires shuttle/harness" {:gate (:id gate)}))
                prompt (or (gate-prompt gate)
                           (fail! "subagent gate requires shuttle/prompt or derivable instruction" {:gate (:id gate)}))
                run (shuttle/spawn-run! {:harness harness
                                         :cwd (attr gate :shuttle/cwd)
                                         :max-attempts (parse-max-attempts (attr gate :shuttle/max-attempts))
                                         :prompt (treadle-preamble {:gate gate :run-id run-id :prompt prompt})
                                         :title (str "Delegated: " (:title gate))
                                         :attrs {"treadle/gate" (:id gate)
                                                 "treadle/run-id" run-id}})]
            (api/update (rt) (:id gate) {:attributes {"treadle/run" (:id run)}
                                         :edges [{:type "delegates" :to (:id run)}]})))
        (catch Throwable t
          (stamp! (:id gate) {"treadle/error" (str (ex-message t)
                                                   (some->> (ex-data t) (str " ")))}))))))

(defn- spawn-ready-gates! []
  (doseq [root (workflow/active-runs)
          :let [run-id (attr root :workflow/run-id)]
          step (workflow/next-steps run-id)
          :when (= "subagent" (:gate step))]
    (try
      (spawn-for-gate! run-id step)
      (catch Throwable t
        (stamp! (:id step) {"treadle/error" (str (ex-message t)
                                                 (some->> (ex-data t) (str " ")))})))))

(defn scan!
  "Deliver finished shuttle runs and spawn ready workflow subagent gates."
  []
  (locking scan-monitor
    (doseq [run (finished-undelivered-runs)]
      (deliver-run! run))
    (spawn-ready-gates!)
    {:scanned true}))

(defn on-event
  "Weaver event handler: graph changes may finish or unblock treadle work."
  [_event]
  (scan!))

(defn gate-stalled?
  "Return durable stall detail for a ready subagent gate view, or nil.

  A gate is stalled when spawn failed onto `treadle/error`, or its stamped run is
  in shuttle phase `failed`/`exhausted`. No wall-clock hang policy is applied."
  [gate-view]
  (let [gate (api/show (rt) (:id gate-view))
        run-id (attr gate :treadle/run)
        run (when run-id (api/show (rt) run-id))]
    (cond
      (attr gate :treadle/error) {:gate (:id gate) :error (attr gate :treadle/error)}
      (contains? #{"failed" "exhausted"} (attr run :shuttle/phase))
      {:gate (:id gate) :run run-id :phase (attr run :shuttle/phase)
       :error (attr run :shuttle/error)})))

(defn install!
  "Install the treadle event handler and perform an initial scan.

  Fails loudly unless `skein.spools.shuttle/install!` has already registered
  the shuttle engine in this weaver runtime."
  []
  (let [runtime (rt)
        handlers (set (map :key (api/event-handlers runtime)))]
    (when-not (contains? handlers :shuttle/engine)
      (fail! "Treadle requires the shuttle engine to be installed first" {:handlers handlers}))
    (api/register-event-handler! runtime :treadle/engine event-types
                                 'skein.spools.treadle/on-event
                                 {:spool "treadle"})
    (workflow/register-stall-predicate! :treadle gate-stalled?)
    (api/register-query! 'stalled-gates
                         [:and [:= :state "active"]
                          [:= [:attr "workflow/gate"] "subagent"]
                          [:exists [:attr "treadle/error"]]])
    (api/register-query! 'blocked-deliveries
                         [:and [:= :state "closed"]
                          [:exists [:attr "treadle/delivery-blocked"]]
                          [:missing [:attr "treadle/delivered"]]])
    (scan!)
    {:installed true
     :namespace 'skein.spools.treadle}))
