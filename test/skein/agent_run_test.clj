(ns skein.agent-run-test
  "Tests for the shuttle agent-spawning spool against a real weaver runtime.

  Harness processes in these tests use the shipped `sh` harness (the prompt is
  the script), so runs are cheap and deterministic while still exercising the
  full readiness-driven spawn engine, result capture, notes, and reconciliation."
  (:require [clojure.java.io :as io]
            [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [skein.spools.agent-run :as shuttle]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as api]
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
        (shuttle/defharness! :base {:argv ["tool" "-p"] :parse :raw :doc "Base tool surface."})
        (shuttle/defalias! :fast {:alias-of :base :extra-args ["--model" "fast"] :doc "Fast seat."})
        (shuttle/defalias! :fast-reviewer {:alias-of :fast :prompt-prefix "Review: "})
        (let [effective (shuttle/resolve-harness :fast-reviewer)]
          (is (= ["tool" "-p"] (:argv effective)))
          (is (= ["--model" "fast"] (:extra-args effective)))
          (is (= "Review: " (:prompt-prefix effective)))))
      (testing "the listing shows alias and root-harness docs together"
        (let [by-name (into {} (map (juxt :name identity)) (shuttle/harnesses))
              fast (get by-name "fast")
              reviewer (get by-name "fast-reviewer")]
          (is (= {:alias-of "base" :harness "base"
                  :harness-doc "Base tool surface." :doc "Fast seat."}
                 (select-keys fast [:alias-of :harness :harness-doc :doc])))
          ;; chains resolve to the root, not the immediate base
          (is (= "fast" (:alias-of reviewer)))
          (is (= "base" (:harness reviewer)))
          (is (= "Base tool surface." (:harness-doc reviewer)))))
      (testing "a same-named seat shadows the tool alias-first and terminates at it"
        ;; register a seat carrying the tool's own name: resolution must walk the
        ;; alias hop first, then fall to the harness registry that shares the name
        (shuttle/defalias! :pi {:alias-of :pi :extra-args ["--agent" "main"] :doc "pi worker seat."})
        (let [effective (shuttle/resolve-harness :pi)]
          (is (= ["pi" "-p" "--mode" "json"] (:argv effective)) "terminates at the pi tool's argv")
          (is (= ["--agent" "main"] (:extra-args effective)) "the seat's extra args are applied")))
      (testing "an unshadowed harness resolves directly"
        (let [effective (shuttle/resolve-harness :sh)]
          (is (= ["sh" "-c"] (:argv effective)))
          (is (= [] (:extra-args effective)))))
      (testing "the listing shows a same-named tool and seat as two rows"
        (let [rows (filter #(= "pi" (:name %)) (shuttle/harnesses))
              by-kind (into {} (map (juxt :kind identity)) rows)]
          (is (= 2 (count rows)) "the same-name shadow pair does not collapse to one row")
          (is (= "harness" (:kind (get by-kind "harness"))))
          (is (= "alias" (:kind (get by-kind "alias"))))
          ;; the alias row carries the root tool's name and doc beside its own
          (is (= "pi" (:harness (get by-kind "alias"))))
          (is (= "pi worker seat." (:doc (get by-kind "alias"))))
          (is (string? (:harness-doc (get by-kind "alias"))))))
      (testing "alias cycles fail loudly with a distinct, non-not-found error"
        (shuttle/defalias! :a {:alias-of :b})
        (shuttle/defalias! :b {:alias-of :a})
        (try
          (shuttle/resolve-harness :a)
          (is false "an alias cycle should throw")
          (catch clojure.lang.ExceptionInfo e
            (is (str/includes? (ex-message e) "cycle"))
            (is (= "alias-cycle" (:error-class (ex-data e))))
            (is (not= "harness-not-found" (:error-class (ex-data e)))
                "a cycle must not masquerade as the transient not-found reload race")))
        ;; the listing stays best-effort: a broken chain drops its root keys
        ;; instead of taking the whole diagnostic surface down
        (let [entry (first (filter #(= "a" (:name %)) (shuttle/harnesses)))]
          (is (some? entry))
          (is (not (contains? entry :harness)))
          (is (not (contains? entry :harness-doc))))
        (try
          (shuttle/resolve-harness :missing)
          (is false "missing harness should throw")
          (catch clojure.lang.ExceptionInfo e
            (is (str/includes? (ex-message e) "Harness not found"))
            (is (= "harness-not-found" (:error-class (ex-data e))))
            ;; the diagnostic now lists both registries' available names
            (is (contains? (ex-data e) :available-harnesses))
            (is (contains? (ex-data e) :available-aliases))))))))

(deftest run-spawns-when-ready-and-captures-result
  (with-shuttle
    (fn [rt]
      (let [run (shuttle/spawn-run! {:harness :sh :prompt "echo hello-shuttle"})
            done (await-phase rt (:id run) #{"done"})]
        (is (= "closed" (:state done)))
        (is (= "hello-shuttle" (get-in done [:attributes :agent-run/result])))
        (is (= 1 (get-in done [:attributes :agent-run/attempt])))
        (is (some? (get-in done [:attributes :agent-run/pid])))))))

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
          (is (= "hello-stdin" (get-in done [:attributes :agent-run/result]))))))))

