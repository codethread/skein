(ns skein.spools.roster
  "Active-work registry: `roster/*` attribute vocabulary plus explicit-runtime
  `start!`/`heartbeat!`/`finish!`/`list`/`await-quiet!` helpers over ordinary
  strands, installed as the declared-subcommand `roster` op and a named
  `roster` query.

  A roster entry is an active strand marked `roster/entry` \"true\" with a
  required `feature` and `owner`. `start!` either creates a new entry strand or
  restamps an explicitly supplied existing strand in place (preserving its
  other attributes), so callers can bring a workflow/devflow root under roster
  tracking without losing its identity. Every public model function here takes
  `runtime` as its first argument and never resolves ambient runtime itself
  (see SPEC-RosterSpool-001 P4/P5 and
  docs/spools/writing-shared-spools.md's explicit-runtime pattern), so this spool
  composes safely across published daemons, test runtimes, and side-by-side
  worlds; only `install!` resolves the active runtime, at the activation
  boundary used by other shipped spools, and CLI op handlers read `:op/runtime`
  from their invocation context rather than resolving it themselves.

  The public seam input shapes are declared as `clojure.spec` specs
  (`::start-attrs`, `::heartbeat-opts`, `::finish-opts`, `::list-opts`,
  `::await-quiet-opts`, and
  their field predicates) as the discoverable/reusable source of truth,
  matching sibling spools; each fn layers manual checks for what s/keys cannot
  express (closed key sets and start!'s id-derivable feature/owner)."
  (:refer-clojure :exclude [list])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.events.alpha :as events]
            [skein.api.graph.alpha :as graph]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.format.alpha :as fmt]
            [skein.api.spool.alpha :refer [fail! reject-unknown-keys! attr-key->str attr-get poll-until-deadline!]])
  (:import [java.time Duration Instant]))

(def default-stale-after-ms
  "Default staleness threshold for `list`/`await-quiet!`: fifteen minutes."
  (* 15 60 1000))

(defn- non-blank-string?
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- require-non-blank!
  [k v]
  (when-not (non-blank-string? v)
    (fail! (str (name k) " must be a non-blank string") {:argument k :value v}))
  v)

(defn- optional-non-blank!
  [k v]
  (when (some? v)
    (require-non-blank! k v))
  v)

(defn- attr-value
  "Return a strand attribute by keyword or string key, via the shared spool-tier
  tolerant reader (`skein.api.spool.alpha/attr-get`)."
  [strand k]
  (attr-get strand k))

(defn- now-str
  "Stringify the recorded instant for `start!`/`heartbeat!`/`finish!`. A `:now`
  override must be an `Instant` — anything else (including an explicit `:now
  nil`) fails loudly here rather than stringifying into an unparseable
  timestamp attribute that only explodes later in `entry-row` (TEN-003)."
  [opts]
  (if (contains? opts :now)
    (let [now (:now opts)]
      (when-not (instance? Instant now)
        (fail! ":now must be an Instant" {:now now}))
      (str now))
    (str (Instant/now))))

;; ---------------------------------------------------------------------------
;; start!
;; ---------------------------------------------------------------------------

