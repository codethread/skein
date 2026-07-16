(ns skein.delegation-test
  "Tests for the delegation spool layered over the agent-run engine."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.graph.alpha :as graph]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.delegation :as agents]
            [skein.spools.agent-run :as shuttle]
            [skein.spools.test-support :as test-support :refer [await-phase]]
            [skein.test.alpha :as t]))

(defn- with-agents
  "Run f with a fresh weaver runtime that has the agent-run and delegation spools installed.

  Nests config-dir under a `.skein` child of the temp root
  (`test-support`'s `:nest-skein? true` opt): delegation/agent-run derive the
  worktree root from config-dir's parent, matching the real repo-root/.skein
  layout, so an unnested config-dir would report the wrong workspace root."
  [f]
  (test-support/with-runtime
    {:nest-skein? true :prefix "skein-agents-config"}
    (fn [rt _config-dir]
      (shuttle/install!)
      (agents/install!)
      (f rt))))

(defn- seed-run!
  "Add a completed agent-run strand carrying the usage attributes the spend
  aggregation reads. A nil cost-usd/tokens-total/tokens value is omitted, so the
  seeded run models a format that reported no figure for that dimension."
  [rt {:keys [harness phase cost-usd tokens-total tokens started finished]}]
  (weaver/add rt {:title (str harness " run")
                  :attributes (cond-> {:agent-run/run "true"
                                       :agent-run/harness harness
                                       :agent-run/phase (or phase "done")
                                       :agent-run/started-at started
                                       :agent-run/finished-at finished}
                                (some? cost-usd) (assoc :agent-run/cost-usd cost-usd)
                                (some? tokens-total) (assoc :agent-run/tokens-total tokens-total)
                                (some? tokens) (assoc :agent-run/tokens tokens))}))

(defn- wire-value [value]
  (json/read-str (json/write-str value) :key-fn keyword))

(deftest production-return-coverage-is-derived-from-delegation-provenance
  (with-agents
    (fn [rt]
      (let [entries (filterv #(= 'skein.spools.delegation (:provenance %)) (weaver/ops rt))
            missing (mapv :name (filter #(not (contains? % :returns)) entries))
            required (into #{} (mapcat (fn [{:keys [name returns]}]
                                         (for [subcommand (keys (:subcommands returns))]
                                           [name {:subcommand subcommand}]))) entries)
            run {:id "run-1" :title "run" :state "active" :phase "pending" :harness "sh"}
            representatives
            {"about" (assoc (agents/agent-op {:op/argv ["about"]}) :operation "agent about")
             "spawn" (assoc run :operation "agent spawn")
             "ps" [run]
             "spend" {:operation "agent spend" :filters {} :totals {} :groups [] :runs []}
             "await" {:operation "agent await" :timed-out false :runs [run]}
             "logs" {:operation "agent logs" :id "run-1" :out {:path "/tmp/out" :text "ok"}}
             "kill" {:operation "agent kill" :killed "run-1"}
             "harnesses" [{:name "sh" :kind "harness"}]
             "backends" [{:name "tmux" :ops ["start" "alive"]}]
             "delegate" {:operation "agent delegate" :task "task-1"
                         :run {:id "run-1" :phase "pending" :harness "sh"}}
             "retry" {:operation "agent retry" :superseded "run-0"
                      :run {:id "run-1" :phase "pending" :harness "sh"}}
             "status" {:operation "agent status" :tree [] :ready [] :running [] :failed []
                       :awaiting_verification [] :blocked []}
             "note" {:operation "agent note" :id "note-1" :note "finding"}
             "notes" [{:id "note-1" :note "finding"}]
             "review" {:operation "agent review" :target "task-1" :reviewers ["run-1"]}
             "rosters" [{:name "repo" :seats []}]
             "council" {:operation "agent council" :blackboard "board-1" :turns [["run-1"]]}}
            checked (into #{}
                          (map (fn [[subcommand value]]
                                 (t/check-op-return! rt 'agent {:subcommand subcommand}
                                                     (wire-value value))
                                 ["agent" {:subcommand subcommand}]))
                          representatives)]
        (is (= [] missing))
        (is (= #{} (set/difference required checked)))))))

(deftest agents-install-registers-op-pattern-query
  (with-agents
    (fn [rt]
      (is (some #(= "agent" (:name %)) (weaver/ops rt)))
      (is (map? (agents/agent-op {:op/argv ["about"]})))
      (let [detail (weaver/resolve-op rt 'agent)]
        (is (not (contains? detail :raw-envelope)))
        (is (= ["about" "await" "backends" "council" "delegate" "harnesses" "kill" "logs" "note" "notes" "ps" "retry" "review" "rosters" "spawn" "spend" "status"]
               (sort (keys (get-in detail [:arg-spec :subcommands])))))
        (let [review-flags (get-in detail [:arg-spec :subcommands "review" :flags])
              manual-entries (->> agents/about-doc :verbs vals)
              manual-verbs (set (map :verb manual-entries))
              declared-verbs (set (keys (get-in detail [:arg-spec :subcommands])))]
          (is (every? string? manual-verbs)
              "every agent about-doc verb entry must carry a string :verb")
          (is (empty? (set/difference manual-verbs declared-verbs))
              (str "about-doc verbs missing from arg-spec: " (sort (set/difference manual-verbs declared-verbs))))
          (is (empty? (set/difference declared-verbs manual-verbs))
              (str "arg-spec subcommands missing from about-doc: " (sort (set/difference declared-verbs manual-verbs))))
          (is (contains? review-flags :commit-range))
          (is (contains? review-flags :changed-files)))))))

(deftest agents-install-declares-review-and-panel-vocab
  (with-agents
    (fn [rt]
      (let [review (vocab/declaration rt :attr-namespace "review")
            panel (vocab/declaration rt :attr-namespace "panel")]
        (is (= :skein/spools-delegation (:owner review))
            "review/* is owned by the delegation spool's use-key")
        (is (= :skein/spools-delegation (:owner panel))
            "panel/* is owned by the delegation spool's use-key")
        (is (contains? (set (:keys review)) "review/focus"))
        (is (contains? (set (:keys panel)) "panel/seat"))
        (is (contains? (set (:keys panel)) "panel/blackboard")
            "the deliberation board is panel's noun, not review's")))))

(deftest agent-run-install-declares-agent-run-vocab
  (with-agents
    (fn [rt]
      (let [decl (vocab/declaration rt :attr-namespace "agent-run")]
        (is (= :attr-namespace (:kind decl)))
        (is (= :skein/spools-shuttle (:owner decl))
            "agent-run/* is owned by the agent-run spool's use-key")
        (is (contains? (set (:keys decl)) "agent-run/run"))))))

(deftest agent-op-dispatches-and-fails-loudly
  (with-agents
    (fn [_]
      (testing "about is explicit and carries the manual"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing subcommand"
                              (agents/agent-op {:op/argv []})))
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
                (agents/agent-op {:op/argv ["await" (:id spawned) "--timeout-secs" (str (test-support/await-budget-secs))]})]
            (is (false? timed-out))
            (is (= "via-op" (:result (first runs)))))
          (agents/agent-op {:op/argv ["note" (:id spawned) "op note" "--by" (:id spawned)]})
          (is (= ["op note"]
                 (mapv :note (agents/agent-op {:op/argv ["notes" (:id spawned)]}))))
          (is (pos? (count (agents/agent-op {:op/argv ["ps"]}))))
          (is (vector? (agents/agent-op {:op/argv ["ps" "--active"]})))
          (is (= [] (agents/agent-op {:op/argv ["ps" "--active" "--for" "no-such-strand"]})))))
      (testing "invalid input fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subcommand"
                              (agents/agent-op {:op/argv ["dance"]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown flag"
                              (agents/agent-op {:op/argv ["spawn" "--nope" "x"]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --harness"
                              (agents/agent-op {:op/argv ["spawn" "--prompt" "x"]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer"
                              (agents/agent-op {:op/argv ["await" "id" "--timeout-secs" "soon"]})))))))

(deftest agent-note-attr-decoration-round-trips
  (with-agents
    (fn [rt]
      (testing "--attr decorates the note strand and reads back intact beside --by"
        (let [target (weaver/add rt {:title "note target"})]
          (agents/agent-op {:op/argv ["note" (:id target) "decision recorded"
                                      "--attr" "note/kind=decision"
                                      "--attr" "panel/pass=p1"
                                      "--by" "run-x"]})
          (let [[note] (agents/agent-op {:op/argv ["notes" (:id target)]})
                strand (weaver/show rt (:id note))]
            (is (= "decision recorded" (:note note)))
            (is (= "run-x" (:by note)))
            (is (= "decision" (get-in strand [:attributes :note/kind])))
            (is (= "p1" (get-in strand [:attributes :panel/pass]))))))
      (testing "a --attr spec without key=value fails loudly"
        (let [target (weaver/add rt {:title "bad attr target"})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed --attr"
                                (agents/agent-op {:op/argv ["note" (:id target) "x" "--attr" "novalue"]})))))
      (testing "a repeated --attr key fails loudly, matching strand note's contract"
        (let [target (weaver/add rt {:title "dup attr target"})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate attribute key in --attr"
                                (agents/agent-op {:op/argv ["note" (:id target) "x"
                                                            "--attr" "note/kind=a"
                                                            "--attr" "note/kind=b"]}))))))))

(deftest agent-spend-aggregates-recorded-usage
  (with-agents
    (fn [rt]
      (seed-run! rt {:harness "claude" :cost-usd 1.5 :tokens-total 1000
                     :tokens {:input 600 :output 400}
                     :started "2026-07-01T10:00:00Z" :finished "2026-07-01T10:05:00Z"})
      (seed-run! rt {:harness "claude" :cost-usd 0.5 :tokens-total 200
                     :started "2026-07-02T09:00:00Z" :finished "2026-07-02T09:01:00Z"})
      (seed-run! rt {:harness "pi" :cost-usd 2.0 :tokens-total 500
                     :started "2026-07-01T11:00:00Z" :finished "2026-07-01T11:10:00Z"})
      ;; a raw run reports no cost/tokens, only its timestamp-derived duration
      (seed-run! rt {:harness "raw"
                     :started "2026-07-03T08:00:00Z" :finished "2026-07-03T08:02:00Z"})
      (testing "default report groups by harness and totals every run"
        (let [report (weaver/op! rt 'agent ["spend"])
              groups (into {} (map (juxt :key identity)) (:groups report))]
          (is (= "agent spend" (:operation report)))
          (is (= :harness (get-in report [:filters :group-by])))
          (is (= 4 (get-in report [:totals :runs])))
          (is (== 4.0 (get-in report [:totals :cost-usd])))
          (is (== 1700 (get-in report [:totals :tokens-total])))
          (is (== 1080000 (get-in report [:totals :duration-ms])))
          (is (= 4 (count (:runs report))))
          (is (= 2 (get-in groups ["claude" :runs])))
          (is (== 2.0 (get-in groups ["claude" :cost-usd])))
          (is (== 2.0 (get-in groups ["pi" :cost-usd])))))
      (testing "a run missing cost/tokens contributes null, never 0"
        (let [report (agents/agent-op {:op/argv ["spend" "--harness" "raw"]})]
          (is (= 1 (get-in report [:totals :runs])))
          (is (nil? (get-in report [:totals :cost-usd]))
              "an absent cost is null, not a summed 0")
          (is (nil? (get-in report [:totals :tokens-total])))
          (is (== 120000 (get-in report [:totals :duration-ms]))
              "duration is derived from timestamps even with no cost/tokens")))
      (testing "--harness narrows to one harness"
        (let [report (weaver/op! rt 'agent ["spend" "--harness" "claude"])]
          (is (= "claude" (get-in report [:filters :harness])))
          (is (= 2 (get-in report [:totals :runs])))
          (is (== 2.0 (get-in report [:totals :cost-usd])))))
      (testing "--since/--until window on started-at"
        (let [report (weaver/op! rt 'agent ["spend"
                                            "--since" "2026-07-02T00:00:00Z"
                                            "--until" "2026-07-02T23:59:59Z"])]
          (is (= "2026-07-02T00:00:00Z" (get-in report [:filters :since])))
          (is (= "2026-07-02T23:59:59Z" (get-in report [:filters :until])))
          (is (= 1 (get-in report [:totals :runs])))
          (is (== 0.5 (get-in report [:totals :cost-usd])))))
      (testing "--group-by day rebuckets by calendar day"
        (let [report (weaver/op! rt 'agent ["spend" "--group-by" "day"])
              groups (into {} (map (juxt :key identity)) (:groups report))]
          (is (= :day (get-in report [:filters :group-by])))
          (is (= 2 (get-in groups ["2026-07-01" :runs])))
          (is (== 3.5 (get-in groups ["2026-07-01" :cost-usd])))
          (is (= 1 (get-in groups ["2026-07-02" :runs])))
          (is (= 1 (get-in groups ["2026-07-03" :runs])))))
      (testing "an unknown --group-by fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"group-by"
                              (weaver/op! rt 'agent ["spend" "--group-by" "week"]))))
      (testing "a malformed --since/--until fails loudly instead of a silent lexical window"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ISO-8601 instant"
                              (weaver/op! rt 'agent ["spend" "--since" "yesterday"])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ISO-8601 instant"
                              (weaver/op! rt 'agent ["spend" "--until" "2026-07-02"])))))))

(deftest spawn-for-creates-task-edge
  (with-agents
    (fn [rt]
      (let [task (weaver/add rt {:title "served task"})
            run (agents/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "echo ok" "--for" (:id task)]})]
        (is (= (:id task) (:for run)))
        (is (some #(and (= "parent-of" (:edge_type %)) (= (:id run) (:to_strand_id %)))
                  (:edges (graph/subgraph rt [(:id task)]))))))))

(deftest agent-logs-read-output-and-error-files
  (with-agents
    (fn [_]
      (let [spawned (agents/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "printf 'a\\nb\\n'; printf 'e\\n' >&2"]})]
        (agents/agent-op {:op/argv ["await" (:id spawned) "--timeout-secs" (str (test-support/await-budget-secs))]})
        (let [logs (agents/agent-op {:op/argv ["logs" (:id spawned) "--tail" "1"]})]
          (is (= "b" (get-in logs [:out :text])))
          (is (= "e" (get-in logs [:err :text]))))))))

(deftest review-spawns-independent-reviewers
  (with-agents
    (fn [rt]
      (let [target (weaver/add rt {:title "Review target" :attributes {:body "Inspect me"}})
            review (agents/review! (:id target) {:reviewers [{:harness :sh :focus "correctness"}
                                                             {:harness :sh :focus "tests"}]
                                                 :contract "Review contract"})]
        (is (= (:id target) (:target review)))
        (is (= 2 (count (:reviewers review))))
        (is (nil? (:synthesizer review)))
        (let [cwd-review (agents/review! (:id target) {:reviewers [{:harness :sh :focus "cwd pass"}]
                                                       :cwd "/tmp/claude/review-cwd"})
              run (weaver/show rt (first (:reviewers cwd-review)))]
          (is (= "/tmp/claude/review-cwd" (get-in run [:attributes :agent-run/cwd]))
              "review --cwd rides onto each reviewer run"))
        (doseq [run-id (:reviewers review)]
          (let [run (weaver/show rt run-id)]
            (is (= (:id target) (get-in run [:attributes :panel/blackboard])))
            (is (str/includes? (get-in run [:attributes :agent-run/prompt]) "Review contract"))
            (is (str/includes? (get-in run [:attributes :agent-run/prompt])
                               "--attr note/kind=review-dump")
                "reviewer prompts instruct the review-dump view hint")))))))

(deftest review-rejects-kanban-card-targets
  ;; findings append as notes on the review target; card notes stay lean for
  ;; handover, so a card-targeted review must fail toward the card's task tier
  (with-agents
    (fn [rt]
      (let [card (weaver/add rt {:title "Feature card"
                                 :attributes {:kanban/card "true"
                                              :kanban/status "claimed"}})
            task (weaver/add rt {:title "Review-bearing task"
                                 :attributes {:kanban/task "true"}})]
        (let [rejected (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                             #"never a kanban card"
                                             (agents/review! (:id card)
                                                             {:reviewers [{:harness :sh :focus "any"}]
                                                              :contract "C"})))]
          (is (str/includes? (ex-message rejected) "kanban task add")
              "the failure names the task-tier remediation"))
        (testing "a kanban task target stays reviewable"
          (let [review (agents/review! (:id task) {:reviewers [{:harness :sh :focus "any"}]
                                                   :contract "C"})]
            (is (= (:id task) (:target review)))))
        (testing "a :target-blackboard panel rejects a kanban card the same way"
          ;; seat notes accumulate on the blackboard, so a card board is the
          ;; same pollution vector the review guard closes
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"never a kanban card"
                                (agents/panel! {:seats [{:name "a" :harness :sh :brief "b"}]
                                                :blackboard :target}
                                               {:target (:id card)}))))))))

(deftest review-consumes-workspace-default-contract
  (with-agents
    (fn [rt]
      (try
        (shuttle/set-default-review-contract! "Workspace policy contract")
        (let [target (weaver/add rt {:title "Default-contract target"})
              review (agents/review! (:id target) {:reviewers [{:harness :sh :focus "policy"}]})
              run (weaver/show rt (first (:reviewers review)))]
          (is (str/includes? (get-in run [:attributes :agent-run/prompt])
                             "Workspace policy contract"))
          (let [review* (agents/review! (:id target) {:reviewers [{:harness :sh :focus "override"}]
                                                      :contract "Explicit contract"})
                run* (weaver/show rt (first (:reviewers review*)))]
            (is (str/includes? (get-in run* [:attributes :agent-run/prompt]) "Explicit contract"))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                              (shuttle/set-default-review-contract! "  ")))
        (finally
          (shuttle/set-default-review-contract! nil))))))

(deftest defroster-validates-and-lists-rosters
  (with-agents
    (fn [_]
      (testing "registration returns a summary and rosters lists full data"
        (is (= {:roster :repo :seats 2}
               (agents/defroster! "repo"
                 {:seats [{:name "tests" :harness :sh :brief "Judge the tests." :scope "test files"}
                          {:name "docs" :harness "sh" :brief "Judge the docs."}]
                  :synthesis {:harness :sh}})))
        (let [[roster] (agents/rosters)]
          (is (= :repo (:name roster)))
          (is (= ["tests" "docs"] (mapv :name (:seats roster))))
          (is (= {:harness :sh} (:synthesis roster)))))
      (testing "re-registration replaces the roster"
        (agents/defroster! :repo {:seats [{:name "solo" :harness :sh :brief "One pass."}]})
        (is (= ["solo"] (mapv :name (:seats (first (agents/rosters)))))))
      (testing "the roster shape is spec-defined and registered data conforms"
        (is (s/valid? :skein.spools.delegation/roster
                      {:seats [{:name "solo" :harness :sh :brief "One pass."}]})))
      (testing "structurally malformed roster data fails loudly via the spec"
        (doseq [bad [[:not-a-map]
                     {:seats []}
                     {:seats [{:harness :sh :brief "c"}]}
                     {:seats [{:name "r" :brief "c"}]}
                     {:seats [{:name "r" :harness :sh}]}
                     {:seats [{:name "r" :harness :sh :brief "c" :scope "  "}]}
                     {:seats [{:name "r" :harness :sh :brief "c"}] :synthesis :sh}
                     {:seats [{:name "r" :harness :sh :brief "c"}] :synthesis {}}]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not conform to spec"
                                (agents/defroster! :bad bad)))))
      (testing "checks the spec cannot express stay loud"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (agents/defroster! :bad {:seats [{:name "r" :harness :sh :brief "c" :breif "typo"}]})))
        ;; a typo REPLACING a required key must diagnose as the unknown key,
        ;; not as the missing-key spec explain it also causes
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (agents/defroster! :bad {:seats [{:name "r" :harness :sh :breif "c"}]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (agents/defroster! :bad {:seats [{:name "r" :harness :sh :brief "c"}] :extra true})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (agents/defroster! :bad {:seats [{:name "r" :harness :sh :brief "c"}]
                                                       :synthesis {:harness :sh :model "x"}})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be unique"
                              (agents/defroster! :bad {:seats [{:name "r" :harness :sh :brief "a"}
                                                               {:name "r" :harness :sh :brief "b"}]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keyword or non-blank string"
                              (agents/defroster! "  " {:seats [{:name "r" :harness :sh :brief "c"}]})))))))

(deftest roster-review-fans-out-declared-reviewers
  (with-agents
    (fn [rt]
      (agents/defroster! :repo
        {:seats [{:name "test-sleeps" :harness :sh
                  :brief "Flag sleeps and arbitrary timeouts in tests."
                  :scope "test files"}
                 {:name "docs" :harness :sh :brief "Judge documentation drift."}]
         :synthesis {:harness :sh}})
      (let [target (weaver/add rt {:title "Roster target" :attributes {:body "Inspect me"}})
            review (agents/review! (:id target) {:roster :repo :cwd "/tmp/claude/roster-cwd"})
            runs (mapv #(weaver/show rt %) (:reviewers review))
            synth (weaver/show rt (:synthesizer review))]
        (testing "one run per roster entry, each with its own contract and scope"
          (is (= 2 (count runs)))
          (is (= ["test-sleeps" "docs"]
                 (mapv #(get-in % [:attributes :review/focus]) runs)))
          (let [[sleeps docs] (mapv #(get-in % [:attributes :agent-run/prompt]) runs)]
            (is (str/includes? sleeps "Flag sleeps and arbitrary timeouts in tests."))
            (is (str/includes? sleeps "Scope: confine this review to test files"))
            (is (str/includes? sleeps (shuttle/default-review-contract-text))
                "entry contracts layer onto the workspace base contract")
            (is (str/includes? docs "Judge documentation drift."))
            (is (not (str/includes? docs "Scope:")))))
        (testing "roster name is stamped on every spawned run"
          (is (every? #(= "repo" (get-in % [:attributes :review/roster]))
                      (conj runs synth))))
        (testing "roster reviews synthesize by default with the declared harness and base contract"
          (is (= "sh" (get-in synth [:attributes :agent-run/harness])))
          (is (str/includes? (get-in synth [:attributes :agent-run/prompt])
                             (shuttle/default-review-contract-text)))
          (is (not (str/includes? (get-in synth [:attributes :agent-run/prompt])
                                  "Flag sleeps and arbitrary timeouts")))
          (is (= (set (:reviewers review))
                 (->> (:edges (graph/subgraph rt [(:id synth)] {:type "depends-on"}))
                      (filter #(= (:id synth) (:from_strand_id %)))
                      (map :to_strand_id)
                      set))))
        (testing "without a declared synthesis the first seat's harness is used"
          (agents/defroster! :undeclared
            {:seats [{:name "solo" :harness :sh :brief "One pass."}]})
          (let [review* (agents/review! (:id target) {:roster :undeclared})
                synth* (weaver/show rt (:synthesizer review*))]
            (is (= "sh" (get-in synth* [:attributes :agent-run/harness])))))))))

(deftest roster-review-specs-are-the-single-prompt-source
  (with-agents
    (fn [rt]
      (agents/defroster! :composed
        {:seats [{:name "a" :harness :sh :brief "Brief A." :scope "src"}
                 {:name "b" :harness :sh :brief "Brief B."}]
         :synthesis {:harness :sh}})
      (let [target (weaver/add rt {:title "Spec target"})
            review (agents/review! (:id target) {:roster :composed})
            specs (agents/roster-review-specs :composed {:target (:id target)
                                                         :review-id (:pass review)})
            runs (mapv #(weaver/show rt %) (:reviewers review))
            synth (weaver/show rt (:synthesizer review))]
        (testing "specs are gate-ready plain data"
          (is (= ["a" "b"] (mapv :name (:reviewers specs))))
          (is (every? #(and (string? (:prompt %)) (map? (:attrs %))) (:reviewers specs)))
          (is (string? (get-in specs [:synthesizer :prompt]))
              "synthesis prompt is buildable before any run exists"))
        (testing "review! spawns exactly the spec prompts and attrs"
          (is (= (mapv :prompt (:reviewers specs))
                 (mapv #(get-in % [:attributes :agent-run/prompt]) runs)))
          (is (= (get-in specs [:synthesizer :prompt])
                 (get-in synth [:attributes :agent-run/prompt])))
          (doseq [[spec run] (map vector (:reviewers specs) runs)
                  [k v] (:attrs spec)]
            (is (= v (get-in run [:attributes (keyword k)])))))
        (testing "the pass tag threads notes together and separates rounds"
          (is (str/includes? (get (first (:reviewers specs)) :prompt)
                             (str "--attr panel/pass=" (:pass review))))
          (is (str/includes? (get-in specs [:synthesizer :prompt])
                             (str "--attr panel/pass=" (:pass review))))
          (is (str/includes? (get (first (:reviewers specs)) :prompt)
                             "--attr note/kind=review-dump")
              "reviewer findings carry the review-dump view hint")
          (is (str/includes? (get-in specs [:synthesizer :prompt])
                             "--attr note/kind=summary")
              "the synthesis note carries the summary view hint")
          (is (not= (:pass (agents/roster-review-specs :composed {:target (:id target)}))
                    (:pass (agents/roster-review-specs :composed {:target (:id target)})))
              "each pass mints a distinct tag"))
        (testing "the seam output conforms to its public spec"
          (is (s/valid? :skein.spools.delegation/review-specs specs)
              (s/explain-str :skein.spools.delegation/review-specs specs)))
        (testing "an inline roster value works anywhere a name does"
          (let [inline {:seats [{:name "adhoc" :harness :sh :brief "One-off pass."}]}
                inline-specs (agents/roster-review-specs inline {:target (:id target)})
                inline-review (agents/review! (:id target) {:roster inline})
                run (weaver/show rt (first (:reviewers inline-review)))]
            (is (= :inline (:roster inline-specs)))
            (is (s/valid? :skein.spools.delegation/review-specs inline-specs))
            (is (= "inline" (get-in run [:attributes :review/roster])))
            (is (str/includes? (get-in run [:attributes :agent-run/prompt]) "One-off pass."))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not conform to spec"
                                  (agents/roster-review-specs {:seats []} {:target (:id target)})))))
        (testing "specs fail loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank :target"
                                (agents/roster-review-specs :composed {})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #":review-id must be a non-blank"
                                (agents/roster-review-specs :composed {:target (:id target) :review-id " "})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Roster not found"
                                (agents/roster-review-specs :missing {:target (:id target)}))))))))

(deftest roster-review-fails-loudly
  (with-agents
    (fn [rt]
      (agents/defroster! :repo {:seats [{:name "solo" :harness :sh :brief "One pass."}]})
      (let [target (weaver/add rt {:title "Roster failure target"})]
        (testing "unknown roster"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Roster not found"
                                (agents/review! (:id target) {:roster :nope}))))
        (testing "roster is the one authoritative source of reviewer settings"
          (doseq [conflicting [{:seat-count 3} {:harnesses ["sh"]} {:contract "c"} {:reviewers [{:harness :sh}]}]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"from the roster"
                                  (agents/review! (:id target) (merge {:roster :repo} conflicting))))))
        (testing "CLI flag conflicts surface through the op"
          (doseq [flag-pair [["--seats" "3"] ["--harness" "sh"] ["--contract" "c"]]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"from the roster"
                                  (agents/agent-op {:op/argv (into ["review" (:id target) "--roster" "repo"] flag-pair)})))))
        (testing "rosters verb lists and rejects arguments"
          (is (= [:repo] (mapv :name (agents/agent-op {:op/argv ["rosters"]}))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unexpected extra arguments"
                                (agents/agent-op {:op/argv ["rosters" "extra"]}))))))))

(deftest blackboard-fragments-emit-both-forms
  (testing "seat identity locates the seat in the panel grid"
    (is (= "You are seat 2 of 3, turn 1 of 4.\n"
           (#'agents/seat-identity-fragment {:seat 2 :seats 3 :turn 1 :turns 4})))
    (is (= "Continuing as seat 2 of 3, now on turn 2 of 4.\n"
           (#'agents/seat-identity-fragment {:form :continuation :seat 2 :seats 3 :turn 2 :turns 4}))))
  (testing "independence directive is full-only; resuming an independent seat is a contradiction"
    (is (str/includes? (#'agents/independence-fragment {}) "Work independently"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no continuation form"
                          (#'agents/independence-fragment {:form :continuation}))))
  (testing "deliberation continuation names the concrete previous turn and demands an integer turn"
    (is (str/includes? (#'agents/deliberation-fragment {}) "previous turn"))
    (is (= "Read peers' turn 2 posts on the board, then rebut or refine.\n"
           (#'agents/deliberation-fragment {:form :continuation :turn 3})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer :turn"
                          (#'agents/deliberation-fragment {:form :continuation})))))

(deftest blackboard-board-fragments-back-the-review-prompt
  (with-agents
    (fn [_]
      (testing "read-the-board renders both views and a terse continuation pointer"
        (is (str/ends-with? (#'agents/read-the-board-fragment {:view :strand :board-id "s1"})
                            "` and inspect the relevant repository state.\n"))
        (is (str/includes? (#'agents/read-the-board-fragment {:view :notes :board-id "s1"})
                           "agent notes s1"))
        (is (str/includes? (#'agents/read-the-board-fragment {:view :strand :form :continuation :board-id "s1"})
                           "The board is strand s1")))
      (testing "post-with-tag threads the tag as a panel/pass decoration attr and omits it when nil"
        (is (str/includes? (#'agents/post-with-tag-fragment {:board-id "s1" :tag "pass-7"})
                           "--attr panel/pass=pass-7"))
        (is (not (str/includes? (#'agents/post-with-tag-fragment {:board-id "s1" :tag nil})
                                "--attr panel/pass"))))
      (testing "post-with-tag threads the note/kind view hint and omits it when nil"
        (is (str/includes? (#'agents/post-with-tag-fragment {:board-id "s1" :tag "pass-7"
                                                             :kind "review-dump"})
                           "--attr note/kind=review-dump"))
        (is (not (str/includes? (#'agents/post-with-tag-fragment {:board-id "s1" :tag "pass-7"})
                                "--attr note/kind"))))
      (testing "post-with-tag threads --round and omits it when nil"
        (is (str/includes? (#'agents/post-with-tag-fragment {:board-id "s1" :tag "pass-7" :round 2})
                           "--round 2"))
        (is (not (str/includes? (#'agents/post-with-tag-fragment {:board-id "s1" :tag "pass-7"})
                                "--round"))))
      (testing "review-prompt is assembled from the shared fragments byte-for-byte"
        (let [prompt (#'agents/review-prompt {:target-id "s1" :contract "C" :note-tag "p1"})]
          (is (str/includes? prompt (#'agents/read-the-board-fragment {:view :strand :board-id "s1"})))
          (is (str/includes? prompt (#'agents/post-with-tag-fragment {:board-id "s1" :tag "p1"
                                                                      :kind "review-dump"}))))))))

;; ---------------------------------------------------------------------------
;; Change context: the caller-supplied diff surface injected into reviewers
;; ---------------------------------------------------------------------------

(deftest change-context-block-renders-the-diff-surface
  (testing "a nil context yields the empty string so prompts stay byte-identical"
    (is (= "" (#'agents/change-context-block nil))))
  (testing "a full context lists the commit range, changed files, and code windows"
    (let [block (#'agents/change-context-block
                 {:commit-range "main..HEAD"
                  :files ["src/a.clj" "src/b.clj"]
                  :windows [{:path "src/a.clj" :lines "40-90"} {:path "src/b.clj"}]})]
      (is (str/includes? block "[change under review]"))
      (is (str/includes? block "Commit range: main..HEAD"))
      (is (str/includes? block "Changed files (2):"))
      (is (str/includes? block "  src/a.clj\n"))
      (is (str/includes? block "  src/a.clj:40-90\n"))
      (is (str/includes? block "authoritative diff surface"))))
  (testing "review-prompt injects the change context when supplied and omits it otherwise"
    (with-agents
      (fn [_]
        (let [with-ctx (#'agents/review-prompt {:target-id "s1" :contract "C" :note-tag "p1"
                                                :change-context {:commit-range "main..HEAD"
                                                                 :files ["src/a.clj"]}})
              without (#'agents/review-prompt {:target-id "s1" :contract "C" :note-tag "p1"})]
          (is (str/includes? with-ctx "Commit range: main..HEAD"))
          (is (not (str/includes? without "[change under review]"))))))))

(deftest synthesis-prompt-asks-for-root-cause-dedup
  (with-agents
    (fn [_]
      (is (str/includes? (#'agents/review-synthesis-prompt {:target-id "s1" :contract "C" :note-tag "p1"})
                         "De-duplicate by root cause")))))

(deftest roster-review-specs-carry-change-context
  (with-agents
    (fn [rt]
      (agents/defroster! :ctx
        {:seats [{:name "a" :harness :sh :brief "Brief A."}
                 {:name "b" :harness :sh :brief "Brief B."}]
         :synthesis {:harness :sh}})
      (let [target (weaver/add rt {:title "ctx target"})
            change-context {:commit-range "main..HEAD" :files ["src/a.clj" "test/a_test.clj"]}
            specs (agents/roster-review-specs :ctx {:target (:id target)
                                                    :change-context change-context})]
        (testing "every reviewer prompt carries the diff surface"
          (is (every? #(str/includes? (:prompt %) "Commit range: main..HEAD") (:reviewers specs)))
          (is (every? #(str/includes? (:prompt %) "src/a.clj") (:reviewers specs))))
        (testing "the synthesizer never carries the change context"
          (is (not (str/includes? (get-in specs [:synthesizer :prompt]) "[change under review]"))))
        (testing "review! threads the change context onto spawned runs"
          (let [review (agents/review! (:id target) {:roster :ctx :change-context change-context})
                run (weaver/show rt (first (:reviewers review)))]
            (is (str/includes? (get-in run [:attributes :agent-run/prompt]) "Commit range: main..HEAD"))))
        (testing "malformed change context fails loudly against its spec"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"change-context does not conform"
                                (agents/roster-review-specs :ctx {:target (:id target)
                                                                  :change-context {:files "not-a-vector"}}))))))))

(deftest review-validates-change-context-on-the-ad-hoc-path
  (with-agents
    (fn [rt]
      (let [target (weaver/add rt {:title "ad-hoc ctx target"})]
        (testing "a direct :reviewers caller with no :roster fails loudly on a malformed change context"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"change-context does not conform"
                                (agents/review! (:id target)
                                                {:reviewers [{:harness :sh :focus "correctness"}]
                                                 :change-context {:files "not-a-vector"}}))))))))

(deftest change-context-from-flags-builds-the-diff-surface
  (testing "an explicit changed-files list wins without touching git"
    (is (= {:commit-range "main..HEAD" :files ["x.clj" "y.clj"]}
           (#'agents/change-context-from-flags {"--commit-range" "main..HEAD"
                                                "--changed-files" "x.clj, y.clj"}))))
  (testing "no diff flags yields nil"
    (is (nil? (#'agents/change-context-from-flags {"--cwd" "/tmp"}))))
  (testing "a commit range with no --cwd to expand it fails loudly instead of emitting a partial surface"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--commit-range requires --cwd"
                          (#'agents/change-context-from-flags {"--commit-range" "main..HEAD"})))))

(deftest git-changed-files-expands-a-commit-range
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      (.toPath (io/file "/tmp")) "skein-agents-git"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        path (.getCanonicalPath dir)
        git (fn [& args] (apply sh/sh "git" "-C" path args))]
    (try
      (git "init" "-q")
      (git "config" "user.email" "t@example.com")
      (git "config" "user.name" "t")
      (spit (io/file dir "a.txt") "one\n")
      (git "add" "-A")
      (git "commit" "-q" "-m" "base")
      (spit (io/file dir "a.txt") "two\n")
      (spit (io/file dir "b.txt") "new\n")
      (git "add" "-A")
      (git "commit" "-q" "-m" "change")
      (testing "an explicit range expands to only its touched files"
        (is (= ["a.txt" "b.txt"] (sort (#'agents/git-changed-files path "HEAD~1..HEAD")))))
      (testing "an unexpandable range fails loudly instead of dropping the context"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Could not expand commit range"
                              (#'agents/git-changed-files path "no-such-ref..HEAD"))))
      (finally
        (sh/sh "rm" "-rf" path)))))

;; ---------------------------------------------------------------------------
;; Panel primitive (PLAN-Pnl-001.PH4)
;;
;; session-fix is a fake claude-json harness that ignores its args and emits a
;; fixed session id, so a panel prompt's embedded quotes never corrupt the
;; captured JSON — panel resume wiring only needs the predecessor to capture a
;; session, not to echo the prompt. session-fix-no-resume captures a session
;; but declares no :resume splice, so resuming it must fail at spawn.

(def ^:private session-fix
  {:argv ["sh" "-c" "printf '{\"result\":\"ok\",\"session_id\":\"sess-abc\"}'" "session-fix"]
   :parse :claude-json
   :preamble? false
   :resume ["--resume" :agent-run/session-id]})

(def ^:private session-fix-no-resume (dissoc session-fix :resume))

(deftest panel-specs-compiles-conformant-run-specs
  (with-agents
    (fn [rt]
      (let [target (weaver/add rt {:title "Panel target" :attributes {:body "Inspect me"}})
            panel {:seats [{:name "correctness" :harness :sh :brief "Judge correctness." :scope "src"}
                           {:name "tests" :harness "sh" :brief "Judge the tests."}]
                   :synthesis {:harness :sh :brief "Weigh both."}}
            specs (agents/panel-specs panel {:target (:id target)})
            row1 (first (:turns specs))]
        (testing "output conforms to its public spec"
          (is (s/valid? :skein.spools.delegation/panel-specs specs)
              (s/explain-str :skein.spools.delegation/panel-specs specs)))
        (testing "a single default round is one turn row of independent seats"
          (is (= 1 (count (:turns specs))))
          (is (= ["correctness" "tests"] (mapv :name row1)))
          (is (every? #(str/includes? (:prompt %) "Work independently") row1))
          (is (every? #(str/includes? (:prompt %) "You are seat") row1))
          (is (every? #(nil? (:resume-ref %)) row1))
          (is (str/includes? (:prompt (first row1)) "Scope: confine your work to src")))
        (testing "keyword and string harnesses both compile"
          (is (= [:sh "sh"] (mapv :harness row1))))
        (testing "every run spec stamps panel + review attrs"
          (doseq [spec row1]
            (is (= (:id target) (get (:attrs spec) "panel/blackboard")))
            (is (= (:pass specs) (get (:attrs spec) "panel/pass")))
            (is (= "1" (get (:attrs spec) "panel/turn")))
            (is (= (:name spec) (get (:attrs spec) "panel/seat")))))
        (testing "blackboard directive names the target strand"
          (is (= {:kind :target :id (:id target)} (:blackboard specs))))
        (testing "synthesis compiles when declared"
          (is (str/includes? (get-in specs [:synthesizer :prompt]) "Weigh both."))
          (is (str/includes? (get-in specs [:synthesizer :prompt]) "Synthesize the panel deliberation")))
        (testing "no synthesis yields no synthesizer and still conforms"
          (let [bare (agents/panel-specs (dissoc panel :synthesis) {:target (:id target)})]
            (is (nil? (:synthesizer bare)))
            (is (s/valid? :skein.spools.delegation/panel-specs bare))))
        (testing ":review-id overrides the minted pass tag"
          (is (= "pass-x" (:pass (agents/panel-specs panel {:target (:id target) :review-id "pass-x"})))))))))

(deftest panel-specs-multi-round-wires-barriers-and-prompt-forms
  (with-agents
    (fn [_]
      (let [panel {:seats [{:name "alpha" :harness :sh :brief "Debate hard." :continuity :resume}
                           {:name "beta" :harness :sh :brief "Debate harder." :continuity :resume}]
                   :turns {:rounds 3}
                   :blackboard :fresh}
            specs (agents/panel-specs panel {})]
        (testing "one turn row per round, conforming to the spec"
          (is (= 3 (count (:turns specs))))
          (is (s/valid? :skein.spools.delegation/panel-specs specs)
              (s/explain-str :skein.spools.delegation/panel-specs specs)))
        (testing "a fresh blackboard defers the id to the spawner via a placeholder"
          (is (= {:kind :fresh} (:blackboard specs)))
          (is (str/includes? (:prompt (ffirst (:turns specs))) "«panel-board»")))
        (testing "turn 1 opens on the full prompt with no resume threading"
          (let [row1 (first (:turns specs))]
            (is (every? #(nil? (:resume-ref %)) row1))
            (is (every? #(nil? (:resume-prompt %)) row1))
            (is (every? #(str/includes? (:prompt %) "You are seat") row1))
            (is (not (str/includes? (:prompt (first row1)) "Work independently")))))
        (testing "turn 2 threads each seat onto its own previous-row index with a continuation prompt"
          (let [row2 (second (:turns specs))]
            (is (= [0 1] (mapv :resume-ref row2)))
            (is (every? #(str/includes? (:resume-prompt %) "Continuing as seat") row2))
            (is (str/includes? (:resume-prompt (first row2)) "Read peers' turn 1"))))
        (testing "turn 3 continuation references the prior turn"
          (is (str/includes? (:resume-prompt (first (nth (:turns specs) 2))) "Read peers' turn 2")))
        ;; a seat that posts without --round lands a note with no note/round,
        ;; and `agent notes <board> --round n` (which filters note/round) then
        ;; returns nothing for every round of the deliberation
        (testing "each seat is told to post with its own turn as --round, so notes stay round-filterable"
          (doseq [[idx row] (map-indexed vector (:turns specs))
                  spec row]
            (is (str/includes? (:prompt spec) (str "--round " (inc idx))))
            (when-let [resume (:resume-prompt spec)]
              (is (str/includes? resume (str "--round " (inc idx)))))))))))

(deftest panel-spawns-fresh-board-with-barriers-and-resume-threads
  (with-agents
    (fn [rt]
      (shuttle/register-harness! :session-fix session-fix)
      (let [panel {:seats [{:name "alpha" :harness :session-fix :brief "Deliberate." :continuity :resume}
                           {:name "beta" :harness :session-fix :brief "Deliberate too." :continuity :resume}]
                   :turns {:rounds 2}
                   :blackboard :fresh
                   :synthesis {:harness :session-fix}}
            result (agents/panel! panel {})
            board (weaver/show rt (:blackboard result))
            [row1 row2] (:turns result)
            deps-of (fn [id]
                      (->> (:edges (graph/subgraph rt [id] {:type "depends-on"}))
                           (filter #(= id (:from_strand_id %)))
                           (map :to_strand_id) set))]
        (testing "a fresh blackboard strand is minted carrying the panel's pass tag"
          (is (= (:pass result) (get-in board [:attributes :panel/pass]))))
        (testing "each turn row has one run per seat"
          (is (= 2 (count row1)))
          (is (= 2 (count row2))))
        (testing "turn 2 barriers on every turn-1 run"
          (doseq [run-id row2]
            (is (= (set row1) (deps-of run-id)))))
        (testing "turn 2 threads each seat onto its own predecessor via agent-run/resumes"
          (is (= (first row1) (get-in (weaver/show rt (first row2)) [:attributes :agent-run/resumes])))
          (is (= (second row1) (get-in (weaver/show rt (second row2)) [:attributes :agent-run/resumes]))))
        (testing "spawned prompts select the form and resolve the board placeholder"
          (let [p1 (get-in (weaver/show rt (first row1)) [:attributes :agent-run/prompt])
                p2 (get-in (weaver/show rt (first row2)) [:attributes :agent-run/prompt])]
            (is (str/includes? p1 "You are seat"))
            (is (str/includes? p2 "Continuing as seat"))
            (is (not (str/includes? p2 "«panel-board»")))
            (is (str/includes? p2 (:blackboard result)))
            (is (= (:blackboard result)
                   (get-in (weaver/show rt (first row1)) [:attributes :panel/blackboard])))))
        (testing "a resuming turn stashes its full-brief prompt for retry --fresh"
          (let [f2 (get-in (weaver/show rt (first row2)) [:attributes :agent-run/fresh-prompt])]
            (is (str/includes? f2 "You are seat")
                "the full form, not the continuation, is durable for a cold restart")
            (is (not (str/includes? f2 "«panel-board»")))))
        (testing "the synthesizer depends on the final turn row"
          (is (= (set row2) (deps-of (:synthesizer result)))))))))

(deftest panel-fails-loudly
  (with-agents
    (fn [rt]
      (shuttle/register-harness! :session-fix-no-resume session-fix-no-resume)
      (let [target (weaver/add rt {:title "panel fail target"})]
        (testing "malformed and structurally invalid panels fail via the spec"
          (doseq [bad [[:not-a-map]
                       {:seats []}
                       {:seats [{:name "a" :harness :sh}]}
                       {:seats [{:harness :sh :brief "b"}]}]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not conform to spec"
                                  (agents/panel-specs bad {:target (:id target)})))))
        (testing "unknown keys diagnose before the spec"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                                (agents/panel-specs {:seats [{:name "a" :harness :sh :brief "b" :nope 1}]} {:target (:id target)})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                                (agents/panel-specs {:seats [{:name "a" :harness :sh :brief "b"}] :bogus 1} {:target (:id target)}))))
        (testing "seat names must be unique"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be unique"
                                (agents/panel-specs {:seats [{:name "x" :harness :sh :brief "a"}
                                                             {:name "x" :harness :sh :brief "b"}]}
                                                    {:target (:id target)}))))
        (testing "a :target blackboard requires a non-blank target"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank :target"
                                (agents/panel-specs {:seats [{:name "a" :harness :sh :brief "b"}]} {}))))
        (testing "a :target blackboard is single-round only (seats read the subject, not peer posts)"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"single round only"
                                (agents/panel-specs {:seats [{:name "a" :harness :sh :brief "b"}]
                                                     :turns {:rounds 2}
                                                     :blackboard :target}
                                                    {:target (:id target)}))))
        (testing ":review-id must be non-blank when present"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                                (agents/panel-specs {:seats [{:name "a" :harness :sh :brief "b"}]}
                                                    {:target (:id target) :review-id "  "}))))
        (testing "continuity :resume on a harness without a :resume splice fails at spawn"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declares a :resume splice"
                                (agents/panel! {:seats [{:name "a" :harness :session-fix-no-resume
                                                         :brief "b" :continuity :resume}]
                                                :turns {:rounds 2}
                                                :blackboard :fresh}
                                               {}))))
        (testing "invalid panel options are validated and include the failing value"
          (let [bad {:target 1}
                ex (try
                     (agents/panel! {:seats [{:name "a" :harness :sh :brief "b" :continuity :fresh}]
                                     :blackboard :target}
                                    bad)
                     (catch clojure.lang.ExceptionInfo e
                       e))]
            (is (instance? clojure.lang.ExceptionInfo ex))
            (is (= bad (:value (ex-data ex)))
                "panel options fail with invalid value")
            (is (str/includes? (ex-message ex) "panel! options"))))
        (testing "unknown panel options fail loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                                (agents/panel! {:seats [{:name "a" :harness :sh :brief "b"}]
                                                :blackboard :fresh}
                                               {:tarhet "x"}))))))))

(deftest panel-specs-blackboard-directive-is-tightly-specd
  ;; the compiled seam is a consumer contract, so a malformed blackboard
  ;; directive must fail the spec rather than only being documented away
  (testing ":target carries an :id; :fresh omits it"
    (is (s/valid? :skein.spools.delegation.panel-specs/blackboard {:kind :target :id "s1"}))
    (is (s/valid? :skein.spools.delegation.panel-specs/blackboard {:kind :fresh})))
  (testing "kind/id disagreement is rejected"
    (is (not (s/valid? :skein.spools.delegation.panel-specs/blackboard {:kind :target})))
    (is (not (s/valid? :skein.spools.delegation.panel-specs/blackboard {:kind :fresh :id "s1"})))))

(deftest roster->panel-produces-independent-target-panel
  (with-agents
    (fn [rt]
      (let [roster {:seats [{:name "tests" :harness :sh :brief "Judge the tests." :scope "test files"}
                            {:name "docs" :harness "sh" :brief "Judge the docs."}]
                    :synthesis {:harness :sh}}
            panel (agents/roster->panel roster)
            target (weaver/add rt {:title "roster-panel target"})]
        (testing "each roster seat becomes an independent panel seat"
          (is (= [{:name "tests" :harness :sh :brief "Judge the tests." :continuity :fresh :scope "test files"}
                  {:name "docs" :harness "sh" :brief "Judge the docs." :continuity :fresh}]
                 (:seats panel)))
          (is (= {:rounds 1} (:turns panel)))
          (is (= :target (:blackboard panel)))
          (is (= {:harness :sh} (:synthesis panel))))
        (testing "the converted panel compiles to the single-round independent shape"
          (let [specs (agents/panel-specs panel {:target (:id target)})]
            (is (= 1 (count (:turns specs))))
            (is (every? #(str/includes? (:prompt %) "Work independently") (first (:turns specs))))
            (is (s/valid? :skein.spools.delegation/panel-specs specs))))
        (testing "a synthesis-less roster falls back to the first seat's harness"
          (is (= {:harness :sh}
                 (:synthesis (agents/roster->panel {:seats [{:name "solo" :harness :sh :brief "One pass."}]})))))
        (testing "a malformed roster fails identically to defroster! input"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not conform to spec"
                                (agents/roster->panel {:seats []}))))))))

(deftest council-turn-rows-per-seat-harnesses-and-loud-harness
  (with-agents
    (fn [rt]
      (let [deps-of (fn [id]
                      (->> (:edges (graph/subgraph rt [id] {:type "depends-on"}))
                           (filter #(= id (:from_strand_id %)))
                           (map :to_strand_id) set))]
        (testing "a scalar seat count expands to identical seats across turn-as-run rows"
          (let [{:keys [blackboard turns synthesizer]}
                (agents/council! "test topic" {:harness :sh :seat-count 2 :rounds 2})
                board (weaver/show rt blackboard)
                [row1 row2] turns]
            (testing "council re-ships as a fresh-blackboard panel"
              (is (some? (get-in board [:attributes :panel/pass]))
                  "the minted board carries the panel pass tag")
              (is (= ["seat-1" "seat-2"]
                     (mapv #(get-in (weaver/show rt %) [:attributes :panel/seat]) row1))
                  "seats are stamped with panel's seat identity"))
            (is (= [2 2] [(count row1) (count row2)]))
            (testing "the council strand parents every run"
              (is (= (set (concat row1 row2 [synthesizer]))
                     (set (map :to_strand_id (:edges (graph/subgraph rt [blackboard])))))))
            (testing "the poll-loop choreography is gone; the topic remains"
              (doseq [run-id (concat row1 row2 [synthesizer])]
                (let [p (get-in (weaver/show rt run-id) [:attributes :agent-run/prompt])]
                  (is (not (str/includes? p "poll")))
                  (is (not (str/includes? p "sleep")))
                  (is (str/includes? p "test topic")))))
            (testing "turn 2 barriers on every turn-1 run and the synthesizer waits on the final row"
              (doseq [run-id row2] (is (= (set row1) (deps-of run-id))))
              (is (= (set row2) (deps-of synthesizer))))))
        (testing "per-seat harnesses and perspectives ride onto seats"
          (let [{:keys [turns]} (agents/council! "seated topic"
                                                 {:rounds 1
                                                  :seats [{:name "skeptic" :harness :sh :brief "Argue against."}
                                                          {:name "advocate" :harness :sh :brief "Argue for."}]})
                row1 (first turns)
                prompts (mapv #(get-in (weaver/show rt %) [:attributes :agent-run/prompt]) row1)]
            (is (= 2 (count row1)))
            (is (some #(str/includes? % "Your assigned perspective: Argue against.") prompts))
            (is (some #(str/includes? % "Your assigned perspective: Argue for.") prompts))
            (is (every? #(= "sh" (get-in (weaver/show rt %) [:attributes :agent-run/harness])) row1))))
        (testing "council fails loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                                (agents/council! "  " {:harness :sh})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not conform"
                                (agents/council! "t" {:harness :sh :rounds 0})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a harness"
                                (agents/council! "t" {:seat-count 2}))
              "the silent :claude default is gone: no harness resolves loudly")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not both"
                                (agents/council! "t" {:harness :sh :seat-count 2
                                                      :seats [{:name "a" :harness :sh}]})))
          (let [bad {:harness 1 :rounds 2}
                ex (try
                     (agents/council! "t" bad)
                     (catch clojure.lang.ExceptionInfo e
                       e))]
            (is (instance? clojure.lang.ExceptionInfo ex))
            (is (= bad (:value (ex-data ex)))
                "council invalid input carries the failing value")
            (is (str/includes? (ex-message ex) "council! options")))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                                (agents/council! "t" {:harness :sh :seat-counts 2})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                                (agents/council! "t" {:harness :sh
                                                      :seats [{:name "a" :harnes :sh}]}))))))))

(deftest delegate-fails-loudly-for-contract-violations
  (with-agents
    (fn [rt]
      (let [blocker (weaver/add rt {:title "blocker"})
            blocked (weaver/add rt {:title "blocked" :attributes {:body "body" :agent-run/harness "sh"}
                                    :edges [{:type "depends-on" :to (:id blocker)}]})
            no-harness (weaver/add rt {:title "no harness" :attributes {:body "body"}})
            hitl (weaver/add rt {:title "hitl" :attributes {:body "body" :agent-run/harness "sh" :hitl true}})
            active (weaver/add rt {:title "active" :attributes {:body "body" :agent-run/harness "sh"}})
            cwd-task (weaver/add rt {:title "cwd fallback" :attributes {:body "body" :agent-run/harness "cwd-sh"}})]
        (shuttle/register-harness! :cwd-sh {:argv ["sh" "-c"]
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
              run (weaver/show rt (get-in delegated [:run :id]))]
          (is (= (-> (io/file (get-in rt [:metadata :config-dir]))
                     .getParentFile
                     .getCanonicalPath)
                 (get-in run [:attributes :agent-run/cwd]))
              "agents supplies workspace root explicitly, so harness :cwd cannot win"))
        (let [gate (weaver/add rt {:title "gate"})]
          (shuttle/spawn-run! {:harness :sh :prompt "echo later" :parent (:id active) :serves (:id active) :depends-on [(:id gate)]})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ACTIVE run"
                                (agents/agent-op {:op/argv ["delegate" (:id active)]}))))))))

(deftest agent-op-fail-loudly-matrix
  (with-agents
    (fn [rt]
      (let [task (weaver/add rt {:title "empty task" :attributes {:agent-run/harness "sh"}})
            closed (weaver/add rt {:title "closed" :state "closed" :attributes {:body "body" :agent-run/harness "sh"}})
            plan (weaver/add rt {:title "plan"})
            missing-harness (weaver/add rt {:title "ready missing harness" :attributes {:body "body"}})]
        (weaver/update rt (:id plan) {:edges [{:type "parent-of" :to (:id missing-harness)}]})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"body or --prompt"
                              (agents/agent-op {:op/argv ["delegate" (:id task)]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"task must be active"
                              (agents/agent-op {:op/argv ["delegate" (:id closed)]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ready tasks missing agent-run/harness"
                              (agents/agent-op {:op/argv ["delegate" "--ready" (:id plan)]})))
        (is (not-any? #{(:id missing-harness)}
                      (:ready (agents/agent-op {:op/argv ["status" (:id plan)]})))
            "status :ready lists tasks delegable right now, matching delegate --ready selection")
        (is (empty? (shuttle/runs {:for (:id missing-harness)})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutually exclusive"
                              (agents/agent-op {:op/argv ["await" "run-a" "--under" (:id plan)]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --prompt"
                              (agents/agent-op {:op/argv ["spawn" "--harness" "sh"]})))
        (let [done (agents/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "echo done"]})]
          (agents/agent-op {:op/argv ["await" (:id done) "--timeout-secs" (str (test-support/await-budget-secs))]})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no live process"
                                (agents/agent-op {:op/argv ["kill" (:id done)]}))))))))

(deftest delegate-guards-all-non-superseded-runs
  (with-agents
    (fn [rt]
      (let [active-task (weaver/add rt {:title "active run task" :attributes {:body "body" :agent-run/harness "sh"}})
            failed-task (weaver/add rt {:title "failed run task" :attributes {:body "body" :agent-run/harness "sh"}})
            done-task (weaver/add rt {:title "done run task" :attributes {:body "body" :agent-run/harness "sh"}})
            plan (weaver/add rt {:title "plan"})
            gate (weaver/add rt {:title "gate"})]
        (weaver/update rt (:id plan) {:edges [{:type "parent-of" :to (:id active-task)}
                                              {:type "parent-of" :to (:id failed-task)}
                                              {:type "parent-of" :to (:id done-task)}]})
        (shuttle/spawn-run! {:harness :sh :prompt "echo later" :parent (:id active-task) :serves (:id active-task) :depends-on [(:id gate)]})
        (let [failed (shuttle/spawn-run! {:harness :sh :prompt "exit 7" :parent (:id failed-task) :serves (:id failed-task)})
              done (shuttle/spawn-run! {:harness :sh :prompt "echo ok" :parent (:id done-task) :serves (:id done-task)})]
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

(deftest delegate-ready-classifies-each-task-once
  ;; Regression (card 0nd97): op-delegate classifies every task against
  ;; pre-spawn state, so a task delegated this pass — which mints a fresh
  ;; pending run — is never ALSO reported in :skipped as has-active-run.
  (with-agents
    (fn [rt]
      (let [plan (weaver/add rt {:title "plan"})
            tasks (mapv (fn [n] (weaver/add rt {:title (str "task " n)
                                                :attributes {:body "body" :agent-run/harness "sh"}}))
                        (range 3))]
        (weaver/update rt (:id plan) {:edges (mapv (fn [t] {:type "parent-of" :to (:id t)}) tasks)})
        (let [ready (agents/agent-op {:op/argv ["delegate" "--ready" (:id plan)]})]
          (is (= (set (map :id tasks)) (set (map :task (:delegated ready))))
              "every ready task is delegated exactly once")
          (is (apply distinct? (map :task (:delegated ready)))
              "no task appears twice in :delegated")
          (is (empty? (:skipped ready))
              "a task delegated this pass is never re-reported as skipped"))))))

(deftest fanout-verbs-stamp-fanout-cap-group
  ;; V2 (PROP-Foc-001.C3, TASK-Foc-002.DW1): review, council, and delegate
  ;; --ready each parse --fanout-cap and stamp one shared
  ;; agent-run/fanout-group plus the requested agent-run/fanout-cap on every run
  ;; of the fan-out, so the PH1 window bounds each group to min(W, K). A raw
  ;; spawn or single delegate creates a single run, so it carries no group and
  ;; is governed by the workspace ceiling W alone.
  (with-agents
    (fn [rt]
      (letfn [(fan [run-ids]
                (let [runs (mapv #(weaver/show rt %) run-ids)]
                  {:groups (set (map #(get-in % [:attributes :agent-run/fanout-group]) runs))
                   :caps (set (map #(get-in % [:attributes :agent-run/fanout-cap]) runs))}))]
        (testing "review stamps one group across reviewers and the synthesizer"
          (let [target (weaver/add rt {:title "review target" :attributes {:body "x"}})
                review (agents/agent-op {:op/argv ["review" (:id target)
                                                   "--seats" "2" "--synthesize"
                                                   "--fanout-cap" "3"]})
                {:keys [groups caps]} (fan (conj (:reviewers review) (:synthesizer review)))]
            (is (some? (:synthesizer review)) "the review fans out a synthesizer too")
            (is (= 1 (count groups)) "every review run shares one fan-out group")
            (is (not (contains? groups nil)) "no review run is left ungrouped")
            (is (= #{3} caps) "every review run carries the requested cap")))
        (testing "council stamps one group across seats and the synthesizer"
          (let [council (agents/agent-op {:op/argv ["council" "--topic" "decide"
                                                    "--harness" "sh" "--seats" "2"
                                                    "--rounds" "1" "--fanout-cap" "2"]})
                run-ids (conj (vec (mapcat identity (:turns council))) (:synthesizer council))
                {:keys [groups caps]} (fan run-ids)]
            (is (= 1 (count groups)) "every council run shares one fan-out group")
            (is (not (contains? groups nil)) "no council run is left ungrouped")
            (is (= #{2} caps) "every council run carries the requested cap")))
        (testing "delegate --ready stamps one group across the classified batch"
          (let [plan (weaver/add rt {:title "plan"})
                tasks (mapv (fn [n] (weaver/add rt {:title (str "task " n)
                                                    :attributes {:body "body" :agent-run/harness "sh"}}))
                            (range 3))
                _ (weaver/update rt (:id plan) {:edges (mapv (fn [t] {:type "parent-of" :to (:id t)}) tasks)})
                ready (agents/agent-op {:op/argv ["delegate" "--ready" (:id plan) "--fanout-cap" "2"]})
                run-ids (mapv #(get-in % [:run :id]) (:delegated ready))
                {:keys [groups caps]} (fan run-ids)]
            (is (= 3 (count run-ids)) "every ready task is delegated into the fan-out")
            (is (= 1 (count groups)) "the whole batch shares one fan-out group")
            (is (not (contains? groups nil)) "no delegated run is left ungrouped")
            (is (= #{2} caps) "every delegated run carries the requested cap")))
        (testing "raw spawn and single delegate carry no fan-out group"
          (let [spawned (agents/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "echo x"]})
                task (weaver/add rt {:title "single" :attributes {:body "body" :agent-run/harness "sh"}})
                single (agents/agent-op {:op/argv ["delegate" (:id task)]})
                ;; --fanout-cap on a single delegate is inert: one run is
                ;; governed by W alone and never joins a group (MI3)
                capped (weaver/add rt {:title "single capped" :attributes {:body "body" :agent-run/harness "sh"}})
                single-capped (agents/agent-op {:op/argv ["delegate" (:id capped) "--fanout-cap" "5"]})]
            (doseq [id [(:id spawned) (get-in single [:run :id]) (get-in single-capped [:run :id])]]
              (let [run (weaver/show rt id)]
                (is (nil? (get-in run [:attributes :agent-run/fanout-group]))
                    "a single run carries no fan-out group")
                (is (nil? (get-in run [:attributes :agent-run/fanout-cap]))
                    "a single run carries no fan-out cap")))))
        (testing "--fanout-cap must be a positive integer"
          (let [target (weaver/add rt {:title "bad-cap target" :attributes {:body "x"}})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                                  (agents/agent-op {:op/argv ["review" (:id target) "--fanout-cap" "0"]})))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                                  (agents/agent-op {:op/argv ["council" "--topic" "t" "--harness" "sh"
                                                              "--fanout-cap" "0"]})))))))))

(deftest panel-and-council-reject-incoherent-fanout-attrs
  ;; Trusted Clojure callers of panel!/council! can hand-build :fanout-attrs, so
  ;; the input specs enforce the same coherent contract the agent-run window
  ;; does: a non-blank group paired with a positive-integer cap. A malformed stamp
  ;; is rejected at the boundary rather than reaching the window as a bad record.
  (with-agents
    (fn [_rt]
      (let [seats [{:name "a" :harness :sh :brief "b"}]]
        (testing "a coherent stamp is accepted"
          (is (s/valid? :skein.spools.delegation.council-input/fanout-attrs
                        {"agent-run/fanout-group" "grp" "agent-run/fanout-cap" 2})))
        (testing "incoherent stamps fail the spec"
          (doseq [bad [{"agent-run/fanout-group" "grp"}                      ; group, no cap
                       {"agent-run/fanout-group" "grp" "agent-run/fanout-cap" 0}
                       {"agent-run/fanout-group" "grp" "agent-run/fanout-cap" "2"}
                       {"agent-run/fanout-group" "" "agent-run/fanout-cap" 2} ; blank group
                       {"agent-run/fanout-cap" 3}]]                          ; cap, no group
            (is (not (s/valid? :skein.spools.delegation.council-input/fanout-attrs bad))
                (str "incoherent " bad " must fail the spec"))))
        (testing "panel! rejects a malformed :fanout-attrs stamp loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not conform"
                                (agents/panel! {:seats seats :blackboard :fresh}
                                               {:fanout-attrs {"agent-run/fanout-group" "grp"
                                                               "agent-run/fanout-cap" 0}}))))
        (testing "council! rejects a malformed :fanout-attrs stamp loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not conform"
                                (agents/council! "topic"
                                                 {:harness :sh :seat-count 2 :rounds 1
                                                  :fanout-attrs {"agent-run/fanout-cap" 3}}))))))))

(defn- serves? [rt run-id target-id]
  (some #(= target-id (:to_strand_id %)) (graph/outgoing-edges rt [run-id] "serves")))

(deftest non-serving-helpers-do-not-gate-delegation
  ;; Regression (card b4nml): recon spawns and review runs carry parent-of
  ;; placement alone with no serves edge, so a read-only helper run hanging under
  ;; a task must never block a later delegate of it, nor make delegate --ready
  ;; skip it as has-active-run.
  (with-agents
    (fn [rt]
      (let [spawn-task (weaver/add rt {:title "recon then delegate" :attributes {:body "body" :agent-run/harness "sh"}})
            review-task (weaver/add rt {:title "review then delegate" :attributes {:body "body" :agent-run/harness "sh"}})
            plan (weaver/add rt {:title "plan"})]
        (weaver/update rt (:id plan) {:edges [{:type "parent-of" :to (:id spawn-task)}
                                              {:type "parent-of" :to (:id review-task)}]})
        (testing "a spawn --for helper carries no serves edge and does not block delegate"
          (let [helper (agents/agent-op {:op/argv ["spawn" "--harness" "sh" "--prompt" "echo recon" "--for" (:id spawn-task)]})]
            (is (not (serves? rt (:id helper) (:id spawn-task)))
                "spawn places its run as a helper with no serves edge")
            (let [delegated (agents/agent-op {:op/argv ["delegate" (:id spawn-task)]})]
              (is (= (:id spawn-task) (:task delegated))
                  "the recon helper does not block delegating the task")
              (is (serves? rt (get-in delegated [:run :id]) (:id spawn-task))
                  "the delegation run itself serves the task"))))
        (testing "review runs carry no serves edge, so delegate --ready still delegates the reviewed task"
          (let [review (agents/review! (:id review-task) {:reviewers [{:harness :sh :focus "recon"}]})]
            (is (not-any? #(serves? rt % (:id review-task)) (:reviewers review))
                "reviewer runs carry no serves edge")
            (let [ready (agents/agent-op {:op/argv ["delegate" "--ready" (:id plan)]})]
              (is (some #{(:id review-task)} (map :task (:delegated ready)))
                  "the reviewed task is delegated, not skipped as has-active-run")
              (is (not-any? #{(:id review-task)} (map :task (:skipped ready)))))))))))

(deftest retry-run-id-preserves-provenance-and-dependencies
  (with-agents
    (fn [rt]
      (let [task (weaver/add rt {:title "served" :attributes {:body "body" :agent-run/harness "sh"}})
            spawner (shuttle/spawn-run! {:harness :sh :prompt "echo parent"})
            blocker (weaver/add rt {:title "retry blocker"})
            failed (shuttle/spawn-run! {:harness :sh
                                        :prompt "exit 3"
                                        :parent (:id task)
                                        :serves (:id task)
                                        :spawned-by (:id spawner)
                                        :depends-on [(:id blocker)]
                                        :cwd "/tmp/retry-cwd"
                                        :max-attempts 5})]
        (weaver/update rt (:id blocker) {:state "closed"})
        (await-phase rt (:id failed) #{"failed"})
        (let [retried (agents/agent-op {:op/argv ["retry" (:id failed) "--prompt" "echo recovered"]})
              new-id (get-in retried [:run :id])
              new-run (weaver/show rt new-id)
              summary (shuttle/run-summary new-run)
              dep-edges (:edges (graph/subgraph rt [new-id] {:type "depends-on"}))]
          (is (= (:id task) (:for summary)))
          (is (= (:id spawner) (:spawned-by summary)))
          (is (= "/tmp/retry-cwd" (get-in new-run [:attributes :agent-run/cwd])))
          (is (= 5 (get-in new-run [:attributes :agent-run/max-attempts])))
          (is (some #(= (:id blocker) (:to_strand_id %)) dep-edges))
          ;; regression (change-review-3abc37ac): a retried serving run keeps
          ;; BOTH edges of the C1 invariant — serves for semantics AND parent-of
          ;; placement under the served target, so it stays in status trees
          (is (some #(and (= "parent-of" (:edge_type %)) (= new-id (:to_strand_id %)))
                    (:edges (graph/subgraph rt [(:id task)])))
              "the successor keeps parent-of placement under the served target"))))))

(deftest retry-helper-run-preserves-structural-placement
  ;; regression (change-review-3abc37ac): retrying a read-only helper by run id
  ;; keeps its parent-of placement (spawn --for X still renders "for X") while
  ;; staying a helper — no serves edge on the successor.
  (with-agents
    (fn [rt]
      (let [task (weaver/add rt {:title "reconned"})
            helper (shuttle/spawn-run! {:harness :sh :prompt "exit 3" :parent (:id task)})]
        (await-phase rt (:id helper) #{"failed"})
        (let [retried (agents/agent-op {:op/argv ["retry" (:id helper) "--prompt" "echo recovered"]})
              new-id (get-in retried [:run :id])]
          (is (some #(and (= "parent-of" (:edge_type %)) (= new-id (:to_strand_id %)))
                    (:edges (graph/subgraph rt [(:id task)])))
              "the helper successor keeps parent-of placement")
          (is (empty? (graph/outgoing-edges rt [new-id] "serves"))
              "a helper retry stays a helper: no serves edge"))))))

(deftest retry-by-task-targets-serving-run-not-helper
  ;; Regression (card 0nd97): retry <task-id> means "retry the task's own work",
  ;; so it resolves against the failed SERVING run and never lets a failed
  ;; read-only helper (recon/review, no serves edge) shadow it.
  (with-agents
    (fn [rt]
      (let [task (weaver/add rt {:title "served + reconned" :attributes {:body "body" :agent-run/harness "sh"}})
            helper (shuttle/spawn-run! {:harness :sh :prompt "exit 3" :parent (:id task)})
            serving (shuttle/spawn-run! {:harness :sh :prompt "exit 3" :parent (:id task) :serves (:id task)})]
        (await-phase rt (:id helper) #{"failed"})
        (await-phase rt (:id serving) #{"failed"})
        (let [retried (agents/agent-op {:op/argv ["retry" (:id task)]})]
          (is (= (:id serving) (:superseded retried))
              "retry-by-task supersedes the failed serving run, not the helper")
          (is (= "superseded" (get-in (weaver/show rt (:id serving)) [:attributes :agent-run/phase])))
          (is (= "failed" (get-in (weaver/show rt (:id helper)) [:attributes :agent-run/phase]))
              "the failed non-serving helper is left untouched"))))))

(deftest retry-continuity-preserves-severs-and-guards-resume
  ;; resuming runs are hand-built so the matrix is deterministic and sleep-free:
  ;; the failed turn resumes a done predecessor and carries its own captured
  ;; session plus both prompt forms, exercising every A3 branch without launching
  ;; a real turn. A plain retry continues the failed turn's session (the
  ;; succession primitive resumes the run it supersedes, not the origin).
  (with-agents
    (fn [rt]
      (shuttle/register-harness! :session-fix session-fix)
      (let [make-pred (fn [] (:id (weaver/add rt {:title "predecessor turn"
                                                  :state "closed"
                                                  :attributes {"agent-run/run" "true"
                                                               "agent-run/harness" "session-fix"
                                                               "agent-run/session-id" "sess-abc"
                                                               "agent-run/phase" "done"}})))
            make-failed (fn [pred extra]
                          (:id (weaver/add rt {:title "resuming turn"
                                               :attributes (merge {"agent-run/run" "true"
                                                                   "agent-run/harness" "session-fix"
                                                                   "agent-run/prompt" "CONTINUATION prompt"
                                                                   "agent-run/fresh-prompt" "FULLBRIEF prompt"
                                                                   "agent-run/resumes" pred
                                                                   "agent-run/session-id" "sess-def"
                                                                   "agent-run/phase" "failed"}
                                                                  extra)})))]
        (testing "a plain retry continues the failed turn's own session on the continuation prompt"
          (let [pred (make-pred)
                failed (make-failed pred {})
                retried (agents/agent-op {:op/argv ["retry" failed]})
                new-run (weaver/show rt (get-in retried [:run :id]))]
            (is (= failed (:superseded retried)))
            (is (= failed (get-in new-run [:attributes :agent-run/resumes])))
            (is (str/includes? (get-in new-run [:attributes :agent-run/prompt]) "CONTINUATION prompt"))
            (is (= "FULLBRIEF prompt" (get-in new-run [:attributes :agent-run/fresh-prompt]))
                "the full-brief form carries forward for a later --fresh")))
        (testing "--fresh severs the linkage and cold-starts on the full-brief prompt"
          (let [pred (make-pred)
                failed (make-failed pred {})
                retried (agents/agent-op {:op/argv ["retry" failed "--fresh"]})
                new-run (weaver/show rt (get-in retried [:run :id]))]
            (is (nil? (get-in new-run [:attributes :agent-run/resumes])))
            (is (str/includes? (get-in new-run [:attributes :agent-run/prompt]) "FULLBRIEF prompt"))
            (is (not (str/includes? (get-in new-run [:attributes :agent-run/prompt]) "CONTINUATION prompt")))))
        (testing "a resume-classed failure refuses a plain retry and names --fresh"
          (let [pred (make-pred)
                failed (make-failed pred {"agent-run/error-class" "resume"})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--fresh"
                                  (agents/agent-op {:op/argv ["retry" failed]})))
            (testing "--fresh recovers it"
              (let [retried (agents/agent-op {:op/argv ["retry" failed "--fresh"]})
                    new-run (weaver/show rt (get-in retried [:run :id]))]
                (is (nil? (get-in new-run [:attributes :agent-run/resumes])))
                (is (str/includes? (get-in new-run [:attributes :agent-run/prompt]) "FULLBRIEF prompt"))))))))))

(deftest retry-preserves-panel-review-structural-attrs
  ;; a failed panel/review run is hand-built so the assertion is deterministic
  ;; and sleep-free: the retried run must keep the structural attrs that make
  ;; the deliberation queryable from run attrs, not just the prompt.
  (with-agents
    (fn [rt]
      (let [failed (:id (weaver/add rt {:title "panel seat turn"
                                        :attributes {"agent-run/run" "true"
                                                     "agent-run/harness" "sh"
                                                     "agent-run/prompt" "seat brief"
                                                     "agent-run/phase" "failed"
                                                     "panel/blackboard" "tgt-1"
                                                     "panel/pass" "panel-abc123"
                                                     "review/roster" "repo"
                                                     "review/focus" "skeptic"
                                                     "panel/seat" "skeptic"
                                                     "panel/turn" "2"
                                                  ;; a lifecycle attr the engine re-derives; it must NOT ride along
                                                     "agent-run/result" "stale old result"}}))
            retried (agents/agent-op {:op/argv ["retry" failed]})
            attrs (:attributes (weaver/show rt (get-in retried [:run :id])))]
        (is (= "tgt-1" (:panel/blackboard attrs)))
        (is (= "panel-abc123" (:panel/pass attrs)))
        (is (= "repo" (:review/roster attrs)))
        (is (= "skeptic" (:review/focus attrs)))
        (is (= "skeptic" (:panel/seat attrs)))
        (is (= "2" (:panel/turn attrs)))
        (is (not= "stale old result" (:agent-run/result attrs))
            "engine lifecycle attrs are re-stamped, not carried from the superseded run")))))

(deftest await-under-and-retry-workflow
  (with-agents
    (fn [rt]
      (let [plan (weaver/add rt {:title "plan"})
            task (weaver/add rt {:title "task" :attributes {:body "body" :agent-run/harness "sh"}})
            gate (weaver/add rt {:title "await gate"})
            _ (weaver/update rt (:id plan) {:edges [{:type "parent-of" :to (:id task)}]})
            delegated {:run (select-keys (shuttle/run-summary
                                          (shuttle/spawn-run! {:harness :sh :prompt "echo first"
                                                               :parent (:id task) :depends-on [(:id gate)]}))
                                         [:id :phase :harness])}]
        (weaver/update rt (:id gate) {:state "closed"})
        (let [{:keys [timed-out runs]} (agents/agent-op {:op/argv ["await" "--under" (:id plan) "--timeout-secs" (str (test-support/await-budget-secs))]})]
          (is (false? timed-out))
          (is (= (:id (get delegated :run)) (:id (first runs)))))
        (let [failed-task (weaver/add rt {:title "fails" :attributes {:body "exit 2" :agent-run/harness "sh"}})
              _ (weaver/update rt (:id plan) {:edges [{:type "parent-of" :to (:id failed-task)}]})
              d (agents/agent-op {:op/argv ["delegate" (:id failed-task)]})]
          (await-phase rt (get-in d [:run :id]) #{"failed"})
          (let [retried (agents/agent-op {:op/argv ["retry" (:id failed-task) "--prompt" "echo recovered"]})]
            (is (= (get-in d [:run :id]) (:superseded retried)))
            (is (= (:id failed-task) (:task retried))))
          (let [fresh (weaver/add rt {:title "fresh" :attributes {:body "body" :agent-run/harness "sh"}})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing to supersede"
                                  (agents/agent-op {:op/argv ["retry" (:id fresh)]})))))))))

(defn- status-tree-ids [status]
  (mapv :id (tree-seq #(seq (:children %)) :children {:children (:tree status)})))

(deftest status-triage-lists-ready-running-failed-and-verification
  (with-agents
    (fn [rt]
      (let [ready-task (weaver/add rt {:title "ready" :attributes {:body "body" :agent-run/harness "sh"}})
            implemented (weaver/add rt {:title "implemented" :attributes {:status "implemented"}})
            failed-run (shuttle/spawn-run! {:harness :sh :prompt "exit 9" :parent (:id ready-task)})]
        (await-phase rt (:id failed-run) #{"failed"})
        (let [status (agents/agent-op {:op/argv ["status"]})]
          (is (some #{(:id implemented)} (:awaiting_verification status)))
          (is (some #(= (:id failed-run) (:run %)) (:failed status))))))))

(deftest status-ignores-closed-tasks-in-triage-and-tree
  (with-agents
    (fn [rt]
      (let [plan (weaver/add rt {:title "plan"})
            blocker (weaver/add rt {:title "closed blocker" :state "closed"})
            closed-implemented (weaver/add rt {:title "closed implemented"
                                               :state "closed"
                                               :attributes {:status "implemented" :body "body" :agent-run/harness "sh"}})
            closed-blocked (weaver/add rt {:title "closed blocked"
                                           :state "closed"
                                           :attributes {:body "body" :agent-run/harness "sh"}
                                           :edges [{:type "depends-on" :to (:id blocker)}]})]
        (weaver/update rt (:id plan) {:edges [{:type "parent-of" :to (:id closed-implemented)}
                                              {:type "parent-of" :to (:id closed-blocked)}]})
        (let [status (agents/agent-op {:op/argv ["status" (:id plan)]})]
          (is (not-any? #{(:id closed-implemented)} (:awaiting_verification status))
              "closed implemented tasks are already verified")
          (is (not-any? #{(:id closed-implemented) (:id closed-blocked)} (:ready status)))
          (is (not-any? #(#{(:id closed-implemented) (:id closed-blocked)} (:task %)) (:blocked status)))
          (is (not-any? #{(:id closed-implemented) (:id closed-blocked)} (status-tree-ids status))
              "closed task descendants do not pollute the status tree"))))))

;; ---------------------------------------------------------------------------
;; Interactive delegation

(def ^:private fake-mux
  ;; The detached session runs the launcher command and then sleeps, staying
  ;; alive until :stop kills it — a real multiplexer session (tmux holding a
  ;; live TUI) outlives the command that opened it and ends only when reaped.
  ;; A session that exited the instant its command finished would let the
  ;; liveness probe in ps/supervise fail the run "dead" before the test closes
  ;; its served strand, a race that only surfaces under slow-runner load.
  {:start ["sh" "-c" "nohup sh -c '\"$0\" >/dev/null 2>&1; exec sleep 600' \"$1\" >/dev/null 2>&1 & printf '{\"pid\":\"%s\"}' \"$!\"" "fake-mux" :command]
   :alive ["kill" "-0" :handle/pid]
   :stop ["kill" :handle/pid]
   :attach ["echo" "attach" :handle/pid]
   :doc "test-only fake multiplexer over detached processes"})

(defn- await-handle-pid
  "Poll until the run's backend handle pid lands (it is written strictly
  after phase running) or timeout; return the strand."
  [rt id]
  (test-support/poll-until
   #(let [s (weaver/show rt id)]
      (when (get-in s [:attributes (keyword "agent-run" "handle.pid")]) s))
   {:on-timeout #(throw (ex-info "timeout waiting handle" {:id id :strand (weaver/show rt id)}))}))

(deftest backends-verb-lists-registered-backends
  (with-agents
    (fn [_]
      (shuttle/register-backend! :fake-mux fake-mux)
      (let [names (set (map :name (agents/agent-op {:op/argv ["backends"]})))]
        (is (contains? names "tmux"))
        (is (contains? names "fake-mux"))))))

(deftest hitl-task-delegates-only-interactively-and-reaps-on-close
  (with-agents
    (fn [rt]
      (shuttle/register-backend! :fake-mux fake-mux)
      (let [task (weaver/add rt {:title "pair on the plan"
                                 :attributes {:body "Discuss and agree the plan with the user."
                                              :agent-run/harness "sh" :hitl "true"}})]
        (testing "headless delegation of a hitl task fails loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hitl"
                                (agents/agent-op {:op/argv ["delegate" (:id task)]}))))
        (testing "interactive delegation requires a backend"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"backend"
                                (agents/agent-op {:op/argv ["delegate" (:id task) "--interactive"]}))))
        (testing "interactive-only flags without --interactive fail loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require --interactive"
                                (agents/agent-op {:op/argv ["delegate" (:id task) "--backend" "fake-mux"]})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"per-task"
                                (agents/agent-op {:op/argv ["delegate" "--ready" "some-plan" "--backend" "fake-mux"]}))))
        (testing "interactive delegation opens a session serving the task"
          (let [d (agents/agent-op {:op/argv ["delegate" (:id task) "--interactive" "--backend" "fake-mux"]})
                run-id (get-in d [:run :id])
                running (await-handle-pid rt run-id)
                pid (get-in running [:attributes (keyword "agent-run" "handle.pid")])]
            (testing "ps carries the attach command"
              (let [summary (first (filter #(= run-id (:id %)) (agents/agent-op {:op/argv ["ps" "--active"]})))]
                (is (= "interactive" (:mode summary)))
                (is (= (str "echo attach " pid) (:attach summary)))))
            (testing "the session prompt carries the interactive pairing framing"
              (is (clojure.string/includes? (get-in running [:attributes :agent-run/prompt])
                                            "working WITH the user")))
            (testing "closing the task reaps the session run"
              (weaver/update rt (:id task) {:state "closed"})
              (let [done (await-phase rt run-id #{"done"})]
                (is (= "closed" (:state done)))))))))))

(deftest interactive-retry-preserves-mode-and-backend
  (with-agents
    (fn [rt]
      (shuttle/register-backend! :fake-mux fake-mux)
      (let [task (weaver/add rt {:title "session task"
                                 :attributes {:body "work with the user" :agent-run/harness "sh" :hitl "true"}})
            d (agents/agent-op {:op/argv ["delegate" (:id task) "--interactive" "--backend" "fake-mux"]})
            run-id (get-in d [:run :id])]
        (await-handle-pid rt run-id)
        (agents/agent-op {:op/argv ["kill" run-id]})
        (await-phase rt run-id #{"failed"})
        (let [retried (agents/agent-op {:op/argv ["retry" (:id task)]})
              new-run (await-handle-pid rt (get-in retried [:run :id]))]
          (is (= "interactive" (get-in new-run [:attributes :agent-run/mode])))
          (is (= "fake-mux" (get-in new-run [:attributes :agent-run/backend])))
          (agents/agent-op {:op/argv ["kill" (:id new-run)]}))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for the agents-roster versioned spool-state: a key added to
  ;; new-state without a state-version bump would survive reload! as a stale map.
  (test-support/assert-state-shape
   ;; white-box read of a private var: kondo flags cross-ns private access, but
   ;; #'ns/private is legal and intentional here.
   #_{:clj-kondo/ignore [:unresolved-var]}
   #'agents/new-state
   #{:rosters}))
