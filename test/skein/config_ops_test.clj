(ns skein.config-ops-test
  "Focused tests for pure repo-local config operation projections."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.weaver.spool-sync :as spool-sync]
            [skein.spools.test-support :as test-support]
            [skein.test.alpha :as t]))

(deftest embedded-spools-edn-validates-before-rewriting
  (let [source (java.io.File/createTempFile "skein-embedded-spools" ".edn")
        source-path (.getCanonicalPath source)
        read-config (fn [config]
                      (spit source (pr-str config))
                      (test-support/embedded-spools-edn source-path))]
    (try
      (testing "relative local roots become source-relative canonical paths"
        (is (= (.getCanonicalPath (io/file (.getParentFile source) "spools/demo"))
               (get-in (read-config {:spools {'demo/local {:local/root "spools/demo"}}})
                       [:spools 'demo/local :local/root]))))
      (testing "malformed shapes fail with actionable source and family context"
        (doseq [[config family received expected-shape]
                [[{} nil nil "a :spools map"]
                 [{:spools []} nil [] "a :spools map"]
                 [{:spools {"demo/bad" {}}} "demo/bad" "demo/bad" "a symbol family key"]
                 [{:spools {'demo/bad []}} 'demo/bad [] "a map family entry"]
                 [{:spools {'demo/bad {:local/root 42}}} 'demo/bad 42
                  "a non-blank string :local/root"]]]
          (let [error (try
                        (read-config config)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (some? error))
            (is (= {:source-path source-path
                    :family family
                    :received received
                    :received-type (if (nil? received) "nil" (.getName (class received)))
                    :expected-shape expected-shape}
                   (ex-data error))))))
      (finally
        (io/delete-file source true)))))

(defn- run-with-config-world [f]
  (t/run-with-weaver-world
   {:storage :sqlite-memory
    :spools-edn (test-support/embedded-spools-edn ".skein/spools.edn")}
   (fn [{:keys [runtime]}]
     (weaver-runtime/with-runtime-and-spool-classloader
       runtime
       #(do
          (spool-sync/sync-approved-spools runtime)
          (f runtime))))))

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
  (run-with-config-world
   (fn [runtime]
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
                      entries))))))))

(deftest kanban-tree-builds-on-the-canonical-entity-projection
  (run-with-config-world
   (fn [runtime]
     (load-file ".skein/config.clj")
     ((requiring-resolve 'ct.spools.kanban/install!))
     ((requiring-resolve 'config/install!))
     (let [card (weaver/add! runtime
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
       (is (= (:updated_at card) (:updated_at row)))))))
