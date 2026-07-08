(ns skein.spools.reed
  "Fulfil workflow `:shell` gates by running their command off the event thread.

  A reed watches workflow runs for ready gates whose waiter is `:shell`, runs the
  gate's `shell/argv` directly (no implicit shell) on a spool-owned worker pool,
  and closes the gate through `skein.spools.workflow/complete!` on a zero exit. A
  non-zero exit, timeout, spawn error, or invalid argv stamps a loud, distinct
  `shell/error` and leaves the gate ready and stamped rather than masquerading as
  a completed run. It is a treadle sibling minus everything shuttle-specific: the
  failure detail lives on the gate itself, so there is no separate run strand, no
  `delegates` edge, and no session/harness vocabulary. This namespace is the only
  adapter that knows both the workflow gate contract and process execution."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.spools.workflow :as workflow]
            [skein.spools.util :refer [fail! attr-get]]
            [skein.api.weaver.alpha :as api]
            [skein.api.events.alpha :as events]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime])
  (:import [java.lang ProcessHandle]
           [java.nio.charset StandardCharsets]
           [java.time Instant]
           [java.util.concurrent Executors ExecutorService ThreadFactory TimeUnit]))

(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded})

(def ^:private output-tail-bytes
  "Fixed cap on captured combined stdout+stderr: reed retains only the last N
  bytes so a runaway child cannot exhaust weaver heap (`PLAN-ShellGates-001.R3`)."
  (* 16 1024))

(def ^:private timeout-reader-drain-ms
  "Maximum extra wait for the stdout/stderr reader after a timeout kill.

  This keeps `shell/timeout-secs` a true wall-clock-ish bound even when a
  descendant process inherited the merged output pipe and delays EOF."
  250)

(def ^:private timeout-output-marker
  "\n[reed: output truncated after timeout while waiting for process pipes to close]\n")

(def ^:dynamic *runtime*
  "Runtime captured for asynchronous reed worker threads."
  nil)

(defn- rt []
  (or *runtime* (current/runtime)))

(def ^:private state-version
  "Shape version for the reed's runtime spool-state map. Bump whenever
  `new-state`'s key set changes: spool-state survives `reload!`, so a
  post-upgrade reload would otherwise reuse a preserved map missing the new key.
  The `state-shape-matches-declared-version` test guards against silent drift."
  1)

(defn- daemon-thread-factory ^ThreadFactory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (swap! counter inc)))
          (.setDaemon true))))))

(defn- new-state []
  (let [^ExecutorService workers (Executors/newCachedThreadPool (daemon-thread-factory "reed-worker"))]
    {:scan-monitor (Object.)
     :worker-executor workers
     :close-fn (fn []
                 (.shutdownNow workers)
                 (when-not (.awaitTermination workers 1000 TimeUnit/MILLISECONDS)
                   (fail! "Reed worker executor did not stop" {})))}))

(defn- state []
  (runtime/spool-state (rt) ::state {:version state-version} new-state))

(defn- scan-monitor [] (:scan-monitor (state)))

(defn- worker-executor ^ExecutorService []
  (or (:worker-executor (state))
      (fail! "Reed worker executor is missing from spool state" {})))

(defn- attr [strand k]
  (attr-get strand k))

(defn- stamp! [id attributes]
  (api/update (rt) id {:attributes attributes}))

(defn- now [] (str (Instant/now)))

;; ---------------------------------------------------------------------------
;; Gate attribute contract

(defn- parse-argv
  "Return the gate's `shell/argv` as a validated `List<String>`, or fail loudly
  (TEN-003) so no process spawns. Missing, non-array, empty, or non-string-element
  argv is a hard error stamped onto `shell/error`."
  [gate]
  (let [argv (attr gate :shell/argv)]
    (when-not (sequential? argv)
      (fail! "shell/argv must be a JSON array of strings" {:gate (:id gate) :argv argv}))
    (when (empty? argv)
      (fail! "shell/argv must be a non-empty array" {:gate (:id gate)}))
    (when-not (every? string? argv)
      (fail! "shell/argv elements must all be strings" {:gate (:id gate) :argv argv}))
    (vec argv)))

(defn- parse-timeout
  "Return the gate's `shell/timeout-secs` as a positive long, nil when absent, or
  fail loudly on a non-positive/non-integer value — reed never silently clamps."
  [gate]
  (let [v (attr gate :shell/timeout-secs)]
    (cond
      (nil? v) nil
      (and (integer? v) (pos? v)) (long v)
      :else (fail! "shell/timeout-secs must be a positive integer" {:gate (:id gate) :value v}))))

