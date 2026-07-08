(ns skein.kanban-test
  "Tests for the kanban board spool against a disposable weaver runtime."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.runtime.alpha :as runtime-alpha]
            [skein.api.weaver.alpha :as api]
            [skein.spools.format :as fmt]
            [skein.spools.kanban :as kanban]
            [skein.spools.test-support :refer [with-runtime]]))

(defn- spool-root []
  (.getCanonicalPath (io/file "spools/kanban")))

(defn- install-kanban! [rt config-dir]
  (spit (io/file config-dir "spools.edn")
        (pr-str {:spools {'skein.spools/kanban {:local/root (spool-root)}}}))
  (runtime-alpha/sync! rt)
  (runtime-alpha/use! rt :skein/spools-kanban
                      {:ns 'skein.spools.kanban
                       :spools ['skein.spools/kanban]
                       :call 'skein.spools.kanban/install!
                       :required? true}))

(defn- op! [rt & argv]
  (api/op! rt 'kanban argv))

(deftest kanban-about-commands-match-declared-subcommands
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [detail (api/resolve-op rt 'kanban)
            manual-entries (-> (kanban/about) :commands)
            manual-commands (set (keep :verb manual-entries))
            declared-commands (set (keys (get-in detail [:arg-spec :subcommands])))]
        (is (every? #(or (:verb %) (:repl %)) manual-entries)
            "every kanban about command entry must carry :verb or documented :repl")
        (is (empty? (set/difference manual-commands declared-commands))
            (str "kanban about commands missing from arg-spec: " (sort (set/difference manual-commands declared-commands))))
        (is (empty? (set/difference declared-commands manual-commands))
            (str "kanban arg-spec subcommands missing from about: " (sort (set/difference declared-commands manual-commands))))))))

(deftest kanban-add-next-claim-and-finish-round-trip
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (is (some #(= "kanban" (:name %)) (api/ops rt)))
      (testing "add creates a pending feature card"
        (let [added (op! rt "add" "Build active work convention" "--source" "devflow/rfcs/2026-07-02-feature-tracking-registry.md")
              id (get-in added [:card :id])
              stored (api/show rt id)]
          (is (= "Build active work convention" (:title stored)))
          (is (= "true" (get-in stored [:attributes :kanban/card])))
          (is (= "pending" (get-in stored [:attributes :kanban/status])))
          (is (= "feature" (get-in stored [:attributes :kanban/type])))
          (is (= "devflow/rfcs/2026-07-02-feature-tracking-registry.md"
                 (get-in stored [:attributes :kanban/source])))
          (testing "next serves the oldest pending feature"
            (is (= id (get-in (op! rt "next") [:next :id]))))
          (testing "claim requires owner and branch"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires --owner"
                                  (op! rt "claim" id "--branch" "feature-branch")))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires --branch"
                                  (op! rt "claim" id "--owner" "agent"))))
          (testing "claim stamps status and work-root attributes"
            (let [claimed (op! rt "claim" id "--owner" "agent" "--branch" "kanban-spool"
                               "--worktree" "/tmp/wt")]
              (is (= "claimed" (get-in claimed [:card :attributes :kanban/status])))
              (is (= "agent" (get-in claimed [:card :attributes :owner])))
              (is (= "kanban-spool" (get-in claimed [:card :attributes :branch])))
              ;; regression: the claimed status must survive the round trip to
              ;; storage (string/keyword attr-key collisions once dropped it)
              (is (= "claimed" (get-in (api/show rt id) [:attributes :kanban/status])))
              (is (nil? (:next (op! rt "next"))))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be pending"
                                    (op! rt "claim" id "--owner" "other" "--branch" "b")))))
          (testing "review, rework, and finish enforce the review lane"
            (let [reviewing (op! rt "review" id)]
              (is (= "in_review" (get-in reviewing [:card :attributes :kanban/status])))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be claimed"
                                    (op! rt "review" id)))
              (is (= "claimed" (get-in (op! rt "rework" id) [:card :attributes :kanban/status])))
              (is (= "in_review" (get-in (op! rt "review" id) [:card :attributes :kanban/status]))))
            (let [finished (op! rt "finish" id)]
              (is (= "closed" (get-in finished [:card :state])))
              (is (= "done" (get-in finished [:card :attributes :kanban/status]))))))))))

