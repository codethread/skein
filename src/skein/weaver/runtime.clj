(ns skein.weaver.runtime
  (:require [nrepl.server :as nrepl]
            [skein.weaver.config :as config]
            [skein.weaver.metadata :as metadata]
            [skein.weaver.socket :as socket]
            [skein.db :as db])
  (:import [java.lang ProcessHandle]
           [java.time Instant]))

(def loopback-host "127.0.0.1")

(defonce current-runtime (atom nil))

(declare stop!)

(defn current-pid []
  (.pid (ProcessHandle/current)))

(defn init-file [world]
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
                         :approved-lib-sync-state (atom {})
                         :module-use-state (atom {})
                         :library-classloader (clojure.lang.DynamicClassLoader.
                                               (.getContextClassLoader (Thread/currentThread)))
                         :server server
                         :metadata meta}
           runtime-state (atom runtime-base)]
       (try
         (let [socket-runtime (socket/start! runtime-state (:socket-path meta) #(stop! @runtime-state))
               runtime (assoc runtime-base :socket-runtime socket-runtime)]
           (reset! runtime-state runtime)
           (reset! current-runtime runtime)
           (when-let [init (init-file world)]
             (with-library-classloader runtime #(load-file init)))
           (let [published-runtime (assoc runtime :metadata-file (metadata/publish! meta))]
             (reset! runtime-state published-runtime)
             (reset! current-runtime published-runtime)
             published-runtime))
         (catch Throwable t
           (reset! current-runtime nil)
           (when-let [socket-runtime (:socket-runtime @runtime-state)]
             (socket/stop! socket-runtime))
           (nrepl/stop-server server)
           (metadata/delete! world)
           (throw t)))))))

(defn status [runtime]
  (:metadata runtime))

(defn stop! [runtime]
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

(defn -main [& args]
  (let [{:keys [config-dir]} (parse-main-args args)]
    (start! nil {:world (config/world config-dir)})
    (println "weaver started")
    (while @current-runtime
      (Thread/sleep 100))))
