(ns todo.daemon.runtime
  (:require [nrepl.server :as nrepl]
            [todo.daemon.config :as config]
            [todo.daemon.metadata :as metadata]
            [todo.daemon.socket :as socket]
            [todo.db :as db])
  (:import [java.lang ProcessHandle]
           [java.time Instant]))

(def loopback-host "127.0.0.1")

(defonce current-runtime (atom nil))

(declare stop!)

(defn current-pid []
  (.pid (ProcessHandle/current)))

(defn start!
  ([db-file] (start! db-file {}))
  ([db-file {:keys [config-file]}]
   (when @current-runtime
     (throw (ex-info "A daemon runtime is already active in this process" {:metadata (:metadata @current-runtime)})))
   (let [canonical-path (metadata/canonical-db-path db-file)
         existing (metadata/read-metadata canonical-path)]
     (when-not (metadata/stale-or-missing? existing)
       (throw (ex-info "Daemon metadata already exists for database" {:canonical-db-path canonical-path
                                                                       :metadata existing})))
     (let [ds (db/datasource canonical-path)
           server (nrepl/start-server :bind loopback-host :port 0)
           port (:port server)
           nonce (metadata/new-nonce)
           meta (metadata/metadata-shape {:pid (current-pid)
                                          :host loopback-host
                                          :port port
                                          :canonical-db-path canonical-path
                                          :nonce nonce
                                          :started-at (str (Instant/now))})
           runtime-base {:datasource ds
                         :query-registry (atom {})
                         :server server
                         :metadata meta}
           runtime-state (atom runtime-base)]
       (try
         (let [socket-runtime (socket/start! runtime-state (get-in meta [:json :socket-path]) #(stop! @runtime-state))
               runtime (assoc runtime-base :socket-runtime socket-runtime)]
           (reset! runtime-state runtime)
           (reset! current-runtime runtime)
         (when config-file
           (config/load-config! config-file))
           (let [published-runtime (assoc runtime :metadata-file (metadata/publish! meta))]
             (reset! runtime-state published-runtime)
             (reset! current-runtime published-runtime)
             published-runtime))
         (catch Throwable t
           (reset! current-runtime nil)
           (when-let [socket-runtime (:socket-runtime @runtime-state)]
             (socket/stop! socket-runtime))
           (nrepl/stop-server server)
           (metadata/delete! canonical-path)
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
  (when-let [canonical-path (get-in runtime [:metadata :canonical-db-path])]
    (metadata/delete! canonical-path))
  {:stopped true})
