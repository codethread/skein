(ns skein.agent-run-test
  "Tests for the agent-run spawning spool against a real weaver runtime.

  Harness processes in these tests use the shipped `sh` harness (the prompt is
  the script), so runs are cheap and deterministic while still exercising the
  full readiness-driven spawn engine, result capture, notes, and reconciliation."
  (:require [clojure.java.io :as io]
            [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [skein.spools.agent-run :as shuttle]
            [skein.api.events.alpha :as events]
            [skein.api.graph.alpha :as graph]
            [skein.api.notes.alpha :as notes]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.test-support :as test-support :refer [await-phase]]))

(defn- with-shuttle
  "Run f with a fresh weaver runtime that has the agent-run engine installed.

  Publishes the runtime as the process ambient runtime (`test-support`'s
  `:publish? true` opt): the engine's async reap/monitor threads and its own
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
    #(let [strand (weaver/show rt id)]
       (when (pred (get-in strand [:attributes k])) strand))
    {:timeout-ms timeout-ms
     :on-timeout #(throw (ex-info "Timed out waiting for matching attribute"
                                  {:id id :attr k :strand (weaver/show rt id)}))})))

(deftest install-declares-usage-attrs-in-agent-run-vocab
  (with-shuttle
    (fn [rt]
      (let [decl (vocab/declaration rt :attr-namespace "agent-run")
            keys (set (:keys decl))]
        (is (= :skein/spools-shuttle (:owner decl))
            "the agent-run namespace stays owned by :skein/spools-shuttle")
        (testing "the four completion-time usage keys are declared"
          (doseq [k ["agent-run/cost-usd" "agent-run/tokens-total"
                     "agent-run/tokens" "agent-run/usage-source"]]
            (is (contains? keys k) (str k " is listed in the agent-run vocab"))))
        (testing "re-installing is idempotent for the same owner (survives reload!)"
          (shuttle/install!)
          (is (= decl (vocab/declaration rt :attr-namespace "agent-run"))))))))

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
      (testing "a seat-level rate card overrides the tool's default card"
        ;; a codex seat picks the model, so per-model pricing lives on the seat
        ;; and must win over the tool's base card; a seat with none inherits it.
        (shuttle/defharness! :priced {:argv ["tool"] :parse :codex-json
                                      :cost-rates {:input 1.25 :output 10.0}})
        (shuttle/defalias! :priced-cheap {:alias-of :priced
                                          :cost-rates {:input 0.25 :output 2.0}})
        (shuttle/defalias! :priced-default {:alias-of :priced})
        (is (= {:input 0.25 :output 2.0} (:cost-rates (shuttle/resolve-harness :priced-cheap)))
            "the seat's card wins over the tool's")
        (is (= {:input 1.25 :output 10.0} (:cost-rates (shuttle/resolve-harness :priced-default)))
            "a seat with no card inherits the tool's")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cost-rates"
                              (shuttle/defharness! :bad {:argv ["x"] :cost-rates {:inputs 1.0}}))
            "a typo'd rate key fails at registration rather than going unpriced"))
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
      (let [blocker (weaver/add rt {:title "external gate" :attributes {"k" "v"}})
            child-a (shuttle/spawn-run! {:harness :sh :prompt "echo a"})
            child-b (shuttle/spawn-run! {:harness :sh :prompt "echo b"})
            collector (shuttle/spawn-run! {:harness :sh :prompt "echo collected"
                                           :depends-on [(:id blocker) (:id child-a) (:id child-b)]})]
        (await-phase rt (:id child-a) #{"done"})
        (await-phase rt (:id child-b) #{"done"})
        (testing "collector stays pending while any dependency is active"
          (Thread/sleep 300)
          (is (= "pending" (get-in (weaver/show rt (:id collector)) [:attributes :agent-run/phase]))))
        (testing "closing the last dependency triggers the spawn via events"
          (weaver/update rt (:id blocker) {:state "closed"})
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
               (get-in (weaver/show rt (:id child)) [:attributes :agent-run/spawned-by])))
        (is (some #(and (= (:id child) (:to_strand_id %)) (= "parent-of" (:edge_type %)))
                  (:edges (graph/subgraph rt [(:id parent)]))))
        (is (= (:id parent) (:spawned-by (shuttle/run-summary (weaver/show rt (:id child))))))
        (is (nil? (:for (shuttle/run-summary (weaver/show rt (:id child))))))))))

