(ns todo.plugin-test
  (:require [clojure.test :refer [deftest is testing]]
            [atom.plugin.alpha :as plugin]
            [todo.daemon.config :as daemon-config]
            [todo.daemon.runtime :as runtime]
            [todo.db-test :as db-test]))

(defn with-runtime [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/td-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [rt (runtime/start! db-file {:world (daemon-config/world config-dir)})]
      (try
        (f rt)
        (finally
          (runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file))))))

(deftest plugin-registration-records-and-returns-metadata
  (with-runtime
    (fn [_]
      (is (= {:format-version 1
              :name 'demo/plugin
              :version "0.1.0"
              :requires-atom "0.0.1"
              :provides ['demo/feature]
              :source "local"
              :dir "/tmp/plugin"
              :init-file "/tmp/plugin/init.clj"
              :loaded-at "2026-06-25T00:00:00Z"}
             (plugin/register! {:format-version 1
                                :name :demo/plugin
                                :version "0.1.0"
                                :requires-atom "0.0.1"
                                :provides [:demo/feature]
                                :source "local"
                                :dir "/tmp/plugin"
                                :init-file "/tmp/plugin/init.clj"
                                :loaded-at "2026-06-25T00:00:00Z"}))))))

(deftest plugin-registration-replaces-by-canonical-name
  (with-runtime
    (fn [_]
      (plugin/register! {:format-version 1 :name :demo/plugin :version "0.1.0"})
      (is (= {:format-version 1 :name 'demo/plugin :version "0.2.0"}
             (plugin/register! {:format-version 1 :name 'demo/plugin :version "0.2.0"})))
      (is (= {:format-version 1 :name 'demo/plugin :version "0.2.0"}
             (plugin/plugin :demo/plugin)))
      (is (= [{:format-version 1 :name 'demo/plugin :version "0.2.0"}]
             (plugin/plugins))))))

(deftest plugin-lookup-normalizes-symbol-and-keyword-names
  (with-runtime
    (fn [_]
      (plugin/register! {:format-version 1 :name :demo/plugin})
      (is (= {:format-version 1 :name 'demo/plugin} (plugin/plugin 'demo/plugin)))
      (is (= {:format-version 1 :name 'demo/plugin} (plugin/plugin :demo/plugin)))
      (is (nil? (plugin/plugin :demo/missing))))))

(deftest plugin-introspection-is-daemon-lifetime-state
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/td-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [world (daemon-config/world config-dir)
          rt (runtime/start! db-file {:world world})]
      (try
        (plugin/register! {:format-version 1 :name :demo/a})
        (plugin/register! {:format-version 1 :name :demo/b})
        (is (= ['demo/a 'demo/b] (mapv :name (plugin/plugins))))
        (runtime/stop! rt)
        (let [fresh-rt (runtime/start! db-file {:world world})]
          (try
            (is (= [] (plugin/plugins)))
            (finally
              (runtime/stop! fresh-rt))))
        (finally
          (db-test/delete-sqlite-family! db-file))))))

(deftest plugin-metadata-validation-fails-loudly
  (with-runtime
    (fn [_]
      (testing "unknown key rejection"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (plugin/register! {:format-version 1 :name :demo/plugin :extra true}))))
      (testing "format-version validation"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"format version"
                              (plugin/register! {:format-version 2 :name :demo/plugin})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":format-version"
                              (plugin/register! {:name :demo/plugin}))))
      (testing "invalid metadata"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"metadata must be a map"
                              (plugin/register! nil)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbol or keyword"
                              (plugin/register! {:format-version 1 :name "demo/plugin"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":version must be a string"
                              (plugin/register! {:format-version 1 :name :demo/plugin :version 1})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":requires-atom must be a string"
                              (plugin/register! {:format-version 1 :name :demo/plugin :requires-atom 1})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":provides must be a vector"
                              (plugin/register! {:format-version 1 :name :demo/plugin :provides :demo/feature})))))))
