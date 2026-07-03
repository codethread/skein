(ns skein.core.db-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [skein.core.db :as db]
            [skein.core.query :as query]
            [skein.core.specs :as specs]))

(defn delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn temp-db-file []
  (let [file (java.io.File/createTempFile "skein-db-test" ".sqlite")]
    (.delete file)
    (.getAbsolutePath file)))

(defn with-db [f]
  (let [db-file (temp-db-file)
        ds (db/datasource db-file)]
    (try
      (db/init! ds)
      (f ds)
      (finally
        (delete-sqlite-family! db-file)))))

(deftest init-creates-strand-schema
  (with-db
    (fn [ds]
      (is (= #{"id" "title" "state" "attributes" "created_at" "updated_at"}
             (set (map :name (db/execute! ds ["PRAGMA table_info(strands)"])))))
      (is (= #{"from_strand_id" "to_strand_id" "edge_type" "attributes"}
             (set (map :name (db/execute! ds ["PRAGMA table_info(strand_edges)"])))))
      (is (= #{"depends-on" "parent-of" "supersedes"}
             (set (db/list-acyclic-relations ds))))
      (is (empty? (db/execute! ds ["SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('tasks', 'task_edges')"]))))))

(deftest strand-creation-and-validation
  (with-db
    (fn [ds]
      (let [strand (-> (db/add-strand! ds {:title "Sketch model" :attributes {:priority "high"}})
                       (update :attributes db/<-json))]
        (is (re-matches #"[a-z0-9]+" (:id strand)))
        (is (= {:title "Sketch model" :state "active" :attributes {:priority "high"}}
               (select-keys strand [:title :state :attributes]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                            (db/add-strand! ds {:title "Old" :status "todo"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                            (db/add-strand! ds {:title "Old" :final_at "now"})))
      (is (s/valid? ::specs/state "replaced"))
      (is (not (s/valid? ::specs/strand-input {:title "Bad" :state "replaced"})))
      (is (not (s/valid? ::specs/strand-input {:title "Bad" :attributes {:owner (Object.)}})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid strand"
                            (db/add-strand! ds {:title "Bad" :state "true"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid strand"
                            (db/add-strand! ds {:title "Bad" :state "replaced"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown core strand fields"
                            (db/add-strand! ds {:title "Bad" :priority "high"}))))))

(deftest active-readiness-and-reactivation
  (with-db
    (fn [ds]
      (let [design (:id (db/add-strand! ds {:title "Design" :state "closed"}))
            schema (:id (db/add-strand! ds {:title "Schema"}))
            docs (:id (db/add-strand! ds {:title "Docs"}))]
        (db/add-edge! ds {:from schema :to design :type "depends-on" :attributes {}})
        (db/add-edge! ds {:from docs :to schema :type "depends-on" :attributes {}})
        (is (= [schema] (mapv :id (db/ready-strands ds))))
        (let [closed (db/update-strand! ds schema {:state "closed"})]
          (is (= "closed" (:state closed))))
        (is (= [docs] (mapv :id (db/ready-strands ds))))
        (let [reactivated (db/update-strand! ds schema {:state "active"})]
          (is (= "active" (:state reactivated))))
        (is (= [schema] (mapv :id (db/ready-strands ds))))))))

(deftest removed-lifecycle-fields-are-rejected
  (with-db
    (fn [ds]
      (let [strand (:id (db/add-strand! ds {:title "Strand"}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown core strand fields"
                              (db/update-strand! ds strand {:priority "high"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                              (db/update-strand! ds strand {:final_at "now"})))))))

(deftest burn-deletes-strands-and-incident-edges
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {}})
        (db/add-edge! ds {:from c :to b :type "related-to" :attributes {}})
        (is (= {:burned [b] :count 1} (db/burn-by-id! ds b)))
        (is (nil? (db/get-strand ds b)))
        (is (= #{a c} (set (map :id (db/all-strands ds)))))
        (is (empty? (db/execute! ds ["SELECT 1 FROM strand_edges WHERE from_strand_id = ? OR to_strand_id = ?" b b])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Strand ids not found"
                              (db/burn-by-ids! ds [b])))))))

(deftest query-fields-use-state-and-attributes
  (with-db
    (fn [ds]
      (let [agent (:id (db/add-strand! ds {:title "Agent" :attributes {:owner "agent"}}))
            _human (db/add-strand! ds {:title "Human" :attributes {:owner "human"}})
            scratch (:id (db/add-strand! ds {:title "Scratch" :attributes {:kind "scratch"}}))]
        (is (= [agent]
               (mapv :id (db/all-strands ds [:and [:= :state "active"] [:= [:attr :owner] "agent"]]))))
        (is (= [scratch] (mapv :id (db/all-strands ds [:= [:attr :kind] "scratch"]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query field"
                              (query/compile-query [:= :kind "scratch"] {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query field"
                              (query/compile-query [:= :status "todo"] {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query field"
                              (query/compile-query [:exists :final_at] {})))))))

(deftest edge-query-predicates-and-ready-semantics
  (with-db
    (fn [ds]
      (let [active-blocker (:id (db/add-strand! ds {:title "Active blocker"}))
            closed-blocker (:id (db/add-strand! ds {:title "Closed blocker" :state "closed"}))
            blocked (:id (db/add-strand! ds {:title "Blocked" :attributes {:owner "agent"}}))
            ready (:id (db/add-strand! ds {:title "Ready" :attributes {:owner "agent"}}))]
        (db/add-edge! ds {:from blocked :to active-blocker :type "depends-on" :attributes {}})
        (db/add-edge! ds {:from ready :to closed-blocker :type "depends-on" :attributes {}})
        (is (= #{blocked ready}
               (set (mapv :id (db/all-strands ds [:edge/out "depends-on" [:exists :id]])))))
        (is (= #{active-blocker closed-blocker}
               (set (mapv :id (db/all-strands ds [:edge/in [:param :relation] [:= [:attr :owner] "agent"]]
                                               {:relation "depends-on"})))))
        (is (= #{active-blocker ready} (set (mapv :id (db/ready-strands ds)))))
        (is (= (set (mapv :id (db/ready-strands ds)))
               (set (mapv :id (db/all-strands ds [:and
                                                  [:= :state "active"]
                                                  [:not [:edge/out "depends-on" [:= :state "active"]]]])))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nested edge predicates"
                              (query/compile-query [:edge/out "depends-on" [:edge/in "depends-on" [:= :state "active"]]] {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"valid relation name"
                              (query/compile-query [:edge/out [:param :relation] [:exists :id]] {:relation "Bad Relation"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"valid relation name"
                              (query/compile-query [:edge/out [:param :relation] [:exists :id]] {:relation ["__skein_query_param__"]})))))))

(deftest graph-primitives-use-strand-edge-columns
  (with-db
    (fn [ds]
      (let [root (:id (db/add-strand! ds {:title "Root"}))
            child (:id (db/add-strand! ds {:title "Child"}))
            custom-root (:id (db/add-strand! ds {:title "Custom root" :attributes {:kind "root"}}))
            custom-child (:id (db/add-strand! ds {:title "Custom child" :attributes {:kind "leaf"}}))]
        (db/add-edge! ds {:from root :to child :type "parent-of" :attributes {}})
        (db/declare-acyclic-relation! ds "blocks")
        (db/add-edge! ds {:from custom-root :to custom-child :type "blocks" :attributes {}})
        (db/add-edge! ds {:from child :to custom-child :type "related-to" :attributes {}})
        (is (= (sort [root child]) (mapv :id (:strands (db/subgraph ds [root])))))
        (is (= [custom-root] (db/ancestor-root-ids ds [custom-child] {:type "blocks"})))
        (is (= [custom-root]
               (db/ancestor-root-ids ds [custom-child] {:type "blocks"
                                                        :where [:= [:attr :kind] [:param :kind]]
                                                        :params {:kind "root"}})))
        (is (= []
               (db/ancestor-root-ids ds [custom-child] {:where [:= [:attr :kind] [:param :kind]]
                                                        :params {:kind "root"}})))
        (is (= (sort [custom-root custom-child])
               (mapv :id (:strands (db/subgraph ds [custom-root] {:type "blocks"})))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declared acyclic relation"
                              (db/subgraph ds [child] {:type "related-to"})))
        (let [edges (:edges (db/subgraph ds [root]))]
          (is (= [[root child "parent-of"]]
                 (mapv (juxt :from_strand_id :to_strand_id :edge_type) edges)))
          (is (not-any? #(contains? % :from_task_id) edges))
          (is (not-any? #(contains? % :to_task_id) edges)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"same strand"
                              (db/add-edge! ds {:from root :to root :type "related-to" :attributes {}})))))))

(deftest relation-declarations-and-scoped-cycle-checks
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (testing "arbitrary valid annotation relation names store and may cycle"
          (db/add-edge! ds {:from a :to b :type "related-to" :attributes {}})
          (db/add-edge! ds {:from b :to a :type "related-to" :attributes {}})
          (db/add-edge! ds {:from a :to c :type "agent.notes/v1" :attributes {}})
          (is (= #{"agent.notes/v1" "related-to"}
                 (set (map :edge_type (db/execute! ds ["SELECT edge_type FROM strand_edges WHERE edge_type IN ('related-to', 'agent.notes/v1')"]))))))
        (testing "declared acyclic relations reject relation-local cycles"
          (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {}})
          (db/add-edge! ds {:from b :to c :type "depends-on" :attributes {}})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (db/add-edge! ds {:from c :to a :type "depends-on" :attributes {}}))))
        (testing "invalid edge endpoints fail before storage"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Strand ids not found"
                                (db/add-edge! ds {:from "missing-from" :to b :type "related-to" :attributes {}})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Strand ids not found"
                                (db/add-edge! ds {:from a :to "missing-to" :type "related-to" :attributes {}})))
          (is (empty? (db/execute! ds ["SELECT 1 FROM strand_edges WHERE from_strand_id LIKE 'missing%' OR to_strand_id LIKE 'missing%'"]))))
        (testing "invalid relation names fail at storage boundaries"
          (doseq [relation ["" "Related-To" ".hidden" "has space" ":keyword" 42]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid edge"
                                  (db/add-edge! ds {:from a :to b :type relation :attributes {}})))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"valid relation name"
                                  (db/declare-acyclic-relation! ds relation)))))
        (testing "custom declarations are idempotent and declare-before-use"
          (is (= {:relation "blocks/v2" :acyclic true}
                 (db/declare-acyclic-relation! ds "blocks/v2")))
          (is (= {:relation "blocks/v2" :acyclic true}
                 (db/declare-acyclic-relation! ds "blocks/v2")))
          (db/add-edge! ds {:from a :to b :type "blocks/v2" :attributes {}})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (db/add-edge! ds {:from b :to a :type "blocks/v2" :attributes {}})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"after edges"
                                (db/declare-acyclic-relation! ds "related-to"))))))))

(deftest init-rejects-old-closed-edge-schema
  (let [db-file (temp-db-file)
        ds (db/datasource db-file)]
    (try
      (db/execute! ds ["CREATE TABLE strands (
                         id TEXT PRIMARY KEY,
                         title TEXT NOT NULL,
                         state TEXT NOT NULL DEFAULT 'active',
                         attributes TEXT NOT NULL DEFAULT '{}',
                         created_at TEXT NOT NULL DEFAULT (datetime('now')),
                         updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                       )"])
      (db/execute! ds ["CREATE TABLE strand_edges (
                         from_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                         to_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                         edge_type TEXT NOT NULL,
                         attributes TEXT NOT NULL DEFAULT '{}',
                         PRIMARY KEY (from_strand_id, to_strand_id, edge_type),
                         CHECK (edge_type IN ('depends-on', 'related-to', 'parent-of', 'supersedes')),
                         CHECK (json_valid(attributes))
                       )"])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strand_edges table is not compatible"
                            (db/init! ds)))
      (finally
        (delete-sqlite-family! db-file)))))

(declare edge-rows graph-snapshot)

(deftest batch-creation-uses-strand-lifecycle
  (with-db
    (fn [ds]
      (let [result (db/add-strand-batch! ds [{:ref 'design :title "Design" :state "closed"}
                                             {:ref 'docs :title "Docs" :edges [{:type "depends-on" :to 'design}]}])
            refs (:refs result)]
        (is (= #{:created :refs} (set (keys result))))
        (is (= #{"design" "docs"} (set (keys refs))))
        (is (= "closed" (:state (db/get-strand ds (get refs "design")))))
        (is (= [{:to_strand_id (get refs "design") :edge_type "depends-on"}]
               (db/execute! ds ["SELECT to_strand_id, edge_type FROM strand_edges WHERE from_strand_id = ?" (get refs "docs")])))))))

(deftest supersession-transaction-replaces-and-rewires
  (with-db
    (fn [ds]
      (let [old (:id (db/add-strand! ds {:title "Old"}))
            replacement (:id (db/add-strand! ds {:title "Replacement"}))
            active-dependent (:id (db/add-strand! ds {:title "Active dependent"}))
            closed-dependent (:id (db/add-strand! ds {:title "Closed dependent" :state "closed"}))
            already-linked (:id (db/add-strand! ds {:title "Already linked"}))]
        (db/add-edge! ds {:from active-dependent :to old :type "depends-on" :attributes {:reason "active"}})
        (db/add-edge! ds {:from closed-dependent :to old :type "depends-on" :attributes {:reason "closed"}})
        (db/add-edge! ds {:from already-linked :to old :type "depends-on" :attributes {:reason "old"}})
        (db/add-edge! ds {:from already-linked :to replacement :type "depends-on" :attributes {:reason "existing"}})
        (let [result (db/supersede-strand! ds old replacement)]
          (is (= "active" (get-in result [:old :before :state])))
          (is (= "replaced" (get-in result [:old :after :state])))
          (is (= replacement (:replacement-id result)))
          (is (= [replacement old "supersedes"]
                 ((juxt :from_strand_id :to_strand_id :edge_type) (:supersedes-edge result))))
          (is (= #{active-dependent closed-dependent already-linked}
                 (set (map :from (:rewired-dependencies result)))))
          (is (= #{[replacement old "supersedes"]
                   [active-dependent replacement "depends-on"]
                   [closed-dependent replacement "depends-on"]
                   [already-linked replacement "depends-on"]}
                 (set (map (juxt :from_strand_id :to_strand_id :edge_type) (edge-rows ds)))))
          (is (= {:reason "existing"}
                 (:attributes (first (filter #(= already-linked (:from_strand_id %)) (edge-rows ds))))))
          (is (= [old]
                 (mapv :id (db/all-strands ds [:edge/in "supersedes" [:= :id replacement]]))))
          (is (nil? (some #(= "replaced_by" (:name %))
                          (db/execute! ds ["PRAGMA table_info(strands)"])))))))))

(deftest supersession-missing-id-failures-roll-back
  (with-db
    (fn [ds]
      (let [old (:id (db/add-strand! ds {:title "Old"}))
            replacement (:id (db/add-strand! ds {:title "Replacement"}))]
        (doseq [[message f] [[#"Old strand not found" #(db/supersede-strand! ds "missing" replacement)]
                             [#"Replacement strand not found" #(db/supersede-strand! ds old "missing")]]]
          (let [before (graph-snapshot ds)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo message (f)))
            (is (= before (graph-snapshot ds)))))))))

(deftest supersession-validation-failures-roll-back
  (with-db
    (fn [ds]
      (let [old (:id (db/add-strand! ds {:title "Old"}))
            replacement (:id (db/add-strand! ds {:title "Replacement"}))
            closed-replacement (:id (db/add-strand! ds {:title "Closed replacement" :state "closed"}))
            dependent (:id (db/add-strand! ds {:title "Dependent"}))]
        (db/add-edge! ds {:from dependent :to old :type "depends-on" :attributes {}})
        (db/supersede-strand! ds old replacement)
        (let [before (graph-snapshot ds)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already replaced"
                                (db/supersede-strand! ds old replacement)))
          (is (= before (graph-snapshot ds))))
        (doseq [[message f] [[#"Replacement strand must be active" #(db/supersede-strand! ds replacement closed-replacement)]
                             [#"cannot supersede itself" #(db/supersede-strand! ds replacement replacement)]]]
          (let [before (graph-snapshot ds)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo message (f)))
            (is (= before (graph-snapshot ds)))))))))

(deftest supersession-lineage-and-dependency-cycles-roll-back
  (with-db
    (fn [ds]
      (let [old (:id (db/add-strand! ds {:title "Old"}))
            replacement (:id (db/add-strand! ds {:title "Replacement"}))]
        (db/add-edge! ds {:from old :to replacement :type "supersedes" :attributes {}})
        (let [before (graph-snapshot ds)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (db/supersede-strand! ds old replacement)))
          (is (= before (graph-snapshot ds)))))))
  (with-db
    (fn [ds]
      (let [old (:id (db/add-strand! ds {:title "Old"}))
            replacement (:id (db/add-strand! ds {:title "Replacement"}))
            dependent (:id (db/add-strand! ds {:title "Dependent"}))]
        (db/add-edge! ds {:from dependent :to old :type "depends-on" :attributes {}})
        (db/add-edge! ds {:from replacement :to dependent :type "depends-on" :attributes {}})
        (let [before (graph-snapshot ds)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (db/supersede-strand! ds old replacement)))
          (is (= before (graph-snapshot ds))))))))

(defn edge-rows [ds]
  (mapv #(update % :attributes db/<-json)
        (db/execute! ds ["SELECT from_strand_id, to_strand_id, edge_type, attributes FROM strand_edges ORDER BY from_strand_id, to_strand_id, edge_type"])))

(defn graph-snapshot [ds]
  {:strands (mapv #(update % :attributes db/<-json) (db/all-strands ds))
   :edges (edge-rows ds)})

(defn assert-batch-fails-without-mutation [ds message-re payload]
  (let [before (graph-snapshot ds)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo message-re
                          (db/apply-batch! ds payload)))
    (is (= before (graph-snapshot ds)))))

(deftest apply-batch-happy-path-mutates-graph-and-returns-final-refs
  (with-db
    (fn [ds]
      (let [old-doc (db/add-strand! ds {:title "Old doc" :attributes {:state "draft"}})
            old-design (db/add-strand! ds {:title "Old design"})
            dependency (db/add-strand! ds {:title "Dependency"})
            result (db/apply-batch! ds {:refs {:old-doc (:id old-doc)
                                               :old-design (:id old-design)
                                               :dependency (:id dependency)}
                                        :strands [{:ref :old-doc
                                                   :state "closed"
                                                   :attributes {:state "superseded"}}
                                                  {:ref :new-doc
                                                   :title "New doc"
                                                   :attributes {:kind "doc"}}]
                                        :edges [{:op :upsert
                                                 :from :new-doc
                                                 :to :dependency
                                                 :type "depends-on"
                                                 :attributes {:reason "needed"}}]
                                        :burn [:old-design]})
            new-doc-id (get-in result [:refs :new-doc])]
        (is (= {:old-doc (:id old-doc)
                :old-design (:id old-design)
                :dependency (:id dependency)
                :new-doc new-doc-id}
               (:refs result)))
        (is (string? new-doc-id))
        (is (= #{(:id old-doc) (:id dependency) new-doc-id}
               (set (map :id (db/all-strands ds)))))
        (is (= {:title "Old doc" :state "closed" :attributes {:state "superseded"}}
               (select-keys (update (db/get-strand ds (:id old-doc)) :attributes db/<-json)
                            [:title :state :attributes])))
        (is (= [{:from_strand_id new-doc-id
                 :to_strand_id (:id dependency)
                 :edge_type "depends-on"
                 :attributes {:reason "needed"}}]
               (edge-rows ds)))))))

(deftest apply-batch-validates-shape-without-mutation
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))]
        (doseq [[message payload]
                [[#"Unknown keys" {:refs {:a a} :surprise true}]
                 [#"must be a map" {:refs []}]
                 [#"must be a vector" {:refs {:a a} :strands {}}]
                 [#"must be a vector" {:refs {:a a} :edges {}}]
                 [#"must be a vector" {:refs {:a a} :burn {}}]
                 [#"Strand ids not found" {:refs {:missing "nope"}}]
                 [#"Batch refs" {:refs {(keyword "") a}}]]]
          (assert-batch-fails-without-mutation ds message payload))))))

(deftest apply-batch-validates-ref-bindings-without-mutation
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))]
        (doseq [[message payload]
                [[#"Multiple batch refs" {:refs {:a a :b a}}]
                 [#"Batch refs" {:refs {"a" a}}]
                 [#"Batch refs" {:refs {:ns/a a}}]
                 [#"Batch refs" {:refs {:a a} :edges [{:op :upsert :from :a :to "b" :type "depends-on"}]}]
                 [#"Batch refs" {:refs {:a a} :edges [{:op :upsert :from :a :to :ns/b :type "depends-on"}]}]
                 [#"Batch refs" {:refs {:a a} :burn ["b"]}]
                 [#"Batch refs" {:refs {:a a} :burn [:ns/b]}]
                 [#"unknown ref" {:refs {:a a} :edges [{:op :upsert :from :a :to :missing :type "depends-on"}]}]
                 [#"existing bound refs" {:refs {:a a} :burn [:missing]}]]]
          (assert-batch-fails-without-mutation ds message payload))))))

(deftest apply-batch-validates-strand-and-burn-conflicts-without-mutation
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))]
        (doseq [[message payload]
                [[#"requires a non-blank" {:strands [{:ref :new}]}]
                 [#"Duplicate batch strand ref" {:strands [{:ref :new :title "One"} {:ref :new :title "Two"}]}]
                 [#"Lifecycle state must be active or closed" {:strands [{:ref :new :title "New" :state "replaced"}]}]
                 [#"Removed lifecycle fields are not core strand fields" {:strands [{:ref :new :title "New" :active true}]}]
                 [#"existing bound refs" {:strands [{:ref :new :title "New"}] :burn [:new]}]
                 [#"both mutated and burned" {:refs {:a a} :strands [{:ref :a :title "Changed"}] :burn [:a]}]]]
          (assert-batch-fails-without-mutation ds message payload))))))

(deftest apply-batch-validates-edge-behavior-and-replaces-attributes
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/add-edge! ds {:from b :to c :type "depends-on" :attributes {}})
        (assert-batch-fails-without-mutation ds #"Unsupported batch edge operation"
                                             {:refs {:a a :b b} :edges [{:op :delete :from :a :to :b :type "depends-on"}]})
        (assert-batch-fails-without-mutation ds #"burned ref"
                                             {:refs {:a a :b b} :burn [:b]
                                              :edges [{:op :upsert :from :a :to :b :type "depends-on"}]})
        (assert-batch-fails-without-mutation ds #"create a cycle"
                                             {:refs {:a a :b b :c c}
                                              :edges [{:op :upsert :from :c :to :b :type "depends-on"}]})
        (assert-batch-fails-without-mutation ds #"valid relation name"
                                             {:refs {:a a :b b}
                                              :edges [{:op :upsert :from :a :to :b :type "Blocks"}]})
        (db/apply-batch! ds {:refs {:a a :b b}
                             :edges [{:op :upsert :from :a :to :b :type "references"}
                                     {:op :upsert :from :b :to :a :type "references"}]})
        (is (= 2 (count (filter #(= "references" (:edge_type %)) (edge-rows ds)))))
        (db/add-edge! ds {:from a :to b :type "related-to" :attributes {:old true}})
        (db/apply-batch! ds {:refs {:a a :b b}
                             :edges [{:op :upsert :from :a :to :b :type "related-to" :attributes {:new true}}]})
        (is (= {:new true}
               (-> (db/execute-one! ds ["SELECT attributes FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = 'related-to'" a b])
                   :attributes
                   db/<-json)))))))

(deftest apply-batch-rolls-back-earlier-valid-mutations
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/add-edge! ds {:from b :to c :type "depends-on" :attributes {}})
        (assert-batch-fails-without-mutation ds #"create a cycle"
                                             {:refs {:a a :b b :c c}
                                              :strands [{:ref :a :title "Changed before failure"}
                                                        {:ref :new :title "Created before failure"}]
                                              :edges [{:op :upsert :from :c :to :b :type "depends-on"}]})))))

(deftest memory-storage-runs-schema-crud-and-transactions-on-held-connection
  (let [{:keys [connectable close-fn] :as storage} (db/memory-storage)]
    (is (= :sqlite-memory (:storage-kind storage)))
    (is (nil? (:canonical-db-path storage)))
    (try
      (db/init! connectable)
      (let [strand (db/add-strand! connectable {:title "Mem"})]
        (is (= "Mem" (:title (db/get-strand connectable (:id strand))))))
      (testing "with-transaction rolls back on failure"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                              (jdbc/with-transaction [tx connectable]
                                (db/add-strand! tx {:title "Rolled back"})
                                (throw (ex-info "boom" {})))))
        (is (= ["Mem"] (mapv :title (db/all-strands connectable)))))
      (finally
        (close-fn)))
    (testing "use after close fails loudly"
      (is (thrown? java.sql.SQLException (db/all-strands connectable))))))