(deftest kanban-declared-subcommands-help-and-parser-errors
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (testing "help projections list the declared verb surface"
        (let [detail (api/op! rt 'help ["kanban"])
              alias (op! rt "help")
              verbs (mapv :name (get-in detail [:arg-spec :subcommands]))]
          (is (= detail alias))
          (is (= ["about" "add" "board" "card" "claim" "finish" "next" "note" "prime" "priority" "promote" "review" "rework"] verbs))
          (is (some #(= "about" (:name %)) (get-in alias [:arg-spec :subcommands])))))
      (testing "missing and unknown verbs fail during parser routing with available names"
        (let [missing (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing subcommand"
                                            (op! rt)))
              unknown (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subcommand"
                                            (op! rt "bogus")))]
          (is (= :missing-subcommand (:reason (ex-data missing))))
          (is (= :unknown-subcommand (:reason (ex-data unknown))))
          (is (= ["about" "add" "board" "card" "claim" "finish" "next" "note" "prime" "priority" "promote" "review" "rework"]
                 (:available-subcommands (ex-data missing))))
          (is (= (:available-subcommands (ex-data missing))
                 (:available-subcommands (ex-data unknown)))))))))

(deftest kanban-prime-supersets-about-with-working-discipline
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [prime (op! rt "prime")
            about (op! rt "about")]
        (is (= "kanban prime" (:operation prime)))
        (testing "prime reuses about's command/lane/attribute surface"
          (is (= (:commands about) (:commands prime)))
          (is (= (:lanes about) (:lanes prime)))
          (is (= (:attributes about) (:attributes prime))))
        (testing "prime carries the working discipline about does not"
          (is (seq (:working-agreement prime)))
          (is (seq (:pick-up-next-card prime)))
          (is (seq (:notes-and-handovers prime)))
          (is (seq (:staying-aware prime)))
          (is (string? (:branch-visibility prime)))
          (is (nil? (:working-agreement about))))
        (testing "prime's fill-authored blocks wrap into single-line prose items"
          (is (= 4 (count (:working-agreement prime))))
          (is (= 3 (count (:staying-aware prime))))
          (is (every? #(nil? (re-find #"\n" %)) (:staying-aware prime))))
        (testing "prime advertises itself in the command surface without duplicating usage"
          (is (some #(= "prime" (:verb %)) (:commands prime)))
          (is (= "strand help kanban" (get-in prime [:discovery :help]))))))))

(deftest fill-wraps-prose-and-preserves-indented-blocks
  (testing "flush-left lines soft-wrap; a bare bar starts a new item; an indented line keeps the item verbatim"
    (is (= ["Prose that is long enough to wrap across two source lines."
            "Before running:\n    strand kanban prime\n    strand kanban board"]
           (fmt/fill "
                     |Prose that is long enough to
                     |wrap across two source lines.
                     |
                     |Before running:
                     |    strand kanban prime
                     |    strand kanban board"))))
  (testing "reflow soft-wraps a single-paragraph block into one string"
    (is (= "One sentence spread over two source lines."
           (fmt/reflow "
                       |One sentence spread over
                       |two source lines."))))
  (testing "a bar-less block is an authoring error, not empty output"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no barred lines"
                          (fmt/fill "prose that lost its bars")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no barred lines"
                          (fmt/reflow "prose that lost its bars")))))

(deftest kanban-refinement-lane-and-promote
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [idea (op! rt "add" "Vague idea" "--status" "refinement")
            idea-id (get-in idea [:card :id])]
        (is (= "refinement" (get-in idea [:card :attributes :kanban/status])))
        (testing "refinement cards are not actionable"
          (is (nil? (:next (op! rt "next"))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be pending"
                                (op! rt "claim" idea-id "--owner" "a" "--branch" "b"))))
        (testing "promote moves the card into the pending lane"
          (is (= "pending" (get-in (op! rt "promote" idea-id)
                                   [:card :attributes :kanban/status])))
          (is (= idea-id (get-in (op! rt "next") [:next :id])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be refinement"
                                (op! rt "promote" idea-id))))
        (testing "add rejects unknown statuses and types"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pending or refinement"
                                (op! rt "add" "Bad lane" "--status" "someday")))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"feature or epic"
                                (op! rt "add" "Bad type" "--type" "story"))))))))

(deftest kanban-priority-orders-lanes-and-next
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [old-default (get-in (op! rt "add" "Default work") [:card :id])
            someday (get-in (op! rt "add" "Someday idea" "--priority" "p4") [:card :id])
            blocker (get-in (op! rt "add" "Breaking change blocker" "--priority" "p1") [:card :id])]
        (testing "add stamps p3 unless told otherwise and validates the flag"
          (is (= "p3" (get-in (api/show rt old-default) [:attributes :kanban/priority])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"p1, p2, p3, p4"
                                (op! rt "add" "Bad priority" "--priority" "urgent"))))
        (testing "next serves the highest priority first despite creation order"
          (is (= blocker (get-in (op! rt "next") [:next :id]))))
        (testing "board lanes sort p1 first and expose :priority on compact cards"
          (let [pending (:pending (op! rt "board"))]
            (is (= [blocker old-default someday] (mapv :id pending)))
            (is (= ["p1" "p3" "p4"] (mapv :priority pending)))))
        (testing "cards that predate priorities read as p3"
          (let [legacy (api/add rt {:title "Legacy card"
                                    :attributes {:kanban/card "true"
                                                 :kanban/status "pending"
                                                 :kanban/type "feature"}})
                on-board (some #(when (= (:id legacy) (:id %)) %)
                               (:pending (op! rt "board")))]
            (is (= "p3" (:priority on-board)))))
        (testing "priority reprioritises an active card and fails loudly otherwise"
          (is (= "p2" (get-in (op! rt "priority" someday "p2")
                              [:card :attributes :kanban/priority])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"p1, p2, p3, p4"
                                (op! rt "priority" someday "p9")))
          (op! rt "claim" blocker "--owner" "agent" "--branch" "priority-x")
          (op! rt "finish" blocker)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be active"
                                (op! rt "priority" blocker "p1"))))
        (testing "about documents the priority ladder"
          (is (= #{:p1 :p2 :p3 :p4} (set (keys (:priorities (op! rt "about")))))))))))

(deftest kanban-epics-group-features
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [epic-id (get-in (op! rt "add" "Big theme" "--type" "epic") [:card :id])
            feat-id (get-in (op! rt "add" "First slice" "--epic" epic-id) [:card :id])]
        (testing "epic features are linked with parent-of and shown on the board"
          (let [edges (:edges (api/subgraph rt [epic-id] {:type "parent-of"}))]
            (is (some #(and (= epic-id (:from_strand_id %))
                            (= feat-id (:to_strand_id %))) edges)))
          (let [board (op! rt "board")]
            (is (= [epic-id] (mapv :id (:epics board))))
            (is (= epic-id (:epic (first (:pending board)))))))
        (testing "epics are never served or claimed as work"
          (is (= feat-id (get-in (op! rt "next") [:next :id])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be claimed"
                                (op! rt "claim" epic-id "--owner" "a" "--branch" "b"))))
        (testing "epics cannot nest and epic targets must be epics"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot nest"
                                (op! rt "add" "Nested" "--type" "epic" "--epic" epic-id)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an epic"
                                (op! rt "add" "Bad parent" "--epic" feat-id))))))))

