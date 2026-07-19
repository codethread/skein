(ns skein.api.peers.alpha
  "Discover and call local sibling weavers from mill-published runtime metadata."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.core.weaver.metadata :as metadata]
            [skein.core.weaver.protocol :as protocol])
  (:import [java.io BufferedReader BufferedWriter File InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]
           [java.util UUID]))

(defn- state-root
  "Return Skein's mill state root for the current process environment."
  []
  (io/file (or (System/getenv "XDG_STATE_HOME")
               (str (System/getProperty "user.home") File/separator ".local" File/separator "state"))
           "skein"))

(defn- weaver-metadata-files [root]
  (let [weavers (io/file root "weavers")]
    (if (.isDirectory weavers)
      (->> (or (seq (.listFiles weavers)) [])
           (filter #(.isDirectory ^java.io.File %))
           (map #(io/file % "weaver.edn"))
           (filter #(.isFile ^java.io.File %)))
      [])))

(defn- expand-home
  "Expand a leading `~`/`~/` to the user home directory, matching the Go-side
  source-path handling; other paths pass through unchanged."
  [path]
  (let [home (System/getProperty "user.home")]
    (cond
      (= path "~") home
      (str/starts-with? path "~/") (str home (subs path 1))
      :else path)))

(defn- canonical-path [path]
  (.getPath (.getCanonicalFile (io/file (expand-home path)))))

(defn- malformed-metadata [^java.io.File file metadata cause]
  (ex-info "Malformed weaver metadata"
           (cond-> {:code :peer/malformed-metadata
                    :file (.getPath file)}
             metadata (assoc :metadata metadata))
           cause))

(defn- read-peer-metadata [^java.io.File file]
  (let [state-dir (.getPath (.getParentFile file))
        m (try
            (metadata/read-metadata {:state-dir state-dir})
            (catch Throwable t
              (throw (malformed-metadata file nil t))))]
    (when-not (and (metadata/valid-metadata? m)
                   (= (canonical-path state-dir) (canonical-path (:state-dir m))))
      (throw (malformed-metadata file m nil)))
    m))

(defn- row [m]
  {:name (:name m)
   :workspace (:config-dir m)
   :weaver-id (:nonce m)
   :protocol-version (:protocol-version m)
   :socket-path (:socket-path m)
   :state-dir (:state-dir m)
   :running? (not (metadata/stale-or-missing? m))})

(defn peers
  "Return data-first rows for weaver metadata under the mill state root.

  Stale rows are included with `:running? false`. Present malformed metadata
  throws with `:code :peer/malformed-metadata` rather than being skipped."
  []
  (->> (weaver-metadata-files (state-root))
       (sort-by #(.getPath ^java.io.File %))
       (map read-peer-metadata)
       (mapv row)))

(defn- path-like?
  "True when the caller explicitly wrote a filesystem path: contains a `/` or
  starts with `~`. Bare tokens always resolve as logical peer names, so a
  local directory named like a peer can never shadow the name."
  [value]
  (when (string? value)
    (or (str/includes? value "/")
        (str/starts-with? value "~"))))

(defn- candidate-summary [rows]
  (mapv #(select-keys % [:name :workspace :weaver-id :socket-path :state-dir]) rows))

(defn- resolve-peer
  "Resolve exactly one running peer by friendly name or selected workspace path.

  Explicitly path-like input (contains `/`, or starts with `~`) matches the
  canonical selected workspace path; any bare token matches friendly names, so
  a local directory named like a peer never shadows the logical name. Stale,
  missing, and ambiguous matches fail loudly with domain-style `:code` data."
  [name-or-workspace]
  (let [rows (peers)
        by-path? (path-like? name-or-workspace)
        wanted (if by-path?
                 (canonical-path name-or-workspace)
                 (str name-or-workspace))
        matches (filterv (fn [row]
                           (if by-path?
                             (= wanted (canonical-path (:workspace row)))
                             (= wanted (:name row))))
                         rows)
        running (filterv :running? matches)]
    (cond
      (empty? matches)
      (throw (ex-info "No matching peer weaver"
                      {:code :peer/not-found
                       :query name-or-workspace
                       :match-by (if by-path? :workspace :name)}))

      (empty? running)
      (throw (ex-info "Matching peer weaver is stale"
                      {:code :peer/stale
                       :query name-or-workspace
                       :match-by (if by-path? :workspace :name)
                       :candidates (candidate-summary matches)}))

      (> (count running) 1)
      (throw (ex-info "Ambiguous peer weaver name"
                      {:code :peer/ambiguous
                       :query name-or-workspace
                       :candidates (candidate-summary running)}))

      :else
      (first running))))

(defn- resolved-peer [peerish]
  (if (map? peerish)
    peerish
    (resolve-peer peerish)))

(defn- operation-name [op]
  (cond
    (string? op) op
    (and (or (keyword? op) (symbol? op)) (nil? (namespace op))) (name op)
    :else (throw (ex-info "Peer operation must be a string, unqualified symbol, or unqualified keyword" {:operation op}))))

(defn- ensure-peer-protocol! [peer]
  (when-not (= protocol/version (:protocol-version peer))
    (throw (ex-info "Peer metadata protocol version mismatch"
                    {:code :peer/protocol-version-mismatch
                     :peer (select-keys peer [:name :workspace :weaver-id :socket-path :state-dir])
                     :expected protocol/version
                     :actual (:protocol-version peer)})))
  peer)

(defn- peer-identity [peer]
  (select-keys peer [:name :workspace :weaver-id :socket-path :state-dir]))

(defn- socket-error [peer op message details]
  (ex-info message
           {:code :peer/socket-error
            :peer (peer-identity peer)
            :operation op
            :error (or details {})}))

(defn- transport-failure [peer op cause]
  (ex-info (str "Peer transport failed for " (or (:name peer) (:workspace peer)) " operation " op)
           {:code :peer/transport-failed
            :peer (peer-identity peer)
            :operation op}
           cause))

(defn- request-envelope [peer op args request-id]
  {"protocol_version" protocol/version
   "request_id" request-id
   "weaver_id" (:weaver-id peer)
   "operation" op
   "arguments" args
   "options" {}})

(defn- socket-roundtrip! [peer op envelope]
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

(defn- valid-error-envelope? [error]
  (and (map? error)
       (#{"domain" "protocol" "transport"} (get error "type"))
       (string? (get error "code"))
       (string? (get error "message"))
       (map? (get error "details"))))

(defn- validate-lifecycle-result! [peer op result]
  (when (= "status" op)
    (when-not (and (map? result)
                   (= (:weaver-id peer) (get result "weaver_id")))
      (throw (socket-error peer op "Peer lifecycle response identity mismatch" {"type" "protocol"
                                                                                "code" "protocol/identity-mismatch"
                                                                                "message" "weaver id mismatch"
                                                                                "details" {}}))))
  result)

(defn- reject-stream-response!
  "A peer that answers with a stream header cannot be consumed by `call!`.

  Fail loudly without draining the stream (SPEC-004-D003.C9): the roundtrip read
  only the header line, and the connection is closed on return."
  [peer op response]
  (when (and (map? response) (true? (get response "stream")))
    (throw (ex-info "Peer op streams; call! cannot consume a stream response"
                    {:code :peer/stream-unsupported
                     :peer (peer-identity peer)
                     :operation op})))
  response)

(defn- verify-response! [peer op request-id response]
  (when-not (map? response)
    (throw (socket-error peer op "Peer response is not an object" {})))
  (when-not (= protocol/version (get response "protocol_version"))
    (throw (ex-info "Peer protocol version mismatch"
                    {:code :peer/protocol-version-mismatch
                     :peer (peer-identity peer)
                     :operation op
                     :expected protocol/version
                     :actual (get response "protocol_version")})))
  (when-not (= request-id (get response "request_id"))
    (throw (ex-info "Peer response request id mismatch"
                    {:code :peer/request-id-mismatch
                     :peer (peer-identity peer)
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

(defn- call-frame
  "Build the request frame for a peer op name.

  `status` maps to the minimal lifecycle operation; every other op name rides the
  `invoke` envelope carrying `argv`/`payloads` (SPEC-004-D003.C9). Rejection is
  receiving-side: there is no client-side operation allowlist."
  [op {:keys [argv payloads]}]
  (if (= "status" op)
    ["status" {}]
    ["invoke" {"name" op
               "argv" (vec argv)
               "payloads" (or payloads {})}]))

(defn call!
  "Invoke a named op on a resolved peer over the `invoke` envelope, or `status`.

  `peerish` may be a row from `peers`, a friendly name, or an existing workspace
  path. `op` is an op name (string or unqualified symbol/keyword); pass `\"status\"`
  for the minimal lifecycle op. Optional `args`
  is a map with `:argv` (vector of strings) and `:payloads` (name→value map) for
  the invoke envelope. Domain error envelopes become `ExceptionInfo` with
  `:code :peer/domain-error`; a peer that answers with a stream header fails
  loudly with `:code :peer/stream-unsupported` (streams are out of scope for
  `call!`). Transport failures are loud and include peer identity. No retries or
  peer lifecycle management are attempted."
  ([peerish op] (call! peerish op {}))
  ([peerish op args]
   (let [peer (ensure-peer-protocol! (resolved-peer peerish))
         op (operation-name op)
         [operation arguments] (call-frame op args)
         request-id (str (UUID/randomUUID))
         response (->> (request-envelope peer operation arguments request-id)
                       (socket-roundtrip! peer op)
                       (reject-stream-response! peer op)
                       (verify-response! peer op request-id))]
     (if (true? (get response "ok"))
       (get response "result")
       (let [error (get response "error")]
         (if (= "domain" (get error "type"))
           (throw (ex-info (get error "message" "Peer domain error")
                           {:code :peer/domain-error
                            :peer (peer-identity peer)
                            :operation op
                            :error error}))
           (throw (ex-info (get error "message" "Peer socket error")
                           {:code :peer/socket-error
                            :peer (peer-identity peer)
                            :operation op
                            :error error}))))))))
