(ns skein.shuttle-test
  "Tests for the shuttle agent-spawning spool against a real weaver runtime.

  Harness processes in these tests use the shipped `sh` harness (the prompt is
  the script), so runs are cheap and deterministic while still exercising the
  full readiness-driven spawn engine, result capture, notes, reconciliation,
  op dispatch, and council wiring."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.db-test :as db-test]
            [skein.spools.shuttle :as shuttle]
            [skein.weaver.api :as api]
            [skein.weaver.config :as daemon-config]
            [skein.weaver.runtime :as runtime]))

(defn- temp-config-dir []
  (doto (.toFile (java.nio.file.Files/createTempDirectory
                  (.toPath (io/file "/tmp"))
                  "skein-shuttle-config"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
    (.mkdirs)))

(defn- test-world [config-dir]
  (daemon-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn- reset-engine! []
  (reset! @#'shuttle/in-flight {}))

(defn- with-shuttle
  "Run f with a fresh weaver runtime that has the shuttle installed."
  [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (temp-config-dir)]
    (try
      (reset-engine!)
      (let [rt (runtime/start! db-file {:world (test-world (.getCanonicalPath config-dir))})]
        (try
          (shuttle/install!)
          (f rt)
          (finally
            (runtime/stop! rt))))
      (finally
        (reset-engine!)
        (db-test/delete-sqlite-family! db-file)))))

(defn- await-phase
  "Poll until the strand's shuttle/phase is in `phases` or timeout; return it."
  [id phases timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [strand (api/show @runtime/current-runtime id)
            phase (get-in strand [:attributes :shuttle/phase])]
        (cond
          (contains? phases phase) strand
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for run phase"
                          {:id id :want phases :strand strand}))
          :else (do (Thread/sleep 50) (recur)))))))

(deftest harness-registry-validates-and-resolves-aliases
  (shuttle/register-default-harnesses!)
  (testing "definition validation fails loudly"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"argv"
                          (shuttle/defharness! :bad {:argv []})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                          (shuttle/defharness! :bad {:argv ["x"] :nope 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"parse"
                          (shuttle/defharness! :bad {:argv ["x"] :parse :yaml}))))
  (testing "alias layering flattens onto the base harness"
    (shuttle/defharness! :base {:argv ["tool" "-p"] :parse :raw})
    (shuttle/defalias! :fast {:alias-of :base :extra-args ["--model" "fast"]})
    (shuttle/defalias! :fast-reviewer {:alias-of :fast :prompt-prefix "Review: "})
    (let [effective (shuttle/resolve-harness :fast-reviewer)]
      (is (= ["tool" "-p"] (:argv effective)))
      (is (= ["--model" "fast"] (:extra-args effective)))
      (is (= "Review: " (:prompt-prefix effective)))))
  (testing "alias cycles and missing harnesses fail loudly"
    (shuttle/defalias! :a {:alias-of :b})
    (shuttle/defalias! :b {:alias-of :a})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle"
                          (shuttle/resolve-harness :a)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Harness not found"
                          (shuttle/resolve-harness :missing)))))

