(ns skein.weaver.socket
  "Serve the weaver JSON protocol over a Unix-domain socket."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [skein.query :as query])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ClosedChannelException ServerSocketChannel]
           [org.sqlite SQLiteException]))

(def ^:private allowed-operations
  #{"add" "update" "supersede" "show" "burn" "list" "ready" "list-query" "ready-query" "weave" "pattern-explain" "op" "status" "stop"})

(def ^:private required-request-keys
  #{"protocol_version" "request_id" "weaver_id" "operation" "arguments" "options"})

(def ^:private payload-hook-operations
  #{"add" "update" "supersede" "burn" "weave" "op"})

(defn- protocol-error [request-id code message details]
  {"protocol_version" 1 "request_id" request-id "ok" false "result" nil
   "error" {"type" "protocol" "code" code "message" message "details" (or details {})}})

(defn- success [request-id result]
  {"protocol_version" 1 "request_id" request-id "ok" true "result" result "error" nil})

(defn- json-safe-value [value]
  (cond
    (nil? value) nil
    (or (string? value) (number? value) (boolean? value)) value
    (keyword? value) (subs (str value) 1)
    (symbol? value) (str value)
    (map? value) (into {} (map (fn [[k v]] [(json-safe-value k) (json-safe-value v)])) value)
    (sequential? value) (mapv json-safe-value value)
    (set? value) (mapv json-safe-value (sort-by pr-str value))
    :else (pr-str value)))

(defn- domain-error [request-id e]
  (let [message (ex-message e)
        details (or (ex-data e) {})]
    {"protocol_version" 1 "request_id" request-id "ok" false "result" nil
     "error" {"type" "domain"
              "code" (or (:code details)
                         (if (and (:canonical-query details) (contains? details :available)) "query/not-found" "domain/error"))
              "message" message
              "details" (json-safe-value (dissoc details :code))}}))

(defn- uninitialized-db-error? [e]
  (and (instance? SQLiteException e)
       (str/includes? (or (ex-message e) "") "no such table:")))

(defn- uninitialized-db-exception []
  (ex-info "Database is not initialized; run `strand init` first" {:code "database/not-initialized"}))

(defn- string-map? [m] (and (map? m) (every? string? (vals m))))
(def ^:private readable-states #{"active" "closed" "replaced"})
(def ^:private generic-states #{"active" "closed"})
(defn- valid-edge? [edge]
  (and (map? edge)
       (every? #{"type" "to" "attributes"} (keys edge))
       (contains? edge "type")
       (contains? edge "to")
       (string? (get edge "type"))
       (string? (get edge "to"))
       (or (not (contains? edge "attributes"))
           (map? (get edge "attributes")))))

(defn- argument-error [req]
  (let [op (get req "operation")
        args (get req "arguments")]
    (when-not
      (case op
        "list" (and (every? #{"state"} (keys args))
                    (or (not (contains? args "state")) (contains? readable-states (get args "state"))))
        "ready" (= {} args)
        "status" (= {} args)
        "stop" (= {} args)
        "add" (and (every? #{"title" "attributes" "state" "edges"} (keys args))
                   (contains? args "title")
                   (contains? args "attributes")
                   (string? (get args "title"))
                   (map? (get args "attributes"))
                   (or (not (contains? args "state")) (contains? generic-states (get args "state")))
                   (or (not (contains? args "edges")) (and (vector? (get args "edges")) (every? valid-edge? (get args "edges")))))
        "update" (and (every? #{"id" "title" "state" "attributes" "edges"} (keys args))
                      (contains? args "id")
                      (string? (get args "id"))
                      (or (not (contains? args "title")) (nil? (get args "title")) (string? (get args "title")))
                      (or (not (contains? args "state")) (nil? (get args "state")) (contains? generic-states (get args "state")))
                      (or (not (contains? args "attributes")) (nil? (get args "attributes")) (map? (get args "attributes")))
                      (or (not (contains? args "edges")) (and (vector? (get args "edges")) (every? valid-edge? (get args "edges")))))
        "show" (and (= #{"id"} (set (keys args))) (string? (get args "id")))
        "supersede" (and (= #{"old_id" "replacement_id"} (set (keys args)))
                          (string? (get args "old_id"))
                          (string? (get args "replacement_id")))
        "burn" (and (= #{"id"} (set (keys args))) (string? (get args "id")))
        "list-query" (and (every? #{"query" "params" "state"} (keys args))
                          (contains? args "query")
                          (contains? args "params")
                          (string? (get args "query"))
                          (string-map? (get args "params"))
                          (or (not (contains? args "state")) (contains? readable-states (get args "state"))))
        "ready-query" (and (= #{"query" "params"} (set (keys args)))
                           (string? (get args "query"))
                           (string-map? (get args "params")))
        "weave" (and (= #{"pattern" "input"} (set (keys args)))
                     (string? (get args "pattern")))
        "pattern-explain" (and (= #{"pattern"} (set (keys args)))
                               (string? (get args "pattern")))
        "op" (and (= #{"name" "args"} (set (keys args)))
                  (string? (get args "name"))
                  (not (str/blank? (get args "name")))
                  (vector? (get args "args"))
                  (every? string? (get args "args")))
        false)
      (protocol-error (get req "request_id") "protocol/malformed-request" "operation arguments do not match protocol" {"operation" op}))))

(defn- validate-request [metadata req]
  (let [keys-present (set (keys req))]
    (cond
      (not= required-request-keys keys-present)
      (protocol-error (get req "request_id") "protocol/malformed-request" "Request envelope keys do not match protocol" {"keys" (vec keys-present)})
      (not= 1 (get req "protocol_version"))
      (protocol-error (get req "request_id") "protocol/unsupported-version" "Unsupported protocol version" {})
      (not (string? (get req "request_id")))
      (protocol-error nil "protocol/malformed-request" "request_id must be a string" {})
      (not= (:nonce metadata) (get req "weaver_id"))
      (protocol-error (get req "request_id") "protocol/identity-mismatch" "Weaver identity mismatch" {})
      (not (allowed-operations (get req "operation")))
      (protocol-error (get req "request_id") "protocol/operation-not-allowed" "Operation is not available over JSON socket" {"operation" (get req "operation")})
      (not (map? (get req "arguments")))
      (protocol-error (get req "request_id") "protocol/malformed-request" "arguments must be an object" {})
      (not= {} (get req "options"))
      (protocol-error (get req "request_id") "protocol/malformed-request" "options must be empty" {})

      :else (argument-error req))))

(defn- status-result [runtime]
  (let [m (:metadata runtime)]
    {"healthy" true
     "pid" (:pid m)
     "protocol_version" (:protocol-version m)
     "config_dir" (:config-dir m)
     "state_dir" (:state-dir m)
     "data_dir" (:data-dir m)
     "database_path" (:canonical-db-path m)
     "weaver_id" (:nonce m)
     "socket_path" (:socket-path m)
     "started_at" (:started-at m)
     "nrepl" {"host" (get-in m [:endpoint :host]) "port" (get-in m [:endpoint :port])}}))

(defn- api [sym]
  (requiring-resolve (symbol "skein.weaver.api" (name sym))))

(defn- run-payload-hooks! [runtime req]
  (when (payload-hook-operations (get req "operation"))
    ((api 'run-payload-received-hooks!) runtime {:request/source :json-socket
                                                 :request/operation (keyword (get req "operation"))
                                                 :request/id (get req "request_id")
                                                 :request/args (get req "arguments")
                                                 :request/options (get req "options")})))

(defn- query-name [name]
  (let [trimmed (str/trim name)
        canonical (if (str/starts-with? trimmed ":") (subs trimmed 1) trimmed)]
    (when (str/blank? canonical)
      (throw (ex-info "Query names must not be blank" {:query name})))
    (symbol canonical)))

(defn- query-params [query-def params]
  (let [declared (set (:params query-def))
        declared-names (set (map name declared))
        provided (set (keys params))]
    (when-let [unknown (seq (remove declared-names provided))]
      (throw (ex-info "Unknown query parameters" {:params (vec unknown)
                                                   :declared (vec declared)})))
    (into {} (keep (fn [k]
                     (when (contains? params (name k))
                       [k (get params (name k))]))
                   declared))))

(defn- dispatch-query [runtime op args]
  (let [qdef ((api 'resolve-query) runtime (query-name (get args "query")))
        params (query-params qdef (get args "params"))
        qdef (if (contains? args "state")
               [:and (query/query-expr qdef params) [:= :state (get args "state")]]
               qdef)]
    ((api op) runtime qdef params)))

(defn- request-context [op]
  {:request/source :json-socket
   :request/operation (keyword op)})

(defn- dispatch [runtime op args]
  (case op
    "add" ((api 'add) runtime (cond-> {:title (get args "title")
                                         :attributes (get args "attributes")}
                                  (contains? args "state") (assoc :state (get args "state"))
                                  (contains? args "edges") (assoc :edges (mapv (fn [edge]
                                                                                  (cond-> {:type (get edge "type")
                                                                                           :to (get edge "to")}
                                                                                    (contains? edge "attributes")
                                                                                    (assoc :attributes (get edge "attributes"))))
                                                                                (get args "edges"))))
           (request-context op))
    "update" ((api 'update) runtime (get args "id")
              (cond-> {}
                (contains? args "edges") (assoc :edges (mapv (fn [edge]
                                                                (cond-> {:type (get edge "type")
                                                                         :to (get edge "to")}
                                                                  (contains? edge "attributes")
                                                                  (assoc :attributes (get edge "attributes"))))
                                                              (get args "edges")))
                (some? (get args "title")) (assoc :title (get args "title"))
                (some? (get args "state")) (assoc :state (get args "state"))
                (some? (get args "attributes")) (assoc :attributes (get args "attributes")))
              (request-context op))
    "show" ((api 'show) runtime (get args "id"))
    "supersede" ((api 'supersede) runtime (get args "old_id") (get args "replacement_id") (request-context op))
    "burn" ((api 'burn-by-ids) runtime [(get args "id")] (request-context op))
    "list" (if (contains? args "state")
             ((api 'list) runtime [:= :state (get args "state")] {})
             ((api 'list) runtime))
    "ready" ((api 'ready) runtime)
    "list-query" (dispatch-query runtime 'list args)
    "ready-query" (dispatch-query runtime 'ready args)
    "weave" ((api 'weave!) runtime (query-name (get args "pattern")) (walk/keywordize-keys (get args "input")) (request-context op))
    "pattern-explain" ((api 'pattern-explain) runtime (query-name (get args "pattern")))
    "op" ((api 'op!) runtime (query-name (get args "name")) (get args "args"))
    "status" (status-result runtime)
    "stop" {"stopping" true "pid" (get-in runtime [:metadata :pid]) "weaver_id" (get-in runtime [:metadata :nonce])}))

(defn handle-request
  "Handle one newline-delimited JSON protocol request.

  Returns `[response stop?]`, where `stop?` asks the socket loop to stop the
  runtime after the response is flushed."
  [runtime line]
  (try
    (let [req (json/read-str line)
          request-id (get req "request_id")]
      (if-let [err (validate-request (:metadata runtime) req)]
        [err false]
        (try
          (run-payload-hooks! runtime req)
          [(success request-id (dispatch runtime (get req "operation") (get req "arguments")))
           (= "stop" (get req "operation"))]
          (catch clojure.lang.ExceptionInfo e
            [(domain-error request-id e) false])
          (catch Exception e
            (if (uninitialized-db-error? e)
              [(domain-error request-id (uninitialized-db-exception)) false]
              [{"protocol_version" 1 "request_id" request-id "ok" false "result" nil
                "error" {"type" "transport" "code" "transport/server-error" "message" (ex-message e) "details" {}}} false])))))
    (catch Exception _
      [(protocol-error nil "protocol/malformed-json" "Request must be one JSON object followed by newline" {}) false])))

(defn start!
  "Start the JSON socket server for `runtime-state` at `socket-path`."
  [runtime-state socket-path stop-fn]
  (let [file (io/file socket-path)
        _ (.mkdirs (.getParentFile file))
        _ (.delete file)
        address (UnixDomainSocketAddress/of socket-path)
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)
        running? (atom true)]
    (try
      (.bind server address)
      (let [thread (Thread.
                    (fn []
                      (while @running?
                        (try
                          (with-open [ch (.accept server)
                                      rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                                      wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
                            (let [line (.readLine rdr)
                                  [response stop?] (if line
                                                     (handle-request @runtime-state line)
                                                     [(protocol-error nil "protocol/malformed-request" "Empty request" {}) false])]
                              (.write wrt (json/write-str response))
                              (.newLine wrt)
                              (.flush wrt)
                              (when stop?
                                (future (stop-fn)))))
                          (catch ClosedChannelException _)
                          (catch Exception _))))
                    "skein-weaver-json-socket")]
        (.setDaemon thread true)
        (.start thread)
        {:server server :thread thread :running? running? :socket-path socket-path})
      (catch Throwable t
        (.close server)
        (.delete file)
        (throw t)))))

(defn stop!
  "Stop a JSON socket server runtime and remove its socket file."
  [socket-runtime]
  (when socket-runtime
    (reset! (:running? socket-runtime) false)
    (.close (:server socket-runtime))
    (.delete (io/file (:socket-path socket-runtime)))))
