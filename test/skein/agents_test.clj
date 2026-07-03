(ns skein.agents-test
  "Tests for the agents coordination spool layered over shuttle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.weaver.alpha :as api]
            [skein.core.db-test :as db-test]
            [skein.core.weaver.config :as daemon-config]
            [skein.core.weaver.runtime :as runtime]
            [skein.spools.agents :as agents]
            [skein.spools.shuttle :as shuttle]))

(defn- temp-config-dir []
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       (.toPath (io/file "/tmp"))
                       "skein-agents-config"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        config-dir (io/file root ".skein")]
    (.mkdirs config-dir)
    config-dir))

(defn- test-world [config-dir]
  (daemon-config/world config-dir (str config-dir "/state") (str config-dir "/data")))

(defn- with-agents [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (temp-config-dir)]
    (try
      (let [rt (runtime/start! db-file {:world (test-world (.getCanonicalPath config-dir))
                                        :publish? false})]
        (try
          (runtime/with-runtime-binding
            rt
            (fn []
              (shuttle/install!)
              (agents/install!)
              (f rt)))
          (finally (runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)))))

(defn- await-phase [rt id phases]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (let [s (api/show rt id)
            phase (get-in s [:attributes :shuttle/phase])]
        (cond
          (contains? phases phase) s
          (> (System/currentTimeMillis) deadline) (throw (ex-info "timeout" {:id id :strand s}))
          :else (do (Thread/sleep 50) (recur)))))))

