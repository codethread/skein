(ns skein.spools-test
  "Tests for runtime spool workspace surfaces: approved spools.edn and
  spools.local.edn reading, sync!, layered use!, reload!, event helper routing,
  daemon init, and the ephemeral helper spool."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skein.core.client :as client]
            [skein.core.db-test :as db-test]
            [skein.api.events.alpha :as events]
            [skein.api.graph.alpha :as graph]
            [skein.spools.ephemeral :as ephemeral]
            [skein.spools.test-support :refer [temp-config-dir test-world with-runtime]]
            [skein.repl :as repl]
            [skein.api.runtime.alpha :as runtime-alpha]
            [skein.api.weaver.alpha :as api]
            [skein.core.weaver.runtime :as runtime]
            [skein.test.alpha :as t]))

(defn- delete-recursive [file]
  (doseq [child (reverse (file-seq file))]
    (.delete child)))

;; Namespace-level on purpose: event handlers are registered by symbol and
;; resolved to top-level vars, so capture state cannot be a per-test local.
;; Reset by the :each fixture below; the runner never splits a namespace.
(def reload-deliveries (atom []))

(use-fixtures :each (fn [f] (reset! reload-deliveries []) (f)))

(defn fresh-reload-handler [event]
  (swap! reload-deliveries conj (:event/id event)))

