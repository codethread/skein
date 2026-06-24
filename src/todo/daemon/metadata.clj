(ns todo.daemon.metadata
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.util UUID]))

(def runtime-dir-name "todo-daemon")

(defn canonical-db-path [db-file]
  (.getPath (.getCanonicalFile (io/file db-file))))

(defn stable-path-hash [canonical-path]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes canonical-path StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn runtime-dir []
  (io/file (System/getProperty "java.io.tmpdir") runtime-dir-name))

(defn metadata-file [canonical-path]
  (io/file (runtime-dir) (str (stable-path-hash canonical-path) ".edn")))

(defn new-nonce []
  (str (UUID/randomUUID)))

(defn metadata-shape [{:keys [pid host port canonical-db-path nonce]}]
  {:pid pid
   :transport :nrepl
   :endpoint {:host host :port port}
   :canonical-db-path canonical-db-path
   :nonce nonce})

(defn write-atomic! [file data]
  (.mkdirs (.getParentFile file))
  (let [tmp (io/file (.getParentFile file) (str (.getName file) "." (new-nonce) ".tmp"))]
    (spit tmp (with-out-str (pprint/pprint data)))
    (Files/move (.toPath tmp)
                (.toPath file)
                (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                StandardCopyOption/REPLACE_EXISTING]))
    file))

(defn publish! [metadata]
  (let [file (metadata-file (:canonical-db-path metadata))]
    (write-atomic! file metadata)
    file))

(defn read-metadata [canonical-path]
  (let [file (metadata-file canonical-path)]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(defn delete! [canonical-path]
  (.delete (metadata-file canonical-path)))


(defn stale-or-missing? [metadata]
  (not (and (map? metadata)
            (int? (:pid metadata))
            (= :nrepl (:transport metadata))
            (string? (get-in metadata [:endpoint :host]))
            (int? (get-in metadata [:endpoint :port]))
            (string? (:canonical-db-path metadata))
            (string? (:nonce metadata)))))

