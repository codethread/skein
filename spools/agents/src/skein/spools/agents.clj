(ns skein.spools.agents
  "Agent coordination spool layered over the shuttle run engine."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as api]
            [skein.spools.shuttle :as shuttle]))

(defn- rt
  []
  (current/runtime))

(defn- fail!
  [m d]
  (throw (ex-info m d)))

(defn- non-blank?
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- attr
  [s k]
  (get-in s [:attributes k]))

(defn- workspace-root-dir
  []
  (let [config-dir (or (get-in (rt) [:metadata :config-dir])
                       (fail! "agents requires an active workspace" {}))
        config-file (java.io.File. config-dir)]
    (if (= ".skein" (.getName config-file))
      (-> config-file .getParentFile .getCanonicalPath)
      (.getCanonicalPath config-file))))

(defn- sattr
  [s k]
  (get-in s [:attributes (keyword "shuttle" k)]))

(defn- parse-int!
  [flag value]
  (try
    (Long/parseLong value)
    (catch NumberFormatException _
      (fail! "Flag requires an integer value" {:flag flag :value value}))))

(defn- truncate
  [s n]
  (if (> (count s) n)
    (str (subs s 0 (- n 1)) "…")
    s))

(def review-contract
  "Read-only reviewer contract text used as the workspace default for `agent review`."
  "[review contract]\n- You are a read-only reviewer: inspect the target strand, its subtree when present, notes, and repository state.\n- Do not edit files, mutate strands, close tasks, set status=implemented, commit, or spawn mutating workers.\n- Append prioritized findings as notes on the target strand with agent note.\n- Report only actionable correctness, regression, contract, and maintainability risks; say when no findings are found.")

(def worker-contract
  "Worker contract text appended to every shuttle preamble."
  "[worker contract]\n- Read your assigned strand AND its notes first: strand show <task-id>; agent notes <task-id> — the body may be newer than your launch prompt, and a predecessor's notes may save you from repeating its mistakes.\n- Record progress as you go: strand update <task-id> --attr progress=...\n- Set --attr status=implemented only when your validation gate is green.\n- Never close your assigned strand. Never mutate sibling or parent strands. Never commit unless your contract says so.\n- Spawn read-only helpers freely: agent spawn --harness explore --prompt \"...\" --spawned-by <your-run-id>; then agent await <helper-run-id> — the findings are in the returned result.\n- Leave durable findings for the coordinator and successors: agent note <task-id> \"...\" --by <your-run-id>.\n- Keep delegation shallow; never spawn a second mutator inside your own file scope.")

