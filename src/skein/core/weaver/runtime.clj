(ns skein.core.weaver.runtime
  "Start, stop, and supervise the in-process weaver daemon runtime."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nrepl.server :as nrepl]
            [skein.core.weaver.config :as config]
            [skein.core.weaver.metadata :as metadata]
            [skein.core.weaver.scheduler :as scheduler]
            [skein.core.weaver.socket :as socket]
            [skein.core.db :as db])
  (:import [java.lang ProcessHandle]
           [java.time Instant]
           [java.util.concurrent ArrayBlockingQueue TimeUnit]))

(def ^:private loopback-host "127.0.0.1")

(defonce ^{:doc "Atom containing the published ambient weaver runtime map for this process, or nil."} current-runtime
  (atom nil))

(def ^:dynamic *runtime*
  "Dynamically bound runtime for trusted in-process startup, reload, and nREPL code."
  nil)

(defonce ^:private nrepl-port-runtimes
  (atom {}))

(def event-queue-capacity
  "Maximum number of queued weaver events before enqueueing fails."
  1024)
(def ^:private recent-event-failure-limit
  "Maximum number of recent event handler failures retained in memory."
  100)

(declare stop! with-spool-classloader with-runtime-binding)

(defn- event-system-base []
  {:handler-registry (atom {})
   :recent-failures (atom [])
   :queue (ArrayBlockingQueue. event-queue-capacity)
   :running? (atom true)
   :worker (atom nil)})

(defn stop-event-system!
  "Stop the runtime event worker and clear queued events."
  [runtime]
  (when-let [{:keys [queue running? worker]} (:event-system runtime)]
    (reset! running? false)
    (.clear ^ArrayBlockingQueue queue)
    (when-let [worker-thread @worker]
      (.interrupt ^Thread worker-thread)
      (.join ^Thread worker-thread 1000)
      (reset! worker nil)))
  nil)