(deftest failing-run-stays-active-and-loud
  (with-shuttle
    (fn [rt]
      (let [run (shuttle/spawn-run! {:harness :sh :prompt "echo boom >&2; exit 3"})
            failed (await-phase rt (:id run) #{"failed"})]
        (is (= "active" (:state failed)))
        (is (str/includes? (get-in failed [:attributes :agent-run/error]) "exited 3"))
        (is (str/includes? (get-in failed [:attributes :agent-run/error]) "boom"))))))

(deftest empty-result-exit-0-fails-loudly-and-is-retryable
  (with-shuttle
    (fn [rt]
      (testing "a harness that exits 0 but writes nothing is not recorded done"
        ;; the incident: a transport death drops the harness mid-turn, which
        ;; writes no result yet still exits 0. Recording that as done with an
        ;; empty agent-run/result dodges agent-failures and both recovery paths.
        (let [run (shuttle/spawn-run! {:harness :sh :prompt "exit 0"})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "active" (:state failed)) "stays active so it is loud and retryable")
          (is (= "failed" (get-in failed [:attributes :agent-run/phase])))
          (is (zero? (get-in failed [:attributes :agent-run/exit-code])))
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "empty result"))
          (testing "the failed phase is exactly what agent retry supersedes"
            (is (contains? #{"failed" "exhausted"}
                           (get-in failed [:attributes :agent-run/phase]))))))
      (testing "a blank (whitespace-only) result fails the same way"
        (let [run (shuttle/spawn-run! {:harness :sh :prompt "printf '   \\n'"})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "failed" (get-in failed [:attributes :agent-run/phase])))
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "empty result")))))))

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
          (is (= "pending" (get-in (api/show rt (:id collector)) [:attributes :agent-run/phase]))))
        (testing "closing the last dependency triggers the spawn via events"
          (api/update rt (:id blocker) {:state "closed"})
          (let [done (await-phase rt (:id collector) #{"done"})]
            (is (= "collected" (get-in done [:attributes :agent-run/result])))))))))

