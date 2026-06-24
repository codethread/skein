(ns todo.daemon.runtime
  (:require [nrepl.server :as nrepl]
            [todo.daemon.metadata :as metadata]
            [todo.db :as db])
  (:import [java.lang ProcessHandle]))

(def loopback-host "127.0.0.1")

(defn current-pid []
  (.pid (ProcessHandle/current)))

(defn start! [db-file]
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
                                         :nonce nonce})]
      (try
        {:datasource ds
         :server server
         :metadata meta
         :metadata-file (metadata/publish! meta)}
        (catch Exception e
          (nrepl/stop-server server)
          (throw e))))))

(defn status [runtime]
  (:metadata runtime))

(defn stop! [runtime]
  (when-let [server (:server runtime)]
    (nrepl/stop-server server))
  (when-let [canonical-path (get-in runtime [:metadata :canonical-db-path])]
    (metadata/delete! canonical-path))
  {:stopped true})
