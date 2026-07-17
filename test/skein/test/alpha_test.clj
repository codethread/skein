(ns skein.test.alpha-test
  "Tests for the blessed skein.test.alpha weaver-world helpers."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skein.api.weaver.alpha :as weaver]
            [skein.test.alpha :as t]))

(deftest check-op-return-selects-declared-return-leaves
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          flat-shape {:type :map :required {:items {:type :collection :items :integer}}}
          flat-value {:items [1 2]}
          stream-returns {:subcommands
                          {"watch" {:stream {:emits :string
                                             :result [:nullable :boolean]}}}}]
      (weaver/register-op! rt 'flat
                           {:returns flat-shape}
                           'skein.test.alpha-test/unused-op)
      (weaver/register-op! rt 'subcommand
                           {:arg-spec {:op "subcommand"
                                       :subcommands {"show" {}}}
                            :returns {:subcommands {"show" :integer}}}
                           'skein.test.alpha-test/unused-op)
      (weaver/register-op! rt 'stream
                           {:arg-spec {:op "stream"
                                       :subcommands {"watch" {}}}
                            :stream? true
                            :returns stream-returns}
                           'skein.test.alpha-test/unused-op)
      (testing "flat success preserves identity"
        (is (identical? flat-value (t/check-op-return! rt 'flat flat-value))))
      (testing "subcommand result"
        (is (= 42 (t/check-op-return! rt 'subcommand {:subcommand "show"} 42))))
      (testing "stream emitted item and terminal result"
        (is (= "line" (t/check-op-return! rt 'stream
                                          {:subcommand "watch" :channel :emits}
                                          "line")))
        (is (nil? (t/check-op-return! rt 'stream
                                      {:subcommand "watch" :channel :result}
                                      nil))))
      (testing "mismatch diagnostics preserve selected declaration and shape path"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Operation return value does not match declaration"
                                      (t/check-op-return! rt 'flat {:items [1 "bad"]})))]
          (is (= "flat" (:operation (ex-data e))))
          (is (= flat-shape (:declaration (ex-data e))))
          (is (= [:items 1] (:path (ex-data e))))
          (is (= "bad" (:actual (ex-data e)))))))))

(deftest check-op-return-fails-loudly-on-absent-or-misaligned-declarations
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (weaver/register-op! rt 'undeclared 'skein.test.alpha-test/unused-op)
      (weaver/register-op! rt 'flat
                           {:returns :string}
                           'skein.test.alpha-test/unused-op)
      (weaver/register-op! rt 'stream
                           {:stream? true
                            :returns {:stream {:emits :string :result :boolean}}}
                           'skein.test.alpha-test/unused-op)
      (doseq [[operation context value reason]
              [['undeclared {} "value" :missing-return-declaration]
               ['flat {:subcommand "show"} "value" :unexpected-return-subcommand]
               ['flat {:channel :result} "value" :unexpected-return-channel]
               ['stream {} true :missing-return-channel]
               ['stream {:channel :unknown} true :unknown-return-channel]]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (t/check-op-return! rt operation context value)))]
          (is (= (name operation) (:operation (ex-data e))))
          (is (= reason (:reason (ex-data e)))))))))

(defn unused-op
  "Test-only registered operation handler; output checks consume captured values."
  [_]
  nil)

(deftest with-weaver-world-runs-file-backed-world-and-cleans-up
  (let [captured (atom nil)
        result (t/with-weaver-world [ctx {}]
                 (reset! captured ctx)
                 (is (= :sqlite-file (:storage ctx)))
                 (is (.isFile (io/file (:db-path ctx))))
                 (is (map? (:metadata ctx)))
                 (is (= (:config-dir ctx) (get-in ctx [:metadata :config-dir])))
                 (testing "quoted forms are rendered and evaluated in the weaver"
                   (let [strand (t/repl! ctx
                                         '(do
                                            (require '[skein.api.current.alpha :as current]
                                                     '[skein.api.weaver.alpha :as weaver])
                                            (weaver/add (current/runtime)
                                                        {:title "From repl"})))]
                     (is (= "From repl" (:title strand)))))
                 (is (= 1 (count (t/repl! ctx
                                          '(do
                                             (require '[skein.api.current.alpha :as current]
                                                      '[skein.api.weaver.alpha :as weaver])
                                             (weaver/list (current/runtime)))))))
                 :done)]
    (is (= :done result))
    (testing "generated workspace root is removed after the body"
      (is (false? (.exists (io/file (:config-dir @captured))))))))

(deftest with-weaver-world-writes-fixtures-and-loads-init
  (t/with-weaver-world [ctx {:config-json "{}\n"
                             :spools-edn {:spools {}}
                             :init (pr-str '(do
                                              (require '[skein.api.current.alpha :as current]
                                                       '[skein.api.graph.alpha :as graph])
                                              (graph/register-query! (current/runtime)
                                                                     'from-init
                                                                     [:= :state "active"])))
                             :files {"modules/demo.clj" "(ns demo)\n"}}]
    (is (= "{}\n" (slurp (io/file (:config-dir ctx) "config.json"))))
    (is (= "{:spools {}}" (slurp (io/file (:config-dir ctx) "spools.edn"))))
    (is (= "(ns demo)\n" (slurp (io/file (:config-dir ctx) "modules/demo.clj"))))
    (testing "init.clj ran inside the weaver runtime"
      (is (contains? (t/repl! ctx
                              '(do
                                 (require '[skein.api.current.alpha :as current]
                                          '[skein.api.graph.alpha :as graph])
                                 (graph/queries (current/runtime))))
                     "from-init")))))

(deftest weaver-world-fixture-binds-memory-storage-context
  ((t/weaver-world-fixture {:storage :sqlite-memory})
   (fn []
     (let [ctx t/*weaver-world*]
       (is (= :sqlite-memory (:storage ctx)))
       (is (nil? (:db-path ctx)))
       (is (nil? (get-in ctx [:metadata :canonical-db-path])))
       (is (false? (.exists (io/file (:data-dir ctx) "skein.sqlite"))))
       (is (= [] (t/repl! ctx
                          '(do
                             (require '[skein.api.current.alpha :as current]
                                      '[skein.api.weaver.alpha :as weaver])
                             (weaver/list (current/runtime))))))))))

(deftest weaver-worlds-nest-and-stay-isolated
  (t/with-weaver-world [outer {}]
    (t/with-weaver-world [inner {:storage :sqlite-memory}]
      (t/repl! outer '(do
                        (require '[skein.api.current.alpha :as current]
                                 '[skein.api.weaver.alpha :as weaver])
                        (weaver/add (current/runtime) {:title "outer"})))
      (is (= 1 (count (t/repl! outer '(do
                                        (require '[skein.api.current.alpha :as current]
                                                 '[skein.api.weaver.alpha :as weaver])
                                        (weaver/list (current/runtime)))))))
      (is (= [] (t/repl! inner '(do
                                  (require '[skein.api.current.alpha :as current]
                                           '[skein.api.weaver.alpha :as weaver])
                                  (weaver/list (current/runtime)))))))))

(deftest spool-checkout-root-resolves-directory-checkouts-from-classpath-entry
  (let [checkout (doto (io/file (System/getProperty "java.io.tmpdir")
                                (str "skein-spool-checkout-" (java.util.UUID/randomUUID)))
                   (.mkdirs))
        source-root (io/file checkout "src/main/clojure")
        source-file (io/file source-root "demo/spool.clj")]
    (try
      (io/make-parents source-file)
      (spit (io/file checkout "deps.edn") "{:paths [\"src/main/clojure\"]}\n")
      (spit source-file "(ns demo.spool)\n")
      (let [resource-loader (fn [path]
                              (when (= "demo/spool.clj" path)
                                (.toURL (.toURI source-file))))]
        (is (= (.getCanonicalFile checkout)
               (.getCanonicalFile (t/spool-checkout-root "demo/spool.clj" resource-loader)))))
      (finally
        (doseq [file (reverse (file-seq checkout))]
          (.delete file))))))

(deftest spool-checkout-root-fails-loudly-for-jar-backed-resources
  (let [resource-loader (fn [path]
                          (when (= "demo/spool.clj" path)
                            (java.net.URL. "jar:file:/tmp/demo.jar!/demo/spool.clj")))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Spool source is not a directory checkout"
                          (t/spool-checkout-root "demo/spool.clj" resource-loader)))))

(deftest helper-fails-loudly-on-bad-input
  (testing "unknown options"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown weaver world options"
                          (t/run-with-weaver-world {:libs-edn {}} identity))))
  (testing "fixture files must stay inside the workspace root"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must stay inside"
                          (t/run-with-weaver-world {:files {"../escape.txt" "nope"}} identity))))
  (testing "spools-edn must be a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":spools-edn must be an EDN map"
                          (t/run-with-weaver-world {:spools-edn "{:spools {}}"} identity))))
  (testing "startup failures propagate"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"startup file failed to load"
                          (t/with-weaver-world [_ {:init "(throw (ex-info \"boom\" {}))"}]
                            nil))))
  (testing "weaver-side eval failures throw with weaver context"
    (t/with-weaver-world [ctx {}]
      (let [ex (try
                 (t/repl! ctx '(throw (ex-info "weaver boom" {:detail 1})))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= "weaver boom" (:weaver-message (ex-data ex))))))))
