(ns skein.api.runtime.alpha-test
  "Release-marker, config-write, and path coverage for the explicit runtime API."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.runtime.alpha :as runtime]
            [skein.core.specs :as specs]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.runtime :as weaver-runtime]))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            (java.nio.file.Paths/get "/tmp" (make-array String 0))
            prefix
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [^java.io.File root]
  (when (.exists root)
    (doseq [^java.io.File file (reverse (file-seq root))]
      (when-not (.delete file)
        (throw (ex-info "Failed to delete test file" {:file (.getPath file)}))))))

(defn- run-git! [dir & args]
  (let [process (-> (ProcessBuilder. ^java.util.List (vec (cons "git" args)))
                    (.directory dir)
                    (.start))
        stderr (future (slurp (.getErrorStream process)))
        stdout (future (slurp (.getInputStream process)))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "Git fixture command failed"
                      {:args args :exit exit :stdout @stdout :stderr @stderr})))
    (str/trim @stdout)))

(defn- git-source! [tag]
  (let [root (temp-dir "skein-marker-source")]
    (run-git! root "init" "--quiet")
    (run-git! root "config" "user.email" "skein-test@example.invalid")
    (run-git! root "config" "user.name" "Skein Test")
    (spit (io/file root "source.txt") "source\n")
    (run-git! root "add" "source.txt")
    (run-git! root "commit" "--quiet" "-m" "fixture")
    (when tag
      (run-git! root "tag" "-a" tag "-m" tag))
    root))

(defn- compiled-runtime-resource! [source-root]
  (let [resource (io/file source-root
                          "target/classes/skein/core/weaver/runtime.clj")]
    (.mkdirs (.getParentFile resource))
    (spit resource "compiled fixture\n")
    (.toURL (.toURI resource))))

(defn- test-world [root]
  (weaver-config/world (str (io/file root "config"))
                       (str (io/file root "state"))
                       (str (io/file root "data"))))

(defn- with-source-root [source-root f]
  (with-redefs-fn {(ns-resolve 'skein.core.weaver.runtime 'source-checkout-root)
                   (constantly source-root)}
    f))

(defn- with-runtime-resource [resource-url f]
  (let [source-checkout-root (deref (ns-resolve 'skein.core.weaver.runtime
                                                'source-checkout-root))]
    (with-redefs-fn {(ns-resolve 'skein.core.weaver.runtime 'source-checkout-root)
                     #(source-checkout-root resource-url)}
      f)))

(defn- with-started-runtime [source-root opts f]
  (let [root (temp-dir "skein-marker-world")
        world (test-world root)]
    (try
      (let [rt (with-source-root
                 source-root
                 #(weaver-runtime/start! nil (merge {:world world
                                                     :publish? false
                                                     :storage :sqlite-memory}
                                                    opts)))]
        (try
          (f rt world)
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (delete-tree! root)))))

(deftest spool-state-opts-spec-owns-public-shape
  (is (s/valid? ::runtime/spool-state-opts nil))
  (is (s/valid? ::runtime/spool-state-opts {:version :v2 :migrate-fn identity}))
  (doseq [opts [{:versoin 2}
                {:version nil}
                {:version 1.5}
                {:version 1 :migrate-fn 5}
                {:migrate-fn identity}]]
    (is (not (s/valid? ::runtime/spool-state-opts opts)))))

(deftest release-marker-resolution
  (let [source-root (git-source! "v3")]
    (try
      (testing "an explicit claim beats an annotated tag on source HEAD"
        (with-started-runtime
          source-root
          {:release-marker "v7"}
          (fn [rt _]
            (is (= {:marker "v7" :provenance :claimed}
                   (runtime/release-marker rt))))))
      (testing "an annotated release tag on source HEAD is used without a claim"
        (with-started-runtime
          source-root
          {}
          (fn [rt _]
            (is (= {:marker "v3" :provenance :tag}
                   (runtime/release-marker rt))))))
      (finally
        (delete-tree! source-root))))
  (testing "an untagged source checkout has an explicit none shape"
    (let [source-root (git-source! nil)]
      (try
        (with-started-runtime
          source-root
          {}
          (fn [rt _]
            (is (= {:marker nil :provenance :none}
                   (runtime/release-marker rt)))))
        (finally
          (delete-tree! source-root))))))

(deftest compiled-classpath-release-marker-resolution
  (let [source-root (git-source! "v4")
        world-root (temp-dir "skein-compiled-marker-world")
        resource-url (compiled-runtime-resource! source-root)]
    (try
      (with-runtime-resource
        resource-url
        #(let [rt (weaver-runtime/start! nil {:world (test-world world-root)
                                              :publish? false
                                              :storage :sqlite-memory})]
           (try
             (is (= {:marker "v4" :provenance :tag}
                    (runtime/release-marker rt)))
             (finally
               (weaver-runtime/stop! rt)))))
      (finally
        (delete-tree! world-root)
        (delete-tree! source-root)))))

