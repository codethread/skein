(ns skein.config-test
  "Tests for the repo-local .skein config modules (config.clj plus the
  harnesses.clj and workflows.clj siblings): registration surface, the
  delegate-pipeline weave pattern, the land workflow, and the devflow op
  wrappers over skein.spools.devflow."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.core.db-test :as db-test]
            [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.runtime.alpha :as runtime-alpha]
            [skein.api.graph.alpha :as graph]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as api]
            [skein.core.weaver.config :as daemon-config]
            [skein.core.weaver.runtime :as runtime]
            [skein.spools.workflow :as workflow]
            [skein.test.alpha :as test-alpha]))

(defn- delete-directory!
  "Delete a directory tree rooted at `path` if it exists."
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [child (reverse (file-seq file))]
        (io/delete-file child true)))))

(defn- test-world
  "Return an isolated test world rooted in a temporary directory."
  [config-dir]
  (daemon-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn- with-config-runtime
  "Run f with an isolated runtime and the repo-local .skein config loaded.

  Loads the split config modules the way init.clj orders them: config.clj
  first (workflows.clj references its public CLI-tail helpers at load time),
  then harnesses.clj and workflows.clj. attention.clj and nvd_scan.clj are
  deliberately not loaded here — chime rules are asserted through the full
  startup fixture, and the NVD job must never register from a direct load."
  [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-config-test-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [rt (runtime/start! db-file {:world (test-world config-dir)})]
      (try
        ;; harnesses.clj's pi-main alias layers over the shipped :pi harness,
        ;; which shuttle/install! registers in real startups; this fixture
        ;; loads the config files alone, so register the defaults here.
        ((requiring-resolve 'skein.spools.shuttle/register-default-harnesses!))
        (load-file ".skein/config.clj")
        (load-file ".skein/harnesses.clj")
        (load-file ".skein/workflows.clj")
        ((requiring-resolve 'config/install!))
        ((requiring-resolve 'harnesses/install!))
        ((requiring-resolve 'workflows/install!))
        (f rt)
        (finally
          (runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- copy-config-dir!
  "Copy the repo-local config files into a temporary config dir."
  [target]
  (.mkdirs (io/file target))
  (doseq [name ["init.clj" "config.clj" "workflows.clj" "harnesses.clj"
                "attention.clj" "nvd_scan.clj" "reviewers.clj" "spools.edn"]]
    (io/copy (io/file ".skein" name) (io/file target name)))
  ;; The shipped spools.edn approves local roots relative to the config dir,
  ;; which does not resolve from a copy. Rewrite it to the repo's canonical
  ;; spool roots so the whole test JVM syncs one root per lib
  ;; (tools.deps add-libs state is JVM-global; see skein.test-runner's
  ;; ordering note).
  (spit (io/file target "spools.edn")
        (pr-str {:spools {'skein.spools/shuttle
                          {:local/root (.getCanonicalPath (io/file "spools/shuttle"))}
                          'skein.spools/agents
                          {:local/root (.getCanonicalPath (io/file "spools/agents"))}
                          'skein.spools/chime
                          {:local/root (.getCanonicalPath (io/file "spools/chime"))}
                          'skein.spools/kanban
                          {:local/root (.getCanonicalPath (io/file "spools/kanban"))}
                          'skein.spools/cron
                          {:local/root (.getCanonicalPath (io/file "spools/cron"))}
                          'skein.spools/bench
                          {:local/root (.getCanonicalPath (io/file "spools/bench"))}
                          ;; init.clj requires this spool; the omission used to be
                          ;; masked by fail-quiet required use!, which now throws.
                          ;; Its root lives inside the workspace (.skein/spools/macros),
                          ;; unlike the repo-root spools above.
                          'skein.macros/macros
                          {:local/root (.getCanonicalPath (io/file ".skein/spools/macros"))}
                          'codethread/devflow
                          {:local/root (.getCanonicalPath (test-alpha/spool-checkout-root "skein/spools/devflow.clj"))}}}))
  ;; The shipped config leaves chime's notifier to each developer's personal
  ;; init.local.clj. Bind an inert command through that same overlay hook
  ;; (loaded after init.clj on startup and on every reload) so the test also
  ;; exercises the overlay path, a developer's real init.local.clj is never
  ;; read, and test-created HITL checkpoints record no notifier-missing noise.
  (spit (io/file target "init.local.clj")
        (pr-str '(do (require '[skein.spools.chime :as chime])
                     (chime/set-notifier! {:argv ["true"]})))))

(defn- with-startup-config-runtime
  "Run f with an isolated runtime started through copied .skein/init.clj."
  [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-config-startup-" (java.util.UUID/randomUUID))]
    (copy-config-dir! config-dir)
    (let [rt (runtime/start! db-file {:world (test-world config-dir)})]
      (try
        (f rt)
        (finally
          (runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- op!
  "Invoke a repo-local registered op by name with a CLI-shaped argv."
  [op-name argv]
  (api/op! (current/runtime) (symbol op-name) argv))

(def ^:private config-op-names
  "The config-owned CLI ops whose generated `help <op>` the refactor must preserve.

  Every op authored as a `defop` in .skein/config.clj; the surrounding spool ops
  (kanban/agent/bench) and workflow ops (land) are untouched by the refactor and
  are covered by their own tests, so this holistic guard scopes to config.clj."
  ["current-dags" "branches" "carder-report" "devflow-start" "devflow-next"
   "devflow-choices" "devflow-choose" "devflow-complete" "devflow-advance"
   "devflow-describe" "devflow-history" "devflow-archive" "devflow-status"
   "workflow-runs" "devflow-conventions" "flow-await" "flow-status" "hitl"])

(def ^:private named-query-names
  "The config-owned named queries whose registered definitions the refactor must
  preserve, authored as `defquery` blocks in .skein/config.clj."
  ["feature-active" "feature-work" "feature-owner-work" "feature-run"
   "workflow-runs" "devflow-runs" "work"])

(defn- capture-config-surface
  "Load the config module at config-path into an isolated runtime and return its
  registered config-owned surface as plain, EDN-round-trippable data.

  The surface is `{:op-help {op -> help-detail} :queries {name -> definition}}`.
  Only config.clj is loaded (no harnesses/workflows), so this captures exactly the
  op/query surface the defquery/defop refactor could have perturbed; the
  devflow-conventions payload is pinned separately by
  `devflow-conventions-op-lists-repo-conventions`. Used both to snapshot the
  pre-refactor baseline and to capture the current converted config for a
  byte-identical comparison."
  [config-path]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-surface-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [rt (runtime/start! db-file {:world (test-world config-dir)})]
      (try
        (load-file config-path)
        ((requiring-resolve 'config/install!))
        {:op-help (into {} (map (fn [op] [op (op! "help" [op])])) config-op-names)
         :queries (into {} (map (fn [q] [q (get (graph/queries rt) q)])) named-query-names)}
        (finally
          (runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- assert-config-registrations
  "Assert the repo-local query/op/pattern registrations are present."
  [rt]
  (doseq [query-name ["kanban-cards" "kanban-unstarted" "feature-active" "feature-work"
                      "feature-owner-work" "feature-run" "workflow-runs" "devflow-runs" "work"]]
    (is (contains? (graph/queries rt) query-name)))
  (is (contains? (graph/queries rt) "bench-runs"))
  (doseq [op-name ["kanban" "branches" "current-dags" "devflow-start" "devflow-next" "devflow-choices"
                   "devflow-choose" "devflow-complete" "devflow-advance"
                   "devflow-describe" "devflow-history" "devflow-archive"
                   "devflow-status" "workflow-runs" "devflow-conventions"
                   "flow-await" "flow-status" "hitl" "land" "agent" "bench"]]
    (is (some #(= op-name (:name %)) (api/ops rt))))
  (is (some #(= "delegate-pipeline" (:name %)) (patterns/patterns rt)))
  ;; agent-plan is spool-owned now; a real startup wires the agents spool in
  ;; via init.clj, so it must still be registered end to end
  (is (some #(= "agent-plan" (:name %)) (patterns/patterns rt)))
  ;; agent review must consume the one authoritative policy text by default;
  ;; the text ships from skein.spools.agents, the accessor stays on shuttle
  (is (= (var-get (requiring-resolve 'skein.spools.agents/review-contract))
         ((requiring-resolve 'skein.spools.shuttle/default-review-contract-text))))
  ;; the repo owns chime's attention rules; the chime engine ships none
  (is (= [:agent-failure :hitl-checkpoint-ready :kanban-blocked :kanban-completed
          :kanban-started :parked-run :treadle-error]
         (mapv :name ((requiring-resolve 'skein.spools.chime/rules)))))
  ;; the declarative reviewer rosters register from .skein/reviewers.clj
  (let [rosters ((requiring-resolve 'skein.spools.agents/rosters))]
    (is (= [:change-review :complex-patch-review :docs-review] (mapv :name rosters)))
    (is (some #(= "test-sleeps" (:name %)) (:reviewers (first rosters))))))

(deftest devflow-conventions-op-lists-repo-conventions
  ;; :queries derives from skein.macros.queries/remembered-queries (TASK-Srm-007);
  ;; :ops stays the RFC-020.Q2 hand-authored fallback (PLAN-Srm-001.DN1). This
  ;; pins the whole payload so neither listing can silently drift.
  (with-config-runtime
    (fn [_rt]
      (is (= {:operation "devflow-conventions"
              :spools [{:namespace "skein.spools.workflow"
                        :doc "spools/workflow.md"
                        :purpose "Workflow engine: definitions compiled to strand molecules with checkpoints, routing, and gates."}
                       {:namespace "skein.spools.devflow"
                        :doc "spools/devflow.md"
                        :purpose "Feature lifecycle (intake -> proposal -> spec-plan -> tasks/implementation) keyed by feature name."}
                       {:namespace "skein.spools.ephemeral"
                        :doc "spools/ephemeral.md"
                        :purpose "Temporary parent-owned strands burned via a userland attribute."}
                       {:namespace "skein.spools.kanban"
                        :doc "spools/kanban.md"
                        :purpose "User-facing kanban board: feature/epic cards with refinement/pending/claimed/in_review lanes, notes, and handovers."}]
              :ops [{:name "kanban" :help "strand help kanban" :manual "strand kanban about"}
                    {:name "branches" :help "strand help branches"}
                    {:name "devflow-start" :help "strand help devflow-start"}
                    {:name "devflow-next" :help "strand help devflow-next"}
                    {:name "devflow-choices" :help "strand help devflow-choices"}
                    {:name "devflow-choose" :help "strand help devflow-choose"}
                    {:name "devflow-complete" :help "strand help devflow-complete"}
                    {:name "devflow-advance" :help "strand help devflow-advance"}
                    {:name "devflow-describe" :help "strand help devflow-describe"}
                    {:name "devflow-history" :help "strand help devflow-history"}
                    {:name "devflow-archive" :help "strand help devflow-archive"}
                    {:name "devflow-status" :help "strand help devflow-status"}
                    {:name "workflow-runs" :help "strand help workflow-runs"}
                    {:name "current-dags" :help "strand help current-dags"}
                    {:name "carder-report" :help "strand help carder-report"}
                    {:name "agent" :help "strand help agent" :manual "strand agent about"}
                    {:name "flow-await" :help "strand help flow-await"}
                    {:name "flow-status" :help "strand help flow-status"}
                    {:name "hitl" :help "strand help hitl" :purpose "Interactive user+agent session with a self-terminating tracking strand."}
                    {:name "land" :help "strand help land" :manual "strand land about"
                     :purpose (format-alpha/reflow
                               "|Coordinator-only landing workflow: push+draft-PR, green CI, roster
                                |sign-off, squash-merge to local main with full verification, then
                                |green main CI. Registered by .skein/workflows.clj.")}]
              :patterns [{:name "agent-plan"
                          :purpose "Create a feature strand plus task/review children for agent work; shipped by skein.spools.agents."}
                         {:name "delegate-pipeline"
                          :purpose "Sequential chain-loop workflow of subagent gates with optional acceptance checkpoint. Registered by .skein/workflows.clj."}]
              :queries [{:name "kanban-cards" :usage "strand list --query kanban-cards"}
                        {:name "kanban-unstarted" :usage "strand ready --query kanban-unstarted"}
                        {:name "feature-active" :usage "strand list --query feature-active --param feature=<feature>"}
                        {:name "feature-work" :usage "strand ready --query feature-work --param feature=<feature>"}
                        {:name "feature-owner-work"
                         :usage "strand ready --query feature-owner-work --param feature=<feature> --param owner=<owner>"}
                        {:name "feature-run" :usage "strand list --query feature-run --param feature=<feature>"}
                        {:name "workflow-runs" :usage "strand list --query workflow-runs"}
                        {:name "devflow-runs" :usage "strand list --query devflow-runs"}
                        {:name "work" :usage "strand ready --query work"}]}
             (op! "devflow-conventions" []))))))

(deftest converted-config-surface-is-byte-identical-to-pre-refactor
  ;; TASK-Srm-009.MI1 acceptance gate. surface_baseline.edn is the config-owned
  ;; op-help + named-query surface captured from the pre-refactor config (base
  ;; ad5d2eb, before the defquery/defop conversion) via capture-config-surface.
  ;; Asserting the current converted config reproduces it byte-for-byte proves the
  ;; refactor changed no generated `help <op>` and no registered query definition;
  ;; the devflow-conventions payload is pinned by the test above. The golden is a
  ;; frozen pre-refactor snapshot (committed, not git-history-dependent, so it
  ;; survives CI's shallow checkout) and also guards the surface against later
  ;; drift.
  (let [golden (edn/read-string (slurp "test/skein/surface_baseline.edn"))
        current (capture-config-surface ".skein/config.clj")]
    (is (= (:queries golden) (:queries current))
        "every named query definition matches the pre-refactor baseline")
    (doseq [op config-op-names]
      (is (= (get-in golden [:op-help op]) (get-in current [:op-help op]))
          (str "generated help for " op " must match the pre-refactor baseline")))))

(deftest named-queries-return-expected-rows-against-seeded-strands
  ;; TASK-Srm-009.MI1: exercise each registered named query's rows against one
  ;; deterministic seed, so a defquery `:where`/`:params` regression surfaces as a
  ;; wrong row set rather than only a definition diff.
  (with-config-runtime
    (fn [rt]
      (doseq [[title attrs] [["A1" {:feature "alpha" :kind "task" :owner "amy"}]
                             ["A2" {:feature "alpha" :kind "review" :owner "bob"}]
                             ["A3" {:feature "alpha" :kind "note"}]
                             ["B1" {:feature "beta" :kind "task" :owner "amy"}]
                             ["R1" {:workflow/run-id "alpha"}]
                             ["M1" {:workflow/role "molecule"}]
                             ["D1" {:workflow/role "molecule" :workflow/family "devflow"}]
                             ["S1" {:shuttle/run "true"}]
                             ["K1" {:kanban/card "true" :kanban/status "refinement"}]]]
        (api/add rt {:title title :state "active" :attributes attrs}))
      (let [rows (fn [query-name params]
                   (set (map :title (api/list rt (get (graph/queries rt) query-name) params))))]
        (is (= #{"A1" "A2" "A3"} (rows "feature-active" {:feature "alpha"})))
        (is (= #{"A1" "A2"} (rows "feature-work" {:feature "alpha"})))
        (is (= #{"A1"} (rows "feature-owner-work" {:feature "alpha" :owner "amy"})))
        (is (= #{"R1"} (rows "feature-run" {:feature "alpha"})))
        (is (= #{"M1" "D1"} (rows "workflow-runs" {})))
        (is (= #{"D1"} (rows "devflow-runs" {})))
        (is (= #{"A1" "A2" "A3" "B1" "R1"} (rows "work" {})))))))

(deftest chime-attention-rules-register-and-fire
  ;; TASK-Srm-009.MI1: through the full startup fixture (which loads attention.clj
  ;; via init.clj), assert the registered chime rule keys and that the registered
  ;; handlers actually fire — resolving each rule's registered fn symbol and
  ;; invoking it, so a defrule handler/registration regression is caught behavior,
  ;; not just key, deep.
  (with-startup-config-runtime
    (fn [_rt]
      (let [rules ((requiring-resolve 'skein.spools.chime/rules))
            by-key (into {} (map (juxt :name identity)) rules)
            fire (fn [rule-key strand]
                   (@(requiring-resolve (:fn (get by-key rule-key)))
                    {:strand strand :ready-ids #{}}))]
        (is (= [:agent-failure :hitl-checkpoint-ready :kanban-blocked :kanban-completed
                :kanban-started :parked-run :treadle-error]
               (mapv :name rules)))
        ;; treadle-error fires on any strand stamped with a treadle error
        (let [note (fire :treadle-error {:id "g1" :state "active" :title "Gate A"
                                         :attributes {:treadle/error "spawn failed"}})]
          (is (= "Treadle error: Gate A" (:title note)))
          (is (str/includes? (:body note) "spawn failed")))
        ;; and stays silent (no false positive) when the condition is absent
        (is (nil? (fire :treadle-error {:id "g2" :state "active" :title "Clean gate"
                                        :attributes {}})))
        ;; agent-failure fires on a failed shuttle run and carries its error
        (let [note (fire :agent-failure {:id "r1" :state "active" :title "Run"
                                         :attributes {:shuttle/phase "failed" :shuttle/error "boom"}})]
          (is (= "Agent run failed: Run" (:title note)))
          (is (str/includes? (:body note) "boom")))))))

(deftest current-dags-op-builds-self-contained-plan-task-projection
  (with-config-runtime
    (fn [rt]
      (let [plan (api/add rt {:title "Feature: plan feature"
                              :state "active"
                              :attributes {:feature "plan-feature" :kind "plan" :workflow "agent-plan"}})
            impl (api/add rt {:title "Implement it"
                              :state "active"
                              :attributes {:feature "plan-feature" :kind "task" :workflow "agent-plan"
                                           :task_key "impl" :owner "agent-a" :harness "build"
                                           :cwd "/tmp/work" :validation ["clojure -M:test"]}})
            review (api/add rt {:title "Review it"
                                :state "active"
                                :attributes {:feature "plan-feature" :kind "review" :workflow "agent-plan"
                                             :task_key "review" :hitl true}})]
        (api/update rt (:id plan) {:edges [{:type "parent-of" :to (:id impl)}
                                           {:type "parent-of" :to (:id review)}]})
        (api/update rt (:id review) {:edges [{:type "depends-on" :to (:id impl)}]})
        (let [strands (api/list rt (var-get (requiring-resolve 'config/feature-active-query))
                                {:feature "plan-feature"})
              children (:edges (graph/subgraph rt [(:id plan)] {:type "parent-of"}))
              ready (api/ready rt (var-get (requiring-resolve 'config/feature-work-query))
                               {:feature "plan-feature"})]
          (is (= 3 (count strands)))
          (is (= #{(:id impl) (:id review)}
                 (set (map :to_strand_id children))))
          ;; review depends on impl, so only impl is ready
          (is (= ["Implement it"] (mapv :title ready)))
          ;; current-dags stays self-contained: a blocker outside the plan DAG
          ;; must not surface as a dangling depends-on edge
          (let [external (api/add rt {:title "External blocker"
                                      :state "active"
                                      :attributes {:kind "task"}})]
            (api/update rt (:id impl)
                        {:edges [{:type "depends-on" :to (:id external)}]})
            (let [dag (->> (:dags (op! "current-dags" []))
                           (filter #(= (:id plan) (get-in % [:root :id])))
                           first)
                  dag-ids (set (map :id (:strands dag)))]
              (is (some? dag))
              (is (every? #(and (contains? dag-ids (:from_strand_id %))
                                (contains? dag-ids (:to_strand_id %)))
                          (concat (:parent_of_edges dag) (:depends_on_edges dag)))))))))))

(deftest branches-op-groups-branch-stamped-work-roots
  (with-config-runtime
    (fn [rt]
      (let [root (api/add rt {:title "Card: feature-x"
                              :state "active"
                              :attributes {:kanban/card "true" :kanban/status "claimed"
                                           :owner "agent-a" :branch "feature-x"
                                           :worktree "/tmp/feature-x"}})
            task (api/add rt {:title "Implement feature-x"
                              :state "active"
                              :attributes {:kind "task"}})
            review (api/add rt {:title "Review feature-x"
                                :state "active"
                                :attributes {:kind "review"}})]
        (api/update rt (:id root) {:edges [{:type "parent-of" :to (:id task)}
                                           {:type "parent-of" :to (:id review)}]})
        (api/update rt (:id review) {:edges [{:type "depends-on" :to (:id task)}]})
        ;; a branch-stamped child (task assigned owner+branch) must not
        ;; surface as a second work root for the branch
        (api/update rt (:id task) {:attributes {:kind "task" :branch "feature-x" :owner "agent-b"}})
        (let [result (op! "branches" [])
              branch (first (:branches result))
              view (first (:roots branch))]
          (is (= ["feature-x"] (mapv :branch (:branches result))))
          (is (= [(:id root)] (mapv #(get-in % [:root :id]) (:roots branch))))
          (is (= #{(:id task) (:id review)}
                 (set (map :id (:active_descendants view)))))
          ;; review depends on the task, so the frontier is root + task
          (is (= #{(:id root) (:id task)} (set (map :id (:ready view))))))
        (is (= 1 (count (:branches (op! "branches" ["feature-x"])))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no active work root"
                              (op! "branches" ["missing-branch"])))))))

(deftest delegate-pipeline-weave-creates-chain-loop-gates
  (with-config-runtime
    (fn [rt]
      (patterns/weave! rt :delegate-pipeline
                       {:run_id "pipe-test"
                        :harness "pi-main"
                        :accept true
                        :tasks [{:id "a" :title "Do A" :body "A body"}
                                {:id "b" :title "Do B"}]})
      (let [strands (api/list rt)
            by-task (into {} (keep (fn [s]
                                     (when-let [task (or (get-in s [:attributes :delegate-pipeline/task])
                                                         (get-in s [:attributes "delegate-pipeline/task"]))]
                                       [task s])) strands))
            attr (fn [s k]
                   (or (get-in s [:attributes k])
                       (get-in s [:attributes (name k)])))]
        (is (= #{"a" "b"} (set (keys by-task))))
        (is (str/includes? (attr (by-task "a") :shuttle/prompt)
                           "[worker contract]"))
        (is (= "pi-main" (attr (by-task "b") :shuttle/harness))))))
  (testing "acceptance checkpoint is optional and task max-attempts pass through"
    (with-config-runtime
      (fn [rt]
        (patterns/weave! rt :delegate-pipeline
                         {:run_id "pipe-no-accept"
                          :tasks [{:id "a" :title "Do A" :harness "pi-main" :max-attempts 4}]})
        (let [strands (api/list rt)
              task (first (filter #(= "a" (or (get-in % [:attributes :delegate-pipeline/task])
                                              (get-in % [:attributes "delegate-pipeline/task"])))
                                  strands))]
          (is (some? task))
          (is (= 4 (or (get-in task [:attributes :shuttle/max-attempts])
                       (get-in task [:attributes "shuttle/max-attempts"]))))
          (is (not-any? #(= "checkpoint" (or (get-in % [:attributes :workflow/role])
                                             (get-in % [:attributes "workflow/role"])))
                        strands)))))))

(deftest devflow-ops-drive-intake-into-proposal
  (with-config-runtime
    (fn [_rt]
      (let [started (op! "devflow-start" ["ops-feature" "already-in-worktree-ok"])]
        (is (= "devflow-start" (:operation started)))
        (is (= "intake" (:stage (first (:ready started)))))
        (is (= "create-or-confirm-worktree" (:checkpoint (first (:ready started))))))
      (is (contains? (:choices (op! "devflow-choices" ["ops-feature"])) "abort"))
      (let [after-worktree (op! "devflow-choose" ["ops-feature" "already-in-worktree"])]
        (is (= "brief" (:artifact (first (:ready after-worktree))))))
      (let [after-brief (op! "devflow-complete" ["ops-feature" "brief captured"])]
        (is (= "discuss-scope" (:checkpoint (first (:ready after-brief))))))
      (let [in-proposal (op! "devflow-choose" ["ops-feature" "proposal-ready"])]
        (is (= "proposal" (:stage (first (:ready in-proposal)))))
        (is (= "devflow.proposal.orient" (:action-ref (first (:ready in-proposal))))))
      (let [status (op! "devflow-status" ["ops-feature"])]
        (is (false? (:done status)))
        (is (= "proposal" (get-in (first (:roots status)) [:attributes :devflow/stage])))
        (is (= [{:attributes {:devflow/stage "proposal"}}]
               (->> (:runs (op! "workflow-runs" ["devflow"]))
                    (mapv (fn [run] {:attributes (select-keys (:attributes run) [:devflow/stage])})))))))))

(deftest devflow-advance-describe-history-and-archive-ops
  (with-config-runtime
    (fn [_rt]
      (let [description (op! "devflow-describe" ["proposal"])]
        (is (= "devflow-describe" (:operation description)))
        (is (= "proposal" (:stage description)))
        (is (= "Devflow proposal: <feature>" (get-in description [:description :name]))))
      (op! "devflow-start" ["archive-feature" "already-in-worktree-ok"])
      (let [aborted (op! "devflow-advance" ["archive-feature" "abort" "{\"reason\":\"not now\"}"])]
        (is (= "devflow-advance" (:operation aborted)))
        (is (= "abort" (:stage (first (:ready aborted)))))
        (is (= "devflow.abort.record" (:action-ref (first (:ready aborted))))))
      (let [done (op! "devflow-advance" ["archive-feature" "recorded abort"])]
        (is (true? (:done done)))
        (is (empty? (:ready done))))
      (let [history (op! "devflow-history" ["archive-feature"])]
        (is (= "devflow-history" (:operation history)))
        (is (seq (:history history))))
      (let [archived (op! "devflow-archive" ["archive-feature"])]
        (is (= "devflow-archive" (:operation archived)))
        (is (= "digest" (get-in archived [:digest :attributes :workflow/role])))))))

(deftest work-query-excludes-workflow-plumbing-but-keeps-steps
  (with-config-runtime
    (fn [rt]
      (doseq [[title role] [["Root" "molecule"]
                            ["Procedure" "procedure"]
                            ["Digest" "digest"]
                            ["Step" "step"]
                            ["Checkpoint" "checkpoint"]
                            ["Run record" nil]
                            ["Plain task" nil]
                            ["Pending card" nil]
                            ["Refinement card" nil]]]
        (api/add rt {:title title
                     :state "active"
                     :attributes (cond-> {:feature "work-query"}
                                   role (assoc :workflow/role role)
                                   (= title "Run record") (assoc :shuttle/run "true")
                                   (= title "Pending card") (assoc :kanban/card "true"
                                                                   :kanban/status "pending")
                                   (= title "Refinement card") (assoc :kanban/card "true"
                                                                      :kanban/status "refinement"))}))
      (is (= #{"Step" "Checkpoint" "Plain task" "Pending card"}
             (set (map :title (api/list rt (var-get (requiring-resolve 'config/work-query)) {})))))
      (is (= #{"Step" "Checkpoint" "Plain task" "Pending card"}
             (set (map :title (api/ready rt (var-get (requiring-resolve 'config/work-query)) {}))))))))

(deftest reviewers-file-registers-declarative-roster
  ;; exercises the same load path init.clj's :file+:call reviewers module runs
  (with-config-runtime
    (fn [_rt]
      (load-file ".skein/reviewers.clj")
      ((requiring-resolve 'reviewers/install!))
      (let [rosters ((requiring-resolve 'skein.spools.agents/rosters))
            roster (first (filter #(= :change-review (:name %)) rosters))
            complex-roster (first (filter #(= :complex-patch-review (:name %)) rosters))
            docs-roster (first (filter #(= :docs-review (:name %)) rosters))]
        (is (= [:change-review :complex-patch-review :docs-review] (mapv :name rosters)))
        (let [sleeps (first (filter #(= "test-sleeps" (:name %)) (:reviewers roster)))]
          (is (some? sleeps) "owner-required test-sleeps reviewer is declared")
          (is (str/includes? (:contract sleeps) "time itself is a genuine component")))
        (is (= :review-gpt (get-in roster [:synthesizer :harness]))
            "sign-off synthesis stays on the cross-vendor GPT seat")
        (is (= :hard-gpt (get-in complex-roster [:synthesizer :harness]))
            "complex patch review is synthesized outside its reviewer seats")
        (let [fact-check (first (filter #(= "docs-fact-check" (:name %)) (:reviewers docs-roster)))]
          (is (some? fact-check) "docs roster leads with the accuracy seat")
          (is (str/includes? (:contract fact-check) "NEVER the canonical .skein")))
        (is (= :review-gpt (get-in docs-roster [:synthesizer :harness]))
            "docs sign-off synthesis stays on the cross-vendor GPT seat")))))

(deftest codex-harness-persists-sessions-and-declares-resume
  ;; PLAN-Pnl-001.A2/PH2: the repo :codex harness drops --ephemeral (sessions
  ;; persist) and declares the verified `codex exec resume <session-id>` splice.
  (with-config-runtime
    (fn [_rt]
      (let [codex ((requiring-resolve 'skein.spools.shuttle/resolve-harness) :codex)]
        (is (not-any? #{"--ephemeral"} (:argv codex))
            "sessions persist so codex exec resume can continue them")
        (is (= ["resume" :shuttle/session-id] (:resume codex))
            "codex declares its verified resume subcommand splice")))))

(deftest devflow-ops-fail-loudly-on-bad-input
  (with-config-runtime
    (fn [_rt]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required argument feature"
                            (op! "devflow-start" [])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"worktree-check"
                            (op! "devflow-start" ["bad-feature" "nope"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"JSON input must be an object"
                            (op! "devflow-choose" ["any-feature" "abort" "[1,2]"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                            (op! "devflow-status" ["never-started"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at most one step"
                            (op! "devflow-complete" ["any-feature" "step=a" "step=b"])))
      (let [started (op! "devflow-start" ["checkpoint-feature"])
            checkpoint-id (:id (first (:ready started)))]
        ;; the step=<id> selector resolves independently of other optional args
        (is (contains? (:choices (op! "devflow-choices"
                                      ["checkpoint-feature" (str "step=" checkpoint-id)]))
                       "abort"))
        ;; the engine's checkpoint guard surfaces through the op wrapper
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot complete a checkpoint"
                              (op! "devflow-complete" ["checkpoint-feature"])))))))

(deftest devflow-ops-render-arg-spec-help
  ;; the discovery convention requires generated help from arg-spec data, not
  ;; hand-written usage strings: every devflow wrapper op must render its
  ;; positional contract through the built-in help projection, so
  ;; devflow-conventions' `strand help devflow-*` pointers resolve to real help.
  (with-config-runtime
    (fn [_rt]
      (letfn [(positionals [op-name]
                (->> (get-in (op! "help" [op-name]) [:arg-spec :positionals])
                     (mapv (juxt :name :required :variadic))))]
        (is (= [["feature" true false] ["worktree-check" false false]]
               (positionals "devflow-start")))
        (is (= [["feature" true false]] (positionals "devflow-next")))
        (is (= [["feature" true false] ["step-selector" false true]]
               (positionals "devflow-choices")))
        (is (= [["feature" true false] ["choice" true false] ["tail" false true]]
               (positionals "devflow-choose")))
        (is (= [["feature" true false] ["tail" false true]]
               (positionals "devflow-complete")))
        (is (= [["feature" true false] ["tail" false true]]
               (positionals "devflow-advance")))
        (is (= [["stage-key" false false]] (positionals "devflow-describe")))
        (is (= [["feature" true false]] (positionals "devflow-history")))
        (is (= [["feature" true false]] (positionals "devflow-archive")))
        (is (= [["feature" true false]] (positionals "devflow-status")))
        (is (= "Start the devflow lifecycle for a feature."
               (get-in (op! "help" ["devflow-start"]) [:arg-spec :doc])))))))

(deftest land-ops-drive-a-poured-run-end-to-end
  (with-config-runtime
    (fn [_rt]
      (let [started (op! "land" ["start" "land-x" "--branch" "land-x" "--worktree" "/tmp/land-x"])]
        (is (= "land-start" (:operation started)))
        (is (false? (:done started)))
        (is (= "land.pr.open" (:action-ref (first (:ready started))))))
      ;; drive the linear steps up to the sign-off checkpoint
      (is (= "land.ci.green"
             (:action-ref (first (:ready (op! "land" ["complete" "land-x" "pushed; PR #1"]))))))
      (is (= "land.signoff.review"
             (:action-ref (first (:ready (op! "land" ["complete" "land-x" "HEAD green"]))))))
      (let [at-checkpoint (op! "land" ["complete" "land-x" "roster passed"])]
        (is (= "checkpoint" (:kind (first (:ready at-checkpoint)))))
        (is (= "signoff" (:checkpoint (first (:ready at-checkpoint))))))
      ;; the sign-off checkpoint offers approved + abort; abort requires a reason
      (let [choices (workflow/choice-details "land-x")
            abort-input (get-in choices ["abort" "input"])]
        (is (= #{"approved" "abort"} (set (keys choices))))
        (is (= "reason" (get (first abort-input) "key")))
        (is (true? (get (first abort-input) "required"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required keys"
                            (op! "land" ["choose" "land-x" "abort"])))
      ;; approved is terminal-in-molecule: it continues to the local merge/verify
      (let [approved (op! "land" ["choose" "land-x" "approved"])]
        (is (= "land-choose" (:operation approved)))
        (is (= "land.merge.local-verify" (:action-ref (first (:ready approved)))))
        (is (= "merge-lock" (get-in (op! "land" ["status" "land-x"]) [:merge-lock :attributes :kind]))))
      (op! "land" ["start" "land-z" "--branch" "land-z" "--worktree" "/tmp/land-z"])
      (op! "land" ["complete" "land-z"])
      (op! "land" ["complete" "land-z"])
      (op! "land" ["complete" "land-z"])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"another land run holds the merge lock"
                            (op! "land" ["choose" "land-z" "approved"])))
      (is (= "land.main.ci-green"
             (:action-ref (first (:ready (op! "land" ["complete" "land-x" "merged; gates green"]))))))
      (let [ready-cleanup (op! "land" ["complete" "land-x" "main pushed"])
            cleanup-step (first (:ready ready-cleanup))]
        (is (= "land.cleanup" (:action-ref cleanup-step)))
        ;; cardless run: the cleanup instruction must omit kanban-finish
        ;; entirely rather than render a literal "<card>" placeholder
        (is (not (str/includes? (:instruction cleanup-step) "kanban finish")))
        (is (not (str/includes? (:instruction cleanup-step) "<card>"))))
      (let [done (op! "land" ["complete" "land-x" "cleaned up"])]
        (is (true? (:done done)))
        (is (empty? (:ready done))))
      (let [status (op! "land" ["status" "land-x"])]
        (is (= "land-status" (:operation status)))
        (is (true? (:done status)))
        (is (empty? (:ready status)))
        (is (nil? (:merge-lock status)))
        (is (seq (:history status)))))))

(deftest land-signoff-abort-routes-to-record-step
  (with-config-runtime
    (fn [rt]
      (let [card-id (:id (api/add rt {:title "Abort card"
                                      :attributes {:kanban/card "true"
                                                   :kanban/status "claimed"
                                                   :kanban/type "feature"}}))]
        (op! "land" ["start" "land-y" "--branch" "land-y" "--worktree" "/tmp/land-y" "--card" card-id]))
      (op! "land" ["complete" "land-y"])           ; push-draft-pr
      (op! "land" ["complete" "land-y"])           ; ci-green
      (let [root (workflow/current-root "land-y")
            context (get-in root [:attributes :workflow/context])
            card-id (or (:card context) (get context "card"))]
        (is (= "in_review" (get-in (api/show rt card-id) [:attributes :kanban/status]))))
      (op! "land" ["complete" "land-y"])           ; signoff-review
      (let [aborted (op! "land" ["choose" "land-y" "abort" "{\"reason\":\"scope changed\"}"])]
        (is (= "land-choose" (:operation aborted)))
        ;; routing is a hard cutover to the reason-recording continuation
        (is (= "land.abort.record" (:action-ref (first (:ready aborted))))))
      (let [root (workflow/current-root "land-y")
            context (get-in root [:attributes :workflow/context])
            card-id (or (:card context) (get context "card"))]
        (is (= "claimed" (get-in (api/show rt card-id) [:attributes :kanban/status]))))
      (let [done (op! "land" ["complete" "land-y" "abort recorded"])]
        (is (true? (:done done)))
        (is (empty? (:ready done)))))))

(deftest land-cleanup-instruction-interpolates-the-real-card-id
  (with-config-runtime
    (fn [rt]
      (let [card-id (:id (api/add rt {:title "Cleanup card"
                                      :attributes {:kanban/card "true"
                                                   :kanban/status "claimed"
                                                   :kanban/type "feature"}}))]
        (op! "land" ["start" "land-w" "--branch" "land-w" "--worktree" "/tmp/land-w" "--card" card-id])
        (op! "land" ["complete" "land-w"])                          ; push-draft-pr
        (op! "land" ["complete" "land-w"])                          ; ci-green
        (op! "land" ["complete" "land-w"])                          ; signoff-review
        (op! "land" ["choose" "land-w" "approved"])
        (op! "land" ["complete" "land-w"])                          ; merge-local-verify
        (let [ready-cleanup (op! "land" ["complete" "land-w"])      ; push-main-ci-green
              cleanup-step (first (:ready ready-cleanup))]
          (is (= "land.cleanup" (:action-ref cleanup-step)))
          (is (str/includes? (:instruction cleanup-step)
                             (str "strand kanban finish " card-id " --outcome done")))
          (is (not (str/includes? (:instruction cleanup-step) "<card>"))))))))

(deftest land-break-lock-closes-active-sentinel-with-reason
  (with-config-runtime
    (fn [_rt]
      (op! "land" ["start" "land-lock-x" "--branch" "land-lock-x" "--worktree" "/tmp/land-lock-x"])
      (op! "land" ["complete" "land-lock-x"])
      (op! "land" ["complete" "land-lock-x"])
      (op! "land" ["complete" "land-lock-x"])
      (op! "land" ["choose" "land-lock-x" "approved"])
      (is (= "merge-lock" (get-in (op! "land" ["status" "land-lock-x"])
                                  [:merge-lock :attributes :kind])))
      ;; a blank reason fails at the handler; a missing reason fails at the arg-spec
      ;; parse layer — both are loud rejections rather than a silent break
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reason must be a non-blank string"
                            (op! "land" ["break-lock" ""])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required argument tail"
                            (op! "land" ["break-lock"])))
      (let [broken (op! "land" ["break-lock" "coordinator confirmed stale lock"])]
        (is (= "land-break-lock" (:operation broken)))
        (is (= "closed" (get-in broken [:broken :state])))
        (is (= "coordinator confirmed stale lock"
               (get-in broken [:broken :attributes :land/broken-reason])))
        (is (nil? (:merge-lock (op! "land" ["status" "land-lock-x"]))))))))

(deftest land-break-lock-refuses-to-break-when-multiple-locks-are-active
  (with-config-runtime
    (fn [rt]
      ;; a healthy world holds one lock; two active merge-lock strands is a
      ;; corrupt state break-lock must refuse rather than pick one arbitrarily.
      (api/add rt {:title "Merge lock: land-dup-a"
                   :attributes {:kind "merge-lock" :land/run-id "land-dup-a"}})
      (api/add rt {:title "Merge lock: land-dup-b"
                   :attributes {:kind "merge-lock" :land/run-id "land-dup-b"}})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"multiple active merge locks found"
                            (op! "land" ["break-lock" "trying to clear a corrupt state"]))))))

(deftest land-start-fails-loudly-on-a-blank-card
  (with-config-runtime
    (fn [_rt]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"card must be a non-blank string"
                            (op! "land" ["start" "land-blank-card"
                                         "--branch" "land-blank-card"
                                         "--worktree" "/tmp/land-blank-card"
                                         "--card" ""])))
      ;; absent --card stays legal: not every land run has a card
      (is (= "land-start"
             (:operation (op! "land" ["start" "land-no-card"
                                      "--branch" "land-no-card"
                                      "--worktree" "/tmp/land-no-card"])))))))

(deftest land-op-renders-arg-spec-subcommand-help-and-fails-loudly
  (with-config-runtime
    (fn [_rt]
      (let [help (op! "help" ["land"])
            subs (get-in help [:arg-spec :subcommands])
            by-name (into {} (map (juxt :name identity)) subs)]
        (is (= #{"about" "start" "next" "complete" "choose" "status" "break-lock"}
               (set (map :name subs))))
        (is (str/starts-with? (get-in help [:arg-spec :doc])
                              "Drive the coordinator landing workflow"))
        ;; flags render sorted by key: branch, card, worktree
        (is (= [["branch" true] ["card" false] ["worktree" true]]
               (mapv (juxt :name :required) (get-in by-name ["start" :flags]))))
        (is (= [["feature" true false]]
               (mapv (juxt :name :required :variadic) (get-in by-name ["start" :positionals]))))
        (is (= [["feature" true false] ["choice" true false] ["tail" false true]]
               (mapv (juxt :name :required :variadic) (get-in by-name ["choose" :positionals])))))
      ;; required flags and positionals fail loudly at parse
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --branch"
                            (op! "land" ["start" "no-flags"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required argument feature"
                            (op! "land" ["start" "--branch" "b" "--worktree" "w"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                            (op! "land" ["status" "never-landed"]))))))

(deftest flow-status-op-joins-history-frontier-gates-runs-and-stalls
  (with-config-runtime
    (fn [rt]
      (let [definition (workflow/workflow
                        "Flow status test"
                        (workflow/gate :a "Delegate A" :subagent)
                        (workflow/gate :b "Delegate B" :subagent :depends-on [:a])
                        (workflow/checkpoint :accept "Accept" :depends-on [:b]
                                             :choices [:accepted]))]
        (workflow/start! "flow-status-test" definition {})
        (let [gate-a (:id (first (workflow/next-steps "flow-status-test")))
              run-a (api/add rt {:title "Run A"
                                 :state "closed"
                                 :attributes {:shuttle/run "true"
                                              :shuttle/phase "done"
                                              :shuttle/result "A complete"
                                              :treadle/gate gate-a
                                              :treadle/run-id "flow-status-test"}})]
          (api/update rt gate-a {:attributes {:treadle/run (:id run-a)}})
          (workflow/complete! "flow-status-test" {:step gate-a :by (:id run-a)})
          (let [gate-b (:id (first (workflow/next-steps "flow-status-test")))
                run-b (api/add rt {:title "Run B"
                                   :state "active"
                                   :attributes {:shuttle/run "true"
                                                :shuttle/phase "failed"
                                                :shuttle/error "boom"
                                                :treadle/gate gate-b
                                                :treadle/run-id "flow-status-test"}})]
            (api/update rt gate-b {:attributes {:treadle/run (:id run-b)}})
            ;; failure summaries are scoped to the requested run: an unrelated
            ;; failed run and an unrelated error-stamped gate must not leak in
            (api/add rt {:title "Unrelated failed run"
                         :attributes {:shuttle/run "true"
                                      :shuttle/phase "failed"
                                      :shuttle/error "other workflow"}})
            (api/add rt {:title "Unrelated stalled gate"
                         :attributes {:workflow/gate "subagent"
                                      :treadle/error "spawn failed elsewhere"}})
            (let [status (op! "flow-status" ["flow-status-test"])
                  by-title (into {} (map (juxt :title identity)) (:gates status))]
              (is (= "flow-status" (:operation status)))
              (is (false? (:done status)))
              (is (= ["Delegate B"] (mapv :title (:frontier status))))
              (is (= [:gate-closed] (mapv :type (get-in status [:history 0 :events]))))
              (is (= "done" (get-in by-title ["Delegate A" :run :shuttle/phase])))
              (is (= "failed" (get-in by-title ["Delegate B" :run :shuttle/phase])))
              (is (true? (get-in by-title ["Delegate B" :stalled?])))
              (is (= #{(:id run-b)} (set (map :id (:agent-failures status)))))
              (is (empty? (:stalled-gates status)))
              (is (str/includes? (:dev/mermaid status) "Delegate B (stalled)")))))))))

(defn- assert-treadle-installed-after-config
  "Assert treadle loaded and declares :config in :after — its install! runs an
  initial gate scan, so config.clj's harness aliases must already exist or a
  durable ready gate would be stamped treadle/error on every cold start."
  [rt]
  (let [use (get (runtime-alpha/uses rt) :skein/spools-treadle)]
    (is (= :loaded (:status use)))
    (is (some #{:config} (get-in use [:opts :after])))))

(deftest repo-local-startup-and-reload-preserve-registrations
  (with-startup-config-runtime
    (fn [rt]
      (assert-config-registrations rt)
      (assert-treadle-installed-after-config rt)
      (op! "devflow-start" ["startup-feature" "already-in-worktree-ok"])
      (is (= :loaded (:status (runtime-alpha/reload! rt))))
      (assert-config-registrations rt)
      ;; runtime registries reload; the strand graph and run state persist
      (let [status (op! "devflow-status" ["startup-feature"])]
        (is (false? (:done status)))
        (is (= "create-or-confirm-worktree" (:checkpoint (first (:ready status)))))))))
