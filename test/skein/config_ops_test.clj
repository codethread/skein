(ns skein.config-ops-test
  "Focused tests for pure repo-local config operation projections."
  (:require [clojure.test :refer [deftest is]]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.test.alpha :as t]))

(deftest kanban-tree-builds-on-the-canonical-entity-projection
  (t/run-with-weaver-world
   {:storage :sqlite-memory}
   (fn [{:keys [runtime]}]
     (weaver-runtime/with-runtime-binding
       runtime
       #(do
          (load-file ".skein/config.clj")
          ((requiring-resolve 'ct.spools.kanban/install!))
          ((requiring-resolve 'config/install!))
          (let [card (weaver/add runtime
                                 {:title "Feature"
                                  :attributes {:kanban/card "true"
                                               :kanban/type "feature"}})
                projection ((ns-resolve 'config 'kanban-tree-projection) runtime false)
                row (first (:cards projection))]
            (is (= (select-keys card [:id :title :state :attributes])
                   (select-keys row [:id :title :state :attributes])))
            (is (= #{:id :title :state :attributes :created_at :updated_at
                     :type :epic :tasks}
                   (set (keys row))))
            (is (= {:type "feature" :epic nil :tasks []}
                   (select-keys row [:type :epic :tasks])))
            (is (= (:created_at card) (:created_at row)))
            (is (= (:updated_at card) (:updated_at row)))))))))
