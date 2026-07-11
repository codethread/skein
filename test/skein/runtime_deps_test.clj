(ns skein.runtime-deps-test
  "Tests for runtime dependency loading against a live weaver."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nrepl.core :as nrepl]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.runtime :as weaver-runtime]))

(defn test-world [config-dir]
  (weaver-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            (.toPath (io/file "/tmp"))
            prefix
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- keep-add-libs-root! [_file]
  ;; tools.deps keeps add-libs local roots in JVM-global basis state. Retaining
  ;; temp spool roots prevents later add-libs calls in this shard from failing
  ;; while canonicalizing an earlier, now-deleted local/root coordinate.
  nil)

(defn- write-hot-lib! [config-dir suffix]
  (let [root (io/file config-dir "spools" "runtime-spike")
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

(defn- write-maven-spool! [config-dir suffix]
  (let [root (io/file config-dir "spools" "maven-spike")
        ns-sym (symbol (str "maven-spike.hot-" suffix))
        src-dir (io/file root "src" "maven_spike")
        lib (symbol (str "maven-spike/lib-" suffix))]
    (.mkdirs src-dir)
    (spit (io/file src-dir (str "hot_" suffix ".clj"))
          (str "(ns " ns-sym ")\n"
               "(defn marker []\n"
               "  (binding [*use-context-classloader* true]\n"
               "    (ffirst ((requiring-resolve 'clojure.data.csv/read-csv) \"daemon-maven-added\"))))\n"))
    (spit (io/file root "deps.edn")
          (pr-str {:paths ["src"]
                   :deps {'org.clojure/data.csv {:mvn/version "1.1.0"}}}))
    (spit (io/file config-dir "spools.edn")
          (pr-str {:spools {lib {:local/root "spools/maven-spike"}}}))
    {:root (.getCanonicalPath root)
     :lib lib
     :ns ns-sym
     :marker (symbol (str ns-sym "/marker"))}))

(defn- daemon-value [rt form]
  (with-open [conn (nrepl/connect :host (get-in rt [:metadata :endpoint :host])
                                  :port (get-in rt [:metadata :endpoint :port]))]
    (let [responses (doall (nrepl/message (nrepl/client conn 90000)
                                          {:op "eval" :code (pr-str form)}))]
      (when-let [ex (some :ex responses)]
        (throw (ex-info "Daemon eval threw" {:exception ex :responses responses})))
      (if (some #(some #{"done"} (:status %)) responses)
        (some :value responses)
        (throw (ex-info "nREPL client drained without a done status (maven resolution/add-libs likely exceeded the client timeout)"
                        {:responses responses}))))))

(deftest daemon-runtime-can-hot-add-config-dir-local-root
  (let [config-dir (temp-dir "skein-runtime-deps-config")
        suffix (str "s" (str/replace (str (java.util.UUID/randomUUID)) "-" ""))]
    (try
      (let [world (test-world (.getCanonicalPath config-dir))
            rt (weaver-runtime/start! nil {:world world})
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
            (weaver-runtime/stop! rt))))
      (finally
        (keep-add-libs-root! config-dir)))))

(deftest approved-spool-sync-loads-maven-deps-before-activation
  (let [config-dir (temp-dir "skein-runtime-maven-spool-config")
        suffix (str "s" (str/replace (str (java.util.UUID/randomUUID)) "-" ""))]
    (try
      (let [world (test-world (.getCanonicalPath config-dir))
            rt (weaver-runtime/start! nil {:world world})
            {:keys [lib ns marker]} (write-maven-spool! config-dir suffix)]
        (try
          (is (= ":skipped"
                 (daemon-value rt `(do (require 'skein.api.current.alpha
                                                'skein.api.runtime.alpha)
                                       (:status (skein.api.runtime.alpha/use!
                                                 (skein.api.current.alpha/runtime)
                                                 :maven-spike
                                                 {:spools ['~lib]
                                                  :ns '~ns
                                                  :call '~marker}))))))
          (is (= ":loaded"
                 (daemon-value rt `(do (require 'skein.api.current.alpha
                                                'skein.api.runtime.alpha)
                                       (get-in (skein.api.runtime.alpha/sync!
                                                (skein.api.current.alpha/runtime))
                                               [:spools '~lib :status])))))
          (is (= ":loaded"
                 (daemon-value rt `(do (require 'skein.api.current.alpha
                                                'skein.api.runtime.alpha)
                                       (:status (skein.api.runtime.alpha/use!
                                                 (skein.api.current.alpha/runtime)
                                                 :maven-spike
                                                 {:spools ['~lib]
                                                  :ns '~ns
                                                  :call '~marker}))))))
          (is (= "\"daemon-maven-added\""
                 (daemon-value rt `(do (require 'skein.api.current.alpha
                                                'skein.api.runtime.alpha)
                                       (get-in (skein.api.runtime.alpha/use
                                                (skein.api.current.alpha/runtime)
                                                :maven-spike)
                                               [:call :return])))))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (keep-add-libs-root! config-dir)))))
