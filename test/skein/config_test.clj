(ns skein.config-test
  "Tests for the repo-local .skein config modules (config.clj plus the
  harnesses.clj, workflows.clj, and analytics.clj siblings): registration
  surface, the delegate-pipeline weave pattern, the land workflow, the
  devflow op wrappers over skein.spools.devflow, and the feature-costs
  usage rollup."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.core.db-test :as db-test]
            [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.graph.alpha :as graph]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.spools.devflow :as devflow]
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
  (weaver-config/world config-dir
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
    (let [rt (weaver-runtime/start! db-file {:world (test-world config-dir)})]
      (try
        ;; harnesses.clj's worker alias layers over the shipped :pi harness,
        ;; which shuttle/install! registers in real startups; this fixture
        ;; loads the config files alone, so register the defaults here.
        ((requiring-resolve 'skein.spools.agent-run/register-default-harnesses!))
        (load-file ".skein/config.clj")
        (load-file ".skein/harnesses.clj")
        (load-file ".skein/workflows.clj")
        (load-file ".skein/analytics.clj")
        ((requiring-resolve 'config/install!))
        ((requiring-resolve 'harnesses/install!))
        ((requiring-resolve 'workflows/install!))
        ((requiring-resolve 'analytics/install!))
        (f rt)
        (finally
          (weaver-runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- copy-config-dir!
  "Copy the repo-local config files into a temporary config dir."
  [target]
  (.mkdirs (io/file target))
  (doseq [name ["init.clj" "config.clj" "workflows.clj" "harnesses.clj"
                "attention.clj" "nvd_scan.clj" "reviewers.clj" "analytics.clj"
                "kanban_tracker.clj" "spools.edn"]]
    (io/copy (io/file ".skein" name) (io/file target name)))
  ;; The shipped spools.edn approves local roots relative to the config dir,
  ;; which does not resolve from a copy. Rewrite it to the repo's canonical
  ;; spool roots so the whole test JVM syncs one root per lib
  ;; (tools.deps add-libs state is JVM-global; see skein.test-runner's
  ;; ordering note).
  (spit (io/file target "spools.edn")
        (pr-str {:spools {'skein.spools/agent-run
                          {:local/root (.getCanonicalPath (io/file "spools/agent-run"))}
                          'skein.spools/workflow
                          {:local/root (.getCanonicalPath (io/file "spools/workflow"))}
                          'skein.spools/ephemeral
                          {:local/root (.getCanonicalPath (io/file "spools/ephemeral"))}
                          'skein.spools/roster
                          {:local/root (.getCanonicalPath (io/file "spools/roster"))}
                          'skein.spools/loom
                          {:local/root (.getCanonicalPath (io/file "spools/loom"))}
                          'skein.spools/carder
                          {:local/root (.getCanonicalPath (io/file "spools/carder"))}
                          'skein.spools/text-search
                          {:local/root (.getCanonicalPath (io/file "spools/text-search"))}
                          'skein.spools/delegation
                          {:local/root (.getCanonicalPath (io/file "spools/delegation"))}
                          'skein.spools/chime
                          {:local/root (.getCanonicalPath (io/file "spools/chime"))}
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
                          {:local/root (.getCanonicalPath (test-alpha/spool-checkout-root "skein/spools/devflow.clj"))}
                          'codethread/kanban
                          {:local/root (.getCanonicalPath (test-alpha/spool-checkout-root "skein/spools/kanban.clj"))}}}))
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
    (let [rt (weaver-runtime/start! db-file {:world (test-world config-dir)})]
      (try
        (f rt)
        (finally
          (weaver-runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- op!
  "Invoke a repo-local registered op by name with a CLI-shaped argv."
  [op-name argv]
  (weaver/op! (current/runtime) (symbol op-name) argv))

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
    (let [rt (weaver-runtime/start! db-file {:world (test-world config-dir)})]
      (try
        (load-file config-path)
        ((requiring-resolve 'config/install!))
        {:op-help (into {} (map (fn [op] [op (op! "help" [op])])) config-op-names)
         :queries (into {} (map (fn [q] [q (get (graph/queries rt) q)])) named-query-names)}
        (finally
          (weaver-runtime/stop! rt)
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
    (is (some #(= op-name (:name %)) (weaver/ops rt))))
  (is (some #(= "delegate-pipeline" (:name %)) (patterns/patterns rt)))
  ;; agent-plan is spool-owned now; a real startup wires the agents spool in
  ;; via init.clj, so it must still be registered end to end
  (is (some #(= "agent-plan" (:name %)) (patterns/patterns rt)))
  ;; agent review must consume the one authoritative policy text by default;
  ;; the text ships from skein.spools.delegation, the accessor stays on agent-run
  (is (= (var-get (requiring-resolve 'skein.spools.delegation/review-contract))
         ((requiring-resolve 'skein.spools.agent-run/default-review-contract-text))))
  ;; the repo owns chime's attention rules; the chime engine ships none
  (is (= [:agent-failure :gate-error :hitl-checkpoint-ready :kanban-blocked :kanban-completed
          :kanban-started :parked-run]
         (mapv :name ((requiring-resolve 'skein.spools.chime/rules)))))
  ;; the declarative reviewer rosters register from .skein/reviewers.clj
  (let [rosters ((requiring-resolve 'skein.spools.delegation/rosters))]
    (is (= [:change-review :complex-patch-review :docs-review] (mapv :name rosters)))
    (is (some #(= "test-sleeps" (:name %)) (:reviewers (first rosters))))))

(def ^:private external-spool-coordinate-pairs
  "Pairs of weaver-side spools.edn keys and test-JVM deps.edn coordinates."
  [{:spools-key 'codethread/devflow
    :deps-key 'io.github.codethread/devflow.spool}
   {:spools-key 'codethread/kanban
    :deps-key 'io.github.codethread/kanban.spool}])

(deftest external-spool-coordinates-are-synced-across-spools-edn-and-deps-edn
  ;; The weaver resolves external spool coordinates from .skein/spools.edn while
  ;; config_test loads .skein/config.clj in-process through deps.edn's :test
  ;; :extra-deps. Each external spool must name the same checkout in both files,
  ;; otherwise the test JVM and the weaver can run different spool revisions.
  (let [spools-edn (edn/read-string (slurp ".skein/spools.edn"))
        deps-edn (edn/read-string (slurp "deps.edn"))
        spools (get spools-edn :spools)
        declared-spools-keys (set (map :spools-key external-spool-coordinate-pairs))
        external-spools-keys (set (keep (fn [[spools-key entry]]
                                          (when (:git/url entry)
                                            spools-key))
                                        spools))
        missing-pairs (sort (remove declared-spools-keys external-spools-keys))
        extra-pairs (sort (remove external-spools-keys declared-spools-keys))]
    (is (= external-spools-keys declared-spools-keys)
        (str ".skein/spools.edn :git/url external spools must be declared in "
             "external-spool-coordinate-pairs; missing " (pr-str missing-pairs)
             ", extra " (pr-str extra-pairs)))
    (doseq [{:keys [spools-key deps-key]} external-spool-coordinate-pairs]
      (testing (str spools-key " <-> " deps-key)
        (let [spools-entry (get-in spools-edn [:spools spools-key])
              deps-entry (get-in deps-edn [:aliases :test :extra-deps deps-key])]
          (is (map? spools-entry)
              (str spools-key " missing from .skein/spools.edn for declared pair "
                   spools-key " <-> " deps-key))
          (is (map? deps-entry)
              (str deps-key " missing from deps.edn :test :extra-deps for declared pair "
                   spools-key " <-> " deps-key))
          (cond
            (and (:git/sha spools-entry) (:git/sha deps-entry))
            (is (= (:git/sha spools-entry) (:git/sha deps-entry))
                (str spools-key " :git/sha in .skein/spools.edn (" (:git/sha spools-entry)
                     ") must match " deps-key " :git/sha in deps.edn :test :extra-deps ("
                     (:git/sha deps-entry) ")"))

            (and (:local/root spools-entry) (:local/root deps-entry))
            (let [spools-root (.getCanonicalFile (io/file ".skein" (:local/root spools-entry)))
                  deps-root (.getCanonicalFile (io/file "." (:local/root deps-entry)))]
              (is (= spools-root deps-root)
                  (str spools-key " :local/root in .skein/spools.edn (" spools-root
                       ") must resolve to the same checkout as " deps-key
                       " :local/root in deps.edn :test :extra-deps (" deps-root ")")))

            :else
            ;; a failing assertion rather than a throw so one bad pair still
            ;; lets the remaining declared pairs be checked in the same run
            (is false
                (str spools-key " <-> " deps-key
                     " coordinates in .skein/spools.edn and deps.edn must both be "
                     ":local/root or both be :git/sha; got " (pr-str spools-entry)
                     " and " (pr-str deps-entry)))))))))

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
                        :purpose "User-facing kanban board: feature/epic cards with refinement/pending/claimed/in_review lanes."}]
              :ops [{:name "kanban" :help "strand help kanban" :manual "strand kanban about"}
                    {:name "kanban-export" :help "strand help kanban-export"}
                    {:name "kanban-tree" :help "strand help kanban-tree"
                     :purpose "Epic -> feature -> task kanban hierarchy with derived task status, in one projection for renderers."}
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
                    {:name "feature-costs" :help "strand help feature-costs"
                     :purpose "Agent-run cost/usage rollup beneath a work root, as pure data. Registered by .skein/analytics.clj."}
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
                          :purpose "Create a feature strand plus task/review children for agent work; shipped by skein.spools.delegation."}
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
                             ["S1" {:agent-run/run "true"}]
                             ["K1" {:kanban/card "true" :kanban/status "refinement"}]]]
        (weaver/add rt {:title title :state "active" :attributes attrs}))
      (let [rows (fn [query-name params]
                   (set (map :title (weaver/list rt (get (graph/queries rt) query-name) params))))]
        (is (= #{"A1" "A2" "A3"} (rows "feature-active" {:feature "alpha"})))
        (is (= #{"A1" "A2"} (rows "feature-work" {:feature "alpha"})))
        (is (= #{"A1"} (rows "feature-owner-work" {:feature "alpha" :owner "amy"})))
        (is (= #{"R1"} (rows "feature-run" {:feature "alpha"})))
        (is (= #{"M1" "D1"} (rows "workflow-runs" {})))
        (is (= #{"D1"} (rows "devflow-runs" {})))
        (is (= #{"A1" "A2" "A3" "B1" "R1"} (rows "work" {})))))))

(deftest feature-costs-rolls-up-agent-run-usage-beneath-a-root
  ;; analytics.clj contract: pure-data rollup of the agent-run usage stamps in
  ;; a work root's parent-of subtree — rows ordered by start time, totals with
  ;; wall-clock bounds, per-harness aggregates sorted by cost, and explicit
  ;; missing-usage ids for runs whose harness recorded nothing.
  (with-config-runtime
    (fn [rt]
      (let [card (weaver/add rt {:title "Feature card" :state "active"
                                 :attributes {:kanban/card "true"}})
            run-a (weaver/add rt {:title "Delegate: implement"
                                  :state "closed"
                               ;; values stamped as typed JSON, the shape the
                               ;; shuttle spool writes; the corrupt-usage test
                               ;; covers the string form
                                  :attributes {:agent-run/run "true"
                                               :agent-run/harness "build"
                                               :agent-run/cost-usd 1.25
                                               :agent-run/tokens-total 1000
                                               :agent-run/tokens {"input" 800 "output" 200}
                                               :agent-run/usage-source "session"
                                               :agent-run/exit-code 0
                                               :agent-run/started-at "2026-07-10T10:00:00Z"
                                               :agent-run/finished-at "2026-07-10T10:05:00Z"}})
            run-b (weaver/add rt {:title "Review: skeptic"
                                  :state "closed"
                                  :attributes {:agent-run/run "true"
                                               :agent-run/harness "hard-gpt"
                                               :agent-run/started-at "2026-07-10T10:06:00Z"
                                               :agent-run/finished-at "2026-07-10T10:08:30Z"}})
            note (weaver/add rt {:title "Not a run" :state "closed"
                                 :attributes {:kind "note"}})]
        (weaver/update rt (:id card) {:edges [{:type "parent-of" :to (:id run-a)}
                                              {:type "parent-of" :to (:id run-b)}
                                              {:type "parent-of" :to (:id note)}]})
        (let [result (op! "feature-costs" [(:id card)])]
          (is (= "feature-costs" (:operation result)))
          (is (= {:id (:id card) :title "Feature card" :state "active"}
                 (:root result)))
          (is (= [(:id run-a) (:id run-b)] (mapv :id (:runs result)))
              "rows are ordered by start time and exclude non-run strands")
          (is (= {:runs 2 :runs-with-usage 1 :cost-usd 1.25 :tokens-total 1000
                  :wall-clock {:started-at "2026-07-10T10:00:00Z"
                               :finished-at "2026-07-10T10:08:30Z"
                               :duration-secs 510.0}}
                 (:totals result)))
          (is (= [{:harness "build" :runs 1 :runs-with-usage 1
                   :cost-usd 1.25 :tokens-total 1000}
                  {:harness "hard-gpt" :runs 1 :runs-with-usage 0
                   :cost-usd 0.0 :tokens-total 0}]
                 (:by-harness result))
              "per-harness rollups sort most expensive first")
          (is (= [(:id run-b)] (:missing-usage result)))
          (let [row (first (:runs result))]
            (is (= 300.0 (:duration-secs row)))
            (is (zero? (:exit-code row)))
            (is (= {:input 800 :output 200} (:tokens row))
                "token breakdown is a keyword-keyed map, not JSON text")))))))

(deftest feature-costs-fails-loudly-on-unknown-root-and-corrupt-usage
  (with-config-runtime
    (fn [rt]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"root strand not found"
                            (op! "feature-costs" ["nope"])))
      (let [root (weaver/add rt {:title "Ad hoc root" :state "active" :attributes {}})
            bad (weaver/add rt {:title "Corrupt run" :state "closed"
                                :attributes {:agent-run/run "true"
                                             :agent-run/cost-usd "not-a-number"}})]
        (weaver/update rt (:id root) {:edges [{:type "parent-of" :to (:id bad)}]})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed agent-run attribute"
                              (op! "feature-costs" [(:id root)]))
            "a present but unparseable usage value is corrupt data, not absence")))))

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
        (is (= [:agent-failure :gate-error :hitl-checkpoint-ready :kanban-blocked
                :kanban-completed :kanban-started :parked-run]
               (mapv :name rules)))
        ;; gate-error fires on any strand stamped with a gate error
        (let [note (fire :gate-error {:id "g1" :state "active" :title "Gate A"
                                      :attributes {:gate/error "spawn failed"}})]
          (is (= "Gate error: Gate A" (:title note)))
          (is (str/includes? (:body note) "spawn failed")))
        ;; and stays silent (no false positive) when the condition is absent
        (is (nil? (fire :gate-error {:id "g2" :state "active" :title "Clean gate"
                                     :attributes {}})))
        ;; agent-failure fires on a failed agent run and carries its error
        (let [note (fire :agent-failure {:id "r1" :state "active" :title "Run"
                                         :attributes {:agent-run/phase "failed" :agent-run/error "boom"}})]
          (is (= "Agent run failed: Run" (:title note)))
          (is (str/includes? (:body note) "boom")))))))

(deftest current-dags-op-builds-self-contained-plan-task-projection
  (with-config-runtime
    (fn [rt]
      (let [plan (weaver/add rt {:title "Feature: plan feature"
                                 :state "active"
                                 :attributes {:feature "plan-feature" :kind "plan" :workflow "agent-plan"}})
            impl (weaver/add rt {:title "Implement it"
                                 :state "active"
                                 :attributes {:feature "plan-feature" :kind "task" :workflow "agent-plan"
                                              :task_key "impl" :owner "agent-a" :harness "build"
                                              :cwd "/tmp/work" :validation ["clojure -M:test"]}})
            review (weaver/add rt {:title "Review it"
                                   :state "active"
                                   :attributes {:feature "plan-feature" :kind "review" :workflow "agent-plan"
                                                :task_key "review" :hitl true}})]
        (weaver/update rt (:id plan) {:edges [{:type "parent-of" :to (:id impl)}
                                              {:type "parent-of" :to (:id review)}]})
        (weaver/update rt (:id review) {:edges [{:type "depends-on" :to (:id impl)}]})
        (let [strands (weaver/list rt (var-get (requiring-resolve 'config/feature-active-query))
                                   {:feature "plan-feature"})
              children (:edges (graph/subgraph rt [(:id plan)] {:type "parent-of"}))
              ready (weaver/ready rt (var-get (requiring-resolve 'config/feature-work-query))
                                  {:feature "plan-feature"})]
          (is (= 3 (count strands)))
          (is (= #{(:id impl) (:id review)}
                 (set (map :to_strand_id children))))
          ;; review depends on impl, so only impl is ready
          (is (= ["Implement it"] (mapv :title ready)))
          ;; current-dags stays self-contained: a blocker outside the plan DAG
          ;; must not surface as a dangling depends-on edge
          (let [external (weaver/add rt {:title "External blocker"
                                         :state "active"
                                         :attributes {:kind "task"}})]
            (weaver/update rt (:id impl)
                           {:edges [{:type "depends-on" :to (:id external)}]})
            (let [dag (->> (:dags (op! "current-dags" []))
                           (filter #(= (:id plan) (get-in % [:root :id])))
                           first)
                  dag-ids (set (map :id (:strands dag)))]
              (is (some? dag))
              (is (every? #(and (contains? dag-ids (:from_strand_id %))
                                (contains? dag-ids (:to_strand_id %)))
                          (concat (:parent_of_edges dag) (:depends_on_edges dag)))))))))))

(deftest kanban-tree-op-projects-epic-feature-task-hierarchy
  ;; The kanban-tree projection joins the parent-of tiers (epic -> feature ->
  ;; task) the flat query surface can't, and derives task status. Uses the full
  ;; startup fixture because the op resolves the kanban spool's `kanban-cards`
  ;; query. Asserts epic linkage, top-level vs nested features, derived statuses
  ;; (done/blocked/doing/ready), and that closed tasks appear only under --all.
  (with-startup-config-runtime
    (fn [rt]
      (let [blocker (weaver/add rt {:title "Blocker" :state "active" :attributes {:kind "task"}})
            epic (weaver/add rt {:title "Epic E" :state "active"
                                 :attributes {:kanban/card "true" :kanban/type "epic"}})
            f1 (weaver/add rt {:title "Feature under epic" :state "active"
                               :attributes {:kanban/card "true" :kanban/type "feature"}})
            f2 (weaver/add rt {:title "Top-level feature" :state "active"
                               :attributes {:kanban/card "true" :kanban/type "feature"}})
            t-doing (weaver/add rt {:title "Doing task" :state "active"
                                    :attributes {:kanban/task "true" :owner "amy"}})
            t-ready (weaver/add rt {:title "Ready task" :state "active"
                                    :attributes {:kanban/task "true"}})
            t-blocked (weaver/add rt {:title "Blocked task" :state "active"
                                      :attributes {:kanban/task "true" :owner "bob"}})
            t-done (weaver/add rt {:title "Done task" :state "closed"
                                   :attributes {:kanban/task "true" :owner "amy"}})]
        (weaver/update rt (:id epic) {:edges [{:type "parent-of" :to (:id f1)}]})
        (weaver/update rt (:id f1) {:edges [{:type "parent-of" :to (:id t-doing)}
                                            {:type "parent-of" :to (:id t-ready)}
                                            {:type "parent-of" :to (:id t-blocked)}
                                            {:type "parent-of" :to (:id t-done)}]})
        (weaver/update rt (:id t-blocked) {:edges [{:type "depends-on" :to (:id blocker)}]})
        (let [by-id (fn [result] (into {} (map (juxt :id identity)) (:cards result)))
              active (by-id (op! "kanban-tree" []))
              full (by-id (op! "kanban-tree" ["--all" "true"]))
              task-status (fn [card] (into {} (map (juxt :title :status)) (:tasks card)))]
          ;; active view: only kanban cards (epic + features); the plain-task
          ;; blocker carries no kanban/card and never surfaces as a card
          (is (= #{(:id epic) (:id f1) (:id f2)} (set (keys active))))
          (is (= "epic" (:type (active (:id epic)))))
          (is (= "feature" (:type (active (:id f1)))))
          ;; epic linkage: a feature under an epic carries its epic id; others nil
          (is (= (:id epic) (:epic (active (:id f1)))))
          (is (nil? (:epic (active (:id f2)))))
          (is (nil? (:epic (active (:id epic)))))
          ;; derived task status; the closed task is filtered from the active view
          (is (= {"Doing task" "doing" "Ready task" "ready" "Blocked task" "blocked"}
                 (task-status (active (:id f1)))))
          (is (= [] (:tasks (active (:id f2)))))
          ;; --all surfaces the closed (done) task alongside the active ones
          (is (= {"Doing task" "doing" "Ready task" "ready" "Blocked task" "blocked" "Done task" "done"}
                 (task-status (full (:id f1))))))))))

(deftest branches-op-groups-branch-stamped-work-roots
  (with-config-runtime
    (fn [rt]
      (let [root (weaver/add rt {:title "Card: feature-x"
                                 :state "active"
                                 :attributes {:kanban/card "true" :kanban/status "claimed"
                                              :owner "agent-a" :branch "feature-x"
                                              :worktree "/tmp/feature-x"}})
            task (weaver/add rt {:title "Implement feature-x"
                                 :state "active"
                                 :attributes {:kind "task"}})
            review (weaver/add rt {:title "Review feature-x"
                                   :state "active"
                                   :attributes {:kind "review"}})]
        (weaver/update rt (:id root) {:edges [{:type "parent-of" :to (:id task)}
                                              {:type "parent-of" :to (:id review)}]})
        (weaver/update rt (:id review) {:edges [{:type "depends-on" :to (:id task)}]})
        ;; a branch-stamped child (task assigned owner+branch) must not
        ;; surface as a second work root for the branch
        (weaver/update rt (:id task) {:attributes {:kind "task" :branch "feature-x" :owner "agent-b"}})
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
                        :harness "worker"
                        :accept true
                        :tasks [{:id "a" :title "Do A" :body "A body"}
                                {:id "b" :title "Do B"}]})
      (let [strands (weaver/list rt)
            by-task (into {} (keep (fn [s]
                                     (when-let [task (or (get-in s [:attributes :delegate-pipeline/task])
                                                         (get-in s [:attributes "delegate-pipeline/task"]))]
                                       [task s])) strands))
            attr (fn [s k]
                   (or (get-in s [:attributes k])
                       (get-in s [:attributes (name k)])))]
        (is (= #{"a" "b"} (set (keys by-task))))
        (is (str/includes? (attr (by-task "a") :agent-run/prompt)
                           "[worker contract]"))
        (is (= "worker" (attr (by-task "b") :agent-run/harness))))))
  (testing "acceptance checkpoint is optional and task max-attempts pass through"
    (with-config-runtime
      (fn [rt]
        (patterns/weave! rt :delegate-pipeline
                         {:run_id "pipe-no-accept"
                          :tasks [{:id "a" :title "Do A" :harness "worker" :max-attempts 4}]})
        (let [strands (weaver/list rt)
              task (first (filter #(= "a" (or (get-in % [:attributes :delegate-pipeline/task])
                                              (get-in % [:attributes "delegate-pipeline/task"])))
                                  strands))]
          (is (some? task))
          (is (= 4 (or (get-in task [:attributes :agent-run/max-attempts])
                       (get-in task [:attributes "agent-run/max-attempts"]))))
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
        (weaver/add rt {:title title
                        :state "active"
                        :attributes (cond-> {:feature "work-query"}
                                      role (assoc :workflow/role role)
                                      (= title "Run record") (assoc :agent-run/run "true")
                                      (= title "Pending card") (assoc :kanban/card "true"
                                                                      :kanban/status "pending")
                                      (= title "Refinement card") (assoc :kanban/card "true"
                                                                         :kanban/status "refinement"))}))
      (is (= #{"Step" "Checkpoint" "Plain task" "Pending card"}
             (set (map :title (weaver/list rt (var-get (requiring-resolve 'config/work-query)) {})))))
      (is (= #{"Step" "Checkpoint" "Plain task" "Pending card"}
             (set (map :title (weaver/ready rt (var-get (requiring-resolve 'config/work-query)) {}))))))))

(deftest reviewers-file-registers-declarative-roster
  ;; exercises the same load path init.clj's :file+:call reviewers module runs
  (with-config-runtime
    (fn [_rt]
      (load-file ".skein/reviewers.clj")
      ((requiring-resolve 'reviewers/install!))
      (let [rosters ((requiring-resolve 'skein.spools.delegation/rosters))
            roster (first (filter #(= :change-review (:name %)) rosters))
            complex-roster (first (filter #(= :complex-patch-review (:name %)) rosters))
            docs-roster (first (filter #(= :docs-review (:name %)) rosters))]
        (is (= [:change-review :complex-patch-review :docs-review] (mapv :name rosters)))
        (let [sleeps (first (filter #(= "test-sleeps" (:name %)) (:reviewers roster)))]
          (is (some? sleeps) "owner-required test-sleeps reviewer is declared")
          (is (str/includes? (:contract sleeps) "time itself is a genuine component")))
        (is (= :sol-med (get-in roster [:synthesizer :harness]))
            "sign-off synthesis stays on the cross-vendor GPT seat")
        (is (= :terra-med (get-in complex-roster [:synthesizer :harness]))
            "complex patch review is synthesized outside its reviewer seats")
        (let [fact-check (first (filter #(= "docs-fact-check" (:name %)) (:reviewers docs-roster)))]
          (is (some? fact-check) "docs roster leads with the accuracy seat")
          (is (str/includes? (:contract fact-check) "NEVER the canonical .skein")))
        (is (= :sol-med (get-in docs-roster [:synthesizer :harness]))
            "docs sign-off synthesis stays on the cross-vendor GPT seat")))))

(deftest codex-harness-persists-sessions-and-declares-resume
  ;; PLAN-Pnl-001.A2/PH2: the repo :codex harness drops --ephemeral (sessions
  ;; persist) and declares the verified `codex exec resume <session-id>` splice.
  (with-config-runtime
    (fn [_rt]
      (let [codex ((requiring-resolve 'skein.spools.agent-run/resolve-harness) :codex)]
        (is (not-any? #{"--ephemeral"} (:argv codex))
            "sessions persist so codex exec resume can continue them")
        (is (= ["resume" :agent-run/session-id] (:resume codex))
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

(defn- shell-gate-complete!
  "Close the ready :shell land gate for feature the way the shell executor
  does — `complete!` with `:by \"shell\"`. The config fixture loads
  workflows.clj without installing the shell executor, so tests stand in
  for its pass path."
  [feature notes]
  (workflow/complete! feature {:by "shell" :notes notes}))

(deftest land-ops-drive-a-poured-run-end-to-end
  (with-config-runtime
    (fn [rt]
      (let [started (op! "land" ["start" "land-x" "--branch" "land-x" "--worktree" "/tmp/land-x"])]
        (is (= "land-start" (:operation started)))
        (is (false? (:done started)))
        (is (= "land.pr.open" (:action-ref (first (:ready started))))))
      ;; completing push-draft-pr leaves the machine ci-green shell gate ready,
      ;; carrying the interpolated watch command for the shell executor
      (let [gate (first (:ready (op! "land" ["complete" "land-x" "pushed; PR #1"])))
            gate-attrs (:attributes (weaver/show rt (:id gate)))]
        (is (= "land.ci.green" (:action-ref gate)))
        (is (= "shell" (:gate gate)))
        (is (= ["gh" "pr" "checks" "land-x" "--watch" "--fail-fast"] (:shell/argv gate-attrs)))
        (is (= "/tmp/land-x" (:shell/cwd gate-attrs))))
      ;; a coordinator cannot hand-close a CI gate; the shell executor owns it
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Gate steps require a non-blank :by"
                            (op! "land" ["complete" "land-x" "trying to skip CI"])))
      (shell-gate-complete! "land-x" "checks green")
      (is (= "land.signoff.review"
             (:action-ref (first (:ready (op! "land" ["next" "land-x"]))))))
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
      (shell-gate-complete! "land-z" "checks green")
      (op! "land" ["complete" "land-z"])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"another land run holds the merge lock"
                            (op! "land" ["choose" "land-z" "approved"])))
      ;; merge-local-verify hands off to the explicit main push step, then the
      ;; machine main-ci-green shell gate carrying the pushed-sha watch script
      (is (= "land.main.push"
             (:action-ref (first (:ready (op! "land" ["complete" "land-x" "merged; gates green"]))))))
      (let [gate (first (:ready (op! "land" ["complete" "land-x" "main pushed"])))
            gate-attrs (:attributes (weaver/show rt (:id gate)))]
        (is (= "land.main.ci-green" (:action-ref gate)))
        (is (= "shell" (:gate gate)))
        (is (= "sh" (first (:shell/argv gate-attrs))))
        (is (str/includes? (last (:shell/argv gate-attrs)) "gh run list")))
      (shell-gate-complete! "land-x" "main runs green")
      (let [ready-cleanup (op! "land" ["next" "land-x"])
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
      (let [card-id (:id (weaver/add rt {:title "Abort card"
                                         :attributes {:kanban/card "true"
                                                      :kanban/status "claimed"
                                                      :kanban/type "feature"}}))]
        (op! "land" ["start" "land-y" "--branch" "land-y" "--worktree" "/tmp/land-y" "--card" card-id]))
      ;; completing push-draft-pr starts the automated CI watch and review
      ;; pipeline, so it is the completion that moves the card to in_review
      (op! "land" ["complete" "land-y"])           ; push-draft-pr
      (let [root (workflow/current-root "land-y")
            context (get-in root [:attributes :workflow/context])
            card-id (or (:card context) (get context "card"))]
        (is (= "in_review" (get-in (weaver/show rt card-id) [:attributes :kanban/status]))))
      (shell-gate-complete! "land-y" "checks green") ; ci-green
      (op! "land" ["complete" "land-y"])           ; signoff-review
      (let [aborted (op! "land" ["choose" "land-y" "abort" "{\"reason\":\"scope changed\"}"])]
        (is (= "land-choose" (:operation aborted)))
        ;; routing is a hard cutover to the reason-recording continuation
        (is (= "land.abort.record" (:action-ref (first (:ready aborted))))))
      (let [root (workflow/current-root "land-y")
            context (get-in root [:attributes :workflow/context])
            card-id (or (:card context) (get context "card"))]
        (is (= "claimed" (get-in (weaver/show rt card-id) [:attributes :kanban/status]))))
      (let [done (op! "land" ["complete" "land-y" "abort recorded"])]
        (is (true? (:done done)))
        (is (empty? (:ready done)))))))

(deftest land-cleanup-instruction-interpolates-the-real-card-id
  (with-config-runtime
    (fn [rt]
      (let [card-id (:id (weaver/add rt {:title "Cleanup card"
                                         :attributes {:kanban/card "true"
                                                      :kanban/status "claimed"
                                                      :kanban/type "feature"}}))]
        (op! "land" ["start" "land-w" "--branch" "land-w" "--worktree" "/tmp/land-w" "--card" card-id])
        (op! "land" ["complete" "land-w"])                          ; push-draft-pr
        (shell-gate-complete! "land-w" "checks green")              ; ci-green
        (op! "land" ["complete" "land-w"])                          ; signoff-review
        (op! "land" ["choose" "land-w" "approved"])
        (op! "land" ["complete" "land-w"])                          ; merge-local-verify
        (op! "land" ["complete" "land-w"])                          ; push-main
        (shell-gate-complete! "land-w" "main runs green")           ; main-ci-green
        (let [ready-cleanup (op! "land" ["next" "land-w"])
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
      (shell-gate-complete! "land-lock-x" "checks green")
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
      (weaver/add rt {:title "Merge lock: land-dup-a"
                      :attributes {:kind "merge-lock" :land/run-id "land-dup-a"}})
      (weaver/add rt {:title "Merge lock: land-dup-b"
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
              run-a (weaver/add rt {:title "Run A"
                                    :state "closed"
                                    :attributes {:agent-run/run "true"
                                                 :agent-run/phase "done"
                                                 :agent-run/result "A complete"
                                                 :gate/run-id "flow-status-test"}
                                    :edges [{:type "serves" :to gate-a}]})]
          (workflow/complete! "flow-status-test" {:step gate-a :by (:id run-a)})
          (let [gate-b (:id (first (workflow/next-steps "flow-status-test")))
                run-b (weaver/add rt {:title "Run B"
                                      :state "active"
                                      :attributes {:agent-run/run "true"
                                                   :agent-run/phase "failed"
                                                   :agent-run/error "boom"
                                                   :gate/run-id "flow-status-test"}
                                      :edges [{:type "serves" :to gate-b}]})]
            ;; failure summaries are scoped to the requested run: an unrelated
            ;; failed run and an unrelated error-stamped gate must not leak in
            (weaver/add rt {:title "Unrelated failed run"
                            :attributes {:agent-run/run "true"
                                         :agent-run/phase "failed"
                                         :agent-run/error "other workflow"}})
            (weaver/add rt {:title "Unrelated stalled gate"
                            :attributes {:workflow/gate "subagent"
                                         :gate/error "spawn failed elsewhere"}})
            (let [status (op! "flow-status" ["flow-status-test"])
                  by-title (into {} (map (juxt :title identity)) (:gates status))]
              (is (= "flow-status" (:operation status)))
              (is (false? (:done status)))
              (is (= ["Delegate B"] (mapv :title (:frontier status))))
              (is (= [:gate-closed] (mapv :type (get-in status [:history 0 :events]))))
              (is (= "done" (get-in by-title ["Delegate A" :run :agent-run/phase])))
              (is (= "failed" (get-in by-title ["Delegate B" :run :agent-run/phase])))
              (is (true? (get-in by-title ["Delegate B" :stalled?])))
              (is (= #{(:id run-b)} (set (map :id (:agent-failures status)))))
              (is (empty? (:stalled-gates status)))
              (is (str/includes? (:dev/mermaid status) "Delegate B (stalled)")))))))))

(defn- assert-treadle-installed-after-config
  "Assert the subagent executor loaded and declares :config in :after — its install! runs an
  initial gate scan, so config.clj's harness aliases must already exist or a
  durable ready gate would be stamped gate/error on every cold start."
  [rt]
  (let [use (get (runtime/uses rt) :skein/spools-treadle)]
    (is (= :loaded (:status use)))
    (is (some #{:config} (get-in use [:opts :after])))))

(defn- assert-workflow-spool-consent-edges
  "Assert repo startup guards every module that now relies on the workflow coordinate."
  [rt]
  (let [uses (runtime/uses rt)]
    (doseq [use-id [:skein/spools-workflow :skein/spools-reed]]
      (is (= ['skein.spools/workflow] (get-in uses [use-id :opts :spools]))
          (str use-id " must opt into skein.spools/workflow")))
    (is (= ['skein.spools/carder 'skein.spools/loom 'skein.spools/workflow
            'skein.spools/agent-run 'codethread/devflow 'skein.macros/macros]
           (get-in uses [:config :opts :spools]))
        ":config must guard every spool coordinate its config.clj ns requires")
    (is (true? (get-in uses [:config :opts :required?]))
        ":config is required — a guarded but non-required module skips silently, dropping the op/query surface")
    (doseq [use-id [:workflows]]
      (is (= ['skein.spools/loom 'skein.spools/workflow 'skein.spools/delegation]
             (get-in uses [use-id :opts :spools]))
          (str use-id " must opt into skein.spools/loom, skein.spools/workflow, and skein.spools/delegation")))))

(defn- assert-kanban-tracker-installed
  "Assert startup loaded the required devflow tracker binding."
  [rt]
  (let [tracker-use (get (runtime/uses rt) :kanban/tracker)]
    (is (= :loaded (:status tracker-use)))
    (is (true? (get-in tracker-use [:opts :required?])))
    (is (re-find #"Bound tracker: devflow" (:tracker (op! "kanban" ["about"]))))))

(deftest kanban-tracker-devflow-projection-contract
  (load-file ".skein/kanban_tracker.clj")
  (let [project (requiring-resolve 'kanban-tracker/devflow-projection)]
    (testing "an active root projects its stage and ready steps"
      (with-redefs [devflow/feature-roots (constantly [{:attributes {:devflow/stage "tasks"}}])
                    devflow/next-steps (constantly [{:id "next" :title "Do next" :kind "step"}])]
        (is (= {:status "tasks"
                :next-steps [{:id "next" :title "Do next" :kind "step"}]}
               (project "active-run")))))
    (testing "no active root is the accepted nil-status projection"
      (with-redefs [devflow/feature-roots (constantly [])
                    devflow/next-steps (fn [_] (throw (ex-info "must not read steps" {})))]
        (is (= {:status nil :next-steps []}
               (project "inactive-run")))))
    (testing "a malformed run id fails at the adapter boundary"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank string"
                            (project ""))))))

(defn- assert-ephemeral-spool-consent-edge
  "Assert repo startup guards the activated ephemeral spool with its coordinate."
  [rt]
  (let [uses (runtime/uses rt)]
    (is (= ['skein.spools/ephemeral] (get-in uses [:skein/spools-ephemeral :opts :spools]))
        ":skein/spools-ephemeral must opt into skein.spools/ephemeral")))

(defn- assert-roster-spool-consent-edge
  "Assert repo startup guards the activated roster spool with its coordinate."
  [rt]
  (let [uses (runtime/uses rt)]
    (is (= ['skein.spools/roster] (get-in uses [:skein/spools-roster :opts :spools]))
        ":skein/spools-roster must opt into skein.spools/roster")))

(defn- assert-loom-spool-consent-edge
  "Assert repo startup guards the activated loom spool with its coordinate."
  [rt]
  (let [uses (runtime/uses rt)]
    (is (= ['skein.spools/loom] (get-in uses [:skein/spools-loom :opts :spools]))
        ":skein/spools-loom must opt into skein.spools/loom")))

(deftest repo-local-startup-and-reload-preserve-registrations
  (with-startup-config-runtime
    (fn [rt]
      (assert-config-registrations rt)
      (assert-treadle-installed-after-config rt)
      (assert-workflow-spool-consent-edges rt)
      (assert-kanban-tracker-installed rt)
      (assert-ephemeral-spool-consent-edge rt)
      (assert-roster-spool-consent-edge rt)
      (assert-loom-spool-consent-edge rt)
      (op! "devflow-start" ["startup-feature" "already-in-worktree-ok"])
      (is (= :loaded (:status (runtime/reload! rt))))
      (assert-config-registrations rt)
      (assert-workflow-spool-consent-edges rt)
      (assert-kanban-tracker-installed rt)
      (assert-ephemeral-spool-consent-edge rt)
      (assert-roster-spool-consent-edge rt)
      (assert-loom-spool-consent-edge rt)
      ;; runtime registries reload; the strand graph and run state persist
      (let [status (op! "devflow-status" ["startup-feature"])]
        (is (false? (:done status)))
        (is (= "create-or-confirm-worktree" (:checkpoint (first (:ready status)))))))))

;; ---------------------------------------------------------------------------
;; Guard-wiring assertion gate (PROP-usc-001.R1/.V, PLAN-usc-001.V4)
;; ---------------------------------------------------------------------------

(defn- read-first-form
  "Read the first top-level form of a Clojure source file without evaluating it."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false]
      (read {:eof ::eof} r))))

(defn- read-all-forms
  "Read every top-level form of a Clojure source file without evaluating them."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false]
      (into [] (take-while #(not= ::eof %))
            (repeatedly #(read {:eof ::eof} r))))))

(defn- unquote-form
  "Unwrap a reader `(quote x)` list to x, leaving other forms untouched."
  [form]
  (if (and (seq? form) (= 'quote (first form))) (second form) form))

(defn- use-form?
  "True when form is a `runtime/use!` module registration call."
  [form]
  (and (seq? form) (= 'runtime/use! (first form))))

(defn- parse-use-form
  "Project a `(runtime/use! runtime <key> <opts>)` form into its guard-relevant data."
  [form]
  (let [opts (nth form 3)]
    {:key (nth form 2)
     :ns (some-> (:ns opts) unquote-form)
     :file (:file opts)
     :spools (into #{} (map unquote-form) (:spools opts))}))

(defn- ns-require-libs
  "Return the required namespace symbols from a parsed `ns` form's :require clauses."
  [ns-form]
  (->> (rest ns-form)
       (filter #(and (seq? %) (= :require (first %))))
       (mapcat rest)
       (map #(if (sequential? %) (first %) %))))

(defn- spool-or-macros-ns?
  "True when sym names a skein.spools.* or skein.macros.* namespace."
  [sym]
  (let [n (name sym)]
    (or (str/starts-with? n "skein.spools.")
        (str/starts-with? n "skein.macros."))))

(defn- coordinate-source-roots
  "Map each loaded/available synced coordinate to its deps.edn :paths source dirs.

  This is the approved-manifest resolution surface: the spools.edn coordinate,
  the root the runtime synced it to, and that root's deps.edn `:paths` — the only
  thing that maps a namespace to a coordinate without a name heuristic."
  [rt]
  (into {}
        (keep (fn [[coord {:keys [root status]}]]
                (when (#{:loaded :already-available} status)
                  (let [deps (edn/read-string (slurp (io/file root "deps.edn")))
                        paths (or (:paths deps) ["src"])]
                    [coord (mapv #(io/file root %) paths)]))))
        (:spools (runtime/syncs rt))))

(defn- ns->source-relative-path
  "Return the classpath-relative source path for a namespace symbol."
  [ns-sym]
  (str (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(defn- resolve-spool-coordinate
  "Resolve ns-sym to the coordinate whose synced root holds its source file, or nil."
  [coordinate-roots ns-sym]
  (let [relative (ns->source-relative-path ns-sym)]
    (some (fn [[coord source-dirs]]
            (when (some #(.isFile (io/file % relative)) source-dirs)
              coord))
          coordinate-roots)))

(deftest init-use-guards-declare-required-spool-coordinates
  ;; PROP-usc-001.R1/.V, PLAN-usc-001.V4/.TC2: the guard-wiring acceptance gate.
  ;; A synced root resolves through the add-libs classloader whether or not a
  ;; use! declares :spools, so a green world load never proves consent is wired.
  ;; This asserts it directly: every init.clj use! that pulls a skein.spools.*/
  ;; skein.macros.* namespace onto the classpath — a :ns activation (its own
  ;; coordinate) or a :file module's ns :require (each required coordinate) —
  ;; must declare that coordinate in :spools, batteries (the classpath exception
  ;; with no coordinate) excepted. Coordinates resolve through the synced root
  ;; manifests, never a name heuristic: skein.spools.devflow lives in the
  ;; codethread/devflow root and skein.spools.executors.shell in the
  ;; skein.spools/workflow root, so a prefix rule would both false-fail devflow
  ;; and false-pass a real miss.
  (with-startup-config-runtime
    (fn [rt]
      (let [coordinate-roots (coordinate-source-roots rt)
            uses (map parse-use-form (filter use-form? (read-all-forms ".skein/init.clj")))]
        (is (seq uses) "parsed at least one init.clj use! form")
        (doseq [{:keys [key file spools] use-ns :ns} uses
                :when (not= use-ns 'skein.spools.batteries)]
          (let [required-nss (if file
                               (->> (ns-require-libs (read-first-form (io/file ".skein" file)))
                                    (filter spool-or-macros-ns?))
                               [use-ns])]
            (doseq [required-ns required-nss]
              (let [coord (resolve-spool-coordinate coordinate-roots required-ns)]
                (is (some? coord)
                    (str key " requires " required-ns
                         " but no synced spool root supplies its source"))
                (is (contains? spools coord)
                    (str key " requires " required-ns " (coordinate " coord
                         ") but its :spools guard " spools " does not declare it"))))))))))
