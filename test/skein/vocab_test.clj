(ns skein.vocab-test
  "Tests for the skein.api.vocab.alpha vocabulary registry: the core seed lives
  in the `new-state` init-fn (present on a fresh runtime, no `install!`),
  `declare!` is a single-owner hard edge with same-owner idempotency, and the
  reads are runtime-first, sorted, and narrowable."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.api.relations.alpha :as relations]
            [skein.api.vocab.alpha :as vocab]
            [skein.spools.test-support :refer [assert-state-shape with-runtime]]))

(defn- attr-decl [name owner]
  {:kind :attr-namespace :name name :owner owner :doc (str name " attributes")})

(deftest fresh-runtime-carries-core-seed
  (testing "the reflected edge catalog and core note/* are present before any install!"
    (with-runtime
      (fn [rt _]
        (let [edges (vocab/declarations rt {:kind :edge})]
          (is (= (set (map :relation relations/catalog))
                 (set (map :name edges)))
              "one owned :edge declaration per catalog entry")
          (is (every? #(= :skein/core (:owner %)) edges))
          (let [depends (vocab/declaration rt :edge "depends-on")]
            (is (= {:kind :edge :name "depends-on" :owner :skein/core
                    :family :operational
                    :direction "blocked --depends-on--> blocker"
                    :declared-acyclic? true}
                   (dissoc depends :doc))
                "edge declaration reflects catalog family/direction/acyclicity")))
        (let [note (vocab/declaration rt :attr-namespace "note")]
          (is (= 'skein.api.notes.alpha (:owner note)))
          (is (= :attr-namespace (:kind note))))))))

(deftest declare-and-read-round-trip
  (with-runtime
    (fn [rt _]
      (let [decl (attr-decl "widget" :my.spool/init)]
        (is (= decl (vocab/declare! rt decl)) "declare! returns the recorded map")
        (is (= decl (vocab/declaration rt :attr-namespace "widget")))
        (is (nil? (vocab/declaration rt :attr-namespace "undeclared")))
        (is (contains? (set (vocab/declarations rt)) decl))
        (testing "declarations are sorted by [:kind :name]"
          (let [all (vocab/declarations rt)]
            (is (= all (sort-by (juxt :kind :name) all)))))
        (testing "narrowing by kind"
          (is (every? #(= :attr-namespace (:kind %))
                      (vocab/declarations rt {:kind :attr-namespace}))))))))

(deftest declare-rejects-invalid-shapes
  (with-runtime
    (fn [rt _]
      (testing "unknown kind"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                              (vocab/declare! rt {:kind :bogus :name "x" :owner :o :doc "d"}))))
      (testing "unknown keys"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (vocab/declare! rt (assoc (attr-decl "x" :o) :extra true)))))
      (testing "missing required keys"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required keys"
                              (vocab/declare! rt {:kind :attr-namespace :name "x"}))))
      (testing "unknown read opt key"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (vocab/declarations rt {:bogus true})))))))

(deftest declare-is-single-owner-hard-edge
  (with-runtime
    (fn [rt _]
      (vocab/declare! rt (attr-decl "widget" :owner-a))
      (testing "different owner throws with attribution"
        (let [ex (try (vocab/declare! rt (attr-decl "widget" :owner-b))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= {:name "widget" :kind :attr-namespace
                  :existing-owner :owner-a :declaring-owner :owner-b}
                 (ex-data ex)))))
      (testing "same owner is an idempotent replace (the reload invariant)"
        (let [replaced (assoc (attr-decl "widget" :owner-a) :doc "revised")]
          (is (= replaced (vocab/declare! rt replaced)))
          (is (= "revised" (:doc (vocab/declaration rt :attr-namespace "widget")))))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for vocab's versioned spool-state: a key added to new-state
  ;; without a state-version bump would survive reload! as a stale map.
  (assert-state-shape #'vocab/new-state #{:registry}))