(defn- parse-cwd
  "Return the optional `shell/cwd` string, or fail loudly on malformed values."
  [gate]
  (let [v (attr gate :shell/cwd)]
    (cond
      (nil? v) nil
      (and (string? v) (not (str/blank? v))) v
      :else (fail! "shell/cwd must be a non-blank string" {:gate (:id gate) :value v}))))

;; ---------------------------------------------------------------------------
;; Process execution (worker thread only)

(defn- drain-tail!
  "Fully drain `in`, returning the last `limit` bytes decoded as UTF-8. A ring
  buffer caps retention at `limit`, so a child that writes without bound cannot
  exhaust heap; the whole stream is never buffered."
  ^String [^java.io.InputStream in ^long limit]
  (let [^bytes ring (byte-array limit)
        ^bytes chunk (byte-array 8192)]
    (loop [total 0]
      (let [n (.read in chunk 0 (alength chunk))]
        (if (neg? n)
          (let [kept (int (min total limit))
                start (int (mod (- total kept) limit))
                ^bytes out (byte-array kept)
                first-run (int (min kept (- limit start)))]
            (System/arraycopy ring start out 0 first-run)
            (when (< first-run kept)
              (System/arraycopy ring 0 out first-run (- kept first-run)))
            (String. out StandardCharsets/UTF_8))
          (let [p (int (mod total limit))
                head (int (min n (- limit p)))]
            ;; A single read returns at most 8192 bytes < limit, so the write
            ;; wraps the ring at most once.
            (System/arraycopy chunk 0 ring p head)
            (when (< head n)
              (System/arraycopy chunk head ring 0 (- n head)))
            (recur (+ total (long n)))))))))

(defn- destroy-process-tree! [^Process process]
  (doseq [^ProcessHandle descendant (iterator-seq (.iterator (.descendants (.toHandle process))))]
    (try
      (.destroyForcibly descendant)
      (catch Throwable _
        nil)))
  (.destroyForcibly process))

(defn- timeout-output [reader]
  (let [output (deref reader timeout-reader-drain-ms ::timed-out)]
    (if (= ::timed-out output)
      {:output timeout-output-marker :output-truncated? true}
      {:output output})))

(defn- execute!
  "Run `argv` (a `List<String>`) directly with no implicit shell, capturing a
  bounded combined stdout+stderr tail. Returns `{:exit int :output str}` on
  natural exit, or `{:timeout? true :exit int :output str}` when a
  `timeout-secs` bound elapses and the process tree is force-killed."
  [argv cwd timeout-secs]
  (let [pb (doto (ProcessBuilder. ^java.util.List argv)
             (.redirectErrorStream true))]
    (when cwd
      (.directory pb (io/file cwd)))
    (let [^Process process (.start pb)]
      ;; Signal stdin EOF so a child that reads stdin exits instead of hanging
      ;; the worker until the timeout bound.
      (.close (.getOutputStream process))
      (let [reader (future (drain-tail! (.getInputStream process) output-tail-bytes))]
        (if timeout-secs
          (if (.waitFor process (long timeout-secs) TimeUnit/SECONDS)
            {:exit (.exitValue process) :output @reader}
            (do (destroy-process-tree! process)
                (.waitFor process)
                (merge {:timeout? true :exit (.exitValue process)}
                       (timeout-output reader))))
          (do (.waitFor process)
              {:exit (.exitValue process) :output @reader}))))))

;; ---------------------------------------------------------------------------
;; Terminal outcomes (worker thread only)

(defn- pass!
  "Close the gate on a zero exit through ordinary workflow vocabulary, recording
  the shell outcome in the same batch. Stamping the exit code, bounded output, and
  cleared claim as `complete!` `:attributes` closes the gate and records its
  outcome atomically, so no observer ever sees a closed gate without its
  `shell/exit-code`/`shell/output`, and leaving the ready frontier atomically
  stops any concurrent scan re-dispatching the check."
  [run-id gate-id exit output]
  (workflow/complete! run-id
                      {:step gate-id :by "shell"
                       :notes (str "shell command exited " exit)
                       :attributes (cond-> {"shell/running" nil "shell/exit-code" exit}
                                     (some? output) (assoc "shell/output" output))}))

