(ns skein.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [skein.db :as db]
            [skein.query :as query]))

(defn delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn temp-db-file []
  (let [file (java.io.File/createTempFile "todo-db-test" ".sqlite")]
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
      (is (= #{"id" "title" "active" "ephemeral" "attributes" "created_at" "updated_at" "inactive_at"}
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
        (is (= {:title "Sketch model" :active true :ephemeral false :attributes {:priority "high"} :inactive_at nil}
               (select-keys strand [:title :active :ephemeral :attributes :inactive_at]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                            (db/add-strand! ds {:title "Old" :status "todo"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                            (db/add-strand! ds {:title "Old" :final_at "now"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid strand"
                            (db/add-strand! ds {:title "Bad" :active "true"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Inactive ephemeral"
                            (db/add-strand! ds {:title "Bad" :active false :ephemeral true}))))))

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

(deftest ephemeral-retention-and-invalid-updates
  (with-db
    (fn [ds]
      (let [ephemeral (:id (db/add-strand! ds {:title "Scratch" :ephemeral true}))
            dependent (:id (db/add-strand! ds {:title "Dependent"}))]
        (db/add-edge! ds {:from dependent :to ephemeral :type "depends-on" :attributes {}})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot change active and ephemeral"
                              (db/update-strand! ds dependent {:active false :ephemeral true})))
        (db/update-strand! ds dependent {:active false})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Inactive ephemeral"
                              (db/update-strand! ds dependent {:ephemeral true})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                              (db/update-strand! ds dependent {:final_at "now"})))
        (is (nil? (db/update-strand! ds ephemeral {:active false})))
        (is (nil? (db/get-strand ds ephemeral)))
        (is (empty? (db/related-strands ds dependent)))))))

(deftest query-fields-use-active-ephemeral-inactive-at
  (with-db
    (fn [ds]
      (let [agent (:id (db/add-strand! ds {:title "Agent" :attributes {:owner "agent"}}))
            _human (db/add-strand! ds {:title "Human" :attributes {:owner "human"}})
            scratch (:id (db/add-strand! ds {:title "Scratch" :ephemeral true}))]
        (is (= [agent]
               (mapv :id (db/all-strands ds [:and [:= :active true] [:= [:attr :owner] "agent"]]))))
        (is (= [scratch] (mapv :id (db/all-strands ds [:= :ephemeral true]))))
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
        (is (= [[root child "parent-of"]]
               (mapv (juxt :from_strand_id :to_strand_id :edge_type) (:edges (db/subgraph ds [root])))))
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
        (is (= [(get refs "design")] (mapv :id (db/strand-dependencies ds (get refs "docs")))))))))
