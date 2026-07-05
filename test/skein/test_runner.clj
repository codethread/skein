(ns skein.test-runner
  "Explicit test entrypoint with documented serial JVM-global islands,
  subprocess shards for add-libs suites, and per-namespace timing output."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test])
  (:import (java.io StringWriter)
           (java.util.concurrent Callable Executors ExecutorService TimeUnit)))

(def parallel-namespaces
  "Test namespaces that are safe to run concurrently, one namespace per worker."
  ['skein.core.db-test 'skein.core.scheduler-test 'skein.plugin-test 'skein.relations-test
   'skein.spools.bobbin-test 'skein.spools.carder-test 'skein.spools.loom-test 'skein.spools.selvage-test
   'skein.guild-test 'skein.agents-test 'skein.treadle-test 'skein.test.alpha-test 'skein.api.cli.alpha-test
   'skein.spools.batteries-test 'skein.roster-test 'skein.spools.util-test])

(def serial-namespaces
  "JVM-global namespaces the parent still runs serially outside add-libs shards."
  [;; ambient REPL connection atoms and with-redefs.
   'skein.repl-test
   ;; with-redefs for trusted client routing / ambient API behavior.
   'skein.alpha-test
   ;; with-redefs for nREPL timeout behavior.
   'skein.core.client-test
   ;; with-redefs to force batch failure path.
   'skein.spools.workflow-test
   ;; module-local bind! is process-global and loud-failure asserts no published runtime.
   'skein.userland-test
   ;; published singleton semantics.
   'skein.weaver-publication-test
   ;; multiple published peer runtimes verify routing semantics.
   'skein.peers-test
   ;; Event bus order assertions observe process-global delivery under load.
   'skein.weaver-test
   ;; Scheduler dispatch rides the shared event worker and arms real executor
   ;; timers; reuses weaver-test helpers, so it runs beside it serially.
   'skein.scheduler-runtime-test
   ;; Blessed scheduler API tests also arm real executor timers via a real
   ;; runtime for classloader-accurate handler resolution; same reasoning.
   'skein.api.scheduler.alpha-test
   ;; End-to-end scheduler coverage drives real weaver stop/start cycles and
   ;; dispatches graph mutations on the shared lane; same real-timer reasoning.
   'skein.scheduler-e2e-test
   ;; notifier binding and process-output assertions mutate runtime-owned chime state but are flaky under parent parallel load.
   'skein.chime-test])

(def add-libs-shards
  "Subprocess JVM shard groups for tests that mutate JVM-global tools.deps state."
  {;; Largest add-libs suite stands alone to balance wall time against parent work.
   "A" ['skein.spools-test]
   ;; Shuttle-first within this JVM; runtime-deps intentionally poisons the basis, so it is last.
   "B" ['skein.shuttle-test 'skein.chime-sync-test 'skein.runtime-deps-test]
   ;; Medium add-libs suites share one JVM to amortize boot without exceeding shard A.
   "C" ['skein.config-test 'skein.kanban-test]})

(def shard-timeout-minutes 5)

(defn- summary-zero [] test/*initial-report-counters*)
(defn- merge-summaries [& summaries] (apply merge-with + (summary-zero) (map #(dissoc % :type) summaries)))
(defn- bounded-pool-size [] (max 1 (min (count parallel-namespaces) (.availableProcessors (Runtime/getRuntime)))))

(defn- require-namespace! [ns-sym]
  (require ns-sym)
  ns-sym)

(defn- run-namespace [group ns-sym]
  (let [out (StringWriter.) started (System/nanoTime)]
    (binding [test/*report-counters* (ref (summary-zero)) test/*test-out* out]
      ;; Require inside the try so a require-time failure (e.g. add-libs/git-dep
      ;; resolution under load) is reported as an :error in this namespace's
      ;; summary instead of propagating out and exiting the shard with no
      ;; summary at all. run-parallel still pre-requires serially to avoid
      ;; concurrent first-load races; that leaves this require a cheap no-op.
      (let [summary (try (require-namespace! ns-sym)
                         (test/run-tests ns-sym)
                         (catch Throwable t
                           (test/do-report {:type :error :message (str "Uncaught exception while running " ns-sym) :expected nil :actual t})
                           @test/*report-counters*))]
        {:group group :ns ns-sym :summary summary :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000)) :output (str out)}))))

(defn- run-serial [group namespaces] (mapv #(run-namespace group %) namespaces))
(defn- run-parallel []
  (run! require-namespace! parallel-namespaces)
  (let [^ExecutorService pool (Executors/newFixedThreadPool (bounded-pool-size))]
    (try
      (->> parallel-namespaces (mapv #(.submit pool ^Callable (fn [] (run-namespace :parent/parallel %)))) (mapv #(.get %)))
      (finally (.shutdown pool) (.awaitTermination pool 1 TimeUnit/MINUTES)))))

(defn- print-result! [{:keys [group ns summary elapsed-ms output]}]
  (print output) (when-not (.endsWith output "\n") (println))
  (println "Namespace summary:" ns (assoc summary :group group :elapsed-ms elapsed-ms)))

(defn- java-command [shard-id summary-file]
  (let [java-bin (str (System/getProperty "java.home") java.io.File/separator "bin" java.io.File/separator "java")]
    [java-bin
     "--enable-native-access=ALL-UNNAMED"
     "-cp" (System/getProperty "java.class.path")
     "clojure.main" "-m" "skein.test-runner" "--shard" shard-id "--summary-file" summary-file]))

(defn- read-shard-summary [shard-id ^java.io.File summary-file]
  (let [content (when (.isFile summary-file) (slurp summary-file))]
    (when (str/blank? content)
      (throw (ex-info "Shard wrote no summary payload"
                      {:shard shard-id :summary-file (str summary-file)})))
    (edn/read-string content)))

(defn- start-shard! [[shard-id _]]
  (let [summary-file (doto (java.io.File/createTempFile (str "skein-shard-" shard-id "-") ".edn")
                       (.deleteOnExit))
        process (-> (ProcessBuilder. ^java.util.List (java-command shard-id (.getAbsolutePath summary-file)))
                    (.redirectErrorStream true)
                    (.start))]
    ;; Drain stdout from spawn: waiting to read until waitFor would block the
    ;; shard once its output exceeds the OS pipe buffer and misreport the
    ;; write stall as a hung shard at the timeout. The machine summary payload
    ;; travels through summary-file (out-of-band of stdout) so background
    ;; non-test thread chatter can never split or corrupt it.
    {:shard shard-id
     :process process
     :summary-file summary-file
     :output-future (future (with-open [reader (io/reader (.getInputStream process))]
                              (slurp reader)))}))

(defn- await-shard!
  "Wait for one shard and return its outcome map; never throws.

  Failure outcomes carry :error with shard attribution so the parent can print
  every shard's output before exiting non-zero."
  [{:keys [shard process output-future summary-file]}]
  (if (.waitFor process shard-timeout-minutes TimeUnit/MINUTES)
    (let [exit (.exitValue process)
          output @output-future
          parsed (try
                   (read-shard-summary shard summary-file)
                   (catch Throwable t
                     {:parse-error (ex-message t)}))]
      (cond
        (:parse-error parsed)
        {:shard shard :output output
         :error {:shard shard :exit exit :reason (:parse-error parsed)}}

        (not (zero? exit))
        (assoc parsed :shard shard :output output
               :error {:shard shard :exit exit :reason "shard subprocess exited non-zero"})

        :else
        (assoc parsed :shard shard :output output)))
    (do (.destroyForcibly process)
        {:shard shard
         :output (deref output-future 5000 "")
         :error {:shard shard :reason (str "timed out after " shard-timeout-minutes " minutes")}})))

(defn- run-shard-subprocesses! []
  (let [started (mapv start-shard! add-libs-shards)]
    (try
      (mapv await-shard! started)
      (finally
        (doseq [{:keys [process ^java.io.File summary-file]} started]
          (when (.isAlive process) (.destroyForcibly process))
          (.delete summary-file))))))

(defn- start-shards-thread! []
  (let [result (promise)
        thread (Thread. (fn []
                          (try
                            (deliver result {:ok (run-shard-subprocesses!)})
                            (catch Throwable t
                              (deliver result {:error t}))))
                        "skein-add-libs-shards")]
    (.start thread)
    result))

(defn- print-shard! [{:keys [shard output summary elapsed-ms error]}]
  (println "\n=== add-libs shard" shard "output ===")
  (print (or output "")) (when-not (.endsWith (or output "") "\n") (println))
  (println "=== add-libs shard" shard "summary ==="
           (cond-> (assoc (or summary {}) :shard shard :elapsed-ms elapsed-ms)
             error (assoc :error error))))

(defn- run-parent []
  (concat (run-serial :parent/serial serial-namespaces) (run-parallel)))

(defn- run-shard [shard-id summary-file]
  (when-not summary-file
    (throw (ex-info "Shard mode requires --summary-file" {:shard shard-id})))
  (let [namespaces (get add-libs-shards shard-id)]
    (when-not namespaces
      (throw (ex-info "Unknown add-libs shard" {:shard shard-id :known-shards (sort (keys add-libs-shards))})))
    (let [started (System/nanoTime)
          results (run-serial (keyword "shard" shard-id) namespaces)
          summary (apply merge-summaries (map :summary results))
          payload {:shard shard-id
                   :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))
                   :summary summary
                   :timings (into {} (map (juxt :ns #(select-keys % [:group :elapsed-ms]))) results)}]
      (doseq [result results] (print-result! result))
      (println "\nNamespace timings (ms):" (:timings payload))
      (println "Aggregate summary:" summary)
      ;; Machine payload goes to the parent-provided sidecar file, never
      ;; stdout: the parent reads it only after waitFor, so the fully-flushed
      ;; spit is immune to stdout interleaving by non-test background threads.
      (spit summary-file (pr-str payload))
      (flush)
      ;; Explicit exit either way: drain/agent pool threads are non-daemon and
      ;; would otherwise hold the shard JVM (and the parent's waitFor) ~60s.
      (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0)))))

(defn- parse-args [args]
  (case (first args)
    nil {:mode :parent}
    "--shard" (let [[_ shard & opts] args
                    opt-map (apply hash-map opts)]
                {:mode :shard :shard shard :summary-file (get opt-map "--summary-file")})
    (throw (ex-info "Unknown test-runner arguments" {:args args}))))

(defn -main [& args]
  (let [{:keys [mode shard summary-file]} (parse-args args)]
    (case mode
      :shard (run-shard shard summary-file)
      :parent (let [shards-result (start-shards-thread!)
                    parent-results (run-parent)
                    shard-outcome @shards-result
                    shard-results (:ok shard-outcome)
                    shard-failures (keep :error shard-results)
                    summary (apply merge-summaries (concat (map :summary parent-results)
                                                           (keep :summary shard-results)))]
                ;; Print everything gathered before deciding the exit code:
                ;; failures are exactly when this output is needed for triage.
                (doseq [result parent-results] (print-result! result))
                (doseq [shard-result shard-results] (print-shard! shard-result))
                (println "\nNamespace timings (ms):"
                         {:parent (into {} (map (juxt :ns #(select-keys % [:group :elapsed-ms]))) parent-results)
                          :shards (into {} (map (juxt :shard #(select-keys % [:elapsed-ms :timings]))) shard-results)})
                (println "Aggregate summary:" summary)
                (when-let [error (:error shard-outcome)]
                  (println "\nadd-libs shard runner crashed before producing outcomes:" (ex-message error))
                  (System/exit 1))
                (doseq [failure shard-failures]
                  (println "add-libs shard failure:" failure))
                (flush)
                ;; Explicit exit either way: the shard drain futures leave
                ;; non-daemon pool threads that would hold the JVM ~60s.
                (System/exit (if (or (seq shard-failures)
                                     (pos? (+ (:fail summary) (:error summary))))
                               1
                               0))))))