(def about-doc
  "Structured manual returned by `agent about`."
  {:manual "agent — spawn and coordinate coding-agent runs over the strand graph. Every operational verb returns JSON (`about` returns this manual). All verbs are flat under `strand agent <verb>`."
   :concepts {:read-first true
              :traps ["A RUN is a strand carrying shuttle/* attributes; a TASK is an ordinary work strand you delegate. Their ids look identical; each verb states which kind it takes."
                      "Run success never closes the task it served: YOU verify, then close the task to unblock dependents. Skip the close and the plan silently stalls."
                      "A task's FILE SCOPE is the set of files its body names as owned; every scope rule (disjoint siblings, one mutator per scope) refers to that owned set."
                      "An INTERACTIVE run is a live multiplexer session (mode=interactive): it completes when the strand it serves closes, not when a process exits. ps shows its attach command; a session that dies early fails the run loudly."]
              :scheduler "depends-on readiness is the only scheduler: a pending run starts the moment its blockers close."
              :run-result "A successful run closes itself with the worker's final message in shuttle/result. A failed run stays active, loud, and visible until retry or kill."
              :phase-enum ["pending" "running" "done" "failed" "exhausted" "superseded"]
              :terminal-phases ["done" "failed" "exhausted" "superseded"]
              :active-terminal-phases ["failed" "exhausted"]}
   :verbs {:spawn {:group "engine"
                   :usage "agent spawn --harness <name> --prompt ... [--title t] [--depends-on <strand-id>]... [--for <strand-id>] [--spawned-by <run-id>] [--cwd <dir>] [--max-attempts n] [--interactive --backend <name> [--reap auto|manual]]"
                   :takes "No positional args; raw run creation, no task contract."
                   :semantics ["Async; the run starts when ready."
                               "--for is the strand this run serves and creates the parent-of edge."
                               "--spawned-by is the caller's run id for helper provenance only. Helpers usually pass only --spawned-by."
                               "--interactive launches the harness into a multiplexer session via --backend instead of exec-and-wait. With --for the run completes when that strand closes; without it, when the run strand itself is closed. --reap manual leaves the session open for the human after completion (default auto tears it down)."]
                   :fails ["missing --harness" "missing --prompt" "unknown flag" "malformed --max-attempts" "--interactive without --backend" "--backend/--reap without --interactive"]
                   :returns {"id" "run id" "title" "string" "state" "active|closed" "phase" "pending" "harness" "string" "mode" "optional interactive" "session" "optional session name" "attach" "optional human attach command"}}
           :ps {:group "engine"
                :usage "agent ps [--active] [--for <strand-id>]"
                :semantics ["List shuttle run summaries; --active restricts to active run strands; --for restricts to runs serving one strand."
                            "Listing doubles as an interactive liveness check: a dead session is failed here. Interactive summaries carry mode/backend/session and the attach command to hand the human."]
                :returns [{"id" "run id" "title" "string" "state" "string" "phase" "string" "harness" "string" "for" "optional served strand" "spawned-by" "optional parent run" "attempt" "optional integer" "result" "optional string" "error" "optional string" "mode" "optional interactive" "backend" "optional string" "session" "optional string" "attach" "optional string"}]}
           :await {:group "engine"
                   :usage "agent await <run-id>... [--under <root-id>] [--timeout-secs n]"
                   :semantics ["Block until every listed run is terminal: closed, failed, or exhausted."
                               "--under <root-id> awaits every non-terminal pending/running run in the delegation tree beneath a plan or task."
                               "Run ids and --under are mutually exclusive. A finished helper's findings are in result; logs are usually only for failure forensics."]
                   :fails ["ids with --under" "no ids and no non-terminal runs under root" "malformed timeout"]
                   :returns {"timed-out" false "runs" ["ps summary shape including result/error"]}}
           :logs {:group "engine"
                  :usage "agent logs <run-id> [--tail n]"
                  :semantics ["Return captured stdout/stderr text and paths for debugging and failure forensics."
                              "For a RUNNING interactive run, logs captures the session transcript fresh (harness :capture when configured, else backend scrollback) — a coordinator peek without attaching. Finished interactive runs return the transcript persisted at teardown; err is omitted for interactive runs."]
                  :fails ["missing run id" "run has no shuttle/log" "log file missing" "malformed --tail" "interactive run with no capture op"]
                  :returns {"id" "run id" "out" {"path" "string" "text" "string"} "err" {"path" "string" "text" "string (headless only)"}}}
           :kill {:group "engine"
                  :usage "agent kill <run-id>"
                  :semantics ["Kill a running run's live process or interactive session and mark it failed. For an already failed run, use retry instead."]
                  :fails ["missing run id" "run has no live process" "run has no live session"]
                  :returns {"killed" "run id"}}
           :harnesses {:group "engine"
                       :usage "agent harnesses"
                       :semantics ["List configured harnesses and aliases."
                                   "A harness picks who does the work; validation remains in task attributes and proves the work independently."]
                       :returns [{"name" "string" "kind" "harness|alias" "alias-of" "optional string" "argv" "optional vector" "doc" "optional string"}]}
           :backends {:group "engine"
                      :usage "agent backends"
                      :semantics ["List configured interactive session backends (terminal multiplexers registered with defbackend! in trusted config)."]
                      :returns [{"name" "string" "ops" ["start alive stop capture attach subset"] "doc" "optional string"}]}
           :delegate {:group "delegation"
                      :usage "agent delegate <task-id> [--harness h] [--cwd dir] [--prompt extra] [--spawned-by run] [--interactive [--backend b] [--reap auto|manual]]"
                      :takes "An active, ready task strand."
                      :semantics ["Builds the worker prompt from the task's current title, body, and validation attribute."
                                  "Injects the worker contract and spawns a run attached --for the task."
                                  "Harness resolution is --harness flag > task harness attribute > loud failure; there is no default."
                                  "cwd resolution is --cwd flag > task cwd attribute > workspace root."
                                  "A task with any non-superseded run is not delegable: pending/running is active, failed/exhausted wants retry, done must be verified and closed."
                                  "--interactive opens a live multiplexer session for the task instead of a headless run — this is how hitl=true tasks are delegated. Backend resolution is --backend flag > task backend attribute > loud failure. The session is torn down when the task closes (the agent closes it once the human agrees the work is done)."]
                      :fails ["task not found" "task not active" "task not ready" "missing body and --prompt" "missing harness" "hitl=true without --interactive" "missing backend with --interactive" "--backend/--reap without --interactive" "has active run" "failed/exhausted run wants retry" "successful run awaits verification"]
                      :returns {"task" "task id" "run" {"id" "run id" "phase" "pending" "harness" "string" "attach" "optional string"}}}
           :delegate-ready {:group "delegation"
                            :usage "agent delegate --ready <plan-id> [--cwd dir]"
                            :takes "A plan/root strand."
                            :semantics ["Fan out every ready, non-hitl task under the plan that has no active, failed/exhausted, or successful non-superseded run."
                                        "Harness comes from each task's harness attribute; mixed-harness fan-out is expected."
                                        "Fails loudly up front, delegating nothing, if any selected ready task lacks harness."
                                        "Idempotent: re-invoke after verifying and closing finished tasks to pick up newly unblocked work."]
                            :skipped-reasons ["hitl" "has-active-run" "failed-needs-retry" "already-succeeded"]
                            :fails ["positional task supplied with --ready" "selected ready tasks missing harness"]
                            :returns {"plan" "plan id" "delegated" [{"task" "task id" "run" {"id" "run id" "phase" "pending" "harness" "string"}}] "skipped" [{"task" "task id" "reason" "hitl|has-active-run|failed-needs-retry|already-succeeded"}]}}
           :retry {:group "delegation"
                   :usage "agent retry <task-or-run-id> [--harness h] [--cwd dir] [--prompt extra]"
                   :semantics ["The recovery verb. Given a task id, find its failed/exhausted run, close it with phase superseded, rebuild the prompt from the task's current body, and spawn fresh."
                               "When the contract was wrong, edit the task body first with `strand update <task-id> --attr body=:payload/<name> --payload <name>=<path>` or `--attr body=:stdin`."
                               "Given a raw run id, supersede and respawn from the original prompt while preserving served target, spawned-by provenance, depends-on edges, cwd, and max-attempts."
                               "A failed interactive run retries as a fresh session preserving mode, backend, and reap policy; there are deliberately no retry flags to change them — respawn with spawn/delegate if the backend itself was the problem."]
                   :fails ["target not found" "nothing failed/exhausted to supersede"]
                   :returns {"superseded" "old run id" "task" "optional task id" "run" {"id" "new run id" "phase" "pending" "harness" "string"}}}
           :status {:group "delegation"
                    :usage "agent status [root-id]"
                    :semantics ["Coordinator dashboard. root-id is a plan or task; no root means active delegation in the workspace."
                                "Tree renders tasks, their runs, and nested sub-spawns via parent-of plus spawned-by."
                                "ready lists tasks delegable right now, matching delegate --ready's successful selection."
                                "awaiting_verification lists tasks where a worker set status=implemented; coordinator still verifies and closes the task."]
                    :returns {"tree" [{"id" "strand id" "title" "string" "kind" "task|run" "phase" "optional run phase" "status" "optional task status" "children" []}]
                              "ready" ["task ids"]
                              "running" ["run ids"]
                              "failed" [{"task" "optional task id" "run" "run id" "error" "string"}]
                              "awaiting_verification" ["task ids"]
                              "blocked" [{"task" "task id" "blockers" ["ids"]}]}}
           :note {:group "memory-review"
                  :usage "agent note <strand-id> text [--by <run-id>] [--round n]"
                  :semantics ["Append an immutable note to any strand memory."
                              "Notes are append-only memory, not mutation; workers may note any strand, including parents, without violating their contract."
                              "--round is for councils."]
                  :returns {"id" "note id" "note-for" "strand id"}}
           :notes {:group "memory-review"
                   :usage "agent notes <strand-id> [--round n]"
                   :semantics ["Read a strand's notes in order; optionally filter one council round."]
                   :returns [{"id" "note id" "note" "text" "at" "timestamp" "by" "optional run id" "round" "optional integer"}]}
           :review {:group "memory-review"
                    :usage "agent review <target-id> [--members n] [--harness a,b] [--cwd dir] [--contract text] [--spawned-by run] [--synthesize]"
                    :semantics ["Spawn independent read-only reviewers of the target strand and its subtree; reviewing a plan root reviews the whole feature."
                                "Each reviewer reads strand contracts plus repository state at --cwd. Pass the worktree where the diff lives."
                                "Findings are appended as notes on the target. --synthesize adds a run depending on all reviewers; await it for the verdict."]
                    :fails ["target not found" "no reviewers" "reviewer missing harness"]
                    :returns {"target" "target id" "reviewers" ["run ids"] "synthesizer" "optional run id"}}
           :council {:group "memory-review"
                     :usage "agent council --topic ... [--members n] [--rounds n] [--harness name] [--spawned-by run]"
                     :semantics ["Create a shared council strand, spawn members that deliberate through notes, then spawn a synthesizer depending on members."]
                     :fails ["blank topic" "non-positive members or rounds"]
                     :returns {"council" "strand id" "members" ["run ids"] "synthesizer" "run id"}}}
   :plan-creation {:usage "strand weave --pattern agent-plan"
                   :semantics ["Create a feature/plan strand plus task/review children."
                               "Task bodies are full worker contracts: scope, owned files, validation commands, and commit policy."
                               "depends_on values are sibling keys resolved to strand ids at weave time."
                               "Set harness on tasks; delegate --ready requires it."
                               "Harness and validation are independent axes: harness picks who does the work; validation lists commands that prove it."]
                   :task-fields ["key" "title" "body" "depends_on" "harness" "cwd" "validation" "max-attempts" "hitl" "kind"]
                   :returns {"plan" {"id" "strand id" "title" "string"} "tasks" {"<key>" {"id" "strand id" "title" "string"}}}}
   :coordinator-loop [{:step 1 :action "Provision working directories first; worktree management is deliberately outside this tool."}
                      {:step 2 :action "Weave an agent-plan; every task body is a complete contract and each delegated task has a harness."}
                      {:step 3 :action "agent delegate --ready <plan-id>; read skipped as well as delegated."}
                      {:step 4 :action "agent await --under <plan-id>, or await the returned run ids."}
                      {:step 5 :action "Verify status=implemented tasks yourself, re-run validation in their cwd, inspect the diff, then close the task. Closing unblocks dependents."}
                      {:step 6 :action "For failed runs: logs, diagnose, fix task body/environment, then agent retry <task-id>."}
                      {:step 7 :action "Repeat until status shows nothing ready, running, or failed; then fan in and close the plan root."}]
   :policy {:siblings "Sibling tasks own disjoint files; never two mutators in one file scope."
            :delegation-depth "Keep delegation shallow: workers spawn read-only helpers, not sub-plans, unless their contract says otherwise."}
   :worker-contract worker-contract})