(defn- run-event-worker! [runtime event-system]
  (let [worker (Thread.
                (fn []
                  (try
                    (while @(:running? event-system)
                      (when-let [event (.poll ^ArrayBlockingQueue (:queue event-system) 100 TimeUnit/MILLISECONDS)]
                        (when @(:running? event-system)
                          (if (= :scheduler/fire (:event/type event))
                            ;; Clock-triggered wakes share this serialized lane
                            ;; rather than a second worker. run-fire! records its
                            ;; own completion/failure history and never throws
                            ;; into the worker; the guard is defence in depth.
                            (try
                              (with-runtime-binding
                                runtime
                                #(with-spool-classloader runtime (fn [] (scheduler/run-fire! runtime event))))
                              (catch Throwable _ nil))
                            ;; :fn stays un-destructured: a local named `fn` would
                            ;; shadow the fn macro in the handler thunks below.
                            (doseq [{:keys [key types fn-value] :as handler} (vals @(:handler-registry event-system))
                                    :when (contains? types (:event/type event))]
                              (try
                                (with-runtime-binding
                                  runtime
                                  #(with-spool-classloader runtime (fn [] (fn-value event))))
                                (catch Throwable t
                                  (let [failure {:handler/key key
                                                 :handler/fn (:fn handler)
                                                 :event/id (:event/id event)
                                                 :event/type (:event/type event)
                                                 :exception/message (ex-message t)
                                                 :failed/at (str (Instant/now))}]
                                    (swap! (:recent-failures event-system)
                                           #(->> (conj % failure)
                                                 (take-last recent-event-failure-limit)
                                                 vec))))))))))
                    (catch InterruptedException _ nil)))
                "skein-event-worker")]
    (.setDaemon worker true)
    (.start worker)
    (reset! (:worker event-system) worker)
    nil))

(defn start-event-system!
  "Attach and start a fresh event system on `runtime`."
  [runtime]
  (let [event-system (event-system-base)
        runtime* (assoc runtime :event-system event-system)]
    (run-event-worker! runtime* event-system)
    runtime*))

(defn resume-event-system!
  "Start event dispatch for the runtime's current event system."
  [runtime]
  (let [{:keys [running?] :as event-system} (:event-system runtime)]
    (reset! running? true)
    (run-event-worker! runtime event-system)
    nil))

(defn clear-event-system-for-reload!
  "Stop event dispatch and clear handlers, queued events, and failures."
  [runtime]
  (let [{:keys [handler-registry recent-failures queue running?]} (:event-system runtime)]
    (stop-event-system! runtime)
    (reset! handler-registry {})
    (reset! recent-failures [])
    (.clear ^ArrayBlockingQueue queue)
    (reset! running? false)
    nil))

(defn restart-event-system!
  "Reset handlers, failures, and worker state for the runtime event system."
  [runtime]
  (clear-event-system-for-reload! runtime)
  (resume-event-system! runtime))

(defn- current-pid
  "Return the current OS process id."
  []
  (.pid (ProcessHandle/current)))

(def ^:private startup-file-names
  ["init.clj" "init.local.clj"])

(defn startup-files
  "Return present selected-workspace startup files in load order."
  [world]
  (into []
        (keep (fn [name]
                (let [file (io/file (:config-dir world) name)]
                  (when (.isFile file)
                    {:name name
                     :file (.getCanonicalPath file)}))))
        startup-file-names))

(defn with-runtime-binding
  "Call `f` with runtime as the thread-local ambient runtime."
  [runtime f]
  (binding [*runtime* runtime]
    (f)))

(defn runtime-for-nrepl-port
  "Return the runtime serving an nREPL server port, or fail loudly when unknown."
  [port]
  (or (get @nrepl-port-runtimes port)
      (throw (ex-info "No Skein runtime registered for nREPL port" {:port port}))))

(defn load-startup-files!
  "Load present selected-workspace startup files in startup order.

  Missing startup files are skipped. Present files that fail to read or evaluate
  throw with file path context so startup/reload abort loudly. Returns entries
  containing each loaded file path and its final return value."
  [runtime world]
  (with-runtime-binding
    runtime
    (fn []
      (mapv (fn [{:keys [file] :as startup-file}]
              (try
                (assoc startup-file :return (with-spool-classloader runtime #(load-file file)))
                (catch Throwable t
                  (throw (ex-info "Selected workspace startup file failed to load"
                                  {:config-dir (:config-dir world)
                                   :file file}
                                  t)))))
            (startup-files world)))))

(defn install-built-in-ops!
  "Install Skein's built-in CLI ops, resolving the api-tier registrar dynamically.

  The op registry and its built-ins are owned by `skein.api.weaver.alpha`, above
  this core namespace; `requiring-resolve` keeps the require graph pointing
  downward while startup and reload share one install path."
  [runtime]
  (with-runtime-binding runtime #((requiring-resolve 'skein.api.weaver.alpha/register-built-in-ops!) runtime)))

(defn- clear-reload-state! [runtime]
  (reset! (:approved-spool-sync-state runtime) {})
  (reset! (:module-use-state runtime) {})
  (reset! (:query-registry runtime) {})
  (reset! (:view-registry runtime) {})
  (reset! (:pattern-registry runtime) {})
  (reset! (:op-registry runtime) {})
  (reset! (:hook-registry runtime) {})
  (clear-event-system-for-reload! runtime)
  (install-built-in-ops! runtime))

(defn reload-config!
  "Reload selected config-dir startup files after clearing runtime registries.

  Clears every weaver-lifetime registry and the event system, reinstalls the
  built-in ops, then reloads `init.clj`/`init.local.clj` and re-arms the
  scheduler so handlers newly supplied by reloaded spools/config resolve before
  any durable pending wake fires. This is `skein.api.runtime.alpha/reload!`'s
  implementation."
  [runtime]
  (try
    (clear-reload-state! runtime)
    (let [world {:config-dir (get-in runtime [:metadata :config-dir])}
          files (load-startup-files! runtime world)]
      (resume-event-system! runtime)
      ;; Re-arm after config reload so handlers newly supplied by reloaded
      ;; spools/config resolve; rearm! also discards fire envelopes the reload
      ;; flushed from the event queue (DELTA-weaver-scheduler-runtime-001.CC5).
      (scheduler/rearm! runtime)
      {:status :loaded
       :files files
       :returns (mapv :return files)})
    (catch Throwable t
      ;; Do not re-clear on failure. The initial clear-reload-state! already
      ;; reinstalled the built-in ops, and startup files register userland ops
      ;; incrementally, so a spool install that throws midway would otherwise
      ;; take every already-registered op down with it — the "zero useful ops
      ;; until a manual atom reset" cliff. Leave whatever loaded so the world
      ;; stays operable, resume dispatch, and rethrow the failure loudly.
      (resume-event-system! runtime)
      (scheduler/rearm! runtime)
      (throw t))))

(defn- with-spool-classloader [runtime f]
  (let [thread (Thread/currentThread)
        previous-loader (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread (:spool-classloader runtime))
      (f)
      (finally
        (.setContextClassLoader thread previous-loader)))))

(defn with-runtime-and-spool-classloader
  "Call `f` with runtime ambiently bound and the runtime spool classloader as
  the thread's context classloader, matching trusted startup-file evaluation.

  Also rebinds Compiler/LOADER onto the spool classloader: inside an outer
  eval (such as an nREPL session) a compiler loader is already bound, so the
  context classloader alone would not let require/load in `f` see synced spool
  sources."
  [runtime f]
  (with-runtime-binding
    runtime
    #(with-spool-classloader
       runtime
       (fn []
         (clojure.lang.Var/pushThreadBindings
          {clojure.lang.Compiler/LOADER (clojure.lang.DynamicClassLoader. (:spool-classloader runtime))})
         (try
           (f)
           (finally
             (clojure.lang.Var/popThreadBindings)))))))

(defn- default-name [world]
  (.getName (io/file (:config-dir world))))

(defn- close-spool-state!
  "Close runtime-owned spool state resources before storage disappears."
  ([runtime] (close-spool-state! runtime nil))
  ([runtime startup-error]
   (doseq [[key value] @(:spool-state runtime)
           :let [close-fn (:close-fn value)]
           :when close-fn]
     (try
       (close-fn)
       (catch Throwable t
         (let [close-error (ex-info "Spool state close hook failed"
                                    {:spool-state/key key
                                     :exception/message (ex-message t)}
                                    t)]
           (if startup-error
             (.addSuppressed ^Throwable startup-error close-error)
             (throw close-error))))))
   nil))

(defn- close-storage!
  "Close weaver-owned storage resources for `runtime`, when the handle has any."
  [runtime]
  (when-let [close-fn (get-in runtime [:storage :close-fn])]
    (close-fn))
  nil)

(defn- storage-for
  "Normalize trusted storage selection into a storage handle."
  [storage db-file world]
  (case (or storage :sqlite-file)
    :sqlite-file (db/file-storage (or db-file (:db-path world)))
    :sqlite-memory (if db-file
                     (throw (ex-info "In-memory weaver storage does not take a database file"
                                     {:storage storage :db-file db-file}))
                     (db/memory-storage))
    (throw (ex-info "Unknown weaver storage kind" {:storage storage}))))

(defn start!
  "Start a weaver runtime for `db-file` and optional `world`.

  Publishes metadata, starts nREPL and JSON socket transports, loads trusted
  config, and by default publishes the runtime as this process's ambient runtime.
  Set `:publish? false` to start an unpublished runtime that can coexist with
  other runtimes in the same JVM. Trusted callers may select `:storage
  :sqlite-memory` for a weaver-lifetime in-memory database; file-backed SQLite
  in the selected workspace remains the default."
  ([] (start! nil {}))
  ([db-file] (start! db-file {}))
  ([db-file {:keys [world name publish? storage] :or {publish? true}}]
   (when (and publish? @current-runtime)
     (throw (ex-info "A weaver runtime is already active in this process" {:metadata (:metadata @current-runtime)})))
   (let [world (or world (config/world))
         existing (metadata/read-metadata world)
         socket-file (metadata/socket-file world)]
     (when-not (metadata/stale-or-missing? existing)
       (throw (ex-info "Weaver metadata already exists for weaver world" {:config-dir (:config-dir world)
                                                                          :metadata existing})))
     (when (and (nil? existing) (.exists socket-file))
       (throw (ex-info "Weaver socket exists without metadata; cannot prove weaver world is stale" {:config-dir (:config-dir world)
                                                                                                    :socket-path (.getPath socket-file)})))
     (.mkdirs (io/file (:state-dir world)))
     (.mkdirs (io/file (:data-dir world)))
     (let [storage (storage-for storage db-file world)
           ds (:connectable storage)
           _ (db/init! ds)
           server (nrepl/start-server :bind loopback-host :port 0)
           port (:port server)
           nonce (metadata/new-nonce)
           meta (metadata/metadata-shape {:pid (current-pid)
                                          :host loopback-host
                                          :port port
                                          :storage-kind (:storage-kind storage)
                                          :storage-label (:storage-label storage)
                                          :canonical-db-path (:canonical-db-path storage)
                                          :nonce nonce
                                          :world world
                                          :name (or name (default-name world))
                                          :started-at (str (Instant/now))})
           runtime-base {:storage storage
                         :datasource ds
                         :query-registry (atom {})
                         :view-registry (atom {})
                         :pattern-registry (atom {})
                         :op-registry (atom {})
                         :hook-registry (atom {})
                         :approved-spool-sync-state (atom {})
                         :module-use-state (atom {})
                         :spool-state (atom {})
                         :spool-classloader (clojure.lang.DynamicClassLoader.
                                             (.getContextClassLoader (Thread/currentThread)))
                         :server server
                         :metadata meta}
           runtime-base (start-event-system! runtime-base)
           runtime-state (atom runtime-base)]
       (try
         (let [socket-runtime (socket/start! runtime-state (:socket-path meta))
               runtime (assoc runtime-base :socket-runtime socket-runtime)]
           (reset! runtime-state runtime)
           (swap! nrepl-port-runtimes assoc port runtime)
           (when (and publish? (not (compare-and-set! current-runtime nil runtime)))
             (throw (ex-info "A weaver runtime is already active in this process" {:metadata (:metadata @current-runtime)})))
           (install-built-in-ops! runtime)
           (load-startup-files! runtime world)
           ;; Arm the scheduler only after startup files finish loading, so
           ;; handlers supplied by approved spools/config resolve before any
           ;; durable pending wake is re-armed (DELTA-...-runtime-001.CC5).
           (scheduler/rearm! runtime)
           (let [published-runtime (assoc runtime :metadata-file (metadata/publish! meta))]
             (reset! runtime-state published-runtime)
             (swap! nrepl-port-runtimes assoc port published-runtime)
             (when publish?
               (compare-and-set! current-runtime runtime published-runtime))
             published-runtime))
         (catch Throwable t
           (swap! nrepl-port-runtimes dissoc port)
           (when publish?
             (compare-and-set! current-runtime @runtime-state nil))
           (stop-event-system! @runtime-state)
           (when-let [socket-runtime (:socket-runtime @runtime-state)]
             (socket/stop! socket-runtime))
           (nrepl/stop-server server)
           ;; Spool state may own executors started by config before metadata is
           ;; published. Close it on failed startup too, while storage remains
           ;; available, without masking the original startup exception.
           (close-spool-state! @runtime-state t)
           (close-storage! @runtime-state)
           (metadata/delete! world)
           (throw t)))))))

(defn status
  "Return the published metadata for `runtime`."
  [runtime]
  (:metadata runtime))

(defn stop!
  "Stop transports, event processing, storage, and metadata for `runtime`."
  [runtime]
  (stop-event-system! runtime)
  (when-let [socket-runtime (:socket-runtime runtime)]
    (socket/stop! socket-runtime))
  (when-let [server (:server runtime)]
    (nrepl/stop-server server))
  ;; Spool state closes before storage so runtime-owned schedulers/workers can
  ;; cancel or join their work while storage is still valid.
  (close-spool-state! runtime)
  ;; Storage closes only after transports, event dispatch, and spool-owned
  ;; workers stop, so no in-flight weaver work can observe closed storage.
  (close-storage! runtime)
  (when-let [port (get-in runtime [:metadata :endpoint :port])]
    (swap! nrepl-port-runtimes dissoc port))
  (swap! current-runtime (fn [published] (when-not (= published runtime) published)))
  (when-let [state-dir (get-in runtime [:metadata :state-dir])]
    (metadata/delete! {:state-dir state-dir}))
  {:stopped true})

(defn- require-main-dir! [opts k flag]
  (or (get opts k)
      (throw (ex-info (str flag " is required") {:args opts :missing flag}))))

(defn- parse-main-args [args]
  (loop [remaining args
         opts {}]
    (case (first remaining)
      nil {:config-dir (require-main-dir! opts :config-dir "--workspace")
           :state-dir (require-main-dir! opts :state-dir "--state-dir")
           :data-dir (require-main-dir! opts :data-dir "--data-dir")
           :name (:name opts)}
      "--workspace" (let [[_ dir & more] remaining]
                      (when-not dir
                        (throw (ex-info "--workspace requires a directory" {:args args})))
                      (recur more (assoc opts :config-dir dir)))
      "--state-dir" (let [[_ dir & more] remaining]
                      (when-not dir
                        (throw (ex-info "--state-dir requires a directory" {:args args})))
                      (recur more (assoc opts :state-dir dir)))
      "--data-dir" (let [[_ dir & more] remaining]
                     (when-not dir
                       (throw (ex-info "--data-dir requires a directory" {:args args})))
                     (recur more (assoc opts :data-dir dir)))
      "--name" (let [[_ name & more] remaining]
                 (when (str/blank? name)
                   (throw (ex-info "--name requires a non-blank value" {:args args})))
                 (recur more (assoc opts :name name)))
      (throw (ex-info "Usage: skein.core.weaver.runtime --workspace <dir> --state-dir <dir> --data-dir <dir> [--name <name>]" {:args args})))))

(defn- install-signal-shutdown!
  "Run the clean stop path on SIGTERM/SIGINT (and normal JVM exit).

  A JVM shutdown hook is the portable handler for both termination signals; it
  drives `stop!`, which takes transports down, closes storage, and removes the
  weaver.edn/weaver.json/weaver.sock artifacts. This replaces the removed socket
  `stop` operation (SPEC-004-D003.C3). Signal delivery itself is not unit-tested
  in-JVM; the artifact cleanup it invokes is covered by the programmatic
  `stop!` tests."
  []
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               (when-let [rt @current-runtime]
                                 (stop! rt)))
                             "skein-weaver-signal-shutdown")))

(defn -main
  "Start a foreground weaver process from command-line arguments."
  [& args]
  (let [{:keys [config-dir state-dir data-dir name]} (parse-main-args args)]
    (start! nil {:world (config/world config-dir state-dir data-dir) :name name})
    (install-signal-shutdown!)
    (println "weaver started")
    (while @current-runtime
      (Thread/sleep 100))))
