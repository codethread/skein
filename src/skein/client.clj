(ns skein.client
  "Thin Clojure client for calling a running Skein weaver over nREPL.

  This namespace validates published weaver metadata, verifies daemon identity,
  and routes public client operations to the daemon-owned API surface."
  (:refer-clojure :exclude [list update])
  (:require [clojure.edn :as edn]
            [nrepl.core :as nrepl]
            [skein.weaver.config :as config]
            [skein.weaver.metadata :as metadata])
  (:import [java.net InetAddress]))

(def ^:private default-timeout-ms 2000)

(def ^:private api-symbols
  {:init 'skein.weaver.api/init
   :add 'skein.weaver.api/add
   :update 'skein.weaver.api/update
   :show 'skein.weaver.api/show
   :burn-by-id 'skein.weaver.api/burn-by-id
   :burn-by-ids 'skein.weaver.api/burn-by-ids
   :list 'skein.weaver.api/list
   :ready 'skein.weaver.api/ready
   :supersede 'skein.weaver.api/supersede
   :declare-acyclic-relation! 'skein.weaver.api/declare-acyclic-relation!
   :acyclic-relations 'skein.weaver.api/acyclic-relations
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
   :register-event-handler! 'skein.weaver.api/register-event-handler!
   :unregister-event-handler! 'skein.weaver.api/unregister-event-handler!
   :event-handlers 'skein.weaver.api/event-handlers
   :recent-event-failures 'skein.weaver.api/recent-event-failures
   :register-pattern! 'skein.weaver.api/register-pattern!
   :register-op! 'skein.weaver.api/register-op!
   :ops 'skein.weaver.api/ops
   :resolve-op 'skein.weaver.api/resolve-op
   :op! 'skein.weaver.api/op!
   :patterns 'skein.weaver.api/patterns
   :resolve-pattern 'skein.weaver.api/resolve-pattern
   :pattern-explain 'skein.weaver.api/pattern-explain
   :weave! 'skein.weaver.api/weave!
   :apply-batch 'skein.weaver.api/apply-batch
   :approved-libs 'skein.weaver.api/approved-libs
   :sync-approved-libs 'skein.weaver.api/sync-approved-libs
   :approved-lib-syncs 'skein.weaver.api/approved-lib-syncs
   :reload-config! 'skein.weaver.api/reload-config!
   :use! 'skein.weaver.api/use!
   :uses 'skein.weaver.api/uses
   :use 'skein.weaver.api/use})

(defn- fail
  "Throw an ExceptionInfo with message and structured client error data."
  [message data]
  (throw (ex-info message data)))

(defn- loopback-host?
  "Return true when host resolves to a loopback address."
  [host]
  (.isLoopbackAddress (InetAddress/getByName host)))

(defn metadata-for-world
  "Return validated runtime metadata for config-dir's weaver world.

  Fails when metadata is missing, stale, for another config dir, or points at a
  non-loopback endpoint. With no argument, uses the default configured world."
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

(defn- metadata-for
  "Return validated runtime metadata for the weaver serving db-file."
  [db-file]
  (let [meta (metadata-for-world nil)
        canonical-path (metadata/canonical-db-path db-file)]
    (when-not (= canonical-path (:canonical-db-path meta))
      (fail "Weaver metadata is missing or stale" {:type :skein.client/missing-or-stale-metadata
                                                    :canonical-db-path canonical-path
                                                    :metadata meta}))
    meta))

(defn- fixed-form
  "Return an nREPL form that invokes a known weaver API operation with args."
  [op args]
  (let [api-symbol (or (api-symbols op)
                       (fail "Unknown weaver API operation" {:type :skein.client/unknown-operation
                                                              :operation op}))]
    (str "(do "
         "(require '[skein.weaver.api] '[skein.weaver.runtime]) "
         "(let [args '" (pr-str (vec args)) "] "
         "(try {:ok true :value (apply " api-symbol " @skein.weaver.runtime/current-runtime args)} "
         "(catch Throwable t {:ok false :class (str (class t)) :message (ex-message t) :data (ex-data t)})))"
         ")")))

(defn- identity-form
  "Return an nREPL form that reads the connected weaver runtime metadata."
  []
  "(do (require '[skein.weaver.runtime]) (:metadata @skein.weaver.runtime/current-runtime))")

(defn- stop-form
  "Return an nREPL form that schedules the connected weaver to stop."
  []
  "(do (require '[skein.weaver.runtime]) (let [rt @skein.weaver.runtime/current-runtime] (future (Thread/sleep 50) (skein.weaver.runtime/stop! rt)) {:stopped true}))")

(defn- connect
  "Open an nREPL connection to the endpoint in validated weaver metadata."
  [metadata timeout-ms]
  (let [{:keys [host port]} (:endpoint metadata)]
    (try
      (nrepl/connect :host host :port port :timeout timeout-ms)
      (catch Exception e
        (throw (ex-info "Unable to connect to weaver nREPL endpoint" {:type :skein.client/connection-failed
                                                                       :endpoint (:endpoint metadata)}
                        e))))))

(defn- eval-form
  "Evaluate form on conn and return the decoded Clojure value.

  Converts nREPL transport failures, daemon-side exceptions, and missing values
  into ExceptionInfo with client error data."
  [conn form timeout-ms context]
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

(defn- verify-identity!
  "Verify that conn serves the expected weaver runtime metadata."
  [conn expected timeout-ms]
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

(defn call-world
  "Call a weaver API operation in config-dir's world and return Clojure data."
  [config-dir {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}} op & args]
  (let [meta (metadata-for-world config-dir)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (fixed-form op args) timeout-ms {:operation op
                                                       :config-dir (:config-dir meta)}))))

(defn call
  "Call a weaver API operation for db-file's world and return Clojure data."
  [db-file {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}} op & args]
  (let [meta (metadata-for db-file)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (fixed-form op args) timeout-ms {:operation op
                                                       :canonical-db-path (:canonical-db-path meta)}))))

(defn status-world
  "Return identity metadata for the running weaver in config-dir's world."
  [config-dir & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for-world config-dir)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms))))