(deftest run-spawns-when-ready-and-captures-result
  (with-shuttle
    (fn [_]
      (let [run (shuttle/spawn-run! {:harness :sh :prompt "echo hello-shuttle"})
            done (await-phase (:id run) #{"done"} 10000)]
        (is (= "closed" (:state done)))
        (is (= "hello-shuttle" (get-in done [:attributes :shuttle/result])))
        (is (= 1 (get-in done [:attributes :shuttle/attempt])))
        (is (some? (get-in done [:attributes :shuttle/pid])))))))

(deftest failing-run-stays-active-and-loud
  (with-shuttle
    (fn [_]
      (let [run (shuttle/spawn-run! {:harness :sh :prompt "echo boom >&2; exit 3"})
            failed (await-phase (:id run) #{"failed"} 10000)]
        (is (= "active" (:state failed)))
        (is (str/includes? (get-in failed [:attributes :shuttle/error]) "exited 3"))
        (is (str/includes? (get-in failed [:attributes :shuttle/error]) "boom"))))))

(deftest dependent-run-waits-for-blocker-and-fans-in
  (with-shuttle
    (fn [rt]
      (let [blocker (api/add rt {:title "external gate" :attributes {"k" "v"}})
            child-a (shuttle/spawn-run! {:harness :sh :prompt "echo a"})
            child-b (shuttle/spawn-run! {:harness :sh :prompt "echo b"})
            collector (shuttle/spawn-run! {:harness :sh :prompt "echo collected"
                                           :depends-on [(:id blocker) (:id child-a) (:id child-b)]})]
        (await-phase (:id child-a) #{"done"} 10000)
        (await-phase (:id child-b) #{"done"} 10000)
        (testing "collector stays pending while any dependency is active"
          (Thread/sleep 300)
          (is (= "pending" (get-in (api/show rt (:id collector)) [:attributes :shuttle/phase]))))
        (testing "closing the last dependency triggers the spawn via events"
          (api/update rt (:id blocker) {:state "closed"})
          (let [done (await-phase (:id collector) #{"done"} 10000)]
            (is (= "collected" (get-in done [:attributes :shuttle/result])))))))))

(deftest spawned-by-records-provenance-tree
  (with-shuttle
    (fn [rt]
      (let [parent (shuttle/spawn-run! {:harness :sh :prompt "echo parent"})
            child (shuttle/spawn-run! {:harness :sh :prompt "echo child"
                                       :spawned-by (:id parent)})]
        (await-phase (:id parent) #{"done"} 10000)
        (await-phase (:id child) #{"done"} 10000)
        (is (= (:id parent)
               (get-in (api/show rt (:id child)) [:attributes :shuttle/spawned-by])))
        (is (some #(and (= (:id child) (:to_strand_id %)) (= "parent-of" (:edge_type %)))
                  (:edges (api/subgraph rt [(:id parent)]))))
        (is (= (:id parent) (:spawned-by (shuttle/run-summary (api/show rt (:id child))))))
        (is (nil? (:for (shuttle/run-summary (api/show rt (:id child))))))))))

(deftest run-summary-reports-treadle-gate-provenance
  (with-shuttle
    (fn [rt]
      (let [gate (api/add rt {:title "gate"})
            run (shuttle/spawn-run! {:harness :sh
                                     :prompt "echo delegated"
                                     :attrs {"treadle/gate" (:id gate)}})]
        (is (= (:id gate) (:for (shuttle/run-summary (api/show rt (:id run))))))
        (await-phase (:id run) #{"done"} 10000)))))

(deftest notes-are-append-only-memory-with-rounds
  (with-shuttle
    (fn [rt]
      (let [target (api/add rt {:title "shared blackboard"})
            target-id (:id target)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                              (shuttle/note! "missing-id" "x")))
        (shuttle/note! target-id "first finding" {:by "run-1" :round 1})
        (shuttle/note! target-id "second finding" {:by "run-2" :round 1})
        (shuttle/note! target-id "rebuttal" {:by "run-1" :round 2})
        (is (= ["first finding" "second finding" "rebuttal"]
               (mapv :note (shuttle/notes target-id))))
        (is (= ["rebuttal"] (mapv :note (shuttle/notes target-id {:round 2}))))
        (is (= ["run-1" "run-2"] (mapv :by (shuttle/notes target-id {:round 1}))))
        (testing "notes are closed strands linked by a notes annotation edge"
          (let [note-id (:id (first (shuttle/notes target-id)))
                note (api/show rt note-id)]
            (is (= "closed" (:state note)))))))))

(deftest await-runs-blocks-until-terminal-and-times-out
  (with-shuttle
    (fn [rt]
      (let [quick (shuttle/spawn-run! {:harness :sh :prompt "echo quick"})
            {:keys [timed-out runs]} (shuttle/await-runs [(:id quick)] {:timeout-secs 10})]
        (is (false? timed-out))
        (is (= "quick" (:result (first runs)))))
      (let [blocker (api/add rt {:title "never closes"})
            stuck (shuttle/spawn-run! {:harness :sh :prompt "echo never"
                                       :depends-on [(:id blocker)]})
            {:keys [timed-out]} (shuttle/await-runs [(:id stuck)] {:timeout-secs 1})]
        (is (true? timed-out))))))

(deftest kill-terminates-a-running-harness
  (with-shuttle
    (fn [_]
      (let [run (shuttle/spawn-run! {:harness :sh :prompt "sleep 30; echo survived"})]
        (await-phase (:id run) #{"running"} 10000)
        (shuttle/kill! (:id run))
        (let [failed (await-phase (:id run) #{"failed"} 10000)]
          (is (str/includes? (get-in failed [:attributes :shuttle/error]) "killed")))))))

(deftest reconcile-respawns-orphans-and-exhausts-bounded-attempts
  (with-shuttle
    (fn [rt]
      (testing "an orphaned running run respawns and completes"
        (let [orphan (api/add rt {:title "orphan"
                                  :attributes {"shuttle/run" "true"
                                               "shuttle/harness" "sh"
                                               "shuttle/prompt" "echo recovered"
                                               "shuttle/phase" "running"
                                               "shuttle/attempt" 1
                                               "shuttle/pid" 99999999}})
              summary (shuttle/reconcile!)]
          (is (= [(:id orphan)] (:respawned summary)))
          (is (= "recovered"
                 (get-in (await-phase (:id orphan) #{"done"} 10000)
                         [:attributes :shuttle/result])))))
      (testing "a run out of attempts is marked exhausted, stays active"
        (let [spent (api/add rt {:title "spent"
                                 :attributes {"shuttle/run" "true"
                                              "shuttle/harness" "sh"
                                              "shuttle/prompt" "echo nope"
                                              "shuttle/phase" "running"
                                              "shuttle/attempt" 3}})
              summary (shuttle/reconcile!)]
          (is (= [(:id spent)] (:exhausted summary)))
          (let [strand (api/show rt (:id spent))]
            (is (= "active" (:state strand)))
            (is (= "exhausted" (get-in strand [:attributes :shuttle/phase])))
            (is (str/includes? (get-in strand [:attributes :shuttle/error]) "exhausted"))))))))

(deftest spawn-validates-inputs-before-creating-anything
  (with-shuttle
    (fn [_]
      (testing "reserved control attributes cannot be overridden"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"control attributes"
                              (shuttle/spawn-run! {:harness :sh :prompt "echo x"
                                                   :attrs {"shuttle/phase" "done"}}))))
      (testing "provenance targets must exist before the run is created"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"parent strand not found"
                              (shuttle/spawn-run! {:harness :sh :prompt "echo x"
                                                   :spawned-by "missing-id"})))
        (is (empty? (filter #(= "echo x" (:title %)) (shuttle/runs))))))))

(deftest unresolvable-harness-fails-the-run-loudly
  (with-shuttle
    (fn [rt]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Harness not found"
                            (shuttle/spawn-run! {:harness :absent :prompt "x"})))
      (testing "a pending strand referencing a missing harness fails at spawn"
        (api/add rt {:title "handmade"
                     :attributes {"shuttle/run" "true"
                                  "shuttle/harness" "absent"
                                  "shuttle/prompt" "echo x"
                                  "shuttle/phase" "pending"}})
        (let [run-id (:id (first (filter #(= "handmade" (:title %))
                                         (api/list rt shuttle/run-query {}))))
              failed (await-phase run-id #{"failed"} 10000)]
          (is (str/includes? (get-in failed [:attributes :shuttle/error]) "Harness not found")))))))

(deftest agent-op-dispatches-and-fails-loudly
  (with-shuttle
    (fn [_]
      (testing "about is the default and carries the full manual"
        (is (= (shuttle/agent-op {:op/argv []}) (shuttle/agent-op {:op/argv ["about"]})))
        (is (contains? (shuttle/agent-op {:op/argv ["about"]}) :subcommands)))
      (testing "spawn/ps/await/notes drive a full run over argv"
        (let [spawned (shuttle/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "echo via-op"]})]
          (is (= "pending" (:phase spawned)))
          (let [{:keys [runs timed-out]}
                (shuttle/agent-op {:op/argv ["await" (:id spawned) "--timeout-secs" "10"]})]
            (is (false? timed-out))
            (is (= "via-op" (:result (first runs)))))
          (shuttle/agent-op {:op/argv ["note" (:id spawned) "op note" "--by" (:id spawned)]})
          (is (= ["op note"]
                 (mapv :note (shuttle/agent-op {:op/argv ["notes" (:id spawned)]}))))
          (is (pos? (count (shuttle/agent-op {:op/argv ["ps"]}))))))
      (testing "invalid input fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown agent subcommand"
                              (shuttle/agent-op {:op/argv ["dance"]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown flag"
                              (shuttle/agent-op {:op/argv ["spawn" "--nope" "x"]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires --harness"
                              (shuttle/agent-op {:op/argv ["spawn" "--prompt" "x"]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer"
                              (shuttle/agent-op {:op/argv ["await" "id" "--timeout-secs" "soon"]})))))))

(deftest agent-logs-read-output-and-error-files
  (with-shuttle
    (fn [_]
      (let [spawned (shuttle/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "printf 'a\\nb\\n'; printf 'e\\n' >&2"]})]
        (shuttle/agent-op {:op/argv ["await" (:id spawned) "--timeout-secs" "10"]})
        (let [logs (shuttle/agent-op {:op/argv ["logs" (:id spawned) "--tail" "1"]})]
          (is (= "b" (get-in logs [:out :text])))
          (is (= "e" (get-in logs [:err :text]))))))))

(deftest council-wires-members-and-synthesizer
  (with-shuttle
    (fn [rt]
      (let [{:keys [council members synthesizer]}
            (shuttle/council! "test topic" {:harness :sh :members 2 :rounds 1})]
        (is (= 2 (count members)))
        (testing "council strand is the shared parent of members and synthesizer"
          (let [edges (:edges (api/subgraph rt [council]))]
            (is (= (set (conj members synthesizer))
                   (set (map :to_strand_id edges))))))
        (testing "synthesizer waits for every member"
          ;; sh members run their prompt (the council protocol text) as a
          ;; shell script, which fails — members go failed and stay active,
          ;; so the synthesizer must still be pending.
          (shuttle/await-runs members {:timeout-secs 10})
          (Thread/sleep 200)
          (is (= "pending"
                 (get-in (api/show rt synthesizer) [:attributes :shuttle/phase])))
          (doseq [member members]
            (api/update rt member {:state "closed"}))
          (is (contains? #{"done" "failed"}
                         (get-in (await-phase synthesizer #{"done" "failed"} 10000)
                                 [:attributes :shuttle/phase]))))))))

(deftest spool-loads-through-approved-spool-workspace-flow
  (let [db-file (db-test/temp-db-file)
        config-dir (temp-config-dir)
        repo-root (.getCanonicalPath (io/file "spools/shuttle"))]
    (try
      (reset-engine!)
      (spit (io/file config-dir "spools.edn")
            (pr-str {:spools {'skein.spools/shuttle {:local/root repo-root}}}))
      (let [rt (runtime/start! db-file {:world (test-world (.getCanonicalPath config-dir))})]
        (try
          (let [synced ((requiring-resolve 'skein.runtime.alpha/sync!))
                used ((requiring-resolve 'skein.runtime.alpha/use!)
                      :shuttle {:ns 'skein.spools.shuttle
                                :spools ['skein.spools/shuttle]
                                :call 'skein.spools.shuttle/install!
                                :required? true})]
            (is (contains? #{:loaded :already-available}
                           (get-in synced [:spools 'skein.spools/shuttle :status])))
            (is (= :loaded (:status used)))
            (is (some #(= "agent" (:name %)) (api/ops rt))))
          (finally
            (runtime/stop! rt))))
      (finally
        (reset-engine!)
        (db-test/delete-sqlite-family! db-file)))))