(deftest run-summary-reports-serves-gate-provenance
  (with-shuttle
    (fn [rt]
      (let [gate (weaver/add rt {:title "gate"})
            run (shuttle/spawn-run! {:harness :sh
                                     :prompt "echo delegated"
                                     :serves (:id gate)})]
        (is (= (:id gate) (:for (shuttle/run-summary (weaver/show rt (:id run))))))
        (await-phase rt (:id run) #{"done"})))))

(deftest runs-for-filter-includes-serves-gate-provenance
  ;; regression: the --for prefilter narrowed candidates to parent-of children
  ;; of the target before summaries were built, but delegated runs can serve the
  ;; target by `serves` edge without a parent-of edge from the target, so a valid
  ;; gate-serving run must remain visible from `runs {:for gate-id}` / `agent ps --for`.
  (with-shuttle
    (fn [rt]
      (let [gate (weaver/add rt {:title "gate"})
            run (shuttle/spawn-run! {:harness :sh
                                     :prompt "echo delegated"
                                     :serves (:id gate)})]
        (await-phase rt (:id run) #{"done"})
        (is (= [(:id run)]
               (mapv :id (shuttle/runs {:for (:id gate)})))
            "the gate-serving run must survive the --for prefilter")))))

(deftest ps-summary-building-does-not-scale-graph-scans-with-strand-count
  ;; regression: ps once issued one subgraph per strand to find each run's
  ;; parents, so summary cost scaled with total strand count (~440k queries
  ;; live at 371 runs). Summary building must be a bounded set of indexed
  ;; fetches, independent of how many unrelated strands exist.
  (with-shuttle
    (fn [rt]
      (let [target (weaver/add rt {:title "delegated target"})
            run (shuttle/spawn-run! {:harness :sh :prompt "echo work"
                                     :parent (:id target)})]
        (await-phase rt (:id run) #{"done"})
        ;; unrelated strands the summary path must never touch per-strand
        (dotimes [i 150] (weaver/add rt {:title (str "noise-" i)}))
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
      (let [target (weaver/add rt {:title "shared blackboard"})
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
                note (weaver/show rt note-id)]
            (is (= "closed" (:state note)))))))))

(deftest await-runs-blocks-until-terminal-and-times-out
  (with-shuttle
    (fn [rt]
      (let [quick (shuttle/spawn-run! {:harness :sh :prompt "echo quick"})
            {:keys [timed-out runs]} (shuttle/await-runs [(:id quick)] {:timeout-secs (test-support/await-budget-secs)})]
        (is (false? timed-out))
        (is (= "quick" (:result (first runs)))))
      (let [blocker (weaver/add rt {:title "never closes"})
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
        (let [orphan (weaver/add rt {:title "orphan"
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
        (let [spent (weaver/add rt {:title "spent"
                                    :attributes {"agent-run/run" "true"
                                                 "agent-run/harness" "sh"
                                                 "agent-run/prompt" "echo nope"
                                                 "agent-run/phase" "running"
                                                 "agent-run/attempt" 3}})
              summary (shuttle/reconcile!)]
          (is (= [(:id spent)] (:exhausted summary)))
          (let [strand (weaver/show rt (:id spent))]
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
        (weaver/add rt {:title "handmade"
                        :attributes {"agent-run/run" "true"
                                     "agent-run/harness" "absent"
                                     "agent-run/prompt" "echo x"
                                     "agent-run/phase" "pending"}})
        (let [run-id (:id (first (filter #(= "handmade" (:title %))
                                         (weaver/list rt shuttle/run-query {}))))
              failed (await-phase rt run-id #{"failed"})]
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "Harness not found")))))))

(deftest recovered-run-with-late-registered-alias-defers-then-respawns
  (with-shuttle
    (fn [rt]
      (let [orphan (weaver/add rt {:title "late-alias-orphan"
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
          (let [orphan (weaver/add rt {:title "missing-alias-orphan"
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
    #(let [strand (weaver/show rt id)]
       (when (some? (get-in strand [:attributes k])) strand))
    {:timeout-ms timeout-ms
     :on-timeout #(throw (ex-info "Timed out waiting for attribute"
                                  {:id id :attr k :strand (weaver/show rt id)}))})))

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
      (let [target (weaver/add rt {:title "hitl task"})
            {:keys [run pid]} (spawn-interactive! rt {:parent (:id target)})]
        (is (= "claim" (get-in run [:attributes :agent-run/completion])))
        (is (= (:id target) (get-in run [:attributes :agent-run/for])))
        (is (str/starts-with? (get-in run [:attributes :agent-run/session]) "skein-"))
        (testing "summary carries interactive fields"
          (let [summary (shuttle/run-summary (weaver/show rt (:id run)))]
            (is (= "interactive" (:mode summary)))
            (is (= "fake-mux" (:backend summary)))
            (is (= (str "echo attach " pid) (:attach summary)))))
        (testing "closing the served strand reaps the session"
          (weaver/update rt (:id target) {:state "closed"})
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
        (weaver/update rt (:id run) {:state "closed"})
        (let [done (await-phase rt (:id run) #{"done"})]
          (is (= "closed" (:state done)))
          (is (true? (await-process-death pid))))))))

(deftest reap-manual-leaves-the-session-to-the-human
  (with-shuttle
    (fn [rt]
      (let [target (weaver/add rt {:title "keep my terminal"})
            {:keys [run pid]} (spawn-interactive! rt {:parent (:id target) :reap :manual})]
        (weaver/update rt (:id target) {:state "closed"})
        (let [done (await-phase rt (:id run) #{"done"})]
          (is (= "closed" (:state done)))
          (is (process-alive? pid))
          (clojure.java.shell/sh "kill" (str pid)))))))

(deftest dead-session-fails-loudly-and-completion-wins-races
  (with-shuttle
    (fn [rt]
      (testing "a session dying before its target closes fails the run"
        (let [target (weaver/add rt {:title "abandoned"})
              {:keys [run pid]} (spawn-interactive! rt {:parent (:id target)})]
          (clojure.java.shell/sh "kill" "-9" (str pid))
          (is (true? (await-process-death pid)))
          (shuttle/supervise!)
          (let [failed (await-phase rt (:id run) #{"failed"})]
            (is (= "active" (:state failed)))
            (is (str/includes? (get-in failed [:attributes :agent-run/error]) "session ended")))
          (is (= "active" (:state (weaver/show rt (:id target)))))))
      (testing "a dead session whose target already closed is done, not failed"
        (let [target (weaver/add rt {:title "finished then exited"})
              {:keys [run pid]} (spawn-interactive! rt {:parent (:id target)})]
          ;; close the target and kill the session without letting the event
          ;; handler win the race: supervision must classify this as complete
          (weaver/update rt (:id target) {:state "closed"})
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
      (let [target (weaver/add rt {:title "captured task"})
            run (shuttle/spawn-run! {:harness :sh-hooked :prompt "sleep 300"
                                     :mode :interactive :backend :fake-mux
                                     :parent (:id target)})
            running (await-attr rt (:id run) (keyword "agent-run" "handle.pid"))
            pid (get-in running [:attributes (keyword "agent-run" "handle.pid")])]
        (testing "capture! peeks a live session without killing it"
          (let [{:keys [text path]} (shuttle/capture! (:id run))]
            (is (= (str "dialogue log for " (:id run) " in /tmp") text))
            (is (= path (get-in (weaver/show rt (:id run)) [:attributes :agent-run/log])))
            (is (process-alive? pid))))
        (testing "teardown persists the harness capture, not backend scrollback"
          (weaver/update rt (:id target) {:state "closed"})
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
        (let [failed (weaver/show rt (:id run))]
          (is (= "failed" (get-in failed [:attributes :agent-run/phase])))
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "killed")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no live session"
                              (shuttle/kill! (:id run))))))))

(deftest reconcile-adopts-live-sessions-and-fails-dead-ones
  (with-shuttle
    (fn [rt]
      (let [target (weaver/add rt {:title "survives restarts"})
            {:keys [run pid]} (spawn-interactive! rt {:parent (:id target)})]
        (testing "a live session is adopted, not respawned"
          (forget-in-flight!)
          (let [summary (shuttle/reconcile!)]
            (is (= [(:id run)] (:adopted summary)))
            (is (empty? (:respawned summary))))
          (is (= "running" (get-in (weaver/show rt (:id run)) [:attributes :agent-run/phase])))
          (is (process-alive? pid)))
        (testing "a dead orphan fails loudly instead of respawning"
          (clojure.java.shell/sh "kill" "-9" (str pid))
          (is (true? (await-process-death pid)))
          (forget-in-flight!)
          (let [summary (shuttle/reconcile!)]
            (is (= [(:id run)] (:failed summary))))
          (let [failed (weaver/show rt (:id run))]
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
         ;; not-yet-written pid file counts as alive: supervise! runs on the graph
         ;; event from the running-phase write, which lands before :start, and a
         ;; false dead-session there steals the teardown claim from the
         ;; malformed-handle failure this test is about
         :alive ["sh" "-c" "[ ! -f \"/tmp/$1.pid\" ] || kill -0 \"$(cat \"/tmp/$1.pid\")\"" "broken-mux" :handle/session]
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
      (let [target (weaver/add rt {:title "await target"})
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
          (is (= (:id pred) (get-in (weaver/show rt (:id resumer)) [:attributes :agent-run/resumes])))
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
        (let [pred (weaver/add rt {:title "sh-pred" :state "closed"
                                   :attributes {"agent-run/run" "true" "agent-run/harness" "sh"
                                                "agent-run/session-id" "sess-x" "agent-run/phase" "done"}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declares a :resume splice"
                                (shuttle/spawn-run! {:harness :sh :prompt "x" :resume (:id pred)})))))
      (testing "a predecessor with no captured session-id is rejected"
        (let [pred (weaver/add rt {:title "no-session" :state "closed"
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
              blocker (weaver/add rt {:title "gate"})]
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
      (let [pred (weaver/add rt {:title "lost-session" :state "closed"
                                 :attributes {"agent-run/run" "true" "agent-run/harness" "session-echo"
                                              "agent-run/phase" "done"}})
            resumer (weaver/add rt {:title "resumer"
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
              run (weaver/add rt {:title "handmade-interactive-resume"
                                  :attributes {"agent-run/run" "true" "agent-run/harness" "session-echo"
                                               "agent-run/prompt" "continue" "agent-run/phase" "pending"
                                               "agent-run/mode" "interactive" "agent-run/backend" "fake-mux"
                                               "agent-run/resumes" (:id pred)}})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "resume" (get-in failed [:attributes :agent-run/error-class])))
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "cannot resume"))))
      (testing "a handmade cross-harness resumer is rejected at launch"
        (let [pred (captured-predecessor rt)
              run (weaver/add rt {:title "handmade-cross-harness"
                                  :attributes {"agent-run/run" "true" "agent-run/harness" "sh"
                                               "agent-run/prompt" "echo x" "agent-run/phase" "pending"
                                               "agent-run/resumes" (:id pred)}})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "resume" (get-in failed [:attributes :agent-run/error-class])))
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "exact same harness"))))
      (testing "a second handmade continuation of a live session is rejected at launch"
        (let [pred (captured-predecessor rt)
              blocker (weaver/add rt {:title "gate"})]
          ;; the first continuation stays active-but-blocked, so it holds the
          ;; session while the second reaches launch and must fail loudly
          (weaver/add rt {:title "held-continuation"
                          :attributes {"agent-run/run" "true" "agent-run/harness" "session-echo"
                                       "agent-run/prompt" "first" "agent-run/phase" "pending"
                                       "agent-run/resumes" (:id pred)}
                          :edges [{:type "depends-on" :to (:id blocker)}]})
          (let [run (weaver/add rt {:title "second-continuation"
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
     :default-review-contract :fanout-ceiling :close-fn}))

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

(deftest interactive-preamble-renders-completion-note-through-single-renderer
  ;; TASK-Nwt-011.DW1: the for-id completion-contract note line converges on the
  ;; single writer-ref->prompt renderer (DELTA-Nwt-001.C4), so the preamble
  ;; carries that exact fragment; the target-less no-for-id branch stays
  ;; hand-written because a writer-ref needs one resolved target.
  (with-shuttle
    (fn [_rt]
      (let [preamble (#'shuttle/interactive-preamble
                      {:id "run-1" :attributes {:agent-run/for "tgt-1"}})
            fragment (notes/writer-ref->prompt {:target "tgt-1" :by "run-1"})]
        (is (str/includes? preamble fragment)
            "the completion-contract note line renders through writer-ref->prompt")
        (testing "the no-for-id branch renders no note fragment"
          (let [no-for (#'shuttle/interactive-preamble {:id "run-2" :attributes {}})]
            (is (not (str/includes? no-for "agent note ")))))))))

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
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required engine spool-state entry is missing"
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
      (is (string? (:result parsed)))
      (is (str/blank? (:result parsed))
          "a text-less event stream is a blank result, never the raw stream"))))

(deftest parse-pi-json-terminal-error-surfaces-as-error
  ;; live incident (runs m5i8k/mdxnn, 2026-07-10): a provider usage limit ends
  ;; the turn with stopReason "error" + errorMessage on the final assistant
  ;; message while pi itself exits 0. The old parse fell back to the raw event
  ;; stream as :result, so the runs closed done with zero findings and dodged
  ;; every recovery path.
  (testing "stopReason error on the last assistant message returns :error"
    (let [stdout (str/join "\n"
                           ["{\"type\":\"session\",\"id\":\"sess-3\"}"
                            "{\"type\":\"message_end\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"do the review\"}]}}"
                            "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[],\"stopReason\":\"error\",\"errorMessage\":\"Codex error: The usage limit has been reached\"}}"
                            "{\"type\":\"turn_end\",\"message\":{\"role\":\"assistant\",\"content\":[],\"stopReason\":\"error\",\"errorMessage\":\"Codex error: The usage limit has been reached\"}}"])
          parsed (#'shuttle/parse-pi-json stdout)]
      (is (= "Codex error: The usage limit has been reached" (:error parsed)))
      (is (str/blank? (:result parsed)))
      (is (= "sess-3" (:session-id parsed)))))
  (testing "an errorMessage without stopReason still surfaces as :error"
    (let [stdout (str/join "\n"
                           ["{\"type\":\"session\",\"id\":\"sess-4\"}"
                            "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[],\"errorMessage\":\"transport dropped\"}}"])
          parsed (#'shuttle/parse-pi-json stdout)]
      (is (= "transport dropped" (:error parsed)))))
  (testing "a non-string errorMessage fails the run instead of throwing into the parse-error path"
    ;; a thrown parse lands in finish-run!'s catch, whose raw-stdout :result
    ;; would drive the run to done — the exact hollow-success class this guards.
    (let [stdout (str/join "\n"
                           ["{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[],\"stopReason\":\"error\",\"errorMessage\":429}}"])
          parsed (#'shuttle/parse-pi-json stdout)]
      (is (= "429" (:error parsed)))
      (is (str/blank? (:result parsed)))))
  (testing "earlier assistant text is preserved as :result alongside :error"
    (let [stdout (str/join "\n"
                           ["{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"partial progress\"}]}}"
                            "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[],\"stopReason\":\"error\",\"errorMessage\":\"boom\"}}"])
          parsed (#'shuttle/parse-pi-json stdout)]
      (is (= "boom" (:error parsed)))
      (is (= "partial progress" (:result parsed)))))
  (testing "a clean final assistant message carries no :error"
    (let [stdout (str/join "\n"
                           ["{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"all done\"}],\"stopReason\":\"stop\"}}"])
          parsed (#'shuttle/parse-pi-json stdout)]
      (is (nil? (:error parsed)))
      (is (= "all done" (:result parsed))))))

;; ---------------------------------------------------------------------------
;; Usage capture (TASK-Ru-001): the committed fixtures under
;; test/fixtures/run-usage/ are the source of truth for the field mapping — a
;; future provider shape change fails one of these rather than silently
;; mis-capturing spend. Never assert against a local weaver log.

(defn- fixture [name]
  (slurp (io/resource (str "fixtures/run-usage/" name))))

(defn- about= [expected actual]
  (< (Math/abs (double (- expected actual))) 1e-9))

(deftest parse-pi-json-folds-usage-deltas
  ;; The fixture carries three per-turn message_end deltas plus the traps a
  ;; naive fold would fall into: zero-usage message_start/message_update events
  ;; (cumulative *within* a turn) and turn_end duplicates of each message_end.
  ;; Summing only the message_end deltas is the run total; take-last or a fold
  ;; that swept in the updates/turn_ends would produce different numbers.
  (let [{:keys [usage]} (#'shuttle/parse-pi-json (fixture "pi-json.out"))
        {:keys [tokens]} usage]
    (is (= "pi-json" (:usage-source usage)))
    (testing "cost is the sum of each turn's nested cost.total delta"
      (is (about= 0.0868035 (:cost-usd usage))))
    (testing "tokens-total is the sum of per-turn deltas, not take-last/cumulative"
      (is (= 57120 (:tokens-total usage)))
      (is (not= 20563 (:tokens-total usage))
          "20563 is the last turn's totalTokens — a take-last fold would report it")
      (is (not= 16254 (:tokens-total usage))
          "16254 is the first turn only — a per-turn-overwrite fold would report it"))
    (testing "breakdown dims are summed across turns; a reported-zero dim is dropped"
      (is (= 21121 (:input tokens)))
      (is (= 1695 (:output tokens)))
      (is (= 34304 (:cache-read tokens)))
      (is (not (contains? tokens :cache-write))
          "cacheWrite is zero in every turn, so it is absent, never a stored 0"))))

(deftest parse-pi-json-reasoning-in-breakdown-not-total
  ;; pi already counts reasoning inside totalTokens, so folding it into
  ;; tokens-total again would double-count it (PROP-Ru-001.C1/C2).
  (let [{:keys [usage]} (#'shuttle/parse-pi-json (fixture "pi-json.out"))]
    (is (= 1055 (get-in usage [:tokens :reasoning]))
        "reasoning is recorded in the breakdown")
    (is (= 57120 (:tokens-total usage))
        "tokens-total is the summed totalTokens; reasoning is not added on top")
    (is (not= (+ 57120 1055) (:tokens-total usage))
        "a reasoning double-count would show 58175")))

(deftest parse-claude-json-captures-usage-from-result-object
  (let [{:keys [usage]} (#'shuttle/parse-claude-json (fixture "claude-json.out"))
        {:keys [tokens]} usage]
    (is (= "claude-json" (:usage-source usage)))
    (is (about= 1.4548964999999998 (:cost-usd usage)))
    (testing "tokens-total is the sum of the four reported counts"
      (is (= (+ 3219 19587 65548 587293) (:tokens-total usage)))
      (is (= 675647 (:tokens-total usage))))
    (testing "usage sub-map fields map onto the C1 breakdown"
      (is (= 3219 (:input tokens)))
      (is (= 19587 (:output tokens)))
      (is (= 65548 (:cache-write tokens)))
      (is (= 587293 (:cache-read tokens))))
    (testing "claude reports no reasoning, so the dim is absent"
      (is (not (contains? tokens :reasoning))))))

(deftest parse-claude-json-omits-absent-field
  ;; A claude version that does not report a token dimension omits the key
  ;; rather than reading it as zero (PROP-Ru-001.C3, G3).
  (let [stdout (str "{\"result\":\"ok\",\"session_id\":\"s\",\"total_cost_usd\":0.5,"
                    "\"usage\":{\"input_tokens\":100,\"output_tokens\":40,"
                    "\"cache_read_input_tokens\":900}}")
        {:keys [usage]} (#'shuttle/parse-claude-json stdout)
        {:keys [tokens]} usage]
    (is (not (contains? tokens :cache-write))
        "cache_creation_input_tokens was absent, so cache-write is omitted, not 0")
    (is (= 100 (:input tokens)))
    (is (= 40 (:output tokens)))
    (is (= 900 (:cache-read tokens)))
    (is (= (+ 100 40 900) (:tokens-total usage))
        "tokens-total sums only the reported counts")))

(deftest parse-claude-json-non-numeric-usage-fails-loudly
  ;; A provider schema drift that reports a usage field as a string/object/null
  ;; must fail loudly, not silently drop the dimension — bad budget data can
  ;; never masquerade as a legitimately unreported figure (TEN-003, PROP-Ru-001).
  (testing "a non-numeric token count throws naming the key and value"
    (let [stdout (str "{\"result\":\"ok\",\"session_id\":\"s\",\"total_cost_usd\":0.5,"
                      "\"usage\":{\"input_tokens\":\"lots\",\"output_tokens\":40}}")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"usage figure is not a number"
                            (#'shuttle/parse-claude-json stdout)))
      (try
        (#'shuttle/parse-claude-json stdout)
        (catch clojure.lang.ExceptionInfo e
          (is (= :input (:key (ex-data e))) "the failing dimension is named")
          (is (= "lots" (:value (ex-data e))) "the offending value is reported")))))
  (testing "a non-numeric total_cost_usd throws"
    (let [stdout (str "{\"result\":\"ok\",\"session_id\":\"s\",\"total_cost_usd\":\"free\","
                      "\"usage\":{\"input_tokens\":100}}")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"usage figure is not a number"
                            (#'shuttle/parse-claude-json stdout)))))
  (testing "an absent usage object stays absent, never a thrown drift"
    (let [parsed (#'shuttle/parse-claude-json "{\"result\":\"ok\",\"session_id\":\"s\"}")]
      (is (not (contains? (:usage parsed) :tokens-total)))
      (is (not (contains? (:usage parsed) :cost-usd))))))

(deftest parse-codex-json-takes-last-cumulative-turn
  ;; codex's turn.completed usage is the cumulative session total, not a
  ;; per-turn delta (openai/codex#17539): the fixture carries two turn.completed
  ;; events and the run total is the LAST, never a sum. This is the opposite
  ;; fold from pi's per-turn deltas, so a copy of that logic would break here.
  (let [{:keys [result session-id usage]} (#'shuttle/parse-codex-json (fixture "codex-json.out"))
        {:keys [tokens]} usage]
    (testing "session id comes from thread.started and result is the last agent_message"
      (is (= "codex-thread-fixture" session-id))
      (is (= "Final sanitized codex result summary." result)
          "the trailing agent_message wins over the earlier partial"))
    (is (= "codex-json" (:usage-source usage)))
    (testing "tokens-total is the last turn's input+output, never a cross-turn sum"
      (is (= (+ 14437 320) (:tokens-total usage)))
      (is (= 14757 (:tokens-total usage)))
      (is (not= (+ 8200 14757) (:tokens-total usage))
          "22957 would be a sum across the two cumulative turns"))
    (testing "the breakdown splits cached out of input and keeps reasoning beside output"
      (is (= (- 14437 10112) (:input tokens)) "input is uncached = input_tokens - cached")
      (is (= 4325 (:input tokens)))
      (is (= 10112 (:cache-read tokens)))
      (is (= 320 (:output tokens)))
      (is (= 64 (:reasoning tokens))))
    (testing "codex reports no dollar cost, so a bare parse carries none"
      (is (not (contains? usage :cost-usd))))))

(deftest parse-codex-json-no-agent-message-is-blank
  ;; A stream with usage but no agent_message spent tokens yet produced no
  ;; report; :result must be blank (never the raw stream) so finish-run!'s exit-0
  ;; blank-result guard fires instead of closing a hollow done.
  (let [stdout (str/join "\n"
                         ["{\"type\":\"thread.started\",\"thread_id\":\"t-none\"}"
                          "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":0,\"output_tokens\":0,\"reasoning_output_tokens\":0}}"])
        {:keys [result session-id usage]} (#'shuttle/parse-codex-json stdout)]
    (is (str/blank? result) "no agent_message means a blank result, never the raw stream")
    (is (= "t-none" session-id) "session id and usage are still captured for forensics")
    (is (= 100 (:tokens-total usage)))))

(deftest parse-codex-json-non-numeric-usage-fails-loudly
  ;; codex usage drift (a token field as string/null) must fail loudly through
  ;; the shared normalize-usage path, never silently drop or price as zero.
  (let [stdout (str "{\"type\":\"turn.completed\",\"usage\":"
                    "{\"input_tokens\":\"lots\",\"cached_input_tokens\":0,\"output_tokens\":5,\"reasoning_output_tokens\":0}}")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"usage figure is not a number"
                          (#'shuttle/parse-codex-json stdout)))))

(deftest apply-cost-rates-derives-cost-from-token-split
  ;; A token-only usage tally gains cost-usd only when a rate card prices it; the
  ;; card is USD per 1M over the stored split, and usage-source is left as the
  ;; parser set it so a derived cost stays distinguishable from a reported one.
  (let [usage {:usage-source "codex-json" :tokens-total 14757
               :tokens {:input 4325 :cache-read 10112 :output 320 :reasoning 64}}
        rates {:input 1.25 :cache-read 0.125 :output 10.0}
        priced (#'shuttle/apply-cost-rates usage rates)
        expected (+ (* (/ 4325 1e6) 1.25)
                    (* (/ 10112 1e6) 0.125)
                    (* (/ 320 1e6) 10.0))]
    (is (about= expected (:cost-usd priced)))
    (is (= "codex-json" (:usage-source priced))
        "a card-derived cost keeps the parser's usage-source")
    (testing "reasoning is unpriced — it is a subset of output already counted"
      (is (= (:tokens usage) (:tokens priced))))
    (testing "no card leaves the tally untouched"
      (is (not (contains? (#'shuttle/apply-cost-rates usage nil) :cost-usd))))
    (testing "a provider-reported cost is never overwritten by a card"
      (let [reported (assoc usage :cost-usd 99.0)]
        (is (= 99.0 (:cost-usd (#'shuttle/apply-cost-rates reported rates))))))
    (testing "a card that prices no spent dimension leaves cost absent"
      (is (not (contains? (#'shuttle/apply-cost-rates {:usage-source "codex-json" :tokens {}}
                                                      rates)
                          :cost-usd))))))

(deftest parse-output-threads-usage-per-format
  (testing ":raw records nothing it cannot see — no :usage key"
    (let [parsed (#'shuttle/parse-output :raw "plain text output")]
      (is (= "plain text output" (:result parsed)))
      (is (not (contains? parsed :usage)))))
  (testing ":pi-json threads the folded usage"
    (let [parsed (#'shuttle/parse-output :pi-json (fixture "pi-json.out"))]
      (is (= "pi-json" (get-in parsed [:usage :usage-source])))))
  (testing ":claude-json threads the folded usage"
    (let [parsed (#'shuttle/parse-output :claude-json (fixture "claude-json.out"))]
      (is (= "claude-json" (get-in parsed [:usage :usage-source])))))
  (testing ":codex-json threads the cumulative-last usage"
    (let [parsed (#'shuttle/parse-output :codex-json (fixture "codex-json.out"))]
      (is (= "codex-json" (get-in parsed [:usage :usage-source]))))))

(deftest pi-json-terminal-error-run-fails-loudly
  (with-shuttle
    (fn [rt]
      (testing "a :pi-json harness whose turn errors is failed, not closed done"
        ;; sh stands in for pi: exit 0 while the event stream reports the
        ;; provider failure — exactly the live usage-limit incident shape.
        (shuttle/defharness! :sh-pi-err {:argv ["sh" "-c"] :parse :pi-json :preamble? false})
        (let [stream (str "printf '%s\\n' "
                          "'{\"type\":\"session\",\"id\":\"sess-err\"}' "
                          "'{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[],"
                          "\"stopReason\":\"error\",\"errorMessage\":\"Codex error: The usage limit has been reached\"}}'")
              run (shuttle/spawn-run! {:harness :sh-pi-err :prompt stream})
              failed (await-phase rt (:id run) #{"failed"})]
          (is (= "active" (:state failed)) "stays active so it is loud and retryable")
          (is (= "failed" (get-in failed [:attributes :agent-run/phase])))
          (is (zero? (get-in failed [:attributes :agent-run/exit-code])))
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "final turn errored"))
          (is (str/includes? (get-in failed [:attributes :agent-run/error]) "usage limit"))
          (is (= "sess-err" (get-in failed [:attributes :agent-run/session-id]))
              "session id is preserved for forensics"))))))

;; ---------------------------------------------------------------------------
;; Terminal-write seam (TASK-Ru-002): finish-run! writes the captured :usage
;; onto both terminal branches that have parsed output — the done branch and the
;; terminal-error branch — so a usage-limit failure still records its spend. A
;; :raw run and any unreported dimension write nothing (never a stored 0).

(deftest pi-json-completing-run-records-usage
  (with-shuttle
    (fn [rt]
      ;; sh stands in for pi: exit 0 with a clean event stream carrying a
      ;; message_end usage delta and its nested cost.total.
      (shuttle/defharness! :sh-pi-usage {:argv ["sh" "-c"] :parse :pi-json :preamble? false})
      (let [stream (str "printf '%s\\n' "
                        "'{\"type\":\"session\",\"id\":\"sess-u\"}' "
                        "'{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\","
                        "\"content\":[{\"type\":\"text\",\"text\":\"all done\"}],"
                        "\"usage\":{\"input\":100,\"output\":20,\"cacheRead\":5,"
                        "\"totalTokens\":125,\"cost\":{\"total\":0.0123}}}}'")
            run (shuttle/spawn-run! {:harness :sh-pi-usage :prompt stream})
            done (await-phase rt (:id run) #{"done"})
            attrs (:attributes done)]
        (is (= "closed" (:state done)))
        (is (= "all done" (:agent-run/result attrs)))
        (is (= "pi-json" (:agent-run/usage-source attrs)))
        (is (about= 0.0123 (:agent-run/cost-usd attrs)))
        (is (= 125 (:agent-run/tokens-total attrs)))
        (is (= 100 (get-in attrs [:agent-run/tokens :input])))
        (is (= 20 (get-in attrs [:agent-run/tokens :output])))
        (is (= 5 (get-in attrs [:agent-run/tokens :cache-read])))))))

(deftest claude-json-completing-run-records-usage-from-result-object
  (with-shuttle
    (fn [rt]
      (shuttle/defharness! :sh-claude-usage {:argv ["sh" "-c"] :parse :claude-json :preamble? false})
      (let [obj (str "{\"result\":\"all done\",\"session_id\":\"sc\","
                     "\"total_cost_usd\":0.5,"
                     "\"usage\":{\"input_tokens\":100,\"output_tokens\":40,"
                     "\"cache_read_input_tokens\":900}}")
            run (shuttle/spawn-run! {:harness :sh-claude-usage :prompt (str "printf '%s' '" obj "'")})
            done (await-phase rt (:id run) #{"done"})
            attrs (:attributes done)]
        (is (= "claude-json" (:agent-run/usage-source attrs)))
        (is (about= 0.5 (:agent-run/cost-usd attrs)))
        (is (= (+ 100 40 900) (:agent-run/tokens-total attrs)))
        (is (= 100 (get-in attrs [:agent-run/tokens :input])))
        (is (= 900 (get-in attrs [:agent-run/tokens :cache-read])))
        (is (not (contains? (:agent-run/tokens attrs) :cache-write))
            "cache_creation_input_tokens was absent, so cache-write is never zero-filled")))))

(deftest pi-json-terminal-error-run-still-records-its-cost
  (with-shuttle
    (fn [rt]
      ;; the highest-value runs to capture are exactly the usage-limit failures:
      ;; the turn errors (stopReason error) yet still reports the spend it made,
      ;; so mark-failed!'s extra map carries the usage onto the failed record.
      (shuttle/defharness! :sh-pi-err-usage {:argv ["sh" "-c"] :parse :pi-json :preamble? false})
      (let [stream (str "printf '%s\\n' "
                        "'{\"type\":\"session\",\"id\":\"se\"}' "
                        "'{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[],"
                        "\"stopReason\":\"error\",\"errorMessage\":\"usage limit reached\","
                        "\"usage\":{\"input\":50,\"output\":10,\"totalTokens\":60,\"cost\":{\"total\":0.02}}}}'")
            run (shuttle/spawn-run! {:harness :sh-pi-err-usage :prompt stream})
            failed (await-phase rt (:id run) #{"failed"})
            attrs (:attributes failed)]
        (is (= "active" (:state failed)) "still loud and retryable")
        (is (= "failed" (:agent-run/phase attrs)))
        (is (= "pi-json" (:agent-run/usage-source attrs)))
        (is (about= 0.02 (:agent-run/cost-usd attrs)))
        (is (= 60 (:agent-run/tokens-total attrs)))))))

(deftest pi-json-blank-result-run-preserves-its-usage
  (with-shuttle
    (fn [rt]
      ;; a tool-only assistant turn: pi exits 0 and reports usage but writes no
      ;; result text, so the run is failed as a hollow done. The tokens it spent
      ;; must still land, exactly like the done and terminal-error branches — a
      ;; textless run that burned budget can't be invisible to `agent spend`.
      (shuttle/defharness! :sh-pi-toolonly {:argv ["sh" "-c"] :parse :pi-json :preamble? false})
      (let [stream (str "printf '%s\\n' "
                        "'{\"type\":\"session\",\"id\":\"st\"}' "
                        "'{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\","
                        "\"content\":[{\"type\":\"toolcall\",\"id\":\"t1\"}],"
                        "\"usage\":{\"input\":80,\"output\":15,\"totalTokens\":95,\"cost\":{\"total\":0.03}}}}'")
            run (shuttle/spawn-run! {:harness :sh-pi-toolonly :prompt stream})
            failed (await-phase rt (:id run) #{"failed"})
            attrs (:attributes failed)]
        (is (= "active" (:state failed)) "loud and retryable")
        (is (= "failed" (:agent-run/phase attrs)))
        (is (str/includes? (:agent-run/error attrs) "empty result"))
        (is (= "pi-json" (:agent-run/usage-source attrs))
            "usage survives the blank-result branch")
        (is (about= 0.03 (:agent-run/cost-usd attrs)))
        (is (= 95 (:agent-run/tokens-total attrs)))))))

(deftest raw-completing-run-records-no-usage
  (with-shuttle
    (fn [rt]
      ;; the shipped :sh harness is :parse :raw — it sees no usage object, so it
      ;; records none rather than synthesizing zeros.
      (let [run (shuttle/spawn-run! {:harness :sh :prompt "echo plain-result"})
            done (await-phase rt (:id run) #{"done"})
            attrs (:attributes done)]
        (is (= "plain-result" (:agent-run/result attrs)))
        (is (not (contains? attrs :agent-run/usage-source)))
        (is (not (contains? attrs :agent-run/cost-usd)))
        (is (not (contains? attrs :agent-run/tokens-total)))
        (is (not (contains? attrs :agent-run/tokens)))))))

(deftest completing-run-with-nil-cost-omits-the-key-never-zero
  (with-shuttle
    (fn [rt]
      ;; a claude result object that reports tokens but no total_cost_usd: the
      ;; cost dimension is absent, so the key is omitted — never a stored 0.
      (shuttle/defharness! :sh-claude-nocost {:argv ["sh" "-c"] :parse :claude-json :preamble? false})
      (let [obj (str "{\"result\":\"done\",\"session_id\":\"sn\","
                     "\"usage\":{\"input_tokens\":100,\"output_tokens\":40}}")
            run (shuttle/spawn-run! {:harness :sh-claude-nocost :prompt (str "printf '%s' '" obj "'")})
            done (await-phase rt (:id run) #{"done"})
            attrs (:attributes done)]
        (is (not (contains? attrs :agent-run/cost-usd))
            "no total_cost_usd reported → the cost key is absent, never a stored 0")
        (is (= (+ 100 40) (:agent-run/tokens-total attrs)) "tokens are still captured")
        (is (= "claude-json" (:agent-run/usage-source attrs)))))))

(def ^:private codex-stream
  ;; one codex exec --json turn: thread id, an agent_message, and a cumulative
  ;; turn.completed usage. Uncached input = 2000 - 500 = 1500.
  (str "printf '%s\\n' "
       "'{\"type\":\"thread.started\",\"thread_id\":\"codex-e2e\"}' "
       "'{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"codex end to end\"}}' "
       "'{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":2000,\"cached_input_tokens\":500,"
       "\"output_tokens\":100,\"reasoning_output_tokens\":20}}'"))

(deftest codex-json-completing-run-derives-cost-from-rate-card
  (with-shuttle
    (fn [rt]
      ;; codex reports tokens but no dollar cost, so cost-usd is derived from the
      ;; seat's :cost-rates card over the token split.
      (shuttle/defharness! :sh-codex-rated
        {:argv ["sh" "-c"] :parse :codex-json :preamble? false
         :cost-rates {:input 1.25 :cache-read 0.125 :output 10.0}})
      (let [run (shuttle/spawn-run! {:harness :sh-codex-rated :prompt codex-stream})
            done (await-phase rt (:id run) #{"done"})
            attrs (:attributes done)]
        (is (= "closed" (:state done)))
        (is (= "codex end to end" (:agent-run/result attrs)))
        (is (= "codex-e2e" (:agent-run/session-id attrs)))
        (is (= "codex-json" (:agent-run/usage-source attrs))
            "the source stays codex-json so a card-derived cost is distinguishable")
        (is (= (+ 2000 100) (:agent-run/tokens-total attrs)))
        (is (= 1500 (get-in attrs [:agent-run/tokens :input])))
        (is (= 500 (get-in attrs [:agent-run/tokens :cache-read])))
        (is (= 100 (get-in attrs [:agent-run/tokens :output])))
        (is (= 20 (get-in attrs [:agent-run/tokens :reasoning])))
        (is (about= (+ (* (/ 1500 1e6) 1.25) (* (/ 500 1e6) 0.125) (* (/ 100 1e6) 10.0))
                    (:agent-run/cost-usd attrs)))))))

(deftest codex-json-run-without-rate-card-records-tokens-but-no-cost
  (with-shuttle
    (fn [rt]
      ;; a token-only parse with no declared rate card leaves cost-usd absent —
      ;; recorded tokens without cost beat a guessed number.
      (shuttle/defharness! :sh-codex-norate {:argv ["sh" "-c"] :parse :codex-json :preamble? false})
      (let [run (shuttle/spawn-run! {:harness :sh-codex-norate :prompt codex-stream})
            done (await-phase rt (:id run) #{"done"})
            attrs (:attributes done)]
        (is (= "codex-json" (:agent-run/usage-source attrs)))
        (is (= (+ 2000 100) (:agent-run/tokens-total attrs)) "tokens still land")
        (is (= 1500 (get-in attrs [:agent-run/tokens :input])))
        (is (not (contains? attrs :agent-run/cost-usd))
            "no rate card → cost is absent, never a guessed number")))))

(deftest codex-json-no-agent-message-run-fails-loudly
  (with-shuttle
    (fn [rt]
      ;; a codex stream that spends tokens but emits no agent_message is a hollow
      ;; done: the run is failed (loud, retryable) while its usage is preserved.
      (shuttle/defharness! :sh-codex-noresult
        {:argv ["sh" "-c"] :parse :codex-json :preamble? false
         :cost-rates {:input 1.25 :output 10.0}})
      (let [stream (str "printf '%s\\n' "
                        "'{\"type\":\"thread.started\",\"thread_id\":\"codex-none\"}' "
                        "'{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":300,"
                        "\"cached_input_tokens\":0,\"output_tokens\":40,\"reasoning_output_tokens\":0}}'")
            run (shuttle/spawn-run! {:harness :sh-codex-noresult :prompt stream})
            failed (await-phase rt (:id run) #{"failed"})
            attrs (:attributes failed)]
        (is (= "active" (:state failed)) "loud and retryable")
        (is (= "failed" (:agent-run/phase attrs)))
        (is (str/includes? (:agent-run/error attrs) "empty result"))
        (is (= "codex-none" (:agent-run/session-id attrs)) "session id survives the blank-result branch")
        (is (= (+ 300 40) (:agent-run/tokens-total attrs)) "spent tokens are preserved")
        (is (about= (+ (* (/ 300 1e6) 1.25) (* (/ 40 1e6) 10.0)) (:agent-run/cost-usd attrs))
            "derived cost rides onto the failed record too")))))

;; ---------------------------------------------------------------------------
;; Spend aggregation (PROP-Ru-001.C7)

(defn- add-spend-run!
  "Add a completed agent-run strand carrying the given spend attributes so the
  pure `spend` aggregation reads it without spawning a real harness process. A
  run with no :cost/:tokens-total is a raw/pre-feature run: it records only its
  timestamps."
  [rt {:keys [harness started finished cost tokens-total tokens]}]
  (weaver/add rt {:title (str harness "-run")
                  :state "closed"
                  :attributes (cond-> {"agent-run/run" "true"
                                       "agent-run/harness" harness
                                       "agent-run/phase" "done"
                                       "agent-run/started-at" started
                                       "agent-run/finished-at" finished}
                                cost (assoc "agent-run/cost-usd" cost)
                                tokens-total (assoc "agent-run/tokens-total" tokens-total)
                                tokens (assoc "agent-run/tokens" tokens))}))

(deftest spend-aggregates-totals-and-groups-by-harness
  (with-shuttle
    (fn [rt]
      (add-spend-run! rt {:harness "pi" :started "2026-07-08T10:00:00Z" :finished "2026-07-08T10:00:04Z"
                          :cost 0.10 :tokens-total 100 :tokens {"input" 60 "output" 40}})
      (add-spend-run! rt {:harness "pi" :started "2026-07-08T10:10:00Z" :finished "2026-07-08T10:10:06Z"
                          :cost 0.20 :tokens-total 200})
      (add-spend-run! rt {:harness "claude" :started "2026-07-08T10:20:00Z" :finished "2026-07-08T10:20:01Z"
                          :cost 0.05 :tokens-total 50})
      (let [{:keys [operation totals groups runs]} (shuttle/spend)
            by-key (into {} (map (juxt :key identity) groups))]
        (is (= "agent-spend" operation))
        (testing "totals sum every run's cost, tokens, and derived duration"
          (is (= 3 (:runs totals)))
          (is (about= 0.35 (:cost-usd totals)))
          (is (= 350 (:tokens-total totals)))
          (is (= 11000 (:duration-ms totals)))
          (is (= 3 (count runs))))
        (testing "the default grouping is per-harness"
          (is (= #{"pi" "claude"} (set (keys by-key))))
          (is (= 2 (get-in by-key ["pi" :runs])))
          (is (about= 0.30 (get-in by-key ["pi" :cost-usd])))
          (is (= 300 (get-in by-key ["pi" :tokens-total])))
          (is (= 10000 (get-in by-key ["pi" :duration-ms])))
          (is (= 1 (get-in by-key ["claude" :runs])))
          (is (about= 0.05 (get-in by-key ["claude" :cost-usd]))))
        (testing "a per-run row carries its nested token breakdown"
          (is (some :tokens runs)))))))

(deftest spend-raw-run-contributes-duration-and-count-with-null-cost
  (with-shuttle
    (fn [rt]
      (add-spend-run! rt {:harness "raw" :started "2026-07-08T10:00:00Z" :finished "2026-07-08T10:00:02Z"})
      (add-spend-run! rt {:harness "priced" :started "2026-07-08T10:10:00Z" :finished "2026-07-08T10:10:03Z"
                          :cost 0.10 :tokens-total 100})
      (let [{:keys [totals groups runs]} (shuttle/spend)
            by-key (into {} (map (juxt :key identity) groups))
            raw-row (first (filter #(= "raw" (:harness %)) runs))]
        (testing "the raw run adds its count and duration but no cost/tokens"
          (is (= 2 (:runs totals)))
          (is (about= 0.10 (:cost-usd totals)) "sums skip the raw run's absent cost")
          (is (= 100 (:tokens-total totals)))
          (is (= 5000 (:duration-ms totals))))
        (testing "the raw per-run row reports null cost/tokens, never 0"
          (is (nil? (:cost-usd raw-row)))
          (is (nil? (:tokens-total raw-row)))
          (is (= 2000 (:duration-ms raw-row))))
        (testing "a group of only raw runs sums to null cost, not 0"
          (is (nil? (get-in by-key ["raw" :cost-usd])))
          (is (nil? (get-in by-key ["raw" :tokens-total])))
          (is (= 2000 (get-in by-key ["raw" :duration-ms])))
          (is (about= 0.10 (get-in by-key ["priced" :cost-usd]))))))))

(deftest spend-buckets-by-day-and-windows-on-started-at
  (with-shuttle
    (fn [rt]
      (add-spend-run! rt {:harness "pi" :started "2026-07-08T10:00:00Z" :finished "2026-07-08T10:00:01Z"
                          :cost 0.10 :tokens-total 10})
      (add-spend-run! rt {:harness "pi" :started "2026-07-08T22:00:00Z" :finished "2026-07-08T22:00:02Z"
                          :cost 0.20 :tokens-total 20})
      (add-spend-run! rt {:harness "claude" :started "2026-07-09T09:00:00Z" :finished "2026-07-09T09:00:03Z"
                          :cost 0.30 :tokens-total 30})
      (testing ":group-by :day buckets by the started-at date"
        (let [by-key (into {} (map (juxt :key identity) (:groups (shuttle/spend {:group-by :day}))))]
          (is (= #{"2026-07-08" "2026-07-09"} (set (keys by-key))))
          (is (= 2 (get-in by-key ["2026-07-08" :runs])))
          (is (about= 0.30 (get-in by-key ["2026-07-08" :cost-usd])))
          (is (= 3000 (get-in by-key ["2026-07-08" :duration-ms])))
          (is (= 1 (get-in by-key ["2026-07-09" :runs])))))
      (testing ":since windows on started-at"
        (let [{:keys [totals runs]} (shuttle/spend {:since "2026-07-09T00:00:00Z"})]
          (is (= 1 (:runs totals)))
          (is (= "claude" (:harness (first runs))))))
      (testing ":until windows on started-at"
        (is (= 2 (:runs (:totals (shuttle/spend {:until "2026-07-08T23:59:59Z"}))))))
      (testing ":harness narrows to one harness"
        (is (= 2 (:runs (:totals (shuttle/spend {:harness "pi"})))))
        (is (zero? (:runs (:totals (shuttle/spend {:harness "absent"})))))))))

(deftest spend-scales-by-one-bulk-query-not-per-run
  ;; regression guard mirroring ps-summary-building-does-not-scale...: the spend
  ;; aggregation must read every run from a single bulk query, never one per run,
  ;; so its cost is independent of how many runs or unrelated strands exist
  ;; (PROP-Ru-001.R4).
  (with-shuttle
    (fn [rt]
      (dotimes [i 12]
        (add-spend-run! rt {:harness "pi"
                            :started (format "2026-07-08T10:%02d:00Z" i)
                            :finished (format "2026-07-08T10:%02d:01Z" i)
                            :cost 0.01 :tokens-total 10}))
      ;; unrelated strands the aggregation must never touch per-strand
      (dotimes [i 150] (weaver/add rt {:title (str "noise-" i)}))
      (let [list-calls (atom 0)
            real-list weaver/list
            counting-list (fn [& args]
                            (swap! list-calls inc)
                            (apply real-list args))
            result (#'shuttle/spend* {} counting-list)]
        (is (= 12 (:runs (:totals result))))
        (is (= 1 @list-calls)
            "one bulk query resolves every run's spend, independent of strand count")))))

;; ---------------------------------------------------------------------------
;; Fan-out window enforcement (TASK-Foc-001.V1)
;;
;; A gate harness is the shipped `sh` harness (`:preamble? false`) running a
;; script that blocks until its per-run sentinel file appears. A run stays
;; `running` until the test creates that file, so admission width is asserted
;; with no timing assumption. After each scan!-triggering mutation the tests
;; settle the event lane (admission runs on it) before reading in-flight state.

(defn- gate-dir! []
  (.toFile (java.nio.file.Files/createTempDirectory
            "skein-fanout-gate"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- gate-run!
  "Spawn a headless run that blocks until its sentinel file exists. `attrs`
  stamps fan-out group/cap fixtures — PH1 has no delegation flag, so V1 case 3
  stamps them via the spawn-run! :attrs path. Returns {:id id :sentinel path}."
  ([dir n] (gate-run! dir n {}))
  ([dir n attrs]
   (let [sentinel (.getPath (io/file dir (str "gate-" n)))
         ;; echo a non-blank result on release: an exit-0 run with empty output
         ;; fails loudly as an empty result, so the drain must end done, not failed.
         run (shuttle/spawn-run!
              {:harness :sh
               :prompt (str "until [ -f '" sentinel "' ]; do sleep 0.02; done; echo released")
               :attrs attrs})]
     {:id (:id run) :sentinel sentinel})))

(defn- release-gate! [gate] (spit (:sentinel gate) ""))

(defn- settle! [rt] (events/await-quiescent! rt))

(defn- await-in-flight-count
  "Poll (fail-loud budget) until exactly `n` runs are in flight; return the set."
  [n]
  (test-support/poll-until
   #(let [ids (shuttle/in-flight-run-ids)] (when (= n (count ids)) ids))
   {:on-timeout #(throw (ex-info "in-flight count never reached target"
                                 {:want n :in-flight (shuttle/in-flight-run-ids)}))}))

(defn- pending-ids [rt]
  (->> (weaver/list rt shuttle/run-query {})
       (filter #(= "pending" (get-in % [:attributes :agent-run/phase])))
       (map :id)
       set))

(defn- drain-gated!
  "Release every gate so no blocked sh process is left behind, wait for the
  engine to drop the gated runs from in-flight, then kill any interactive
  sessions the test held open."
  [_rt gates sessions]
  (doseq [g gates] (release-gate! g))
  (let [gate-ids (map :id gates)]
    (test-support/poll-until
     #(let [in-flight (shuttle/in-flight-run-ids)] (not-any? in-flight gate-ids))
     {:on-timeout #(throw (ex-info "gated runs never drained after release"
                                   {:in-flight (shuttle/in-flight-run-ids)}))}))
  (doseq [s sessions]
    (try (shuttle/kill! (:id (:run s))) (catch Throwable _ nil))))

(deftest window-caps-headless-width-at-the-ceiling
  ;; V1 case 1: ceiling 2, five gated runs -> exactly 2 admitted, 3 stay pending
  ;; with no window bookkeeping attribute (they never launched).
  (with-shuttle
    (fn [rt]
      (let [dir (gate-dir!)
            _ (shuttle/set-fanout-ceiling! 2)
            gates (mapv #(gate-run! dir %) (range 5))]
        (try
          (settle! rt)
          (let [in-flight (await-in-flight-count 2)]
            (is (= 2 (count in-flight)) "exactly ceiling runs admitted")
            (let [pending (pending-ids rt)]
              (is (= 3 (count pending)) "the rest stay pending")
              (doseq [id pending]
                (let [strand (weaver/show rt id)]
                  (is (= "pending" (get-in strand [:attributes :agent-run/phase])))
                  (is (nil? (get-in strand [:attributes :agent-run/started-at]))
                      "a deferred run writes no new attribute and arms nothing")))))
          (finally (drain-gated! rt gates nil)))))))

(deftest window-readmits-a-slot-on-completion-without-exceeding-width
  ;; V1 case 2: ceiling 2, five gated runs. Releasing one lets its close
  ;; re-admit the next through on-event; width never exceeds 2 across the drain.
  (with-shuttle
    (fn [rt]
      (let [dir (gate-dir!)
            _ (shuttle/set-fanout-ceiling! 2)
            gates (mapv #(gate-run! dir %) (range 5))
            by-id (into {} (map (juxt :id identity)) gates)]
        (settle! rt)
        (await-in-flight-count 2)
        ;; drain one at a time, releasing a currently-running run each pass; a
        ;; released run leaves in-flight and never returns (it is done, not
        ;; pending), so five passes retire all five runs.
        (dotimes [_ 5]
          (let [in-flight (shuttle/in-flight-run-ids)]
            (is (<= (count in-flight) 2) "width never exceeds the ceiling")
            (let [victim (first (filter by-id in-flight))]
              (release-gate! (by-id victim))
              (test-support/poll-until
               #(not (contains? (shuttle/in-flight-run-ids) victim))
               {:on-timeout #(throw (ex-info "released run never left in-flight"
                                             {:victim victim}))})
              (settle! rt))))
        (is (<= (count (shuttle/in-flight-run-ids)) 2))
        (doseq [g gates]
          (is (= "done" (get-in (weaver/show rt (:id g)) [:attributes :agent-run/phase]))
              "every gated run completes as the window drains"))))))

(deftest window-group-cap-tightens-below-the-ceiling
  ;; V1 case 3a: ceiling 4 but a fanout-cap 2 group admits only 2 (min(W, K)).
  (with-shuttle
    (fn [rt]
      (let [dir (gate-dir!)
            _ (shuttle/set-fanout-ceiling! 4)
            gates (mapv #(gate-run! dir % {"agent-run/fanout-group" "g1"
                                           "agent-run/fanout-cap" 2})
                        (range 3))]
        (try
          (settle! rt)
          (let [in-flight (await-in-flight-count 2)]
            (is (= 2 (count in-flight)) "group cap 2 tightens below the ceiling of 4")
            (is (= 1 (count (pending-ids rt)))))
          (finally (drain-gated! rt gates nil)))))))

(deftest window-group-cap-above-ceiling-stays-bounded-by-workspace-width
  ;; V1 case 3b: ceiling 4, fanout-cap 20 group -> min(4, 20) = 4 admitted; the
  ;; workspace width bounds the group, the cap never widens past it.
  (with-shuttle
    (fn [rt]
      (let [dir (gate-dir!)
            _ (shuttle/set-fanout-ceiling! 4)
            gates (mapv #(gate-run! dir % {"agent-run/fanout-group" "g2"
                                           "agent-run/fanout-cap" 20})
                        (range 6))]
        (try
          (settle! rt)
          (let [in-flight (await-in-flight-count 4)]
            (is (= 4 (count in-flight)) "min(ceiling 4, cap 20) = 4")
            (is (= 2 (count (pending-ids rt)))))
          (finally (drain-gated! rt gates nil)))))))

(deftest interactive-runs-consume-no-window-slot
  ;; V1 case 4: two interactive sessions running plus ceiling 2 still admits two
  ;; headless gated runs — interactive sessions are exempt and hold no slot.
  (with-shuttle
    (fn [rt]
      (let [dir (gate-dir!)
            _ (shuttle/set-fanout-ceiling! 2)
            sessions (mapv (fn [_] (spawn-interactive! rt)) (range 2))
            gates (mapv #(gate-run! dir %) (range 2))
            gate-ids (map :id gates)]
        (try
          (settle! rt)
          ;; two interactive + two headless are all tracked in-flight
          (await-in-flight-count 4)
          (test-support/poll-until
           #(let [in-flight (shuttle/in-flight-run-ids)] (every? in-flight gate-ids))
           {:on-timeout #(throw (ex-info "headless runs never admitted alongside interactive"
                                         {:in-flight (shuttle/in-flight-run-ids)
                                          :gate-ids gate-ids}))})
          (is (every? (shuttle/in-flight-run-ids) gate-ids)
              "both headless runs admit despite two interactive sessions and ceiling 2")
          (finally (drain-gated! rt gates sessions)))))))

(deftest fanout-ceiling-config-validates-and-survives-reload
  ;; V1 case 5: setter defaults to 4, rejects invalid input loudly, and a
  ;; configured ceiling survives a state-version reload through migrate-state.
  (with-shuttle
    (fn [rt]
      ;; fanout-ceiling is internal (the window's read); the setter is the config
      ;; surface, so the test reaches the getter through the var per repo idiom.
      (testing "default with no setter is 4"
        (is (= 4 (#'shuttle/fanout-ceiling))))
      (testing "the setter stores a positive integer and rejects the rest loudly"
        (is (= 3 (shuttle/set-fanout-ceiling! 3)))
        (is (= 3 (#'shuttle/fanout-ceiling)))
        (doseq [bad [0 -1 2.5 "4" nil]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                                (shuttle/set-fanout-ceiling! bad))))
        (is (= 3 (#'shuttle/fanout-ceiling)) "a rejected set leaves the prior ceiling intact"))
      (testing "a configured ceiling survives a state-version reload via migrate-state"
        ;; an untagged map has no stored version, so accessing state reinits
        ;; through migrate-state (as a post-bump reload of a preserved map would)
        (let [preserved (assoc (#'shuttle/new-state) :fanout-ceiling (atom 7))]
          (swap! (:spool-state rt) assoc :skein.spools.agent-run/state preserved)
          (is (= 7 (#'shuttle/fanout-ceiling))
              "migrate-state carries the configured ceiling forward")))
      (testing "a pre-feature map lacking the ceiling reinits to the default"
        (let [pre-v4 {:harness-registry (atom {})
                      :backend-registry (atom {})
                      :in-flight (atom {})
                      :preamble-extension (atom nil)
                      :default-review-contract (atom nil)}]
          (swap! (:spool-state rt) assoc :skein.spools.agent-run/state pre-v4)
          (is (= 4 (#'shuttle/fanout-ceiling))
              "select-keys omits the absent key so new-state's default survives"))))))

(deftest coherent-fanout-validates-the-contract-shapes
  ;; V1 fail-loud: the window treats a run's group/cap as a coherent contract.
  ;; Only two shapes are legal — ungrouped, or a non-blank group with a
  ;; positive-integer cap; every other combination fails loudly with the run id
  ;; and offending values so malformed data cannot silently widen (TEN-003).
  (testing "legal shapes pass and return [group cap]"
    (is (= [nil nil] (#'shuttle/coherent-fanout "r" nil nil)))
    (is (= ["g" 2] (#'shuttle/coherent-fanout "r" "g" 2))))
  (testing "incoherent shapes fail loudly, carrying the run id and offending values"
    (doseq [[group cap] [["g" nil]    ; group without a cap
                         ["g" 0]      ; non-positive cap
                         ["g" -1]
                         ["g" "2"]    ; non-integer cap
                         ["  " 2]     ; blank group
                         [nil 3]]]    ; cap without a group
      (let [ex (try (#'shuttle/coherent-fanout "run-x" group cap)
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo ex)
            (str "incoherent " [group cap] " must fail loudly"))
        (is (re-find #"incoherent" (ex-message ex)))
        (is (= {:run-id "run-x" :fanout-group group :fanout-cap cap}
               (ex-data ex))
            "the offending run id and values are in ex-data")))))

(deftest malformed-fanout-fails-its-own-run-without-disturbing-siblings
  ;; V1 fail-loud, end to end: a grouped run whose persisted cap is malformed
  ;; reaches admission and fails THAT run loudly, while a coherent sibling in the
  ;; same scan admits and runs — one bad record never aborts the scan (TEN-003).
  (with-shuttle
    (fn [rt]
      (let [dir (gate-dir!)
            _ (shuttle/set-fanout-ceiling! 4)
            malformed (gate-run! dir "bad" {"agent-run/fanout-group" "grp"
                                            "agent-run/fanout-cap" 0})
            sibling (gate-run! dir "ok")]
        (try
          (settle! rt)
          (let [failed (await-phase rt (:id malformed) #{"failed"})
                error (get-in failed [:attributes :agent-run/error])]
            (is (re-find #"incoherent" error)
                "the malformed run fails loudly with the contract message")
            (is (re-find #":fanout-cap 0" error)
                "the offending cap value is recorded on the failed run")
            (is (not (contains? (shuttle/in-flight-run-ids) (:id malformed)))
                "the failed run never entered the window"))
          (test-support/poll-until
           #(contains? (shuttle/in-flight-run-ids) (:id sibling))
           {:on-timeout #(throw (ex-info "coherent sibling never admitted"
                                         {:in-flight (shuttle/in-flight-run-ids)}))})
          (is (contains? (shuttle/in-flight-run-ids) (:id sibling))
              "the coherent sibling admits despite its malformed peer")
          (finally (drain-gated! rt [sibling] nil)))))))
