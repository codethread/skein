(ns skein.backlog-test
  "Tests for the BACKLOG.md local spool against a disposable weaver runtime."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.runtime.alpha :as runtime-alpha]
            [skein.api.weaver.alpha :as api]
            [skein.spools.test-support :refer [with-runtime]]))

(defn- spool-root []
  (.getCanonicalPath (io/file "spools/backlog")))

(defn- install-backlog! [rt config-dir]
  (spit (io/file config-dir "spools.edn")
        (pr-str {:spools {'skein.spools/backlog {:local/root (spool-root)}}}))
  (runtime-alpha/sync! rt)
  (runtime-alpha/use! rt :skein/spools-backlog
                      {:ns 'skein.spools.backlog
                       :spools ['skein.spools/backlog]
                       :call 'skein.spools.backlog/install!
                       :required? true}))

(defn- backlog-file [config-dir]
  (io/file config-dir "BACKLOG.md"))

(defn- op! [rt & argv]
  (api/op! rt 'backlog argv))

(defn- eventually
  [f]
  (loop [attempt 0]
    (let [result (f)]
      (cond
        result result
        (< attempt 50) (do (Thread/sleep 20) (recur (inc attempt)))
        :else nil))))

(deftest backlog-add-next-claim-and-finish-round-trip
  (with-runtime
    (fn [rt config-dir]
      (install-backlog! rt config-dir)
      (is (some #(= "backlog" (:name %)) (api/ops rt)))
      (testing "add creates a strand and Git-visible checkbox row"
        (let [added (op! rt "add" "Build active work convention" "--source" "devflow/rfcs/2026-07-02-feature-tracking-registry.md")
              id (get-in added [:item :id])
              stored (api/show rt id)
              file-text (slurp (backlog-file config-dir))]
          (is (= "Build active work convention" (:title stored)))
          (is (= "true" (get-in stored [:attributes :backlog/item])))
          (is (= "pending" (get-in stored [:attributes :backlog/status])))
          (is (str/includes? file-text (str "- [ ] `" id "` Build active work convention")))
          (testing "next follows BACKLOG.md file order and pending status"
            (is (= id (get-in (op! rt "next") [:next :item :id]))))
          (testing "claim marks the strand, not the checkbox"
            (let [claimed (op! rt "claim" id "--owner" "agent" "--branch" "backlog-spool")]
              (is (= "claimed" (get-in claimed [:item :attributes :backlog/status])))
              (is (= "agent" (get-in claimed [:item :attributes :owner])))
              (is (str/includes? (slurp (backlog-file config-dir)) (str "- [ ] `" id "`")))
              (is (nil? (:next (op! rt "next"))))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be pending"
                                    (op! rt "claim" id "--owner" "other-agent")))))
          (testing "finish closes the strand and checks the row"
            (let [finished (op! rt "finish" id)]
              (is (= "closed" (get-in finished [:item :state])))
              (is (= "done" (get-in finished [:item :attributes :backlog/status])))
              (is (str/includes? (slurp (backlog-file config-dir)) (str "- [x] `" id "`")))
              (is (= true (:ok (op! rt "sync")))))))))))

(deftest backlog-sync-fails-on-drift
  (with-runtime
    (fn [rt config-dir]
      (install-backlog! rt config-dir)
      (let [added (op! rt "add" "Drifty feature")
            id (get-in added [:item :id])]
        (spit (backlog-file config-dir) (str/replace (slurp (backlog-file config-dir)) id "missing"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out of sync"
                              (op! rt "sync")))))))

(deftest backlog-sync-fails-on-duplicate-rows
  (with-runtime
    (fn [rt config-dir]
      (install-backlog! rt config-dir)
      (let [added (op! rt "add" "Duplicated feature")
            id (get-in added [:item :id])]
        (spit (backlog-file config-dir)
              (str (slurp (backlog-file config-dir))
                   "- [ ] `" id "` Duplicated feature again\n"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out of sync"
                              (op! rt "sync")))))))

(deftest backlog-batch-weave-creates-items-rows-and-dependencies
  (with-runtime
    (fn [rt config-dir]
      (install-backlog! rt config-dir)
      (let [existing (api/add rt {:title "Existing blocker"})
            result (api/weave! rt :backlog-batch
                               {:items [{:key "design"
                                         :title "Design batch"
                                         :body "Design body"}
                                        {:key "docs"
                                         :title "Write docs"
                                         :deps ["design" (:id existing)]}]})
            design-id (get-in result [:refs "design"])
            docs-id (get-in result [:refs "docs"])
            design (api/show rt design-id)
            docs (api/show rt docs-id)
            edge-set (set (map (juxt :from_strand_id :to_strand_id :edge_type)
                               (:edges (api/subgraph rt [docs-id] {:type "depends-on"}))))]
        (is (eventually #(let [file (backlog-file config-dir)]
                           (when (.exists file)
                             (let [file-text (slurp file)]
                               (and (str/includes? file-text (str "- [ ] `" design-id "` Design batch"))
                                    (str/includes? file-text (str "- [ ] `" docs-id "` Write docs"))))))))
        (is (= "Design batch" (:title design)))
        (is (= "Design body" (get-in design [:attributes :body])))
        (is (= "true" (get-in docs [:attributes :backlog/item])))
        (is (contains? edge-set [docs-id design-id "depends-on"]))
        (is (contains? edge-set [docs-id (:id existing) "depends-on"]))
        (is (= true (:ok (op! rt "sync"))))))))

(deftest backlog-batch-weave-fails-loudly
  (with-runtime
    (fn [rt config-dir]
      (install-backlog! rt config-dir)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pattern input failed spec validation"
                            (api/weave! rt :backlog-batch
                                        {:items [{:key "x" :title "X" :surprise true}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"item keys must be unique"
                            (api/weave! rt :backlog-batch
                                        {:items [{:key "x" :title "X"}
                                                 {:key "x" :title "Again"}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target strand not found"
                            (api/weave! rt :backlog-batch
                                        {:items [{:key "x" :title "X" :deps ["missing-strand"]}]}))))))
