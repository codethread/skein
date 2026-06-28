(ns skein.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [skein.db :as db]
            [skein.query :as query]))

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
      (is (= #{"id" "title" "active" "attributes" "created_at" "updated_at" "inactive_at"}
             (set (map :name (db/execute! ds ["PRAGMA table_info(strands)"])))))
      (is (= #{"from_strand_id" "to_strand_id" "edge_type" "attributes"}
             (set (map :name (db/execute! ds ["PRAGMA table_info(strand_edges)"])))))
      (is (empty? (db/execute! ds ["SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('tasks', 'task_edges')"]))))))

(deftest strand-creation-and-validation
  (with-db
    (fn [ds]
      (let [strand (-> (db/add-strand! ds {:title "Sketch model" :attributes {:priority "high"}})
                       (update :attributes db/<-json))]
        (is (re-matches #"[a-z0-9]+" (:id strand)))
        (is (= {:title "Sketch model" :active true :attributes {:priority "high"} :inactive_at nil}
               (select-keys strand [:title :active :attributes :inactive_at]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                            (db/add-strand! ds {:title "Old" :status "todo"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                            (db/add-strand! ds {:title "Old" :final_at "now"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid strand"
                            (db/add-strand! ds {:title "Bad" :active "true"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown core strand fields"
                            (db/add-strand! ds {:title "Bad" :priority "high"}))))))

(deftest active-readiness-and-reactivation
  (with-db
    (fn [ds]
      (let [design (:id (db/add-strand! ds {:title "Design" :active false}))
            schema (:id (db/add-strand! ds {:title "Schema"}))
            docs (:id (db/add-strand! ds {:title "Docs"}))]
        (db/add-edge! ds {:from schema :to design :type "depends-on" :attributes {}})
        (db/add-edge! ds {:from docs :to schema :type "depends-on" :attributes {}})
        (is (= [schema] (mapv :id (db/ready-strands ds))))
        (let [inactive (db/update-strand! ds schema {:active false})]
          (is (false? (:active inactive)))
          (is (some? (:inactive_at inactive))))
        (is (= [docs] (mapv :id (db/ready-strands ds))))
        (let [reactivated (db/update-strand! ds schema {:active true})]
          (is (true? (:active reactivated)))
          (is (nil? (:inactive_at reactivated))))
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

(deftest query-fields-use-active-inactive-at-and-attributes
  (with-db
    (fn [ds]
      (let [agent (:id (db/add-strand! ds {:title "Agent" :attributes {:owner "agent"}}))
            _human (db/add-strand! ds {:title "Human" :attributes {:owner "human"}})
            scratch (:id (db/add-strand! ds {:title "Scratch" :attributes {:kind "scratch"}}))]
        (is (= [agent]
               (mapv :id (db/all-strands ds [:and [:= :active true] [:= [:attr :owner] "agent"]]))))
        (is (= [scratch] (mapv :id (db/all-strands ds [:= [:attr :kind] "scratch"]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query field"
                              (query/compile-query [:= :kind "scratch"] {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query field"
                              (query/compile-query [:= :status "todo"] {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query field"
                              (query/compile-query [:exists :final_at] {})))))))

(deftest graph-primitives-use-strand-edge-columns
  (with-db
    (fn [ds]
      (let [root (:id (db/add-strand! ds {:title "Root"}))
            child (:id (db/add-strand! ds {:title "Child"}))]
        (db/add-edge! ds {:from root :to child :type "parent-of" :attributes {}})
        (is (= (sort [root child]) (mapv :id (:strands (db/subgraph ds [root])))))
        (let [edges (:edges (db/subgraph ds [root]))]
          (is (= [[root child "parent-of"]]
                 (mapv (juxt :from_strand_id :to_strand_id :edge_type) edges)))
          (is (not-any? #(contains? % :from_task_id) edges))
          (is (not-any? #(contains? % :to_task_id) edges)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"same strand"
                              (db/add-edge! ds {:from root :to root :type "related-to" :attributes {}})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                              (db/add-edge! ds {:from child :to root :type "related-to" :attributes {}})))))))

(deftest batch-creation-uses-strand-lifecycle
  (with-db
    (fn [ds]
      (let [result (db/add-strand-batch! ds [{:ref 'design :title "Design" :active false}
                                             {:ref 'docs :title "Docs" :edges [{:type "depends-on" :to 'design}]}])
            refs (:refs result)]
        (is (= #{{"design" (get refs "design")} {"docs" (get refs "docs")}}
               #{{"design" (get refs "design")} {"docs" (get refs "docs")}}))
        (is (= [{:to_strand_id (get refs "design") :edge_type "depends-on"}]
               (db/execute! ds ["SELECT to_strand_id, edge_type FROM strand_edges WHERE from_strand_id = ?" (get refs "docs")])))))))

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
                                                   :active false
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
        (is (= {:title "Old doc" :active false :attributes {:state "superseded"}}
               (select-keys (update (db/get-strand ds (:id old-doc)) :attributes db/<-json)
                            [:title :active :attributes])))
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