(deftest spawned-by-records-provenance-tree
  (with-shuttle
    (fn [rt]
      (let [parent (shuttle/spawn-run! {:harness :sh :prompt "echo parent"})
            child (shuttle/spawn-run! {:harness :sh :prompt "echo child"
                                       :spawned-by (:id parent)})]
        (await-phase rt (:id parent) #{"done"})
        (await-phase rt (:id child) #{"done"})
        (is (= (:id parent)
               (get-in (api/show rt (:id child)) [:attributes :agent-run/spawned-by])))
        (is (some #(and (= (:id child) (:to_strand_id %)) (= "parent-of" (:edge_type %)))
                  (:edges (graph/subgraph rt [(:id parent)]))))
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
        (let [incoming-calls (atom 0)
              real-incoming graph/incoming-edges
              incoming-edges-fn (fn [& args]
                                  (swap! incoming-calls inc)
                                  (apply real-incoming args))
              summaries (#'shuttle/runs* {} incoming-edges-fn)]
          (is (= (:id target)
                 (:for (first (filter #(= (:id run) (:id %)) summaries)))))
          ;; one bulk incoming-edge fetch resolves every run's parents; no
          ;; subgraph seam is present on the summary path anymore.
          (is (= 1 @incoming-calls)))))))

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
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "killed")))))))

(deftest reconcile-respawns-orphans-and-exhausts-bounded-attempts
  (with-shuttle
    (fn [rt]
      (testing "an orphaned running run respawns and completes"
        (let [orphan (api/add rt {:title "orphan"
                                  :attributes {"agent-run/run" "true"
                                               "agent-run/harness" "sh"
                                               "agent-run/prompt" "echo recovered"
                                               "agent-run/phase" "running"
                                               "agent-run/attempt" 1
                                               "agent-run/pid" 99999999}})
              summary (shuttle/reconcile!)]
          (is (= [(:id orphan)] (:respawned summary)))
          (is (= "recovered"
                 (get-in (await-phase rt (:id orphan) #{"done"})
                         [:attributes :agent-run/result])))))
      (testing "a run out of attempts is marked exhausted, stays active"
        (let [spent (api/add rt {:title "spent"
                                 :attributes {"agent-run/run" "true"
                                              "agent-run/harness" "sh"
                                              "agent-run/prompt" "echo nope"
                                              "agent-run/phase" "running"
                                              "agent-run/attempt" 3}})
              summary (shuttle/reconcile!)]
          (is (= [(:id spent)] (:exhausted summary)))
          (let [strand (api/show rt (:id spent))]
            (is (= "active" (:state strand)))
            (is (= "exhausted" (get-in strand [:attributes :agent-run/phase])))
            (is (str/includes? (get-in strand [:attributes :agent-run/error]) "exhausted"))))))))

(deftest spawn-validates-inputs-before-creating-anything
  (with-shuttle
    (fn [_rt]
      (testing "reserved control attributes cannot be overridden"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"control attributes"
                              (shuttle/spawn-run! {:harness :sh :prompt "echo x"
                                                   :attrs {"agent-run/phase" "done"}}))))
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
                     :attributes {"agent-run/run" "true"
                                  "agent-run/harness" "absent"
                                  "agent-run/prompt" "echo x"
                                  "agent-run/phase" "pending"}})
        (let [run-id (:id (first (filter #(= "handmade" (:title %))
                                         (api/list rt shuttle/run-query {}))))
              failed (await-phase rt run-id #{"failed"})]
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "Harness not found")))))))

