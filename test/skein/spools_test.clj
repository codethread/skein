(ns skein.spools-test
  "Tests for runtime spool workspace surfaces: approved spools.edn and
  spools.local.edn reading, sync!, layered use!, reload!, event helper routing,
  daemon init, and the ephemeral helper spool."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skein.core.client :as client]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.spool-sync :as spool-sync]
            [skein.api.events.alpha :as events]
            [skein.api.graph.alpha :as graph]
            [skein.spools.ephemeral :as ephemeral]
            [skein.spools.test-support :refer [temp-config-dir with-runtime]]
            [skein.repl :as repl]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.views.alpha :as views]
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
  (let [original @#'access/cache-base]
    (try
      (alter-var-root #'access/cache-base (constantly (fn [] cache-dir)))
      (f)
      (finally
        (alter-var-root #'access/cache-base (constantly original))))))

(defn- with-resolver
  "Run `f` with the private Maven resolver seam replaced by `resolver`.

  `resolver` receives the merged `{lib coord}` universe and returns a tools.deps
  `:added` lib-map, so sync tests exercise the two-phase loader without real
  network resolution."
  [resolver f]
  (let [original @#'spool-sync/resolve-spool-maven-libs]
    (try
      (alter-var-root #'spool-sync/resolve-spool-maven-libs (constantly resolver))
      (f)
      (finally
        (alter-var-root #'spool-sync/resolve-spool-maven-libs (constantly original))))))

(defn- write-local-lib! [config-dir lib-name ns-sym]
  (let [root (io/file config-dir "spools" lib-name)
        ns-path (-> (str ns-sym)
                    (str/replace \- \_)
                    (str/replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file (str "(ns " ns-sym ")\n(defn marker [] :synced-lib-loaded)\n(defn event-handler [_] :handled)\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    root))

(defn- write-spool-manifest!
  "Write a legacy spool.edn file so regression tests can prove sync! ignores it.

  Spool manifests are not part of the spool contract; documentation-only
  metadata belongs in README prose per devflow/specs/repl-api.md SPEC-003.P5."
  [root manifest]
  (spit (io/file root "spool.edn") (pr-str manifest)))

(defn- write-spool-ns! [root ns-sym content]
  (let [ns-path (-> (str ns-sym)
                    (str/replace \- \_)
                    (str/replace \. java.io.File/separatorChar))
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
                    (str/replace \- \_)
                    (str/replace \. java.io.File/separatorChar))
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
      (is (= {:spools {}} (runtime/approved rt))))))

(deftest approved-fails-loudly-when-local-spools-edn-is-malformed
  (with-runtime
    (fn [rt config-dir]
      (write-local-spools! config-dir "{:spools")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"spools.local.edn is malformed or unreadable"
                            (runtime/approved rt))))))

(deftest approved-rejects-legacy-libs-files-and-symlinks
  (with-runtime
    (fn [rt config-dir]
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file config-dir "libs.local.edn"))
       (.toPath (io/file config-dir "missing"))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"rename libs.edn/libs.local.edn to spools.edn/spools.local.edn"
                            (runtime/approved rt))))))

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
               (runtime/approved rt)))))))

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
               (get-in (runtime/approved rt) [:spools 'demo/override])))
        (is (not= (.getCanonicalPath shared-root)
                  (get-in (runtime/approved rt) [:spools 'demo/override :root])))))))

(deftest approved-fails-when-spools-edn-is-not-a-file
  (with-runtime
    (fn [rt config-dir]
      (.mkdirs (io/file config-dir "spools.edn"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"malformed or unreadable"
                            (runtime/approved rt))))))

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
               (runtime/approved rt)))))))

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
               (runtime/approved rt)))))))

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
               (runtime/approved rt)))))))

(deftest approved-does-not-reject-missing-local-roots
  (with-runtime
    (fn [rt config-dir]
      (let [missing (io/file config-dir "spools" "missing")]
        (write-spools! config-dir (pr-str {:spools {'demo/missing {:local/root "spools/missing"}}}))
        (is (= {:spools {'demo/missing {:kind :local
                                        :local/root "spools/missing"
                                        :root (.getCanonicalPath missing)
                                        :source (shared-source config-dir)}}}
               (runtime/approved rt)))))))

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
                   (runtime/approved rt)))))))))

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
            (runtime/approved rt)
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
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.event_handler_" suffix))
            lib (symbol (str "demo/event-handler-lib-" suffix))]
        (write-local-lib! config-dir "event-handler" ns-sym)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/event-handler"}}}))
        (is (= :loaded (get-in (client/call-world (get-in rt [:metadata :config-dir]) {:timeout-ms 30000} :sync-approved-spools)
                               [:spools lib :status])))
        (is (= :loaded (:status (client/call-world (get-in rt [:metadata :config-dir]) {:timeout-ms 30000} :use! :events {:ns ns-sym :spools [lib]}))))
        (let [entry (client/call-world (get-in rt [:metadata :config-dir]) {:timeout-ms 30000}
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
          (is (thrown-with-msg? clojure.lang.ExceptionInfo pattern (runtime/approved rt))))))))

(deftest approved-applies-structural-validation-to-local-spools
  (with-runtime
    (fn [rt config-dir]
      (write-local-spools! config-dir (pr-str {:spools {'demo/lib {:local/root " "}}}))
      (try
        (runtime/approved rt)
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
                            (runtime/approved rt))))))

(deftest sync-loads-approved-local-root-and-exposes-state
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
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
               (runtime/sync! rt)))
        (is (= {:spools {lib {:lib lib
                              :kind :local
                              :local/root "spools/demo"
                              :root (.getCanonicalPath root)
                              :source (shared-source config-dir)
                              :status :loaded}}}
               (runtime/syncs rt)))
        (is (= {:spools {lib {:lib lib
                              :kind :local
                              :local/root "spools/demo"
                              :root (.getCanonicalPath root)
                              :source (shared-source config-dir)
                              :status :already-available}}}
               (runtime/sync! rt)))))))

;; Regression guard: a present legacy spool.edn, even with retired manifest
;; keys, must have no effect on sync results. The absent :manifest and
;; :unmet-needs assertions below prevent manifest machinery from creeping back.
(deftest sync-ignores-spool-manifest-files
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.ignored_manifest_" suffix))
            lib (symbol (str "demo/ignored-manifest-" suffix))
            root (write-local-lib! config-dir "ignored-manifest" ns-sym)]
        (write-spool-manifest! root {:coordinate 'demo/other
                                     :provides ['demo.missing]
                                     :needs {'demo/missing {:suggest {:git/url "file:///tmp/suggested"}}}
                                     :docs []})
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/ignored-manifest"}}}))
        (let [result (get-in (runtime/sync! rt) [:spools lib])]
          (is (= :loaded (:status result)))
          (is (not (contains? result :manifest)))
          (is (not (contains? result :unmet-needs)))
          (is (= :loaded (:status (runtime/use! rt :ignored/manifest {:ns ns-sym :spools [lib]})))))))))

