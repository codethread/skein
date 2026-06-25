(ns todo.daemon.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

(defn- env-or [k fallback]
  (let [v (System/getenv k)]
    (if (seq v) v fallback)))

(defn- user-home []
  (System/getProperty "user.home"))

(defn- canonical-path [file]
  (.getCanonicalPath (io/file file)))

(defn- world-map [config-dir state-dir data-dir]
  {:config-dir config-dir
   :state-dir state-dir
   :data-dir data-dir
   :config-file (str config-dir "/config.json")
   :db-path (str data-dir "/tasks.sqlite")})

(defn world
  ([]
   (let [home (user-home)
         config-home (env-or "XDG_CONFIG_HOME" (str home "/.config"))
         state-home (env-or "XDG_STATE_HOME" (str home "/.local/state"))
         data-home (env-or "XDG_DATA_HOME" (str home "/.local/share"))]
     (world-map (str config-home "/atom")
                (str state-home "/atom")
                (str data-home "/atom"))))
  ([config-dir]
   (if config-dir
     (let [dir (canonical-path config-dir)]
       (world-map dir (str dir "/state") (str dir "/data")))
     (world))))

(defn read-config [path]
  (let [file (java.io.File. path)
        eof ::eof]
    (when-not (.isFile file)
      (throw (ex-info "Daemon config file not found" {:config path})))
    (with-open [reader (PushbackReader. (clojure.java.io/reader file))]
      (let [config (edn/read {:eof nil} reader)]
        (when-not (map? config)
          (throw (ex-info "Daemon config must be an EDN map" {:config path :value config})))
        (when-let [unknown (seq (remove #{:load-files} (keys config)))]
          (throw (ex-info "Unsupported daemon config keys" {:config path :keys (vec unknown)})))
        (when-not (or (nil? (:load-files config))
                      (and (vector? (:load-files config))
                           (every? string? (:load-files config))))
          (throw (ex-info "Daemon config :load-files must be a vector of file paths" {:config path :load-files (:load-files config)})))
        (when-not (= eof (edn/read {:eof eof} reader))
          (throw (ex-info "Daemon config must contain exactly one EDN map" {:config path})))
        config))))

(defn load-config! [path]
  (let [config (read-config path)
        config-dir (.getParentFile (.getCanonicalFile (java.io.File. path)))]
    (doseq [load-path (:load-files config)]
      (let [file (java.io.File. load-path)
            resolved (if (.isAbsolute file) file (java.io.File. config-dir load-path))]
        (when-not (.isFile resolved)
          (throw (ex-info "Daemon trusted load file not found" {:config path :load-file load-path})))
        (load-file (.getCanonicalPath resolved))))
    config))
