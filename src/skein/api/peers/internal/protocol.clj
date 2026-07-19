(ns skein.api.peers.internal.protocol
  "The peers module's wire concern: request envelopes, the one-shot unix
  socket roundtrip, and loud response verification for `call!`
  (SPEC-004.C88). One request per connection; protocol-version and
  weaver-identity mismatches, malformed envelopes, and transport failures
  all throw structured `ex-info` carrying peer identity."
  (:require [clojure.data.json :as json]
            [skein.api.peers.internal.shared :as shared])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(def protocol-version
  "The peer JSON socket protocol version this client speaks."
  1)

(defn operation-name
  "Normalise `op` to its string name; namespaced or non-named ops fail."
  [op]
  (cond
    (string? op) op
    (and (or (keyword? op) (symbol? op)) (nil? (namespace op))) (name op)
    :else (throw (ex-info
                  "Peer operation must be a string, unqualified symbol, or unqualified keyword"
                  {:operation op}))))

(defn ensure-peer-protocol!
  "Return `peer` after checking its metadata protocol version; fail loudly
  on mismatch before any socket work."
  [peer]
  (when-not (= protocol-version (:protocol-version peer))
    (throw (ex-info "Peer metadata protocol version mismatch"
                    {:code :peer/protocol-version-mismatch
                     :peer (shared/peer-identity peer)
                     :expected protocol-version
                     :actual (:protocol-version peer)})))
  peer)

(defn socket-error
  "Build the `:peer/socket-error` failure for a malformed peer response."
  [peer op message details]
  (ex-info message
           {:code :peer/socket-error
            :peer (shared/peer-identity peer)
            :operation op
            :error (or details {})}))

(defn transport-failure
  "Build the `:peer/transport-failed` failure wrapping a connection `cause`."
  [peer op cause]
  (ex-info (str "Peer transport failed for " (or (:name peer) (:workspace peer))
                " operation " op)
           {:code :peer/transport-failed
            :peer (shared/peer-identity peer)
            :operation op}
           cause))

(defn call-frame
  "Build the request frame for a peer op name.

  `status` maps to the minimal lifecycle operation; every other op name rides
  the `invoke` envelope carrying `argv`/`payloads` (SPEC-004-D003.C9).
  Rejection is receiving-side: there is no client-side operation allowlist."
  [op {:keys [argv payloads]}]
  (if (= "status" op)
    ["status" {}]
    ["invoke" {"name" op
               "argv" (vec argv)
               "payloads" (or payloads {})}]))

(defn request-envelope
  "Build the JSON request envelope for one peer roundtrip."
  [peer op args request-id]
  {"protocol_version" protocol-version
   "request_id" request-id
   "weaver_id" (:weaver-id peer)
   "operation" op
   "arguments" args
   "options" {}})

(defn socket-roundtrip!
  "Send `envelope` over one unix socket connection to `peer` and read the
  single response line; any connection or IO failure throws
  `:peer/transport-failed`."
  [peer op envelope]
  (try
    (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                     (.connect (UnixDomainSocketAddress/of ^String (:socket-path peer))))
                rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
      (.write wrt (json/write-str envelope))
      (.newLine wrt)
      (.flush wrt)
      (json/read-str (.readLine rdr)))
    (catch Throwable t
      (throw (transport-failure peer op t)))))

(defn valid-error-envelope?
  "True when `error` is a well-formed peer error envelope."
  [error]
  (and (map? error)
       (#{"domain" "protocol" "transport"} (get error "type"))
       (string? (get error "code"))
       (string? (get error "message"))
       (map? (get error "details"))))

(defn validate-lifecycle-result!
  "For the `status` lifecycle op, verify the result claims `peer`'s weaver
  identity; return `result` otherwise unchanged."
  [peer op result]
  (when (= "status" op)
    (when-not (and (map? result)
                   (= (:weaver-id peer) (get result "weaver_id")))
      (throw (socket-error peer op "Peer lifecycle response identity mismatch"
                           {"type" "protocol"
                            "code" "protocol/identity-mismatch"
                            "message" "weaver id mismatch"
                            "details" {}}))))
  result)

(defn reject-stream-response!
  "A peer that answers with a stream header cannot be consumed by `call!`.

  Fail loudly without draining the stream (SPEC-004-D003.C9): the roundtrip
  read only the header line, and the connection is closed on return."
  [peer op response]
  (when (and (map? response) (true? (get response "stream")))
    (throw (ex-info "Peer op streams; call! cannot consume a stream response"
                    {:code :peer/stream-unsupported
                     :peer (shared/peer-identity peer)
                     :operation op})))
  response)

(defn verify-response!
  "Verify `response` speaks this protocol as `request-id`'s answer and is a
  well-formed success or error envelope; return it otherwise unchanged."
  [peer op request-id response]
  (when-not (map? response)
    (throw (socket-error peer op "Peer response is not an object" {})))
  (when-not (= protocol-version (get response "protocol_version"))
    (throw (ex-info "Peer protocol version mismatch"
                    {:code :peer/protocol-version-mismatch
                     :peer (shared/peer-identity peer)
                     :operation op
                     :expected protocol-version
                     :actual (get response "protocol_version")})))
  (when-not (= request-id (get response "request_id"))
    (throw (ex-info "Peer response request id mismatch"
                    {:code :peer/request-id-mismatch
                     :peer (shared/peer-identity peer)
                     :operation op
                     :expected request-id
                     :actual (get response "request_id")})))
  (cond
    (true? (get response "ok"))
    (do
      (when (some? (get response "error"))
        (throw (socket-error peer op "Peer success response included an error" {})))
      (validate-lifecycle-result! peer op (get response "result"))
      response)

    (false? (get response "ok"))
    (do
      (when (some? (get response "result"))
        (throw (socket-error peer op "Peer error response included a result" {})))
      (when-not (valid-error-envelope? (get response "error"))
        (throw (socket-error peer op "Peer error response envelope is malformed" {})))
      response)

    :else
    (throw (socket-error peer op "Peer response ok flag is not boolean" {}))))

(defn unwrap-result!
  "Return a verified success `response`'s result, or raise the peer's error:
  domain envelopes as `:peer/domain-error`, everything else as
  `:peer/socket-error`."
  [peer op response]
  (if (true? (get response "ok"))
    (get response "result")
    (let [error (get response "error")]
      (if (= "domain" (get error "type"))
        (throw (ex-info (get error "message" "Peer domain error")
                        {:code :peer/domain-error
                         :peer (shared/peer-identity peer)
                         :operation op
                         :error error}))
        (throw (ex-info (get error "message" "Peer socket error")
                        {:code :peer/socket-error
                         :peer (shared/peer-identity peer)
                         :operation op
                         :error error}))))))
