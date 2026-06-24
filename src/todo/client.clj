(ns todo.client
  (:refer-clojure :exclude [list update])
  (:require [clojure.edn :as edn]
            [nrepl.core :as nrepl]
            [todo.daemon.metadata :as metadata])
  (:import [java.net InetAddress]))

(def default-timeout-ms 2000)

(def api-symbols
  {:init 'todo.daemon.api/init
   :add 'todo.daemon.api/add
   :update 'todo.daemon.api/update
   :show 'todo.daemon.api/show
   :list 'todo.daemon.api/list
   :ready 'todo.daemon.api/ready})

(defn fail [message data]
  (throw (ex-info message data)))

(defn loopback-host? [host]
  (.isLoopbackAddress (InetAddress/getByName host)))

(defn metadata-for [db-file]
  (let [canonical-path (metadata/canonical-db-path db-file)
        meta (metadata/read-metadata canonical-path)]
    (when (metadata/stale-or-missing? meta)
      (fail "Daemon metadata is missing or stale" {:type :todo.client/missing-or-stale-metadata
                                                    :canonical-db-path canonical-path
                                                    :metadata meta}))
    (when-not (= canonical-path (:canonical-db-path meta))
      (fail "Daemon metadata database path does not match requested database" {:type :todo.client/metadata-db-mismatch
                                                                                :expected canonical-path
                                                                                :actual (:canonical-db-path meta)
                                                                                :metadata meta}))
    (when-not (loopback-host? (get-in meta [:endpoint :host]))
      (fail "Daemon metadata endpoint is not loopback" {:type :todo.client/non-local-endpoint
                                                         :endpoint (:endpoint meta)}))
    meta))

(defn fixed-form [op args]
  (let [api-symbol (or (api-symbols op)
                       (fail "Unknown daemon API operation" {:type :todo.client/unknown-operation
                                                              :operation op}))]
    (str "(do "
         "(require '[todo.daemon.api] '[todo.daemon.runtime]) "
         "(let [args '" (pr-str (vec args)) "] "
         "(try {:ok true :value (apply " api-symbol " @todo.daemon.runtime/current-runtime args)} "
         "(catch Throwable t {:ok false :class (str (class t)) :message (ex-message t) :data (ex-data t)})))"
         ")")))

(defn identity-form []
  "(do (require '[todo.daemon.runtime]) (:metadata @todo.daemon.runtime/current-runtime))")

(defn stop-form []
  "(do (require '[todo.daemon.runtime]) (let [rt @todo.daemon.runtime/current-runtime] (future (Thread/sleep 50) (todo.daemon.runtime/stop! rt)) {:stopped true}))")

(defn connect [metadata timeout-ms]
  (let [{:keys [host port]} (:endpoint metadata)]
    (try
      (nrepl/connect :host host :port port :timeout timeout-ms)
      (catch Exception e
        (throw (ex-info "Unable to connect to daemon nREPL endpoint" {:type :todo.client/connection-failed
                                                                       :endpoint (:endpoint metadata)}
                        e))))))

(defn eval-form [conn form timeout-ms context]
  (let [client (nrepl/client conn timeout-ms)
        session (nrepl/client-session client timeout-ms)
        responses (try
                    (doall (nrepl/message session {:op "eval" :code form}))
                    (catch java.net.SocketTimeoutException e
                      (throw (ex-info "Daemon nREPL request timed out" (assoc context :type :todo.client/timeout) e)))
                    (catch Exception e
                      (throw (ex-info "Daemon nREPL request failed" (assoc context :type :todo.client/request-failed) e))))
        statuses (set (mapcat :status responses))]
    (when (contains? statuses "eval-error")
      (let [err (some :err responses)]
        (fail "Daemon API call failed" (assoc context :type :todo.client/daemon-error
                                             :err err
                                             :responses responses))))
    (when (contains? statuses "error")
      (fail "Daemon nREPL returned an error" (assoc context :type :todo.client/nrepl-error
                                                   :responses responses)))
    (if-let [value (some :value responses)]
      (let [result (edn/read-string value)]
        (if (and (map? result) (contains? result :ok))
          (if (:ok result)
            (:value result)
            (fail "Daemon API call failed" (assoc context
                                             :type :todo.client/daemon-error
                                             :daemon-class (:class result)
                                             :daemon-message (:message result)
                                             :daemon-data (:data result))))
          result))
      (if (empty? responses)
        (fail "Daemon nREPL request timed out" (assoc context :type :todo.client/timeout
                                                   :responses responses))
        (fail "Daemon nREPL returned no value" (assoc context :type :todo.client/no-value
                                                     :responses responses))))))

(defn verify-identity! [conn expected timeout-ms]
  (let [actual (eval-form conn (identity-form) timeout-ms {:operation :identity})]
    (when-not (= (:canonical-db-path expected) (:canonical-db-path actual))
      (fail "Connected daemon serves a different database" {:type :todo.client/db-mismatch
                                                            :expected (:canonical-db-path expected)
                                                            :actual (:canonical-db-path actual)}))
    (when-not (= (:nonce expected) (:nonce actual))
      (fail "Connected daemon identity does not match runtime metadata" {:type :todo.client/identity-mismatch
                                                                         :expected (:nonce expected)
                                                                         :actual (:nonce actual)}))
    actual))

(defn call [db-file {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}} op & args]
  (let [meta (metadata-for db-file)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (fixed-form op args) timeout-ms {:operation op
                                                       :canonical-db-path (:canonical-db-path meta)}))))

(defn status [db-file & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for db-file)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms))))

(defn stop [db-file & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for db-file)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (stop-form) timeout-ms {:operation :stop
                                              :canonical-db-path (:canonical-db-path meta)}))))

(defn init [db-file & [opts]] (call db-file (or opts {}) :init))
(defn add [db-file task & [opts]] (call db-file (or opts {}) :add task))
(defn update [db-file id patch & [opts]] (call db-file (or opts {}) :update id patch))
(defn show [db-file id & [opts]] (call db-file (or opts {}) :show id))
(defn list [db-file & [opts]] (call db-file (or opts {}) :list))
(defn ready [db-file & [opts]] (call db-file (or opts {}) :ready))