(deftest kanban-notes-handover-and-card-view
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [card-id (get-in (op! rt "add" "Crashable feature") [:card :id])]
        (op! rt "claim" card-id "--owner" "agent-a" "--branch" "crashable")
        (let [task (api/add rt {:title "Implement it" :attributes {:kind "task"}})
              review (api/add rt {:title "Review it" :attributes {:kind "review"}})]
          (api/update rt card-id {:edges [{:type "parent-of" :to (:id task)}
                                          {:type "parent-of" :to (:id review)}]})
          (api/update rt (:id review) {:edges [{:type "depends-on" :to (:id task)}]})
          (op! rt "note" card-id "Decided to keep lane names" "--author" "agent-a")
          (let [handover (op! rt "note" card-id
                              "Done: impl. Next: review. Validation: tests green."
                              "--author" "agent-a" "--handover")]
            (is (= "true" (get-in handover [:note :attributes :kanban/handover])))
            (is (= "closed" (get-in handover [:note :state]))))
          (testing "card view joins notes, latest handover, work, and frontier"
            (let [view (op! rt "card" card-id)]
              (is (= card-id (get-in view [:card :id])))
              (is (= 2 (count (:notes view))))
              (is (true? (get-in view [:latest-handover :handover])))
              (is (= "Done: impl. Next: review. Validation: tests green."
                     (get-in view [:latest-handover :body])))
              (is (= #{(:id task) (:id review)}
                     (set (map :id (:active-work view)))))
              ;; review depends on the task, so only the task is ready
              (is (= [(:id task)] (mapv :id (:ready view))))))
          (testing "board surfaces the latest handover on claimed cards"
            (let [claimed (first (:claimed (op! rt "board")))]
              (is (= card-id (:id claimed)))
              (is (true? (get-in claimed [:latest-handover :handover])))))
          (testing "notes reject non-card targets and missing text"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a kanban card"
                                  (op! rt "note" (:id task) "text")))
            (let [missing-text (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required argument text"
                                                     (op! rt "note" card-id)))]
              (is (= :missing-required (:reason (ex-data missing-text)))))))))))

