(ns skein.core.client
  "Thin Clojure client for calling a running Skein weaver over nREPL.

  This namespace validates published weaver metadata, verifies daemon identity,
  and routes public client operations to the daemon-owned API surface."
  (:refer-clojure :exclude [list update])
  (:require [clojure.edn :as edn]
            [nrepl.core :as nrepl]
            [skein.core.weaver.config :as config]
            [skein.core.weaver.metadata :as metadata])
  (:import [java.net InetAddress]))

(def ^:private default-timeout-ms 2000)

(def ^:private api-symbols
  {:init 'skein.api.weaver.alpha/init
   :add 'skein.api.weaver.alpha/add
   :update 'skein.api.weaver.alpha/update
   :show 'skein.api.weaver.alpha/show
   :burn-by-id 'skein.api.graph.alpha/burn-by-id!
   :burn-by-ids 'skein.api.graph.alpha/burn-by-ids!
   :list 'skein.api.weaver.alpha/list
   :ready 'skein.api.weaver.alpha/ready
   :supersede 'skein.api.weaver.alpha/supersede
   :declare-acyclic-relation! 'skein.api.weaver.alpha/declare-acyclic-relation!
   :acyclic-relations 'skein.api.weaver.alpha/acyclic-relations
   :register-query 'skein.api.graph.alpha/register-query
   :load-queries 'skein.api.graph.alpha/load-queries
   :queries 'skein.api.graph.alpha/queries
   :query-explain 'skein.api.graph.alpha/query-explain
   :resolve-query 'skein.api.graph.alpha/resolve-query
   :list-query 'skein.api.weaver.alpha/list-query
   :ready-query 'skein.api.weaver.alpha/ready-query
   :query-ids 'skein.api.graph.alpha/query-ids!
   :strands-by-ids 'skein.api.graph.alpha/strands-by-ids
   :ancestor-root-ids 'skein.api.graph.alpha/ancestor-root-ids
   :subgraph 'skein.api.graph.alpha/subgraph
   :register-view! 'skein.api.views.alpha/register-view!
   :view! 'skein.api.views.alpha/view!
   :views 'skein.api.views.alpha/views
   :register-event-handler! 'skein.api.events.alpha/register!
   :unregister-event-handler! 'skein.api.events.alpha/unregister!
   :event-handlers 'skein.api.events.alpha/handlers
   :recent-event-failures 'skein.api.events.alpha/recent-failures
   :register-hook! 'skein.api.hooks.alpha/register!
   :unregister-hook! 'skein.api.hooks.alpha/unregister!
   :hooks 'skein.api.hooks.alpha/hooks
   :register-pattern! 'skein.api.patterns.alpha/register-pattern!
   :register-op! 'skein.api.weaver.alpha/register-op!
   :replace-op! 'skein.api.weaver.alpha/replace-op!
   :ops 'skein.api.weaver.alpha/ops
   :resolve-op 'skein.api.weaver.alpha/resolve-op
   :op! 'skein.api.weaver.alpha/op!
   :patterns 'skein.api.patterns.alpha/patterns
   :resolve-pattern 'skein.api.patterns.alpha/pattern
   :pattern-explain 'skein.api.patterns.alpha/explain
   :weave! 'skein.api.patterns.alpha/weave!
   :apply-batch 'skein.api.batch.alpha/apply!
   :approved-spools 'skein.api.runtime.alpha/approved
   :sync-approved-spools 'skein.api.runtime.alpha/sync!
   :approved-spool-syncs 'skein.api.runtime.alpha/syncs
   :reload-config! 'skein.api.runtime.alpha/reload!
   :use! 'skein.api.runtime.alpha/use!
   :uses 'skein.api.runtime.alpha/uses
   :use 'skein.api.runtime.alpha/use})

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
  non-loopback endpoint. Requires an explicit selected config dir. An explicit
  state dir may be supplied by mill-routed helpers."
  ([config-dir]
   (metadata-for-world config-dir nil))
  ([config-dir state-dir]
   (let [world (if state-dir
                 (config/world config-dir state-dir (str state-dir "/data"))
                 (config/world config-dir))
         meta (metadata/read-metadata world)]
     (when (metadata/stale-or-missing? meta)
       (fail "Weaver metadata is missing or stale" {:type :skein.core.client/missing-or-stale-metadata
                                                    :config-dir (:config-dir world)
                                                    :metadata meta}))
     (when-not (= (:config-dir world) (:config-dir meta))
       (fail "Weaver metadata config dir does not match requested world" {:type :skein.core.client/metadata-config-mismatch
                                                                          :expected (:config-dir world)
                                                                          :actual (:config-dir meta)
                                                                          :metadata meta}))
     (when-not (loopback-host? (get-in meta [:endpoint :host]))
       (fail "Weaver metadata endpoint is not loopback" {:type :skein.core.client/non-local-endpoint
                                                         :endpoint (:endpoint meta)}))
     meta)))

(def ^:private hooked-operation-request-contexts
  {:add {:request/source :nrepl :request/operation :add}
   :update {:request/source :nrepl :request/operation :update}
   :supersede {:request/source :nrepl :request/operation :supersede}
   :burn-by-id {:request/source :nrepl :request/operation :burn}
   :burn-by-ids {:request/source :nrepl :request/operation :burn}
   :weave! {:request/source :nrepl :request/operation :weave}
   :apply-batch {:request/source :nrepl :request/operation :apply-batch}})

(defn- runtime-form
  "Return code that resolves the runtime serving the connected nREPL port."
  [port]
  (str "(skein.core.weaver.runtime/runtime-for-nrepl-port " port ")"))

(defn- fixed-form
  "Return an nREPL form that invokes a known weaver API operation with args."
  [op args port]
  (let [api-symbol (or (api-symbols op)
                       (fail "Unknown weaver API operation" {:type :skein.core.client/unknown-operation
                                                             :operation op}))
        call-args (cond-> (vec args)
                    (contains? hooked-operation-request-contexts op)
                    (conj (hooked-operation-request-contexts op)))]
    (str "(do "
         "(require '[" (namespace api-symbol) "] '[skein.core.weaver.runtime]) "
         "(let [rt " (runtime-form port) " args '" (pr-str call-args) "] "
         "(try {:ok true :value (apply " api-symbol " rt args)} "
         "(catch Throwable t {:ok false :class (str (class t)) :message (ex-message t) :data (ex-data t)})))"
         ")")))

(defn- identity-form
  "Return an nREPL form that reads the connected weaver runtime metadata."
  [port]
  (str "(do (require '[skein.core.weaver.runtime]) (:metadata " (runtime-form port) "))"))

(defn- stop-form
  "Return an nREPL form that schedules the connected weaver to stop."
  [port]
  (str "(do (require '[skein.core.weaver.runtime]) (let [rt " (runtime-form port) "] (future (Thread/sleep 50) (skein.core.weaver.runtime/stop! rt)) {:stopped true}))"))

(defn- connect
  "Open an nREPL connection to the endpoint in validated weaver metadata."
  ^java.io.Closeable [metadata timeout-ms]
  (let [{:keys [host port]} (:endpoint metadata)]
    (try
      (nrepl/connect :host host :port port :timeout timeout-ms)
      (catch Exception e
        (throw (ex-info "Unable to connect to weaver nREPL endpoint" {:type :skein.core.client/connection-failed
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
                      (throw (ex-info "Weaver nREPL request timed out" (assoc context :type :skein.core.client/timeout) e)))
                    (catch Exception e
                      (throw (ex-info "Weaver nREPL request failed" (assoc context :type :skein.core.client/request-failed) e))))
        statuses (set (mapcat :status responses))]
    (when (contains? statuses "eval-error")
      (let [err (some :err responses)]
        (fail "Weaver API call failed" (assoc context :type :skein.core.client/weaver-error
                                              :err err
                                              :responses responses))))
    (when (contains? statuses "error")
      (fail "Weaver nREPL returned an error" (assoc context :type :skein.core.client/nrepl-error
                                                    :responses responses)))
    (if-let [value (some :value responses)]
      (let [result (edn/read-string value)]
        (if (and (map? result) (contains? result :ok))
          (if (:ok result)
            (:value result)
            (fail "Weaver API call failed" (assoc context
                                                  :type :skein.core.client/weaver-error
                                                  :weaver-class (:class result)
                                                  :weaver-message (:message result)
                                                  :weaver-data (:data result))))
          result))
      (if (empty? responses)
        (fail "Weaver nREPL request timed out" (assoc context :type :skein.core.client/timeout
                                                      :responses responses))
        (fail "Weaver nREPL returned no value" (assoc context :type :skein.core.client/no-value
                                                      :responses responses))))))

(defn- verify-identity!
  "Verify that conn serves the expected weaver runtime metadata."
  [conn expected timeout-ms]
  (let [actual (eval-form conn (identity-form (get-in expected [:endpoint :port])) timeout-ms {:operation :identity})]
    (when-not (= (:config-dir expected) (:config-dir actual))
      (fail "Connected weaver serves a different config dir" {:type :skein.core.client/config-mismatch
                                                              :expected (:config-dir expected)
                                                              :actual (:config-dir actual)}))
    (when-not (= (:nonce expected) (:nonce actual))
      (fail "Connected weaver identity does not match runtime metadata" {:type :skein.core.client/identity-mismatch
                                                                         :expected (:nonce expected)
                                                                         :actual (:nonce actual)}))
    (when-not (= (:protocol-version expected) (:protocol-version actual))
      (fail "Connected weaver protocol does not match runtime metadata" {:type :skein.core.client/protocol-mismatch
                                                                         :expected (:protocol-version expected)
                                                                         :actual (:protocol-version actual)}))
    actual))

(defn call-world
  "Call a weaver API operation in config-dir's world and return Clojure data."
  [config-dir {:keys [timeout-ms state-dir] :or {timeout-ms default-timeout-ms}} op & args]
  (let [meta (metadata-for-world config-dir state-dir)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (fixed-form op args (get-in meta [:endpoint :port])) timeout-ms {:operation op
                                                                                       :config-dir (:config-dir meta)}))))

(defn- raw-form
  "Return an nREPL form that evaluates code under the connected runtime binding.

  Code evaluates via load-string so its top-level forms compile one at a time
  and in-code requires/aliases work like they do at a REPL. It runs under the
  runtime spool classloader so synced spool namespaces are requirable, matching
  trusted startup-file evaluation."
  [code port]
  (str "(do "
       "(require '[skein.core.weaver.runtime]) "
       "(let [rt " (runtime-form port) "] "
       "(skein.core.weaver.runtime/with-runtime-and-spool-classloader rt "
       "(fn [] (try {:ok true :value (clojure.core/load-string " (pr-str code) ")} "
       "(catch Throwable t "
       ;; load-string wraps thrown exceptions in CompilerException; report the cause
       "(let [t (if (and (instance? clojure.lang.Compiler$CompilerException t) (ex-cause t)) (ex-cause t) t)] "
       "{:ok false :class (str (class t)) :message (ex-message t) :data (ex-data t)})))))))"))

(defn eval-in-world
  "Evaluate weaver-routed code in config-dir's world and return Clojure data.

  `code` is a form string evaluated in the weaver runtime's thread-local
  ambient binding, so `skein.api.current.alpha/runtime` resolves to the
  connected weaver. Result values must be EDN-readable; weaver-side exceptions
  and transport failures throw ExceptionInfo with client error context."
  [config-dir {:keys [timeout-ms state-dir] :or {timeout-ms default-timeout-ms}} code]
  (let [meta (metadata-for-world config-dir state-dir)]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (raw-form code (get-in meta [:endpoint :port])) timeout-ms {:operation :eval
                                                                                  :config-dir (:config-dir meta)}))))

(defn status-world
  "Return identity metadata for the running weaver in config-dir's world."
  [config-dir & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for-world config-dir (:state-dir opts))]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms))))

(defn stop-world
  "Stop the running weaver in config-dir's world."
  [config-dir & [opts]]
  (let [timeout-ms (:timeout-ms (or opts {}) default-timeout-ms)
        meta (metadata-for-world config-dir (:state-dir opts))]
    (with-open [conn (connect meta timeout-ms)]
      (verify-identity! conn meta timeout-ms)
      (eval-form conn (stop-form (get-in meta [:endpoint :port])) timeout-ms {:operation :stop
                                                                              :config-dir (:config-dir meta)}))))
