(ns skein.test.alpha-test
  "Tests for the blessed skein.test.alpha weaver-world helpers."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skein.test.alpha :as t]))

(def ^:private require-api
  "(require '[skein.api.current.alpha :as current] '[skein.api.weaver.alpha :as api])")

(deftest with-weaver-world-runs-file-backed-world-and-cleans-up
  (let [captured (atom nil)
        result (t/with-weaver-world [ctx {}]
                 (reset! captured ctx)
                 (is (= :sqlite-file (:storage ctx)))
                 (is (.isFile (io/file (:db-path ctx))))
                 (is (map? (:metadata ctx)))
                 (is (= (:config-dir ctx) (get-in ctx [:metadata :config-dir])))
                 (let [strand (t/repl! ctx (str "(do " require-api
                                                " (api/add (current/runtime) {:title \"From repl\"}))"))]
                   (is (= "From repl" (:title strand))))
                 (is (= 1 (count (t/repl! ctx (str "(do " require-api " (api/list (current/runtime)))")))))
                 :done)]
    (is (= :done result))
    (testing "generated workspace root is removed after the body"
      (is (false? (.exists (io/file (:config-dir @captured))))))))

(deftest with-weaver-world-writes-fixtures-and-loads-init
  (t/with-weaver-world [ctx {:config-json "{}\n"
                             :spools-edn {:spools {}}
                             :init (str require-api
                                        " (api/register-query (current/runtime) 'from-init [:= :state \"active\"])")
                             :files {"modules/demo.clj" "(ns demo)\n"}}]
    (is (= "{}\n" (slurp (io/file (:config-dir ctx) "config.json"))))
    (is (= "{:spools {}}" (slurp (io/file (:config-dir ctx) "spools.edn"))))
    (is (= "(ns demo)\n" (slurp (io/file (:config-dir ctx) "modules/demo.clj"))))
    (testing "init.clj ran inside the weaver runtime"
      (is (contains? (t/repl! ctx (str "(do " require-api " (api/queries (current/runtime)))"))
                     "from-init")))))

(deftest weaver-world-fixture-binds-memory-storage-context
  ((t/weaver-world-fixture {:storage :sqlite-memory})
   (fn []
     (let [ctx t/*weaver-world*]
       (is (= :sqlite-memory (:storage ctx)))
       (is (nil? (:db-path ctx)))
       (is (nil? (get-in ctx [:metadata :canonical-db-path])))
       (is (false? (.exists (io/file (:data-dir ctx) "skein.sqlite"))))
       (is (= [] (t/repl! ctx (str "(do " require-api " (api/list (current/runtime)))"))))))))

(deftest weaver-worlds-nest-and-stay-isolated
  (t/with-weaver-world [outer {}]
    (t/with-weaver-world [inner {:storage :sqlite-memory}]
      (t/repl! outer (str "(do " require-api " (api/add (current/runtime) {:title \"outer\"}))"))
      (is (= 1 (count (t/repl! outer (str "(do " require-api " (api/list (current/runtime)))")))))
      (is (= [] (t/repl! inner (str "(do " require-api " (api/list (current/runtime)))")))))))

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
                 (t/repl! ctx "(throw (ex-info \"weaver boom\" {:detail 1}))")
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= "weaver boom" (:weaver-message (ex-data ex))))))))
