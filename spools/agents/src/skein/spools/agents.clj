(ns skein.spools.agents
  "Agent coordination spool layered over the shuttle run engine."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.runtime.alpha :as runtime]
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
              :composition "council is a preset over an internal PANEL primitive — seats × a shared blackboard × turn-as-run rows × synthesis (skein.spools.agents/panel!). review shares the same blackboard protocol and is expressible as a single-round panel (skein.spools.agents/roster->panel), but fans out through roster-review-specs so its established prompts and attrs stay frozen. There is deliberately no panel verb: compose panels from trusted Clojure or reach for them through these presets."
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
                   :usage "agent retry <task-or-run-id> [--fresh] [--harness h] [--cwd dir] [--prompt extra]"
                   :semantics ["The recovery verb. Given a task id, find its failed/exhausted run, close it with phase superseded, rebuild the prompt from the task's current body, and spawn fresh."
                               "When the contract was wrong, edit the task body first with `strand update <task-id> --attr body=:payload/<name> --payload <name>=<path>` or `--attr body=:stdin`."
                               "Given a raw run id, supersede and respawn from the original prompt while preserving served target, spawned-by provenance, depends-on edges, cwd, and max-attempts."
                               "A resumed run (one continuing a predecessor's session) re-resumes that same session by default. --fresh severs the linkage and respawns cold on the run's full-brief prompt, since a fresh process can never take the short continuation form."
                               "A plain retry of a run whose failure is resume-classed (the session was lost) fails loudly instructing --fresh, never looping against a dead session."
                               "A failed interactive run retries as a fresh session preserving mode, backend, and reap policy; there are deliberately no retry flags to change them — respawn with spawn/delegate if the backend itself was the problem."]
                   :fails ["target not found" "nothing failed/exhausted to supersede" "resume-classed failure retried without --fresh"]
                   :returns {"superseded" "old run id" "task" "optional task id" "run" {"id" "new run id" "phase" "pending" "harness" "string"}}}
           :status {:group "delegation"
                    :usage "agent status [root-id]"
                    :semantics ["Coordinator dashboard. root-id is a plan or task; no root means active delegation in the workspace."
                                "Tree renders active tasks (closed descendants are excluded), their runs, and nested sub-spawns via parent-of plus spawned-by."
                                "ready lists active tasks delegable right now, matching delegate --ready's successful selection."
                                "awaiting_verification lists active tasks where a worker set status=implemented; closed tasks are already verified."]
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
                    :usage "agent review <target-id> [--roster name | --members n --harness a,b --contract text] [--cwd dir] [--spawned-by run] [--synthesize]"
                    :semantics ["Spawn independent read-only reviewers of the target strand and its subtree; reviewing a plan root reviews the whole feature."
                                "Each reviewer reads strand contracts plus repository state at --cwd. Pass the worktree where the diff lives."
                                "Findings are appended as notes on the target. --synthesize adds a run depending on all reviewers; await it for the verdict."
                                "--roster fans out a named declarative roster (see rosters): one run per entry with its own precise contract and scope, always synthesized. The roster is the one authoritative source of reviewer count, harnesses, and contracts, so --members/--harness/--contract are rejected with it."]
                    :fails ["target not found" "no reviewers" "reviewer missing harness" "unknown roster" "--roster with --members/--harness/--contract"]
                    :returns {"target" "target id" "reviewers" ["run ids"] "synthesizer" "optional run id"}}
           :rosters {:group "memory-review"
                     :usage "agent rosters"
                     :semantics ["List named reviewer rosters registered by trusted config (defroster!)."
                                 "Each roster entry declares name, harness, a precise single-concern contract, and optional scope; review --roster <name> fans it out over a target."
                                 "Workflow composition: skein.spools.agents/roster-review-specs (trusted Clojure) returns the same fan-out as gate-ready specs, sharing one prompt source with the verb."]
                     :returns [{"name" "roster name" "reviewers" [{"name" "string" "harness" "string" "contract" "string" "scope" "optional string"}] "synthesizer" "optional harness override map"}]}
           :council {:group "memory-review"
                     :usage "agent council --topic ... [--members n] [--rounds n] [--harness name] [--synthesizer name] [--cwd dir] [--spawned-by run]"
                     :semantics ["Convene a fresh-blackboard panel: seats deliberate over a shared council strand across --rounds turn-as-run barrier rows, then a synthesizer weighs the whole deliberation."
                                 "--members spawns N identical seats on --harness. Harness has no default: a council with no resolvable harness fails loudly, mirroring delegate."
                                 "The synthesizer runs --synthesizer or the first seat's harness."
                                 "The CLI is scalar-only. Per-seat harness/brief (the :seats vector) is trusted-Clojure / inline-panel territory (skein.spools.agents/council! or panel!), keeping rich structured data out of shell argv."]
                     :fails ["blank topic" "non-positive members or rounds" "no resolvable harness" ":members combined with :seats"]
                     :returns {"council" "shared council strand id" "turns" [["run ids per round"]] "synthesizer" "run id"}}}
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

;; ---------------------------------------------------------------------------
;; Reviewer rosters: named, declarative reviewer fan-out registered by trusted
;; config (the git-reviewable rules file is the source of truth; this registry
;; is its weaver-lifetime, in-band mirror).

