(ns skein.plugin-test
  "Tests for weaver plugin/op registration and dispatch."
  (:require [clojure.test :refer [deftest is]]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.weaver.alpha]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.db-test :as db-test]))
(defn test-world [config-dir]
  (weaver-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn with-runtime [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/td-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [rt (weaver-runtime/start! db-file {:world (test-world config-dir)
                                             :publish? false})]
      (try
        (f rt)
        (finally
          (weaver-runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file))))))

(deftest old-plugin-and-bootstrap-surfaces-are-not-available
  (is (thrown? java.io.FileNotFoundException (require 'atom.plugin.alpha)))
  (is (thrown? java.io.FileNotFoundException (require 'atom.bootstrap.alpha)))
  (is (thrown? java.io.FileNotFoundException (require 'atom.prelude.alpha)))
  (is (nil? (ns-resolve 'skein.core.client 'load-plugin)))
  (is (nil? (ns-resolve 'skein.api.weaver.alpha 'load-plugin)))
  (is (nil? (ns-resolve 'skein.api.weaver.alpha 'plugins)))
  (is (nil? (ns-resolve 'skein.api.weaver.alpha 'plugin))))

(deftest runtime-loader-state-is-the-public-path
  (with-runtime
    (fn [rt]
      (is (= {:spools {} :families {}} (runtime/approved rt)))
      (is (= {:spools {}} (runtime/syncs rt)))
      (is (= {} (runtime/uses rt))))))