(deftest use-is-driven-by-consumer-spools-after-load-and-call-options
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            base-ns (symbol (str "demo.base_" suffix))
            child-ns (symbol (str "demo.child_" suffix))
            base-lib (symbol (str "demo/base-" suffix))
            child-lib (symbol (str "demo/child-" suffix))
            called-file (io/file config-dir "called.edn")]
        (write-local-lib! config-dir "base" base-ns)
        (write-local-lib! config-dir "child" child-ns)
        (write-spool-ns! (io/file config-dir "spools" "child")
                         child-ns
                         (str "(ns " child-ns ")
"
                              "(defn install! [] (spit " (pr-str (str called-file)) " (pr-str :called)) :installed)
"))
        (write-spools! config-dir (pr-str {:spools {base-lib {:local/root "spools/base"}
                                                    child-lib {:local/root "spools/child"}}}))
        (runtime/sync! rt)
        (is (= :loaded (:status (runtime/use! rt :base {:ns base-ns :spools [base-lib]}))))
        (let [result (runtime/use! rt :child {:ns child-ns
                                              :spools [base-lib child-lib]
                                              :after [:base]
                                              :call (symbol (str child-ns "/install!"))})]
          (is (= :loaded (:status result)))
          (is (= :installed (get-in result [:call :return])))
          (is (= :called (read-string (slurp called-file)))))))))

(deftest use-required-spool-gates-throw-for-surviving-skip-reasons
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.required_gate_" suffix))
            approved-lib (symbol (str "demo/required-gate-" suffix))
            failed-lib (symbol (str "demo/required-failed-" suffix))]
        (write-local-lib! config-dir "required-gate" ns-sym)
        (write-spools! config-dir (pr-str {:spools {approved-lib {:local/root "spools/required-gate"}
                                                    failed-lib {:local/root "spools/missing"}}}))
        (doseq [[label key opts expected]
                [["not approved"
                  :required/not-approved
                  {:ns ns-sym :spools ['demo/not-approved] :required? true}
                  {:reason :not-approved :lib 'demo/not-approved}]
                 ["not synced"
                  :required/not-synced
                  {:ns ns-sym :spools [approved-lib] :required? true}
                  {:reason :not-synced :lib approved-lib}]]]
          (testing label
            (try
              (runtime/use! rt key opts)
              (is false "expected required spool gate to throw")
              (catch clojure.lang.ExceptionInfo e
                (is (= "Required module use was skipped" (ex-message e)))
                (is (= (merge {:key key
                               :opts opts
                               :status :skipped}
                              expected)
                       (select-keys (ex-data e) [:key :opts :status :reason :lib])))))))
        (runtime/sync! rt)
        (testing "sync failed"
          (try
            (runtime/use! rt :required/sync-failed {:ns ns-sym :spools [failed-lib] :required? true})
            (is false "expected required spool gate to throw")
            (catch clojure.lang.ExceptionInfo e
              (is (= "Required module use was skipped" (ex-message e)))
              (is (= {:key :required/sync-failed
                      :opts {:ns ns-sym :spools [failed-lib] :required? true}
                      :status :skipped
                      :reason :sync-failed
                      :lib failed-lib}
                     (select-keys (ex-data e) [:key :opts :status :reason :lib])))
              (is (= :failed (get-in (ex-data e) [:sync :status])))
              (is (= :missing-root (get-in (ex-data e) [:sync :reason]))))))))))

;; Dogfoods skein.test.alpha for author-visible weaver-world behavior
;; (LAT-PLAN-001.PH6). Uses an explicit :root because synced local roots must
;; outlive the world: deleting an add-libs'ed root leaves stale basis entries
;; for later syncs in this JVM.
(deftest daemon-init-runs-with-spool-classloader-after-sync
  (let [root (temp-config-dir)
        suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
        ns-sym (symbol (str "demo.init-synced-" suffix))
        lib (symbol (str "demo/init-lib-" suffix))
        ns-path (-> (str ns-sym)
                    (str/replace \- \_)
                    (str/replace \. java.io.File/separatorChar))
        result-file (io/file root "init-result.edn")]
    (try
      (t/with-weaver-world [ctx {:root (.getPath root)
                                 :spools-edn {:spools {lib {:local/root "spools/init-demo"}}}
                                 :files {(str "spools/init-demo/src/" ns-path ".clj")
                                         (str "(ns " ns-sym ")\n(defn marker [] :synced-lib-loaded)\n")
                                         "spools/init-demo/deps.edn" "{:paths [\"src\"]}\n"}
                                 :init (str "(require '[skein.api.current.alpha :as current] '[skein.api.runtime.alpha :as runtime])\n"
                                            "(spit " (pr-str (str result-file))
                                            " (pr-str (runtime/sync! (current/runtime))))\n")}]
        (is (= :loaded (get-in (read-string (slurp result-file)) [:spools lib :status])))
        (is (= :loaded (get-in (t/repl! ctx "(require '[skein.api.current.alpha :as current] '[skein.api.runtime.alpha :as runtime]) (runtime/syncs (current/runtime))")
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
               (runtime/sync! rt)))
        (write-spools! config-dir "{:spools")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed or unreadable" (runtime/sync! rt)))
        (is (= {:spools {}} (runtime/syncs rt)))))))

(deftest sync-records-runtime-add-failures-as-failed-outcomes
  (with-runtime
    (fn [rt config-dir]
      (let [root (io/file config-dir "spools" "bad-deps")]
        (.mkdirs root)
        (spit (io/file root "deps.edn") "{:paths [}")
        (write-spools! config-dir (pr-str {:spools {'demo/bad-deps {:local/root "spools/bad-deps"}}}))
        (let [result (get-in (runtime/sync! rt) [:spools 'demo/bad-deps])]
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
               (runtime/sync! rt)))))))

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
            (let [result (get-in (runtime/sync! rt) [:spools 'demo/git])]
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
            ns-sym (symbol (str "demo.git_fetch_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha (init-git-repo! repo ns-sym)
            url (file-url repo)
            lib 'demo/git-fetch]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url url :git/sha sha}}}))
            (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
            (is (= :fetched (get-in (runtime/syncs rt) [:spools lib :fetch])))
            (is (false? (.exists (io/file cache-dir "skein" "spools" sha ".git"))))
            (delete-recursive repo)
            (let [result (get-in (runtime/sync! rt) [:spools lib])]
              (is (= :already-available (:status result)))
              (is (= :cached (:fetch result))))))))))

(deftest sync-refetches-advertised-refs-when-direct-sha-fetch-misses
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_refetch_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            _ (init-git-repo! repo ns-sym)
            _ (spit (io/file repo "after-initial-cache.txt") "fresh")
            _ (run-git! repo "add" ".")
            _ (run-git! repo "commit" "-m" "fresh sha")
            sha (str/trim (run-git! repo "rev-parse" "HEAD"))
            url (file-url repo)
            lib 'demo/git-refetch
            original-run-git @#'spool-sync/run-git
            exact-sha-fetches (atom 0)]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url url :git/sha sha}}}))
            (try
              (alter-var-root
               #'spool-sync/run-git
               (constantly
                (fn [dir & args]
                  (if (and (= "fetch" (first args))
                           (= [url sha] (take-last 2 args))
                           (< (swap! exact-sha-fetches inc) 3))
                    {:exit 128 :stderr "simulated stale remote cache miss"}
                    (apply original-run-git dir args)))))
              (let [result (get-in (runtime/sync! rt) [:spools lib])]
                (is (= :loaded (:status result)))
                (is (= :fetched (:fetch result)))
                (is (= 2 @exact-sha-fetches))
                (is (.isFile (io/file cache-dir "skein" "spools" sha "after-initial-cache.txt"))))
              (finally
                (alter-var-root #'spool-sync/run-git (constantly original-run-git))))))))))

(deftest sync-git-unreachable-sha-names-cache-path-and-remote
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            sha (init-git-repo! repo 'demo.unreachable_sha)
            unknown (str/join (repeat 40 \a))]
        (is (not= sha unknown))
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/unreachable {:git/url (file-url repo) :git/sha unknown}}}))
            (let [result (get-in (runtime/sync! rt) [:spools 'demo/unreachable])]
              (is (= :failed (:status result)))
              (is (= :fetch-failed (:reason result)))
              (is (= (file-url repo) (:remote result)))
              (is (= (.getPath (io/file cache-dir "skein" "spools" unknown)) (:cache-path result)))
              (is (string? (:stderr result)))
              (is (not (contains? result :initial-stderr))))))))))