(deftest agents-install-registers-op-pattern-query
  (with-agents
    (fn [rt]
      (is (some #(= "agent" (:name %)) (api/ops rt)))
      (is (map? (agents/agent-op {:op/argv ["about"]}))))))

(deftest agent-op-dispatches-and-fails-loudly
  (with-agents
    (fn [_]
      (testing "about is the default and carries the manual"
        (is (= (agents/agent-op {:op/argv []}) (agents/agent-op {:op/argv ["about"]})))
        (let [about (agents/agent-op {:op/argv ["about"]})]
          (is (contains? about :verbs))
          (is (contains? (:verbs about) :delegate))
          (is (contains? (get-in about [:verbs :delegate :returns]) "task"))
          (is (seq (get-in about [:verbs :delegate :fails])))
          (is (some #(str/includes? % "FILE SCOPE") (get-in about [:concepts :traps])))
          (is (some #(str/includes? % "Run success never closes")
                    (get-in about [:concepts :traps])))
          (is (some #(str/includes? (:action %) "Provision working directories")
                    (:coordinator-loop about)))
          (is (some #(= "validation" %) (get-in about [:plan-creation :task-fields])))
          (is (str/includes? (:worker-contract about) "Read your assigned strand AND its notes"))))
      (testing "spawn/ps/await/notes drive a full run over argv"
        (let [spawned (agents/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "echo via-op"]})]
          (is (= "pending" (:phase spawned)))
          (let [{:keys [runs timed-out]}
                (agents/agent-op {:op/argv ["await" (:id spawned) "--timeout-secs" "10"]})]
            (is (false? timed-out))
            (is (= "via-op" (:result (first runs)))))
          (agents/agent-op {:op/argv ["note" (:id spawned) "op note" "--by" (:id spawned)]})
          (is (= ["op note"]
                 (mapv :note (agents/agent-op {:op/argv ["notes" (:id spawned)]}))))
          (is (pos? (count (agents/agent-op {:op/argv ["ps"]}))))
          (is (vector? (agents/agent-op {:op/argv ["ps" "--active"]})))
          (is (= [] (agents/agent-op {:op/argv ["ps" "--active" "--for" "no-such-strand"]})))))
      (testing "invalid input fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown agent subcommand"
                              (agents/agent-op {:op/argv ["dance"]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown flag"
                              (agents/agent-op {:op/argv ["spawn" "--nope" "x"]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires --harness"
                              (agents/agent-op {:op/argv ["spawn" "--prompt" "x"]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer"
                              (agents/agent-op {:op/argv ["await" "id" "--timeout-secs" "soon"]})))))))

(deftest spawn-for-creates-task-edge
  (with-agents
    (fn [rt]
      (let [task (api/add rt {:title "served task"})
            run (agents/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "echo ok" "--for" (:id task)]})]
        (is (= (:id task) (:for run)))
        (is (some #(and (= "parent-of" (:edge_type %)) (= (:id run) (:to_strand_id %)))
                  (:edges (api/subgraph rt [(:id task)]))))))))

(deftest agent-logs-read-output-and-error-files
  (with-agents
    (fn [_]
      (let [spawned (agents/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "printf 'a\\nb\\n'; printf 'e\\n' >&2"]})]
        (agents/agent-op {:op/argv ["await" (:id spawned) "--timeout-secs" "10"]})
        (let [logs (agents/agent-op {:op/argv ["logs" (:id spawned) "--tail" "1"]})]
          (is (= "b" (get-in logs [:out :text])))
          (is (= "e" (get-in logs [:err :text]))))))))

(deftest review-spawns-independent-reviewers
  (with-agents
    (fn [rt]
      (let [target (api/add rt {:title "Review target" :attributes {:body "Inspect me"}})
            review (agents/review! (:id target) {:reviewers [{:harness :sh :focus "correctness"}
                                                             {:harness :sh :focus "tests"}]
                                                :contract "Review contract"})]
        (is (= (:id target) (:target review)))
        (is (= 2 (count (:reviewers review))))
        (is (nil? (:synthesizer review)))
        (let [cwd-review (agents/review! (:id target) {:reviewers [{:harness :sh :focus "cwd pass"}]
                                                       :cwd "/tmp/claude/review-cwd"})
              run (api/show rt (first (:reviewers cwd-review)))]
          (is (= "/tmp/claude/review-cwd" (get-in run [:attributes :shuttle/cwd]))
              "review --cwd rides onto each reviewer run"))
        (doseq [run-id (:reviewers review)]
          (let [run (api/show rt run-id)]
            (is (= (:id target) (get-in run [:attributes :shuttle/review-target])))
            (is (str/includes? (get-in run [:attributes :shuttle/prompt]) "Review contract"))))))))

(deftest review-consumes-workspace-default-contract
  (with-agents
    (fn [rt]
      (try
        (shuttle/set-default-review-contract! "Workspace policy contract")
        (let [target (api/add rt {:title "Default-contract target"})
              review (agents/review! (:id target) {:reviewers [{:harness :sh :focus "policy"}]})
              run (api/show rt (first (:reviewers review)))]
          (is (str/includes? (get-in run [:attributes :shuttle/prompt])
                             "Workspace policy contract"))
          (let [review* (agents/review! (:id target) {:reviewers [{:harness :sh :focus "override"}]
                                                      :contract "Explicit contract"})
                run* (api/show rt (first (:reviewers review*)))]
            (is (str/includes? (get-in run* [:attributes :shuttle/prompt]) "Explicit contract"))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                              (shuttle/set-default-review-contract! "  ")))
        (finally
          (shuttle/set-default-review-contract! nil))))))

(deftest council-wires-members-and-synthesizer
  (with-agents
    (fn [rt]
      (let [{:keys [council members synthesizer]}
            (agents/council! "test topic" {:harness :sh :members 2 :rounds 1})]
        (is (= 2 (count members)))
        (testing "council strand is the shared parent of members and synthesizer"
          (let [edges (:edges (api/subgraph rt [council]))]
            (is (= (set (conj members synthesizer))
                   (set (map :to_strand_id edges))))))
        (testing "synthesizer waits for every member"
          (shuttle/await-runs members {:timeout-secs 10})
          (Thread/sleep 200)
          (is (= "pending"
                 (get-in (api/show rt synthesizer) [:attributes :shuttle/phase])))
          (doseq [member members]
            (api/update rt member {:state "closed"}))
          (is (contains? #{"done" "failed"}
                         (get-in (await-phase rt synthesizer #{"done" "failed"})
                                 [:attributes :shuttle/phase]))))))))

(deftest delegate-fails-loudly-for-contract-violations
  (with-agents
    (fn [rt]
      (let [blocker (api/add rt {:title "blocker"})
            blocked (api/add rt {:title "blocked" :attributes {:body "body" :harness "sh"}
                                 :edges [{:type "depends-on" :to (:id blocker)}]})
            no-harness (api/add rt {:title "no harness" :attributes {:body "body"}})
            hitl (api/add rt {:title "hitl" :attributes {:body "body" :harness "sh" :hitl true}})
            active (api/add rt {:title "active" :attributes {:body "body" :harness "sh"}})
            cwd-task (api/add rt {:title "cwd fallback" :attributes {:body "body" :harness "cwd-sh"}})]
        (shuttle/defharness! :cwd-sh {:argv ["sh" "-c"]
                                      :parse :raw
                                      :preamble? false
                                      :cwd "/tmp/harness-cwd"})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not ready"
                              (agents/agent-op {:op/argv ["delegate" (:id blocked)]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"harness"
                              (agents/agent-op {:op/argv ["delegate" (:id no-harness)]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hitl"
                              (agents/agent-op {:op/argv ["delegate" (:id hitl)]})))
        (let [delegated (agents/agent-op {:op/argv ["delegate" (:id cwd-task)]})
              run (api/show rt (get-in delegated [:run :id]))]
          (is (= (-> (io/file (get-in rt [:metadata :config-dir]))
                     .getParentFile
                     .getCanonicalPath)
                 (get-in run [:attributes :shuttle/cwd]))
              "agents supplies workspace root explicitly, so harness :cwd cannot win"))
        (let [gate (api/add rt {:title "gate"})]
          (shuttle/spawn-run! {:harness :sh :prompt "echo later" :parent (:id active) :depends-on [(:id gate)]})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ACTIVE run"
                                (agents/agent-op {:op/argv ["delegate" (:id active)]}))))))))


(deftest agent-op-fail-loudly-matrix
  (with-agents
    (fn [rt]
      (let [task (api/add rt {:title "empty task" :attributes {:harness "sh"}})
            closed (api/add rt {:title "closed" :state "closed" :attributes {:body "body" :harness "sh"}})
            plan (api/add rt {:title "plan"})
            missing-harness (api/add rt {:title "ready missing harness" :attributes {:body "body"}})]
        (api/update rt (:id plan) {:edges [{:type "parent-of" :to (:id missing-harness)}]})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"body or --prompt"
                              (agents/agent-op {:op/argv ["delegate" (:id task)]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"task must be active"
                              (agents/agent-op {:op/argv ["delegate" (:id closed)]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ready tasks missing harness"
                              (agents/agent-op {:op/argv ["delegate" "--ready" (:id plan)]})))
        (is (not (some #{(:id missing-harness)}
                       (:ready (agents/agent-op {:op/argv ["status" (:id plan)]}))))
            "status :ready lists tasks delegable right now, matching delegate --ready selection")
        (is (empty? (shuttle/runs {:for (:id missing-harness)})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutually exclusive"
                              (agents/agent-op {:op/argv ["await" "run-a" "--under" (:id plan)]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"spawn requires --prompt"
                              (agents/agent-op {:op/argv ["spawn" "--harness" "sh"]})))
        (let [done (agents/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "echo done"]})]
          (agents/agent-op {:op/argv ["await" (:id done) "--timeout-secs" "10"]})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no live process"
                                (agents/agent-op {:op/argv ["kill" (:id done)]}))))))))

(deftest delegate-guards-all-non-superseded-runs
  (with-agents
    (fn [rt]
      (let [active-task (api/add rt {:title "active run task" :attributes {:body "body" :harness "sh"}})
            failed-task (api/add rt {:title "failed run task" :attributes {:body "body" :harness "sh"}})
            done-task (api/add rt {:title "done run task" :attributes {:body "body" :harness "sh"}})
            plan (api/add rt {:title "plan"})
            gate (api/add rt {:title "gate"})]
        (api/update rt (:id plan) {:edges [{:type "parent-of" :to (:id active-task)}
                                           {:type "parent-of" :to (:id failed-task)}
                                           {:type "parent-of" :to (:id done-task)}]})
        (shuttle/spawn-run! {:harness :sh :prompt "echo later" :parent (:id active-task) :depends-on [(:id gate)]})
        (let [failed (shuttle/spawn-run! {:harness :sh :prompt "exit 7" :parent (:id failed-task)})
              done (shuttle/spawn-run! {:harness :sh :prompt "echo ok" :parent (:id done-task)})]
          (await-phase rt (:id failed) #{"failed"})
          (await-phase rt (:id done) #{"done"})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ACTIVE run"
                                (agents/agent-op {:op/argv ["delegate" (:id active-task)]})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"wants retry"
                                (agents/agent-op {:op/argv ["delegate" (:id failed-task)]})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"successful run"
                                (agents/agent-op {:op/argv ["delegate" (:id done-task)]})))
          (let [status (agents/agent-op {:op/argv ["status" (:id plan)]})
                ready (agents/agent-op {:op/argv ["delegate" "--ready" (:id plan)]})]
            (is (= [] (:ready status)))
            (is (= #{[(:id active-task) "has-active-run"]
                     [(:id failed-task) "failed-needs-retry"]
                     [(:id done-task) "already-succeeded"]}
                   (set (map (juxt :task :reason) (:skipped ready)))))))))))

(deftest retry-run-id-preserves-provenance-and-dependencies
  (with-agents
    (fn [rt]
      (let [task (api/add rt {:title "served" :attributes {:body "body" :harness "sh"}})
            spawner (shuttle/spawn-run! {:harness :sh :prompt "echo parent"})
            blocker (api/add rt {:title "retry blocker"})
            failed (shuttle/spawn-run! {:harness :sh
                                        :prompt "exit 3"
                                        :parent (:id task)
                                        :spawned-by (:id spawner)
                                        :depends-on [(:id blocker)]
                                        :cwd "/tmp/retry-cwd"
                                        :max-attempts 5})]
        (api/update rt (:id blocker) {:state "closed"})
        (await-phase rt (:id failed) #{"failed"})
        (let [retried (agents/agent-op {:op/argv ["retry" (:id failed) "--prompt" "echo recovered"]})
              new-id (get-in retried [:run :id])
              new-run (api/show rt new-id)
              summary (shuttle/run-summary new-run)
              dep-edges (:edges (api/subgraph rt [new-id] {:type "depends-on"}))]
          (is (= (:id task) (:for summary)))
          (is (= (:id spawner) (:spawned-by summary)))
          (is (= "/tmp/retry-cwd" (get-in new-run [:attributes :shuttle/cwd])))
          (is (= 5 (get-in new-run [:attributes :shuttle/max-attempts])))
          (is (some #(= (:id blocker) (:to_strand_id %)) dep-edges)))))))

(deftest await-under-and-retry-workflow
  (with-agents
    (fn [rt]
      (let [plan (api/add rt {:title "plan"})
            task (api/add rt {:title "task" :attributes {:body "body" :harness "sh"}})
            gate (api/add rt {:title "await gate"})
            _ (api/update rt (:id plan) {:edges [{:type "parent-of" :to (:id task)}]})
            delegated {:run (select-keys (shuttle/run-summary (shuttle/spawn-run! {:harness :sh :prompt "echo first" :parent (:id task) :depends-on [(:id gate)]})) [:id :phase :harness])}]
        (api/update rt (:id gate) {:state "closed"})
        (let [{:keys [timed-out runs]} (agents/agent-op {:op/argv ["await" "--under" (:id plan) "--timeout-secs" "10"]})]
          (is (false? timed-out))
          (is (= (:id (get delegated :run)) (:id (first runs)))))
        (let [failed-task (api/add rt {:title "fails" :attributes {:body "exit 2" :harness "sh"}})
              _ (api/update rt (:id plan) {:edges [{:type "parent-of" :to (:id failed-task)}]})
              d (agents/agent-op {:op/argv ["delegate" (:id failed-task)]})]
          (await-phase rt (get-in d [:run :id]) #{"failed"})
          (let [retried (agents/agent-op {:op/argv ["retry" (:id failed-task) "--prompt" "echo recovered"]})]
            (is (= (get-in d [:run :id]) (:superseded retried)))
            (is (= (:id failed-task) (:task retried))))
          (let [fresh (api/add rt {:title "fresh" :attributes {:body "body" :harness "sh"}})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing to supersede"
                                  (agents/agent-op {:op/argv ["retry" (:id fresh)]})))))))))

(deftest status-triage-lists-ready-running-failed-and-verification
  (with-agents
    (fn [rt]
      (let [ready-task (api/add rt {:title "ready" :attributes {:body "body" :harness "sh"}})
            implemented (api/add rt {:title "implemented" :attributes {:status "implemented"}})
            failed-run (shuttle/spawn-run! {:harness :sh :prompt "exit 9" :parent (:id ready-task)})]
        (await-phase rt (:id failed-run) #{"failed"})
        (let [status (agents/agent-op {:op/argv ["status"]})]
          (is (some #{(:id implemented)} (:awaiting_verification status)))
          (is (some #(= (:id failed-run) (:run %)) (:failed status))))))))
