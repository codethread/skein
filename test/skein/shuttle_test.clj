(ns skein.shuttle-test
  "Tests for the shuttle agent-spawning spool against a real weaver runtime.

  Harness processes in these tests use the shipped `sh` harness (the prompt is
  the script), so runs are cheap and deterministic while still exercising the
  full readiness-driven spawn engine, result capture, notes, and reconciliation."
  (:require [clojure.java.io :as io]
            [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [skein.core.db-test :as db-test]
            [skein.spools.shuttle :as shuttle]
            [skein.api.weaver.alpha :as api]
            [skein.core.weaver.runtime :as runtime]
            [skein.spools.test-support :as test-support :refer [await-phase]]))

(defn- with-shuttle
  "Run f with a fresh weaver runtime that has the shuttle installed.

  Publishes the runtime as the process ambient runtime (`test-support`'s
  `:publish? true` opt): shuttle's async reap/monitor threads and its own
  `rt` fallback resolve the runtime via `current/runtime` rather than a
  per-call binding, so they need the ambient singleton to actually exist."
  [f]
  (test-support/with-runtime
    {:publish? true :prefix "skein-shuttle-config"}
    (fn [rt _config-dir]
      (shuttle/install!)
      (f rt))))

(defn- await-attr-matching
  "Poll until attribute `k` satisfies `pred` or timeout; return the strand."
  ([rt id k pred] (await-attr-matching rt id k pred (test-support/await-budget-ms)))
  ([rt id k pred timeout-ms]
   (test-support/poll-until
    #(let [strand (api/show rt id)]
       (when (pred (get-in strand [:attributes k])) strand))
    {:timeout-ms timeout-ms
     :on-timeout #(throw (ex-info "Timed out waiting for matching attribute"
                                  {:id id :attr k :strand (api/show rt id)}))})))

(deftest harness-registry-validates-and-resolves-aliases
  (with-shuttle
    (fn [_rt]
      (shuttle/register-default-harnesses!)
      (testing "definition validation fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"argv"
                              (shuttle/defharness! :bad {:argv []})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (shuttle/defharness! :bad {:argv ["x"] :nope 1})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"parse"
                              (shuttle/defharness! :bad {:argv ["x"] :parse :yaml})))
        ;; an invalid :prompt-via must fail at registration, not silently fall
        ;; back to argv delivery and re-expose the prompt on the command line
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"prompt-via"
                              (shuttle/defharness! :bad {:argv ["x"] :prompt-via :std-in}))))
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
        (try
          (shuttle/resolve-harness :missing)
          (is false "missing harness should throw")
          (catch clojure.lang.ExceptionInfo e
            (is (str/includes? (ex-message e) "Harness not found"))
            (is (= "harness-not-found" (:error-class (ex-data e))))))))))

