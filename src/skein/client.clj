(ns skein.client
  (:refer-clojure :exclude [list update])
  (:require [clojure.edn :as edn]
            [nrepl.core :as nrepl]
            [skein.weaver.config :as config]
            [skein.weaver.metadata :as metadata])
  (:import [java.net InetAddress]))

(def default-timeout-ms 2000)

(def api-symbols
  {:init 'skein.weaver.api/init
   :add 'skein.weaver.api/add
   :update 'skein.weaver.api/update
   :show 'skein.weaver.api/show
   :burn-by-id 'skein.weaver.api/burn-by-id
   :burn-by-ids 'skein.weaver.api/burn-by-ids
   :list 'skein.weaver.api/list
   :ready 'skein.weaver.api/ready
   :register-query 'skein.weaver.api/register-query
   :load-queries 'skein.weaver.api/load-queries
   :queries 'skein.weaver.api/queries
   :resolve-query 'skein.weaver.api/resolve-query
   :list-query 'skein.weaver.api/list-query
   :ready-query 'skein.weaver.api/ready-query
   :query-ids 'skein.weaver.api/query-ids
   :strands-by-ids 'skein.weaver.api/strands-by-ids
   :ancestor-root-ids 'skein.weaver.api/ancestor-root-ids
   :subgraph 'skein.weaver.api/subgraph
   :register-view! 'skein.weaver.api/register-view!
   :view! 'skein.weaver.api/view!
   :views 'skein.weaver.api/views
   :register-pattern! 'skein.weaver.api/register-pattern!
   :patterns 'skein.weaver.api/patterns
   :resolve-pattern 'skein.weaver.api/resolve-pattern
   :pattern-explain 'skein.weaver.api/pattern-explain
   :weave! 'skein.weaver.api/weave!
   :approved-libs 'skein.weaver.api/approved-libs
   :sync-approved-libs 'skein.weaver.api/sync-approved-libs
   :approved-lib-syncs 'skein.weaver.api/approved-lib-syncs
   :reload-config! 'skein.weaver.api/reload-config!
   :use! 'skein.weaver.api/use!
   :uses 'skein.weaver.api/uses
   :use 'skein.weaver.api/use})

(defn fail [message data]
  (throw (ex-info message data)))

(defn loopback-host? [host]
  (.isLoopbackAddress (InetAddress/getByName host)))

(defn metadata-for-world
  ([] (metadata-for-world nil))
  ([config-dir]
   (let [world (config/world config-dir)
         meta (metadata/read-metadata world)]
     (when (metadata/stale-or-missing? meta)
       (fail "Weaver metadata is missing or stale" {:type :skein.client/missing-or-stale-metadata
                                                     :config-dir (:config-dir world)
                                                     :metadata meta}))
     (when-not (= (:config-dir world) (:config-dir meta))
       (fail "Weaver metadata config dir does not match requested world" {:type :skein.client/metadata-config-mismatch
                                                                           :expected (:config-dir world)
                                                                           :actual (:config-dir meta)
                                                                           :metadata meta}))
     (when-not (loopback-host? (get-in meta [:endpoint :host]))
       (fail "Weaver metadata endpoint is not loopback" {:type :skein.client/non-local-endpoint
                                                          :endpoint (:endpoint meta)}))
     meta)))

(defn metadata-for [db-file]
  (let [meta (metadata-for-world nil)
        canonical-path (metadata/canonical-db-path db-file)]
    (when-not (= canonical-path (:canonical-db-path meta))
      (fail "Weaver metadata is missing or stale" {:type :skein.client/missing-or-stale-metadata
                                                    :canonical-db-path canonical-path
                                                    :metadata meta}))
    meta))

(defn fixed-form [op args]
  (let [api-symbol (or (api-symbols op)
                       (fail "Unknown weaver API operation" {:type :skein.client/unknown-operation
                                                              :operation op}))]
    (str "(do "
         "(require '[skein.weaver.api] '[skein.weaver.runtime]) "
         "(let [args '" (pr-str (vec args)) "] "
         "(try {:ok true :value (apply " api-symbol " @skein.weaver.runtime/current-runtime args)} "
         "(catch Throwable t {:ok false :class (str (class t)) :message (ex-message t) :data (ex-data t)})))"
         ")")))

(defn identity-form []
  "(do (require '[skein.weaver.runtime]) (:metadata @skein.weaver.runtime/current-runtime))")

(defn stop-form []
  "(do (require '[skein.weaver.runtime]) (let [rt @skein.weaver.runtime/current-runtime] (future (Thread/sleep 50) (skein.weaver.runtime/stop! rt)) {:stopped true}))")

(defn connect [metadata timeout-ms]
  (let [{:keys [host port]} (:endpoint metadata)]
    (try
      (nrepl/connect :host host :port port :timeout timeout-ms)
      (catch Exception e
        (throw (ex-info "Unable to connect to weaver nREPL endpoint" {:type :skein.client/connection-failed
                                                                       :endpoint (:endpoint metadata)}
                        e))))))

(defn eval-form [conn form timeout-ms context]
  (let [client (nrepl/client conn timeout-ms)
        session (nrepl/client-session client timeout-ms)
        responses (try
                    (doall (nrepl/message session {:op "eval" :code form}))
                    (catch java.net.SocketTimeoutException e
                      (throw (ex-info "Weaver nREPL request timed out" (assoc context :type :skein.client/timeout) e)))
                    (catch Exception e
                      (throw (ex-info "Weaver nREPL request failed" (assoc context :type :skein.client/request-failed) e))))
        statuses (set (mapcat :status responses))]
    (when (contains? statuses "eval-error")
      (let [err (some :err responses)]
        (fail "Weaver API call failed" (assoc context :type :skein.client/weaver-error
                                             :err err
                                             :responses responses))))
    (when (contains? statuses "error")
      (fail "Weaver nREPL returned an error" (assoc context :type :skein.client/nrepl-error
                                                   :responses responses)))
    (if-let [value (some :value responses)]
      (let [result (edn/read-string value)]
        (if (and (map? result) (contains? result :ok))
          (if (:ok result)
            (:value result)
            (fail "Weaver API call failed" (assoc context
                                             :type :skein.client/weaver-error
                                             :weaver-class (:class result)
                                             :weaver-message (:message result)
                                             :weaver-data (:data result))))
          result))
      (if (empty? responses)
        (fail "Weaver nREPL request timed out" (assoc context :type :skein.client/timeout
                                                   :responses responses))
        (fail "Weaver nREPL returned no value" (assoc context :type :skein.client/no-value
                                                     :responses responses))))))

(defn verify-identity! [conn expected timeout-ms]
  (let [actual (eval-form conn (identity-form) timeout-ms {:operation :identity})]
    (when-not (= (:config-dir expected) (:config-dir actual))
      (fail "Connected weaver serves a different config dir" {:type :skein.client/config-mismatch
                                                               :expected (:config-dir expected)
                                                               :actual (:config-dir actual)}))
    (when-not (= (:nonce expected) (:nonce actual))
      (fail "Connected weaver identity does not match runtime metadata" {:type :skein.client/identity-mismatch
                                                                         :expected (:nonce expected)
                                                                         :actual (:nonce actual)}))
    actual))

(defn call-world [config-dir {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}} op & args]
  (let [meta (metadata-for-world config-dir)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (fixed-form op args) timeout-ms {:operation op
                                                       :config-dir (:config-dir meta)}))))

