(ns skein.runtime-deps-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [nrepl.core :as nrepl]
            [skein.weaver.config :as daemon-config]
            [skein.weaver.runtime :as runtime]))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
             (.toPath (io/file "/tmp"))
             prefix
             (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-recursive [file]
  (doseq [child (reverse (file-seq file))]
    (.delete child)))

(defn- write-hot-lib! [config-dir suffix]
  (let [root (io/file config-dir "libs" "runtime-spike")
        ns-sym (symbol (str "runtime-spike.hot-" suffix))
        src-dir (io/file root "src" "runtime_spike")]
    (.mkdirs src-dir)
    (spit (io/file src-dir (str "hot_" suffix ".clj"))
          (str "(ns " ns-sym ")\n(defn marker [] :daemon-hot-added)\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    {:root (.getCanonicalPath root)
     :lib (symbol (str "runtime-spike/lib-" suffix))
     :ns ns-sym
     :marker (symbol (str ns-sym "/marker"))}))

(defn- daemon-value [rt form]
  (with-open [conn (nrepl/connect :host (get-in rt [:metadata :endpoint :host])
                                  :port (get-in rt [:metadata :endpoint :port]))]
    (let [responses (nrepl/message (nrepl/client conn 5000)
                                   {:op "eval" :code (pr-str form)})]
      (when-let [ex (some :ex responses)]
        (throw (ex-info "Daemon eval threw" {:exception ex :responses responses})))
      (some :value responses))))

(deftest daemon-runtime-can-hot-add-config-dir-local-root
  (let [config-dir (temp-dir "skein-runtime-deps-config")
        suffix (str "s" (.replace (str (java.util.UUID/randomUUID)) "-" ""))]
    (try
      (let [world (daemon-config/world (.getCanonicalPath config-dir))
            rt (runtime/start! nil {:world world})
            {:keys [root lib ns marker]} (write-hot-lib! config-dir suffix)]
        (try
          (is (= ":missing"
                 (daemon-value rt `(try (require '~ns)
                                        :present
                                        (catch java.io.FileNotFoundException _#
                                          :missing)))))
          (is (= ":daemon-hot-added"
                 (daemon-value rt `(do (require 'clojure.repl.deps)
                                       (clojure.repl.deps/add-libs {'~lib {:local/root ~root}})
                                       (require '~ns)
                                       ((requiring-resolve '~marker))))))
          (finally
            (runtime/stop! rt))))
      (finally
        (delete-recursive config-dir)))))