(defn- ns-requires [resource-path]
  (->> (slurp (io/resource resource-path))
       java.io.StringReader.
       java.io.PushbackReader.
       read
       (filter #(and (seq? %) (= :require (first %))))
       first
       rest
       (map first)
       set))

(defn- write-spools! [config-dir content]
  (spit (io/file config-dir "spools.edn") content))

(defn- write-local-spools! [config-dir content]
  (spit (io/file config-dir "spools.local.edn") content))

(defn- shared-source [config-dir]
  {:kind :shared
   :file (.getPath (io/file (.getCanonicalPath config-dir) "spools.edn"))})

(defn- local-source [config-dir]
  {:kind :local
   :file (.getPath (io/file (.getCanonicalPath config-dir) "spools.local.edn"))})

(defn- with-cache-base [cache-dir f]
  (let [original @#'api/cache-base]
    (try
      (alter-var-root #'api/cache-base (constantly (fn [] cache-dir)))
      (f)
      (finally
        (alter-var-root #'api/cache-base (constantly original))))))

(defn- write-local-lib! [config-dir lib-name ns-sym]
  (let [root (io/file config-dir "spools" lib-name)
        ns-path (-> (str ns-sym)
                    (.replace \- \_)
                    (.replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file (str "(ns " ns-sym ")\n(defn marker [] :synced-lib-loaded)\n(defn event-handler [_] :handled)\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    root))

(defn- write-spool-manifest! [root manifest]
  (spit (io/file root "spool.edn") (pr-str manifest)))

(defn- write-spool-ns! [root ns-sym content]
  (let [ns-path (-> (str ns-sym)
                    (.replace \- \_)
                    (.replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file content)
    src-file))

(defn- run-git! [dir & args]
  (let [process (-> (ProcessBuilder. (into-array String (cons "git" args)))
                    (.directory dir)
                    (.start))
        stderr (future (slurp (.getErrorStream process)))
        stdout (future (slurp (.getInputStream process)))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "git fixture command failed"
                      {:args args :exit exit :stdout @stdout :stderr @stderr})))
    @stdout))

(defn- write-git-lib! [root ns-sym]
  (let [ns-path (-> (str ns-sym)
                    (.replace \- \_)
                    (.replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file (str "(ns " ns-sym ")\n(defn marker [] :git-spool-loaded)\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")))

(defn- file-url [file]
  (str "file://" (.getCanonicalPath file)))

(defn- init-git-repo! [root ns-sym & {:keys [tag annotated-tag]}]
  (.mkdirs root)
  (run-git! root "init")
  (run-git! root "config" "user.email" "skein-test@example.invalid")
  (run-git! root "config" "user.name" "Skein Test")
  (write-git-lib! root ns-sym)
  (run-git! root "add" ".")
  (run-git! root "commit" "-m" "fixture")
  (let [sha (str/trim (run-git! root "rev-parse" "HEAD"))]
    (when tag
      (run-git! root "tag" tag))
    (when annotated-tag
      (run-git! root "tag" "-a" annotated-tag "-m" annotated-tag))
    sha))

(deftest approved-returns-empty-spools-when-files-are-missing
  (with-runtime
    (fn [rt _]
      (is (= {:spools {}} (runtime-alpha/approved rt))))))

(deftest approved-fails-loudly-when-local-spools-edn-is-malformed
  (with-runtime
    (fn [rt config-dir]
      (write-local-spools! config-dir "{:spools")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"spools.local.edn is malformed or unreadable"
                            (runtime-alpha/approved rt))))))

(deftest approved-rejects-legacy-libs-files-and-symlinks
  (with-runtime
    (fn [rt config-dir]
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file config-dir "libs.local.edn"))
       (.toPath (io/file config-dir "missing"))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"rename libs.edn/libs.local.edn to spools.edn/spools.local.edn"
                            (runtime-alpha/approved rt))))))

(deftest approved-includes-shared-only-and-local-only-spools
  (with-runtime
    (fn [rt config-dir]
      (let [shared-root (io/file config-dir "spools" "shared")
            local-root (io/file config-dir "spools" "local")]
        (write-spools! config-dir (pr-str {:spools {'demo/shared {:local/root "spools/shared"}}}))
        (write-local-spools! config-dir (pr-str {:spools {'demo/local {:local/root "spools/local"}}}))
        (is (= {:spools {'demo/shared {:kind :local
                                     :local/root "spools/shared"
                                     :root (.getCanonicalPath shared-root)
                                     :source (shared-source config-dir)}
                       'demo/local {:kind :local
                                    :local/root "spools/local"
                                    :root (.getCanonicalPath local-root)
                                    :source (local-source config-dir)}}}
               (runtime-alpha/approved rt)))))))

(deftest approved-local-spools-override-shared-by-coordinate
  (with-runtime
    (fn [rt config-dir]
      (let [shared-root (io/file config-dir "spools" "shared")
            local-root (io/file config-dir "spools" "local")]
        (write-spools! config-dir (pr-str {:spools {'demo/override {:local/root "spools/shared"}}}))
        (write-local-spools! config-dir (pr-str {:spools {'demo/override {:local/root "spools/local"}}}))
        (is (= {:kind :local
                :local/root "spools/local"
                :root (.getCanonicalPath local-root)
                :source (local-source config-dir)}
               (get-in (runtime-alpha/approved rt) [:spools 'demo/override])))
        (is (not= (.getCanonicalPath shared-root)
                  (get-in (runtime-alpha/approved rt) [:spools 'demo/override :root])))))))

(deftest approved-fails-when-spools-edn-is-not-a-file
  (with-runtime
    (fn [rt config-dir]
      (.mkdirs (io/file config-dir "spools.edn"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"malformed or unreadable"
                            (runtime-alpha/approved rt))))))

(deftest approved-normalizes-relative-and-absolute-roots
  (with-runtime
    (fn [rt config-dir]
      (let [relative-root (io/file config-dir "spools" "demo")
            absolute-root (io/file config-dir "external" "abs")]
        (.mkdirs relative-root)
        (.mkdirs absolute-root)
        (write-spools! config-dir
                     (pr-str {:spools {'demo/relative {:local/root "spools/demo"}
                                     'demo/absolute {:local/root (.getAbsolutePath absolute-root)}}}))
        (is (= {:spools {'demo/absolute {:kind :local
                                       :local/root (.getAbsolutePath absolute-root)
                                       :root (.getCanonicalPath absolute-root)
                                       :source (shared-source config-dir)}
                       'demo/relative {:kind :local
                                       :local/root "spools/demo"
                                       :root (.getCanonicalPath relative-root)
                                       :source (shared-source config-dir)}}}
               (runtime-alpha/approved rt)))))))

(deftest approved-expands-home-relative-roots
  (with-runtime
    (fn [rt config-dir]
      (let [home (System/getProperty "user.home")
            home-root (io/file home "dev" "projects" "my-lib")]
        (write-spools! config-dir (pr-str {:spools {'demo/home {:local/root "~/dev/projects/my-lib"}}}))
        (is (= {:spools {'demo/home {:kind :local
                                   :local/root "~/dev/projects/my-lib"
                                   :root (.getCanonicalPath home-root)
                                   :source (shared-source config-dir)}}}
               (runtime-alpha/approved rt)))))))

(deftest approved-canonicalizes-symlink-roots
  (with-runtime
    (fn [rt config-dir]
      (let [target (io/file config-dir "spools" "target")
            link (io/file config-dir "spools" "link")]
        (.mkdirs target)
        (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath target)
                                                (make-array java.nio.file.attribute.FileAttribute 0))
        (write-spools! config-dir (pr-str {:spools {'demo/link {:local/root "spools/link"}}}))
        (is (= {:spools {'demo/link {:kind :local
                                   :local/root "spools/link"
                                   :root (.getCanonicalPath target)
                                   :source (shared-source config-dir)}}}
               (runtime-alpha/approved rt)))))))

(deftest approved-does-not-reject-missing-local-roots
  (with-runtime
    (fn [rt config-dir]
      (let [missing (io/file config-dir "spools" "missing")]
        (write-spools! config-dir (pr-str {:spools {'demo/missing {:local/root "spools/missing"}}}))
        (is (= {:spools {'demo/missing {:kind :local
                                      :local/root "spools/missing"
                                      :root (.getCanonicalPath missing)
                                      :source (shared-source config-dir)}}}
               (runtime-alpha/approved rt)))))))

(deftest approved-normalizes-git-spools-with-cache-base-and-deps-root
  (with-runtime
    (fn [rt config-dir]
      (let [cache-dir (io/file config-dir "cache")
            sha "0123456789abcdef0123456789abcdef01234567"]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/git {:git/url "file:///tmp/repo"
                                                                 :git/sha sha
                                                                 :git/tag "v1"
                                                                 :deps/root "nested/spool"}}}))
            (is (= {:spools {'demo/git {:kind :git
                                      :git/url "file:///tmp/repo"
                                      :git/sha sha
                                      :git/tag "v1"
                                      :deps/root "nested/spool"
                                      :root (.getPath (io/file cache-dir "skein" "spools" sha "nested/spool"))
                                      :source (shared-source config-dir)}}}
                   (runtime-alpha/approved rt)))))))))

(deftest approved-rejects-malformed-git-spools
  (with-runtime
    (fn [rt config-dir]
      (doseq [[label entry pattern data-keys]
              [["absolute deps root" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :deps/root "/abs"} #":deps/root" [:deps/root]]
               ["parent deps root" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :deps/root "a/../b"} #":deps/root" [:deps/root]]
               ["deps root on local" {:local/root "spools/demo" :deps/root "src"} #"exactly one coordinate kind" [:entry]]
               ["short sha" {:git/url "u" :git/sha "012345"} #":git/sha" [:git/sha]]
               ["uppercase sha" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef0123456A"} #":git/sha" [:git/sha]]
               ["non-hex sha" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef0123456g"} #":git/sha" [:git/sha]]
               ["blank url" {:git/url " " :git/sha "0123456789abcdef0123456789abcdef01234567"} #":git/url" [:git/url]]
               ["unknown key" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :extra true} #"unknown keys" [:keys]]
               ["mixed local and git" {:local/root "spools/demo" :git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567"} #"exactly one coordinate kind" [:entry]]]]
        (testing label
          (write-spools! config-dir (pr-str {:spools {'demo/lib entry}}))
          (try
            (runtime-alpha/approved rt)
            (is false "expected approved spool validation to fail")
            (catch clojure.lang.ExceptionInfo e
              (is (re-find pattern (ex-message e)))
              (doseq [k data-keys]
                (is (contains? (ex-data e) k))))))))))

(deftest ephemeral-spool-composes-public-helper-surfaces
  (is (= '#{skein.api.current.alpha skein.api.graph.alpha skein.api.weaver.alpha}
         (ns-requires "skein/spools/ephemeral.clj")))
  (with-runtime
    (fn [rt _]
      (let [parent (repl/strand! "Parent")
            child (ephemeral/ephemeral! (:id parent) "Scratch" {:owner "agent"})]
        (is (= "true" (get-in child [:attributes :ephemeral])))
        (is (= [[(:id parent) (:id child) "parent-of"]]
               (mapv (juxt :from_strand_id :to_strand_id :edge_type)
                     (:edges (graph/subgraph rt [(:id parent)])))))
        (is (= [(:id child)] (ephemeral/ephemeral-ids)))
        (is (= {:burned [(:id child)] :count 1}
               (select-keys (ephemeral/burn-ephemeral!) [:burned :count])))
        (is (= [] (ephemeral/ephemeral-ids)))))))

(deftest event-helpers-register-list-replace-unregister-directly
  (with-runtime
    (fn [rt _]
      (is (= {:key :capture
              :types #{:strand/added}
              :fn 'skein.weaver-test/capture-event
              :metadata {:purpose :test}}
             (events/register! rt :capture #{:strand/added} 'skein.weaver-test/capture-event {:purpose :test})))
      (is (= [:capture] (mapv :key (events/handlers rt))))
      (is (= {:key :capture
              :types #{:strand/updated}
              :fn 'skein.weaver-test/capture-event
              :metadata {}}
             (events/register! rt :capture #{:strand/updated} 'skein.weaver-test/capture-event)))
      (is (= #{:strand/updated} (:types (first (events/handlers rt)))))
      (is (= {:unregistered :capture} (events/unregister! rt :capture)))
      (is (= [] (events/handlers rt)))
      (is (= [] (events/recent-failures rt))))))

(deftest connected-client-can-register-weaver-only-spool-handler
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.event_handler_" suffix))
            lib (symbol (str "demo/event-handler-lib-" suffix))]
        (write-local-lib! config-dir "event-handler" ns-sym)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/event-handler"}}}))
        (is (= :loaded (get-in (client/call-world (get-in rt [:metadata :config-dir]) {} :sync-approved-spools)
                               [:spools lib :status])))
        (is (= :loaded (:status (client/call-world (get-in rt [:metadata :config-dir]) {} :use! :events {:ns ns-sym :spools [lib]}))))
        (let [entry (client/call-world (get-in rt [:metadata :config-dir]) {}
                                       :register-event-handler! :lib #{:strand/added}
                                       (symbol (str ns-sym) "event-handler") {})]
          (is (= :lib (:key entry)))
          (is (= (symbol (str ns-sym) "event-handler") (:fn entry))))))))

(deftest approved-fails-loudly-on-structural-errors
  (with-runtime
    (fn [rt config-dir]
      (doseq [[label content pattern]
              [["malformed EDN" "{:spools" #"malformed or unreadable"]
               ["unknown top-level key" (pr-str {:spools {} :extra true}) #"unknown top-level keys"]
               ["missing :spools" (pr-str {}) #"requires :spools map"]
               ["non-map :spools" (pr-str {:spools []}) #"requires :spools map"]
               ["non-symbol coordinate" (pr-str {:spools {"demo/lib" {:local/root "spools/demo"}}}) #"coordinate must be a symbol"]
               ["non-map entry" (pr-str {:spools {'demo/lib "spools/demo"}}) #"entry must be a map"]
               ["unknown per-lib key" (pr-str {:spools {'demo/lib {:local/root "spools/demo" :extra true}}}) #"unknown keys"]
               ["missing root" (pr-str {:spools {'demo/lib {}}}) #"requires non-blank string"]
               ["non-string root" (pr-str {:spools {'demo/lib {:local/root 1}}}) #"requires non-blank string"]
               ["blank root" (pr-str {:spools {'demo/lib {:local/root "  "}}}) #"requires non-blank string"]]]
        (testing label
          (write-spools! config-dir content)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo pattern (runtime-alpha/approved rt))))))))

(deftest approved-applies-structural-validation-to-local-spools
  (with-runtime
    (fn [rt config-dir]
      (write-local-spools! config-dir (pr-str {:spools {'demo/lib {:local/root " "}}}))
      (try
        (runtime-alpha/approved rt)
        (is false "expected spools.local.edn structural validation to fail")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"requires non-blank string" (ex-message e)))
          (is (= (local-source config-dir) (select-keys (ex-data e) [:kind :file]))))))))

(deftest approved-fails-loudly-on-broken-local-spools-symlink
  (with-runtime
    (fn [rt config-dir]
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file config-dir "spools.local.edn"))
       (.toPath (io/file config-dir "missing-target.edn"))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"spools.local.edn is malformed or unreadable"
                            (runtime-alpha/approved rt))))))

(deftest sync-loads-approved-local-root-and-exposes-state
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.synced-" suffix))
            lib (symbol (str "demo/lib-" suffix))
            root (write-local-lib! config-dir "demo" ns-sym)]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/demo"}}}))
        (is (= {:spools {lib {:lib lib
                            :kind :local
                            :local/root "spools/demo"
                            :root (.getCanonicalPath root)
                            :source (shared-source config-dir)
                            :status :loaded}}}
               (runtime-alpha/sync! rt)))
        (is (= {:spools {lib {:lib lib
                            :kind :local
                            :local/root "spools/demo"
                            :root (.getCanonicalPath root)
                            :source (shared-source config-dir)
                            :status :loaded}}}
               (runtime-alpha/syncs rt)))
        (is (= {:spools {lib {:lib lib
                            :kind :local
                            :local/root "spools/demo"
                            :root (.getCanonicalPath root)
                            :source (shared-source config-dir)
                            :status :already-available}}}
               (runtime-alpha/sync! rt)))))))

(deftest sync-parses-optional-spool-manifest
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.manifest_" suffix))
            lib (symbol (str "demo/manifest-" suffix))
            root (write-local-lib! config-dir "manifest" ns-sym)
            manifest {:coordinate lib
                      :provides #{ns-sym}
                      :needs {'demo/needed nil}
                      :docs {ns-sym "docs"}}]
        (write-spool-manifest! root manifest)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/manifest"}}}))
        (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
          (is (= :loaded (:status result)))
          (is (= {:coordinate lib
                  :provides [ns-sym]
                  :needs {'demo/needed nil}
                  :docs {ns-sym "docs"}}
                 (:manifest result)))
          (is (= [{:lib 'demo/needed :reason :not-approved}]
                 (:unmet-needs result))))))))

(deftest sync-omits-manifest-key-when-spool-manifest-is-absent
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.no_manifest_" suffix))
            lib (symbol (str "demo/no-manifest-" suffix))]
        (write-local-lib! config-dir "no-manifest" ns-sym)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/no-manifest"}}}))
        (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
          (is (= :loaded (:status result)))
          (is (not (contains? result :manifest))))))))

(deftest sync-fails-malformed-spool-manifests-loudly-without-loading-root
  (with-runtime
    (fn [rt config-dir]
      (doseq [[label manifest]
              [["non-map" []]
               ["unknown key" {:extra true}]
               ["bad coordinate" {:coordinate "demo/lib"}]
               ["bad provides shape" {:provides 'demo.core}]
               ["bad provides entry" {:provides ["demo.core"]}]
               ["bad needs shape" {:needs []}]
               ["bad need coordinate" {:needs {"demo/lib" nil}}]
               ["bad need value" {:needs {'demo/lib true}}]
               ["unknown suggestion key" {:needs {'demo/lib {:suggest {:git/url "u"} :extra true}}}]
               ["blank suggestion url" {:needs {'demo/lib {:suggest {:git/url " "}}}}]
               ["bad docs shape" {:docs []}]
               ["bad docs key" {:docs {"demo.core" "docs"}}]
               ["bad docs value" {:docs {'demo.core 1}}]]]
        (testing label
          (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
                ns-sym (symbol (str "demo.bad_manifest_" suffix))
                lib (symbol (str "demo/bad-manifest-" suffix))
                root (write-local-lib! config-dir (str "bad-manifest-" suffix) ns-sym)]
            (write-spool-manifest! root manifest)
            (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/bad-manifest-" suffix)}}}))
            (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
              (is (= :failed (:status result)))
              (is (= :manifest-invalid (:reason result)))
              (is (not (contains? result :manifest)))
              (when (= label "non-map")
                (is (= [] (:invalid-manifest result))))
              (is (thrown? Exception (requiring-resolve (symbol (str ns-sym "/marker"))))))))))))

(deftest sync-fails-coordinate-mismatched-spool-manifest
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.coordinate_mismatch_" suffix))
            lib (symbol (str "demo/coordinate-mismatch-" suffix))
            root (write-local-lib! config-dir "coordinate-mismatch" ns-sym)]
        (write-spool-manifest! root {:coordinate 'demo/other})
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/coordinate-mismatch"}}}))
        (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
          (is (= :failed (:status result)))
          (is (= :coordinate-mismatch (:reason result)))
          (is (= lib (:expected result)))
          (is (= 'demo/other (:actual result))))))))

(deftest sync-reports-satisfied-and-unmet-manifest-needs
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            base-ns (symbol (str "demo.need_base_" suffix))
            needing-ns (symbol (str "demo.need_consumer_" suffix))
            failed-ns (symbol (str "demo.need_failed_" suffix))
            base-lib (symbol (str "demo/need-base-" suffix))
            needing-lib (symbol (str "demo/need-consumer-" suffix))
            failed-lib (symbol (str "demo/need-failed-" suffix))
            base-root (write-local-lib! config-dir (str "need-base-" suffix) base-ns)
            needing-root (write-local-lib! config-dir (str "need-consumer-" suffix) needing-ns)
            failed-root (write-local-lib! config-dir (str "need-failed-" suffix) failed-ns)]
        (write-spool-manifest! base-root {:coordinate base-lib})
        (write-spool-manifest! needing-root {:needs {base-lib nil
                                                     'demo/unapproved {:suggest {:git/url "file:///tmp/suggested"}}
                                                     failed-lib nil}})
        (spit (io/file failed-root "deps.edn") "{:paths [}")
        (write-spools! config-dir (pr-str {:spools {base-lib {:local/root (str "spools/need-base-" suffix)}
                                                needing-lib {:local/root (str "spools/need-consumer-" suffix)}
                                                failed-lib {:local/root (str "spools/need-failed-" suffix)}}}))
        (let [results (:spools (runtime-alpha/sync! rt))]
          (is (= :loaded (get-in results [base-lib :status])))
          (is (= :loaded (get-in results [needing-lib :status])))
          (is (= :failed (get-in results [failed-lib :status])))
          (is (= #{{:lib 'demo/unapproved
                    :reason :not-approved
                    :suggest {:git/url "file:///tmp/suggested"}}
                   {:lib failed-lib
                    :reason :sync-failed}}
                 (set (get-in results [needing-lib :unmet-needs])))))))))

(deftest use-gates-on-manifest-unmet-needs-and-required-throws
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.unmet_gate_" suffix))
            lib (symbol (str "demo/unmet-gate-" suffix))
            root (write-local-lib! config-dir "unmet-gate" ns-sym)]
        (write-spool-manifest! root {:needs {'demo/missing nil}})
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/unmet-gate"}}}))
        (runtime-alpha/sync! rt)
        (let [result (runtime-alpha/use! rt :unmet/gate {:ns ns-sym :spools [lib]})]
          (is (= :skipped (:status result)))
          (is (= :unmet-needs (:reason result)))
          (is (= [{:lib 'demo/missing :reason :not-approved}] (:unmet-needs result))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Required module use was skipped"
                              (runtime-alpha/use! rt :unmet/required {:ns ns-sym :spools [lib] :required? true})))))))

(deftest use-verifies-manifest-provides-before-activation
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            good-ns (symbol (str "demo.provides_good_" suffix))
            bad-ns (symbol (str "demo.provides_missing_" suffix))
            good-lib (symbol (str "demo/provides-good-" suffix))
            bad-lib (symbol (str "demo/provides-bad-" suffix))
            good-root (write-local-lib! config-dir "provides-good" good-ns)
            bad-root (write-local-lib! config-dir "provides-bad" good-ns)]
        (write-spool-manifest! good-root {:provides [good-ns]})
        (write-spool-manifest! bad-root {:provides [bad-ns]})
        (write-spools! config-dir (pr-str {:spools {good-lib {:local/root "spools/provides-good"}
                                                bad-lib {:local/root "spools/provides-bad"}}}))
        (runtime-alpha/sync! rt)
        (is (= :loaded (:status (runtime-alpha/use! rt :provides/good {:ns good-ns :spools [good-lib]}))))
        (let [result (runtime-alpha/use! rt :provides/bad {:ns good-ns :spools [bad-lib]})]
          (is (= :skipped (:status result)))
          (is (= :provides-unloadable (:reason result)))
          (is (= bad-ns (get-in result [:provides-unloadable :ns])))
          (is (string? (get-in result [:provides-unloadable :message]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Required module use was skipped"
                              (runtime-alpha/use! rt :provides/required {:ns good-ns :spools [bad-lib] :required? true})))))))

;; Dogfoods skein.test.alpha for author-visible weaver-world behavior
;; (LAT-PLAN-001.PH6). Uses an explicit :root because synced local roots must
;; outlive the world: deleting an add-libs'ed root leaves stale basis entries
;; for later syncs in this JVM.
(deftest daemon-init-runs-with-spool-classloader-after-sync
  (let [root (temp-config-dir)
        suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
        ns-sym (symbol (str "demo.init-synced-" suffix))
        lib (symbol (str "demo/init-lib-" suffix))
        ns-path (-> (str ns-sym)
                    (.replace \- \_)
                    (.replace \. java.io.File/separatorChar))
        result-file (io/file root "init-result.edn")]
    (try
      (t/with-weaver-world [ctx {:root (.getPath root)
                                 :spools-edn {:spools {lib {:local/root "spools/init-demo"}}}
                                 :files {(str "spools/init-demo/src/" ns-path ".clj")
                                         (str "(ns " ns-sym ")\n(defn marker [] :synced-lib-loaded)\n")
                                         "spools/init-demo/deps.edn" "{:paths [\"src\"]}\n"}
                                 :init (str "(require '[skein.api.current.alpha :as current] '[skein.api.runtime.alpha :as runtime-alpha])\n"
                                            "(spit " (pr-str (str result-file))
                                            " (pr-str (runtime-alpha/sync! (current/runtime))))\n")}]
        (is (= :loaded (get-in (read-string (slurp result-file)) [:spools lib :status])))
        (is (= :loaded (get-in (t/repl! ctx "(require '[skein.api.current.alpha :as current] '[skein.api.runtime.alpha :as runtime-alpha]) (runtime-alpha/syncs (current/runtime))")
                               [:spools lib :status])))
        (testing "repl! forms run under the spool classloader, so synced namespaces are requirable"
          (is (= :synced-lib-loaded
                 (t/repl! ctx (str "(require '" ns-sym ") (" ns-sym "/marker)"))))))
      (finally
        (when-not (.exists result-file)
          (delete-recursive root))))))

(deftest sync-clears-stale-state-before-structural-failure
  (with-runtime
    (fn [rt config-dir]
      (let [missing (io/file config-dir "spools" "missing")]
        (write-spools! config-dir (pr-str {:spools {'demo/missing {:local/root "spools/missing"}}}))
        (is (= {:spools {'demo/missing {:lib 'demo/missing
                                      :kind :local
                                      :local/root "spools/missing"
                                      :root (.getCanonicalPath missing)
                                      :source (shared-source config-dir)
                                      :status :failed
                                      :reason :missing-root}}}
               (runtime-alpha/sync! rt)))
        (write-spools! config-dir "{:spools")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed or unreadable" (runtime-alpha/sync! rt)))
        (is (= {:spools {}} (runtime-alpha/syncs rt)))))))

(deftest sync-records-runtime-add-failures-as-failed-outcomes
  (with-runtime
    (fn [rt config-dir]
      (let [root (io/file config-dir "spools" "bad-deps")]
        (.mkdirs root)
        (spit (io/file root "deps.edn") "{:paths [}")
        (write-spools! config-dir (pr-str {:spools {'demo/bad-deps {:local/root "spools/bad-deps"}}}))
        (let [result (get-in (runtime-alpha/sync! rt) [:spools 'demo/bad-deps])]
          (is (= {:lib 'demo/bad-deps
                  :kind :local
                  :local/root "spools/bad-deps"
                  :root (.getCanonicalPath root)
                  :source (shared-source config-dir)
                  :status :failed
                  :reason :runtime-add-failed}
                 (select-keys result [:lib :kind :local/root :root :source :status :reason])))
          (is (string? (:message result)))
          (is (string? (:class result))))))))

(deftest sync-records-missing-and-unreadable-roots-as-failed-outcomes
  (with-runtime
    (fn [rt config-dir]
      (let [not-dir (io/file config-dir "spools" "not-dir")]
        (.mkdirs (.getParentFile not-dir))
        (spit not-dir "not a directory")
        (write-spools! config-dir (pr-str {:spools {'demo/missing {:local/root "spools/missing"}
                                                'demo/not-dir {:local/root "spools/not-dir"}}}))
        (is (= {:spools {'demo/missing {:lib 'demo/missing
                                      :kind :local
                                      :local/root "spools/missing"
                                      :root (.getCanonicalPath (io/file config-dir "spools" "missing"))
                                      :source (shared-source config-dir)
                                      :status :failed
                                      :reason :missing-root}
                       'demo/not-dir {:lib 'demo/not-dir
                                      :kind :local
                                      :local/root "spools/not-dir"
                                      :root (.getCanonicalPath not-dir)
                                      :source (shared-source config-dir)
                                      :status :failed
                                      :reason :unreadable-root}}}
               (runtime-alpha/sync! rt)))))))

(deftest sync-git-missing-root-outcome-is-kind-shaped
  (with-runtime
    (fn [rt config-dir]
      (let [cache-dir (io/file config-dir "cache")
            sha "0123456789abcdef0123456789abcdef01234567"]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/git {:git/url "file:///tmp/repo"
                                                                 :git/sha sha
                                                                 :git/tag "v1"
                                                                 :deps/root "spool"}}}))
            (let [result (get-in (runtime-alpha/sync! rt) [:spools 'demo/git])]
              (is (= {:lib 'demo/git
                      :kind :git
                      :git/url "file:///tmp/repo"
                      :git/sha sha
                      :git/tag "v1"
                      :deps/root "spool"
                      :root (.getPath (io/file cache-dir "skein" "spools" sha "spool"))
                      :source (shared-source config-dir)
                      :status :failed
                      :reason :fetch-failed}
                     (select-keys result [:lib :kind :git/url :git/sha :git/tag :deps/root :root :source :status :reason])))
              (is (integer? (:exit result)))
              (is (string? (:stderr result)))
              (is (not (contains? result :local/root))))))))))

(deftest sync-fetches-git-spool-and-uses-cache-hit-without-origin
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_fetch_" (.replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha (init-git-repo! repo ns-sym)
            url (file-url repo)
            lib 'demo/git-fetch]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url url :git/sha sha}}}))
            (is (= :loaded (get-in (runtime-alpha/sync! rt) [:spools lib :status])))
            (is (= :fetched (get-in (runtime-alpha/syncs rt) [:spools lib :fetch])))
            (is (false? (.exists (io/file cache-dir "skein" "spools" sha ".git"))))
            (delete-recursive repo)
            (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
              (is (= :already-available (:status result)))
              (is (= :cached (:fetch result))))))))))

(deftest sync-git-concurrent-cache-publish-race-loads-winner-tree
  (with-runtime
    (fn [rt config-dir]
      (let [cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_publish_race_" (.replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha "0123456789abcdef0123456789abcdef01234567"
            url "file:///tmp/race-winner-supplies-cache"
            lib 'demo/git-publish-race]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url url :git/sha sha}}}))
            (let [cache-root (io/file cache-dir "skein" "spools" sha)
                  original-checkout @#'api/checkout-git-spool!]
              (try
                (alter-var-root
                 #'api/checkout-git-spool!
                 (constantly
                  (fn [_entry tmp]
                    ;; The pre-check in materialize-git-spool! has already
                    ;; missed. Simulate a concurrent winner publishing a valid,
                    ;; non-empty tree immediately before this materializer's
                    ;; atomic move, so the loser must treat the move failure as
                    ;; a cache hit rather than :fetch-failed.
                    (write-git-lib! cache-root ns-sym)
                    (spit (io/file tmp "loser.txt") "loser"))))
                (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
                  (is (= :loaded (:status result)))
                  (is (= :cached (:fetch result)))
                  (is (= :loaded (:status (runtime-alpha/use! rt :race/winner {:ns ns-sym :spools [lib]})))))
                (finally
                  (alter-var-root #'api/checkout-git-spool! (constantly original-checkout)))))))))))

(deftest sync-rejects-deps-edn-paths-escaping-the-spool-root
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.escape_" suffix))
            lib (symbol (str "demo/escape-" suffix))
            root (write-local-lib! config-dir (str "escape-" suffix) ns-sym)
            outside (io/file config-dir "outside-src")]
        (.mkdirs outside)
        (spit (io/file root "deps.edn") (pr-str {:paths ["src" "../../outside-src"]}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/escape-" suffix)}}}))
        (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
          (is (= :failed (:status result)))
          (is (= :runtime-add-failed (:reason result)))
          (is (re-find #"stay inside the spool root" (:message result))))))))

(deftest sync-accepts-deps-edn-path-naming-the-spool-root-itself
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.rootpath_" suffix))
            lib (symbol (str "demo/rootpath-" suffix))
            root (write-local-lib! config-dir (str "rootpath-" suffix) ns-sym)]
        (spit (io/file root "deps.edn") (pr-str {:paths ["." "src"]}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/rootpath-" suffix)}}}))
        (is (#{:loaded :already-available}
             (get-in (runtime-alpha/sync! rt) [:spools lib :status])))))))

(deftest sync-shared-local-spool-rejects-transitive-deps
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.shared_local_deps_" suffix))
            lib (symbol (str "demo/shared-local-deps-" suffix))
            root (write-local-lib! config-dir (str "shared-local-deps-" suffix) ns-sym)]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.example/lib {:mvn/version "1.0.0"}}}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/shared-local-deps-" suffix)}}}))
        (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
          (is (= :failed (:status result)))
          (is (= :runtime-add-failed (:reason result)))
          (is (re-find #"must not declare :deps" (:message result)))
          (is (re-find #"spools\.edn" (:message result)))
          (is (re-find #"cannot consent to transitive tools\.deps dependencies" (:message result)))
          (is (re-find #"spools\.local\.edn" (:message result))))))))

(deftest sync-local-spools-local-root-still-allows-transitive-deps
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.local_deps_" suffix))
            lib (symbol (str "demo/local-deps-" suffix))
            root (write-local-lib! config-dir (str "local-deps-" suffix) ns-sym)]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.clojure/data.json {:mvn/version "2.5.1"}}}))
        (write-local-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/local-deps-" suffix)}}}))
        (is (#{:loaded :already-available}
             (get-in (runtime-alpha/sync! rt) [:spools lib :status])))))))

(deftest sync-shared-local-spool-without-transitive-deps-still-loads
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.shared_local_no_deps_" suffix))
            lib (symbol (str "demo/shared-local-no-deps-" suffix))]
        (write-local-lib! config-dir (str "shared-local-no-deps-" suffix) ns-sym)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/shared-local-no-deps-" suffix)}}}))
        (is (#{:loaded :already-available}
             (get-in (runtime-alpha/sync! rt) [:spools lib :status])))))))

(deftest sync-git-spool-rejects-transitive-deps
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_deps_" (.replace (str (java.util.UUID/randomUUID)) "-" "")))
            _ (init-git-repo! repo ns-sym)
            _ (spit (io/file repo "deps.edn") (pr-str {:paths ["src"] :deps {'org.example/lib {:mvn/version "1.0.0"}}}))
            _ (run-git! repo "add" ".")
            _ (run-git! repo "commit" "-m" "deps")
            sha (str/trim (run-git! repo "rev-parse" "HEAD"))
            lib 'demo/git-deps]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url (file-url repo) :git/sha sha}}}))
            (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
              (is (= :failed (:status result)))
              (is (= :runtime-add-failed (:reason result)))
              (is (re-find #"must not declare :deps" (:message result)))
              (is (= :fetched (:fetch result))))))))))

(deftest delete-tree-removes-symlinks-without-following-them
  (let [base (.toFile (java.nio.file.Files/createTempDirectory "skein-delete-tree" (make-array java.nio.file.attribute.FileAttribute 0)))
        outside (io/file base "outside")
        outside-file (io/file outside "keep.txt")
        tree (io/file base "tree")]
    (try
      (.mkdirs outside)
      (spit outside-file "keep")
      (.mkdirs tree)
      (spit (io/file tree "inner.txt") "inner")
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file tree "link-out"))
       (.toPath outside)
       (make-array java.nio.file.attribute.FileAttribute 0))
      (#'api/delete-tree! tree)
      (is (false? (.exists tree)))
      (is (true? (.exists outside-file)))
      (finally
        (delete-recursive base)))))

(deftest sync-git-manifest-failure-keeps-fetch-outcome
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_bad_manifest_" (.replace (str (java.util.UUID/randomUUID)) "-" "")))
            _ (init-git-repo! repo ns-sym)
            _ (write-spool-manifest! repo [])
            _ (run-git! repo "add" ".")
            _ (run-git! repo "commit" "-m" "manifest")
            sha (str/trim (run-git! repo "rev-parse" "HEAD"))
            lib 'demo/git-bad-manifest]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url (file-url repo) :git/sha sha}}}))
            (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
              (is (= :failed (:status result)))
              (is (= :manifest-invalid (:reason result)))
              (is (= :fetched (:fetch result)))
              (is (not (contains? result :manifest))))))))))

(deftest sync-git-unknown-sha-is-fetch-failed
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            sha (init-git-repo! repo 'demo.unknown_sha)
            unknown (apply str (repeat 40 \a))]
        (is (not= sha unknown))
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/unknown {:git/url (file-url repo) :git/sha unknown}}}))
            (let [result (get-in (runtime-alpha/sync! rt) [:spools 'demo/unknown])]
              (is (= :failed (:status result)))
              (is (= :fetch-failed (:reason result)))
              (is (integer? (:exit result)))
              (is (string? (:stderr result))))))))))

(deftest sync-git-tag-verification-loads-and-rejects-mismatch
  (with-runtime
    (fn [rt config-dir]
      (let [matching-repo (io/file config-dir "matching-repo")
            mismatching-repo (io/file config-dir "mismatching-repo")
            cache-dir (io/file config-dir "cache")
            matching-sha (init-git-repo! matching-repo 'demo.matching_tag :annotated-tag "v1")
            mismatching-sha (init-git-repo! mismatching-repo 'demo.mismatching_tag :tag "v1")]
        (spit (io/file mismatching-repo "extra.txt") "new")
        (run-git! mismatching-repo "add" ".")
        (run-git! mismatching-repo "commit" "-m" "second")
        (let [new-sha (str/trim (run-git! mismatching-repo "rev-parse" "HEAD"))]
          (with-cache-base
            cache-dir
            (fn []
              (write-spools! config-dir (pr-str {:spools {'demo/matching {:git/url (file-url matching-repo)
                                                                        :git/sha matching-sha
                                                                        :git/tag "v1"}
                                                          'demo/mismatching {:git/url (file-url mismatching-repo)
                                                                           :git/sha new-sha
                                                                           :git/tag "v1"}}}))
              (let [results (:spools (runtime-alpha/sync! rt))]
                (is (= :loaded (get-in results ['demo/matching :status])))
                (is (= :fetched (get-in results ['demo/matching :fetch])))
                (is (= :failed (get-in results ['demo/mismatching :status])))
                (is (= :tag-mismatch (get-in results ['demo/mismatching :reason])))
                (is (= new-sha (get-in results ['demo/mismatching :expected])))
                (is (= mismatching-sha (get-in results ['demo/mismatching :actual])))
                (is (false? (.exists (io/file cache-dir "skein" "spools" new-sha))))))))))))

(deftest sync-git-deps-root-selects-monorepo-subdir
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "mono")
            spool-root (io/file repo "nested" "spool")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_mono_" (.replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha (do
                  (.mkdirs repo)
                  (run-git! repo "init")
                  (run-git! repo "config" "user.email" "skein-test@example.invalid")
                  (run-git! repo "config" "user.name" "Skein Test")
                  (write-git-lib! spool-root ns-sym)
                  (spit (io/file repo "README.md") "root only")
                  (run-git! repo "add" ".")
                  (run-git! repo "commit" "-m" "monorepo")
                  (str/trim (run-git! repo "rev-parse" "HEAD")))]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/mono {:git/url (file-url repo)
                                                                 :git/sha sha
                                                                 :deps/root "nested/spool"}}}))
            (let [result (get-in (runtime-alpha/sync! rt) [:spools 'demo/mono])]
              (is (= :loaded (:status result)))
              (is (= :fetched (:fetch result)))
              (is (= (.getPath (io/file cache-dir "skein" "spools" sha "nested/spool")) (:root result))))))))))

(deftest sync-git-deps-root-missing-after-fetch-keeps-fetch-outcome
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "missing-root-repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_missing_root_" (.replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha (init-git-repo! repo ns-sym)
            lib 'demo/git-missing-root]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url (file-url repo)
                                                          :git/sha sha
                                                          :deps/root "missing/spool"}}}))
            (let [result (get-in (runtime-alpha/sync! rt) [:spools lib])]
              (is (= :failed (:status result)))
              (is (= :missing-root (:reason result)))
              (is (= :fetched (:fetch result)))
              (is (= (.getPath (io/file cache-dir "skein" "spools" sha "missing/spool"))
                     (:root result))))))))))

(defn- write-module-file! [config-dir relative-path content]
  (let [file (io/file config-dir relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

(deftest reload-loads-selected-config-init-file
  (with-runtime
    (fn [rt config-dir]
      (let [result-file (io/file config-dir "reload-result.edn")]
        (spit (io/file config-dir "init.clj")
              (str "(spit " (pr-str (str result-file)) " (pr-str :reloaded))\n"
                   ":reload-return\n"))
        (let [result (runtime-alpha/reload! rt)]
          (is (= :loaded (:status result)))
          (is (= [{:name "init.clj"
                   :file (.getCanonicalPath (io/file config-dir "init.clj"))
                   :return :reload-return}]
                 (:files result)))
          (is (= [:reload-return] (:returns result)))
          (is (= :reloaded (read-string (slurp result-file)))))))))

(deftest reload-skips-missing-startup-files
  (with-runtime
    (fn [rt _]
      (is (= {:status :loaded
              :files []
              :returns []}
             (runtime-alpha/reload! rt))))))

(deftest reload-loads-generated-runtime-template-from-live-repl-namespace
  (with-runtime
    (fn [rt config-dir]
      (spit (io/file config-dir "init.clj")
            "(require '[skein.api.current.alpha :as current] '[skein.api.runtime.alpha :as runtime-alpha])\n\n(runtime-alpha/sync! (current/runtime))\n")
      (binding [*ns* (the-ns 'skein.repl)]
        (is (= :loaded (:status (runtime-alpha/reload! rt))))))))

(deftest reload-clears-prior-runtime-config-state-before-loading
  (with-runtime
    (fn [rt config-dir]
      (write-module-file! config-dir "modules/stale.clj" "(ns demo.stale)\n(defn handler [_] :ok)\n")
      (is (= :loaded (:status (runtime-alpha/use! rt :stale {:file "modules/stale.clj"}))))
      (api/register-query rt 'stale [:= [:attr :owner] "stale"])
      (api/register-view! rt 'stale-view 'demo.stale/view)
      (reset! reload-deliveries [])
      (api/register-event-handler! rt :stale #{:strand/added} 'demo.stale/handler {})
      (api/register-event-handler! rt :fails #{:strand/added} 'skein.weaver-test/failing-event {})
      (api/enqueue-event! rt {:event/type :strand/added
                              :event/id "before-reload"
                              :event/at "2026-06-27T00:00:00Z"
                              :event/source :test})
      (Thread/sleep 250)
      (is (seq (api/recent-event-failures rt)))
      (spit (io/file config-dir "init.clj")
            "(require '[skein.api.current.alpha :as current] '[skein.api.weaver.alpha :as api])\n(let [rt (current/runtime)]\n  (api/register-query rt 'fresh [:= [:attr :owner] \"fresh\"])\n  (api/register-event-handler! rt :fresh #{:strand/added} 'skein.spools-test/fresh-reload-handler {}))\n")
      (is (= :loaded (:status (runtime-alpha/reload! rt))))
      (is (nil? (runtime-alpha/use rt :stale)))
      (is (nil? (get (api/queries rt) "stale")))
      (is (= [:= [:attr :owner] "fresh"] (get (api/queries rt) "fresh")))
      (is (= [] (api/views rt)))
      (is (= [:fresh] (mapv :key (api/event-handlers rt))))
      (is (= [] (api/recent-event-failures rt)))
      (api/enqueue-event! rt {:event/type :strand/added
                              :event/id "after-reload"
                              :event/at "2026-06-27T00:00:00Z"
                              :event/source :test})
      (Thread/sleep 250)
      (is (= ["after-reload"] @reload-deliveries)))))

(deftest use-loads-namespace-from-synced-root-and-records-state
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.use-ns-" suffix))
            lib (symbol (str "demo/use-ns-lib-" suffix))
            root (write-local-lib! config-dir "use-ns" ns-sym)
            expected-file (io/file root "src" "demo" (str "use_ns_" suffix ".clj"))]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/use-ns"}}}))
        (runtime-alpha/sync! rt)
        (let [result (runtime-alpha/use! rt :demo/ns {:ns ns-sym :spools [lib]})]
          (is (= :loaded (:status result)))
          (is (= ns-sym (get-in result [:loaded :ns])))
          (is (= (.getCanonicalPath expected-file) (get-in result [:loaded :file])))
          (is (= result (runtime-alpha/use rt :demo/ns)))
          (is (= :synced-lib-loaded ((requiring-resolve (symbol (str ns-sym "/marker")))))))))))

(deftest use-searches-multiple-synced-roots-for-namespace-source
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            first-ns (symbol (str "demo.first" suffix))
            second-ns (symbol (str "demo.second-lib-" suffix))
            first-lib (symbol (str "demo/first-lib-" suffix))
            second-lib (symbol (str "demo/second-lib-" suffix))
            root (write-local-lib! config-dir "second" second-ns)]
        (write-local-lib! config-dir "first" first-ns)
        (write-spools! config-dir (pr-str {:spools {first-lib {:local/root "spools/first"}
                                               second-lib {:local/root "spools/second"}}}))
        (runtime-alpha/sync! rt)
        (let [result (runtime-alpha/use! rt :demo/second {:ns second-ns :spools #{first-lib second-lib}})]
          (is (= :loaded (:status result)))
          (is (= (.getCanonicalPath (io/file root "src" "demo" (str "second_lib_" suffix ".clj")))
                 (get-in result [:loaded :file]))))))))

(deftest use-reports-missing-synced-namespace-source
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            existing-ns (symbol (str "demo.existing" suffix))
            missing-ns (symbol (str "demo.missing-lib-" suffix))
            lib (symbol (str "demo/missing-ns-lib-" suffix))
            root (write-local-lib! config-dir "missing-ns" existing-ns)]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/missing-ns"}}}))
        (runtime-alpha/sync! rt)
        (let [result (runtime-alpha/use! rt :demo/missing-ns {:ns missing-ns :spools [lib]})]
          (is (= :failed (:status result)))
          (is (= "Could not locate namespace source in synced spool roots" (get-in result [:error :message])))
          (is (= {:ns missing-ns
                  :relative-path (str "demo" java.io.File/separator "missing_lib_" suffix ".clj")
                  :searched-roots [(.getCanonicalPath (io/file root "src"))]}
                 (get-in result [:error :data]))))))))

(deftest use-loads-selected-config-relative-file-and-records-call-return
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.filemod" suffix))
            file-rel "modules/file_mod.clj"]
        (write-module-file! config-dir file-rel
                            (str "(ns " ns-sym ")\n(defn install! [] {:installed true})\n"))
        (let [result (runtime-alpha/use! rt :demo/file {:file file-rel
                                            :call (symbol (str ns-sym "/install!"))})]
          (is (= :loaded (:status result)))
          (is (= (.getCanonicalPath (io/file config-dir file-rel)) (get-in result [:loaded :file])))
          (is (= {:fn (symbol (str ns-sym "/install!"))
                  :return {:installed true}}
                 (:call result))))))))

(deftest use-lib-gates-observe-local-overrides
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.override_gated_" suffix))
            lib (symbol (str "demo/override-gated-" suffix))
            root (write-local-lib! config-dir "override-gated-local" ns-sym)]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/missing-shared"}}}))
        (write-local-spools! config-dir (pr-str {:spools {lib {:local/root "spools/override-gated-local"}}}))
        (is (= :loaded (get-in (runtime-alpha/sync! rt) [:spools lib :status])))
        (is (= (local-source config-dir) (get-in (runtime-alpha/syncs rt) [:spools lib :source])))
        (let [result (runtime-alpha/use! rt :override/gated {:ns ns-sym :spools [lib]})]
          (is (= :loaded (:status result)))
          (is (= (.getCanonicalPath (io/file root "src" "demo" (str "override_gated_" suffix ".clj")))
                 (get-in result [:loaded :file]))))))))

(deftest use-skips-on-lib-gates-before-loading
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.gated" suffix))
            approved-spool (symbol (str "demo/gated-lib-" suffix))
            failed-lib (symbol (str "demo/failed-lib-" suffix))]
        (write-local-lib! config-dir "gated" ns-sym)
        (write-spools! config-dir (pr-str {:spools {approved-spool {:local/root "spools/gated"}
                                               failed-lib {:local/root "spools/missing"}}}))
        (is (= :not-approved (:reason (runtime-alpha/use! rt :not-approved {:ns ns-sym :spools ['demo/not-approved]}))))
        (is (= :not-synced (:reason (runtime-alpha/use! rt :not-synced {:ns ns-sym :spools [approved-spool]}))))
        (runtime-alpha/sync! rt)
        (let [result (runtime-alpha/use! rt :sync-failed {:ns ns-sym :spools [failed-lib]})]
          (is (= :skipped (:status result)))
          (is (= :sync-failed (:reason result)))
          (is (= :failed (get-in result [:sync :status]))))))))

(deftest use-skips-on-missing-after
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.after" suffix))]
        (write-module-file! config-dir "modules/after.clj" (str "(ns " ns-sym ")\n"))
        (is (= :loaded (:status (runtime-alpha/use! rt :base {:file "modules/after.clj"}))))
        (is (= :missing-after (:reason (runtime-alpha/use! rt :child {:ns ns-sym :after [:base :missing]}))))))))

(deftest use-records-failures-and-required-rethrows
  (with-runtime
    (fn [rt config-dir]
      (write-module-file! config-dir "modules/bad.clj" "(throw (ex-info \"boom\" {:bad true}))\n")
      (is (= :failed (:status (runtime-alpha/use! rt :bad {:file "modules/bad.clj"}))))
      (is (thrown? Exception
                   (runtime-alpha/use! rt :required-bad {:file "modules/bad.clj" :required? true})))
      (is (= :failed (:status (runtime-alpha/use rt :required-bad))))
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.callfail" suffix))]
        (write-module-file! config-dir "modules/call_fail.clj"
                            (str "(ns " ns-sym ")\n(defn install! [] (throw (ex-info \"call boom\" {})))\n"))
        (let [result (runtime-alpha/use! rt :call-fail {:file "modules/call_fail.clj"
                                            :call (symbol (str ns-sym "/install!"))})]
          (is (= :failed (:status result))))))))

(deftest use-fails-loudly-on-malformed-options
  (with-runtime
    (fn [rt _]
      (doseq [[label key opts pattern]
              [["bad key" "bad" {:ns 'demo.core} #"key must be"]
               ["non-map opts" :bad [] #"opts must be a map"]
               ["unknown key" :bad {:ns 'demo.core :extra true} #"unknown keys"]
               ["neither target" :bad {} #"exactly one"]
               ["both targets" :bad {:ns 'demo.core :file "x.clj"} #"exactly one"]
               ["bad ns" :bad {:ns "demo.core"} #":ns must be a symbol"]
               ["bad file" :bad {:file ""} #":file must be"]
               ["absolute file" :bad {:file "/tmp/mod.clj"} #"relative to selected config-dir"]
               ["escaping file" :bad {:file "../mod.clj"} #"stay within selected config-dir"]
               ["bad spools" :bad {:ns 'demo.core :spools ['demo/lib 1]} #":spools entries"]
               ["bad after" :bad {:ns 'demo.core :after [1]} #":after entries"]
               ["bad call" :bad {:ns 'demo.core :call 'install!} #":call must"]
               ["bad required" :bad {:ns 'demo.core :required? :yes} #":required\? must"]]]
        (testing label
          (is (thrown-with-msg? clojure.lang.ExceptionInfo pattern (runtime-alpha/use! rt key opts))))))))

(deftest use-duplicate-keys-replace-previous-state
  (with-runtime
    (fn [rt config-dir]
      (write-module-file! config-dir "modules/dup1.clj" "(ns demo.dup1)\n")
      (write-module-file! config-dir "modules/dup2.clj" "(ns demo.dup2)\n")
      (is (= "modules/dup1.clj" (get-in (runtime-alpha/use! rt :dup {:file "modules/dup1.clj"}) [:opts :file])))
      (is (= "modules/dup2.clj" (get-in (runtime-alpha/use! rt :dup {:file "modules/dup2.clj"}) [:opts :file])))
      (is (= #{:dup} (set (keys (runtime-alpha/uses rt)))))
      (is (= "modules/dup2.clj" (get-in (runtime-alpha/use rt :dup) [:opts :file]))))))

(deftest use-gates-before-load-and-call-side-effects
  (with-runtime
    (fn [rt config-dir]
      (let [side-effect-file (io/file config-dir "gated-side-effect.edn")]
        (write-module-file! config-dir "modules/gated_effect.clj"
                            (str "(ns demo.gated-effect)\n"
                                 "(spit " (pr-str (str side-effect-file)) " :loaded)\n"
                                 "(defn install! [] (spit " (pr-str (str side-effect-file)) " :called))\n"))
        (is (= :not-approved (:reason (runtime-alpha/use! rt :gate/not-approved
                                                 {:file "modules/gated_effect.clj"
                                                  :spools ['demo/not-approved]
                                                  :call 'demo.gated-effect/install!}))))
        (is (false? (.exists side-effect-file)))
        (is (= :missing-after (:reason (runtime-alpha/use! rt :gate/missing-after
                                                {:file "modules/gated_effect.clj"
                                                 :after [:missing]
                                                 :call 'demo.gated-effect/install!}))))
        (is (false? (.exists side-effect-file)))))))

;; Dogfoods skein.test.alpha for author-visible connected-client behavior
;; (LAT-PLAN-001.PH6). Explicit :root so the module's install! side-effect file
;; has a path known before the world starts.
(deftest connected-client-use-executes-in-daemon-runtime
  (let [root (temp-config-dir)
        result-file (io/file root "connected-result.edn")]
    (try
      (t/with-weaver-world [ctx {:root (.getPath root)
                                 :files {"modules/connected.clj"
                                         (str "(ns demo.connected)\n"
                                              "(defn install! [] (spit " (pr-str (str result-file)) " (pr-str :daemon-called)) :ok)\n")}}]
        (let [result (client/call-world (:config-dir ctx) {} :use! :connected {:file "modules/connected.clj"
                                                                               :call 'demo.connected/install!})]
          (is (= :loaded (:status result)))
          (is (= :ok (get-in result [:call :return])))
          (is (= :daemon-called (read-string (slurp result-file))))
          (is (= :loaded (:status (client/call-world (:config-dir ctx) {} :use :connected))))))
      (finally
        (delete-recursive root)))))
