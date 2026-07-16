(ns cutover.vocab-reset
  "One-shot cutover that rewrites the durable attributes of *active* strands from
  the pre-reset vocabulary to the words the vocabulary-reset branch published, so
  a live world matches the renamed code at the user-signed weaver restart
  (SPEC-005.C11, the shared-spool vocabulary rule's cutover clause).

  Scope is active strands only: closed and replaced strands are historical
  memory, not authority, so their attributes stay exactly as they were written.
  A key absent from `migrations` is left in the old vocabulary by design.

  The migration table is data — one map per durable-attribute migration — so a
  later slice appends an entry instead of editing the engine. Three shapes:

    {:migration :rename-key   :from \"old/key\" :to \"new/key\"}
    {:migration :rename-value :key \"k\" :from \"old\" :to \"new\"}
    {:migration :drop-key     :key \"k\"}

  Each entry may narrow its rows with guards: `:when-value` / `:unless-value`
  match the row's own JSON value, and `:when-sibling` requires the strand to
  carry another attribute at a given value. Guards are what let one overloaded
  key split two ways — `bench/run` carries a root id on judge strands but a
  plain \"true\" marker elsewhere, and only the former becomes `bench/run-id`.

  Table order matters only where an entry guards on a sibling another entry
  drops: the plan-root `kind` rename reads `workflow` \"agent-plan\", so it is
  listed before the entry that drops that key. Every other pair is independent.

  Idempotent by construction: each statement matches the *old* shape, so a
  re-run against a migrated world matches nothing and reports a zero total. A
  strand carrying both the old and the new key is an inconsistent world rather
  than a migrated one — the attributes primary key (strand_id, key) cannot hold
  both after the rewrite — so the pre-flight refuses it loudly instead of
  surfacing a raw SQLite constraint violation. Rows a guard declined are
  reported too, so a strand shaped unexpectedly is read by the operator rather
  than migrated blind or dropped in silence.

  The db target must be explicit (--db, or --workspace resolved through
  `mill weaver status`); the script refuses to guess a canonical world. A live
  world's db lives under the weaver state directory, not workspace-local
  `data/`.

  Run it with scripts/ on the classpath:
    clojure -Sdeps '{:extra-paths [\"scripts\"]}' -M -m cutover.vocab-reset \\
      --db <path>

  WHEN: once per world, at merge/reload time, by the user — not by an agent and
  not against a world an agent owns. The canonical `.skein` world is migrated as
  a hand step: land the branch, stop the weaver, run this against the world's db,
  then start the weaver again on the renamed code. Rehearse on a copy of the db
  first; the rewrite is one transaction, so a failure leaves the world as it was."
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [next.jdbc :as jdbc]))

(def migrations
  "Every durable-attribute migration the vocabulary reset shipped, in table form.

  Transcribed per rename from the branch's mutation slices rather than derived
  from a prefix rule: the old vocabulary split one prefix across several new
  owners (`review/*` became `panel/*` only for the blackboard/pass/synthesis
  keys, while `review/roster` and `review/focus` kept their word), so a blanket
  prefix rewrite would mis-map its neighbours."
  [;; workflow: the run's shape is its form, and role names the graph position.
   {:migration :rename-key :from "workflow/phase" :to "workflow/form"
    :note "phase collided with agent-run/phase's lifecycle reading"}
   {:migration :rename-value :key "workflow/role" :from "molecule" :to "root"
    :note "the molecule metaphor lost to the plain graph noun"}
   {:migration :drop-key :key "workflow/wisp"
    :note "derivable from workflow/form = wisp; a second name for one concept"}
   {:migration :drop-key :key "workflow/hitl"
    :note "derivable from workflow/checkpoint-kind = human"}
   {:migration :rename-key :from "gate/run-id" :to "workflow/run-id"
    :note "the workflow run owns the id; the gate only pointed at it"}
   {:migration :rename-key :from "shell/error" :to "gate/error"
    :note "one failure stamp for both gate kinds, shell and subagent"}

   ;; agent-run: the run completes on its target, it is not merely 'for' it.
   {:migration :rename-key :from "agent-run/for" :to "agent-run/completes-on"}
   {:migration :rename-key :from "panel/fresh-prompt" :to "agent-run/fresh-prompt"
    :note "prompt freshness is the run's concern, not the panel's"}

   ;; delegation: the panel primitive owns the board it reviews against.
   {:migration :rename-key :from "review/target" :to "panel/blackboard"}
   {:migration :rename-key :from "review/pass" :to "panel/pass"}
   {:migration :rename-key :from "review/synthesis" :to "panel/synthesis"}
   {:migration :drop-key :key "panel/role" :when-value "panel"
    :note "the blackboard's self-marker; its kind already says what it is"}

   ;; Bare keys on delegated task strands, namespaced onto their owning spool.
   ;; Guarded by kind: a bare `harness` elsewhere is not agent-run's word.
   {:migration :rename-key :from "harness" :to "agent-run/harness"
    :when-sibling {"kind" "task"}}
   {:migration :rename-key :from "cwd" :to "agent-run/cwd"
    :when-sibling {"kind" "task"}}
   {:migration :rename-key :from "max-attempts" :to "agent-run/max-attempts"
    :when-sibling {"kind" "task"}}
   {:migration :rename-key :from "backend" :to "agent-run/backend"
    :when-sibling {"kind" "task"}}

   ;; The plan root stopped carrying its workflow name as a separate marker:
   ;; kind says what it is. This pair is order-dependent (see the ns docstring).
   {:migration :rename-value :key "kind" :from "plan" :to "agent-plan"
    :when-sibling {"workflow" "agent-plan"}}
   {:migration :drop-key :key "workflow" :when-value "agent-plan"
    :note "dropped from roots and children; kind carries the plan's identity"}

   ;; ephemeral: a bare key squatted the global namespace. This rename lands on
   ;; a parallel branch, so the entry is carried here from the slice's contract
   ;; rather than from this checkout's history.
   {:migration :rename-key :from "ephemeral" :to "ephemeral/entry"}

   ;; bench: the harness is the registered agent def, and `bench/run` was
   ;; overloaded — a root id on judge strands, a bare "true" marker on run
   ;; roots. Only the id-carrying rows become bench/run-id.
   {:migration :rename-key :from "bench/agent" :to "bench/harness"}
   {:migration :rename-key :from "bench/run" :to "bench/run-id"
    :unless-value "true"
    :note "marker rows keep bench/run; judge rows carried the root id"}
   {:migration :drop-key :key "bench/aborted" :when-value "true"
    :note "redundant with bench/error = aborted on a closed strand"}

   ;; roster: the entry's lifecycle is a phase, and its ending is an outcome.
   {:migration :rename-key :from "roster/status" :to "roster/phase"}
   {:migration :rename-key :from "roster/result" :to "roster/outcome"}
   ;; roster/body had no bare twin — it moves rather than goes, or the entry
   ;; loses its text. The four below did dual-write their bare convention key,
   ;; so dropping the roster/* half keeps the surviving copy.
   {:migration :rename-key :from "roster/body" :to "body"}
   {:migration :drop-key :key "roster/feature"}
   {:migration :drop-key :key "roster/owner"}
   {:migration :drop-key :key "roster/branch"}
   {:migration :drop-key :key "roster/worktree"}])

(def ^:private active-scope
  "SQL predicate narrowing a statement to attributes of active strands."
  "strand_id IN (SELECT id FROM strands WHERE state = 'active')")

(defn- sibling-clause
  "Return `[sql params]` requiring the strand to carry `k` at `v`, or nil."
  [when-sibling]
  (when when-sibling
    (let [[k v] (first when-sibling)]
      (when (< 1 (count when-sibling))
        (throw (ex-info "A :when-sibling guard takes exactly one attribute"
                        {:when-sibling when-sibling})))
      [(str " AND strand_id IN (SELECT strand_id FROM attributes"
            " WHERE key = ? AND value = ?)")
       [k (json/write-str v)]])))

(defn- value-clause
  "Return `[sql params]` matching (or excluding) the row's own JSON value."
  [{:keys [when-value unless-value]}]
  (cond
    (and when-value unless-value)
    (throw (ex-info "An entry takes :when-value or :unless-value, not both"
                    {:when-value when-value :unless-value unless-value}))
    when-value [" AND value = ?" [(json/write-str when-value)]]
    unless-value [" AND value <> ?" [(json/write-str unless-value)]]
    :else nil))

(defn- guards
  "Return `[sql params]` for an entry's row guards, composed in table order."
  [entry]
  (let [clauses (keep identity [(value-clause entry)
                                (sibling-clause (:when-sibling entry))])]
    [(str/join (map first clauses))
     (into [] (mapcat second) clauses)]))

(defn- label
  "Return the human-readable name this entry reports itself under."
  [{:keys [migration from to key] :as entry}]
  (case migration
    :rename-key (str from " -> " to (when (:when-sibling entry) " (guarded)"))
    :rename-value (str key ": " from " -> " to)
    :drop-key (str "drop " key)))

(defn- apply-entry!
  "Apply one migration entry within `tx`; return the number of rows changed."
  [tx {:keys [migration from to key] :as entry}]
  (let [[guard-sql guard-params] (guards entry)]
    (-> (jdbc/execute-one!
         tx
         (case migration
           :rename-key
           (into [(str "UPDATE attributes SET key = ? WHERE key = ? AND "
                       active-scope guard-sql)
                  to from]
                 guard-params)

           :rename-value
           (into [(str "UPDATE attributes SET value = ? WHERE key = ? AND value = ? AND "
                       active-scope guard-sql)
                  (json/write-str to) key (json/write-str from)]
                 guard-params)

           :drop-key
           (into [(str "DELETE FROM attributes WHERE key = ? AND " active-scope guard-sql)
                  key]
                 guard-params)

           (throw (ex-info "Unknown migration shape" {:entry entry}))))
        :next.jdbc/update-count)))

(defn- collisions
  "Return the active strands already carrying both sides of a `:rename-key`.

  A row here means the world is inconsistent rather than half-migrated: the
  rewrite cannot land both keys under the attributes primary key, so the caller
  must abort loudly with the evidence instead of letting SQLite raise."
  [tx]
  (into []
        (mapcat (fn [{:keys [from to] :as entry}]
                  (let [[guard-sql guard-params] (guards entry)]
                    (->> (jdbc/execute!
                          tx
                          (into [(str "SELECT strand_id FROM attributes WHERE key = ? AND "
                                      active-scope guard-sql
                                      " AND strand_id IN (SELECT strand_id FROM attributes"
                                      " WHERE key = ?)")
                                 from]
                                (concat guard-params [to])))
                         (map (fn [row]
                                {:strand-id (:attributes/strand_id row)
                                 :migration (label entry)}))))))
        (filter (comp #{:rename-key} :migration) migrations)))

(defn- left-behind
  "Return the active rows a `:when-sibling` guard declined, by entry label.

  A guard narrows a bare key to the strands whose owner it is — `harness` is
  agent-run's word on a delegated task, and nobody else's. A row the guard
  skipped is therefore either correctly none of our business or a strand shaped
  unexpectedly, and only the operator can tell which: report it rather than
  migrate it blind or drop it silently. Advisory, not fatal."
  [tx]
  (into {}
        (keep (fn [{:keys [from] :as entry}]
                (let [ids (->> (jdbc/execute!
                                tx
                                [(str "SELECT strand_id FROM attributes WHERE key = ? AND "
                                      active-scope)
                                 from])
                               (mapv :attributes/strand_id))]
                  (when (seq ids) [(label entry) ids]))))
        (filter (every-pred (comp #{:rename-key} :migration) :when-sibling)
                migrations)))

(defn rewrite!
  "Apply every migration to `ds` (a next.jdbc datasource) in one transaction.

  Returns `{:changes {label row-count} :total n :left-behind {label [strand-id]}}`.
  `:changes` lists only the entries that touched a row, so a zero total means the
  world was already migrated and a re-run is a no-op. `:left-behind` names the
  guarded rows the cutover declined, for the operator to read.

  Throws when an active strand carries both sides of a rename."
  [ds]
  (jdbc/with-transaction [tx ds]
    (when-let [found (seq (collisions tx))]
      (throw (ex-info (str "Refusing to migrate: " (count found)
                           " active strand(s) carry both the old and the new key")
                      {:collisions found})))
    (let [changes (into {}
                        (keep (fn [entry]
                                (let [n (apply-entry! tx entry)]
                                  (when (pos? n) [(label entry) n]))))
                        migrations)]
      {:changes changes
       :total (reduce + 0 (vals changes))
       :left-behind (left-behind tx)})))

(defn- resolve-workspace-db
  "Resolve a workspace's live SQLite path from `mill weaver status`.

  The live db lives under the weaver state dir, not workspace-local `data/`, so
  we ask mill for database_path rather than assuming a location. Fails loudly if
  mill errors or reports no path."
  [workspace]
  (let [{:keys [exit out err]}
        (shell/sh "mill" "weaver" "status" "--workspace" workspace)]
    (when-not (zero? exit)
      (throw (ex-info (str "mill weaver status failed for workspace " workspace)
                      {:exit exit :err (str/trim (str err))})))
    (let [path (get (json/read-str out) "database_path")]
      (when (str/blank? path)
        (throw (ex-info "mill weaver status returned no database_path"
                        {:workspace workspace :out out})))
      path)))

(defn resolve-db-path
  "Resolve the explicit db target from parsed options.

  Refuses to run without an explicit target and refuses an ambiguous pair — no
  implicit canonical-world discovery, no workspace-local `data/` assumption."
  [{:keys [db workspace]}]
  (cond
    (and db workspace)
    (throw (ex-info "Pass exactly one of --db or --workspace, not both"
                    {:db db :workspace workspace}))
    db db
    workspace (resolve-workspace-db workspace)
    :else
    (throw (ex-info "Refusing to run without an explicit db target: pass --db <path> or --workspace <dir>"
                    {}))))

(def ^:private cli-options
  [["-d" "--db PATH" "Explicit path to the target skein.sqlite"]
   ["-w" "--workspace DIR" "Workspace whose live db is resolved via `mill weaver status`"]
   ["-h" "--help"]])

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (when (seq errors)
      (binding [*out* *err*] (run! println errors))
      (System/exit 2))
    (when (:help options)
      (println "Usage: cutover.vocab-reset --db <path> | --workspace <dir>")
      (println summary)
      (System/exit 0))
    (try
      (let [db-path (resolve-db-path options)
            ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})
            {:keys [changes total left-behind]} (rewrite! ds)]
        (println (str "Cutover complete against " db-path))
        (println (str "Rows rewritten: " total))
        (doseq [[k n] (sort-by key changes)]
          (println (format "  %-48s %d" k n)))
        (when (seq left-behind)
          (println)
          (println "Left in the old vocabulary — a guard declined these rows:")
          (doseq [[k ids] (sort-by key left-behind)]
            (println (format "  %-48s %s" k (str/join " " ids)))))
        (System/exit 0))
      (catch Exception e
        (binding [*out* *err*]
          (println "Cutover aborted:" (ex-message e))
          (when-let [data (ex-data e)] (println (pr-str data))))
        (System/exit 1)))))
