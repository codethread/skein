(ns skein.scheduler-e2e-test
  "End-to-end hardening for the weaver scheduler (PH4).

  The storage layer (`skein.core.scheduler-test`), the runtime timer/dispatch
  module (`skein.scheduler-runtime-test`), and the blessed API tier
  (`skein.api.scheduler.alpha-test`) each cover their own seam. This namespace
  exercises the whole primitive against real weaver runtimes in disposable
  worlds the way a userland caller would: schedule through the blessed API, let
  a due handler mutate the strand graph on the shared async lane, and read the
  result back through data-first introspection; then prove a wake scheduled by
  one weaver survives a real stop/start and re-arms in a fresh weaver."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [skein.api.events.alpha :as events]
            [skein.api.scheduler.alpha :as scheduler]
            [skein.api.weaver.alpha :as api]
            [skein.core.db :as db]
            [skein.core.db-test :as db-test]
            [skein.core.weaver.runtime :as runtime]
            [skein.spools.test-support :as test-support]
            [skein.test.alpha :as test-alpha]
            [skein.weaver-test :as wt])
  (:import [java.time Duration Instant]))

;; Resolved by fully qualified symbol, so the fire signal is namespace-level.
(def fired (atom (promise)))

(defn add-strand-handler
  "A due handler that mutates the graph: add a strand tagged from the payload,
  then signal the fire promise. Runs on the weaver's shared serialized lane."
  [{:keys [runtime payload]}]
  (api/add runtime {:title (:title payload)
                    :attributes {:origin "scheduler"}})
  (deliver @fired true))

(defn- await-fire []
  (deref @fired (test-support/await-budget-ms 3000) false))

(defn- scheduler-strand-titles [runtime]
  (->> (api/list runtime)
       (filter #(= "scheduler" (get-in % [:attributes :origin])))
       (mapv :title)))

(deftest scheduled-handler-mutates-graph-and-is-introspectable
  (wt/with-runtime
    (fn [rt _db-file]
      (test-alpha/set-clock! rt (constantly (Instant/ofEpochSecond 0)))
      (scheduler/schedule! rt {:key "seed-strand"
                               :wake-at (Instant/ofEpochSecond 1)
                               :handler `add-strand-handler
                               :payload {:title "Scheduled strand"}})
      (test-alpha/advance! rt (Duration/ofSeconds 2))
      (events/await-quiescent! rt)
      (is (= ["Scheduled strand"] (scheduler-strand-titles rt))
          "the handler mutated the strand graph on the shared lane")
      (is (= ["seed-strand"] (mapv :key (scheduler/recent-fires rt)))
          "the completed wake is visible in introspection")
      (is (nil? (first (scheduler/pending rt))) "no wake remains armed"))))

(deftest pending-wake-survives-weaver-restart-and-fires-on-rearm
  (let [db-file (db-test/temp-db-file)
        world (wt/temp-world)]
    (try
      (reset! fired (promise))
      ;; First weaver schedules a wake through the blessed API, confirms it is
      ;; pending, then stops before the wake is due.
      (let [rt1 (runtime/start! db-file {:world world :publish? false})]
        (try
          (test-alpha/set-clock! rt1 (constantly (Instant/ofEpochSecond 0)))
          (scheduler/schedule! rt1 {:key "survivor"
                                    :wake-at (Instant/ofEpochSecond 3600)
                                    :handler `add-strand-handler
                                    :payload {:title "Survivor strand"}})
          (is (= ["survivor"] (mapv :key (scheduler/pending rt1)))
              "the wake is durably pending in the first weaver")
          (finally
            (runtime/stop! rt1))))
      ;; Simulate the wake instant arriving while the weaver was down: the
      ;; durable row is now overdue. A fresh weaver on the same world must
      ;; re-arm from durable pending rows and fire it (SPEC-004.C100).
      (db/schedule-wake! (db/datasource db-file)
                         {:key "survivor" :wake-at (Instant/ofEpochSecond 1)
                          :handler 'skein.scheduler-e2e-test/add-strand-handler
                          :payload {:title "Survivor strand"}})
      (let [rt2 (runtime/start! db-file {:world world :publish? false})]
        (try
          (is (await-fire) "the persisted overdue wake fires after a real weaver restart")
          (test-support/poll-until #(empty? (scheduler/pending rt2))
                                   {:on-timeout #(throw (ex-info "Timed out waiting for scheduler pending to drain"
                                                                 {:pending (scheduler/pending rt2)}))})
          (is (= ["Survivor strand"] (scheduler-strand-titles rt2))
              "the re-armed handler mutated the graph in the fresh weaver")
          (is (= ["survivor"] (mapv :key (scheduler/recent-fires rt2)))
              "the restart-fired wake is visible in introspection")
          (finally
            (runtime/stop! rt2))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (wt/delete-tree! (io/file (:config-dir world)))))))
