(ns skein.delegation-test
  "Tests for the delegation spool layered over the agent-run engine."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as api]
            [skein.spools.delegation :as agents]
            [skein.spools.agent-run :as shuttle]
            [skein.spools.test-support :as test-support :refer [await-phase]]))

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

(deftest agents-install-registers-op-pattern-query
  (with-agents
    (fn [rt]
      (is (some #(= "agent" (:name %)) (api/ops rt)))
      (is (map? (agents/agent-op {:op/argv ["about"]})))
      (let [detail (api/resolve-op rt 'agent)]
        (is (not (contains? detail :raw-envelope)))
        (is (= ["about" "await" "backends" "council" "delegate" "harnesses" "kill" "logs" "note" "notes" "ps" "retry" "review" "rosters" "spawn" "status"]
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

(deftest spawn-for-creates-task-edge
  (with-agents
    (fn [rt]
      (let [task (api/add rt {:title "served task"})
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
          (is (= "/tmp/claude/review-cwd" (get-in run [:attributes :agent-run/cwd]))
              "review --cwd rides onto each reviewer run"))
        (doseq [run-id (:reviewers review)]
          (let [run (api/show rt run-id)]
            (is (= (:id target) (get-in run [:attributes :review/target])))
            (is (str/includes? (get-in run [:attributes :agent-run/prompt]) "Review contract"))))))))

(deftest review-consumes-workspace-default-contract
  (with-agents
    (fn [rt]
      (try
        (shuttle/set-default-review-contract! "Workspace policy contract")
        (let [target (api/add rt {:title "Default-contract target"})
              review (agents/review! (:id target) {:reviewers [{:harness :sh :focus "policy"}]})
              run (api/show rt (first (:reviewers review)))]
          (is (str/includes? (get-in run [:attributes :agent-run/prompt])
                             "Workspace policy contract"))
          (let [review* (agents/review! (:id target) {:reviewers [{:harness :sh :focus "override"}]
                                                      :contract "Explicit contract"})
                run* (api/show rt (first (:reviewers review*)))]
            (is (str/includes? (get-in run* [:attributes :agent-run/prompt]) "Explicit contract"))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                              (shuttle/set-default-review-contract! "  ")))
        (finally
          (shuttle/set-default-review-contract! nil))))))

(deftest defroster-validates-and-lists-rosters
  (with-agents
    (fn [_]
      (testing "registration returns a summary and rosters lists full data"
        (is (= {:roster :repo :reviewers 2}
               (agents/defroster! "repo"
                 {:reviewers [{:name "tests" :harness :sh :contract "Judge the tests." :scope "test files"}
                              {:name "docs" :harness "sh" :contract "Judge the docs."}]
                  :synthesizer {:harness :sh}})))
        (let [[roster] (agents/rosters)]
          (is (= :repo (:name roster)))
          (is (= ["tests" "docs"] (mapv :name (:reviewers roster))))
          (is (= {:harness :sh} (:synthesizer roster)))))
      (testing "re-registration replaces the roster"
        (agents/defroster! :repo {:reviewers [{:name "solo" :harness :sh :contract "One pass."}]})
        (is (= ["solo"] (mapv :name (:reviewers (first (agents/rosters)))))))
      (testing "the roster shape is spec-defined and registered data conforms"
        (is (s/valid? :skein.spools.delegation/roster
                      {:reviewers [{:name "solo" :harness :sh :contract "One pass."}]})))
      (testing "structurally malformed roster data fails loudly via the spec"
        (doseq [bad [[:not-a-map]
                     {:reviewers []}
                     {:reviewers [{:harness :sh :contract "c"}]}
                     {:reviewers [{:name "r" :contract "c"}]}
                     {:reviewers [{:name "r" :harness :sh}]}
                     {:reviewers [{:name "r" :harness :sh :contract "c" :scope "  "}]}
                     {:reviewers [{:name "r" :harness :sh :contract "c"}] :synthesizer :sh}
                     {:reviewers [{:name "r" :harness :sh :contract "c"}] :synthesizer {}}]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not conform to spec"
                                (agents/defroster! :bad bad)))))
      (testing "checks the spec cannot express stay loud"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (agents/defroster! :bad {:reviewers [{:name "r" :harness :sh :contract "c" :contarct "typo"}]})))
        ;; a typo REPLACING a required key must diagnose as the unknown key,
        ;; not as the missing-key spec explain it also causes
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (agents/defroster! :bad {:reviewers [{:name "r" :harness :sh :contarct "c"}]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (agents/defroster! :bad {:reviewers [{:name "r" :harness :sh :contract "c"}] :extra true})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (agents/defroster! :bad {:reviewers [{:name "r" :harness :sh :contract "c"}]
                                                       :synthesizer {:harness :sh :model "x"}})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be unique"
                              (agents/defroster! :bad {:reviewers [{:name "r" :harness :sh :contract "a"}
                                                                   {:name "r" :harness :sh :contract "b"}]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keyword or non-blank string"
                              (agents/defroster! "  " {:reviewers [{:name "r" :harness :sh :contract "c"}]})))))))

(deftest roster-review-fans-out-declared-reviewers
  (with-agents
    (fn [rt]
      (agents/defroster! :repo
        {:reviewers [{:name "test-sleeps" :harness :sh
                      :contract "Flag sleeps and arbitrary timeouts in tests."
                      :scope "test files"}
                     {:name "docs" :harness :sh :contract "Judge documentation drift."}]
         :synthesizer {:harness :sh}})
      (let [target (api/add rt {:title "Roster target" :attributes {:body "Inspect me"}})
            review (agents/review! (:id target) {:roster :repo :cwd "/tmp/claude/roster-cwd"})
            runs (mapv #(api/show rt %) (:reviewers review))
            synth (api/show rt (:synthesizer review))]
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
        (testing "without a declared synthesizer the first reviewer's harness is used"
          (agents/defroster! :undeclared
            {:reviewers [{:name "solo" :harness :sh :contract "One pass."}]})
          (let [review* (agents/review! (:id target) {:roster :undeclared})
                synth* (api/show rt (:synthesizer review*))]
            (is (= "sh" (get-in synth* [:attributes :agent-run/harness])))))))))

(deftest roster-review-specs-are-the-single-prompt-source
  (with-agents
    (fn [rt]
      (agents/defroster! :composed
        {:reviewers [{:name "a" :harness :sh :contract "Contract A." :scope "src"}
                     {:name "b" :harness :sh :contract "Contract B."}]
         :synthesizer {:harness :sh}})
      (let [target (api/add rt {:title "Spec target"})
            review (agents/review! (:id target) {:roster :composed})
            specs (agents/roster-review-specs :composed {:target (:id target)
                                                         :review-id (:review-pass review)})
            runs (mapv #(api/show rt %) (:reviewers review))
            synth (api/show rt (:synthesizer review))]
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
                             (str "[" (:review-pass review) "]")))
          (is (str/includes? (get-in specs [:synthesizer :prompt])
                             (str "tagged [" (:review-pass review) "]")))
          (is (not= (:review-pass (agents/roster-review-specs :composed {:target (:id target)}))
                    (:review-pass (agents/roster-review-specs :composed {:target (:id target)})))
              "each pass mints a distinct tag"))
        (testing "the seam output conforms to its public spec"
          (is (s/valid? :skein.spools.delegation/review-specs specs)
              (s/explain-str :skein.spools.delegation/review-specs specs)))
        (testing "an inline roster value works anywhere a name does"
          (let [inline {:reviewers [{:name "adhoc" :harness :sh :contract "One-off pass."}]}
                inline-specs (agents/roster-review-specs inline {:target (:id target)})
                inline-review (agents/review! (:id target) {:roster inline})
                run (api/show rt (first (:reviewers inline-review)))]
            (is (= :inline (:roster inline-specs)))
            (is (s/valid? :skein.spools.delegation/review-specs inline-specs))
            (is (= "inline" (get-in run [:attributes :review/roster])))
            (is (str/includes? (get-in run [:attributes :agent-run/prompt]) "One-off pass."))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not conform to spec"
                                  (agents/roster-review-specs {:reviewers []} {:target (:id target)})))))
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
      (agents/defroster! :repo {:reviewers [{:name "solo" :harness :sh :contract "One pass."}]})
      (let [target (api/add rt {:title "Roster failure target"})]
        (testing "unknown roster"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Roster not found"
                                (agents/review! (:id target) {:roster :nope}))))
        (testing "roster is the one authoritative source of reviewer settings"
          (doseq [conflicting [{:members 3} {:harnesses ["sh"]} {:contract "c"} {:reviewers [{:harness :sh}]}]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"from the roster"
                                  (agents/review! (:id target) (merge {:roster :repo} conflicting))))))
        (testing "CLI flag conflicts surface through the op"
          (doseq [flag-pair [["--members" "3"] ["--harness" "sh"] ["--contract" "c"]]]
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
      (testing "post-with-tag prefixes the tag and omits it when nil"
        (is (str/includes? (#'agents/post-with-tag-fragment {:board-id "s1" :tag "pass-7"})
                           "\"[pass-7] <findings>\""))
        (is (str/includes? (#'agents/post-with-tag-fragment {:board-id "s1" :tag nil})
                           "\"<findings>\"")))
      (testing "review-prompt is assembled from the shared fragments byte-for-byte"
        (let [prompt (#'agents/review-prompt {:target-id "s1" :contract "C" :note-tag "p1"})]
          (is (str/includes? prompt (#'agents/read-the-board-fragment {:view :strand :board-id "s1"})))
          (is (str/includes? prompt (#'agents/post-with-tag-fragment {:board-id "s1" :tag "p1"}))))))))

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
        {:reviewers [{:name "a" :harness :sh :contract "Contract A."}
                     {:name "b" :harness :sh :contract "Contract B."}]
         :synthesizer {:harness :sh}})
      (let [target (api/add rt {:title "ctx target"})
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
                run (api/show rt (first (:reviewers review)))]
            (is (str/includes? (get-in run [:attributes :agent-run/prompt]) "Commit range: main..HEAD"))))
        (testing "malformed change context fails loudly against its spec"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"change-context does not conform"
                                (agents/roster-review-specs :ctx {:target (:id target)
                                                                  :change-context {:files "not-a-vector"}}))))))))

(deftest review-validates-change-context-on-the-ad-hoc-path
  (with-agents
    (fn [rt]
      (let [target (api/add rt {:title "ad-hoc ctx target"})]
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
      (let [target (api/add rt {:title "Panel target" :attributes {:body "Inspect me"}})
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
            (is (= (:id target) (get (:attrs spec) "review/target")))
            (is (= (:review-pass specs) (get (:attrs spec) "review/pass")))
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
          (is (= "pass-x" (:review-pass (agents/panel-specs panel {:target (:id target) :review-id "pass-x"})))))))))

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
          (is (str/includes? (:resume-prompt (first (nth (:turns specs) 2))) "Read peers' turn 2")))))))

(deftest panel-spawns-fresh-board-with-barriers-and-resume-threads
  (with-agents
    (fn [rt]
      (shuttle/defharness! :session-fix session-fix)
      (let [panel {:seats [{:name "alpha" :harness :session-fix :brief "Deliberate." :continuity :resume}
                           {:name "beta" :harness :session-fix :brief "Deliberate too." :continuity :resume}]
                   :turns {:rounds 2}
                   :blackboard :fresh
                   :synthesis {:harness :session-fix}}
            result (agents/panel! panel {})
            board (api/show rt (:blackboard result))
            [row1 row2] (:turns result)
            deps-of (fn [id]
                      (->> (:edges (graph/subgraph rt [id] {:type "depends-on"}))
                           (filter #(= id (:from_strand_id %)))
                           (map :to_strand_id) set))]
        (testing "a fresh blackboard strand is minted"
          (is (= "panel" (get-in board [:attributes :panel/role]))))
        (testing "each turn row has one run per seat"
          (is (= 2 (count row1)))
          (is (= 2 (count row2))))
        (testing "turn 2 barriers on every turn-1 run"
          (doseq [run-id row2]
            (is (= (set row1) (deps-of run-id)))))
        (testing "turn 2 threads each seat onto its own predecessor via agent-run/resumes"
          (is (= (first row1) (get-in (api/show rt (first row2)) [:attributes :agent-run/resumes])))
          (is (= (second row1) (get-in (api/show rt (second row2)) [:attributes :agent-run/resumes]))))
        (testing "spawned prompts select the form and resolve the board placeholder"
          (let [p1 (get-in (api/show rt (first row1)) [:attributes :agent-run/prompt])
                p2 (get-in (api/show rt (first row2)) [:attributes :agent-run/prompt])]
            (is (str/includes? p1 "You are seat"))
            (is (str/includes? p2 "Continuing as seat"))
            (is (not (str/includes? p2 "«panel-board»")))
            (is (str/includes? p2 (:blackboard result)))
            (is (= (:blackboard result)
                   (get-in (api/show rt (first row1)) [:attributes :review/target])))))
        (testing "a resuming turn stashes its full-brief prompt for retry --fresh"
          (let [f2 (get-in (api/show rt (first row2)) [:attributes :panel/fresh-prompt])]
            (is (str/includes? f2 "You are seat")
                "the full form, not the continuation, is durable for a cold restart")
            (is (not (str/includes? f2 "«panel-board»")))))
        (testing "the synthesizer depends on the final turn row"
          (is (= (set row2) (deps-of (:synthesizer result)))))))))

(deftest panel-fails-loudly
  (with-agents
    (fn [rt]
      (shuttle/defharness! :session-fix-no-resume session-fix-no-resume)
      (let [target (api/add rt {:title "panel fail target"})]
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
      (let [roster {:reviewers [{:name "tests" :harness :sh :contract "Judge the tests." :scope "test files"}
                                {:name "docs" :harness "sh" :contract "Judge the docs."}]
                    :synthesizer {:harness :sh}}
            panel (agents/roster->panel roster)
            target (api/add rt {:title "roster-panel target"})]
        (testing "each reviewer becomes an independent seat carrying its contract as brief"
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
        (testing "a synthesizer-less roster falls back to the first reviewer's harness"
          (is (= {:harness :sh}
                 (:synthesis (agents/roster->panel {:reviewers [{:name "solo" :harness :sh :contract "One pass."}]})))))
        (testing "a malformed roster fails identically to defroster! input"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not conform to spec"
                                (agents/roster->panel {:reviewers []}))))))))

(deftest council-turn-rows-per-seat-harnesses-and-loud-harness
  (with-agents
    (fn [rt]
      (let [deps-of (fn [id]
                      (->> (:edges (graph/subgraph rt [id] {:type "depends-on"}))
                           (filter #(= id (:from_strand_id %)))
                           (map :to_strand_id) set))]
        (testing "scalar members expand to identical seats across turn-as-run rows"
          (let [{:keys [council turns synthesizer]}
                (agents/council! "test topic" {:harness :sh :members 2 :rounds 2})
                board (api/show rt council)
                [row1 row2] turns]
            (is (= "panel" (get-in board [:attributes :panel/role]))
                "council re-ships as a fresh-blackboard panel")
            (is (= [2 2] [(count row1) (count row2)]))
            (testing "the council strand parents every run"
              (is (= (set (concat row1 row2 [synthesizer]))
                     (set (map :to_strand_id (:edges (graph/subgraph rt [council])))))))
            (testing "the poll-loop choreography is gone; the topic remains"
              (doseq [run-id (concat row1 row2 [synthesizer])]
                (let [p (get-in (api/show rt run-id) [:attributes :agent-run/prompt])]
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
                prompts (mapv #(get-in (api/show rt %) [:attributes :agent-run/prompt]) row1)]
            (is (= 2 (count row1)))
            (is (some #(str/includes? % "Your assigned perspective: Argue against.") prompts))
            (is (some #(str/includes? % "Your assigned perspective: Argue for.") prompts))
            (is (every? #(= "sh" (get-in (api/show rt %) [:attributes :agent-run/harness])) row1))))
        (testing "council fails loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                                (agents/council! "  " {:harness :sh})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not conform"
                                (agents/council! "t" {:harness :sh :rounds 0})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a harness"
                                (agents/council! "t" {:members 2}))
              "the silent :claude default is gone: no harness resolves loudly")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not both"
                                (agents/council! "t" {:harness :sh :members 2
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
                                (agents/council! "t" {:harness :sh :membres 2})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                                (agents/council! "t" {:harness :sh
                                                      :seats [{:name "a" :harnes :sh}]}))))))))

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
                 (get-in run [:attributes :agent-run/cwd]))
              "agents supplies workspace root explicitly, so harness :cwd cannot win"))
        (let [gate (api/add rt {:title "gate"})]
          (shuttle/spawn-run! {:harness :sh :prompt "echo later" :parent (:id active) :serves (:id active) :depends-on [(:id gate)]})
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
      (let [active-task (api/add rt {:title "active run task" :attributes {:body "body" :harness "sh"}})
            failed-task (api/add rt {:title "failed run task" :attributes {:body "body" :harness "sh"}})
            done-task (api/add rt {:title "done run task" :attributes {:body "body" :harness "sh"}})
            plan (api/add rt {:title "plan"})
            gate (api/add rt {:title "gate"})]
        (api/update rt (:id plan) {:edges [{:type "parent-of" :to (:id active-task)}
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
      (let [plan (api/add rt {:title "plan"})
            tasks (mapv (fn [n] (api/add rt {:title (str "task " n)
                                             :attributes {:body "body" :harness "sh"}}))
                        (range 3))]
        (api/update rt (:id plan) {:edges (mapv (fn [t] {:type "parent-of" :to (:id t)}) tasks)})
        (let [ready (agents/agent-op {:op/argv ["delegate" "--ready" (:id plan)]})]
          (is (= (set (map :id tasks)) (set (map :task (:delegated ready))))
              "every ready task is delegated exactly once")
          (is (apply distinct? (map :task (:delegated ready)))
              "no task appears twice in :delegated")
          (is (empty? (:skipped ready))
              "a task delegated this pass is never re-reported as skipped"))))))

(defn- serves? [rt run-id target-id]
  (some #(= target-id (:to_strand_id %)) (graph/outgoing-edges rt [run-id] "serves")))

(deftest non-serving-helpers-do-not-gate-delegation
  ;; Regression (card b4nml): recon spawns and review runs carry parent-of
  ;; placement alone with no serves edge, so a read-only helper run hanging under
  ;; a task must never block a later delegate of it, nor make delegate --ready
  ;; skip it as has-active-run.
  (with-agents
    (fn [rt]
      (let [spawn-task (api/add rt {:title "recon then delegate" :attributes {:body "body" :harness "sh"}})
            review-task (api/add rt {:title "review then delegate" :attributes {:body "body" :harness "sh"}})
            plan (api/add rt {:title "plan"})]
        (api/update rt (:id plan) {:edges [{:type "parent-of" :to (:id spawn-task)}
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
              dep-edges (:edges (graph/subgraph rt [new-id] {:type "depends-on"}))]
          (is (= (:id task) (:for summary)))
          (is (= (:id spawner) (:spawned-by summary)))
          (is (= "/tmp/retry-cwd" (get-in new-run [:attributes :agent-run/cwd])))
          (is (= 5 (get-in new-run [:attributes :agent-run/max-attempts])))
          (is (some #(= (:id blocker) (:to_strand_id %)) dep-edges)))))))

(deftest retry-by-task-targets-serving-run-not-helper
  ;; Regression (card 0nd97): retry <task-id> means "retry the task's own work",
  ;; so it resolves against the failed SERVING run and never lets a failed
  ;; read-only helper (recon/review, no serves edge) shadow it.
  (with-agents
    (fn [rt]
      (let [task (api/add rt {:title "served + reconned" :attributes {:body "body" :harness "sh"}})
            helper (shuttle/spawn-run! {:harness :sh :prompt "exit 3" :parent (:id task)})
            serving (shuttle/spawn-run! {:harness :sh :prompt "exit 3" :parent (:id task) :serves (:id task)})]
        (await-phase rt (:id helper) #{"failed"})
        (await-phase rt (:id serving) #{"failed"})
        (let [retried (agents/agent-op {:op/argv ["retry" (:id task)]})]
          (is (= (:id serving) (:superseded retried))
              "retry-by-task supersedes the failed serving run, not the helper")
          (is (= "superseded" (get-in (api/show rt (:id serving)) [:attributes :agent-run/phase])))
          (is (= "failed" (get-in (api/show rt (:id helper)) [:attributes :agent-run/phase]))
              "the failed non-serving helper is left untouched"))))))

(deftest retry-continuity-preserves-severs-and-guards-resume
  ;; resuming runs are hand-built so the matrix is deterministic and sleep-free:
  ;; a closed predecessor with a captured session, plus a failed turn carrying
  ;; both prompt forms, exercises every A3 branch without launching a real turn.
  (with-agents
    (fn [rt]
      (shuttle/defharness! :session-fix session-fix)
      (let [make-pred (fn [] (:id (api/add rt {:title "predecessor turn"
                                               :state "closed"
                                               :attributes {"agent-run/run" "true"
                                                            "agent-run/harness" "session-fix"
                                                            "agent-run/session-id" "sess-abc"
                                                            "agent-run/phase" "done"}})))
            make-failed (fn [pred extra]
                          (:id (api/add rt {:title "resuming turn"
                                            :attributes (merge {"agent-run/run" "true"
                                                                "agent-run/harness" "session-fix"
                                                                "agent-run/prompt" "CONTINUATION prompt"
                                                                "panel/fresh-prompt" "FULLBRIEF prompt"
                                                                "agent-run/resumes" pred
                                                                "agent-run/phase" "failed"}
                                                               extra)})))]
        (testing "a plain retry re-resumes the same predecessor on the continuation prompt"
          (let [pred (make-pred)
                failed (make-failed pred {})
                retried (agents/agent-op {:op/argv ["retry" failed]})
                new-run (api/show rt (get-in retried [:run :id]))]
            (is (= failed (:superseded retried)))
            (is (= pred (get-in new-run [:attributes :agent-run/resumes])))
            (is (str/includes? (get-in new-run [:attributes :agent-run/prompt]) "CONTINUATION prompt"))
            (is (= "FULLBRIEF prompt" (get-in new-run [:attributes :panel/fresh-prompt]))
                "the full-brief form carries forward for a later --fresh")))
        (testing "--fresh severs the linkage and cold-starts on the full-brief prompt"
          (let [pred (make-pred)
                failed (make-failed pred {})
                retried (agents/agent-op {:op/argv ["retry" failed "--fresh"]})
                new-run (api/show rt (get-in retried [:run :id]))]
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
                    new-run (api/show rt (get-in retried [:run :id]))]
                (is (nil? (get-in new-run [:attributes :agent-run/resumes])))
                (is (str/includes? (get-in new-run [:attributes :agent-run/prompt]) "FULLBRIEF prompt"))))))))))

(deftest retry-preserves-panel-review-structural-attrs
  ;; a failed panel/review run is hand-built so the assertion is deterministic
  ;; and sleep-free: the retried run must keep the structural attrs that make
  ;; the deliberation queryable from run attrs, not just the prompt.
  (with-agents
    (fn [rt]
      (let [failed (:id (api/add rt {:title "panel seat turn"
                                     :attributes {"agent-run/run" "true"
                                                  "agent-run/harness" "sh"
                                                  "agent-run/prompt" "seat brief"
                                                  "agent-run/phase" "failed"
                                                  "review/target" "tgt-1"
                                                  "review/pass" "panel-abc123"
                                                  "review/roster" "repo"
                                                  "review/focus" "skeptic"
                                                  "panel/seat" "skeptic"
                                                  "panel/turn" "2"
                                                  ;; a lifecycle attr the engine re-derives; it must NOT ride along
                                                  "agent-run/result" "stale old result"}}))
            retried (agents/agent-op {:op/argv ["retry" failed]})
            attrs (:attributes (api/show rt (get-in retried [:run :id])))]
        (is (= "tgt-1" (:review/target attrs)))
        (is (= "panel-abc123" (:review/pass attrs)))
        (is (= "repo" (:review/roster attrs)))
        (is (= "skeptic" (:review/focus attrs)))
        (is (= "skeptic" (:panel/seat attrs)))
        (is (= "2" (:panel/turn attrs)))
        (is (not= "stale old result" (:agent-run/result attrs))
            "engine lifecycle attrs are re-stamped, not carried from the superseded run")))))

(deftest await-under-and-retry-workflow
  (with-agents
    (fn [rt]
      (let [plan (api/add rt {:title "plan"})
            task (api/add rt {:title "task" :attributes {:body "body" :harness "sh"}})
            gate (api/add rt {:title "await gate"})
            _ (api/update rt (:id plan) {:edges [{:type "parent-of" :to (:id task)}]})
            delegated {:run (select-keys (shuttle/run-summary
                                          (shuttle/spawn-run! {:harness :sh :prompt "echo first"
                                                               :parent (:id task) :depends-on [(:id gate)]}))
                                         [:id :phase :harness])}]
        (api/update rt (:id gate) {:state "closed"})
        (let [{:keys [timed-out runs]} (agents/agent-op {:op/argv ["await" "--under" (:id plan) "--timeout-secs" (str (test-support/await-budget-secs))]})]
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

(defn- status-tree-ids [status]
  (mapv :id (tree-seq #(seq (:children %)) :children {:children (:tree status)})))

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

(deftest status-ignores-closed-tasks-in-triage-and-tree
  (with-agents
    (fn [rt]
      (let [plan (api/add rt {:title "plan"})
            blocker (api/add rt {:title "closed blocker" :state "closed"})
            closed-implemented (api/add rt {:title "closed implemented"
                                            :state "closed"
                                            :attributes {:status "implemented" :body "body" :harness "sh"}})
            closed-blocked (api/add rt {:title "closed blocked"
                                        :state "closed"
                                        :attributes {:body "body" :harness "sh"}
                                        :edges [{:type "depends-on" :to (:id blocker)}]})]
        (api/update rt (:id plan) {:edges [{:type "parent-of" :to (:id closed-implemented)}
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
   #(let [s (api/show rt id)]
      (when (get-in s [:attributes (keyword "agent-run" "handle.pid")]) s))
   {:on-timeout #(throw (ex-info "timeout waiting handle" {:id id :strand (api/show rt id)}))}))

(deftest backends-verb-lists-registered-backends
  (with-agents
    (fn [_]
      (shuttle/defbackend! :fake-mux fake-mux)
      (let [names (set (map :name (agents/agent-op {:op/argv ["backends"]})))]
        (is (contains? names "tmux"))
        (is (contains? names "fake-mux"))))))

(deftest hitl-task-delegates-only-interactively-and-reaps-on-close
  (with-agents
    (fn [rt]
      (shuttle/defbackend! :fake-mux fake-mux)
      (let [task (api/add rt {:title "pair on the plan"
                              :attributes {:body "Discuss and agree the plan with the user."
                                           :harness "sh" :hitl "true"}})]
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
              (api/update rt (:id task) {:state "closed"})
              (let [done (await-phase rt run-id #{"done"})]
                (is (= "closed" (:state done)))))))))))

(deftest interactive-retry-preserves-mode-and-backend
  (with-agents
    (fn [rt]
      (shuttle/defbackend! :fake-mux fake-mux)
      (let [task (api/add rt {:title "session task"
                              :attributes {:body "work with the user" :harness "sh" :hitl "true"}})
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
