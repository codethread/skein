(ns skein.roster-test
  "Tests for the explicit-runtime roster model and helpers, and the installed
  `roster` op/named query."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [skein.api.events.alpha :as events]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.roster :as roster]
            [skein.spools.test-support :as test-support :refer [with-runtime]]
            [skein.test.alpha :as t])
  (:import [java.time Duration Instant]))

(defn- attr
  [strand k]
  (get (:attributes strand) k))

(defn- op! [rt & argv]
  (weaver/op! rt 'roster argv))

(defn- op-entry [rt op-name]
  (some #(when (= (name op-name) (:name %)) %) (weaver/ops rt)))

(defn- wire-value [value]
  (json/read-str (json/write-str value) :key-fn keyword))

(defn- declared-subcommand-leaves [{:keys [name returns]}]
  (set (map (fn [subcommand] [name {:subcommand subcommand}])
            (keys (:subcommands returns)))))

(deftest production-return-coverage-is-derived-from-roster-provenance
  (with-runtime
    (fn [rt _]
      (roster/install!)
      (let [entries (filterv #(= 'skein.spools.roster (:provenance %)) (weaver/ops rt))
            missing (mapv :name (filter #(not (contains? % :returns)) entries))
            required (into #{} (mapcat declared-subcommand-leaves) entries)
            checked (atom #{})
            check! (fn [subcommand value]
                     (t/check-op-return! rt 'roster {:subcommand subcommand} (wire-value value))
                     (swap! checked conj ["roster" {:subcommand subcommand}])
                     value)
            _ (check! "about" (op! rt "about"))
            _ (check! "prime" (op! rt "prime"))
            tracked (check! "start" (op! rt "start" "--feature" "coverage" "--owner" "agent"))
            _ (check! "heartbeat" (op! rt "heartbeat" (:id tracked)))
            _ (check! "list" (op! rt "list" "--feature" "coverage"))
            _ (check! "await-quiet" (op! rt "await-quiet" "--feature" "coverage" "--timeout-secs" "0"))
            _ (check! "finish" (op! rt "finish" (:id tracked)))]
        (is (= [] missing))
        (is (= #{} (set/difference required @checked)))))))

(deftest start!-creates-a-new-active-entry
  (with-runtime
    (fn [rt _]
      (let [entry (roster/start! rt {:feature "roster-spool" :owner "agent-a"})]
        (is (= "active" (:state entry)))
        (is (= "true" (attr entry :roster/entry)))
        (is (= "roster-spool" (attr entry :feature)))
        (is (= "agent-a" (attr entry :owner)))
        (is (= "active" (attr entry :roster/phase)))
        (is (string? (attr entry :roster/started-at)))
        (is (= (attr entry :roster/started-at) (attr entry :roster/heartbeat-at)))
        (is (nil? (attr entry :branch)))))))

(deftest start!-stamps-optional-attributes-and-default-title
  (with-runtime
    (fn [rt _]
      (let [entry (roster/start! rt {:feature "f" :owner "o" :branch "b" :worktree "/tmp/w"
                                     :engine "afk" :run-id "run-1" :source-id "src-1" :body "context"})]
        (is (= "b" (attr entry :branch)))
        (is (= "/tmp/w" (attr entry :worktree)))
        (is (= "afk" (attr entry :roster/engine)))
        (is (= "run-1" (attr entry :roster/run-id)))
        (is (= "src-1" (attr entry :roster/source-id)))
        (is (= "context" (attr entry :body)))
        (is (= "Roster: f (o)" (:title entry)))))))

(deftest start!-fails-loudly-without-feature-or-owner
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"feature must be a non-blank string"
                            (roster/start! rt {:owner "agent-a"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"owner must be a non-blank string"
                            (roster/start! rt {:feature "f"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"received unknown keys"
                            (roster/start! rt {:feature "f" :owner "o" :bogus true})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"start! attrs must be a map"
                            (roster/start! rt nil))))))

(deftest start!-restamps-an-existing-strand-deriving-identity-and-preserving-attributes
  (with-runtime
    (fn [rt _]
      (let [source (weaver/add rt {:title "Workflow root"
                                   :state "closed"
                                   :attributes {:workflow/run-id "wf-1" :feature "roster-spool" :owner "agent-a"}})
            entry (roster/start! rt {:id (:id source)})]
        (is (= (:id source) (:id entry)))
        (is (= "active" (:state entry)))
        (is (= "wf-1" (attr entry :workflow/run-id)))
        (is (= "roster-spool" (attr entry :feature)))
        (is (= "agent-a" (attr entry :owner)))
        (is (= "Workflow root" (:title entry))))))
  (with-runtime
    (fn [rt _]
      (testing "an explicit id that does not exist fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"start! :id strand not found"
                              (roster/start! rt {:id "missing" :feature "f" :owner "o"})))))))

(deftest heartbeat!-updates-heartbeat-at-on-an-active-entry
  (with-runtime
    (fn [rt _]
      (let [entry (roster/start! rt {:feature "f" :owner "o" :now (Instant/parse "2020-01-01T00:00:00Z")})
            updated (roster/heartbeat! rt (:id entry) {:now (Instant/parse "2020-01-01T01:00:00Z")})]
        (is (= "2020-01-01T01:00:00Z" (attr updated :roster/heartbeat-at)))
        (is (= (attr entry :roster/started-at) (attr updated :roster/started-at)))))))

(deftest heartbeat!-fails-loudly-for-missing-closed-or-non-roster-ids
  (with-runtime
    (fn [rt _]
      (let [entry (roster/start! rt {:feature "f" :owner "o"})
            other (weaver/add rt {:title "Not a roster entry"})]
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

(deftest finish!-closes-the-entry-with-phase-outcome-and-finished-at
  (with-runtime
    (fn [rt _]
      (let [entry (roster/start! rt {:feature "f" :owner "o"})
            finished (roster/finish! rt (:id entry) {:phase "abandoned" :outcome "superseded"
                                                     :now (Instant/parse "2020-06-01T00:00:00Z")})]
        (is (= "closed" (:state finished)))
        (is (= "abandoned" (attr finished :roster/phase)))
        (is (= "superseded" (attr finished :roster/outcome)))
        (is (= "2020-06-01T00:00:00Z" (attr finished :roster/finished-at)))))))

(deftest finish!-defaults-phase-to-finished-and-validates-inputs
  (with-runtime
    (fn [rt _]
      (let [entry (roster/start! rt {:feature "f" :owner "o"})]
        (is (= "finished" (attr (roster/finish! rt (:id entry) {}) :roster/phase))))
      (let [entry (roster/start! rt {:feature "f" :owner "o"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":phase must be finished or abandoned"
                              (roster/finish! rt (:id entry) {:phase "done"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts must be a map"
                              (roster/finish! rt (:id entry) nil)))
        (roster/finish! rt (:id entry) {})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be active to finish"
                              (roster/finish! rt (:id entry) {})))))))

(deftest heartbeat-vs-finish-cannot-produce-contradictory-entries
  ;; Regression for the lost-update race: heartbeat! and finish! both used to
  ;; resend the whole stale attribute map, so a heartbeat committing just after
  ;; a concurrent finish could roll roster/phase back to "active" on a closed
  ;; strand. Both now send minimal deltas, so no interleaving can produce a
  ;; state=closed / roster/phase=active entry.
  (with-runtime
    (fn [rt _]
      (dotimes [_ 40]
        (let [entry (roster/start! rt {:feature "f" :owner "o"})
              id (:id entry)
              start (java.util.concurrent.CountDownLatch. 1)
              hb (future (.await start)
                         (try (roster/heartbeat! rt id)
                              (catch clojure.lang.ExceptionInfo _ :refused)))
              fin (future (.await start) (roster/finish! rt id {}))]
          (.countDown start)
          @hb @fin
          (let [final (weaver/show rt id)]
            (is (= "closed" (:state final)))
            (is (= "finished" (attr final :roster/phase))
                "a late heartbeat must never resurrect a finished entry to active")))))))

(deftest start!-heartbeat!-finish!-reject-a-non-instant-now-override
  (with-runtime
    (fn [rt _]
      (doseq [bad ["2020-01-01T00:00:00Z" 0 nil]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":now must be an Instant"
                              (roster/start! rt {:feature "f" :owner "o" :now bad}))))
      (let [entry (roster/start! rt {:feature "f" :owner "o"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":now must be an Instant"
                              (roster/heartbeat! rt (:id entry) {:now "2020-01-01T00:00:00Z"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":now must be an Instant"
                              (roster/finish! rt (:id entry) {:now nil})))))))

(deftest list-fails-loudly-for-a-missing-or-unparseable-heartbeat-timestamp
  ;; An operator can stamp roster/entry=true directly via the generic update op,
  ;; bypassing start!. Deriving staleness must then fail with the strand id, not
  ;; a bare NPE/DateTimeParseException naming nothing.
  (with-runtime
    (fn [rt _]
      (let [missing (weaver/add rt {:title "hand-stamped, no heartbeat"
                                    :attributes {:roster/entry "true"
                                                 :feature "f" :owner "o"}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing roster/heartbeat-at"
                              (roster/list rt {})))
        (roster/finish! rt (:id missing) {}))
      (let [bad (weaver/add rt {:title "hand-stamped, bad heartbeat"
                                :attributes {:roster/entry "true" :feature "f"
                                             :owner "o" :roster/heartbeat-at "not-an-instant"}})
            ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unparseable roster/heartbeat-at"
                                     (roster/list rt {})))]
        (is (= (:id bad) (:id (ex-data ex))) "failure names the offending strand")))))

(deftest list-returns-active-entries-scoped-by-attributes-and-excludes-finished
  (with-runtime
    (fn [rt _]
      (let [a (roster/start! rt {:feature "f1" :owner "agent-a" :branch "b1"})
            b (roster/start! rt {:feature "f2" :owner "agent-b" :branch "b2"})
            finished (roster/start! rt {:feature "f1" :owner "agent-a"})]
        (roster/finish! rt (:id finished) {})
        (testing "unscoped list returns only active entries"
          (is (= #{(:id a) (:id b)} (set (map (comp :id :strand) (roster/list rt {}))))))
        (testing "scoping by feature/owner/branch narrows the result"
          (is (= [(:id a)] (map (comp :id :strand) (roster/list rt {:feature "f1"}))))
          (is (= [(:id b)] (map (comp :id :strand) (roster/list rt {:owner "agent-b"}))))
          (is (= [(:id a)] (map (comp :id :strand) (roster/list rt {:branch "b1"})))))
        (testing "unknown scope keys fail loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"received unknown keys"
                                (roster/list rt {:bogus "x"}))))))))

(deftest list-derives-staleness-against-the-default-and-overridden-threshold
  (with-runtime
    (fn [rt _]
      (let [now (Instant/now)
            fresh (roster/start! rt {:feature "f" :owner "o" :now (.minus now (Duration/ofMinutes 5))})
            old (roster/start! rt {:feature "f" :owner "o" :now (.minus now (Duration/ofMinutes 20))})
            rows (roster/list rt {})
            by-id (into {} (map (juxt (comp :id :strand) identity)) rows)]
        (testing "default fifteen-minute threshold"
          (is (false? (:stale? (get by-id (:id fresh)))))
          (is (true? (:stale? (get by-id (:id old))))))
        (testing "overriding :stale-after-ms narrows the window"
          (let [rows (roster/list rt {:stale-after-ms (* 3 60 1000)})
                by-id (into {} (map (juxt (comp :id :strand) identity)) rows)]
            (is (true? (:stale? (get by-id (:id fresh)))))))
        (testing "non-positive thresholds fail loudly"
          (doseq [bad [0 -1 1.5 "900000" nil]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #":stale-after-ms must be a positive integer"
                                  (roster/list rt {:stale-after-ms bad})))))))))

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
        (let [tracked (roster/start! rt {:feature "f" :owner "o"})
              other (weaver/add rt {:title "Not a roster entry"})
              ids (set (map :id (weaver/list rt [:= [:attr "roster/entry"] "true"] {})))]
          (is (contains? ids (:id tracked)))
          (is (not (contains? ids (:id other))))
          (is (= [(:id tracked)] (mapv :id (weaver/list-query rt 'roster {})))))))))

(deftest install-declares-the-roster-attribute-namespace
  (with-runtime
    (fn [rt _]
      (roster/install!)
      (let [decl (vocab/declaration rt :attr-namespace "roster")]
        (is (some? decl))
        (is (= :skein/spools-roster (:owner decl)))))))

(deftest roster-declared-subcommands-help-and-parser-errors
  (with-runtime
    (fn [rt _]
      (roster/install!)
      (testing "help projections list the declared verb surface"
        (let [detail (weaver/op! rt 'help ["roster"])
              alias (op! rt "help")
              verbs (mapv :name (get-in detail [:arg-spec :subcommands]))]
          (is (= detail alias))
          (is (= ["about" "await-quiet" "finish" "heartbeat" "list" "prime" "start"] verbs))))
      (testing "missing and unknown verbs fail during parser routing with available names"
        (let [missing (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing subcommand"
                                            (op! rt)))
              unknown (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subcommand"
                                            (op! rt "bogus")))]
          (is (= :missing-subcommand (:reason (ex-data missing))))
          (is (= :unknown-subcommand (:reason (ex-data unknown))))
          (is (= ["about" "await-quiet" "finish" "heartbeat" "list" "prime" "start"]
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

(deftest roster-op-start-heartbeat-finish-and-list-round-trip
  (with-runtime
    (fn [rt _]
      (roster/install!)
      (let [tracked (op! rt "start" "--feature" "roster-spool" "--owner" "agent-a"
                         "--branch" "roster-spool" "--engine" "afk")]
        (is (= "active" (:state tracked)))
        (is (= "roster-spool" (attr tracked :feature)))
        (is (= "agent-a" (attr tracked :owner)))
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
        (testing "finish closes the entry with the requested phase/outcome"
          (let [finished (op! rt "finish" (:id tracked) "--phase" "abandoned" "--outcome" "superseded")]
            (is (= "closed" (:state finished)))
            (is (= "abandoned" (attr finished :roster/phase)))
            (is (= "superseded" (attr finished :roster/outcome)))))
        (testing "finished entries drop out of list"
          (is (= [] (op! rt "list" "--feature" "roster-spool"))))
        (testing "start requires --feature and --owner"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --feature"
                                (op! rt "start" "--owner" "agent-a")))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --owner"
                                (op! rt "start" "--feature" "f"))))))))

;; ---------------------------------------------------------------------------
;; await-quiet!
;; ---------------------------------------------------------------------------

(deftest await-quiet!-returns-quiet-immediately-with-no-active-entries-in-scope
  (with-runtime
    (fn [rt _]
      (let [result (roster/await-quiet! rt {:feature "roster-spool" :timeout-secs 0})]
        (is (= :quiet (:reason result)))
        (is (= [] (:entries result)))))))

(deftest await-quiet!-short-circuits-on-stale-entries-without-waiting-for-timeout
  (with-runtime
    (fn [rt _]
      (roster/start! rt {:feature "roster-spool" :owner "agent-a"
                         :now (.minus (Instant/now) (Duration/ofMinutes 20))})
      (let [start (System/currentTimeMillis)
            result (roster/await-quiet! rt {:feature "roster-spool" :timeout-secs 60})
            elapsed (- (System/currentTimeMillis) start)]
        (is (= :stale (:reason result)))
        (is (true? (:stale? (first (:entries result)))))
        (is (< elapsed 5000) "stale short-circuit must not wait out the timeout")))))

(deftest await-quiet!-returns-quiet-once-the-active-entry-finishes
  (with-runtime
    (fn [rt _]
      (let [entry (roster/start! rt {:feature "roster-spool" :owner "agent-a"})
            _finisher (future (Thread/sleep 100) (roster/finish! rt (:id entry) {}))
            result (roster/await-quiet! rt {:feature "roster-spool" :timeout-secs 2})]
        (is (= :quiet (:reason result)))
        (is (= [] (:entries result)))))))

(deftest await-quiet!-times-out-when-scope-stays-active-and-fresh
  (with-runtime
    (fn [rt _]
      (let [entry (roster/start! rt {:feature "roster-spool" :owner "agent-a"})
            result (roster/await-quiet! rt {:feature "roster-spool" :timeout-secs 1})]
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
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":timeout-secs must be a non-negative integer"
                              (roster/await-quiet! rt {:timeout-secs bad})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":poll-ms must be a non-negative integer"
                              (roster/await-quiet! rt {:poll-ms bad})))))))

(deftest roster-await-quiet-op-round-trips-through-the-cli
  (with-runtime
    (fn [rt _]
      (roster/install!)
      (testing "quiet when the scope has no active entries"
        (let [result (op! rt "await-quiet" "--feature" "roster-spool" "--timeout-secs" "0")]
          (is (= :quiet (:reason result)))
          (is (= [] (:entries result)))))
      (testing "stale short-circuits before the timeout"
        (roster/start! rt {:feature "roster-spool" :owner "agent-a"
                           :now (.minus (Instant/now) (Duration/ofMinutes 20))})
        (let [rows (op! rt "list" "--feature" "roster-spool" "--stale-after-secs" "900")
              result (op! rt "await-quiet" "--feature" "roster-spool"
                          "--timeout-secs" "60" "--stale-after-secs" "900")]
          (is (true? (:stale? (first rows))))
          (is (= :stale (:reason result)))))
      (testing "removed millisecond flags fail loudly"
        (doseq [[subcommand flag] [["await-quiet" "--timeout-ms"]
                                   ["list" "--stale-after-ms"]]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown flag"
                                (op! rt subcommand flag "1"))))))))

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
  (= "true" (attr (weaver/show rt id) :roster/entry)))

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
        (let [root (weaver/add rt {:title "Roster spool feature"
                                   :attributes {:devflow/feature "roster-spool" :owner "agent-a"}})]
          (is (wait-until #(stamped? rt (:id root)))
              "root should auto-stamp asynchronously")
          (let [entry (weaver/show rt (:id root))]
            (is (= (:id root) (:id entry)) "restamps in place, not a new strand")
            (is (= "roster-spool" (attr entry :feature)))
            (is (= "agent-a" (attr entry :owner)))
            (is (= "devflow" (attr entry :roster/engine)))
            (is (= "active" (attr entry :roster/phase)))
            (is (string? (attr entry :roster/heartbeat-at)))
            (is (= "Roster spool feature" (:title entry)) "title is preserved"))))
      (testing "a workflow root's run-id supplies the feature slug"
        (let [root (weaver/add rt {:title "Workflow root"
                                   :attributes {:workflow/run-id "wf-7" :owner "agent-b"}})]
          (is (wait-until #(stamped? rt (:id root))))
          (let [entry (weaver/show rt (:id root))]
            (is (= "wf-7" (attr entry :feature)))
            (is (= "workflow" (attr entry :roster/engine)))))))))

(deftest auto-stamp-carries-branch-and-worktree-into-the-roster-entry
  ;; PH4 roots (kanban cards, devflow/workflow roots) routinely carry plain
  ;; branch/worktree attrs; auto-stamping must copy them into
  ;; roster/branch/roster/worktree so branch-scoped roster/await-quiet! find
  ;; the auto-tracked root.
  (with-runtime
    (fn [rt _]
      (roster/watch! rt)
      (let [root (weaver/add rt {:title "Feature root on a branch"
                                 :attributes {:workflow/run-id "roster-spool" :owner "agent-a"
                                              :branch "roster-spool" :worktree "/tmp/wt"}})]
        (is (wait-until #(stamped? rt (:id root))))
        (let [entry (weaver/show rt (:id root))]
          (is (= "roster-spool" (attr entry :branch)))
          (is (= "/tmp/wt" (attr entry :worktree))))
        (testing "branch-scoped roster finds the auto-tracked root"
          (is (= [(:id root)]
                 (map (comp :id :strand) (roster/list rt {:branch "roster-spool"})))))))))

(deftest graph-mutations-refresh-the-tracked-root-heartbeat
  (with-runtime
    (fn [rt _]
      (let [baseline (Instant/parse "2020-01-01T00:00:00Z")
            root (roster/start! rt {:title "Tracked root"
                                    :feature "roster-spool"
                                    :owner "agent-a"
                                    :now baseline})
            child (weaver/add rt {:title "child task"})
            _ (weaver/update rt (:id root) {:edges [{:type "parent-of" :to (:id child)}]})]
        (roster/watch! rt)
        (testing "a mutation on a parent-of descendant refreshes the root's heartbeat"
          (let [later? (fn [] (let [now (attr (weaver/show rt (:id root)) :roster/heartbeat-at)]
                                (when (and now (.isAfter (Instant/parse now) baseline)) now)))]
            (weaver/update rt (:id child) {:title "child task (started)"})
            (is (wait-until later?)
                "updating a descendant must advance the root's roster/heartbeat-at")))))))

(deftest does-not-auto-stamp-roots-missing-identity-or-marked-plumbing
  (with-runtime
    (fn [rt _]
      (roster/watch! rt)
      (let [no-owner (weaver/add rt {:title "No owner" :attributes {:feature "roster-spool"}})
            no-feature (weaver/add rt {:title "No feature" :attributes {:owner "agent-a"}})
            plumbing (weaver/add rt {:title "Workflow root"
                                     :attributes {:workflow/role "root"
                                                  :workflow/run-id "roster-spool"
                                                  :owner "agent-a"}})
            closed (weaver/add rt {:title "Closed root" :state "closed"
                                   :attributes {:feature "roster-spool" :owner "agent-a"}})
            ;; a sufficient root added last is a FIFO barrier: once the single
            ;; event worker has stamped it, every earlier event has been handled.
            barrier (weaver/add rt {:title "Barrier root"
                                    :attributes {:feature "roster-spool" :owner "agent-a"}})]
        (is (wait-until #(stamped? rt (:id barrier)))
            "barrier root must auto-stamp")
        (testing "negative roots are left untouched by the handler"
          (is (not (stamped? rt (:id no-owner))))
          (is (not (stamped? rt (:id no-feature))))
          (is (not (stamped? rt (:id plumbing))))
          (is (not (stamped? rt (:id closed)))))))))
