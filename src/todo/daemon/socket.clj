(ns todo.daemon.socket
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ClosedChannelException ServerSocketChannel]))

(def allowed-operations
  #{"init" "add" "update" "show" "list" "ready" "list-query" "ready-query" "status" "stop"})

(def required-request-keys #{"protocol_version" "request_id" "daemon_id" "database_path" "operation" "arguments" "options"})
(def allowed-option-keys #{"format"})

(defn- protocol-error [request-id code message details]
  {"protocol_version" 1 "request_id" request-id "ok" false "result" nil
   "error" {"type" "protocol" "code" code "message" message "details" (or details {})}})

(defn- success [request-id result]
  {"protocol_version" 1 "request_id" request-id "ok" true "result" result "error" nil})

(defn- domain-error [request-id e]
  {"protocol_version" 1 "request_id" request-id "ok" false "result" nil
   "error" {"type" "domain" "code" "domain/error" "message" (ex-message e) "details" (or (ex-data e) {})}})

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
        "list" (= {} args)
        "ready" (= {} args)
        "status" (= {} args)
        "stop" (= {} args)
        "add" (and (= #{"title" "status" "attributes"} (set (keys args)))
                   (string? (get args "title"))
                   (string? (get args "status"))
                   (map? (get args "attributes")))
        "update" (and (= #{"id" "title" "status" "attributes" "edges"} (set (keys args)))
                      (string? (get args "id"))
                      (or (nil? (get args "title")) (string? (get args "title")))
                      (or (nil? (get args "status")) (string? (get args "status")))
                      (or (nil? (get args "attributes")) (map? (get args "attributes")))
                      (vector? (get args "edges"))
                      (every? valid-edge? (get args "edges")))
        "show" (and (= #{"id"} (set (keys args))) (string? (get args "id")))
        "list-query" (and (= #{"query" "params"} (set (keys args)))
                          (string? (get args "query"))
                          (string-map? (get args "params")))
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
      (not= (:nonce metadata) (get req "daemon_id"))
      (protocol-error (get req "request_id") "protocol/identity-mismatch" "Daemon identity mismatch" {})
      (not= (:canonical-db-path metadata) (get req "database_path"))
      (protocol-error (get req "request_id") "protocol/database-mismatch" "Database path mismatch" {})
      (not (allowed-operations (get req "operation")))
      (protocol-error (get req "request_id") "protocol/operation-not-allowed" "Operation is not available over JSON socket" {"operation" (get req "operation")})
      (not (map? (get req "arguments")))
      (protocol-error (get req "request_id") "protocol/malformed-request" "arguments must be an object" {})
      (not= allowed-option-keys (set (keys (get req "options"))))
      (protocol-error (get req "request_id") "protocol/malformed-request" "options must contain only format" {})
      (not (#{{"format" "human"} {"format" "json"}} (get req "options")))
      (protocol-error (get req "request_id") "protocol/malformed-request" "format must be human or json" {})

      :else (argument-error req))))

(defn- status-result [runtime]
  (let [m (:metadata runtime)]
    {"healthy" true "pid" (:pid m) "database_path" (:canonical-db-path m)
     "daemon_id" (:nonce m) "socket_path" (get-in m [:json :socket-path])
     "nrepl" {"host" (get-in m [:endpoint :host]) "port" (get-in m [:endpoint :port])}}))

(defn- api [sym]
  (requiring-resolve (symbol "todo.daemon.api" (name sym))))

(defn- dispatch [runtime op args]
  (case op
    "init" ((api 'init) runtime)
    "add" ((api 'add) runtime {:title (get args "title") :status (get args "status") :attributes (get args "attributes")})
    "update" ((api 'update) runtime (get args "id") {:title (get args "title")
                                                       :status (get args "status")
                                                       :attributes (get args "attributes")
                                                       :edges (mapv (fn [edge]
                                                                      {:type (get edge "type")
                                                                       :to (get edge "to")})
                                                                    (get args "edges"))})
    "show" ((api 'show) runtime (get args "id"))
    "list" ((api 'list) runtime)
    "ready" ((api 'ready) runtime)
    "list-query" ((api 'list-query) runtime (get args "query") (get args "params"))
    "ready-query" ((api 'ready-query) runtime (get args "query") (get args "params"))
    "status" (status-result runtime)
    "stop" {"stopping" true "pid" (get-in runtime [:metadata :pid]) "database_path" (get-in runtime [:metadata :canonical-db-path]) "daemon_id" (get-in runtime [:metadata :nonce])}))

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
            [{"protocol_version" 1 "request_id" request-id "ok" false "result" nil
              "error" {"type" "transport" "code" "transport/server-error" "message" (ex-message e) "details" {}}} false]))))
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
                    "todo-json-socket")]
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