(deftest kanban-board-groups-lanes
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [idea-id (get-in (op! rt "add" "Idea" "--status" "refinement") [:card :id])
            queued-id (get-in (op! rt "add" "Queued") [:card :id])
            working-id (get-in (op! rt "add" "Working") [:card :id])
            review-id (get-in (op! rt "add" "Reviewing") [:card :id])
            done-id (get-in (op! rt "add" "Done already") [:card :id])]
        (op! rt "claim" working-id "--owner" "agent" "--branch" "feature-x")
        (op! rt "claim" review-id "--owner" "reviewer" "--branch" "feature-y")
        (op! rt "review" review-id)
        (op! rt "claim" done-id "--owner" "agent" "--branch" "done-x")
        (op! rt "finish" done-id "--outcome" "abandoned")
        (let [board (op! rt "board")]
          (is (= [idea-id] (mapv :id (:refinement board))))
          (is (= [queued-id] (mapv :id (:pending board))))
          (is (= [working-id] (mapv :id (:claimed board))))
          (is (= [review-id] (mapv :id (:in_review board))))
          (is (= "feature-x" (:branch (first (:claimed board)))))
          (is (= 1 (get-in board [:closed :count])))
          (is (not (contains? board :unknown-status))))
        (is (= "abandoned" (get-in (api/show rt done-id) [:attributes :kanban/status])))))))

(deftest kanban-board-needs-review-frontier
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [card-id (get-in (op! rt "add" "Reviewable feature") [:card :id])]
        (op! rt "claim" card-id "--owner" "agent" "--branch" "review-branch")
        (testing "needs-review is always present and empty before any review work"
          (is (= [] (:needs-review (op! rt "board")))))
        (let [ready-review (api/add rt {:title "Review ready" :attributes {:kind "review"}})
              impl (api/add rt {:title "Implement" :attributes {:kind "task"}})
              blocked-review (api/add rt {:title "Review blocked" :attributes {:kind "review"}})]
          (api/update rt card-id {:edges [{:type "parent-of" :to (:id ready-review)}
                                          {:type "parent-of" :to (:id impl)}
                                          {:type "parent-of" :to (:id blocked-review)}]})
          ;; blocked-review depends on impl, so it stays out of the ready frontier
          (api/update rt (:id blocked-review) {:edges [{:type "depends-on" :to (:id impl)}]})
          (testing "needs-review surfaces only ready review children with the card branch"
            (let [entries (:needs-review (op! rt "board"))]
              (is (vector? entries))
              (is (= [(:id ready-review)] (mapv #(get-in % [:item :id]) entries)))
              (is (= card-id (:card (first entries))))
              (is (= "review-branch" (:branch (first entries)))))))))))