(defn- rosters-atom []
  (:rosters (runtime/spool-state (rt) ::state #(hash-map :rosters (atom {})))))

(defn- harness-ref?
  "True for a keyword or non-blank string naming a harness/alias. Existence is
  checked at review time against the shuttle registry, not at registration,
  so roster files may load before config registers aliases."
  [v]
  (or (keyword? v) (non-blank? v)))

;; The roster shape is spec-defined: the spec is the source of truth for
;; structure, and validation consults it. Manual checks below cover only what
;; s/keys cannot express: closed key sets (s/keys maps are open) and
;; cross-entry name uniqueness.
(s/def :skein.spools.agents.roster/name non-blank?)
(s/def :skein.spools.agents.roster/harness harness-ref?)
(s/def :skein.spools.agents.roster/contract non-blank?)
(s/def :skein.spools.agents.roster/scope non-blank?)
(s/def :skein.spools.agents.roster/reviewer
  (s/keys :req-un [:skein.spools.agents.roster/name
                   :skein.spools.agents.roster/harness
                   :skein.spools.agents.roster/contract]
          :opt-un [:skein.spools.agents.roster/scope]))
(s/def :skein.spools.agents.roster/reviewers
  (s/coll-of :skein.spools.agents.roster/reviewer :kind vector? :min-count 1))
(s/def :skein.spools.agents.roster/synthesizer
  (s/keys :req-un [:skein.spools.agents.roster/harness]))
(s/def :skein.spools.agents/roster
  (s/keys :req-un [:skein.spools.agents.roster/reviewers]
          :opt-un [:skein.spools.agents.roster/synthesizer]))

;; roster-review-specs output: the public seam shape workflow authors consume.
(s/def :skein.spools.agents.review-specs/prompt non-blank?)
(s/def :skein.spools.agents.review-specs/attrs (s/map-of string? string?))
(s/def :skein.spools.agents.review-specs/reviewer
  (s/keys :req-un [:skein.spools.agents.roster/name
                   :skein.spools.agents.roster/harness
                   :skein.spools.agents.review-specs/prompt
                   :skein.spools.agents.review-specs/attrs]))
(s/def :skein.spools.agents.review-specs/reviewers
  (s/coll-of :skein.spools.agents.review-specs/reviewer :kind vector? :min-count 1))
(s/def :skein.spools.agents.review-specs/synthesizer :skein.spools.agents.review-specs/reviewer)
(s/def :skein.spools.agents.review-specs/roster keyword?)
(s/def :skein.spools.agents.review-specs/target non-blank?)
(s/def :skein.spools.agents.review-specs/review-pass non-blank?)
(s/def :skein.spools.agents/review-specs
  (s/keys :req-un [:skein.spools.agents.review-specs/roster
                   :skein.spools.agents.review-specs/target
                   :skein.spools.agents.review-specs/review-pass
                   :skein.spools.agents.review-specs/reviewers
                   :skein.spools.agents.review-specs/synthesizer]))

(def ^:private reviewer-entry-keys #{:name :harness :contract :scope})
(def ^:private roster-keys #{:reviewers :synthesizer})
(def ^:private synthesizer-keys #{:harness})

(defn- validate-roster!
  "Validate roster data: closed key sets first — a typo'd key diagnoses far
  better as \"unknown keys [:contarct]\" than as the missing-`:contract` spec
  explain it also causes — then the :skein.spools.agents/roster spec for
  structure, then reviewer-name uniqueness. Key checks are guarded on map
  shape so non-map garbage still falls through to the spec failure. Returns
  the roster."
  [roster-id roster]
  (when (map? roster)
    (when-let [unknown (seq (remove roster-keys (keys roster)))]
      (fail! "Roster has unknown keys"
             {:roster roster-id :unknown (vec unknown) :allowed (sort roster-keys)}))
    (when (sequential? (:reviewers roster))
      (doseq [entry (:reviewers roster)
              :when (map? entry)]
        (when-let [unknown (seq (remove reviewer-entry-keys (keys entry)))]
          (fail! "Roster reviewer entry has unknown keys"
                 {:roster roster-id :entry entry :unknown (vec unknown) :allowed (sort reviewer-entry-keys)}))))
    (let [synthesizer (:synthesizer roster)]
      (when (map? synthesizer)
        (when-let [unknown (seq (remove synthesizer-keys (keys synthesizer)))]
          (fail! "Roster :synthesizer has unknown keys"
                 {:roster roster-id :unknown (vec unknown) :allowed (sort synthesizer-keys)})))))
  (when-not (s/valid? :skein.spools.agents/roster roster)
    (fail! "Roster does not conform to spec"
           {:roster roster-id
            :spec :skein.spools.agents/roster
            :explain (s/explain-str :skein.spools.agents/roster roster)}))
  (let [names (mapv :name (:reviewers roster))]
    (when-not (apply distinct? names)
      (fail! "Roster reviewer :name values must be unique" {:roster roster-id :names names})))
  roster)

(defn- roster-key [roster-name]
  (cond
    (keyword? roster-name) roster-name
    (non-blank? roster-name) (keyword roster-name)
    :else (fail! "Roster name must be a keyword or non-blank string" {:roster roster-name})))

(defn defroster!
  "Register or replace a named reviewer roster (weaver-lifetime state, so
  trusted startup config re-registers it like harness aliases and queries).

  Roster data is plain and spec-defined — see `:skein.spools.agents/roster`:
  `{:reviewers [{:name :harness :contract :scope?} ...] :synthesizer
  {:harness ...}?}`. Each reviewer is one independent read-only review run
  with its own precise contract; `:scope` is prompt-level confinement text.
  `:synthesizer` overrides the harness of the synthesis run (default: first
  reviewer's harness). Malformed data fails loudly with spec explain data."
  [roster-name roster]
  (let [roster-id (roster-key roster-name)]
    (validate-roster! roster-id roster)
    (swap! (rosters-atom) assoc roster-id roster)
    {:roster roster-id :reviewers (count (:reviewers roster))}))

(defn rosters
  "List registered reviewer rosters as full plain data."
  []
  (mapv (fn [[key roster]] (assoc roster :name key))
        (sort-by key @(rosters-atom))))

(defn- resolve-roster! [roster-name]
  (let [key (roster-key roster-name)]
    (or (get @(rosters-atom) key)
        (fail! "Roster not found" {:roster key :available (sort (keys @(rosters-atom)))}))))

;; ---------------------------------------------------------------------------
;; Blackboard prompt protocol (PLAN-Pnl-001.A6)
;;
;; One namespace-internal set of prompt fragments shared by the review/roster
;; presets and (later) the panel compiler. Each fragment emits one instruction
;; block from plain data; a caller assembles the blocks it needs. Every
;; fragment supports two prompt FORMS: `:full` spells the whole brief for a
;; fresh process, while `:continuation` restates only the coordinates a resumed
;; session cannot infer (turn/round + where the board is) — the session already
;; carries the brief, so a continuation never re-hands context it holds. A
;; fragment whose text does not vary by form ignores `:form`; one that has no
;; valid continuation fails loudly rather than emitting a silent default.

(defn- seat-identity-fragment
  "Identity line locating a seat in the panel grid: seat k of N on turn r of R.
  `:continuation` restates the same coordinates for a resumed seat that already
  knows it is a panellist."
  [{:keys [form seat seats turn turns] :or {form :full}}]
  (case form
    :full (str "You are seat " seat " of " seats ", turn " turn " of " turns ".\n")
    :continuation (str "Continuing as seat " seat " of " seats
                       ", now on turn " turn " of " turns ".\n")))

(defn- read-the-board-fragment
  "Instruction for reading the shared blackboard. `:view :strand` reads the
  strand plus repository state (a reviewer inspecting a target); `:view :notes`
  reads the accumulated peer posts (a deliberating seat). `:continuation` is a
  terse pointer for a session that already knows the protocol."
  [{:keys [form view board-id] :or {form :full view :strand}}]
  (let [cmd (shuttle/pinned-strand-command)]
    (case view
      :strand (case form
                :full (str "Read the target with `" cmd " show " board-id
                           "` and inspect the relevant repository state.\n")
                :continuation (str "The board is strand " board-id
                                   "; re-read it with `" cmd " show " board-id "`.\n"))
      :notes (case form
               :full (str "Read the board with `" cmd " agent notes " board-id
                          "` to see what peers have posted.\n")
               :continuation (str "The board is strand " board-id
                                  "; read new posts with `" cmd " agent notes " board-id "`.\n")))))

(defn- post-with-tag-fragment
  "Instruction for appending a tagged contribution to the board strand. The tag
  keeps one pass/round separable on a strand that accumulates notes across
  rounds; a nil tag omits the prefix. `:lead`/`:label`/`:placeholder` frame the
  post for the caller (reviewers post `When finished, append findings with`;
  deliberating seats post their turn's position). The posting command is the
  same whether the process is fresh or resumed, so this fragment ignores
  `:form`."
  [{:keys [board-id tag lead label placeholder]
    :or {lead "When finished" label "append findings with" placeholder "<findings>"}}]
  (let [cmd (shuttle/pinned-strand-command)
        tag-prefix (if tag (str "[" tag "] ") "")]
    (str lead ", " label ": " cmd " agent note " board-id
         " \"" tag-prefix placeholder "\" --by <your run-id>\n")))

(defn- independence-fragment
  "Review-style directive: reach an independent judgment without coordinating
  with peers. Independent seats are single-turn (round one only) and never
  resume, so a continuation form is a contradiction and fails loudly."
  [{:keys [form] :or {form :full}}]
  (case form
    :full "Work independently: reach your own judgment from the target and repository state alone; do not read or coordinate with other seats.\n"
    :continuation (fail! "independence directive has no continuation form: independent seats never resume"
                         {:form form})))

(defn- deliberation-fragment
  "Multi-round directive: read peers' posts from the previous turn, then rebut
  or refine. `:continuation` names the concrete previous turn, so it requires an
  integer `:turn` and fails loudly without one rather than emitting a blank
  round reference."
  [{:keys [form turn] :or {form :full}}]
  (case form
    :full "Read every peer's post from the previous turn on the board, then rebut or refine your position in light of them.\n"
    :continuation (do (when-not (integer? turn)
                        (fail! "deliberation continuation requires an integer :turn" {:turn turn}))
                      (str "Read peers' turn " (dec turn)
                           " posts on the board, then rebut or refine.\n"))))

;; `review-prompt` reproduces the read-the-board and post-with-tag fragments
;; byte-for-byte (the frozen roster tests are the compatibility proof); the
;; review-specific framing (target subtree, focus, scope, notes-only discipline)
;; stays inline because it is not part of the shared blackboard vocabulary.
(defn- review-prompt [{:keys [target-id focus contract scope note-tag]}]
  (str contract "\n\n"
       "Review target strand " target-id " and its subtree.\n"
       (when (not (str/blank? focus))
         (str "Focus: " focus "\n"))
       (when (not (str/blank? scope))
         (str "Scope: confine this review to " scope "\n"))
       (read-the-board-fragment {:view :strand :board-id target-id})
       (post-with-tag-fragment {:board-id target-id :tag note-tag})
       "Findings are notes-only; do not write verdict attributes."))

(defn- review-synthesis-prompt [{:keys [target-id review-runs contract note-tag]}]
  (let [cmd (shuttle/pinned-strand-command)]
    (str contract "\n\n"
         "Synthesize independent review findings for target strand " target-id ".\n"
         ;; a target accumulates notes across rounds; the pass tag is the
         ;; discriminator that exists before any run does, so workflow-composed
         ;; synthesis can isolate its own round too
         (when note-tag
           (str "This review pass is tagged [" note-tag "]: reviewers prefixed their notes with it;"
                " synthesize those notes and ignore other rounds.\n"))
         (when (seq review-runs)
           (str "Review run ids: " (str/join ", " review-runs) "\n"))
         "Read target notes with `" cmd " agent notes " target-id "`, append one synthesis note with `"
         cmd " agent note " target-id " \"<synthesis>\" --by <your run-id>`, then finish with the synthesis.")))

(defn roster-review-specs
  "Return a roster's review fan-out as plain, fully-built run specs
  (shape: `:skein.spools.agents/review-specs`).

  This is the one prompt-building source for roster reviews. `review!` spawns
  shuttle runs from these specs, and workflow authors map them onto
  `:subagent` gates without re-implementing the contract layering: `:harness`
  and `:prompt` become the gate's `shuttle/harness`/`shuttle/prompt`,
  `:attrs` merge into the gate's attributes, and the synthesizer gate
  depends on every reviewer gate. Specs are pure data built from the
  roster and the workspace base review contract; `:target` is the strand id
  under review (existence is checked where runs are spawned, not here).
  Unknown rosters and a blank target fail loudly.

  `roster` is a registered roster name **or an inline roster value** — a map
  conforming to `:skein.spools.agents/roster`, validated identically to
  `defroster!` input. Inline values are how parameterised compositions work:
  rosters are plain data, so pour-time code may filter, augment, or construct
  one and hand it straight to this seam (specs/attrs label it `:inline`;
  register under a name when attribution matters).

  Every call mints a `:review-pass` tag (override with a non-blank
  `:review-id`): reviewers prefix their notes with it and the synthesizer
  filters on it, so one pass's findings stay separable on a target that
  accumulates notes across rounds — run ids cannot serve here because
  workflow-composed synthesis is defined before any run exists."
  [roster {:keys [target review-id]}]
  (when-not (non-blank? target)
    (fail! "roster-review-specs requires a non-blank :target" {:roster roster :target target}))
  (when (and (some? review-id) (not (non-blank? review-id)))
    (fail! "roster-review-specs :review-id must be a non-blank string when present"
           {:roster roster :review-id review-id}))
  (let [[roster-id roster-def] (if (map? roster)
                                 [:inline (validate-roster! :inline roster)]
                                 (let [id (roster-key roster)]
                                   [id (resolve-roster! id)]))
        base (shuttle/default-review-contract-text)
        pass-id (or review-id
                    (str (name roster-id) "-" (subs (str (java.util.UUID/randomUUID)) 0 8)))
        shared-attrs {"shuttle/review-target" target
                      "shuttle/review-roster" (name roster-id)
                      "shuttle/review-pass" pass-id}]
    {:roster roster-id
     :target target
     :review-pass pass-id
     :reviewers (mapv (fn [{entry-name :name :keys [harness contract scope]}]
                        {:name entry-name
                         :harness harness
                         :prompt (review-prompt {:target-id target
                                                 :focus entry-name
                                                 :contract (str base "\n\n[reviewer: " entry-name "]\n" contract)
                                                 :scope scope
                                                 :note-tag pass-id})
                         :attrs (assoc shared-attrs "shuttle/review-focus" entry-name)})
                      (:reviewers roster-def))
     ;; the synthesizer weighs findings roster-independently, so it receives
     ;; the base contract, never a per-reviewer one
     :synthesizer {:name "synthesis"
                   :harness (or (get-in roster-def [:synthesizer :harness])
                                (:harness (first (:reviewers roster-def))))
                   :prompt (review-synthesis-prompt {:target-id target :contract base :note-tag pass-id})
                   :attrs (assoc shared-attrs "shuttle/review-synthesis" "true")}}))

;; ---------------------------------------------------------------------------
;; Panel primitive (PLAN-Pnl-001.A4/A5)
;;
;; A panel is plain, spec'd data — seats deliberating over a blackboard across
;; turns, compiled to run specs by `panel-specs` and spawned by `panel!`. There
;; is no panel registry (A5): panels are inline values or preset-derived. The
;; compiler embeds the board id in every prompt; for a `:fresh` blackboard the
;; board strand does not exist until `panel!` mints it, so the specs carry a
;; placeholder token that the spawner substitutes for the minted id.

(def ^:private board-placeholder
  "Sentinel the compiler embeds where a `:fresh` blackboard's id will land; the
  spawner replaces it with the minted board strand id in every prompt and attr."
  "«panel-board»")

;; The panel shape is spec-defined; validation consults the spec. Manual checks
;; cover only what s/keys cannot: closed key sets and seat-name uniqueness.
(s/def :skein.spools.agents.panel.seat/name non-blank?)
(s/def :skein.spools.agents.panel.seat/harness harness-ref?)
(s/def :skein.spools.agents.panel.seat/brief non-blank?)
(s/def :skein.spools.agents.panel.seat/scope non-blank?)
(s/def :skein.spools.agents.panel.seat/continuity #{:fresh :resume})
(s/def :skein.spools.agents.panel/seat
  (s/keys :req-un [:skein.spools.agents.panel.seat/name
                   :skein.spools.agents.panel.seat/harness
                   :skein.spools.agents.panel.seat/brief]
          :opt-un [:skein.spools.agents.panel.seat/scope
                   :skein.spools.agents.panel.seat/continuity]))
(s/def :skein.spools.agents.panel/seats
  (s/coll-of :skein.spools.agents.panel/seat :kind vector? :min-count 1))
(s/def :skein.spools.agents.panel.turns/rounds pos-int?)
(s/def :skein.spools.agents.panel/turns
  (s/keys :req-un [:skein.spools.agents.panel.turns/rounds]))
(s/def :skein.spools.agents.panel/blackboard #{:target :fresh})
(s/def :skein.spools.agents.panel.synthesis/harness harness-ref?)
(s/def :skein.spools.agents.panel.synthesis/brief non-blank?)
(s/def :skein.spools.agents.panel/synthesis
  (s/or :none #{:none}
        :spec (s/keys :req-un [:skein.spools.agents.panel.synthesis/harness]
                      :opt-un [:skein.spools.agents.panel.synthesis/brief])))
(s/def :skein.spools.agents/panel
  (s/keys :req-un [:skein.spools.agents.panel/seats]
          :opt-un [:skein.spools.agents.panel/turns
                   :skein.spools.agents.panel/blackboard
                   :skein.spools.agents.panel/synthesis]))

;; panel-specs output: the compiled run-spec shape spawners and gate authors
;; consume. Turns are ordered rows (turn 1 first); a run spec carries both
;; prompt forms plus, for a resuming turn, the seat index it continues.
(s/def :skein.spools.agents.panel-specs/name non-blank?)
(s/def :skein.spools.agents.panel-specs/harness harness-ref?)
(s/def :skein.spools.agents.panel-specs/prompt non-blank?)
(s/def :skein.spools.agents.panel-specs/resume-prompt non-blank?)
(s/def :skein.spools.agents.panel-specs/attrs (s/map-of string? string?))
(s/def :skein.spools.agents.panel-specs/resume-ref nat-int?)
(s/def :skein.spools.agents.panel-specs/run
  (s/keys :req-un [:skein.spools.agents.panel-specs/name
                   :skein.spools.agents.panel-specs/harness
                   :skein.spools.agents.panel-specs/prompt
                   :skein.spools.agents.panel-specs/attrs]
          :opt-un [:skein.spools.agents.panel-specs/resume-prompt
                   :skein.spools.agents.panel-specs/resume-ref]))
(s/def :skein.spools.agents.panel-specs/turn
  (s/coll-of :skein.spools.agents.panel-specs/run :kind vector? :min-count 1))
(s/def :skein.spools.agents.panel-specs/turns
  (s/coll-of :skein.spools.agents.panel-specs/turn :kind vector? :min-count 1))
(s/def :skein.spools.agents.panel-specs.blackboard/kind #{:target :fresh})
(s/def :skein.spools.agents.panel-specs.blackboard/id non-blank?)
;; `:id` is not blanket-optional: a `:target` directive must carry the board
;; strand id it deliberates over, and a `:fresh` directive must omit it (the id
;; does not exist until the spawner mints the board), so consumers of this seam
;; can trust the directive shape rather than re-checking kind/id agreement.
(s/def :skein.spools.agents.panel-specs/blackboard
  (s/or :target (s/and #(= :target (:kind %))
                       (s/keys :req-un [:skein.spools.agents.panel-specs.blackboard/kind
                                        :skein.spools.agents.panel-specs.blackboard/id]))
        :fresh (s/and #(= :fresh (:kind %))
                      #(not (contains? % :id))
                      (s/keys :req-un [:skein.spools.agents.panel-specs.blackboard/kind]))))
(s/def :skein.spools.agents.panel-specs/review-pass non-blank?)
(s/def :skein.spools.agents.panel-specs/synthesizer :skein.spools.agents.panel-specs/run)
(s/def :skein.spools.agents/panel-specs
  (s/keys :req-un [:skein.spools.agents.panel-specs/blackboard
                   :skein.spools.agents.panel-specs/review-pass
                   :skein.spools.agents.panel-specs/turns]
          :opt-un [:skein.spools.agents.panel-specs/synthesizer]))

(def ^:private panel-keys #{:seats :turns :blackboard :synthesis})
(def ^:private panel-seat-keys #{:name :harness :brief :scope :continuity})
(def ^:private panel-turns-keys #{:rounds})
(def ^:private panel-synthesis-keys #{:harness :brief})

(defn- validate-panel!
  "Validate panel data: closed key sets first — so a typo'd key diagnoses as
  \"unknown keys\" rather than as the missing-key spec explain it also causes —
  then the :skein.spools.agents/panel spec for structure, then seat-name
  uniqueness. Key checks are guarded on map shape so non-map garbage falls
  through to the spec failure. Returns the panel."
  [panel-id panel]
  (when (map? panel)
    (when-let [unknown (seq (remove panel-keys (keys panel)))]
      (fail! "Panel has unknown keys"
             {:panel panel-id :unknown (vec unknown) :allowed (sort panel-keys)}))
    (when (sequential? (:seats panel))
      (doseq [seat (:seats panel)
              :when (map? seat)]
        (when-let [unknown (seq (remove panel-seat-keys (keys seat)))]
          (fail! "Panel seat has unknown keys"
                 {:panel panel-id :seat seat :unknown (vec unknown) :allowed (sort panel-seat-keys)}))))
    (when (map? (:turns panel))
      (when-let [unknown (seq (remove panel-turns-keys (keys (:turns panel))))]
        (fail! "Panel :turns has unknown keys"
               {:panel panel-id :unknown (vec unknown) :allowed (sort panel-turns-keys)})))
    (when (map? (:synthesis panel))
      (when-let [unknown (seq (remove panel-synthesis-keys (keys (:synthesis panel))))]
        (fail! "Panel :synthesis has unknown keys"
               {:panel panel-id :unknown (vec unknown) :allowed (sort panel-synthesis-keys)}))))
  (when-not (s/valid? :skein.spools.agents/panel panel)
    (fail! "Panel does not conform to spec"
           {:panel panel-id
            :spec :skein.spools.agents/panel
            :explain (s/explain-str :skein.spools.agents/panel panel)}))
  (let [names (mapv :name (:seats panel))]
    (when-not (apply distinct? names)
      (fail! "Panel seat :name values must be unique" {:panel panel-id :names names})))
  panel)

;; The seat prompt reuses the shared blackboard fragments (A6). The directive is
;; derived from the turn grid: a single-round panel is independent (review
;; shape); a multi-round panel opens on turn 1 and deliberates thereafter,
;; reading peers' previous turn. The board is read as a subject strand for a
;; `:target` blackboard and as accumulated peer posts for a `:fresh` one.
(defn- panel-seat-prompt
  [{:keys [form seat seats turn turns brief scope board-id view directive note-tag]
    :or {form :full}}]
  (let [post (post-with-tag-fragment {:board-id board-id :tag note-tag
                                      :lead "When you have a position for this turn"
                                      :label "post it with" :placeholder "<your position>"})
        read-board? (or (= view :strand) (= directive :deliberate))]
    (case form
      :full (str brief "\n\n"
                 (seat-identity-fragment {:form :full :seat seat :seats seats :turn turn :turns turns})
                 (when (non-blank? scope) (str "Scope: confine your work to " scope "\n"))
                 (case directive
                   :independent (independence-fragment {})
                   :deliberate (deliberation-fragment {})
                   :open "")
                 (when read-board? (read-the-board-fragment {:form :full :view view :board-id board-id}))
                 post)
      :continuation (str (seat-identity-fragment {:form :continuation :seat seat :seats seats :turn turn :turns turns})
                         (deliberation-fragment {:form :continuation :turn turn})
                         (read-the-board-fragment {:form :continuation :view view :board-id board-id})
                         post))))

(defn- panel-synthesis-prompt
  [{:keys [board-id brief note-tag]}]
  (let [cmd (shuttle/pinned-strand-command)]
    (str (when (non-blank? brief) (str brief "\n\n"))
         "Synthesize the panel deliberation on strand " board-id ".\n"
         (when note-tag
           (str "This panel pass is tagged [" note-tag "]: seats prefixed their posts with it;"
                " synthesize those posts and ignore other passes.\n"))
         "Read the board with `" cmd " agent notes " board-id "`, append one synthesis note with `"
         cmd " agent note " board-id " \"<synthesis>\" --by <your run-id>`, then finish with the synthesis.")))

(defn panel-specs
  "Compile an **inline panel value** into plain, fully-built run specs
  (shape: `:skein.spools.agents/panel-specs`). This is the one prompt-building
  source for panels; `panel!` spawns runs from these specs.

  `panel` is a map conforming to `:skein.spools.agents/panel`, validated
  identically to `panel!` input (closed keys and uniqueness before spec
  conform). Defaults are applied here: `:turns {:rounds 1}`, `:blackboard
  :target`, per-seat `:continuity :fresh`.

  Options: `:target` is the blackboard strand id for a `:target` panel (a
  blank target fails loudly); a `:fresh` panel ignores it and embeds a board
  placeholder the spawner resolves after minting. `:review-id` overrides the
  minted `:review-pass` tag (a blank override fails loudly).

  Output `:turns` is a vector of turn rows (turn 1 first); each run spec is
  `{:name :harness :prompt :resume-prompt? :attrs :resume-ref?}` where
  `:resume-ref` is the seat index in the previous row this turn continues (only
  present for a `:continuity :resume` turn r>1). Every run spec stamps
  `shuttle/panel-seat`, `shuttle/panel-turn`, `shuttle/review-target`, and
  `shuttle/review-pass`. `:synthesizer` is present unless `:synthesis` is
  absent or `:none`."
  [panel {:keys [target review-id]}]
  (when (and (some? review-id) (not (non-blank? review-id)))
    (fail! "panel-specs :review-id must be a non-blank string when present"
           {:review-id review-id}))
  (validate-panel! :inline panel)
  (let [seats (:seats panel)
        seat-count (count seats)
        rounds (get-in panel [:turns :rounds] 1)
        blackboard (get panel :blackboard :target)
        synthesis (:synthesis panel)
        view (case blackboard :target :strand :fresh :notes)
        ;; a :target board is read as the subject strand (`show`), never as
        ;; accumulated peer posts, so a deliberation turn on it could never tell
        ;; a seat how to read peers — reject the combination loudly rather than
        ;; emit a prompt that references peer turns it cannot fetch. Multi-round
        ;; deliberation belongs on a :fresh board (peers post and read notes).
        _ (when (and (= :target blackboard) (> rounds 1))
            (fail! "panel :blackboard :target supports a single round only: seats read a target via `show`, not peer posts via `notes`; use :blackboard :fresh for multi-round deliberation"
                   {:blackboard blackboard :rounds rounds}))
        board-ref (case blackboard
                    :target (if (non-blank? target)
                              target
                              (fail! "panel-specs :blackboard :target requires a non-blank :target"
                                     {:target target}))
                    :fresh board-placeholder)
        pass-id (or review-id (str "panel-" (subs (str (java.util.UUID/randomUUID)) 0 8)))
        row-specs (fn [turn]
                    (mapv (fn [idx {seat-name :name :keys [harness brief scope continuity]
                                    :or {continuity :fresh}}]
                            (let [directive (cond (= 1 rounds) :independent
                                                  (= 1 turn) :open
                                                  :else :deliberate)
                                  attrs {"shuttle/review-target" board-ref
                                         "shuttle/review-pass" pass-id
                                         "shuttle/panel-seat" seat-name
                                         "shuttle/panel-turn" (str turn)}]
                              (cond-> {:name seat-name
                                       :harness harness
                                       :prompt (panel-seat-prompt {:form :full :seat (inc idx) :seats seat-count
                                                                   :turn turn :turns rounds :brief brief :scope scope
                                                                   :board-id board-ref :view view
                                                                   :directive directive :note-tag pass-id})
                                       :attrs attrs}
                                ;; turn>1 is always a deliberation turn, so it
                                ;; carries a valid continuation form; the resume
                                ;; ref is only threaded for a :resume seat
                                (> turn 1) (assoc :resume-prompt
                                                  (panel-seat-prompt {:form :continuation :seat (inc idx) :seats seat-count
                                                                      :turn turn :turns rounds :board-id board-ref
                                                                      :view view :note-tag pass-id}))
                                (and (> turn 1) (= continuity :resume)) (assoc :resume-ref idx))))
                          (range) seats))
        turns (mapv row-specs (range 1 (inc rounds)))]
    (cond-> {:blackboard (case blackboard
                           :target {:kind :target :id target}
                           :fresh {:kind :fresh})
             :review-pass pass-id
             :turns turns}
      (and synthesis (not= :none synthesis))
      (assoc :synthesizer
             {:name "synthesis"
              :harness (:harness synthesis)
              :prompt (panel-synthesis-prompt {:board-id board-ref :brief (:brief synthesis) :note-tag pass-id})
              :attrs {"shuttle/review-target" board-ref
                      "shuttle/review-pass" pass-id
                      "shuttle/review-synthesis" "true"}}))))

(defn panel!
  "Spawn a panel from an inline panel value.

  Compiles `panel` with `panel-specs`, then resolves the blackboard: a
  `:target` panel deliberates over the supplied `:target` strand; a `:fresh`
  panel mints a new shared board strand (role `panel`) and substitutes it for
  the compiler's board placeholder in every prompt and attr. Each turn row is
  spawned as one run per seat, wiring a `depends-on` barrier on every run of
  the previous row. A `:continuity :resume` seat additionally threads its turn
  r>1 run onto its previous turn's run via spawn `:resume` — because a session
  cannot be resumed before it exists, a row containing a resuming seat is
  spawned only after `panel!` awaits the previous row to completion (so
  `:fresh`-continuity rounds spawn upfront behind barriers, while `:resume`
  rounds block prior rounds). The synthesizer, when the panel declares one,
  depends on the final turn row.

  Options: `:target` (required for a `:target` blackboard), `:review-id`,
  `:spawned-by`, `:cwd`. Returns `{:panel :blackboard :turns [[run-ids...]...]
  :synthesizer? :review-pass}`."
  [panel {:keys [target review-id spawned-by cwd]}]
  (let [specs (panel-specs panel {:target target :review-id review-id})
        board-id (case (get-in specs [:blackboard :kind])
                   :target (get-in specs [:blackboard :id])
                   :fresh (:id (api/add (rt) {:title (truncate (str "Panel: " (:name (first (first (:turns specs))))) 72)
                                              :attributes (cond-> {"shuttle/role" "panel"
                                                                   "shuttle/review-pass" (:review-pass specs)}
                                                            spawned-by (assoc "shuttle/spawned-by" spawned-by))})))
        resolve-board (fn [s] (str/replace (str s) board-placeholder board-id))
        spawn-run (fn [spec resume-run extra]
                    (let [;; a resuming turn launches on the short continuation
                          ;; prompt; stamp its full-brief form as
                          ;; shuttle/fresh-prompt so `retry --fresh` has a
                          ;; durable cold-start prompt (a fresh process must
                          ;; never be handed the continuation, PLAN-Pnl-001.A6)
                          attrs (cond-> (into {} (map (fn [[k v]] [k (resolve-board v)])) (:attrs spec))
                                  resume-run (assoc "shuttle/fresh-prompt" (resolve-board (:prompt spec))))]
                      (shuttle/spawn-run!
                       (cond-> (merge {:harness (:harness spec)
                                       :prompt (resolve-board (if resume-run (:resume-prompt spec) (:prompt spec)))
                                       :parent board-id
                                       :attrs attrs}
                                      extra)
                         spawned-by (assoc :spawned-by spawned-by)
                         cwd (assoc :cwd cwd)
                         resume-run (assoc :resume (:id resume-run))))))
        turn-runs (loop [idx 0 prev nil acc []]
                    (if (< idx (count (:turns specs)))
                      (let [row (nth (:turns specs) idx)]
                        ;; a resuming row needs its predecessors' sessions
                        ;; captured, so barrier-await the previous row first
                        (when (and (pos? idx) (some :resume-ref row))
                          (shuttle/await-runs (mapv :id prev) {}))
                        (let [runs (mapv (fn [spec]
                                           (let [resume-run (when-let [ri (:resume-ref spec)] (nth prev ri))]
                                             (spawn-run spec resume-run
                                                        {:title (truncate (str "Panel " (:name spec)
                                                                               " turn " (inc idx)) 72)
                                                         :depends-on (mapv :id prev)})))
                                         row)]
                          (recur (inc idx) runs (conj acc runs))))
                      acc))
        synth (when-let [sspec (:synthesizer specs)]
                (spawn-run sspec nil {:title (truncate (str "Panel synthesis: " board-id) 72)
                                      :depends-on (mapv :id (last turn-runs))}))]
    (cond-> {:panel panel
             :blackboard board-id
             :turns (mapv (fn [runs] (mapv :id runs)) turn-runs)
             :review-pass (:review-pass specs)}
      synth (assoc :synthesizer (:id synth)))))

(defn roster->panel
  "Convert a roster value into an equivalent single-round, target-blackboard
  panel: each reviewer becomes an independent seat whose contract is the seat
  brief, and the roster synthesizer (or the first reviewer's harness) becomes
  the panel synthesis. Pure — the roster is validated identically to
  `defroster!` input. A rounds=1 panel compiles to the independent review
  shape, so this is how `review!` is expressible over the panel primitive."
  [roster]
  (validate-roster! :inline roster)
  (let [reviewers (:reviewers roster)]
    {:seats (mapv (fn [{seat-name :name :keys [harness contract scope]}]
                    (cond-> {:name seat-name :harness harness :brief contract :continuity :fresh}
                      (non-blank? scope) (assoc :scope scope)))
                  reviewers)
     :turns {:rounds 1}
     :blackboard :target
     :synthesis {:harness (or (get-in roster [:synthesizer :harness])
                              (:harness (first reviewers)))}}))

(defn review!
  "Spawn independent read-only reviewers for a target strand.

  `:roster` names a `defroster!` roster (or is an inline
  `:skein.spools.agents/roster` value) and is the one authoritative source of
  reviewer count, harnesses, and contracts for that review: combining it with
  `:reviewers`, `:members`, `:harnesses`, or `:contract` fails loudly. A
  roster review always synthesizes, from the same `roster-review-specs` data
  a workflow composition would consume."
  [target-id {:keys [reviewers members harnesses contract synthesize? spawned-by cwd roster]
              :or {members 2}
              :as opts}]
  (when-not (api/show (rt) target-id)
    (fail! "Review target strand not found" {:id target-id}))
  (when roster
    (when-let [conflicts (seq (filter #(contains? opts %) [:reviewers :members :harnesses :contract]))]
      (fail! "Roster review takes reviewer count, harnesses, and contracts from the roster"
             {:roster roster :conflicts (vec conflicts)
              :cli-flags (vec (keep {:members "--members" :harnesses "--harness" :contract "--contract"}
                                    conflicts))})))
  (let [roster-specs (when roster (roster-review-specs roster {:target target-id}))
        contract (or contract (shuttle/default-review-contract-text))
        reviewer-specs
        (or (:reviewers roster-specs)
            (let [reviewers (or reviewers
                                (let [hs (or (seq harnesses) [:claude])]
                                  (mapv (fn [idx]
                                          {:harness (nth hs (mod idx (count hs)))
                                           :focus (str "review pass " (inc idx))})
                                        (range members))))]
              (when-not (and (vector? reviewers) (seq reviewers))
                (fail! "Review requires at least one reviewer" {:reviewers reviewers}))
              (mapv (fn [{:keys [harness focus] :as reviewer}]
                      {:name focus
                       :harness (or harness (fail! "Review reviewer requires :harness" {:reviewer {:focus focus}}))
                       :prompt (review-prompt {:target-id target-id :focus focus
                                               :contract (or (:contract reviewer) contract)
                                               :scope (:scope reviewer)})
                       :attrs {"shuttle/review-target" target-id
                               "shuttle/review-focus" (or focus "")}})
                    reviewers)))
        synthesize? (or synthesize? (some? roster-specs))
        spawn-spec! (fn [spec extra]
                      (shuttle/spawn-run!
                       (merge {:harness (:harness spec)
                               :prompt (:prompt spec)
                               :parent target-id
                               :spawned-by spawned-by
                               :cwd cwd
                               :attrs (:attrs spec)}
                              extra)))
        review-runs (mapv (fn [{spec-name :name :as spec}]
                            (spawn-spec! spec {:title (truncate (str "Review " target-id
                                                                     (when spec-name (str ": " spec-name)))
                                                                72)}))
                          reviewer-specs)
        synthesis (when synthesize?
                    (spawn-spec! (or (:synthesizer roster-specs)
                                     {:harness (:harness (first reviewer-specs))
                                      :prompt (review-synthesis-prompt {:target-id target-id
                                                                        :review-runs (mapv :id review-runs)
                                                                        :contract contract})
                                      :attrs {"shuttle/review-target" target-id
                                              "shuttle/review-synthesis" "true"}})
                                 {:title (truncate (str "Review synthesis: " target-id) 72)
                                  :depends-on (mapv :id review-runs)}))]
    (cond-> {:target target-id :reviewers (mapv :id review-runs)}
      roster-specs (assoc :review-pass (:review-pass roster-specs))
      synthesis (assoc :synthesizer (:id synthesis)))))

(defn- council-seat-brief
  "A council seat's subject brief: the shared topic plus an optional per-seat
  perspective. `panel-seat-prompt` frames it with seat identity, the
  deliberation directive, and the blackboard protocol, so the poll-loop
  choreography councils used to spell out is gone — the panel compiler owns it."
  [topic perspective]
  (str "Deliberate on this topic with the other council members, working toward a considered position:\n"
       topic
       (when (non-blank? perspective)
         (str "\n\nYour assigned perspective: " perspective))))

(defn council!
  "Convene a multi-agent council as a `:fresh`-blackboard panel (A7): its rounds
  are turn-as-run barrier rows and seats deliberate by posting to and reading a
  shared council strand across turns, then a synthesizer weighs the whole
  deliberation.

  Scalar convenience: `:members n` mints N identical seats, each running the
  council-wide `:harness`. Rich control: `:seats [{:name :harness? :brief?}]`
  gives per-seat harness and perspective; `:members` and `:seats` are mutually
  exclusive. Harness has no default (mirroring `delegate`) — a seat with neither
  its own `:harness` nor a council-wide `:harness` fails loudly. The synthesizer
  runs `:synthesizer` (a harness) or the first seat's harness. `:rounds` (default
  2) is the turn count; `:spawned-by` and `:cwd` ride onto every run.

  Returns `{:council <shared strand id> :turns [[run-ids]...] :synthesizer
  <run id>}`."
  [topic {:keys [harness members rounds seats synthesizer spawned-by cwd]
          :or {members 2 rounds 2}
          :as opts}]
  (when (str/blank? topic)
    (fail! "Council topic must be non-blank" {}))
  (when (and (contains? opts :seats) (contains? opts :members))
    (fail! "Council takes :members (scalar) or :seats (per-seat), not both"
           {:members members :seats seats}))
  (when-not (pos-int? rounds)
    (fail! "Council :rounds must be a positive integer" {:rounds rounds}))
  (let [resolve-harness (fn [seat-harness]
                          (or seat-harness harness
                              (fail! "Council seat requires a harness: pass a council-wide :harness or a per-seat :harness"
                                     {:topic topic})))
        panel-seats
        (if (contains? opts :seats)
          (do (when-not (and (vector? seats) (seq seats))
                (fail! "Council :seats must be a non-empty vector" {:seats seats}))
              (mapv (fn [{seat-name :name seat-harness :harness :keys [brief]}]
                      {:name (if (non-blank? seat-name)
                               seat-name
                               (fail! "Council seat requires a non-blank :name" {:seat {:brief brief}}))
                       :harness (resolve-harness seat-harness)
                       :brief (council-seat-brief topic brief)})
                    seats))
          (do (when-not (pos-int? members)
                (fail! "Council :members must be a positive integer" {:members members}))
              (mapv (fn [n]
                      {:name (str "member-" n)
                       :harness (resolve-harness nil)
                       :brief (council-seat-brief topic nil)})
                    (range 1 (inc members)))))
        panel {:seats panel-seats
               :turns {:rounds rounds}
               :blackboard :fresh
               :synthesis {:harness (or synthesizer (:harness (first panel-seats)))
                           :brief (str "Synthesize the council deliberation on:\n" topic)}}
        result (panel! panel (cond-> {}
                               spawned-by (assoc :spawned-by spawned-by)
                               cwd (assoc :cwd cwd)))]
    (cond-> {:council (:blackboard result)
             :turns (:turns result)}
      (:synthesizer result) (assoc :synthesizer (:synthesizer result)))))

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
                          "--contract" :single "--spawned-by" :single "--cwd" :single
                          "--roster" :single})]
    (when-not (= 1 (count positional))
      (fail! "review requires <target-id>" {:got positional}))
    (review! (first positional)
             (cond-> {}
               (get flags "--roster") (assoc :roster (get flags "--roster"))
               (get flags "--members") (assoc :members (parse-int! "--members" (get flags "--members")))
               (get flags "--harness") (assoc :harnesses (split-csv (get flags "--harness")))
               (get flags "--contract") (assoc :contract (get flags "--contract"))
               (get flags "--spawned-by") (assoc :spawned-by (get flags "--spawned-by"))
               (get flags "--cwd") (assoc :cwd (get flags "--cwd"))
               (get flags "--synthesize") (assoc :synthesize? true)))))

(defn- op-rosters [argv]
  (when (seq argv)
    (fail! "rosters takes no arguments" {:got argv}))
  (rosters))

;; The council CLI is scalar-only (TEN-006): rich per-seat data (the :seats
;; vector) belongs in trusted Clojure / inline panels, not shell argv.
(defn- op-council [argv]
  (let [{:keys [positional flags]}
        (parse-argv argv {"--topic" :single "--members" :single "--rounds" :single
                          "--harness" :single "--synthesizer" :single
                          "--spawned-by" :single "--cwd" :single})]
    (when (seq positional)
      (fail! "council takes only flags" {:unexpected positional}))
    (council! (or (get flags "--topic") (fail! "council requires --topic" {}))
              (cond-> {}
                (get flags "--members") (assoc :members (parse-int! "--members" (get flags "--members")))
                (get flags "--rounds") (assoc :rounds (parse-int! "--rounds" (get flags "--rounds")))
                (get flags "--harness") (assoc :harness (get flags "--harness"))
                (get flags "--synthesizer") (assoc :synthesizer (get flags "--synthesizer"))
                (get flags "--spawned-by") (assoc :spawned-by (get flags "--spawned-by"))
                (get flags "--cwd") (assoc :cwd (get flags "--cwd"))))))

;; Structural run attrs the agents spool stamps at spawn (panel/review shape).
;; A raw-run retry carries these onto the respawn so the deliberation stays
;; queryable from run attrs after recovery — council seats have no other
;; recovery path. Engine control and lifecycle attrs (harness, prompt, cwd,
;; session-id, log, result, error, …) are re-derived or re-stamped by
;; spawn-run!, so only these spool-owned structural attrs are carried.
(def ^:private preserved-run-attr-names
  ["review-target" "review-pass" "review-roster" "review-focus"
   "review-synthesis" "panel-seat" "panel-turn"])

(defn- op-retry [argv]
  (let [{:keys [positional flags]} (parse-argv argv {"--harness" :single "--cwd" :single "--prompt" :single "--fresh" :bool})
        [id] positional
        fresh? (boolean (get flags "--fresh"))]
    (when-not (= 1 (count positional)) (fail! "retry requires <task-or-run-id>" {:got positional}))
    (let [strand (or (api/show (rt) id) (fail! "retry target not found" {:id id}))
          run (if (run? strand) strand (first (filter #(failed-phases (sattr % "phase")) (task-runs id))))
          task-id (when-not (run? strand) id)]
      (when-not (and run (failed-phases (sattr run "phase"))) (fail! "nothing to supersede" {:id id}))
      (let [resumes (sattr run "resumes")
            ;; A3 continuity: a plain retry re-resumes the predecessor session;
            ;; --fresh severs it. A resume-classed failure (A1) means the session
            ;; itself was lost, so re-resuming would only loop against it — refuse
            ;; the plain retry and name the --fresh escape rather than silently
            ;; cold-starting a continuation the caller did not ask for.
            _ (when (and resumes (not fresh?) (= "resume" (sattr run "error-class")))
                (fail! "run failed resuming its session; retry --fresh to respawn cold on the full-brief prompt"
                       {:run (:id run) :resumes resumes}))
            preserve-resume? (and resumes (not fresh?))
            fresh-prompt (sattr run "fresh-prompt")
            ;; carry the spool-owned structural attrs plus, when the retry keeps
            ;; resuming, the full-brief form so a later --fresh of the re-resumed
            ;; run can still cold-start correctly
            carried-attrs (cond-> (into {}
                                        (keep (fn [n] (when-let [v (sattr run n)] [(str "shuttle/" n) v])))
                                        preserved-run-attr-names)
                            (and preserve-resume? fresh-prompt) (assoc "shuttle/fresh-prompt" fresh-prompt))
            extra (get flags "--prompt")
            append-extra (fn [p] (str p (when extra (str "\n\n" extra))))]
        (api/update (rt) (:id run) {:state "closed" :attributes {"shuttle/phase" "superseded"}})
        (let [summary (shuttle/run-summary run)
              task (when task-id (api/show (rt) task-id))
              served-target (or task-id (:for summary))
              deps (->> (:edges (api/subgraph (rt) [(:id run)] {:type "depends-on"}))
                        (filter #(= (:id run) (:from_strand_id %)))
                        (mapv :to_strand_id))
              interactive? (= "interactive" (sattr run "mode"))
              prompt (cond
                       task (prompt-for-task task extra interactive?)
                       ;; --fresh severs resume, so the process starts cold and
                       ;; must get the full-brief form the resuming run stashed;
                       ;; the short continuation assumes a session this spawn
                       ;; will not have (A6)
                       (and fresh? resumes) (append-extra
                                             (or fresh-prompt
                                                 (fail! "retry --fresh cannot reconstruct the full-brief prompt for this resuming run"
                                                        {:run (:id run)})))
                       :else (append-extra (sattr run "prompt")))
              new-run (shuttle/spawn-run! (cond-> {:harness (or (get flags "--harness") (sattr run "harness"))
                                                   :prompt prompt
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
                                            (sattr run "max-attempts") (assoc :max-attempts (sattr run "max-attempts"))
                                            preserve-resume? (assoc :resume resumes)
                                            (seq carried-attrs) (assoc :attrs carried-attrs)))]
          (cond-> {:superseded (:id run) :run (select-keys (shuttle/run-summary new-run) [:id :phase :harness])}
            task-id (assoc :task task-id)))))))

(defn- blockers [task]
  (->> (:edges (api/subgraph (rt) [(:id task)] {:type "depends-on"}))
       (filter #(and (= (:id task) (:from_strand_id %)) (not= "closed" (:state (api/show (rt) (:to_strand_id %))))))
       (mapv :to_strand_id)))

(defn- status-visible-child?
  [s]
  (or (run? s)
      (= "active" (:state s))))

(defn- tree-node [s]
  (let [kids (->> (children-ids (:id s))
                  (map #(api/show (rt) %))
                  (filter status-visible-child?))]
    (cond-> {:id (:id s) :title (:title s) :kind (if (run? s) "run" "task") :children (mapv tree-node kids)}
      (run? s) (assoc :phase (sattr s "phase"))
      (attr s :status) (assoc :status (attr s :status)))))

(defn- op-status [argv]
  (let [{:keys [positional]} (parse-argv argv {})]
    (when (> (count positional) 1) (fail! "status takes at most one root-id" {:got positional}))
    (let [root (first positional)
          nodes (if root (cons (api/show (rt) root) (parent-descendants root)) (api/list (rt) [:= :state "active"] {}))
          tasks (filter #(and (not (run? %)) (= "active" (:state %))) nodes)
          runs (filter run? nodes)]
      {:tree (mapv tree-node (if root [(api/show (rt) root)] (filter #(and (not (run? %)) (seq (task-runs (:id %)))) tasks)))
       :ready (mapv :id (filter #(and (not= root (:id %))
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
      "rosters" (op-rosters args)
      "delegate" (op-delegate args)
      "retry" (op-retry args)
      "status" (op-status args)
      (fail! "Unknown agent subcommand" {:subcommand sub :available ["about" "spawn" "ps" "await" "logs" "kill" "harnesses" "backends" "note" "notes" "council" "review" "rosters" "delegate" "retry" "status"]}))))

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
