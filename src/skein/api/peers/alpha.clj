(ns skein.api.peers.alpha
  "Discover and call local sibling weavers from mill-published runtime metadata."
  (:require [skein.api.peers.internal.discovery :as discovery]
            [skein.api.peers.internal.protocol :as protocol]
            [skein.api.peers.internal.resolution :as resolution])
  (:import [java.util UUID]))

(defn peers
  "Return data-first rows for weaver metadata under the mill state root.

  Stale rows are included with `:running? false`. Present malformed metadata
  throws with `:code :peer/malformed-metadata` rather than being skipped."
  []
  (->> (discovery/weaver-metadata-files (discovery/state-root))
       (sort-by #(.getPath ^java.io.File %))
       (map discovery/read-peer-metadata)
       (mapv discovery/row)))

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
   (let [peer (-> peerish
                  (resolution/resolved-peer peers)
                  (protocol/ensure-peer-protocol!))
         op (protocol/operation-name op)
         [operation arguments] (protocol/call-frame op args)
         request-id (str (UUID/randomUUID))]
     (->> (protocol/request-envelope peer operation arguments request-id)
          (protocol/socket-roundtrip! peer op)
          (protocol/reject-stream-response! peer op)
          (protocol/verify-response! peer op request-id)
          (protocol/unwrap-result! peer op)))))
