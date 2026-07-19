(ns skein.api.peers.internal.shared
  "Leaf mechanics shared across the peers module's concerns: path
  canonicalisation and the peer identity projection carried in errors."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn expand-home
  "Expand a leading `~`/`~/` to the user home directory, matching the Go-side
  source-path handling; other paths pass through unchanged."
  [path]
  (let [home (System/getProperty "user.home")]
    (cond
      (= path "~") home
      (str/starts-with? path "~/") (str home (subs path 1))
      :else path)))

(defn canonical-path
  "Return the canonical filesystem path for `path` after home expansion."
  [path]
  (.getPath (.getCanonicalFile (io/file (expand-home path)))))

(defn peer-identity
  "Project the identifying keys of a peer row for error data and summaries."
  [peer]
  (select-keys peer [:name :workspace :weaver-id :socket-path :state-dir]))
