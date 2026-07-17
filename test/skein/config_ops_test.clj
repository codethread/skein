(ns skein.config-ops-test
  "Focused tests for pure repo-local config operation projections."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.test.alpha :as t]))

(defn- return-case-leaves [operation context return-case]
  (if (and (map? return-case) (contains? return-case :stream))
    (set (map (fn [channel] [operation (assoc context :channel channel)])
              [:emits :result]))
    #{[operation context]}))

(defn- op-return-leaves [{:keys [name returns]}]
  (if (and (map? returns) (contains? returns :subcommands))
    (into #{}
          (mapcat (fn [[subcommand return-case]]
                    (return-case-leaves name {:subcommand subcommand} return-case)))
          (:subcommands returns))
    (return-case-leaves name {} returns)))

(defn- owner-return-coverage [rt provenances checked-leaves]
  (let [entries (filterv #(contains? provenances (:provenance %)) (weaver/ops rt))
        missing (filterv #(not (contains? % :returns)) entries)
        required (into #{} (mapcat op-return-leaves) (remove #(not (contains? % :returns)) entries))]
    {:entries entries
     :missing (mapv :name missing)
     :required required
     :unchecked (set/difference required checked-leaves)}))

(deftest repo-config-ops-declare-and-check-every-production-return-leaf
  (t/run-with-weaver-world
   {:storage :sqlite-memory}
   (fn [{:keys [runtime]}]
     (weaver-runtime/with-runtime-binding
       runtime
       #(do
          (load-file ".skein/config.clj")
          (load-file ".skein/analytics.clj")
          (load-file ".skein/workflows.clj")
          ((requiring-resolve 'config/install!))
          ((requiring-resolve 'analytics/install!))
          ((requiring-resolve 'workflows/install!))
          (let [provenances #{'config 'analytics 'workflows}
                checked (atom #{})
                check! (fn [operation context value]
                         (t/check-op-return! runtime (symbol operation) context value)
                         (swap! checked conj [operation context]))
                {:keys [entries missing required]}
                (owner-return-coverage runtime provenances @checked)]
            (is (seq entries))
            (is (empty? missing) (str "production ops missing :returns: " missing))
            (doseq [[operation context] required]
              (check! operation context
                      (if (= "flow-await" operation)
                        {}
                        {:operation operation})))
            (let [{:keys [unchecked]} (owner-return-coverage runtime provenances @checked)]
              (is (= required @checked))
              (is (empty? unchecked)))
            (testing "only the flat unstamped flow-await result omits operation"
              (is (= #{"flow-await"}
                     (into #{}
                           (keep (fn [{:keys [name returns]}]
                                   (when (and (= 'config (:provenance
                                                          (weaver/resolve-op runtime (symbol name))))
                                              (not (contains? (:required returns {}) :operation)))
                                     name)))
                           entries))))))))))

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