(deftest sync-git-concurrent-cache-publish-race-loads-winner-tree
  (with-runtime
    (fn [rt config-dir]
      (let [cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_publish_race_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha "0123456789abcdef0123456789abcdef01234567"
            url "file:///tmp/race-winner-supplies-cache"
            lib 'demo/git-publish-race]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url url :git/sha sha}}}))
            (let [cache-root (io/file cache-dir "skein" "spools" sha)
                  original-checkout @#'spool-sync/checkout-git-spool!]
              (try
                (alter-var-root
                 #'spool-sync/checkout-git-spool!
                 (constantly
                  (fn [_entry tmp _cache-root]
                    ;; The pre-check in materialize-git-spool! has already
                    ;; missed. Simulate a concurrent winner publishing a valid,
                    ;; non-empty tree immediately before this materializer's
                    ;; atomic move, so the loser must treat the move failure as
                    ;; a cache hit rather than :fetch-failed.
                    (write-git-lib! cache-root ns-sym)
                    (spit (io/file tmp "loser.txt") "loser"))))
                (let [result (get-in (runtime/sync! rt) [:spools lib])]
                  (is (= :loaded (:status result)))
                  (is (= :cached (:fetch result)))
                  (is (= :loaded (:status (runtime/use! rt :race/winner {:ns ns-sym :spools [lib]})))))
                (finally
                  (alter-var-root #'spool-sync/checkout-git-spool! (constantly original-checkout)))))))))))

(deftest sync-rejects-deps-edn-paths-escaping-the-spool-root
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.escape_" suffix))
            lib (symbol (str "demo/escape-" suffix))
            root (write-local-lib! config-dir (str "escape-" suffix) ns-sym)
            outside (io/file config-dir "outside-src")]
        (.mkdirs outside)
        (spit (io/file root "deps.edn") (pr-str {:paths ["src" "../../outside-src"]}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/escape-" suffix)}}}))
        (let [result (get-in (runtime/sync! rt) [:spools lib])]
          (is (= :failed (:status result)))
          (is (= :runtime-add-failed (:reason result)))
          (is (re-find #"stay inside the spool root" (:message result))))))))

(deftest sync-accepts-deps-edn-path-naming-the-spool-root-itself
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.rootpath_" suffix))
            lib (symbol (str "demo/rootpath-" suffix))
            root (write-local-lib! config-dir (str "rootpath-" suffix) ns-sym)]
        (spit (io/file root "deps.edn") (pr-str {:paths ["." "src"]}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/rootpath-" suffix)}}}))
        (is (#{:loaded :already-available}
             (get-in (runtime/sync! rt) [:spools lib :status])))))))

(deftest sync-approved-local-spool-loads-maven-deps-from-shared-config
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.shared_local_deps_" suffix))
            lib (symbol (str "demo/shared-local-deps-" suffix))
            root (write-local-lib! config-dir (str "shared-local-deps-" suffix) ns-sym)]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.clojure/data.json {:mvn/version "2.5.1"}}}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/shared-local-deps-" suffix)}}}))
        (is (#{:loaded :already-available}
             (get-in (runtime/sync! rt) [:spools lib :status])))))))

;; Under stateless resolution, :loaded/:already-available is driven by whether a
;; sync newly added the root's source dir or its directly-declared Maven jars to
;; the classloader (DELTA-Sor-001.CC1). A fresh root loads via its source dir; an
;; unchanged re-sync is already-available; adding a Maven dep whose jar is absent
;; flips the root back to :loaded even though its source dir is already present.
(deftest sync-status-reflects-newly-added-source-and-maven-jars
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.added_maven_dep_" suffix))
            lib (symbol (str "demo/added-maven-dep-" suffix))
            root (write-local-lib! config-dir (str "added-maven-dep-" suffix) ns-sym)
            fake-jar (str (io/file config-dir "fake" (str "data.csv-1.1.0-" suffix ".jar")))
            universes (atom [])
            resolver (fn [universe]
                       (swap! universes conj universe)
                       (if (contains? universe 'org.clojure/data.csv)
                         {'org.clojure/data.csv {:mvn/version "1.1.0" :paths [fake-jar]}}
                         {}))]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/added-maven-dep-" suffix)}}}))
        (with-resolver
          resolver
          (fn []
            (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
            (is (= :already-available (get-in (runtime/sync! rt) [:spools lib :status])))
            (spit (io/file root "deps.edn")
                  (pr-str {:paths ["src"]
                           :deps {'org.clojure/data.csv {:mvn/version "1.1.0"}}}))
            (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
            (is (= [{} {} {'org.clojure/data.csv {:mvn/version "1.1.0"}}]
                   @universes))))))))

(deftest sync-approved-local-overlay-rejects-source-bearing-deps
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.local_deps_" suffix))
            lib (symbol (str "demo/local-deps-" suffix))
            root (write-local-lib! config-dir (str "local-deps-" suffix) ns-sym)]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'demo/source {:local/root "../other"}}}))
        (write-local-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/local-deps-" suffix)}}}))
        (let [result (get-in (runtime/sync! rt) [:spools lib])]
          (is (= :failed (:status result)))
          (is (= :runtime-add-failed (:reason result)))
          (is (re-find #"source-bearing coordinates" (:message result)))
          (is (= {:keys [:local/root] :lib 'demo/source}
                 (select-keys (get result :data) [:keys :lib]))))))))

(deftest sync-approved-spool-rejects-mutable-versions-and-repo-redirection
  (with-runtime
    (fn [rt config-dir]
      (doseq [[label deps-edn pattern]
              [["snapshot" {:paths ["src"] :deps {'org.example/lib {:mvn/version "1.0.0-SNAPSHOT"}}} #"mutable Maven versions"]
               ["release" {:paths ["src"] :deps {'org.example/lib {:mvn/version "RELEASE"}}} #"mutable Maven versions"]
               ["latest" {:paths ["src"] :deps {'org.example/lib {:mvn/version "LATEST"}}} #"mutable Maven versions"]
               ["repos" {:paths ["src"] :mvn/repos {"central" {:url "file:/tmp/nope"}} :deps {}} #"top-level :mvn/repos"]
               ["local repo" {:paths ["src"] :mvn/local-repo "/tmp/nope" :deps {}} #"top-level :mvn/local-repo"]]]
        (testing label
          (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
                ns-sym (symbol (str "demo.bad_deps_" suffix))
                lib (symbol (str "demo/bad-deps-" suffix))
                root (write-local-lib! config-dir (str "bad-deps-" suffix) ns-sym)]
            (spit (io/file root "deps.edn") (pr-str deps-edn))
            (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/bad-deps-" suffix)}}}))
            (let [result (get-in (runtime/sync! rt) [:spools lib])]
              (is (= :failed (:status result)))
              (is (= :runtime-add-failed (:reason result)))
              (is (re-find pattern (:message result))))))))))

(deftest sync-approved-spool-allows-maven-refinement-keys-and-ignores-aliases
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.maven_refinement_" suffix))
            lib (symbol (str "demo/maven-refinement-" suffix))
            root (write-local-lib! config-dir (str "maven-refinement-" suffix) ns-sym)
            calls (atom [])]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.clojure/data.json {:mvn/version "2.5.1"
                                                      :exclusions ['org.clojure/clojure]
                                                      :classifier "sources"
                                                      :extension "jar"}}
                       :aliases {:dev {:extra-deps {'demo/source {:local/root "../ignored"}}}}
                       :ignored/top-level {:also :ignored}}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/maven-refinement-" suffix)}}}))
        (with-redefs [clojure.repl.deps/add-libs (fn [libs]
                                                   (swap! calls conj libs)
                                                   (set (keys libs)))]
          (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
          (is (= [{'org.clojure/data.json {:mvn/version "2.5.1"
                                           :exclusions ['org.clojure/clojure]
                                           :classifier "sources"
                                           :extension "jar"}}
                  {lib {:local/root (.getCanonicalPath root)}}]
                 @calls)))))))

(deftest sync-git-spool-rejects-source-bearing-deps
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_deps_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            _ (init-git-repo! repo ns-sym)
            _ (spit (io/file repo "deps.edn") (pr-str {:paths ["src"] :deps {'demo/source {:git/url "file:///tmp/repo" :git/sha (str/join (repeat 40 \a))}}}))
            _ (run-git! repo "add" ".")
            _ (run-git! repo "commit" "-m" "deps")
            sha (str/trim (run-git! repo "rev-parse" "HEAD"))
            lib 'demo/git-deps]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url (file-url repo) :git/sha sha}}}))
            (let [result (get-in (runtime/sync! rt) [:spools lib])]
              (is (= :failed (:status result)))
              (is (= :runtime-add-failed (:reason result)))
              (is (re-find #"source-bearing coordinates" (:message result)))
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
      (#'spool-sync/delete-tree! tree)
      (is (false? (.exists tree)))
      (is (true? (.exists outside-file)))
      (finally
        (delete-recursive base)))))

;; Regression guard: a fetched git spool may contain a legacy spool.edn, but
;; sync results are driven solely by the approved coordinate and fetch outcome.
;; The absent :manifest assertion prevents manifest machinery from creeping back.
(deftest sync-git-ignores-spool-manifest-and-keeps-fetch-outcome
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_ignored_manifest_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            _ (init-git-repo! repo ns-sym)
            _ (write-spool-manifest! repo [])
            _ (run-git! repo "add" ".")
            _ (run-git! repo "commit" "-m" "manifest")
            sha (str/trim (run-git! repo "rev-parse" "HEAD"))
            lib 'demo/git-ignored-manifest]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url (file-url repo) :git/sha sha}}}))
            (let [result (get-in (runtime/sync! rt) [:spools lib])]
              (is (= :loaded (:status result)))
              (is (= :fetched (:fetch result)))
              (is (not (contains? result :manifest))))))))))

(deftest sync-git-unknown-sha-is-fetch-failed
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            sha (init-git-repo! repo 'demo.unknown_sha)
            unknown (str/join (repeat 40 \a))]
        (is (not= sha unknown))
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/unknown {:git/url (file-url repo) :git/sha unknown}}}))
            (let [result (get-in (runtime/sync! rt) [:spools 'demo/unknown])]
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
              (let [results (:spools (runtime/sync! rt))]
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
            ns-sym (symbol (str "demo.git_mono_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
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
            (let [result (get-in (runtime/sync! rt) [:spools 'demo/mono])]
              (is (= :loaded (:status result)))
              (is (= :fetched (:fetch result)))
              (is (= (.getPath (io/file cache-dir "skein" "spools" sha "nested/spool")) (:root result))))))))))

(deftest sync-git-deps-root-missing-after-fetch-keeps-fetch-outcome
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "missing-root-repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_missing_root_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha (init-git-repo! repo ns-sym)
            lib 'demo/git-missing-root]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url (file-url repo)
                                                             :git/sha sha
                                                             :deps/root "missing/spool"}}}))
            (let [result (get-in (runtime/sync! rt) [:spools lib])]
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
        (let [result (runtime/reload! rt)]
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
             (runtime/reload! rt))))))

(deftest reload-loads-generated-runtime-template-from-live-repl-namespace
  (with-runtime
    (fn [rt config-dir]
      (spit (io/file config-dir "init.clj")
            "(require '[skein.api.current.alpha :as current] '[skein.api.runtime.alpha :as runtime])\n\n(runtime/sync! (current/runtime))\n")
      (binding [*ns* (the-ns 'skein.repl)]
        (is (= :loaded (:status (runtime/reload! rt))))))))

(deftest reload-clears-prior-runtime-config-state-before-loading
  (with-runtime
    (fn [rt config-dir]
      (write-module-file! config-dir "modules/stale.clj" "(ns demo.stale)\n(defn handler [_] :ok)\n")
      (is (= :loaded (:status (runtime/use! rt :stale {:file "modules/stale.clj"}))))
      (graph/register-query! rt 'stale [:= [:attr :owner] "stale"])
      (views/register-view! rt 'stale-view 'demo.stale/view)
      (reset! reload-deliveries [])
      (events/register! rt :stale #{:strand/added} 'demo.stale/handler {})
      (events/register! rt :fails #{:strand/added} 'skein.weaver-test/failing-event {})
      (events/enqueue! rt {:event/type :strand/added
                           :event/id "before-reload"
                           :event/at "2026-06-27T00:00:00Z"
                           :event/source :test})
      (Thread/sleep 250)
      (is (seq (events/recent-failures rt)))
      (spit (io/file config-dir "init.clj")
            (str "(require '[skein.api.current.alpha :as current] '[skein.api.graph.alpha :as graph] '[skein.api.events.alpha :as events])\n"
                 "(let [rt (current/runtime)]\n"
                 "  (graph/register-query! rt 'fresh [:= [:attr :owner] \"fresh\"])\n"
                 "  (events/register! rt :fresh #{:strand/added} 'skein.spools-test/fresh-reload-handler {}))\n"))
      (is (= :loaded (:status (runtime/reload! rt))))
      (is (nil? (runtime/use rt :stale)))
      (is (nil? (get (graph/queries rt) "stale")))
      (is (= [:= [:attr :owner] "fresh"] (get (graph/queries rt) "fresh")))
      (is (= [] (views/views rt)))
      (is (= [:fresh] (mapv :key (events/handlers rt))))
      (is (= [] (events/recent-failures rt)))
      (events/enqueue! rt {:event/type :strand/added
                           :event/id "after-reload"
                           :event/at "2026-06-27T00:00:00Z"
                           :event/source :test})
      (Thread/sleep 250)
      (is (= ["after-reload"] @reload-deliveries)))))

(deftest use-loads-namespace-from-synced-root-and-records-state
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.use-ns-" suffix))
            lib (symbol (str "demo/use-ns-lib-" suffix))
            root (write-local-lib! config-dir "use-ns" ns-sym)
            expected-file (io/file root "src" "demo" (str "use_ns_" suffix ".clj"))]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/use-ns"}}}))
        (runtime/sync! rt)
        (let [result (runtime/use! rt :demo/ns {:ns ns-sym :spools [lib]})]
          (is (= :loaded (:status result)))
          (is (= ns-sym (get-in result [:loaded :ns])))
          (is (= (.getCanonicalPath expected-file) (get-in result [:loaded :file])))
          (is (= result (runtime/use rt :demo/ns)))
          (is (= :synced-lib-loaded ((requiring-resolve (symbol (str ns-sym "/marker")))))))))))

(deftest use-searches-multiple-synced-roots-for-namespace-source
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            first-ns (symbol (str "demo.first" suffix))
            second-ns (symbol (str "demo.second-lib-" suffix))
            first-lib (symbol (str "demo/first-lib-" suffix))
            second-lib (symbol (str "demo/second-lib-" suffix))
            root (write-local-lib! config-dir "second" second-ns)]
        (write-local-lib! config-dir "first" first-ns)
        (write-spools! config-dir (pr-str {:spools {first-lib {:local/root "spools/first"}
                                                    second-lib {:local/root "spools/second"}}}))
        (runtime/sync! rt)
        (let [result (runtime/use! rt :demo/second {:ns second-ns :spools #{first-lib second-lib}})]
          (is (= :loaded (:status result)))
          (is (= (.getCanonicalPath (io/file root "src" "demo" (str "second_lib_" suffix ".clj")))
                 (get-in result [:loaded :file]))))))))

(deftest use-reports-missing-synced-namespace-source
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            existing-ns (symbol (str "demo.existing" suffix))
            missing-ns (symbol (str "demo.missing-lib-" suffix))
            lib (symbol (str "demo/missing-ns-lib-" suffix))
            root (write-local-lib! config-dir "missing-ns" existing-ns)]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/missing-ns"}}}))
        (runtime/sync! rt)
        (let [result (runtime/use! rt :demo/missing-ns {:ns missing-ns :spools [lib]})]
          (is (= :failed (:status result)))
          (is (= "Could not locate namespace source in synced spool roots" (get-in result [:error :message])))
          (is (= {:ns missing-ns
                  :relative-path (str "demo" java.io.File/separator "missing_lib_" suffix ".clj")
                  :searched-roots [(.getCanonicalPath (io/file root "src"))]}
                 (get-in result [:error :data]))))))))

(deftest use-loads-selected-config-relative-file-and-records-call-return
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.filemod" suffix))
            file-rel "modules/file_mod.clj"]
        (write-module-file! config-dir file-rel
                            (str "(ns " ns-sym ")\n(defn install! [] {:installed true})\n"))
        (let [result (runtime/use! rt :demo/file {:file file-rel
                                                  :call (symbol (str ns-sym "/install!"))})]
          (is (= :loaded (:status result)))
          (is (= (.getCanonicalPath (io/file config-dir file-rel)) (get-in result [:loaded :file])))
          (is (= {:fn (symbol (str ns-sym "/install!"))
                  :return {:installed true}}
                 (:call result))))))))

(deftest use-lib-gates-observe-local-overrides
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.override_gated_" suffix))
            lib (symbol (str "demo/override-gated-" suffix))
            root (write-local-lib! config-dir "override-gated-local" ns-sym)]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/missing-shared"}}}))
        (write-local-spools! config-dir (pr-str {:spools {lib {:local/root "spools/override-gated-local"}}}))
        (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
        (is (= (local-source config-dir) (get-in (runtime/syncs rt) [:spools lib :source])))
        (let [result (runtime/use! rt :override/gated {:ns ns-sym :spools [lib]})]
          (is (= :loaded (:status result)))
          (is (= (.getCanonicalPath (io/file root "src" "demo" (str "override_gated_" suffix ".clj")))
                 (get-in result [:loaded :file]))))))))

(deftest use-skips-on-lib-gates-before-loading
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.gated" suffix))
            approved-spool (symbol (str "demo/gated-lib-" suffix))
            failed-lib (symbol (str "demo/failed-lib-" suffix))]
        (write-local-lib! config-dir "gated" ns-sym)
        (write-spools! config-dir (pr-str {:spools {approved-spool {:local/root "spools/gated"}
                                                    failed-lib {:local/root "spools/missing"}}}))
        (is (= {:status :skipped
                :reason :not-approved
                :lib 'demo/not-approved}
               (select-keys (runtime/use! rt :not-approved {:ns ns-sym :spools ['demo/not-approved]})
                            [:status :reason :lib])))
        (is (= {:status :skipped
                :reason :not-synced
                :lib approved-spool}
               (select-keys (runtime/use! rt :not-synced {:ns ns-sym :spools [approved-spool]})
                            [:status :reason :lib])))
        (runtime/sync! rt)
        (let [result (runtime/use! rt :sync-failed {:ns ns-sym :spools [failed-lib]})]
          (is (= {:status :skipped
                  :reason :sync-failed
                  :lib failed-lib}
                 (select-keys result [:status :reason :lib])))
          (is (= :failed (get-in result [:sync :status])))
          (is (= :missing-root (get-in result [:sync :reason]))))))))

(deftest use-skips-on-missing-after
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.after" suffix))]
        (write-module-file! config-dir "modules/after.clj" (str "(ns " ns-sym ")\n"))
        (is (= :loaded (:status (runtime/use! rt :base {:file "modules/after.clj"}))))
        (is (= :missing-after (:reason (runtime/use! rt :child {:ns ns-sym :after [:base :missing]}))))))))

(deftest use-records-failures-and-required-rethrows
  (with-runtime
    (fn [rt config-dir]
      (write-module-file! config-dir "modules/bad.clj" "(throw (ex-info \"boom\" {:bad true}))\n")
      (is (= :failed (:status (runtime/use! rt :bad {:file "modules/bad.clj"}))))
      (is (thrown? Exception
                   (runtime/use! rt :required-bad {:file "modules/bad.clj" :required? true})))
      (is (= :failed (:status (runtime/use rt :required-bad))))
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.callfail" suffix))]
        (write-module-file! config-dir "modules/call_fail.clj"
                            (str "(ns " ns-sym ")\n(defn install! [] (throw (ex-info \"call boom\" {})))\n"))
        (let [result (runtime/use! rt :call-fail {:file "modules/call_fail.clj"
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
          (is (thrown-with-msg? clojure.lang.ExceptionInfo pattern (runtime/use! rt key opts))))))))

(deftest use-duplicate-keys-replace-previous-state
  (with-runtime
    (fn [rt config-dir]
      (write-module-file! config-dir "modules/dup1.clj" "(ns demo.dup1)\n")
      (write-module-file! config-dir "modules/dup2.clj" "(ns demo.dup2)\n")
      (is (= "modules/dup1.clj" (get-in (runtime/use! rt :dup {:file "modules/dup1.clj"}) [:opts :file])))
      (is (= "modules/dup2.clj" (get-in (runtime/use! rt :dup {:file "modules/dup2.clj"}) [:opts :file])))
      (is (= #{:dup} (set (keys (runtime/uses rt)))))
      (is (= "modules/dup2.clj" (get-in (runtime/use rt :dup) [:opts :file]))))))

(deftest use-gates-before-load-and-call-side-effects
  (with-runtime
    (fn [rt config-dir]
      (let [side-effect-file (io/file config-dir "gated-side-effect.edn")]
        (write-module-file! config-dir "modules/gated_effect.clj"
                            (str "(ns demo.gated-effect)\n"
                                 "(spit " (pr-str (str side-effect-file)) " :loaded)\n"
                                 "(defn install! [] (spit " (pr-str (str side-effect-file)) " :called))\n"))
        (is (= :not-approved (:reason (runtime/use! rt :gate/not-approved
                                                    {:file "modules/gated_effect.clj"
                                                     :spools ['demo/not-approved]
                                                     :call 'demo.gated-effect/install!}))))
        (is (false? (.exists side-effect-file)))
        (is (= :missing-after (:reason (runtime/use! rt :gate/missing-after
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
        (let [result (client/call-world (:config-dir ctx) {:timeout-ms 30000} :use! :connected
                                        {:file "modules/connected.clj"
                                         :call 'demo.connected/install!})]
          (is (= :loaded (:status result)))
          (is (= :ok (get-in result [:call :return])))
          (is (= :daemon-called (read-string (slurp result-file))))
          (is (= :loaded (:status (client/call-world (:config-dir ctx) {:timeout-ms 30000} :use :connected))))))
      (finally
        (delete-recursive root)))))

;; ---------------------------------------------------------------------------
;; spool-state reload-awareness: versioned reinit / migrate so a preserved value
;; whose shape drifted between deploys can never be reused silently.
;; ---------------------------------------------------------------------------

(deftest spool-state-unversioned-reuses-existing-value
  (with-runtime
    (fn [rt _config-dir]
      (let [first-value (runtime/spool-state rt ::demo (constantly {:v 1}))
            ;; a second init-fn returning a different shape must NOT run: the
            ;; unversioned accessor reuses the first value for the runtime life.
            second-value (runtime/spool-state rt ::demo (fn [] (throw (ex-info "should not init" {}))))]
        (is (= {:v 1} first-value))
        (is (identical? first-value second-value))))))

(deftest spool-state-versioned-reuses-on-matching-version
  (with-runtime
    (fn [rt _config-dir]
      (let [opts {:version 1}
            a (runtime/spool-state rt ::demo opts (constantly {:v 1}))
            b (runtime/spool-state rt ::demo opts (fn [] (throw (ex-info "should not reinit" {}))))]
        (is (identical? a b))
        (is (= 1 (::runtime/version (meta a)))
            "version is stored as value metadata, leaving the plain value visible")))))

(deftest spool-state-reinits-on-version-mismatch
  (with-runtime
    (fn [rt _config-dir]
      (let [closed? (atom false)
            v1 (runtime/spool-state rt ::demo {:version 1}
                                    (constantly {:shape :old :close-fn #(reset! closed? true)}))
            ;; a bumped version with a new-shape init-fn deliberately reinits and
            ;; releases the old value's resources via its :close-fn.
            v2 (runtime/spool-state rt ::demo {:version 2}
                                    (constantly {:shape :new :extra true}))]
        (is (= :old (:shape v1)))
        (is (= :new (:shape v2)))
        (is (true? (:extra v2)))
        (is (true? @closed?) "mismatched reinit runs the old value's close-fn")
        (is (= 2 (::runtime/version (meta v2))))))))

(deftest spool-state-migrate-fn-owns-old-value
  (with-runtime
    (fn [rt _config-dir]
      (let [closed? (atom false)
            _v1 (runtime/spool-state rt ::demo {:version 1}
                                     (constantly {:keep :registry :close-fn #(reset! closed? true)}))
            migrated (runtime/spool-state
                      rt ::demo
                      {:version 2 :migrate-fn (fn [old] {:keep (:keep old) :fresh true})}
                      (fn [] (throw (ex-info "migrate-fn owns reinit, init-fn must not run" {}))))]
        (is (= {:keep :registry :fresh true} (into {} migrated)))
        (is (false? @closed?) "migrate-fn owns the old value; the runtime does not auto-close")
        (is (= 2 (::runtime/version (meta migrated))))))))

(deftest spool-state-versioned-requires-metadata-support
  (with-runtime
    (fn [rt _config-dir]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must support metadata"
                            (runtime/spool-state rt ::demo {:version 1}
                                                 (constantly (Object.))))))))

(deftest spool-state-rejects-malformed-opts
  (with-runtime
    (fn [rt _config-dir]
      (testing "a typo'd key fails loudly instead of degrading to unversioned reuse"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (runtime/spool-state rt ::demo {:versoin 2} (constantly {:v 1})))))
      (testing "opts must be a map or nil"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts must be a map"
                              (runtime/spool-state rt ::demo 2 (constantly {:v 1})))))
      (testing ":version must be a non-nil comparable tag"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":version must be"
                              (runtime/spool-state rt ::demo {:version nil} (constantly {:v 1}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":version must be"
                              (runtime/spool-state rt ::demo {:version 1.5} (constantly {:v 1})))))
      (testing ":migrate-fn must be a function and requires a :version to compare"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":migrate-fn must be a function"
                              (runtime/spool-state rt ::demo {:version 1 :migrate-fn 5} (constantly {:v 1}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":migrate-fn requires a :version"
                              (runtime/spool-state rt ::demo {:migrate-fn identity} (constantly {:v 1}))))))))

(deftest spool-state-serializes-concurrent-version-mismatch-reinit
  ;; Several threads observing the same version mismatch must not each build a
  ;; replacement: a lock-free CAS loser would discard its freshly-built value
  ;; (and the executors it holds) and leak them for the JVM lifetime. The reinit
  ;; — init-fn plus the old value's close-fn — must run exactly once.
  (with-runtime
    (fn [rt _config-dir]
      (let [inits (atom 0)
            closes (atom 0)
            build-fn (fn [] (swap! inits inc) (Thread/sleep 20) {:shape :new})]
        (runtime/spool-state rt ::demo {:version 1}
                             (constantly {:shape :old :close-fn #(swap! closes inc)}))
        (let [n 8
              start (java.util.concurrent.CountDownLatch. 1)
              futures (doall (repeatedly n #(future (.await start)
                                                    (runtime/spool-state rt ::demo {:version 2} build-fn))))]
          (.countDown start)
          (run! deref futures)
          (is (= 1 @inits) "init-fn built the replacement exactly once")
          (is (= 1 @closes) "the old value's close-fn ran exactly once")
          (is (= :new (:shape (runtime/spool-state rt ::demo {:version 2} build-fn)))))))))

(deftest spool-state-reinit-survives-close-fn-failure
  ;; A failing best-effort cleanup during a version-mismatch reinit is warned,
  ;; not swallowed silently and not fatal — the reinit still completes.
  (with-runtime
    (fn [rt _config-dir]
      (runtime/spool-state rt ::demo {:version 1}
                           (constantly {:shape :old :close-fn #(throw (ex-info "boom" {}))}))
      (let [v2 (runtime/spool-state rt ::demo {:version 2} (constantly {:shape :new}))]
        (is (= :new (:shape v2)))))))

(defn- write-empty-lib!
  "Write a synced-able spool root with a deps.edn but no namespace sources."
  [config-dir lib-name]
  (let [root (io/file config-dir "spools" lib-name)]
    (.mkdirs (io/file root "src"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    root))

(defn- reload-reason [rt coord]
  (try
    (spool-sync/reload-synced-spool! rt coord)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:reason (ex-data e)))))

(deftest reload-synced-spool-makes-single-namespace-source-live
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.reload-ns-" suffix))
            lib (symbol (str "demo/reload-lib-" suffix))
            root (write-local-lib! config-dir "reload-ns" ns-sym)
            src-file (io/file root "src" "demo" (str "reload_ns_" suffix ".clj"))]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/reload-ns"}}}))
        (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
        (let [result (spool-sync/reload-synced-spool! rt lib)]
          (is (= lib (:coord result)))
          (is (= (.getCanonicalPath root) (:root result)))
          (is (= [{:ns ns-sym :file (.getCanonicalPath src-file)}]
                 (:namespaces result)))
          (is (= :synced-lib-loaded ((requiring-resolve (symbol (str ns-sym "/marker")))))))))))

(deftest reload-synced-spool-fails-when-coordinate-not-approved
  (with-runtime
    (fn [rt _config-dir]
      (is (= :not-approved (reload-reason rt 'demo/never-approved))))))

(deftest reload-synced-spool-fails-when-coordinate-not-synced
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.unsynced-" suffix))
            lib (symbol (str "demo/unsynced-lib-" suffix))]
        (write-local-lib! config-dir "unsynced" ns-sym)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/unsynced"}}}))
        (is (= :not-synced (reload-reason rt lib)))))))

(deftest reload-synced-spool-fails-when-sync-failed
  (with-runtime
    (fn [rt config-dir]
      (let [lib (symbol (str "demo/sync-failed-lib-" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/missing"}}}))
        (is (= :missing-root (get-in (runtime/sync! rt) [:spools lib :reason])))
        (is (= :sync-failed (reload-reason rt lib)))))))

;; A synced root removed or replaced *after* a clean sync is a real post-sync
;; scenario, but reproducing it by deleting a genuinely add-libs'd root would
;; poison this add-libs shard's JVM-global tools.deps basis (every later sync!
;; re-resolves the now-missing root and fails). The on-disk re-check reads only
;; the sync-state :root, so seeding a clean :loaded entry whose root is absent /
;; replaced exercises the same gate without ever add-libs'ing the doomed root.
(deftest reload-synced-spool-fails-when-root-missing-after-sync
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            lib (symbol (str "demo/missing-root-lib-" suffix))
            rel (str "spools/missing-root-" suffix)
            missing-root (io/file config-dir rel)]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root rel}}}))
        (swap! (access/approved-spool-sync-state rt) assoc lib
               {:lib lib :status :loaded :root (.getCanonicalPath missing-root)})
        (is (= :missing-root (reload-reason rt lib)))))))

(deftest reload-synced-spool-fails-when-root-replaced-by-file-after-sync
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            lib (symbol (str "demo/unreadable-root-lib-" suffix))
            rel (str "spools/unreadable-root-" suffix)
            root-file (io/file config-dir rel)]
        (.mkdirs (.getParentFile root-file))
        (spit root-file "not a directory\n")
        (write-spools! config-dir (pr-str {:spools {lib {:local/root rel}}}))
        (swap! (access/approved-spool-sync-state rt) assoc lib
               {:lib lib :status :loaded :root (.getCanonicalPath root-file)})
        (is (= :unreadable-root (reload-reason rt lib)))))))

(deftest reload-synced-spool-fails-when-root-has-no-namespaces
  (with-runtime
    (fn [rt config-dir]
      (let [lib (symbol (str "demo/no-ns-lib-" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))]
        (write-empty-lib! config-dir "no-ns")
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/no-ns"}}}))
        (is (contains? #{:loaded :already-available}
                       (get-in (runtime/sync! rt) [:spools lib :status])))
        (is (= :no-namespaces (reload-reason rt lib)))))))

;; Two distinct source files under the root declaring the same namespace would let
;; `into {}` keep whichever parsed last and silently drop the other from the reload
;; set. Sync only adds the root to the classpath (no compile), so the collision must
;; fail loudly at reload-ordering time, naming the namespace and both file paths.
(deftest reload-synced-spool-fails-when-two-sources-declare-same-namespace
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            shared-ns (symbol (str "demo.dup-shared-" suffix))
            lib (symbol (str "demo/dup-ns-lib-" suffix))
            root (io/file config-dir "spools" "dup-ns")
            file-a (io/file root "src" "demo" (str "dup_a_" suffix ".clj"))
            file-b (io/file root "src" "demo" (str "dup_b_" suffix ".clj"))]
        (.mkdirs (.getParentFile file-a))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (spit file-a (str "(ns " shared-ns ")\n(defn a [] :a)\n"))
        (spit file-b (str "(ns " shared-ns ")\n(defn b [] :b)\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/dup-ns"}}}))
        (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (spool-sync/reload-synced-spool! rt lib)))
              data (ex-data ex)]
          (is (= :duplicate-namespace (:reason data)))
          (is (= shared-ns (:namespace data)))
          (is (= lib (:coord data)))
          (is (= #{(.getCanonicalPath file-a) (.getCanonicalPath file-b)}
                 (set (:files data)))))))))

;; A genuine circular intra-root require makes tools.namespace throw a raw
;; `::circular-dependency` ex-info with no :status/:coord. The seam must catch it
;; and rethrow under the documented `{:status :failed :reason :coord}` contract.
;; Sync only adds the root to the classpath (no compile), so the cycle surfaces at
;; reload-ordering rather than sync.
(deftest reload-synced-spool-fails-on-circular-intra-root-requires
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-a (symbol (str "demo.cycle-a-" suffix))
            ns-b (symbol (str "demo.cycle-b-" suffix))
            lib (symbol (str "demo/cycle-lib-" suffix))
            root (io/file config-dir "spools" "cycle")]
        (.mkdirs (io/file root "src"))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (write-spool-ns! root ns-a (str "(ns " ns-a " (:require [" ns-b " :as b]))\n"))
        (write-spool-ns! root ns-b (str "(ns " ns-b " (:require [" ns-a " :as a]))\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/cycle"}}}))
        (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (spool-sync/reload-synced-spool! rt lib)))
              data (ex-data ex)]
          (is (= :circular-requires (:reason data)))
          (is (= lib (:coord data)))
          (is (= #{ns-a ns-b} (set (vals (:cycle data))))))))))

;; PLAN-shr-001.V3: two intra-root namespaces where `a` uses a macro from `b`.
;; A bumped `b` macro only reaches `a`'s expansion when `b` reloads before `a`;
;; the reverse order re-expands `a` against the stale macro. Asserting `a`'s new
;; value proves the topo-sort reloads dependencies first, not in arbitrary order.
(deftest reload-synced-spool-reloads-intra-root-dependencies-first
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-b (symbol (str "demo.dep-macro-" suffix))
            ns-a (symbol (str "demo.dep-consumer-" suffix))
            lib (symbol (str "demo/dep-order-lib-" suffix))
            root (io/file config-dir "spools" "dep-order")]
        (.mkdirs (io/file root "src"))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (write-spool-ns! root ns-b (str "(ns " ns-b ")\n(defmacro tag [] :v1)\n"))
        (write-spool-ns! root ns-a
                         (str "(ns " ns-a " (:require [" ns-b " :as b]))\n(defn value [] (b/tag))\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/dep-order"}}}))
        (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
        (is (= :loaded (:status (runtime/use! rt (keyword (str "dep-order-" suffix))
                                              {:ns ns-a :spools [lib]}))))
        (is (= :v1 ((requiring-resolve (symbol (str ns-a "/value"))))))
        (write-spool-ns! root ns-b (str "(ns " ns-b ")\n(defmacro tag [] :v2)\n"))
        (let [result (spool-sync/reload-synced-spool! rt lib)]
          (is (= :v2 ((requiring-resolve (symbol (str ns-a "/value")))))
              "a re-expands against the bumped macro only if b reloaded first")
          (is (= [ns-b ns-a] (mapv :ns (:namespaces result)))))))))