(deftest recovered-run-with-late-registered-alias-defers-then-respawns
  (with-shuttle
    (fn [rt]
      (let [orphan (api/add rt {:title "late-alias-orphan"
                                :attributes {"agent-run/run" "true"
                                             "agent-run/harness" "late-sh"
                                             "agent-run/prompt" "echo recovered-late"
                                             "agent-run/phase" "running"
                                             "agent-run/attempt" 1
                                             "agent-run/pid" 99999999}})
            summary (shuttle/reconcile!)]
        (is (= [(:id orphan)] (:respawned summary)))
        (let [deferred (await-attr-matching rt (:id orphan) :agent-run/error
                                            #(and % (str/includes? % "Harness not found")))]
          (is (= "pending" (get-in deferred [:attributes :agent-run/phase])))
          (is (some? (get-in deferred [:attributes :agent-run/recovered-at])))
          (is (= 1 (get-in deferred [:attributes :agent-run/attempt]))))
        (shuttle/defalias! :late-sh {:alias-of :sh})
        (shuttle/scan!)
        (let [done (await-phase rt (:id orphan) #{"done"})]
          (is (= "closed" (:state done)))
          (is (= "recovered-late" (get-in done [:attributes :agent-run/result])))
          (is (= 2 (get-in done [:attributes :agent-run/attempt]))))))))

(deftest recovered-run-with-permanently-missing-alias-eventually-fails
  (let [original @#'shuttle/*recovery-harness-deferral-ms*]
    (alter-var-root #'shuttle/*recovery-harness-deferral-ms* (constantly 0))
    (try
      (with-shuttle
        (fn [rt]
          (let [orphan (api/add rt {:title "missing-alias-orphan"
                                    :attributes {"agent-run/run" "true"
                                                 "agent-run/harness" "never-registered"
                                                 "agent-run/prompt" "echo unreachable"
                                                 "agent-run/phase" "running"
                                                 "agent-run/attempt" 1
                                                 "agent-run/pid" 99999999}})
                summary (shuttle/reconcile!)]
            (is (= [(:id orphan)] (:respawned summary)))
            (let [failed (await-phase rt (:id orphan) #{"failed"})]
              (is (= "active" (:state failed)))
              (is (str/includes? (get-in failed [:attributes :agent-run/error])
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
        running (await-attr rt (:id run) (keyword "agent-run" "handle.pid"))
        pid (get-in running [:attributes (keyword "agent-run" "handle.pid")])]
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
                                                 :attrs {"agent-run/handle.pid" "1"}}))))))

(deftest interactive-run-reaps-when-served-strand-closes
  (with-shuttle
    (fn [rt]
      (let [target (api/add rt {:title "hitl task"})
            {:keys [run pid]} (spawn-interactive! rt {:parent (:id target)})]
        (is (= "claim" (get-in run [:attributes :agent-run/completion])))
        (is (= (:id target) (get-in run [:attributes :agent-run/for])))
        (is (str/starts-with? (get-in run [:attributes :agent-run/session]) "skein-"))
        (testing "summary carries interactive fields"
          (let [summary (shuttle/run-summary (api/show rt (:id run)))]
            (is (= "interactive" (:mode summary)))
            (is (= "fake-mux" (:backend summary)))
            (is (= (str "echo attach " pid) (:attach summary)))))
        (testing "closing the served strand reaps the session"
          (api/update rt (:id target) {:state "closed"})
          (let [done (await-phase rt (:id run) #{"done"})]
            (is (= "closed" (:state done)))
            (is (nil? (get-in done [:attributes :agent-run/teardown-error])))
            (is (true? (await-process-death pid)))
            (testing "scrollback was captured before teardown"
              (let [log (get-in done [:attributes :agent-run/log])]
                (is (some? log))
                (is (= (str "scrollback " pid) (slurp log)))))))))))

(deftest manual-close-run-tears-down-on-own-close
  (with-shuttle
    (fn [rt]
      (let [{:keys [run pid]} (spawn-interactive! rt)]
        (is (= "manual-close" (get-in run [:attributes :agent-run/completion])))
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
            (is (str/includes? (get-in failed [:attributes :agent-run/error]) "session ended")))
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
            running (await-attr rt (:id run) (keyword "agent-run" "handle.pid"))
            pid (get-in running [:attributes (keyword "agent-run" "handle.pid")])]
        (testing "capture! peeks a live session without killing it"
          (let [{:keys [text path]} (shuttle/capture! (:id run))]
            (is (= (str "dialogue log for " (:id run) " in /tmp") text))
            (is (= path (get-in (api/show rt (:id run)) [:attributes :agent-run/log])))
            (is (process-alive? pid))))
        (testing "teardown persists the harness capture, not backend scrollback"
          (api/update rt (:id target) {:state "closed"})
          (let [done (await-phase rt (:id run) #{"done"})
                log (get-in done [:attributes :agent-run/log])]
            (is (str/starts-with? (slurp log) "dialogue log for"))))
        (testing "capture! fails loudly when nothing provides a capture op"
          (shuttle/defbackend! :bare-mux (dissoc fake-mux :capture :attach))
          (let [bare (shuttle/spawn-run! {:harness :sh :prompt "sleep 300"
                                          :mode :interactive :backend :bare-mux})]
            (await-attr rt (:id bare) (keyword "agent-run" "handle.pid"))
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
          (is (= "failed" (get-in failed [:attributes :agent-run/phase])))
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "killed")))
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
          (is (= "running" (get-in (api/show rt (:id run)) [:attributes :agent-run/phase])))
          (is (process-alive? pid)))
        (testing "a dead orphan fails loudly instead of respawning"
          (clojure.java.shell/sh "kill" "-9" (str pid))
          (is (true? (await-process-death pid)))
          (forget-in-flight!)
          (let [summary (shuttle/reconcile!)]
            (is (= [(:id run)] (:failed summary))))
          (let [failed (api/show rt (:id run))]
            (is (= "active" (:state failed)))
            (is (= "failed" (get-in failed [:attributes :agent-run/phase])))))))))

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
            session (get-in failed [:attributes :agent-run/session])
            pid-file (io/file "/tmp" (str session ".pid"))
            pid (str/trim (slurp pid-file))]
        (is (str/includes? (get-in failed [:attributes :agent-run/error]) "not a JSON handle"))
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
;; agent-run/result) and it fabricates a fixed session id the engine captures as
;; agent-run/session-id. The prompt stays quote-free so the emitted line is valid
;; JSON.

(def ^:private session-echo
  {:argv ["sh" "-c"
          "printf '{\"result\":\"args:'; for a in \"$@\"; do printf ' %s' \"$a\"; done; printf '\",\"session_id\":\"sess-abc\"}'"
          "session-echo"]
   :parse :claude-json
   :preamble? false
   :resume ["--resume" :agent-run/session-id]})

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
  "Spawn a session-echo run and return it once done, its agent-run/session-id
  captured (sess-abc)."
  [rt]
  (let [pred (shuttle/spawn-run! {:harness :session-echo :prompt "start"})
        done (await-phase rt (:id pred) #{"done"})]
    (is (= "sess-abc" (get-in done [:attributes :agent-run/session-id])))
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
        (testing "provenance: agent-run/resumes attr and a resumes annotation edge"
          (is (= (:id pred) (get-in (api/show rt (:id resumer)) [:attributes :agent-run/resumes])))
          (is (= [(:id pred)] (edge-targets rt (:id resumer) "resumes"))))
        (let [done (await-phase rt (:id resumer) #{"done"})]
          (testing "the resolved :resume splice rides ahead of the prompt"
            (is (str/includes? (get-in done [:attributes :agent-run/result]) "--resume sess-abc")))
          (is (= (:id pred) (:resumes (shuttle/run-summary done)))))))))

(deftest resume-with-no-opt-is-behavior-identical
  (with-shuttle
    (fn [rt]
      (shuttle/defharness! :session-echo session-echo)
      (let [plain (await-phase rt (:id (shuttle/spawn-run! {:harness :session-echo :prompt "solo"}))
                               #{"done"})]
        (is (nil? (get-in plain [:attributes :agent-run/resumes])))
        (is (not (str/includes? (get-in plain [:attributes :agent-run/result]) "--resume")))
        (is (empty? (edge-targets rt (:id plain) "resumes")))))))

(deftest resume-failure-matrix
  (with-shuttle
    (fn [rt]
      (shuttle/defharness! :session-echo session-echo)
      (shuttle/defbackend! :fake-mux fake-mux)
      (testing "a harness without a :resume splice is rejected"
        (let [pred (api/add rt {:title "sh-pred" :state "closed"
                                :attributes {"agent-run/run" "true" "agent-run/harness" "sh"
                                             "agent-run/session-id" "sess-x" "agent-run/phase" "done"}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declares a :resume splice"
                                (shuttle/spawn-run! {:harness :sh :prompt "x" :resume (:id pred)})))))
      (testing "a predecessor with no captured session-id is rejected"
        (let [pred (api/add rt {:title "no-session" :state "closed"
                                :attributes {"agent-run/run" "true" "agent-run/harness" "session-echo"
                                             "agent-run/phase" "done"}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no captured agent-run/session-id"
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
                              :attributes {"agent-run/run" "true" "agent-run/harness" "session-echo"
                                           "agent-run/phase" "done"}})
            resumer (api/add rt {:title "resumer"
                                 :attributes {"agent-run/run" "true" "agent-run/harness" "session-echo"
                                              "agent-run/prompt" "continue" "agent-run/phase" "pending"
                                              "agent-run/resumes" (:id pred)}})
            failed (await-phase rt (:id resumer) #{"failed"})]
        (is (= "resume" (get-in failed [:attributes :agent-run/error-class])))
        (is (str/includes? (get-in failed [:attributes :agent-run/error]) "missing a required attribute"))))))

(deftest shipped-defaults-declare-capture-and-resume
  ;; PLAN-Pnl-001.A2/PH2: the shipped :claude/:pi defs are persistence-friendly
  ;; out of the box — they capture agent-run/session-id and declare a resume splice.
  (with-shuttle
    (fn [_rt]
      (let [claude (shuttle/resolve-harness :claude)
            pi (shuttle/resolve-harness :pi)]
        (testing "claude captures via :claude-json and resumes by session id"
          (is (= :claude-json (:parse claude)))
          (is (= ["--resume" :agent-run/session-id] (:resume claude))))
        (testing "pi runs JSON events, captures via :pi-json, resumes a specific session"
          (is (= ["pi" "-p" "--mode" "json"] (:argv pi)))
          (is (= :pi-json (:parse pi)))
          (is (= ["--session" :agent-run/session-id] (:resume pi))))))))

(deftest resume-launch-enforces-invariants-on-handmade-runs
  ;; PLAN-Pnl-001.A1 / PH1 review [P1]: creating a pending run strand directly is
  ;; a supported API, so the resume invariants must also hold at the launch seam,
  ;; not only in spawn-run!/validate-resume!. Each handmade run below reaches
  ;; launch and must fail loudly, resume-classed for --fresh recovery.
  (with-shuttle
    (fn [rt]
      (shuttle/defharness! :session-echo session-echo)
      (shuttle/defbackend! :fake-mux fake-mux)
      (testing "a handmade interactive run carrying agent-run/resumes is rejected"
        (let [pred (captured-predecessor rt)
              run (api/add rt {:title "handmade-interactive-resume"
                               :attributes {"agent-run/run" "true" "agent-run/harness" "session-echo"
                                            "agent-run/prompt" "continue" "agent-run/phase" "pending"
                                            "agent-run/mode" "interactive" "agent-run/backend" "fake-mux"
                                            "agent-run/resumes" (:id pred)}})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "resume" (get-in failed [:attributes :agent-run/error-class])))
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "cannot resume"))))
      (testing "a handmade cross-harness resumer is rejected at launch"
        (let [pred (captured-predecessor rt)
              run (api/add rt {:title "handmade-cross-harness"
                               :attributes {"agent-run/run" "true" "agent-run/harness" "sh"
                                            "agent-run/prompt" "echo x" "agent-run/phase" "pending"
                                            "agent-run/resumes" (:id pred)}})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "resume" (get-in failed [:attributes :agent-run/error-class])))
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "exact same harness"))))
      (testing "a second handmade continuation of a live session is rejected at launch"
        (let [pred (captured-predecessor rt)
              blocker (api/add rt {:title "gate"})]
          ;; the first continuation stays active-but-blocked, so it holds the
          ;; session while the second reaches launch and must fail loudly
          (api/add rt {:title "held-continuation"
                       :attributes {"agent-run/run" "true" "agent-run/harness" "session-echo"
                                    "agent-run/prompt" "first" "agent-run/phase" "pending"
                                    "agent-run/resumes" (:id pred)}
                       :edges [{:type "depends-on" :to (:id blocker)}]})
          (let [run (api/add rt {:title "second-continuation"
                                 :attributes {"agent-run/run" "true" "agent-run/harness" "session-echo"
                                              "agent-run/prompt" "second" "agent-run/phase" "pending"
                                              "agent-run/resumes" (:id pred)}})
                failed (await-phase rt (:id run) #{"failed"})]
            (is (= "resume" (get-in failed [:attributes :agent-run/error-class])))
            (is (str/includes? (get-in failed [:attributes :agent-run/error]) "already continues"))))))))

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
   #{:harness-registry :alias-registry :backend-registry :in-flight
     :recovery-scheduler :worker-executor :preamble-extension :preamble-conflicts
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
      (swap! (:spool-state rt) update :skein.spools.agent-run/state
             (fn [s] (with-meta (dissoc s :worker-executor) (meta s))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required shuttle spool-state entry is missing"
                            (#'shuttle/worker-executor))))))

(deftest reload-with-changed-state-shape-reinits
  ;; Simulate a reload that preserved a pre-upgrade (v2) state: no version tag,
  ;; missing the executor/scheduler keys, and one mixed harness registry.
  ;; Accessing state must deliberately migrate to the current shape — fresh
  ;; executors, and the mixed registry split by entry shape into the two v3
  ;; atoms — rather than reuse it.
  (with-shuttle
    (fn [rt]
      (let [old-registry (atom {:pi {:argv ["pi" "-p"] :parse :pi-json}
                                :worker {:alias-of :pi :extra-args ["--agent" "main"]}})
            old-in-flight (atom {"run-x" {:phase :running}})
            old-state {:harness-registry old-registry
                       :backend-registry (atom {})
                       :in-flight old-in-flight
                       :preamble-extension (atom "preserved preamble")
                       :default-review-contract (atom nil)}]
        ;; overwrite the installed (version 1) state with an untagged old-shape map
        (swap! (:spool-state rt) assoc :skein.spools.agent-run/state old-state)
        (let [reinit (#'shuttle/state)]
          (is (some? (:worker-executor reinit)) "reinit supplies a fresh worker executor")
          (is (some? (:recovery-scheduler reinit)) "reinit supplies a fresh recovery scheduler")
          (is (some? (:close-fn reinit)))
          (is (= {:pi {:argv ["pi" "-p"] :parse :pi-json}} @(:harness-registry reinit))
              "the tool entry migrates into the harness registry")
          (is (= {:worker {:alias-of :pi :extra-args ["--agent" "main"]}} @(:alias-registry reinit))
              "the seat entry migrates into the alias registry, nothing dropped")
          (is (identical? old-in-flight (:in-flight reinit)) "in-flight tracking carried over by migrate")
          (is (= "preserved preamble" @(:preamble-extension reinit)))
          ;; and the fail-loud getters now resolve real executors
          (is (some? (#'shuttle/worker-executor))))))))

(deftest migrate-splits-mixed-registry-and-rejects-corrupt-entries
  ;; The v2->v3 split classifies each preserved entry by exact shape; an entry
  ;; that matches neither registry shape (or could pass as both) is a corrupt
  ;; record and must fail loudly rather than be silently dropped or misfiled.
  (with-shuttle
    (fn [rt]
      (testing "an entry matching neither shape fails the migrate loudly"
        (let [old-state {:harness-registry (atom {:broken {:doc "no argv, no alias-of"}})
                         :backend-registry (atom {})
                         :in-flight (atom {})
                         :preamble-extension (atom nil)
                         :default-review-contract (atom nil)}]
          (swap! (:spool-state rt) assoc :skein.spools.agent-run/state old-state)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"neither or both"
                                (#'shuttle/state)))))
      (testing "an entry carrying both shapes' required keys fails the migrate loudly"
        (let [old-state {:harness-registry (atom {:both {:argv ["x"] :alias-of :pi}})
                         :backend-registry (atom {})
                         :in-flight (atom {})
                         :preamble-extension (atom nil)
                         :default-review-contract (atom nil)}]
          (swap! (:spool-state rt) assoc :skein.spools.agent-run/state old-state)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"neither or both"
                                (#'shuttle/state))))))))

(deftest parse-pi-json-result-is-always-a-string
  ;; pi tool_execution_end events carry a top-level "result" holding a tool
  ;; output object, and a run can end on a tool-call-only assistant message
  ;; (live failure: run qehav, 2026-07-07); neither may leak a map into :result.
  (testing "trailing tool-call-only message resolves to the last assistant text"
    (let [stdout (str/join "\n"
                           ["{\"type\":\"session\",\"id\":\"sess-1\"}"
                            "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"final summary\"}]}}"
                            "{\"type\":\"tool_execution_end\",\"toolCallId\":\"t1\",\"result\":{\"output\":\"tool payload object\"}}"
                            "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"toolcall\",\"id\":\"t2\"}]}}"])
          parsed (#'shuttle/parse-pi-json stdout)]
      (is (= "final summary" (:result parsed)))
      (is (= "sess-1" (:session-id parsed)))))
  (testing "streams with no assistant text never surface an object result"
    (let [stdout (str/join "\n"
                           ["{\"type\":\"session\",\"id\":\"sess-2\"}"
                            "{\"type\":\"tool_execution_end\",\"toolCallId\":\"t1\",\"result\":{\"output\":\"obj\"}}"])
          parsed (#'shuttle/parse-pi-json stdout)]
      (is (string? (:result parsed))))))
