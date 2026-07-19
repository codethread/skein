(ns skein.api.graph.alpha-test
  "Request-context arity and lenient-adjacency coverage for the graph API.

  The broad graph behavior lock — registry validation, query selection,
  hydration order, traversal, and burn event fanout — lives in
  `skein.weaver-test` and `skein.core.db-test`; this namespace pins the
  explicit caller-supplied request-context arity of `burn-by-ids!` and the
  lenient adjacency promise at the public surface."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [skein.api.graph.alpha :as graph]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.weaver.alpha :as weaver]
            [skein.test.alpha :as t]))

;; Namespace-level on purpose: hooks are registered by symbol and resolved to
;; top-level vars, so capture state cannot be a per-test local.
(def captured-contexts (atom []))

(use-fixtures :each (fn [f] (reset! captured-contexts []) (f)))

(defn capture-hook
  "Validation hook that records its context and approves the burn."
  [ctx]
  (swap! captured-contexts conj ctx)
  :ok)

(deftest burn-threads-a-caller-request-context-into-the-validation-gate
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (hooks/register-hook! rt :capture #{:strand/burn-before-commit}
                            'skein.api.graph.alpha-test/capture-hook {})
      (let [doomed (weaver/add! rt {:title "Doomed"})
            result (graph/burn-by-ids! rt [(:id doomed)]
                                       {:request/source :nrepl
                                        :request/operation :burn-batch})
            context (last @captured-contexts)]
        (is (= {:burned [(:id doomed)] :count 1} result))
        (is (= :nrepl (:request/source context)))
        (is (= :burn-batch (:request/operation context)))
        (is (= :strand/burn (:mutation/operation context)))
        (is (= [(:id doomed)] (:strand/requested-ids context)))))))

(deftest edge-adjacency-is-lenient-for-absent-ids
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          parent (weaver/add! rt {:title "Parent"})
          child (weaver/add! rt {:title "Child"})]
      (weaver/update! rt (:id parent) {:edges [{:type "parent-of" :to (:id child)}]})
      (is (= [(:id parent)]
             (mapv :from_strand_id (graph/incoming-edges rt [(:id child)] "parent-of"))))
      (is (= [(:id child)]
             (mapv :to_strand_id (graph/outgoing-edges rt [(:id parent)] "parent-of"))))
      (is (empty? (graph/incoming-edges rt ["no-such-id"] "parent-of")))
      (is (empty? (graph/outgoing-edges rt ["no-such-id"] "parent-of"))))))
