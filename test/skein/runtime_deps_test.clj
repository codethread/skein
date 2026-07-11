(ns skein.runtime-deps-test
  "Tests for runtime dependency loading against a live weaver."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nrepl.core :as nrepl]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.weaver.spool-sync :as spool-sync]))

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

;; The retained-root detector is tested purely over SYNTHETIC :libs maps whose
;; :local/root points at a temp dir this test owns and deletes — never via add-libs
;; and never against the live basis — so deleting the root cannot poison the
;; shard's JVM-global tools.deps basis (PLAN-srr-001.PH1).
(defn- deleted-temp-root! [prefix]
  (let [dir (temp-dir prefix)
        root (.getCanonicalPath dir)]
    (.delete dir)
    root))

(deftest retained-root-detector-flags-allowlist-orphan
  (let [lib 'orphan/deleted
        root (deleted-temp-root! "skein-retained-orphan")
        orphans (@#'spool-sync/retained-root-orphans {lib {:local/root root}} #{})]
    (is (= [{:lib lib :local/root root}] orphans))
    (let [err (@#'spool-sync/retained-root-orphans-error orphans)
          data (ex-data err)]
      (is (str/includes? (ex-message err) (str lib)))
      (is (str/includes? (ex-message err) root))
      (is (= [{:lib lib :local/root root}] (:missing-roots data)))
      (is (contains? (:remedy data) :stub-dir))
      (is (contains? (:remedy data) :restart))
      (is (some? (:retained-universe-source data))))))

(deftest retained-root-detector-ignores-healthy-root
  (let [dir (temp-dir "skein-retained-healthy")
        root (.getCanonicalPath dir)]
    (try
      (is (= [] (@#'spool-sync/retained-root-orphans {'healthy/present {:local/root root}} #{})))
      (finally
        (.delete dir)))))

(deftest retained-root-detector-reports-all-missing-roots
  (let [r1 (deleted-temp-root! "skein-retained-m1")
        r2 (deleted-temp-root! "skein-retained-m2")
        orphans (@#'spool-sync/retained-root-orphans {'a/one {:local/root r1}
                                                      'b/two {:local/root r2}}
                                                     #{})]
    (is (= 2 (count orphans)))
    (is (= #{{:lib 'a/one :local/root r1} {:lib 'b/two :local/root r2}}
           (set orphans)))))

(deftest retained-root-detector-excludes-still-approved-root
  (let [lib 'approved/deleted
        root (deleted-temp-root! "skein-retained-approved")]
    (is (= [] (@#'spool-sync/retained-root-orphans {lib {:local/root root}} #{lib})))))

(deftest stub-dir-remedy-clears-retained-root-orphan
  ;; The :stub-dir remedy (DELTA-srr-dr-001.CC2/.D3) is a pure filesystem-existence
  ;; flip over the TASK-srr-001 detector: with the synthetic root deleted the orphan
  ;; is reported; recreating a bare directory at that path clears it. The synthetic
  ;; root is never added to the real basis (PLAN-srr-001.PH2).
  (let [lib 'orphan/stub-round-trip
        root (deleted-temp-root! "skein-retained-stub")
        libs {lib {:local/root root}}]
    (is (= [{:lib lib :local/root root}] (@#'spool-sync/retained-root-orphans libs #{})))
    (let [stub (io/file root)]
      (.mkdirs stub)
      (try
        (is (= [] (@#'spool-sync/retained-root-orphans libs #{})))
        (finally
          (.delete stub))))))

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