(defn status
  "Return identity metadata for the running weaver serving db-file."
  [db-file & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for db-file)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms))))

(defn stop-world
  "Stop the running weaver in config-dir's world."
  [config-dir & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for-world config-dir)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (stop-form) timeout-ms {:operation :stop
                                              :config-dir (:config-dir meta)}))))

(defn stop
  "Stop the running weaver serving db-file."
  [db-file & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for db-file)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (stop-form) timeout-ms {:operation :stop
                                              :canonical-db-path (:canonical-db-path meta)}))))

(defn init
  "Initialize the database served by the weaver for db-file."
  [db-file & [opts]]
  (call db-file (or opts {}) :init))

(defn add
  "Add a strand through the weaver serving db-file."
  [db-file task & [opts]]
  (call db-file (or opts {}) :add task))

(defn update
  "Apply patch to a strand through the weaver serving db-file."
  [db-file id patch & [opts]]
  (call db-file (or opts {}) :update id patch))

(defn supersede
  "Mark old-id as superseded by replacement-id through the weaver serving db-file."
  [db-file old-id replacement-id & [opts]]
  (call db-file (or opts {}) :supersede old-id replacement-id))

(defn show
  "Return a strand by id through the weaver serving db-file."
  [db-file id & [opts]]
  (call db-file (or opts {}) :show id))

(defn list
  "Return strands from the weaver serving db-file.

  With query-def and params, evaluates an ad-hoc query."
  ([db-file]
   (call db-file {} :list))
  ([db-file opts]
   (call db-file (or opts {}) :list))
  ([db-file query-def params]
   (call db-file {} :list query-def params))
  ([db-file query-def params opts]
   (call db-file (or opts {}) :list query-def params)))

(defn ready
  "Return ready strands from the weaver serving db-file.

  With query-def and params, filters readiness through an ad-hoc query."
  ([db-file]
   (call db-file {} :ready))
  ([db-file opts]
   (call db-file (or opts {}) :ready))
  ([db-file query-def params]
   (call db-file {} :ready query-def params))
  ([db-file query-def params opts]
   (call db-file (or opts {}) :ready query-def params)))

(defn register-query
  "Register query-def under query-name in the weaver serving db-file.

  Returns the registered query entry, not the full registry."
  [db-file query-name query-def & [opts]]
  (call db-file (or opts {}) :register-query query-name query-def))

(defn load-queries
  "Merge query-defs into the daemon query registry for db-file.

  Returns the loaded query entries, not the full registry."
  [db-file query-defs & [opts]]
  (call db-file (or opts {}) :load-queries query-defs))

(defn queries
  "Return registered daemon queries for db-file."
  [db-file & [opts]]
  (call db-file (or opts {}) :queries))

(defn resolve-query
  "Resolve query-name in the daemon query registry for db-file."
  [db-file query-name & [opts]]
  (call db-file (or opts {}) :resolve-query query-name))

(defn list-query
  "Return strands matching registered query-name and params for db-file."
  [db-file query-name params & [opts]]
  (call db-file (or opts {}) :list-query query-name params))

(defn ready-query
  "Return ready strands matching registered query-name and params for db-file."
  [db-file query-name params & [opts]]
  (call db-file (or opts {}) :ready-query query-name params))