(defn- parse-argv [argv flag-spec]
  (loop [xs argv pos [] flags {}]
    (if-let [a (first xs)]
      (if (str/starts-with? a "--")
        (let [kind (or (flag-spec a) (fail! "Unknown flag" {:flag a :allowed (sort (keys flag-spec))}))]
          (if (= :bool kind)
            (recur (rest xs) pos (assoc flags a true))
            (let [v (or (second xs) (fail! "Flag requires a value" {:flag a}))]
              (recur (drop 2 xs) pos (if (= :multi kind) (update flags a (fnil conj []) v) (assoc flags a v))))))
        (recur (rest xs) (conj pos a) flags))
      {:positional pos :flags flags})))

(defn- parent-descendants [root-id]
  (let [{:keys [strands]} (api/subgraph (rt) [root-id] {:type "parent-of"})]
    (remove #(= root-id (:id %)) strands)))

(defn- children-ids [id]
  (->> (:edges (api/subgraph (rt) [id] {:type "parent-of"}))
       (filter #(= id (:from_strand_id %)))
       (mapv :to_strand_id)))

(defn- run?
  [s]
  (= "true" (sattr s "run")))

(def ^:private terminal-phases #{"done" "failed" "exhausted" "superseded"})
(def ^:private success-phases #{"done"})
(def ^:private active-run-phases #{"pending" "running"})
(def ^:private failed-phases #{"failed" "exhausted"})

(defn- terminal-run?
  [s]
  (or (not= "active" (:state s))
      (terminal-phases (sattr s "phase"))))

(defn- task-runs [task-id]
  (->> (children-ids task-id) (map #(api/show (rt) %)) (filter run?) vec))

(defn- prompt-for-task
  ([task extra] (prompt-for-task task extra false))
  ([task extra interactive?]
   (let [body (some-> (attr task :body) str str/trim not-empty)
         extra (some-> extra str str/trim not-empty)]
     (when (and (nil? body) (nil? extra)) (fail! "delegate requires task body or --prompt" {:task (:id task)}))
     (str (if interactive?
            (str "You are an interactive session working WITH the user on strand `" (:id task) "` (`" (:title task) "`).\n"
                 "The task body below is the working contract; the human in this session is the authority on scope and completion.\n")
            (str "You are the delegated implementer for strand `" (:id task) "` (`" (:title task) "`).\n"))
          "Read the assigned strand first with the pinned strand command.\n\n"
          (when body (str "Task body:\n" body "\n\n"))
          (when-let [v (attr task :validation)] (str "Validation gate:\n" (if (sequential? v) (str/join "\n" v) v) "\n\n"))
          (when extra (str "Extra instructions:\n" extra "\n"))))))

(defn- active-task! [id]
  (let [task (or (api/show (rt) id) (fail! "task not found" {:task id}))]
    (when-not (= "active" (:state task)) (fail! "task must be active" {:task id :state (:state task)}))
    task))

(defn- ready? [id]
  (contains? (set (map :id (api/ready (rt) [:= :state "active"] {}))) id))

(defn- hitl? [task] (let [v (attr task :hitl)] (or (= true v) (= "true" v))))
(defn- harness-for [task flags]
  (or (get flags "--harness") (attr task :harness) (fail! "delegate requires --harness or task harness attribute" {:task (:id task)})))

(defn- delegate-task [task flags]
  (let [interactive? (boolean (get flags "--interactive"))]
    (when (and (not interactive?) (or (get flags "--backend") (get flags "--reap")))
      (fail! "delegate --backend/--reap require --interactive"
             {:task (:id task) :backend (get flags "--backend") :reap (get flags "--reap")}))
    (when-not (ready? (:id task)) (fail! "task is not ready" {:task (:id task)}))
    (when (and (hitl? task) (not interactive?))
      (fail! "task is hitl: delegate it with --interactive" {:task (:id task)}))
    (when-let [r (first (filter #(and (not= "superseded" (sattr % "phase")) (active-run-phases (sattr % "phase"))) (task-runs (:id task))))]
      (fail! "task has an ACTIVE run" {:task (:id task) :run (:id r)}))
    (when-let [r (first (filter #(and (not= "superseded" (sattr % "phase")) (failed-phases (sattr % "phase"))) (task-runs (:id task))))]
      (fail! "task wants retry" {:task (:id task) :run (:id r)}))
    (when-let [r (first (filter #(and (not= "superseded" (sattr % "phase")) (success-phases (sattr % "phase"))) (task-runs (:id task))))]
      (fail! "task already has a successful run (verify + close it)" {:task (:id task) :run (:id r)}))
    (let [harness (harness-for task flags)
          run (shuttle/spawn-run! (cond-> {:harness harness
                                           :prompt (prompt-for-task task (get flags "--prompt") interactive?)
                                           :title (str "Delegate: " (:title task))
                                           :parent (:id task)
                                           :cwd (or (get flags "--cwd")
                                                    (attr task :cwd)
                                                    (workspace-root-dir))}
                                    interactive? (assoc :mode :interactive
                                                        :backend (or (get flags "--backend")
                                                                     (attr task :backend)
                                                                     (fail! "delegate --interactive requires --backend or task backend attribute" {:task (:id task)}))
                                                        :reap (get flags "--reap"))
                                    (get flags "--spawned-by") (assoc :spawned-by (get flags "--spawned-by"))
                                    (attr task :max-attempts) (assoc :max-attempts (attr task :max-attempts))))]
      {:task (:id task) :run (select-keys (shuttle/run-summary run) [:id :phase :harness :attach])})))

(defn- skip-reason [task]
  (cond
    (hitl? task) "hitl"
    (some #(and (not= "superseded" (sattr % "phase")) (active-run-phases (sattr % "phase"))) (task-runs (:id task))) "has-active-run"
    (some #(and (not= "superseded" (sattr % "phase")) (failed-phases (sattr % "phase"))) (task-runs (:id task))) "failed-needs-retry"
    (some #(and (not= "superseded" (sattr % "phase")) (success-phases (sattr % "phase"))) (task-runs (:id task))) "already-succeeded"))

(defn- op-delegate [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--ready" :single "--harness" :single "--cwd" :single "--prompt" :single "--spawned-by" :single
                                                     "--interactive" :bool "--backend" :single "--reap" :single})]
    (if-let [plan (get flags "--ready")]
      (do (when (seq positional) (fail! "delegate --ready takes no task positional" {:got positional}))
          (when (or (get flags "--interactive") (get flags "--backend") (get flags "--reap"))
            (fail! "delegate --ready is headless fan-out; interactive flags are per-task so live sessions never swamp the human" {:plan plan}))
          (let [tasks (filter #(and (= "active" (:state %)) (not (run? %)) (ready? (:id %))) (parent-descendants plan))
                missing (filter #(and (nil? (skip-reason %)) (not (non-blank? (attr % :harness)))) tasks)]
            (when (seq missing) (fail! "ready tasks missing harness" {:tasks (mapv :id missing)}))
            {:plan plan
             :delegated (mapv #(delegate-task % flags) (remove skip-reason tasks))
             :skipped (mapv (fn [t] {:task (:id t) :reason (skip-reason t)}) (filter skip-reason tasks))}))
      (let [[task-id] positional]
        (when-not (= 1 (count positional)) (fail! "delegate requires <task-id> or --ready <plan-id>" {:got positional}))
        (delegate-task (active-task! task-id) flags)))))

(defn- op-spawn [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--harness" :single "--prompt" :single "--title" :single "--depends-on" :multi "--for" :single "--spawned-by" :single "--cwd" :single "--max-attempts" :single
                                                     "--interactive" :bool "--backend" :single "--reap" :single})]
    (when (seq positional) (fail! "spawn takes only flags" {:unexpected positional}))
    (shuttle/run-summary (shuttle/spawn-run! {:harness (or (get flags "--harness") (fail! "spawn requires --harness" {}))
                                             :prompt (or (get flags "--prompt") (fail! "spawn requires --prompt" {}))
                                             :title (get flags "--title")
                                             :depends-on (get flags "--depends-on")
                                             :parent (get flags "--for")
                                             :spawned-by (get flags "--spawned-by")
                                             :cwd (get flags "--cwd")
                                             :max-attempts (some->> (get flags "--max-attempts") (parse-int! "--max-attempts"))
                                             :mode (when (get flags "--interactive") :interactive)
                                             :backend (get flags "--backend")
                                             :reap (get flags "--reap")}))))

(defn- runs-under [root]
  (->> (parent-descendants root) (filter run?) (remove terminal-run?) (mapv :id)))

(defn- op-await [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--timeout-secs" :single "--under" :single})]
    (when (and (seq positional) (get flags "--under")) (fail! "await ids and --under are mutually exclusive" {:ids positional :under (get flags "--under")}))
    (let [ids (if-let [root (get flags "--under")] (runs-under root) positional)]
      (when (empty? ids) (fail! "await requires run ids or --under with non-terminal runs" {}))
      (shuttle/await-runs ids (cond-> {} (get flags "--timeout-secs") (assoc :timeout-secs (parse-int! "--timeout-secs" (get flags "--timeout-secs"))))))))

(defn- op-logs [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--tail" :single}) [run-id] positional]
    (when-not (= 1 (count positional)) (fail! "logs requires <run-id>" {:got positional}))
    (let [run (or (api/show (rt) run-id) (fail! "Run not found" {:id run-id}))
          n (some->> (get flags "--tail") (parse-int! "--tail"))
          clip (fn [text] (let [lines (str/split-lines text)] (str/join "\n" (if n (take-last n lines) lines))))
          rf (fn [path] (let [f (java.io.File. path)] (when-not (.exists f) (fail! "Run log file missing" {:id run-id :path path})) (clip (slurp f))))]
      (if (= "interactive" (sattr run "mode"))
        ;; phase running alone keys the fresh capture: a closed-but-unreaped
        ;; run (mid-teardown) has no persisted transcript yet, but its session
        ;; is still capturable
        (if (= "running" (sattr run "phase"))
          (let [{:keys [path text]} (shuttle/capture! run-id)]
            {:id run-id :out {:path path :text (clip text)}})
          (let [p (or (sattr run "log") (fail! "Run has no captured transcript" {:id run-id}))]
            {:id run-id :out {:path p :text (rf p)}}))
        (let [p (or (sattr run "log") (fail! "Run has no shuttle/log" {:id run-id}))]
          {:id run-id :out {:path p :text (rf p)} :err {:path (str/replace p #"\.out$" ".err") :text (rf (str/replace p #"\.out$" ".err"))}})))))

(defn- review-prompt [{:keys [target-id focus contract]}]
  (let [cmd (shuttle/pinned-strand-command)]
    (str contract "\n\n"
         "Review target strand " target-id " and its subtree.\n"
         (when (not (str/blank? focus))
           (str "Focus: " focus "\n"))
         "Read the target with `" cmd " show " target-id "` and inspect the relevant repository state.\n"
         "When finished, append findings with: " cmd " op agent note " target-id
         " \"<findings>\" --by <your run-id>\n"
         "Findings are notes-only; do not write verdict attributes.")))

(defn- review-synthesis-prompt [{:keys [target-id review-runs contract]}]
  (let [cmd (shuttle/pinned-strand-command)]
    (str contract "\n\n"
         "Synthesize independent review findings for target strand " target-id ".\n"
         "Review run ids: " (str/join ", " review-runs) "\n"
         "Read target notes with `" cmd " op agent notes " target-id "`, append one synthesis note with `"
         cmd " op agent note " target-id " \"<synthesis>\" --by <your run-id>`, then finish with the synthesis.")))

(defn review!
  "Spawn independent read-only reviewers for a target strand."
  [target-id {:keys [reviewers members harnesses contract synthesize? spawned-by cwd]
              :or {members 2}}]
  (when-not (api/show (rt) target-id)
    (fail! "Review target strand not found" {:id target-id}))
  (let [contract (or contract (shuttle/default-review-contract-text))
        reviewers (or reviewers
                      (let [hs (or (seq harnesses) [:claude])]
                        (mapv (fn [idx]
                                {:harness (nth hs (mod idx (count hs)))
                                 :focus (str "review pass " (inc idx))})
                              (range members))))]
    (when-not (and (vector? reviewers) (seq reviewers))
      (fail! "Review requires at least one reviewer" {:reviewers reviewers}))
    (let [review-runs (mapv (fn [{:keys [harness focus]}]
                              (shuttle/spawn-run!
                               {:harness (or harness (fail! "Review reviewer requires :harness" {:reviewer {:focus focus}}))
                                :title (truncate (str "Review " target-id (when focus (str ": " focus))) 72)
                                :prompt (review-prompt {:target-id target-id :focus focus :contract contract})
                                :parent target-id
                                :spawned-by spawned-by
                                :cwd cwd
                                :attrs {"shuttle/review-target" target-id
                                        "shuttle/review-focus" (or focus "")}}))
                            reviewers)
          synthesis (when synthesize?
                      (shuttle/spawn-run!
                       {:harness (:harness (first reviewers))
                        :title (truncate (str "Review synthesis: " target-id) 72)
                        :prompt (review-synthesis-prompt {:target-id target-id
                                                          :review-runs (mapv :id review-runs)
                                                          :contract contract})
                        :parent target-id
                        :spawned-by spawned-by
                        :cwd cwd
                        :depends-on (mapv :id review-runs)
                        :attrs {"shuttle/review-target" target-id
                                "shuttle/review-synthesis" "true"}}))]
      (cond-> {:target target-id :reviewers (mapv :id review-runs)}
        synthesis (assoc :synthesizer (:id synthesis))))))

(defn- council-member-prompt [{:keys [council-id topic member member-count rounds]}]
  (let [cmd (shuttle/pinned-strand-command)
        notes-cmd (str cmd " op agent notes " council-id)]
    (str "You are council member " member " of " member-count
         " deliberating this topic over " rounds " rounds:\n"
         topic "\n\n"
         "Shared memory is the council strand " council-id ". Protocol for each round r (1.." rounds "):\n"
         "1. Do your own genuine exploration/thinking for round r (use your tools; investigate, do not just opine).\n"
         "2. Post your position: " cmd " op agent note " council-id
         " \"<your round-r analysis>\" --by <your run-id> --round r\n"
         "3. Wait for peers: poll `" notes-cmd " --round r` (sleep a few seconds between polls, up to ~2 minutes)"
         " until it lists " member-count " notes, then read them all with `" notes-cmd "`.\n"
         "4. Let their arguments genuinely update your round r+1 thinking; agree, rebut, or refine.\n\n"
         "After the final round, end with your definitive position as your last message —"
         " it is captured automatically as your result.")))

(defn- council-synthesis-prompt [{:keys [council-id topic member-count rounds]}]
  (let [cmd (shuttle/pinned-strand-command)]
    (str "You are the synthesizer for a " member-count "-member, " rounds "-round council on:\n"
         topic "\n\n"
         "Read the full deliberation: " cmd " op agent notes " council-id "\n"
         "Weigh the arguments, note consensus and unresolved disagreements, and produce one decisive synthesis.\n"
         "Record it on the council strand: " cmd " update " council-id
         " --attr shuttle/result=\"<one-paragraph verdict>\"\n"
         "Then end with the full synthesis as your last message.")))

(defn council!
  "Convene a multi-agent council over a shared memory strand."
  [topic {:keys [harness members rounds spawned-by]
          :or {harness :claude members 2 rounds 2}}]
  (when (str/blank? topic)
    (fail! "Council topic must be non-blank" {}))
  (when-not (and (pos-int? members) (pos-int? rounds))
    (fail! "Council :members and :rounds must be positive integers" {:members members :rounds rounds}))
  (let [council (api/add (rt) {:title (truncate (str "Council: " topic) 72)
                               :attributes (cond-> {"shuttle/role" "council"
                                                    "shuttle/topic" topic
                                                    "shuttle/members" members
                                                    "shuttle/rounds" rounds}
                                             spawned-by (assoc "shuttle/spawned-by" spawned-by))})
        council-id (:id council)
        member-runs (mapv (fn [member]
                            (shuttle/spawn-run!
                             {:harness harness
                              :title (truncate (str "Council member " member ": " topic) 72)
                              :prompt (council-member-prompt {:council-id council-id
                                                              :topic topic
                                                              :member member
                                                              :member-count members
                                                              :rounds rounds})
                              :parent council-id
                              :attrs {"shuttle/council" council-id}}))
                          (range 1 (inc members)))
        synthesizer (shuttle/spawn-run!
                     {:harness harness
                      :title (truncate (str "Council synthesis: " topic) 72)
                      :prompt (council-synthesis-prompt {:council-id council-id
                                                         :topic topic
                                                         :member-count members
                                                         :rounds rounds})
                      :parent council-id
                      :depends-on (mapv :id member-runs)
                      :attrs {"shuttle/council" council-id}})]
    {:council council-id
     :members (mapv :id member-runs)
     :synthesizer (:id synthesizer)}))

(defn- op-note [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--by" :single "--round" :single})]
    (when-not (= 2 (count positional))
      (fail! "note requires <strand-id> <text>" {:got positional}))
    (shuttle/note! (first positional) (second positional)
                   (cond-> {}
                     (get flags "--by") (assoc :by (get flags "--by"))
                     (get flags "--round") (assoc :round (parse-int! "--round" (get flags "--round")))))))

(defn- op-notes [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--round" :single})]
    (when-not (= 1 (count positional))
      (fail! "notes requires <strand-id>" {:got positional}))
    (shuttle/notes (first positional)
                   (cond-> {}
                     (get flags "--round") (assoc :round (parse-int! "--round" (get flags "--round")))))))

(defn- split-csv [s]
  (when s
    (mapv str/trim (remove str/blank? (str/split s #",")))))

(defn- op-review [argv]
  (let [{:keys [positional flags]}
        (parse-argv argv {"--members" :single "--harness" :single "--synthesize" :bool
                          "--contract" :single "--spawned-by" :single "--cwd" :single})]
    (when-not (= 1 (count positional))
      (fail! "review requires <target-id>" {:got positional}))
    (review! (first positional)
             (cond-> {}
               (get flags "--members") (assoc :members (parse-int! "--members" (get flags "--members")))
               (get flags "--harness") (assoc :harnesses (split-csv (get flags "--harness")))
               (get flags "--contract") (assoc :contract (get flags "--contract"))
               (get flags "--spawned-by") (assoc :spawned-by (get flags "--spawned-by"))
               (get flags "--cwd") (assoc :cwd (get flags "--cwd"))
               (get flags "--synthesize") (assoc :synthesize? true)))))

(defn- op-council [argv]
  (let [{:keys [positional flags]}
        (parse-argv argv {"--topic" :single "--members" :single "--rounds" :single
                          "--harness" :single "--spawned-by" :single})]
    (when (seq positional)
      (fail! "council takes only flags" {:unexpected positional}))
    (council! (or (get flags "--topic") (fail! "council requires --topic" {}))
              (cond-> {}
                (get flags "--members") (assoc :members (parse-int! "--members" (get flags "--members")))
                (get flags "--rounds") (assoc :rounds (parse-int! "--rounds" (get flags "--rounds")))
                (get flags "--harness") (assoc :harness (get flags "--harness"))
                (get flags "--spawned-by") (assoc :spawned-by (get flags "--spawned-by"))))))

(defn- op-retry [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--harness" :single "--cwd" :single "--prompt" :single}) [id] positional]
    (when-not (= 1 (count positional)) (fail! "retry requires <task-or-run-id>" {:got positional}))
    (let [strand (or (api/show (rt) id) (fail! "retry target not found" {:id id}))
          run (if (run? strand) strand (first (filter #(failed-phases (sattr % "phase")) (task-runs id))))
          task-id (when-not (run? strand) id)]
      (when-not (and run (failed-phases (sattr run "phase"))) (fail! "nothing to supersede" {:id id}))
      (api/update (rt) (:id run) {:state "closed" :attributes {"shuttle/phase" "superseded"}})
      (let [summary (shuttle/run-summary run)
            task (when task-id (api/show (rt) task-id))
            served-target (or task-id (:for summary))
            deps (->> (:edges (api/subgraph (rt) [(:id run)] {:type "depends-on"}))
                      (filter #(= (:id run) (:from_strand_id %)))
                      (mapv :to_strand_id))
            interactive? (= "interactive" (sattr run "mode"))
            new-run (shuttle/spawn-run! (cond-> {:harness (or (get flags "--harness") (sattr run "harness"))
                                                 :prompt (if task (prompt-for-task task (get flags "--prompt") interactive?) (str (sattr run "prompt") (when-let [e (get flags "--prompt")] (str "\n\n" e))))
                                                 :title (str "Retry: " (:title (or task run)))
                                                 :parent served-target
                                                 :depends-on deps
                                                 :cwd (or (get flags "--cwd")
                                                          (sattr run "cwd")
                                                          (workspace-root-dir))}
                                          ;; a retried interactive task gets a fresh session on the same backend
                                          interactive? (assoc :mode :interactive
                                                              :backend (sattr run "backend")
                                                              :reap (sattr run "reap"))
                                          (sattr run "spawned-by") (assoc :spawned-by (sattr run "spawned-by"))
                                          (sattr run "max-attempts") (assoc :max-attempts (sattr run "max-attempts"))))]
        (cond-> {:superseded (:id run) :run (select-keys (shuttle/run-summary new-run) [:id :phase :harness])}
          task-id (assoc :task task-id))))))

(defn- blockers [task]
  (->> (:edges (api/subgraph (rt) [(:id task)] {:type "depends-on"}))
       (filter #(and (= (:id task) (:from_strand_id %)) (not= "closed" (:state (api/show (rt) (:to_strand_id %))))))
       (mapv :to_strand_id)))

(defn- tree-node [s]
  (let [kids (map api/show (repeat (rt)) (children-ids (:id s)))]
    (cond-> {:id (:id s) :title (:title s) :kind (if (run? s) "run" "task") :children (mapv tree-node kids)}
      (run? s) (assoc :phase (sattr s "phase"))
      (attr s :status) (assoc :status (attr s :status)))))

(defn- op-status [argv]
  (let [{:keys [positional]} (parse-argv argv {})]
    (when (> (count positional) 1) (fail! "status takes at most one root-id" {:got positional}))
    (let [root (first positional)
          nodes (if root (cons (api/show (rt) root) (parent-descendants root)) (api/list (rt) [:= :state "active"] {}))
          tasks (remove run? nodes)
          runs (filter run? nodes)]
      {:tree (mapv tree-node (if root [(api/show (rt) root)] (filter #(and (not (run? %)) (seq (task-runs (:id %)))) tasks)))
       :ready (mapv :id (filter #(and (not= root (:id %))
                                       (= "active" (:state %))
                                       (ready? (:id %))
                                       (not (hitl? %))
                                       (nil? (skip-reason %))
                                       (non-blank? (attr % :harness)))
                                 tasks))
       :running (mapv :id (filter #(active-run-phases (sattr % "phase")) runs))
       :failed (mapv (fn [r] (cond-> {:run (:id r) :error (sattr r "error")} (:for (shuttle/run-summary r)) (assoc :task (:for (shuttle/run-summary r))))) (filter #(failed-phases (sattr % "phase")) runs))
       :awaiting_verification (mapv :id (filter #(= "implemented" (attr % :status)) tasks))
       :blocked (mapv (fn [t] {:task (:id t) :blockers (blockers t)}) (filter #(seq (blockers %)) tasks))})))

(defn agent-op
  "Dispatch `strand agent` verbs."
  [{:keys [op/argv]}]
  (let [[sub & args] argv]
    (case sub
      nil about-doc
      "about" about-doc
      "spawn" (op-spawn args)
      "ps" (let [{:keys [positional flags]}
                  (parse-argv args {"--active" :bool "--for" :single})]
              (when (seq positional)
                (fail! "ps takes only flags" {:got positional}))
              (shuttle/runs (cond-> {:active (boolean (get flags "--active"))}
                              (get flags "--for") (assoc :for (get flags "--for")))))
      "await" (op-await args)
      "logs" (op-logs args)
      "kill" (do (when-not (= 1 (count args)) (fail! "kill requires <run-id>" {:got args})) (shuttle/kill! (first args)))
      "harnesses" (shuttle/harnesses)
      "backends" (shuttle/backends)
      "note" (op-note args)
      "notes" (op-notes args)
      "council" (op-council args)
      "review" (op-review args)
      "delegate" (op-delegate args)
      "retry" (op-retry args)
      "status" (op-status args)
      (fail! "Unknown agent subcommand" {:subcommand sub :available ["about" "spawn" "ps" "await" "logs" "kill" "harnesses" "backends" "note" "notes" "council" "review" "delegate" "retry" "status"]}))))

;; agent-plan pattern
(s/def ::non-blank-string non-blank?)
(s/def ::feature ::non-blank-string)
(s/def ::title ::non-blank-string)
(s/def ::key ::non-blank-string)
(s/def ::body ::non-blank-string)
(s/def ::kind #{"task" "review"})
(s/def ::hitl boolean?)
(s/def ::depends_on (s/coll-of ::key :kind vector?))
(s/def ::validation (s/coll-of ::non-blank-string :kind vector? :min-count 1))
(s/def ::harness ::non-blank-string)
(s/def ::cwd ::non-blank-string)
(s/def ::max-attempts pos-int?)
(s/def ::task
  (s/keys :req-un [::key ::title]
          :opt-un [::body ::kind ::hitl ::depends_on ::validation
                   ::harness ::cwd ::max-attempts]))
(s/def ::tasks (s/coll-of ::task :kind vector? :min-count 1))
(s/def ::agent-plan-input
  (s/keys :req-un [::feature ::title ::tasks]
          :opt-un [::body]))

(defn- ref-symbol
  [k]
  (symbol k))
(defn agent-plan
  "Create a feature strand plus task/review children for agent work."
  [{:keys [input]}]
  (let [{:keys [feature title body tasks]} input]
    (into [{:ref 'plan
            :title title
            :attributes (cond-> {:feature feature
                                 :kind "plan"
                                 :workflow "agent-plan"}
                          body (assoc :body body))
            :edges (mapv (fn [{:keys [key]}]
                           {:type "parent-of" :to (ref-symbol key)})
                         tasks)}]
          (map (fn [{:keys [key title body kind hitl depends_on validation
                            harness cwd max-attempts]}]
                 (cond-> {:ref (ref-symbol key)
                          :title title
                          :attributes (cond-> {:feature feature
                                               :kind (or kind "task")
                                               :workflow "agent-plan"
                                               :task_key key}
                                        body (assoc :body body)
                                        hitl (assoc :hitl true)
                                        validation (assoc :validation validation)
                                        harness (assoc :harness harness)
                                        cwd (assoc :cwd cwd)
                                        max-attempts (assoc :max-attempts max-attempts))}
                   (seq depends_on)
                   (assoc :edges (mapv (fn [d]
                                          {:type "depends-on" :to (ref-symbol d)})
                                        depends_on)))))
          tasks)))

(defn install!
  "Install the agents op surface, pattern, query, and worker preamble hook."
  []
  (let [runtime (rt)]
    (shuttle/set-preamble-extension! worker-contract)
    {:installed true
     :namespace 'skein.spools.agents
     :op (api/register-op! runtime 'agent
                           {:doc "Spawn and coordinate coding-agent runs; `strand agent about` is the manual"
                            ;; await blocks for arbitrarily long coordination waits
                            :deadline-class :unbounded}
                           'skein.spools.agents/agent-op)
     :pattern (patterns/register-pattern! runtime 'agent-plan "Create a feature strand plus task/review children for agent work." 'skein.spools.agents/agent-plan ::agent-plan-input)
     :query (api/register-query! runtime 'agent-failures [:and [:= :state "active"] [:= [:attr "shuttle/run"] "true"] [:in [:attr "shuttle/phase"] ["failed" "exhausted"]]])}))
