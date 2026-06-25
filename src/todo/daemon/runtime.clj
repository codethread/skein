(ns todo.daemon.runtime
  (:require [nrepl.server :as nrepl]
            [todo.daemon.config :as config]
            [todo.daemon.metadata :as metadata]
            [todo.db :as db])
  (:import [java.lang ProcessHandle]))

(def loopback-host "127.0.0.1")

(defonce current-runtime (atom nil))

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
                                          :nonce nonce})
           runtime {:datasource ds
                    :query-registry (atom {})
                    :server server
                    :metadata meta}]
       (try
         (reset! current-runtime runtime)
         (when config-file
           (config/load-config! config-file))
         (let [published-runtime (assoc runtime :metadata-file (metadata/publish! meta))]
           (reset! current-runtime published-runtime)
           published-runtime)
         (catch Throwable t
           (reset! current-runtime nil)
           (nrepl/stop-server server)
           (throw t)))))))

(defn status [runtime]
  (:metadata runtime))

(defn stop! [runtime]
  (when-let [server (:server runtime)]
    (nrepl/stop-server server))
  (when (= runtime @current-runtime)
    (reset! current-runtime nil))
  (when-let [canonical-path (get-in runtime [:metadata :canonical-db-path])]
    (metadata/delete! canonical-path))
  {:stopped true})