(deftest run-spawns-when-ready-and-captures-result
  (with-shuttle
    (fn [rt]
      (let [run (shuttle/spawn-run! {:harness :sh :prompt "echo hello-shuttle"})
            done (await-phase rt (:id run) #{"done"})]
        (is (= "closed" (:state done)))
        (is (= "hello-shuttle" (get-in done [:attributes :shuttle/result])))
        (is (= 1 (get-in done [:attributes :shuttle/attempt])))
        (is (some? (get-in done [:attributes :shuttle/pid])))))))

(deftest stdin-prompt-stays-off-argv
  (with-shuttle
    (fn [rt]
      (shuttle/register-default-harnesses!)
      (testing "the shipped headless agent harnesses deliver the prompt on stdin"
        (is (= :stdin (:prompt-via (shuttle/resolve-harness :claude))))
        (is (= :stdin (:prompt-via (shuttle/resolve-harness :pi)))))
      (testing "build-argv omits the prompt for a :prompt-via :stdin harness"
        ;; the whole point of item 9: the worker prompt never lands in argv, so
        ;; it stays out of `ps` and clear of any `pkill -f` pattern kill.
        (let [harness (shuttle/resolve-harness :claude)
              argv (#'shuttle/build-argv harness nil "SECRET-WORKER-PROMPT")]
          (is (= (:argv harness) argv))
          (is (not-any? #(str/includes? % "SECRET-WORKER-PROMPT") argv))))
      (testing "a :prompt-via :stdin run pipes the prompt to the process and completes"
        ;; `sh` with no args reads its script from stdin, so the prompt is
        ;; delivered entirely off-argv while still driving the process.
        (shuttle/defharness! :sh-stdin {:argv ["sh"] :prompt-via :stdin :preamble? false})
        (let [run (shuttle/spawn-run! {:harness :sh-stdin :prompt "echo hello-stdin"})
              done (await-phase rt (:id run) #{"done"})]
          (is (= "closed" (:state done)))
          (is (= "hello-stdin" (get-in done [:attributes :shuttle/result]))))))))

(deftest failing-run-stays-active-and-loud
  (with-shuttle
    (fn [rt]
      (let [run (shuttle/spawn-run! {:harness :sh :prompt "echo boom >&2; exit 3"})
            failed (await-phase rt (:id run) #{"failed"})]
        (is (= "active" (:state failed)))
        (is (str/includes? (get-in failed [:attributes :shuttle/error]) "exited 3"))
        (is (str/includes? (get-in failed [:attributes :shuttle/error]) "boom"))))))

(deftest empty-result-exit-0-fails-loudly-and-is-retryable
  (with-shuttle
    (fn [rt]
      (testing "a harness that exits 0 but writes nothing is not recorded done"
        ;; the incident: a transport death drops the harness mid-turn, which
        ;; writes no result yet still exits 0. Recording that as done with an
        ;; empty shuttle/result dodges agent-failures and both recovery paths.
        (let [run (shuttle/spawn-run! {:harness :sh :prompt "exit 0"})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "active" (:state failed)) "stays active so it is loud and retryable")
          (is (= "failed" (get-in failed [:attributes :shuttle/phase])))
          (is (zero? (get-in failed [:attributes :shuttle/exit-code])))
          (is (str/includes? (get-in failed [:attributes :shuttle/error]) "empty result"))
          (testing "the failed phase is exactly what agent retry supersedes"
            (is (contains? #{"failed" "exhausted"}
                           (get-in failed [:attributes :shuttle/phase]))))))
      (testing "a blank (whitespace-only) result fails the same way"
        (let [run (shuttle/spawn-run! {:harness :sh :prompt "printf '   \\n'"})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "failed" (get-in failed [:attributes :shuttle/phase])))
          (is (str/includes? (get-in failed [:attributes :shuttle/error]) "empty result")))))))

(deftest dependent-run-waits-for-blocker-and-fans-in
  (with-shuttle
    (fn [rt]
      (let [blocker (api/add rt {:title "external gate" :attributes {"k" "v"}})
            child-a (shuttle/spawn-run! {:harness :sh :prompt "echo a"})
            child-b (shuttle/spawn-run! {:harness :sh :prompt "echo b"})
            collector (shuttle/spawn-run! {:harness :sh :prompt "echo collected"
                                           :depends-on [(:id blocker) (:id child-a) (:id child-b)]})]
        (await-phase rt (:id child-a) #{"done"})
        (await-phase rt (:id child-b) #{"done"})
        (testing "collector stays pending while any dependency is active"
          (Thread/sleep 300)
          (is (= "pending" (get-in (api/show rt (:id collector)) [:attributes :shuttle/phase]))))
        (testing "closing the last dependency triggers the spawn via events"
          (api/update rt (:id blocker) {:state "closed"})
          (let [done (await-phase rt (:id collector) #{"done"})]
            (is (= "collected" (get-in done [:attributes :shuttle/result])))))))))

(deftest spawned-by-records-provenance-tree
  (with-shuttle
    (fn [rt]
      (let [parent (shuttle/spawn-run! {:harness :sh :prompt "echo parent"})
            child (shuttle/spawn-run! {:harness :sh :prompt "echo child"
                                       :spawned-by (:id parent)})]
        (await-phase rt (:id parent) #{"done"})
        (await-phase rt (:id child) #{"done"})
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
        (await-phase rt (:id run) #{"done"})))))

(deftest runs-for-filter-includes-treadle-gate-provenance
  ;; regression: the --for prefilter narrowed candidates to parent-of children
  ;; of the target before summaries were built, but treadle-delegated runs record
  ;; provenance in the treadle/gate attr with no parent-of edge from the gate, so
  ;; a valid gate-owned run vanished from `runs {:for gate-id}` / `agent ps --for`.
  (with-shuttle
    (fn [rt]
      (let [gate (api/add rt {:title "gate"})
            run (shuttle/spawn-run! {:harness :sh
                                     :prompt "echo delegated"
                                     :attrs {"treadle/gate" (:id gate)}})]
        (await-phase rt (:id run) #{"done"})
        (is (= [(:id run)]
               (mapv :id (shuttle/runs {:for (:id gate)})))
            "the gate-delegated run must survive the --for prefilter")))))

(deftest ps-summary-building-does-not-scale-graph-scans-with-strand-count
  ;; regression: ps once issued one subgraph per strand to find each run's
  ;; parents, so summary cost scaled with total strand count (~440k queries
  ;; live at 371 runs). Summary building must be a bounded set of indexed
  ;; fetches, independent of how many unrelated strands exist.
  (with-shuttle
    (fn [rt]
      (let [target (api/add rt {:title "delegated target"})
            run (shuttle/spawn-run! {:harness :sh :prompt "echo work"
                                     :parent (:id target)})]
        (await-phase rt (:id run) #{"done"})
        ;; unrelated strands the summary path must never touch per-strand
        (dotimes [i 150] (api/add rt {:title (str "noise-" i)}))
        (let [subgraph-calls (atom 0)
              incoming-calls (atom 0)
              real-subgraph api/subgraph
              real-incoming api/incoming-edges]
          (with-redefs [api/subgraph (fn [& args]
                                       (swap! subgraph-calls inc)
                                       (apply real-subgraph args))
                        api/incoming-edges (fn [& args]
                                             (swap! incoming-calls inc)
                                             (apply real-incoming args))]
            (let [summaries (shuttle/runs)]
              (is (= (:id target)
                     (:for (first (filter #(= (:id run) (:id %)) summaries)))))
              ;; never a per-strand subgraph fan-out
              (is (zero? @subgraph-calls))
              ;; one bulk incoming-edge fetch resolves every run's parents
              (is (= 1 @incoming-calls)))))))))

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
            {:keys [timed-out runs]} (shuttle/await-runs [(:id quick)] {:timeout-secs (test-support/await-budget-secs)})]
        (is (false? timed-out))
        (is (= "quick" (:result (first runs)))))
      (let [blocker (api/add rt {:title "never closes"})
            stuck (shuttle/spawn-run! {:harness :sh :prompt "echo never"
                                       :depends-on [(:id blocker)]})
            {:keys [timed-out]} (shuttle/await-runs [(:id stuck)] {:timeout-secs 1})]
        (is (true? timed-out))))))

(deftest kill-terminates-a-running-harness
  (with-shuttle
    (fn [rt]
      (let [run (shuttle/spawn-run! {:harness :sh :prompt "sleep 30; echo survived"})]
        (await-phase rt (:id run) #{"running"})
        (shuttle/kill! (:id run))
        (let [failed (await-phase rt (:id run) #{"failed"})]
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
                 (get-in (await-phase rt (:id orphan) #{"done"})
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
    (fn [_rt]
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
              failed (await-phase rt run-id #{"failed"})]
          (is (str/includes? (get-in failed [:attributes :shuttle/error]) "Harness not found")))))))

(deftest recovered-run-with-late-registered-alias-defers-then-respawns
  (with-shuttle
    (fn [rt]
      (let [orphan (api/add rt {:title "late-alias-orphan"
                                :attributes {"shuttle/run" "true"
                                             "shuttle/harness" "late-sh"
                                             "shuttle/prompt" "echo recovered-late"
                                             "shuttle/phase" "running"
                                             "shuttle/attempt" 1
                                             "shuttle/pid" 99999999}})
            summary (shuttle/reconcile!)]
        (is (= [(:id orphan)] (:respawned summary)))
        (let [deferred (await-attr-matching rt (:id orphan) :shuttle/error
                                            #(and % (str/includes? % "Harness not found")))]
          (is (= "pending" (get-in deferred [:attributes :shuttle/phase])))
          (is (some? (get-in deferred [:attributes :shuttle/recovered-at])))
          (is (= 1 (get-in deferred [:attributes :shuttle/attempt]))))
        (shuttle/defalias! :late-sh {:alias-of :sh})
        (shuttle/scan!)
        (let [done (await-phase rt (:id orphan) #{"done"})]
          (is (= "closed" (:state done)))
          (is (= "recovered-late" (get-in done [:attributes :shuttle/result])))
          (is (= 2 (get-in done [:attributes :shuttle/attempt]))))))))

(deftest recovered-run-with-permanently-missing-alias-eventually-fails
  (let [original @#'shuttle/*recovery-harness-deferral-ms*]
    (alter-var-root #'shuttle/*recovery-harness-deferral-ms* (constantly 0))
    (try
      (with-shuttle
        (fn [rt]
          (let [orphan (api/add rt {:title "missing-alias-orphan"
                                    :attributes {"shuttle/run" "true"
                                                 "shuttle/harness" "never-registered"
                                                 "shuttle/prompt" "echo unreachable"
                                                 "shuttle/phase" "running"
                                                 "shuttle/attempt" 1
                                                 "shuttle/pid" 99999999}})
                summary (shuttle/reconcile!)]
            (is (= [(:id orphan)] (:respawned summary)))
            (let [failed (await-phase rt (:id orphan) #{"failed"})]
              (is (= "active" (:state failed)))
              (is (str/includes? (get-in failed [:attributes :shuttle/error])
                                 "Harness not found"))))))
      (finally
        (alter-var-root #'shuttle/*recovery-harness-deferral-ms* (constantly original))))))

;; ---------------------------------------------------------------------------
;; Interactive runs
;;
;; The fake-mux backend emulates a terminal multiplexer with plain detached
;; processes: :start nohups the launcher script and returns its pid as the
;; handle, :alive/:stop signal that pid. Real tmux is exercised by smoke, not
;; unit tests.

(def ^:private fake-mux
  {:start ["sh" "-c" "nohup \"$1\" >/dev/null 2>&1 & printf '{\"pid\":\"%s\"}' \"$!\"" "fake-mux" :command]
   :alive ["kill" "-0" :handle/pid]
   :stop ["kill" :handle/pid]
   :capture ["sh" "-c" "printf 'scrollback %s' \"$1\"" "fake-capture" :handle/pid]
   :attach ["echo" "attach" :handle/pid]
   :doc "test-only fake multiplexer over detached processes"})

(defn- process-alive?
  "Zero-fork liveness probe: ProcessHandle/of avoids the fork+exec `kill -0`
  did per poll, which was itself a casualty under fork-storm test load."
  [pid]
  (let [handle (java.lang.ProcessHandle/of (Long/parseLong (str pid)))]
    (and (.isPresent handle) (.isAlive (.get handle)))))

(defn- await-process-death
  ([pid] (await-process-death pid (test-support/await-budget-ms 5000)))
  ([pid timeout-ms]
   (boolean (test-support/poll-until #(not (process-alive? pid))
                                     {:timeout-ms timeout-ms
                                      :on-timeout (constantly false)}))))

(defn- await-attr
  "Poll until the strand carries attribute `k` or timeout; return the strand.
  Needed for interactive launches: phase running is written durably before
  the backend starts, so the handle lands strictly after running."
  ([rt id k] (await-attr rt id k (test-support/await-budget-ms)))
  ([rt id k timeout-ms]
   (test-support/poll-until
    #(let [strand (api/show rt id)]
       (when (some? (get-in strand [:attributes k])) strand))
    {:timeout-ms timeout-ms
     :on-timeout #(throw (ex-info "Timed out waiting for attribute"
                                  {:id id :attr k :strand (api/show rt id)}))})))

(defn- forget-in-flight!
  "Simulate a weaver crash: drop this runtime's in-flight ownership so
  reconcile! sees still-running strands as orphans."
  []
  (reset! ((var-get #'shuttle/in-flight)) {}))

(defn- spawn-interactive!
  "Spawn a long-lived fake-mux run and wait for its session handle; returns
  {:run <strand> :pid <handle pid>}."
  [rt & [opts]]
  (shuttle/defbackend! :fake-mux fake-mux)
  (let [run (shuttle/spawn-run! (merge {:harness :sh :prompt "sleep 300"
                                        :mode :interactive :backend :fake-mux}
                                       opts))
        running (await-attr rt (:id run) (keyword "shuttle" "handle.pid"))
        pid (get-in running [:attributes (keyword "shuttle" "handle.pid")])]
    (is (process-alive? pid))
    {:run running :pid pid}))

(deftest backend-registry-validates-defs
  (with-shuttle
    (fn [_rt]
      (testing "required ops and unknown keys fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required op"
                              (shuttle/defbackend! :bad {:start ["x"]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (shuttle/defbackend! :bad (assoc fake-mux :nope ["x"])))))
      (testing "argv token namespaces are validated statically"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot reference handle"
                              (shuttle/defbackend! :bad (assoc fake-mux :start ["x" :handle/session]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an available input"
                              (shuttle/defbackend! :bad (assoc fake-mux :alive ["x" :cwd]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown namespace"
                              (shuttle/defbackend! :bad (assoc fake-mux :stop ["x" :nope/what])))))
      (testing "missing backends fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Backend not found"
                              (shuttle/resolve-backend :absent-backend)))))))

(deftest spawn-validates-interactive-options
  (with-shuttle
    (fn [_rt]
      (shuttle/defbackend! :fake-mux fake-mux)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require :backend"
                            (shuttle/spawn-run! {:harness :sh :prompt "x" :mode :interactive})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"apply only to interactive"
                            (shuttle/spawn-run! {:harness :sh :prompt "x" :backend :fake-mux})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reap must be auto or manual"
                            (shuttle/spawn-run! {:harness :sh :prompt "x" :mode :interactive
                                                 :backend :fake-mux :reap :sometimes})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"control attributes"
                            (shuttle/spawn-run! {:harness :sh :prompt "x"
                                                 :attrs {"shuttle/handle.pid" "1"}}))))))

(deftest interactive-run-reaps-when-served-strand-closes
  (with-shuttle
    (fn [rt]
      (let [target (api/add rt {:title "hitl task"})
            {:keys [run pid]} (spawn-interactive! rt {:parent (:id target)})]
        (is (= "claim" (get-in run [:attributes :shuttle/completion])))
        (is (= (:id target) (get-in run [:attributes :shuttle/for])))
        (is (str/starts-with? (get-in run [:attributes :shuttle/session]) "skein-"))
        (testing "summary carries interactive fields"
          (let [summary (shuttle/run-summary (api/show rt (:id run)))]
            (is (= "interactive" (:mode summary)))
            (is (= "fake-mux" (:backend summary)))
            (is (= (str "echo attach " pid) (:attach summary)))))
        (testing "closing the served strand reaps the session"
          (api/update rt (:id target) {:state "closed"})
          (let [done (await-phase rt (:id run) #{"done"})]
            (is (= "closed" (:state done)))
            (is (nil? (get-in done [:attributes :shuttle/teardown-error])))
            (is (true? (await-process-death pid)))
            (testing "scrollback was captured before teardown"
              (let [log (get-in done [:attributes :shuttle/log])]
                (is (some? log))
                (is (= (str "scrollback " pid) (slurp log)))))))))))

(deftest manual-close-run-tears-down-on-own-close
  (with-shuttle
    (fn [rt]
      (let [{:keys [run pid]} (spawn-interactive! rt)]
        (is (= "manual-close" (get-in run [:attributes :shuttle/completion])))
        (api/update rt (:id run) {:state "closed"})
        (let [done (await-phase rt (:id run) #{"done"})]
          (is (= "closed" (:state done)))
          (is (true? (await-process-death pid))))))))

(deftest reap-manual-leaves-the-session-to-the-human
  (with-shuttle
    (fn [rt]
      (let [target (api/add rt {:title "keep my terminal"})
            {:keys [run pid]} (spawn-interactive! rt {:parent (:id target) :reap :manual})]
        (api/update rt (:id target) {:state "closed"})
        (let [done (await-phase rt (:id run) #{"done"})]
          (is (= "closed" (:state done)))
          (is (process-alive? pid))
          (clojure.java.shell/sh "kill" (str pid)))))))

(deftest dead-session-fails-loudly-and-completion-wins-races
  (with-shuttle
    (fn [rt]
      (testing "a session dying before its target closes fails the run"
        (let [target (api/add rt {:title "abandoned"})
              {:keys [run pid]} (spawn-interactive! rt {:parent (:id target)})]
          (clojure.java.shell/sh "kill" "-9" (str pid))
          (is (true? (await-process-death pid)))
          (shuttle/supervise!)
          (let [failed (await-phase rt (:id run) #{"failed"})]
            (is (= "active" (:state failed)))
            (is (str/includes? (get-in failed [:attributes :shuttle/error]) "session ended")))
          (is (= "active" (:state (api/show rt (:id target)))))))
      (testing "a dead session whose target already closed is done, not failed"
        (let [target (api/add rt {:title "finished then exited"})
              {:keys [run pid]} (spawn-interactive! rt {:parent (:id target)})]
          ;; close the target and kill the session without letting the event
          ;; handler win the race: supervision must classify this as complete
          (api/update rt (:id target) {:state "closed"})
          (clojure.java.shell/sh "kill" "-9" (str pid))
          (shuttle/supervise!)
          (let [done (await-phase rt (:id run) #{"done"})]
            (is (= "closed" (:state done)))))))))

(deftest harness-capture-overrides-backend-scrollback
  (with-shuttle
    (fn [rt]
      (shuttle/defbackend! :fake-mux fake-mux)
      ;; a harness-aware capture source (stands in for hook-written dialogue
      ;; logs) keyed by the run id the engine exports as SKEIN_RUN_ID
      ;; the harness sets a default cwd: capture must receive that effective
      ;; launch cwd, not the workspace root
      (shuttle/defharness! :sh-hooked
        {:argv ["sh" "-c"] :preamble? false :cwd "/tmp"
         :capture ["sh" "-c" "printf 'dialogue log for %s in %s' \"$1\" \"$2\"" "hook-capture" :run-id :cwd]})
      (let [target (api/add rt {:title "captured task"})
            run (shuttle/spawn-run! {:harness :sh-hooked :prompt "sleep 300"
                                     :mode :interactive :backend :fake-mux
                                     :parent (:id target)})
            running (await-attr rt (:id run) (keyword "shuttle" "handle.pid"))
            pid (get-in running [:attributes (keyword "shuttle" "handle.pid")])]
        (testing "capture! peeks a live session without killing it"
          (let [{:keys [text path]} (shuttle/capture! (:id run))]
            (is (= (str "dialogue log for " (:id run) " in /tmp") text))
            (is (= path (get-in (api/show rt (:id run)) [:attributes :shuttle/log])))
            (is (process-alive? pid))))
        (testing "teardown persists the harness capture, not backend scrollback"
          (api/update rt (:id target) {:state "closed"})
          (let [done (await-phase rt (:id run) #{"done"})
                log (get-in done [:attributes :shuttle/log])]
            (is (str/starts-with? (slurp log) "dialogue log for"))))
        (testing "capture! fails loudly when nothing provides a capture op"
          (shuttle/defbackend! :bare-mux (dissoc fake-mux :capture :attach))
          (let [bare (shuttle/spawn-run! {:harness :sh :prompt "sleep 300"
                                          :mode :interactive :backend :bare-mux})]
            (await-attr rt (:id bare) (keyword "shuttle" "handle.pid"))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no capture op"
                                  (shuttle/capture! (:id bare))))
            (shuttle/kill! (:id bare))))))))

(deftest harness-capture-argv-is-validated
  (with-shuttle
    (fn [_rt]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an available input"
                            (shuttle/defharness! :bad-capture
                              {:argv ["x"] :capture ["cat" :command]}))))))

(deftest kill-terminates-an-interactive-session
  (with-shuttle
    (fn [rt]
      (let [{:keys [run pid]} (spawn-interactive! rt)]
        (shuttle/kill! (:id run))
        (is (true? (await-process-death pid)))
        (let [failed (api/show rt (:id run))]
          (is (= "failed" (get-in failed [:attributes :shuttle/phase])))
          (is (str/includes? (get-in failed [:attributes :shuttle/error]) "killed")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no live session"
                              (shuttle/kill! (:id run))))))))

(deftest reconcile-adopts-live-sessions-and-fails-dead-ones
  (with-shuttle
    (fn [rt]
      (let [target (api/add rt {:title "survives restarts"})
            {:keys [run pid]} (spawn-interactive! rt {:parent (:id target)})]
        (testing "a live session is adopted, not respawned"
          (forget-in-flight!)
          (let [summary (shuttle/reconcile!)]
            (is (= [(:id run)] (:adopted summary)))
            (is (empty? (:respawned summary))))
          (is (= "running" (get-in (api/show rt (:id run)) [:attributes :shuttle/phase])))
          (is (process-alive? pid)))
        (testing "a dead orphan fails loudly instead of respawning"
          (clojure.java.shell/sh "kill" "-9" (str pid))
          (is (true? (await-process-death pid)))
          (forget-in-flight!)
          (let [summary (shuttle/reconcile!)]
            (is (= [(:id run)] (:failed summary))))
          (let [failed (api/show rt (:id run))]
            (is (= "active" (:state failed)))
            (is (= "failed" (get-in failed [:attributes :shuttle/phase])))))))))

(deftest malformed-handle-stops-the-started-session
  (with-shuttle
    (fn [rt]
      ;; :start launches a real detached process keyed by the suggested session
      ;; name but reports a garbage handle. The engine must stop what it
      ;; started (via the suggested-session fallback) before failing the run.
      (shuttle/defbackend! :broken-mux
        {:start ["sh" "-c" "nohup \"$1\" >/dev/null 2>&1 & echo \"$!\" > \"/tmp/$2.pid\"; printf 'not-a-json-handle'"
                 "broken-mux" :command :session]
         :alive ["sh" "-c" "kill -0 \"$(cat \"/tmp/$1.pid\")\"" "broken-mux" :handle/session]
         :stop ["sh" "-c" "kill \"$(cat \"/tmp/$1.pid\")\"" "broken-mux" :handle/session]})
      (let [run (shuttle/spawn-run! {:harness :sh :prompt "sleep 300"
                                     :mode :interactive :backend :broken-mux})
            failed (await-phase rt (:id run) #{"failed"})
            session (get-in failed [:attributes :shuttle/session])
            pid-file (io/file "/tmp" (str session ".pid"))
            pid (str/trim (slurp pid-file))]
        (is (str/includes? (get-in failed [:attributes :shuttle/error]) "not a JSON handle"))
        (testing "the leaked session process was stopped"
          (is (true? (await-process-death pid))))
        (.delete pid-file)))))

(deftest await-detects-dead-interactive-sessions
  (with-shuttle
    (fn [rt]
      (let [target (api/add rt {:title "await target"})
            {:keys [run pid]} (spawn-interactive! rt {:parent (:id target)})
            killer (future (Thread/sleep 500)
                           (clojure.java.shell/sh "kill" "-9" (str pid)))
            {:keys [timed-out runs]} (shuttle/await-runs [(:id run)] {:timeout-secs 30})]
        @killer
        (is (false? timed-out))
        (is (= "failed" (:phase (first runs))))))))

;; ---------------------------------------------------------------------------
;; Session continuation (:resume)
;;
;; The session-echo harness is a fake claude-json harness: its result reflects
;; the argv tail (so a resumed run's spliced flags are observable in
;; shuttle/result) and it fabricates a fixed session id the engine captures as
;; shuttle/session-id. The prompt stays quote-free so the emitted line is valid
;; JSON.

(def ^:private session-echo
  {:argv ["sh" "-c"
          "printf '{\"result\":\"args:'; for a in \"$@\"; do printf ' %s' \"$a\"; done; printf '\",\"session_id\":\"sess-abc\"}'"
          "session-echo"]
   :parse :claude-json
   :preamble? false
   :resume ["--resume" :shuttle/session-id]})

(defn- edge-targets
  "Return the `edge-type` edge targets from `from-id`. The resumes edge is an
  annotation edge (like notes), so subgraph — which only follows the declared
  acyclic relation — cannot see it; read strand_edges directly."
  [rt from-id edge-type]
  (mapv #(first (vals %))
        (jdbc/execute! (:datasource rt)
                       ["SELECT to_strand_id FROM strand_edges WHERE from_strand_id = ? AND edge_type = ?"
                        from-id edge-type])))

(defn- captured-predecessor
  "Spawn a session-echo run and return it once done, its shuttle/session-id
  captured (sess-abc)."
  [rt]
  (let [pred (shuttle/spawn-run! {:harness :session-echo :prompt "start"})
        done (await-phase rt (:id pred) #{"done"})]
    (is (= "sess-abc" (get-in done [:attributes :shuttle/session-id])))
    done))

(deftest defharness-validates-resume-splice
  (with-shuttle
    (fn [_rt]
      (testing "a well-formed splice registers"
        (is (some? (shuttle/defharness! :session-echo session-echo))))
      (testing "unknown placeholder keywords fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an available input"
                              (shuttle/defharness! :bad-resume
                                {:argv ["x"] :resume ["--resume" :shuttle/nope]}))))
      (testing "an empty or non-vector splice fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":resume must be a non-empty vector"
                              (shuttle/defharness! :bad-resume {:argv ["x"] :resume []})))))))

(deftest resume-continues-a-captured-session
  (with-shuttle
    (fn [rt]
      (shuttle/defharness! :session-echo session-echo)
      (let [pred (captured-predecessor rt)
            resumer (shuttle/spawn-run! {:harness :session-echo :prompt "continue"
                                         :resume (:id pred)})]
        (testing "provenance: shuttle/resumes attr and a resumes annotation edge"
          (is (= (:id pred) (get-in (api/show rt (:id resumer)) [:attributes :shuttle/resumes])))
          (is (= [(:id pred)] (edge-targets rt (:id resumer) "resumes"))))
        (let [done (await-phase rt (:id resumer) #{"done"})]
          (testing "the resolved :resume splice rides ahead of the prompt"
            (is (str/includes? (get-in done [:attributes :shuttle/result]) "--resume sess-abc")))
          (is (= (:id pred) (:resumes (shuttle/run-summary done)))))))))

(deftest resume-with-no-opt-is-behavior-identical
  (with-shuttle
    (fn [rt]
      (shuttle/defharness! :session-echo session-echo)
      (let [plain (await-phase rt (:id (shuttle/spawn-run! {:harness :session-echo :prompt "solo"}))
                               #{"done"})]
        (is (nil? (get-in plain [:attributes :shuttle/resumes])))
        (is (not (str/includes? (get-in plain [:attributes :shuttle/result]) "--resume")))
        (is (empty? (edge-targets rt (:id plain) "resumes")))))))

(deftest resume-failure-matrix
  (with-shuttle
    (fn [rt]
      (shuttle/defharness! :session-echo session-echo)
      (shuttle/defbackend! :fake-mux fake-mux)
      (testing "a harness without a :resume splice is rejected"
        (let [pred (api/add rt {:title "sh-pred" :state "closed"
                                :attributes {"shuttle/run" "true" "shuttle/harness" "sh"
                                             "shuttle/session-id" "sess-x" "shuttle/phase" "done"}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declares a :resume splice"
                                (shuttle/spawn-run! {:harness :sh :prompt "x" :resume (:id pred)})))))
      (testing "a predecessor with no captured session-id is rejected"
        (let [pred (api/add rt {:title "no-session" :state "closed"
                                :attributes {"shuttle/run" "true" "shuttle/harness" "session-echo"
                                             "shuttle/phase" "done"}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no captured shuttle/session-id"
                                (shuttle/spawn-run! {:harness :session-echo :prompt "x" :resume (:id pred)})))))
      (testing "a harness name mismatch is rejected with both names"
        (let [pred (captured-predecessor rt)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exact same harness"
                                (shuttle/spawn-run! {:harness :sh :prompt "x" :resume (:id pred)})))
          (try
            (shuttle/spawn-run! {:harness :sh :prompt "x" :resume (:id pred)})
            (is false "expected a mismatch failure")
            (catch clojure.lang.ExceptionInfo e
              (is (= "sh" (:harness (ex-data e))))
              (is (= "session-echo" (:predecessor-harness (ex-data e))))))))
      (testing "only one active continuation may exist per session"
        (let [pred (captured-predecessor rt)
              blocker (api/add rt {:title "gate"})]
          (shuttle/spawn-run! {:harness :session-echo :prompt "first"
                               :resume (:id pred) :depends-on [(:id blocker)]})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already continues this session"
                                (shuttle/spawn-run! {:harness :session-echo :prompt "second"
                                                     :resume (:id pred)})))))
      (testing "interactive runs cannot resume"
        (let [pred (captured-predecessor rt)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot :resume"
                                (shuttle/spawn-run! {:harness :session-echo :prompt "x"
                                                     :mode :interactive :backend :fake-mux
                                                     :resume (:id pred)}))))))))

(deftest resume-launch-failure-is-classed-for-recovery
  (with-shuttle
    (fn [rt]
      (shuttle/defharness! :session-echo session-echo)
      ;; a predecessor that lost its session between spawn and launch: handmade
      ;; so the resumer reaches launch, where resume resolution fails loud and
      ;; classed so recovery can branch to --fresh instead of retrying cold.
      (let [pred (api/add rt {:title "lost-session" :state "closed"
                              :attributes {"shuttle/run" "true" "shuttle/harness" "session-echo"
                                           "shuttle/phase" "done"}})
            resumer (api/add rt {:title "resumer"
                                 :attributes {"shuttle/run" "true" "shuttle/harness" "session-echo"
                                              "shuttle/prompt" "continue" "shuttle/phase" "pending"
                                              "shuttle/resumes" (:id pred)}})
            failed (await-phase rt (:id resumer) #{"failed"})]
        (is (= "resume" (get-in failed [:attributes :shuttle/error-class])))
        (is (str/includes? (get-in failed [:attributes :shuttle/error]) "missing a required attribute"))))))

(deftest shipped-defaults-declare-capture-and-resume
  ;; PLAN-Pnl-001.A2/PH2: the shipped :claude/:pi defs are persistence-friendly
  ;; out of the box — they capture shuttle/session-id and declare a resume splice.
  (with-shuttle
    (fn [_rt]
      (let [claude (shuttle/resolve-harness :claude)
            pi (shuttle/resolve-harness :pi)]
        (testing "claude captures via :claude-json and resumes by session id"
          (is (= :claude-json (:parse claude)))
          (is (= ["--resume" :shuttle/session-id] (:resume claude))))
        (testing "pi runs JSON events, captures via :pi-json, resumes a specific session"
          (is (= ["pi" "-p" "--mode" "json"] (:argv pi)))
          (is (= :pi-json (:parse pi)))
          (is (= ["--session" :shuttle/session-id] (:resume pi))))))))

(deftest resume-launch-enforces-invariants-on-handmade-runs
  ;; PLAN-Pnl-001.A1 / PH1 review [P1]: creating a pending run strand directly is
  ;; a supported API, so the resume invariants must also hold at the launch seam,
  ;; not only in spawn-run!/validate-resume!. Each handmade run below reaches
  ;; launch and must fail loudly, resume-classed for --fresh recovery.
  (with-shuttle
    (fn [rt]
      (shuttle/defharness! :session-echo session-echo)
      (shuttle/defbackend! :fake-mux fake-mux)
      (testing "a handmade interactive run carrying shuttle/resumes is rejected"
        (let [pred (captured-predecessor rt)
              run (api/add rt {:title "handmade-interactive-resume"
                               :attributes {"shuttle/run" "true" "shuttle/harness" "session-echo"
                                            "shuttle/prompt" "continue" "shuttle/phase" "pending"
                                            "shuttle/mode" "interactive" "shuttle/backend" "fake-mux"
                                            "shuttle/resumes" (:id pred)}})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "resume" (get-in failed [:attributes :shuttle/error-class])))
          (is (str/includes? (get-in failed [:attributes :shuttle/error]) "cannot resume"))))
      (testing "a handmade cross-harness resumer is rejected at launch"
        (let [pred (captured-predecessor rt)
              run (api/add rt {:title "handmade-cross-harness"
                               :attributes {"shuttle/run" "true" "shuttle/harness" "sh"
                                            "shuttle/prompt" "echo x" "shuttle/phase" "pending"
                                            "shuttle/resumes" (:id pred)}})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "resume" (get-in failed [:attributes :shuttle/error-class])))
          (is (str/includes? (get-in failed [:attributes :shuttle/error]) "exact same harness"))))
      (testing "a second handmade continuation of a live session is rejected at launch"
        (let [pred (captured-predecessor rt)
              blocker (api/add rt {:title "gate"})]
          ;; the first continuation stays active-but-blocked, so it holds the
          ;; session while the second reaches launch and must fail loudly
          (api/add rt {:title "held-continuation"
                       :attributes {"shuttle/run" "true" "shuttle/harness" "session-echo"
                                    "shuttle/prompt" "first" "shuttle/phase" "pending"
                                    "shuttle/resumes" (:id pred)}
                       :edges [{:type "depends-on" :to (:id blocker)}]})
          (let [run (api/add rt {:title "second-continuation"
                                 :attributes {"shuttle/run" "true" "shuttle/harness" "session-echo"
                                              "shuttle/prompt" "second" "shuttle/phase" "pending"
                                              "shuttle/resumes" (:id pred)}})
                failed (await-phase rt (:id run) #{"failed"})]
            (is (= "resume" (get-in failed [:attributes :shuttle/error-class])))
            (is (str/includes? (get-in failed [:attributes :shuttle/error]) "already continues"))))))))

(deftest spool-loads-through-approved-spool-workspace-flow
  (let [db-file (db-test/temp-db-file)
        config-dir (test-support/temp-config-dir {:prefix "skein-shuttle-config"})
        repo-root (.getCanonicalPath (io/file "spools/shuttle"))]
    (try
      (spit (io/file config-dir "spools.edn")
            (pr-str {:spools {'skein.spools/shuttle {:local/root repo-root}}}))
      (let [rt (runtime/start! db-file {:world (test-support/test-world (.getCanonicalPath config-dir))})]
        (try
          (let [synced ((requiring-resolve 'skein.api.runtime.alpha/sync!) rt)
                used ((requiring-resolve 'skein.api.runtime.alpha/use!)
                      rt :shuttle {:ns 'skein.spools.shuttle
                                   :spools ['skein.spools/shuttle]
                                   :call 'skein.spools.shuttle/install!
                                   :required? true})]
            (is (contains? #{:loaded :already-available}
                           (get-in synced [:spools 'skein.spools/shuttle :status])))
            (is (= :loaded (:status used)))
            (is (not-any? #(= "agent" (:name %)) (api/ops rt))))
          (finally
            (runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)))))

;; ---------------------------------------------------------------------------
;; Reload safety: versioned spool-state, tolerant set-once registration, and
;; loud failure when executors are missing (see daemon-runtime.md §spool-state).
;; ---------------------------------------------------------------------------

(deftest state-shape-matches-declared-version
  ;; Drift alarm for the versioned spool-state convention: if new-state gains or
  ;; loses a key without a matching state-version bump, a post-upgrade reload
  ;; would reuse a shape-mismatched preserved map. Pins the current key set.
  (test-support/assert-state-shape
   #'shuttle/new-state
   #{:harness-registry :backend-registry :in-flight :recovery-scheduler
     :worker-executor :preamble-extension :preamble-conflicts
     :default-review-contract :close-fn}))

(deftest set-preamble-extension-tolerates-reload
  (with-shuttle
    (fn [_rt]
      (testing "first registration and idempotent re-registration of same text"
        (is (false? (:replaced (shuttle/set-preamble-extension! "worker contract A"))))
        (is (false? (:replaced (shuttle/set-preamble-extension! "worker contract A")))))
      (testing "different text replaces rather than failing startup dead"
        (let [result (shuttle/set-preamble-extension! "worker contract B")]
          (is (true? (:replaced result)))
          (is (true? (:preamble-extension result))))
        (is (str/includes? (#'shuttle/preamble "run-1" "task body") "worker contract B"))))))

(deftest set-preamble-extension-records-conflicts-durably
  ;; A genuine cross-spool clash (different text) must leave a durable trace, not
  ;; a stderr-only warning that scrolls away on a long-lived daemon; a same-text
  ;; replay is not a conflict and records nothing.
  (with-shuttle
    (fn [_rt]
      (shuttle/set-preamble-extension! "worker contract A")
      (testing "identical replay records no conflict"
        (shuttle/set-preamble-extension! "worker contract A")
        (is (= [] (shuttle/preamble-extension-conflicts))))
      (testing "a genuine conflict is recorded durably"
        (shuttle/set-preamble-extension! "worker contract B")
        (let [conflicts (shuttle/preamble-extension-conflicts)]
          (is (= 1 (count conflicts)))
          (is (= "worker contract A" (:previous (first conflicts))))
          (is (= "worker contract B" (:replacement (first conflicts))))
          (is (string? (:at (first conflicts)))))))))

(deftest in-flight-run-ids-reflects-tracked-runs
  ;; The parked-run detector uses this to tell a genuinely parked ready run from
  ;; one already in flight, so it must report every tracked phase.
  (with-shuttle
    (fn [_rt]
      (is (= #{} (shuttle/in-flight-run-ids)) "empty when nothing is tracked")
      (swap! (#'shuttle/in-flight) assoc
             "run-a" {:phase :claimed}
             "run-b" {:phase :running}
             "run-c" {:phase :deferred-recovery})
      (is (= #{"run-a" "run-b" "run-c"} (shuttle/in-flight-run-ids))
          "claimed, running, and deferred-recovery runs all count as in-flight")
      (swap! (#'shuttle/in-flight) dissoc "run-b")
      (is (= #{"run-a" "run-c"} (shuttle/in-flight-run-ids))))))

(deftest missing-executor-fails-loudly
  ;; The morning incident: a preserved state lacking :worker-executor turned
  ;; scan!'s launch into (.execute nil ..), silently parking runs. The getter
  ;; now fails loudly (TEN-003) instead of NPE-ing into the event worker.
  (with-shuttle
    (fn [rt]
      (swap! (:spool-state rt) update :skein.spools.shuttle/state
             (fn [s] (with-meta (dissoc s :worker-executor) (meta s))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required shuttle spool-state entry is missing"
                            (#'shuttle/worker-executor))))))

(deftest reload-with-changed-state-shape-reinits
  ;; Simulate a reload that preserved a pre-upgrade state: no version tag and
  ;; missing the executor/scheduler keys. Accessing state must deliberately
  ;; migrate to the current shape (fresh executors) rather than reuse it.
  (with-shuttle
    (fn [rt]
      (let [old-registry (atom {:pi ::alias})
            old-in-flight (atom {"run-x" {:phase :running}})
            old-state {:harness-registry old-registry
                       :backend-registry (atom {})
                       :in-flight old-in-flight
                       :preamble-extension (atom "preserved preamble")
                       :default-review-contract (atom nil)}]
        ;; overwrite the installed (version 1) state with an untagged old-shape map
        (swap! (:spool-state rt) assoc :skein.spools.shuttle/state old-state)
        (let [reinit (#'shuttle/state)]
          (is (some? (:worker-executor reinit)) "reinit supplies a fresh worker executor")
          (is (some? (:recovery-scheduler reinit)) "reinit supplies a fresh recovery scheduler")
          (is (some? (:close-fn reinit)))
          (is (identical? old-registry (:harness-registry reinit)) "durable registry carried over by migrate")
          (is (identical? old-in-flight (:in-flight reinit)) "in-flight tracking carried over by migrate")
          (is (= "preserved preamble" @(:preamble-extension reinit)))
          ;; and the fail-loud getters now resolve real executors
          (is (some? (#'shuttle/worker-executor))))))))
