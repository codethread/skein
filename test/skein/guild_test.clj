(ns skein.guild-test
  "Tests for the guild reference spool's op declaration and deprecation API."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [clojure.data.json :as json]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.guild :as guild]
            [skein.spools.test-support :refer [with-runtime]]
            [skein.test.alpha :as t]))

(s/def ::task string?)
(s/def ::close-input (s/keys :req-un [::task]))

(defn close-handler
  "Return the parsed guild input for test assertions."
  [ctx]
  {:op (:op/name ctx)
   :input (:guild/input ctx)})

(defn- json-arg [value]
  (json/write-str value :key-fn name))

(def ^:private close-return
  {:type :map
   :required {:op :string :input {:type :map :extra :json}}})

(deftest production-return-coverage-is-derived-from-guild-provenance
  (with-runtime
    (fn [rt _]
      (guild/install! "coverage-guild")
      (guild/defop! 'gate.close.v1 {:doc "Close" :returns close-return}
        'skein.guild-test/close-handler)
      (let [entries (filterv #(= 'skein.spools.guild (:provenance %)) (weaver/ops rt))
            missing (mapv :name (filter #(not (contains? % :returns)) entries))
            required (set (map (juxt :name (constantly {})) entries))
            description (weaver/op! rt 'guild.describe [])
            closed (weaver/op! rt 'gate.close.v1 [(json-arg {:task "T-1"})])
            _ (t/check-op-return! rt 'guild.describe description)
            _ (t/check-op-return! rt 'gate.close.v1 closed)
            checked #{["guild.describe" {}] ["gate.close.v1" {}]}]
        (is (= [] missing))
        (is (= #{} (set/difference required checked)))))))

(deftest defop-registers-and-invokes-through-op-registry
  (with-runtime
    (fn [rt _]
      (guild/install!)
      (guild/defop! 'gate.close.v1 {:doc "Close a peer gate" :spec ::close-input :returns close-return}
        'skein.guild-test/close-handler)
      (is (= {:op "gate.close.v1" :input {:task "T-1"}}
             (weaver/op! rt 'gate.close.v1 [(json-arg {:task "T-1"})])))
      (guild/defop! 'ping.v1 {:doc "Ping" :returns close-return} 'skein.guild-test/close-handler)
      (is (= {:op "ping.v1" :input {}}
             (weaver/op! rt 'ping.v1 []))))))

(deftest spec-invalid-input-fails-loudly-with-structured-data
  (with-runtime
    (fn [rt _]
      (guild/install!)
      (guild/defop! 'gate.close.v1 {:doc "Close a peer gate" :spec ::close-input :returns close-return}
        'skein.guild-test/close-handler)
      (try
        (weaver/op! rt 'gate.close.v1 [(json-arg {:wrong "x"})])
        (is false "expected spec validation failure")
        (catch clojure.lang.ExceptionInfo e
          (is (= :op/input-invalid (:code (ex-data e))))
          (is (= "gate.close.v1" (:op (ex-data e))))
          (is (= ":skein.guild-test/close-input" (:spec (ex-data e)))))))))

(deftest describe-lists-active-and-deprecated-ops
  (with-runtime
    (fn [rt _]
      (guild/install! "fallback-guild")
      (is (= "fallback-guild" (:guild (guild/describe-op {:op/runtime rt}))))
      (guild/defop! 'gate.close.v1 {:doc "Close v1" :spec ::close-input :returns close-return}
        'skein.guild-test/close-handler)
      (guild/defop! 'gate.close.v2 {:doc "Close v2" :spec ::close-input :returns close-return}
        'skein.guild-test/close-handler)
      (guild/deprecate! 'gate.close.v1 {:replacement "gate.close.v2" :since "2026-07-02"})
      (let [description (weaver/op! rt 'guild.describe [])]
        (is (string? (:guild description)))
        (is (= [{:name "gate.close.v2"
                 :doc "Close v2"
                 :spec ":skein.guild-test/close-input"}]
               (:active description)))
        (is (= [{:name "gate.close.v1"
                 :replacement "gate.close.v2"
                 :doc "Close v1"
                 :since "2026-07-02"}]
               (:deprecated description)))))))

(deftest deprecated-op-throws-structured-error-and-never-succeeds
  (with-runtime
    (fn [rt _]
      (guild/install!)
      (guild/defop! 'gate.close.v1 {:doc "Close v1" :returns close-return}
        'skein.guild-test/close-handler)
      (guild/deprecate! 'gate.close.v1 {:replacement "gate.close.v2"})
      (try
        (weaver/op! rt 'gate.close.v1 [(json-arg {:task "T-1"})])
        (is false "deprecated op must not succeed")
        (catch clojure.lang.ExceptionInfo e
          (is (= {:code :op/deprecated
                  :op "gate.close.v1"
                  :replacement "gate.close.v2"}
                 (ex-data e))))))))

(deftest malformed-guild-declarations-fail-loudly
  (with-runtime
    (fn [rt _]
      (guild/install!)
      (testing "unknown defop opts"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown guild option keys"
                              (guild/defop! 'gate.close.v1 {:doc "x" :extra true}
                                'skein.guild-test/close-handler))))
      (testing "namespaced registry names are rejected by the public registry"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"simple symbols or keywords"
                              (guild/defop! 'gate/close.v1 {:doc "x"}
                                'skein.guild-test/close-handler))))
      (testing "unqualified handlers fail before registration"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified"
                              (guild/defop! 'bad.handler.v1 {:doc "x"} 'close-handler))))
      (testing "unresolved handlers fail before public registration"
        (is (thrown? java.io.FileNotFoundException
                     (guild/defop! 'bad.resolve.v1 {:doc "x"} 'missing.guild/handler)))
        (is (not-any? #(= "bad.resolve.v1" (:name %)) (weaver/ops rt))))
      (testing "deprecating an unregistered op fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not registered"
                              (guild/deprecate! 'missing.v1 {:replacement "missing.v2"})))))))
