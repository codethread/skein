(ns skein.libs-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skein.libs.alpha :as libs]
            [skein.weaver.config :as daemon-config]
            [skein.weaver.api :as api]
            [skein.client :as client]
            [skein.weaver.runtime :as runtime]
            [skein.db-test :as db-test]
            [skein.repl :as repl]))

(defn- temp-config-dir []
  (doto (.toFile (java.nio.file.Files/createTempDirectory
                  (.toPath (io/file "/tmp"))
                  "skein-libs-config"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
    (.mkdirs)))

(defn- delete-recursive [file]
  (doseq [child (reverse (file-seq file))]
    (.delete child)))

(defn- with-runtime [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (temp-config-dir)]
    (try
      (let [rt (runtime/start! db-file {:world (daemon-config/world (.getCanonicalPath config-dir))})]
        (try
          (f rt config-dir)
          (finally
            (runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        ;; Runtime-added local roots are retained for the process lifetime by tools.deps.
        ;; Keep temp config dirs so later add-libs calls do not see stale basis entries.
        nil))))

(defn- write-libs! [config-dir content]
  (spit (io/file config-dir "libs.edn") content))

(defn- write-local-lib! [config-dir lib-name ns-sym]
  (let [root (io/file config-dir "libs" lib-name)
        ns-path (-> (str ns-sym)
                    (.replace \- \_)
                    (.replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file (str "(ns " ns-sym ")\n(defn marker [] :synced-lib-loaded)\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    root))

(deftest approved-returns-empty-libs-when-file-is-missing
  (with-runtime
    (fn [_ _]
      (is (= {:libs {}} (libs/approved))))))

(deftest approved-fails-when-libs-edn-is-not-a-file
  (with-runtime
    (fn [_ config-dir]
      (.mkdirs (io/file config-dir "libs.edn"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"malformed or unreadable"
                            (libs/approved))))))

(deftest approved-normalizes-relative-and-absolute-roots
  (with-runtime
    (fn [_ config-dir]
      (let [relative-root (io/file config-dir "libs" "demo")
            absolute-root (io/file config-dir "external" "abs")]
        (.mkdirs relative-root)
        (.mkdirs absolute-root)
        (write-libs! config-dir
                     (pr-str {:libs {'demo/relative {:local/root "libs/demo"}
                                     'demo/absolute {:local/root (.getAbsolutePath absolute-root)}}}))
        (is (= {:libs {'demo/absolute {:local/root (.getAbsolutePath absolute-root)
                                       :root (.getCanonicalPath absolute-root)}
                       'demo/relative {:local/root "libs/demo"
                                       :root (.getCanonicalPath relative-root)}}}
               (libs/approved)))))))

(deftest approved-expands-home-relative-roots
  (with-runtime
    (fn [_ config-dir]
      (let [home (System/getProperty "user.home")
            home-root (io/file home "dev" "projects" "my-lib")]
        (write-libs! config-dir (pr-str {:libs {'demo/home {:local/root "~/dev/projects/my-lib"}}}))
        (is (= {:libs {'demo/home {:local/root "~/dev/projects/my-lib"
                                   :root (.getCanonicalPath home-root)}}}
               (libs/approved)))))))

(deftest approved-canonicalizes-symlink-roots
  (with-runtime
    (fn [_ config-dir]
      (let [target (io/file config-dir "libs" "target")
            link (io/file config-dir "libs" "link")]
        (.mkdirs target)
        (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath target)
                                                (make-array java.nio.file.attribute.FileAttribute 0))
        (write-libs! config-dir (pr-str {:libs {'demo/link {:local/root "libs/link"}}}))
        (is (= {:libs {'demo/link {:local/root "libs/link"
                                   :root (.getCanonicalPath target)}}}
               (libs/approved)))))))

(deftest approved-does-not-reject-missing-local-roots
  (with-runtime
    (fn [_ config-dir]
      (let [missing (io/file config-dir "libs" "missing")]
        (write-libs! config-dir (pr-str {:libs {'demo/missing {:local/root "libs/missing"}}}))
        (is (= {:libs {'demo/missing {:local/root "libs/missing"
                                      :root (.getCanonicalPath missing)}}}
               (libs/approved)))))))

(deftest approved-routes-through-connected-helper-context
  (with-redefs [runtime/current-runtime (atom nil)
                repl/connected-config-dir (constantly "/tmp/skein-connected-world")
                skein.client/call-world (fn [config-dir opts op & args]
                                         {:config-dir config-dir
                                          :opts opts
                                          :op op
                                          :args args})]
    (is (= {:config-dir "/tmp/skein-connected-world"
            :opts {}
            :op :approved-libs
            :args nil}
           (libs/approved)))
    (is (= {:config-dir "/tmp/skein-connected-world"
            :opts {}
            :op :sync-approved-libs
            :args nil}
           (libs/sync!)))
    (is (= {:config-dir "/tmp/skein-connected-world"
            :opts {}
            :op :approved-lib-syncs
            :args nil}
           (libs/syncs)))
    (is (= {:config-dir "/tmp/skein-connected-world"
            :opts {}
            :op :reload-config!
            :args nil}
           (libs/reload!)))
    (is (= {:config-dir "/tmp/skein-connected-world"
            :opts {}
            :op :use!
            :args [:demo {:ns 'demo.core}]}
           (libs/use! :demo {:ns 'demo.core})))
    (is (= {:config-dir "/tmp/skein-connected-world"
            :opts {}
            :op :uses
            :args nil}
           (libs/uses)))
    (is (= {:config-dir "/tmp/skein-connected-world"
            :opts {}
            :op :use
            :args [:demo]}
           (libs/use :demo)))))

(deftest approved-fails-loudly-on-structural-errors
  (with-runtime
    (fn [_ config-dir]
      (doseq [[label content pattern]
              [["malformed EDN" "{:libs" #"malformed or unreadable"]
               ["unknown top-level key" (pr-str {:libs {} :extra true}) #"unknown top-level keys"]
               ["missing :libs" (pr-str {}) #"requires :libs map"]
               ["non-map :libs" (pr-str {:libs []}) #"requires :libs map"]
               ["non-symbol coordinate" (pr-str {:libs {"demo/lib" {:local/root "libs/demo"}}}) #"coordinate must be a symbol"]
               ["non-map entry" (pr-str {:libs {'demo/lib "libs/demo"}}) #"entry must be a map"]
               ["unknown per-lib key" (pr-str {:libs {'demo/lib {:local/root "libs/demo" :extra true}}}) #"unknown keys"]
               ["missing root" (pr-str {:libs {'demo/lib {}}}) #"requires non-blank string"]
               ["non-string root" (pr-str {:libs {'demo/lib {:local/root 1}}}) #"requires non-blank string"]
               ["blank root" (pr-str {:libs {'demo/lib {:local/root "  "}}}) #"requires non-blank string"]]]
        (testing label
          (write-libs! config-dir content)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo pattern (libs/approved))))))))

(deftest sync-loads-approved-local-root-and-exposes-state
  (with-runtime
    (fn [_ config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.synced-" suffix))
            lib (symbol (str "demo/lib-" suffix))
            root (write-local-lib! config-dir "demo" ns-sym)]
        (write-libs! config-dir (pr-str {:libs {lib {:local/root "libs/demo"}}}))
        (is (= {:libs {lib {:lib lib
                            :local/root "libs/demo"
                            :root (.getCanonicalPath root)
                            :status :loaded}}}
               (libs/sync!)))
        (is (= {:libs {lib {:lib lib
                            :local/root "libs/demo"
                            :root (.getCanonicalPath root)
                            :status :loaded}}}
               (libs/syncs)))
        (is (= {:libs {lib {:lib lib
                            :local/root "libs/demo"
                            :root (.getCanonicalPath root)
                            :status :already-available}}}
               (libs/sync!)))))))

(deftest daemon-init-runs-with-library-classloader-after-sync
  (let [db-file (db-test/temp-db-file)
        config-dir (temp-config-dir)
        suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
        ns-sym (symbol (str "demo.init-synced-" suffix))
        lib (symbol (str "demo/init-lib-" suffix))
        result-file (io/file config-dir "init-result.edn")]
    (write-local-lib! config-dir "init-demo" ns-sym)
    (try
      (write-libs! config-dir (pr-str {:libs {lib {:local/root "libs/init-demo"}}}))
      (spit (io/file config-dir "init.clj")
            (str "(do\n"
                 "  (require '[skein.libs.alpha :as libs])\n"
                 "  (libs/sync!)\n"
                 "  (require '" ns-sym ")\n"
                 "  (spit " (pr-str (str result-file))
                 " (pr-str ((requiring-resolve '" (symbol (str ns-sym "/marker")) ")))))\n"))
      (let [rt (runtime/start! db-file {:world (daemon-config/world (.getCanonicalPath config-dir))})]
        (try
          (is (= :synced-lib-loaded (read-string (slurp result-file))))
          (is (= :loaded (get-in (libs/syncs) [:libs lib :status])))
          (finally
            (runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (when-not (.exists result-file)
          (delete-recursive config-dir))))))

(deftest sync-clears-stale-state-before-structural-failure
  (with-runtime
    (fn [_ config-dir]
      (let [missing (io/file config-dir "libs" "missing")]
        (write-libs! config-dir (pr-str {:libs {'demo/missing {:local/root "libs/missing"}}}))
        (is (= {:libs {'demo/missing {:lib 'demo/missing
                                      :local/root "libs/missing"
                                      :root (.getCanonicalPath missing)
                                      :status :failed
                                      :reason :missing-root}}}
               (libs/sync!)))
        (write-libs! config-dir "{:libs")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed or unreadable" (libs/sync!)))
        (is (= {:libs {}} (libs/syncs)))))))

(deftest sync-records-runtime-add-failures-as-failed-outcomes
  (with-runtime
    (fn [_ config-dir]
      (let [root (io/file config-dir "libs" "bad-deps")]
        (.mkdirs root)
        (spit (io/file root "deps.edn") "{:paths [}")
        (write-libs! config-dir (pr-str {:libs {'demo/bad-deps {:local/root "libs/bad-deps"}}}))
        (let [result (get-in (libs/sync!) [:libs 'demo/bad-deps])]
          (is (= {:lib 'demo/bad-deps
                  :local/root "libs/bad-deps"
                  :root (.getCanonicalPath root)
                  :status :failed
                  :reason :runtime-add-failed}
                 (select-keys result [:lib :local/root :root :status :reason])))
          (is (string? (:message result)))
          (is (string? (:class result))))))))

(deftest sync-records-missing-and-unreadable-roots-as-failed-outcomes
  (with-runtime
    (fn [_ config-dir]
      (let [not-dir (io/file config-dir "libs" "not-dir")]
        (.mkdirs (.getParentFile not-dir))
        (spit not-dir "not a directory")
        (write-libs! config-dir (pr-str {:libs {'demo/missing {:local/root "libs/missing"}
                                                'demo/not-dir {:local/root "libs/not-dir"}}}))
        (is (= {:libs {'demo/missing {:lib 'demo/missing
                                      :local/root "libs/missing"
                                      :root (.getCanonicalPath (io/file config-dir "libs" "missing"))
                                      :status :failed
                                      :reason :missing-root}
                       'demo/not-dir {:lib 'demo/not-dir
                                      :local/root "libs/not-dir"
                                      :root (.getCanonicalPath not-dir)
                                      :status :failed
                                      :reason :unreadable-root}}}
               (libs/sync!)))))))

(defn- write-module-file! [config-dir relative-path content]
  (let [file (io/file config-dir relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

(deftest reload-loads-selected-config-init-file
  (with-runtime
    (fn [_ config-dir]
      (let [result-file (io/file config-dir "reload-result.edn")]
        (spit (io/file config-dir "init.clj")
              (str "(spit " (pr-str (str result-file)) " (pr-str :reloaded))\n"
                   ":reload-return\n"))
        (let [result (libs/reload!)]
          (is (= :loaded (:status result)))
          (is (= (.getCanonicalPath (io/file config-dir "init.clj")) (:file result)))
          (is (= :reload-return (:return result)))
          (is (= :reloaded (read-string (slurp result-file)))))))))

(deftest reload-fails-loudly-when-init-file-is-missing
  (with-runtime
    (fn [_ _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no init\.clj"
                            (libs/reload!))))))

(deftest reload-clears-prior-runtime-config-state-before-loading
  (with-runtime
    (fn [rt config-dir]
      (write-module-file! config-dir "modules/stale.clj" "(ns demo.stale)\n")
      (is (= :loaded (:status (libs/use! :stale {:file "modules/stale.clj"}))))
      (api/register-query rt 'stale [:= [:attr :owner] "stale"])
      (api/register-view! rt 'stale-view 'demo.stale/view)
      (spit (io/file config-dir "init.clj")
            "(require '[skein.weaver.api :as api] '[skein.weaver.runtime :as runtime])\n(api/register-query @runtime/current-runtime 'fresh [:= [:attr :owner] \"fresh\"])\n")
      (is (= :loaded (:status (libs/reload!))))
      (is (nil? (libs/use :stale)))
      (is (nil? (get (api/queries rt) "stale")))
      (is (= [:= [:attr :owner] "fresh"] (get (api/queries rt) "fresh")))
      (is (= [] (api/views rt))))))

(deftest use-loads-namespace-from-synced-root-and-records-state
  (with-runtime
    (fn [_ config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.use-ns-" suffix))
            lib (symbol (str "demo/use-ns-lib-" suffix))
            root (write-local-lib! config-dir "use-ns" ns-sym)
            expected-file (io/file root "src" "demo" (str "use_ns_" suffix ".clj"))]
        (write-libs! config-dir (pr-str {:libs {lib {:local/root "libs/use-ns"}}}))
        (libs/sync!)
        (let [result (libs/use! :demo/ns {:ns ns-sym :libs [lib]})]
          (is (= :loaded (:status result)))
          (is (= ns-sym (get-in result [:loaded :ns])))
          (is (= (.getCanonicalPath expected-file) (get-in result [:loaded :file])))
          (is (= result (libs/use :demo/ns)))
          (is (= :synced-lib-loaded ((requiring-resolve (symbol (str ns-sym "/marker")))))))))))

(deftest use-searches-multiple-synced-roots-for-namespace-source
  (with-runtime
    (fn [_ config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            first-ns (symbol (str "demo.first" suffix))
            second-ns (symbol (str "demo.second-lib-" suffix))
            first-lib (symbol (str "demo/first-lib-" suffix))
            second-lib (symbol (str "demo/second-lib-" suffix))
            root (write-local-lib! config-dir "second" second-ns)]
        (write-local-lib! config-dir "first" first-ns)
        (write-libs! config-dir (pr-str {:libs {first-lib {:local/root "libs/first"}
                                               second-lib {:local/root "libs/second"}}}))
        (libs/sync!)
        (let [result (libs/use! :demo/second {:ns second-ns :libs #{first-lib second-lib}})]
          (is (= :loaded (:status result)))
          (is (= (.getCanonicalPath (io/file root "src" "demo" (str "second_lib_" suffix ".clj")))
                 (get-in result [:loaded :file]))))))))

(deftest use-reports-missing-synced-namespace-source
  (with-runtime
    (fn [_ config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            existing-ns (symbol (str "demo.existing" suffix))
            missing-ns (symbol (str "demo.missing-lib-" suffix))
            lib (symbol (str "demo/missing-ns-lib-" suffix))
            root (write-local-lib! config-dir "missing-ns" existing-ns)]
        (write-libs! config-dir (pr-str {:libs {lib {:local/root "libs/missing-ns"}}}))
        (libs/sync!)
        (let [result (libs/use! :demo/missing-ns {:ns missing-ns :libs [lib]})]
          (is (= :failed (:status result)))
          (is (= "Could not locate namespace source in synced library roots" (get-in result [:error :message])))
          (is (= {:ns missing-ns
                  :relative-path (str "demo" java.io.File/separator "missing_lib_" suffix ".clj")
                  :searched-roots [(.getCanonicalPath (io/file root "src"))]}
                 (get-in result [:error :data]))))))))

(deftest use-loads-selected-config-relative-file-and-records-call-return
  (with-runtime
    (fn [_ config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.filemod" suffix))
            file-rel "modules/file_mod.clj"]
        (write-module-file! config-dir file-rel
                            (str "(ns " ns-sym ")\n(defn install! [] {:installed true})\n"))
        (let [result (libs/use! :demo/file {:file file-rel
                                            :call (symbol (str ns-sym "/install!"))})]
          (is (= :loaded (:status result)))
          (is (= (.getCanonicalPath (io/file config-dir file-rel)) (get-in result [:loaded :file])))
          (is (= {:fn (symbol (str ns-sym "/install!"))
                  :return {:installed true}}
                 (:call result))))))))

(deftest use-skips-on-lib-gates-before-loading
  (with-runtime
    (fn [_ config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.gated" suffix))
            approved-lib (symbol (str "demo/gated-lib-" suffix))
            failed-lib (symbol (str "demo/failed-lib-" suffix))]
        (write-local-lib! config-dir "gated" ns-sym)
        (write-libs! config-dir (pr-str {:libs {approved-lib {:local/root "libs/gated"}
                                               failed-lib {:local/root "libs/missing"}}}))
        (is (= :not-approved (:reason (libs/use! :not-approved {:ns ns-sym :libs ['demo/not-approved]}))))
        (is (= :not-synced (:reason (libs/use! :not-synced {:ns ns-sym :libs [approved-lib]}))))
        (libs/sync!)
        (let [result (libs/use! :sync-failed {:ns ns-sym :libs [failed-lib]})]
          (is (= :skipped (:status result)))
          (is (= :sync-failed (:reason result)))
          (is (= :failed (get-in result [:sync :status]))))))))

(deftest use-skips-on-missing-after
  (with-runtime
    (fn [_ config-dir]
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.after" suffix))]
        (write-module-file! config-dir "modules/after.clj" (str "(ns " ns-sym ")\n"))
        (is (= :loaded (:status (libs/use! :base {:file "modules/after.clj"}))))
        (is (= :missing-after (:reason (libs/use! :child {:ns ns-sym :after [:base :missing]}))))))))

(deftest use-records-failures-and-required-rethrows
  (with-runtime
    (fn [_ config-dir]
      (write-module-file! config-dir "modules/bad.clj" "(throw (ex-info \"boom\" {:bad true}))\n")
      (is (= :failed (:status (libs/use! :bad {:file "modules/bad.clj"}))))
      (is (thrown? Exception
                   (libs/use! :required-bad {:file "modules/bad.clj" :required? true})))
      (is (= :failed (:status (libs/use :required-bad))))
      (let [suffix (.replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.callfail" suffix))]
        (write-module-file! config-dir "modules/call_fail.clj"
                            (str "(ns " ns-sym ")\n(defn install! [] (throw (ex-info \"call boom\" {})))\n"))
        (let [result (libs/use! :call-fail {:file "modules/call_fail.clj"
                                            :call (symbol (str ns-sym "/install!"))})]
          (is (= :failed (:status result))))))))

(deftest use-fails-loudly-on-malformed-options
  (with-runtime
    (fn [_ _]
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
               ["bad libs" :bad {:ns 'demo.core :libs ['demo/lib 1]} #":libs entries"]
               ["bad after" :bad {:ns 'demo.core :after [1]} #":after entries"]
               ["bad call" :bad {:ns 'demo.core :call 'install!} #":call must"]
               ["bad required" :bad {:ns 'demo.core :required? :yes} #":required\? must"]]]
        (testing label
          (is (thrown-with-msg? clojure.lang.ExceptionInfo pattern (libs/use! key opts))))))))

(deftest use-duplicate-keys-replace-previous-state
  (with-runtime
    (fn [_ config-dir]
      (write-module-file! config-dir "modules/dup1.clj" "(ns demo.dup1)\n")
      (write-module-file! config-dir "modules/dup2.clj" "(ns demo.dup2)\n")
      (is (= "modules/dup1.clj" (get-in (libs/use! :dup {:file "modules/dup1.clj"}) [:opts :file])))
      (is (= "modules/dup2.clj" (get-in (libs/use! :dup {:file "modules/dup2.clj"}) [:opts :file])))
      (is (= #{:dup} (set (keys (libs/uses)))))
      (is (= "modules/dup2.clj" (get-in (libs/use :dup) [:opts :file]))))))

(deftest use-gates-before-load-and-call-side-effects
  (with-runtime
    (fn [_ config-dir]
      (let [side-effect-file (io/file config-dir "gated-side-effect.edn")]
        (write-module-file! config-dir "modules/gated_effect.clj"
                            (str "(ns demo.gated-effect)\n"
                                 "(spit " (pr-str (str side-effect-file)) " :loaded)\n"
                                 "(defn install! [] (spit " (pr-str (str side-effect-file)) " :called))\n"))
        (is (= :not-approved (:reason (libs/use! :gate/not-approved
                                                 {:file "modules/gated_effect.clj"
                                                  :libs ['demo/not-approved]
                                                  :call 'demo.gated-effect/install!}))))
        (is (false? (.exists side-effect-file)))
        (is (= :missing-after (:reason (libs/use! :gate/missing-after
                                                {:file "modules/gated_effect.clj"
                                                 :after [:missing]
                                                 :call 'demo.gated-effect/install!}))))
        (is (false? (.exists side-effect-file)))))))

(deftest connected-client-use-executes-in-daemon-runtime
  (with-runtime
    (fn [rt config-dir]
      (let [result-file (io/file config-dir "connected-result.edn")]
        (write-module-file! config-dir "modules/connected.clj"
                            (str "(ns demo.connected)\n"
                                 "(defn install! [] (spit " (pr-str (str result-file)) " (pr-str :daemon-called)) :ok)\n"))
        (let [config-path (get-in rt [:metadata :config-dir])
              result (client/call-world config-path {} :use! :connected {:file "modules/connected.clj"
                                                                         :call 'demo.connected/install!})]
          (is (= :loaded (:status result)))
          (is (= :ok (get-in result [:call :return])))
          (is (= :daemon-called (read-string (slurp result-file))))
          (is (= :loaded (:status (client/call-world config-path {} :use :connected)))))))))
