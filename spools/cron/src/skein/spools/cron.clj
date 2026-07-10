(ns skein.spools.cron
  "Userland recurrence layer over the weaver's durable scheduler wake primitive.

  Cron registers named jobs that fire on a fixed interval with uniform jitter.
  It owns no timing of its own: each registered job is a durable `cron/<id>`
  scheduler wake (`skein.api.scheduler.alpha`), so the cadence survives weaver
  restart and reload, and scheduler introspection is the single timing view. A
  caller registers a job by fully-qualified `:run!` symbol resolving to a
  `(fn [runtime] ..)`; the engine owns only the wake wiring, the job's
  last-outcome status, and a loud inspectable failure log. It is deliberately
  just recurrence — workflow/gate integration is intentionally out of scope.

  Delivery model. The scheduler dispatches a due `cron/<id>` wake to
  `fire-wake` on the weaver's shared serialized event lane. `fire-wake` stays
  tiny so it never holds the lane on job work: it reschedules the next wake and
  hands the job body off to a cron-owned execution executor, then returns so the
  scheduler completes the delivered wake. The job's own success/failure is
  recorded cron-side and never interrupts cadence. Delivery is at-least-once, so
  `:run!` bodies must tolerate duplicate fires (TEN-003, `SPEC-004.C101`).

  State is runtime-owned via `skein.api.runtime.alpha/spool-state`, so two
  runtimes in one JVM keep independent executors, job tables, and failure logs.
  The in-memory job table carries no cadence: it is repopulated by trusted
  config re-running `register!` after each startup/reload, while the durable
  wake in SQLite is the sole authority for when a job next fires."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.scheduler.alpha :as scheduler]
            [skein.spools.util :refer [fail! reject-unknown-keys! require-valid!]])
  (:import [java.time Instant]
           [java.util Random]
           [java.util.concurrent ExecutorService Executors
            ThreadFactory TimeUnit]))

(declare execute-job!)

(def ^:private state-version
  "Shape version for cron's runtime spool-state map. Bump whenever `new-state`'s
  key set changes: spool-state survives `reload!`, so a post-upgrade reload
  would otherwise reuse a preserved map missing the new key and offload against
  a nil executor (docs/writing-shared-spools.md 'Versioned spool state',
  SPEC-004.C95). The `state-shape-matches-declared-version` test fails loudly if
  `new-state` and this version drift apart."
  2)

(defn- ^ThreadFactory daemon-thread-factory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable (str prefix "-" (swap! counter inc)))
          (.setDaemon true))))))

(defn- new-state []
  (let [^ExecutorService executor (Executors/newSingleThreadExecutor
                                   (daemon-thread-factory "cron"))]
    {:executor executor
     ;; id -> {:id :interval-ms :jitter-ms :run! sym
     ;;        :last-outcome :last-fired-at :last-error}
     :jobs (atom {})
     :failure-log (atom [])
     :rng (Random.)
     ;; In-flight offloaded-job latch: a count incremented on the event lane in
     ;; `fire-wake` before submit and decremented in the executor task's finally,
     ;; with a monitor `await-idle!` waits on. `:idle-monitor` is a dedicated
     ;; per-state Object, held for both the count check and the wait.
     :in-flight-count (atom 0)
     :idle-monitor (Object.)
     :close-fn (fn []
                 (.shutdownNow executor)
                 (when-not (.awaitTermination executor 1000 TimeUnit/MILLISECONDS)
                   (fail! "Cron executor did not stop" {})))}))

(defn- state [runtime]
  (runtime/spool-state runtime ::state {:version state-version} new-state))

(defn- ^ExecutorService executor [runtime] (:executor (state runtime)))
(defn- jobs-atom [runtime] (:jobs (state runtime)))
(defn- failure-log [runtime] (:failure-log (state runtime)))
(defn- ^Random rng [runtime] (:rng (state runtime)))

