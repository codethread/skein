(ns skein.spools.cron
  "Generic timer/scheduling engine for the Skein weaver.

  Cron registers named jobs that fire on a fixed interval with uniform jitter,
  each on a spool-owned scheduled executor created at activation and closed when
  the runtime stops (weaver-lifetime, like shuttle's supervision; a
  version-mismatch reload reinitialises it). It knows nothing about any
  particular job: a caller registers
  a job by fully-qualified `:run!` symbol resolving to a `(fn [runtime] ..)`,
  and the engine owns only the timing, the last-outcome/next-fire status
  listing, and a loud inspectable failure log. It is deliberately just timers —
  workflow/gate integration is intentionally out of scope.

  State is runtime-owned via `skein.api.runtime.alpha/spool-state`, so two
  runtimes in one JVM keep independent executors, job tables, and failure logs.
  A job execution that throws is recorded in `failures` and never stops the
  cadence (TEN-003).

  Due-ness reads the runtime clock (`skein.api.runtime.alpha/now`): in
  production that clock tracks the wall clock, so the real scheduled executor
  fires unchanged, but a runtime under a manual clock releases due jobs through
  a registered clock-pump instead of waiting on wall time
  (DELTA-Dtt-001.CC3)."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.spools.util :refer [fail!]])
  (:import [java.time Instant]
           [java.util Random]
           [java.util.concurrent ScheduledFuture ScheduledThreadPoolExecutor
            ThreadFactory TimeUnit]))

(declare execute-job!)

(def ^:private state-version
  "Shape version for cron's runtime spool-state map. Bump whenever `new-state`'s
  key set changes: spool-state survives `reload!`, so a post-upgrade reload
  would otherwise reuse a preserved map missing the new key and schedule against
  a nil executor (docs/writing-shared-spools.md 'Versioned spool state',
  SPEC-004.C95). The `state-shape-matches-declared-version` test fails loudly if
  `new-state` and this version drift apart."
  1)

(defn- ^ThreadFactory daemon-thread-factory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable (str prefix "-" (swap! counter inc)))
          (.setDaemon true))))))

(defn- new-state []
  (let [executor (ScheduledThreadPoolExecutor. 1 (daemon-thread-factory "cron"))]
    ;; Drop cancelled futures from the queue immediately so deregister/reschedule
    ;; do not leak dead tasks for the (long) interval until they would have fired.
    (.setRemoveOnCancelPolicy executor true)
    {:executor executor
     ;; id -> {:id :interval-ms :jitter-ms :run! sym :future :next-fire-at
     ;;        :last-outcome :last-fired-at :last-error}
     :jobs (atom {})
     :failure-log (atom [])
     :rng (Random.)
     :close-fn (fn []
                 (.shutdownNow executor)
                 (when-not (.awaitTermination executor 1000 TimeUnit/MILLISECONDS)
                   (fail! "Cron executor did not stop" {})))}))

(defn- state [runtime]
  (runtime/spool-state runtime ::state {:version state-version} new-state))

(defn- ^ScheduledThreadPoolExecutor executor [runtime] (:executor (state runtime)))
(defn- jobs-atom [runtime] (:jobs (state runtime)))
(defn- failure-log [runtime] (:failure-log (state runtime)))
(defn- ^Random rng [runtime] (:rng (state runtime)))

