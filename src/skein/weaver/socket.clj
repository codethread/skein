(ns skein.weaver.socket
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.query :as query])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ClosedChannelException ServerSocketChannel]
           [org.sqlite SQLiteException]))

(def allowed-operations
  #{"init" "add" "update" "show" "burn" "list" "ready" "list-query" "ready-query" "status" "stop"})

(def required-request-keys #{"protocol_version" "request_id" "weaver_id" "operation" "arguments" "options"})

(defn- protocol-error [request-id code message details]
  {"protocol_version" 1 "request_id" request-id "ok" false "result" nil
   "error" {"type" "protocol" "code" code "message" message "details" (or details {})}})

(defn- success [request-id result]
  {"protocol_version" 1 "request_id" request-id "ok" true "result" result "error" nil})

(defn- domain-error [request-id e]
  (let [message (ex-message e)
        details (or (ex-data e) {})]
    {"protocol_version" 1 "request_id" request-id "ok" false "result" nil
     "error" {"type" "domain"
              "code" (or (:code details)
                         (if (and (:canonical-query details) (contains? details :available)) "query/not-found" "domain/error"))
              "message" message
              "details" (dissoc details :code)}}))

(defn- uninitialized-db-error? [e]
  (and (instance? SQLiteException e)
       (str/includes? (or (ex-message e) "") "no such table:")))

(defn- uninitialized-db-exception []
  (ex-info "Database is not initialized; run `strand init` first" {:code "database/not-initialized"}))

(defn- string-map? [m] (and (map? m) (every? string? (vals m))))
(defn- valid-edge? [edge]
  (and (map? edge)
       (= #{"type" "to"} (set (keys edge)))
       (string? (get edge "type"))
       (string? (get edge "to"))))

(defn- argument-error [req]
  (let [op (get req "operation")
        args (get req "arguments")]
    (when-not
      (case op
        "init" (= {} args)
        "list" (and (every? #{"active"} (keys args))
                    (or (not (contains? args "active")) (boolean? (get args "active"))))
        "ready" (= {} args)
        "status" (= {} args)
        "stop" (= {} args)
        "add" (and (every? #{"title" "attributes" "active"} (keys args))
                   (contains? args "title")
                   (contains? args "attributes")
                   (string? (get args "title"))
                   (map? (get args "attributes"))
                   (or (not (contains? args "active")) (boolean? (get args "active"))))
        "update" (and (every? #{"id" "title" "active" "attributes" "edges"} (keys args))
                      (contains? args "id")
                      (string? (get args "id"))
                      (or (not (contains? args "title")) (nil? (get args "title")) (string? (get args "title")))
                      (or (not (contains? args "active")) (nil? (get args "active")) (boolean? (get args "active")))
                      (or (not (contains? args "attributes")) (nil? (get args "attributes") ) (map? (get args "attributes")))
                      (or (not (contains? args "edges")) (and (vector? (get args "edges")) (every? valid-edge? (get args "edges"))) ))
        "show" (and (= #{"id"} (set (keys args))) (string? (get args "id")))
        "burn" (and (= #{"id"} (set (keys args))) (string? (get args "id")))
        "list-query" (and (every? #{"query" "params" "active"} (keys args))
                          (contains? args "query")
                          (contains? args "params")
                          (string? (get args "query"))
                          (string-map? (get args "params"))
                          (or (not (contains? args "active")) (boolean? (get args "active"))))
        "ready-query" (and (= #{"query" "params"} (set (keys args)))
                           (string? (get args "query"))
                           (string-map? (get args "params")))
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
     "data_dir" (:data-dir m)
     "database_path" (:canonical-db-path m)
     "weaver_id" (:nonce m)
     "socket_path" (:socket-path m)
     "started_at" (:started-at m)
     "nrepl" {"host" (get-in m [:endpoint :host]) "port" (get-in m [:endpoint :port])}}))

(defn- api [sym]
  (requiring-resolve (symbol "skein.weaver.api" (name sym))))

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
        qdef (if (contains? args "active")
               [:and (query/query-expr qdef params) [:= :active (get args "active")]]
               qdef)]
    ((api op) runtime qdef params)))

(defn- dispatch [runtime op args]
  (case op
    "init" ((api 'init) runtime)
    "add" ((api 'add) runtime (cond-> {:title (get args "title")
                                         :attributes (get args "attributes")}
                                  (contains? args "active") (assoc :active (get args "active"))))
    "update" ((api 'update) runtime (get args "id")
              (cond-> {:edges (mapv (fn [edge]
                                      {:type (get edge "type")
                                       :to (get edge "to")})
                                    (or (get args "edges") []))}
                (some? (get args "title")) (assoc :title (get args "title"))
                (some? (get args "active")) (assoc :active (get args "active"))
                (some? (get args "attributes")) (assoc :attributes (get args "attributes"))))
    "show" ((api 'show) runtime (get args "id"))
    "burn" ((api 'burn-by-id) runtime (get args "id"))
    "list" (if (contains? args "active")
             ((api 'list) runtime [:= :active (get args "active")] {})
             ((api 'list) runtime))
    "ready" ((api 'ready) runtime)
    "list-query" (dispatch-query runtime 'list args)
    "ready-query" (dispatch-query runtime 'ready args)
    "status" (status-result runtime)
    "stop" {"stopping" true "pid" (get-in runtime [:metadata :pid]) "weaver_id" (get-in runtime [:metadata :nonce])}))

(defn handle-request [runtime line]
  (try
    (let [req (json/read-str line)
          request-id (get req "request_id")]
      (if-let [err (validate-request (:metadata runtime) req)]
        [err false]
        (try
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

(defn start! [runtime-state socket-path stop-fn]
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

(defn stop! [socket-runtime]
  (when socket-runtime
    (reset! (:running? socket-runtime) false)
    (.close (:server socket-runtime))
    (.delete (io/file (:socket-path socket-runtime)))))
