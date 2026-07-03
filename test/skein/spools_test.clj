(ns skein.spools-test
  "Tests for runtime spool workspace surfaces: approved spools.edn and
  spools.local.edn reading, sync!, layered use!, reload!, event helper routing,
  daemon init, and the ephemeral helper spool."
  (:require [clojure.java.io :as io]
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
        (is (= {:spools {'demo/shared {:local/root "spools/shared"
                                     :root (.getCanonicalPath shared-root)
                                     :source (shared-source config-dir)}
                       'demo/local {:local/root "spools/local"
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
        (is (= {:local/root "spools/local"
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
        (is (= {:spools {'demo/absolute {:local/root (.getAbsolutePath absolute-root)
                                       :root (.getCanonicalPath absolute-root)
                                       :source (shared-source config-dir)}
                       'demo/relative {:local/root "spools/demo"
                                       :root (.getCanonicalPath relative-root)
                                       :source (shared-source config-dir)}}}
               (runtime-alpha/approved rt)))))))

(deftest approved-expands-home-relative-roots
  (with-runtime
    (fn [rt config-dir]
      (let [home (System/getProperty "user.home")
            home-root (io/file home "dev" "projects" "my-lib")]
        (write-spools! config-dir (pr-str {:spools {'demo/home {:local/root "~/dev/projects/my-lib"}}}))
        (is (= {:spools {'demo/home {:local/root "~/dev/projects/my-lib"
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
        (is (= {:spools {'demo/link {:local/root "spools/link"
                                   :root (.getCanonicalPath target)
                                   :source (shared-source config-dir)}}}
               (runtime-alpha/approved rt)))))))

(deftest approved-does-not-reject-missing-local-roots
  (with-runtime
    (fn [rt config-dir]
      (let [missing (io/file config-dir "spools" "missing")]
        (write-spools! config-dir (pr-str {:spools {'demo/missing {:local/root "spools/missing"}}}))
        (is (= {:spools {'demo/missing {:local/root "spools/missing"
                                      :root (.getCanonicalPath missing)
                                      :source (shared-source config-dir)}}}
               (runtime-alpha/approved rt)))))))

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
                            :local/root "spools/demo"
                            :root (.getCanonicalPath root)
                            :source (shared-source config-dir)
                            :status :loaded}}}
               (runtime-alpha/sync! rt)))
        (is (= {:spools {lib {:lib lib
                            :local/root "spools/demo"
                            :root (.getCanonicalPath root)
                            :source (shared-source config-dir)
                            :status :loaded}}}
               (runtime-alpha/syncs rt)))
        (is (= {:spools {lib {:lib lib
                            :local/root "spools/demo"
                            :root (.getCanonicalPath root)
                            :source (shared-source config-dir)
                            :status :already-available}}}
               (runtime-alpha/sync! rt)))))))

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
                  :local/root "spools/bad-deps"
                  :root (.getCanonicalPath root)
                  :source (shared-source config-dir)
                  :status :failed
                  :reason :runtime-add-failed}
                 (select-keys result [:lib :local/root :root :source :status :reason])))
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
                                      :local/root "spools/missing"
                                      :root (.getCanonicalPath (io/file config-dir "spools" "missing"))
                                      :source (shared-source config-dir)
                                      :status :failed
                                      :reason :missing-root}
                       'demo/not-dir {:lib 'demo/not-dir
                                      :local/root "spools/not-dir"
                                      :root (.getCanonicalPath not-dir)
                                      :source (shared-source config-dir)
                                      :status :failed
                                      :reason :unreadable-root}}}
               (runtime-alpha/sync! rt)))))))

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