(defn- record-failure! [runtime entry]
  (let [full (assoc entry :at (str (Instant/now)))]
    (swap! (failure-log runtime) #(->> (conj (vec %) full) (take-last 100) vec))
    full))

(defn failures
  "Return recorded cron failures for this runtime's weaver lifetime, oldest
  first. Each entry carries `:kind` (`:run` for a `:run!` throw, `:offload` for
  an execution-executor rejection), `:job`, a `:message`, and `:at`."
  [runtime]
  @(failure-log runtime))

(defn- jitter-offset-ms
  "Return a uniform jitter offset in the range [-bound-ms, bound-ms].

  `rng` is a `java.util.Random`; pass a seeded one for deterministic tests. A
  zero or negative bound yields 0."
  [bound-ms ^Random rng]
  (if (pos? bound-ms)
    (long (Math/round (* (- (* 2.0 (.nextDouble rng)) 1.0) (double bound-ms))))
    0))

(defn- reschedule-delay-ms [interval-ms jitter-ms ^Random rng]
  (max 0 (+ (long interval-ms) (jitter-offset-ms jitter-ms rng))))

(defn- job-id [id]
  (cond
    (keyword? id) id
    (and (string? id) (not (str/blank? id))) (keyword id)
    :else (fail! "Cron job :id must be a keyword or non-blank string" {:id id})))

(defn- wake-key
  "The stable scheduler wake key owning job `id`'s cadence."
  [id]
  (str "cron/" (name id)))

(defn- resolve-symbol [role sym]
  (when-not (and (symbol? sym) (namespace sym))
    (fail! (str "Cron job " role " must be a fully qualified symbol") {role sym}))
  (or (requiring-resolve sym)
      (fail! (str "Cron job " role " cannot be resolved") {role sym})))

(defn- arm-wake!
  "Persist (replacing any existing) the `cron/<id>` wake at `now + interval +
  jitter`, keyed and payloaded so `fire-wake` can rediscover the job."
  [runtime id interval-ms jitter-ms]
  (let [^Instant now (runtime/now runtime)
        delay-ms (reschedule-delay-ms interval-ms jitter-ms (rng runtime))
        wake-at (.plusMillis now (long delay-ms))]
    (scheduler/schedule! runtime {:key (wake-key id)
                                  :wake-at wake-at
                                  :handler 'skein.spools.cron/fire-wake
                                  :payload {:job (name id)}})))

(defn- config-tuple [job]
  [(:interval-ms job) (:jitter-ms job) (:run! job)])

(defn deregister!
  "Cancel a cron job's pending wake and remove it from `runtime`.

  Returns `{:deregistered id}` when the job existed (in-memory config or a
  pending `cron/<id>` wake), else `{:deregistered nil}`. The scheduler `cancel!`
  fails loudly on an unknown key, so the cancel is guarded behind a `pending`
  check for `cron/<id>` — a missing wake is tolerated while genuine scheduler
  errors still surface (`PLAN-cron-on-scheduler-001.R1`)."
  [runtime id]
  (let [id (job-id id)
        key (wake-key id)
        [old _] (swap-vals! (jobs-atom runtime) dissoc id)
        pending? (some #(= key (:key %)) (scheduler/pending runtime))]
    (when pending?
      (scheduler/cancel! runtime key))
    {:deregistered (when (or pending? (contains? old id)) id)}))

(defn- in-flight-count [runtime] (:in-flight-count (state runtime)))

(defn- inc-in-flight! [runtime]
  (swap! (in-flight-count runtime) inc))

(defn- dec-in-flight! [runtime]
  (let [st (state runtime)
        monitor (:idle-monitor st)]
    (swap! (:in-flight-count st) dec)
    ;; :idle-monitor is the dedicated per-state (Object.) monitor; the rule only
    ;; recognises bare-symbol locks and cannot see the stable Object behind the
    ;; local, so it warns on an intentional, correct monitor.
    #_{:splint/disable [lint/locking-object]}
    (locking monitor
      (.notifyAll ^Object monitor))))

(defn- execute-job!
  "Run job `id`'s resolved `:run!` on the execution executor, recording the
  outcome cron-side and always releasing the in-flight latch. Never reschedules —
  cadence was already persisted on the event lane in `fire-wake`."
  [runtime id]
  (try
    (when-let [job (get @(jobs-atom runtime) id)]
      (let [fired-at (str (Instant/now))]
        (try
          (let [run-fn (resolve-symbol :run! (:run! job))
                result (run-fn runtime)]
            (swap! (jobs-atom runtime) update id
                   (fn [j] (when j (assoc j :last-outcome result
                                         :last-fired-at fired-at
                                         :last-error nil)))))
          (catch Throwable t
            (record-failure! runtime {:kind :run :job id
                                      :message (ex-message t) :data (ex-data t)})
            (swap! (jobs-atom runtime) update id
                   (fn [j] (when j (assoc j :last-error (ex-message t)
                                         :last-fired-at fired-at))))))))
    (finally
      (dec-in-flight! runtime))))

(defn fire-wake
  "Scheduler wake handler for a `cron/<id>` fire, run on the shared event lane.

  Invoked by `skein.core.weaver.scheduler/run-fire!` with its context map. Stays
  tiny so it never holds the lane on job work (`PLAN-cron-on-scheduler-001.A2`):
  (1) decode `{:job id}`; (2) look up the in-memory job — absent means the job
  was deregistered, so return without rescheduling; (3) reschedule the next
  `cron/<id>` wake **before** offload, so the cadence is persisted even if the
  offload fails; (4) count the job in-flight and submit its `:run!` to the
  cron-owned execution executor, recording an executor rejection loudly cron-side
  without throwing; (5) return so the scheduler completes the delivered wake. The
  job body never runs on the lane."
  [{:keys [runtime payload]}]
  (when-let [job (get @(jobs-atom runtime) (job-id (:job payload)))]
    (let [id (:id job)]
      (arm-wake! runtime id (:interval-ms job) (:jitter-ms job))
      (inc-in-flight! runtime)
      (try
        (.submit (executor runtime) ^Runnable (fn [] (execute-job! runtime id)))
        (catch Throwable t
          ;; Any submit-time failure — the expected executor shutdown/reload race
          ;; (RejectedExecutionException) or an unexpected throw from a corrupt
          ;; executor — means `execute-job!` never runs to release the latch in its
          ;; finally. The next wake is already armed, so balance the increment we
          ;; took, record the dropped run loudly, and return rather than throwing
          ;; into the lane (`PLAN-cron-on-scheduler-001.R3`).
          (dec-in-flight! runtime)
          (record-failure! runtime {:kind :offload :job id :message (ex-message t)})))))
  nil)

(defn await-idle!
  "Block until every offloaded cron job on `runtime` has finished, then return
  `runtime`.

  The deterministic join for tests: because job bodies run off the event lane,
  `skein.api.events.alpha/await-quiescent!` returns before a job completes. The
  in-flight latch is incremented on the event lane in `fire-wake` before submit,
  so once the lane has quiesced any offloaded job is already counted. Blocks on
  the latch monitor until the count reaches zero or the budget expires, throwing
  loudly on timeout (TEN-003). The default budget comes from
  `skein.spools.test-support/await-budget-ms`; override it with `:timeout-ms`."
  ([runtime] (await-idle! runtime {}))
  ([runtime {:keys [timeout-ms]}]
   (let [st (state runtime)
         counter (:in-flight-count st)
         monitor (:idle-monitor st)
         timeout-ms (or timeout-ms
                        ((requiring-resolve 'skein.spools.test-support/await-budget-ms)))]
     (when-not (and (integer? timeout-ms) (pos? timeout-ms))
       (fail! "await-idle! :timeout-ms must be a positive integer" {:timeout-ms timeout-ms}))
     (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
       ;; :idle-monitor is the dedicated per-state (Object.) monitor; see dec-in-flight!.
       #_{:splint/disable [lint/locking-object]}
       (locking monitor
         (loop []
           (let [remaining (- deadline (System/currentTimeMillis))]
             (cond
               (zero? @counter) runtime
               (<= remaining 0) (fail! "Timed out awaiting cron idle"
                                       {:timeout-ms timeout-ms :in-flight @counter})
               :else (do (.wait ^Object monitor remaining) (recur))))))))))

;; Public seam shape (clojure.spec)
;;
;; `::job` is the declared, discoverable source of truth for `register!`'s job
;; map — the contract downstream config authors write against — matching the
;; sibling reference spools (roster, delegation). `register!` gates each field
;; through `require-valid!` so the specs own the shape while the contextual
;; loud messages survive (failing value + allowed shape, TEN-003), and closes
;; the key set with `reject-unknown-keys!` since `s/keys` stays open. `:id`
;; accepts a keyword or non-blank string (coerced by `job-id`); `:run!` only
;; asserts a fully-qualified symbol here — `resolve-symbol` layers the
;; requiring-resolve check spec cannot express.
(s/def ::id (s/or :keyword keyword?
                  :string (s/and string? (complement str/blank?))))
(s/def ::interval-ms pos-int?)
(s/def ::jitter-ms nat-int?)
(s/def ::run! qualified-symbol?)
(s/def ::job (s/keys :req-un [::id ::interval-ms ::run!] :opt-un [::jitter-ms]))

(def ^:private job-keys
  "The closed key set of a `register!` job map (see `::job`)."
  #{:id :interval-ms :jitter-ms :run!})

(defn register!
  "Register (or replace) a named cron job on `runtime` as a durable wake.

  The `job` map is validated against the `::job` spec (a keyword/non-blank
  `:id`, positive `:interval-ms`, optional non-negative `:jitter-ms`, and a
  fully-qualified `:run!` symbol); unknown keys are rejected loudly.

  `job` keys:
  - `:id` — keyword or non-blank string identifying the job.
  - `:interval-ms` — positive integer base period between fires.
  - `:jitter-ms` — non-negative integer; each fire is offset by a uniform value
    in [-jitter, +jitter]. Optional, default 0.
  - `:run!` — fully-qualified symbol resolving to `(fn [runtime] ..)`, invoked on
    every fire. Its return value is recorded as `:last-outcome`; a thrown
    exception is recorded in `failures` and does not stop the cadence.

  Re-registration preserves a pending `cron/<id>` wake when the cadence-defining
  `[interval-ms jitter-ms run!]` tuple is unchanged, or when the runtime has no
  in-memory config yet (fresh JVM adopting a durable wake). A changed tuple arms
  a fresh wake at `now + interval + jitter`; a missing pending wake also arms a
  fresh wake. Returns the job's status map."
  [runtime job]
  (when-not (map? job)
    (fail! "Cron register! job must be a map" {:job job}))
  (reject-unknown-keys! "Cron register!" job-keys job)
  (require-valid! ::id (:id job)
                  "Cron job :id must be a keyword or non-blank string")
  (require-valid! ::interval-ms (:interval-ms job)
                  "Cron job :interval-ms must be a positive integer")
  (require-valid! ::jitter-ms (or (:jitter-ms job) 0)
                  "Cron job :jitter-ms must be a non-negative integer")
  (require-valid! ::run! (:run! job)
                  "Cron job :run! must be a fully-qualified symbol")
  (let [id (job-id (:id job))
        interval (:interval-ms job)
        jitter (or (:jitter-ms job) 0)]
    (resolve-symbol :run! (:run! job))
    (let [key (wake-key id)
          old-entry (get @(jobs-atom runtime) id)
          pending? (some #(= key (:key %)) (scheduler/pending runtime))
          entry {:id id :interval-ms interval :jitter-ms jitter :run! (:run! job)}
          replace? (or (not pending?)
                       (and old-entry (not= (config-tuple old-entry) (config-tuple entry))))]
      (swap! (jobs-atom runtime) assoc id entry)
      (when replace?
        (arm-wake! runtime id interval jitter))
      (get @(jobs-atom runtime) id))))

(defn jobs
  "Return the cron jobs registered on `runtime` as status maps, sorted by id.

  Each map carries `:id`, `:interval-ms`, `:jitter-ms`, the `:run!` symbol, and
  (once fired) `:last-outcome`/`:last-fired-at`/`:last-error`. When a job next
  fires lives in its durable `cron/<id>` wake — read scheduler introspection
  (`skein.api.scheduler.alpha/pending`), the single timing view."
  [runtime]
  (->> @(jobs-atom runtime) vals (sort-by (comp str :id)) vec))

(defn install!
  "Activate cron on the current runtime, creating the execution executor.

  Registers no jobs — trusted config registers jobs with `register!`. Cron owns
  no timer or clock pump; the scheduler primitive drives every `cron/<id>` wake.
  Called as a no-arg module `:call` at startup/reload."
  []
  (let [runtime (current/runtime)]
    (state runtime)
    {:installed true
     :namespace 'skein.spools.cron
     :jobs (mapv :id (jobs runtime))}))