(defn- record-failure! [runtime entry]
  (let [full (assoc entry :at (str (Instant/now)))]
    (swap! (failure-log runtime) #(->> (conj (vec %) full) (take-last 100) vec))
    full))

(defn failures
  "Return recorded cron failures (seed and execution) for this runtime's weaver
  lifetime, oldest first. Each entry carries `:kind` (`:run` or `:initial-delay`),
  `:job`, a `:message`, and `:at`."
  [runtime]
  @(failure-log runtime))

(defn jitter-offset-ms
  "Return a uniform jitter offset in the range [-bound-ms, bound-ms].

  `rng` is a `java.util.Random`; pass a seeded one for deterministic tests. A
  zero or negative bound yields 0. Exposed so a caller's `:initial-delay-fn` can
  reuse the engine's single jitter definition."
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

(defn- resolve-symbol [role sym]
  (when-not (and (symbol? sym) (namespace sym))
    (fail! (str "Cron job " role " must be a fully qualified symbol") {role sym}))
  (or (requiring-resolve sym)
      (fail! (str "Cron job " role " cannot be resolved") {role sym})))

(defn- present-job
  "Return a job entry as an inspectable status map (dropping the live future)."
  [job]
  (dissoc job :future))

(defn deregister!
  "Cancel a cron job's pending fire and remove it from `runtime`.

  Returns `{:deregistered id}` when the job existed, else `{:deregistered nil}`."
  [runtime id]
  (let [id (job-id id)
        [old _] (swap-vals! (jobs-atom runtime) dissoc id)]
    (when-let [^ScheduledFuture fut (get-in old [id :future])]
      (.cancel fut false))
    {:deregistered (when (contains? old id) id)}))

(defn- schedule-fire! [runtime id delay-ms]
  (let [^Instant now (runtime/now runtime)
        fire-at (.plusMillis now (long delay-ms))
        task ^Runnable (fn [] (execute-job! runtime id))
        fut (.schedule (executor runtime) task (long delay-ms) TimeUnit/MILLISECONDS)]
    ;; Only stamp the future when the job is still registered; a deregister that
    ;; raced this schedule must win, so cancel a future we can no longer track.
    (let [[old _] (swap-vals! (jobs-atom runtime)
                              (fn [jobs]
                                (if (contains? jobs id)
                                  (update jobs id assoc :future fut :next-fire-at (str fire-at))
                                  jobs)))]
      (when-not (contains? old id)
        (.cancel fut false)))
    fut))

(defn- execute-job! [runtime id]
  (when-let [job (get @(jobs-atom runtime) id)]
    (let [run-fn (resolve-symbol :run! (:run! job))
          fired-at (str (Instant/now))
          outcome (try
                    (let [result (run-fn runtime)]
                      (swap! (jobs-atom runtime) update id
                             (fn [j] (when j (assoc j :last-outcome result
                                                    :last-fired-at fired-at
                                                    :last-error nil))))
                      result)
                    (catch Throwable t
                      (record-failure! runtime {:kind :run :job id
                                                :message (ex-message t) :data (ex-data t)})
                      (swap! (jobs-atom runtime) update id
                             (fn [j] (when j (assoc j :last-error (ex-message t)
                                                    :last-fired-at fired-at))))
                      nil))]
      ;; Reschedule regardless of outcome (best-effort cadence), unless the job
      ;; was deregistered while this fire was running.
      (when (contains? @(jobs-atom runtime) id)
        (schedule-fire! runtime id (reschedule-delay-ms (:interval-ms job) (:jitter-ms job) (rng runtime))))
      outcome)))

(defn- due? [^Instant now job]
  (when-let [ts (:next-fire-at job)]
    (not (.isBefore now (Instant/parse ts)))))

(defn- fire-due!
  "Clock-consumer pump: fire every registered job whose `:next-fire-at` is not
  after the runtime clock.

  In production the runtime clock tracks the wall clock, so the real
  `ScheduledFuture` armed by `schedule-fire!` always wins the race and this pump
  finds nothing due. Under a manual clock, `skein.test.alpha/advance!` can move
  the clock past a job's fire instant without any wall time passing, leaving
  the real future armed for a delay that will never elapse; this pump cancels
  that future and executes the job in its place so the fire is released before
  `advance!` returns (DELTA-Dtt-001.CC3)."
  [runtime]
  (let [now (runtime/now runtime)
        due-ids (->> @(jobs-atom runtime) vals (filter #(due? now %)) (mapv :id))]
    (doseq [id due-ids]
      (when-let [job (get @(jobs-atom runtime) id)]
        (when-let [^ScheduledFuture fut (:future job)]
          (.cancel fut false))
        (execute-job! runtime id)))))

(defn- register-pump!
  "Register cron's due-check with the runtime clock-pump registry so
  `skein.test.alpha/advance!` can release due jobs deterministically.

  Registers through the core accessor `skein.core.weaver.runtime/register-clock-pump!`
  rather than poking `:clock-pumps` directly: the clock-pump registry is
  deliberately kept off the alpha surface (DELTA-Dtt-003.D1), and unlike the
  scheduler (whose runtime require-cycle forces a direct poke) cron has no such
  constraint. The accessor fails loudly when the runtime carries no clock-pump
  registry."
  [runtime]
  (weaver-runtime/register-clock-pump! runtime ::pump fire-due!)
  nil)

(defn- initial-delay-ms [runtime job]
  (let [{:keys [id interval-ms jitter-ms initial-delay-fn]} job
        default #(reschedule-delay-ms interval-ms jitter-ms (rng runtime))]
    (if initial-delay-fn
      (let [f (resolve-symbol :initial-delay-fn initial-delay-fn)]
        (try
          (let [d (f runtime)]
            (if (and (integer? d) (not (neg? d)))
              d
              (do (record-failure! runtime {:kind :initial-delay :job id
                                            :message "initial-delay-fn did not return a non-negative delay"
                                            :value d})
                  (default))))
          (catch Throwable t
            ;; Seeding is best-effort coordination: record the failure loudly and
            ;; fall back to the ordinary interval so the cadence still starts.
            (record-failure! runtime {:kind :initial-delay :job id
                                      :message (ex-message t) :data (ex-data t)})
            (default))))
      (default))))

(defn register!
  "Register (or replace) a named cron job on `runtime`'s scheduled executor.

  `job` keys:
  - `:id` — keyword or non-blank string identifying the job. Re-registering the
    same id cancels the existing job's pending fire first.
  - `:interval-ms` — positive integer base period between fires.
  - `:jitter-ms` — non-negative integer; each fire is offset by a uniform value
    in [-jitter, +jitter]. Optional, default 0.
  - `:run!` — fully-qualified symbol resolving to `(fn [runtime] ..)`, invoked on
    every fire. Its return value is recorded as `:last-outcome`; a thrown
    exception is recorded in `failures` and does not stop the cadence.
  - `:initial-delay-fn` — optional fully-qualified symbol resolving to
    `(fn [runtime] -> delay-ms)` for the FIRST fire only (e.g. a seed derived
    from external state). A throw or non-delay result is recorded loudly and the
    engine falls back to interval+jitter. Absent -> interval+jitter.

  Returns the job's status map."
  [runtime job]
  (let [id (job-id (:id job))
        interval (:interval-ms job)
        jitter (or (:jitter-ms job) 0)]
    (when-not (and (integer? interval) (pos? interval))
      (fail! "Cron job :interval-ms must be a positive integer" {:id id :interval-ms interval}))
    (when-not (and (integer? jitter) (not (neg? jitter)))
      (fail! "Cron job :jitter-ms must be a non-negative integer" {:id id :jitter-ms jitter}))
    (resolve-symbol :run! (:run! job))
    (when-let [seed (:initial-delay-fn job)]
      (resolve-symbol :initial-delay-fn seed))
    (deregister! runtime id)
    (let [entry (cond-> {:id id :interval-ms interval :jitter-ms jitter :run! (:run! job)}
                  (:initial-delay-fn job) (assoc :initial-delay-fn (:initial-delay-fn job)))]
      (swap! (jobs-atom runtime) assoc id entry)
      (schedule-fire! runtime id (initial-delay-ms runtime entry)))
    (present-job (get @(jobs-atom runtime) id))))

(defn jobs
  "Return the cron jobs registered on `runtime` as status maps, sorted by id.

  Each map carries `:id`, `:interval-ms`, `:jitter-ms`, the `:run!` symbol,
  `:next-fire-at`, and (once fired) `:last-outcome`/`:last-fired-at`/`:last-error`."
  [runtime]
  (->> @(jobs-atom runtime) vals (map present-job) (sort-by (comp str :id)) vec))

(defn install!
  "Activate cron on the current runtime, creating the scheduled executor.

  Registers no jobs — trusted config registers jobs with `register!`. Also
  (re)registers the clock-consumer pump so deterministic tests can drive due
  jobs off the runtime clock. Called as a no-arg module `:call` at
  startup/reload."
  []
  (let [runtime (current/runtime)]
    (state runtime)
    (register-pump! runtime)
    {:installed true
     :namespace 'skein.spools.cron
     :jobs (mapv :id (jobs runtime))}))