(defn call [db-file {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}} op & args]
  (let [meta (metadata-for db-file)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (fixed-form op args) timeout-ms {:operation op
                                                       :canonical-db-path (:canonical-db-path meta)}))))

(defn status-world [config-dir & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for-world config-dir)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms))))

(defn status [db-file & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for db-file)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms))))

(defn stop-world [config-dir & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for-world config-dir)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (stop-form) timeout-ms {:operation :stop
                                              :config-dir (:config-dir meta)}))))

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
(defn list
  ([db-file]
   (call db-file {} :list))
  ([db-file opts]
   (call db-file (or opts {}) :list))
  ([db-file query-def params]
   (call db-file {} :list query-def params))
  ([db-file query-def params opts]
   (call db-file (or opts {}) :list query-def params)))

(defn ready
  ([db-file]
   (call db-file {} :ready))
  ([db-file opts]
   (call db-file (or opts {}) :ready))
  ([db-file query-def params]
   (call db-file {} :ready query-def params))
  ([db-file query-def params opts]
   (call db-file (or opts {}) :ready query-def params)))

(defn register-query [db-file query-name query-def & [opts]]
  (call db-file (or opts {}) :register-query query-name query-def))

(defn load-queries [db-file query-defs & [opts]]
  (call db-file (or opts {}) :load-queries query-defs))

(defn queries [db-file & [opts]]
  (call db-file (or opts {}) :queries))

(defn resolve-query [db-file query-name & [opts]]
  (call db-file (or opts {}) :resolve-query query-name))

(defn list-query [db-file query-name params & [opts]]
  (call db-file (or opts {}) :list-query query-name params))

(defn ready-query [db-file query-name params & [opts]]
  (call db-file (or opts {}) :ready-query query-name params))