(defn- fail-gate!
  "Stamp a loud, distinct `shell/error` (with `shell/exit-code`/`shell/output`
  where a process ran) and clear the claim in one atomic update, leaving the gate
  ready and stamped. The `shell/error` presence makes reed skip the gate until a
  coordinator clears it."
  [gate-id detail exit output]
  (stamp! gate-id (cond-> {"shell/running" nil "shell/error" detail}
                    (some? exit) (assoc "shell/exit-code" exit)
                    (some? output) (assoc "shell/output" output))))

(defn- run-gate!
  "Execute one claimed `:shell` gate on the worker thread and stamp its outcome."
  [run-id gate-id]
  (try
    (let [gate (api/show (rt) gate-id)
          argv (parse-argv gate)
          timeout-secs (parse-timeout gate)
          cwd (parse-cwd gate)
          {:keys [exit output timeout? output-truncated?]} (execute! argv cwd timeout-secs)]
      (cond
        timeout? (fail-gate! gate-id (cond-> (str "shell command timed out after " timeout-secs "s")
                                       output-truncated? (str "; output truncated while waiting for process pipes to close"))
                             exit output)
        (zero? exit) (pass! run-id gate-id exit output)
        :else (fail-gate! gate-id (str "shell command exited " exit) exit output)))
    (catch Throwable t
      (fail-gate! gate-id (str (ex-message t) (some->> (ex-data t) (str " "))) nil nil))))

;; ---------------------------------------------------------------------------
;; Event-driven scan

(defn- claim-and-dispatch!
  "Idempotently claim a ready, un-errored, un-claimed `:shell` gate by stamping a
  `shell/running` marker before dispatch, then submit the actual process run to
  the worker pool. The event thread never blocks on a child process.

  The gate is re-read fresh (not trusted from the ready snapshot, which a
  concurrent close can outrace) and must still be `active`: `pass!` clears the
  claim and closes the gate in one atomic batch, and `fail-gate!` clears the
  claim while stamping `shell/error` — so every claim-clearing transition also
  either closes the gate or stamps an error, and this guard blocks re-dispatch
  in all three cases."
  [runtime run-id gate-view]
  (let [gate (api/show (rt) (:id gate-view))]
    (when (and (= "active" (:state gate))
               (not (attr gate :shell/error))
               (not (attr gate :shell/running)))
      (stamp! (:id gate) {"shell/running" (now)})
      (.execute (worker-executor)
                ^Runnable (fn []
                            (current/with-runtime runtime
                              (binding [*runtime* runtime]
                                (run-gate! run-id (:id gate)))))))))

(defn scan!
  "Dispatch every ready `:shell` gate not already claimed or errored.

  Enumerates ready gates purely through the workflow surface and serializes on a
  runtime-owned monitor so concurrent scans cannot double-launch a gate."
  []
  (let [runtime (rt)]
    (binding [*runtime* runtime]
      ;; scan-monitor returns the runtime-owned (Object.) monitor; the rule only
      ;; recognises bare-symbol locks and can't see the stable Object behind it.
      #_{:splint/disable [lint/locking-object]}
      (locking (scan-monitor)
        (doseq [root (workflow/active-runs)
                :let [run-id (attr root :workflow/run-id)]
                step (workflow/next-steps run-id)
                :when (= "shell" (:gate step))]
          (claim-and-dispatch! runtime run-id step))
        {:scanned true}))))

(defn on-event
  "Weaver event handler: graph changes may make a `:shell` gate ready."
  [_event]
  (scan!))

(defn gate-stalled?
  "Return durable stall detail for a ready `:shell` gate view, or nil.

  The failure detail lives on the gate itself (`shell/error`), so — unlike
  treadle — there is no `delegates`-edge join back to a separate run row."
  [gate-view]
  (let [gate (api/show (rt) (:id gate-view))]
    (when-let [error (attr gate :shell/error)]
      {:gate (:id gate) :error error})))

(defn install!
  "Install the reed event handler, register the `:shell` executor and the
  `stalled-shell-gates` coordinator query, and perform an initial scan."
  []
  (let [runtime (rt)]
    (events/register! runtime :reed/engine event-types
                      'skein.spools.reed/on-event
                      {:spool "reed"})
    (workflow/register-executor! :shell gate-stalled?)
    ;; The coordinator attention surface for stuck shell gates: an active `:shell`
    ;; gate carrying `shell/error`. No delegates-edge join is needed because the
    ;; failure detail lives on the gate itself.
    (api/register-query! runtime 'stalled-shell-gates
                         [:and [:= :state "active"]
                          [:= [:attr "workflow/gate"] "shell"]
                          [:exists [:attr "shell/error"]]])
    (scan!)
    {:installed true
     :namespace 'skein.spools.reed}))
