(ns skein.api.events.alpha-test
  "Seam contract coverage for the events API.

  The broad behavior lock — entry shape and replace-by-key round-trips,
  asynchronous dispatch, failure record capture, reload interplay — is
  deliberately retained in `skein.weaver-test` and `skein.spools-test`,
  not duplicated here. This namespace pins what those locks skip: the loud
  registration seam (every validated piece rejects with its own error and
  leaves the registry untouched), unregistration idempotence, deterministic
  registry ordering across mixed key types, and seam spec conformance."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [skein.api.events.alpha :as events]
            [skein.test.alpha :as t])
  (:import [clojure.lang ExceptionInfo]))

(defn capture-event
  "Handler fixture: registration only needs a resolvable callable."
  [_event]
  nil)

(def not-callable
  "Non-callable var pinning the resolution contract."
  42)

(def ^:private handler-sym
  "Qualified symbol of the fixture handler, as registration wants it."
  'skein.api.events.alpha-test/capture-event)

(deftest registration-rejects-each-invalid-piece-and-leaves-the-registry-untouched
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (thrown-with-msg? ExceptionInfo #"key must be a keyword, symbol, or string"
                            (events/register-handler! rt 42 #{:strand/added} handler-sym)))
      (is (thrown-with-msg? ExceptionInfo #"key string must be non-blank"
                            (events/register-handler! rt "  " #{:strand/added} handler-sym)))
      (is (thrown-with-msg? ExceptionInfo #"types must be a set"
                            (events/register-handler! rt :k [:strand/added] handler-sym)))
      (is (thrown-with-msg? ExceptionInfo #"types must be non-empty"
                            (events/register-handler! rt :k #{} handler-sym)))
      (is (thrown-with-msg? ExceptionInfo #"types must be keywords"
                            (events/register-handler! rt :k #{"strand/added"} handler-sym)))
      (is (thrown-with-msg? ExceptionInfo #"must be a fully qualified symbol"
                            (events/register-handler! rt :k #{:strand/added} 'unqualified)))
      (is (thrown-with-msg? ExceptionInfo #"could not be resolved"
                            (events/register-handler! rt :k #{:strand/added} 'no.such.ns/handler)))
      (is (thrown-with-msg? ExceptionInfo #"must resolve to a callable value"
                            (events/register-handler!
                             rt :k #{:strand/added}
                             'skein.api.events.alpha-test/not-callable)))
      (is (thrown-with-msg? ExceptionInfo #"metadata must be a map"
                            (events/register-handler! rt :k #{:strand/added} handler-sym
                                                      [:not-a-map])))
      (is (thrown-with-msg? ExceptionInfo #"metadata must contain only data-first values"
                            (events/register-handler! rt :k #{:strand/added} handler-sym
                                                      {:opaque (Object.)})))
      (is (= [] (events/handlers rt))
          "a rejected registration never partially mutates the registry"))))

(deftest unregistration-is-idempotent-and-validates-its-key
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (= {:unregistered :never-registered}
             (events/unregister-handler! rt :never-registered)))
      (is (thrown-with-msg? ExceptionInfo #"key must be a keyword, symbol, or string"
                            (events/unregister-handler! rt 42))))))

(deftest registry-reads-are-deterministic-across-mixed-key-types
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (doseq [key [:kw-key "string-key" 'sym-key]]
        (events/register-handler! rt key #{:strand/added} handler-sym))
      (let [entries (events/handlers rt)]
        (is (= (sort-by pr-str [:kw-key "string-key" 'sym-key]) (mapv :key entries))
            "entries sort by printed key, stable across key types")
        (is (every? #(s/valid? ::events/handler-entry %) entries))
        (is (not-any? :fn-value entries)
            "the resolved function value never leaves the registry")))))
