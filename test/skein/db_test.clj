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
                            (db/add-strand! ds {:title "Bad" :ephemeral true}))))))

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
                              (db/update-strand! ds strand {:ephemeral true})))
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
            scratch (:id (db/add-strand! ds {:title "Scratch" :attributes {:ephemeral "true"}}))]
        (is (= [agent]
               (mapv :id (db/all-strands ds [:and [:= :active true] [:= [:attr :owner] "agent"]]))))
        (is (= [scratch] (mapv :id (db/all-strands ds [:= [:attr :ephemeral] "true"]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query field"
                              (query/compile-query [:= :ephemeral true] {})))
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