;; PLAN-shr-001.V2 keystone (the gap proof): a synced+loaded spool fn returns :v1;
;; after the source is bumped to :v2, neither the config `reload!` nor a bare
;; `(require ns :reload)` — both blind to the spool classloader's roots — see the
;; change, but `reload-synced-spool!` load-files it live under the spool loader.
(deftest reload-synced-spool-picks-up-bumped-source-that-reload-and-require-miss
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.keystone-" suffix))
            lib (symbol (str "demo/keystone-lib-" suffix))
            root (io/file config-dir "spools" "keystone")
            src-file (io/file root "src" "demo" (str "keystone_" suffix ".clj"))
            version #(requiring-resolve (symbol (str ns-sym "/version")))]
        (.mkdirs (.getParentFile src-file))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (spit src-file (str "(ns " ns-sym ")\n(defn version [] :v1)\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/keystone"}}}))
        (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
        (is (= :loaded (:status (runtime/use! rt (keyword (str "keystone-" suffix))
                                              {:ns ns-sym :spools [lib]}))))
        (is (= :v1 ((version))))
        (spit src-file (str "(ns " ns-sym ")\n(defn version [] :v2)\n"))
        ;; Config reload does not reload spool sources; it also clears the sync
        ;; registry, so restore it with a plain re-sync (which only re-adds the
        ;; root to the classloader — it never load-files the namespace).
        (runtime/reload! rt)
        (is (= :v1 ((version))) "config reload is blind to the bumped spool source")
        (is (contains? #{:loaded :already-available}
                       (get-in (runtime/sync! rt) [:spools lib :status])))
        (is (= :v1 ((version))) "re-sync add-libs the root but does not reload the source")
        ;; A bare require :reload runs on the base loader, which has no spool root.
        (try (require ns-sym :reload) (catch java.io.FileNotFoundException _ nil))
        (is (= :v1 ((version))) "require :reload is classloader-blind to the spool root")
        (let [result (spool-sync/reload-synced-spool! rt lib)]
          (is (= :v2 ((version))) "the blessed seam load-files the bumped source live")
          (is (= [ns-sym] (mapv :ns (:namespaces result)))))))))