(def ^:private start-keys
  #{:id :feature :owner :title :body :branch :worktree :engine :run-id :source-id :now})

(defn- existing-attr
  [existing k]
  (when existing (attr-value existing k)))

(defn- resolve-identity!
  "Return {:feature :owner}, preferring attrs and falling back to existing's
  `feature`/`owner` attributes when attrs omits them."
  [existing {:keys [feature owner]}]
  (let [feature (or feature (existing-attr existing :feature))
        owner (or owner (existing-attr existing :owner))]
    {:feature (require-non-blank! :feature feature)
     :owner (require-non-blank! :owner owner)}))

(defn- start-attributes
  [{:keys [feature owner branch worktree engine run-id source-id body]} started-at now]
  (cond-> {:roster/entry "true"
           :roster/phase "active"
           :roster/started-at started-at
           :roster/heartbeat-at now
           :feature feature
           :owner owner}
    branch (assoc :branch branch)
    worktree (assoc :worktree worktree)
    engine (assoc :roster/engine engine)
    run-id (assoc :roster/run-id run-id)
    source-id (assoc :roster/source-id source-id)
    body (assoc :body body)))

(defn start!
  "Create or restamp one roster entry.

  `attrs` requires non-blank `:feature` and `:owner` unless `:id` names an
  existing strand that already carries them (as `feature` and `owner`).
  Supplying `:id` restamps that strand into the roster entry in place, merging
  roster attributes onto whatever it already carries and forcing it active;
  omitting `:id` creates a new entry strand, optionally recording `:source-id`
  as a link back to another strand it mirrors. Other optional keys: `:title`,
  `:body`, `:branch`, `:worktree`, `:engine`, `:run-id`, and `:now` (an
  `Instant` override for deterministic callers/tests). Fails loudly for
  malformed attrs or a missing `:id` strand."
  [runtime attrs]
  (when-not (map? attrs)
    (fail! "start! attrs must be a map" {:attrs attrs}))
  (reject-unknown-keys! "start!" start-keys attrs)
  (let [{:keys [id title body branch worktree engine run-id source-id]} attrs]
    (optional-non-blank! :id id)
    (optional-non-blank! :title title)
    (optional-non-blank! :body body)
    (optional-non-blank! :branch branch)
    (optional-non-blank! :worktree worktree)
    (optional-non-blank! :engine engine)
    (optional-non-blank! :run-id run-id)
    (optional-non-blank! :source-id source-id)
    (let [existing (when id
                     (or (weaver/show runtime id)
                         (fail! "start! :id strand not found" {:id id})))
          identity (resolve-identity! existing attrs)
          now (now-str attrs)
          started-at (or (existing-attr existing :roster/started-at) now)
          roster-attrs (start-attributes (merge identity attrs) started-at now)]
      (if existing
        ;; Send only the roster attribute delta, not a merge over the whole
        ;; stale attribute snapshot: `weaver/update` applies attributes via SQLite
        ;; json_patch (RFC 7396 merge-patch), so resending unchanged keys would
        ;; silently revert any concurrent write to them (the auto-heartbeat
        ;; event worker and a direct caller race on the same entry) — see
        ;; SPEC-RosterSpool-001.C4.
        (weaver/update runtime id
                       (cond-> {:attributes roster-attrs}
                         (not= "active" (:state existing)) (assoc :state "active")
                         (and title (not= title (:title existing))) (assoc :title title)))
        (weaver/add runtime {:title (or title (str "Roster: " (:feature identity) " (" (:owner identity) ")"))
                             :attributes roster-attrs})))))

;; ---------------------------------------------------------------------------
;; shared entry lookup
;; ---------------------------------------------------------------------------

(defn- require-roster-entry!
  [runtime id]
  (let [strand (or (weaver/show runtime id)
                   (fail! "Roster entry not found" {:id id}))]
    (when-not (= "true" (attr-value strand :roster/entry))
      (fail! "Strand is not a roster entry" {:id id}))
    strand))

(defn- require-active-roster-entry!
  [runtime id op]
  (let [strand (require-roster-entry! runtime id)]
    (when-not (= "active" (:state strand))
      (fail! (str "Roster entry must be active to " op)
             {:reason :roster/entry-not-active :id id :state (:state strand)}))
    strand))

;; ---------------------------------------------------------------------------
;; heartbeat!
;; ---------------------------------------------------------------------------

(defn heartbeat!
  "Update `roster/heartbeat-at` on an active roster entry.

  Refuses loudly for a missing, closed/finished, or non-roster entry id: a
  finished entry is never re-heartbeated. `opts` accepts `:now` to override the
  recorded instant for deterministic callers/tests. Sends only the
  `roster/heartbeat-at` delta (not the whole attribute snapshot), so a heartbeat
  that commits just after a concurrent `finish!` can only advance the timestamp
  on the already-closed entry — it can never resurrect `roster/phase` to
  \"active\" (SPEC-RosterSpool-001.C4). Fails loudly for non-map opts or
  unknown opt keys."
  [runtime entry-id & [opts]]
  (require-non-blank! :entry-id entry-id)
  (when (some? opts)
    (when-not (map? opts)
      (fail! "heartbeat! opts must be a map" {:opts opts}))
    (reject-unknown-keys! "heartbeat!" #{:now} opts))
  (require-active-roster-entry! runtime entry-id "heartbeat")
  (weaver/update runtime entry-id {:attributes {:roster/heartbeat-at (now-str opts)}}))

;; ---------------------------------------------------------------------------
;; finish!
;; ---------------------------------------------------------------------------

(def ^:private finish-phases #{"finished" "abandoned"})

;; ---------------------------------------------------------------------------
;; Public seam shapes (clojure.spec)
;;
;; Specs are the discoverable/reusable source of truth for the shapes of the
;; public `start!`/`finish!`/`list`/`await-quiet!` input maps, matching
;; sibling spools (skein.spools.delegation, skein.spools.workflow). Each fn's
;; manual checks cover what s/keys cannot express: closed key sets (s/keys maps
;; stay open) and start!'s feature/owner requirement, which is satisfiable from
;; an existing `:id` strand rather than the attrs map. Predicate specs are used
;; as the gate inside the existing `fail!` calls so the specs own the shape
;; while the contextual messages are preserved (SPEC-RosterSpool-001.C1).
;; ---------------------------------------------------------------------------

(s/def ::feature non-blank-string?)
(s/def ::owner non-blank-string?)
(s/def ::title non-blank-string?)
(s/def ::body non-blank-string?)
(s/def ::branch non-blank-string?)
(s/def ::worktree non-blank-string?)
(s/def ::engine non-blank-string?)
(s/def ::run-id non-blank-string?)
(s/def ::source-id non-blank-string?)
(s/def ::id non-blank-string?)
(s/def ::now #(instance? Instant %))
(s/def ::phase finish-phases)
(s/def ::outcome non-blank-string?)
(s/def ::stale-after-ms pos-int?)
(s/def ::timeout-secs (s/and integer? (complement neg?)))
(s/def ::poll-ms (s/and integer? (complement neg?)))

(s/def ::start-attrs
  (s/keys :opt-un [::id ::feature ::owner ::title ::body ::branch ::worktree
                   ::engine ::run-id ::source-id ::now]))
(s/def ::heartbeat-opts (s/keys :opt-un [::now]))
(s/def ::finish-opts (s/keys :opt-un [::phase ::outcome ::now]))
(s/def ::list-opts
  (s/keys :opt-un [::feature ::owner ::branch ::worktree ::engine ::stale-after-ms]))
(s/def ::await-quiet-opts
  (s/keys :opt-un [::feature ::branch ::worktree ::timeout-secs ::stale-after-ms ::poll-ms]))

(defn finish!
  "Close an active roster entry with a final `roster/phase`.

  `opts` is a map: `:phase` (`\"finished\"` default, or `\"abandoned\"`),
  optional `:outcome` string, and `:now` override. Records `roster/phase`,
  `roster/finished-at`, and optional `roster/outcome`, then closes the strand.
  Sends only that delta (not the whole attribute snapshot) so a concurrent
  auto-heartbeat cannot roll the final phase back (SPEC-RosterSpool-001.C4).
  Fails loudly for a missing, closed, or non-roster entry id, an unrecognized
  phase, or malformed opts."
  [runtime entry-id opts]
  (require-non-blank! :entry-id entry-id)
  (when-not (map? opts)
    (fail! "finish! opts must be a map" {:opts opts}))
  (reject-unknown-keys! "finish!" #{:phase :outcome :now} opts)
  (optional-non-blank! :outcome (:outcome opts))
  (let [phase (or (:phase opts) "finished")]
    (when-not (s/valid? ::phase phase)
      (fail! "finish! :phase must be finished or abandoned" {:phase phase :allowed (sort finish-phases)}))
    (require-active-roster-entry! runtime entry-id "finish")
    (let [now (now-str opts)
          attrs (cond-> {:roster/phase phase
                         :roster/finished-at now}
                  (:outcome opts) (assoc :roster/outcome (:outcome opts)))]
      (weaver/update runtime entry-id {:attributes attrs :state "closed"}))))

;; ---------------------------------------------------------------------------
;; entry listing + stale derivation
;; ---------------------------------------------------------------------------

(def ^:private list-opts-keys #{:feature :owner :branch :worktree :engine :stale-after-ms})

(def ^:private scope-attrs
  {:feature :feature
   :owner :owner
   :branch :branch
   :worktree :worktree
   :engine :roster/engine})

(defn- stale-after-ms-opt
  [opts]
  (let [threshold (get opts :stale-after-ms default-stale-after-ms)]
    (when-not (s/valid? ::stale-after-ms threshold)
      (fail! "list :stale-after-ms must be a positive integer" {:stale-after-ms threshold}))
    threshold))

(defn- scope-match?
  [opts strand]
  (every? (fn [[k attr-k]]
            (or (nil? (get opts k))
                (= (get opts k) (attr-value strand attr-k))))
          scope-attrs))

(defn- entry-heartbeat-instant
  "Parse a roster entry's `roster/heartbeat-at` into an `Instant`, failing
  loudly with the offending strand id when the attribute is missing or
  unparseable. An operator can stamp `roster/entry=true` directly via the
  generic `update` op and bypass `start!`, so this seam must not explode with a
  bare NPE/DateTimeParseException that names neither strand nor attribute
  (TEN-003)."
  [strand]
  (let [raw (attr-value strand :roster/heartbeat-at)]
    (when-not (non-blank-string? raw)
      (fail! "Roster entry is missing roster/heartbeat-at"
             {:id (:id strand) :roster/heartbeat-at raw}))
    (try
      (Instant/parse raw)
      (catch java.time.format.DateTimeParseException e
        (fail! "Roster entry has an unparseable roster/heartbeat-at"
               {:id (:id strand) :roster/heartbeat-at raw :cause (.getMessage e)})))))

(defn- entry-row
  [strand threshold now]
  (let [age-ms (.toMillis (Duration/between (entry-heartbeat-instant strand) now))]
    {:strand strand :stale? (>= age-ms threshold) :age-ms age-ms}))

(defn list
  "Return active roster entries, optionally scoped by `:feature`, `:owner`,
  `:branch`, `:worktree`, or `:engine`.

  Each row is `{:strand <normalized strand> :stale? bool :age-ms long}`,
  derived against `opts`'s `:stale-after-ms` (default fifteen minutes; must
  be a positive integer when supplied). Sorted by strand id."
  [runtime opts]
  (when-not (map? opts)
    (fail! "list opts must be a map" {:opts opts}))
  (reject-unknown-keys! "list" list-opts-keys opts)
  (let [threshold (stale-after-ms-opt opts)
        now (Instant/now)]
    (->> (weaver/list runtime [:= [:attr "roster/entry"] "true"] {})
         (filter #(= "active" (:state %)))
         (filter #(scope-match? opts %))
         (map #(entry-row % threshold now))
         (sort-by (comp :id :strand))
         vec)))

;; ---------------------------------------------------------------------------
;; await-quiet!
;; ---------------------------------------------------------------------------

(def default-timeout-secs
  "Default `await-quiet!` `:timeout-secs`: thirty minutes, matching
  `workflow/await!`'s long-poll default."
  (* 30 60))

(def ^:private await-poll-ms
  "Default poll interval between `list` re-checks while awaiting quiet."
  50)

(def ^:private await-quiet-opts-keys
  #{:feature :branch :worktree :timeout-secs :stale-after-ms :poll-ms})

(defn- timeout-secs-opt
  [opts]
  (let [timeout (get opts :timeout-secs default-timeout-secs)]
    (when-not (s/valid? ::timeout-secs timeout)
      (fail! "await-quiet! :timeout-secs must be a non-negative integer" {:timeout-secs timeout}))
    timeout))

(defn- poll-ms-opt
  [opts]
  (let [poll-ms (get opts :poll-ms await-poll-ms)]
    (when-not (s/valid? ::poll-ms poll-ms)
      (fail! "await-quiet! :poll-ms must be a non-negative integer" {:poll-ms poll-ms}))
    poll-ms))

(defn await-quiet!
  "Block until the selected scope has no active non-stale entries.

  `opts` accepts `:feature`, `:branch`, `:worktree`, `:timeout-secs` (default
  thirty minutes), `:stale-after-ms` (default fifteen minutes), and `:poll-ms`
  (default fifty). Returns `{:reason :quiet|:stale|:timeout :entries [...]}`:
  `:stale` short-circuits as soon as any selected entry exceeds the stale
  threshold (checked before waiting further), `:quiet` when the scope has no
  active entries, and `:timeout` when neither happens before the deadline.
  `:entries` is whatever `list` returned for the scope at the decision
  point. Fails loudly for a malformed opts map, unknown keys, or a negative
  `:timeout-secs`/`:poll-ms`."
  [runtime opts]
  (when-not (map? opts)
    (fail! "await-quiet! opts must be a map" {:opts opts}))
  (reject-unknown-keys! "await-quiet!" await-quiet-opts-keys opts)
  (let [timeout-secs (timeout-secs-opt opts)
        poll-ms (poll-ms-opt opts)
        scope (select-keys opts [:feature :branch :worktree :stale-after-ms])
        deadline (+ (System/currentTimeMillis) (* 1000 timeout-secs))]
    (poll-until-deadline!
     {:deadline deadline
      :poll-ms poll-ms
      :check #(list runtime scope)
      :pred->result (fn [entries]
                      (cond
                        (some :stale? entries) {:reason :stale :entries entries}
                        (empty? entries) {:reason :quiet :entries entries}))
      :on-timeout (fn [entries] {:reason :timeout :entries entries})})))

;; ---------------------------------------------------------------------------
;; workflow/devflow graph integration
;;
;; Coupling here is strictly one-directional: roster reads public strand
;; attributes written by workflow/devflow roots and never requires their
;; namespaces (SPEC-RosterSpool-001.C12). A single async event handler both
;; auto-stamps sufficient roots and heartbeats graph-tracked flows.
;; ---------------------------------------------------------------------------

(def ^:private plumbing-roles
  "`workflow/role` values that mark a root as workflow plumbing, never work
  (SPEC-RosterSpool-001.C13). Matches the `work` query's hidden roles."
  #{"molecule" "procedure" "digest"})

(def ^:private feature-slug-attrs
  "Attribute keys consulted, in priority order, for a root's feature slug."
  [:workflow/run-id :devflow/feature :feature])

(def ^:private owner-attrs
  "Attribute keys consulted, in priority order, for a root's owner/actor."
  [:owner :workflow/actor :devflow/actor :actor])

(defn- non-blank-attr
  "A strand attribute's value when it is a non-blank string, else nil."
  [strand k]
  (let [v (attr-value strand k)]
    (when (non-blank-string? v) v)))

(defn- first-attr
  "First non-blank attribute value on `strand` among candidate keys `ks`."
  [strand ks]
  (some #(non-blank-attr strand %) ks))

(defn- roster-entry?
  [strand]
  (= "true" (attr-value strand :roster/entry)))

(defn- plumbing-root?
  [strand]
  (contains? plumbing-roles (attr-value strand :workflow/role)))

(defn- sufficient-root-identity
  "Return `{:feature :owner :engine :branch :worktree}` when `strand` is an
  auto-stampable candidate by attribute content — active, not workflow
  plumbing, and carrying both a feature slug and an owner — otherwise nil.
  `:branch`/`:worktree` are carried through when the root already exposes them
  (work-tree roots routinely carry `branch`/`worktree` per this repo's Branch
  work visibility convention) so branch-scoped `list`/`await-quiet!` find
  auto-tracked workflow/devflow roots. Graph-root-ness is a separate check
  (see `graph-root?`); roots missing feature or owner are the
  explicit-`start!` negative case (SPEC-RosterSpool-001.C13)."
  [strand]
  (when (and (= "active" (:state strand)) (not (plumbing-root? strand)))
    (let [feature (first-attr strand feature-slug-attrs)
          owner (first-attr strand owner-attrs)
          branch (non-blank-attr strand :branch)
          worktree (non-blank-attr strand :worktree)]
      (when (and feature owner)
        (cond-> {:feature feature
                 :owner owner
                 :engine (cond
                           (non-blank-attr strand :devflow/feature) "devflow"
                           (non-blank-attr strand :workflow/run-id) "workflow"
                           :else nil)}
          branch (assoc :branch branch)
          worktree (assoc :worktree worktree))))))

(defn- graph-root?
  "True when `id` has no incoming `parent-of` edge, i.e. it is a work-tree root
  rather than a descendant that happens to carry feature/owner attributes."
  [runtime id]
  (= [id] (graph/ancestor-root-ids runtime [id])))

(defn- changed-attr-keys
  "String-named attribute keys whose value differs between `before` and `after`."
  [before after]
  (let [ba (:attributes before)
        aa (:attributes after)]
    (into #{}
          (comp (remove #(= (get ba %) (get aa %)))
                (map attr-key->str))
          (set (concat (keys ba) (keys aa))))))

(defn- roster-self-write?
  "True when an event reflects roster's own bookkeeping write, so the handler
  ignores it to keep the async event loop from feeding itself: a freshly
  created or just-restamped roster entry (its heartbeat is already current), or
  a heartbeat-only refresh."
  [before after]
  (and (roster-entry? after)
       (or (nil? before)
           (not (roster-entry? before))
           (= #{"roster/heartbeat-at"} (changed-attr-keys before after)))))

(def ^:private active-roster-entry-query
  [:and [:= :state "active"] [:= [:attr "roster/entry"] "true"]])

(defn- refresh-ancestor-heartbeats!
  "Refresh `roster/heartbeat-at` on the active roster entry that roots `id`'s
  `parent-of` ancestry (including `id` itself). No-op when nothing in the
  ancestry is a tracked roster entry (SPEC-RosterSpool-001.C14)."
  [runtime id]
  (doseq [root-id (graph/ancestor-root-ids runtime [id] {:where active-roster-entry-query})]
    ;; Best-effort auto-heartbeat: an entry can finish between the active-entry
    ;; query above and this write. `heartbeat!` stays strict for direct callers
    ;; (it refuses on a closed entry), so here we swallow only that benign race
    ;; rather than surfacing it on the weaver event-failure surface.
    (try
      (heartbeat! runtime root-id)
      (catch clojure.lang.ExceptionInfo e
        (when-not (= :roster/entry-not-active (:reason (ex-data e)))
          (throw e))))))

(defn- auto-stamp!
  [runtime id {:keys [feature owner engine branch worktree]}]
  (start! runtime (cond-> {:id id :feature feature :owner owner}
                    engine (assoc :engine engine)
                    branch (assoc :branch branch)
                    worktree (assoc :worktree worktree))))

(defn handle-mutation-event!
  "Roster's async graph-integration handler (explicit-runtime core).

  For every strand add/update it either (a) restamps a sufficient, unstamped
  graph-root strand into a roster entry, or (b) refreshes the heartbeat of the
  active roster entry that roots the touched strand's `parent-of` ancestry, so
  graph-tracked workflow/devflow flows stay fresh without an explicit
  `heartbeat!`. Roster's own bookkeeping writes are ignored to avoid feedback
  loops. Exceptions are left to surface on the weaver event-failure surface."
  [runtime event]
  (let [after (or (:strand/after event) (:strand event))
        before (:strand/before event)
        id (:strand/id event)]
    (when (and id after (not (roster-self-write? before after)))
      (let [identity (when-not (roster-entry? after)
                       (sufficient-root-identity after))]
        (if (and identity (graph-root? runtime id))
          (auto-stamp! runtime id identity)
          (refresh-ancestor-heartbeats! runtime id))))))

(defn on-event
  "Registered event-handler entry point: dispatches to
  `handle-mutation-event!` under the runtime the event worker bound for this
  delivery."
  [event]
  (handle-mutation-event! (current/runtime) event))

(def ^:private integration-event-key
  :skein.spools.roster/integration)

(defn watch!
  "Register roster's async workflow/devflow graph-integration handler on
  strand add/update events."
  [runtime]
  (events/register! runtime integration-event-key
                    #{:strand/added :strand/updated}
                    'skein.spools.roster/on-event
                    {:purpose :roster/workflow-devflow-integration}))

;; ---------------------------------------------------------------------------
;; discovery: about / prime
;; ---------------------------------------------------------------------------

(defn about
  "Return the roster convention and installed helper surface."
  []
  {:operation "roster about"
   :summary (fmt/reflow "
             |Roster is the durable active-work registry: ordinary strands marked
             |roster/entry answer \"what work is active in this weaver?\" without replacing
             |workflow, devflow, kanban lanes, agent-run run state, or `strand branches`.")
   :attributes {:roster/entry "\"true\" marker for roster entries"
                :roster/phase "active | finished | abandoned"
                :roster/engine "optional engine/source label: workflow | devflow | afk | agent | manual"
                :roster/run-id "optional engine run identifier"
                :roster/source-id "optional source/root strand id this entry mirrors"
                :roster/started-at "ISO-8601 instant recorded by start! when missing"
                :roster/heartbeat-at "ISO-8601 instant used for staleness decisions"
                :roster/finished-at "ISO-8601 instant recorded by finish!"
                :roster/outcome "optional final outcome string, e.g. done, abandoned, failed"
                :feature "required non-blank feature/work slug"
                :owner "required non-blank driver identity (human, harness, or run id)"
                :branch "optional branch name"
                :worktree "optional worktree path"
                :body "optional human-readable context"}
   :staleness {:default-threshold-ms default-stale-after-ms
               :convention (fmt/reflow "
                            |Stale entries stay visible: list annotates them with :stale? and
                            |await-quiet returns :reason :stale rather than hiding or auto-burning
                            |them.")}
   :api {:start! "(start! runtime attrs) create or restamp one roster entry"
         :heartbeat! "(heartbeat! runtime entry-id & [opts]) refresh roster/heartbeat-at on an active entry"
         :finish! "(finish! runtime entry-id opts) close an active entry with a final phase/outcome"
         :list "(list runtime opts) list active entries, optionally scoped, with derived :stale?/:age-ms"
         :await-quiet! "(await-quiet! runtime opts) block until quiet, return :stale early, or time out"}
   :commands [{:usage "strand roster prime — full agent priming: working discipline + command surface"}
              {:usage (str "strand roster start --feature <slug> --owner <owner> [--branch <branch>] "
                           "[--worktree <path>] [--engine <engine>] [--run-id <id>] [--source-id <strand-id>] "
                           "[--body <text>]")}
              {:usage "strand roster heartbeat <entry-id>"}
              {:usage "strand roster finish <entry-id> [--phase finished|abandoned] [--outcome <text>]"}
              {:usage (str "strand roster list [--feature <slug>] [--owner <owner>] [--branch <branch>] "
                           "[--worktree <path>] [--engine <engine>] [--stale-after-secs <n>]")}
              {:usage (str "strand roster await-quiet [--feature <slug>] [--branch <branch>] "
                           "[--worktree <path>] [--timeout-secs <n>] [--stale-after-secs <n>]")}
              {:usage "strand list --query roster / strand ready --query roster — named query over active roster entries"}]})

(defn prime
  "Return the full agent-priming payload for using the roster.

  A superset of `about` — it reuses the same attribute/api/command surface and
  adds the working discipline an agent needs before starting, heartbeating, or
  finishing roster entries."
  []
  (assoc (about)
         :operation "roster prime"
         :working-agreement
         (fmt/fill "
               |Roster answers \"what work is active in this weaver\" without replacing
               |workflow, devflow, kanban lanes, agent-run run state, or `strand branches`; it
               |summarizes work roots for coordination and never enforces locks, ownership,
               |merge gates, or exclusivity.
               |
               |Active, non-plumbing workflow/devflow graph roots that carry a discoverable
               |feature slug and owner are auto-stamped into roster entries, and graph
               |mutations under them refresh the entry's heartbeat automatically. For AFK
               |loops, ad hoc sessions, and any root missing a feature slug or owner, call
               |`start!`/`roster start` explicitly at the start of a unit of work you want
               |visible.")
         :tracking-discipline
         (fmt/fill "
               |`start!` (or `roster start`) requires a non-blank feature slug and owner, and
               |either creates a new entry or restamps an explicitly supplied existing strand
               |in place — call it once at the start.
               |
               |`heartbeat!` (or `roster heartbeat <entry-id>`) refreshes roster/heartbeat-at;
               |call it once per visible unit of progress for engines that do not otherwise
               |mutate the graph during a run.
               |
               |`finish!` (or `roster finish <entry-id>`) closes the entry with a final phase
               |in normal completion, abandon, or failure cleanup.")
         :staying-aware
         (fmt/fill "
               |`roster list` (or the named `roster` query) defaults to active entries only,
               |optionally scoped by feature/owner/branch/worktree/engine, and annotates each
               |row with :stale?/:age-ms against a fifteen-minute default threshold.
               |
               |`roster await-quiet` blocks until the selected scope has no active non-stale
               |entries, returns immediately with :reason :stale when anything selected exceeds
               |the threshold, or returns on timeout — stale and finished work is never
               |silently hidden.")))

;; ---------------------------------------------------------------------------
;; CLI op
;; ---------------------------------------------------------------------------

(defn- start-op
  [ctx]
  (let [{:keys [feature owner branch worktree engine run-id source-id body]} (:op/args ctx)]
    (start! (:op/runtime ctx)
            (cond-> {:feature feature :owner owner}
              branch (assoc :branch branch)
              worktree (assoc :worktree worktree)
              engine (assoc :engine engine)
              run-id (assoc :run-id run-id)
              source-id (assoc :source-id source-id)
              body (assoc :body body)))))

(defn- heartbeat-op
  [ctx]
  (heartbeat! (:op/runtime ctx) (:entry-id (:op/args ctx))))

(defn- finish-op
  [ctx]
  (let [{:keys [entry-id phase outcome]} (:op/args ctx)]
    (finish! (:op/runtime ctx) entry-id
             (cond-> {}
               phase (assoc :phase phase)
               outcome (assoc :outcome outcome)))))

(defn- list-op
  [ctx]
  (let [{:keys [feature owner branch worktree engine stale-after-secs]} (:op/args ctx)]
    (list (:op/runtime ctx)
          (cond-> {}
            feature (assoc :feature feature)
            owner (assoc :owner owner)
            branch (assoc :branch branch)
            worktree (assoc :worktree worktree)
            engine (assoc :engine engine)
            stale-after-secs (assoc :stale-after-ms (* 1000 stale-after-secs))))))

(defn- await-quiet-op
  [ctx]
  (let [{:keys [feature branch worktree timeout-secs stale-after-secs]} (:op/args ctx)]
    (await-quiet! (:op/runtime ctx)
                  (cond-> {}
                    feature (assoc :feature feature)
                    branch (assoc :branch branch)
                    worktree (assoc :worktree worktree)
                    timeout-secs (assoc :timeout-secs timeout-secs)
                    stale-after-secs (assoc :stale-after-ms (* 1000 stale-after-secs))))))

(defn roster-op
  "Dispatch parsed `strand roster ...` subcommands."
  [ctx]
  (case (:subcommand (:op/args ctx))
    "about" (about)
    "prime" (prime)
    "start" (start-op ctx)
    "heartbeat" (heartbeat-op ctx)
    "finish" (finish-op ctx)
    "list" (list-op ctx)
    "await-quiet" (await-quiet-op ctx)))

(def ^:private roster-arg-spec
  "Declared command surface for the `roster` op."
  {:op "roster"
   :doc "Manage active-work roster entries: start, heartbeat, finish, list, and await quiet."
   :subcommands
   {"about" {:doc "Return the roster convention and installed helper surface."}
    "prime" {:doc "Return full agent priming for using the roster."}
    "start" {:doc "Create or restamp an active roster entry."
             :flags {:feature {:required? true :doc "Feature/work slug."}
                     :owner {:required? true :doc "Driver identity: human, harness, or run id."}
                     :branch {:doc "Optional branch name."}
                     :worktree {:doc "Optional worktree path."}
                     :engine {:doc "Optional engine/source label: workflow, devflow, afk, agent, manual."}
                     :run-id {:doc "Optional engine run identifier."}
                     :source-id {:doc "Optional source/root strand id this entry mirrors."}
                     :body {:doc "Optional human-readable context."}}}
    "heartbeat" {:doc "Refresh an active roster entry's heartbeat."
                 :positionals [{:name :entry-id :required? true :doc "Roster entry strand id."}]}
    "finish" {:doc "Close an active roster entry with a final phase."
              :flags {:phase {:doc "finished (default) or abandoned."}
                      :outcome {:doc "Optional outcome string."}}
              :positionals [{:name :entry-id :required? true :doc "Roster entry strand id."}]}
    "list" {:doc "List active roster entries, optionally scoped."
            :flags {:feature {:doc "Scope by feature slug."}
                    :owner {:doc "Scope by owner."}
                    :branch {:doc "Scope by branch."}
                    :worktree {:doc "Scope by worktree path."}
                    :engine {:doc "Scope by engine label."}
                    :stale-after-secs {:type :int
                                       :doc "Override the staleness threshold in seconds (default fifteen minutes)."}}}
    "await-quiet" {:doc "Block until the selected scope has no active non-stale entries."
                   :flags {:feature {:doc "Scope by feature slug."}
                           :branch {:doc "Scope by branch."}
                           :worktree {:doc "Scope by worktree path."}
                           :timeout-secs {:type :int :doc "Optional timeout in seconds."}
                           :stale-after-secs {:type :int :doc "Override the staleness threshold in seconds."}}}}})

(def ^:private roster-namespace-declaration
  "The roster-owned `roster/*` attribute namespace stamped onto entry strands by
  `start-attributes`/`finish!`. Entries also carry the bare cross-spool
  `feature`/`owner`/`branch`/`worktree`/`body` convention keys, which roster
  writes as found and does not own. `:keys` is advisory."
  {:kind :attr-namespace
   :name "roster"
   :owner :skein/spools-roster
   :keys ["roster/entry" "roster/phase" "roster/started-at" "roster/heartbeat-at"
          "roster/finished-at" "roster/outcome" "roster/engine" "roster/run-id"
          "roster/source-id"]
   :doc "Active-work roster entry attributes written by skein.spools.roster/start!."})

(def ^:private strand-return
  {:type :map
   :required {:id :string :title :string :state :string
              :created_at :string :updated_at :string
              :attributes {:type :map :extra :json}}})

(def ^:private roster-row-return
  {:type :map
   :required {:strand strand-return :stale? :boolean :age-ms :integer}})

(def ^:private op-strand-return
  (update strand-return :required assoc :operation :string))

(def ^:private roster-returns
  {:subcommands
   {"about" {:type :map :required {:operation :string} :extra :json}
    "prime" {:type :map :required {:operation :string} :extra :json}
    "start" op-strand-return
    "heartbeat" op-strand-return
    "finish" op-strand-return
    "list" {:type :collection :items roster-row-return}
    "await-quiet" {:type :map
                   :required {:operation :string
                              :reason :string
                              :entries {:type :collection :items roster-row-return}}}}})

(defn install!
  "Install the roster op and named query into the active weaver."
  []
  (let [rt (current/runtime)]
    (watch! rt)
    (vocab/declare! rt roster-namespace-declaration)
    {:installed true
     :namespace 'skein.spools.roster
     :watcher integration-event-key
     :ops [(weaver/register-op! rt 'roster
                                {:doc "Manage active-work roster entries: start, heartbeat, finish, list, and await quiet."
                                 :arg-spec roster-arg-spec
                                 :returns roster-returns
                              ;; await-quiet blocks for arbitrarily long coordination waits (SPEC-RosterSpool-001.C10)
                                 :deadline-class :unbounded}
                                'skein.spools.roster/roster-op)]
     :queries [(graph/register-query! rt 'roster
                                      [:and
                                       [:= :state "active"]
                                       [:= [:attr "roster/entry"] "true"]])]}))
