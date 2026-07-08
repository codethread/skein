(ns skein.roster-test
  "Tests for the explicit-runtime roster model and helpers, and the installed
  `roster` op/named query."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.api.events.alpha :as events]
            [skein.api.weaver.alpha :as api]
            [skein.spools.roster :as roster]
            [skein.spools.test-support :as test-support :refer [with-runtime]])
  (:import [java.time Duration Instant]))

(defn- attr
  [strand k]
  (get (:attributes strand) k))

(defn- op! [rt & argv]
  (api/op! rt 'roster argv))

(defn- op-entry [rt op-name]
  (some #(when (= (name op-name) (:name %)) %) (api/ops rt)))

(deftest track!-creates-a-new-active-entry
  (with-runtime
    (fn [rt _]
      (let [entry (roster/track! rt {:feature "roster-spool" :owner "agent-a"})]
        (is (= "active" (:state entry)))
        (is (= "true" (attr entry :roster/entry)))
        (is (= "roster-spool" (attr entry :roster/feature)))
        (is (= "agent-a" (attr entry :roster/owner)))
        (is (= "active" (attr entry :roster/status)))
        (is (= "roster-spool" (attr entry :feature)))
        (is (= "agent-a" (attr entry :owner)))
        (is (string? (attr entry :roster/started-at)))
        (is (= (attr entry :roster/started-at) (attr entry :roster/heartbeat-at)))
        (is (nil? (attr entry :roster/branch)))))))

(deftest track!-stamps-optional-attributes-and-default-title
  (with-runtime
    (fn [rt _]
      (let [entry (roster/track! rt {:feature "f" :owner "o" :branch "b" :worktree "/tmp/w"
                                     :engine "afk" :run-id "run-1" :source-id "src-1" :body "context"})]
        (is (= "b" (attr entry :roster/branch)))
        (is (= "b" (attr entry :branch)))
        (is (= "/tmp/w" (attr entry :roster/worktree)))
        (is (= "/tmp/w" (attr entry :worktree)))
        (is (= "afk" (attr entry :roster/engine)))
        (is (= "run-1" (attr entry :roster/run-id)))
        (is (= "src-1" (attr entry :roster/source-id)))
        (is (= "context" (attr entry :roster/body)))
        (is (= "Roster: f (o)" (:title entry)))))))

(deftest track!-fails-loudly-without-feature-or-owner
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"feature must be a non-blank string"
                            (roster/track! rt {:owner "agent-a"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"owner must be a non-blank string"
                            (roster/track! rt {:feature "f"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"received unknown keys"
                            (roster/track! rt {:feature "f" :owner "o" :bogus true})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"track! attrs must be a map"
                            (roster/track! rt nil))))))

(deftest track!-restamps-an-existing-strand-deriving-identity-and-preserving-attributes
  (with-runtime
    (fn [rt _]
      (let [source (api/add rt {:title "Workflow root"
                                :state "closed"
                                :attributes {:workflow/run-id "wf-1" :feature "roster-spool" :owner "agent-a"}})
            entry (roster/track! rt {:id (:id source)})]
        (is (= (:id source) (:id entry)))
        (is (= "active" (:state entry)))
        (is (= "wf-1" (attr entry :workflow/run-id)))
        (is (= "roster-spool" (attr entry :roster/feature)))
        (is (= "agent-a" (attr entry :roster/owner)))
        (is (= "Workflow root" (:title entry))))))
  (with-runtime
    (fn [rt _]
      (testing "an explicit id that does not exist fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"track! :id strand not found"
                              (roster/track! rt {:id "missing" :feature "f" :owner "o"})))))))

(deftest heartbeat!-updates-heartbeat-at-on-an-active-entry
  (with-runtime
    (fn [rt _]
      (let [entry (roster/track! rt {:feature "f" :owner "o" :now (Instant/parse "2020-01-01T00:00:00Z")})
            updated (roster/heartbeat! rt (:id entry) {:now (Instant/parse "2020-01-01T01:00:00Z")})]
        (is (= "2020-01-01T01:00:00Z" (attr updated :roster/heartbeat-at)))
        (is (= (attr entry :roster/started-at) (attr updated :roster/started-at)))))))

(deftest heartbeat!-fails-loudly-for-missing-closed-or-non-roster-ids
  (with-runtime
    (fn [rt _]
      (let [entry (roster/track! rt {:feature "f" :owner "o"})
            other (api/add rt {:title "Not a roster entry"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Roster entry not found"
                              (roster/heartbeat! rt "missing")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Strand is not a roster entry"
                              (roster/heartbeat! rt (:id other))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"heartbeat! opts must be a map"
                              (roster/heartbeat! rt (:id entry) :not-a-map)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (roster/heartbeat! rt (:id entry) {:bogus 1})))
        (roster/finish! rt (:id entry) {})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be active to heartbeat"
                              (roster/heartbeat! rt (:id entry))))))))

(deftest finish!-closes-the-entry-with-status-result-and-finished-at
  (with-runtime
    (fn [rt _]
      (let [entry (roster/track! rt {:feature "f" :owner "o"})
            finished (roster/finish! rt (:id entry) {:status "abandoned" :result "superseded"
                                                     :now (Instant/parse "2020-06-01T00:00:00Z")})]
        (is (= "closed" (:state finished)))
        (is (= "abandoned" (attr finished :roster/status)))
        (is (= "superseded" (attr finished :roster/result)))
        (is (= "2020-06-01T00:00:00Z" (attr finished :roster/finished-at)))))))

(deftest finish!-defaults-status-to-finished-and-validates-inputs
  (with-runtime
    (fn [rt _]
      (let [entry (roster/track! rt {:feature "f" :owner "o"})]
        (is (= "finished" (attr (roster/finish! rt (:id entry) {}) :roster/status))))
      (let [entry (roster/track! rt {:feature "f" :owner "o"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":status must be finished or abandoned"
                              (roster/finish! rt (:id entry) {:status "done"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts must be a map"
                              (roster/finish! rt (:id entry) nil)))
        (roster/finish! rt (:id entry) {})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be active to finish"
                              (roster/finish! rt (:id entry) {})))))))

(deftest heartbeat-vs-finish-cannot-produce-contradictory-entries
  ;; Regression for the lost-update race: heartbeat! and finish! both used to
  ;; resend the whole stale attribute map, so a heartbeat committing just after
  ;; a concurrent finish could roll roster/status back to "active" on a closed
  ;; strand. Both now send minimal deltas, so no interleaving can produce a
  ;; state=closed / roster/status=active entry.
  (with-runtime
    (fn [rt _]
      (dotimes [_ 40]
        (let [entry (roster/track! rt {:feature "f" :owner "o"})
              id (:id entry)
              start (java.util.concurrent.CountDownLatch. 1)
              hb (future (.await start)
                         (try (roster/heartbeat! rt id)
                              (catch clojure.lang.ExceptionInfo _ :refused)))
              fin (future (.await start) (roster/finish! rt id {}))]
          (.countDown start)
          @hb @fin
          (let [final (api/show rt id)]
            (is (= "closed" (:state final)))
            (is (= "finished" (attr final :roster/status))
                "a late heartbeat must never resurrect a finished entry to active")))))))

(deftest track!-heartbeat!-finish!-reject-a-non-instant-now-override
  (with-runtime
    (fn [rt _]
      (doseq [bad ["2020-01-01T00:00:00Z" 0 nil]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":now must be an Instant"
                              (roster/track! rt {:feature "f" :owner "o" :now bad}))))
      (let [entry (roster/track! rt {:feature "f" :owner "o"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":now must be an Instant"
                              (roster/heartbeat! rt (:id entry) {:now "2020-01-01T00:00:00Z"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":now must be an Instant"
                              (roster/finish! rt (:id entry) {:now nil})))))))

(deftest roster-fails-loudly-for-a-missing-or-unparseable-heartbeat-timestamp
  ;; An operator can stamp roster/entry=true directly via the generic update op,
  ;; bypassing track!. Deriving staleness must then fail with the strand id, not
  ;; a bare NPE/DateTimeParseException naming nothing.
  (with-runtime
    (fn [rt _]
      (let [missing (api/add rt {:title "hand-stamped, no heartbeat"
                                 :attributes {:roster/entry "true"
                                              :roster/feature "f" :roster/owner "o"}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing roster/heartbeat-at"
                              (roster/roster rt {})))
        (roster/finish! rt (:id missing) {}))
      (let [bad (api/add rt {:title "hand-stamped, bad heartbeat"
                             :attributes {:roster/entry "true" :roster/feature "f"
                                          :roster/owner "o" :roster/heartbeat-at "not-an-instant"}})
            ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unparseable roster/heartbeat-at"
                                     (roster/roster rt {})))]
        (is (= (:id bad) (:id (ex-data ex))) "failure names the offending strand")))))

(deftest roster-lists-active-entries-scoped-by-attributes-and-excludes-finished
  (with-runtime
    (fn [rt _]
      (let [a (roster/track! rt {:feature "f1" :owner "agent-a" :branch "b1"})
            b (roster/track! rt {:feature "f2" :owner "agent-b" :branch "b2"})
            finished (roster/track! rt {:feature "f1" :owner "agent-a"})]
        (roster/finish! rt (:id finished) {})
        (testing "unscoped roster returns only active entries"
          (is (= #{(:id a) (:id b)} (set (map (comp :id :strand) (roster/roster rt {}))))))
        (testing "scoping by feature/owner/branch narrows the result"
          (is (= [(:id a)] (map (comp :id :strand) (roster/roster rt {:feature "f1"}))))
          (is (= [(:id b)] (map (comp :id :strand) (roster/roster rt {:owner "agent-b"}))))
          (is (= [(:id a)] (map (comp :id :strand) (roster/roster rt {:branch "b1"})))))
        (testing "unknown scope keys fail loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"received unknown keys"
                                (roster/roster rt {:bogus "x"}))))))))

(deftest roster-derives-staleness-against-the-default-and-overridden-threshold
  (with-runtime
    (fn [rt _]
      (let [now (Instant/now)
            fresh (roster/track! rt {:feature "f" :owner "o" :now (.minus now (Duration/ofMinutes 5))})
            old (roster/track! rt {:feature "f" :owner "o" :now (.minus now (Duration/ofMinutes 20))})
            rows (roster/roster rt {})
            by-id (into {} (map (juxt (comp :id :strand) identity)) rows)]
        (testing "default fifteen-minute threshold"
          (is (false? (:stale? (get by-id (:id fresh)))))
          (is (true? (:stale? (get by-id (:id old))))))
        (testing "overriding :stale-after-ms narrows the window"
          (let [rows (roster/roster rt {:stale-after-ms (* 3 60 1000)})
                by-id (into {} (map (juxt (comp :id :strand) identity)) rows)]
            (is (true? (:stale? (get by-id (:id fresh)))))))
        (testing "non-positive thresholds fail loudly"
          (doseq [bad [0 -1 1.5 "900000" nil]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #":stale-after-ms must be a positive integer"
                                  (roster/roster rt {:stale-after-ms bad})))))))))

;; ---------------------------------------------------------------------------
;; install!: op registration, named query
;; ---------------------------------------------------------------------------

(deftest install-registers-the-roster-op-and-named-query
  (with-runtime
    (fn [rt _]
      (roster/install!)
      (testing "op registered with provenance and an unbounded deadline (await-quiet blocks)"
        (let [entry (op-entry rt 'roster)]
          (is (some? entry))
          (is (= 'skein.spools.roster (:provenance entry)))
          (is (= :unbounded (:deadline-class entry)))
          (is (some? (:arg-spec entry)))))
      (testing "named roster query defaults to active roster entries"
        (let [tracked (roster/track! rt {:feature "f" :owner "o"})
              other (api/add rt {:title "Not a roster entry"})
              ids (set (map :id (api/list rt [:= [:attr "roster/entry"] "true"] {})))]
          (is (contains? ids (:id tracked)))
          (is (not (contains? ids (:id other))))
          (is (= [(:id tracked)] (mapv :id (api/list-query rt 'roster {})))))))))

(deftest roster-declared-subcommands-help-and-parser-errors
  (with-runtime
    (fn [rt _]
      (roster/install!)
      (testing "help projections list the declared verb surface"
        (let [detail (api/op! rt 'help ["roster"])
              alias (op! rt "help")
              verbs (mapv :name (get-in detail [:arg-spec :subcommands]))]
          (is (= detail alias))
          (is (= ["about" "await-quiet" "finish" "heartbeat" "list" "prime" "track"] verbs))))
      (testing "missing and unknown verbs fail during parser routing with available names"
        (let [missing (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing subcommand"
                                            (op! rt)))
              unknown (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subcommand"
                                            (op! rt "bogus")))]
          (is (= :missing-subcommand (:reason (ex-data missing))))
          (is (= :unknown-subcommand (:reason (ex-data unknown))))
          (is (= ["about" "await-quiet" "finish" "heartbeat" "list" "prime" "track"]
                 (:available-subcommands (ex-data missing)))))))))

(deftest roster-about-and-prime-payload-shapes
  (with-runtime
    (fn [rt _]
      (roster/install!)
      (let [about (op! rt "about")
            prime (op! rt "prime")]
        (is (= "roster about" (:operation about)))
        (is (= "roster prime" (:operation prime)))
        (testing "prime reuses about's attribute/api/command surface"
          (is (= (:attributes about) (:attributes prime)))
          (is (= (:api about) (:api prime)))
          (is (= (:commands about) (:commands prime))))
        (testing "prime carries working discipline about does not"
          (is (seq (:working-agreement prime)))
          (is (seq (:tracking-discipline prime)))
          (is (seq (:staying-aware prime)))
          (is (nil? (:working-agreement about))))))))

(deftest roster-op-track-heartbeat-finish-and-list-round-trip
  (with-runtime
    (fn [rt _]
      (roster/install!)
      (let [tracked (op! rt "track" "--feature" "roster-spool" "--owner" "agent-a"
                         "--branch" "roster-spool" "--engine" "afk")]
        (is (= "active" (:state tracked)))
        (is (= "roster-spool" (attr tracked :roster/feature)))
        (is (= "agent-a" (attr tracked :roster/owner)))
        (is (= "afk" (attr tracked :roster/engine)))
        (testing "list scopes by feature/owner/branch and returns stale-derived rows"
          (let [rows (op! rt "list" "--feature" "roster-spool")]
            (is (= [(:id tracked)] (map (comp :id :strand) rows)))
            (is (false? (:stale? (first rows))))))
        (testing "heartbeat refreshes roster/heartbeat-at"
          (let [before (attr tracked :roster/heartbeat-at)
                heartbeated (op! rt "heartbeat" (:id tracked))]
            (is (= before (attr heartbeated :roster/started-at)))
            (is (string? (attr heartbeated :roster/heartbeat-at)))))
        (testing "finish closes the entry with the requested status/result"
          (let [finished (op! rt "finish" (:id tracked) "--status" "abandoned" "--result" "superseded")]
            (is (= "closed" (:state finished)))
            (is (= "abandoned" (attr finished :roster/status)))
            (is (= "superseded" (attr finished :roster/result)))))
        (testing "finished entries drop out of list"
          (is (= [] (op! rt "list" "--feature" "roster-spool"))))
        (testing "track requires --feature and --owner"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --feature"
                                (op! rt "track" "--owner" "agent-a")))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --owner"
                                (op! rt "track" "--feature" "f"))))))))

;; ---------------------------------------------------------------------------
;; await-quiet!
;; ---------------------------------------------------------------------------

(deftest await-quiet!-returns-quiet-immediately-with-no-active-entries-in-scope
  (with-runtime
    (fn [rt _]
      (let [result (roster/await-quiet! rt {:feature "roster-spool" :timeout-ms 0})]
        (is (= :quiet (:reason result)))
        (is (= [] (:entries result)))))))

(deftest await-quiet!-short-circuits-on-stale-entries-without-waiting-for-timeout
  (with-runtime
    (fn [rt _]
      (roster/track! rt {:feature "roster-spool" :owner "agent-a"
                         :now (.minus (Instant/now) (Duration/ofMinutes 20))})
      (let [start (System/currentTimeMillis)
            result (roster/await-quiet! rt {:feature "roster-spool" :timeout-ms 60000})
            elapsed (- (System/currentTimeMillis) start)]
        (is (= :stale (:reason result)))
        (is (true? (:stale? (first (:entries result)))))
        (is (< elapsed 5000) "stale short-circuit must not wait out the timeout")))))

(deftest await-quiet!-returns-quiet-once-the-active-entry-finishes
  (with-runtime
    (fn [rt _]
      (let [entry (roster/track! rt {:feature "roster-spool" :owner "agent-a"})
            _finisher (future (Thread/sleep 100) (roster/finish! rt (:id entry) {}))
            result (roster/await-quiet! rt {:feature "roster-spool" :timeout-ms 2000})]
        (is (= :quiet (:reason result)))
        (is (= [] (:entries result)))))))

(deftest await-quiet!-times-out-when-scope-stays-active-and-fresh
  (with-runtime
    (fn [rt _]
      (let [entry (roster/track! rt {:feature "roster-spool" :owner "agent-a"})
            result (roster/await-quiet! rt {:feature "roster-spool" :timeout-ms 100})]
        (is (= :timeout (:reason result)))
        (is (= [(:id entry)] (map (comp :id :strand) (:entries result))))))))

(deftest await-quiet!-fails-loudly-for-malformed-opts
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts must be a map"
                            (roster/await-quiet! rt nil)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"received unknown keys"
                            (roster/await-quiet! rt {:owner "agent-a"})))
      (doseq [bad [-1 1.5 "1000"]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":timeout-ms must be a non-negative integer"
                              (roster/await-quiet! rt {:timeout-ms bad})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":poll-ms must be a non-negative integer"
                              (roster/await-quiet! rt {:poll-ms bad})))))))

(deftest roster-await-quiet-op-round-trips-through-the-cli
  (with-runtime
    (fn [rt _]
      (roster/install!)
      (testing "quiet when the scope has no active entries"
        (let [result (op! rt "await-quiet" "--feature" "roster-spool" "--timeout-ms" "0")]
          (is (= :quiet (:reason result)))
          (is (= [] (:entries result)))))
      (testing "stale short-circuits before the timeout"
        (roster/track! rt {:feature "roster-spool" :owner "agent-a"
                           :now (.minus (Instant/now) (Duration/ofMinutes 20))})
        (let [result (op! rt "await-quiet" "--feature" "roster-spool"
                          "--timeout-ms" "60000" "--stale-after-ms" "900000")]
          (is (= :stale (:reason result))))))))

;; ---------------------------------------------------------------------------
;; workflow/devflow graph integration (async event handler)
;; ---------------------------------------------------------------------------

(defn- wait-until
  "Poll `thunk` every 10ms until it returns truthy or the await budget
  elapses, returning the last observed value. Integration events are
  delivered on the async weaver event worker, so tests must wait rather than
  read synchronously."
  [thunk]
  (test-support/poll-until thunk
                           {:timeout-ms (test-support/await-budget-ms 5000)
                            :interval-ms 10
                            :on-timeout (constantly nil)}))

(defn- stamped? [rt id]
  (= "true" (attr (api/show rt id) :roster/entry)))

(deftest install!-registers-the-integration-event-handler
  (with-runtime
    (fn [rt _]
      (let [{:keys [watcher]} (roster/install!)]
        (is (= :skein.spools.roster/integration watcher))
        (is (contains? (set (map :key (events/handlers rt)))
                       :skein.spools.roster/integration))))))

(deftest auto-stamps-active-non-plumbing-roots-carrying-feature-and-owner
  (with-runtime
    (fn [rt _]
      (roster/watch! rt)
      (testing "a devflow feature root is restamped into a roster entry in place"
        (let [root (api/add rt {:title "Roster spool feature"
                                :attributes {:devflow/feature "roster-spool" :owner "agent-a"}})]
          (is (wait-until #(stamped? rt (:id root)))
              "root should auto-stamp asynchronously")
          (let [entry (api/show rt (:id root))]
            (is (= (:id root) (:id entry)) "restamps in place, not a new strand")
            (is (= "roster-spool" (attr entry :roster/feature)))
            (is (= "agent-a" (attr entry :roster/owner)))
            (is (= "devflow" (attr entry :roster/engine)))
            (is (= "active" (attr entry :roster/status)))
            (is (string? (attr entry :roster/heartbeat-at)))
            (is (= "Roster spool feature" (:title entry)) "title is preserved"))))
      (testing "a workflow root's run-id supplies the feature slug"
        (let [root (api/add rt {:title "Workflow root"
                                :attributes {:workflow/run-id "wf-7" :owner "agent-b"}})]
          (is (wait-until #(stamped? rt (:id root))))
          (let [entry (api/show rt (:id root))]
            (is (= "wf-7" (attr entry :roster/feature)))
            (is (= "workflow" (attr entry :roster/engine)))))))))

(deftest auto-stamp-carries-branch-and-worktree-into-the-roster-entry
  ;; PH4 roots (kanban cards, devflow/workflow roots) routinely carry plain
  ;; branch/worktree attrs; auto-stamping must copy them into
  ;; roster/branch/roster/worktree so branch-scoped roster/await-quiet! find
  ;; the auto-tracked root.
  (with-runtime
    (fn [rt _]
      (roster/watch! rt)
      (let [root (api/add rt {:title "Feature root on a branch"
                              :attributes {:workflow/run-id "roster-spool" :owner "agent-a"
                                           :branch "roster-spool" :worktree "/tmp/wt"}})]
        (is (wait-until #(stamped? rt (:id root))))
        (let [entry (api/show rt (:id root))]
          (is (= "roster-spool" (attr entry :roster/branch)))
          (is (= "/tmp/wt" (attr entry :roster/worktree))))
        (testing "branch-scoped roster finds the auto-tracked root"
          (is (= [(:id root)]
                 (map (comp :id :strand) (roster/roster rt {:branch "roster-spool"})))))))))

(deftest graph-mutations-refresh-the-tracked-root-heartbeat
  (with-runtime
    (fn [rt _]
      (roster/watch! rt)
      (let [root (api/add rt {:title "Tracked root"
                              :attributes {:feature "roster-spool" :owner "agent-a"}})]
        (is (wait-until #(stamped? rt (:id root))))
        (testing "a mutation on a parent-of descendant refreshes the root's heartbeat"
          (let [child (api/add rt {:title "child task"})
                _ (api/update rt (:id root) {:edges [{:type "parent-of" :to (:id child)}]})
                baseline (Instant/parse (attr (api/show rt (:id root)) :roster/heartbeat-at))
                later? (fn [] (let [now (attr (api/show rt (:id root)) :roster/heartbeat-at)]
                                (when (and now (.isAfter (Instant/parse now) baseline)) now)))]
            ;; ensure the wall clock advances so a fresh heartbeat is strictly later
            (Thread/sleep 5)
            (api/update rt (:id child) {:title "child task (started)"})
            (is (wait-until later?)
                "updating a descendant must advance the root's roster/heartbeat-at")))))))

(deftest does-not-auto-stamp-roots-missing-identity-or-marked-plumbing
  (with-runtime
    (fn [rt _]
      (roster/watch! rt)
      (let [no-owner (api/add rt {:title "No owner" :attributes {:feature "roster-spool"}})
            no-feature (api/add rt {:title "No feature" :attributes {:owner "agent-a"}})
            plumbing (api/add rt {:title "Workflow molecule"
                                  :attributes {:workflow/role "molecule"
                                               :workflow/run-id "roster-spool"
                                               :owner "agent-a"}})
            closed (api/add rt {:title "Closed root" :state "closed"
                                :attributes {:feature "roster-spool" :owner "agent-a"}})
            ;; a sufficient root added last is a FIFO barrier: once the single
            ;; event worker has stamped it, every earlier event has been handled.
            barrier (api/add rt {:title "Barrier root"
                                 :attributes {:feature "roster-spool" :owner "agent-a"}})]
        (is (wait-until #(stamped? rt (:id barrier)))
            "barrier root must auto-stamp")
        (testing "negative roots are left untouched by the handler"
          (is (not (stamped? rt (:id no-owner))))
          (is (not (stamped? rt (:id no-feature))))
          (is (not (stamped? rt (:id plumbing))))
          (is (not (stamped? rt (:id closed)))))))))
