(ns skein.api.peers.internal.discovery
  "Enumerate sibling weaver metadata under the mill state root: locate
  `weaver.edn` files, read and validate them loudly, and project data-first
  rows with a running/stale determination."
  (:require [clojure.java.io :as io]
            [skein.api.peers.internal.shared :as shared]
            [skein.core.weaver.metadata :as metadata])
  (:import [java.io File]))

(defn state-root
  "Return Skein's mill state root for the current process environment."
  []
  (io/file (or (System/getenv "XDG_STATE_HOME")
               (str (System/getProperty "user.home")
                    File/separator ".local" File/separator "state"))
           "skein"))

(defn weaver-metadata-files
  "Return every `weaver.edn` file under `root`'s `weavers` directory."
  [root]
  (let [weavers (io/file root "weavers")]
    (if (.isDirectory weavers)
      (->> (or (seq (.listFiles weavers)) [])
           (filter #(.isDirectory ^File %))
           (map #(io/file % "weaver.edn"))
           (filter #(.isFile ^File %)))
      [])))

(defn malformed-metadata
  "Build the loud `:peer/malformed-metadata` failure for a metadata `file`."
  [^File file metadata cause]
  (ex-info "Malformed weaver metadata"
           (cond-> {:code :peer/malformed-metadata
                    :file (.getPath file)}
             metadata (assoc :metadata metadata))
           cause))

(defn read-peer-metadata
  "Read and validate one peer's metadata `file`; fail loudly when malformed
  or when the metadata's recorded state dir does not match the file's."
  [^File file]
  (let [state-dir (.getPath (.getParentFile file))
        m (try
            (metadata/read-metadata {:state-dir state-dir})
            (catch Throwable t
              (throw (malformed-metadata file nil t))))]
    (when-not (and (metadata/valid-metadata? m)
                   (= (shared/canonical-path state-dir)
                      (shared/canonical-path (:state-dir m))))
      (throw (malformed-metadata file m nil)))
    m))

(defn row
  "Project validated metadata `m` into a data-first peer row."
  [m]
  {:name (:name m)
   :workspace (:config-dir m)
   :weaver-id (:nonce m)
   :protocol-version (:protocol-version m)
   :socket-path (:socket-path m)
   :state-dir (:state-dir m)
   :running? (not (metadata/stale-or-missing? m))})
