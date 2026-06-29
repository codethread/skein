(ns skein.weaver.runtime
  "Start, stop, and supervise the in-process weaver daemon runtime."
  (:require [nrepl.server :as nrepl]
            [skein.weaver.config :as config]
            [skein.weaver.metadata :as metadata]
            [skein.weaver.socket :as socket]
            [skein.db :as db])
  (:import [java.lang ProcessHandle]
           [java.time Instant]
           [java.util.concurrent ArrayBlockingQueue TimeUnit]))

(def ^:private loopback-host "127.0.0.1")

(defonce ^{:doc "Atom containing the active weaver runtime map for this process, or nil."} current-runtime
  (atom nil))

(def event-queue-capacity
  "Maximum number of queued weaver events before enqueueing fails."
  1024)
(def ^:private recent-event-failure-limit
  "Maximum number of recent event handler failures retained in memory."
  100)

(declare stop! with-library-classloader)

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
    (.clear queue)
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
                          (doseq [{:keys [key types fn fn-value]} (vals @(:handler-registry event-system))
                                  :when (contains? types (:event/type event))]
                            (try
                              (with-library-classloader runtime #(fn-value event))
                              (catch Throwable t
                                (let [failure {:handler/key key
                                               :handler/fn fn
                                               :event/id (:event/id event)
                                               :event/type (:event/type event)
                                               :exception/message (ex-message t)
                                               :failed/at (str (Instant/now))}]
                                  (swap! (:recent-failures event-system)
                                         #(->> (conj % failure)
                                               (take-last recent-event-failure-limit)
                                               vec)))))))))
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

(defn restart-event-system!
  "Reset handlers, failures, and worker state for the runtime event system."
  [runtime]
  (let [{:keys [handler-registry recent-failures queue running?] :as event-system} (:event-system runtime)]
    (stop-event-system! runtime)
    (reset! handler-registry {})
    (reset! recent-failures [])
    (.clear queue)
    (reset! running? true)
    (run-event-worker! runtime event-system)
    nil))

(defn- current-pid
  "Return the current OS process id."
  []
  (.pid (ProcessHandle/current)))

(defn- init-file
  "Return the canonical init.clj path for `world`, or nil when absent."
  [world]
  (let [file (clojure.java.io/file (:config-dir world) "init.clj")]
    (when (.isFile file)
      (.getCanonicalPath file))))

(defn- with-library-classloader [runtime f]
  (let [thread (Thread/currentThread)
        previous-loader (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread (:library-classloader runtime))
      (f)
      (finally
        (.setContextClassLoader thread previous-loader)))))

(defn start!
  "Start a weaver runtime for `db-file` and optional `world`.

  Publishes metadata, starts nREPL and JSON socket transports, loads trusted
  config, and fails if this process already owns a runtime."
  ([] (start! nil {}))
  ([db-file] (start! db-file {}))
  ([db-file {:keys [world]}]
   (when @current-runtime
     (throw (ex-info "A weaver runtime is already active in this process" {:metadata (:metadata @current-runtime)})))
   (let [world (or world (config/world))
         db-file (or db-file (:db-path world))
         canonical-path (metadata/canonical-db-path db-file)
         existing (metadata/read-metadata world)
         socket-file (metadata/socket-file world)]
     (when-not (metadata/stale-or-missing? existing)
       (throw (ex-info "Weaver metadata already exists for weaver world" {:config-dir (:config-dir world)
                                                                           :metadata existing})))
     (when (and (nil? existing) (.exists socket-file))
       (throw (ex-info "Weaver socket exists without metadata; cannot prove weaver world is stale" {:config-dir (:config-dir world)
                                                                                                    :socket-path (.getPath socket-file)})))
     (.mkdirs (clojure.java.io/file (:state-dir world)))
     (.mkdirs (clojure.java.io/file (:data-dir world)))
     (let [ds (db/datasource canonical-path)
           server (nrepl/start-server :bind loopback-host :port 0)
           port (:port server)
           nonce (metadata/new-nonce)
           meta (metadata/metadata-shape {:pid (current-pid)
                                          :host loopback-host
                                          :port port
                                          :canonical-db-path canonical-path
                                          :nonce nonce
                                          :world world
                                          :started-at (str (Instant/now))})
           runtime-base {:datasource ds
                         :query-registry (atom {})
                         :view-registry (atom {})
                         :pattern-registry (atom {})
                         :op-registry (atom {})
                         :approved-lib-sync-state (atom {})
                         :module-use-state (atom {})
                         :library-classloader (clojure.lang.DynamicClassLoader.
                                               (.getContextClassLoader (Thread/currentThread)))
                         :server server
                         :metadata meta}
           runtime-base (start-event-system! runtime-base)
           runtime-state (atom runtime-base)]
       (try
         (let [socket-runtime (socket/start! runtime-state (:socket-path meta) #(stop! @runtime-state))
               runtime (assoc runtime-base :socket-runtime socket-runtime)]
           (reset! runtime-state runtime)
           (reset! current-runtime runtime)
           ((requiring-resolve 'skein.weaver.api/register-built-in-ops!) runtime)
           (when-let [init (init-file world)]
             (with-library-classloader runtime #(load-file init)))
           (let [published-runtime (assoc runtime :metadata-file (metadata/publish! meta))]
             (reset! runtime-state published-runtime)
             (reset! current-runtime published-runtime)
             published-runtime))
         (catch Throwable t
           (reset! current-runtime nil)
           (stop-event-system! @runtime-state)
           (when-let [socket-runtime (:socket-runtime @runtime-state)]
             (socket/stop! socket-runtime))
           (nrepl/stop-server server)
           (metadata/delete! world)
           (throw t)))))))

(defn status
  "Return the published metadata for `runtime`."
  [runtime]
  (:metadata runtime))

(defn stop!
  "Stop transports, event processing, and metadata for `runtime`."
  [runtime]
  (stop-event-system! runtime)
  (when-let [socket-runtime (:socket-runtime runtime)]
    (socket/stop! socket-runtime))
  (when-let [server (:server runtime)]
    (nrepl/stop-server server))
  (when (= runtime @current-runtime)
    (reset! current-runtime nil))
  (when-let [state-dir (get-in runtime [:metadata :state-dir])]
    (metadata/delete! {:state-dir state-dir}))
  {:stopped true})

(defn- parse-main-args [args]
  (loop [remaining args
         opts {}]
    (case (first remaining)
      nil opts
      "--config-dir" (let [[_ dir & more] remaining]
                       (when-not dir
                         (throw (ex-info "--config-dir requires a directory" {:args args})))
                       (recur more (assoc opts :config-dir dir)))
      (throw (ex-info "Usage: skein.weaver.runtime [--config-dir <dir>]" {:args args})))))

(defn -main
  "Start a foreground weaver process from command-line arguments."
  [& args]
  (let [{:keys [config-dir]} (parse-main-args args)]
    (start! nil {:world (config/world config-dir)})
    (println "weaver started")
    (while @current-runtime
      (Thread/sleep 100))))