(deftest kanban-card-related-both-directions
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [a-id (get-in (op! rt "add" "Card A") [:card :id])
            b-id (get-in (op! rt "add" "Card B") [:card :id])
            edge (fn [related] (mapv (fn [e] [(:relation e) (get-in e [:strand :id])]) related))]
        ;; A depends-on B: A is the dependent, B is the dependency
        (api/update rt a-id {:edges [{:type "depends-on" :to b-id}]})
        (testing "the dependent card sees the depends-on direction"
          (is (= [["depends-on" b-id]] (edge (:related (op! rt "card" a-id))))))
        (testing "the dependency card sees the depended-on-by direction"
          (is (= [["depended-on-by" a-id]] (edge (:related (op! rt "card" b-id))))))
        (testing "incoming edges from non-card strands surface too"
          ;; regression: depends-on subgraph expansion walks outgoing edges only,
          ;; so a card-rooted scan never saw task -> card blockers
          (let [task (api/add rt {:title "Cross-feature task" :attributes {:kind "task"}})]
            (api/update rt (:id task) {:edges [{:type "depends-on" :to b-id}]})
            (is (= #{["depended-on-by" a-id] ["depended-on-by" (:id task)]}
                   (set (edge (:related (op! rt "card" b-id))))))))
        (testing "related is always present and empty for an unlinked card"
          (let [c-id (get-in (op! rt "add" "Card C") [:card :id])]
            (is (= [] (:related (op! rt "card" c-id))))))))))

(deftest kanban-board-str-renders-ascii-lanes
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [long-title (apply str "Very long title " (repeat 40 "padding "))
            _idea (op! rt "add" long-title "--status" "refinement")
            working-id (get-in (op! rt "add" "Working card") [:card :id])]
        (op! rt "claim" working-id "--owner" "agent-a" "--branch" "feature-x")
        (op! rt "note" working-id "Done: half. Next: rest." "--handover")
        (let [rendered ((requiring-resolve 'skein.spools.kanban/board-str) (op! rt "board"))
              lines (str/split-lines rendered)]
          (is (str/includes? rendered "REFINEMENT (1)"))
          (is (str/includes? rendered "PENDING (0)"))
          (is (str/includes? rendered "CLAIMED / WIP (1)"))
          (is (str/includes? rendered "IN REVIEW (0)"))
          (is (str/includes? rendered "[p3 @feature-x agent-a] Working card"))
          (is (str/includes? rendered "Done: half. Next: rest."))
          (is (str/includes? rendered "NEEDS REVIEW (0)"))
          (testing "rows are clipped to the board width"
            (is (every? #(<= (count %) 100) lines))))))))

(deftest kanban-batch-weave-creates-cards-and-dependencies
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (let [existing (api/add rt {:title "Existing blocker"})
            result (api/weave! rt :kanban-batch
                               {:items [{:key "design"
                                         :title "Design batch"
                                         :body "Design body"
                                         :priority "p2"}
                                        {:key "docs"
                                         :title "Write docs"
                                         :deps ["design" (:id existing)]}]})
            design-id (get-in result [:refs "design"])
            docs-id (get-in result [:refs "docs"])
            design (api/show rt design-id)
            docs (api/show rt docs-id)
            edge-set (set (map (juxt :from_strand_id :to_strand_id :edge_type)
                               (:edges (api/subgraph rt [docs-id] {:type "depends-on"}))))]
        (is (= "Design batch" (:title design)))
        (is (= "Design body" (get-in design [:attributes :body])))
        (is (= "p2" (get-in design [:attributes :kanban/priority])))
        (is (= "true" (get-in docs [:attributes :kanban/card])))
        (is (= "pending" (get-in docs [:attributes :kanban/status])))
        (is (= "p3" (get-in docs [:attributes :kanban/priority])))
        (is (contains? edge-set [docs-id design-id "depends-on"]))
        (is (contains? edge-set [docs-id (:id existing) "depends-on"]))))))

(deftest kanban-batch-weave-fails-loudly
  (with-runtime
    (fn [rt config-dir]
      (install-kanban! rt config-dir)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pattern input failed spec validation"
                            (api/weave! rt :kanban-batch
                                        {:items [{:key "x" :title "X" :surprise true}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pattern input failed spec validation"
                            (api/weave! rt :kanban-batch
                                        {:items [{:key "x" :title "X" :priority "urgent"}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"item keys must be unique"
                            (api/weave! rt :kanban-batch
                                        {:items [{:key "x" :title "X"}
                                                 {:key "x" :title "Again"}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target strand not found"
                            (api/weave! rt :kanban-batch
                                        {:items [{:key "x" :title "X" :deps ["missing-strand"]}]}))))))