(deftest non-repo-classpath-release-marker-resolution
  (let [source-root (temp-dir "skein-non-git-source")
        world-root (temp-dir "skein-git-failure-world")
        resource-url (compiled-runtime-resource! source-root)]
    (try
      (with-runtime-resource
        resource-url
        #(let [rt (weaver-runtime/start! nil {:world (test-world world-root)
                                              :publish? false
                                              :storage :sqlite-memory})]
           (try
             (is (= {:marker nil :provenance :none}
                    (runtime/release-marker rt)))
             (finally
               (weaver-runtime/stop! rt)))))
      (finally
        (delete-tree! world-root)
        (delete-tree! source-root)))))

(deftest git-inspection-command-failures-remain-loud
  (let [source-root (git-source! nil)
        run-git (deref (ns-resolve 'skein.core.weaver.runtime 'run-git))]
    (try
      (doseq [[args expected-exit]
              [[["rev-parse" "--verify" "refs/heads/missing"] 128]
               [["-c" "alias.noisy=!echo warning >&2" "noisy"] 0]]]
        (let [error (try
                      (apply run-git source-root args)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
              data (ex-data error)]
          (is (= :git-inspection-failed (:reason data)))
          (is (= expected-exit (:exit data)))
          (is (not (str/blank? (:stderr data))))))
      (finally
        (delete-tree! source-root)))))

(deftest runtime-result-specs-own-public-shapes
  (is (s/valid? ::specs/release-marker-syntax "v0"))
  (is (not (s/valid? ::specs/release-marker-syntax "v01")))
  (is (s/valid? ::specs/release-marker-claim "v12"))
  (is (not (s/valid? ::specs/release-marker-claim "v0")))
  (is (s/valid? ::specs/release-marker-result
                {:marker nil :provenance :none}))
  (is (not (s/valid? ::specs/release-marker-result
                     {:marker "v2" :provenance :none})))
  (is (s/valid? ::specs/weaver-start-options
                {:world (test-world (io/file "/tmp/skein-start-options"))
                 :name nil
                 :publish? false
                 :storage :sqlite-memory
                 :release-marker "v2"}))
  (is (s/valid? ::specs/weaver-start-options
                {:world (dissoc (test-world (io/file "/tmp/skein-start-options"))
                                :config-file)}))
  (is (not (s/valid? ::specs/weaver-start-options
                     {:world (test-world (io/file "/tmp/skein-start-options"))
                      :unknown true})))
  (is (s/valid? ::specs/config-dir-result "/tmp/config"))
  (is (s/valid? ::specs/spools-file-result (io/file "/tmp/config/spools.edn")))
  (is (s/valid? ::runtime/reload-spool-result
                {:root-lib 'demo/root
                 :root "/tmp/root"
                 :namespaces [{:ns 'demo.core :file "/tmp/root/src/demo/core.clj"}]}))
  (is (not (s/valid? ::runtime/reload-spool-result
                     {:root-lib 'demo/root
                      :root "/tmp/root"
                      :namespaces []
                      :unexpected true}))))

(deftest module-use-result-specs-own-public-shapes
  (let [loaded {:key :demo/loaded
                :opts {:ns 'demo.core}
                :status :loaded
                :loaded {:ns 'demo.core}}
        skipped {:key :demo/skipped
                 :opts {:ns 'demo.core :spools ['demo/root]}
                 :status :skipped
                 :reason :not-approved
                 :lib 'demo/root}
        failed {:key :demo/failed
                :opts {:file "module.clj"}
                :status :failed
                :error {:message "boom"
                        :class "class clojure.lang.ExceptionInfo"
                        :data {:reason :boom}}}]
    (doseq [entry [loaded skipped failed]]
      (is (s/valid? ::runtime/use-entry entry)))
    (is (s/valid? ::runtime/uses-result
                  {:demo/loaded loaded :demo/skipped skipped :demo/failed failed}))
    (is (s/valid? ::runtime/use-result nil))
    (is (not (s/valid? ::runtime/use-entry (assoc loaded :unexpected true))))
    (is (not (s/valid? ::runtime/use-entry (dissoc failed :error))))))

(deftest start-options-are-validated-before-destructuring
  (doseq [opts [nil {:unknown true} {:publish? :yes}]]
    (let [error (try
                  (weaver-runtime/start! nil opts)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-start-options (:reason (ex-data error))))
      (is (= ::specs/weaver-start-options (:spec (ex-data error)))))))

(deftest malformed-release-marker-claims-fail-loudly
  (doseq [claim ["1" "v01" "v-1" :v2]]
    (let [root (temp-dir "skein-invalid-marker")]
      (try
        (let [error (try
                      (with-source-root
                        nil
                        #(weaver-runtime/start! nil {:world (test-world root)
                                                     :publish? false
                                                     :storage :sqlite-memory
                                                     :release-marker claim}))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :invalid-release-marker (:reason (ex-data error))))
          (is (= claim (:marker (ex-data error)))))
        (finally
          (delete-tree! root))))))

(deftest v0-release-marker-claim-is-reserved
  (let [root (temp-dir "skein-v0-marker")]
    (try
      (let [error (try
                    (with-source-root
                      nil
                      #(weaver-runtime/start! nil {:world (test-world root)
                                                   :publish? false
                                                   :storage :sqlite-memory
                                                   :release-marker "v0"}))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (re-find #"v0 is reserved" (ex-message error)))
        (is (= {:reason :reserved-release-marker
                :marker "v0"
                :provenance :claimed}
               (ex-data error))))
      (finally
        (delete-tree! root)))))

(deftest runtime-path-accessors-use-the-selected-config-dir
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (is (= (:config-dir world) (runtime/config-dir rt)))
      (is (= (.getCanonicalFile (io/file (:config-dir world) "spools.edn"))
             (.getCanonicalFile (runtime/spools-file rt)))))))

(deftest spool-config-write-specs-own-public-shapes
  (let [entry {:git/url "https://example.invalid/demo.git"
               :git/sha (str/join (repeat 40 "a"))
               :git/tag "v1"}]
    (is (s/valid? ::runtime/spool-family 'demo/family))
    (is (s/valid? ::runtime/spool-entry entry))
    (is (s/valid? ::runtime/spool-write-result
                  {:status :inserted
                   :lib 'demo/family
                   :entry entry
                   :file (io/file "/tmp/spools.edn")}))
    (is (not (s/valid? ::runtime/spool-entry (assoc entry :git/sha "short"))))
    (is (not (s/valid? ::runtime/spool-write-result
                       {:status :inserted :lib 'demo/family :entry entry})))))

(deftest approved-surfaces-declared-and-effective-family-config
  (with-started-runtime
    nil
    {:release-marker "v3"}
    (fn [rt world]
      (let [config-dir (io/file (:config-dir world))
            sha (str/join (repeat 40 "b"))
            declared {:git/url "https://example.invalid/demo.git"
                      :git/sha sha
                      :git/tag "v2"
                      :roots {'demo/root "."}
                      :requires {'demo/root "v1"}
                      :skein/min "v1"}]
        (.mkdirs config-dir)
        (spit (io/file config-dir "spools.edn")
              (pr-str {:spools {'demo/family declared}}))
        (is (= :spools-edn
               (get-in (runtime/approved rt) [:spools 'demo/root :provenance])))
        (spit (io/file config-dir "spools.local.edn")
              (pr-str {:spools {'demo/family {:local/root "../demo"
                                              :claims "v3"}}}))
        (let [approved (runtime/approved rt)]
          (is (= {:provenance :local-overlay :claims "v3"}
                 (select-keys (get-in approved [:spools 'demo/root])
                              [:provenance :claims])))
          (is (= {:declared declared
                  :effective-coordinate {:kind :local :local/root "../demo"}
                  :provenance :local-overlay
                  :claims "v3"}
                 (get-in approved [:families 'demo/family]))))))))

(deftest approved-projects-implicit-single-root-family
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [config-dir (io/file (:config-dir world))
            declared {:local/root "spools/demo"}]
        (.mkdirs config-dir)
        (spit (io/file config-dir "spools.edn")
              (pr-str {:spools {'demo/family declared}}))
        (let [approved (runtime/approved rt)]
          (is (= #{'demo/family} (set (keys (:spools approved)))))
          (is (= {:declared declared
                  :effective-coordinate {:kind :local :local/root "spools/demo"}
                  :provenance :spools-edn
                  :claims nil}
                 (get-in approved [:families 'demo/family]))))))))

(deftest upsert-spool-entry-validates-before-write-and-preserves-header-comments
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [file (io/file (:config-dir world) "spools.edn")
            overlay-file (io/file (:config-dir world) "spools.local.edn")
            overlay ";; hand edited\n{:spools {}}\n"
            header ";; This header explains hand-owned policy.\n;; Keep it byte-for-byte.\n"
            suffix "\n ;; top-level comment survives too\n :mvn-overrides {}}\n"
            original (str header "{:spools {}" suffix)
            entry {:local/root "spools/demo"}]
        (.mkdirs (.getParentFile file))
        (spit file original)
        (spit overlay-file overlay)
        (is (= :inserted (:status (runtime/upsert-spool-entry! rt 'demo/family entry))))
        (let [written (slurp file)]
          (is (str/starts-with? written header))
          (is (str/ends-with? written suffix))
          (is (= entry (get-in (edn/read-string written) [:spools 'demo/family]))))
        (is (= overlay (slurp overlay-file)))
        (is (= :updated
               (:status (runtime/upsert-spool-entry!
                         rt 'demo/family {:local/root "spools/demo-2"}))))
        (let [before (slurp file)
              error (try
                      (runtime/upsert-spool-entry!
                       rt 'demo/bad {:git/url "https://example.invalid/bad.git"
                                     :git/sha "short"})
                      nil
                      (catch clojure.lang.ExceptionInfo exception exception))]
          (is (= "Git spool entry requires 40 lowercase hex characters :git/sha"
                 (ex-message error)))
          (is (= 'demo/bad (:family (ex-data error))))
          (is (= before (slurp file))))))))

(deftest spool-config-writes-refuse-trailing-edn-without-changing-the-file
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [file (io/file (:config-dir world) "spools.edn")
            config "{:spools {demo/family {:local/root \"spools/demo\"}}}\n{:ignored true}\n"]
        (.mkdirs (.getParentFile file))
        (doseq [write! [#(runtime/upsert-spool-entry!
                          rt 'demo/family {:local/root "spools/replacement"})
                        #(runtime/remove-spool-entry! rt 'demo/family)]]
          (spit file config)
          (let [error (try
                        (write!)
                        nil
                        (catch clojure.lang.ExceptionInfo exception exception))]
            (is (re-find #"trailing content" (ex-message error)))
            (is (= (.getPath file) (:file (ex-data error))))
            (is (= "{:ignored true}" (:trailing-content (ex-data error))))
            (is (= config (slurp file)))))))))

(deftest remove-spool-entry-refuses-requirers-and-removes-unreferenced-family
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [file (io/file (:config-dir world) "spools.edn")
            sha-a (str/join (repeat 40 "a"))
            sha-b (str/join (repeat 40 "b"))
            target {:git/url "https://example.invalid/target.git"
                    :git/sha sha-a
                    :git/tag "v1"
                    :roots {'demo/root "."}}
            requirer {:git/url "https://example.invalid/requirer.git"
                      :git/sha sha-b
                      :git/tag "v1"
                      :requires {'demo/root "v1"}}]
        (.mkdirs (.getParentFile file))
        (spit file (pr-str {:spools {'demo/target target 'demo/requirer requirer}}))
        (let [error (try
                      (runtime/remove-spool-entry! rt 'demo/target)
                      nil
                      (catch clojure.lang.ExceptionInfo exception exception))]
          (is (= :spool-family-required (:reason (ex-data error))))
          (is (= [{:family 'demo/requirer :roots #{'demo/root}}]
                 (:requirers (ex-data error))))
          (is (= target (get-in (edn/read-string (slurp file))
                                [:spools 'demo/target]))))
        (is (= :removed (:status (runtime/remove-spool-entry! rt 'demo/requirer))))
        (is (nil? (get-in (edn/read-string (slurp file))
                          [:spools 'demo/requirer])))))))