;; TASK-shr-003.MI1: the blessed verb fails loudly before delegating when `coord`
;; is not a symbol, rather than passing a bad key into the sync-state lookup. It
;; routes through the canonical `require-valid!` seam, so the failure carries the
;; standard `{:value :explain}` boundary-validation shape its siblings produce.
(deftest reload-spool-verb-rejects-non-symbol-coordinate
  (with-runtime
    (fn [rt _config-dir]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (runtime/reload-spool! rt :demo/keyword-coord)))
            data (ex-data ex)]
        (is (= :demo/keyword-coord (:value data)))
        (is (contains? data :explain))))))

;; TASK-shr-003.MI5: the keystone gap proof exercised *through* the blessed
;; `runtime/reload-spool!` (runtime passed explicitly, no ambient singleton): a
;; synced+loaded spool fn returns :v1; after the source is bumped, the blessed
;; verb makes :v2 live and returns the data-first coordinate/root/namespaces map.
(deftest reload-spool-verb-makes-bumped-source-live
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.verb-keystone-" suffix))
            lib (symbol (str "demo/verb-keystone-lib-" suffix))
            root (io/file config-dir "spools" "verb-keystone")
            src-file (io/file root "src" "demo" (str "verb_keystone_" suffix ".clj"))
            version #(requiring-resolve (symbol (str ns-sym "/version")))]
        (.mkdirs (.getParentFile src-file))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (spit src-file (str "(ns " ns-sym ")\n(defn version [] :v1)\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/verb-keystone"}}}))
        (is (= :loaded (get-in (runtime/sync! rt) [:spools lib :status])))
        (is (= :loaded (:status (runtime/use! rt (keyword (str "verb-keystone-" suffix))
                                              {:ns ns-sym :spools [lib]}))))
        (is (= :v1 ((version))))
        (spit src-file (str "(ns " ns-sym ")\n(defn version [] :v2)\n"))
        (let [result (runtime/reload-spool! rt lib)]
          (is (= :v2 ((version))) "the blessed verb makes the bumped source live")
          (is (= lib (:coord result)))
          (is (= (.getCanonicalPath root) (:root result)))
          (is (= [{:ns ns-sym :file (.getCanonicalPath src-file)}]
                 (:namespaces result))))))))
